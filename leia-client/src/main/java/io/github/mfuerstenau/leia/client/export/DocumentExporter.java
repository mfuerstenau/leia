package io.github.mfuerstenau.leia.client.export;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Exports documents as JSON (ADR 0030, ADR 0126): each export fetches the document with every
 * stored value whole and its term vectors, rebuilds its indexed terms and points if asked, puts it
 * together and writes it to a file, checked as the backend checks a terms export (ADR 0022), or
 * hands over the text, for a clipboard. Each runs as an {@link ExportJob}, which a UI polls.
 */
public final class DocumentExporter {

    /** How often a rebuild is asked how far it is. */
    private static final Duration POLL = Duration.ofMillis(100);

    /** The calls an export makes: to the backend, or a test's. */
    public interface Calls extends DocumentExport.Fetch {

        /** {@code startReconstruct} of every field; the job's id. */
        CompletionStage<Long> startReconstruct(int docId);

        /** {@code getReconstruct}. */
        CompletionStage<GetReconstructResult> reconstruct(long jobId);

        /** {@code cancelJob}. */
        CompletionStage<Void> cancelJob(long jobId);

        /** The calls to {@code backend}. */
        static Calls of(LeiaBackend backend) {
            return new Calls() {
                @Override
                public CompletionStage<GetDocumentResult> document(int docId) {
                    return backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(docId));
                }

                @Override
                public CompletionStage<StoredValue> storedValue(int docId, String field, int index) {
                    return backend.call(Methods.GET_STORED_VALUE, new GetStoredValueParams(docId, field, index))
                            .thenApply(GetStoredValueResult::value);
                }

                @Override
                public CompletionStage<List<TermVectorTerm>> termVector(int docId, String field) {
                    return backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(docId, field))
                            .thenApply(GetTermVectorResult::terms);
                }

                @Override
                public CompletionStage<Long> startReconstruct(int docId) {
                    return backend.call(Methods.START_RECONSTRUCT, new StartReconstructParams(docId, Optional.empty()))
                            .thenApply(started -> started.jobId());
                }

                @Override
                public CompletionStage<GetReconstructResult> reconstruct(long jobId) {
                    return backend.call(Methods.GET_RECONSTRUCT, new JobParams(jobId));
                }

                @Override
                public CompletionStage<Void> cancelJob(long jobId) {
                    return backend.call(Methods.CANCEL_JOB, new JobParams(jobId))
                            .thenAccept(_ -> {});
                }
            };
        }
    }

    /** Where an export goes. */
    public sealed interface Target {

        /**
         * A file, never in or below one of {@code indexDirs} (rule 1).
         *
         * @param path absolute
         * @param overwrite whether a file that is there may be replaced
         * @param indexDirs the index directories of the open path, opened or skipped
         */
        record File(Path path, boolean overwrite, List<String> indexDirs) implements Target {

            public File {
                indexDirs = List.copyOf(indexDirs);
            }
        }

        /** The text, in {@link ExportJob.Snapshot#text}, such as for a clipboard. */
        record Text() implements Target {}
    }

    /**
     * An export.
     *
     * @param termVectors whether the open index has term vectors to fetch (ADR 0044)
     * @param indexedTerms whether to rebuild the indexed terms and points (ADR 0029, ADR 0041)
     * @param schema the field of the index with a name, for the flags, number and index options
     * @param index the path that was opened
     * @param indexDirectory the index directory the document is in, as shown; empty with only one
     */
    public record Request(
            int docId,
            boolean termVectors,
            boolean indexedTerms,
            Function<String, Optional<IndexField>> schema,
            String index,
            Optional<String> indexDirectory,
            Target target) {}

    private final Calls calls;
    private final Duration poll;

    public DocumentExporter(Calls calls) {
        this(calls, POLL);
    }

    /** @param poll how often a rebuild is asked how far it is */
    public DocumentExporter(Calls calls, Duration poll) {
        this.calls = calls;
        this.poll = poll;
    }

    /** Starts the export of {@code request}. */
    public ExportJob start(Request request) {
        return ExportJob.start(calls, request, poll);
    }
}
