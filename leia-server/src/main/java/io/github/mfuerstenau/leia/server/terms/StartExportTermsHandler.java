package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.core.TermExport;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.terms.ExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.TermDelimiter;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code startExportTerms}: checks the field and the file at once, then writes the terms of the
 * field of the {@link CurrentIndex} to the file in a job ({@link GetExportTermsHandler}, ADR 0022),
 * on Lucene 10 or a runtime (ADR 0044).
 */
public final class StartExportTermsHandler implements MethodHandler<StartExportTermsParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final IndexDirectories indexDirectories;
    private final Jobs jobs;

    @Inject
    StartExportTermsHandler(CurrentIndex currentIndex, IndexDirectories indexDirectories, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.indexDirectories = indexDirectories;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartExportTermsParams, JobStarted> method() {
        return Methods.START_EXPORT_TERMS;
    }

    @Override
    public JobStarted handle(StartExportTermsParams params) throws RpcException {
        String field = params.field();
        OpenRuntime open = currentIndex.runtime();
        TermExport.TermSource terms = RuntimeResults.exportTerms(open, field);
        String lucene = " with Lucene " + open.runtime().luceneVersion();
        Path file = path(params.path());
        boolean overwrite = params.overwrite().orElse(false);
        List<Path> directories = indexDirectories.all().stream()
                .map(IndexDirectories.IndexDirectory::path)
                .toList();
        try {
            TermExport.checkTarget(directories, file, overwrite);
        } catch (TermExport.OutputException e) {
            throw new RpcException(ErrorCode.OUTPUT_NOT_WRITABLE, e.getMessage(), e);
        }
        TermExport.Delimiter delimiter = delimiter(params.delimiter().orElse(TermDelimiter.TAB));
        long jobId = jobs.start(
                "export of the terms of field " + field + " to " + file + lucene,
                ExportTermsResult.class,
                progress -> export(directories, terms, field, file, delimiter, overwrite, progress));
        return new JobStarted(jobId);
    }

    private static Path path(String path) throws RpcException {
        try {
            Path file = Path.of(path);
            if (file.isAbsolute()) {
                return file;
            }
        } catch (InvalidPathException e) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: not a path: " + path, e);
        }
        throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: the path must be absolute: " + path);
    }

    private static TermExport.Delimiter delimiter(TermDelimiter delimiter) {
        return switch (delimiter) {
            case COMMA -> TermExport.Delimiter.COMMA;
            case SPACE -> TermExport.Delimiter.SPACE;
            case TAB -> TermExport.Delimiter.TAB;
        };
    }

    private static ExportTermsResult export(
            List<Path> directories,
            TermExport.TermSource terms,
            String field,
            Path file,
            TermExport.Delimiter delimiter,
            boolean overwrite,
            ScanProgress progress)
            throws RpcException {
        try {
            TermExport.Result result = TermExport.export(directories, terms, file, delimiter, overwrite, progress);
            return new ExportTermsResult(result.path().toString(), result.terms(), result.bytes());
        } catch (TermExport.OutputException e) {
            throw new RpcException(ErrorCode.OUTPUT_NOT_WRITABLE, e.getMessage(), e);
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the terms of field '" + field + "': " + e.getMessage(),
                    e);
        }
    }
}
