package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.FieldTerms.TopTerm;
import io.github.mfuerstenau.leia.core.IndexOpenException.Reason;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.PriorityQueue;

/**
 * A Lucene index opened for reading only: a {@link DirectoryReader} on the latest commit, never an
 * {@code IndexWriter}. The directory uses {@link NoLockFactory}, so no lock file can be created
 * either (AGENTS.md, hard rule 1).
 */
public final class ReadOnlyIndex implements Closeable {

    /** How many terms {@link #fieldTerms} scans between two progress reports. */
    public static final int PROGRESS_INTERVAL = 1024;

    private final Directory directory;
    private final DirectoryReader reader;
    private final SegmentInfos commit;
    private final FieldInfos fieldInfos;
    private final IndexSummary summary;

    private ReadOnlyIndex(Directory directory, DirectoryReader reader, SegmentInfos commit, IndexSummary summary) {
        this.directory = directory;
        this.reader = reader;
        this.commit = commit;
        this.fieldInfos = FieldInfos.getMergedFieldInfos(reader);
        this.summary = summary;
    }

    /**
     * Opens the latest commit of the index in {@code path}.
     *
     * @throws IndexOpenException if there is no readable index at {@code path}
     */
    public static ReadOnlyIndex open(Path path) throws IndexOpenException {
        Path dir = path.toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            throw new IndexOpenException(Reason.NOT_FOUND, "No such file or directory: " + dir);
        }
        if (!Files.isDirectory(dir)) {
            throw new IndexOpenException(Reason.NOT_AN_INDEX, "Not a directory: " + dir);
        }

