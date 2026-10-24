package io.github.mfuerstenau.leia.lucene9;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * What the Lucene 9 in this runtime's class loader has of the APIs newer than 9.0 that the adapter
 * uses (ADR 0091). The adapter is built for 9.12.3, but a server's {@code lib/} can have any 9.x
 * (ADR 0090), so the classes that every index loads ({@link Lucene9Index}, {@link Lucene9Commits},
 * {@link Lucene9Check}) name none of these APIs where the JVM would have to load them, and each
 * flag here says whether the code behind it can run. A feature whose flag is false is left out of
 * the index, as on an index without its data.
 *
 * <p>Each flag is found once, by the presence of a class or method, not by Lucene's version, so it
 * holds for any minor. Only reflection: this class names no Lucene type.
 */
final class Lucene9Api {

    /** {@code IndexReader.storedFields()} and {@code termVectors()}, 9.5. */
    static final boolean STORED_FIELDS = hasMethod("org.apache.lucene.index.IndexReader", "storedFields");

    /** The KNN queries and vector values of a float or a byte vector, 9.5. */
    static final boolean KNN = hasClass("org.apache.lucene.search.KnnFloatVectorQuery")
            && hasClass("org.apache.lucene.index.FloatVectorValues");

    /** {@code FieldInfo.getVectorEncoding()}, 9.4; before it every vector is of floats. */
    static final boolean VECTOR_ENCODING = hasMethod("org.apache.lucene.index.FieldInfo", "getVectorEncoding");

    /** {@code SortedSetDocValues.docValueCount()}, not in 9.0. */
    static final boolean DOC_VALUE_COUNT = hasMethod("org.apache.lucene.index.SortedSetDocValues", "docValueCount");

    /** {@code IndexSearcher.setTimeout(QueryTimeout)}, not in 9.0. */
    static final boolean SEARCH_TIMEOUT = hasMethod("org.apache.lucene.search.IndexSearcher", "setTimeout");

    /** {@code PointValues.PointTree}, not in 9.0. */
    static final boolean POINT_TREE = hasClass("org.apache.lucene.index.PointValues$PointTree");

    /**
     * {@code UnifiedHighlighter.Builder} (not in 9.0) and the constructor of {@code FieldHighlighter}
     * with the order of the passages (not in 9.4), which {@link Lucene9Highlight} extends.
     */
    static final boolean HIGHLIGHTER = hasClass("org.apache.lucene.search.uhighlight.UnifiedHighlighter$Builder")
            && hasConstructor("org.apache.lucene.search.uhighlight.FieldHighlighter", 8);

    /** {@code SegmentInfo.getHasBlocks()}, 9.9. */
    static final boolean HAS_BLOCKS = hasMethod("org.apache.lucene.index.SegmentInfo", "getHasBlocks");

    private Lucene9Api() {}

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, Lucene9Api.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static boolean hasClass(String name) {
        return find(name) != null;
    }

    private static boolean hasMethod(String className, String method) {
        Class<?> type = find(className);
        return type != null
                && Arrays.stream(type.getMethods()).map(Method::getName).anyMatch(method::equals);
    }

    private static boolean hasConstructor(String className, int parameters) {
        Class<?> type = find(className);
        return type != null
                && Arrays.stream(type.getDeclaredConstructors())
                        .mapToInt(Constructor::getParameterCount)
                        .anyMatch(count -> count == parameters);
    }
}
