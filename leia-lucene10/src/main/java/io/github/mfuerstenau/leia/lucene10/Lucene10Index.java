package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.ClassicOptions;
import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.DocFilter;
import io.github.mfuerstenau.leia.core.FieldStats;
import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.IndexStats.CommitStats;
import io.github.mfuerstenau.leia.core.IndexStats.SegmentStats;
import io.github.mfuerstenau.leia.core.KnnQueries;
import io.github.mfuerstenau.leia.core.KnnVector;
import io.github.mfuerstenau.leia.core.PostingsPage;
import io.github.mfuerstenau.leia.core.Queries;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.Similarities;
import io.github.mfuerstenau.leia.core.StoredScan;
import io.github.mfuerstenau.leia.core.TermPage;
import io.github.mfuerstenau.leia.core.TermPosition;
import io.github.mfuerstenau.leia.core.TermVectorTerm;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Highlight;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.Points;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Scoring;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Terms;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongConsumer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;

/**
 * An index read with Lucene 10 through leia-core's {@link ReadOnlyIndex} (ADR 0065): each group
 * delegates to leia-core and maps its records to those of {@code leia-runtime}. It is read-only like
 * the rest of leia, and several directories open as one, as leia-core opens them (ADR 0021): one whose
 * fields conflict with those before it is skipped too.
 */
final class Lucene10Index implements RuntimeIndex, Documents, Terms, Search, Knn, Scoring {

    /** Lucene 10's default similarity, which {@link Search} scores with. */
    private static final Similarity DEFAULT_SIMILARITY = new Similarity.Bm25(1.2f, 0.75f);

    private final ReadOnlyIndex index;
    private final DirectoryKind directoryKind;
    private final List<Field> fields;

