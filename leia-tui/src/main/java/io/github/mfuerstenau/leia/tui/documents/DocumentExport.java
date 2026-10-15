package io.github.mfuerstenau.leia.tui.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exporting a document as JSON (ADR 0030): everything leia knows about it, put together in the UI
 * from {@code getDocument}, {@code getStoredValue}, {@code getTermVector} and the rebuild job of
 * ADR 0029, which rebuilds the points too (ADR 0041). The format is versioned and leia's own; its leaf values are the protocol records, binary
 * as hex. The UI writes the file, so it checks the path as the backend checks a terms export (ADR
 * 0022): never into an index directory (rule 1).
 */
public final class DocumentExport {

    /** The {@code format} of an export. */
    public static final String FORMAT = "leia-document";

    /** The {@code version} of the format; a change to it, or to a protocol record in it, bumps it. */
    public static final int VERSION = 3;

    /**
     * A document as leia exports it.
     *
     * @param index the path that was opened
     * @param indexDirectory the index directory the document is in, as shown; empty with only one
     * @param indexedTerms whether the indexed terms and points were rebuilt; if not, no field has
     *     {@code indexed} or {@code points}
     * @param fields the fields in which the document has something, in field number order
     */
    public record ExportedDocument(
            String format,
            int version,
            String index,
            Optional<String> indexDirectory,
            int docId,
            boolean deleted,
            boolean indexedTerms,
            List<ExportedField> fields) {}

    /**
     * What the document has in one field.
     *
     * @param number the field number; empty if the index doesn't know the field
     * @param flags as the documents screen shows them
     * @param indexOptions what the postings of the field hold, over the whole index
     * @param stored the stored values, in the order they were added, whole
     * @param vector the KNN vector; empty if the document has none in the field (version 2, ADR 0040)
     * @param termVector the term vector, in term order; empty if the document has none in the field
     * @param indexed the terms the field was indexed with; empty if the field has no postings or the
     *     terms weren't rebuilt
     * @param points the points the field was indexed with, as bytes per dimension in hex; empty if the
     *     field has no points or they weren't rebuilt (version 3, ADR 0041)
     */
    public record ExportedField(
            String name,
            OptionalInt number,
            String flags,
            IndexOptions indexOptions,
            OptionalLong norm,
            List<StoredValue> stored,
            Optional<DocValues> docValues,
            Optional<KnnVector> vector,
            Optional<List<TermVectorTerm>> termVector,
            Optional<IndexedTerms> indexed,
            Optional<List<PointValue>> points) {}

    /** The rebuilt terms of a field (ADR 0029), in term order, with their occurrences in the document. */
    public record IndexedTerms(ReconstructSource source, List<TermVectorTerm> terms) {}

    /**
     * What the backend says about a document, with every stored value whole: all of an export but
     * the rebuilt terms.
     *
     * @param termVectors the term vectors, by field name
     */
    public record DocumentData(GetDocumentResult document, Map<String, List<TermVectorTerm>> termVectors) {}

    /** Where {@link #load} gets a document from: the backend, or a test's data. */
    public interface Fetch {

        /** {@code getDocument}. */
        CompletionStage<GetDocumentResult> document(int docId);

        /** {@code getStoredValue}. */
        CompletionStage<StoredValue> storedValue(int docId, String field, int index);

        /** {@code getTermVector}. */
        CompletionStage<List<TermVectorTerm>> termVector(int docId, String field);
    }

