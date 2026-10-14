package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.AnalyzerSpec.Collation;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.CollationDecomposition;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.CollationStrength;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.Collator;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;

/**
 * The collators of the collation analyzers (ADR 0038): an analyzer with a {@code
 * (java.text.Collator)} constructor, such as {@code CollationKeyAnalyzer}, takes the JDK's, and one
 * with a {@code (com.ibm.icu.text.Collator)} constructor, such as {@code ICUCollationKeyAnalyzer},
 * takes ICU's. ICU's module is an extension jar (ADR 0016), so its types are found by name, through
 * the analyzer class's own loader.
 */
final class Collations {

    private static final String ICU_COLLATOR = "com.ibm.icu.text.Collator";
    private static final String ICU_LOCALE = "com.ibm.icu.util.ULocale";

    private Collations() {}

    /** The public {@code (java.text.Collator)} or {@code (com.ibm.icu.text.Collator)} constructor, if there is one. */
    static Optional<Constructor<? extends Analyzer>> constructor(Class<? extends Analyzer> type) {
        try {
            return Optional.of(type.getConstructor(Collator.class));
        } catch (NoSuchMethodException | LinkageError e) {
            // Not the JDK's; maybe ICU's.
        }
        try {
            return Optional.of(type.getConstructor(Class.forName(ICU_COLLATOR, false, type.getClassLoader())));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * The collator for {@code collation} that {@code constructor} takes.
     *
     * @throws InvalidAnalyzerException if the locale is not a well-formed language tag, or the
     *     collator doesn't have the strength or the decomposition
     */
    static Object collator(Constructor<? extends Analyzer> constructor, Collation collation)
            throws InvalidAnalyzerException {
        Locale locale = locale(collation.locale());
        Class<?> type = constructor.getParameterTypes()[0];
        return type == Collator.class ? jdk(locale, collation) : icu(type, collation);
    }

    private static Locale locale(String tag) throws InvalidAnalyzerException {
        if (tag.isEmpty()) {
            return Locale.ROOT;
        }
        try {
            return new Locale.Builder().setLanguageTag(tag).build();
        } catch (IllformedLocaleException e) {
            throw new InvalidAnalyzerException("collation: '" + tag + "' is not a language tag: " + e.getMessage(), e);
        }
    }

    private static Collator jdk(Locale locale, Collation collation) throws InvalidAnalyzerException {
        Collator collator = Collator.getInstance(locale);
        if (collation.strength().isPresent()) {
            collator.setStrength(
                    switch (collation.strength().orElseThrow()) {
                        case PRIMARY -> Collator.PRIMARY;
                        case SECONDARY -> Collator.SECONDARY;
                        case TERTIARY -> Collator.TERTIARY;
                        case IDENTICAL -> Collator.IDENTICAL;
                        case QUATERNARY ->
                            throw new InvalidAnalyzerException(
                                    "collation: the JDK's collator has no strength QUATERNARY", null);
                    });
        }
        if (collation.decomposition().isPresent()) {
            collator.setDecomposition(
                    switch (collation.decomposition().orElseThrow()) {
                        case NO -> Collator.NO_DECOMPOSITION;
                        case CANONICAL -> Collator.CANONICAL_DECOMPOSITION;
                        case FULL -> Collator.FULL_DECOMPOSITION;
                    });
        }
        return collator;
    }

    /** ICU's collator for the language tag, with its {@code -u-} keywords. */
    private static Object icu(Class<?> collatorType, Collation collation) throws InvalidAnalyzerException {
        if (collation.decomposition().orElse(null) == CollationDecomposition.FULL) {
            throw new InvalidAnalyzerException("collation: ICU's collator has no decomposition FULL", null);
        }
        try {
            Class<?> localeType = Class.forName(ICU_LOCALE, true, collatorType.getClassLoader());
            Object locale = localeType.getMethod("forLanguageTag", String.class).invoke(null, collation.locale());
            Object collator = collatorType.getMethod("getInstance", localeType).invoke(null, locale);
            if (collation.strength().isPresent()) {
                CollationStrength strength = collation.strength().orElseThrow();
                set(collatorType, collator, "setStrength", strength.name());
            }
            if (collation.decomposition().isPresent()) {
                CollationDecomposition decomposition = collation.decomposition().orElseThrow();
                set(collatorType, collator, "setDecomposition", decomposition.name() + "_DECOMPOSITION");
            }
            return collator;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new InvalidAnalyzerException("collation: " + message, cause);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new InvalidAnalyzerException("collation: " + e, e);
        }
    }

    /** Calls the setter with the value of the collator type's static {@code int} field {@code constant}. */
    private static void set(Class<?> collatorType, Object collator, String setter, String constant)
            throws ReflectiveOperationException {
        int value = collatorType.getField(constant).getInt(null);
        Method method = collatorType.getMethod(setter, int.class);
        method.invoke(collator, value);
    }
}
