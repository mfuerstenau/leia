package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * One value of a token's attribute, as Lucene's {@code AttributeImpl.reflectWith} reports it.
 *
 * @param attribute the attribute interface's simple name, such as {@code KeywordAttribute}
 * @param key the value's name, such as {@code keyword}
 * @param value Lucene's {@code toString} of the value, such as {@code [66 6f 78]} for bytes
 */
public record TokenAttribute(String attribute, String key, String value) {}
