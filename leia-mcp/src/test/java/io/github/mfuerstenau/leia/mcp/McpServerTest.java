package io.github.mfuerstenau.leia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.mcp.tools.Tools;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * MCP mode over the demo index (docs/demo-index.md), through a real backend, as an MCP client
 * speaks to it on stdin and stdout (ADR 0100).
 */
@Timeout(60)
class McpServerTest {

    private static LeiaBackend backend;
    private static VersionResult version;
    private static String demo;

    private BlockingTool blocking;
    private McpClient client;

    @BeforeAll
    static void startBackend() throws Exception {
        backend = LeiaBackend.start(Optional.empty(), List.of());
        version = backend.call(Methods.VERSION, new VersionParams()).get();
        String property = System.getProperty("leia.test.index.demo");
        demo = Path.of(property).toAbsolutePath().normalize().toString();
    }

    @AfterAll
    static void closeBackend() {
        backend.close();
    }

    @BeforeEach
    void serve() throws Exception {
        blocking = new BlockingTool();
        List<McpTool<?, ?>> tools = new ArrayList<>(Tools.ALL);
        tools.add(blocking);
        McpServer server =
                new McpServer(backend, version, new Session(DirectoryImpl.FS, Optional.empty(), List.of()), tools);
        server.open(demo);
        client = new McpClient(server);
    }

    @AfterEach
    void end() throws Exception {
        blocking.release.countDown();
        client.close();
    }

    @Test
    void negotiatesTheProtocolVersion() throws Exception {
        JsonNode known = client.request(1, "initialize", initialize("2025-06-18"));
        JsonNode unknown = client.request(2, "initialize", initialize("1999-01-01"));

        assertThat(known.path("result").path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(known.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("leia");
        assertThat(known.path("result").path("serverInfo").path("version").asString())
                .isEqualTo(version.version());
        assertThat(known.path("result").path("capabilities").toString())
                .isEqualTo("{\"tools\":{\"listChanged\":false}}");
        assertThat(unknown.path("result").path("protocolVersion").asString()).isEqualTo("2025-11-25");
    }

    @Test
    void answersAPingAndRefusesWhatItDoesntKnow() throws Exception {
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(client.request(1, "ping", "{}").path("result").toString()).isEqualTo("{}");
        assertThat(client.request(2, "resources/list", "{}")
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32601);
        assertThat(client.request(3, "tools/call", "{\"name\":\"drop_index\"}")
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-32602);

        client.send("{not json");
        client.send("[1, 2]");
        client.send("{\"jsonrpc\":\"1.0\",\"id\":4,\"method\":\"ping\"}");
        client.request(5, "ping", "{}");

        List<JsonNode> errors = client.received().stream()
                .filter(message -> message.has("error"))
                .toList();
        assertThat(errors)
                .extracting(error -> error.path("error").path("code").asInt())
                .containsExactly(-32601, -32602, -32700, -32600, -32600);
        assertThat(errors.get(2).get("id").isNull()).isTrue();
        assertThat(errors.get(4).get("id").asInt()).isEqualTo(4);
    }

    @Test
    void listsTheTools() throws Exception {
        JsonNode tools = client.request(1, "tools/list", "{}").path("result").path("tools");

        assertThat(StreamSupport.stream(tools.spliterator(), false)
                        .map(tool -> tool.path("name").asString())
                        .toList())
                .startsWith(
                        "open_index",
                        "index_info",
                        "search",
                        "analyze",
                        "terms",
                        "get_document",
                        "get_field_value",
                        "reconstruct_fields");
    }

    @Test
    void searchesAPageAtATime() throws Exception {
        JsonNode first = client.structured(
                1, "search", "{\"query\":\"title:lamp\",\"defaultField\":\"body\",\"fields\":[\"id\"],\"limit\":3}");
        JsonNode second = client.structured(
                2,
                "search",
                "{\"query\":\"lamp\",\"defaultField\":\"title\",\"fields\":[\"id\"],\"limit\":3,\"offset\":"
                        + first.path("nextOffset").asInt() + "}");

        assertThat(first.path("query").asString()).isEqualTo("title:lamp");
        assertThat(first.path("hits")).hasSize(3);
        assertThat(first.path("offset").asInt()).isZero();
        assertThat(first.path("nextOffset").asInt()).isEqualTo(3);
        assertThat(first.path("hits").get(0).path("fields").get(0).path("name").asString())
                .isEqualTo("id");
        assertThat(second.path("offset").asInt()).isEqualTo(3);
        assertThat(second.path("totalHits").asLong())
                .isEqualTo(first.path("totalHits").asLong());
        assertThat(second.path("hits").get(0).path("docId").asInt()).isNotIn(docIds(first));
    }

    @Test
    void searchesWithTheAnalyzerOfTheField() throws Exception {
        String keyword = "\"query\":\"id:doc-0042\",\"defaultField\":\"body\",\"fields\":[\"id\"]";

        JsonNode standard = client.structured(1, "search", "{" + keyword + "}");
        JsonNode named = client.structured(2, "search", "{" + keyword + ",\"analyzer\":\"KeywordAnalyzer\"}");
        JsonNode perField = client.structured(
                3,
                "search",
                "{" + keyword + ",\"fieldAnalyzers\":[{\"field\":\"id\",\"analyzer\":\"KeywordAnalyzer\"}]}");
        JsonNode qualified = client.structured(
                4, "search", "{" + keyword + ",\"analyzer\":\"org.apache.lucene.analysis.core.KeywordAnalyzer\"}");

        assertThat(standard.path("totalHits").asLong()).isNotEqualTo(1);
        assertThat(docIds(named)).containsExactly(42);
        assertThat(docIds(perField)).containsExactly(42);
        assertThat(docIds(qualified)).containsExactly(42);
    }

    @Test
    void refusesAnUnknownAnalyzerAndOneWithATerm() throws Exception {
        assertThat(client.error(
                        1, "search", "{\"query\":\"lamp\",\"defaultField\":\"title\",\"analyzer\":\"NoSuchAnalyzer\"}"))
                .startsWith("No analyzer NoSuchAnalyzer; there are ")
                .contains("KeywordAnalyzer")
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(
                        2,
                        "search",
                        "{\"term\":{\"field\":\"id\",\"value\":\"doc-0042\"},\"analyzer\":\"KeywordAnalyzer\"}"))
                .contains("go with query only")
                .endsWith("(INVALID_PARAMS)");
    }

    @Test
    void analyzesATextWithStandardAndNamedAnalyzers() throws Exception {
        JsonNode standard = client.structured(1, "analyze", "{\"text\":\"doc-0042 The Lamp\"}");
        JsonNode keyword =
                client.structured(2, "analyze", "{\"text\":\"doc-0042 The Lamp\",\"analyzer\":\"KeywordAnalyzer\"}");

        assertThat(standard.path("analyzer").asString()).endsWith("StandardAnalyzer");
        assertThat(standard.path("tokenCount").asInt()).isEqualTo(4);
        assertThat(standard.has("cut")).isFalse();
        JsonNode tokens = standard.path("tokens");
        assertThat(StreamSupport.stream(tokens.spliterator(), false)
                        .map(token -> token.path("term").asString())
                        .toList())
                .containsExactly("doc", "0042", "the", "lamp");
        assertThat(tokens.get(1).path("position").asInt()).isEqualTo(1);
        assertThat(tokens.get(1).path("startOffset").asInt()).isEqualTo(4);
        assertThat(tokens.get(1).path("endOffset").asInt()).isEqualTo(8);
        assertThat(tokens.get(1).has("positionLength")).isFalse();
        assertThat(keyword.path("tokens")).hasSize(1);
        assertThat(keyword.path("tokens").get(0).path("term").asString()).isEqualTo("doc-0042 The Lamp");
    }

    @Test
    void cutsTheTokensOfAnAnalysisAtMaxTokens() throws Exception {
        JsonNode result = client.structured(1, "analyze", "{\"text\":\"doc-0042 The Lamp\",\"maxTokens\":2}");

        assertThat(result.path("tokens")).hasSize(2);
        assertThat(result.path("tokenCount").asInt()).isEqualTo(4);
        assertThat(result.path("cut").asBoolean()).isTrue();
    }

    @Test
    void refusesAnUnknownAnalyzerAndBadLimitsOfAnAnalysis() throws Exception {
        assertThat(client.error(1, "analyze", "{\"text\":\"lamp\",\"analyzer\":\"NoSuchAnalyzer\"}"))
                .startsWith("No analyzer NoSuchAnalyzer; there are ")
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(2, "analyze", "{\"text\":\"lamp\",\"maxTokens\":0}"))
                .startsWith("maxTokens must be from 1 to 10000, not 0");
    }