    /**
     * serde's {@code to_string_pretty}, as the Rust TUI wrote the format: two spaces, {@code ": "},
     * {@code []} for an empty array, {@code \n} on every platform, and lowercase hex in
     * the escape of a control character (the mapper's part).
     */
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                    .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                    .withArrayEmptySeparator("")
                    .withObjectEmptySeparator(""))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_ABSENT))
            .build();

    private static final ObjectWriter WRITER = MAPPER.writer().with(PRETTY);

    private DocumentExport() {}

    /**
     * Document {@code docId}, with every stored value that {@code getDocument} cuts fetched whole,
     * and, {@code withTermVectors}, the term vector of every field that has one; an index that an old
     * Lucene reads may lack them (ADR 0044). The calls are made one after the other, and the first
     * that fails fails the load.
     */
    public static CompletionStage<DocumentData> load(Fetch fetch, int docId, boolean withTermVectors) {
        return fetch.document(docId).thenCompose(document -> {
            List<DocumentField> fields = new ArrayList<>(document.fields());
            Map<String, List<TermVectorTerm>> termVectors = new HashMap<>();
            CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
            for (int at = 0; at < fields.size(); at++) {
                DocumentField field = fields.get(at);
                List<StoredValue> stored = new ArrayList<>(field.stored());
                for (int index = 0; index < stored.size(); index++) {
                    if (stored.get(index).fullLength().isPresent()) {
                        int i = index;
                        chain = chain.thenCompose(_ -> fetch.storedValue(docId, field.name(), i))
                                .thenAccept(value -> stored.set(i, value));
                    }
                }
                int f = at;
                chain = chain.thenRun(() -> fields.set(
                        f,
                        new DocumentField(
                                field.name(),
                                field.norm(),
                                List.copyOf(stored),
                                field.docValues(),
                                field.hasTermVector(),
                                field.vector())));
                if (withTermVectors && field.hasTermVector()) {
                    chain = chain.thenCompose(_ -> fetch.termVector(docId, field.name()))
                            .thenAccept(terms -> termVectors.put(field.name(), terms));
                }
            }
            return chain.thenApply(_ -> new DocumentData(
                    new GetDocumentResult(document.docId(), document.deleted(), List.copyOf(fields)),
                    Map.copyOf(termVectors)));
        });
    }

    /**
     * The export of {@code data}, with the fields {@code schema} describes. {@code rebuilt} holds
     * the indexed terms and points, if they were rebuilt.
     */
    public static ExportedDocument assemble(
            DocumentData data,
            Optional<List<ReconstructedField>> rebuilt,
            Function<String, Optional<IndexField>> schema,
            String index,
            Optional<String> indexDirectory) {
        Map<String, ReconstructedField> byField = new LinkedHashMap<>();
        rebuilt.ifPresent(fields -> fields.forEach(field -> byField.put(field.field(), field)));
        List<ExportedField> fields = new ArrayList<>();
        for (DocumentField field : data.document().fields()) {
            Optional<ReconstructedField> indexed = Optional.ofNullable(byField.remove(field.name()));
            boolean hasTerms = indexed.filter(f -> !f.terms().isEmpty()).isPresent();
            boolean hasPoints = indexed.filter(f -> !f.points().isEmpty()).isPresent();
            if (Documents.isEmpty(field) && !hasTerms && !hasPoints) {
                continue;
            }
            Optional<IndexField> info = schema.apply(field.name());
            boolean postings =
                    info.map(f -> f.indexOptions() != IndexOptions.NONE).orElse(true);
            boolean points = info.map(f -> f.pointDimensionCount() > 0).orElse(hasPoints);
            fields.add(new ExportedField(
                    field.name(),
                    info.map(f -> OptionalInt.of(f.number())).orElse(OptionalInt.empty()),
                    Documents.flags(info, field),
                    info.map(IndexField::indexOptions).orElse(IndexOptions.NONE),
                    field.norm(),
                    field.stored(),
                    field.docValues(),
                    field.vector(),
                    Optional.ofNullable(data.termVectors().get(field.name())),
                    indexed.filter(_ -> postings).map(f -> new IndexedTerms(f.source(), f.terms())),
                    indexed.filter(_ -> points).map(ReconstructedField::points)));
        }
        return new ExportedDocument(
                FORMAT,
                VERSION,
                index,
                indexDirectory,
                data.document().docId(),
                data.document().deleted(),
                rebuilt.isPresent(),
                List.copyOf(fields));
    }

    /** {@code document} as pretty-printed JSON, ending in a line break. */
    public static String toJson(ExportedDocument document) {
        return WRITER.writeValueAsString(document) + "\n";
    }

    /** An export read back; anything it doesn't know fails. */
    public static ExportedDocument fromJson(String json) {
        return MAPPER.readValue(json, ExportedDocument.class);
    }

    /** The file an export is offered to: {@code doc-<id>.json}. */
    public static String defaultFile(int docId) {
        return "doc-" + docId + ".json";
    }

    /**
     * Checks that an export may write {@code path}, as the backend checks a terms export (ADR 0022):
     * an absolute path whose directory exists, outside and not below every index directory in {@code
     * indexDirs}, not a directory or a symbolic link, and not there yet unless {@code overwrite}.
     * Real paths are compared, so a link into an index directory is caught. Returns why not.
     */
    public static Optional<String> checkTarget(Path path, boolean overwrite, List<String> indexDirs) {
        if (!path.isAbsolute()) {
            return Optional.of("The path must be absolute: " + path);
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return Optional.of("No such directory: " + (parent == null ? path : parent));
        }
        Path realParent;
        try {
            realParent = parent.toRealPath();
        } catch (IOException e) {
            return Optional.of("Cannot resolve " + parent + ": " + reason(e));
        }
        for (String dir : indexDirs) {
            Path realDir;
            try {
                realDir = Path.of(dir).toRealPath();
            } catch (IOException e) {
                realDir = Path.of(dir);
            }
            if (realParent.startsWith(realDir)) {
                return Optional.of("Not writing into the index directory " + dir + ": leia never changes an index");
            }
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (attributes.isSymbolicLink()) {
            return Optional.of("Not writing through a symbolic link: " + path);
        }
        if (attributes.isDirectory()) {
            return Optional.of("Is a directory: " + path);
        }
        return overwrite ? Optional.empty() : Optional.of("File exists: " + path);
    }

    /**
     * Writes {@code text} to {@code path} after {@link #checkTarget}. If writing fails, what was
     * written is deleted. Returns why it wasn't written.
     */
    public static Optional<String> writeFile(Path path, String text, boolean overwrite, List<String> indexDirs) {
        Optional<String> refused = checkTarget(path, overwrite, indexDirs);
        if (refused.isPresent()) {
            return refused;
        }
        OpenOption[] options = overwrite
                ? new OpenOption[] {
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                }
                : new OpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW};
        FileChannel channel;
        try {
            channel = FileChannel.open(path, options);
        } catch (FileAlreadyExistsException e) {
            return Optional.of("File exists: " + path);
        } catch (IOException e) {
            return Optional.of("Cannot create " + path + ": " + reason(e));
        }
        try (channel) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
            return Optional.empty();
        } catch (IOException e) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // What was written stays; the error says the export failed.
            }
            return Optional.of("Cannot write " + path + ": " + reason(e));
        }
    }

    /** What went wrong, without the path that the message names anyway. */
    private static String reason(IOException e) {
        if (e instanceof NoSuchFileException) {
            return "No such file or directory";
        }
        if (e instanceof FileSystemException fs && fs.getReason() != null) {
            return fs.getReason();
        }
        if (e instanceof FileSystemException) {
            return e.getClass().getSimpleName();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
