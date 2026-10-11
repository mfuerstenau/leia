package io.github.mfuerstenau.leia.tui.overview;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.labelLine;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Logo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The overview screen: what {@code getIndexStats} reports about the open index, with a scrollable
 * table of its fields, and the term counts and top terms of the fields that were scanned.
 */
public final class Overview {

    /** Rows that PageUp and PageDown move. */
    static final int PAGE = 10;

    /** How many top terms a scan asks for at first, as in Luke. */
    public static final int DEFAULT_TOP_TERMS = 50;

    /** The numbers of top terms that {@code +} and {@code -} step through; the backend allows up to 1000. */
    private static final int[] TOP_TERMS_STEPS = {10, 20, 50, 100, 200, 500, 1000};

    /** From this width on, the terms panel is next to the fields instead of below them. */
    static final int SIDE_BY_SIDE_WIDTH = 130;

    /** The width of the terms panel next to the fields. */
    static final int TERMS_PANEL_WIDTH = 46;

    /**
     * From this height on, the logo is at the right of the index summary, which grows to its height,
     * if the summary still fits next to it.
     */
    static final int LOGO_HEIGHT = 30;

    /** The index panel shows at most this many index directories, and as many skipped ones. */
    static final int SUMMARY_INDEXES = 8;

    /** Why an index directory was skipped takes at most this many lines. */
    static final int SKIPPED_LINES = 3;

    /**
     * A skipped index directory's reason wraps under its start if this many columns are left there,
     * and at the panel's left edge otherwise.
     */
    static final int SKIPPED_MIN_WIDTH = 30;

