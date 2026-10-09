package io.github.mfuerstenau.leia.core;

import java.util.Map;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenStream;

/**
 * A token filter factory that {@link ExtensionJarsTest} registers through a jar's service file
 * (ADR 0024). Without that file, Lucene doesn't know it.
 */
public final class JarTestFilterFactory extends TokenFilterFactory {

    public static final String NAME = "leiaJarTest";

    public JarTestFilterFactory(Map<String, String> args) {
        super(args);
    }

    /** For the SPI. */
    public JarTestFilterFactory() {
        throw defaultCtorException();
    }

    @Override
    public TokenStream create(TokenStream input) {
        return input;
    }

    /** The factory of the jar that fails to load, so that its name is never taken. */
    public static final class Other extends TokenFilterFactory {

        public static final String NAME = "leiaJarTestOther";

        public Other(Map<String, String> args) {
            super(args);
        }

        /** For the SPI. */
        public Other() {
            throw defaultCtorException();
        }

        @Override
        public TokenStream create(TokenStream input) {
            return input;
        }
    }
}
