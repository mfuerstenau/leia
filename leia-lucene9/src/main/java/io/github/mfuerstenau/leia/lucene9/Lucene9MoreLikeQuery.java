package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch.Options;
import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch.Source;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;

/**
 * Documents like one document of the index, or like a text, on Lucene 9.12: a copy of leia-core's
 * {@code MoreLikeQuery} (ADR 0013, ADR 0028, ADR 0066), with the same {@code toString}. It only holds
 * the settings and the analyzer's spec; {@link Lucene9Index} builds the analyzer and then, with {@link
 * #build}, the {@link MoreLikeThis} query from the reader it searches, before it rewrites.
 */
final class Lucene9MoreLikeQuery extends Query {

    /** How many characters (code points) of a text {@link #toString} shows. */
    static final int TEXT_SHOWN = 40;

    private final Source source;
    private final Options options;
    private final AnalyzerSpec analyzer;

    Lucene9MoreLikeQuery(Source source, Options options, AnalyzerSpec analyzer) {
        this.source = Objects.requireNonNull(source);
        this.options = Objects.requireNonNull(options);
        this.analyzer = Objects.requireNonNull(analyzer);
    }

    Source source() {
        return source;
    }

    Options options() {
        return options;
    }

    AnalyzerSpec analyzer() {
        return analyzer;
    }

    /**
     * The query that {@link MoreLikeThis} builds from {@code reader}: the terms of the document's term
     * vectors or, for fields without one, of its stored strings analyzed with {@code analyzer}; or the
     * terms of the text, analyzed for each field. They are {@code SHOULD} clauses of a {@code
     * BooleanQuery}, weakest first.
     *
     * @param analyzer built from {@link #analyzer()}; the caller closes it
     */
    Query build(IndexReader reader, Analyzer analyzer) throws IOException {
        MoreLikeThis mlt = new MoreLikeThis(reader);
        mlt.setAnalyzer(analyzer);
        mlt.setFieldNames(options.fields().toArray(String[]::new));
        mlt.setMinTermFreq(options.minTermFreq());
        mlt.setMinDocFreq(options.minDocFreq());
        mlt.setMaxDocFreq(options.maxDocFreq().orElse(MoreLikeThis.DEFAULT_MAX_DOC_FREQ));
        mlt.setMaxQueryTerms(options.maxQueryTerms());
        mlt.setBoost(options.boost());
        mlt.setMinWordLen(options.minWordLen());
        mlt.setMaxWordLen(options.maxWordLen().orElse(MoreLikeThis.DEFAULT_MAX_WORD_LENGTH));
        mlt.setStopWords(options.stopWords().isEmpty() ? null : options.stopWords());
        return switch (source) {
            case Source.Doc(int docId) -> mlt.like(docId);
            case Source.Text(String text) -> {
                // Each field analyzes the text; all of them fill one queue of the best terms.
                Map<String, Collection<Object>> values = new LinkedHashMap<>();
                for (String field : options.fields()) {
                    values.put(field, List.of(text));
                }
                yield mlt.like(values);
            }
        };
    }

    /**
     * {@inheritDoc} Never: it is built first.
     *
     * @throws IllegalStateException always
     */
    @Override
    public Query rewrite(IndexSearcher searcher) {
        throw new IllegalStateException("A More-Like-This query is built before it is rewritten: " + this);
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    /**
     * The source and the settings, such as {@code like(doc 42, fields [body], minTermFreq 2, …)} or
     * {@code like(text "brass lamp", fields [body], …)}.
     */
    @Override
    public String toString(String field) {
        StringBuilder text = new StringBuilder("like(");
        switch (source) {
            case Source.Doc(int docId) -> text.append("doc ").append(docId);
            case Source.Text(String like) -> text.append("text ").append(quoted(like));
        }
        text.append(", fields ")
                .append(options.fields())
                .append(", minTermFreq ")
                .append(options.minTermFreq())
                .append(", minDocFreq ")
                .append(options.minDocFreq());
        options.maxDocFreq().ifPresent(max -> text.append(", maxDocFreq ").append(max));
        text.append(", maxQueryTerms ").append(options.maxQueryTerms());
        if (options.boost()) {
            text.append(", boost");
        }
        if (options.minWordLen() > 0) {
            text.append(", minWordLen ").append(options.minWordLen());
        }
        options.maxWordLen().ifPresent(max -> text.append(", maxWordLen ").append(max));
        if (!options.stopWords().isEmpty()) {
            text.append(", stopWords ")
                    .append(options.stopWords().stream().sorted().toList());
        }
        return text.append(')').toString();
    }

    /** The start of {@code text} in quotes, with quotes and backslashes escaped, and … if it is cut. */
    private static String quoted(String text) {
        boolean cut = text.codePointCount(0, text.length()) > TEXT_SHOWN;
        String shown = cut ? text.substring(0, text.offsetByCodePoints(0, TEXT_SHOWN)) : text;
        return '"' + shown.replace("\\", "\\\\").replace("\"", "\\\"") + (cut ? "…\"" : "\"");
    }

    @Override
    public boolean equals(Object other) {
        return sameClassAs(other)
                && other instanceof Lucene9MoreLikeQuery that
                && source.equals(that.source)
                && options.equals(that.options)
                && analyzer.equals(that.analyzer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classHash(), source, options, analyzer);
    }
}
