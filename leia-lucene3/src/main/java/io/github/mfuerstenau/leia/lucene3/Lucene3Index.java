package io.github.mfuerstenau.leia.lucene3;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
import java.util.stream.Stream;
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
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.ReaderUtil;
import org.apache.lucene.util.Version;

/**
 * An index written by Lucene 2.x or 3.x, read with Lucene 3.6 (ADR 0044). Lucene 10 can't read such
 * an index: there were no codecs before 4.0.
 *
 * <p>Lucene is relocated in this module's jar, so its API takes and returns no Lucene types, only
 * the records below. It is read-only like the rest of leia: it opens a reader, never an {@code
 * IndexWriter}, through a {@code Directory} without locks. A reader of Lucene 3 can be used from
 * several threads, so jobs can read it.
 */
public final class Lucene3Index implements Closeable {

    /** The oldest {@code segments_N} format that Lucene 3.6 reads: 2.1, the first with {@code segments_N}. */
    private static final int FORMAT_LOCKLESS = -2;
    /** The newest {@code segments_N} format before codecs: 3.1 to 3.6. */
    private static final int FORMAT_3_1 = -11;

    private final Path path;
    private final String directoryImpl;
    private final Directory directory;
    private final IndexReader reader;
    private final FieldInfos fieldInfos;

    private Lucene3Index(Path path, String directoryImpl, Directory directory, IndexReader reader) {
        this.path = path;
        this.directoryImpl = directoryImpl;
        this.directory = directory;
        this.reader = reader;
        this.fieldInfos = ReaderUtil.getMergedFieldInfos(reader);
    }

    /** The version of the Lucene that reads these indexes, such as {@code 3.6.2}. */
    public static String luceneVersion() {
        return Constants.LUCENE_MAIN_VERSION;
    }

