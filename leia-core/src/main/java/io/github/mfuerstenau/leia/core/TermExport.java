package io.github.mfuerstenau.leia.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Writes the terms of a field to a file, one {@code term<delimiter>docFreq} per line, as Luke's
 * export does (ADR 0022). The file must lie outside every index directory of the opened path, so
 * the index is never written to (AGENTS.md, hard rule 1).
 */
public final class TermExport {

    /** How many terms are written between two progress reports. */
    public static final int PROGRESS_INTERVAL = 1024;

    private static final HexFormat HEX = HexFormat.of();

    private TermExport() {}

    /** What separates a term from its document frequency. */
    public enum Delimiter {
        COMMA(','),
        SPACE(' '),
        TAB('\t');

        private final char character;

        Delimiter(char character) {
            this.character = character;
        }

        public char character() {
            return character;
        }
    }

    /**
     * The outcome of an export.
     *
     * @param path the file written
     * @param terms the terms written, one per line
     * @param bytes the size of the file
     */
    public record Result(Path path, long terms, long bytes) {}

    /** Receives the terms of an export. */
    @FunctionalInterface
    public interface TermSink {

        /**
         * One term, as {@code length} bytes of {@code bytes} from {@code offset} on, with its document
         * frequency. The bytes are valid only during the call.
         */
        void term(byte[] bytes, int offset, int length, int docFreq) throws IOException;
    }

    /** The terms of a field, as an export reads them. */
    @FunctionalInterface
    public interface TermSource {

        /** Passes every term to {@code sink}, in term order. */
        void forEachTerm(TermSink sink) throws IOException;
    }

    /**
     * The terms of {@code field} of {@code index}, document frequencies including deleted documents.
     * A field without postings has none.
     *
     * @throws IllegalArgumentException if the index has no such field
     */
    public static TermSource terms(ReadOnlyIndex index, String field) {
        if (!index.hasField(field)) {
            throw new IllegalArgumentException("No field '" + field + "' in the index");
        }
        return sink ->
                index.forEachTerm(field, (term, docFreq) -> sink.term(term.bytes, term.offset, term.length, docFreq));
    }

    /** The file can't be written: where it is, what is there, or an I/O error while writing. */
    public static final class OutputException extends IOException {

        @Serial
        private static final long serialVersionUID = 1L;

        OutputException(String message) {
            super(message);
        }

        OutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Checks that the export may write {@code file}: an absolute path whose directory exists, outside
     * and not below every one of {@code indexDirectories} (the opened and the skipped ones), not a directory or a
     * symbolic link, and not there yet unless {@code overwrite}. Real paths are compared, so a link
     * into an index directory is caught.
     *
     * @throws IllegalArgumentException if {@code file} is not absolute
     * @throws OutputException if the file can't or mustn't be written
     */
    public static void checkTarget(List<Path> indexDirectories, Path file, boolean overwrite) throws OutputException {
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("The path must be absolute: " + file);
        }
        Path target = file.normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new OutputException("No such directory: " + parent);
        }
        Path realParent;
        try {
            realParent = parent.toRealPath();
        } catch (IOException e) {
            throw new OutputException("Cannot resolve " + parent + ": " + e.getMessage(), e);
        }
        for (Path dir : indexDirectories) {
            if (realParent.startsWith(realPath(dir))) {
                throw new OutputException(
                        "Not writing into the index directory " + dir + ": leia never changes an index");
            }
        }
        if (Files.isSymbolicLink(target)) {
            throw new OutputException("Not writing through a symbolic link: " + target);
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new OutputException("Is a directory: " + target);
        }
        if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new OutputException("File exists: " + target);
        }
    }

    private static Path realPath(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            return dir.toAbsolutePath().normalize();
        }
    }

    /**
     * Writes every term of {@code terms}, in term order, with its document frequency, to {@code
     * file}, after {@link #checkTarget} against {@code indexDirectories}. A term that isn't valid
     * UTF-8 is written as lowercase hex. No terms give an empty file. If the export fails or is cancelled, the file is deleted.
     *
     * <p>{@code progress} gets the number of terms written every {@value #PROGRESS_INTERVAL} terms and
     * at the end; it can stop the export by throwing a {@link CancellationException}.
     *
     * @throws IllegalArgumentException if {@code file} is not absolute
     * @throws OutputException if the file can't or mustn't be written
     * @throws IOException if reading the terms fails
     */
    public static Result export(
            List<Path> indexDirectories,
            TermSource terms,
            Path file,
            Delimiter delimiter,
            boolean overwrite,
            ScanProgress progress)
            throws IOException {
        checkTarget(indexDirectories, file, overwrite);
        Path target = file.normalize();
        OpenOption[] options = overwrite
                ? new OpenOption[] {
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                }
                : new OpenOption[] {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
        OutputStream stream;
        try {
            stream = Files.newOutputStream(target, options);
        } catch (FileAlreadyExistsException e) {
            throw new OutputException("File exists: " + target, e);
        } catch (IOException e) {
            throw new OutputException("Cannot create " + target + ": " + e.getMessage(), e);
        }

        Lines lines = new Lines(new BufferedOutputStream(stream, 1 << 16), target);
        boolean done = false;
        try {
            long[] written = {0};
            byte separator = (byte) delimiter.character();
            terms.forEachTerm((bytes, offset, length, docFreq) -> {
                lines.write(bytes, offset, length, separator, docFreq);
                if (++written[0] % PROGRESS_INTERVAL == 0) {
                    progress.scanned(written[0]);
                }
            });
            progress.scanned(written[0]);
            lines.close();
            done = true;
            return new Result(target, written[0], lines.bytes);
        } finally {
            if (!done) {
                lines.closeQuietly();
                try {
                    Files.deleteIfExists(target);
                } catch (IOException e) {
                    // The export fails anyway, with the error that ended it.
                }
            }
        }
    }

    /** Writes the lines, counting the bytes; turns write errors into {@link OutputException}s. */
    private static final class Lines {

        private final OutputStream out;
        private final Path path;
        private final CharsetDecoder utf8 = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        long bytes;
        private boolean closed;

        Lines(OutputStream out, Path path) {
            this.out = out;
            this.path = path;
        }

        void write(byte[] term, int offset, int length, byte separator, int docFreq) throws OutputException {
            try {
                if (isUtf8(term, offset, length)) {
                    out.write(term, offset, length);
                    bytes += length;
                } else {
                    byte[] hex = HEX.formatHex(term, offset, offset + length).getBytes(StandardCharsets.US_ASCII);
                    out.write(hex);
                    bytes += hex.length;
                }
                byte[] rest =
                        (((char) separator) + Integer.toString(docFreq) + "\n").getBytes(StandardCharsets.US_ASCII);
                out.write(rest);
                bytes += rest.length;
            } catch (IOException e) {
                throw failed(e);
            }
        }

        private boolean isUtf8(byte[] term, int offset, int length) {
            try {
                utf8.reset().decode(ByteBuffer.wrap(term, offset, length));
                return true;
            } catch (CharacterCodingException e) {
                return false;
            }
        }

        void close() throws OutputException {
            closed = true;
            try {
                out.close();
            } catch (IOException e) {
                throw failed(e);
            }
        }

        void closeQuietly() {
            if (!closed) {
                closed = true;
                try {
                    out.close();
                } catch (IOException e) {
                    // The export fails anyway.
                }
            }
        }

        private OutputException failed(IOException e) {
            return new OutputException("Cannot write " + path + ": " + e.getMessage(), e);
        }
    }
}
