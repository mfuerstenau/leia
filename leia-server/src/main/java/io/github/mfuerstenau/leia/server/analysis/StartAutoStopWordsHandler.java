package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;

/**
 * {@code startAutoStopWords}: checks the params at once, then finds the stop words of fields of the
 * {@link CurrentIndex} in a job ({@link GetAutoStopWordsHandler}), as Lucene's {@code
 * QueryAutoStopWordAnalyzer} does (ADR 0039), on Lucene 10 or a runtime (ADR 0061).
 */
public final class StartAutoStopWordsHandler implements MethodHandler<StartAutoStopWordsParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartAutoStopWordsHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartAutoStopWordsParams, JobStarted> method() {
        return Methods.START_AUTO_STOP_WORDS;
    }

    @Override
    public JobStarted handle(StartAutoStopWordsParams params) throws RpcException {
        AutoStopWords.Threshold threshold = threshold(params);
        return RuntimeResults.startAutoStopWords(currentIndex.runtime(), params.fields(), threshold, jobs);
    }

    private static AutoStopWords.Threshold threshold(StartAutoStopWordsParams params) throws RpcException {
        if (params.maxDocFreq().isPresent() == params.maxPercentDocs().isPresent()) {
            throw invalid("give exactly one of maxDocFreq and maxPercentDocs");
        }
        if (params.maxDocFreq().isPresent()) {
            int maxDocFreq = params.maxDocFreq().getAsInt();
            if (maxDocFreq < 0) {
                throw invalid("maxDocFreq must be at least 0, not " + maxDocFreq);
            }
            return new AutoStopWords.Threshold.MaxDocFreq(maxDocFreq);
        }
        double fraction = params.maxPercentDocs().getAsDouble();
        if (!(fraction >= 0 && fraction <= 1)) {
            throw invalid("maxPercentDocs must be from 0 to 1, not " + fraction);
        }
        return new AutoStopWords.Threshold.MaxPercentDocs(fraction);
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }

    /** Finds the stop words of an index. */
    @FunctionalInterface
    public interface Scan {

        AutoStopWords find() throws IOException, AutoStopWords.TooManyWordsException;
    }

    /**
     * What {@code scan} finds, for Lucene 10 and the runtimes (ADR 0061).
     *
     * @throws RpcException {@code INVALID_PARAMS} for too many words, {@code INDEX_NOT_READABLE} if
     *     the terms can't be read
     */
    public static io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords found(Scan scan) throws RpcException {
        try {
            AutoStopWords found = scan.find();
            return new io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords(
                    found.maxDocFreq(),
                    found.words().entrySet().stream()
                            .map(entry -> new FieldStopWords(entry.getKey(), entry.getValue()))
                            .toList());
        } catch (AutoStopWords.TooManyWordsException e) {
            throw invalid(e.getMessage());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read the terms for auto stop words: " + e.getMessage(), e);
        }
    }
}
