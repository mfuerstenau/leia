package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code scan_stored}: the stored values that a regular expression matches, also in fields that
 * aren't indexed (ADR 0110). The scan stops once the page is full.
 */
final class ScanStoredTool implements McpTool<ScanStoredTool.Arguments, ScanStoredTool.Result> {

    static final int DEFAULT_LIMIT = 20;

    /** The most hits of a page. */
    static final int MAX_LIMIT = 100;

    record Arguments(
            @Description("A Java regular expression, found anywhere in a value; flags go inline, such as (?s)")
            String pattern,

            @Description("Whether case doesn't matter; false by default") @Nullable
            Boolean caseInsensitive,

            @Description("The stored fields to scan; all of them by default, which takes long on a large index")
            @Nullable
            List<String> fields,

            @Description("Whether to scan deleted documents too; false by default") @Nullable
            Boolean includeDeleted,

            @Description("The hits to skip: the nextOffset of the last page; 0 by default. Every page scans from the"
                    + " first document again")
            @Nullable
            Integer offset,

            @Description("The most hits of the page, from 1 to " + MAX_LIMIT + "; " + DEFAULT_LIMIT + " by default")
            @Nullable
            Integer limit) {}

    record Result(
            @Description("The values with a match, in document order")
            List<Hit> hits,

            int offset,

            @Description("The offset of the next page, which has hits; absent on the last page") @Nullable
            Integer nextOffset,

            @Description("The document ids gone through, deleted ones included; with stopped, at least that many")
            long scannedDocs,

            @Description("Present, and true, only if the scan stopped once the page was full: the counts cover"
                    + " scannedDocs only")
            @Nullable
            Boolean stopped,

            @Description("The values with a match among the scanned documents")
            long matchedValues,

            @Description("The documents with a match; absent unless the scan went through the whole index") @Nullable
            Integer matchedDocs,

            @Description("The values that were skipped because matching them took more than a second: simplify the"
                    + " pattern. Absent unless the scan went through the whole index, and if there were none")
            @Nullable
            Long timedOutValues,

            long tookMillis) {}

    record Hit(
            @Description("The document, for get_document") int docId,

            @Description("Present, and true, only for a deleted document") @Nullable
            Boolean deleted,

            String field,

            @Description("Which of the document's stored values in the field, as get_field_value takes it; absent if 0")
            @Nullable
            Integer valueIndex,

            @Description("The length of the whole value in bytes")
            int length,

            @Description("How many matches the value has; absent if 1") @Nullable
            Integer matchCount,

            @Description("The value around the first match: about 40 characters before it, 160 after")
            String snippet,

            @Description("The text of the first match, cut at the end of the snippet if it runs past it")
            String match) {}

    @Override
    public String name() {
        return "scan_stored";
    }

    @Override
    public String description() {
        return "Find stored values by a Java regex, also in fields that aren't indexed, such as _source. Scans"
                + " documents in id order and stops once the page is full; for the next page, call again with"
                + " nextOffset. Slow on large indexes: narrow it with fields.";
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
        int offset = Slices.bounded("offset", arguments.offset(), 0, 0, StartStoredScanParams.MAX_HITS - 1);
        int limit = Slices.bounded("limit", arguments.limit(), DEFAULT_LIMIT, 1, MAX_LIMIT);
        if (offset + limit > StartStoredScanParams.MAX_HITS) {
            throw ToolError.invalid("offset + limit must be at most " + StartStoredScanParams.MAX_HITS
                    + ": a scan keeps that many hits only");
        }
        StartStoredScanParams params = new StartStoredScanParams(
                arguments.pattern(),
                Given.isTrue(arguments.caseInsensitive()),
                Optional.ofNullable(arguments.fields()),
                Given.isTrue(arguments.includeDeleted()));
        Long total = Optionals.orNull(call.session().maxDoc());
        Page page = new Page(offset, limit);
        long start = System.nanoTime();
        GetStoredScanResult last = call.job(
                Methods.START_STORED_SCAN,
                params,
                Methods.GET_STORED_SCAN,
                jobId -> new GetStoredScanParams(jobId, page.seen),
                page::add,
                poll -> poll.result().isPresent(),
                poll -> new Jobs.Progress(poll.scannedDocs(), total));
        long tookMillis = (System.nanoTime() - start) / 1_000_000;
        return result(page, last, tookMillis);
    }

    /** The hits of the page, as the polls bring them. */
    static final class Page {
        final int offset;
        final int limit;
        final List<Hit> hits = new ArrayList<>();

        /** The hits received so far, those before and after the page included. */
        int seen;

        /** The document of the last hit received. */
        int lastDocId = -1;

        Page(int offset, int limit) {
            this.offset = offset;
            this.limit = limit;
        }

        /**
         * The poll as the outcome once the page is full and one hit more is there, or the scan is done.
         * The scan passes on {@code MAX_HITS} hits only, so a page that ends there is done when it is
         * full: no hit more will come, however long the scan goes on counting.
         */
        Optional<GetStoredScanResult> add(GetStoredScanResult poll) {
            for (StoredScanHit hit : poll.hits()) {
                if (seen >= offset && seen < offset + limit) {
                    hits.add(hit(hit));
                }
                seen++;
                lastDocId = hit.docId();
            }
            boolean full = seen > offset + limit || seen == StartStoredScanParams.MAX_HITS;
            return poll.result().isPresent() || full ? Optional.of(poll) : Optional.empty();
        }

        /**
         * The document ids gone through. The scan reports its progress every 64 documents, so a scan
         * that was stopped has gone through at least those of its last hit.
         */
        long scanned(GetStoredScanResult last) {
            return last.result().isPresent() ? last.scannedDocs() : Math.max(last.scannedDocs(), lastDocId + 1L);
        }
    }

    private static Result result(Page page, GetStoredScanResult last, long tookMillis) {
        Optional<StoredScanResult> full = last.result();
        return new Result(
                page.hits,
                page.offset,
                page.seen > page.offset + page.limit ? page.offset + page.limit : null,
                page.scanned(last),
                Absent.flag(full.isEmpty()),
                last.matchedValues(),
                full.map(StoredScanResult::matchedDocs).orElse(null),
                full.map(StoredScanResult::timedOutValues)
                        .filter(timedOut -> timedOut > 0)
                        .orElse(null),
                tookMillis);
    }

    private static Hit hit(StoredScanHit hit) {
        return new Hit(
                hit.docId(),
                Absent.flag(hit.deleted()),
                hit.field(),
                Absent.nonZero(hit.valueIndex()),
                hit.length(),
                hit.matchCount() == 1 ? null : hit.matchCount(),
                hit.snippet().text(),
                match(hit.snippet(), hit.spans()));
    }

    /** The first match's text: its UTF-8 byte span, relative to the snippet, as a slice of the snippet. */
    static String match(Snippet snippet, List<MatchSpan> spans) {
        if (spans.isEmpty()) {
            return "";
        }
        String text = snippet.text();
        int start = charIndex(text, spans.get(0).start() - snippet.start());
        int end = charIndex(text, spans.get(0).end() - snippet.start());
        return text.substring(start, Math.max(start, end));
    }

    /**
     * The index in {@code text} of the character at {@code bytes} UTF-8 bytes from its start, or the
     * end of the text if that is past it.
     */
    private static int charIndex(String text, int bytes) {
        int index = 0;
        int counted = 0;
        while (index < text.length() && counted < bytes) {
            int codePoint = text.codePointAt(index);
            counted += utf8Length(codePoint);
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
