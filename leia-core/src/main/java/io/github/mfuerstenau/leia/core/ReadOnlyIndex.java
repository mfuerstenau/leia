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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.PriorityQueue;

/**
 * A path opened for reading only (ADR 0021): one or more index directories, each with a {@link
 * DirectoryReader} on its latest commit, read together through one {@link IndexReader}. Never an
 * {@code IndexWriter}. The directories are one of {@link DirectoryImpl}, which use no locking, so no
 * lock file can be created either (AGENTS.md, hard rule 1).
 */
public final class ReadOnlyIndex implements OpenedIndex {

    private static final Logger LOG = Logger.getLogger(ReadOnlyIndex.class.getName());

    /** How many terms {@link #fieldTerms} scans between two progress reports. */
    public static final int PROGRESS_INTERVAL = 1024;

    private final Path path;
    private final DirectoryImpl directoryImpl;
    private final List<Part> parts;
    private final List<SkippedIndex> skipped;
    /** The reader of the only part, or a {@link MultiReader} over the parts' readers. */
    private final IndexReader reader;

    private final FieldInfos fieldInfos;
    private final List<IndexSummary> summaries;
    /** Whether {@link #close} closes the directories: until {@link #reopen} hands them on. */
    private boolean ownsDirectories = true;

    private boolean closed;

    /**
     * An index directory with its reader. The reader may be shared with the index that {@link
     * #reopen} returned, so every index that holds it has a reference of its own, released on close.
     */
    private record Part(Path dir, Directory directory, DirectoryReader reader, SegmentInfos commit) {

        static Part open(Path dir, DirectoryImpl directoryImpl) throws IndexOpenException {
            Directory directory = null;
            DirectoryReader reader = null;
            try {
                directory = directoryImpl.open(dir);
                reader = DirectoryReader.open(directory);
                return new Part(dir, directory, reader, readCommit(directory, reader));
            } catch (IOException e) {
                IOUtils.closeWhileHandlingException(reader, directory);
                throw translate(dir, e);
            } catch (RuntimeException e) {
                IOUtils.closeWhileHandlingException(reader, directory);
                throw translate(dir, e);
            }
        }

        /** This part with the latest commit, or empty if it is the opened one. */
        Optional<Part> reopen() throws IndexOpenException {
            if (!Files.isDirectory(dir)) {
                throw new IndexOpenException(Reason.NOT_FOUND, "No such directory: " + dir);
            }
            DirectoryReader newReader = null;
            try {
                newReader = DirectoryReader.openIfChanged(reader);
                if (newReader == null) {
                    return Optional.empty();
                }
                return Optional.of(new Part(dir, directory, newReader, readCommit(directory, newReader)));
            } catch (IOException e) {
                IOUtils.closeWhileHandlingException(newReader);
                throw translate(dir, e);
            } catch (RuntimeException e) {
                IOUtils.closeWhileHandlingException(newReader);
                throw translate(dir, e);
            }
        }

        private static SegmentInfos readCommit(Directory directory, DirectoryReader reader) throws IOException {
            return SegmentInfos.readCommit(directory, reader.getIndexCommit().getSegmentsFileName());
        }

        void closeAll() {
            IOUtils.closeWhileHandlingException(reader, directory);
        }
    }

    private ReadOnlyIndex(Path path, DirectoryImpl directoryImpl, List<Part> parts, List<SkippedIndex> skipped)
            throws IOException {
        this.path = path;
        this.directoryImpl = directoryImpl;
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        this.reader = parts.size() == 1
                ? parts.getFirst().reader()
                : new MultiReader(parts.stream().map(Part::reader).toArray(IndexReader[]::new), false);
        this.fieldInfos = FieldInfos.getMergedFieldInfos(reader);
        List<IndexSummary> summaries = new ArrayList<>();
        int docBase = 0;
        for (Part part : parts) {
            summaries.add(summarize(part, docBase));
            docBase += part.reader().maxDoc();
        }
        this.summaries = List.copyOf(summaries);
    }

    /**
     * Opens the latest commit of the index in {@code path} through {@link DirectoryImpl#FS}.
     *
     * @throws IndexOpenException if there is no readable index at {@code path}
     */
    public static ReadOnlyIndex open(Path path) throws IndexOpenException {
        return open(path, DirectoryImpl.FS);
    }