    @Test
    void analyzesWithoutAnOpenIndex() throws Exception {
        McpServer server =
                new McpServer(backend, version, new Session(DirectoryImpl.FS, Optional.empty(), List.of()), Tools.ALL);
        McpClient closed = new McpClient(server);
        try {
            assertThat(closed.structured(1, "analyze", "{\"text\":\"The Lamp\"}")
                            .path("tokenCount")
                            .asInt())
                    .isEqualTo(2);
        } finally {
            closed.close();
        }
    }

    @Test
    void searchesAnExactTerm() throws Exception {
        JsonNode result = client.structured(1, "search", "{\"term\":{\"field\":\"id\",\"value\":\"doc-0042\"}}");

        assertThat(docIds(result)).containsExactly(42);
        assertThat(result.path("totalHits").asLong()).isEqualTo(1);
        assertThat(result.has("nextOffset")).isFalse();
        JsonNode id = result.path("hits").get(0).path("fields").get(0);
        assertThat(id.path("name").asString()).isEqualTo("id");
        assertThat(id.path("values").get(0).toString()).isEqualTo("{\"value\":\"doc-0042\"}");
    }

    @Test
    void showsBriefValuesWithinABudgetByDefault() throws Exception {
        JsonNode result = client.structured(1, "search", "{\"term\":{\"field\":\"id\",\"value\":\"doc-0042\"}}");

        JsonNode hit = result.path("hits").get(0);
        assertThat(hit.path("moreFields")).isNotEmpty();
        boolean cut = false;
        for (JsonNode field : hit.path("fields")) {
            for (JsonNode value : field.path("values")) {
                assertThat(value.path("value").asString().length()).isLessThanOrEqualTo(100);
                cut |= value.path("cut").asBoolean();
            }
        }
        assertThat(cut).isTrue();
    }

