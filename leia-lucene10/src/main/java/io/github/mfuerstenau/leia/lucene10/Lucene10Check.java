package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.IndexCheck;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Lucene 10's {@code CheckIndex}, run by leia-core's {@link IndexCheck} (ADR 0022, ADR 0065). */
final class Lucene10Check implements Check {

    /** Lucene's default level. */
    private static final int DEFAULT_LEVEL = 2;

    @Override
    public boolean hasLevels() {
        return true;
    }

    /** {@inheritDoc} The level defaults to 2. */
    @Override
    public CheckResult check(
            Path dir, DirectoryKind kind, OptionalInt level, Consumer<String> line, LongConsumer progress) {
        IndexCheck.Result result = IndexCheck.check(
                dir, Lucene10Index.directoryImpl(kind), level.orElse(DEFAULT_LEVEL), line, progress::accept);
        Optional<String> error = result.error();
        if (error.isPresent()) {
            return CheckResult.failed(error.get());
        }
        return new CheckResult(
                result.clean(),
                result.error(),
                result.segmentsFile(),
                result.missingSegments(),
                result.toolOutOfDate(),
                result.segmentCount(),
                result.badSegments(),
                result.lostDocs(),
                result.validCounter(),
                result.segments().stream()
                        .map(segment -> new SegmentCheck(
                                segment.name(),
                                segment.maxDoc(),
                                segment.codec(),
                                segment.compound(),
                                segment.files(),
                                segment.sizeBytes(),
                                segment.hasDeletions(),
                                segment.openReaderPassed(),
                                segment.error()))
                        .toList());
    }
}
