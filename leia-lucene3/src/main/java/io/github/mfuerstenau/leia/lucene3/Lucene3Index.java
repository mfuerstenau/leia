package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Terms;
import java.io.File;
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
import java.util.function.LongConsumer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.document.NumericField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.index.TermPositionVector;
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
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.ReaderUtil;
import org.apache.lucene.util.Version;

/**
 * An index written by Lucene 2.x or 3.x, read with Lucene 3.6 (ADR 0044). Lucene 10 can't read such
 * an index: there were no codecs before 4.0.
 *
 * <p>Lucene is relocated in this module's jar, and leia reaches it only through {@link
 * Lucene3Runtime} and the interfaces of {@code leia-runtime}. It is read-only like the rest of leia: it opens a reader, never an {@code
 * IndexWriter}, through a {@code Directory} without locks. A reader of Lucene 3 can be used from
 * several threads, so jobs can read it.
 */
final class Lucene3Index implements RuntimeIndex, Documents, Terms, Search {

    private final Path path;
    private final DirectoryKind directoryKind;
    private final Directory directory;
    private final IndexReader reader;
    private final FieldInfos fieldInfos;

    private Lucene3Index(Path path, DirectoryKind directoryKind, Directory directory, IndexReader reader) {
        this.path = path;
        this.directoryKind = directoryKind;
        this.directory = directory;
        this.reader = reader;
        this.fieldInfos = ReaderUtil.getMergedFieldInfos(reader);
    }

    /**
     * Opens the latest commit of {@code dir} with a reader.
     *
     * @throws IOException if the index can't be read
     */
    static Lucene3Index open(Path dir, DirectoryKind kind) throws IOException {
        File file = dir.toFile();
        NoLockFactory noLocks = NoLockFactory.getNoLockFactory();
        Directory directory = switch (kind) {
            case FS -> FSDirectory.open(file, noLocks);
            case MMAP -> new MMapDirectory(file, noLocks);
            case NIO -> new NIOFSDirectory(file, noLocks);
        };
        try {
            // Read-only: no write lock, no changes to the index.
            return new Lucene3Index(dir, kind, directory, IndexReader.open(directory, true));
        } catch (IOException | RuntimeException e) {
            directory.close();
            throw e;
        }
    }

    @Override
    public Path path() {
        return path;
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
    public Stats stats() throws IOException {
        SegmentInfos infos = new SegmentInfos();
        infos.read(directory);
        List<String> versions = new ArrayList<>();
        for (SegmentInfo info : infos) {
            versions.add(info.getVersion() == null ? "2.x" : info.getVersion());
        }
        return new Stats(
                infos.getSegmentsFileName(),
                infos.getGeneration(),
                infos.getVersion(),
                reader.numDocs(),
                reader.maxDoc(),
                versions,
                new HashMap<>(infos.getUserData()),
                fields());
    }

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
                    info.storeTermVector));
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
                    field.name(), norm, List.copyOf(stored.getOrDefault(field.name(), List.of())), hasTermVector));
        }
        return new IndexDocument(docId, reader.isDeleted(docId), List.copyOf(fields));
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
                                    : OptionalInt.empty()));
                }
            }
            result.add(new VectorTerm(terms[i], freqs[i], List.copyOf(positions)));
        }
        return List.copyOf(result);
    }

    /** {@inheritDoc} Lucene 3 doesn't record {@code totalTermFreq}, so it is counted from the postings. */
    @Override
    public TermsPage terms(String field, Optional<String> from, int limit) throws IOException {
        List<TermEntry> terms = new ArrayList<>();
        // interned field names are compared with ==, so take Term's own.
        try (TermEnum termEnum = reader.terms(new Term(field, from.orElse("")))) {
            Term term = termEnum.term();
            while (term != null && term.field().equals(field)) {
                if (terms.size() == limit) {
                    return new TermsPage(List.copyOf(terms), Optional.of(term.text()));
                }
                terms.add(new TermEntry(term.text(), termEnum.docFreq(), totalTermFreq(term)));
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

    /** {@inheritDoc} Lucene 3 skips deleted documents in the postings, though {@code docFreq} counts them. */
    @Override
    public PostingsPage postings(String field, String text, int fromDoc, int limit) throws IOException {
        Term term = new Term(field, text);
        List<Posting> postings = new ArrayList<>();
        OptionalInt next = OptionalInt.empty();
        try (TermDocs docs = reader.termDocs(term)) {
            boolean more = docs.skipTo(fromDoc);
            while (more) {
                if (postings.size() == limit) {
                    next = OptionalInt.of(docs.doc());
                    break;
                }
                postings.add(new Posting(docs.doc(), docs.freq()));
                more = docs.next();
            }
        }
        return new PostingsPage(reader.docFreq(term), totalTermFreq(term), List.copyOf(postings), next);
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

    /** {@inheritDoc} The analyzer is Lucene 3.6's {@code StandardAnalyzer}. */
    @Override
    public ParsedQuery parse(String expression, ParserOptions options) throws InvalidQueryException {
        QueryParser parser =
                new QueryParser(Version.LUCENE_36, options.defaultField(), new StandardAnalyzer(Version.LUCENE_36));
        parser.setDefaultOperator(options.defaultAnd() ? QueryParser.AND_OPERATOR : QueryParser.OR_OPERATOR);
        parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
        parser.setPhraseSlop(options.phraseSlop());
        parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
        parser.setEnablePositionIncrements(options.enablePositionIncrements());
        parser.setAutoGeneratePhraseQueries(options.autoGeneratePhraseQueries());
        try {
            return new Lucene3Query(parser.parse(expression));
        } catch (ParseException | RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
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

    @Override
    public void close() throws IOException {
        try {
            reader.close();
        } finally {
            directory.close();
        }
    }
}