    @Test
    void showsTheAskedFieldsWithoutABudget() throws Exception {
        JsonNode result = client.structured(
                1,
                "search",
                "{\"term\":{\"field\":\"id\",\"value\":\"doc-0042\"},\"fields\":[\"body\",\"_source\"],"
                        + "\"maxValueLength\":200}");

        JsonNode hit = result.path("hits").get(0);
        assertThat(hit.has("moreFields")).isFalse();
        assertThat(hit.path("fields")).hasSize(2);
        for (JsonNode field : hit.path("fields")) {
            JsonNode value = field.path("values").get(0);
            assertThat(value.path("value").asString()).hasSize(200);
            assertThat(value.path("cut").asBoolean()).isTrue();
        }
        assertThat(client.error(
                        2, "search", "{\"term\":{\"field\":\"id\",\"value\":\"doc-0042\"},\"maxValueLength\":257}"))
                .contains("maxValueLength must be from 1 to 256");
    }

    @Test
    void leavesOutWhatIsTheDefaultInADocumentAndTheFields() throws Exception {
        JsonNode plain = client.structured(1, "get_document", "{\"docId\":0}")
                .path("fields")
                .get(0);
        assertThat(plain.has("hasTermVector")).isFalse();
        assertThat(plain.path("stored").get(0).has("type")).isFalse();
        assertThat(plain.path("stored").get(0).has("binary")).isFalse();

        JsonNode blob = client.structured(2, "get_document", "{\"docId\":42,\"fields\":[\"blob\"]}")
                .path("fields")
                .get(0)
                .path("stored")
                .get(0);
        assertThat(blob.path("binary").asBoolean()).isTrue();
        assertThat(blob.path("type").asString()).isEqualTo("BINARY");

        JsonNode fields = client.structured(3, "index_info", "{}").path("fields");
        for (JsonNode field : fields) {
            if (field.path("name").asString().equals("id")) {
                assertThat(field.has("docValuesType")).isFalse();
                assertThat(field.has("pointDimensionCount")).isFalse();
            }
            if (field.path("name").asString().equals("category")) {
                assertThat(field.path("docValuesType").asString()).isEqualTo("SORTED");
            }
        }
    }

    @Test
    void namesTheEncodingOfNumericDocValuesThatRepeatTheStoredValue() throws Exception {
        JsonNode fields = client.structured(
                        1,
                        "get_document",
                        "{\"docId\":1,\"fields\":[\"price\",\"rating\",\"quantity\",\"timestamp\",\"weight\"]}")
                .path("fields");
        java.util.Map<String, JsonNode> byName = new java.util.HashMap<>();
        for (JsonNode field : fields) {
            byName.put(field.path("name").asString(), field.path("docValues"));
        }

        assertThat(byName.get("price").path("asStored").asString()).isEqualTo("DOUBLE");
        assertThat(byName.get("rating").path("asStored").asString()).isEqualTo("FLOAT");
        assertThat(byName.get("quantity").path("asStored").asString()).isEqualTo("LONG");
        assertThat(byName.get("timestamp").path("asStored").asString()).isEqualTo("LONG");
        for (String name : List.of("price", "rating", "quantity", "timestamp")) {
            assertThat(byName.get(name).has("numbers")).as(name).isFalse();
            assertThat(byName.get(name).path("count").asInt()).as(name).isEqualTo(1);
        }
        // Nothing stored to confirm the encoding: the raw numbers stay.
        assertThat(byName.get("weight").has("asStored")).isFalse();
        assertThat(byName.get("weight").path("numbers")).hasSize(1);
        // A string, so that a client's JSON number can't round digits beyond 2^53.
        JsonNode weight = byName.get("weight").path("numbers").get(0);
        assertThat(weight.isString()).isTrue();
        assertThat(Long.parseLong(weight.asString())).isGreaterThan(1L << 53);
    }

