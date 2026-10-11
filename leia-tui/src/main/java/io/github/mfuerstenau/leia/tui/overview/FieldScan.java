package io.github.mfuerstenau.leia.tui.overview;

import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;

/** Where a field's term scan is. */
public sealed interface FieldScan {

    /** Waits for the scan before it. */
    record Queued() implements FieldScan {}

    record Running(long termsScanned) implements FieldScan {}

    /**
     * @param topN the number of top terms the scan asked for
     * @param table the selection in the top terms
     */
    record Done(FieldTerms terms, int topN, TableState table) implements FieldScan {}

    record Failed(String error) implements FieldScan {}

    /** A finished scan, with the first top term selected if there is one. */
    static Done done(FieldTerms terms, int topN) {
        TableState table = new TableState();
        if (!terms.topTerms().isEmpty()) {
            table.select(0);
        }
        return new Done(terms, topN, table);
    }

    /**
     * Whether a scan that asked for {@code asked} top terms has the top {@code topN}: it asked for as
     * many, or it got every term of the field.
     */
    static boolean hasTopTerms(FieldTerms terms, int asked, int topN) {
        return asked >= topN || terms.topTerms().size() >= terms.uniqueTerms();
    }
}
