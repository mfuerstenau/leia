package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import io.github.mfuerstenau.leia.core.SearchResults.HitField;
import io.github.mfuerstenau.leia.core.SearchResults.SortValue;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.ExitableDirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.FilterCollector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.search.similarities.Similarity;

/**
 * Runs searches and explains them, cooperatively cancellable (ADR 0010). Each call searches with a
 * new {@link IndexSearcher} over an {@link ExitableDirectoryReader}, whose {@link QueryTimeout} asks
 * the caller's {@link ScanProgress} whether to go on: it is checked while rewriting, enumerating
 * terms and collecting hits. The searcher scores with the caller's {@link Similarity} (ADR 0012).
 */
final class Searcher {

    /** Without an exact count, counting stops after this many hits, as in Luke. */
    static final int TOTAL_HITS_THRESHOLD = 1000;

    private Searcher() {}

    static SearchResults search(
            DirectoryReader reader,
            Query query,
            Similarity similarity,
            Optional<Sort> sort,
            int offset,
            int limit,
            boolean exactTotalHits,
            Optional<Set<String>> fields,
            ScanProgress progress)
            throws IOException, InvalidQueryException {
        Cancellation cancellation = new Cancellation(progress);
        IndexSearcher searcher = searcher(reader, similarity, cancellation);
        long start = System.nanoTime();
        try {
            cancellation.searched(0);
            Query rewritten = searcher.rewrite(query);
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
            cancellation.searched(reader.maxDoc());
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            return new SearchResults(
                    query.toString(), rewritten.toString(), top.totalHits, hits(reader, page, fields), took);
        } catch (ExitableDirectoryReader.ExitingReaderException e) {
            throw cancellation.stopped(e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    static Explanation explain(
            DirectoryReader reader, Query query, Similarity similarity, int docId, ScanProgress progress)
            throws IOException, InvalidQueryException {
        Cancellation cancellation = new Cancellation(progress);
        IndexSearcher searcher = searcher(reader, similarity, cancellation);
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

    private static IndexSearcher searcher(DirectoryReader reader, Similarity similarity, Cancellation cancellation)
            throws IOException {
        // Never closed: closing the wrapper would close the index's reader.
        IndexSearcher searcher = new IndexSearcher(ExitableDirectoryReader.wrap(reader, cancellation));
        searcher.setTimeout(cancellation);
        searcher.setSimilarity(similarity);
        // No query cache: a repeated search shows what it really costs.
        searcher.setQueryCache(null);
        return searcher;
    }

    private static List<Hit> hits(DirectoryReader reader, ScoreDoc[] page, Optional<Set<String>> fields)
            throws IOException {
        StoredFields storedFields = reader.storedFields();
        Predicate<String> wanted = fields.isPresent() ? fields.get()::contains : _ -> true;
        List<Hit> hits = new ArrayList<>(page.length);
        for (ScoreDoc scoreDoc : page) {
            DocumentReader.StoredValues stored = new DocumentReader.StoredValues(wanted);
            storedFields.document(scoreDoc.doc, stored);
            List<HitField> hitFields = stored.values.entrySet().stream()
                    .map(entry -> new HitField(entry.getKey(), List.copyOf(entry.getValue())))
                    .toList();
            List<SortValue> sortValues = scoreDoc instanceof FieldDoc fieldDoc
                    ? Arrays.stream(fieldDoc.fields).map(SortValue::of).toList()
                    : List.of();
            hits.add(new Hit(scoreDoc.doc, scoreDoc.score, sortValues, hitFields));
        }
        return List.copyOf(hits);
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
    private record ProgressCollectorManager<C extends Collector, T extends TopDocs>(
            CollectorManager<C, T> manager, Cancellation cancellation)
            implements CollectorManager<ProgressCollector<C>, TopDocs> {

        @Override
        public ProgressCollector<C> newCollector() throws IOException {
            return new ProgressCollector<>(manager.newCollector(), cancellation);
        }

        @Override
        public TopDocs reduce(Collection<ProgressCollector<C>> collectors) throws IOException {
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
}