    /**
     * Whether {@code dir} holds an index that Lucene 10 can't read and Lucene 3.6 can: one whose
     * newest {@code segments_N} file starts with a format of Lucene 2.1 to 3.6, not with the header
     * that every Lucene since 4.0 writes. Reads only that first int, without Lucene.
     */
    public static boolean handles(Path dir) {
        Optional<Path> segments = newestSegmentsFile(dir);
        if (segments.isEmpty()) {
            return false;
        }
        try (InputStream in = Files.newInputStream(segments.get());
                DataInputStream data = new DataInputStream(in)) {
            int format = data.readInt();
            return format <= FORMAT_LOCKLESS && format >= FORMAT_3_1;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<Path> newestSegmentsFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> generation(file).isPresent())
                    .max(Comparator.comparingLong(file -> generation(file).orElseThrow()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static OptionalLong generation(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith("segments_")) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(name.substring("segments_".length()), Character.MAX_RADIX));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Opens the latest commit of {@code dir} with a reader.
     *
     * @param directoryImpl {@code FS}, {@code MMAP} or {@code NIO}, as leia's {@code --directory}
     * @throws IOException if the index can't be read
     */
    public static Lucene3Index open(Path dir, String directoryImpl) throws IOException {
        File file = dir.toFile();
        NoLockFactory noLocks = NoLockFactory.getNoLockFactory();
        Directory directory = switch (directoryImpl) {
            case "FS" -> FSDirectory.open(file, noLocks);
            case "MMAP" -> new MMapDirectory(file, noLocks);
            case "NIO" -> new NIOFSDirectory(file, noLocks);
            default -> throw new IllegalArgumentException("Unknown directory: " + directoryImpl);
        };
        try {
            // Read-only: no write lock, no changes to the index.
            return new Lucene3Index(dir, directoryImpl, directory, IndexReader.open(directory, true));
        } catch (IOException | RuntimeException e) {
            directory.close();
            throw e;
        }
    }

    public Path path() {
        return path;
    }

    /** The {@code directoryImpl} it was opened with. */
    public String directoryImpl() {
        return directoryImpl;
    }

    /** How a field is indexed, as far as Lucene 3 records it. */
    public enum IndexOptions {
        NONE,
        DOCS,
        DOCS_AND_FREQS,
        DOCS_AND_FREQS_AND_POSITIONS
    }

    /** A field of the index. */
    public record Field(
            String name,
            int number,
            IndexOptions indexOptions,
            boolean hasNorms,
            boolean hasPayloads,
            boolean hasTermVectors) {}

    /**
     * The opened commit and the fields.
     *
     * @param segmentVersions the Lucene version that wrote each segment; {@code 2.x} for a segment
     *     written before 3.0, which didn't record it
     */
    public record Stats(
            String segmentsFile,
            long generation,
            long version,
            int numDocs,
            int maxDoc,
            List<String> segmentVersions,
            Map<String, String> userData,
            List<Field> fields) {

        /** The major version of the oldest segment, which stands in for the version that created the index. */
        public int oldestMajor() {
            return segmentVersions.stream()
                    .mapToInt(version -> Integer.parseInt(version.substring(0, version.indexOf('.'))))
                    .min()
                    .orElse(3);
        }

        /** The oldest segment version, such as {@code 2.x}; empty without segments. */
        public Optional<String> oldestSegmentVersion() {
            return segmentVersions.stream().min(Comparator.naturalOrder());
        }
    }

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

    /** The fields, by field number. */
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

    public int maxDoc() {
        return reader.maxDoc();
    }

    /** A stored value: text, bytes or, from a {@code NumericField}, a number. */
    public sealed interface StoredValue {
        record Text(String text) implements StoredValue {}

        record Binary(byte[] bytes) implements StoredValue {}

        record Numeric(Number number) implements StoredValue {}
    }

    /** What a document has in one field of the index. */
    public record DocumentField(String name, OptionalLong norm, List<StoredValue> stored, boolean hasTermVector) {}

    /** A document, with every field of the index in field-number order. */
    public record IndexDocument(int docId, boolean deleted, List<DocumentField> fields) {}

    /**
     * Reads document {@code docId}, a deleted one too.
     *
     * @throws IllegalArgumentException if there is no such document
     */
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

    /** One occurrence of a term in a term vector. */
    public record Position(OptionalInt position, OptionalInt startOffset, OptionalInt endOffset) {}

    /** A term of a term vector. */
    public record VectorTerm(String term, int freq, List<Position> positions) {}

    /** The term vector of a document in {@code field}, in term order; empty if it has none. */
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

    /**
     * A term of a field.
     *
     * @param totalTermFreq counted from the postings of live documents: Lucene 3 doesn't record it
     */
    public record TermEntry(String term, int docFreq, long totalTermFreq) {}

    /** A page of terms, and the term after it. */
    public record TermsPage(List<TermEntry> terms, Optional<String> next) {}

    /** Up to {@code limit} terms of {@code field}, from {@code from} on (or from its first term). */
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

    /** A document that contains a term, and how often. */
    public record Posting(int docId, int freq) {}

    /**
     * A page of postings.
     *
     * @param postings live documents only: Lucene 3 skips deleted ones
     */
    public record PostingsPage(int docFreq, long totalTermFreq, List<Posting> postings, OptionalInt nextDoc) {}

    /** Up to {@code limit} postings of {@code term} in {@code field}, from document {@code fromDoc} on. */
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

    /** The classic query parser's options that Lucene 3.6 has. */
    public record ParserOptions(
            String defaultField,
            boolean defaultAnd,
            boolean allowLeadingWildcard,
            int phraseSlop,
            int fuzzyPrefixLength,
            boolean enablePositionIncrements,
            boolean autoGeneratePhraseQueries) {}

    /** A query that the classic query parser of Lucene 3.6 can't parse. */
    public static final class InvalidQueryException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A hit, with the stored values of the fields asked for. */
    public record Hit(int docId, float score, Map<String, List<StoredValue>> fields) {}

    public record SearchResults(String query, String rewrittenQuery, long totalHits, List<Hit> hits, long tookMillis) {}

    /** A query from {@link #parse}, for {@link #search}. */
    public static final class ParsedQuery {
        private final Query query;

        private ParsedQuery(Query query) {
            this.query = query;
        }

        @Override
        public String toString() {
            return query.toString();
        }
    }

    /** Parses {@code expression} with the classic query parser and Lucene 3.6's {@code StandardAnalyzer}. */
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
            return new ParsedQuery(parser.parse(expression));
        } catch (ParseException | RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /**
     * Searches with a query from {@link #parse}, best score first.
     *
     * @param fields the stored fields to return per hit; empty for all of them
     * @param progress receives the documents searched so far, once per segment; it may throw to stop
     *     the search
     */
    public SearchResults search(
            ParsedQuery parsed, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException {
        Query query = parsed.query;
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
