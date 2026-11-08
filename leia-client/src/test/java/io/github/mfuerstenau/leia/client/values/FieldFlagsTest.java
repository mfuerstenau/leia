package io.github.mfuerstenau.leia.client.values;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** What a document has in a field. */
class FieldFlagsTest {

    private static final DocumentField NOTHING =
            new DocumentField("f", OptionalLong.empty(), List.of(), Optional.empty(), false, Optional.empty());

    @Test
    void aFieldWithNothingOfTheDocumentIsEmpty() {
        assertThat(FieldFlags.isEmpty(NOTHING)).isTrue();
        assertThat(FieldFlags.of(Optional.empty(), NOTHING)).isEqualTo("--------");
    }

    @Test
    void anyOfNormStoredDocValuesTermVectorAndVectorIsSomething() {
        List<DocumentField> fields = List.of(
                new DocumentField("f", OptionalLong.of(3), List.of(), Optional.empty(), false, Optional.empty()),
                new DocumentField(
                        "f",
                        OptionalLong.empty(),
                        List.of(new StoredValue(StoredType.STRING, "x", false)),
                        Optional.empty(),
                        false,
                        Optional.empty()),
                new DocumentField(
                        "f",
                        OptionalLong.empty(),
                        List.of(),
                        Optional.of(new DocValues(DocValuesType.NUMERIC, List.of(1L), List.of())),
                        false,
                        Optional.empty()),
                new DocumentField("f", OptionalLong.empty(), List.of(), Optional.empty(), true, Optional.empty()),
                new DocumentField(
                        "f",
                        OptionalLong.empty(),
                        List.of(),
                        Optional.empty(),
                        false,
                        Optional.of(new KnnVector(VectorEncoding.FLOAT32, List.of(1f), List.of()))));

        assertThat(fields).noneMatch(FieldFlags::isEmpty);
        assertThat(fields.stream().map(field -> FieldFlags.of(Optional.empty(), field)))
                .containsExactly("-N------", "---S----", "-----D--", "----V---", "-------K");
    }
}
