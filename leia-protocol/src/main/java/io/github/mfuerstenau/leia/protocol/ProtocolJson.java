package io.github.mfuerstenau.leia.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Jackson configuration of the wire format (ADR 0001, ADR 0003).
 *
 * <ul>
 *   <li>Unknown properties are rejected.
 *   <li>Record components of type {@code Optional*} are optional: omitted when empty, and accepted
 *       when missing or {@code null}. All other components are required and must not be {@code
 *       null}.
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
