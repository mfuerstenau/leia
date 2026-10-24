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
 * What the tests of a fake server check (ADR 0090), the same for Lucene 9 and 10: the build's
 * {@code leia-test-servers} writes each, with an index that needs its postings format, {@code
 * LeiaServerPostings}, and a plain one. The one for 9 is an OpenSearch, the one for 10 an
 * Elasticsearch, whose postings format needs the setup of Elasticsearch's bootstrap.
 */
final class ServerBackends {

    private ServerBackends() {}

    /** How leia names the fake server for Lucene {@code major}. */
    static String label(int major) {
        return (major == 9 ? "OpenSearch" : "Elasticsearch") + " 0.0.0-leiatest";
    }

    /** The fake server for Lucene {@code major}: its home, with {@code lib/}. */
    static Path server(int major) {
        return testFile("leia.test.server" + major);
    }

    /** The index of the fake server for {@code major} that needs its postings format. */
    static String index(int major) {
        return testFile("leia.test.server" + major + ".index").toString();
    }

    /** The index of the fake server for {@code major} with Lucene's codec only. */
    static String plain(int major) {
        return testFile("leia.test.server" + major + ".plain").toString();
    }

    /** Without the server, its index lacks a postings format; so this comes before it is loaded. */
    static void missesTheServersFormat(int major) throws Exception {
        try (RealBackend backend = RealBackend.start()) {
            RpcException missing =
                    backend.fails(Methods.OPEN_INDEX, new OpenIndexParams(index(major)), ErrorCode.MISSING_CODEC);
            assertThat(missing.getMessage()).contains("LeiaServerPostings");
        }
    }

    /** The server for {@code major}, whose Lucene is {@code lucene}, reads its index and its plain one. */
    static RealBackend readsWithTheServer(int major, String lucene) throws Exception {
        RealBackend backend =
                RealBackend.start(Optional.of(testFile("leia.test.runtimes")), List.of(), List.of(server(major)));

        ServerRuntimeInfo server = backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams())
                .servers()
                .getFirst();
        assertThat(server.home()).isEqualTo(server(major).toString());
        assertThat(server.label()).hasValue(label(major));
        assertThat(server.luceneVersion()).isEqualTo(lucene);
        assertThat(server.indexMajors()).containsExactly(major - 1, major);

        OpenIndexResult opened = backend.call(Methods.OPEN_INDEX, new OpenIndexParams(index(major)));
        assertThat(opened.luceneRuntime()).isEqualTo(lucene);
        assertThat(opened.server()).hasValue(label(major));
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

        assertThat(backend.call(Methods.OPEN_INDEX, new OpenIndexParams(plain(major))))
                .satisfies(plain -> {
                    assertThat(plain.luceneRuntime()).isEqualTo(lucene);
                    assertThat(plain.server()).hasValue(label(major));
                });
        backend.call(Methods.CLOSE_INDEX, new CloseIndexParams());
        return backend;
    }

    private static CheckIndexResult check(RealBackend backend) throws Exception {
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
