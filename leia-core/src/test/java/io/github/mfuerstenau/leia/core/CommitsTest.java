package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.CommitDetails.CommitFile;
import io.github.mfuerstenau.leia.core.CommitDetails.FieldFormats;
import io.github.mfuerstenau.leia.core.CommitDetails.Segment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitsTest {

    @TempDir
    Path tmp;

    @Test
    void listsTheCommitsNewestFirst() throws Exception {
        Path dir = writeIndex();
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            CommitList list = index.commits(0);

            assertThat(list.unreadable()).isEmpty();
            assertThat(list.commits())
                    .extracting(CommitInfo::segmentsFile, CommitInfo::current, CommitInfo::segmentCount)
                    .containsExactly(tuple("segments_2", true, 2), tuple("segments_1", false, 1));
            CommitInfo latest = list.commits().getFirst();
            assertThat(latest.generation()).isEqualTo(2);
            assertThat(latest.maxDoc()).isEqualTo(3);
            assertThat(latest.delCount()).isEqualTo(1);
            assertThat(latest.luceneVersion()).isEqualTo(Version.LATEST);
            assertThat(latest.userData()).isEqualTo(Map.of("step", "two"));
            assertThat(list.commits().getLast().userData()).isEmpty();
        }
    }

    @Test
    void readsTheSegmentsOfACommit() throws Exception {
        Path dir = writeIndex();
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            CommitDetails details = index.commit(0, 2).orElseThrow();

            assertThat(details.commit().current()).isTrue();
            assertThat(details.segments()).extracting(Segment::name).containsExactly("_0", "_1");
            Segment first = details.segments().get(0);
            assertThat(first.maxDoc()).isEqualTo(2);
            assertThat(first.docBase()).isZero();
            assertThat(first.delCount()).isEqualTo(1);
            assertThat(first.delGen()).hasValue(1);
            assertThat(first.fieldInfosGen()).isEmpty();
            assertThat(first.compound()).isTrue();
            assertThat(first.version()).isEqualTo(Version.LATEST);
            assertThat(first.codec()).isEqualTo(Codec.getDefault().getName());
            assertThat(first.formats().postings()).isEqualTo("PerField40");
            assertThat(first.formats().storedFields()).endsWith("StoredFieldsFormat");
            assertThat(first.diagnostics()).containsEntry("source", "flush");
            assertThat(first.indexSort()).isEmpty();
            assertThat(first.id()).isNotBlank();

            Segment second = details.segments().get(1);
            assertThat(second.docBase()).isEqualTo(2);
            assertThat(second.delGen()).isEmpty();
            assertThat(second.compound()).isFalse();
        }
    }

    @Test
    void listsTheFilesOfACommitWithTheirSegmentAndSize() throws Exception {
        Path dir = writeIndex();
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            CommitDetails details = index.commit(0, 2).orElseThrow();

            assertThat(details.files()).extracting(CommitFile::name).isSorted().contains("segments_2", "_0.cfs");
            assertThat(file(details, "segments_2").segment()).isEmpty();
            assertThat(file(details, "_0.cfs").segment()).hasValue("_0");
            assertThat(file(details, "_0_1.liv").segment()).hasValue("_0");
            assertThat(file(details, "_0.cfs").size()).hasValue(Files.size(dir.resolve("_0.cfs")));
            long segmentSize = details.files().stream()
                    .filter(file -> file.segment().equals(Optional.of("_1")))
                    .mapToLong(file -> file.size().getAsLong())
                    .sum();
            assertThat(details.segments().get(1).sizeInBytes()).hasValue(segmentSize);
        }
    }

    @Test
    void readsAnOlderCommit() throws Exception {
        Path dir = writeIndex();
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            CommitDetails details = index.commit(0, 1).orElseThrow();

            assertThat(details.commit().current()).isFalse();
            assertThat(details.segments()).singleElement().satisfies(segment -> {
                assertThat(segment.name()).isEqualTo("_0");
                assertThat(segment.delCount()).isZero();
            });
            assertThat(details.files()).extracting(CommitFile::name).doesNotContain("_0_1.liv", "segments_2");
        }
    }

    @Test
    void seesCommitsWrittenAfterOpening() throws Exception {
        Path dir = writeIndex();
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            try (FSDirectory directory = FSDirectory.open(dir);
                    IndexWriter writer = new IndexWriter(directory, config())) {
                writer.addDocument(doc("4"));
                writer.commit();
            }

            assertThat(index.commits(0).commits())
                    .extracting(CommitInfo::generation, CommitInfo::current)
                    .containsExactly(tuple(3L, false), tuple(2L, true), tuple(1L, false));
        }
    }

    @Test
    void aMissingFileHasNoSize() throws Exception {
        Path dir = writeIndex();
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Files.delete(dir.resolve("_0_1.liv"));

            CommitDetails details = index.commit(0, 2).orElseThrow();

            assertThat(file(details, "_0_1.liv").size()).isEmpty();
            assertThat(details.segments().get(0).sizeInBytes()).isEmpty();
            assertThat(details.segments().get(1).sizeInBytes()).isPresent();
        }
    }

    @Test
    void skipsAnUnreadableCommit() throws Exception {
        Path dir = writeIndex();
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Files.writeString(dir.resolve("segments_9"), "not a commit");

            CommitList list = index.commits(0);

            assertThat(list.commits()).extracting(CommitInfo::generation).containsExactly(2L, 1L);
            assertThat(list.unreadable()).singleElement().satisfies(unreadable -> {
                assertThat(unreadable.segmentsFile()).isEqualTo("segments_9");
                assertThat(unreadable.error()).isInstanceOf(IOException.class);
            });
            assertThatThrownBy(() -> index.commit(0, 9)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void aCommitWithACodecThatIsNotLoadedIsUnreadable() throws Exception {
        try (Directory directory = FSDirectory.open(testIndex("custom-codec"), NoLockFactory.INSTANCE)) {
            CommitList list = CommitReader.commits(directory, 1);

            assertThat(list.commits()).isEmpty();
            assertThat(list.unreadable()).singleElement().satisfies(unreadable -> {
                assertThat(unreadable.segmentsFile()).isEqualTo("segments_1");
                assertThat(unreadable.error())
                        .isInstanceOf(MissingCodecException.class)
                        .hasMessage("the index needs the codec 'LeiaTest', which isn't loaded");
            });
            assertThatThrownBy(() -> CommitReader.commit(directory, 1, 1)).isInstanceOf(MissingCodecException.class);
        }
    }

    @Test
    void anUnknownGenerationIsNotFound() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.commit(0, 7)).isEmpty();
            assertThat(index.commit(0, 0)).isEmpty();
            assertThat(index.commit(0, -1)).isEmpty();
        }
    }

    @Test
    void readsTheCommitOfALucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            CommitInfo current = index.commits(0).commits().getFirst();
            assertThat(current.current()).isTrue();
            assertThat(current.luceneVersion().major).isEqualTo(9);

            CommitDetails details = index.commit(0, current.generation()).orElseThrow();
            assertThat(details.segments()).isNotEmpty().allSatisfy(segment -> {
                assertThat(segment.version().major).isEqualTo(9);
                assertThat(segment.codec()).startsWith("Lucene9");
            });
        }
    }

    @Test
    void readsTheFormatsOfEachFieldInCompoundAndPlainSegments() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeFormatsIndex())) {
            CommitDetails details = index.commit(0, 2).orElseThrow();

            assertThat(details.segments()).extracting(Segment::compound).containsExactly(true, false);
            assertThat(details.segments()).allSatisfy(segment -> {
                assertThat(segment.fieldInfosError()).isEmpty();
                assertThat(segment.fieldFormats()).isEqualTo(DEFAULT_FIELD_FORMATS);
            });
        }
    }

    @Test
    void readsTheFormatsOfEachFieldFromAFieldInfosUpdate() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeFormatsIndex())) {
            Segment updated = index.commit(0, 3).orElseThrow().segments().getFirst();

            assertThat(updated.fieldInfosGen()).hasValue(1);
            assertThat(updated.compound()).isTrue();
            assertThat(updated.fieldFormats()).isEqualTo(DEFAULT_FIELD_FORMATS);
        }
    }

    @Test
    void fieldInfosThatCantBeReadDontFailTheCommit() throws Exception {
        Path dir = writeFormatsIndex();
        Files.delete(dir.resolve("_1.fnm"));
        try (Directory directory = FSDirectory.open(dir, NoLockFactory.INSTANCE)) {
            CommitDetails details = CommitReader.commit(directory, 2, 2).orElseThrow();

            assertThat(details.segments().getFirst().fieldFormats()).isEqualTo(DEFAULT_FIELD_FORMATS);
            Segment broken = details.segments().get(1);
            assertThat(broken.fieldFormats()).isEmpty();
            assertThat(broken.fieldInfosError()).containsInstanceOf(NoSuchFileException.class);
            assertThat(broken.fieldInfosMessage())
                    .hasValueSatisfying(message -> assertThat(message)
                            .startsWith("Corrupt index, missing file: ")
                            .contains("_1.fnm"));
        }
    }

    @Test
    void readsTheFormatsOfFieldsWhoseFormatsAreNotLoaded() throws Exception {
        try (Directory directory = FSDirectory.open(testIndex("custom-formats"), NoLockFactory.INSTANCE)) {
            CommitDetails details = CommitReader.commit(directory, 1, 1).orElseThrow();

            assertThat(details.segments()).isNotEmpty().allSatisfy(segment -> {
                assertThat(segment.fieldInfosError()).isEmpty();
                assertThat(segment.fieldFormats()).isNotEmpty().allSatisfy(field -> {
                    field.postings().ifPresent(name -> assertThat(name).isEqualTo("LeiaTestPostings"));
                    field.docValues().ifPresent(name -> assertThat(name).isEqualTo("LeiaTestDocValues"));
                    field.knnVectors().ifPresent(name -> assertThat(name).isEqualTo("LeiaTestKnnVectors"));
                });
                assertThat(segment.fieldFormats())
                        .anySatisfy(field -> assertThat(field.postings()).isPresent())
                        .anySatisfy(field -> assertThat(field.docValues()).isPresent())
                        .anySatisfy(field -> assertThat(field.knnVectors()).isPresent());
            });
        }
    }

    @Test
    void readsTheFieldFormatsOfALucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            long generation = index.commits(0).commits().getFirst().generation();

            Segment segment =
                    index.commit(0, generation).orElseThrow().segments().getFirst();
            assertThat(segment.fieldInfosError()).isEmpty();
            assertThat(segment.fieldFormats())
                    .extracting(FieldFormats::field, FieldFormats::postings)
                    .contains(tuple("body", Optional.of("Lucene912")), tuple("id", Optional.of("Lucene912")));
        }
    }

    private static CommitFile file(CommitDetails details, String name) {
        return details.files().stream()
                .filter(file -> file.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Two commits, both kept: {@code segments_1} with segment {@code _0} (2 documents, compound),
     * and {@code segments_2} that adds {@code _1} (1 document, not compound), deletes a document of
     * {@code _0} and has user data.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, config())) {
            writer.addDocument(doc("1"));
            writer.addDocument(doc("2"));
            writer.commit();
            writer.getConfig().setUseCompoundFile(false);
            writer.addDocument(doc("3"));
            writer.deleteDocuments(new Term("id", "1"));
            writer.setLiveCommitData(Map.of("step", "two").entrySet());
            writer.commit();
        }
        return dir;
    }

    /** The formats of {@link #formatsDoc}'s fields in Lucene's default codec. */
    private static final List<FieldFormats> DEFAULT_FIELD_FORMATS = List.of(
            new FieldFormats("body", Optional.of("Lucene104"), Optional.empty(), Optional.empty()),
            new FieldFormats("id", Optional.of("Lucene104"), Optional.empty(), Optional.empty()),
            new FieldFormats("rank", Optional.empty(), Optional.of("Lucene90"), Optional.empty()),
            new FieldFormats("stored", Optional.empty(), Optional.empty(), Optional.empty()),
            new FieldFormats("tag", Optional.of("Lucene104"), Optional.of("Lucene90"), Optional.empty()),
            new FieldFormats("vector", Optional.empty(), Optional.empty(), Optional.of("Lucene99HnswVectorsFormat")));

    /**
     * Three commits of documents with a field of each kind: {@code segments_1} with {@code _0}
     * (compound), {@code segments_2} that adds {@code _1} (not compound), and {@code segments_3}
     * that updates a doc value of {@code _0}, so that its field infos have an update.
     */
    private Path writeFormatsIndex() throws IOException {
        Path dir = tmp.resolve("formats");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, config())) {
            writer.addDocument(formatsDoc("1"));
            writer.commit();
            writer.getConfig().setUseCompoundFile(false);
            writer.addDocument(formatsDoc("2"));
            writer.commit();
            writer.updateNumericDocValue(new Term("id", "1"), "rank", 7);
            writer.commit();
        }
        return dir;
    }

    private static Document formatsDoc(String id) {
        Document doc = doc(id);
        doc.add(new TextField("body", "a text " + id, Field.Store.NO));
        doc.add(new StringField("tag", "t" + id, Field.Store.NO));
        doc.add(new SortedDocValuesField("tag", new BytesRef("t" + id)));
        doc.add(new NumericDocValuesField("rank", Long.parseLong(id)));
        doc.add(new KnnFloatVectorField("vector", new float[] {1, Float.parseFloat(id)}));
        doc.add(new StoredField("stored", id));
        return doc;
    }

    private static IndexWriterConfig config() {
        return new IndexWriterConfig()
                .setMergePolicy(NoMergePolicy.INSTANCE)
                .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE);
    }

    private static Document doc(String id) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        return doc;
    }
}
