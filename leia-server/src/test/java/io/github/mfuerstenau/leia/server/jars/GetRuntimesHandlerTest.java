package io.github.mfuerstenau.leia.server.jars;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** How {@code getRuntimes} and {@code loadRuntimes} say a runtime's state (ADR 0073, ADR 0080). */
class GetRuntimesHandlerTest {

    private static final RuntimeFolders.Known LUCENE10 = RuntimeFolders.LATEST;

    private static RuntimeFolder folder(@Nullable String version, RuntimeFolders.State state) {
        return GetRuntimesHandler.folder(new Runtimes.KnownState(LUCENE10, version, state));
    }

    @Test
    void mapsEachStateOfAFolder() {
        assertThat(folder("10.5.2", new RuntimeFolders.State.Loaded()))
                .isEqualTo(new RuntimeFolder(
                        "lucene10", List.of(9, 10), RuntimeState.LOADED, Optional.of("10.5.2"), Optional.empty()));
        assertThat(folder(null, new RuntimeFolders.State.Missing()).state()).isEqualTo(RuntimeState.MISSING);
        assertThat(folder(null, new RuntimeFolders.State.Incomplete("lucene-core-10.5.2.jar")))
                .satisfies(folder -> {
                    assertThat(folder.state()).isEqualTo(RuntimeState.INCOMPLETE);
                    assertThat(folder.problem()).hasValue("lucene-core-10.5.2.jar");
                });
        assertThat(folder(null, new RuntimeFolders.State.Refused("built for 99")))
                .satisfies(folder -> {
                    assertThat(folder.state()).isEqualTo(RuntimeState.REFUSED);
                    assertThat(folder.problem()).hasValue("built for 99");
                });
        assertThat(folder(null, new RuntimeFolders.State.Restart()))
                .isEqualTo(new RuntimeFolder(
                        "lucene10", List.of(9, 10), RuntimeState.RESTART, Optional.empty(), Optional.empty()));
    }

    @Test
    void aRuntimeFromTheJarsIsLoadedWhateverItsFolderSays() {
        assertThat(folder("10.5.2", new RuntimeFolders.State.Restart()).state()).isEqualTo(RuntimeState.LOADED);
    }
}
