package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.AutoStopWords.Threshold;
import io.github.mfuerstenau.leia.core.TextAnalysis.Token;
import io.github.mfuerstenau.leia.core.TextAnalysis.TokenStage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.query.QueryAutoStopWordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Analyzers per field and auto stop words (ADR 0039): building, analyzing, and the scan. */
class AutoStopWordsTest {

    private static final String WHITESPACE = "org.apache.lucene.analysis.core.WhitespaceAnalyzer";

    @TempDir
    Path tmp;

    @Test
    void eachFieldGetsItsOwnAnalyzer() throws Exception {
        var spec = new AnalyzerSpec.PerField(
                new AnalyzerSpec.Standard(), Map.of("raw", new AnalyzerSpec.Preset(WHITESPACE)));

        try (Analyzer analyzer = Analyzers.build(spec)) {
            TextAnalysis raw = Analyzers.analyze(analyzer, "raw", "The Fox", 100);
            assertThat(raw.stages()).extracting(TokenStage::name).containsExactly("WhitespaceAnalyzer");
            assertThat(terms(raw)).containsExactly("The", "Fox");
            TextAnalysis other = Analyzers.analyze(analyzer, "body", "The Fox", 100);
            assertThat(other.stages()).extracting(TokenStage::name).containsExactly("StandardAnalyzer");
            assertThat(terms(other)).containsExactly("the", "fox");
            // As the parsers and MLT use it.
            assertThat(tokens(analyzer, "raw", "The Fox")).containsExactly("The", "Fox");
            assertThat(tokens(analyzer, "body", "The Fox")).containsExactly("the", "fox");
        }
    }

    @Test
    void aFieldAnalyzerThatFailsSaysWhichField() {
        var spec = new AnalyzerSpec.PerField(
                new AnalyzerSpec.Standard(), Map.of("raw", new AnalyzerSpec.Preset("org.example.NoAnalyzer")));

        assertThatThrownBy(() -> Analyzers.build(spec))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("field 'raw': no preset analyzer 'org.example.NoAnalyzer'");
    }

    @Test
    void closesEveryLayer() throws Exception {
        var spec = new AnalyzerSpec.AutoStopWords(
                new AnalyzerSpec.PerField(
                        new AnalyzerSpec.Standard(), Map.of("raw", new AnalyzerSpec.Preset(WHITESPACE))),
                Map.of("raw", List.of("x")));
        Analyzer analyzer = Analyzers.build(spec);
        Analyzer perField = ((AutoStopWordsAnalyzer) analyzer).delegate();
        Analyzer raw = ((PerFieldAnalyzer) perField).analyzer("raw");
        Analyzer standard = ((PerFieldAnalyzer) perField).analyzer("body");

        analyzer.close();

        for (Analyzer closed : List.of(raw, standard)) {
            assertThatThrownBy(() -> closed.tokenStream("f", "x")).isInstanceOf(AlreadyClosedException.class);
        }
    }

    @Test
    void stopWordsAreAStageOfTheirFieldOnly() throws Exception {
        var spec = new AnalyzerSpec.AutoStopWords(new AnalyzerSpec.Standard(), Map.of("body", List.of("the", "a")));

        try (Analyzer analyzer = Analyzers.build(spec)) {
            TextAnalysis body = Analyzers.analyze(analyzer, "body", "the fox and a dog", 100);
            assertThat(body.stages())
                    .extracting(TokenStage::name)
                    .containsExactly("StandardAnalyzer", Analyzers.AUTO_STOP_WORDS);
            assertThat(body.stages().get(1).tokens())
                    .extracting(Token::term, Token::position)
                    .containsExactly(tuple("fox", 1), tuple("and", 2), tuple("dog", 4));
            TextAnalysis title = Analyzers.analyze(analyzer, "title", "the fox", 100);
            assertThat(title.stages()).extracting(TokenStage::name).containsExactly("StandardAnalyzer");
            assertThat(tokens(analyzer, "title", "the fox")).containsExactly("the", "fox");
        }
    }

