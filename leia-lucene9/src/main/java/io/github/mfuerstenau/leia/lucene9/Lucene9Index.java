package io.github.mfuerstenau.leia.lucene9;

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
import io.github.mfuerstenau.leia.runtime.support.Optionals;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
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
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFieldVisitor;
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
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.jspecify.annotations.Nullable;

/**
 * An index created by Lucene 8.x, read with Lucene 9.12 (ADR 0049). Lucene 10 can't read such an
 * index, even once 9.x has written to it: it reads indexes created by 9 and 10 only. The segments of
 * 9.x can have KNN vectors.
 *
 * <p>Lucene is in this runtime's class loader (ADR 0073), and leia reaches it only through {@link
 * Lucene9Runtime} and the interfaces of {@code leia-runtime}. It is read-only like the rest of leia: it
 * opens readers, never an {@code IndexWriter}, through directories without locks. Several directories
 * open as one, in a {@link MultiReader} (ADR 0045). A reader of Lucene 9 can be used from several
 * threads, so jobs can read it. Soft-deleted documents are live, as a plain {@code DirectoryReader}
 * has them.
 */
final class Lucene9Index
        implements RuntimeIndex,
                Documents,
                Terms,
                Search,
                Scoring,
                DocValuesSearch,
                StandardSearch,
                MoreLikeThisSearch {

    /** 9.12's default similarity, which {@link Search} scores with. */
    private static final Similarity DEFAULT_SIMILARITY = new Similarity.Bm25(1.2f, 0.75f);

    /** Without an exact count, counting stops after this many hits, as in Luke and leia-core. */
    static final int TOTAL_HITS_THRESHOLD = 1000;

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

    private Lucene9Index(DirectoryKind directoryKind, List<Part> parts, List<Skipped> skipped) throws IOException {
        this.directoryKind = directoryKind;
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        this.reader = parts.size() == 1
                ? parts.getFirst().reader()
                : new MultiReader(parts.stream().map(Part::reader).toArray(IndexReader[]::new), false);
        try {
            this.fieldInfos = FieldInfos.getMergedFieldInfos(reader);
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
    static Lucene9Index open(List<Path> dirs, DirectoryKind kind) throws IOException {
        IndexParts.Opened<Part> opened = IndexParts.compatible(
                dirs,
                IndexParts.open(dirs, dir -> openPart(dir, kind)),
                Part::dir,
                Lucene9Index::conflict,
                Lucene9Index::closeables);
        try {
            return new Lucene9Index(kind, opened.parts(), opened.skipped());
        } catch (IOException | RuntimeException e) {
            IndexParts.closeAll(
                    e,
                    opened.parts().stream()
                            .flatMap(part -> closeables(part).stream())
                            .toList());
            throw e;
        }
    }

    /** Why the fields of {@code parts} can't be combined, as {@link FieldInfos#getMergedFieldInfos} refuses them; empty if they can. */
    private static Optional<String> conflict(List<Part> parts) throws IOException {
        if (parts.size() < 2) {
            return Optional.empty();
        }
        try (MultiReader multi =
                new MultiReader(parts.stream().map(Part::reader).toArray(IndexReader[]::new), false)) {
            FieldInfos.getMergedFieldInfos(multi);
            return Optional.empty();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** What closes a part that no index holds yet. */
    private static List<Closeable> closeables(Part part) {
        return List.of(part.reader(), part.directory());
    }

    private static Part openPart(Path dir, DirectoryKind kind) throws IOException {
        Directory directory = Directories.open(dir, kind);
        try {
            // A reader takes no lock and changes nothing.
            return new Part(dir, directory, DirectoryReader.open(directory));
        } catch (IOException | RuntimeException e) {
            directory.close();
            Optional<IndexReadException> failure = Lucene9Failures.classify(e);
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

    @Override
    public Optional<Points> points() {
        return Lucene9Api.POINT_TREE ? Optional.of(new Lucene9Points(reader, fieldInfos)) : Optional.empty();
    }

    /**
     * {@inheritDoc} Only segments written by 9.x can have vectors, and only a Lucene of 9.5 or later
     * searches them (ADR 0091).
     */
    @Override
    public Optional<Knn> knn() {
        return Lucene9Api.KNN ? Optional.of(new Lucene9Knn(this)) : Optional.empty();
    }

    /** {@inheritDoc} Only segments of 9.5's and later HNSW formats have a graph leia reads. */
    @Override
    public Optional<KnnGraph> knnGraph() {
        return Lucene9Api.KNN ? Optional.of(new Lucene9KnnGraph(reader, fieldInfos)) : Optional.empty();
    }

    @Override
    public Optional<Rebuild> rebuild() {
        return Optional.of(new Lucene9Rebuild(reader));
    }

    @Override
    public Optional<Highlight> highlight() {
        return Lucene9Api.HIGHLIGHTER ? Optional.of(new Lucene9Highlight(reader)) : Optional.empty();
    }

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
                Optional<IndexReadException> failure = Lucene9Failures.classify(e);
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
            Lucene9Index index = new Lucene9Index(directoryKind, newParts, skipped);
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
                    Optional.ofNullable(infos.getCommitLuceneVersion())
                            .map(Object::toString)
                            .orElse(null),
                    // 8 for every index this runtime opens; it says how the norms are encoded.
                    infos.getIndexCreatedVersionMajor(),
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
            if (FilterLeafReader.unwrap(leaf.reader()) instanceof SegmentReader segment) {
                SegmentInfo info = segment.getSegmentInfo().info;
                segments.add(new Segment(
                        segment.getSegmentName(),
                        leaf.docBase,
                        leaf.reader().maxDoc(),
                        leaf.reader().numDocs(),
                        // Recorded since 5.0 (ADR 0113).
                        Optional.ofNullable(info.getId())
                                .map(HexFormat.of()::formatHex)
                                .orElse(null),
                        List.copyOf(new TreeSet<>(info.files()))));
            } else {
                segments.add(new Segment(
                        "?", leaf.docBase, leaf.reader().maxDoc(), leaf.reader().numDocs()));
            }
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
                    info.getPointDimensionCount(),
                    info.getPointNumBytes(),
                    termStats(info.name).orElse(null),
                    vectorFormat(info).orElse(null)));
        }
        result.sort(Comparator.comparingInt(Field::number));
        return List.copyOf(result);
    }

    /** How {@code info}'s KNN vectors are stored; empty without them. */
    private static Optional<VectorFormat> vectorFormat(FieldInfo info) {
        if (info.getVectorDimension() == 0) {
            return Optional.empty();
        }
        // Before 9.4, every vector is of floats.
        VectorEncoding encoding = Lucene9Api.VECTOR_ENCODING ? Lucene9VectorEncoding.of(info) : VectorEncoding.FLOAT32;
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
        Document document = Lucene9Stored.of(reader).document(docId);
        Map<String, List<StoredValue>> stored = new HashMap<>();
        for (IndexableField field : document.getFields()) {
            stored.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
        }
        Fields vectors = Lucene9Stored.termVectors(reader, docId);
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
                    Optionals.orNull(norm),
                    List.copyOf(stored.getOrDefault(field.name(), List.of())),
                    docValues(leaf.reader(), leafDoc, field).orElse(null),
                    hasTermVector,
                    Lucene9Api.KNN
                            ? Lucene9Knn.vector(leaf.reader(), leafDoc, field).orElse(null)
                            : null));
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
        if (Lucene9Api.DOC_VALUE_COUNT) {
            for (int i = 0; i < set.docValueCount(); i++) {
                values.add(BytesRef.deepCopyOf(set.lookupOrd(set.nextOrd())).bytes);
            }
        } else {
            // Without docValueCount (9.0), the ords end with NO_MORE_ORDS.
            for (long ord = set.nextOrd(); ord != SortedSetDocValues.NO_MORE_ORDS; ord = set.nextOrd()) {
                values.add(BytesRef.deepCopyOf(set.lookupOrd(ord)).bytes);
            }
        }
        return Optional.of(new DocValues(field.docValuesType(), List.of(), List.copyOf(values)));
    }

    @Override
    public List<StoredDocument> storedValues(
            int fromDoc, int toDoc, @Nullable Set<String> fields, boolean includeDeleted) throws IOException {
        checkRange(fromDoc, toDoc);
        Lucene9Stored.Reader storedFields = Lucene9Stored.of(reader);
        Bits liveDocs = MultiBits.getLiveDocs(reader);
        List<StoredDocument> documents = new ArrayList<>();
        for (int doc = fromDoc; doc < toDoc; doc++) {
            boolean deleted = liveDocs != null && !liveDocs.get(doc);
            if (deleted && !includeDeleted) {
                continue;
            }
            Document document;
            if (fields != null) {
                DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor(fields);
                storedFields.document(doc, visitor);
                document = visitor.getDocument();
            } else {
                document = storedFields.document(doc);
            }
            documents.add(new StoredDocument(doc, deleted, storedValues(document)));
        }
        return List.copyOf(documents);
    }

    @Override
    public void storedValues(
            int segment,
            @Nullable Set<String> fields,
            boolean includeDeleted,
            IntUnaryOperator next,
            Consumer<StoredDocument> sink,
            LongConsumer progress)
            throws IOException {
        LeafReader leaf = segmentLeaf(segment);
        Lucene9Stored.Reader stored = Lucene9Stored.sequential(leaf);
        Bits liveDocs = leaf.getLiveDocs();
        int maxDoc = leaf.maxDoc();
        int reported = 0;
        progress.accept(0);
        for (int doc = next.applyAsInt(0); doc < maxDoc; doc = next.applyAsInt(doc + 1)) {
            if (doc - reported >= PROGRESS_INTERVAL) {
                progress.accept(doc);
                reported = doc;
            }
            boolean deleted = liveDocs != null && !liveDocs.get(doc);
            if (deleted && !includeDeleted) {
                continue;
            }
            DocumentStoredFieldVisitor visitor = Optional.ofNullable(fields)
                    .map(DocumentStoredFieldVisitor::new)
                    .orElseGet(DocumentStoredFieldVisitor::new);
            stored.document(doc, visitor);
            sink.accept(new StoredDocument(doc, deleted, storedValues(visitor.getDocument())));
        }
        progress.accept(maxDoc);
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
    public void storedFields(int segment, StoredFieldSink sink, LongConsumer progress) throws IOException {
        LeafReader leaf = segmentLeaf(segment);
        int maxDoc = leaf.maxDoc();
        Lucene9Stored.Reader stored = Lucene9Stored.sequential(leaf);
        StoredFieldNames names = new StoredFieldNames(sink);
        for (int doc = 0; doc < maxDoc; doc++) {
            if (doc % PROGRESS_INTERVAL == 0) {
                progress.accept(doc);
            }
            names.doc = doc;
            stored.document(doc, names);
        }
        progress.accept(maxDoc);
    }

    @Override
    public int[] deletedDocs(int segment) {
        LeafReader leaf = segmentLeaf(segment);
        Bits liveDocs = leaf.getLiveDocs();
        if (liveDocs == null) {
            return new int[0];
        }
        int[] deleted = new int[leaf.maxDoc() - leaf.numDocs()];
        int count = 0;
        for (int doc = 0; doc < leaf.maxDoc() && count < deleted.length; doc++) {
            if (!liveDocs.get(doc)) {
                deleted[count++] = doc;
            }
        }
        return deleted;
    }

    /** Segment {@code segment} of the reader, as {@link #storedFields} takes it. */
    private LeafReader segmentLeaf(int segment) {
        List<LeafReaderContext> leaves = reader.leaves();
        if (segment < 0 || segment >= leaves.size()) {
            throw new IllegalArgumentException("No segment " + segment + ": there are " + leaves.size());
        }
        return leaves.get(segment).reader();
    }

    /** Notes the field of each stored value and skips the value (ADR 0113). */
    private static final class StoredFieldNames extends StoredFieldVisitor {

        private final StoredFieldSink sink;
        int doc;

        StoredFieldNames(StoredFieldSink sink) {
            this.sink = sink;
        }

        @Override
        public Status needsField(FieldInfo field) {
            sink.accept(doc, field.name);
            return Status.NO;
        }
    }

    @Override
    public List<VectorTerm> termVector(int docId, String field) throws IOException {
        checkDoc(docId);
        Fields vectors = Lucene9Stored.termVectors(reader, docId);
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
                withPositions && position >= 0 ? position : null,
                withOffsets ? postings.startOffset() : null,
                withOffsets ? postings.endOffset() : null,
                payload == null || payload.length == 0 ? null : BytesRef.deepCopyOf(payload).bytes);
    }

    @Override
    public TermsPage terms(String field, byte @Nullable [] from, int limit) throws IOException {
        org.apache.lucene.index.Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new TermsPage(List.of(), null);
        }
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        if (from != null) {
            term = termsEnum.seekCeil(new BytesRef(from)) == TermsEnum.SeekStatus.END ? null : termsEnum.term();
        } else {
            term = termsEnum.next();
        }
        List<TermEntry> entries = new ArrayList<>();
        for (; term != null; term = termsEnum.next()) {
            if (entries.size() == limit) {
                return new TermsPage(List.copyOf(entries), BytesRef.deepCopyOf(term).bytes);
            }
            entries.add(new TermEntry(BytesRef.deepCopyOf(term).bytes, termsEnum.docFreq(), totalTermFreq(termsEnum)));
        }
        return new TermsPage(List.copyOf(entries), null);
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
    public PostingsPage postings(String field, byte[] text, int fromDoc, int limit, int maxPositions)
            throws IOException {
        org.apache.lucene.index.Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new PostingsPage(0, 0, List.of(), null);
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(new BytesRef(text))) {
            return new PostingsPage(0, 0, List.of(), null);
        }
        Bits liveDocs = MultiBits.getLiveDocs(reader);
        boolean withPositions = maxPositions > 0 && terms.hasPositions();
        boolean withOffsets = withPositions && terms.hasOffsets();
        PostingsEnum docs = termsEnum.postings(null, maxPositions > 0 ? PostingsEnum.ALL : PostingsEnum.FREQS);
        List<Posting> postings = new ArrayList<>();
        OptionalInt next = OptionalInt.empty();
        for (int doc = docs.advance(Math.max(fromDoc, 0)); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docs.nextDoc()) {
            if (postings.size() == limit) {
                next = OptionalInt.of(doc);
                break;
            }
            int freq = docs.freq();
            List<Position> positions = new ArrayList<>();
            for (int i = 0; withPositions && i < Math.min(freq, maxPositions); i++) {
                positions.add(position(docs, true, withOffsets));
            }
            postings.add(new Posting(doc, freq, liveDocs != null && !liveDocs.get(doc), List.copyOf(positions)));
        }
        return new PostingsPage(
                termsEnum.docFreq(), totalTermFreq(termsEnum), List.copyOf(postings), Optionals.orNull(next));
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
    static final class Lucene9Query implements ParsedQuery {
        private final Query query;

        Lucene9Query(Query query) {
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
        Analyzer analyzer = Lucene9Analyzers.analyzer(spec);
        try {
            QueryParser parser = new Lucene9DateRangeQueryParser(options.defaultField(), analyzer, options.dates());
            parser.setDefaultOperator(options.defaultAnd() ? QueryParser.Operator.AND : QueryParser.Operator.OR);
            parser.setAllowLeadingWildcard(options.allowLeadingWildcard());
            parser.setPhraseSlop(options.phraseSlop());
            parser.setFuzzyPrefixLength(options.fuzzyPrefixLength());
            parser.setEnablePositionIncrements(options.enablePositionIncrements());
            parser.setFuzzyMinSim(options.fuzzyMaxEdits());
            // In this order: the parser refuses auto-generated phrases while it doesn't split.
            parser.setSplitOnWhitespace(options.splitOnWhitespace());
            parser.setAutoGeneratePhraseQueries(options.autoGeneratePhraseQueries());
            return new Lucene9Query(parser.parse(expression));
        } catch (ParseException | RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        } finally {
            analyzer.close();
        }
    }

    @Override
    public ParsedQuery term(String field, byte[] term) {
        return new Lucene9Query(new TermQuery(new Term(field, new BytesRef(term))));
    }

    @Override
    public ParsedQuery numbers(String field, NumberEncoding number, Match<String> match) {
        return new Lucene9Query(Lucene9DocValuesQueries.numbers(fieldInfo(field), number, match));
    }

    @Override
    public ParsedQuery bytes(String field, Match<byte[]> match) {
        return new Lucene9Query(Lucene9DocValuesQueries.bytes(fieldInfo(field), match));
    }

    @Override
    public ParsedQuery prefix(String field, byte[] prefix) {
        return new Lucene9Query(Lucene9DocValuesQueries.prefix(fieldInfo(field), prefix));
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
        try (Analyzer analyzer = Lucene9Analyzers.analyzer(spec)) {
            return new Lucene9Query(Lucene9StandardParser.parse(expression, options, points, analyzer));
        } catch (RuntimeException e) {
            throw new InvalidQueryException(e.getMessage(), e);
        }
    }

    /** {@inheritDoc} The query is built in each search, from the reader searched (ADR 0013). */
    @Override
    public ParsedQuery like(Source source, MoreLikeThisSearch.Options options, AnalyzerSpec spec)
            throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        Lucene9Analyzers.analyzer(spec).close();
        return new Lucene9Query(new Lucene9MoreLikeQuery(source, options, spec));
    }

    /**
     * The analyzer that a More-Like-This query analyzes with while it is built; null for any other
     * query. The caller closes it.
     */
    static Analyzer likeAnalyzer(Query query) {
        if (!(query instanceof Lucene9MoreLikeQuery like)) {
            return null;
        }
        try {
            return Lucene9Analyzers.analyzer(like.analyzer());
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
        return query instanceof Lucene9MoreLikeQuery like ? like.build(searcher.getIndexReader(), analyzer) : query;
    }

    /** {@inheritDoc} It scores with 9.12's default, BM25 with k1 1.2 and b 0.75. */
    @Override
    public SearchResults search(
            ParsedQuery parsed,
            int offset,
            int limit,
            boolean exactTotalHits,
            @Nullable List<String> fields,
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
                        Lucene9Sorts.numeric(sortFields[i]) && !hasNumber(sortFields[i].getField(), scoreDoc.doc)
                                ? new SortValue(null, null)
                                : sortValue(fieldDoc.fields[i]));
            }
        }
        return List.copyOf(sortValues);
    }

    /**
     * {@inheritDoc} The progress is reported once per segment, and asked from time to time in
     * between, also by the graph search of an approximate KNN query, which runs when it is rewritten.
     * A field sort leaves the scores out, so the page's hits get theirs in one more pass.
     */
    @Override
    public SearchResults search(
            ParsedQuery parsed,
            Similarity similarity,
            List<SortKey> sortKeys,
            int offset,
            int limit,
            boolean exactTotalHits,
            @Nullable List<String> fields,
            LongConsumer progress)
            throws IOException {
        Query query = query(parsed);
        Optional<Sort> sort =
                sortKeys.isEmpty() ? Optional.empty() : Optional.of(Lucene9Sorts.sort(fieldInfos, sortKeys));
        long start = System.nanoTime();
        SearchProgress searchProgress = new SearchProgress(progress);
        IndexSearcher searcher = searcher(similarity, searchProgress);
        Optional<List<MoreLikeThisSearch.LikeTerm>> likeTerms = Optional.empty();
        Query rewritten;
        try (Analyzer analyzer = likeAnalyzer(query)) {
            Query built = built(searcher, query, analyzer);
            if (query instanceof Lucene9MoreLikeQuery like) {
                // Explained before the rewrite merges a lone clause or drops a ^1.0.
                likeTerms = Optional.of(Lucene9LikeTerms.of(
                        searcher.getIndexReader(), like, analyzer, built, () -> searchProgress.searched(0)));
            }
            rewritten = searcher.rewrite(built);
        }
        // Every hit counted, unless not asked to, as leia-core counts them; that also keeps an index
        // sort from ending the search early.
        int threshold = exactTotalHits ? Integer.MAX_VALUE : TOTAL_HITS_THRESHOLD;
        TopDocsCollector<?> top = sort.isPresent()
                ? TopFieldCollector.create(sort.get(), offset + limit, threshold)
                : TopScoreDocCollector.create(offset + limit, threshold);
        searcher.search(rewritten, new ProgressCollector(top, searchProgress));
        TopDocs docs = top.topDocs(offset, limit);
        if (sort.isPresent()) {
            TopFieldCollector.populateScores(docs.scoreDocs, searcher, rewritten);
        }
        progress.accept(reader.maxDoc());
        SortField[] sortFields = sort.map(Sort::getSort).orElse(new SortField[0]);
        Lucene9Stored.Reader storedFields = Lucene9Stored.of(reader);
        List<Hit> hits = new ArrayList<>();
        for (ScoreDoc scoreDoc : docs.scoreDocs) {
            Map<String, List<StoredValue>> values = new LinkedHashMap<>();
            for (IndexableField field : storedFields.document(scoreDoc.doc).getFields()) {
                if (fields == null || fields.contains(field.name())) {
                    values.computeIfAbsent(field.name(), _ -> new ArrayList<>()).add(storedValue(field));
                }
            }
            hits.add(new Hit(scoreDoc.doc, scoreDoc.score, sortValues(scoreDoc, sortFields), values));
        }
        return new SearchResults(
                query.toString(),
                rewritten.toString(),
                docs.totalHits.value,
                docs.totalHits.relation == TotalHits.Relation.EQUAL_TO,
                List.copyOf(hits),
                (System.nanoTime() - start) / 1_000_000,
                likeTerms.orElse(null));
    }

    /** A searcher of the whole index that scores with {@code similarity} and asks {@code progress}. */
    private IndexSearcher searcher(Similarity similarity, SearchProgress progress) {
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(Lucene9Sorts.similarity(similarity));
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        timeout(searcher, progress);
        return searcher;
    }

    /**
     * Has {@code searcher} ask {@code progress} from time to time. Never a timeout: only a way to ask
     * the progress, which throws to stop the search. On 9.0 a searcher has no timeout, and the
     * progress is asked only at the start of each segment (ADR 0091).
     */
    private static void timeout(IndexSearcher searcher, SearchProgress progress) {
        if (Lucene9Api.SEARCH_TIMEOUT) {
            searcher.setTimeout(progress);
        }
    }

    /** A value of {@link FieldDoc#fields}: a number, a term, or null for a missing string. */
    private static SortValue sortValue(Object value) {
        return switch (value) {
            case Number number -> new SortValue(number, null);
            case BytesRef term -> new SortValue(null, BytesRef.deepCopyOf(term).bytes);
            case null, default -> new SortValue(null, null);
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

    /**
     * {@inheritDoc} Deleted documents match through a reader whose leaves have no deletions. The
     * progress is reported once per segment, and asked from time to time in between.
     */
    @Override
    public Matches matches(ParsedQuery parsed, boolean includeDeleted, LongConsumer progress) throws IOException {
        Query query = query(parsed);
        long start = System.nanoTime();
        SearchProgress searchProgress = new SearchProgress(progress);
        IndexSearcher searcher = new IndexSearcher(includeDeleted ? undeleted(reader) : reader);
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        timeout(searcher, searchProgress);
        Query rewritten;
        try (Analyzer analyzer = likeAnalyzer(query)) {
            rewritten = searcher.rewrite(built(searcher, query, analyzer));
        }
        BitSet docs = new BitSet(reader.maxDoc());
        searcher.search(rewritten, new ProgressCollector(new MatchCollector(docs), searchProgress));
        progress.accept(reader.maxDoc());
        return new Matches(docs, query.toString(), rewritten.toString(), (System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public boolean matchesDeleted() {
        return true;
    }

    /** {@inheritDoc} It scores with 9.12's default, BM25 with k1 1.2 and b 0.75. */
    @Override
    public Explanation explain(ParsedQuery parsed, int docId, LongConsumer progress) throws IOException {
        return explain(parsed, DEFAULT_SIMILARITY, docId, progress);
    }

    /**
     * {@inheritDoc} The progress is asked from time to time, also by the graph search of an
     * approximate KNN query, which runs when it is rewritten.
     */
    @Override
    public Explanation explain(ParsedQuery parsed, Similarity similarity, int docId, LongConsumer progress)
            throws IOException {
        Query query = query(parsed);
        IndexSearcher searcher = searcher(similarity, new SearchProgress(progress));
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
                        .map(Lucene9Index::explanation)
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

    /** Sets the bit of each matching document, by its id in the whole index; no scores. */
    private record MatchCollector(BitSet docs) implements Collector {

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) {
            int docBase = context.docBase;
            return new LeafCollector() {
                @Override
                public void setScorer(Scorable scorer) {
                    // No scores: the collector only notes the documents.
                }

                @Override
                public void collect(int doc) {
                    docs.set(docBase + doc);
                }
            };
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }
    }

    /** The Lucene query of {@code parsed}. */
    static Query query(ParsedQuery parsed) {
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

    /** The field {@code field}, which has vectors. */
    Field vectorField(String field) {
        return fields.stream()
                .filter(info -> info.name().equals(field) && info.vectors() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No KNN vectors in field " + field));
    }

    IndexReader reader() {
        return reader;
    }

    FieldInfos fieldInfos() {
        return fieldInfos;
    }

    LeafReaderContext leaf(int docId) {
        List<LeafReaderContext> leaves = reader.leaves();
        return leaves.get(ReaderUtil.subIndex(docId, leaves));
    }

    void checkDoc(int docId) {
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
