package io.github.mfuerstenau.leia.server.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of a {@code lucene-core} jar, such as {@code 9.10.0}, as a server's {@code lib/} has
 * it (ADR 0090): from its file name, {@code lucene-core-9.10.0.jar}, or else from its manifest.
 *
 * @param version the version, such as {@code 9.10.0} or {@code 9.12.0-snapshot-a1b2c3d}
 * @param major its first number
 */
public record LuceneJarVersion(String version, int major) {

    private static final Pattern FILE_NAME = Pattern.compile("lucene-core-((\\d++)\\.\\d++[^/\\\\]*)\\.jar");

    /** A version at the start of a manifest's value, such as {@code 9.10.0 695c0ac… - 2024-02-14}. */
    private static final Pattern LEADING = Pattern.compile("((\\d+)\\.\\d+(?:\\.\\d+)?)(?:\\s.*)?", Pattern.DOTALL);

    /** Whether {@code fileName} is that of a {@code lucene-core} jar. */
    public static boolean isLuceneCore(String fileName) {
        return fileName.startsWith("lucene-core-") && fileName.endsWith(".jar");
    }

    /** The version in a file name such as {@code lucene-core-9.10.0.jar}; empty if it has none. */
    public static Optional<LuceneJarVersion> fromFileName(String fileName) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return major(matcher.group(2)).map(major -> new LuceneJarVersion(matcher.group(1), major));
    }

    /**
     * The version in a manifest: its {@code Implementation-Version}, as Lucene writes it, or else its
     * {@code Specification-Version}; empty if neither starts with one.
     */
    public static Optional<LuceneJarVersion> fromManifest(Manifest manifest) {
        Attributes attributes = manifest.getMainAttributes();
        return fromValue(attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION))
                .or(() -> fromValue(attributes.getValue(Attributes.Name.SPECIFICATION_VERSION)));
    }

    private static Optional<LuceneJarVersion> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = LEADING.matcher(value.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return major(matcher.group(2)).map(major -> new LuceneJarVersion(matcher.group(1), major));
    }

    private static Optional<Integer> major(String digits) {
        try {
            return Optional.of(Integer.parseInt(digits));
        } catch (NumberFormatException _) {
            // Too many digits.
            return Optional.empty();
        }
    }

    /**
     * The version of the jar at {@code jar}: from its file name, or else its manifest.
     *
     * @throws IOException if the name has none and the jar can't be read
     */
    public static Optional<LuceneJarVersion> of(Path jar) throws IOException {
        Optional<LuceneJarVersion> fromName = fromFileName(jar.getFileName().toString());
        if (fromName.isPresent()) {
            return fromName;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest == null ? Optional.empty() : fromManifest(manifest);
        }
    }
}