    @Test
    void refusesABadQueryOrBadArguments() throws Exception {
        assertThat(client.error(1, "search", "{\"query\":\"title:(lamp\",\"defaultField\":\"body\"}"))
                .endsWith("(INVALID_QUERY)");
        assertThat(client.error(2, "search", "{\"query\":\"lamp\",\"defaultField\":\"title\",\"bogus\":1}"))
                .contains("bogus")
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(3, "search", "{\"query\":\"lamp\"}")).contains("defaultField");
        assertThat(client.error(4, "search", "{\"defaultField\":\"title\"}"))
                .contains("exactly one of query, term and docValues");
        assertThat(client.error(5, "search", "{\"query\":\"lamp\",\"defaultField\":\"title\",\"limit\":101}"))
                .contains("limit must be from 1 to 100");
        assertThat(client.error(6, "search", "{\"term\":{\"value\":\"doc-0042\"}}"))
                .isEqualTo("Missing argument term.field (INVALID_PARAMS)");
        assertThat(client.error(7, "get_document", "{}")).isEqualTo("Missing argument docId (INVALID_PARAMS)");
        assertThat(client.error(8, "get_document", "{\"docId\":\"seven\"}")).endsWith("(INVALID_PARAMS)");
        assertThat(client.error(9, "get_document", "{\"docId\":500}")).endsWith("(DOC_NOT_FOUND)");
    }

    @Test
    void showsADocumentWithOnlyTheFieldsItHasSomethingIn() throws Exception {
        JsonNode document = client.structured(1, "get_document", "{\"docId\":0}");

        assertThat(document.path("deleted").asBoolean()).isFalse();
        assertThat(document.path("fields")).hasSize(1);
        assertThat(document.path("fields").get(0).path("name").asString()).isEqualTo("id");
        assertThat(document.path("fields")
                        .get(0)
                        .path("stored")
                        .get(0)
                        .path("value")
                        .asString())
                .isEqualTo("doc-0000");
    }

    @Test
    void cutsALongValueAndReadsItWholeInSlices() throws Exception {
        JsonNode source = client.structured(1, "get_document", "{\"docId\":42,\"fields\":[\"_source\"]}")
                .path("fields")
                .get(0)
                .path("stored")
                .get(0);
        int fullLength = source.path("fullLength").asInt();

        assertThat(source.path("value").asString()).hasSize(2048);
        // Over the 64 KiB that the backend cuts at, so the length comes from the whole value.
        assertThat(fullLength).isGreaterThan(64 * 1024);

        StringBuilder whole = new StringBuilder();
        int offset = 0;
        for (int id = 2; ; id++) {
            JsonNode slice = client.structured(
                    id,
                    "get_field_value",
                    "{\"docId\":42,\"field\":\"_source\",\"offset\":" + offset + ",\"length\":50000}");
            assertThat(slice.path("offset").asInt()).isEqualTo(offset);
            assertThat(slice.path("totalLength").asInt()).isEqualTo(fullLength);
            whole.append(slice.path("value").asString());
            if (!slice.has("nextOffset")) {
                break;
            }
            offset = slice.path("nextOffset").asInt();
        }
        assertThat(whole).hasSize(fullLength).startsWith(source.path("value").asString());
        assertThat(whole.toString()).startsWith("{").endsWith("}");
    }

    @Test
    void slicesABinaryValueInWholeBytes() throws Exception {
        JsonNode slice = client.structured(1, "get_field_value", "{\"docId\":42,\"field\":\"blob\",\"length\":3}");

        assertThat(slice.path("binary").asBoolean()).isTrue();
        assertThat(slice.path("totalLength").asInt()).isEqualTo(200_000);
        assertThat(slice.path("value").asString()).hasSize(2);
        assertThat(slice.path("nextOffset").asInt()).isEqualTo(2);
        assertThat(client.error(2, "get_field_value", "{\"docId\":42,\"field\":\"blob\",\"offset\":1}"))
                .contains("even");
        assertThat(client.error(3, "get_field_value", "{\"docId\":42,\"field\":\"blob\",\"offset\":200001}"))
                .contains("past the end");
    }

    @Test
    void rebuildsTheFieldsThatArentStored() throws Exception {
        JsonNode result = client.structured(
                1, "reconstruct_fields", "{\"docId\":1,\"fields\":[\"keywords\",\"labels\",\"title\"]}");
        JsonNode fields = result.path("fields");

        assertThat(fields).hasSize(3);
        JsonNode keywords = fields.get(0);
        assertThat(keywords.path("field").asString()).isEqualTo("keywords");
        assertThat(keywords.path("source").asString()).isEqualTo("POSTINGS");
        assertThat(keywords.path("indexOptions").asString()).isEqualTo("DOCS");
        assertThat(keywords.path("termCount").asInt()).isPositive();
        assertThat(keywords.has("text")).isFalse();
        assertThat(keywords.path("terms")).isNotEmpty();
        assertThat(fields.get(1).path("indexOptions").asString()).isEqualTo("DOCS_AND_FREQS");
        assertThat(fields.get(1).path("terms").get(0).path("freq").asInt()).isPositive();
        JsonNode title = fields.get(2);
        assertThat(title.path("text").asString()).isNotBlank();
        // text stands for the terms.
        assertThat(title.has("terms")).isFalse();
        assertThat(title.path("termCount").asInt()).isPositive();
        String stored = client.structured(2, "get_document", "{\"docId\":1,\"fields\":[\"title\"]}")
                .path("fields")
                .get(0)
                .path("stored")
                .get(0)
                .path("value")
                .asString();
        // StandardAnalyzer lowercases the title.
        assertThat(title.path("text").asString()).isEqualTo(stored.toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void cutsTheTermsAtMaxTerms() throws Exception {
        JsonNode body = client.structured(
                        1,
                        "reconstruct_fields",
                        "{\"docId\":42,\"fields\":[\"body\"],\"maxTerms\":5,\"withTerms\":true}")
                .path("fields")
                .get(0);

        assertThat(body.path("source").asString()).isEqualTo("TERM_VECTOR");
        assertThat(body.path("terms")).hasSize(5);
        assertThat(body.path("termCount").asInt()).isGreaterThan(5);
        assertThat(body.path("cut").asBoolean()).isTrue();
        assertThat(client.error(2, "reconstruct_fields", "{\"docId\":42,\"maxTerms\":10001}"))
                .contains("maxTerms must be from 1 to 10000");
    }

    @Test
    void showsADeletedDocument() throws Exception {
        assertThat(client.structured(1, "get_document", "{\"docId\":37}")
                        .path("deleted")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void opensAnIndexAndKeepsTheOldOneIfThatFails() throws Exception {
        backend.call(Methods.CLOSE_INDEX, new CloseIndexParams()).get();

        assertThat(client.error(1, "index_info", "{}")).contains("open_index").endsWith("(NO_INDEX_OPEN)");
        assertThat(client.error(2, "get_document", "{\"docId\":0}")).endsWith("(NO_INDEX_OPEN)");

        JsonNode opened = client.structured(3, "open_index", "{\"path\":\"" + demo + "\",\"directory\":\"NIO\"}");
        assertThat(opened.path("path").asString()).isEqualTo(demo);
        assertThat(opened.path("numDocs").asInt()).isEqualTo(487);
        assertThat(opened.path("maxDoc").asInt()).isEqualTo(500);
        assertThat(opened.path("luceneRuntime").asString()).startsWith("10.");
        assertThat(opened.path("fields").get(0).path("name").asString()).isEqualTo("id");

        assertThat(client.error(4, "open_index", "{\"path\":\"" + demo + "/missing\"}"))
                .endsWith("(INDEX_NOT_FOUND)");
        assertThat(client.structured(5, "index_info", "{}").path("path").asString())
                .isEqualTo(demo);
    }

    @Test
    void opensOnlyBelowTheRootsAndRefusesTheRestAlike(@TempDir Path tmp) throws Exception {
        Path index = Path.of(demo).toRealPath();
        Path root = Files.createDirectory(tmp.resolve("root"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("escape"), outside);
        List<Path> roots = List.of(index.getParent(), root.toRealPath());
        String refusal =
                "open_index opens only paths below " + roots.get(0) + ", " + roots.get(1) + " (INVALID_PARAMS)";

        McpClient rooted = serve(new Session(DirectoryImpl.FS, Optional.empty(), roots), Tools.ALL);
        try {
            assertThat(rooted.structured(1, "open_index", "{\"path\":\"" + demo + "\"}")
                            .path("path")
                            .asString())
                    .isEqualTo(index.toString());
            // A path outside, one through a link out of a root, one that climbs out, and one that
            // doesn't exist: refused alike, so the refusal says nothing of what is there.
            for (Path path : List.of(
                    outside,
                    root.resolve("escape"),
                    index.getParent().resolve(".."),
                    root.resolve("missing"),
                    tmp.resolve("missing"))) {
                assertThat(rooted.error(2, "open_index", "{\"path\":\"" + path + "\"}"))
                        .isEqualTo(refusal);
            }
            assertThat(rooted.structured(3, "index_info", "{}").path("path").asString())
                    .isEqualTo(index.toString());
        } finally {
            rooted.close();
        }
    }

    @Test
    void servesThePinnedIndexWithoutOpenIndex() throws Exception {
        McpClient pinned = serve(new Session(DirectoryImpl.FS, Optional.empty(), List.of()), Tools.PINNED);
        try {
            JsonNode initialized = pinned.request(1, "initialize", initialize("2025-06-18"));
            JsonNode tools =
                    pinned.request(2, "tools/list", "{}").path("result").path("tools");
            JsonNode open = pinned.request(3, "tools/call", "{\"name\":\"open_index\",\"arguments\":{\"path\":\"/\"}}");

            assertThat(initialized.path("result").path("instructions").asString())
                    .isEqualTo(McpServer.PINNED_INSTRUCTIONS)
                    .doesNotContain("open_index");
            assertThat(StreamSupport.stream(tools.spliterator(), false)
                            .map(tool -> tool.path("name").asString())
                            .toList())
                    .containsExactly(
                            "index_info",
                            "search",
                            "analyze",
                            "terms",
                            "get_document",
                            "get_field_value",
                            "reconstruct_fields");
            assertThat(open.path("error").path("code").asInt()).isEqualTo(Messages.INVALID_PARAMS);
            assertThat(pinned.structured(4, "index_info", "{}").path("numDocs").asInt())
                    .isEqualTo(487);
        } finally {
            pinned.close();
        }
    }

    @Test
    void answersNothingToACancelledRequestAndCancelsItsJob() throws Exception {
        client.send(call(1, "blocking", "{\"_meta\":{\"progressToken\":\"p\"}}"));
        assertThat(blocking.polled.await(30, TimeUnit.SECONDS)).isTrue();
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}");
        // Answered on the main thread, after it has seen the cancellation.
        client.request(2, "ping", "{}");
        blocking.release.countDown();

        client.structured(3, "index_info", "{}");

        assertThat(client.received())
                .noneMatch(message -> message.path("id").isIntegralNumber()
                        && message.path("id").asInt() == 1);
        assertThat(client.received())
                .anyMatch(message -> message.path("method").asString("").equals("notifications/progress")
                        && message.path("params")
                                .path("progressToken")
                                .asString("")
                                .equals("p"));
        // index_info doesn't wait for it: the calls run side by side (ADR 0101).
        assertThat(blocking.cancelled.await(30, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void endsAtTheEndOfStdinAndCancelsTheJobThatRuns() throws Exception {
        client.send(call(1, "blocking", "{}"));
        assertThat(blocking.polled.await(30, TimeUnit.SECONDS)).isTrue();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            blocking.release.countDown();
        });

        assertThat(client.end()).isZero();
        assertThat(client.received()).isEmpty();
        assertThat(blocking.cancelled.await(30, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void answersACallWhileAnotherWaitsForItsJob() throws Exception {
        client.send(call(1, "blocking", "{}"));
        assertThat(blocking.polled.await(30, TimeUnit.SECONDS)).isTrue();

        JsonNode found = client.structured(
                2, "search", "{\"term\":{\"field\":\"id\",\"value\":\"doc-0001\"},\"fields\":[\"id\"]}");

        assertThat(found.path("totalHits").asLong()).isEqualTo(1);
        assertThat(client.received())
                .noneMatch(message -> message.path("id").isIntegralNumber()
                        && message.path("id").asInt() == 1);
    }

    @Test
    void opensAnIndexOnlyOnceTheOtherCallsHaveEnded() throws Exception {
        client.send(callWithArguments(1, "blocking", "{\"finish\":true}"));
        assertThat(blocking.polled.await(30, TimeUnit.SECONDS)).isTrue();
        client.send(callWithArguments(2, "open_index", "{\"path\":\"" + demo + "\"}"));
        // Time for open_index to replace the index, and so stop the job of 1, if it didn't wait.
        Thread.sleep(200);
        blocking.release.countDown();

        JsonNode first = client.answer(1);
        assertThat(client.received())
                .noneMatch(message -> message.path("id").isIntegralNumber()
                        && message.path("id").asInt() == 2);
        JsonNode second = client.answer(2);

        assertThat(first.path("result").path("isError").asBoolean())
                .as(first.toString())
                .isFalse();
        assertThat(second.path("result").path("isError").asBoolean())
                .as(second.toString())
                .isFalse();
    }

    /** A server of its own over the backend, with {@code session} and {@code tools}, and the demo index open. */
    private static McpClient serve(Session session, List<McpTool<?, ?>> tools) throws Exception {
        McpServer server = new McpServer(backend, version, session, tools);
        server.open(demo);
        return new McpClient(server);
    }

    private static String initialize(String protocolVersion) {
        return "{\"protocolVersion\":\"" + protocolVersion
                + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}";
    }

    private static String callWithArguments(int id, String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    private static String call(int id, String tool, String extra) {
        String meta = extra.equals("{}") ? "" : "," + extra.substring(1, extra.length() - 1);
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}" + meta + "}}";
    }

    @Test
    void listsTheTermsAPageAtATime() throws Exception {
        JsonNode first = client.structured(1, "terms", "{\"field\":\"id\",\"limit\":3}");
        JsonNode second = client.structured(
                2,
                "terms",
                "{\"field\":\"id\",\"limit\":3,\"from\":" + first.path("next").toString() + "}");

        assertThat(terms(first)).containsExactly("doc-0000", "doc-0001", "doc-0002");
        assertThat(first.path("terms").get(0).path("docFreq").asInt()).isEqualTo(1);
        assertThat(first.path("terms").get(0).has("totalTermFreq")).isFalse();
        assertThat(first.path("next").path("value").asString()).isEqualTo("doc-0003");
        assertThat(terms(second)).containsExactly("doc-0003", "doc-0004", "doc-0005");
    }

    @Test
    void listsTheTermsWithAPrefixAndEndsWithThem() throws Exception {
        JsonNode first = client.structured(1, "terms", "{\"field\":\"id\",\"prefix\":\"doc-04\",\"limit\":60}");
        JsonNode second = client.structured(
                2,
                "terms",
                "{\"field\":\"id\",\"prefix\":\"doc-04\",\"limit\":60,\"from\":"
                        + first.path("next").toString() + "}");
        JsonNode all = client.structured(3, "terms", "{\"field\":\"id\",\"prefix\":\"doc-04\",\"limit\":1000}");

        assertThat(terms(first)).hasSize(60).startsWith("doc-0400").endsWith("doc-0459");
        assertThat(terms(second)).hasSize(40).startsWith("doc-0460").endsWith("doc-0499");
        assertThat(second.has("next")).isFalse();
        assertThat(terms(all)).hasSize(100);
        assertThat(all.has("next")).isFalse();
    }

    @Test
    void listsTheTermsInTheMostDocuments() throws Exception {
        JsonNode result = client.structured(1, "terms", "{\"field\":\"category\",\"top\":5}");

        assertThat(result.path("terms")).hasSize(5);
        assertThat(result.path("uniqueTerms").asLong()).isEqualTo(6);
        assertThat(result.has("next")).isFalse();
        assertThat(result.path("terms").get(0).path("docFreq").asInt())
                .isGreaterThanOrEqualTo(
                        result.path("terms").get(4).path("docFreq").asInt());
    }

    @Test
    void flagsBinaryTerms() throws Exception {
        JsonNode result = client.structured(1, "terms", "{\"field\":\"binary_key\",\"limit\":2}");
        JsonNode prefixed = client.structured(2, "terms", "{\"field\":\"binary_key\",\"prefix\":\"a\"}");

        assertThat(result.path("terms").get(0).path("binary").asBoolean()).isTrue();
        assertThat(result.path("next").path("binary").asBoolean()).isTrue();
        assertThat(prefixed.path("terms")).isEmpty();
    }

    @Test
    void refusesTermsOfAnUnknownFieldAndBadArguments() throws Exception {
        assertThat(client.error(1, "terms", "{\"field\":\"nope\"}")).endsWith("(FIELD_NOT_FOUND)");
        assertThat(client.error(2, "terms", "{\"field\":\"id\",\"top\":3,\"prefix\":\"doc\"}"))
                .contains("top excludes")
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(3, "terms", "{\"field\":\"id\",\"limit\":1001}"))
                .contains("limit must be from 1 to 1000");
        assertThat(client.error(4, "terms", "{\"field\":\"id\",\"top\":0}")).contains("top must be from 1");
    }

    @Test
    void searchesARangeOfPoints() throws Exception {
        String quantity = "\"defaultField\":\"title\",\"exactTotalHits\":true,\"limit\":1";
        JsonNode points = client.structured(1, "search", "{\"query\":\"quantity:[-5 TO 5]\"," + quantity + "}");
        JsonNode values = client.structured(
                2,
                "search",
                "{\"docValues\":{\"field\":\"quantity\",\"range\":{\"lower\":\"-5\",\"upper\":\"5\"}}," + quantity
                        + "}");
        long from = 1768003200000L;
        long to = 1768953599999L;
        JsonNode timestamps = client.structured(
                3, "search", "{\"query\":\"timestamp:[" + from + " TO " + to + "]\"," + quantity + "}");
        JsonNode timestampValues = client.structured(
                4,
                "search",
                "{\"docValues\":{\"field\":\"timestamp\",\"range\":{\"lower\":\"" + from + "\",\"upper\":\"" + to
                        + "\"}}," + quantity + "}");

        assertThat(points.path("totalHits").asLong())
                .isEqualTo(9)
                .isEqualTo(values.path("totalHits").asLong());
        assertThat(timestamps.path("totalHits").asLong())
                .isPositive()
                .isEqualTo(timestampValues.path("totalHits").asLong());
    }

    @Test
    void searchesADoublePointOnceItsTypeIsGiven() throws Exception {
        String query = "\"query\":\"title:lamp AND price:[10 TO 20]\",\"defaultField\":\"title\"";

        JsonNode guessed = client.structured(1, "search", "{" + query + "}");
        JsonNode typed = client.structured(
                2, "search", "{" + query + ",\"pointTypes\":[{\"field\":\"price\",\"type\":\"DOUBLE\"}]}");

        assertThat(guessed.path("totalHits").asLong()).isZero();
        assertThat(docIds(typed)).containsExactly(196);
        assertThat(client.error(3, "search", "{" + query + ",\"pointTypes\":[{\"field\":\"price\",\"type\":\"INT\"}]}"))
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(4, "search", "{\"term\":{\"field\":\"id\",\"value\":\"doc-0042\"},\"pointTypes\":[]}"))
                .contains("pointTypes goes with query")
                .endsWith("(INVALID_PARAMS)");
    }

    @Test
    void sortsByADoubleField() throws Exception {
        JsonNode result = client.structured(
                1,
                "search",
                "{\"query\":\"title:lamp\",\"defaultField\":\"title\",\"fields\":[],"
                        + "\"sort\":[{\"field\":\"price\",\"type\":\"DOUBLE\",\"reverse\":true}]}");

        List<Double> prices = sortNumbers(result, 0);
        assertThat(docIds(result)).first().isEqualTo(51);
        assertThat(prices).first().isEqualTo(495.49);
        assertThat(prices).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void guessesAStringSortOfSortedDocValues() throws Exception {
        JsonNode result = client.structured(
                1,
                "search",
                "{\"query\":\"*:*\",\"defaultField\":\"title\",\"fields\":[],\"limit\":100,"
                        + "\"sort\":[{\"field\":\"category\"}]}");

        List<String> categories = StreamSupport.stream(result.path("hits").spliterator(), false)
                .map(hit -> hit.path("sortValues").get(0).path("value").asString())
                .toList();
        assertThat(categories).isNotEmpty().isSorted();
    }

    @Test
    void sortsByTwoKeys() throws Exception {
        JsonNode result = client.structured(
                1,
                "search",
                "{\"query\":\"*:*\",\"defaultField\":\"title\",\"fields\":[],\"sort\":["
                        + "{\"field\":\"scores\",\"selector\":\"MAX\",\"reverse\":true},{\"field\":\"_doc\"}]}");

        assertThat(docIds(result)).first().isEqualTo(64);
        result.path("hits").forEach(hit -> assertThat(hit.path("sortValues")).hasSize(2));
        assertThat(result.path("hits")
                        .get(0)
                        .path("sortValues")
                        .get(0)
                        .path("value")
                        .asString())
                .isEqualTo("100");
    }

    @Test
    void putsDocumentsWithoutAValueLast() throws Exception {
        JsonNode result = client.structured(
                1,
                "search",
                "{\"query\":\"*:*\",\"defaultField\":\"title\",\"fields\":[],\"offset\":350,\"limit\":100,"
                        + "\"sort\":[{\"field\":\"tags\"}]}");

        List<Boolean> missing = StreamSupport.stream(result.path("hits").spliterator(), false)
                .map(hit -> hit.path("sortValues").get(0).has("value"))
                .toList();
        assertThat(missing).contains(true).contains(false);
        assertThat(missing.indexOf(false)).isGreaterThan(missing.lastIndexOf(true));
        result.path("hits").forEach(hit -> {
            if (!hit.path("sortValues").get(0).has("value")) {
                assertThat(hit.path("sortValues").get(0)).isEmpty();
            }
        });
    }

    @Test
    void sortsByTheScoreLowestFirst() throws Exception {
        JsonNode result = client.structured(
                1,
                "search",
                "{\"query\":\"title:lamp\",\"defaultField\":\"title\",\"fields\":[],"
                        + "\"sort\":[{\"field\":\"_score\",\"reverse\":true}]}");

        List<Double> scores = StreamSupport.stream(result.path("hits").spliterator(), false)
                .map(hit -> hit.path("score").asDouble())
                .toList();
        assertThat(scores).isSorted();
        assertThat(scores.get(0)).isLessThan(scores.get(scores.size() - 1));
    }

    @Test
    void hasNoSortValuesWithoutSort() throws Exception {
        JsonNode result =
                client.structured(1, "search", "{\"query\":\"title:lamp\",\"defaultField\":\"title\",\"fields\":[]}");

        result.path("hits").forEach(hit -> assertThat(hit.has("sortValues")).isFalse());
    }

    @Test
    void refusesASortThatDoesNotFit() throws Exception {
        String query = "\"query\":\"*:*\",\"defaultField\":\"title\"";

        assertThat(client.error(1, "search", "{" + query + ",\"sort\":[{\"field\":\"checksum\"}]}"))
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(2, "search", "{" + query + ",\"sort\":[{\"field\":\"nothing\"}]}"))
                .endsWith("(FIELD_NOT_FOUND)");
        assertThat(client.error(3, "search", "{" + query + ",\"sort\":[{\"field\":\"_doc\",\"selector\":\"MAX\"}]}"))
                .contains("don't go with _doc")
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(
                        4, "search", "{" + query + ",\"sort\":[{\"field\":\"scores\",\"selector\":\"MIDDLE_MIN\"}]}"))
                .endsWith("(INVALID_PARAMS)");
    }

    private static List<Double> sortNumbers(JsonNode result, int key) {
        return StreamSupport.stream(result.path("hits").spliterator(), false)
                .map(hit -> hit.path("sortValues").get(key).path("value").asDouble())
                .toList();
    }

    @Test
    void searchesTheDocValuesOfAField() throws Exception {
        String rest = ",\"exactTotalHits\":true,\"limit\":1}";

        JsonNode scores = client.structured(
                1, "search", "{\"docValues\":{\"field\":\"scores\",\"range\":{\"lower\":\"90\"}}" + rest);
        JsonNode kitchen = client.structured(
                2, "search", "{\"docValues\":{\"field\":\"category\",\"values\":[\"kitchen\"]}" + rest);
        JsonNode prefix =
                client.structured(3, "search", "{\"docValues\":{\"field\":\"category\",\"prefix\":\"o\"}" + rest);
        JsonNode weight = client.structured(
                4,
                "search",
                "{\"docValues\":{\"field\":\"weight\",\"number\":\"SORTABLE_DOUBLE\",\"range\":{\"lower\":\"0\"}}"
                        + rest);

        assertThat(scores.path("totalHits").asLong()).isPositive();
        assertThat(kitchen.path("totalHits").asLong()).isEqualTo(80);
        assertThat(prefix.path("totalHits").asLong()).isEqualTo(158);
        assertThat(weight.path("totalHits").asLong()).isPositive();
    }

    @Test
    void refusesMoreThanOneKindOfQuery() throws Exception {
        assertThat(client.error(
                        1,
                        "search",
                        "{\"query\":\"lamp\",\"defaultField\":\"title\",\"docValues\":{\"field\":\"category\","
                                + "\"prefix\":\"o\"}}"))
                .contains("exactly one of query, term and docValues")
                .endsWith("(INVALID_PARAMS)");
        assertThat(client.error(
                        2,
                        "search",
                        "{\"docValues\":{\"field\":\"category\",\"prefix\":\"o\"},\"analyzer\":\"KeywordAnalyzer\"}"))
                .contains("go with query only")
                .endsWith("(INVALID_PARAMS)");
    }

    private static List<String> terms(JsonNode result) {
        return StreamSupport.stream(result.path("terms").spliterator(), false)
                .map(term -> term.path("term").asString())
                .toList();
    }

    private static List<Integer> docIds(JsonNode result) {
        return StreamSupport.stream(result.path("hits").spliterator(), false)
                .map(hit -> hit.path("docId").asInt())
                .toList();
    }

    /**
     * A tool over a search job whose first poll waits for {@link #release}, and which is never done:
     * it ends only by cancellation. With {@code finish}, it waits for {@link #release} between
     * starting the job and polling it once, and is done then; the poll fails if the job was stopped.
     */
    static final class BlockingTool implements McpTool<BlockingTool.Arguments, BlockingTool.Result> {

        record Arguments(Optional<Boolean> finish) {}

        record Result() {}

        final CountDownLatch polled = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public String name() {
            return "blocking";
        }

        @Override
        public String description() {
            return "Waits";
        }

        @Override
        public Class<Arguments> arguments() {
            return Arguments.class;
        }

        @Override
        public Class<Result> result() {
            return Result.class;
        }

        @Override
        public Result call(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
            StartSearchParams params = new StartSearchParams(
                    new SearchQuery(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(new TermQuery("id", new BytesValue("doc-0001", false))),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()),
                    new Similarity(Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.empty(), Optional.empty()),
                    List.of(),
                    0,
                    1,
                    false,
                    Optional.of(List.of()),
                    Optional.empty());
            if (arguments.finish().orElse(false)) {
                long jobId = call.call(Methods.START_SEARCH, params).jobId();
                polled.countDown();
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                call.call(Methods.GET_SEARCH, new JobParams(jobId));
                return new Result();
            }
            try {
                return call.job(
                        Methods.START_SEARCH,
                        params,
                        Methods.GET_SEARCH,
                        _ -> {
                            polled.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException _) {
                                Thread.currentThread().interrupt();
                            }
                            return Optional.<Result>empty();
                        },
                        poll -> new Jobs.Progress(poll.docsSearched(), OptionalLong.empty()));
            } catch (Jobs.Cancelled e) {
                cancelled.countDown();
                throw e;
            }
        }
    }
}
