package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;

/**
 * What {@link Analyzers#build} makes an {@link Analyzer} from (ADR 0014): {@code StandardAnalyzer},
 * a preset analyzer by class name (ADR 0015) or a {@code CustomAnalyzer}.
 */
public sealed interface AnalyzerSpec {

    /** Lucene's {@code StandardAnalyzer}. */
    record Standard() implements AnalyzerSpec {}

    /**
     * An analyzer created with its public no-argument constructor (ADR 0015).
     *
     * @param className the class's fully qualified name, one that {@link Analyzers#components} lists
     */
    record Preset(String className) implements AnalyzerSpec {

        public Preset {
            Objects.requireNonNull(className);
        }
    }

    /**
     * A {@code CustomAnalyzer}: char filters, one tokenizer, then token filters.
     *
     * @param configDir the directory that resource files are read from before the classpath; empty
     *     for the classpath only
     */
    record Custom(
            List<Component> charFilters, Component tokenizer, List<Component> tokenFilters, Optional<Path> configDir)
            implements AnalyzerSpec {

        public Custom {
            charFilters = List.copyOf(charFilters);
            Objects.requireNonNull(tokenizer);
            tokenFilters = List.copyOf(tokenFilters);
            Objects.requireNonNull(configDir);
        }
    }

    /**
     * A char filter, tokenizer or token filter.
     *
     * @param name the factory's SPI name, such as {@code lowercase}; case doesn't matter
     * @param params the factory's parameters
     */
    record Component(String name, Map<String, String> params) {

        public Component {
            Objects.requireNonNull(name);
            params = Map.copyOf(params);
        }
    }
}
