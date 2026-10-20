package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Highlight;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch;
import io.github.mfuerstenau.leia.runtime.Points;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Scoring;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.StandardSearch;
import io.github.mfuerstenau.leia.runtime.Terms;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import io.github.mfuerstenau.leia.runtime.support.IndexParts;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * An index written by Lucene 4.x or 5.x, read with Lucene 5.5 (ADR 0046). Lucene 10 can't read such
 * an index: it reads the formats of 9 and 10 only.
 *
 * <p>Lucene is relocated in this module's jar, and leia reaches it only through {@link
 * Lucene5Runtime} and the interfaces of {@code leia-runtime}. It is read-only like the rest of leia: it
 * opens readers, never an {@code IndexWriter}, through directories without locks. Several directories
 * open as one, in a {@link MultiReader} (ADR 0045). A reader of Lucene 5 can be used from several
 * threads, so jobs can read it.
 */
final class Lucene5Index implements RuntimeIndex, Documents, Terms, Search {

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
    private boolean ownsDirectories = true;
    private boolean closed;

    private Lucene5Index(DirectoryKind directoryKind, List<Part> parts, List<Skipped> skipped) throws IOException {
        this.directoryKind = directoryKind;
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        this.reader = parts.size() == 1
                ? parts.getFirst().reader()
                : new MultiReader(parts.stream().map(Part::reader).toArray(IndexReader[]::new), false);
        try {
            this.fieldInfos = MultiFields.getMergedFieldInfos(reader);
            this.fields = readFields();
        } catch (IOException | RuntimeException e) {
            if (parts.size() > 1) {
                IndexParts.closeAll(e, List.of(reader));
            }
            throw e;
        }
    }

