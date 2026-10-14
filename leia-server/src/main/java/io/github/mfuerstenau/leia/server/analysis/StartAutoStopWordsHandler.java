package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;

/**
 * {@code startAutoStopWords}: checks the params at once, then finds the stop words of fields of the
 * {@link CurrentIndex} in a job ({@link GetAutoStopWordsHandler}), as Lucene's {@code
 * QueryAutoStopWordAnalyzer} does (ADR 0039).
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
        io.github.mfuerstenau.leia.core.AutoStopWords.Threshold threshold = threshold(params);
        ReadOnlyIndex index = currentIndex.readable();
        for (String field : params.fields()) {
            FieldInfo info = index.fieldInfo(field).orElse(null);
            if (info == null || info.getIndexOptions() == IndexOptions.NONE) {
                throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No indexed field '" + field + "' in the index");
            }
        }
        List<String> fields = List.copyOf(params.fields());
        long jobId = jobs.start(
                "auto stop words of " + (fields.isEmpty() ? "every indexed field" : "fields " + fields),
                AutoStopWords.class,
                progress -> scan(index, fields, threshold, progress));
        return new JobStarted(jobId);
    }

    private static io.github.mfuerstenau.leia.core.AutoStopWords.Threshold threshold(StartAutoStopWordsParams params)
            throws RpcException {
        if (params.maxDocFreq().isPresent() == params.maxPercentDocs().isPresent()) {
            throw invalid("give exactly one of maxDocFreq and maxPercentDocs");
        }
        if (params.maxDocFreq().isPresent()) {
            int maxDocFreq = params.maxDocFreq().getAsInt();
            if (maxDocFreq < 0) {
                throw invalid("maxDocFreq must be at least 0, not " + maxDocFreq);
            }
            return new io.github.mfuerstenau.leia.core.AutoStopWords.Threshold.MaxDocFreq(maxDocFreq);
        }
        double fraction = params.maxPercentDocs().getAsDouble();
        if (!(fraction >= 0 && fraction <= 1)) {
            throw invalid("maxPercentDocs must be from 0 to 1, not " + fraction);
        }
        return new io.github.mfuerstenau.leia.core.AutoStopWords.Threshold.MaxPercentDocs(fraction);
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }

    private static AutoStopWords scan(
            ReadOnlyIndex index,
            List<String> fields,
            io.github.mfuerstenau.leia.core.AutoStopWords.Threshold threshold,
            ScanProgress progress)
            throws RpcException {
        try {
            io.github.mfuerstenau.leia.core.AutoStopWords found =
                    index.autoStopWords(fields, threshold, StartAutoStopWordsParams.MAX_WORDS, progress);
            return new AutoStopWords(
                    found.maxDocFreq(),
                    found.words().entrySet().stream()
                            .map(entry -> new FieldStopWords(entry.getKey(), entry.getValue()))
                            .toList());
        } catch (io.github.mfuerstenau.leia.core.AutoStopWords.TooManyWordsException e) {
            throw invalid(e.getMessage());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read the terms for auto stop words: " + e.getMessage(), e);
        }
    }
}
