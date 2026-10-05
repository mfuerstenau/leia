package io.github.mfuerstenau.leia.protocol;

import java.io.Serial;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.AnnotatedParameter;
import tools.jackson.databind.introspect.JacksonAnnotationIntrospector;

/**
 * Marks every creator parameter (record component) as required unless its type is one of the
 * {@code Optional} types. {@code FAIL_ON_MISSING_CREATOR_PROPERTIES} can't do this, because it also
 * fails on a missing {@code Optional}.
 */
final class ProtocolAnnotationIntrospector extends JacksonAnnotationIntrospector {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Set<Class<?>> OPTIONAL_TYPES =
            Set.of(Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class);

    @Override
    public Boolean hasRequiredMarker(MapperConfig<?> config, AnnotatedMember member) {
        Boolean explicit = super.hasRequiredMarker(config, member);
        if (explicit == null && member instanceof AnnotatedParameter parameter) {
            return !OPTIONAL_TYPES.contains(parameter.getRawType());
        }
        return explicit;
    }
}
