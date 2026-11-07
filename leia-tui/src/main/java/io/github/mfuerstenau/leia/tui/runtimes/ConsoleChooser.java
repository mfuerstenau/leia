package io.github.mfuerstenau.leia.tui.runtimes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Asks on the terminal which mirror to retry a failed download from, for {@code --download-runtimes}
 * (ADR 0124): the mirrors numbered, those the jar failed from marked, and a number or {@code a}. Only
 * a number of the list retries; {@code a} and the end of the input stop the install, and anything else
 * asks again.
 */
public final class ConsoleChooser implements MirrorChooser {

    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleChooser(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public Choice choose(Failure failure, List<Mirror> mirrors, Set<URI> tried) {
        out.println();
        out.println("Retry " + failure.folder() + "/" + failure.jar() + " from which mirror?");
        for (int i = 0; i < mirrors.size(); i++) {
            Mirror mirror = mirrors.get(i);
            out.println("  " + (i + 1) + ". " + mirror.name() + "  " + mirror.uri()
                    + (tried.contains(mirror.uri()) ? "  (failed)" : ""));
        }
        while (true) {
            out.print("Mirror [1-" + mirrors.size() + "], or a to abort: ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException _) {
                line = null;
            }
            if (line == null) {
                out.println();
                return new Choice.Abort();
            }
            String answer = line.strip().toLowerCase(Locale.ROOT);
            if (answer.equals("a") || answer.equals("abort")) {
                return new Choice.Abort();
            }
            try {
                int n = Integer.parseInt(answer);
                if (n >= 1 && n <= mirrors.size()) {
                    return new Choice.Retry(mirrors.get(n - 1));
                }
            } catch (NumberFormatException _) {
                // Asks again.
            }
        }
    }
}
