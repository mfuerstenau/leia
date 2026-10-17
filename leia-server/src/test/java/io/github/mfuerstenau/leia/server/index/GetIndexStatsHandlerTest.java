package io.github.mfuerstenau.leia.server.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReaderSegment;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GetIndexStatsHandlerTest {

    @TempDir
    Path tmp;

    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs(), new Filters());
    private final GetIndexStatsHandler handler = new GetIndexStatsHandler(currentIndex);

    @Test
    void reportsTheOpenIndex() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));
        new OpenIndexHandler(currentIndex, new Runtimes()).handle(new OpenIndexParams(dir.toString()));

        GetIndexStatsResult result = handler.handle(new GetIndexStatsParams());

        assertThat(result)
                .isEqualTo(new GetIndexStatsResult(
                        dir.toString(),
                        "MMapDirectory",
                        1,
                        2,
                        2,
                        List.of(new IndexCommitStats(
                                dir.toString(),
                                0,
                                "segments_1",
                                1,
                                ((DirectoryReader) currentIndex.readable().reader()).getVersion(),
                                1,
                                2,
                                2,
                                Version.LATEST.major,
                                Version.LATEST.toString(),
                                Optional.of(Version.LATEST.toString()),
                                Map.of("source", "test"),
                                List.of(new ReaderSegment("_0", 0, 2, 2)))),
                        List.of(
                                new IndexField(
                                        "id",
                                        0,
                                        IndexOptions.DOCS,
                                        false,
                                        false,
                                        false,
                                        DocValuesType.NONE,
                                        0,
                                        0,
                                        0,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(new TermStats(2, 2, 2))),
                                new IndexField(
                                        "body",
                                        1,
                                        IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                                        true,
                                        false,
                                        false,
                                        DocValuesType.NONE,
                                        0,
                                        0,
                                        0,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(new TermStats(2, 4, 4))),
                                new IndexField(
                                        "rank",
                                        2,
                                        IndexOptions.NONE,
                                        false,
                                        false,
                                        false,
                                        DocValuesType.NUMERIC,
                                        0,
                                        0,
                                        0,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))));
    }

    @Test
    void failsWithoutOpenIndex() {
        assertThatThrownBy(() -> handler.handle(new GetIndexStatsParams()))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_INDEX_OPEN))
                .hasMessage("No index is open");
    }

    @ParameterizedTest
    @EnumSource(org.apache.lucene.index.IndexOptions.class)
    void mapsEveryIndexOptionsByName(org.apache.lucene.index.IndexOptions options) {
        assertThat(GetIndexStatsHandler.indexOptions(options).name()).isEqualTo(options.name());
    }

    @ParameterizedTest
    @EnumSource(org.apache.lucene.index.DocValuesType.class)
    void mapsEveryDocValuesTypeByName(org.apache.lucene.index.DocValuesType type) {
        assertThat(GetIndexStatsHandler.docValuesType(type).name()).isEqualTo(type.name());
    }

    /** Two documents in one segment: "id" (a keyword), "body" (2 terms each), "rank" (doc values). */
    private static Path writeIndex(Path dir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 1; i <= 2; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                doc.add(new TextField("body", "document " + i, Field.Store.NO));
                doc.add(new NumericDocValuesField("rank", i));
                writer.addDocument(doc);
            }
            writer.setLiveCommitData(Map.of("source", "test").entrySet());
            writer.commit();
        }
        return dir;
    }
}
