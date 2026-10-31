package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * {@code terms}: the terms of a field, a page at a time in term order, or the ones in the most
 * documents (ADR 0106).
 */
final class TermsTool implements McpTool<TermsTool.Arguments, TermsTool.Result> {

    static final int DEFAULT_LIMIT = 50;

    record Arguments(
            String field,

            @Description("Only terms that start with this text; a binary term if its bytes start with the text's")
            Optional<String> prefix,

            @Description("Where the page starts: the next of the last page, which continues it with the same prefix")
            Optional<Term> from,

            @Description("The most terms of the page, from 1 to " + GetTermsParams.MAX_LIMIT + "; " + DEFAULT_LIMIT
                    + " by default")
            OptionalInt limit,

            @Description("Instead of a page, the N terms in the most documents, from 1 to "
                    + StartFieldTermScanParams.MAX_TOP_N + ", most first: it scans every term of the field, which"
                    + " takes long on a large one. Excludes prefix, from and limit")
            OptionalInt top) {}

    record Term(
            @Description("The term as text, or in hex with binary")
            String value,

            @Description("Whether value is the term's bytes in hex; false by default")
            Optional<Boolean> binary) {}

    record Result(
            String field,

            @Description("The terms, in term order, or with top, most documents first")
            List<Entry> terms,

            @Description("The from of the next page; absent after the last term, and with top")
            Optional<Term> next,

            @Description("How many distinct terms the field has; with top only")
            OptionalLong uniqueTerms) {}

    record Entry(
            String term,

            @Description("Present, and true, only if term is the term's bytes in hex")
            Optional<Boolean> binary,

            @Description("The documents with the term, deleted ones included")
            int docFreq,

            @Description("The occurrences of the term in all documents; absent if equal to docFreq")
            OptionalLong totalTermFreq) {}

    @Override
    public String name() {
        return "terms";
    }

    @Override
    public String description() {
        return "List the terms of a field, as the index holds them after analysis: the values to search for, and why"
                + " a term query misses. A page in term order, optionally from a prefix, or with top the terms in the"
                + " most documents. Counts include deleted documents. For the next page, call it again with the"
                + " result's next as from and the same prefix.";
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
        if (arguments.top().isPresent()) {
            if (arguments.prefix().isPresent()
                    || arguments.from().isPresent()
                    || arguments.limit().isPresent()) {
                throw ToolError.invalid("top excludes prefix, from and limit: it ranks every term of the field");
            }
            int top = Slices.bounded("top", arguments.top(), 0, 1, StartFieldTermScanParams.MAX_TOP_N);
            return top(arguments.field(), top, call);
        }
        return page(arguments, call);
    }

    private static Result top(String field, int n, Call call) throws ToolException, Jobs.Cancelled {
        FieldTerms terms = call.job(
                Methods.START_FIELD_TERM_SCAN,
                new StartFieldTermScanParams(field, n),
                Methods.GET_FIELD_TERM_SCAN,
                GetFieldTermScanResult::terms,
                poll -> new Jobs.Progress(poll.termsScanned(), OptionalLong.empty()));
        List<Entry> entries = new ArrayList<>();
        for (TopTerm term : terms.topTerms()) {
            entries.add(entry(term.term(), term.binary(), term.docFreq(), term.totalTermFreq()));
        }
        return new Result(field, entries, Optional.empty(), OptionalLong.of(terms.uniqueTerms()));
    }

    private static Result page(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
        int limit = Slices.bounded("limit", arguments.limit(), DEFAULT_LIMIT, 1, GetTermsParams.MAX_LIMIT);
        String prefix = arguments.prefix().orElse("");
        Optional<BytesValue> from = arguments
                .from()
                .map(term -> new BytesValue(term.value(), term.binary().orElse(false)));
        if (from.isEmpty() && !prefix.isEmpty()) {
            from = Optional.of(new BytesValue(prefix, false));
        }
        GetTermsResult page = call.call(Methods.GET_TERMS, new GetTermsParams(arguments.field(), from, limit));
        Prefix matcher = new Prefix(prefix);
        List<Entry> entries = new ArrayList<>();
        boolean cut = false;
        for (TermEntry term : page.terms()) {
            if (matcher.matches(term.term(), term.binary())) {
                entries.add(entry(term.term(), term.binary(), term.docFreq(), term.totalTermFreq()));
            } else {
                cut = true;
                break;
            }
        }
        boolean complete = !cut;
        Optional<Term> next = page.next()
                .filter(value -> complete && matcher.matches(value.value(), value.binary()))
                .map(value -> new Term(value.value(), Absent.flag(value.binary())));
        return new Result(arguments.field(), entries, next, OptionalLong.empty());
    }

    /**
     * Whether a term starts with the prefix: a text term by its text, a binary term, in hex, by the
     * prefix's UTF-8 bytes in hex. In term order, the terms with the prefix's bytes come together, the
     * binary ones among them.
     */
    record Prefix(String text, String hex) {
        Prefix(String text) {
            this(text, HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)));
        }

        boolean matches(String term, boolean binary) {
            return binary ? term.startsWith(hex) : term.startsWith(text);
        }
    }

    private static Entry entry(String term, boolean binary, int docFreq, long totalTermFreq) {
        return new Entry(
                term,
                Absent.flag(binary),
                docFreq,
                totalTermFreq == docFreq ? OptionalLong.empty() : OptionalLong.of(totalTermFreq));
    }
}
