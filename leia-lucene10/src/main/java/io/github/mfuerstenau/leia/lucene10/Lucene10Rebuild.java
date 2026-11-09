package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongConsumer;

/** The rebuild of a document's terms and points by leia-core's {@code Reconstruct} (ADR 0029, ADR 0065). */
final class Lucene10Rebuild implements Rebuild {

    private final ReadOnlyIndex index;

    Lucene10Rebuild(ReadOnlyIndex index) {
        this.index = index;
    }

    @Override
    public OptionalLong count(int docId, List<String> fields) throws IOException {
        return index.reconstructCount(docId, fields);
    }

    @Override
    public List<RebuiltField> rebuild(int docId, List<String> fields, LongConsumer progress) throws IOException {
        return index.reconstruct(docId, fields, progress::accept).stream()
                .map(field -> new RebuiltField(
                        field.field(),
                        RuntimeIndex.IndexOptions.valueOf(field.indexOptions().name()),
                        Source.valueOf(field.source().name()),
                        field.terms().stream().map(Lucene10Index::vectorTerm).toList(),
                        field.points().stream().map(Lucene10Index::bytes).toList()))
                .toList();
    }
}
