package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Byte strings in the records (ADR 0006): as text if the bytes are valid UTF-8, otherwise as lowercase
 * hex, marked as binary.
 */
public final class ByteStrings {

    /**
     * A byte string as it goes into a record.
     *
     * @param value the text, or the hex if {@code binary}
     * @param binary whether the bytes are not valid UTF-8
     */
    public record Encoded(String value, boolean binary) {}

    private ByteStrings() {}

    public static Encoded encode(byte[] bytes) {
        try {
            return new Encoded(
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(ByteBuffer.wrap(bytes))
                            .toString(),
                    false);
        } catch (CharacterCodingException _) {
            return new Encoded(hex(bytes), true);
        }
    }

    /** A byte string as {@link BytesValue}. */
    public static BytesValue bytesValue(byte[] bytes) {
        Encoded encoded = encode(bytes);
        return new BytesValue(encoded.value(), encoded.binary());
    }

    /**
     * The bytes of a byte string from a record: the UTF-8 of the text, or else the bytes in hex.
     *
     * @throws IllegalArgumentException if {@code binary} and {@code value} is not valid hex
     */
    public static byte[] decode(String value, boolean binary) {
        return binary ? HexFormat.of().parseHex(value) : value.getBytes(StandardCharsets.UTF_8);
    }

    /** The bytes in lowercase hex. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** The first {@code length} bytes in lowercase hex. */
    public static String hex(byte[] bytes, int length) {
        return HexFormat.of().formatHex(bytes, 0, length);
    }
}
