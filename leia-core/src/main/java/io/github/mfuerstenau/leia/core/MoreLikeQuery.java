package io.github.mfuerstenau.leia.core;

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
 * Documents like one document of the index, or like a text (ADR 0013, ADR 0028). The query only
 * holds the settings: it rewrites to the {@link MoreLikeThis} query, which is built from the
 * searcher's reader. Rewriting happens inside the search, so it can be cancelled through the {@code
 * ExitableDirectoryReader} like the search (ADR 0010). Stored strings and the text are analyzed with
 * the caller's analyzer (ADR 0014).
 */
final class MoreLikeQuery extends Query {

    /** How many characters (code points) of a text {@link #toString} shows. */
    static final int TEXT_SHOWN = 40;

    /** Where the terms come from. */
    sealed interface Source {

        /** The terms of a document of the index, which may be deleted. */
        record Doc(int docId) implements Source {}

        /** The terms of a text, analyzed for each of the fields. */
        record Text(String text) implements Source {
            public Text {
                Objects.requireNonNull(text);
            }
        }
    }

    private final Source source;
    private final MoreLikeThisOptions options;
    private final Analyzer analyzer;

    MoreLikeQuery(Source source, MoreLikeThisOptions options, Analyzer analyzer) {
        this.source = Objects.requireNonNull(source);
        this.options = Objects.requireNonNull(options);
        this.analyzer = Objects.requireNonNull(analyzer);
    }

    Source source() {
        return source;
    }

    MoreLikeThisOptions options() {
        return options;
    }

    Analyzer analyzer() {
        return analyzer;
    }

    /**
     * The query that {@link MoreLikeThis} builds from {@code reader}: the terms of the document's term
     * vectors or, for fields without one, of its stored strings analyzed with the query's analyzer;
     * or the terms of the text, analyzed for each field. They are {@code SHOULD} clauses of a {@code
     * BooleanQuery}, weakest first.
     */
    Query build(IndexReader reader) throws IOException {
        MoreLikeThis mlt = new MoreLikeThis(reader);
        mlt.setAnalyzer(analyzer);
        mlt.setFieldNames(options.fields().toArray(String[]::new));
        mlt.setMinTermFreq(options.minTermFreq());
        mlt.setMinDocFreq(options.minDocFreq());
        mlt.setMaxDocFreq(Objects.requireNonNullElse(options.maxDocFreq(), MoreLikeThis.DEFAULT_MAX_DOC_FREQ));
        mlt.setMaxQueryTerms(options.maxQueryTerms());
        mlt.setBoost(options.boost());
        mlt.setMinWordLen(options.minWordLen());
        mlt.setMaxWordLen(Objects.requireNonNullElse(options.maxWordLen(), MoreLikeThis.DEFAULT_MAX_WORD_LENGTH));
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

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        return build(searcher.getIndexReader());
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
        if (options.maxDocFreq() != null) {
            text.append(", maxDocFreq ").append(options.maxDocFreq());
        }
        text.append(", maxQueryTerms ").append(options.maxQueryTerms());
        if (options.boost()) {
            text.append(", boost");
        }
        if (options.minWordLen() > 0) {
            text.append(", minWordLen ").append(options.minWordLen());
        }
        if (options.maxWordLen() != null) {
            text.append(", maxWordLen ").append(options.maxWordLen());
        }
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
    // The same analyzer instance, as Lucene's queries compare theirs
    @SuppressWarnings("ReferenceEquality")
    public boolean equals(Object other) {
        return sameClassAs(other)
                && other instanceof MoreLikeQuery that
                && source.equals(that.source)
                && options.equals(that.options)
                && analyzer == that.analyzer;
    }

    @Override
    public int hashCode() {
        return Objects.hash(classHash(), source, options, System.identityHashCode(analyzer));
    }
}
