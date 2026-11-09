package io.github.mfuerstenau.leia.mcp;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON Schema of a tool's arguments or result, generated from its record (ADR 0100 §2). A
 * component is required exactly when it isn't JSpecify's {@link Nullable}, which it reads at
 * runtime (ADR 0127), and {@link Description} gives its description. It knows strings, the
 * numbers, booleans, enums, lists, maps with string keys and records, and no other type: not the
 * {@code Optional}s.
 */
public final class Schemas {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Set<Class<?>> INTEGERS =
            Set.of(int.class, Integer.class, long.class, Long.class, short.class, Short.class, byte.class, Byte.class);

    private static final Set<Class<?>> NUMBERS = Set.of(float.class, Float.class, double.class, Double.class);

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
            if (!nullable(component)) {
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
     * in the records it holds, as {@code outer.inner}, and the {@code null} items and values of its
     * lists and maps, as {@code list[0]} and {@code map.key}, since Jackson binds them as {@code null}.
     */
    public static List<String> missing(Class<? extends Record> type, JsonNode value) {
        List<String> missing = new ArrayList<>();
        missing(type, value, "", missing);
        return missing;
    }

    private static void missing(Class<?> type, JsonNode value, String prefix, List<String> missing) {
        for (RecordComponent component : type.getRecordComponents()) {
            JsonNode node = value.get(component.getName());
            if (node == null || node.isNull()) {
                if (!nullable(component)) {
                    missing.add(prefix + component.getName());
                }
            } else {
                missingIn(component.getGenericType(), node, prefix + component.getName(), missing);
            }
        }
    }

    /** What {@code node}, a value of {@code type} at {@code path} that isn't {@code null}, lacks inside. */
    private static void missingIn(Type type, JsonNode node, String path, List<String> missing) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
            if (node.isArray()) {
                Type item = parameterized.getActualTypeArguments()[0];
                for (int i = 0; i < node.size(); i++) {
                    missingItem(item, node.get(i), path + "[" + i + "]", missing);
                }
            }
        } else if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Map.class) {
            if (node.isObject()) {
                Type item = parameterized.getActualTypeArguments()[1];
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    missingItem(item, property.getValue(), path + "." + property.getKey(), missing);
                }
            }
        } else if (type instanceof Class<?> raw && raw.isRecord() && node.isObject()) {
            missing(raw, node, path + ".", missing);
        }
    }

    private static void missingItem(Type type, JsonNode item, String path, List<String> missing) {
        if (item.isNull()) {
            missing.add(path);
        } else {
            missingIn(type, item, path, missing);
        }
    }

    private static ObjectNode schema(Type type) {
        ObjectNode schema = NODES.objectNode();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
            schema.put("type", "array");
            schema.set("items", schema(parameterized.getActualTypeArguments()[0]));
            return schema;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Map.class) {
            schema.put("type", "object");
            schema.set("additionalProperties", schema(parameterized.getActualTypeArguments()[1]));
            return schema;
        }
        if (!(type instanceof Class<?> raw)) {
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

    /** Whether {@code component} is {@code @Nullable}, also as {@code Outer.@Nullable Inner}. */
    private static boolean nullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }
}
