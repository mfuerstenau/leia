package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.Points;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Terms;
import io.github.mfuerstenau.leia.runtime.support.IndexParts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongConsumer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnByteVectorQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * An index created by Lucene 8.x, read with Lucene 9.12 (ADR 0049). Lucene 10 can't read such an
 * index, even once 9.x has written to it: it reads indexes created by 9 and 10 only. The segments of
 * 9.x can have KNN vectors.
 *
 * <p>Lucene is relocated in this module's jar, and leia reaches it only through {@link
 * Lucene9Runtime} and the interfaces of {@code leia-runtime}. It is read-only like the rest of leia: it
 * opens readers, never an {@code IndexWriter}, through directories without locks. Several directories
 * open as one, in a {@link MultiReader} (ADR 0045). A reader of Lucene 9 can be used from several
 * threads, so jobs can read it. Soft-deleted documents are live, as a plain {@code DirectoryReader}
 * has them.
 */
final class Lucene9Index implements RuntimeIndex, Documents, Terms, Search, Knn {

    /** How many terms {@link #topTerms} scans between two reports of its progress. */
    static final int PROGRESS_INTERVAL = 1024;

    /** A directory that opened, and its reader. */
    private record Part(Path dir, Directory directory, DirectoryReader reader) {}

    private final DirectoryKind directoryKind;
    private final List<Part> parts;
    private final List<Skipped> skipped;
    private final IndexReader reader;
    private final FieldInfos fieldInfos;
    private final List<Field> fields;

    private Lucene9Index(DirectoryKind directoryKind, List<Part> parts, List<Skipped> skipped) throws IOException {
        this.directoryKind = directoryKind;
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        this.reader = parts.size() == 1
                ? parts.getFirst().reader()
                : new MultiReader(parts.stream().map(Part::reader).toArray(IndexReader[]::new), true);
        this.fieldInfos = FieldInfos.getMergedFieldInfos(reader);
        this.fields = readFields();
    }

    /**
     * Opens the latest commit of each of {@code dirs} with a reader, and combines those that open.
     *
     * @throws IOException if none of them can be read
     */
    static Lucene9Index open(List<Path> dirs, DirectoryKind kind) throws IOException {
        IndexParts.Opened<Part> opened = IndexParts.open(dirs, dir -> openPart(dir, kind));
        try {
            return new Lucene9Index(kind, opened.parts(), opened.skipped());
        } catch (IOException | RuntimeException e) {
            for (Part part : opened.parts()) {
                part.reader().close();
                part.directory().close();
            }
            throw e;
        }
    }

    private static Part openPart(Path dir, DirectoryKind kind) throws IOException {
        Directory directory = Directories.open(dir, kind);
        try {
            // A reader takes no lock and changes nothing.
            return new Part(dir, directory, DirectoryReader.open(directory));
        } catch (IOException | RuntimeException e) {
            directory.close();
            throw e;
        }
    }

    @Override
    public List<Path> directories() {
        return parts.stream().map(Part::dir).toList();
    }

    @Override
    public List<Skipped> skipped() {
        return skipped;
    }

    @Override
    public DirectoryKind directory() {
        return directoryKind;
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
        return Optional.of(new Lucene9Points(reader, fieldInfos));
    }

    /** {@inheritDoc} Only segments written by 9.x can have vectors. */
    @Override
    public Optional<Knn> knn() {
        return Optional.of(this);
    }

