package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.StoredField;
import io.github.mfuerstenau.leia.protocol.document.StoredFields;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.ServerRuntimeInfo;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What the tests of a fake server check (ADR 0090), the same for each: the build's {@code
 * leia-test-servers} writes each, with an index that needs its postings format, {@code
 * LeiaServerPostings}, and a plain one. Each has a name: {@code 9} is an OpenSearch on Lucene 9.10,
 * {@code 10} an Elasticsearch on 10.2, whose postings format needs the setup of Elasticsearch's
 * bootstrap, and {@code 90} an Elasticsearch on 9.0 (ADR 0091).
 */
final class ServerBackends {

    private ServerBackends() {}

    /** How leia names the fake server {@code name}. */
    static String label(String name) {
        return (name.equals("9") ? "OpenSearch" : "Elasticsearch") + " 0.0.0-leiatest";
    }

    /** The Lucene major of the fake server {@code name}. */
    static int major(String name) {
        return name.equals("10") ? 10 : 9;
    }

    /** The fake server {@code name}: its home, with {@code lib/}. */
    static Path server(String name) {
        return testFile("leia.test.server" + name);
    }

    /** The index of the fake server {@code name} that needs its postings format. */
    static String index(String name) {
        return testFile("leia.test.server" + name + ".index").toString();
    }

    /** The index of the fake server {@code name} with Lucene's codec only. */
    static String plain(String name) {
        return testFile("leia.test.server" + name + ".plain").toString();
    }

    /** Without the server, its index lacks a postings format; so this comes before it is loaded. */
    static void missesTheServersFormat(String name) throws Exception {
        try (RealBackend backend = RealBackend.start()) {
            RpcException missing =
                    backend.fails(Methods.OPEN_INDEX, new OpenIndexParams(index(name)), ErrorCode.MISSING_CODEC);
            assertThat(missing.getMessage()).contains("LeiaServerPostings");
        }
    }

    /** The server {@code name}, whose Lucene is {@code lucene}, reads its index and its plain one. */
    static RealBackend readsWithTheServer(String name, String lucene) throws Exception {
        RealBackend backend =
                RealBackend.start(Optional.of(testFile("leia.test.runtimes")), List.of(), List.of(server(name)));

        ServerRuntimeInfo server = backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams())
                .servers()
                .getFirst();
        assertThat(server.home()).isEqualTo(server(name).toString());
        assertThat(server.label()).hasValue(label(name));
        assertThat(server.luceneVersion()).isEqualTo(lucene);
        assertThat(server.indexMajors()).containsExactly(major(name) - 1, major(name));

        OpenIndexResult opened = backend.call(Methods.OPEN_INDEX, new OpenIndexParams(index(name)));
        assertThat(opened.luceneRuntime()).isEqualTo(lucene);
        assertThat(opened.server()).hasValue(label(name));
        assertThat(opened.numDocs()).hasValue(3);
        assertThat(backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(1)).fields())
                .filteredOn(field -> field.name().equals("title"))
                .flatExtracting(DocumentField::stored)
                .extracting(value -> value.value())
                .containsExactly("server codecs");
        assertThat(backend.call(Methods.GET_TERMS, new GetTermsParams("title", Optional.empty(), 10))
                        .terms())
                .extracting(TermEntry::term)
                .contains("server", "shard", "codecs");
        assertThat(check(backend).indexes())
                .singleElement()
                .satisfies(index -> assertThat(index.clean()).isTrue());
        // The stored fields through the server's Lucene, the second time from the cache (ADR 0113).
        StoredFields stored = backend.storedFields(false);
        assertThat(stored.fields()).extracting(StoredField::name).contains("title");
        assertThat(stored.cachedSegments()).isZero();
        assertThat(backend.storedFields(false).cachedSegments()).isEqualTo(stored.segments());

        assertThat(backend.call(Methods.OPEN_INDEX, new OpenIndexParams(plain(name))))
                .satisfies(plain -> {
                    assertThat(plain.luceneRuntime()).isEqualTo(lucene);
                    assertThat(plain.server()).hasValue(label(name));
                });
        backend.call(Methods.CLOSE_INDEX, new CloseIndexParams());
        return backend;
    }

    /** Checks the open index. */
    static CheckIndexResult check(RealBackend backend) throws Exception {
        long check = backend.call(Methods.START_CHECK_INDEX, new StartCheckIndexParams())
                .jobId();
        Optional<CheckIndexResult> checked = Optional.empty();
        for (int poll = 0; poll < 3000 && checked.isEmpty(); poll++) {
            Thread.sleep(10);
            checked = backend.call(Methods.GET_CHECK_INDEX, new GetCheckIndexParams(check, 0))
                    .result();
        }
        return checked.orElseThrow();
    }
}
