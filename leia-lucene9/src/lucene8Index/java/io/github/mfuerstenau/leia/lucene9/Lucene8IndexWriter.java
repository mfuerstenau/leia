package io.github.mfuerstenau.leia.lucene9;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.payloads.DelimitedPayloadTokenFilter;
import org.apache.lucene.analysis.payloads.IdentityEncoder;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.codecs.lucene86.Lucene86PointsWriter;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.bkd.BKDWriter;

/**
 * Writes a small test index with Lucene 8.11 (build tooling, not part of leia).
 *
 * <p>Compiled against and run with the unrelocated lucene-core 8.11.4 only (see {@code
 * leia-lucene9/build.gradle.kts}); {@code Lucene8to9IndexWriter} adds a commit of 9.12 to a copy. It
 * is {@code Lucene7IndexWriter}'s index, sorted by {@code order}. Every commit is kept, and {@code
 * __soft_deletes} is the soft deletes field. The first commit has documents 1 to 4 in the segment
 * {@code _0}, without a compound file; the second adds documents 5 to 7 in {@code _1}, a compound
 * file, deletes document 2, sets the {@code dv_num} of document 4 to 400, and soft-updates document
 * 3: the old one is soft-deleted, the new one is in {@code _1}, where the index sort puts it first,
 * as document 4 of the index. Each commit has user data. Each document has:
 *
 * <ul>
 *   <li>{@code id}, stored and not analyzed;
 *   <li>{@code body}, stored, split at whitespace, with offsets and with term vectors (positions,
 *       offsets and payloads): {@code alpha} in every document with the payload {@code a<i>}, {@code
 *       beta} in every other, {@code gamma} twice in the first only;
 *   <li>{@code tag}, indexed docs-only without norms;
 *   <li>{@code price}, the stored int {@code i * 10};
 *   <li>{@code blob}, the stored bytes {@code ff fe i}, which aren't UTF-8;
 *   <li>doc values of every type: {@code dv_num} {@code i} (but none in document 3), {@code dv_bin}
 *       {@code b<i>}, {@code dv_sorted} {@code s<i % 3>}, {@code dv_set} {@code x} and {@code y<i>},
 *       and {@code dv_sorted_num} {@code -i} and {@code i};
 *   <li>points: {@code pt}, the ints {@code 100 * i} to {@code 100 * i + 14}; {@code loc}, the
 *       two-dimensional ints {@code (i, 0)} to {@code (i, 10)}; {@code amount}, the long {@code i *
 *       1000}. They are written {@value #POINTS_PER_LEAF} points per leaf, so that the trees have
 *       several leaves;
 *   <li>{@code order}, the numeric doc values {@code i}, which the index is sorted by.
 * </ul>
 */
public final class Lucene8IndexWriter {

    /** The most points in a leaf of the BKD trees: small, so a few documents make several leaves. */
    static final int POINTS_PER_LEAF = 16;

    /** The soft deletes field. */
    static final String SOFT_DELETES = "__soft_deletes";

