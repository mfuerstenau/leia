package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.document.LatLonPoint;
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
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;

/**
 * Writes a demo index for trying leia by hand (build tooling, not part of leia).
 *
 * <p>A small product catalogue with a field for nearly everything leia shows: every index option,
 * norms, payloads, term vectors, each doc values type, points, KNN vectors, binary terms, stored
 * values of every type, JSON and delimited values for the viewer, values over the 64 KiB cut, and
 * sparse and empty fields. 500 documents in 3 segments, some deleted, with commit user data, and
 * all 4 commits kept. The content is random but the same on every run. See {@code
 * docs/demo-index.md} for what is where.
 */
public final class DemoIndexWriter {

    private static final int DOCS = 500;
    /** A commit, and with it a segment, after this many documents. */
    private static final int SEGMENT_SIZE = 200;
    /** The documents whose number is a multiple of this are deleted, except for document 0. */
    private static final int DELETE_EVERY = 37;
    /** A document with huge values: a long body, a large JSON source and a large binary value. */
    private static final int HUGE_DOC = 42;
    /** A document with unusual text: control characters, emoji, wide characters. */
    private static final int ODD_TEXT_DOC = 7;
    /** The only document with the field {@code deleted_only}; it is deleted. */
    private static final int DELETED_ONLY_DOC = 74;

    private static final String[] WORDS = {
        "the",
        "a",
        "and",
        "of",
        "with",
        "for",
        "in",
        "to",
        "is",
        "on",
        "quick",
        "brown",
        "fox",
        "lazy",
        "dog",
        "red",
        "blue",
        "green",
        "black",
        "white",
        "leather",
        "wooden",
        "steel",
        "cotton",
        "wallet",
        "chair",
        "table",
        "lamp",
        "shirt",
        "jacket",
        "bottle",
        "kettle",
        "knife",
        "pan",
        "mug",
        "backpack",
        "notebook",
        "pencil",
        "camera",
        "speaker",
        "light",
        "heavy",
        "small",
        "large",
        "classic",
        "modern",
        "vintage",
        "durable",
        "portable",
        "waterproof",
        "handmade",
        "premium",
        "budget",
        "compact",
        "ergonomic",
        "wireless",
        "rechargeable",
        "stainless",
        "organic",
        "recycled",
        "grüße",
        "café",
        "naïve",
        "日本",
        "東京",
        "señor",
        "smörgåsbord",
        "kitchen",
        "garden",
        "office",
        "outdoor",
        "travel",
        "sport",
        "winter",
        "summer",
        "gift",
        "set",
        "pack",
        "pair",
        "edition",
        "series",
        "model",
        "version",
        "inch",
        "cm",
        "kg",
        "watt",
        "volt",
        "2024",
        "2025",
        "2026",
        "xl",
        "xxl",
        "pro",
        "max",
        "mini",
        "ultra",
        "zebra"
    };
    private static final String[] CATEGORIES = {"kitchen", "office", "outdoor", "clothing", "electronics", "toys"};
    private static final String[] TAGS = {"new", "sale", "bestseller", "eco", "limited", "imported", "local"};
    private static final String[] CITIES = {"Berlin", "Hamburg", "München", "Köln", "Wien", "Zürich"};
    private static final double[][] WAREHOUSES = {
        {52.52, 13.405}, {53.551, 9.993}, {48.137, 11.575}, {50.937, 6.96}, {48.208, 16.373}, {47.376, 8.541}
    };

