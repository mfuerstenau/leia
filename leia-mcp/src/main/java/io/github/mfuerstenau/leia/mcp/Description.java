package io.github.mfuerstenau.leia.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The description of a tool record's component, which {@link Schemas} puts into its JSON Schema (ADR 0100). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Description {

    String value();
}
