package io.github.mfuerstenau.leia.tui.about;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.theme.Icon;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.KeyLegend;
import io.github.mfuerstenau.leia.tui.widgets.Keys;
import io.github.mfuerstenau.leia.tui.widgets.Logo;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The window that {@code ?} opens on any screen, with two tabs (ADR 0120). Keys, the first, lists
 * every key of the shown screen by its group, footer or not, then the moves and the app's keys.
 * About has leia's version, build, author and project, the Java and the runtimes, the open index,
 * and the licence with the NOTICE. Either scrolls, the logo with it; Tab switches them.
 */
public final class AboutWindow {

    /** Columns between the logo and the text next to it. */
    private static final int GAP = 2;

    /** The fewest columns left for the text next to the logo. */
    private static final int MIN_TEXT_WIDTH = 30;

    private static final String KEYS_TAB_KEYS = "↑↓ scroll   g/G start/end   Tab about   Esc close";

    private static final String ABOUT_TAB_KEYS = "↑↓ scroll   g/G start/end   y copy link   Tab keys   Esc close";

    /** Columns of the group names in the key list, the widest and a gap. */
    private static final int GROUP_WIDTH = 7;

    /** The window's tabs. */
    public enum Tab {
        KEYS,
        ABOUT
    }

    /** What a key did to the window. */
    public sealed interface Event {}

    /** Nothing for the caller to do. */
    public record Nothing() implements Event {}

    /** Esc or {@code ?}: close the window. */
    public record Close() implements Event {}

    /** {@code y} on the About tab: put the project's URL on the clipboard. */
    public record Copy(String text) implements Event {}

    private final VersionResult version;
    private final List<Line> environment;
    private final String notice;

    /** What the key list is of, such as {@code Documents}. */
    private final String screen;

    private final Keys keys;

    private Tab tab = Tab.KEYS;

    /** The first row shown. */
    private int scroll;

    /** How many rows the last frame showed, for paging. */
    private int rows = 1;

    /** How many rows the content had in the last frame, for scrolling. */
    private int total = 1;

    private Optional<String> message = Optional.empty();

    /**
     * A window with {@code keys}, those of {@code screen}, and on leia {@code version}, with {@code
     * environment}, the lines about the Java, the runtimes and the open index, and the NOTICE of
     * {@code leia.jar}.
     */
    public AboutWindow(VersionResult version, List<Line> environment, String screen, Keys keys) {
        this(version, environment, About.notice(), screen, keys);
    }

    AboutWindow(VersionResult version, List<Line> environment, String notice, String screen, Keys keys) {
        this.version = version;
        this.environment = List.copyOf(environment);
        this.notice = notice;
        this.screen = screen;
        this.keys = keys.merged();
    }

    public Tab tab() {
        return tab;
    }

    public Event handleKey(KeyEvent key) {
        message = Optional.empty();
        switch (key.code()) {
            case ESCAPE -> {
                return new Close();
            }
            case DOWN -> scrollTo(scroll + 1);
            case UP -> scrollTo(scroll - 1);
            case PAGE_DOWN -> scrollTo(scroll + rows);
            case PAGE_UP -> scrollTo(scroll - rows);
            case HOME -> scrollTo(0);
            case END -> scrollTo(Integer.MAX_VALUE);
            case TAB -> {
                tab = tab == Tab.KEYS ? Tab.ABOUT : Tab.KEYS;
                scroll = 0;
            }
            case CHAR -> {
                switch (key.codePoint()) {
                    case '?' -> {
                        return new Close();
                    }
                    case 'j' -> scrollTo(scroll + 1);
                    case 'k' -> scrollTo(scroll - 1);
                    case 'g' -> scrollTo(0);
                    case 'G' -> scrollTo(Integer.MAX_VALUE);
                    case 'y' -> {
                        if (tab == Tab.ABOUT) {
                            message = Optional.of("Copied " + About.URL + " (OSC 52).");
                            return new Copy(About.URL);
                        }
                    }
                    default -> {}
                }
            }
            default -> {}
        }
        return new Nothing();
    }

    private void scrollTo(int to) {
        scroll = Math.clamp(to, 0, Math.max(total - rows, 0));
    }

    /** leia's name, version and build, its author and its project. */
    List<Line> header() {
        List<Line> lines = new ArrayList<>(Logo.aboutLines(version));
        About.author(notice).ifPresent(author -> lines.add(Line.from("by " + author)));
        lines.add(Line.from(About.URL));
        return lines;
    }

