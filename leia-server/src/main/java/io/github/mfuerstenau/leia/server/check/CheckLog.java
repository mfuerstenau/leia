package io.github.mfuerstenau.leia.server.check;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines of output of a check (ADR 0022): the job appends them from its threads, polls read them
 * from the request loop.
 */
final class CheckLog {

    private final List<String> lines = new ArrayList<>();

    synchronized void add(String line) {
        lines.add(line);
    }

    /**
     * The lines from {@code from} on, and how many there are.
     *
     * @param from the first line; past the end gives none
     */
    synchronized Snapshot from(int from) {
        List<String> tail = from < lines.size() ? List.copyOf(lines.subList(from, lines.size())) : List.of();
        return new Snapshot(lines.size(), tail);
    }

    record Snapshot(int lineCount, List<String> lines) {}
}