    @Override
    public Stats stats() throws IOException {
        List<IndexStats> indexes = new ArrayList<>();
        int docBase = 0;
        for (Part part : parts) {
            IndexCommit commit = part.reader().getIndexCommit();
            SegmentInfos infos = SegmentInfos.readCommit(part.directory(), commit.getSegmentsFileName());
            List<String> versions = new ArrayList<>();
            for (SegmentCommitInfo info : infos) {
                versions.add(Lucene9Commits.version(info));
            }
            indexes.add(new IndexStats(
                    part.dir(),
                    docBase,
                    commit.getSegmentsFileName(),
                    commit.getGeneration(),
                    infos.getVersion(),
                    part.reader().numDocs(),
                    part.reader().maxDoc(),
                    List.copyOf(versions),
                    Optional.ofNullable(infos.getCommitLuceneVersion()).map(Object::toString),
                    // 8 for every index this runtime opens; it says how the norms are encoded.
                    OptionalInt.of(infos.getIndexCreatedVersionMajor()),
                    Map.copyOf(commit.getUserData())));
            docBase += part.reader().maxDoc();
        }
        return new Stats(reader.numDocs(), reader.maxDoc(), List.copyOf(indexes), fields());
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    private List<Field> readFields() throws IOException {
        List<Field> result = new ArrayList<>();
        for (FieldInfo info : fieldInfos) {
            IndexOptions options = switch (info.getIndexOptions()) {
                case NONE -> IndexOptions.NONE;
                case DOCS -> IndexOptions.DOCS;
                case DOCS_AND_FREQS -> IndexOptions.DOCS_AND_FREQS;
                case DOCS_AND_FREQS_AND_POSITIONS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
                case DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS;
            };
            result.add(new Field(
                    info.name,
                    info.number,
                    options,
                    info.hasNorms(),
                    info.hasPayloads(),
                    info.hasVectors(),
                    docValuesType(info.getDocValuesType()),
                    info.getPointDimensionCount(),
                    info.getPointNumBytes(),
                    termStats(info.name),
                    vectorFormat(info)));
        }
        result.sort(Comparator.comparingInt(Field::number));
        return List.copyOf(result);
    }

    /** How {@code info}'s KNN vectors are stored; empty without them. */
    private static Optional<VectorFormat> vectorFormat(FieldInfo info) {
        if (info.getVectorDimension() == 0) {
            return Optional.empty();
        }
        VectorEncoding encoding = switch (info.getVectorEncoding()) {
            case BYTE -> VectorEncoding.BYTE;
            case FLOAT32 -> VectorEncoding.FLOAT32;
        };
        VectorSimilarity similarity = switch (info.getVectorSimilarityFunction()) {
            case EUCLIDEAN -> VectorSimilarity.EUCLIDEAN;
            case DOT_PRODUCT -> VectorSimilarity.DOT_PRODUCT;
            case COSINE -> VectorSimilarity.COSINE;
            case MAXIMUM_INNER_PRODUCT -> VectorSimilarity.MAXIMUM_INNER_PRODUCT;
        };
        return Optional.of(new VectorFormat(info.getVectorDimension(), encoding, similarity));
    }

    private static DocValuesType docValuesType(org.apache.lucene.index.DocValuesType type) {
        return switch (type) {
            case NONE -> DocValuesType.NONE;
            case NUMERIC -> DocValuesType.NUMERIC;
            case BINARY -> DocValuesType.BINARY;
            case SORTED -> DocValuesType.SORTED;
            case SORTED_NUMERIC -> DocValuesType.SORTED_NUMERIC;
            case SORTED_SET -> DocValuesType.SORTED_SET;
        };
    }

    /** The postings statistics of {@code field}; empty if it has no postings or they aren't recorded. */
    private Optional<TermStats> termStats(String field) throws IOException {
        org.apache.lucene.index.Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null || terms.getDocCount() < 0 || terms.getSumDocFreq() < 0) {
            return Optional.empty();
        }
        long sumTotalTermFreq = terms.getSumTotalTermFreq();
        // -1 if frequencies are not indexed, where each one is 1.
        return Optional.of(new TermStats(
                terms.getDocCount(),
                terms.getSumDocFreq(),
                sumTotalTermFreq < 0 ? terms.getSumDocFreq() : sumTotalTermFreq));
    }

    @Override
    public int maxDoc() {
        return reader.maxDoc();
    }

    @Override
    public IndexDocument document(int docId) throws IOException {
        checkDoc(docId);
        Document document = reader.storedFields().document(docId);
        Map<String, List<StoredValue>> stored = new HashMap<>();
        for (IndexableField field : document.getFields()) {
            stored.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
        }
        Fields vectors = reader.termVectors().get(docId);
        LeafReaderContext leaf = leaf(docId);
        int leafDoc = docId - leaf.docBase;
        List<DocumentField> result = new ArrayList<>();
        for (Field field : fields) {
            OptionalLong norm = OptionalLong.empty();
            if (field.hasNorms()) {
                NumericDocValues norms = leaf.reader().getNormValues(field.name());
                if (norms != null && norms.advanceExact(leafDoc)) {
                    norm = OptionalLong.of(norms.longValue());
                }
            }
            boolean hasTermVector = vectors != null && vectors.terms(field.name()) != null;
            result.add(new DocumentField(
                    field.name(),
                    norm,
                    List.copyOf(stored.getOrDefault(field.name(), List.of())),
                    docValues(leaf.reader(), leafDoc, field),
                    hasTermVector,
                    vector(leaf.reader(), leafDoc, field)));
        }
        Bits liveDocs = MultiBits.getLiveDocs(reader);
        return new IndexDocument(docId, liveDocs != null && !liveDocs.get(docId), List.copyOf(result));
    }

