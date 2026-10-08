package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.TermExport.Delimiter;
import io.github.mfuerstenau.leia.core.TermExport.OutputException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            TermExport.Result result = TermExport.export(index, "tag", file, Delimiter.TAB, false, ScanProgress.NONE);

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

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            TermExport.export(index, "tag", comma, Delimiter.COMMA, false, ScanProgress.NONE);
            TermExport.export(index, "tag", space, Delimiter.SPACE, false, ScanProgress.NONE);
        }
        assertThat(Files.readString(comma)).isEqualTo("a,b,1\nblue,2\ngrün,1\n");
        assertThat(Files.readString(space)).isEqualTo("a,b 1\nblue 2\ngrün 1\n");
    }

    @Test
    void writesBinaryTermsAsHex() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("raw.out");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            TermExport.export(index, "raw", file, Delimiter.TAB, false, ScanProgress.NONE);
        }
        assertThat(Files.readString(file, StandardCharsets.US_ASCII)).isEqualTo("00ff\t1\n");
    }

    @Test
    void aFieldWithoutPostingsGivesAnEmptyFile() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("rank.out");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            TermExport.Result result = TermExport.export(index, "rank", file, Delimiter.TAB, false, ScanProgress.NONE);

            assertThat(result.terms()).isZero();
        }
        assertThat(file).isEmptyFile();
    }

    @Test
    void reportsProgressAndDeletesTheFileWhenCancelled() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = tmp.resolve("terms.out");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThatThrownBy(() -> TermExport.export(index, "tag", file, Delimiter.TAB, false, _ -> {
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

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            for (Path file : new Path[] {dir.resolve("terms.out"), below.resolve("terms.out"), link.resolve("t.out")}) {
                assertThatThrownBy(() -> TermExport.export(index, "tag", file, Delimiter.TAB, true, ScanProgress.NONE))
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

        try (ReadOnlyIndex index = ReadOnlyIndex.open(tmp.resolve("indexes"))) {
            assertThat(index.skippedDirectories()).containsExactly(b);
            assertThatThrownBy(() -> TermExport.checkTarget(index, b.resolve("terms.out"), false))
                    .isInstanceOf(OutputException.class)
                    .hasMessageContaining("Not writing into the index directory " + b);
            TermExport.checkTarget(index, tmp.resolve("indexes/terms.out"), false);
        }
    }

    @Test
    void overwritesAnExistingFileOnlyIfAsked() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path file = Files.writeString(tmp.resolve("terms.out"), "old");

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThatThrownBy(() -> TermExport.export(index, "tag", file, Delimiter.TAB, false, ScanProgress.NONE))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("File exists: " + file);
            assertThat(file).hasContent("old");

            TermExport.export(index, "tag", file, Delimiter.TAB, true, ScanProgress.NONE);
        }
        assertThat(Files.readString(file)).startsWith("a,b\t1\n");
    }

    @Test
    void refusesPathsItCantWrite() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        Path target = Files.writeString(tmp.resolve("target"), "keep");
        Path link = Files.createSymbolicLink(tmp.resolve("link.out"), target);

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThatThrownBy(() -> TermExport.checkTarget(index, tmp.resolve("missing/terms.out"), false))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("No such directory: " + tmp.resolve("missing"));
            assertThatThrownBy(() -> TermExport.checkTarget(index, tmp, true))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("Is a directory: " + tmp);
            assertThatThrownBy(() -> TermExport.checkTarget(index, link, true))
                    .isInstanceOf(OutputException.class)
                    .hasMessage("Not writing through a symbolic link: " + link);
            assertThatThrownBy(() -> TermExport.checkTarget(index, Path.of("terms.out"), false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TermExport.export(
                            index, "nope", tmp.resolve("x.out"), Delimiter.TAB, false, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(target).hasContent("keep");
        assertThat(tmp.resolve("x.out")).doesNotExist();
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
