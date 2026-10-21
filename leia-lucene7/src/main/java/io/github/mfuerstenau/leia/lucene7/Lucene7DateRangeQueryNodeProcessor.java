package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.support.DateRanges;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.core.config.FieldConfig;
import org.apache.lucene.queryparser.flexible.core.config.QueryConfigHandler;
import org.apache.lucene.queryparser.flexible.core.nodes.FieldQueryNode;
import org.apache.lucene.queryparser.flexible.core.nodes.QueryNode;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler.ConfigurationKeys;
import org.apache.lucene.queryparser.flexible.standard.nodes.TermRangeQueryNode;
import org.apache.lucene.queryparser.flexible.standard.processors.TermRangeQueryNodeProcessor;

/**
 * Lucene 7.7's processor of term ranges for the standard query parser, which reads the bounds as
 * dates in the parser's time zone rather than the JVM's default, a copy of leia-core's (ADR 0026, ADR
 * 0069). Adapted from Lucene's {@link TermRangeQueryNodeProcessor} (Apache License 2.0).
 */
final class Lucene7DateRangeQueryNodeProcessor extends TermRangeQueryNodeProcessor {

    /**
     * As Lucene's: if either bound isn't a date, or there is no resolution, both bounds stay text,
     * normalized by the analyzer.
     */
    @Override
    protected QueryNode postProcessNode(QueryNode node) throws QueryNodeException {
        if (!(node instanceof TermRangeQueryNode range)) {
            return node;
        }
        QueryConfigHandler config = getQueryConfigHandler();
        FieldQueryNode lower = range.getLowerBound();
        FieldQueryNode upper = range.getUpperBound();
        Locale locale = config.get(ConfigurationKeys.LOCALE);
        if (locale == null) {
            locale = Locale.getDefault();
        }
        TimeZone zone = config.get(ConfigurationKeys.TIMEZONE);
        if (zone == null) {
            zone = TimeZone.getDefault();
        }
        CharSequence field = range.getField();
        FieldConfig fieldConfig = config.getFieldConfig(field == null ? null : field.toString());
        DateTools.Resolution resolution =
                fieldConfig == null ? null : fieldConfig.get(ConfigurationKeys.DATE_RESOLUTION);

        String part1 = lower.getTextAsString();
        String part2 = upper.getTextAsString();
        try {
            DateFormat format = DateRanges.format(locale, zone);
            if (!part1.isEmpty()) {
                lower.setText(DateTools.dateToString(format.parse(part1), resolution));
            }
            if (!part2.isEmpty()) {
                Date date = format.parse(part2);
                if (range.isUpperInclusive()) {
                    date = DateRanges.endOfDay(date, zone, locale);
                }
                upper.setText(DateTools.dateToString(date, resolution));
            }
        } catch (Exception _) {
            // Not a date, or no resolution.
            Analyzer analyzer = config.get(ConfigurationKeys.ANALYZER);
            if (analyzer != null) {
                lower.setText(
                        analyzer.normalize(lower.getFieldAsString(), part1).utf8ToString());
                upper.setText(
                        analyzer.normalize(lower.getFieldAsString(), part2).utf8ToString());
            }
        }
        return node;
    }
}
