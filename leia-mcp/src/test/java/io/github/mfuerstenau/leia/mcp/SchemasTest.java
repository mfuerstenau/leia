package io.github.mfuerstenau.leia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SchemasTest {

    record Inner(@Description("The field") String field, Optional<Boolean> binary) {}

    record Outer(
            @Description("A text") String text,
            int count,
            long total,
            float score,
            boolean flag,
            QueryOperator operator,
            Optional<String> maybe,
            OptionalInt maybeInt,
            OptionalLong maybeLong,
            List<Inner> inners,
            Optional<Inner> inner,
            Optional<List<String>> names) {}

    record Empty() {}

    record Untyped(Map<String, Object> values) {}

    @Test
    void requiresExactlyTheComponentsThatArentOptional() {
        assertThat(Schemas.of(Outer.class).toString()).isEqualTo("""
                        {"type":"object","properties":{\
                        "text":{"type":"string","description":"A text"},\
                        "count":{"type":"integer"},\
                        "total":{"type":"integer"},\
                        "score":{"type":"number"},\
                        "flag":{"type":"boolean"},\
                        "operator":{"type":"string","enum":["OR","AND"]},\
                        "maybe":{"type":"string"},\
                        "maybeInt":{"type":"integer"},\
                        "maybeLong":{"type":"integer"},\
                        "inners":{"type":"array","items":{"type":"object","properties":{\
                        "field":{"type":"string","description":"The field"},\
                        "binary":{"type":"boolean"}},"required":["field"],"additionalProperties":false}},\
                        "inner":{"type":"object","properties":{\
                        "field":{"type":"string","description":"The field"},\
                        "binary":{"type":"boolean"}},"required":["field"],"additionalProperties":false},\
                        "names":{"type":"array","items":{"type":"string"}}},\
                        "required":["text","count","total","score","flag","operator","inners"],\
                        "additionalProperties":false}""");
    }

    record Labelled(Map<String, String> labels, Optional<Map<String, Inner>> inners) {}

    @Test
    void describesAMapAsAnObjectOfItsValues() {
        assertThat(Schemas.of(Labelled.class).toString()).isEqualTo("""
                        {"type":"object","properties":{\
                        "labels":{"type":"object","additionalProperties":{"type":"string"}},\
                        "inners":{"type":"object","additionalProperties":{"type":"object","properties":{\
                        "field":{"type":"string","description":"The field"},\
                        "binary":{"type":"boolean"}},"required":["field"],"additionalProperties":false}}},\
                        "required":["labels"],"additionalProperties":false}""");
    }

    @Test
    void givesARecordWithoutComponentsNoRequiredList() {
        assertThat(Schemas.of(Empty.class).toString())
                .isEqualTo("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    @Test
    void knowsNoUntypedValues() {
        assertThatThrownBy(() -> Schemas.of(Untyped.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findsTheMissingComponentsAlsoInNestedRecords() {
        assertThat(Schemas.missing(Outer.class, Messages.MAPPER.readTree("""
                        {"text": null, "count": 1, "inner": {"binary": true}}""")))
                .containsExactly("text", "total", "score", "flag", "operator", "inners", "inner.field");
        assertThat(Schemas.missing(Inner.class, Messages.MAPPER.readTree("{\"field\": \"id\"}")))
                .isEmpty();
    }

    @Test
    void findsTheMissingComponentsAndNullsInListsAndMaps() {
        assertThat(Schemas.missing(Outer.class, Messages.MAPPER.readTree("""
                        {"text": "t", "count": 1, "total": 2, "score": 3, "flag": true, "operator": "OR",
                         "inners": [{"field": "id"}, {}, null], "names": ["a", null]}""")))
                .containsExactly("inners[1].field", "inners[2]", "names[1]");
        assertThat(Schemas.missing(Labelled.class, Messages.MAPPER.readTree("""
                        {"labels": {"a": "b", "c": null}, "inners": {"x": {"binary": true}}}""")))
                .containsExactly("labels.c", "inners.x.field");
    }
}
