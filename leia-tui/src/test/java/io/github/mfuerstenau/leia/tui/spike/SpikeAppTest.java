package io.github.mfuerstenau.leia.tui.spike;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Drives the spike through TamboUI's Pilot, which the port's tests will rely on (ADR 0034). */
class SpikeAppTest {

    private static final KeyModifiers ALT_GR = KeyModifiers.of(true, true, false);

    @Test
    void typesAltGrCharactersAndLogsShortcuts() throws Exception {
        try (SpikeApp app = new SpikeApp("test", null, Instant.now(), Instant.now());
                TuiTestRunner test = TuiTestRunner.runTest(app::handle, app::render, new Size(100, 30))) {
            Pilot pilot = test.pilot();
            pilot.press('a');
            // On Windows, crossterm reports AltGr as Ctrl+Alt; both must type the character.
            pilot.press('@', ALT_GR);
            pilot.press('漢');
            pilot.press('d', KeyModifiers.CTRL);
            pilot.pause();

            assertThat(app.inputText()).isEqualTo("a@漢");
            assertThat(app.keyLog()).hasSize(4);
            assertThat(app.keyLog().get(3)).contains("CHAR").contains("ctrl");
        }
    }

    @Test
    void scrollsTheTableAndSwitchesTabs() throws Exception {
        try (SpikeApp app = new SpikeApp("test", null, Instant.now(), Instant.now());
                TuiTestRunner test = TuiTestRunner.runTest(app::handle, app::render, new Size(100, 30))) {
            Pilot pilot = test.pilot();
            pilot.press('t', KeyModifiers.CTRL);
            pilot.press(KeyCode.END);
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();

            assertThat(app.tab()).isEqualTo(SpikeApp.Tab.TABLE);
            assertThat(app.selectedRow()).isLessThan(SpikeApp.TABLE_ROWS - 1).isGreaterThan(SpikeApp.TABLE_ROWS - 40);
        }
    }

    @Test
    void sendsOsc52AndGetsTheBackendResultAsAnEvent() throws Exception {
        try (SpikeApp app = new SpikeApp("test", null, Instant.now(), Instant.now());
                TuiTestRunner test = TuiTestRunner.runTest(app::handle, app::render, new Size(100, 30))) {
            Pilot pilot = test.pilot();
            // On the keys tab, 4 is typed into the input; Ctrl+T leaves it.
            pilot.press('t', KeyModifiers.CTRL);
            pilot.press('4');
            pilot.press('y');
            pilot.press('b');
            pilot.pause(Duration.ofMillis(600));

            assertThat(app.tab()).isEqualTo(SpikeApp.Tab.MISC);
            assertThat(test.backend().rawOutput()).contains(Osc52.of(SpikeApp.CLIPBOARD_TEXT));
            assertThat(app.backendResult()).isEqualTo("call 1 done on leia-backend");
        }
    }
}
