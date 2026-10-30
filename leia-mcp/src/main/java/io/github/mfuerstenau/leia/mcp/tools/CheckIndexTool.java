package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.CheckTarget;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * {@code check_index}: Lucene's {@code CheckIndex} on the open index, read-only (ADR 0112). The
 * output lines of the check aren't returned: the failures and their causes are in the result.
 */
final class CheckIndexTool implements McpTool<CheckIndexTool.Arguments, CheckIndexTool.Result> {

    record Arguments(
            @Description("How thoroughly, from " + StartCheckIndexParams.MIN_LEVEL + " to "
                    + StartCheckIndexParams.MAX_LEVEL + ": 1 verifies the checksums of every file (the default), 2"
                    + " also checks every part of each segment, 3 also runs the slow checks")
            OptionalInt level,

            @Description("The indexes, and in them the segments, to check; all of them by default. Narrow a large"
                    + " index down: a check of every segment can take long")
            Optional<List<Target>> only) {}

    record Target(
            @Description("The index directory, as index_info lists it in indexes or skipped")
            String path,

            @Description("The segments of its commit to check, such as _0, not empty; all of them by default")
            Optional<List<String>> segments) {}

    record Result(
            @Description("Whether no problem was found in any index")
            boolean clean,

            @Description("Each index checked: the opened ones in order, then the skipped ones")
            List<Index> indexes,

            long tookMillis) {}

    record Index(
            @Description("The index directory") String path,

            @Description("Present, and true, only for an index that was left out when the path was opened")
            Optional<Boolean> skipped,

            @Description("Whether no problem was found") boolean clean,

            @Description("Why the index couldn't be checked at all, such as no segments_N; then the rest says nothing")
            Optional<String> error,

            @Description("The segments_N file of the latest commit, which is the one checked")
            Optional<String> segmentsFile,

            @Description("Present, and true, only if the latest commit or an older one couldn't be read")
            Optional<Boolean> missingSegments,

            @Description("Present, and true, only if a newer Lucene than leia's wrote the index")
            Optional<Boolean> toolOutOfDate,

            @Description("Present, and true, only if the commit's counter for segment names is not above every"
                    + " segment's name")
            Optional<Boolean> invalidCounter,

            @Description("The segments of the latest commit")
            int segmentCount,

            @Description("The segments that failed a test; absent if 0")
            OptionalInt badSegments,

            @Description("The documents in the bad segments; absent if 0")
            OptionalInt lostDocs,

            @Description("The segments that failed, with why; the ones that passed aren't listed. Absent if none")
            Optional<List<FailedSegment>> failedSegments,

            @Description("The segments that were asked for with only; absent if the whole index was")
            Optional<List<String>> onlySegments,

            @Description(
                    "The segments asked for that aren't in the latest commit; then clean is false. Absent if" + " none")
            Optional<List<String>> segmentsNotFound) {}

    record FailedSegment(
            String name,

            @Description("Documents including deleted ones") int maxDoc,

            @Description("The segment's codec, if its .si file could be read")
            Optional<String> codec,

            @Description("The test it failed and why, with the causes")
            String error) {}

    @Override
    public String name() {
        return "check_index";
    }

    @Override
    public String description() {
        return "Check the open index for corruption with Lucene's CheckIndex, read-only. Level 1 verifies checksums;"
                + " 2 and 3 go deeper and take longer. Narrow a large index with only: paths and segments.";
    }

    @Override
    public Class<Arguments> arguments() {
        return Arguments.class;
    }

    @Override
    public Class<Result> result() {
        return Result.class;
    }

    @Override
    public Result call(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
        Optional<OpenIndexResult> opened = call.session().opened();
        if (opened.isPresent()) {
            if (!opened.get().features().contains(Feature.CHECK_INDEX)) {
                throw ToolError.notSupported("The Lucene runtime that reads this index can't check it");
            }
            if (arguments.level().isPresent() && !opened.get().features().contains(Feature.CHECK_LEVELS)) {
                throw ToolError.notSupported(
                        "The Lucene runtime that reads this index checks at its one level only: leave level out");
            }
        }
        StartCheckIndexParams params = new StartCheckIndexParams(
                arguments.level(),
                OptionalInt.empty(),
                arguments
                        .only()
                        .map(only -> only.stream()
                                .map(target -> new CheckTarget(target.path(), target.segments()))
                                .toList()));
        Lines lines = new Lines();
        CheckIndexResult checked = call.job(
                Methods.START_CHECK_INDEX,
                params,
                Methods.GET_CHECK_INDEX,
                jobId -> new GetCheckIndexParams(jobId, lines.count),
                lines::add,
                poll -> poll.result().isPresent(),
                poll -> new Jobs.Progress(poll.lineCount(), OptionalLong.empty()));
        return new Result(
                checked.indexes().stream().allMatch(IndexCheck::clean),
                checked.indexes().stream().map(CheckIndexTool::index).toList(),
                checked.tookMillis());
    }

    /** How many lines of output the polls have seen, so that a poll never asks for them again. */
    private static final class Lines {
        int count;

        Optional<CheckIndexResult> add(GetCheckIndexResult poll) {
            count = poll.lineCount();
            return poll.result();
        }
    }

    private static Index index(IndexCheck check) {
        List<FailedSegment> failed = check.segments().stream()
                .filter(segment -> segment.error().isPresent())
                .map(CheckIndexTool::failed)
                .toList();
        return new Index(
                check.path(),
                Absent.flag(check.skipped()),
                check.clean(),
                check.error(),
                check.segmentsFile(),
                Absent.flag(check.missingSegments()),
                Absent.flag(check.toolOutOfDate()),
                Absent.flag(!check.validCounter()),
                check.segmentCount(),
                Absent.nonZero(check.badSegments()),
                Absent.nonZero(check.lostDocs()),
                Absent.nonEmpty(failed),
                check.onlySegments(),
                Absent.nonEmpty(check.segmentsNotFound()));
    }

    private static FailedSegment failed(SegmentCheck segment) {
        return new FailedSegment(
                segment.name(),
                segment.maxDoc(),
                segment.codec(),
                segment.error().orElseThrow());
    }
}
