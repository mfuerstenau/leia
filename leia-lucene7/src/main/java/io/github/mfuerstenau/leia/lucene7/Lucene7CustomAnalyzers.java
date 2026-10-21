package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec.Component;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.apache.lucene.analysis.util.ClasspathResourceLoader;
import org.apache.lucene.analysis.util.FilesystemResourceLoader;
import org.apache.lucene.analysis.util.ResourceLoader;
import org.apache.lucene.analysis.util.TokenFilterFactory;
import org.apache.lucene.analysis.util.TokenizerFactory;
import org.apache.lucene.util.Version;

/**
 * The custom analyzers of Lucene 7.7 (ADR 0075), built as leia-core's {@code Analyzers} builds them
 * on Lucene 10, with its messages: the factories are 7.7's own, found through the SPI of its own class loader, and
 * resource files are read from the config directory, then through the runtime's own class loader. The
 * extension jars of {@code --jar} link against Lucene 10, so their factories are not 7.7's.
 */
final class Lucene7CustomAnalyzers {

    private static final Pattern MODULE_ADVICE = Pattern.compile(" \\(if you use Java Module System[^)]*\\)");

    private Lucene7CustomAnalyzers() {}

    /**
     * A new custom analyzer for {@code spec}, which reads its resource files now.
     *
     * @throws UnsupportedAnalyzerException for a component that 7.7 doesn't have
     * @throws InvalidAnalyzerException for an unknown or bad parameter, a config directory that is not a
     *     directory, or a resource file that can't be read or parsed
     */
    static CustomAnalyzer create(AnalyzerSpec.Custom spec)
            throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        CustomAnalyzer.Builder builder = builder(spec);
        for (Component component : spec.charFilters()) {
            add(
                    "char filter",
                    component,
                    CharFilterFactory.availableCharFilters(),
                    () -> builder.addCharFilter(component.name(), new HashMap<>(component.params())));
        }
        Component tokenizer = spec.tokenizer();
        add(
                "tokenizer",
                tokenizer,
                TokenizerFactory.availableTokenizers(),
                () -> builder.withTokenizer(tokenizer.name(), new HashMap<>(tokenizer.params())));
        for (Component component : spec.tokenFilters()) {
            add(
                    "token filter",
                    component,
                    TokenFilterFactory.availableTokenFilters(),
                    () -> builder.addTokenFilter(component.name(), new HashMap<>(component.params())));
        }
        return builder.build();
    }

    /** A builder that reads resource files from the config directory, then through the runtime's loader. */
    private static CustomAnalyzer.Builder builder(AnalyzerSpec.Custom spec) throws InvalidAnalyzerException {
        ResourceLoader classpath = new ClasspathResourceLoader(Lucene7CustomAnalyzers.class.getClassLoader());
        try {
            return CustomAnalyzer.builder(spec.configDir()
                    .<ResourceLoader>map(dir -> new FilesystemResourceLoader(dir, classpath))
                    .orElse(classpath));
        } catch (IllegalArgumentException e) {
            throw new InvalidAnalyzerException("config directory: " + e.getMessage(), e);
        }
    }

    private interface Step {
        void run() throws IOException;
    }

    /** Runs {@code step}, which adds {@code component}, and says which component failed and why. */
    private static void add(String kind, Component component, Set<String> available, Step step)
            throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        String what = kind + " '" + component.name() + "'";
        // The analysis tab offers Lucene 10's components, which 7.7 may lack.
        if (available.stream().noneMatch(component.name()::equalsIgnoreCase)) {
            throw new UnsupportedAnalyzerException("Lucene " + Version.LATEST + " has no " + what);
        }
        try {
            step.run();
        } catch (NoSuchFileException e) {
            throw new InvalidAnalyzerException(what + ": no file " + e.getMessage(), e);
        } catch (IOException | UncheckedIOException | IllegalArgumentException | IllegalStateException e) {
            throw new InvalidAnalyzerException(what + ": " + message(e), e);
        } catch (LinkageError e) {
            throw new InvalidAnalyzerException(what + ": " + e, e);
        }
    }

    private static String message(Exception e) {
        Throwable cause = e instanceof UncheckedIOException unchecked ? unchecked.getCause() : e;
        // The class path loader's advice on modules doesn't apply: the runtime has none of its own.
        return MODULE_ADVICE.matcher(Failures.message(cause)).replaceFirst("");
    }
}