    /**
     * Opens the latest commit of every index of {@code path} through {@code directoryImpl} (ADR 0020,
     * ADR 0021): the index in {@code path}, or else every index below it. An index below it that fails
     * to open, or whose fields conflict with those of the indexes before it, is skipped and listed in
     * {@link #skipped()}.
     *
     * @throws IndexOpenException if there is no index at or below {@code path}, or none of them opens
     */
    public static ReadOnlyIndex open(Path path, DirectoryImpl directoryImpl) throws IndexOpenException {
        Path root = IndexFinder.root(path);
        List<Path> dirs = IndexFinder.find(root);
        if (dirs.size() == 1) {
            Path dir = dirs.getFirst();
            Part part;
            try {
                part = Part.open(dir, directoryImpl);
            } catch (IndexOpenException e) {
                // An index found below the path is named, as it may not be the one expected.
                throw dir.equals(root) ? e : named(dir, e);
            }
            try {
                return new ReadOnlyIndex(root, directoryImpl, List.of(part), List.of());
            } catch (IOException | RuntimeException e) {
                part.closeAll();
                throw translate(part.dir(), e);
            }
        }

        List<Part> parts = new ArrayList<>();
        List<SkippedIndex> skipped = new ArrayList<>();
        Optional<IndexOpenException> firstFailure = Optional.empty();
        try {
            for (Path dir : dirs) {
                try {
                    parts.add(Part.open(dir, directoryImpl));
                } catch (IndexOpenException e) {
                    LOG.log(Level.INFO, () -> "Skipped " + dir + ": " + e.getMessage());
                    skipped.add(new SkippedIndex(dir, e.getMessage()));
                    firstFailure = firstFailure.or(() -> Optional.of(named(dir, e)));
                }
            }
            if (parts.isEmpty()) {
                throw firstFailure.orElseThrow();
            }
            List<Part> compatible = compatible(parts, skipped);
            skipped.sort(Comparator.comparingInt(skippedIndex -> dirs.indexOf(skippedIndex.path())));
            return new ReadOnlyIndex(root, directoryImpl, compatible, skipped);
        } catch (IOException | RuntimeException e) {
            parts.forEach(Part::closeAll);
            throw translate(root, e);
        }
    }

    /**
     * The parts whose fields agree with those of the parts before them. A field may only be indexed
     * one way in all of them, which {@link FieldInfos#getMergedFieldInfos} checks. The others are
     * closed and added to {@code skipped}.
     */
    private static List<Part> compatible(List<Part> parts, List<SkippedIndex> skipped) throws IOException {
        if (conflict(parts).isEmpty()) {
            return parts;
        }
        List<Part> accepted = new ArrayList<>();
        for (Part part : parts) {
            List<Part> candidates = new ArrayList<>(accepted);
            candidates.add(part);
            Optional<String> conflict = conflict(candidates);
            if (conflict.isEmpty()) {
                accepted.add(part);
            } else {
                LOG.log(Level.INFO, () -> "Skipped " + part.dir() + ": " + conflict.get());
                skipped.add(new SkippedIndex(
                        part.dir(), "Its fields conflict with those of the indexes before it: " + conflict.get()));
                part.closeAll();
            }
        }
        return accepted;
    }