    /** The licence and the NOTICE. */
    List<Line> licence() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled("Licence", Palette.current().heading())));
        lines.add(Line.from("leia is licensed under the Apache License, Version 2.0: " + About.LICENSE_URL));
        lines.add(Line.empty());
        notice.lines().map(Line::from).forEach(lines::add);
        return lines;
    }

    /** The key list: a group's name, then each of its keys in bold and what it does, one a line. */
    List<Line> keyLines(int width) {
        int keyWidth = 0;
        for (Keys.Section section : keys.sections()) {
            for (Keys.Key key : section.keys()) {
                keyWidth = Math.max(keyWidth, CharWidth.of(key.key()));
            }
        }
        List<Line> lines = new ArrayList<>();
        for (Keys.Section section : keys.sections()) {
            boolean first = true;
            for (Keys.Key key : section.keys()) {
                String group = first ? section.group().title() : "";
                lines.add(Line.from(
                        Span.styled(pad(group, GROUP_WIDTH), Palette.current().muted()),
                        Span.styled(
                                pad(key.key(), keyWidth + 2), Palette.current().label()),
                        Span.raw(key.does())));
                first = false;
            }
        }
        return Panels.wrap(lines, width);
    }

    /** {@code text} padded with spaces to {@code width} columns, and a space at least. */
    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(width - CharWidth.of(text), 1));
    }

    /** Everything the shown tab shows, for a text {@code width} columns wide. */
    List<Line> content(int width) {
        return tab == Tab.KEYS ? keyLines(width) : aboutLines(width);
    }

    /** Everything the About tab shows. */
    private List<Line> aboutLines(int width) {
        List<Line> lines = new ArrayList<>(headerBesideLogo(width));
        lines.add(Line.empty());
        lines.addAll(Panels.wrap(environment, width));
        lines.add(Line.empty());
        lines.addAll(Panels.wrap(licence(), width));
        return lines;
    }

    /** The header with the logo at its left, where that leaves room for the text; else the header alone. */
    private List<Line> headerBesideLogo(int width) {
        Icon logo = Palette.current().logo();
        int textWidth = width - logo.width() - GAP;
        if (textWidth < MIN_TEXT_WIDTH) {
            return Panels.wrap(header(), width);
        }
        List<Line> text = Panels.wrap(header(), textWidth);
        int offset = Math.max(logo.height() - text.size(), 0) / 2;
        List<Line> lines = new ArrayList<>();
        for (int row = 0; row < Math.max(logo.height(), text.size()); row++) {
            List<Span> spans = new ArrayList<>();
            if (row < logo.height()) {
                logo.rows().get(row).forEach(cell -> spans.add(Span.styled(cell.character(), cell.style())));
            } else {
                spans.add(Span.raw(" ".repeat(logo.width())));
            }
            spans.add(Span.raw(" ".repeat(GAP)));
            int at = row - offset;
            if (at >= 0 && at < text.size()) {
                spans.addAll(text.get(at).spans());
            }
            lines.add(Line.from(spans));
        }
        return lines;
    }

    /** The tabs, the shown one reversed, and what it shows. */
    private Line titleLine(String shown) {
        List<Span> spans = new ArrayList<>();
        for (Tab each : Tab.values()) {
            String name = each == Tab.KEYS ? " Keys " : " About ";
            spans.add(each == tab ? Span.styled(name, Palette.current().selection()) : Span.raw(name));
        }
        spans.add(Span.raw("· " + shown));
        return Line.from(spans);
    }

    /** Draws the window over most of {@code area}. */
    public void render(Frame frame, Rect area) {
        Rect window = Panels.centered(area, Math.max(area.width() - 4, 0), Math.max(area.height() - 2, 0));
        Panels.clear(frame, window);
        Block probe = Panels.box().build();
        Rect inner = probe.inner(window);
        if (inner.width() <= 0 || inner.height() <= 0) {
            frame.renderWidget(probe, window);
            return;
        }
        List<Line> footer = new ArrayList<>();
        message.ifPresent(text ->
                footer.add(Line.from(Span.styled(" " + text, Palette.current().muted()))));
        footer.addAll(KeyLegend.lines(tab == Tab.KEYS ? KEYS_TAB_KEYS : ABOUT_TAB_KEYS, inner.width()));
        int footerHeight = Math.min(footer.size(), Math.max(inner.height() / 3, 1));
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(footerHeight))
                .split(inner);
        Rect list = parts.get(0);

        List<Line> all = content(list.width());
        total = all.size();
        rows = Math.max(list.height(), 1);
        scroll = Math.min(scroll, Math.max(total - rows, 0));
        List<Line> shown = all.subList(scroll, Math.min(scroll + rows, total));
        String name = tab == Tab.KEYS ? screen : "leia " + version.version();
        String where =
                total <= rows ? " " : ", lines " + (scroll + 1) + "–" + (scroll + shown.size()) + " of " + total + " ";
        frame.renderWidget(
                Panels.box().title(Title.from(titleLine(name + where))).build(), window);
        frame.renderWidget(Paragraph.from(Text.from(shown)), list);
        frame.renderWidget(Paragraph.from(Text.from(footer)), parts.get(1));
    }
}
