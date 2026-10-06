package io.github.mfuerstenau.leia.core;

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
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.IOUtils;

/**
 * A Lucene index opened for reading only: a {@link DirectoryReader} on the latest commit, never an
 * {@code IndexWriter}. The directory uses {@link NoLockFactory}, so no lock file can be created
 * either (AGENTS.md, hard rule 1).
 */
public final class ReadOnlyIndex implements Closeable {

    private final Directory directory;
    private final DirectoryReader reader;
    private final SegmentInfos commit;
    private final IndexSummary summary;

    private ReadOnlyIndex(Directory directory, DirectoryReader reader, SegmentInfos commit, IndexSummary summary) {
        this.directory = directory;
        this.reader = reader;
        this.commit = commit;
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
        for (FieldInfo field : FieldInfos.getMergedFieldInfos(reader)) {
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

    /** The reader on the opened commit. It stays valid until this index is closed. */
    public DirectoryReader reader() {
        return reader;
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(reader, directory);
    }
}
