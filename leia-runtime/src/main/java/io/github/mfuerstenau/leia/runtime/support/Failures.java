package io.github.mfuerstenau.leia.runtime.support;

import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.IndexReadException.Reason;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How the runtimes put a failure into words for leia (ADR 0050). */
public final class Failures {

    /** How many causes {@link #describe} names at most. */
    private static final int MAX_CAUSES = 8;

    /**
     * Lucene's {@code NamedSPILoader.lookup} says so, from 4.0 on, in an {@link
     * IllegalArgumentException}; the type is the runtime's Lucene's own.
     */
    private static final Pattern SPI_MESSAGE =
            Pattern.compile("An SPI class of type (?:\\S+\\.)?(\\w+) with name '([^']*)' does not exist");

    /** Lucene names its own codecs and formats by the version that introduced them. */
    private static final Pattern LUCENE_NAME = Pattern.compile("Lucene\\d+");

    /** The SPI types an index names, by their simple name, as leia-core describes them. */
    private static final Map<String, String> SPI_TYPES = Map.of(
            "Codec", "codec",
            "PostingsFormat", "postings format",
            "DocValuesFormat", "doc values format",
            "KnnVectorsFormat", "KNN vectors format");

    private Failures() {}

    /** The message of {@code e}, or its class if it has none. */
    public static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** The message of {@code t}, followed by those of its causes, at most {@value #MAX_CAUSES} of them. */
    public static String describe(Throwable t) {
        StringBuilder text = new StringBuilder(message(t));
        int depth = 0;
        for (Throwable cause = t.getCause(); cause != null && depth < MAX_CAUSES; cause = cause.getCause(), depth++) {
            text.append("; caused by ")
                    .append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(message(cause));
        }
        return text.toString();
    }

    /**
     * The codec or format that {@code e} or one of its causes reports missing, as leia-core reports it
     * (ADR 0070): {@link Reason#UNSUPPORTED_FORMAT} for one of Lucene's own, which another Lucene
     * reads, {@link Reason#MISSING_CODEC} for any other, which a jar could add.
     *
     * @param luceneVersion the version of the runtime's Lucene
     */
    public static Optional<IndexReadException> missingCodec(Throwable e, String luceneVersion) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException && t.getMessage() != null) {
                Matcher matcher = SPI_MESSAGE.matcher(t.getMessage());
                if (matcher.find() && SPI_TYPES.containsKey(matcher.group(1))) {
                    String kind = SPI_TYPES.get(matcher.group(1));
                    String name = matcher.group(2);
                    return Optional.of(
                            LUCENE_NAME.matcher(name).matches()
                                    ? new IndexReadException(
                                            Reason.UNSUPPORTED_FORMAT,
                                            "Lucene " + luceneVersion + " has no " + kind + " '" + name
                                                    + "'; the index needs another Lucene version",
                                            e)
                                    : new IndexReadException(
                                            Reason.MISSING_CODEC,
                                            "the index needs the " + kind + " '" + name + "', which isn't loaded",
                                            e));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The first file that {@code e} or one of its causes reports missing: Lucene wraps a file that a
     * commit refers to but that is missing in a {@code CorruptIndexException}. A runtime asks only if
     * {@code e} isn't Lucene's {@code IndexNotFoundException}, which says there is no commit at all.
     */
    public static Optional<IOException> missingFile(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NoSuchFileException || t instanceof FileNotFoundException) {
                return Optional.of((IOException) t);
            }
        }
        return Optional.empty();
    }

    /** {@code failure}, of opening an index, in leia-core's words: its reason in front of its message. */
    public static IndexReadException opening(IndexReadException failure) {
        String message = message(failure);
        return new IndexReadException(
                failure.reason(),
                switch (failure.reason()) {
                    case CORRUPT -> "Corrupt index: " + message;
                    case UNSUPPORTED_FORMAT -> "Unsupported index format: " + message;
                    case MISSING_CODEC -> "Missing codec: " + message;
                    case NOT_READABLE ->
                        failure.getCause() instanceof AccessDeniedException
                                ? "Permission denied: " + message
                                : "Cannot read the index: " + message;
                },
                failure.getCause());
    }

    /**
     * {@code e}, a failure of the index directory {@code dir}, with the directory in front of its
     * message, and with its reason if it has one.
     */
    public static IOException named(Path dir, Throwable e) {
        String message = dir + ": " + message(e);
        return e instanceof IndexReadException failure
                ? new IndexReadException(failure.reason(), message, e)
                : new IOException(message, e);
    }
}
