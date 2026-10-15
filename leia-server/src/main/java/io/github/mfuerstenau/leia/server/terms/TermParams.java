package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import org.apache.lucene.util.BytesRef;

/** Checks of the params that the term and postings methods share. */
public final class TermParams {

    private TermParams() {}

    static void checkField(ReadOnlyIndex index, String field) throws RpcException {
        if (!index.hasField(field)) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
    }

    public static void checkLimit(int limit, int max) throws RpcException {
        if (limit < 1 || limit > max) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: limit must be between 1 and " + max + ", not " + limit);
        }
    }

    public static BytesRef term(BytesValue term) throws RpcException {
        try {
            return ByteStrings.decode(term.value(), term.binary());
        } catch (IllegalArgumentException e) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: the binary term '" + term.value() + "' is not hex", e);
        }
    }

    /** A term for messages: the text, or else the hex. */
    public static String text(BytesValue term) {
        return term.binary() ? "0x" + term.value() : term.value();
    }
}
