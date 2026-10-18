package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.Points;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Terms;
import io.github.mfuerstenau.leia.runtime.support.IndexParts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.document.MapFieldSelector;
import org.apache.lucene.document.NumericField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.index.TermPositionVector;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.index.TermVectorOffsetInfo;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.ReaderUtil;
import org.apache.lucene.util.Version;

/**
 * An index written by Lucene 2.x or 3.x, read with Lucene 3.6 (ADR 0044). Lucene 10 can't read such
 * an index: there were no codecs before 4.0.
 *
 * <p>Lucene is relocated in this module's jar, and leia reaches it only through {@link
 * Lucene3Runtime} and the interfaces of {@code leia-runtime}. It is read-only like the rest of leia: it
 * opens readers, never an {@code IndexWriter}, through directories without locks. Several directories
 * open as one, in a {@link MultiReader} (ADR 0045). A reader of Lucene 3 can be used from several
 * threads, so jobs can read it.
 */
final class Lucene3Index implements RuntimeIndex, Documents, Terms, Search {

    /** How many terms {@link #topTerms} scans between two reports of its progress. */
    static final int PROGRESS_INTERVAL = 1024;

    /** A directory that opened, and its reader. */
    private record Part(Path dir, Directory directory, IndexReader reader) {}

    private final DirectoryKind directoryKind;
    private final List<Part> parts;
    private final List<Skipped> skipped;
    private final IndexReader reader;
    private final FieldInfos fieldInfos;

    private Lucene3Index(DirectoryKind directoryKind, List<Part> parts, List<Skipped> skipped) {
        this.directoryKind = directoryKind;
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        this.reader = parts.size() == 1
                ? parts.getFirst().reader()
                : new MultiReader(parts.stream().map(Part::reader).toArray(IndexReader[]::new), true);
        this.fieldInfos = ReaderUtil.getMergedFieldInfos(reader);
    }

    /**
     * Opens the latest commit of each of {@code dirs} with a reader, and combines those that open.
     *
     * @throws IOException if none of them can be read
     */
    static Lucene3Index open(List<Path> dirs, DirectoryKind kind) throws IOException {
        IndexParts.Opened<Part> opened = IndexParts.open(dirs, dir -> openPart(dir, kind));
        return new Lucene3Index(kind, opened.parts(), opened.skipped());
    }

