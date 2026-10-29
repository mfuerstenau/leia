package io.github.mfuerstenau.leia.mcp;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON Schema of a tool's arguments or result, generated from its record (ADR 0100 §2). A
 * component is required exactly when it isn't {@code Optional*}, as hard rule 3 of AGENTS.md has
 * it, and {@link Description} gives its description. It knows strings, the numbers, booleans,
 * enums, lists, the {@code Optional}s and records, and no other type.
 */
public final class Schemas {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Set<Class<?>> INTEGERS = Set.of(
            int.class,
            Integer.class,
            long.class,
            Long.class,
            short.class,
            Short.class,
            byte.class,
            Byte.class,
            OptionalInt.class,
            OptionalLong.class);

    private static final Set<Class<?>> NUMBERS =
            Set.of(float.class, Float.class, double.class, Double.class, OptionalDouble.class);

    private Schemas() {}

    /** The schema of record {@code type}: an object with its components, and no other properties. */
    public static ObjectNode of(Class<? extends Record> type) {
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        List<String> required = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            ObjectNode property = schema(component.getGenericType());
            Description description = component.getAnnotation(Description.class);
            if (description != null) {
                property.put("description", description.value());
            }
            properties.set(component.getName(), property);
            if (!optional(component.getGenericType())) {
                required.add(component.getName());
            }
        }
        if (!required.isEmpty()) {
            ArrayNode names = schema.putArray("required");
            required.forEach(names::add);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * The required components of {@code type} that {@code value} lacks or has as {@code null}, also
     * in the records it holds, as {@code outer.inner}.
     */
    public static List<String> missing(Class<? extends Record> type, JsonNode value) {
        List<String> missing = new ArrayList<>();
        missing(type, value, "", missing);
        return missing;
    }

    private static void missing(Class<?> type, JsonNode value, String prefix, List<String> missing) {
        for (RecordComponent component : type.getRecordComponents()) {
            JsonNode node = value.get(component.getName());
            Type componentType = component.getGenericType();
            if (node == null || node.isNull()) {
                if (!optional(componentType)) {
                    missing.add(prefix + component.getName());
                }
            } else if (node.isObject() && unwrap(componentType) instanceof Class<?> inner && inner.isRecord()) {
                missing(inner, node, prefix + component.getName() + ".", missing);
            }
        }
    }

    private static ObjectNode schema(Type type) {
        Type unwrapped = unwrap(type);
        ObjectNode schema = NODES.objectNode();
        if (unwrapped instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
            schema.put("type", "array");
            schema.set("items", schema(parameterized.getActualTypeArguments()[0]));
            return schema;
        }
        if (!(unwrapped instanceof Class<?> raw)) {
            throw new IllegalArgumentException("No schema for " + type);
        }
        if (raw == String.class) {
            schema.put("type", "string");
        } else if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
        } else if (INTEGERS.contains(raw)) {
            schema.put("type", "integer");
        } else if (NUMBERS.contains(raw)) {
            schema.put("type", "number");
        } else if (raw.isEnum()) {
            schema.put("type", "string");
            ArrayNode values = schema.putArray("enum");
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
        } else if (raw.isRecord()) {
            return of(raw.asSubclass(Record.class));
        } else {
            throw new IllegalArgumentException("No schema for " + type);
        }
        return schema;
    }

    /** {@code T} of an {@code Optional<T>}, else {@code type} itself. */
    private static Type unwrap(Type type) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Optional.class) {
            return parameterized.getActualTypeArguments()[0];
        }
        return type;
    }

    private static boolean optional(Type type) {
        Type raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
        return raw == Optional.class
                || raw == OptionalInt.class
                || raw == OptionalLong.class
                || raw == OptionalDouble.class;
    }
}
