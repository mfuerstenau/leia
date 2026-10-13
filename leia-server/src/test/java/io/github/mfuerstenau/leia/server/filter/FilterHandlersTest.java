package io.github.mfuerstenau.leia.server.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.ReleaseFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code startFilter}, {@code getFilter}, {@code stepFilter} and {@code releaseFilter} (ADR 0031,
 * ADR 0032).
 */
class FilterHandlersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Analyzer STANDARD = new Analyzer(
            Optional.of(new StandardAnalyzer()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final Filters filters = new Filters();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, filters);
    private final StartFilterHandler start = new StartFilterHandler(currentIndex, jobs);
    private final GetFilterHandler get = new GetFilterHandler(jobs, filters);
    private final StepFilterHandler step = new StepFilterHandler(filters);
    private final ReleaseFilterHandler release = new ReleaseFilterHandler(filters);

    @Test
    void buildsAFilterAndStepsThroughItsMatches() throws Exception {
        openIndex();

        long jobId = start.handle(byQuery(classic("red"), false)).jobId();
        GetFilterResult done = await(jobId);
        FilterResult filter = done.result().orElseThrow();

        assertThat(done.docsSearched()).isEqualTo(5);
        assertThat(filter.filterId()).isEqualTo(jobId);
        assertThat(filter.matchCount()).isEqualTo(2);
        assertThat(filter.query()).isEqualTo("body:red");
        assertThat(filter.rewrittenQuery()).isEqualTo("body:red");
        assertThat(step(jobId, 0, true)).contains(new FilterMatch(0, 0));
        assertThat(step(jobId, 1, true)).contains(new FilterMatch(2, 1));
        // The deleted red document 3 doesn't match.
        assertThat(step(jobId, 3, true)).isEmpty();
        assertThat(step(jobId, 1, false)).contains(new FilterMatch(0, 0));
        assertThat(step(jobId, -1, false)).isEmpty();
        // The job is gone; the filter stays.
        assertFails(() -> get.handle(new JobParams(jobId)), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void deletedDocumentsMatchWhenAsked() throws Exception {
        openIndex();

        long jobId = start.handle(byQuery(classic("red"), true)).jobId();

        assertThat(await(jobId).result().orElseThrow().matchCount()).isEqualTo(3);
        assertThat(step(jobId, 3, true)).contains(new FilterMatch(3, 2));
    }

    @Test
    void buildsAFilterFromDocValues() throws Exception {
        openIndex();
        SearchQuery query = new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new DocValuesQuery(
                        "color",
                        Optional.empty(),
                        Optional.of(List.of(new BytesValue("red", false))),
                        Optional.empty())));

        long jobId = start.handle(byQuery(query, true)).jobId();
        FilterResult filter = await(jobId).result().orElseThrow();

        assertThat(filter.matchCount()).isEqualTo(3);
        assertThat(step(jobId, 1, true)).contains(new FilterMatch(2, 1));
        assertThat(step(jobId, 3, true)).contains(new FilterMatch(3, 2));
    }

    @Test
    void buildsAFilterFromAPatternOverStoredValues() throws Exception {
        openIndex();

        long jobId =
                start.handle(byPattern("^R", true, Optional.empty(), false)).jobId();
        GetFilterResult done = await(jobId);
        FilterResult filter = done.result().orElseThrow();

        assertThat(done.docsSearched()).isEqualTo(5);
        assertThat(filter.matchCount()).isEqualTo(2);
        assertThat(filter.query()).isEqualTo("/^R/i");
        assertThat(filter.rewrittenQuery()).isEqualTo("/^R/i");
        assertThat(filter.timedOutValues()).hasValue(0);
        assertThat(step(jobId, 1, true)).contains(new FilterMatch(2, 1));

        long withDeleted = start.handle(byPattern("e", false, Optional.of(List.of("body")), true))
                .jobId();
        assertThat(await(withDeleted).result().orElseThrow().matchCount()).isEqualTo(5);
        // The id field has no "e"; only body was scanned.
        long ids = start.handle(byPattern("\\d", false, Optional.of(List.of("body")), false))
                .jobId();
        assertThat(await(ids).result().orElseThrow().matchCount()).isZero();
    }

    @Test
    void aQueryFilterSaysNothingOfTimedOutValues() throws Exception {
        openIndex();

        long jobId = start.handle(byQuery(classic("red"), false)).jobId();

        assertThat(await(jobId).result().orElseThrow().timedOutValues()).isEmpty();
    }

    @Test
    void checksThePatternAtOnce() throws Exception {
        openIndex();

        assertFails(() -> start.handle(byPattern("(red", false, Optional.empty(), false)), ErrorCode.INVALID_QUERY);
        assertFails(
                () -> start.handle(byPattern("red", false, Optional.of(List.of("missing")), false)),
                ErrorCode.FIELD_NOT_FOUND);
        assertFails(
                () -> start.handle(new StartFilterParams(Optional.empty(), Optional.empty(), false)),
                ErrorCode.INVALID_PARAMS);
        assertFails(
                () -> start.handle(new StartFilterParams(
                        Optional.of(classic("red")),
                        Optional.of(new FilterPattern("red", false, Optional.empty())),
                        false)),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void keepsOneFilterUntilItIsReleased() throws Exception {
        openIndex();
        long first = start.handle(byQuery(classic("red"), false)).jobId();
        await(first);
        long second = start.handle(byQuery(classic("blue"), false)).jobId();

        // The first one is kept until the second is built.
        assertThat(step(first, 0, true)).contains(new FilterMatch(0, 0));
        await(second);

        assertFails(() -> step(first, 0, true), ErrorCode.FILTER_NOT_FOUND);
        assertThat(release.handle(new ReleaseFilterParams(first)).released()).isFalse();
        assertThat(step(second, 0, true)).contains(new FilterMatch(1, 0));
        assertThat(release.handle(new ReleaseFilterParams(second)).released()).isTrue();
        assertThat(release.handle(new ReleaseFilterParams(second)).released()).isFalse();
        assertFails(() -> step(second, 0, true), ErrorCode.FILTER_NOT_FOUND);
    }

    @Test
    void closingOrReplacingTheIndexForgetsTheFilter() throws Exception {
        openIndex();
        long first = start.handle(byQuery(classic("red"), false)).jobId();
        await(first);

        currentIndex.replace(ReadOnlyIndex.open(tmp.resolve("index")));

        assertFails(() -> step(first, 0, true), ErrorCode.FILTER_NOT_FOUND);

        long second = start.handle(byQuery(classic("red"), false)).jobId();
        await(second);
        currentIndex.close();

        assertFails(() -> step(second, 0, true), ErrorCode.FILTER_NOT_FOUND);
    }

    @Test
    void checksTheQueryAtOnce() throws Exception {
        assertFails(() -> start.handle(byQuery(classic("red"), false)), ErrorCode.NO_INDEX_OPEN);
        openIndex();

        assertFails(() -> start.handle(byQuery(classic("title:(lamp"), false)), ErrorCode.INVALID_QUERY);
        assertFails(() -> start.handle(byQuery(classic("red", "missing"), false)), ErrorCode.FIELD_NOT_FOUND);

        currentIndex.replace(DirectoryOnlyIndex.open(tmp.resolve("index"), DirectoryImpl.FS));

        assertFails(() -> start.handle(byQuery(classic("red"), false)), ErrorCode.NO_READER);
    }

    @Test
    void anUnknownFilterIsNotFound() throws Exception {
        assertFails(() -> step(42, 0, true), ErrorCode.FILTER_NOT_FOUND);
    }

    private Optional<FilterMatch> step(long filterId, int fromDoc, boolean forward) throws RpcException {
        return step.handle(new StepFilterParams(filterId, fromDoc, forward)).match();
    }

    private GetFilterResult await(long jobId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetFilterResult result = get.handle(new JobParams(jobId));
            if (result.result().isPresent()) {
                return result;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private static StartFilterParams byQuery(SearchQuery query, boolean includeDeleted) {
        return new StartFilterParams(Optional.of(query), Optional.empty(), includeDeleted);
    }

    private static StartFilterParams byPattern(
            String pattern, boolean caseInsensitive, Optional<List<String>> fields, boolean includeDeleted) {
        return new StartFilterParams(
                Optional.empty(), Optional.of(new FilterPattern(pattern, caseInsensitive, fields)), includeDeleted);
    }

    private static SearchQuery classic(String expression) {
        return classic(expression, "body");
    }

    private static SearchQuery classic(String expression, String defaultField) {
        ParserOptions options =
                new ParserOptions(false, 0, 0, true, Optional.empty(), Optional.empty(), Optional.empty());
        return new SearchQuery(
                Optional.of(new ClassicQuery(
                        expression, defaultField, QueryOperator.OR, options, 2, false, false, STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * One segment: "red", "blue", "red", "red" (deleted) and "green", with the ids 0 to 4, each
     * also as the sorted doc values {@code color}.
     */
    private void openIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            List<String> bodies = List.of("red", "blue", "red", "red", "green");
            for (int id = 0; id < bodies.size(); id++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id), Field.Store.YES));
                doc.add(new TextField("body", bodies.get(id), Field.Store.YES));
                doc.add(new SortedDocValuesField("color", new BytesRef(bodies.get(id))));
                writer.addDocument(doc);
            }
            writer.deleteDocuments(new Term("id", "3"));
        }
        currentIndex.replace(ReadOnlyIndex.open(dir));
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
