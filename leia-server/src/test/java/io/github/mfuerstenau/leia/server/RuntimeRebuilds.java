package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rebuild of a document on an index of the runtimes' writers (ADR 0059, ADR 0062): documents 1
 * to 7 with a {@code body} "alpha … document <id> …" with term vectors that cover its postings, an
 * {@code id} of docs only (not analyzed, with positions, on Lucene 3) and a {@code tag} (even or odd)
 * of docs only, from Lucene 4 on a stored-only {@code blob}, and from Lucene 6 on 15 {@code pt}
 * points each; document 1, id 2, is deleted.
 */
final class RuntimeRebuilds {

    private RuntimeRebuilds() {}

    /**
     * Rebuilds the deleted document 1 from its term vector, its postings and, with {@code points},
     * its points, with the total it goes through if {@code total}; every rebuildable field without
     * fields given; a missing field, a field with nothing to rebuild and a document id out of range
     * fail.
     */
    static void rebuildsADocument(RealBackend backend, int maxDoc, boolean points, boolean total) throws Exception {
        List<String> names = new ArrayList<>(List.of("body", "id", "tag"));
        if (points) {
            names.add("pt");
        }
        GetReconstructResult done = backend.job(
                Methods.START_RECONSTRUCT,
                new StartReconstructParams(1, Optional.of(names)),
                Methods.GET_RECONSTRUCT,
                result -> result.result().isPresent() ? Optional.of(result) : Optional.empty());
        // The term vector of body is read whole: only id, tag and pt are gone through.
        if (total) {
            assertThat(done.total()).isPresent();
            assertThat(done.scanned()).isEqualTo(done.total().getAsLong());
        } else {
            assertThat(done.total()).isEmpty();
            assertThat(done.scanned()).isPositive();
        }
        List<ReconstructedField> fields = done.result().orElseThrow().fields();
        List<IndexField> indexFields =
                backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams()).fields();
        assertThat(fields).extracting(ReconstructedField::field).containsExactlyElementsOf(names);

        ReconstructedField body = fields.get(0);
        assertThat(body.source()).isEqualTo(ReconstructSource.TERM_VECTOR);
        assertThat(body.indexOptions()).isEqualTo(indexOptions(indexFields, "body"));
        assertThat(body.terms()).extracting(TermVectorTerm::term).contains("2", "alpha", "document");
        assertThat(body.terms())
                .filteredOn(term -> term.term().equals("alpha"))
                .singleElement()
                .satisfies(alpha -> assertThat(alpha.positions())
                        .singleElement()
                        .satisfies(position -> {
                            assertThat(position.position()).hasValue(0);
                            assertThat(position.startOffset()).hasValue(0);
                        }));

        ReconstructedField id = fields.get(1);
        assertThat(id.source()).isEqualTo(ReconstructSource.POSTINGS);
        assertThat(id.indexOptions()).isEqualTo(indexOptions(indexFields, "id"));
        assertThat(id.terms()).singleElement().satisfies(term -> {
            assertThat(term.term()).isEqualTo("2");
            assertThat(term.freq()).isEqualTo(1);
            assertThat(term.positions()).hasSize(id.indexOptions() == IndexOptions.DOCS ? 0 : 1);
        });
        assertThat(fields.get(2).terms()).extracting(TermVectorTerm::term).containsExactly("even");
        if (points) {
            ReconstructedField pt = fields.get(3);
            assertThat(pt.indexOptions()).isEqualTo(IndexOptions.NONE);
            assertThat(pt.terms()).isEmpty();
            assertThat(pt.points())
                    .hasSize(15)
                    .allSatisfy(point -> assertThat(point.dimensions()).hasSize(1));
        }

        ReconstructResult all = backend.reconstruct(0, null);
        assertThat(all.fields())
                .extracting(ReconstructedField::field)
                .containsExactlyElementsOf(indexFields.stream()
                        .filter(field -> field.indexOptions() != IndexOptions.NONE || field.pointDimensionCount() > 0)
                        .map(IndexField::name)
                        .toList())
                .contains("body", "id")
                .doesNotContain("blob");
        boolean blob = indexFields.stream().anyMatch(field -> field.name().equals("blob"));

        backend.fails(
                Methods.START_RECONSTRUCT,
                new StartReconstructParams(0, Optional.of(List.of("nope"))),
                ErrorCode.FIELD_NOT_FOUND);
        if (blob) {
            backend.fails(
                    Methods.START_RECONSTRUCT,
                    new StartReconstructParams(0, Optional.of(List.of("blob"))),
                    ErrorCode.INVALID_PARAMS);
        }
        backend.fails(
                Methods.START_RECONSTRUCT,
                new StartReconstructParams(maxDoc, Optional.empty()),
                ErrorCode.DOC_NOT_FOUND);
    }

    private static IndexOptions indexOptions(List<IndexField> fields, String name) {
        return fields.stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow()
                .indexOptions();
    }
}
