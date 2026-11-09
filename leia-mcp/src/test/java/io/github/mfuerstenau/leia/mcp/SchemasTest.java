package io.github.mfuerstenau.leia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SchemasTest {

    record Inner(
            @Description("The field") String field,
            @Nullable Boolean binary) {}

    record Outer(
            @Description("A text") String text,
            int count,
            long total,
            float score,
            boolean flag,
            QueryOperator operator,
            @Nullable String maybe,
            @Nullable Integer maybeInt,
            @Nullable Long maybeLong,
            @Nullable Double maybeDouble,
            List<Inner> inners,
            @Nullable Inner inner,
            @Nullable List<String> names) {}

    record Empty() {}

    record Untyped(Map<String, Object> values) {}

    record Optionals(Optional<String> maybe) {}

    record Holder(SchemasTest.@Nullable Inner qualified) {}

    @Test
    void requiresExactlyTheComponentsThatArentNullable() {
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
                        "maybeDouble":{"type":"number"},\
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

    record Labelled(Map<String, String> labels, @Nullable Map<String, Inner> inners) {}

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
    void knowsNoOptionals() {
        assertThatThrownBy(() -> Schemas.of(Optionals.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("No schema for");
    }

    @Test
    void seesNullableOnAQualifiedType() {
        assertThat(Schemas.of(Holder.class).toString()).isEqualTo("""
                        {"type":"object","properties":{\
                        "qualified":{"type":"object","properties":{\
                        "field":{"type":"string","description":"The field"},\
                        "binary":{"type":"boolean"}},"required":["field"],"additionalProperties":false}},\
                        "additionalProperties":false}""");
        assertThat(Schemas.missing(Holder.class, Messages.MAPPER.readTree("{}")))
                .isEmpty();
        assertThat(Schemas.missing(Holder.class, Messages.MAPPER.readTree("{\"qualified\": {}}")))
                .containsExactly("qualified.field");
    }

    @Test
    void acceptsAnAbsentOrNullNullableComponent() {
        assertThat(Schemas.missing(Outer.class, Messages.MAPPER.readTree("""
                        {"text": "t", "count": 1, "total": 2, "score": 3, "flag": true, "operator": "OR",
                         "inners": [], "maybe": null, "maybeInt": null, "names": null}"""))).isEmpty();
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
