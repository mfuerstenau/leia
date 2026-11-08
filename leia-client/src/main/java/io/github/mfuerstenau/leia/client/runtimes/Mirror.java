package io.github.mfuerstenau.leia.client.runtimes;

import java.net.URI;

/**
 * A repository that serves the Lucene jars of Maven Central unchanged, at their Maven paths (ADR
 * 0124). Every jar is checked against the SHA-256 and size that leia's build recorded, so a mirror
 * needs no trust.
 *
 * @param uri its base, ending in {@code /}
 * @param name what leia calls it, such as {@code Google Cloud, EU}
 * @param origin where leia learnt of it
 */
public record Mirror(URI uri, String name, Origin origin) {

    /** Where leia learnt of a mirror, in the order that {@link Mirrors#resolve} tries them. */
    public enum Origin {
        /** {@code -Dleia.download.url}. */
        PROPERTY,
        /** {@code --download-url}. */
        OPTION,
        /** The mirror the user chose last in the window of {@code D}. */
        SAVED,
        /** A line of the user's {@code mirrors} file. */
        FILE,
        /** leia's own list. */
        BUILT_IN,
        /** Typed into the window of {@code D}. */
        TYPED
    }

    public Mirror {
        uri = normalized(uri);
    }

    /** A mirror named after its host. */
    public static Mirror of(URI uri, Origin origin) {
        return new Mirror(uri, uri.getHost() == null ? uri.toString() : uri.getHost(), origin);
    }

    /** Where the jar of {@code mavenPath} is on this mirror. */
    public URI resolve(String mavenPath) {
        return uri.resolve(mavenPath);
    }

    /** Such as {@code Google Cloud, EU (https://…/maven2/)}. */
    public String label() {
        return name + " (" + uri + ")";
    }

    /** {@code uri} ending in {@code /}, so that a Maven path resolves below it. */
    static URI normalized(URI uri) {
        return uri.toString().endsWith("/") ? uri : URI.create(uri + "/");
    }
}