    private DemoIndexWriter() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        // Start empty, so every run writes the same index.
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Random random = new Random(42);
        try (Directory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig(new DemoAnalyzer())
                                // No merges: keep the segments and the deleted documents.
                                .setMergePolicy(NoMergePolicy.INSTANCE)
                                // Keep every commit, for the commits screen.
                                .setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE))) {
            for (int i = 0; i < DOCS; i++) {
                writer.addDocument(i == 0 ? sparse() : product(i, random));
                if ((i + 1) % SEGMENT_SIZE == 0) {
                    writer.commit();
                    // The last segment is not in a compound file, so that both kinds show.
                    writer.getConfig().setUseCompoundFile(i + 1 + SEGMENT_SIZE < DOCS);
                }
            }
            writer.commit();
            for (int i = DELETE_EVERY; i < DOCS; i += DELETE_EVERY) {
                writer.deleteDocuments(new Term("id", id(i)));
            }
            writer.setLiveCommitData(Map.of(
                            "writer", "leia DemoIndexWriter",
                            "source", "demo product catalogue",
                            "docs", Integer.toString(DOCS))
                    .entrySet());
            writer.commit();
        }
    }

    /** Document 0: only an id, so nearly every field is empty. */
    private static Document sparse() {
        Document doc = new Document();
        doc.add(new StringField("id", id(0), Field.Store.YES));
        return doc;
    }

    private static Document product(int i, Random random) {
        Document doc = new Document();
        String category = pick(CATEGORIES, random);
        String city = pick(CITIES, random);
        String title = capitalize(words(random, 2 + random.nextInt(5)));
        String body = i == HUGE_DOC ? words(random, 5000) : sentences(random, 1 + random.nextInt(8));
        double price = Math.round(random.nextDouble() * 50_000) / 100.0;
        int quantity = random.nextInt(1000) - 50;
        float rating = Math.round(random.nextFloat() * 50) / 10f;
        long timestamp = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() + random.nextLong(300L * 86_400_000L);
        List<String> tags = random.nextInt(5) == 0
                ? List.of()
                : IntStream.range(0, 1 + random.nextInt(3))
                        .mapToObj(n -> pick(TAGS, random))
                        .distinct()
                        .toList();

        // Terms: every index option, norms or not.
        doc.add(new StringField("id", id(i), Field.Store.YES));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new Field("body", body, BODY));
        doc.add(new Field("keywords", words(random, 3 + random.nextInt(4)), DOCS_WITH_NORMS));
        doc.add(new Field("labels", words(random, 2 + random.nextInt(6)), DOCS_AND_FREQS));
        doc.add(new Field("tagged", sentences(random, 1), PAYLOADS));
        doc.add(new StringField("category", category, Field.Store.NO));
        doc.add(new SortedDocValuesField("category", new BytesRef(category)));
        for (String tag : tags) {
            doc.add(new StringField("tags", tag, Field.Store.YES));
            doc.add(new SortedSetDocValuesField("tags", new BytesRef(tag)));
        }
        // Binary terms that are not UTF-8: shown in hex.
        byte[] key = {(byte) 0xff, (byte) (i >> 8), (byte) i, (byte) 0x80};
        doc.add(new StringField("binary_key", new BytesRef(key), Field.Store.YES));

        // Numbers: points, stored values and doc values of each kind.
        doc.add(new DoublePoint("price", price));
        doc.add(new StoredField("price", price));
        doc.add(new DoubleDocValuesField("price", price));
        doc.add(new IntPoint("quantity", quantity));
        doc.add(new StoredField("quantity", quantity));
        doc.add(new NumericDocValuesField("quantity", quantity));
        doc.add(new FloatPoint("rating", rating));
        doc.add(new StoredField("rating", rating));
        doc.add(new FloatDocValuesField("rating", rating));
        doc.add(new LongPoint("timestamp", timestamp));
        doc.add(new StoredField("timestamp", timestamp));
        doc.add(new SortedNumericDocValuesField("timestamp", timestamp));
        for (int n = 0; n < 1 + random.nextInt(4); n++) {
            doc.add(new SortedNumericDocValuesField("scores", random.nextInt(201) - 100));
        }
        double weight = Math.round(random.nextDouble() * 2000) / 100.0;
        doc.add(new SortedNumericDocValuesField("weight", NumericUtils.doubleToSortableLong(weight)));
        double[] warehouse = WAREHOUSES[random.nextInt(WAREHOUSES.length)];
        doc.add(new LatLonPoint("location", warehouse[0], warehouse[1]));
        doc.add(new LatLonDocValuesField("location", warehouse[0], warehouse[1]));
        byte[] checksum = new byte[8];
        random.nextBytes(checksum);
        doc.add(new BinaryDocValuesField("checksum", new BytesRef(checksum)));

        // KNN vectors.
        float[] embedding = new float[8];
        for (int n = 0; n < embedding.length; n++) {
            embedding[n] = random.nextFloat() * 2 - 1;
        }
        embedding[0] += 2; // never all zeros, which cosine rejects
        doc.add(new KnnFloatVectorField("embedding", embedding, VectorSimilarityFunction.COSINE));
        byte[] fingerprint = new byte[16];
        random.nextBytes(fingerprint);
        doc.add(new KnnByteVectorField("fingerprint", fingerprint, VectorSimilarityFunction.EUCLIDEAN));

        // Values for the viewer.
        String source = source(i, title, category, city, price, quantity, rating, tags, random);
        doc.add(new StoredField("_source", new BytesRef(source.getBytes(StandardCharsets.UTF_8))));
        for (String json : jsonSamples(i, random)) {
            doc.add(new StoredField("json", json));
        }
        doc.add(new StoredField("csv", String.join(",", id(i), title, city, Integer.toString(quantity), "", category)));
        doc.add(new StoredField("tsv", String.join("\t", id(i), city, Double.toString(price), category)));
        doc.add(new StoredField(
                "kv",
                "id=" + id(i) + ";city=" + city + ";price=" + price + ";tags=" + String.join(",", tags)
                        + ";formula=a=b+c;flag;very_long_key_name_for_alignment=yes"));
        doc.add(new StoredField(
                "log",
                Instant.ofEpochMilli(timestamp) + "|" + pick(new String[] {"INFO", "WARN", "ERROR"}, random)
                        + "|order-service|order " + i + " shipped to " + city));
        doc.add(new StoredField(
                "unit_separated", "name\u001e" + title + "\u001fcity\u001e" + city + "\u001fqty\u001e" + quantity));
        doc.add(new StoredField(
                "lines",
                IntStream.rangeClosed(1, 3 + random.nextInt(20))
                        .mapToObj(n -> "line " + n + ": " + words(random, 4))
                        .collect(Collectors.joining("\n"))));
        // One field, values of every stored type: ←/→ in the viewer.
        doc.add(new StoredField("mixed", "text " + i));
        doc.add(new StoredField("mixed", i));
        doc.add(new StoredField("mixed", (long) i * 1_000_000_007L));
        doc.add(new StoredField("mixed", i / 3f));
        doc.add(new StoredField("mixed", i / 7d));
        doc.add(new StoredField("mixed", new BytesRef(key)));
        doc.add(new StoredField("mixed", "{\"doc\":" + i + "}"));

        // Sparse and empty fields.
        if (i % 10 == 3) {
            doc.add(new TextField("note", "Note for " + id(i) + ": " + sentences(random, 1), Field.Store.YES));
        }
        if (i % 25 == 5) {
            doc.add(new StoredField("empty_string", ""));
        }
        if (i == DELETED_ONLY_DOC) {
            doc.add(new TextField("deleted_only", "only in a deleted document", Field.Store.YES));
        }
        if (i == ODD_TEXT_DOC) {
            doc.add(new TextField(
                    "odd_text",
                    "tab\there, bell\u0007, nul\u0000, CRLF\r\nnext line, emoji 🦊🐶, wide 日本語テキスト, combining e\u0301",
                    Field.Store.YES));
        }
        if (i == HUGE_DOC) {
            byte[] blob = new byte[100_000];
            random.nextBytes(blob);
            blob[0] = (byte) 0xff; // never valid UTF-8
            doc.add(new StoredField("blob", blob));
        }
        return doc;
    }

    private static String id(int i) {
        return "doc-%04d".formatted(i);
    }

    /** The document as JSON, like the {@code _source} field of Elasticsearch or OpenSearch. */
    private static String source(
            int i,
            String title,
            String category,
            String city,
            double price,
            int quantity,
            float rating,
            List<String> tags,
            Random random) {
        StringBuilder json = new StringBuilder()
                .append("{\"id\":")
                .append(quote(id(i)))
                .append(",\"title\":")
                .append(quote(title))
                .append(",\"category\":")
                .append(quote(category))
                .append(",\"price\":")
                .append(price)
                .append(",\"quantity\":")
                .append(quantity)
                .append(",\"rating\":")
                .append(rating)
                .append(",\"tags\":[")
                .append(tags.stream().map(DemoIndexWriter::quote).collect(Collectors.joining(",")))
                .append("],\"warehouse\":{\"city\":")
                .append(quote(city))
                .append(",\"open\":")
                .append(random.nextBoolean())
                .append(",\"manager\":null},\"dimensions\":{}");
        if (i == HUGE_DOC) {
            // Over the 64 KiB cut, so the viewer fetches it whole.
            json.append(",\"history\":[");
            for (int n = 0; n < 3000; n++) {
                json.append(n == 0 ? "" : ",")
                        .append("{\"n\":")
                        .append(n)
                        .append(",\"event\":")
                        .append(quote(words(random, 3)))
                        .append('}');
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    /** JSON in every shape, and some that is not JSON. */
    private static List<String> jsonSamples(int i, Random random) {
        String deep = "[".repeat(20) + i + "]".repeat(20);
        // Deeper than serde_json's recursion limit for values; validating into IgnoredAny has none.
        String veryDeep = "[".repeat(200) + "]".repeat(200);
        return List.of(
                "{\"order\":" + i + ",\"total\":2.50,\"big\":12345678901234567890123,\"exp\":-1.5e3,"
                        + "\"unicode\":\"gr\\u00fc\\u00dfe \u65e5\u672c\",\"escaped\":\"quote \\\" slash \\\\ tab \\t\","
                        + "\"empty\":{},\"none\":[],\"dup\":1,\"dup\":2,\"nested\":{\"a\":{\"b\":{\"c\":[true,false,null]}}}}",
                "[{\"line\":1,\"sku\":\"A-" + random.nextInt(1000) + "\"},{\"line\":2,\"sku\":\"B-"
                        + random.nextInt(1000) + "\"}]",
                "\"just a JSON string\"",
                Integer.toString(i * 31),
                deep,
                "{\"trailing\":\"comma\",}",
                "{\"unterminated\": \"str",
                "not json at all",
                veryDeep);
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String pick(String[] values, Random random) {
        return values[random.nextInt(values.length)];
    }

    /** Words with a skewed distribution, so the top terms stand out. */
    private static String words(Random random, int count) {
        List<String> words = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            words.add(WORDS[(int) (Math.pow(random.nextDouble(), 2.5) * WORDS.length)]);
        }
        return String.join(" ", words);
    }

    private static String sentences(Random random, int count) {
        return IntStream.range(0, count)
                // A rare SKU per sentence: a long tail of terms, more than a page on the terms screen.
                .mapToObj(
                        n -> capitalize(words(random, 4 + random.nextInt(14))) + " sku" + random.nextInt(10_000) + ".")
                .collect(Collectors.joining(" "));
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Positions, offsets and term vectors with positions and offsets; stored. */
    private static final FieldType BODY = fieldType(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS, true);

    /** Documents only, but with norms: unlike {@code StringField}, it is tokenized. */
    private static final FieldType DOCS_WITH_NORMS = fieldType(IndexOptions.DOCS, false);

    private static final FieldType DOCS_AND_FREQS = fieldType(IndexOptions.DOCS_AND_FREQS, false);

    /** Positions with payloads (from {@link DemoAnalyzer}), and term vectors with payloads. */
    private static final FieldType PAYLOADS = payloads();

    private static FieldType fieldType(IndexOptions options, boolean termVectors) {
        FieldType type = new FieldType();
        type.setIndexOptions(options);
        type.setTokenized(true);
        type.setStored(termVectors);
        if (termVectors) {
            type.setStoreTermVectors(true);
            type.setStoreTermVectorPositions(true);
            type.setStoreTermVectorOffsets(true);
        }
        type.freeze();
        return type;
    }

    private static FieldType payloads() {
        FieldType type = new FieldType();
        type.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        type.setTokenized(true);
        type.setStored(true);
        type.setStoreTermVectors(true);
        type.setStoreTermVectorPositions(true);
        type.setStoreTermVectorPayloads(true);
        type.freeze();
        return type;
    }

    /** Lower-cased standard tokens; the field {@code tagged} gets payloads. */
    private static final class DemoAnalyzer extends Analyzer {
        DemoAnalyzer() {
            // The default reuses one field's components for all fields, payloads included.
            super(PER_FIELD_REUSE_STRATEGY);
        }

        @Override
        protected TokenStreamComponents createComponents(String field) {
            StandardTokenizer tokenizer = new StandardTokenizer();
            TokenStream stream = new LowerCaseFilter(tokenizer);
            if (field.equals("tagged")) {
                stream = new PayloadFilter(stream);
            }
            return new TokenStreamComponents(tokenizer, stream);
        }
    }

    /**
     * A made-up part of speech as a text payload, and for numbers a 4-byte big-endian int, which
     * shows as hex.
     */
    private static final class PayloadFilter extends TokenFilter {
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final PayloadAttribute payload = addAttribute(PayloadAttribute.class);

        PayloadFilter(TokenStream input) {
            super(input);
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (!input.incrementToken()) {
                return false;
            }
            String text = term.toString();
            if (text.chars().allMatch(Character::isDigit)) {
                int n = Integer.parseInt(text);
                payload.setPayload(
                        new BytesRef(new byte[] {(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n}));
            } else {
                String tag = text.length() <= 3 ? "DET" : text.endsWith("e") ? "VB" : "NN";
                payload.setPayload(new BytesRef(tag));
            }
            return true;
        }
    }
}