    /** Why the fields of {@code parts} can't be merged; empty if they can. */
    private static Optional<String> conflict(List<Part> parts) throws IOException {
        if (parts.size() < 2) {
            return Optional.empty();
        }
        try (MultiReader multi =
                new MultiReader(parts.stream().map(Part::reader).toArray(IndexReader[]::new), false)) {
            FieldInfos.getMergedFieldInfos(multi);
            return Optional.empty();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * Opens the latest commit of each index again if it differs from the opened one (ADR 0019, ADR
     * 0021). The new index shares the directories, the readers of the indexes that didn't change, and
     * the readers of the segments in both commits. It owns the directories from then on: closing this
     * index then closes only its readers. This index stays open and usable until it is closed. Neither
     * the indexes found nor the skipped ones change.
     *
     * @return the new index; empty if every latest commit is the opened one
     * @throws IndexOpenException if a latest commit can't be read; this index is unaffected
     * @throws IllegalStateException if this index was reopened before
     */
    public Optional<ReadOnlyIndex> reopen() throws IndexOpenException {
        if (!ownsDirectories) {
            throw new IllegalStateException("Already reopened: " + path);
        }
        List<Part> newParts = new ArrayList<>();
        List<Part> reopened = new ArrayList<>();
        for (Part part : parts) {
            try {
                Optional<Part> newPart = part.reopen();
                newPart.ifPresent(reopened::add);
                newParts.add(newPart.orElse(part));
            } catch (IndexOpenException e) {
                reopened.forEach(newPart -> IOUtils.closeWhileHandlingException(newPart.reader()));
                throw parts.size() == 1 ? e : named(part.dir(), e);
            }
        }
        if (reopened.isEmpty()) {
            return Optional.empty();
        }

        List<Part> shared = newParts.stream().filter(parts::contains).toList();
        shared.forEach(part -> part.reader().incRef());
        try {
            ReadOnlyIndex index = new ReadOnlyIndex(path, directoryImpl, newParts, skipped);
            ownsDirectories = false;
            return Optional.of(index);
        } catch (IOException | RuntimeException e) {
            for (Part part : shared) {
                decRefQuietly(part.reader());
            }
            reopened.forEach(part -> IOUtils.closeWhileHandlingException(part.reader()));
            throw translate(path, e);
        }
    }

    private static IndexSummary summarize(Part part, int docBase) {
        SegmentInfos commit = part.commit();
        return new IndexSummary(
                part.dir(),
                docBase,
                commit.getSegmentsFileName(),
                part.reader().numDocs(),
                part.reader().maxDoc(),
                commit.getIndexCreatedVersionMajor(),
                Optional.ofNullable(commit.getMinSegmentLuceneVersion()));
    }

    /** {@code e} with the index directory it is about in front of its message. */
    private static IndexOpenException named(Path dir, IndexOpenException e) {
        return new IndexOpenException(e.reason(), dir + ": " + e.getMessage(), e);
    }

    /** Releases this index's reference to a reader that another index may still hold. */
    private static void decRefQuietly(IndexReader reader) {
        try {
            reader.decRef();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot close a reader");
        }
    }

    /** Maps a failure while opening {@code dir}, an unexpected one included, to a {@link Reason}. */
    private static IndexOpenException translate(Path dir, Exception e) {
        return switch (e) {
            case IndexOpenException open -> open;
            case IOException io -> translate(dir, io);
            case RuntimeException runtime -> {
                Optional<MissingCodecException> missing = MissingCodecException.of(runtime);
                if (missing.isPresent()) {
                    yield translate(dir, missing.get());
                }
                throw runtime;
            }
            default -> throw new IllegalStateException(e);
        };
    }

    /** Maps Lucene's exceptions to a {@link Reason}. The order matters: subclasses come first. */
    static IndexOpenException translate(Path dir, IOException e) {
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

    @Override
    public Path path() {
        return path;
    }

    @Override
    public List<Path> directories() {
        return summaries.stream().map(IndexSummary::path).toList();
    }

    /** Each index directory with its opened commit, in order; at least one. */
    public List<IndexSummary> indexes() {
        return summaries;
    }

    /** The index directories that were found but left out; empty unless several were found. */
    public List<SkippedIndex> skipped() {
        return skipped;
    }

    @Override
    public List<Path> skippedDirectories() {
        return skipped.stream().map(SkippedIndex::path).toList();
    }

    @Override
    public DirectoryImpl directoryImpl() {
        return directoryImpl;
    }

    /** Live documents in all index directories. */
    public int numDocs() {
        return reader.numDocs();
    }

    /** Documents in all index directories, including deleted ones. */
    public int maxDoc() {
        return reader.maxDoc();
    }

    /**
     * Statistics that are cheap to compute: the commits, the merged field infos and the per-segment
     * postings statistics, which the readers loaded on open. Nothing iterates over terms or
     * documents.
     *
     * @throws IOException if reading the postings statistics fails
     */
    public IndexStats stats() throws IOException {
        List<FieldStats> fields = new ArrayList<>();
        for (FieldInfo field : fieldInfos) {
            fields.add(fieldStats(field));
        }
        fields.sort(Comparator.comparingInt(FieldStats::number));
        List<IndexStats.CommitStats> indexes = new ArrayList<>();
        int segmentCount = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            SegmentInfos commit = part.commit();
            indexes.add(new IndexStats.CommitStats(
                    summaries.get(i),
                    commit.getGeneration(),
                    part.reader().getVersion(),
                    commit.size(),
                    commit.getCommitLuceneVersion(),
                    new TreeMap<>(commit.getUserData())));
            segmentCount += commit.size();
        }
        return new IndexStats(
                path,
                parts.getFirst().directory().getClass().getSimpleName(),
                segmentCount,
                reader.numDocs(),
                reader.maxDoc(),
                List.copyOf(indexes),
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

    /** Receives the terms of a field, one by one. */
    @FunctionalInterface
    interface TermVisitor {
        /**
         * @param term the term's bytes, valid only until the call returns
         * @param docFreq documents that contain the term, deleted ones included
         */
        void term(BytesRef term, int docFreq) throws IOException;
    }

    /**
     * Passes every term of {@code field}, over all index directories, to {@code visitor} in term
     * order. A field without postings has none.
     *
     * @throws IllegalArgumentException if the index has no such field
     * @throws IOException if reading the terms fails, or {@code visitor} fails
     */
    void forEachTerm(String field, TermVisitor visitor) throws IOException {
        checkField(field);
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }
        TermsEnum termsEnum = terms.iterator();
        for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
            visitor.term(term, termsEnum.docFreq());
        }
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
     * Rebuilds the terms a document was indexed with in each of {@code fields} (ADR 0029), from its
     * term vector where that holds all that the postings hold, else from the postings of the
     * document's segment. {@code progress} receives the terms gone through and can stop the scan by
     * throwing a {@link CancellationException}. A deleted document is rebuilt too.
     *
     * @param fields the indexed fields to rebuild, in order; empty for every indexed field, in field
     *     number order
     * @throws IllegalArgumentException if there is no such document id or field, or a field isn't
     *     indexed
     * @throws IOException if reading the term vectors or postings fails
     */
    public List<Reconstruct.Field> reconstruct(int docId, Optional<List<String>> fields, ScanProgress progress)
            throws IOException {
        checkDocId(docId);
        return Reconstruct.reconstruct(reader, docId, indexedFields(fields), progress);
    }

    /**
     * How many terms {@link #reconstruct} goes through, or empty if a segment doesn't know.
     *
     * @throws IllegalArgumentException if there is no such document id or field, or a field isn't
     *     indexed
     * @throws IOException if reading the term vectors fails
     */
    public OptionalLong reconstructTermCount(int docId, Optional<List<String>> fields) throws IOException {
        checkDocId(docId);
        return Reconstruct.termCount(reader, docId, indexedFields(fields));
    }

    /** The given fields, which must be indexed, or else every indexed field in field number order. */
    private List<String> indexedFields(Optional<List<String>> fields) {
        if (fields.isEmpty()) {
            return StreamSupport.stream(fieldInfos.spliterator(), false)
                    .filter(field -> field.getIndexOptions() != IndexOptions.NONE)
                    .sorted(Comparator.comparingInt(FieldInfo::getFieldNumber))
                    .map(FieldInfo::getName)
                    .toList();
        }
        for (String field : fields.get()) {
            checkField(field);
            if (fieldInfos.fieldInfo(field).getIndexOptions() == IndexOptions.NONE) {
                throw new IllegalArgumentException("Field " + field + " is not indexed");
            }
        }
        return List.copyOf(fields.get());
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
        return postings(field, term, fromDoc, limit, Optional.empty());
    }

    /**
     * A page of the postings of a term, as {@link #postings(String, BytesRef, int, int)} reads it,
     * through a filter of this index (ADR 0032): each posting says whether its document is in the
     * filter, or with {@code onlyMatches} the page has only those that are.
     *
     * @throws IllegalArgumentException if there is no such field, {@code fromDoc} is negative or
     *     {@code limit} is below 1
     * @throws IOException if reading the postings fails
     */
    public Optional<PostingsPage> postings(
            String field, BytesRef term, int fromDoc, int limit, Optional<PostingsPage.Filter> filter)
            throws IOException {
        checkField(field);
        checkLimit(limit);
        if (fromDoc < 0) {
            throw new IllegalArgumentException("fromDoc must not be negative: " + fromDoc);
        }
        return TermReader.postings(reader, fieldInfos.fieldInfo(field), term, fromDoc, limit, filter);
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
        return Searcher.search(
                reader, directoryReaders(), query, similarity, sort, offset, limit, exactTotalHits, fields, progress);
    }

    /**
     * Finds every document that matches {@code query}, without scoring (ADR 0031). Deleted documents
     * match only with {@code includeDeleted}. {@code progress} can stop it by throwing a {@link
     * CancellationException}, as for {@link #search}. It runs in the calling thread.
     *
     * @throws InvalidQueryException if the rewritten query has too many clauses
     * @throws IOException if reading the index fails
     */
    public DocFilter matches(Query query, boolean includeDeleted, ScanProgress progress)
            throws IOException, InvalidQueryException {
        return Searcher.matches(reader, directoryReaders(), query, includeDeleted, progress);
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
        return Searcher.explain(directoryReaders(), query, similarity, docId, progress);
    }

    /**
     * Looks for {@code pattern} in the stored values of every document (ADR 0027), in document id
     * order, and passes the values in which it was found to {@code listener}. {@code progress}
     * receives the document ids gone through and can stop the scan by throwing a {@link
     * CancellationException}.
     *
     * @param fields the stored fields to scan; empty for all of them
     * @param includeDeleted whether to scan deleted documents too
     * @param maxHits how many hits to pass on; later matches are only counted
     * @param valueTimeLimit how long matching one value may take before it is skipped
     * @throws IllegalArgumentException if a field doesn't exist, or {@code maxHits} is negative
     * @throws IOException if reading a document fails
     */
    public StoredScan.Summary storedScan(
            Pattern pattern,
            Optional<Set<String>> fields,
            boolean includeDeleted,
            int maxHits,
            Duration valueTimeLimit,
            StoredScan.Listener listener,
            ScanProgress progress)
            throws IOException {
        if (fields.isPresent()) {
            fields.get().forEach(this::checkField);
        }
        if (maxHits < 0) {
            throw new IllegalArgumentException("maxHits must not be negative: " + maxHits);
        }
        return StoredScan.scan(reader, pattern, fields, includeDeleted, maxHits, valueTimeLimit, listener, progress);
    }

    /**
     * Finds every document with a stored value in which {@code pattern} is found (ADR 0032), as
     * {@link #storedScan} looks for it, and keeps them as a filter. Values that take longer than
     * {@code valueTimeLimit} are skipped and counted. {@code progress} receives the document ids gone
     * through and can stop the scan by throwing a {@link CancellationException}.
     *
     * @param fields the stored fields to scan; empty for all of them
     * @param includeDeleted whether deleted documents can match too
     * @throws IllegalArgumentException if a field doesn't exist
     * @throws IOException if reading a document fails
     */
    public DocFilter storedScanMatches(
            Pattern pattern,
            Optional<Set<String>> fields,
            boolean includeDeleted,
            Duration valueTimeLimit,
            ScanProgress progress)
            throws IOException {
        if (fields.isPresent()) {
            fields.get().forEach(this::checkField);
        }
        return StoredScan.matches(reader, pattern, fields, includeDeleted, valueTimeLimit, progress);
    }

    @Override
    public CommitList commits(int index) throws IOException {
        checkDirectory(index);
        Part part = parts.get(index);
        return CommitReader.commits(part.directory(), part.commit().getGeneration());
    }

    @Override
    public Optional<CommitDetails> commit(int index, long generation) throws IOException {
        checkDirectory(index);
        Part part = parts.get(index);
        return CommitReader.commit(part.directory(), generation, part.commit().getGeneration());
    }

    /** The readers of the index directories, in order. */
    private List<DirectoryReader> directoryReaders() {
        return parts.stream().map(Part::reader).toList();
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

    /**
     * The reader on the opened commits: the only index directory's, or a {@link MultiReader} over
     * them. It stays valid until this index is closed.
     */
    public IndexReader reader() {
        return reader;
    }

    /**
     * Releases this index's references to the readers, which closes those that no reopened index
     * shares, and closes the directories unless {@link #reopen} handed them on.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        List<Closeable> closeables = new ArrayList<>();
        if (parts.size() > 1) {
            closeables.add(reader);
        }
        for (Part part : parts) {
            closeables.add(part.reader()::decRef);
        }
        if (ownsDirectories) {
            for (Part part : parts) {
                closeables.add(part.directory());
            }
        }
        IOUtils.close(closeables);
    }
}
