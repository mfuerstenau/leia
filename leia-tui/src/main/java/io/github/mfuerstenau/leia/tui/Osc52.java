package io.github.mfuerstenau.leia.tui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The OSC 52 escape sequence that puts text on the terminal's clipboard, as the Rust TUI wrote it. */
public final class Osc52 {

    private Osc52() {}

    public static String of(String text) {
        return "\033]52;c;" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\007";
    }
}
