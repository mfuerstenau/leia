package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.io.Serial;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.util.Version;

/**
 * The index names a codec or format that isn't loaded (ADR 0018): one from a jar that wasn't given,
 * or one of Lucene's own from another Lucene version.
 */
public final class MissingCodecException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Lucene's {@code NamedSPILoader.lookup} says so, wrapped or not, in an {@link IllegalArgumentException}. */
    private static final Pattern SPI_MESSAGE =
            Pattern.compile("An SPI class of type (\\S+) with name '([^']*)' does not exist");

    /** Lucene names its own codecs and formats by the version that introduced them. */
    private static final Pattern LUCENE_NAME = Pattern.compile("Lucene\\d+");

    public enum Kind {
        CODEC(Codec.class, "codec"),
        POSTINGS_FORMAT(PostingsFormat.class, "postings format"),
        DOC_VALUES_FORMAT(DocValuesFormat.class, "doc values format"),
        KNN_VECTORS_FORMAT(KnnVectorsFormat.class, "KNN vectors format");

        private final Class<?> type;
        private final String description;

        Kind(Class<?> type, String description) {
            this.type = type;
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    private final Kind kind;
    private final String name;

    private MissingCodecException(Kind kind, String name, IllegalArgumentException cause) {
        super(
                lucenes(name)
                        ? "Lucene " + Version.LATEST + " has no " + kind + " '" + name
                                + "'; the index needs another Lucene version"
                        : "the index needs the " + kind + " '" + name + "', which isn't loaded",
                cause);
        this.kind = kind;
        this.name = name;
    }

    /** The missing codec or format that {@code e} or one of its causes reports, if any. */
    static Optional<MissingCodecException> of(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException iae && iae.getMessage() != null) {
                Matcher matcher = SPI_MESSAGE.matcher(iae.getMessage());
                if (matcher.find()) {
                    Optional<Kind> kind = kind(matcher.group(1));
                    if (kind.isPresent()) {
                        return Optional.of(new MissingCodecException(kind.get(), matcher.group(2), iae));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Kind> kind(String typeName) {
        return Arrays.stream(Kind.values())
                .filter(kind -> kind.type.getName().equals(typeName))
                .findFirst();
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    /** Whether the name is one of Lucene's own: no jar adds it, another Lucene version has it. */
    public boolean isLucenes() {
        return lucenes(name);
    }

    private static boolean lucenes(String name) {
        return LUCENE_NAME.matcher(name).matches();
    }
}
