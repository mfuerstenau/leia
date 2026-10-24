package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex.Field;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex.Vector;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex.VectorFormat;
import io.github.mfuerstenau.leia.runtime.Search.ParsedQuery;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.KnnByteVectorQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;

/**
 * The KNN vectors of a {@link Lucene9Index}, through the KNN queries and vector values of 9.5: a
 * class of its own, loaded only where {@link Lucene9Api#KNN} says the runtime's Lucene has them
 * (ADR 0091).
 */
final class Lucene9Knn implements Knn {

    private final Lucene9Index index;

    Lucene9Knn(Lucene9Index index) {
        this.index = index;
    }

    /** {@inheritDoc} Lucene 9.12's KNN query of the field's encoding; it searches when rewritten. */
    @Override
    public ParsedQuery approximate(String field, Vector target, int k, Optional<ParsedQuery> filter) {
        index.vectorField(field);
        Query filterQuery = filter.map(Lucene9Index::query).orElse(null);
        return new Lucene9Index.Lucene9Query(
                switch (target) {
                    case Vector.Floats floats -> new KnnFloatVectorQuery(field, floats.values(), k, filterQuery);
                    case Vector.Bytes bytes -> new KnnByteVectorQuery(field, bytes.values(), k, filterQuery);
                });
    }

    @Override
    public ParsedQuery exact(String field, Vector target, Optional<ParsedQuery> filter) {
        index.vectorField(field);
        return new Lucene9Index.Lucene9Query(new ExactKnnQuery(field, target, filter.map(Lucene9Index::query)));
    }

    @Override
    public List<Integer> sample(String field, int n) throws IOException {
        return new Lucene9KnnGraph(index.reader(), index.fieldInfos()).sample(field, n);
    }

    @Override
    public Optional<Vector> vector(int docId, String field) throws IOException {
        index.checkDoc(docId);
        Field info = index.vectorField(field);
        LeafReaderContext leaf = index.leaf(docId);
        return vector(leaf.reader(), docId - leaf.docBase, info);
    }

    /**
     * The KNN vector of {@code doc} of {@code leaf} in {@code field}, a copy, read through a fresh
     * iterator as doc values are; empty if it has none, as a document of a segment of 8.x never has.
     */
    static Optional<Vector> vector(LeafReader leaf, int doc, Field field) throws IOException {
        Optional<VectorFormat> format = field.vectors();
        if (format.isEmpty()) {
            return Optional.empty();
        }
        FieldInfo info = leaf.getFieldInfos().fieldInfo(field.name());
        if (info == null || info.getVectorDimension() == 0) {
            return Optional.empty();
        }
        return switch (format.get().encoding()) {
            case FLOAT32 -> {
                FloatVectorValues values = leaf.getFloatVectorValues(field.name());
                yield values == null || values.advance(doc) != doc
                        ? Optional.empty()
                        : Optional.of(new Vector.Floats(values.vectorValue().clone()));
            }
            case BYTE -> {
                ByteVectorValues values = leaf.getByteVectorValues(field.name());
                yield values == null || values.advance(doc) != doc
                        ? Optional.empty()
                        : Optional.of(new Vector.Bytes(values.vectorValue().clone()));
            }
        };
    }
}
