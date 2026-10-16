package io.github.mfuerstenau.leia.runtime.support;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Splits what {@code CheckIndex} prints into lines, without their {@code \r}, and passes them on
 * (ADR 0050). Each whole line counts in the {@link Cancellation}.
 */
public final class CheckReport extends OutputStream {
    private final Consumer<String> output;
    private final Cancellation cancellation;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    public CheckReport(Consumer<String> output, Cancellation cancellation) {
        this.output = output;
        this.cancellation = cancellation;
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            emit();
        } else {
            line.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        int start = off;
        for (int i = off; i < off + len; i++) {
            if (b[i] == '\n') {
                line.write(b, start, i - start);
                emit();
                start = i + 1;
            }
        }
        line.write(b, start, off + len - start);
    }

    /** Passes on what is left of the last line, if anything. */
    public synchronized void end() {
        if (line.size() > 0) {
            output.accept(text());
        }
    }

    private void emit() {
        output.accept(text());
        cancellation.line();
    }

    private String text() {
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }
}
