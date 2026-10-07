package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * A factory that a custom analyzer can name.
 *
 * @param name the SPI name, such as {@code lowercase}
 * @param className the factory class, whose documentation lists the parameters
 */
public record AvailableComponent(String name, String className) {}