    /**
     * The doc values of {@code doc} of {@code leaf} in {@code field}, through 7.0's iterators: a fresh
     * one for each read, since an iterator only goes forward.
     */
    private static Optional<DocValues> docValues(LeafReader leaf, int doc, Field field) throws IOException {
        String name = field.name();
        List<Long> numbers = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        switch (field.docValuesType()) {
            case NONE -> {
                return Optional.empty();
            }
            case NUMERIC -> {
                NumericDocValues numeric = leaf.getNumericDocValues(name);
                if (numeric == null || !numeric.advanceExact(doc)) {
                    return Optional.empty();
                }
                numbers.add(numeric.longValue());
            }
            case BINARY -> {
                BinaryDocValues binary = leaf.getBinaryDocValues(name);
                if (binary == null || !binary.advanceExact(doc)) {
                    return Optional.empty();
                }
                values.add(BytesRef.deepCopyOf(binary.binaryValue()).bytes);
            }
            case SORTED -> {
                SortedDocValues sorted = leaf.getSortedDocValues(name);
                if (sorted == null || !sorted.advanceExact(doc)) {
                    return Optional.empty();
                }
                values.add(BytesRef.deepCopyOf(sorted.lookupOrd(sorted.ordValue())).bytes);
            }
            case SORTED_NUMERIC -> {
                SortedNumericDocValues sorted = leaf.getSortedNumericDocValues(name);
                if (sorted == null || !sorted.advanceExact(doc)) {
                    return Optional.empty();
                }
                for (int i = 0; i < sorted.docValueCount(); i++) {
                    numbers.add(sorted.nextValue());
                }
            }
            case SORTED_SET -> {
                SortedSetDocValues set = leaf.getSortedSetDocValues(name);
                if (set == null || !set.advanceExact(doc)) {
                    return Optional.empty();
                }
                for (int i = 0; i < set.docValueCount(); i++) {
                    values.add(BytesRef.deepCopyOf(set.lookupOrd(set.nextOrd())).bytes);
                }
            }
        }
        return Optional.of(new DocValues(field.docValuesType(), List.copyOf(numbers), List.copyOf(values)));
    }

    /**
     * The KNN vector of {@code doc} of {@code leaf} in {@code field}, a copy, read through a fresh
     * iterator as doc values are; empty if it has none, as a document of a segment of 8.x never has.
     */
    private static Optional<Vector> vector(LeafReader leaf, int doc, Field field) throws IOException {
        if (field.vectors().isEmpty()) {
            return Optional.empty();
        }
        FieldInfo info = leaf.getFieldInfos().fieldInfo(field.name());
        if (info == null || info.getVectorDimension() == 0) {
            return Optional.empty();
        }
        return switch (field.vectors().get().encoding()) {
            case FLOAT32 -> {
                FloatVectorValues values = leaf.getFloatVectorValues(field.name());
                yield values == null || values.advance(doc) != doc
                        ? Optional.empty()
                        : Optional.of(new Vector.Floats(values.vectorValue().clone()));
            }
            case BYTE -> {
                ByteVectorValues values = leaf.getByteVectorValues(field.name());
                yield values == null || values.advance(doc) != doc
                        ? Optional.empty()
                        : Optional.of(new Vector.Bytes(values.vectorValue().clone()));
            }
        };
    }

    private static StoredValue storedValue(IndexableField field) {
        Number number = field.numericValue();
        if (number != null) {
            return new StoredValue.Numeric(number);
        }
        BytesRef binary = field.binaryValue();
        if (binary != null) {
            return new StoredValue.Binary(BytesRef.deepCopyOf(binary).bytes);
        }
        return new StoredValue.Text(field.stringValue());
    }

