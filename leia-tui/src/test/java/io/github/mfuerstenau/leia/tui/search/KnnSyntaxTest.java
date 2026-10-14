package io.github.mfuerstenau.leia.tui.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.tui.search.KnnSyntax.Target;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KnnSyntaxTest {

    @Test
    void readsADocumentId() {
        assertThat(KnnSyntax.target(" 42 ", VectorEncoding.FLOAT32)).isEqualTo(new Target.Doc(42));
        assertThat(KnnSyntax.target("0", VectorEncoding.BYTE)).isEqualTo(new Target.Doc(0));
    }

    @Test
    void readsAVectorOfFloats() {
        assertThat(KnnSyntax.target("[0.5, -1 2.25e1,,+.5]", VectorEncoding.FLOAT32))
                .isEqualTo(new Target.Vector(
                        new KnnVector(VectorEncoding.FLOAT32, List.of(0.5f, -1f, 22.5f, 0.5f), List.of())));
    }

    @Test
    void readsAVectorOfBytes() {
        assertThat(KnnSyntax.target("[127 -128, 0]", VectorEncoding.BYTE))
                .isEqualTo(new Target.Vector(
                        new KnnVector(VectorEncoding.BYTE, List.of(), List.of((byte) 127, (byte) -128, (byte) 0))));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "red | FLOAT32 | Type a document id, or a vector in brackets such as [0.5, -1, 2.25].",
                "-3 | BYTE | Type a document id, or a vector in brackets such as [12, -7, 0].",
                "99999999999 | FLOAT32 | '99999999999' is too large for a document id.",
                "[1, 2 | FLOAT32 | A vector ends with ].",
                "[ , ] | FLOAT32 | The vector has no components.",
                "[1, NaN] | FLOAT32 | 'NaN' is not a finite decimal number.",
                "[1e39] | FLOAT32 | '1e39' is not a finite decimal number.",
                "[0x1p3] | FLOAT32 | '0x1p3' is not a finite decimal number.",
                "[1, 128] | BYTE | '128' is not a byte: a vector of bytes takes whole numbers from -128 to 127.",
                "[0.5] | BYTE | '0.5' is not a byte: a vector of bytes takes whole numbers from -128 to 127."
            })
    void saysWhyALineDoesntRead(String line, VectorEncoding encoding, String message) {
        assertThatThrownBy(() -> KnnSyntax.target(line, encoding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
