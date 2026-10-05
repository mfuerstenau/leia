package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import java.util.Optional;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;

/**
 * The first, lenient pass over a request line: checks that it is a single JSON value and finds the
 * request id, so that even a malformed request gets an error response with its id.
 */
final class RequestScanner {

    private RequestScanner() {}

    /**
     * Returns the top-level {@code id} of {@code line} if it is a non-negative integer.
     *
     * @throws StreamReadException if {@code line} is not exactly one JSON value
     */
    static Optional<Long> idOf(String line) {
        try (JsonParser parser = ProtocolJson.mapper().createParser(line)) {
            Optional<Long> id = Optional.empty();
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    parser.nextToken();
                    if (name.equals("id") && isNonNegativeLong(parser)) {
                        id = Optional.of(parser.getLongValue());
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
            if (parser.nextToken() != null) {
                throw new StreamReadException(parser, "Trailing content after the JSON value");
            }
            return id;
        }
    }

    private static boolean isNonNegativeLong(JsonParser parser) {
        return parser.currentToken() == JsonToken.VALUE_NUMBER_INT
                && parser.getNumberType() != JsonParser.NumberType.BIG_INTEGER
                && parser.getLongValue() >= 0;
    }
}