    @Override
    public List<VectorTerm> termVector(int docId, String field) throws IOException {
        checkDoc(docId);
        Fields vectors = reader.termVectors().get(docId);
        org.apache.lucene.index.Terms terms = vectors == null ? null : vectors.terms(field);
        if (terms == null) {
            return List.of();
        }
        boolean withPositions = terms.hasPositions();
        boolean withOffsets = terms.hasOffsets();
        List<VectorTerm> result = new ArrayList<>();
        TermsEnum termsEnum = terms.iterator();
        PostingsEnum postings = null;
        for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
            int freq = (int) termsEnum.totalTermFreq();
            List<Position> positions = new ArrayList<>();
            if (withPositions || withOffsets) {
                postings = termsEnum.postings(postings, PostingsEnum.ALL);
                postings.nextDoc();
                for (int i = 0; i < freq; i++) {
                    positions.add(position(postings, withPositions, withOffsets));
                }
            }
            result.add(new VectorTerm(BytesRef.deepCopyOf(term).bytes, freq, List.copyOf(positions)));
        }
        return List.copyOf(result);
    }

    /** The next position of {@code postings}, with what was indexed of it. */
    private static Position position(PostingsEnum postings, boolean withPositions, boolean withOffsets)
            throws IOException {
        int position = postings.nextPosition();
        BytesRef payload = postings.getPayload();
        return new Position(
                withPositions && position >= 0 ? OptionalInt.of(position) : OptionalInt.empty(),
                withOffsets ? OptionalInt.of(postings.startOffset()) : OptionalInt.empty(),
                withOffsets ? OptionalInt.of(postings.endOffset()) : OptionalInt.empty(),
                payload == null || payload.length == 0
                        ? Optional.empty()
                        : Optional.of(BytesRef.deepCopyOf(payload).bytes));
    }

    @Override
    public TermsPage terms(String field, Optional<byte[]> from, int limit) throws IOException {
        org.apache.lucene.index.Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new TermsPage(List.of(), Optional.empty());
        }
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        if (from.isPresent()) {
            term = termsEnum.seekCeil(new BytesRef(from.get())) == TermsEnum.SeekStatus.END ? null : termsEnum.term();
        } else {
            term = termsEnum.next();
        }
        List<TermEntry> entries = new ArrayList<>();
        for (; term != null; term = termsEnum.next()) {
            if (entries.size() == limit) {
                return new TermsPage(List.copyOf(entries), Optional.of(BytesRef.deepCopyOf(term).bytes));
            }
            entries.add(new TermEntry(BytesRef.deepCopyOf(term).bytes, termsEnum.docFreq(), totalTermFreq(termsEnum)));
        }
        return new TermsPage(List.copyOf(entries), Optional.empty());
    }

    /** {@code totalTermFreq}, or {@code docFreq} for a field without frequencies, as Lucene 10 has it. */
    private static long totalTermFreq(TermsEnum termsEnum) throws IOException {
        long total = termsEnum.totalTermFreq();
        return total < 0 ? termsEnum.docFreq() : total;
    }

    /**
     * {@inheritDoc} One pass over the terms keeps the top ones by {@code docFreq}, as leia-core's
     * scan does.
     */
    @Override
    public FieldTerms topTerms(String field, int topN, LongConsumer progress) throws IOException {
        // The weakest first: the lowest docFreq, and of equal ones the later term.
        Comparator<TermEntry> byTerm = (a, b) -> Arrays.compareUnsigned(a.term(), b.term());
        PriorityQueue<TermEntry> top =
                new PriorityQueue<>(Comparator.comparingInt(TermEntry::docFreq).thenComparing(byTerm.reversed()));
        long scanned = 0;
        org.apache.lucene.index.Terms terms = MultiTerms.getTerms(reader, field);
        if (terms != null) {
            TermsEnum termsEnum = terms.iterator();
            for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
                scanned++;
                int docFreq = termsEnum.docFreq();
                // Terms come in order, so a tie with the weakest top term loses: the earlier term stays.
                if (topN > 0 && (top.size() < topN || docFreq > top.peek().docFreq())) {
                    top.add(new TermEntry(BytesRef.deepCopyOf(term).bytes, docFreq, totalTermFreq(termsEnum)));
                    if (top.size() > topN) {
                        top.poll();
                    }
                }
                if (scanned % PROGRESS_INTERVAL == 0) {
                    progress.accept(scanned);
                }
            }
        }
        progress.accept(scanned);
        List<TermEntry> sorted = new ArrayList<>(top);
        sorted.sort(Comparator.comparingInt(TermEntry::docFreq).reversed().thenComparing(byTerm));
        return new FieldTerms(scanned, List.copyOf(sorted));
    }

    /** {@inheritDoc} Lucene 9 reads the postings of deleted documents too, and marks them. */
    @Override
    public PostingsPage postings(String field, byte[] text, int fromDoc, int limit) throws IOException {
        org.apache.lucene.index.Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new PostingsPage(0, 0, List.of(), OptionalInt.empty());
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(new BytesRef(text))) {
            return new PostingsPage(0, 0, List.of(), OptionalInt.empty());
        }
        Bits liveDocs = MultiBits.getLiveDocs(reader);
        PostingsEnum docs = termsEnum.postings(null, PostingsEnum.FREQS);
        List<Posting> postings = new ArrayList<>();
        OptionalInt next = OptionalInt.empty();
        for (int doc = docs.advance(Math.max(fromDoc, 0)); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docs.nextDoc()) {
            if (postings.size() == limit) {
                next = OptionalInt.of(doc);
                break;
            }
            postings.add(new Posting(doc, docs.freq(), liveDocs != null && !liveDocs.get(doc)));
        }
        return new PostingsPage(termsEnum.docFreq(), totalTermFreq(termsEnum), List.copyOf(postings), next);
    }

    /** {@inheritDoc} Deleted documents have their positions too. */
    @Override
    public Optional<List<Position>> positions(int docId, String field, byte[] text) throws IOException {
        checkDoc(docId);
        LeafReaderContext leaf = leaf(docId);
        org.apache.lucene.index.Terms terms = leaf.reader().terms(field);
        if (terms == null) {
            return Optional.empty();
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(new BytesRef(text))) {
            return Optional.empty();
        }
        int leafDoc = docId - leaf.docBase;
        PostingsEnum postings = termsEnum.postings(null, PostingsEnum.ALL);
        if (postings.advance(leafDoc) != leafDoc) {
            return Optional.empty();
        }
        if (!terms.hasPositions()) {
            return Optional.of(List.of());
        }
        boolean withOffsets = terms.hasOffsets();
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < postings.freq(); i++) {
            positions.add(position(postings, true, withOffsets));
        }
        return Optional.of(List.copyOf(positions));
    }

    /** A query of Lucene 9.12. */
    private static final class Lucene9Query implements ParsedQuery {
        private final Query query;

        private Lucene9Query(Query query) {
            this.query = query;
        }

        @Override
        public String toString() {
            return query.toString();
        }
    }

    @Override
    public Set<String> analyzers() {
        return Lucene9Analyzers.NAMES;
    }

    @Override
    public ParsedQuery parse(String expression, ParserOptions options, AnalyzerSpec spec)
            throws InvalidQueryException, UnsupportedAnalyzerException {
        Analyzer analyzer = Lucene9Analyzers.analyzer(spec);
        try {
            QueryParser parser = new QueryParser(options.defaultField(), analyzer);
            parser.setDefaultOperator(options.defaultAnd() ? QueryParser.Operator.AND : QueryParser.Operator.OR);
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            parser.setAutoGeneratePhraseQueries(options.autoGeneratePhraseQueries());
            return new Lucene9Query(parser.parse(expression));
        } catch (ParseException | RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        } finally {
            analyzer.close();
        }
    }

    /**
     * {@inheritDoc} The progress is reported once per segment, and asked from time to time in
     * between, also by the graph search of an approximate KNN query, which runs when it is rewritten.
     */
    @Override
    public SearchResults search(
            ParsedQuery parsed, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException {
        Query query = query(parsed);
        long start = System.nanoTime();
        SearchProgress searchProgress = new SearchProgress(progress);
        IndexSearcher searcher = new IndexSearcher(reader);
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        // Never a timeout: only a way to ask the progress, which throws to stop the search.
        searcher.setTimeout(searchProgress);
        Query rewritten = searcher.rewrite(query);
        // Every hit counted, as 7.7 counts them.
        TopScoreDocCollector top = TopScoreDocCollector.create(offset + limit, Integer.MAX_VALUE);
        searcher.search(rewritten, new ProgressCollector(top, searchProgress));
        progress.accept(reader.maxDoc());
        TopDocs docs = top.topDocs(offset, limit);
        StoredFields storedFields = reader.storedFields();
        List<Hit> hits = new ArrayList<>();
        for (ScoreDoc scoreDoc : docs.scoreDocs) {
            Map<String, List<StoredValue>> values = new LinkedHashMap<>();
            for (IndexableField field : storedFields.document(scoreDoc.doc).getFields()) {
                if (fields.isEmpty() || fields.get().contains(field.name())) {
                    values.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
                }
            }
            hits.add(new Hit(scoreDoc.doc, scoreDoc.score, values));
        }
        return new SearchResults(
                query.toString(),
                rewritten.toString(),
                docs.totalHits.value,
                List.copyOf(hits),
                (System.nanoTime() - start) / 1_000_000);
    }

    /** The Lucene query of {@code parsed}. */
    private static Query query(ParsedQuery parsed) {
        if (!(parsed instanceof Lucene9Query lucene9)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        return lucene9.query;
    }

    /**
     * The progress of a search. As the searcher's {@link QueryTimeout}, it asks the progress every
     * {@link #CHECK_INTERVAL} calls whether to go on: the progress throws to stop the search, which
     * the searcher passes on.
     */
    private static final class SearchProgress implements QueryTimeout {

        /** How many calls of {@link #shouldExit} go by between two questions to the progress. */
        static final int CHECK_INTERVAL = 1024;

        private final LongConsumer progress;
        private long searched;
        private int calls;

        SearchProgress(LongConsumer progress) {
            this.progress = progress;
        }

        void searched(long docs) {
            searched = docs;
            progress.accept(docs);
        }

        @Override
        public boolean shouldExit() {
            if (++calls % CHECK_INTERVAL == 0) {
                progress.accept(searched);
            }
            return false;
        }
    }

    /** Passes the hits on, and reports the documents searched at the start of each segment. */
    private record ProgressCollector(Collector delegate, SearchProgress progress) implements Collector {

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            progress.searched(context.docBase);
            return delegate.getLeafCollector(context);
        }

        @Override
        public ScoreMode scoreMode() {
            return delegate.scoreMode();
        }
    }

    /** {@inheritDoc} Lucene 9.12's KNN query of the field's encoding; it searches when rewritten. */
    @Override
    public ParsedQuery approximate(String field, Vector target, int k, Optional<ParsedQuery> filter) {
        vectorField(field);
        Query filterQuery = filter.map(Lucene9Index::query).orElse(null);
        return new Lucene9Query(
                switch (target) {
                    case Vector.Floats floats -> new KnnFloatVectorQuery(field, floats.values(), k, filterQuery);
                    case Vector.Bytes bytes -> new KnnByteVectorQuery(field, bytes.values(), k, filterQuery);
                });
    }

    @Override
    public ParsedQuery exact(String field, Vector target, Optional<ParsedQuery> filter) {
        vectorField(field);
        return new Lucene9Query(new ExactKnnQuery(field, target, filter.map(Lucene9Index::query)));
    }

    @Override
    public Optional<Vector> vector(int docId, String field) throws IOException {
        checkDoc(docId);
        Field info = vectorField(field);
        LeafReaderContext leaf = leaf(docId);
        return vector(leaf.reader(), docId - leaf.docBase, info);
    }

    /** The field {@code field}, which has vectors. */
    private Field vectorField(String field) {
        return fields.stream()
                .filter(info -> info.name().equals(field) && info.vectors().isPresent())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No KNN vectors in field " + field));
    }

    private LeafReaderContext leaf(int docId) {
        List<LeafReaderContext> leaves = reader.leaves();
        return leaves.get(ReaderUtil.subIndex(docId, leaves));
    }

    private void checkDoc(int docId) {
        if (docId < 0 || docId >= reader.maxDoc()) {
            throw new IllegalArgumentException("No document " + docId + ": maxDoc is " + reader.maxDoc());
        }
    }

    /** Closes the reader, which closes those of the parts, then the directories. */
    @Override
    public void close() throws IOException {
        try {
            reader.close();
        } finally {
            IOException failure = null;
            for (Part part : parts) {
                try {
                    part.directory().close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
