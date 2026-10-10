package io.github.mfuerstenau.leia.server.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloseIndexHandlerTest {

    @TempDir
    Path tmp;

    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs(), new Filters());
    private final CloseIndexHandler handler = new CloseIndexHandler(currentIndex);

    @Test
    void closesTheOpenIndex() throws Exception {
        Path dir = writeEmptyIndex(tmp.resolve("index"));
        new OpenIndexHandler(currentIndex).handle(new OpenIndexParams(dir.toString()));
        ReadOnlyIndex open = currentIndex.readable();

        CloseIndexResult result = handler.handle(new CloseIndexParams());

        assertThat(result).isEqualTo(new CloseIndexResult(Optional.of(dir.toString())));
        assertThat(currentIndex.get()).isEmpty();
        assertThat(open.reader().tryIncRef()).as("reader is closed").isFalse();
    }

    @Test
    void closingWithoutAnOpenIndexIsNoError() {
        assertThat(handler.handle(new CloseIndexParams())).isEqualTo(new CloseIndexResult(Optional.empty()));
        assertThat(currentIndex.get()).isEmpty();
    }

    @Test
    void closingTwiceReportsTheIndexOnce() throws Exception {
        Path dir = writeEmptyIndex(tmp.resolve("index"));
        new OpenIndexHandler(currentIndex).handle(new OpenIndexParams(dir.toString()));

        handler.handle(new CloseIndexParams());

        assertThat(handler.handle(new CloseIndexParams()).path()).isEmpty();
    }

    private static Path writeEmptyIndex(Path dir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.commit();
        }
        return dir;
    }
}
