package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.SearchResults.FieldHighlight;
import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import io.github.mfuerstenau.leia.core.SearchResults.HitField;
import io.github.mfuerstenau.leia.core.SearchResults.LikeTerm;
import io.github.mfuerstenau.leia.core.SearchResults.SortValue;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.ExitableDirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.FilterCollector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;

/**
 * Runs searches and explains them, cooperatively cancellable (ADR 0010). Each call searches with a
 * new {@link IndexSearcher} over {@link ExitableDirectoryReader}s, whose {@link QueryTimeout} asks
 * the caller's {@link ScanProgress} whether to go on: it is checked while rewriting, enumerating
 * terms and collecting hits. The searcher scores with the caller's {@link Similarity} (ADR 0012).
 */
final class Searcher {

    /** Without an exact count, counting stops after this many hits, as in Luke. */
    static final int TOTAL_HITS_THRESHOLD = 1000;

    private Searcher() {}

    static SearchResults search(
            IndexReader reader,
            List<DirectoryReader> directoryReaders,
            Query query,
            Similarity similarity,
            Optional<Sort> sort,
            int offset,
            int limit,
            boolean exactTotalHits,
            Optional<Set<String>> fields,
            Optional<HighlightSpec> highlight,
            ScanProgress progress)
            throws IOException, InvalidQueryException {
        Cancellation cancellation = new Cancellation(progress);
        IndexSearcher searcher = searcher(directoryReaders, similarity, cancellation);
        long start = System.nanoTime();
        try {
            cancellation.searched(0);
            Query built = built(searcher, query);
            Optional<List<LikeTerm>> likeTerms = Optional.empty();
            if (query instanceof MoreLikeQuery like) {
                // Explained before the rewrite merges a lone clause or drops a ^1.0.
                likeTerms = Optional.of(
                        LikeTerms.of(searcher.getIndexReader(), like, built, () -> cancellation.searched(0)));
            }
            Query rewritten = searcher.rewrite(built);
            int numHits = offset + limit;
            int threshold = exactTotalHits ? Integer.MAX_VALUE : TOTAL_HITS_THRESHOLD;
            TopDocs top = sort.isPresent()
                    ? searcher.search(
                            rewritten,
                            new ProgressCollectorManager<>(
                                    new TopFieldCollectorManager(sort.get(), numHits, null, threshold), cancellation))
                    : searcher.search(
                            rewritten,
                            new ProgressCollectorManager<>(
                                    new TopScoreDocCollectorManager(numHits, null, threshold), cancellation));
            cancellation.check();
            ScoreDoc[] page =
                    Arrays.copyOfRange(top.scoreDocs, Math.min(offset, top.scoreDocs.length), top.scoreDocs.length);
            if (sort.isPresent()) {
                // A field sort leaves the scores out; the page's hits get theirs in one more pass.
                TopFieldCollector.populateScores(page, searcher, rewritten);
                cancellation.check();
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            // The built query is highlighted: the highlighter handles multi-term queries itself.
            List<Hit> hits =
                    hits(reader, page, sort, fields, highlight.map(spec -> new Highlighting(searcher, built, spec)));
            cancellation.check();
            cancellation.searched(reader.maxDoc());
            return new SearchResults(query.toString(), rewritten.toString(), top.totalHits, hits, took, likeTerms);
        } catch (ExitableDirectoryReader.ExitingReaderException e) {
            throw cancellation.stopped(e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /**
     * The documents that match {@code query}, found without scoring them (ADR 0031). Deleted
     * documents match too with {@code includeDeleted}: the searcher then reads the index as if
     * nothing were deleted.
     */
    static DocFilter matches(
            IndexReader reader,
            List<DirectoryReader> directoryReaders,
            Query query,
            boolean includeDeleted,
            ScanProgress progress)
            throws IOException, InvalidQueryException {
        Cancellation cancellation = new Cancellation(progress);
        List<DirectoryReader> readers = directoryReaders;
        if (includeDeleted) {
            readers = new ArrayList<>(directoryReaders.size());
            for (DirectoryReader directoryReader : directoryReaders) {
                readers.add(new UndeletedReader(directoryReader));
            }
        }
        IndexSearcher searcher = searcher(readers, IndexSearcher.getDefaultSimilarity(), cancellation);
        long start = System.nanoTime();
        try {
            cancellation.searched(0);
            Query rewritten = searcher.rewrite(built(searcher, query));
            FixedBitSet matches = new FixedBitSet(reader.maxDoc());
            searcher.search(
                    rewritten, new ProgressCollectorManager<>(new MatchCollectorManager(matches), cancellation));
            cancellation.check();
            cancellation.searched(reader.maxDoc());
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            return new DocFilter(matches, query.toString(), rewritten.toString(), took);
        } catch (ExitableDirectoryReader.ExitingReaderException e) {
            throw cancellation.stopped(e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    static Explanation explain(
            List<DirectoryReader> directoryReaders,
            Query query,
            Similarity similarity,
            int docId,
            ScanProgress progress)
            throws IOException, InvalidQueryException {
        Cancellation cancellation = new Cancellation(progress);
        IndexSearcher searcher = searcher(directoryReaders, similarity, cancellation);
        try {
            Explanation explanation = searcher.explain(query, docId);
            cancellation.check();
            return explanation;
        } catch (ExitableDirectoryReader.ExitingReaderException e) {
            throw cancellation.stopped(e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /**
     * Every match of {@code query} in the stored text values of {@code field} of a document, or of
     * each of its stored fields without one (ADR 0042, ADR 0043), up to {@code maxSpans} per value.
     */
    static DocumentHighlight highlight(
            IndexReader reader,
            List<DirectoryReader> directoryReaders,
            Query query,
            HighlightSpec spec,
            int docId,
            Optional<String> field,
            int maxSpans,
            ScanProgress progress)
            throws IOException, InvalidQueryException {
        Cancellation cancellation = new Cancellation(progress);
        IndexSearcher searcher = searcher(directoryReaders, IndexSearcher.getDefaultSimilarity(), cancellation);
        try {
            Query built = built(searcher, query);
            DocumentReader.StoredValues stored = new DocumentReader.StoredValues(
                    name -> field.map(name::equals).orElse(true));
            reader.storedFields().document(docId, stored);
            DocumentHighlight highlight = Highlights.document(searcher, built, spec, docId, stored.values, maxSpans);
            cancellation.check();
            return highlight;
        } catch (ExitableDirectoryReader.ExitingReaderException e) {
            throw cancellation.stopped(e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /** What highlights the hits of a search: its searcher, its built query and the spec. */
    private record Highlighting(IndexSearcher searcher, Query query, HighlightSpec spec) {}

    /**
     * A searcher over {@code directoryReaders}, each wrapped in an {@link ExitableDirectoryReader}, and
     * combined in a {@link MultiReader} if there are several (ADR 0021). Its document ids are those of
     * the index's reader, which combines them in the same order.
     */
    private static IndexSearcher searcher(
            List<DirectoryReader> directoryReaders, Similarity similarity, Cancellation cancellation)
            throws IOException {
        IndexReader[] exitable = new IndexReader[directoryReaders.size()];
        for (int i = 0; i < exitable.length; i++) {
            exitable[i] = ExitableDirectoryReader.wrap(directoryReaders.get(i), cancellation);
        }
        // Never closed: closing a wrapper would close the index's reader. The MultiReader holds no
        // reference that closing would release, as it only adds references to the wrappers.
        IndexSearcher searcher =
                new IndexSearcher(exitable.length == 1 ? exitable[0] : new MultiReader(exitable, false));
        searcher.setTimeout(cancellation);
        searcher.setSimilarity(similarity);
        // No query cache: a repeated search shows what it really costs.
        searcher.setQueryCache(null);
        return searcher;
    }

    /** The query to rewrite: a More-Like-This query is built from the searcher's reader first. */
    private static Query built(IndexSearcher searcher, Query query) throws IOException {
        return query instanceof MoreLikeQuery like ? like.build(searcher.getIndexReader()) : query;
    }

    private static List<Hit> hits(
            IndexReader reader,
            ScoreDoc[] page,
            Optional<Sort> sort,
            Optional<Set<String>> fields,
            Optional<Highlighting> highlighting)
            throws IOException {
        StoredFields storedFields = reader.storedFields();
        Predicate<String> wanted = fields.isPresent() ? fields.get()::contains : _ -> true;
        SortField[] sortFields = sort.map(Sort::getSort).orElse(new SortField[0]);
        List<Map<String, List<IndexDocument.StoredValue>>> stored = new ArrayList<>(page.length);
        for (ScoreDoc scoreDoc : page) {
            DocumentReader.StoredValues values = new DocumentReader.StoredValues(wanted);
            storedFields.document(scoreDoc.doc, values);
            stored.add(values.values);
        }
        List<List<FieldHighlight>> highlights = List.of();
        if (highlighting.isPresent()) {
            int[] docIds =
                    Arrays.stream(page).mapToInt(scoreDoc -> scoreDoc.doc).toArray();
            highlights = Highlights.passages(
                    highlighting.get().searcher(),
                    highlighting.get().query(),
                    highlighting.get().spec(),
                    docIds,
                    stored);
        }
        List<Hit> hits = new ArrayList<>(page.length);
        for (int h = 0; h < page.length; h++) {
            ScoreDoc scoreDoc = page[h];
            List<HitField> hitFields = stored.get(h).entrySet().stream()
                    .map(entry -> new HitField(entry.getKey(), List.copyOf(entry.getValue())))
                    .toList();
            List<SortValue> sortValues = new ArrayList<>(sortFields.length);
            if (scoreDoc instanceof FieldDoc fieldDoc) {
                for (int i = 0; i < sortFields.length; i++) {
                    sortValues.add(
                            numeric(sortFields[i]) && !hasNumber(reader, sortFields[i].getField(), scoreDoc.doc)
                                    ? SortValue.of(null)
                                    : SortValue.of(fieldDoc.fields[i]));
                }
            }
            hits.add(new Hit(
                    scoreDoc.doc,
                    scoreDoc.score,
                    List.copyOf(sortValues),
                    hitFields,
                    highlights.isEmpty() ? List.of() : highlights.get(h)));
        }
        return List.copyOf(hits);
    }

    /** Whether {@code sortField} sorts by numeric doc values, whose missing value Lucene fills in. */
    private static boolean numeric(SortField sortField) {
        return sortField instanceof SortedNumericSortField
                || (sortField.getField() != null
                        && switch (sortField.getType()) {
                            case INT, LONG, FLOAT, DOUBLE -> true;
                            default -> false;
                        });
    }

    /**
     * Whether the document has a value in the numeric or sorted numeric doc values of {@code field},
     * so that its sort value is not the missing value that Lucene put in its place (ADR 0025).
     */
    private static boolean hasNumber(IndexReader reader, String field, int docId) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        LeafReaderContext leaf = leaves.get(ReaderUtil.subIndex(docId, leaves));
        return DocValues.getSortedNumeric(leaf.reader(), field).advanceExact(docId - leaf.docBase);
    }

    /**
     * Stops a search once {@link ScanProgress} throws {@link CancellationException}. Lucene asks
     * {@link #shouldExit} on the searching thread only, and stops by itself once it says yes; {@link
     * #check} then throws the exception.
     */
    private static final class Cancellation implements QueryTimeout {

        private final ScanProgress progress;
        private long searched;
        private CancellationException cancelled;

        Cancellation(ScanProgress progress) {
            this.progress = progress;
        }

        /** Reports the documents searched so far, and throws if the search is cancelled. */
        void searched(long docs) {
            searched = docs;
            progress.scanned(docs);
        }

        @Override
        public boolean shouldExit() {
            if (cancelled == null) {
                try {
                    progress.scanned(searched);
                } catch (CancellationException e) {
                    cancelled = e;
                }
            }
            return cancelled != null;
        }

        /** Throws if Lucene stopped the search because it was cancelled. */
        void check() {
            if (cancelled != null) {
                throw cancelled;
            }
        }

        /** The exception for a search that the reader stopped. */
        CancellationException stopped(ExitableDirectoryReader.ExitingReaderException e) {
            CancellationException cancellation = cancelled != null ? cancelled : new CancellationException();
            cancellation.addSuppressed(e);
            return cancellation;
        }
    }

    /** Reports each segment's first document id as progress before the segment is searched. */
    private record ProgressCollectorManager<C extends Collector, T>(
            CollectorManager<C, T> manager, Cancellation cancellation)
            implements CollectorManager<ProgressCollector<C>, T> {

        @Override
        public ProgressCollector<C> newCollector() throws IOException {
            return new ProgressCollector<>(manager.newCollector(), cancellation);
        }

        @Override
        public T reduce(Collection<ProgressCollector<C>> collectors) throws IOException {
            return manager.reduce(
                    collectors.stream().map(ProgressCollector::collector).toList());
        }
    }

    private static final class ProgressCollector<C extends Collector> extends FilterCollector {

        private final C collector;
        private final Cancellation cancellation;

        ProgressCollector(C collector, Cancellation cancellation) {
            super(collector);
            this.collector = collector;
            this.cancellation = cancellation;
        }

        C collector() {
            return collector;
        }

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            cancellation.searched(context.docBase);
            return super.getLeafCollector(context);
        }
    }

    /**
     * Sets the bit of each matching document, by its id in the whole index. Every collector sets
     * bits in the same set: the searcher has no executor, so it collects one segment after another.
     */
    private record MatchCollectorManager(FixedBitSet matches) implements CollectorManager<Collector, Void> {

        @Override
        public Collector newCollector() {
            return new Collector() {
                @Override
                public LeafCollector getLeafCollector(LeafReaderContext context) {
                    int docBase = context.docBase;
                    return new LeafCollector() {
                        @Override
                        public void setScorer(Scorable scorer) {}

                        @Override
                        public void collect(int doc) {
                            matches.set(docBase + doc);
                        }
                    };
                }

                @Override
                public ScoreMode scoreMode() {
                    return ScoreMode.COMPLETE_NO_SCORES;
                }
            };
        }

        @Override
        public Void reduce(Collection<Collector> collectors) {
            return null;
        }
    }

    /**
     * Reads a directory reader as if none of its documents were deleted, so that deleted documents
     * match too. Never closed, like the {@link ExitableDirectoryReader} around it: closing it would
     * close the index's reader. Nothing may cache by it, since its leaves differ from the reader's.
     */
    private static final class UndeletedReader extends FilterDirectoryReader {

        UndeletedReader(DirectoryReader in) throws IOException {
            super(in, new SubReaderWrapper() {
                @Override
                public LeafReader wrap(LeafReader reader) {
                    return new UndeletedLeaf(reader);
                }
            });
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
            return new UndeletedReader(in);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return null;
        }
    }

    private static final class UndeletedLeaf extends FilterLeafReader {

        UndeletedLeaf(LeafReader in) {
            super(in);
        }

        @Override
        public Bits getLiveDocs() {
            return null;
        }

        @Override
        public int numDocs() {
            return maxDoc();
        }

        @Override
        public CacheHelper getCoreCacheHelper() {
            return null;
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return null;
        }
    }
}
