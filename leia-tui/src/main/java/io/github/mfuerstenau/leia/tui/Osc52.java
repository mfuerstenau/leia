package io.github.mfuerstenau.leia.tui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The OSC 52 escape sequence that puts text on the terminal's clipboard, as the Rust TUI wrote it. */
public final class Osc52 {

    /** From this many bytes on, copying warns that the terminal may drop the copy: tmux takes about 1 MiB. */
    public static final int LARGE_COPY = 100 * 1024;

    private Osc52() {}

    public static String of(String text) {
        return "\033]52;c;" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\007";
    }
}
