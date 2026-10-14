package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.DocumentHighlight.ValueHighlight;
import io.github.mfuerstenau.leia.core.Queries.Operator;
import io.github.mfuerstenau.leia.core.SearchResults.FieldHighlight;
import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import io.github.mfuerstenau.leia.core.SearchResults.Passage;
import io.github.mfuerstenau.leia.core.StoredScan.Span;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HighlightsTest {

    private static final Similarity BM25 = Similarities.bm25(1.2f, 0.75f);

    /** Lucene's analyzers are thread-safe; this one lives as long as the tests. */
    private static final Analyzer ANALYZER = new StandardAnalyzer();

    private static final HighlightSpec SPEC = new HighlightSpec(3, true, ANALYZER);

    /** Text indexed with offsets in its postings. */
    private static final FieldType POSTINGS = type(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS, false, false);

    /** Text with term vectors with offsets, and postings without. */
    private static final FieldType VECTORS = type(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, true, true);

    /** Text with term vectors without offsets. */
    private static final FieldType VECTORS_WITHOUT_OFFSETS =
            type(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, true, false);

    private static final String LONG = "word ".repeat(200) + "the lynx " + "word ".repeat(200);

    @TempDir
    Path tmp;

    @Test
    void marksTheMatchesInPassagesFromThePostings() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Hit hit = only(search(index, "post:fox", SPEC, Optional.empty()));

            assertThat(hit.highlights()).hasSize(1);
            FieldHighlight post = hit.highlights().getFirst();
            assertThat(post.field()).isEqualTo("post");
            assertThat(post.offsetSource()).isEqualTo(OffsetSource.POSTINGS);
            assertThat(post.passages()).hasSize(2);
            Passage first = post.passages().getFirst();
            assertThat(first.valueIndex()).isZero();
            assertThat(first.text()).isEqualTo("The quick brown fox jumps.");
            assertThat(first.start()).isZero();
            assertThat(marked(first)).containsExactly("fox");
            assertThat(first.score()).isPositive();
            Passage second = post.passages().get(1);
            assertThat(second.text()).isEqualTo("A second fox sleeps.");
            assertThat(second.start()).isEqualTo("The quick brown fox jumps. ".length());
        }
    }

    @Test
    void aWildcardIsHighlightedByAnalysis() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldHighlight post = only(search(index, "post:fo*", SPEC, Optional.empty()))
                    .highlights()
                    .getFirst();

            assertThat(post.offsetSource()).isEqualTo(OffsetSource.ANALYSIS);
            assertThat(post.passages()).flatExtracting(HighlightsTest::marked).containsExactly("fox", "fox");
        }
    }

    @Test
    void aPhraseMarksOnlyItsOwnTerms() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldHighlight post = only(search(index, "post:\"quick brown\"", SPEC, Optional.empty()))
                    .highlights()
                    .getFirst();

            assertThat(post.passages()).hasSize(1);
            assertThat(String.join(" ", marked(post.passages().getFirst()))).isEqualTo("quick brown");
        }
    }

    @Test
    void termVectorsGiveOffsetsAndAreAnalyzedWithoutThem() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldHighlight vectors = only(search(index, "vectors:cat", SPEC, Optional.empty()))
                    .highlights()
                    .getFirst();
            assertThat(vectors.offsetSource()).isEqualTo(OffsetSource.TERM_VECTORS);
            assertThat(marked(vectors.passages().getFirst())).containsExactly("cat");

            FieldHighlight without = only(search(index, "bare:cow", SPEC, Optional.empty()))
                    .highlights()
                    .getFirst();
            assertThat(without.offsetSource()).isEqualTo(OffsetSource.ANALYSIS);
            assertThat(marked(without.passages().getFirst())).containsExactly("cow");

            FieldHighlight plain = only(search(index, "plain:owl", SPEC, Optional.empty()))
                    .highlights()
                    .getFirst();
            assertThat(plain.offsetSource()).isEqualTo(OffsetSource.ANALYSIS);
        }
    }

    @Test
    void otherFieldsAreHighlightedOnlyWithoutRequiringAFieldMatch() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Hit required = only(search(index, "post:fox", SPEC, Optional.empty()));
            assertThat(required.highlights()).extracting(FieldHighlight::field).containsExactly("post");

            Hit any = only(search(index, "post:fox", new HighlightSpec(3, false, ANALYZER), Optional.empty()));
            assertThat(any.highlights()).extracting(FieldHighlight::field).containsExactly("post", "plain");
            assertThat(marked(any.highlights().get(1).passages().getFirst())).containsExactly("fox");
        }
    }

    @Test
    void anyFieldsTermsAreHighlightedWhereTheWholeQueryDoesNotMatch() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Hit hit = only(
                    search(index, "+plain:owl +post:quick*", new HighlightSpec(3, false, ANALYZER), Optional.empty()));

            // Neither field alone matches both clauses.
            assertThat(hit.highlights()).extracting(FieldHighlight::field).containsExactly("post", "plain");
            assertThat(marked(hit.highlights().get(0).passages().getFirst())).containsExactly("quick");
            assertThat(marked(hit.highlights().get(1).passages().getFirst())).containsExactly("owl");
        }
    }

    @Test
    void passagesNameTheirValueAmongAllStoredValues() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldHighlight tags = only(search(index, "tags:beta", SPEC, Optional.empty()))
                    .highlights()
                    .getFirst();

            // Value 1 is a stored int, which the highlighter doesn't read.
            assertThat(tags.passages()).hasSize(1);
            assertThat(tags.passages().getFirst().valueIndex()).isEqualTo(2);
            assertThat(tags.passages().getFirst().text()).isEqualTo("beta goose");
            assertThat(tags.passages().getFirst().spans()).containsExactly(new Span(0, 4));
        }
    }

    @Test
    void offsetsAreUtf8Bytes() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Passage passage = only(search(index, "post:grüße", SPEC, Optional.empty()))
                    .highlights()
                    .getFirst()
                    .passages()
                    .getFirst();

            String value = "Ein 🦊 sagt: grüße.";
            int start = value.indexOf("grüße");
            int bytes = value.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
            assertThat(passage.spans())
                    .containsExactly(new Span(bytes, bytes + "grüße".getBytes(StandardCharsets.UTF_8).length));
            assertThat(marked(passage)).containsExactly("grüße");
        }
    }

    @Test
    void aLongPassageIsCutAroundItsFirstMatch() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Passage passage = only(search(index, "post:lynx", SPEC, Optional.of(Set.of("post"))))
                    .highlights()
                    .getFirst()
                    .passages()
                    .getFirst();

            // The value is one sentence of 2009 characters.
            assertThat(passage.text().length()).isLessThanOrEqualTo(Highlights.MAX_PASSAGE_CHARS);
            int lynx = LONG.indexOf("lynx");
            assertThat(passage.start()).isBetween(lynx - Highlights.PASSAGE_BEFORE, lynx);
            assertThat(marked(passage)).containsExactly("lynx");
            assertThat(LONG.substring(
                            passage.start(), passage.start() + passage.text().length()))
                    .isEqualTo(passage.text());
        }
    }

    @Test
    void onlyTheFieldsAskedForAreHighlighted() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Hit hit = only(
                    search(index, "post:fox", new HighlightSpec(3, false, ANALYZER), Optional.of(Set.of("plain"))));

            assertThat(hit.highlights()).extracting(FieldHighlight::field).containsExactly("plain");
        }
    }

    @Test
    void withoutASpecHitsHaveNoHighlights() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            SearchResults results = index.search(
                    classic("post:fox"), BM25, Optional.empty(), 0, 10, false, Optional.empty(), ScanProgress.NONE);

            assertThat(results.hits())
                    .allSatisfy(hit -> assertThat(hit.highlights()).isEmpty());
        }
    }

    @Test
    void marksEveryMatchInAField() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            DocumentHighlight highlight =
                    index.highlight(classic("tags:goose"), SPEC, 1, "tags", 100, ScanProgress.NONE);

            // TextField has no offsets in its postings.
            assertThat(highlight.offsetSource()).isEqualTo(OffsetSource.ANALYSIS);
            assertThat(highlight.truncated()).isFalse();
            assertThat(highlight.values())
                    .containsExactly(
                            new ValueHighlight(0, 2, List.of(new Span(6, 11), new Span(12, 17))),
                            new ValueHighlight(2, 1, List.of(new Span(5, 10))));
        }
    }

    @Test
    void keepsTheFirstSpansAndCountsTheRest() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            DocumentHighlight highlight = index.highlight(classic("post:word"), SPEC, 2, "post", 3, ScanProgress.NONE);

            assertThat(highlight.values()).hasSize(1);
            assertThat(highlight.values().getFirst().matchCount()).isEqualTo(400);
            assertThat(highlight.values().getFirst().spans())
                    .containsExactly(new Span(0, 4), new Span(5, 9), new Span(10, 14));
        }
    }

    @Test
    void aFieldWithoutMatchesHasNoValues() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            DocumentHighlight highlight = index.highlight(classic("post:fox"), SPEC, 1, "tags", 100, ScanProgress.NONE);

            assertThat(highlight.values()).isEmpty();
            assertThat(highlight.offsetSource()).isEqualTo(OffsetSource.NONE);
            assertThatThrownBy(() -> index.highlight(classic("post:fox"), SPEC, 99, "post", 100, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.highlight(classic("post:fox"), SPEC, 0, "nothing", 100, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void cancellingStopsTheHighlight() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            ScanProgress cancelled = _ -> {
                throw new CancellationException("cancelled");
            };

            assertThatThrownBy(() -> index.highlight(classic("post:fox"), SPEC, 0, "post", 100, cancelled))
                    .isInstanceOf(CancellationException.class);
        }
    }

    /** The text of each span of {@code passage}. */
    private static List<String> marked(Passage passage) {
        byte[] text = passage.text().getBytes(StandardCharsets.UTF_8);
        return passage.spans().stream()
                .map(span -> new String(
                        Arrays.copyOfRange(text, span.start() - passage.start(), span.end() - passage.start()),
                        StandardCharsets.UTF_8))
                .toList();
    }

    private static Hit only(SearchResults results) {
        assertThat(results.hits()).hasSize(1);
        return results.hits().getFirst();
    }

    private static Query classic(String expression) throws InvalidQueryException {
        return Queries.classic(
                expression, "post", ParserOptions.defaults(Operator.OR), ClassicOptions.DEFAULTS, ANALYZER);
    }

    private static SearchResults search(
            ReadOnlyIndex index, String expression, HighlightSpec spec, Optional<Set<String>> fields)
            throws IOException, InvalidQueryException {
        return index.search(
                classic(expression),
                BM25,
                Optional.empty(),
                0,
                10,
                false,
                fields,
                Optional.of(spec),
                ScanProgress.NONE);
    }

    /**
     * Four documents: 0 with two fox sentences, a cat with term vectors, a cow with term vectors
     * without offsets, an owl and a fox in plain text; 1 with tags "alpha goose goose", 7 and "beta
     * goose"; 2 with a long value; 3 with non-ASCII text.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(ANALYZER))) {
            Document zero = new Document();
            zero.add(new StringField("id", "0", Field.Store.YES));
            zero.add(new Field("post", "The quick brown fox jumps. A second fox sleeps.", POSTINGS));
            zero.add(new Field("vectors", "A cat purrs.", VECTORS));
            zero.add(new Field("bare", "A cow moos.", VECTORS_WITHOUT_OFFSETS));
            zero.add(new TextField("plain", "An owl and a fox.", Field.Store.YES));
            writer.addDocument(zero);
            Document one = new Document();
            one.add(new StringField("id", "1", Field.Store.YES));
            one.add(new TextField("tags", "alpha goose goose", Field.Store.YES));
            one.add(new StoredField("tags", 7));
            one.add(new TextField("tags", "beta goose", Field.Store.YES));
            writer.addDocument(one);
            Document two = new Document();
            two.add(new StringField("id", "2", Field.Store.YES));
            two.add(new Field("post", LONG, POSTINGS));
            writer.addDocument(two);
            Document three = new Document();
            three.add(new StringField("id", "3", Field.Store.YES));
            three.add(new Field("post", "Ein 🦊 sagt: grüße.", POSTINGS));
            writer.addDocument(three);
        }
        return dir;
    }

    private static FieldType type(IndexOptions options, boolean vectors, boolean vectorOffsets) {
        FieldType type = new FieldType();
        type.setStored(true);
        type.setTokenized(true);
        type.setIndexOptions(options);
        type.setStoreTermVectors(vectors);
        type.setStoreTermVectorPositions(vectors);
        type.setStoreTermVectorOffsets(vectorOffsets);
        type.freeze();
        return type;
    }
}