    private record Column(String title, boolean rightAligned) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("#", true),
            new Column("Name", false),
            new Column("Postings", false),
            new Column("Flags", false),
            new Column("Doc values", false),
            new Column("Points", false),
            new Column("Vector", false),
            new Column("Terms", true),
            new Column("%", true),
            new Column("Docs", true),
            new Column("Σ doc freq", true),
            new Column("Σ term freq", true));

    private static final int NUMBER_COLUMN = 0;
    private static final int NAME_COLUMN = 1;
    private static final int TERMS_COLUMN = 7;

    /** A space and {@code ▲} or {@code ▼} after the title of the sorted column. */
    private static final int SORT_MARKER_WIDTH = 2;

    static final int NAME_MIN_WIDTH = 16;
    static final int COLUMN_SPACING = 2;

    /** What the field table is sorted by. */
    enum SortKey {
        NUMBER,
        NAME,
        /** The unique term count; fields without one come last in either direction. */
        TERMS;

        SortKey next() {
            return switch (this) {
                case NUMBER -> NAME;
                case NAME -> TERMS;
                case TERMS -> NUMBER;
            };
        }

        /** The direction a key starts in: the largest term counts first, everything else ascending. */
        boolean startsDescending() {
            return this == TERMS;
        }

        /** The column whose header shows the sort. */
        int column() {
            return switch (this) {
                case NUMBER -> NUMBER_COLUMN;
                case NAME -> NAME_COLUMN;
                case TERMS -> TERMS_COLUMN;
            };
        }
    }

    /** The table that the navigation keys move. */
    private enum Focus {
        FIELDS,
        TERMS
    }

    private final GetIndexStatsResult stats;

    /** leia's name, version and build, next to the logo. */
    private final List<Line> about;

    /** The index directories that were found below the opened path but left out (ADR 0021). */
    private List<SkippedIndex> skipped = List.of();

    /** The fields in table order, as indexes into {@code stats.fields()}. */
    private List<Integer> order = List.of();

    /** The text of every row's cells in table order, formatted again when a scan or the sort changes. */
    private List<String[]> fieldRows = List.of();

    /** The widest cell of each column, header included. */
    private int[] columnWidths = new int[COLUMNS.size()];

    private final TableState fields = new TableState();
    private SortKey sort = SortKey.NUMBER;
    private boolean descending;

    /** Term scans by field name; a field that was never scanned has none. */
    private final Map<String, FieldScan> scans = new HashMap<>();

    /** How many top terms to show, and to scan for. */
    private int topN;

    private Focus focus = Focus.FIELDS;

    public Overview(GetIndexStatsResult stats, List<Line> about, int topN) {
        this.stats = stats;
        this.about = List.copyOf(about);
        this.topN = topN;
        if (!stats.fields().isEmpty()) {
            fields.select(0);
        }
        formatRows();
    }

    /** Lists the index directories that were found below the opened path but left out. */
    public Overview withSkipped(List<SkippedIndex> skipped) {
        this.skipped = List.copyOf(skipped);
        return this;
    }

    /** The statistics shown. */
    public GetIndexStatsResult stats() {
        return stats;
    }

    /**
     * The overview of the reopened index (ADR 0019): sorted as before, with the same field selected
     * if it is still there. The scans are not kept; their counts are stale. The skipped index
     * directories stay, as a reopen doesn't look for indexes again.
     */
    public Overview reopened(GetIndexStatsResult stats, int topN) {
        Optional<String> selected = selectedField().map(IndexField::name);
        Overview overview = new Overview(stats, about, topN).withSkipped(skipped);
        overview.sort = sort;
        overview.descending = descending;
        overview.formatRows();
        List<IndexField> fields = overview.stats.fields();
        int row = selected.map(name -> IntStream.range(0, overview.order.size())
                        .filter(i -> fields.get(overview.order.get(i)).name().equals(name))
                        .findFirst()
                        .orElse(0))
                .orElse(0);
        if (!fields.isEmpty()) {
            overview.fields.select(row);
        }
        return overview;
    }

    /** Sorts the rows and formats their cells. The selection stays on its field. */
    private void formatRows() {
        OptionalInt selected = selectedIndex();
        order = sortedOrder();
        selected.ifPresent(index -> fields.select(order.indexOf(index)));
        OptionalLong total = totalTerms();
        fieldRows = order.stream()
                .map(index -> {
                    IndexField field = stats.fields().get(index);
                    return fieldCells(field, scans.get(field.name()), total);
                })
                .toList();
        columnWidths = new int[COLUMNS.size()];
        for (int column = 0; column < COLUMNS.size(); column++) {
            // Room for the sort marker, so that the columns don't move when the sort changes.
            int finalColumn = column;
            boolean sortable = List.of(SortKey.values()).stream().anyMatch(key -> key.column() == finalColumn);
            int width = CharWidth.of(COLUMNS.get(column).title()) + (sortable ? SORT_MARKER_WIDTH : 0);
            for (String[] row : fieldRows) {
                width = Math.max(width, CharWidth.of(row[column]));
            }
            columnWidths[column] = width;
        }
    }

    private List<Integer> sortedOrder() {
        List<IndexField> fields = stats.fields();
        Comparator<IndexField> byKey = switch (sort) {
            case NUMBER -> directed(Comparator.comparingInt(IndexField::number));
            case NAME -> directed(Comparator.comparing(IndexField::name));
            // Fields without a count last, in either direction.
            case TERMS ->
                (a, b) -> {
                    OptionalLong countA = termCount(a);
                    OptionalLong countB = termCount(b);
                    if (countA.isPresent() && countB.isPresent()) {
                        int ordering = Long.compare(countA.getAsLong(), countB.getAsLong());
                        return descending ? -ordering : ordering;
                    }
                    return Boolean.compare(countA.isEmpty(), countB.isEmpty());
                };
        };
        Comparator<IndexField> comparator = byKey.thenComparingInt(IndexField::number);
        return IntStream.range(0, fields.size())
                .boxed()
                .sorted((a, b) -> comparator.compare(fields.get(a), fields.get(b)))
                .toList();
    }

    private <T> Comparator<T> directed(Comparator<T> comparator) {
        return descending ? comparator.reversed() : comparator;
    }

    /** The unique term count of a scanned field. */
    private OptionalLong termCount(IndexField field) {
        return scans.get(field.name()) instanceof FieldScan.Done done
                ? OptionalLong.of(done.terms().uniqueTerms())
                : OptionalLong.empty();
    }

    /** The number of terms of all fields, once every field with postings has been scanned. */
    private OptionalLong totalTerms() {
        List<IndexField> indexed = stats.fields().stream()
                .filter(field -> field.terms().isPresent())
                .toList();
        if (indexed.isEmpty()) {
            return OptionalLong.empty();
        }
        long total = 0;
        for (IndexField field : indexed) {
            OptionalLong count = termCount(field);
            if (count.isEmpty()) {
                return OptionalLong.empty();
            }
            total += count.getAsLong();
        }
        return OptionalLong.of(total);
    }

    /** Changes how many top terms are shown. A scan for fewer can be repeated, a scan for more is cut short. */
    public void setTopN(int topN) {
        this.topN = topN;
    }

    /** The next number of top terms up or down from {@code topN}, or {@code topN} at either end. */
    public static int stepTopTerms(int topN, boolean up) {
        if (up) {
            for (int step : TOP_TERMS_STEPS) {
                if (step > topN) {
                    return step;
                }
            }
        } else {
            for (int i = TOP_TERMS_STEPS.length - 1; i >= 0; i--) {
                if (TOP_TERMS_STEPS[i] < topN) {
                    return TOP_TERMS_STEPS[i];
                }
            }
        }
        return topN;
    }

    /** The fields of the index, by field number. */
    public List<IndexField> fields() {
        return stats.fields();
    }

    /** The field under the selection. */
    public Optional<IndexField> selectedField() {
        OptionalInt index = selectedIndex();
        return index.isPresent() ? Optional.of(stats.fields().get(index.getAsInt())) : Optional.empty();
    }

    /** The index into {@code stats.fields()} of the field under the selection. */
    private OptionalInt selectedIndex() {
        Integer selected = fields.selected();
        if (selected == null || order.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(order.get(Math.min(selected, order.size() - 1)));
    }

    /** The field and the top term under the selection, while the top terms have the focus. */
    public Optional<FieldTerm> selectedTopTerm() {
        if (focus != Focus.TERMS) {
            return Optional.empty();
        }
        Optional<IndexField> field = selectedField();
        if (field.isEmpty() || !(scans.get(field.get().name()) instanceof FieldScan.Done done)) {
            return Optional.empty();
        }
        int shown = Math.min(done.terms().topTerms().size(), topN);
        Integer selected = done.table().selected();
        if (selected == null || shown == 0) {
            return Optional.empty();
        }
        TopTerm top = done.terms().topTerms().get(Math.min(selected, shown - 1));
        return Optional.of(new FieldTerm(field.get().name(), new BytesValue(top.term(), top.binary())));
    }

    /** A term of a field, as the terms and search screens take it. */
    public record FieldTerm(String field, BytesValue term) {}

    public Optional<FieldScan> scan(String field) {
        return Optional.ofNullable(scans.get(field));
    }

    /** Sets or, with an empty {@code scan}, forgets the term scan of {@code field}. */
    public void setScan(String field, Optional<FieldScan> scan) {
        scan.ifPresentOrElse(s -> scans.put(field, s), () -> scans.remove(field));
        formatRows();
    }

    /**
     * Whether {@code field} can be scanned: it has postings, and no scan, a failed one, or one for
     * fewer than the top N terms.
     */
    public boolean needsScan(IndexField field) {
        if (field.terms().isEmpty()) {
            return false;
        }
        return switch (scans.get(field.name())) {
            case null -> true;
            case FieldScan.Failed _ -> true;
            case FieldScan.Done done -> !FieldScan.hasTopTerms(done.terms(), done.topN(), topN);
            case FieldScan.Queued _, FieldScan.Running _ -> false;
        };
    }

    /** The fields that {@linkplain #needsScan need a scan}, in field order. */
    public List<String> fieldsToScan() {
        return stats.fields().stream()
                .filter(this::needsScan)
                .map(IndexField::name)
                .toList();
    }

    /**
     * Moves the selection in the focused table, switches the focus with Tab, cycles the sort column
     * with {@code s} and flips its direction with {@code S}. Returns whether the key was used.
     */
    public boolean handleKey(KeyEvent key) {
        if (key.code() == KeyCode.TAB && !key.hasShift()) {
            focus = focus == Focus.FIELDS ? Focus.TERMS : Focus.FIELDS;
            return true;
        }
        if (key.isChar('s')) {
            sort = sort.next();
            descending = sort.startsDescending();
            formatRows();
            return true;
        }
        if (key.isChar('S')) {
            descending = !descending;
            formatRows();
            return true;
        }
        TableState table = fields;
        int rows = order.size();
        Optional<IndexField> selected = selectedField();
        if (focus == Focus.TERMS
                && selected.isPresent()
                && scans.get(selected.get().name()) instanceof FieldScan.Done done) {
            table = done.table();
            rows = Math.min(done.terms().topTerms().size(), topN);
        }
        int moved;
        switch (key.code()) {
            case DOWN -> moved = 1;
            case UP -> moved = -1;
            case PAGE_DOWN -> moved = PAGE;
            case PAGE_UP -> moved = -PAGE;
            case HOME -> moved = Integer.MIN_VALUE;
            case END -> moved = Integer.MAX_VALUE;
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> moved = 1;
                    case 'k' -> moved = -1;
                    case 'g' -> moved = Integer.MIN_VALUE;
                    case 'G' -> moved = Integer.MAX_VALUE;
                    default -> {
                        return false;
                    }
                }
            }
            default -> {
                return false;
            }
        }
        move(table, rows, moved);
        return true;
    }

    /** Moves the selection of a table of {@code rows} rows by {@code by}, within the rows. */
    private static void move(TableState table, int rows, int by) {
        if (rows == 0) {
            table.clearSelection();
            return;
        }
        Integer selected = table.selected();
        long current = selected == null ? 0 : Math.min(selected, rows - 1);
        long next = Math.clamp(current + by, 0, rows - 1);
        table.select((int) next);
    }

    public void render(Frame frame, Rect area) {
        // Inside the block's left and right border.
        int innerWidth = Math.max(area.width() - 2, 0);
        List<Line> summary = summaryLines(stats, skipped, innerWidth);
        // The logo with leia's version and a column on either side, if the summary still fits.
        int logoWidth = Logo.widthBeside(about) + 2;
        boolean withLogo = area.height() >= LOGO_HEIGHT
                && summary.stream().allMatch(line -> line.width() + logoWidth <= innerWidth);
        int rows = withLogo ? Math.max(summary.size(), Logo.HEIGHT) : summary.size();
        List<Rect> parts = Layout.vertical()
                // The lines plus the block's top and bottom border.
                .constraints(Constraint.length(rows + 2), Constraint.fill())
                .split(area);
        Rect top = parts.get(0);
        Rect bottom = parts.get(1);
        Block block = panel(" Index ", false);
        Rect inner = block.inner(top);
        frame.renderWidget(block, top);
        if (withLogo) {
            List<Rect> columns = Layout.horizontal()
                    .constraints(
                            Constraint.fill(),
                            Constraint.length(1),
                            Constraint.length(logoWidth - 2),
                            Constraint.length(1))
                    .split(inner);
            frame.renderWidget(Paragraph.from(Text.from(summary)), columns.get(0));
            Logo.renderBeside(frame, columns.get(2), about);
        } else {
            frame.renderWidget(Paragraph.from(Text.from(summary)), inner);
        }

        List<Rect> panels = bottom.width() >= SIDE_BY_SIDE_WIDTH
                ? Layout.horizontal()
                        .constraints(Constraint.fill(), Constraint.length(TERMS_PANEL_WIDTH))
                        .split(bottom)
                : Layout.vertical()
                        .constraints(Constraint.fill(), Constraint.percentage(45))
                        .split(bottom);
        renderFields(frame, panels.get(0));
        renderTerms(frame, panels.get(1));
    }

    private void renderFields(Frame frame, Rect area) {
        // Inside the block's left and right border.
        int shown = shownColumns(columnWidths, Math.max(area.width() - 2, 0));
        List<Cell> header = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            Column column = COLUMNS.get(i);
            String title = i == sort.column() ? column.title() + (descending ? " ▼" : " ▲") : column.title();
            header.add(cell(column, title, columnWidths[i]));
        }
        List<Row> rows = fieldRows.stream()
                .map(texts -> {
                    List<Cell> cells = new ArrayList<>();
                    for (int i = 0; i < shown; i++) {
                        cells.add(cell(COLUMNS.get(i), texts[i], columnWidths[i]));
                    }
                    return Row.from(cells);
                })
                .toList();
        // Every column gets the width of its widest cell, and the name the rest of the line.
        List<Constraint> widths = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            widths.add(
                    i == NAME_COLUMN
                            ? Constraint.min(Math.min(columnWidths[i], NAME_MIN_WIDTH))
                            : Constraint.length(columnWidths[i]));
        }
        Table table = Table.builder()
                .rows(rows)
                .widths(widths)
                .header(Row.from(header).style(Style.EMPTY.bold()))
                .columnSpacing(COLUMN_SPACING)
                .highlightStyle(highlight(focus == Focus.FIELDS))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER)
                .block(panel(fieldsTitle(), focus == Focus.FIELDS))
                .build();
        frame.renderStatefulWidget(table, area, fields);
    }

    /** The number of fields, and the total number of terms once every field with postings has been scanned. */
    private String fieldsTitle() {
        int count = stats.fields().size();
        OptionalLong total = totalTerms();
        return total.isPresent()
                ? " Fields (" + count + ", " + total.getAsLong() + " terms) "
                : " Fields (" + count + ") ";
    }

    private void renderTerms(Frame frame, Rect area) {
        boolean focused = focus == Focus.TERMS;
        Optional<IndexField> field = selectedField();
        if (field.isEmpty()) {
            frame.renderWidget(panel(" Terms ", focused), area);
            return;
        }
        String name = field.get().name();
        boolean hasPostings = field.get().terms().isPresent();
        String title = " Terms: " + name + " ";
        List<Line> message;
        switch (scans.get(name)) {
            case FieldScan.Done done -> {
                List<TopTerm> all = done.terms().topTerms();
                List<TopTerm> topTerms = all.subList(0, Math.min(all.size(), topN));
                title = " " + name + ": " + done.terms().uniqueTerms() + " terms, top " + topTerms.size() + " ";
                if (!FieldScan.hasTopTerms(done.terms(), done.topN(), topN)) {
                    title += "(⏎ top " + topN + ") ";
                }
                if (!topTerms.isEmpty()) {
                    Table table = topTermsTable(topTerms)
                            .highlightStyle(highlight(focused))
                            .block(panel(title, focused))
                            .build();
                    frame.renderStatefulWidget(table, area, done.table());
                    return;
                }
                message = List.of(Line.from("The field has no terms."));
            }
            case FieldScan.Running running ->
                message = List.of(
                        Line.from("Scanning… " + running.termsScanned() + " terms so far."), Line.from("Esc cancels."));
            case FieldScan.Queued _ ->
                message = List.of(Line.from("Queued after the running scan."), Line.from("Esc cancels."));
            case FieldScan.Failed failed ->
                message = List.of(Line.from("The scan failed: " + failed.error()), Line.from("Enter tries again."));
            case null ->
                message = hasPostings
                        ? List.of(
                                Line.from("Enter counts the terms and finds the top " + topN + "."),
                                Line.from("a does so for every field."))
                        : List.of(Line.from("No postings: the field is not indexed."));
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(message))
                        .overflow(Overflow.WRAP_WORD)
                        .block(panel(title, focused))
                        .build(),
                area);
    }

    private static Table.Builder topTermsTable(List<TopTerm> topTerms) {
        List<String[]> rows = new ArrayList<>();
        for (int rank = 0; rank < topTerms.size(); rank++) {
            TopTerm term = topTerms.get(rank);
            rows.add(new String[] {
                Integer.toString(rank + 1),
                Integer.toString(term.docFreq()),
                Long.toString(term.totalTermFreq()),
                TextFormat.bytesText(term.term(), term.binary())
            });
        }
        String[] titles = {"#", "Docs", "Freq", "Term"};
        int[] widths = new int[3];
        for (int column = 0; column < 3; column++) {
            widths[column] = titles[column].length();
            for (String[] row : rows) {
                widths[column] = Math.max(widths[column], row[column].length());
            }
        }
        Row header = Row.from(
                        right(titles[0], widths[0]),
                        right(titles[1], widths[1]),
                        right(titles[2], widths[2]),
                        Cell.from(titles[3]))
                .style(Style.EMPTY.bold());
        return Table.builder()
                .rows(rows.stream()
                        .map(row -> Row.from(
                                right(row[0], widths[0]),
                                right(row[1], widths[1]),
                                right(row[2], widths[2]),
                                Cell.from(row[3])))
                        .toList())
                .widths(
                        Constraint.length(widths[0]),
                        Constraint.length(widths[1]),
                        Constraint.length(widths[2]),
                        Constraint.fill())
                .header(header)
                .columnSpacing(COLUMN_SPACING)
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER);
    }

    /** The lines of the index panel, {@code width} columns wide inside its border. */
    static List<Line> summaryLines(GetIndexStatsResult stats, List<SkippedIndex> skipped, int width) {
        int deleted = stats.maxDoc() - stats.numDocs();
        String merged = stats.segmentCount() == 1 ? " (fully merged)" : "";
        String documents = stats.numDocs() + " live, " + deleted + " deleted, " + stats.maxDoc() + " max";
        List<Line> lines = new ArrayList<>();
        lines.add(labelLine("Path", stats.path()));
        lines.add(labelLine("Directory", stats.directoryImpl()));
        List<IndexCommitStats> indexes = stats.indexes();
        if (indexes.size() == 1 && skipped.isEmpty()) {
            IndexCommitStats index = indexes.getFirst();
            lines.add(labelLine(
                    "Commit",
                    index.segmentsFile() + " (generation " + index.generation() + ", version " + index.version()
                            + ")"));
            lines.add(labelLine("Segments", stats.segmentCount() + merged));
            lines.add(labelLine("Documents", documents));
            lines.add(labelLine("Lucene", lucene(index)));
            lines.add(labelLine("User data", userData(index.userData())));
        } else {
            String count = skipped.isEmpty()
                    ? Integer.toString(indexes.size())
                    : indexes.size() + " (" + skipped.size() + " skipped)";
            lines.add(labelLine("Indexes", count));
            lines.add(labelLine("Segments", stats.segmentCount() + merged));
            lines.add(labelLine("Documents", documents));
        }
        if (indexes.size() > 1 || !skipped.isEmpty()) {
            lines.addAll(indexLines(stats, skipped, width));
        }
        return lines;
    }

    /**
     * One line per index directory, below the opened path, then each skipped one with why, wrapped to
     * {@code panelWidth}.
     */
    private static List<Line> indexLines(GetIndexStatsResult stats, List<SkippedIndex> skipped, int panelWidth) {
        List<String> names = new ArrayList<>();
        stats.indexes().forEach(index -> names.add(relative(stats.path(), index.path())));
        skipped.forEach(index -> names.add(relative(stats.path(), index.path())));
        int width = names.stream()
                .mapToInt(name -> name.codePointCount(0, name.length()))
                .max()
                .orElse(0);
        List<Line> lines = new ArrayList<>();
        List<IndexCommitStats> indexes = stats.indexes();
        for (int i = 0; i < Math.min(indexes.size(), SUMMARY_INDEXES); i++) {
            IndexCommitStats index = indexes.get(i);
            lines.add(Line.from("  " + pad(names.get(i), width) + "  " + index.segmentsFile() + "  " + index.numDocs()
                    + " live, " + index.maxDoc() + " max  " + lucene(index)));
        }
        if (indexes.size() > SUMMARY_INDEXES) {
            lines.add(Line.from("  … and " + (indexes.size() - SUMMARY_INDEXES) + " more"));
        }
        Style red = Style.EMPTY.red();
        for (int i = 0; i < Math.min(skipped.size(), SUMMARY_INDEXES); i++) {
            String prefix = "  " + pad(names.get(indexes.size() + i), width) + "  skipped: ";
            int prefixWidth = CharWidth.of(prefix);
            int indent = panelWidth - prefixWidth >= SKIPPED_MIN_WIDTH ? prefixWidth : 4;
            List<String> message = TextFormat.wrapHanging(
                    TextFormat.escapeControl(skipped.get(i).message()),
                    Math.max(panelWidth - prefixWidth, 0),
                    Math.max(panelWidth - indent, 0),
                    SKIPPED_LINES);
            for (int line = 0; line < message.size(); line++) {
                String text = line == 0 ? prefix + message.get(line) : " ".repeat(indent) + message.get(line);
                lines.add(Line.styled(text, red));
            }
        }
        if (skipped.size() > SUMMARY_INDEXES) {
            lines.add(Line.styled("  … and " + (skipped.size() - SUMMARY_INDEXES) + " more skipped", red));
        }
        return lines;
    }

    /** {@code text} padded with spaces to {@code width} characters. */
    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(width - text.codePointCount(0, text.length()), 0));
    }

    /** {@code path} below {@code root}, or {@code path} itself if it isn't below it; {@code .} for {@code root} itself. */
    public static String relative(String root, String path) {
        try {
            Path rootPath = Path.of(root);
            Path indexPath = Path.of(path);
            if (!indexPath.startsWith(rootPath)) {
                return path;
            }
            String rest = rootPath.relativize(indexPath).toString();
            return rest.isEmpty() ? "." : rest;
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    private static String lucene(IndexCommitStats index) {
        String lucene =
                "created by " + index.createdVersionMajor() + ", commit written by " + index.commitLuceneVersion();
        return index.minSegmentLuceneVersion()
                .map(oldest -> lucene + ", oldest segment by " + oldest)
                .orElse(lucene);
    }

    private static String userData(Map<String, String> userData) {
        if (userData.isEmpty()) {
            return "-";
        }
        return new TreeMap<>(userData)
                .entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(", "));
    }

    /**
     * How many columns, from the left, fit into {@code available} cells. A column is shown whole or
     * not at all, so on a narrow terminal the columns on the right go first. The number and the name
     * are always shown; the name counts with at most {@link #NAME_MIN_WIDTH}.
     */
    static int shownColumns(int[] widths, int available) {
        int used = 0;
        for (int i = 0; i < widths.length; i++) {
            int width = i == NAME_COLUMN ? Math.min(widths[i], NAME_MIN_WIDTH) : widths[i];
            used += (i == 0 ? 0 : COLUMN_SPACING) + width;
            if (used > available && i > NAME_COLUMN) {
                return i;
            }
        }
        return widths.length;
    }

    private static Cell cell(Column column, String text, int width) {
        return column.rightAligned() ? right(text, width) : Cell.from(text);
    }

    /** The cells of a field's row. {@code totalTerms} is the number of terms of all fields, once known. */
    private static String[] fieldCells(IndexField field, FieldScan scan, OptionalLong totalTerms) {
        String points = field.pointDimensionCount() == 0
                ? "-"
                : field.pointDimensionCount() + "×" + field.pointNumBytes() + "B";
        String vector = field.vectorDimension() == 0 ? "-" : field.vectorDimension() + "d";
        Optional<TermStats> stats = field.terms();
        String docs = stats.map(s -> Integer.toString(s.docCount())).orElse("-");
        String sumDocFreq = stats.map(s -> Long.toString(s.sumDocFreq())).orElse("-");
        String sumTotalTermFreq =
                stats.map(s -> Long.toString(s.sumTotalTermFreq())).orElse("-");
        String terms;
        String share;
        if (stats.isEmpty()) {
            terms = "-";
            share = "-";
        } else {
            terms = switch (scan) {
                case null -> "?";
                case FieldScan.Queued _ -> "queued";
                case FieldScan.Running running -> running.termsScanned() + "…";
                case FieldScan.Done done -> Long.toString(done.terms().uniqueTerms());
                case FieldScan.Failed _ -> "failed";
            };
            // The share of all terms, as Luke shows it.
            if (totalTerms.isEmpty()) {
                share = "?";
            } else if (scan instanceof FieldScan.Done done && totalTerms.getAsLong() > 0) {
                share = String.format(
                        Locale.ROOT, "%.2f", (double) done.terms().uniqueTerms() / totalTerms.getAsLong() * 100.0);
            } else {
                share = "0.00";
            }
        }
        return new String[] {
            Integer.toString(field.number()),
            field.name(),
            postings(field.indexOptions()),
            flags(field),
            docValues(field.docValuesType()),
            points,
            vector,
            terms,
            share,
            docs,
            sumDocFreq,
            sumTotalTermFreq
        };
    }

    public static String postings(IndexOptions options) {
        return switch (options) {
            case NONE -> "-";
            case DOCS -> "docs";
            case DOCS_AND_FREQS -> "freqs";
            case DOCS_AND_FREQS_AND_POSITIONS -> "positions";
            case DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS -> "offsets";
            case DOCS_AND_CUSTOM_FREQS -> "custom freqs";
        };
    }

    /** {@code N} norms, {@code V} term vectors, {@code P} payloads; {@code -} where absent. */
    private static String flags(IndexField field) {
        return (field.hasNorms() ? "N" : "-")
                + (field.hasTermVectors() ? "V" : "-")
                + (field.hasPayloads() ? "P" : "-");
    }

    public static String docValues(DocValuesType type) {
        return switch (type) {
            case NONE -> "-";
            case NUMERIC -> "numeric";
            case BINARY -> "binary";
            case SORTED -> "sorted";
            case SORTED_NUMERIC -> "sorted numeric";
            case SORTED_SET -> "sorted set";
        };
    }
}
