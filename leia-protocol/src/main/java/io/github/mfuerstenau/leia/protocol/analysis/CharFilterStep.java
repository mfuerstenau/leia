package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * The text after a char filter.
 *
 * @param name the char filter's SPI name
 * @param text the text after this char filter and every one before it
 */
public record CharFilterStep(String name, String text) {}