    @Test
    void findsTheWordsThatLucenesAnalyzerFinds() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            for (double fraction : new double[] {0, 0.3, 0.4, 0.75, 1}) {
                AutoStopWords found =
                        index.autoStopWords(List.of(), new Threshold.MaxPercentDocs(fraction), 1000, ScanProgress.NONE);
                try (var lucene =
                        new QueryAutoStopWordAnalyzer(new StandardAnalyzer(), index.reader(), (float) fraction)) {
                    assertThat(found.maxDocFreq()).isEqualTo((int) (index.numDocs() * fraction));
                    // Every indexed field, as in Lucene; rank has doc values only.
                    assertThat(found.words()).containsOnlyKeys("body", "id", "title");
                    for (String field : found.words().keySet()) {
                        assertThat(new TreeSet<>(found.words().get(field)))
                                .as(field + " at " + fraction)
                                .isEqualTo(new TreeSet<>(Set.of(lucene.getStopWords(field))));
                    }
                }
            }
            AutoStopWords body =
                    index.autoStopWords(List.of("body"), new Threshold.MaxDocFreq(5), 1000, ScanProgress.NONE);
            assertThat(body.words()).containsExactly(Map.entry("body", List.of("fox", "the")));
        }
    }

    @Test
    void checksTheFieldsAndTheThreshold() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            for (String field : List.of("nope", "rank")) {
                assertThatThrownBy(() -> index.autoStopWords(
                                List.of(field), new Threshold.MaxDocFreq(1), 1000, ScanProgress.NONE))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("No indexed field '" + field + "' in the index");
            }
            assertThatThrownBy(() ->
                            index.autoStopWords(List.of(), new Threshold.MaxPercentDocs(1.5), 1000, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () -> index.autoStopWords(List.of(), new Threshold.MaxDocFreq(-1), 1000, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void failsAboveTheMostWordsAndCancels() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            // Every id is in one document.
            assertThatThrownBy(
                            () -> index.autoStopWords(List.of("id"), new Threshold.MaxDocFreq(0), 9, ScanProgress.NONE))
                    .isInstanceOf(AutoStopWords.TooManyWordsException.class)
                    .hasMessage("field 'id' has more than 9 stop words; raise the threshold");
            assertThat(index.autoStopWords(List.of("id"), new Threshold.MaxDocFreq(0), 10, ScanProgress.NONE)
                            .words()
                            .get("id"))
                    .hasSize(10);

            List<Long> reports = new ArrayList<>();
            index.autoStopWords(List.of(), new Threshold.MaxDocFreq(0), 1000, reports::add);
            assertThat(reports).isNotEmpty();
            assertThatThrownBy(() -> index.autoStopWords(List.of(), new Threshold.MaxDocFreq(0), 1000, scanned -> {
                        throw new CancellationException();
                    }))
                    .isInstanceOf(CancellationException.class);
        }
    }

    /** Ten documents: {@code fox} in seven, {@code the} in six, and an id each. */
    private Path writeIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new WhitespaceAnalyzer()))) {
            for (int i = 0; i < 10; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", "doc" + i, Field.Store.NO));
                StringBuilder body = new StringBuilder("word" + i);
                if (i < 7) {
                    body.append(" fox");
                }
                if (i < 6) {
                    body.append(" the");
                }
                if (i < 3) {
                    body.append(" dog");
                }
                doc.add(new TextField("body", body.toString(), Field.Store.NO));
                doc.add(new TextField("title", i < 4 ? "a title" : "title", Field.Store.NO));
                doc.add(new NumericDocValuesField("rank", i));
                writer.addDocument(doc);
            }
        }
        return dir;
    }

    private static List<String> terms(TextAnalysis analysis) {
        return analysis.stages().getLast().tokens().stream().map(Token::term).toList();
    }

    /** The terms of {@code analyzer}'s stream for the field, as a query parser reads them. */
    private static List<String> tokens(Analyzer analyzer, String field, String text) throws Exception {
        List<String> terms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        }
        return terms;
    }
}
