package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.TuiConfig;
import io.github.mfuerstenau.leia.client.log.LeiaLog;
import io.github.mfuerstenau.leia.client.log.LogBuffer;
import io.github.mfuerstenau.leia.client.platform.DefaultJars;
import io.github.mfuerstenau.leia.client.platform.Platform;
import io.github.mfuerstenau.leia.client.platform.RuntimesDirs;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** {@link Tui#run} on a test's terminal, as leia-app starts it. */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TuiTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private LeiaBackend backend;
    private Platform platform;

    @BeforeEach
    void start() throws Exception {
        // The old runtimes' folders that the build puts together (ADR 0073); Lucene 10 is on the classpath.
        backend = LeiaBackend.start(Optional.of(Path.of(System.getProperty("leia.test.runtimes"))), List.of());
        platform = new Platform(
                Map.of(
                        "XDG_STATE_HOME", tmp.resolve("state").toString(),
                        "XDG_CONFIG_HOME", tmp.resolve("config").toString(),
                        "XDG_CONFIG_DIRS", tmp.resolve("etc").toString(),
                        "XDG_DATA_HOME", tmp.resolve("data").toString(),
                        "XDG_DATA_DIRS", tmp.resolve("share").toString(),
                        "XDG_CACHE_HOME", tmp.resolve("cache").toString())::get,
                Optional.empty(),
                false,
                Optional.empty());
    }

    @AfterEach
    void stop() {
        backend.close();
        Palette.use(Themes.bundled().dark());
    }

    @Test
    void exitsAfterTheFirstFrameWithTheStartupTimes() throws Exception {
        int status = Tui.run(start(Optional.of(index().toString())), print(out), print(err), true, terminal());

        assertThat(status).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).matches("terminal ready \\d+ ms, first frame \\d+ ms\\R");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void runsUntilTheUserQuitsAndOpensTheIndexOnceTheFirstFrameIsShown() throws Exception {
        PrintStream stdout = System.out;
        QuittingBackend terminal = new QuittingBackend();

        int status = Tui.run(
                start(Optional.of(index().toString())),
                print(out),
                print(err),
                false,
                TuiConfig.builder().backend(terminal));

        assertThat(status).isZero();
        assertThat(terminal.quitTyped).isTrue();
        assertThat(System.out).as("the streams are back").isSameAs(stdout);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void runsWithoutAnIndexToOpen() throws Exception {
        QuittingBackend terminal = new QuittingBackend();

        int status = Tui.run(
                start(Optional.empty()),
                print(out),
                print(err),
                false,
                TuiConfig.builder().backend(terminal));

        assertThat(status).isZero();
        assertThat(terminal.quitTyped).isTrue();
    }

    @Test
    void choosesPanamaThenJLineUnlessABackendIsSet() {
        String before = System.getProperty("tamboui.backend");
        try {
            System.setProperty("tamboui.backend", "jline3");
            Tui.selectBackend();
            assertThat(System.getProperty("tamboui.backend")).isEqualTo("jline3");

            if (System.getenv("TAMBOUI_BACKEND") == null) {
                System.setProperty("tamboui.backend", "");
                Tui.selectBackend();
                assertThat(System.getProperty("tamboui.backend")).isEqualTo("panama,jline3");

                System.clearProperty("tamboui.backend");
                Tui.selectBackend();
                assertThat(System.getProperty("tamboui.backend")).isEqualTo("panama,jline3");
            }
        } finally {
            if (before == null) {
                System.clearProperty("tamboui.backend");
            } else {
                System.setProperty("tamboui.backend", before);
            }
        }
    }

    private Tui.Start start(Optional<String> indexPath) {
        return new Tui.Start(
                platform,
                indexPath,
                OpenMode.READER,
                DirectoryImpl.FS,
                RuntimesDirs.none(),
                DefaultJars.none(),
                Tui.themes(platform),
                Optional.empty(),
                backend,
                new LeiaLog.Opened(print(OutputStream.nullOutputStream()), LogBuffer.detached()),
                Optional.empty(),
                Instant.now());
    }

    /** A small index of one document. */
    private Path index() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("index"));
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document document = new Document();
            document.add(new StringField("id", "1", Field.Store.YES));
            writer.addDocument(document);
        }
        return dir;
    }

    private static TuiConfig.Builder terminal() {
        return TuiConfig.builder().backend(new TestBackend(100, 30));
    }

    private static PrintStream print(OutputStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    /** A terminal on which the user presses Ctrl+C once the first frame is on it. */
    private static final class QuittingBackend extends TestBackend {

        private static final int CTRL_C = 3;

        private final BlockingQueue<Integer> keys = new LinkedBlockingQueue<>();
        private final AtomicBoolean flushed = new AtomicBoolean();
        private volatile boolean quitTyped;

        QuittingBackend() {
            super(100, 30);
        }

        @Override
        public void flush() throws IOException {
            super.flush();
            if (flushed.compareAndSet(false, true)) {
                keys.add(CTRL_C);
            }
        }

        @Override
        public int read(int timeoutMs) throws IOException {
            try {
                Integer key = keys.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (key == null) {
                    return -2;
                }
                quitTyped = true;
                return key;
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }
}