        Directory directory = null;
        DirectoryReader reader = null;
        try {
            directory = FSDirectory.open(dir, NoLockFactory.INSTANCE);
            reader = DirectoryReader.open(directory);
            SegmentInfos commit =
                    SegmentInfos.readCommit(directory, reader.getIndexCommit().getSegmentsFileName());
            return new ReadOnlyIndex(directory, reader, commit, summarize(dir, reader, commit));
        } catch (IOException e) {
            IOUtils.closeWhileHandlingException(reader, directory);
            throw translate(dir, e);
        } catch (RuntimeException e) {
            IOUtils.closeWhileHandlingException(reader, directory);
            throw e;
        }
    }

    private static IndexSummary summarize(Path dir, DirectoryReader reader, SegmentInfos commit) {
        return new IndexSummary(
                dir,
                commit.getSegmentsFileName(),
                reader.numDocs(),
                reader.maxDoc(),
                commit.getIndexCreatedVersionMajor(),
                Optional.ofNullable(commit.getMinSegmentLuceneVersion()));
    }

    /** Maps Lucene's exceptions to a {@link Reason}. The order matters: subclasses come first. */
    private static IndexOpenException translate(Path dir, IOException e) {
        // Lucene wraps a file that the commit refers to but that is missing in a
        // CorruptIndexException ("Unexpected file read error"); name the file instead.
        Optional<IOException> missingFile = missingFile(e);
        if (missingFile.isPresent()) {
            return new IndexOpenException(
                    Reason.CORRUPT,
                    "Corrupt index, missing file: " + missingFile.get().getMessage(),
                    e);
        }
        return switch (e) {
            case IndexNotFoundException _ ->
                new IndexOpenException(Reason.NOT_AN_INDEX, "Not a Lucene index (no segments_N file): " + dir, e);
            case IndexFormatTooOldException _, IndexFormatTooNewException _ ->
                new IndexOpenException(Reason.UNSUPPORTED_FORMAT, "Unsupported index format: " + e.getMessage(), e);
            case CorruptIndexException _, EOFException _ ->
                new IndexOpenException(Reason.CORRUPT, "Corrupt index: " + e.getMessage(), e);
            case AccessDeniedException _ ->
                new IndexOpenException(Reason.NOT_READABLE, "Permission denied: " + e.getMessage(), e);
            default -> new IndexOpenException(Reason.NOT_READABLE, "Cannot read the index: " + e, e);
        };
    }

    /** The first missing-file exception in the cause chain of {@code e}, except "no index here". */
    private static Optional<IOException> missingFile(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IndexNotFoundException) {
                return Optional.empty();
            }
            if (t instanceof NoSuchFileException || t instanceof FileNotFoundException) {
                return Optional.of((IOException) t);
            }
        }
        return Optional.empty();
    }

    public IndexSummary summary() {
        return summary;
    }

    /**
     * Statistics that are cheap to compute: the commit, the merged field infos and the per-segment
     * postings statistics, which the reader loaded on open. Nothing iterates over terms or documents.
     *
     * @throws IOException if reading the postings statistics fails
     */
    public IndexStats stats() throws IOException {
        List<FieldStats> fields = new ArrayList<>();
        for (FieldInfo field : fieldInfos) {
            fields.add(fieldStats(field));
        }
        fields.sort(Comparator.comparingInt(FieldStats::number));
        return new IndexStats(
                summary,
                directory.getClass().getSimpleName(),
                commit.getGeneration(),
                reader.getVersion(),
                commit.size(),
                commit.getCommitLuceneVersion(),
                new TreeMap<>(commit.getUserData()),
                List.copyOf(fields));
    }

    private FieldStats fieldStats(FieldInfo field) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field.getName());
        Optional<FieldStats.TermStats> termStats = terms == null
                ? Optional.empty()
                : Optional.of(new FieldStats.TermStats(
                        terms.getDocCount(), terms.getSumDocFreq(), terms.getSumTotalTermFreq()));
        return new FieldStats(
                field.getName(),
                field.getFieldNumber(),
                field.getIndexOptions(),
                field.hasNorms(),
                field.hasPayloads(),
                field.hasTermVectors(),
                field.getDocValuesType(),
                field.getPointDimensionCount(),
                field.getPointNumBytes(),
                field.getVectorDimension(),
                termStats);
    }

    /** Whether any segment of the index has a field called {@code name}. */
    public boolean hasField(String name) {
        return fieldInfos.fieldInfo(name) != null;
    }

    /**
     * Counts the distinct terms of a field and finds its {@code topN} terms with the highest document
     * frequency, in one pass over the field's terms. A field without postings has no terms. With
     * {@code topN} 0 on an index with one segment, the count comes from the segment, without a scan.
     *
     * <p>{@code progress} gets the number of terms scanned every {@value #PROGRESS_INTERVAL} terms and
     * at the end; it can stop the scan by throwing a {@link CancellationException}. The scan runs in
     * the calling thread and may take long on a large field.
     *
     * @throws IllegalArgumentException if the index has no such field, or {@code topN} is negative
     * @throws IOException if reading the terms fails
     */
    public FieldTerms fieldTerms(String field, int topN, ScanProgress progress) throws IOException {
        if (!hasField(field)) {
            throw new IllegalArgumentException("No field " + field);
        }
        if (topN < 0) {
            throw new IllegalArgumentException("topN must not be negative: " + topN);
        }
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            progress.scanned(0);
            return new FieldTerms(field, 0, List.of());
        }
        long size = terms.size();
        if (topN == 0 && size >= 0) {
            progress.scanned(size);
            return new FieldTerms(field, size, List.of());
        }

        TopTermsQueue top = new TopTermsQueue(topN);
        TermsEnum termsEnum = terms.iterator();
        long scanned = 0;
        for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
            scanned++;
            if (topN > 0) {
                int docFreq = termsEnum.docFreq();
                // Terms come in order, so a tie with the weakest top term loses: the earlier term stays.
                if (top.size() < topN || docFreq > top.top().docFreq()) {
                    top.insertWithOverflow(new TopTerm(BytesRef.deepCopyOf(term), docFreq, termsEnum.totalTermFreq()));
                }
            }
            if (scanned % PROGRESS_INTERVAL == 0) {
                progress.scanned(scanned);
            }
        }
        progress.scanned(scanned);

        TopTerm[] topTerms = new TopTerm[top.size()];
        for (int i = topTerms.length - 1; i >= 0; i--) {
            topTerms[i] = top.pop();
        }
        return new FieldTerms(field, scanned, List.of(topTerms));
    }

    /** Whether {@code docId} is a document id of the index, deleted or not: from 0 to maxDoc - 1. */
    public boolean hasDocId(int docId) {
        return docId >= 0 && docId < reader.maxDoc();
    }

    /**
     * What a document holds in every field of the index: norms, stored values, doc values, and
     * whether it has a term vector. A deleted document is read too.
     *
     * @throws IllegalArgumentException if there is no such document id
     * @throws IOException if reading the document fails
     */
    public IndexDocument document(int docId) throws IOException {
        checkDocId(docId);
        return DocumentReader.document(reader, fieldInfos, docId);
    }

    /**
     * The term vector of a document for a field, in term order. It is empty if the document has
     * none for the field.
     *
     * @throws IllegalArgumentException if there is no such document id or field
     * @throws IOException if reading the term vector fails
     */
    public List<TermVectorTerm> termVector(int docId, String field) throws IOException {
        checkDocId(docId);
        if (!hasField(field)) {
            throw new IllegalArgumentException("No field " + field);
        }
        return DocumentReader.termVector(reader, docId, field);
    }

    private void checkDocId(int docId) {
        if (!hasDocId(docId)) {
            throw new IllegalArgumentException("No document " + docId + "; maxDoc is " + reader.maxDoc());
        }
    }

    /** The weakest top term on top: the lowest document frequency, then the latest term. */
    private static final class TopTermsQueue extends PriorityQueue<TopTerm> {

        TopTermsQueue(int size) {
            super(size);
        }

        @Override
        protected boolean lessThan(TopTerm a, TopTerm b) {
            return a.docFreq() != b.docFreq()
                    ? a.docFreq() < b.docFreq()
                    : a.term().compareTo(b.term()) > 0;
        }
    }

    /** The reader on the opened commit. It stays valid until this index is closed. */
    public DirectoryReader reader() {
        return reader;
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(reader, directory);
    }
}