    private Lucene8IndexWriter() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        // Start empty, so every run writes the same files.
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        try (Directory directory = FSDirectory.open(dir)) {
            try (IndexWriter writer = new IndexWriter(directory, config(false, IndexWriterConfig.OpenMode.CREATE))) {
                for (int i = 1; i <= 4; i++) {
                    writer.addDocument(document(i));
                }
                writer.setLiveCommitData(Map.of("commit", "first").entrySet());
                writer.commit();
            }
            try (IndexWriter writer = new IndexWriter(directory, config(true, IndexWriterConfig.OpenMode.APPEND))) {
                for (int i = 5; i <= 7; i++) {
                    writer.addDocument(document(i));
                }
                writer.deleteDocuments(new Term("id", "2"));
                writer.updateNumericDocValue(new Term("id", "4"), "dv_num", 400);
                writer.softUpdateDocument(new Term("id", "3"), document(3), new NumericDocValuesField(SOFT_DELETES, 1));
                writer.setLiveCommitData(Map.of("commit", "second").entrySet());
                writer.commit();
            }
        }
        // Left behind by the writer; leia's tests check that reading writes none.
        Files.deleteIfExists(Path.of(args[0], "write.lock"));
    }

    /**
     * Keeps every commit and every segment: no merges, compound files as {@code compound} says, small
     * point leaves, soft deletes, and the documents sorted by {@code order}.
     */
    private static IndexWriterConfig config(boolean compound, IndexWriterConfig.OpenMode mode) {
        LogDocMergePolicy mergePolicy = new LogDocMergePolicy();
        mergePolicy.setNoCFSRatio(1.0);
        mergePolicy.setMergeFactor(1000);
        return new IndexWriterConfig(new PayloadAnalyzer())
                .setOpenMode(mode)
                .setUseCompoundFile(compound)
                .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE)
                .setMergePolicy(mergePolicy)
                .setCodec(smallPointLeaves())
                .setSoftDeletesField(SOFT_DELETES)
                .setIndexSort(new Sort(new SortField("order", SortField.Type.LONG)));
    }

    /**
     * The default codec under its own name, so that it reads with the default codec, but writing
     * {@value #POINTS_PER_LEAF} points per leaf.
     */
    private static Codec smallPointLeaves() {
        Codec codec = Codec.getDefault();
        PointsFormat points = codec.pointsFormat();
        return new FilterCodec(codec.getName(), codec) {
            @Override
            public PointsFormat pointsFormat() {
                return new PointsFormat() {
                    @Override
                    public PointsWriter fieldsWriter(SegmentWriteState state) throws IOException {
                        return new Lucene86PointsWriter(state, POINTS_PER_LEAF, BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP);
                    }

                    @Override
                    public PointsReader fieldsReader(SegmentReadState state) throws IOException {
                        return points.fieldsReader(state);
                    }
                };
            }
        };
    }

    /** Splits at whitespace and takes what follows a {@code |} as the payload. */
    private static final class PayloadAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new WhitespaceTokenizer();
            return new TokenStreamComponents(
                    tokenizer, new DelimitedPayloadTokenFilter(tokenizer, '|', new IdentityEncoder()));
        }
    }

    private static Document document(int i) {
        Document doc = new Document();
        doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
        StringBuilder body = new StringBuilder("alpha|a" + i + " document " + i);
        if (i % 2 == 1) {
            body.append(" beta");
        }
        if (i == 1) {
            body.append(" gamma gamma");
        }
        FieldType bodyType = new FieldType(TextField.TYPE_STORED);
        bodyType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        bodyType.setStoreTermVectors(true);
        bodyType.setStoreTermVectorPositions(true);
        bodyType.setStoreTermVectorOffsets(true);
        bodyType.setStoreTermVectorPayloads(true);
        doc.add(new Field("body", body.toString(), bodyType));
        FieldType tagType = new FieldType();
        tagType.setIndexOptions(IndexOptions.DOCS);
        tagType.setTokenized(false);
        tagType.setOmitNorms(true);
        doc.add(new Field("tag", i % 2 == 0 ? "even" : "odd", tagType));
        doc.add(new StoredField("price", i * 10));
        doc.add(new StoredField("blob", new byte[] {(byte) 0xff, (byte) 0xfe, (byte) i}));
        if (i != 3) {
            doc.add(new NumericDocValuesField("dv_num", i));
        }
        doc.add(new BinaryDocValuesField("dv_bin", bytes("b" + i)));
        doc.add(new SortedDocValuesField("dv_sorted", bytes("s" + i % 3)));
        for (String value : List.of("x", "y" + i)) {
            doc.add(new SortedSetDocValuesField("dv_set", bytes(value)));
        }
        doc.add(new SortedNumericDocValuesField("dv_sorted_num", i));
        doc.add(new SortedNumericDocValuesField("dv_sorted_num", -i));
        for (int k = 0; k < 15; k++) {
            doc.add(new IntPoint("pt", 100 * i + k));
        }
        for (int k = 0; k <= 10; k++) {
            doc.add(new IntPoint("loc", i, k));
        }
        doc.add(new LongPoint("amount", i * 1000L));
        doc.add(new NumericDocValuesField("order", i));
        return doc;
    }

    private static BytesRef bytes(String text) {
        return new BytesRef(text.getBytes(StandardCharsets.UTF_8));
    }
}
