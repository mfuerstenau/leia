package io.github.mfuerstenau.leia.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * The Jackson configuration of the wire format (ADR 0001, ADR 0003).
 *
 * <ul>
 *   <li>Unknown properties are rejected.
 *   <li>Record components of type {@code Optional*} are optional: omitted when empty, and accepted
 *       when missing or {@code null}. All other components are required and must not be {@code
 *       null}.
 *   <li>Scalars are not coerced: a string or a fraction is not accepted as a number, and a number
 *       or a boolean is not accepted as a string.
 *   <li>Properties are written in declaration order.
 * </ul>
 */
public final class ProtocolJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .annotationIntrospector(new ProtocolAnnotationIntrospector())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // No scalar coercion, as in serde: "2" and 2.5 are not a long, 7 and true are not a String.
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(
                    LogicalType.Textual,
                    config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_ABSENT))
            .build();

    private ProtocolJson() {}

    /** The shared, immutable mapper for all protocol messages. */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** {@code Request<P>} for the params type of {@code method}. */
    public static JavaType requestType(RpcMethod<?, ?> method) {
        return MAPPER.getTypeFactory().constructParametricType(Request.class, method.paramsType());
    }

    /** {@code Response<R>} for the result type of {@code method}. */
    public static JavaType responseType(RpcMethod<?, ?> method) {
        return responseType(method.resultType());
    }

    /** {@code Response<R>} for {@code resultType}. */
    public static JavaType responseType(Class<?> resultType) {
        return MAPPER.getTypeFactory().constructParametricType(Response.class, resultType);
    }
}
