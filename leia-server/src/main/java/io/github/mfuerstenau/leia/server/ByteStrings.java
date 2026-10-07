package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.apache.lucene.util.BytesRef;

/**
 * Byte strings on the wire (ADR 0006): as text if the bytes are valid UTF-8, otherwise as lowercase
 * hex, marked as binary.
 */
public final class ByteStrings {

    /**
     * A byte string as it goes on the wire.
     *
     * @param value the text, or the hex if {@code binary}
     * @param binary whether the bytes are not valid UTF-8
     */
    public record Encoded(String value, boolean binary) {}

    private ByteStrings() {}

    public static Encoded encode(BytesRef bytes) {
        try {
            return new Encoded(
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(ByteBuffer.wrap(bytes.bytes, bytes.offset, bytes.length))
                            .toString(),
                    false);
        } catch (CharacterCodingException e) {
            return new Encoded(hex(bytes), true);
        }
    }

    /** A byte string as {@link BytesValue}. */
    public static BytesValue bytesValue(BytesRef bytes) {
        Encoded encoded = encode(bytes);
        return new BytesValue(encoded.value(), encoded.binary());
    }

    /**
     * The bytes of a byte string from the wire: the UTF-8 of the text, or else the bytes in hex.
     *
     * @throws IllegalArgumentException if {@code binary} and {@code value} is not valid hex
     */
    public static BytesRef decode(String value, boolean binary) {
        return binary ? new BytesRef(HexFormat.of().parseHex(value)) : new BytesRef(value);
    }

    /** The bytes in lowercase hex. */
    public static String hex(BytesRef bytes) {
        return HexFormat.of().formatHex(bytes.bytes, bytes.offset, bytes.offset + bytes.length);
    }
}
