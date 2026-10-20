package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.FieldDateResolution;
import io.github.mfuerstenau.leia.server.RpcException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The checks of the query parsers' options that need no Lucene, so that an index of Lucene 10 and
 * one that a runtime reads refuse them in the same words (ADR 0026, ADR 0069). The classic options
 * are worded as leia-core's {@code ClassicOptions}, which checks the same for Lucene 10.
 */
public final class ParserChecks {

    /** The largest edit distance Lucene's fuzzy queries support. */
    public static final int MAX_FUZZY_EDITS = 2;

    private ParserChecks() {}

    /** Checks that a field exists, as each path checks it. */
    @FunctionalInterface
    public interface FieldCheck {

        /** @throws RpcException {@code FIELD_NOT_FOUND} for an unknown field */
        void check(String field) throws RpcException;
    }

    /**
     * Checks the options that only the classic parser has.
     *
     * @throws RpcException {@code INVALID_PARAMS} if {@code fuzzyMaxEdits} is out of 0 to 2, or for
     *     {@code autoGeneratePhraseQueries} without {@code splitOnWhitespace}
     */
    public static void classic(int fuzzyMaxEdits, boolean splitOnWhitespace, boolean autoGeneratePhraseQueries)
            throws RpcException {
        if (fuzzyMaxEdits < 0 || fuzzyMaxEdits > MAX_FUZZY_EDITS) {
            throw invalidParams("fuzzyMaxEdits must be 0 to " + MAX_FUZZY_EDITS + ", not " + fuzzyMaxEdits);
        }
        if (autoGeneratePhraseQueries && !splitOnWhitespace) {
            throw invalidParams("autoGeneratePhraseQueries needs splitOnWhitespace");
        }
    }

    /**
     * The date resolutions of single fields, by field name, in their order.
     *
     * @throws RpcException {@code FIELD_NOT_FOUND} if {@code checkField} refuses a field; {@code
     *     INVALID_PARAMS} for a field listed twice
     */
    public static Map<String, DateResolution> fieldDateResolutions(
            List<FieldDateResolution> fields, FieldCheck checkField) throws RpcException {
        Map<String, DateResolution> resolutions = new LinkedHashMap<>();
        for (FieldDateResolution field : fields) {
            checkField.check(field.field());
            if (resolutions.put(field.field(), field.resolution()) != null) {
                throw invalidParams("the field '" + field.field() + "' has its date resolution listed twice");
            }
        }
        return resolutions;
    }

    /** @throws RpcException {@code INVALID_PARAMS} if {@code tag} is not a BCP 47 language tag */
    public static Locale locale(String tag) throws RpcException {
        try {
            if (tag.isBlank()) {
                throw new IllformedLocaleException("blank");
            }
            return new Locale.Builder().setLanguageTag(tag).build();
        } catch (IllformedLocaleException e) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: '" + tag + "' is not a BCP 47 language tag, such as en-US",
                    e);
        }
    }

    /**
     * Not {@code TimeZone.getTimeZone(String)}, which takes an unknown id for GMT.
     *
     * @throws RpcException {@code INVALID_PARAMS} if {@code id} is not a time zone
     */
    public static ZoneId timeZone(String id) throws RpcException {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: '" + id + "' is not a time zone, such as UTC, Europe/Berlin or +02:00",
                    e);
        }
    }

    private static RpcException invalidParams(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }
}
