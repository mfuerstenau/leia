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
import java.util.Set;
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
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.similarities.Similarity;
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
    /** Whether {@link #close} closes the directory: until {@link #reopen} hands it on. */
    private boolean ownsDirectory = true;

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
            Optional<MissingCodecException> missing = MissingCodecException.of(e);
            if (missing.isPresent()) {
                throw translate(dir, missing.get());
            }
            throw e;
        }
    }

    /**
     * Opens the latest commit of the index again if it differs from the opened one (ADR 0019). The
     * new index shares the directory and the readers of the segments in both commits. It owns the
     * directory from then on: closing this index then closes only its reader. This index stays open
     * and usable until it is closed.
     *
     * @return the new index; empty if the latest commit is the opened one
     * @throws IndexOpenException if the latest commit can't be read; this index is unaffected
     * @throws IllegalStateException if this index was reopened before
     */
    public Optional<ReadOnlyIndex> reopen() throws IndexOpenException {
        if (!ownsDirectory) {
            throw new IllegalStateException("Already reopened: " + summary.path());
        }
        Path dir = summary.path();
        if (!Files.isDirectory(dir)) {
            throw new IndexOpenException(Reason.NOT_FOUND, "No such directory: " + dir);
        }

        DirectoryReader newReader = null;
        try {
            newReader = DirectoryReader.openIfChanged(reader);
            if (newReader == null) {
                return Optional.empty();
            }
            SegmentInfos newCommit = SegmentInfos.readCommit(
                    directory, newReader.getIndexCommit().getSegmentsFileName());
            ReadOnlyIndex reopened =
                    new ReadOnlyIndex(directory, newReader, newCommit, summarize(dir, newReader, newCommit));
            ownsDirectory = false;
            return Optional.of(reopened);
        } catch (IOException e) {
            IOUtils.closeWhileHandlingException(newReader);
            throw translate(dir, e);
        } catch (RuntimeException e) {
            IOUtils.closeWhileHandlingException(newReader);
            Optional<MissingCodecException> missing = MissingCodecException.of(e);
            if (missing.isPresent()) {
                throw translate(dir, missing.get());
            }
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
            case MissingCodecException missing
            when missing.isLucenes() ->
                new IndexOpenException(Reason.UNSUPPORTED_FORMAT, "Unsupported index format: " + e.getMessage(), e);
            case MissingCodecException _ ->
                new IndexOpenException(Reason.MISSING_CODEC, "Missing codec: " + e.getMessage(), e);
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

    /** The field called {@code name}, merged over all segments. */
    public Optional<FieldInfo> fieldInfo(String name) {
        return Optional.ofNullable(fieldInfos.fieldInfo(name));
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
        checkField(field);
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
     * The stored values of a document in a field, in the order they were added. A deleted document is
     * read too.
     *
     * @throws IllegalArgumentException if there is no such document id or field
     * @throws IOException if reading the document fails
     */
    public List<IndexDocument.StoredValue> storedValues(int docId, String field) throws IOException {
        checkDocId(docId);
        checkField(field);
        return DocumentReader.storedValues(reader, docId, field);
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
        checkField(field);
        return DocumentReader.termVector(reader, docId, field);
    }

    /**
     * A page of a field's terms in term order, from {@code from} on, or from the first term. A field
     * without postings has no terms.
     *
     * @param from the first term of the page, or else the term after it; empty for the first term
     * @param limit the most terms the page has, at least 1
     * @throws IllegalArgumentException if there is no such field, or {@code limit} is below 1
     * @throws IOException if reading the terms fails
     */
    public TermPage terms(String field, Optional<BytesRef> from, int limit) throws IOException {
        checkField(field);
        checkLimit(limit);
        return TermReader.terms(reader, field, from, limit);
    }

    /**
     * A page of the postings of a term: the documents that contain it, from document id {@code
     * fromDoc} on, deleted documents included.
     *
     * @param fromDoc the first document id of the page, or else the one after it
     * @param limit the most postings the page has, at least 1
     * @return the page; empty if the field has no such term
     * @throws IllegalArgumentException if there is no such field, {@code fromDoc} is negative or
     *     {@code limit} is below 1
     * @throws IOException if reading the postings fails
     */
    public Optional<PostingsPage> postings(String field, BytesRef term, int fromDoc, int limit) throws IOException {
        checkField(field);
        checkLimit(limit);
        if (fromDoc < 0) {
            throw new IllegalArgumentException("fromDoc must not be negative: " + fromDoc);
        }
        return TermReader.postings(reader, fieldInfos.fieldInfo(field), term, fromDoc, limit);
    }

    /**
     * Where a term occurs in a document, from the postings: one entry per occurrence, in order. It is
     * empty if positions are not indexed.
     *
     * @return the positions; empty if the document does not contain the term
     * @throws IllegalArgumentException if there is no such document id or field
     * @throws IOException if reading the postings fails
     */
    public Optional<List<TermPosition>> positions(int docId, String field, BytesRef term) throws IOException {
        checkDocId(docId);
        checkField(field);
        return TermReader.positions(reader, docId, field, term);
    }

    /**
     * Runs {@code query} and returns the hits from {@code offset} to {@code offset + limit}, best
     * first or in the order of {@code sort}, with their stored values in {@code fields} (ADR 0010,
     * ADR 0012). Deleted documents never match. Sorted hits have their scores too.
     *
     * <p>{@code progress} gets the number of documents before the segment being searched; it can stop
     * the search by throwing a {@link CancellationException}, which it is also asked about while the
     * query is rewritten and the hits are collected. The search runs in the calling thread.
     *
     * @param similarity what scores the hits
     * @param sort the order of the hits; empty for the best score first
     * @param exactTotalHits whether to count every hit; otherwise counting stops after {@value
     *     Searcher#TOTAL_HITS_THRESHOLD}
     * @param fields the stored fields to read per hit; empty for all of them
     * @throws IllegalArgumentException if {@code offset} is negative, {@code limit} is below 1, or their sum
     *     overflows
     * @throws InvalidQueryException if the rewritten query has too many clauses
     * @throws IOException if reading the index fails
     */
    public SearchResults search(
            Query query,
            Similarity similarity,
            Optional<Sort> sort,
            int offset,
            int limit,
            boolean exactTotalHits,
            Optional<Set<String>> fields,
            ScanProgress progress)
            throws IOException, InvalidQueryException {
        checkLimit(limit);
        if (offset < 0 || offset > Integer.MAX_VALUE - limit) {
            throw new IllegalArgumentException(
                    "offset must be from 0 to " + (Integer.MAX_VALUE - limit) + ": " + offset);
        }
        return Searcher.search(reader, query, similarity, sort, offset, limit, exactTotalHits, fields, progress);
    }

    /**
     * Explains how a document scores for {@code query} with {@code similarity}, or why it doesn't
     * match. {@code progress} can
     * stop the explanation by throwing a {@link CancellationException}, as for {@link #search}.
     *
     * @throws IllegalArgumentException if there is no such document id
     * @throws InvalidQueryException if the rewritten query has too many clauses
     * @throws IOException if reading the index fails
     */
    public Explanation explain(Query query, Similarity similarity, int docId, ScanProgress progress)
            throws IOException, InvalidQueryException {
        checkDocId(docId);
        return Searcher.explain(reader, query, similarity, docId, progress);
    }

    /**
     * The commits of the index directory, newest first, read from the directory again: they include
     * commits written after the index was opened. The opened one is {@link CommitInfo#current()}.
     *
     * @throws IOException if the directory can't be listed
     */
    public CommitList commits() throws IOException {
        return CommitReader.commits(directory, commit.getGeneration());
    }

    /**
     * One commit of the index directory with its segments and files, read from the directory again.
     *
     * @return the commit; empty if the directory has no commit of that generation
     * @throws IOException if the commit can't be read; a {@link MissingCodecException} if it names a codec
     *     that isn't loaded
     */
    public Optional<CommitDetails> commit(long generation) throws IOException {
        return CommitReader.commit(directory, generation, commit.getGeneration());
    }

    private void checkField(String field) {
        if (!hasField(field)) {
            throw new IllegalArgumentException("No field " + field);
        }
    }

    private static void checkLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
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
        if (ownsDirectory) {
            IOUtils.close(reader, directory);
        } else {
            reader.close();
        }
    }
}
