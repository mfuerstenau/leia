package io.github.mfuerstenau.leia.client.runtimes;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The local caches that may hold a Lucene jar already (ADR 0125): Maven's local repository and
 * Gradle's cache. A jar is taken from one only if it has the size and SHA-256 that leia's build
 * recorded, so it is the same jar as a download, and needs no trust.
 */
public final class LocalCaches {

    /** The system property that turns the caches off with {@code false}. */
    public static final String PROPERTY = "leia.download.local";

    private static final Logger LOG = Logger.getLogger(LocalCaches.class.getName());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(env\\.)?([^}]+)}");

    /** How a cache lays out its jars. */
    public enum Layout {
        /** At their Maven paths. */
        MAVEN,
        /** {@code <group>/<artifact>/<version>/<sha1>/<file>}. */
        GRADLE
    }

    /**
     * A cache.
     *
     * @param name what leia calls it, such as {@code Gradle's cache}
     * @param root its folder
     * @param layout how it lays out its jars
     */
    public record Cache(String name, Path root, Layout layout) {}

    /**
     * A jar in a cache that matches its list.
     *
     * @param cache the cache
     * @param file the jar
     */
    public record Local(Cache cache, Path file) {}

    private final List<Cache> caches;

    LocalCaches(List<Cache> caches) {
        this.caches = List.copyOf(caches);
    }

    /** No caches: every jar is downloaded. */
    public static LocalCaches none() {
        return new LocalCaches(List.of());
    }

    /** The caches, in the order they are searched. */
    public List<Cache> caches() {
        return caches;
    }

    /**
     * The caches that exist: Maven's local repository ({@code -Dmaven.repo.local}, else {@code
     * <localRepository>} of {@code ~/.m2/settings.xml}, else {@code ~/.m2/repository}), then Gradle's
     * cache ({@code $GRADLE_USER_HOME}, else {@code ~/.gradle}). None with {@code
     * -Dleia.download.local=false}.
     *
     * @param env the environment, such as {@code System::getenv}
     * @param properties the system properties, such as {@code System::getProperty}
     * @param home the user's home folder, or null
     */
    public static LocalCaches detect(
            Function<String, @Nullable String> env,
            Function<String, @Nullable String> properties,
            @Nullable Path home) {
        if ("false".equalsIgnoreCase(properties.apply(PROPERTY))) {
            return none();
        }
        List<Cache> caches = new ArrayList<>();
        maven(env, properties, home)
                .filter(Files::isDirectory)
                .ifPresent(root -> caches.add(new Cache("Maven's local repository", root, Layout.MAVEN)));
        Optional.ofNullable(env.apply("GRADLE_USER_HOME"))
                .filter(value -> !value.isBlank())
                .flatMap(LocalCaches::path)
                .or(() -> Optional.ofNullable(home).map(dir -> dir.resolve(".gradle")))
                .map(dir -> dir.resolve("caches/modules-2/files-2.1"))
                .filter(Files::isDirectory)
                .ifPresent(root -> caches.add(new Cache("Gradle's cache", root, Layout.GRADLE)));
        return new LocalCaches(caches);
    }

    private static Optional<Path> maven(
            Function<String, @Nullable String> env,
            Function<String, @Nullable String> properties,
            @Nullable Path home) {
        String property = properties.apply("maven.repo.local");
        if (property != null && !property.isBlank()) {
            return path(property);
        }
        Optional<Path> fromSettings = Optional.ofNullable(home)
                .map(dir -> dir.resolve(".m2/settings.xml"))
                .filter(Files::isRegularFile)
                .flatMap(settings -> localRepository(settings, env, properties, home));
        return fromSettings.or(() -> Optional.ofNullable(home).map(dir -> dir.resolve(".m2/repository")));
    }

    /** The {@code <localRepository>} of a Maven {@code settings.xml}, its placeholders expanded. */
    static Optional<Path> localRepository(
            Path settings,
            Function<String, @Nullable String> env,
            Function<String, @Nullable String> properties,
            @Nullable Path home) {
        try (InputStream in = Files.newInputStream(settings)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Quiet: the default prints to stderr, which belongs to the UI (AGENTS.md rule 4).
            builder.setErrorHandler(new DefaultHandler());
            NodeList nodes = builder.parse(in).getDocumentElement().getElementsByTagNameNS("*", "localRepository");
            if (nodes.getLength() == 0) {
                return Optional.empty();
            }
            String value = nodes.item(0).getTextContent().strip();
            return value.isEmpty() ? Optional.empty() : path(expand(value, env, properties, home));
        } catch (IOException | ParserConfigurationException | SAXException e) {
            LOG.info(() -> "Cannot read the local repository of " + settings + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code ${user.home}}, {@code ${env.X}} and other system properties in {@code value}. */
    private static String expand(
            String value,
            Function<String, @Nullable String> env,
            Function<String, @Nullable String> properties,
            @Nullable Path home) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(2);
            String replacement;
            if (matcher.group(1) != null) {
                replacement = env.apply(name);
            } else if (name.equals("user.home")) {
                replacement = home == null ? null : home.toString();
            } else {
                replacement = properties.apply(name);
            }
            matcher.appendReplacement(
                    expanded, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static Optional<Path> path(String value) {
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException _) {
            return Optional.empty();
        }
    }

    /**
     * The first copy of {@code jar}, at {@code mavenPath}, in the caches that has its size and
     * SHA-256. Reads the copies; empty if none matches.
     */
    public Optional<Local> find(String mavenPath, RuntimeFolders.ListedJar jar) {
        for (Cache cache : caches) {
            for (Path candidate : candidates(cache, mavenPath)) {
                if (matches(candidate, jar)) {
                    return Optional.of(new Local(cache, candidate));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether {@code file} is a regular file with {@code jar}'s size and SHA-256. */
    static boolean matches(Path file, RuntimeFolders.ListedJar jar) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != jar.size()) {
                return false;
            }
            boolean same = RuntimeInstall.sha256(file).equals(jar.sha256());
            if (!same) {
                LOG.fine(() -> "A copy of " + jar.name() + " that doesn't match: " + file);
            }
            return same;
        } catch (IOException | RuntimeInstall.InstallException _) {
            return false;
        }
    }

    private static List<Path> candidates(Cache cache, String mavenPath) {
        if (cache.layout() == Layout.MAVEN) {
            return List.of(cache.root().resolve(mavenPath));
        }
        String[] parts = mavenPath.split("/");
        if (parts.length < 4) {
            return List.of();
        }
        String file = parts[parts.length - 1];
        String version = parts[parts.length - 2];
        String artifact = parts[parts.length - 3];
        String group = String.join(".", List.of(parts).subList(0, parts.length - 3));
        Path versionDir = cache.root().resolve(group).resolve(artifact).resolve(version);
        if (!Files.isDirectory(versionDir)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> hashes = Files.newDirectoryStream(versionDir, Files::isDirectory)) {
            hashes.forEach(hash -> found.add(hash.resolve(file)));
        } catch (IOException _) {
            // A cache that can't be read has nothing to give.
        }
        found.sort(null);
        return found;
    }
}
