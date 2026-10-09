package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.DocumentReader.StoredValues;
import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * A regular expression over stored values (ADR 0027): reads every document's stored fields and
 * looks for the pattern ({@link Matcher#find}) in each text value, and in each binary value that is
 * UTF-8. Numbers are skipped. Positions are UTF-8 byte offsets into the whole value, as lengths are
 * in ADR 0008.
 *
 * <p>Each value has a time limit against runaway backtracking. The matcher reads the value through
 * a {@link CharSequence} that checks the limit and the scan's {@link ScanProgress} every {@value
 * #CHECK_INTERVAL} characters it reads, so a value that takes too long is skipped, and a cancelled
 * scan stops even inside one value. No thread is interrupted.
 */
public final class StoredScan {

    /** The most match spans a hit has; {@link Hit#matchCount} counts all of them. */
    public static final int MAX_SPANS = 100;
    /** The most values that ran out of time that the summary names; it counts all of them. */
    public static final int MAX_TIMED_OUT = 100;
    /** How many characters (UTF-16 units) before the first match the snippet starts. */
    static final int SNIPPET_BEFORE = 40;
    /** How many characters (UTF-16 units) from the start of the first match the snippet ends. */
    static final int SNIPPET_AFTER = 160;
    /** How many documents the scan reads between two progress reports. */
    static final int PROGRESS_INTERVAL = 64;
    /** How many characters the matcher reads between two checks of the time limit and progress. */
    static final int CHECK_INTERVAL = 4096;

    /**
     * Where a match is in a value.
     *
     * @param start the UTF-8 byte offset of the match's first byte
     * @param end the UTF-8 byte offset after the match's last byte; {@code start} for an empty match
     */
    public record Span(int start, int end) {}

    /**
     * A stored value in which the pattern was found.
     *
     * @param docId the document id in the whole index
     * @param deleted whether the document is deleted
     * @param field the field name
     * @param valueIndex the value's position among the document's stored values in the field
     * @param length the value's length in UTF-8 bytes
     * @param matchCount how many matches the value has
     * @param spans the first {@value #MAX_SPANS} matches, in order
     * @param snippetStart the UTF-8 byte offset in the value where {@code snippet} starts
     * @param snippet the value around the first match, cut between two characters
     */
    public record Hit(
            int docId,
            boolean deleted,
            String field,
            int valueIndex,
            int length,
            int matchCount,
            List<Span> spans,
            int snippetStart,
            String snippet) {}

    /** A stored value: of a document, in a field, at a position among the field's values. */
    public record ValueRef(int docId, String field, int valueIndex) {}

    /**
     * What a scan found.
     *
     * @param scannedDocs the document ids gone through, deleted ones included even when skipped
     * @param matchedDocs documents with at least one value in which the pattern was found
     * @param matchedValues values in which the pattern was found
     * @param hitsTruncated whether more values matched than were passed on as hits
     * @param timedOutValues values that were skipped because matching them took too long
     * @param timedOut the first {@value #MAX_TIMED_OUT} of those
     * @param took how long the scan took
     */
    public record Summary(
            int scannedDocs,
            int matchedDocs,
            long matchedValues,
            boolean hitsTruncated,
            long timedOutValues,
            List<ValueRef> timedOut,
            Duration took) {}

    /** Receives what the scan finds, on the scan's thread. */
    public interface Listener {

        /** One of the first {@code maxHits} values in which the pattern was found. */
        void hit(Hit hit);

        /** Called for every value in which the pattern was found, with the number so far. */
        void matched(long matchedValues);
    }

    private final Pattern pattern;
    private final Optional<Set<String>> fields;
    private final boolean includeDeleted;
    private final int maxHits;
    private final long valueTimeLimitNanos;
    private final Listener listener;
    private final ScanProgress progress;
    private final CharsetDecoder utf8 = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    private int scannedDocs;
    private int matchedDocs;
    private long matchedValues;
    private int hits;
    private long timedOutValues;
    private final List<ValueRef> timedOut = new ArrayList<>();

    private StoredScan(
            Pattern pattern,
            Optional<Set<String>> fields,
            boolean includeDeleted,
            int maxHits,
            Duration valueTimeLimit,
            Listener listener,
            ScanProgress progress) {
        this.pattern = pattern;
        this.fields = fields;
        this.includeDeleted = includeDeleted;
        this.maxHits = maxHits;
        this.valueTimeLimitNanos = valueTimeLimit.toNanos();
        this.listener = listener;
        this.progress = progress;
    }

    /**
     * Scans every document of {@code reader}, in document id order.
     *
     * @param fields the stored fields to scan; empty for all of them
     * @param includeDeleted whether to scan deleted documents too
     * @param maxHits how many hits to pass on; later matches are only counted
     * @param valueTimeLimit how long matching one value may take before it is skipped
     * @throws CancellationException from {@code progress}, to stop the scan
     * @throws IOException if reading a document fails
     */
    static Summary scan(
            IndexReader reader,
            Pattern pattern,
            Optional<Set<String>> fields,
            boolean includeDeleted,
            int maxHits,
            Duration valueTimeLimit,
            Listener listener,
            ScanProgress progress)
            throws IOException {
        return new StoredScan(pattern, fields, includeDeleted, maxHits, valueTimeLimit, listener, progress).run(reader);
    }

    private Summary run(IndexReader reader) throws IOException {
        long start = System.nanoTime();
        for (LeafReaderContext context : reader.leaves()) {
            LeafReader leaf = context.reader();
            Bits liveDocs = leaf.getLiveDocs();
            StoredFields storedFields = leaf.storedFields();
            for (int doc = 0; doc < leaf.maxDoc(); doc++) {
                if (scannedDocs % PROGRESS_INTERVAL == 0) {
                    progress.scanned(scannedDocs);
                }
                boolean deleted = liveDocs != null && !liveDocs.get(doc);
                if (!deleted || includeDeleted) {
                    StoredValues values = new StoredValues(
                            name -> fields.isEmpty() || fields.get().contains(name));
                    storedFields.document(doc, values);
                    if (scanDocument(context.docBase + doc, deleted, values.values)) {
                        matchedDocs++;
                    }
                }
                scannedDocs++;
            }
        }
        progress.scanned(scannedDocs);
        return new Summary(
                scannedDocs,
                matchedDocs,
                matchedValues,
                matchedValues > hits,
                timedOutValues,
                List.copyOf(timedOut),
                Duration.ofNanos(System.nanoTime() - start));
    }

    /** Scans the values of one document, and says whether any of them matched. */
    private boolean scanDocument(int docId, boolean deleted, Map<String, List<StoredValue>> values) {
        boolean matched = false;
        for (Map.Entry<String, List<StoredValue>> field : values.entrySet()) {
            List<StoredValue> fieldValues = field.getValue();
            for (int index = 0; index < fieldValues.size(); index++) {
                Optional<String> text = text(fieldValues.get(index));
                if (text.isPresent() && scanValue(docId, deleted, field.getKey(), index, text.get())) {
                    matched = true;
                }
            }
        }
        return matched;
    }

    /** A value's text: a text value, or a binary one that is UTF-8. */
    private Optional<String> text(StoredValue value) {
        return switch (value) {
            case StoredValue.Text text -> Optional.of(text.value());
            case StoredValue.Binary binary -> utf8(binary.value());
            default -> Optional.empty();
        };
    }

    private Optional<String> utf8(BytesRef bytes) {
        try {
            return Optional.of(utf8.decode(ByteBuffer.wrap(bytes.bytes, bytes.offset, bytes.length))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** Looks for the pattern in one value, and says whether it was found. */
    private boolean scanValue(int docId, boolean deleted, String field, int index, String text) {
        // The match positions in UTF-16 units: start, end, start, end, …
        List<Integer> positions = new ArrayList<>();
        int matchCount = 0;
        try {
            Matcher matcher = pattern.matcher(new TimedChars(text, System.nanoTime() + valueTimeLimitNanos));
            while (matcher.find()) {
                if (matchCount < MAX_SPANS) {
                    positions.add(matcher.start());
                    positions.add(matcher.end());
                }
                matchCount++;
            }
        } catch (ValueTimeout e) {
            timedOutValues++;
            if (timedOut.size() < MAX_TIMED_OUT) {
                timedOut.add(new ValueRef(docId, field, index));
            }
            return false;
        }
        if (matchCount == 0) {
            return false;
        }
        matchedValues++;
        listener.matched(matchedValues);
        if (hits < maxHits) {
            hits++;
            listener.hit(hit(docId, deleted, field, index, text, matchCount, positions));
        }
        return true;
    }

    private static Hit hit(
            int docId, boolean deleted, String field, int index, String text, int matchCount, List<Integer> positions) {
        Utf8Offsets offsets = new Utf8Offsets(text);
        List<Span> spans = new ArrayList<>(positions.size() / 2);
        for (int i = 0; i < positions.size(); i += 2) {
            spans.add(new Span(offsets.at(positions.get(i)), offsets.at(positions.get(i + 1))));
        }
        int first = positions.getFirst();
        int from = Math.max(0, first - SNIPPET_BEFORE);
        int to = Math.min(text.length(), first + SNIPPET_AFTER);
        // Never between the two halves of a surrogate pair.
        if (from > 0
                && Character.isLowSurrogate(text.charAt(from))
                && Character.isHighSurrogate(text.charAt(from - 1))) {
            from--;
        }
        if (to < text.length()
                && Character.isLowSurrogate(text.charAt(to))
                && Character.isHighSurrogate(text.charAt(to - 1))) {
            to++;
        }
        return new Hit(
                docId,
                deleted,
                field,
                index,
                offsets.at(text.length()),
                matchCount,
                List.copyOf(spans),
                new Utf8Offsets(text).at(from),
                text.substring(from, to));
    }

    /**
     * Turns UTF-16 positions in a string into UTF-8 byte offsets, as {@code UnicodeUtil} encodes
     * the string: a surrogate pair is 4 bytes, a lone surrogate 3 (U+FFFD). Positions must be asked
     * for in ascending order. A position between the two halves of a pair counts the whole pair.
     */
    static final class Utf8Offsets {

        private final String text;
        private int chars;
        private int bytes;

        Utf8Offsets(String text) {
            this.text = text;
        }

        int at(int position) {
            if (position < chars) {
                if (position == chars - 1 && Character.isLowSurrogate(text.charAt(position))) {
                    // Between the halves of the pair just counted.
                    return bytes;
                }
                throw new IllegalArgumentException("Position " + position + " is before " + chars);
            }
            while (chars < position) {
                char c = text.charAt(chars);
                if (c < 0x80) {
                    bytes += 1;
                } else if (c < 0x800) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(c)
                        && chars + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(chars + 1))) {
                    bytes += 4;
                    chars++;
                } else {
                    bytes += 3;
                }
                chars++;
            }
            return bytes;
        }
    }

    /** Thrown by {@link TimedChars} once a value has taken too long. */
    private static final class ValueTimeout extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ValueTimeout() {
            super(null, null, false, false);
        }
    }

    /**
     * A value as the matcher reads it: every {@value #CHECK_INTERVAL} characters read, it checks the
     * value's deadline and asks the scan's progress, which throws once the scan is cancelled.
     */
    private final class TimedChars implements CharSequence {

        private final String text;
        private final long deadline;
        private int reads;

        TimedChars(String text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (++reads == CHECK_INTERVAL) {
                reads = 0;
                if (System.nanoTime() - deadline > 0) {
                    throw new ValueTimeout();
                }
                progress.scanned(scannedDocs);
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
