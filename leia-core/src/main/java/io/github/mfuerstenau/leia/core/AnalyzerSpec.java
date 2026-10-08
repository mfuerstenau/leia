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
     * An analyzer created by class name (ADR 0015), with its default settings and, if given, stop
     * words and stem exclusions of its own (ADR 0017).
     *
     * @param className the class's fully qualified name, one that {@link Analyzers#components} lists
     * @param stopwords the stop words; empty for the analyzer's default ones
     * @param stemExclusion the words kept from stemming; empty for none
     */
    record Preset(String className, Optional<WordSet> stopwords, Optional<WordSet> stemExclusion)
            implements AnalyzerSpec {

        public Preset {
            Objects.requireNonNull(className);
            Objects.requireNonNull(stopwords);
            Objects.requireNonNull(stemExclusion);
        }

        /** The analyzer with its default stop words and no stem exclusions. */
        public Preset(String className) {
            this(className, Optional.empty(), Optional.empty());
        }
    }

    /** The words of a preset's stop words or stem exclusions (ADR 0017). */
    sealed interface WordSet {

        /** The words themselves; none for an empty set. */
        record Words(List<String> words) implements WordSet {

            public Words {
                words = List.copyOf(words);
            }
        }

        /**
         * A word list file: UTF-8, one word per line, and lines starting with {@code #} are skipped.
         */
        record File(Path path) implements WordSet {

            public File {
                Objects.requireNonNull(path);
            }
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