    private static Part openPart(Path dir, DirectoryKind kind) throws IOException {
        Directory directory = Directories.open(dir, kind);
        try {
            // Read-only: no write lock, no changes to the index.
            return new Part(dir, directory, IndexReader.open(directory, true));
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

    /** {@inheritDoc} Points came with Lucene 6. */
    @Override
    public Optional<Points> points() {
        return Optional.empty();
    }

    /** {@inheritDoc} KNN vectors came with Lucene 9. */
    @Override
    public Optional<Knn> knn() {
        return Optional.empty();
    }

    @Override
    public Stats stats() throws IOException {
        List<IndexStats> indexes = new ArrayList<>();
        int docBase = 0;
        for (Part part : parts) {
            IndexCommit commit = part.reader().getIndexCommit();
            SegmentInfos infos = new SegmentInfos();
            infos.read(part.directory(), commit.getSegmentsFileName());
            List<String> versions = new ArrayList<>();
            for (SegmentInfo info : infos) {
                versions.add(Lucene3Commits.version(info));
            }
            indexes.add(new IndexStats(
                    part.dir(),
                    docBase,
                    commit.getSegmentsFileName(),
                    commit.getGeneration(),
                    commit.getVersion(),
                    part.reader().numDocs(),
                    part.reader().maxDoc(),
                    List.copyOf(versions),
                    // 3.6 records neither which version wrote a commit nor which created the index.
                    Optional.empty(),
                    OptionalInt.empty(),
                    Map.copyOf(commit.getUserData()),
                    segments(part.reader())));
            docBase += part.reader().maxDoc();
        }
        return new Stats(reader.numDocs(), reader.maxDoc(), List.copyOf(indexes), fields());
    }

    /** The reader's segments, in document order (ADR 0051). */
    private static List<Segment> segments(IndexReader reader) {
        IndexReader[] subs = reader.getSequentialSubReaders();
        List<Segment> segments = new ArrayList<>();
        int docBase = 0;
        for (IndexReader sub : subs == null ? new IndexReader[] {reader} : subs) {
            segments.add(new Segment(
                    sub instanceof SegmentReader segment ? segment.getSegmentName() : "?",
                    docBase,
                    sub.maxDoc(),
                    sub.numDocs()));
            docBase += sub.maxDoc();
        }
        return List.copyOf(segments);
    }

    /** {@inheritDoc} Lucene 3 has no doc values, and records no term statistics per field. */
    @Override
    public List<Field> fields() {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < fieldInfos.size(); i++) {
            FieldInfo info = fieldInfos.fieldInfo(i);
            IndexOptions options = !info.isIndexed
                    ? IndexOptions.NONE
                    : switch (info.indexOptions) {
                        case DOCS_ONLY -> IndexOptions.DOCS;
                        case DOCS_AND_FREQS -> IndexOptions.DOCS_AND_FREQS;
                        case DOCS_AND_FREQS_AND_POSITIONS -> IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
                    };
            fields.add(new Field(
                    info.name,
                    info.number,
                    options,
                    info.isIndexed && !info.omitNorms,
                    info.storePayloads,
                    info.storeTermVector,
                    DocValuesType.NONE,
                    0,
                    0,
                    Optional.empty()));
        }
        fields.sort(Comparator.comparingInt(Field::number));
        return List.copyOf(fields);
    }

    @Override
    public int maxDoc() {
        return reader.maxDoc();
    }

    @Override
    public IndexDocument document(int docId) throws IOException {
        checkDoc(docId);
        Document document = reader.document(docId);
        Map<String, List<StoredValue>> stored = new HashMap<>();
        for (Fieldable field : document.getFields()) {
            stored.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
        }
        List<DocumentField> fields = new ArrayList<>();
        for (Field field : fields()) {
            OptionalLong norm = OptionalLong.empty();
            if (field.hasNorms() && reader.hasNorms(field.name())) {
                byte[] norms = reader.norms(field.name());
                if (norms != null) {
                    norm = OptionalLong.of(norms[docId]);
                }
            }
            boolean hasTermVector = field.hasTermVectors() && reader.getTermFreqVector(docId, field.name()) != null;
            fields.add(new DocumentField(
                    field.name(),
                    norm,
                    List.copyOf(stored.getOrDefault(field.name(), List.of())),
                    Optional.empty(),
                    hasTermVector));
        }
        return new IndexDocument(docId, reader.isDeleted(docId), List.copyOf(fields));
    }

    @Override
    public List<StoredDocument> storedValues(
            int fromDoc, int toDoc, Optional<Set<String>> fields, boolean includeDeleted) throws IOException {
        checkRange(fromDoc, toDoc);
        FieldSelector selector = fields.map(names -> new MapFieldSelector(new ArrayList<>(names)))
                .orElse(null);
        List<StoredDocument> documents = new ArrayList<>();
        for (int doc = fromDoc; doc < toDoc; doc++) {
            boolean deleted = reader.isDeleted(doc);
            if (deleted && !includeDeleted) {
                continue;
            }
            Document document = selector == null ? reader.document(doc) : reader.document(doc, selector);
            Map<String, List<StoredValue>> values = new LinkedHashMap<>();
            for (Fieldable field : document.getFields()) {
                values.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
            }
            documents.add(new StoredDocument(doc, deleted, values));
        }
        return List.copyOf(documents);
    }

    private static StoredValue storedValue(Fieldable field) {
        if (field instanceof NumericField numeric && numeric.getNumericValue() != null) {
            return new StoredValue.Numeric(numeric.getNumericValue());
        }
        if (field.isBinary()) {
            byte[] bytes = Arrays.copyOfRange(
                    field.getBinaryValue(), field.getBinaryOffset(), field.getBinaryOffset() + field.getBinaryLength());
            return new StoredValue.Binary(bytes);
        }
        return new StoredValue.Text(field.stringValue());
    }

    @Override
    public List<VectorTerm> termVector(int docId, String field) throws IOException {
        checkDoc(docId);
        TermFreqVector vector = reader.getTermFreqVector(docId, field);
        if (vector == null) {
            return List.of();
        }
        String[] terms = vector.getTerms();
        int[] freqs = vector.getTermFrequencies();
        List<VectorTerm> result = new ArrayList<>();
        for (int i = 0; i < terms.length; i++) {
            List<Position> positions = new ArrayList<>();
            if (vector instanceof TermPositionVector withPositions) {
                int[] at = withPositions.getTermPositions(i);
                TermVectorOffsetInfo[] offsets = withPositions.getOffsets(i);
                int count = Math.max(at == null ? 0 : at.length, offsets == null ? 0 : offsets.length);
                for (int j = 0; j < count; j++) {
                    positions.add(new Position(
                            at != null && j < at.length ? OptionalInt.of(at[j]) : OptionalInt.empty(),
                            offsets != null && j < offsets.length
                                    ? OptionalInt.of(offsets[j].getStartOffset())
                                    : OptionalInt.empty(),
                            offsets != null && j < offsets.length
                                    ? OptionalInt.of(offsets[j].getEndOffset())
                                    : OptionalInt.empty(),
                            Optional.empty()));
                }
            }
            result.add(new VectorTerm(utf8(terms[i]), freqs[i], List.copyOf(positions)));
        }
        return List.copyOf(result);
    }

    /**
     * {@inheritDoc} Terms of Lucene 3 are text, so {@code from} is read as UTF-8; Lucene 3 doesn't
     * record {@code totalTermFreq}, so it is counted from the postings.
     */
    @Override
    public TermsPage terms(String field, Optional<byte[]> from, int limit) throws IOException {
        List<TermEntry> terms = new ArrayList<>();
        // interned field names are compared with ==, so take Term's own.
        try (TermEnum termEnum =
                reader.terms(new Term(field, from.map(Lucene3Index::text).orElse("")))) {
            Term term = termEnum.term();
            while (term != null && term.field().equals(field)) {
                if (terms.size() == limit) {
                    return new TermsPage(List.copyOf(terms), Optional.of(utf8(term.text())));
                }
                terms.add(new TermEntry(utf8(term.text()), termEnum.docFreq(), totalTermFreq(term)));
                term = termEnum.next() ? termEnum.term() : null;
            }
        }
        return new TermsPage(List.copyOf(terms), Optional.empty());
    }

    private long totalTermFreq(Term term) throws IOException {
        long total = 0;
        try (TermDocs docs = reader.termDocs(term)) {
            while (docs.next()) {
                total += docs.freq();
            }
        }
        return total;
    }

    /**
     * {@inheritDoc} One pass over the terms keeps the top ones by {@code docFreq}, as leia-core's
     * scan does; {@code totalTermFreq}, which Lucene 3 doesn't record, is then counted from the
     * postings of the top terms only.
     */
    @Override
    public FieldTerms topTerms(String field, int topN, LongConsumer progress) throws IOException {
        // The weakest first: the lowest docFreq, and of equal ones the later term.
        PriorityQueue<TopTerm> top = new PriorityQueue<>(
                Comparator.comparingInt(TopTerm::docFreq).thenComparing(TopTerm::text, Comparator.reverseOrder()));
        long scanned = 0;
        try (TermEnum termEnum = reader.terms(new Term(field, ""))) {
            for (Term term = termEnum.term();
                    term != null && term.field().equals(field);
                    term = termEnum.next() ? termEnum.term() : null) {
                scanned++;
                int docFreq = termEnum.docFreq();
                // Terms come in order, so a tie with the weakest top term loses: the earlier term stays.
                if (topN > 0 && (top.size() < topN || docFreq > top.peek().docFreq())) {
                    top.add(new TopTerm(term.text(), docFreq));
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
        List<TopTerm> sorted = new ArrayList<>(top);
        sorted.sort(Comparator.comparingInt(TopTerm::docFreq).reversed().thenComparing(TopTerm::text));
        List<TermEntry> terms = new ArrayList<>();
        for (TopTerm term : sorted) {
            terms.add(new TermEntry(utf8(term.text()), term.docFreq(), totalTermFreq(new Term(field, term.text()))));
        }
        return new FieldTerms(scanned, List.copyOf(terms));
    }

    private record TopTerm(String text, int docFreq) {}

    /** {@inheritDoc} Lucene 3 skips deleted documents in the postings, though {@code docFreq} counts them. */
    @Override
    public PostingsPage postings(String field, byte[] text, int fromDoc, int limit) throws IOException {
        Term term = new Term(field, text(text));
        List<Posting> postings = new ArrayList<>();
        OptionalInt next = OptionalInt.empty();
        try (TermDocs docs = reader.termDocs(term)) {
            boolean more = docs.skipTo(fromDoc);
            while (more) {
                if (postings.size() == limit) {
                    next = OptionalInt.of(docs.doc());
                    break;
                }
                postings.add(new Posting(docs.doc(), docs.freq(), false));
                more = docs.next();
            }
        }
        return new PostingsPage(reader.docFreq(term), totalTermFreq(term), List.copyOf(postings), next);
    }

    /**
     * {@inheritDoc} Lucene 3 has positions and payloads in the postings, but no offsets; it skips
     * deleted documents, which so contain no term.
     */
    @Override
    public Optional<List<Position>> positions(int docId, String field, byte[] text) throws IOException {
        checkDoc(docId);
        Term term = new Term(field, text(text));
        try (TermPositions positions = reader.termPositions(term)) {
            if (!positions.skipTo(docId) || positions.doc() != docId) {
                return Optional.empty();
            }
            FieldInfo info = fieldInfos.fieldInfo(field);
            if (info == null
                    || !info.isIndexed
                    || info.indexOptions != FieldInfo.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) {
                return Optional.of(List.of());
            }
            List<Position> result = new ArrayList<>();
            for (int i = 0; i < positions.freq(); i++) {
                int position = positions.nextPosition();
                Optional<byte[]> payload = Optional.empty();
                if (positions.isPayloadAvailable()) {
                    payload = Optional.of(positions.getPayload(new byte[positions.getPayloadLength()], 0));
                }
                result.add(new Position(OptionalInt.of(position), OptionalInt.empty(), OptionalInt.empty(), payload));
            }
            return Optional.of(List.copyOf(result));
        }
    }

    /** A term of Lucene 3 as bytes. */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Bytes as a term of Lucene 3, which are text. */
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A query of Lucene 3.6. */
    private static final class Lucene3Query implements ParsedQuery {
        private final Query query;

        private Lucene3Query(Query query) {
            this.query = query;
        }

        @Override
        public String toString() {
            return query.toString();
        }
    }

    @Override
    public Set<String> analyzers() {
        return Lucene3Analyzers.NAMES;
    }

    @Override
    public ParsedQuery parse(String expression, ParserOptions options, AnalyzerSpec spec)
            throws InvalidQueryException, UnsupportedAnalyzerException {
        Analyzer analyzer = Lucene3Analyzers.analyzer(spec);
        try {
            QueryParser parser = new QueryParser(Version.LUCENE_36, options.defaultField(), analyzer);
            parser.setDefaultOperator(options.defaultAnd() ? QueryParser.AND_OPERATOR : QueryParser.OR_OPERATOR);
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            parser.setAutoGeneratePhraseQueries(options.autoGeneratePhraseQueries());
            return new Lucene3Query(parser.parse(expression));
        } catch (ParseException | RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        } finally {
            analyzer.close();
        }
    }

    /** {@inheritDoc} The progress is reported once per segment. */
    @Override
    public SearchResults search(
            ParsedQuery parsed, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException {
        if (!(parsed instanceof Lucene3Query lucene3)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        Query query = lucene3.query;
        long start = System.nanoTime();
        IndexSearcher searcher = new IndexSearcher(reader);
        try {
            Query rewritten = searcher.rewrite(query);
            TopScoreDocCollector top = TopScoreDocCollector.create(offset + limit, true);
            searcher.search(rewritten, new ProgressCollector(top, progress));
            progress.accept(reader.maxDoc());
            TopDocs docs = top.topDocs(offset, limit);
            List<Hit> hits = new ArrayList<>();
            for (ScoreDoc scoreDoc : docs.scoreDocs) {
                Map<String, List<StoredValue>> values = new LinkedHashMap<>();
                for (Fieldable field : reader.document(scoreDoc.doc).getFields()) {
                    if (fields.isEmpty() || fields.get().contains(field.name())) {
                        values.computeIfAbsent(field.name(), _ -> new ArrayList<>())
                                .add(storedValue(field));
                    }
                }
                hits.add(new Hit(scoreDoc.doc, scoreDoc.score, values));
            }
            return new SearchResults(
                    query.toString(),
                    rewritten.toString(),
                    docs.totalHits,
                    List.copyOf(hits),
                    (System.nanoTime() - start) / 1_000_000);
        } finally {
            searcher.close();
        }
    }

    /** Passes the hits on, and reports the documents searched at the start of each segment. */
    private static final class ProgressCollector extends Collector {
        private final Collector delegate;
        private final LongConsumer progress;

        ProgressCollector(Collector delegate, LongConsumer progress) {
            this.delegate = delegate;
            this.progress = progress;
        }

        @Override
        public void setScorer(Scorer scorer) throws IOException {
            delegate.setScorer(scorer);
        }

        @Override
        public void collect(int doc) throws IOException {
            delegate.collect(doc);
        }

        @Override
        public void setNextReader(IndexReader segment, int docBase) throws IOException {
            progress.accept(docBase);
            delegate.setNextReader(segment, docBase);
        }

        @Override
        public boolean acceptsDocsOutOfOrder() {
            return delegate.acceptsDocsOutOfOrder();
        }
    }

    private void checkDoc(int docId) {
        if (docId < 0 || docId >= reader.maxDoc()) {
            throw new IllegalArgumentException("No document " + docId + ": maxDoc is " + reader.maxDoc());
        }
    }

    private void checkRange(int fromDoc, int toDoc) {
        if (fromDoc < 0 || fromDoc > toDoc || toDoc > reader.maxDoc()) {
            throw new IllegalArgumentException(
                    "No documents " + fromDoc + " to " + toDoc + ": maxDoc is " + reader.maxDoc());
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
