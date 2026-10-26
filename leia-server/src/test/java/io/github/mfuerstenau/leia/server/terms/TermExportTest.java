package io.github.mfuerstenau.leia.server.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.job.ScanProgress;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import io.github.mfuerstenau.leia.server.terms.TermExport.Delimiter;
import io.github.mfuerstenau.leia.server.terms.TermExport.Format;
import io.github.mfuerstenau.leia.server.terms.TermExport.OutputException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exporting the terms of a field to a file (ADR 0022). */
class TermExportTest {

    @TempDir
    Path tmp;

    @Test
    void writesEachTermWithItsDocumentFrequency() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("terms.out");

        try (OpenRuntime index = TestIndexes.open(dir)) {
            TermExport.Result result = export(index, "tag", file, Delimiter.TAB, Format.LUKE, false, ScanProgress.NONE);

            assertThat(result.path()).isEqualTo(file);
            assertThat(result.terms()).isEqualTo(3);
            assertThat(result.bytes()).isEqualTo(Files.size(file));
        }
        assertThat(Files.readString(file)).isEqualTo("a,b\t1\nblue\t2\ngrün\t1\n");
    }

    @Test
    void writesTheChosenDelimiter() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path comma = tmp.resolve("comma.out");
        Path space = tmp.resolve("space.out");

        try (OpenRuntime index = TestIndexes.open(dir)) {
            export(index, "tag", comma, Delimiter.COMMA, Format.LUKE, false, ScanProgress.NONE);
            export(index, "tag", space, Delimiter.SPACE, Format.LUKE, false, ScanProgress.NONE);
        }
        assertThat(Files.readString(comma)).isEqualTo("a,b,1\nblue,2\ngrün,1\n");
        assertThat(Files.readString(space)).isEqualTo("a,b 1\nblue 2\ngrün 1\n");
    }

    @Test
    void writesBinaryTermsAsHex() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("raw.out");

        try (OpenRuntime index = TestIndexes.open(dir)) {
            export(index, "raw", file, Delimiter.TAB, Format.LUKE, false, ScanProgress.NONE);
        }
        assertThat(Files.readString(file, StandardCharsets.US_ASCII)).isEqualTo("00ff\t1\n");
    }

    @Test
    void theFullFormatHasAMarkerAndBothCounts() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("full.out");

        try (OpenRuntime index = TestIndexes.open(dir)) {
            export(index, "tag", file, Delimiter.TAB, Format.FULL, false, ScanProgress.NONE);
        }
        assertThat(Files.readString(file)).isEqualTo("t\t1\t1\ta,b\nt\t2\t2\tblue\nt\t1\t1\tgrün\n");
    }

    @Test
    void theFullFormatMarksHexTermsAndSplitsAtTheFirstThreeDelimiters() throws Exception {
        Path file = tmp.resolve("full.out");
        TermExport.TermSource terms = sink -> {
            byte[] comma = "a,b".getBytes(StandardCharsets.UTF_8);
            sink.term(comma, 0, comma.length, 2, 7);
            sink.term(new byte[] {0, (byte) 0xff}, 0, 2, 1, 1);
        };

        TermExport.export(List.of(), terms, file, Delimiter.COMMA, Format.FULL, false, ScanProgress.NONE);

        assertThat(Files.readString(file)).isEqualTo("t,2,7,a,b\nx,1,1,00ff\n");
    }

    @Test
    void aTermWithAControlCharacterIsHexInBothFormats() throws Exception {
        byte[] newline = "a\nb".getBytes(StandardCharsets.UTF_8);
        byte[] tab = "a\tb".getBytes(StandardCharsets.UTF_8);
        byte[] nul = {'a', 0};
        byte[] del = {'a', 0x7f};
        TermExport.TermSource terms = sink -> {
            for (byte[] term : List.of(newline, tab, nul, del)) {
                sink.term(term, 0, term.length, 1, 2);
            }
        };
        Path luke = tmp.resolve("luke.out");
        Path full = tmp.resolve("full.out");

        TermExport.export(List.of(), terms, luke, Delimiter.TAB, Format.LUKE, false, ScanProgress.NONE);
        TermExport.export(List.of(), terms, full, Delimiter.TAB, Format.FULL, false, ScanProgress.NONE);

        assertThat(Files.readString(luke)).isEqualTo("610a62\t1\n610962\t1\n6100\t1\n617f\t1\n");
        assertThat(Files.readString(full))
                .isEqualTo("x\t1\t2\t610a62\nx\t1\t2\t610962\nx\t1\t2\t6100\nx\t1\t2\t617f\n");
    }

    @Test
    void aFieldWithoutPostingsGivesAnEmptyFile() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("rank.out");

        try (OpenRuntime index = TestIndexes.open(dir)) {
            TermExport.Result result =
                    export(index, "rank", file, Delimiter.TAB, Format.LUKE, false, ScanProgress.NONE);

            assertThat(result.terms()).isZero();
        }
        assertThat(file).isEmptyFile();
    }

    @Test
    void reportsProgressAndDeletesTheFileWhenCancelled() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("terms.out");

        try (OpenRuntime index = TestIndexes.open(dir)) {
            assertThatThrownBy(() -> export(index, "tag", file, Delimiter.TAB, Format.LUKE, false, _ -> {
                        throw new CancellationException("cancelled");
                    }))
                    .isInstanceOf(CancellationException.class);
        }
        assertThat(file).doesNotExist();
    }

    @Test
    void refusesToWriteIntoOrBelowAnIndexDirectory() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path below = Files.createDirectories(dir.resolve("sub"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), dir);

        try (OpenRuntime index = TestIndexes.open(dir)) {
            for (Path file : new Path[] {dir.resolve("terms.out"), below.resolve("terms.out"), link.resolve("t.out")}) {
                assertThatThrownBy(
                                () -> export(index, "tag", file, Delimiter.TAB, Format.LUKE, true, ScanProgress.NONE))
                        .isInstanceOf(OutputException.class)
                        .hasMessageContaining("Not writing into the index directory " + dir);
            }
        }
        assertThat(dir.resolve("terms.out")).doesNotExist();
    }

    @Test
    void refusesToWriteIntoASkippedIndexDirectory() throws Exception {
        writeIndex(tmp.resolve("indexes/a"));
        Path b = Files.createDirectories(tmp.resolve("indexes/b"));
        Files.writeString(b.resolve("segments_1"), "broken");

        try (OpenRuntime index = TestIndexes.open(tmp.resolve("indexes"))) {
            assertThat(index.skipped()).extracting(RuntimeIndex.Skipped::path).containsExactly(b);
            assertThatThrownBy(() -> TermExport.checkTarget(directories(index), b.resolve("terms.out"), false))
                    .isInstanceOf(OutputException.class)
                    .hasMessageContaining("Not writing into the index directory " + b);
            TermExport.checkTarget(directories(index), tmp.resolve("indexes/terms.out"), false);
        }
    }

    @Test
    void overwritesAnExistingFileOnlyIfAsked() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = Files.writeString(tmp.resolve("terms.out"), "old");

        try (OpenRuntime index = TestIndexes.open(dir)) {
            assertThatThrownBy(() -> export(index, "tag", file, Delimiter.TAB, Format.LUKE, false, ScanProgress.NONE))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("File exists: " + file);
            assertThat(file).hasContent("old");

            export(index, "tag", file, Delimiter.TAB, Format.LUKE, true, ScanProgress.NONE);
        }
        assertThat(Files.readString(file)).startsWith("a,b\t1\n");
    }

    @Test
    void refusesPathsItCantWrite() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path target = Files.writeString(tmp.resolve("target"), "keep");
        Path link = Files.createSymbolicLink(tmp.resolve("link.out"), target);

        try (OpenRuntime index = TestIndexes.open(dir)) {
            assertThatThrownBy(
                            () -> TermExport.checkTarget(directories(index), tmp.resolve("missing/terms.out"), false))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("No such directory: " + tmp.resolve("missing"));
            assertThatThrownBy(() -> TermExport.checkTarget(directories(index), tmp, true))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("Is a directory: " + tmp);
            assertThatThrownBy(() -> TermExport.checkTarget(directories(index), link, true))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("Not writing through a symbolic link: " + link);
            assertThatThrownBy(() -> TermExport.checkTarget(directories(index), Path.of("terms.out"), false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> export(
                            index, "nope", tmp.resolve("x.out"), Delimiter.TAB, Format.LUKE, false, ScanProgress.NONE))
                    .isInstanceOf(RpcException.class);
        }
        assertThat(target).hasContent("keep");
        assertThat(tmp.resolve("x.out")).doesNotExist();
    }

    @Test
    void writesOnlyTheSliceOfEachTerm() throws Exception {
        Path file = tmp.resolve("slices.out");
        byte[] text = "xxblueyy".getBytes(StandardCharsets.UTF_8);
        byte[] binary = HexFormat.of().parseHex("aa00ffbb");
        TermExport.TermSource terms = sink -> {
            sink.term(text, 2, 4, 3, 5);
            sink.term(binary, 1, 2, 1, 1);
        };

        TermExport.Result result =
                TermExport.export(List.of(), terms, file, Delimiter.TAB, Format.LUKE, false, ScanProgress.NONE);

        assertThat(result.terms()).isEqualTo(2);
        assertThat(Files.readString(file, StandardCharsets.US_ASCII)).isEqualTo("blue\t3\n00ff\t1\n");
    }

    private static TermExport.Result export(
            OpenRuntime index,
            String field,
            Path file,
            Delimiter delimiter,
            Format format,
            boolean overwrite,
            ScanProgress progress)
            throws Exception {
        return TermExport.export(
                directories(index),
                RuntimeResults.exportTerms(index, field),
                file,
                delimiter,
                format,
                overwrite,
                progress);
    }

    /** The opened and the skipped index directories, as the server passes them. */
    private static List<Path> directories(OpenRuntime index) {
        List<Path> directories = new ArrayList<>(index.index().directories());
        index.skipped().stream().map(RuntimeIndex.Skipped::path).forEach(directories::add);
        return directories;
    }

    /** Three documents: tags "blue", "a,b" and "grün", a binary term, and a field without postings. */
    private static Path writeIndex(Path dir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            String[][] tags = {{"blue"}, {"blue", "a,b"}, {"grün"}};
            for (int i = 0; i < tags.length; i++) {
                Document doc = new Document();
                for (String tag : tags[i]) {
                    doc.add(new StringField("tag", tag, Field.Store.NO));
                }
                doc.add(new NumericDocValuesField("rank", i));
                if (i == 0) {
                    doc.add(new StringField("raw", new BytesRef(new byte[] {0, (byte) 0xff}), Field.Store.NO));
                }
                writer.addDocument(doc);
            }
            writer.commit();
        }
        return dir;
    }
}
