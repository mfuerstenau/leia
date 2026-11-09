package io.github.mfuerstenau.leia.client.export;

import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * The calls of an export as a test has them (ADR 0126): a document whose stored values are whole,
 * no term vectors, and a rebuild that runs until {@link #rebuilt} is set.
 */
public final class FakeExportCalls implements DocumentExporter.Calls {

    /** What {@code getDocument} answers. */
    public volatile CompletionStage<GetDocumentResult> document;

    /** What {@code startReconstruct} answers: the job's id. */
    public volatile CompletableFuture<Long> started = CompletableFuture.completedFuture(7L);

    /** What {@code getReconstruct} answers: running, until a test sets the rebuilt fields. */
    public volatile GetReconstructResult reconstruct = running(5, 10);

    /** If set, what {@code getReconstruct} answers instead of {@link #reconstruct}, such as a failure. */
    public volatile @Nullable CompletableFuture<GetReconstructResult> reconstructCall;

    /** The jobs that were cancelled. */
    public final List<Long> cancelled = new CopyOnWriteArrayList<>();

    /** Completes with the job that was cancelled first. */
    public final CompletableFuture<Long> firstCancelled = new CompletableFuture<>();

    public FakeExportCalls(GetDocumentResult document) {
        this.document = CompletableFuture.completedFuture(document);
    }

    /** A rebuild that went through {@code scanned} of {@code total}. */
    public static GetReconstructResult running(long scanned, long total) {
        return new GetReconstructResult(scanned, OptionalLong.of(total), Optional.empty());
    }

    /** The rebuild is done, as {@code result} says. */
    public void rebuilt(GetReconstructResult result) {
        reconstruct = result;
    }

    @Override
    public CompletionStage<GetDocumentResult> document(int docId) {
        return document;
    }

    @Override
    public CompletionStage<StoredValue> storedValue(int docId, String field, int index) {
        return CompletableFuture.failedFuture(new AssertionError("no stored value is cut"));
    }

    @Override
    public CompletionStage<List<TermVectorTerm>> termVector(int docId, String field) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletionStage<Long> startReconstruct(int docId) {
        return started;
    }

    @Override
    public CompletionStage<GetReconstructResult> reconstruct(long jobId) {
        CompletableFuture<GetReconstructResult> call = reconstructCall;
        return call != null ? call : CompletableFuture.completedFuture(reconstruct);
    }

    @Override
    public CompletionStage<Void> cancelJob(long jobId) {
        cancelled.add(jobId);
        firstCancelled.complete(jobId);
        return CompletableFuture.completedFuture(null);
    }
}
