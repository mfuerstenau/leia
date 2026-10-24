package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.Highlight;
import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.KnnGraph;
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
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * An index written by Lucene 6.x or 7.x, read with Lucene 7.7 (ADR 0048). Lucene 10 can't read such
 * an index: it reads the formats of 9 and 10 only.
 *
 * <p>Lucene is in this runtime's class loader (ADR 0073), and leia reaches it only through {@link
 * Lucene7Runtime} and the interfaces of {@code leia-runtime}. It is read-only like the rest of leia: it
 * opens readers, never an {@code IndexWriter}, through directories without locks. Several directories
 * open as one, in a {@link MultiReader} (ADR 0045). A reader of Lucene 7 can be used from several
 * threads, so jobs can read it. Soft-deleted documents are live, as a plain {@code DirectoryReader}
 * has them.
 */
final class Lucene7Index
        implements RuntimeIndex,
                Documents,
                Terms,
                Search,
                Scoring,
                DocValuesSearch,
                StandardSearch,
                MoreLikeThisSearch {

    /** 7.7's default similarity, which {@link Search} scores with. */
    private static final Similarity DEFAULT_SIMILARITY = new Similarity.Bm25(1.2f, 0.75f);

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

    private Lucene7Index(DirectoryKind directoryKind, List<Part> parts, List<Skipped> skipped) throws IOException {
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
    static Lucene7Index open(List<Path> dirs, DirectoryKind kind) throws IOException {
        IndexParts.Opened<Part> opened = IndexParts.open(dirs, dir -> openPart(dir, kind));
        try {
            return new Lucene7Index(kind, opened.parts(), opened.skipped());
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
            Optional<IndexReadException> failure = Lucene7Failures.classify(e);
            if (failure.isPresent()) {
                throw Failures.opening(failure.get());
            }
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

    /** {@inheritDoc} Lucene 6.0 brought points. */
    @Override
    public Optional<Points> points() {
        return Optional.of(new Lucene7Points(reader, fieldInfos));
    }

    /** {@inheritDoc} KNN vectors came with Lucene 9. */
    @Override
    public Optional<Knn> knn() {
        return Optional.empty();
    }

    @Override
    public Optional<KnnGraph> knnGraph() {
        return Optional.empty();
    }

    @Override
    public Optional<Rebuild> rebuild() {
        return Optional.of(new Lucene7Rebuild(reader));
    }

    /** {@inheritDoc} Not yet: Lucene 7.7's {@code UnifiedHighlighter} has no builder, and its {@code FieldHighlighter} differs from 9.12's (ADR 0063). */
    @Override
    public Optional<Highlight> highlight() {
        return Optional.empty();
    }

    /** {@inheritDoc} Similarity and sort as on 9.12 (ADR 0076). */
    @Override
    public Optional<Scoring> scoring() {
        return Optional.of(this);
    }

    @Override
    public Optional<DocValuesSearch> docValuesSearch() {
        return Optional.of(this);
    }

    @Override
    public Optional<StandardSearch> standardSearch() {
        return Optional.of(this);
    }

    @Override
    public Optional<MoreLikeThisSearch> moreLikeThisSearch() {
        return Optional.of(this);
    }

    /**
     * {@inheritDoc} The readers are counted, as in leia-core: each index holds a reference to each
     * part's reader, and the {@link MultiReader} of several parts holds one more.
     */
    // openIfChanged returns null when nothing changed (ADR 0088)
    @SuppressWarnings("java:S2583")
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
                // Owned by the new part (ADR 0088)
                @SuppressWarnings("java:S2095")
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
                Optional<IndexReadException> failure = Lucene7Failures.classify(e);
                if (parts.size() > 1) {
                    throw Failures.named(
                            part.dir(),
                            failure.<Throwable>map(Failures::opening).orElse(e));
                }
                if (failure.isPresent()) {
                    throw Failures.opening(failure.get());
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
            Lucene7Index index = new Lucene7Index(directoryKind, newParts, skipped);
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
                versions.add(Lucene7Commits.version(info));
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
                    // Recorded since 7.0; 7.7 takes a commit of 6.x as created by 6.
                    OptionalInt.of(infos.getIndexCreatedVersionMajor()),
                    Map.copyOf(commit.getUserData()),
                    segments(part.reader())));
            docBase += part.reader().maxDoc();
        }
        return new Stats(
                reader.numDocs(),
                reader.maxDoc(),
                List.copyOf(indexes),
                fields(),
                parts.getFirst().directory().getClass().getSimpleName());
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
                    info.getPointDataDimensionCount(),
                    info.getPointNumBytes(),
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
                    hasTermVector));
        }
        Bits liveDocs = MultiFields.getLiveDocs(reader);
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
                return sortedNumeric(leaf.getSortedNumericDocValues(name), doc, field);
            }
            case SORTED_SET -> {
                return sortedSet(leaf.getSortedSetDocValues(name), doc, field);
            }
        }
        return Optional.of(new DocValues(field.docValuesType(), List.copyOf(numbers), List.copyOf(values)));
    }

    private static Optional<DocValues> sortedNumeric(SortedNumericDocValues sorted, int doc, Field field)
            throws IOException {
        if (sorted == null || !sorted.advanceExact(doc)) {
            return Optional.empty();
        }
        List<Long> numbers = new ArrayList<>();
        for (int i = 0; i < sorted.docValueCount(); i++) {
            numbers.add(sorted.nextValue());
        }
        return Optional.of(new DocValues(field.docValuesType(), List.copyOf(numbers), List.of()));
    }

    private static Optional<DocValues> sortedSet(SortedSetDocValues set, int doc, Field field) throws IOException {
        if (set == null || !set.advanceExact(doc)) {
            return Optional.empty();
        }
        List<byte[]> values = new ArrayList<>();
        for (long ord = set.nextOrd(); ord != SortedSetDocValues.NO_MORE_ORDS; ord = set.nextOrd()) {
            values.add(BytesRef.deepCopyOf(set.lookupOrd(ord)).bytes);
        }
        return Optional.of(new DocValues(field.docValuesType(), List.of(), List.copyOf(values)));
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

    /** {@inheritDoc} Lucene 7 reads the postings of deleted documents too, and marks them. */
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

    /** A query of Lucene 7.7. */
    private static final class Lucene7Query implements ParsedQuery {
        private final Query query;

        private Lucene7Query(Query query) {
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
        Analyzer analyzer = Lucene7Analyzers.analyzer(spec);
        try {
            QueryParser parser = new Lucene7DateRangeQueryParser(options.defaultField(), analyzer, options.dates());
            parser.setDefaultOperator(options.defaultAnd() ? QueryParser.Operator.AND : QueryParser.Operator.OR);
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            parser.setFuzzyMinSim(options.fuzzyMaxEdits());
            // In this order: the parser refuses auto-generated phrases while it doesn't split.
            parser.setSplitOnWhitespace(options.splitOnWhitespace());
            parser.setAutoGeneratePhraseQueries(options.autoGeneratePhraseQueries());
            return new Lucene7Query(parser.parse(expression));
        } catch (ParseException | RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        } finally {
            analyzer.close();
        }
    }

    @Override
    public ParsedQuery term(String field, byte[] term) {
        return new Lucene7Query(new TermQuery(new Term(field, new BytesRef(term))));
    }

    @Override
    public ParsedQuery numbers(String field, NumberEncoding number, Match<String> match) {
        return new Lucene7Query(Lucene7DocValuesQueries.numbers(fieldInfo(field), number, match));
    }

    @Override
    public ParsedQuery bytes(String field, Match<byte[]> match) {
        return new Lucene7Query(Lucene7DocValuesQueries.bytes(fieldInfo(field), match));
    }

    @Override
    public ParsedQuery prefix(String field, byte[] prefix) {
        return new Lucene7Query(Lucene7DocValuesQueries.prefix(fieldInfo(field), prefix));
    }

    /** @throws IllegalArgumentException if the index has no field {@code field} */
    private FieldInfo fieldInfo(String field) {
        FieldInfo info = fieldInfos.fieldInfo(field);
        if (info == null) {
            throw new IllegalArgumentException("No field '" + field + "' in the index");
        }
        return info;
    }

    @Override
    public ParsedQuery parse(
            String expression,
            StandardSearch.Options options,
            Map<String, StandardSearch.PointType> points,
            AnalyzerSpec spec)
            throws InvalidQueryException, UnsupportedAnalyzerException, InvalidAnalyzerException {
        try (Analyzer analyzer = Lucene7Analyzers.analyzer(spec)) {
            return new Lucene7Query(Lucene7StandardParser.parse(expression, options, points, analyzer));
        } catch (RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /** {@inheritDoc} The query is built in each search, from the reader searched (ADR 0013). */
    @Override
    public ParsedQuery like(Source source, MoreLikeThisSearch.Options options, AnalyzerSpec spec)
            throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        Lucene7Analyzers.analyzer(spec).close();
        return new Lucene7Query(new Lucene7MoreLikeQuery(source, options, spec));
    }

    /**
     * The analyzer that a More-Like-This query analyzes with while it is built; null for any other
     * query. The caller closes it.
     */
    static Analyzer likeAnalyzer(Query query) {
        if (!(query instanceof Lucene7MoreLikeQuery like)) {
            return null;
        }
        try {
            return Lucene7Analyzers.analyzer(like.analyzer());
        } catch (UnsupportedAnalyzerException | InvalidAnalyzerException e) {
            // MoreLikeThisSearch.like built it before.
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * The query to rewrite: a More-Like-This query is built from the searcher's reader with {@code
     * analyzer} from {@link #likeAnalyzer} first, as leia-core's {@code Searcher} builds it.
     */
    static Query built(IndexSearcher searcher, Query query, Analyzer analyzer) throws IOException {
        return query instanceof Lucene7MoreLikeQuery like ? like.build(searcher.getIndexReader(), analyzer) : query;
    }

    /** {@inheritDoc} It scores with 7.7's default, BM25 with k1 1.2 and b 0.75. */
    @Override
    public SearchResults search(
            ParsedQuery parsed,
            int offset,
            int limit,
            boolean exactTotalHits,
            Optional<List<String>> fields,
            LongConsumer progress)
            throws IOException {
        return search(parsed, DEFAULT_SIMILARITY, List.of(), offset, limit, exactTotalHits, fields, progress);
    }

    /** The values a hit was sorted by; none for a hit that wasn't sorted. */
    private List<SortValue> sortValues(ScoreDoc scoreDoc, SortField[] sortFields) throws IOException {
        List<SortValue> sortValues = new ArrayList<>(sortFields.length);
        if (scoreDoc instanceof FieldDoc fieldDoc) {
            for (int i = 0; i < sortFields.length; i++) {
                sortValues.add(
                        Lucene7Sorts.numeric(sortFields[i]) && !hasNumber(sortFields[i].getField(), scoreDoc.doc)
                                ? new SortValue(Optional.empty(), Optional.empty())
                                : sortValue(fieldDoc.fields[i]));
            }
        }
        return List.copyOf(sortValues);
    }

    /**
     * {@inheritDoc} Every hit is counted, as 7.7 always counts them, which also keeps an index sort
     * from ending the search early (ADR 0076). The progress is reported once per segment. A field sort
     * keeps the scores of the hits it collects.
     */
    @Override
    public SearchResults search(
            ParsedQuery parsed,
            Similarity similarity,
            List<SortKey> sortKeys,
            int offset,
            int limit,
            boolean exactTotalHits,
            Optional<List<String>> fields,
            LongConsumer progress)
            throws IOException {
        if (!(parsed instanceof Lucene7Query lucene7)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        Query query = lucene7.query;
        Optional<Sort> sort =
                sortKeys.isEmpty() ? Optional.empty() : Optional.of(Lucene7Sorts.sort(fieldInfos, sortKeys));
        long start = System.nanoTime();
        IndexSearcher searcher = searcher(similarity);
        Optional<List<MoreLikeThisSearch.LikeTerm>> likeTerms = Optional.empty();
        Query rewritten;
        try (Analyzer analyzer = likeAnalyzer(query)) {
            Query built = built(searcher, query, analyzer);
            if (query instanceof Lucene7MoreLikeQuery like) {
                // Explained before the rewrite merges a lone clause or drops a ^1.0.
                likeTerms = Optional.of(Lucene7LikeTerms.of(reader, like, analyzer, built, () -> progress.accept(0)));
            }
            rewritten = searcher.rewrite(built);
        }
        // The field values filled in, with the scores, and every hit counted.
        TopDocsCollector<?> top = sort.isPresent()
                ? TopFieldCollector.create(sort.get(), offset + limit, true, true, false, true)
                : TopScoreDocCollector.create(offset + limit);
        searcher.search(rewritten, new ProgressCollector(top, progress));
        progress.accept(reader.maxDoc());
        TopDocs docs = top.topDocs(offset, limit);
        SortField[] sortFields = sort.map(Sort::getSort).orElse(new SortField[0]);
        List<Hit> hits = new ArrayList<>();
        for (ScoreDoc scoreDoc : docs.scoreDocs) {
            Map<String, List<StoredValue>> values = new LinkedHashMap<>();
            for (IndexableField field : reader.document(scoreDoc.doc).getFields()) {
                if (fields.isEmpty() || fields.get().contains(field.name())) {
                    values.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
                }
            }
            hits.add(new Hit(scoreDoc.doc, scoreDoc.score, sortValues(scoreDoc, sortFields), values));
        }
        return new SearchResults(
                query.toString(),
                rewritten.toString(),
                docs.totalHits,
                List.copyOf(hits),
                (System.nanoTime() - start) / 1_000_000,
                likeTerms);
    }

    /** A searcher of the whole index that scores with {@code similarity}. */
    private IndexSearcher searcher(Similarity similarity) {
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(Lucene7Sorts.similarity(similarity));
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        return searcher;
    }

    /** A value of {@link FieldDoc#fields}: a number, a term, or null for a missing string. */
    private static SortValue sortValue(Object value) {
        return switch (value) {
            case Number number -> new SortValue(Optional.of(number), Optional.empty());
            case BytesRef term -> new SortValue(Optional.empty(), Optional.of(BytesRef.deepCopyOf(term).bytes));
            case null, default -> new SortValue(Optional.empty(), Optional.empty());
        };
    }

    /**
     * Whether the document has a value in the numeric or sorted numeric doc values of {@code field},
     * so that its sort value is not the missing value that Lucene put in its place (ADR 0025).
     */
    private boolean hasNumber(String field, int docId) throws IOException {
        LeafReaderContext leaf = leaf(docId);
        return org.apache.lucene.index.DocValues.getSortedNumeric(leaf.reader(), field)
                .advanceExact(docId - leaf.docBase);
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
        if (!(parsed instanceof Lucene7Query lucene7)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        Query query = lucene7.query;
        long start = System.nanoTime();
        IndexSearcher searcher = new IndexSearcher(includeDeleted ? undeleted(reader) : reader);
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        Query rewritten;
        try (Analyzer analyzer = likeAnalyzer(query)) {
            rewritten = searcher.rewrite(built(searcher, query, analyzer));
        }
        BitSet docs = new BitSet(reader.maxDoc());
        searcher.search(rewritten, new MatchCollector(docs, progress));
        progress.accept(reader.maxDoc());
        return new Matches(docs, query.toString(), rewritten.toString(), (System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public boolean matchesDeleted() {
        return true;
    }

    /** {@inheritDoc} It scores with 7.7's default, BM25 with k1 1.2 and b 0.75. */
    @Override
    public Explanation explain(ParsedQuery parsed, int docId, LongConsumer progress) throws IOException {
        return explain(parsed, DEFAULT_SIMILARITY, docId, progress);
    }

    /**
     * {@inheritDoc} Lucene 7.7 has no way to ask the progress while it explains: it is reported
     * before and after.
     */
    @Override
    public Explanation explain(ParsedQuery parsed, Similarity similarity, int docId, LongConsumer progress)
            throws IOException {
        if (!(parsed instanceof Lucene7Query lucene7)) {
            throw new IllegalArgumentException("Not a query of this index: " + parsed);
        }
        progress.accept(0);
        IndexSearcher searcher = searcher(similarity);
        Query query = lucene7.query;
        Explanation explanation;
        try (Analyzer analyzer = likeAnalyzer(query)) {
            explanation = explanation(searcher.explain(built(searcher, query, analyzer), docId));
        }
        progress.accept(reader.maxDoc());
        return explanation;
    }

    private static Explanation explanation(org.apache.lucene.search.Explanation explanation) {
        return new Explanation(
                explanation.isMatch(),
                explanation.getValue(),
                explanation.getDescription(),
                Arrays.stream(explanation.getDetails())
                        .map(Lucene7Index::explanation)
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
            return super.maxDoc();
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
                public void setScorer(Scorer scorer) {
                    // No scores: the collector only notes the documents.
                }

                @Override
                public void collect(int doc) {
                    docs.set(docBase + doc);
                    if (++collected % PROGRESS_INTERVAL == 0) {
                        progress.accept((long) docBase + doc);
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
