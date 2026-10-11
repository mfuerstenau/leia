package io.github.mfuerstenau.leia.tui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The OSC 52 escape sequence that puts text on the terminal's clipboard, as {@code tui/src/clipboard.rs}. */
final class Osc52 {

    private Osc52() {}

    static String of(String text) {
        return "\033]52;c;" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\007";
    }
}
