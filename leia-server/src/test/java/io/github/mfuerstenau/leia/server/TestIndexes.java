package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.lucene10.Lucene10Runtime;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.index.IndexFinder;
import io.github.mfuerstenau.leia.server.runtime.OpenDirectories;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Opens an index for the handler tests as {@code openIndex} would, through leia-lucene10's {@link
 * Lucene10Runtime} (ADR 0071), without going through the handler.
 */
public final class TestIndexes {

    private static final LuceneRuntime LUCENE_10 = new Lucene10Runtime();

    private TestIndexes() {}

    /** The indexes at or below {@code path}, with a reader, through {@link DirectoryKind#FS}. */
    public static OpenRuntime open(Path path) throws RpcException, IOException {
        Path root = IndexFinder.root(path);
        List<Path> dirs = IndexFinder.find(root);
        RuntimeIndex index = LUCENE_10.open(dirs, DirectoryKind.FS);
        return new OpenRuntime(root, LUCENE_10, index, index.skipped());
    }

    /** The indexes at or below {@code path}, without a reader. */
    public static OpenDirectories withoutReader(Path path, DirectoryKind kind) throws RpcException {
        Path root = IndexFinder.root(path);
        return new OpenDirectories(root, IndexFinder.find(root), kind);
    }

    /** Whether the reader of {@code index} is open: a closed one fails to read. */
    public static boolean readable(OpenRuntime index) {
        try {
            index.index().stats();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The features of an index with a reader and without doc values, points or KNN vectors, which
     * depend on the data (ADR 0071).
     */
    public static Set<Feature> featuresWithoutData() {
        Set<Feature> features = EnumSet.copyOf(Feature.all());
        features.removeAll(List.of(Feature.DOC_VALUES, Feature.POINTS, Feature.KNN_VECTORS, Feature.KNN_GRAPH));
        return features;
    }
}