    /**
     * Opens the latest commit of each of {@code dirs} with a reader, and combines those that open.
     *
     * @throws IOException if none of them can be read
     */
    static Lucene5Index open(List<Path> dirs, DirectoryKind kind) throws IOException {
        IndexParts.Opened<Part> opened = IndexParts.open(dirs, dir -> openPart(dir, kind));
        try {
            return new Lucene5Index(kind, opened.parts(), opened.skipped());
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
    public Optional<Rebuild> rebuild() {
        return Optional.of(new Lucene5Rebuild(reader));
    }

    /** {@inheritDoc} Lucene 5.5 has no {@code UnifiedHighlighter}. */
    @Override
    public Optional<Highlight> highlight() {
        return Optional.empty();
    }

    /** {@inheritDoc} Not yet: 5.5 has no {@code BooleanSimilarity} (ADR 0064). */
    @Override
    public Optional<Scoring> scoring() {
        return Optional.empty();
    }

    @Override
    public Optional<DocValuesSearch> docValuesSearch() {
        return Optional.empty();
    }

    @Override
    public Optional<StandardSearch> standardSearch() {
        return Optional.empty();
    }

    @Override
    public Optional<MoreLikeThisSearch> moreLikeThisSearch() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc} The readers are counted, as in leia-core: each index holds a reference to each
     * part's reader, and the {@link MultiReader} of several parts holds one more.
     */
    @Override
    public Optional<RuntimeIndex> reopen() throws IOException {
        if (!ownsDirectories) {
            throw new IllegalStateException("Already reopened: " + directories());
        }
        List<Part> newParts = new ArrayList<>();
        List<Part> reopened = new ArrayList<>();
        for (Part part : parts) {
            try {
                // Like open, it takes no lock and changes nothing.
                DirectoryReader changed = DirectoryReader.openIfChanged(part.reader());
                if (changed == null) {
                    newParts.add(part);
                } else {
                    Part newPart = new Part(part.dir(), part.directory(), changed);
                    reopened.add(newPart);
                    newParts.add(newPart);
                }
            } catch (IOException | RuntimeException e) {
                IndexParts.closeAll(e, reopened.stream().map(Part::reader).toList());
                if (parts.size() > 1) {
                    throw new IOException(part.dir() + ": " + Failures.message(e), e);
                }
                throw e;
            }
        }
        if (reopened.isEmpty()) {
            return Optional.empty();
        }

        List<Part> shared = newParts.stream().filter(parts::contains).toList();
        shared.forEach(part -> part.reader().incRef());
        try {
            Lucene5Index index = new Lucene5Index(directoryKind, newParts, skipped);
            ownsDirectories = false;
            return Optional.of(index);
        } catch (IOException | RuntimeException e) {
            List<Closeable> release = new ArrayList<>();
            shared.forEach(part -> release.add(part.reader()::decRef));
            reopened.forEach(part -> release.add(part.reader()));
            IndexParts.closeAll(e, release);
            throw e;
        }
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
                versions.add(Lucene5Commits.version(info));
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
                    // Recorded since 5.3.
                    Optional.ofNullable(infos.getCommitLuceneVersion()).map(Object::toString),
                    // Recorded since 7.0.
                    OptionalInt.empty(),
                    Map.copyOf(commit.getUserData()),
                    segments(part.reader())));
            docBase += part.reader().maxDoc();
        }
        return new Stats(reader.numDocs(), reader.maxDoc(), List.copyOf(indexes), fields());
    }

    /** The reader's segments, in document order (ADR 0051). */
    private static List<Segment> segments(IndexReader reader) {
        List<Segment> segments = new ArrayList<>();
        for (LeafReaderContext leaf : reader.leaves()) {
            segments.add(new Segment(
                    FilterLeafReader.unwrap(leaf.reader()) instanceof SegmentReader segment
                            ? segment.getSegmentName()
                            : "?",
                    leaf.docBase,
                    leaf.reader().maxDoc(),
                    leaf.reader().numDocs()));
        }
        return List.copyOf(segments);
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
                    0,
                    0,
                    termStats(info.name)));
        }
        result.sort(Comparator.comparingInt(Field::number));
        return List.copyOf(result);
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
        org.apache.lucene.index.Terms terms = MultiFields.getTerms(reader, field);
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
        Document document = reader.document(docId);
        Map<String, List<StoredValue>> stored = new HashMap<>();
        for (IndexableField field : document.getFields()) {
            stored.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
        }
        Fields vectors = reader.getTermVectors(docId);
        LeafReaderContext leaf = leaf(docId);
        int leafDoc = docId - leaf.docBase;
        List<DocumentField> result = new ArrayList<>();
        for (Field field : fields) {
            OptionalLong norm = OptionalLong.empty();
            if (field.hasNorms()) {
                NumericDocValues norms = leaf.reader().getNormValues(field.name());
                if (norms != null) {
                    norm = OptionalLong.of(norms.get(leafDoc));
                }
            }
            boolean hasTermVector = vectors != null && vectors.terms(field.name()) != null;
            result.add(new DocumentField(
                    field.name(),
                    norm,
                    List.copyOf(stored.getOrDefault(field.name(), List.of())),
                    docValues(leaf.reader(), leafDoc, field),
                    hasTermVector));
        }
        Bits liveDocs = MultiFields.getLiveDocs(reader);
        return new IndexDocument(docId, liveDocs != null && !liveDocs.get(docId), List.copyOf(result));
    }

    /** The doc values of {@code doc} of {@code leaf} in {@code field}, through 5.x's random access. */
    private static Optional<DocValues> docValues(LeafReader leaf, int doc, Field field) throws IOException {
        String name = field.name();
        if (field.docValuesType() == DocValuesType.NONE) {
            return Optional.empty();
        }
        Bits docsWithField = leaf.getDocsWithField(name);
        if (docsWithField == null || !docsWithField.get(doc)) {
            return Optional.empty();
        }
        List<Long> numbers = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        switch (field.docValuesType()) {
            case NUMERIC -> {
                NumericDocValues numeric = leaf.getNumericDocValues(name);
                numbers.add(numeric.get(doc));
            }
            case BINARY -> {
                BinaryDocValues binary = leaf.getBinaryDocValues(name);
                values.add(BytesRef.deepCopyOf(binary.get(doc)).bytes);
            }
            case SORTED -> {
                SortedDocValues sorted = leaf.getSortedDocValues(name);
                int ord = sorted.getOrd(doc);
                if (ord >= 0) {
                    values.add(BytesRef.deepCopyOf(sorted.lookupOrd(ord)).bytes);
                }
            }
            case SORTED_NUMERIC -> {
                SortedNumericDocValues sorted = leaf.getSortedNumericDocValues(name);
                sorted.setDocument(doc);
                for (int i = 0; i < sorted.count(); i++) {
                    numbers.add(sorted.valueAt(i));
                }
            }
            case SORTED_SET -> {
                SortedSetDocValues set = leaf.getSortedSetDocValues(name);
                set.setDocument(doc);
                for (long ord = set.nextOrd(); ord != SortedSetDocValues.NO_MORE_ORDS; ord = set.nextOrd()) {
                    values.add(BytesRef.deepCopyOf(set.lookupOrd(ord)).bytes);
                }
            }
            case NONE -> throw new IllegalStateException("No doc values in " + name);
        }
        return Optional.of(new DocValues(field.docValuesType(), List.copyOf(numbers), List.copyOf(values)));
    }

    @Override
    public List<StoredDocument> storedValues(
            int fromDoc, int toDoc, Optional<Set<String>> fields, boolean includeDeleted) throws IOException {
        checkRange(fromDoc, toDoc);
        Bits liveDocs = MultiFields.getLiveDocs(reader);
        List<StoredDocument> documents = new ArrayList<>();
        for (int doc = fromDoc; doc < toDoc; doc++) {
            boolean deleted = liveDocs != null && !liveDocs.get(doc);
            if (deleted && !includeDeleted) {
                continue;
            }
            Document document;
            if (fields.isPresent()) {
                DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor(fields.get());
                reader.document(doc, visitor);
                document = visitor.getDocument();
            } else {
                document = reader.document(doc);
            }
            documents.add(new StoredDocument(doc, deleted, storedValues(document)));
        }
        return List.copyOf(documents);
    }

    /** A document's stored values by field, in stored order. */
    private static Map<String, List<StoredValue>> storedValues(Document document) {
        Map<String, List<StoredValue>> values = new LinkedHashMap<>();
        for (IndexableField field : document.getFields()) {
            values.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
        }
        return values;
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
        Fields vectors = reader.getTermVectors(docId);
        org.apache.lucene.index.Terms terms = vectors == null ? null : vectors.terms(field);
        if (terms == null) {
            return List.of();
        }
        return vectorTerms(terms);
    }

    /** The terms of a term vector, in term order. */
    static List<VectorTerm> vectorTerms(org.apache.lucene.index.Terms terms) throws IOException {
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
    static Position position(PostingsEnum postings, boolean withPositions, boolean withOffsets) throws IOException {
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
        org.apache.lucene.index.Terms terms = MultiFields.getTerms(reader, field);
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
        org.apache.lucene.index.Terms terms = MultiFields.getTerms(reader, field);
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

    /** {@inheritDoc} Lucene 5 reads the postings of deleted documents too, and marks them. */
    @Override
    public PostingsPage postings(String field, byte[] text, int fromDoc, int limit) throws IOException {
        org.apache.lucene.index.Terms terms = MultiFields.getTerms(reader, field);
        if (terms == null) {
            return new PostingsPage(0, 0, List.of(), OptionalInt.empty());
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(new BytesRef(text))) {
            return new PostingsPage(0, 0, List.of(), OptionalInt.empty());
        }
        Bits liveDocs = MultiFields.getLiveDocs(reader);
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

    /** A query of Lucene 5.5. */
    private static final class Lucene5Query implements ParsedQuery {
        private final Query query;

        private Lucene5Query(Query query) {
            this.query = query;
        }

        @Override
        public String toString() {
            return query.toString();
        }
    }

    @Override
    public ParsedQuery parse(String expression, ParserOptions options, AnalyzerSpec spec)
            throws InvalidQueryException, UnsupportedAnalyzerException, InvalidAnalyzerException {
        Analyzer analyzer = Lucene5Analyzers.analyzer(spec);
        try {
            QueryParser parser = new QueryParser(options.defaultField(), analyzer);
            parser.setDefaultOperator(options.defaultAnd() ? QueryParser.Operator.AND : QueryParser.Operator.OR);
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            parser.setAutoGeneratePhraseQueries(options.autoGeneratePhraseQueries());
            return new Lucene5Query(parser.parse(expression));
        } catch (ParseException | RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        } finally {
            analyzer.close();
        }
    }

    @Override
    public ParsedQuery term(String field, byte[] term) {
        return new Lucene5Query(new TermQuery(new Term(field, new BytesRef(term))));
    }

    /** {@inheritDoc} The progress is reported once per segment. */
    @Override
    public SearchResults search(
            ParsedQuery parsed, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException {
        if (!(parsed instanceof Lucene5Query lucene5)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        Query query = lucene5.query;
        long start = System.nanoTime();
        IndexSearcher searcher = new IndexSearcher(reader);
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        Query rewritten = searcher.rewrite(query);
        TopScoreDocCollector top = TopScoreDocCollector.create(offset + limit);
        searcher.search(rewritten, new ProgressCollector(top, progress));
        progress.accept(reader.maxDoc());
        TopDocs docs = top.topDocs(offset, limit);
        List<Hit> hits = new ArrayList<>();
        for (ScoreDoc scoreDoc : docs.scoreDocs) {
            Map<String, List<StoredValue>> values = new LinkedHashMap<>();
            for (IndexableField field : reader.document(scoreDoc.doc).getFields()) {
                if (fields.isEmpty() || fields.get().contains(field.name())) {
                    values.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
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
    }

    /** Passes the hits on, and reports the documents searched at the start of each segment. */
    private record ProgressCollector(Collector delegate, LongConsumer progress) implements Collector {

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            progress.accept(context.docBase);
            return delegate.getLeafCollector(context);
        }

        @Override
        public boolean needsScores() {
            return delegate.needsScores();
        }
    }

    /**
     * {@inheritDoc} Deleted documents match through a reader whose leaves have no deletions. The
     * progress is reported once per segment, and every {@link #PROGRESS_INTERVAL} matches in between.
     */
    @Override
    public Matches matches(ParsedQuery parsed, boolean includeDeleted, LongConsumer progress) throws IOException {
        if (!(parsed instanceof Lucene5Query lucene5)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        Query query = lucene5.query;
        long start = System.nanoTime();
        IndexSearcher searcher = new IndexSearcher(includeDeleted ? undeleted(reader) : reader);
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        Query rewritten = searcher.rewrite(query);
        BitSet docs = new BitSet(reader.maxDoc());
        searcher.search(rewritten, new MatchCollector(docs, progress));
        progress.accept(reader.maxDoc());
        return new Matches(docs, query.toString(), rewritten.toString(), (System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public boolean matchesDeleted() {
        return true;
    }

    /**
     * {@inheritDoc} Lucene 5.5 has no way to ask the progress while it explains: it is reported
     * before and after.
     */
    @Override
    public Explanation explain(ParsedQuery parsed, int docId, LongConsumer progress) throws IOException {
        if (!(parsed instanceof Lucene5Query lucene5)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        progress.accept(0);
        IndexSearcher searcher = new IndexSearcher(reader);
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        Explanation explanation = explanation(searcher.explain(lucene5.query, docId));
        progress.accept(reader.maxDoc());
        return explanation;
    }

    private static Explanation explanation(org.apache.lucene.search.Explanation explanation) {
        return new Explanation(
                explanation.isMatch(),
                explanation.getValue(),
                explanation.getDescription(),
                Arrays.stream(explanation.getDetails())
                        .map(Lucene5Index::explanation)
                        .toList());
    }

    /**
     * The leaves of {@code reader} without deletions, as one reader with the same document ids. It is
     * never closed: closing it would leave the leaves open, but it takes nothing that needs closing.
     */
    private static IndexReader undeleted(IndexReader reader) throws IOException {
        return new MultiReader(
                reader.leaves().stream()
                        .map(leaf -> new UndeletedLeaf(leaf.reader()))
                        .toArray(IndexReader[]::new),
                false);
    }

    /** A leaf in which every document is live. */
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
    }

    /**
     * Sets the bit of each matching document, by its id in the whole index, without scores. Reports
     * the documents searched at the start of each segment and every {@link #PROGRESS_INTERVAL}
     * matches, since this Lucene has no other way to ask the progress during a search.
     */
    private static final class MatchCollector implements Collector {
        private final BitSet docs;
        private final LongConsumer progress;
        private long collected;

        MatchCollector(BitSet docs, LongConsumer progress) {
            this.docs = docs;
            this.progress = progress;
        }

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) {
            int docBase = context.docBase;
            progress.accept(docBase);
            return new LeafCollector() {
                @Override
                public void setScorer(Scorer scorer) {}

                @Override
                public void collect(int doc) {
                    docs.set(docBase + doc);
                    if (++collected % PROGRESS_INTERVAL == 0) {
                        progress.accept(docBase + doc);
                    }
                }
            };
        }

        @Override
        public boolean needsScores() {
            return false;
        }
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

    private void checkRange(int fromDoc, int toDoc) {
        if (fromDoc < 0 || fromDoc > toDoc || toDoc > reader.maxDoc()) {
            throw new IllegalArgumentException(
                    "No documents " + fromDoc + " to " + toDoc + ": maxDoc is " + reader.maxDoc());
        }
    }

    /**
     * Releases this index's references to the readers, which closes those that no reopened index
     * shares, then the directories unless a reopened index owns them.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        List<Closeable> closeables = new ArrayList<>();
        if (parts.size() > 1) {
            closeables.add(reader);
        }
        for (Part part : parts) {
            closeables.add(part.reader()::decRef);
        }
        if (ownsDirectories) {
            for (Part part : parts) {
                closeables.add(part.directory());
            }
        }
        IndexParts.closeAll(closeables);
    }
}