    private Lucene10Index(ReadOnlyIndex index, DirectoryKind directoryKind) throws IOException {
        this.index = index;
        this.directoryKind = directoryKind;
        try {
            this.fields =
                    index.stats().fields().stream().map(Lucene10Index::field).toList();
        } catch (IOException | RuntimeException e) {
            try {
                index.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Opens the latest commit of each of {@code dirs} with a reader, and combines those that open.
     *
     * @throws IOException if none of them can be read
     */
    static Lucene10Index open(List<Path> dirs, DirectoryKind kind) throws IOException {
        ReadOnlyIndex index;
        try {
            index = ReadOnlyIndex.open(dirs, directoryImpl(kind));
        } catch (IndexOpenException e) {
            throw new IOException(e.getMessage(), e);
        }
        return new Lucene10Index(index, kind);
    }

    static DirectoryImpl directoryImpl(DirectoryKind kind) {
        return switch (kind) {
            case FS -> DirectoryImpl.FS;
            case MMAP -> DirectoryImpl.MMAP;
            case NIO -> DirectoryImpl.NIO;
        };
    }

    @Override
    public List<Path> directories() {
        return index.directories();
    }

    @Override
    public List<Skipped> skipped() {
        return index.skipped().stream()
                .map(skipped -> new Skipped(skipped.path(), skipped.message()))
                .toList();
    }

    @Override
    public DirectoryKind directory() {
        return directoryKind;
    }

    @Override
    public int maxDoc() {
        return index.maxDoc();
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Optional<Documents> documents() {
        return Optional.of(this);
    }

    @Override
    public Optional<Terms> terms() {
        return Optional.of(this);
    }

    @Override
    public Optional<Search> search() {
        return Optional.of(this);
    }

    @Override
    public Optional<Points> points() {
        return Optional.of(new Lucene10Points(index));
    }

    @Override
    public Optional<Knn> knn() {
        return Optional.of(this);
    }

    @Override
    public Optional<Rebuild> rebuild() {
        return Optional.of(new Lucene10Rebuild(index));
    }

    @Override
    public Optional<Highlight> highlight() {
        return Optional.of(new Lucene10Highlight(index));
    }

    @Override
    public Optional<Scoring> scoring() {
        return Optional.of(this);
    }

    @Override
    public Optional<RuntimeIndex> reopen() throws IOException {
        Optional<ReadOnlyIndex> reopened;
        try {
            reopened = index.reopen();
        } catch (IndexOpenException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (reopened.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Lucene10Index(reopened.get(), directoryKind));
    }

    @Override
    public Stats stats() throws IOException {
        io.github.mfuerstenau.leia.core.IndexStats stats = index.stats();
        Map<Integer, String> versions = segmentVersions();
        List<IndexStats> indexes = new ArrayList<>();
        for (CommitStats commit : stats.indexes()) {
            int docBase = commit.summary().docBase();
            List<Segment> segments = new ArrayList<>();
            List<String> segmentVersions = new ArrayList<>();
            for (SegmentStats segment : commit.segments()) {
                segments.add(
                        new Segment(segment.name(), segment.docBase() - docBase, segment.maxDoc(), segment.numDocs()));
                segmentVersions.add(versions.getOrDefault(segment.docBase(), "?"));
            }
            indexes.add(new IndexStats(
                    commit.summary().path(),
                    docBase,
                    commit.summary().segmentsFile(),
                    commit.generation(),
                    commit.version(),
                    commit.summary().numDocs(),
                    commit.summary().maxDoc(),
                    List.copyOf(segmentVersions),
                    Optional.ofNullable(commit.commitLuceneVersion()).map(Object::toString),
                    OptionalInt.of(commit.summary().createdVersionMajor()),
                    Map.copyOf(commit.userData()),
                    List.copyOf(segments)));
        }
        return new Stats(stats.numDocs(), stats.maxDoc(), List.copyOf(indexes), fields);
    }

    /** The Lucene version that wrote each segment of the reader, by the segment's docBase in the index. */
    private Map<Integer, String> segmentVersions() {
        Map<Integer, String> versions = new HashMap<>();
        for (LeafReaderContext leaf : index.reader().leaves()) {
            if (FilterLeafReader.unwrap(leaf.reader()) instanceof SegmentReader segment) {
                versions.put(
                        leaf.docBase, segment.getSegmentInfo().info.getVersion().toString());
            }
        }
        return versions;
    }

    private static Field field(FieldStats field) {
        Optional<TermStats> terms = field.terms()
                .filter(stats -> stats.docCount() >= 0 && stats.sumDocFreq() >= 0)
                .map(stats -> new TermStats(
                        stats.docCount(),
                        stats.sumDocFreq(),
                        // -1 if frequencies are not indexed, where each one is 1.
                        stats.sumTotalTermFreq() < 0 ? stats.sumDocFreq() : stats.sumTotalTermFreq()));
        Optional<VectorFormat> vectors = field.vectorDimension() == 0
                ? Optional.empty()
                : Optional.of(new VectorFormat(
                        field.vectorDimension(),
                        VectorEncoding.valueOf(field.vectorEncoding().name()),
                        VectorSimilarity.valueOf(field.vectorSimilarity().name())));
        return new Field(
                field.name(),
                field.number(),
                IndexOptions.valueOf(field.indexOptions().name()),
                field.hasNorms(),
                field.hasPayloads(),
                field.hasTermVectors(),
                DocValuesType.valueOf(field.docValuesType().name()),
                field.pointDimensionCount(),
                field.pointNumBytes(),
                terms,
                vectors);
    }

    @Override
    public IndexDocument document(int docId) throws IOException {
        io.github.mfuerstenau.leia.core.IndexDocument document = index.document(docId);
        return new IndexDocument(
                document.docId(),
                document.deleted(),
                document.fields().stream()
                        .map(field -> new DocumentField(
                                field.name(),
                                field.norm(),
                                field.stored().stream()
                                        .map(Lucene10Index::storedValue)
                                        .toList(),
                                field.docValues().map(Lucene10Index::docValues),
                                field.hasTermVector(),
                                field.vector().map(Lucene10Index::vector)))
                        .toList());
    }

    private static DocValues docValues(io.github.mfuerstenau.leia.core.IndexDocument.DocValues docValues) {
        return new DocValues(
                DocValuesType.valueOf(docValues.type().name()),
                docValues.numbers(),
                docValues.values().stream().map(Lucene10Index::bytes).toList());
    }

    static StoredValue storedValue(io.github.mfuerstenau.leia.core.IndexDocument.StoredValue value) {
        return switch (value) {
            case io.github.mfuerstenau.leia.core.IndexDocument.StoredValue.Text text ->
                new StoredValue.Text(text.value());
            case io.github.mfuerstenau.leia.core.IndexDocument.StoredValue.Binary binary ->
                new StoredValue.Binary(bytes(binary.value()));
            case io.github.mfuerstenau.leia.core.IndexDocument.StoredValue.IntValue number ->
                new StoredValue.Numeric(number.value());
            case io.github.mfuerstenau.leia.core.IndexDocument.StoredValue.LongValue number ->
                new StoredValue.Numeric(number.value());
            case io.github.mfuerstenau.leia.core.IndexDocument.StoredValue.FloatValue number ->
                new StoredValue.Numeric(number.value());
            case io.github.mfuerstenau.leia.core.IndexDocument.StoredValue.DoubleValue number ->
                new StoredValue.Numeric(number.value());
        };
    }

    private static Vector vector(KnnVector vector) {
        return switch (vector) {
            case KnnVector.Floats floats -> new Vector.Floats(floats.values());
            case KnnVector.Bytes bytes -> new Vector.Bytes(bytes.values());
        };
    }

    private static KnnVector knnVector(Vector vector) {
        return switch (vector) {
            case Vector.Floats floats -> new KnnVector.Floats(floats.values());
            case Vector.Bytes bytes -> new KnnVector.Bytes(bytes.values());
        };
    }

    /** The bytes of {@code ref}, a copy. */
    static byte[] bytes(BytesRef ref) {
        return Arrays.copyOfRange(ref.bytes, ref.offset, ref.offset + ref.length);
    }

    @Override
    public List<StoredDocument> storedValues(
            int fromDoc, int toDoc, Optional<Set<String>> fields, boolean includeDeleted) throws IOException {
        if (fromDoc < 0 || fromDoc > toDoc || toDoc > index.maxDoc()) {
            throw new IllegalArgumentException(
                    "No documents " + fromDoc + " to " + toDoc + ": maxDoc is " + index.maxDoc());
        }
        List<StoredDocument> documents = new ArrayList<>();
        for (StoredScan.Document document :
                index.storedValues(fields, includeDeleted).read(fromDoc, toDoc)) {
            Map<String, List<StoredValue>> values = new LinkedHashMap<>();
            document.values()
                    .forEach((field, stored) -> values.put(
                            field,
                            stored.stream().map(Lucene10Index::storedValue).toList()));
            documents.add(new StoredDocument(document.docId(), document.deleted(), values));
        }
        return List.copyOf(documents);
    }

    @Override
    public List<VectorTerm> termVector(int docId, String field) throws IOException {
        checkDoc(docId);
        if (!index.hasField(field)) {
            return List.of();
        }
        return index.termVector(docId, field).stream()
                .map(Lucene10Index::vectorTerm)
                .toList();
    }

    static VectorTerm vectorTerm(TermVectorTerm term) {
        return new VectorTerm(
                bytes(term.term()),
                term.freq(),
                term.positions().stream().map(Lucene10Index::position).toList());
    }

    private static Position position(TermPosition position) {
        return new Position(
                position.position(),
                position.startOffset(),
                position.endOffset(),
                position.payload().map(Lucene10Index::bytes));
    }

    @Override
    public TermsPage terms(String field, Optional<byte[]> from, int limit) throws IOException {
        if (!index.hasField(field)) {
            return new TermsPage(List.of(), Optional.empty());
        }
        TermPage page = index.terms(field, from.map(BytesRef::new), limit);
        return new TermsPage(
                page.terms().stream()
                        .map(term -> new TermEntry(bytes(term.term()), term.docFreq(), term.totalTermFreq()))
                        .toList(),
                page.next().map(Lucene10Index::bytes));
    }

    /** {@inheritDoc} Deleted documents are read too, and marked. */
    @Override
    public PostingsPage postings(String field, byte[] term, int fromDoc, int limit) throws IOException {
        Optional<io.github.mfuerstenau.leia.core.PostingsPage> page = index.hasField(field)
                ? index.postings(field, new BytesRef(term), Math.max(fromDoc, 0), limit)
                : Optional.empty();
        if (page.isEmpty()) {
            return new PostingsPage(0, 0, List.of(), OptionalInt.empty());
        }
        return new PostingsPage(
                page.get().docFreq(),
                page.get().totalTermFreq(),
                page.get().postings().stream()
                        .map(posting -> new Posting(
                                posting.docId(),
                                // 1 without frequencies, as Lucene's postings have it.
                                posting.freq().orElse(1),
                                posting.deleted()))
                        .toList(),
                page.get().nextDoc());
    }

    @Override
    public Optional<List<Position>> positions(int docId, String field, byte[] term) throws IOException {
        checkDoc(docId);
        if (!index.hasField(field)) {
            return Optional.empty();
        }
        return index.positions(docId, field, new BytesRef(term))
                .map(positions ->
                        positions.stream().map(Lucene10Index::position).toList());
    }

    @Override
    public FieldTerms topTerms(String field, int topN, LongConsumer progress) throws IOException {
        if (!index.hasField(field)) {
            progress.accept(0);
            return new FieldTerms(0, List.of());
        }
        io.github.mfuerstenau.leia.core.FieldTerms terms = index.fieldTerms(field, topN, progress::accept);
        return new FieldTerms(
                terms.uniqueTerms(),
                terms.topTerms().stream()
                        .map(term -> new TermEntry(bytes(term.term()), term.docFreq(), term.totalTermFreq()))
                        .toList());
    }

    /** A query of Lucene 10. */
    private record Lucene10Query(Query query) implements ParsedQuery {

        @Override
        public String toString() {
            return query.toString();
        }
    }

    /** The Lucene query of {@code parsed}. */
    static Query query(ParsedQuery parsed) {
        if (!(parsed instanceof Lucene10Query lucene10)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        return lucene10.query();
    }

    @Override
    public Set<String> analyzers() {
        return Lucene10Analyzers.names();
    }

    /**
     * {@inheritDoc} With leia-core's classic parser, which reads date ranges (ADR 0026), and fuzzy terms
     * of up to 2 edits; it splits on whitespace only to generate phrase queries, which needs it.
     */
    @Override
    public ParsedQuery parse(String expression, ParserOptions options, AnalyzerSpec spec)
            throws InvalidQueryException, UnsupportedAnalyzerException {
        Analyzer analyzer = Lucene10Analyzers.analyzer(spec);
        try {
            return new Lucene10Query(Queries.classic(
                    expression,
                    options.defaultField(),
                    new io.github.mfuerstenau.leia.core.ParserOptions(
                            options.defaultAnd() ? Queries.Operator.AND : Queries.Operator.OR,
                            options.allowLeadingWildcard(),
                            options.phraseSlop(),
                            options.fuzzyPrefixLength(),
                            options.enablePositionIncrements()),
                    new ClassicOptions(
                            ClassicOptions.MAX_FUZZY_EDITS,
                            options.autoGeneratePhraseQueries(),
                            options.autoGeneratePhraseQueries()),
                    analyzer));
        } catch (io.github.mfuerstenau.leia.core.InvalidQueryException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        } finally {
            analyzer.close();
        }
    }

    @Override
    public ParsedQuery term(String field, byte[] term) {
        return new Lucene10Query(Queries.term(field, new BytesRef(term)));
    }

    /** {@inheritDoc} It scores with Lucene 10's default, BM25 with k1 1.2 and b 0.75. */
    @Override
    public SearchResults search(
            ParsedQuery parsed, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException {
        return search(parsed, DEFAULT_SIMILARITY, List.of(), offset, limit, fields, progress);
    }

    /**
     * {@inheritDoc} Through leia-core's search, which asks the progress while it rewrites, reads terms
     * and collects.
     *
     * @throws IllegalArgumentException also if the rewritten query has too many clauses
     */
    @Override
    public SearchResults search(
            ParsedQuery parsed,
            Similarity similarity,
            List<SortKey> sortKeys,
            int offset,
            int limit,
            Optional<List<String>> fields,
            LongConsumer progress)
            throws IOException {
        Optional<Sort> sort = sortKeys.isEmpty() ? Optional.empty() : Optional.of(sort(sortKeys));
        io.github.mfuerstenau.leia.core.SearchResults results;
        try {
            results = index.search(
                    query(parsed),
                    similarity(similarity),
                    sort,
                    offset,
                    limit,
                    true,
                    fields.map(Set::copyOf),
                    progress::accept);
        } catch (io.github.mfuerstenau.leia.core.InvalidQueryException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        List<Hit> hits = new ArrayList<>();
        for (io.github.mfuerstenau.leia.core.SearchResults.Hit hit : results.hits()) {
            Map<String, List<StoredValue>> values = new LinkedHashMap<>();
            for (io.github.mfuerstenau.leia.core.SearchResults.HitField field : hit.fields()) {
                values.put(
                        field.name(),
                        field.values().stream().map(Lucene10Index::storedValue).toList());
            }
            hits.add(new Hit(
                    hit.docId(),
                    hit.score(),
                    hit.sortValues().stream()
                            .map(value ->
                                    new SortValue(value.number(), value.term().map(Lucene10Index::bytes)))
                            .toList(),
                    values));
        }
        return new SearchResults(
                results.query(),
                results.rewrittenQuery(),
                results.totalHits().value(),
                List.copyOf(hits),
                results.took().toMillis());
    }

    private static org.apache.lucene.search.similarities.Similarity similarity(Similarity similarity) {
        return switch (similarity) {
            case Similarity.Bm25 bm25 -> Similarities.bm25(bm25.k1(), bm25.b());
            case Similarity.Classic _ -> Similarities.classic();
            case Similarity.Bool _ -> Similarities.bool();
        };
    }

    /**
     * The Lucene sort of {@code keys}, as leia-core sorts (ADR 0012, ADR 0025).
     *
     * @throws IllegalArgumentException for a field that isn't in the index, or has no doc values that
     *     sort as its key's type or with its key's selector
     */
    private Sort sort(List<SortKey> keys) {
        List<SortField> sortFields = new ArrayList<>(keys.size());
        for (SortKey key : keys) {
            sortFields.add(
                    switch (key) {
                        case SortKey.Score score -> io.github.mfuerstenau.leia.core.SortType.score(score.reverse());
                        case SortKey.Doc doc -> io.github.mfuerstenau.leia.core.SortType.doc(doc.reverse());
                        case SortKey.Field field -> {
                            FieldInfo info = index.fieldInfo(field.field())
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "No field '" + field.field() + "' in the index"));
                            yield io.github.mfuerstenau.leia.core.SortType.valueOf(
                                            field.type().name())
                                    .sortField(
                                            info,
                                            field.reverse(),
                                            io.github.mfuerstenau.leia.core.SortSelector.valueOf(
                                                    field.selector().name()),
                                            io.github.mfuerstenau.leia.core.SortMissing.valueOf(
                                                    field.missing().name()));
                        }
                    });
        }
        return new Sort(sortFields.toArray(SortField[]::new));
    }

    /**
     * {@inheritDoc} Through leia-core's matching, which reads the index as if nothing were deleted
     * for {@code includeDeleted}.
     *
     * @throws IllegalArgumentException also if the rewritten query has too many clauses
     */
    @Override
    public Matches matches(ParsedQuery parsed, boolean includeDeleted, LongConsumer progress) throws IOException {
        DocFilter filter;
        try {
            filter = index.matches(query(parsed), includeDeleted, progress::accept);
        } catch (io.github.mfuerstenau.leia.core.InvalidQueryException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        BitSet docs = new BitSet(index.maxDoc());
        for (int doc = filter.next(0); doc != DocIdSetIterator.NO_MORE_DOCS; doc = filter.next(doc + 1)) {
            docs.set(doc);
        }
        return new Matches(
                docs, filter.query(), filter.rewrittenQuery(), filter.took().toMillis());
    }

    @Override
    public boolean matchesDeleted() {
        return true;
    }

    /** {@inheritDoc} It scores with Lucene 10's default, BM25 with k1 1.2 and b 0.75. */
    @Override
    public Explanation explain(ParsedQuery parsed, int docId, LongConsumer progress) throws IOException {
        return explain(parsed, DEFAULT_SIMILARITY, docId, progress);
    }

    /** @throws IllegalArgumentException also if the rewritten query has too many clauses */
    @Override
    public Explanation explain(ParsedQuery parsed, Similarity similarity, int docId, LongConsumer progress)
            throws IOException {
        try {
            return explanation(index.explain(query(parsed), similarity(similarity), docId, progress::accept));
        } catch (io.github.mfuerstenau.leia.core.InvalidQueryException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static Explanation explanation(org.apache.lucene.search.Explanation explanation) {
        return new Explanation(
                explanation.isMatch(),
                explanation.getValue(),
                explanation.getDescription(),
                Arrays.stream(explanation.getDetails())
                        .map(Lucene10Index::explanation)
                        .toList());
    }

    /** {@inheritDoc} Lucene 10's KNN query of the field's encoding, through leia-core; it searches when rewritten. */
    @Override
    public ParsedQuery approximate(String field, Vector target, int k, Optional<ParsedQuery> filter) {
        return new Lucene10Query(
                KnnQueries.approximate(vectorField(field), knnVector(target), k, filter.map(Lucene10Index::query)));
    }

    @Override
    public ParsedQuery exact(String field, Vector target, Optional<ParsedQuery> filter) {
        return new Lucene10Query(
                KnnQueries.exact(vectorField(field), knnVector(target), filter.map(Lucene10Index::query)));
    }

    @Override
    public Optional<Vector> vector(int docId, String field) throws IOException {
        vectorField(field);
        return index.vector(docId, field).map(Lucene10Index::vector);
    }

    /** The field {@code field}, which has vectors. */
    private FieldInfo vectorField(String field) {
        return index.fieldInfo(field)
                .filter(info -> info.getVectorDimension() > 0)
                .orElseThrow(() -> new IllegalArgumentException("No KNN vectors in field " + field));
    }

    private void checkDoc(int docId) {
        if (!index.hasDocId(docId)) {
            throw new IllegalArgumentException("No document " + docId + ": maxDoc is " + index.maxDoc());
        }
    }

    @Override
    public void close() throws IOException {
        index.close();
    }
}
