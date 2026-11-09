package io.github.mfuerstenau.leia.tui.analysis;

import static io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.cycled;
import static io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.packageOf;
import static io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.quoted;
import static io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.shingleSizeText;
import static io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.simpleName;
import static io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.unigramsText;
import static io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.wordSetText;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationDecomposition;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseCompletionMode;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseCompletionOptions;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseMode;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseOptions;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanDecompoundMode;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanOptions;
import io.github.mfuerstenau.leia.protocol.analysis.LimitTokenCount;
import io.github.mfuerstenau.leia.protocol.analysis.PresetOption;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.WordSetSupport;
import io.github.mfuerstenau.leia.tui.Optionals;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.Language;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.WordSetKind;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** The analyzer popup of the analysis screen: a draft of the analyzer, which the caller tries before it takes it. */
public final class AnalyzerForm {

    /** How wide the popups are at most. */
    private static final int WIDTH = 76;

    /** The token limit that turning it on starts with: the old {@code IndexWriter} field length. */
    static final int DEFAULT_MAX_TOKEN_COUNT = 10_000;

    /** The longest token a preset's {@code maxTokenLength} may be: {@code StandardTokenizer.MAX_TOKEN_LENGTH_LIMIT}. */
    static final int MAX_TOKEN_LENGTH = 1024 * 1024;

    /** How wide the labels of the rows are. */
    private static final int LABEL = 16;

    /** Lucene's defaults for shingles. */
    static final Shingles DEFAULT_SHINGLES = new Shingles(2, 2, " ", true, false, "_");

    /** A part of the custom chain. */
    enum Section {
        CHAR_FILTERS("Char filters", "char filter"),
        TOKENIZER("Tokenizer", "tokenizer"),
        TOKEN_FILTERS("Token filters", "token filter");

        final String heading;

        /** What one component of the section is called. */
        final String noun;

        Section(String heading, String noun) {
            this.heading = heading;
            this.noun = noun;
        }

        /** The names of the section's components, and their class names. */
        List<Choice> available(GetAnalysisComponentsResult components) {
            List<AvailableComponent> available = switch (this) {
                case CHAR_FILTERS -> components.charFilters();
                case TOKENIZER -> components.tokenizers();
                case TOKEN_FILTERS -> components.tokenFilters();
            };
            return available.stream()
                    .map(component -> new Choice(component.name(), component.className()))
                    .toList();
        }
    }

    /** A row of the shingles (ADR 0023). */
    enum ShingleField {
        /** The fewest and the most tokens in a shingle. */
        SIZE,
        SEPARATOR,
        /** Whether the tokens are kept: always, never, or when there are no shingles. */
        UNIGRAMS,
        FILLER
    }

    /** A row of the token limit (ADR 0023). */
    enum LimitField {
        MAX_TOKENS,
        CONSUME_ALL
    }

    /** What the picker offers: a component by its SPI name, or a preset by its simple class name. */
    record Choice(String name, String className) {}

    /** One selectable line of the popup. */
    sealed interface Row {
        /** Standard, preset or custom. */
        record KindRow() implements Row {}

        /** The preset's class. */
        record Preset() implements Row {}

        /** A preset's stop words or stem exclusions. */
        record WordSet(WordSetKind kind) implements Row {}

        /** A preset's options (ADR 0023). */
        record MaxTokenLength() implements Row {}

        record StemOverride() implements Row {}

        record UserDictionary(Language language) implements Row {}

        record JapaneseModeRow() implements Row {}

        record DecompoundMode() implements Row {}

        record StopTags(Language language) implements Row {}

        record UnknownWords() implements Row {}

        /** A collation analyzer's collator (ADR 0038). */
        record CollationLocale() implements Row {}

        record Strength() implements Row {}

        record Decomposition() implements Row {}

        /** {@code JapaneseCompletionAnalyzer}'s mode (ADR 0038). */
        record CompletionMode() implements Row {}

        record ConfigDir() implements Row {}

        /** A component: its section and its index there (0 for the tokenizer). */
        record Component(Section section, int index) implements Row {}

        /** A parameter of a component, by its index in key order. */
        record Param(Section section, int index, int param) implements Row {}

        /** Adds a char filter or token filter at the end. */
        record Add(Section section) implements Row {}

        /** The wrappers, on or off, and their fields while on (ADR 0023). */
        record ShinglesRow() implements Row {}

        record Shingle(ShingleField field) implements Row {}

        record TokenLimit() implements Row {}

        record Limit(LimitField field) implements Row {}
    }

    /** Where a picked component goes. */
    record Target(boolean insert, int index) {}

    /** What a picker picks: a component of a section, put at {@code target}, or a preset (null section). */
    record Pick(@Nullable Section section, @Nullable Target target) {
        static final Pick PRESET = new Pick(null, null);

        boolean preset() {
            return section == null;
        }
    }

    /** The list of components or presets to pick from, filtered as you type. */
    private static final class Picker {
        final Pick pick;
        final LineInput filter = new LineInput("");
        int selected;

        Picker(Pick pick) {
            this.pick = pick;
        }
    }

    /** What a line input edits. */
    sealed interface TextTarget {
        record ConfigDir() implements TextTarget {}

        record WordSet(WordSetKind kind) implements TextTarget {}

        record MaxTokenLength() implements TextTarget {}

        record StemOverride() implements TextTarget {}

        record UserDictionary(Language language) implements TextTarget {}

        record StopTags(Language language) implements TextTarget {}

        record CollationLocale() implements TextTarget {}

        record ShingleSize() implements TextTarget {}

        record ShingleSeparator() implements TextTarget {}

        record ShingleFiller() implements TextTarget {}

        record MaxTokenCount() implements TextTarget {}

        /** A parameter of a component as {@code name=value}; {@code key} is the one it replaces, if any. */
        record Param(Section section, int index, @Nullable String key) implements TextTarget {}
    }

    private static final class TextEdit {
        final TextTarget target;
        final LineInput input;

        @Nullable
        String error;

        TextEdit(TextTarget target, String text) {
            this.target = target;
            this.input = new LineInput(text);
        }
    }

    private final AnalyzerSettings draft;

    /** The presets, and what a custom analyzer can be built from; empty while they are not loaded. */
    private @Nullable GetAnalysisComponentsResult components;

    private int selected;

    /** The open picker, or null. */
    private @Nullable Picker picker;

    /** The open line input, or null. */
    private @Nullable TextEdit textEdit;

    /** Why the draft was refused, until the next change; or null. */
    private @Nullable String error;

    /** Whether no loaded runtime has analysis (ADR 0071). */
    private boolean noAnalysis;

    public AnalyzerForm(AnalyzerSettings settings, @Nullable GetAnalysisComponentsResult components) {
        this.draft = settings.copy();
        this.components = components;
    }

    /** Shows why the backend refused the draft; the popup stays open to fix it. */
    public void setError(String error) {
        this.error = error;
    }

    public void setComponents(GetAnalysisComponentsResult components) {
        this.components = components;
        this.noAnalysis = false;
    }

    /** No loaded runtime has analysis (ADR 0071): the picker says so instead of listing components. */
    public void setNoAnalysis() {
        this.components = null;
        this.noAnalysis = true;
    }

    /** The draft, for the tests. */
    AnalyzerSettings draft() {
        return draft;
    }

    /** Whether a picker is open, for the tests. */
    boolean picking() {
        return picker != null;
    }

    /** The text of the open line input, for the tests. */
    Optional<String> editedText() {
        return textEdit == null ? Optional.empty() : Optional.of(textEdit.input.text());
    }

    /** Why the open line input's text was refused, for the tests. */
    Optional<String> textError() {
        return textEdit == null ? Optional.empty() : Optional.ofNullable(textEdit.error);
    }

    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row.KindRow());
        switch (draft.kind) {
            case STANDARD -> {}
            case PRESET -> {
                rows.add(new Row.Preset());
                for (WordSetKind kind : WordSetKind.values()) {
                    if (support(kind).orElse(null) != WordSetSupport.NONE) {
                        rows.add(new Row.WordSet(kind));
                    }
                }
                if (takes(PresetOption.MAX_TOKEN_LENGTH)) {
                    rows.add(new Row.MaxTokenLength());
                }
                if (takes(PresetOption.STEM_OVERRIDE)) {
                    rows.add(new Row.StemOverride());
                }
                if (takes(PresetOption.JAPANESE)) {
                    rows.add(new Row.UserDictionary(Language.JAPANESE));
                    rows.add(new Row.JapaneseModeRow());
                    rows.add(new Row.StopTags(Language.JAPANESE));
                }
                if (takes(PresetOption.KOREAN)) {
                    rows.add(new Row.UserDictionary(Language.KOREAN));
                    rows.add(new Row.DecompoundMode());
                    rows.add(new Row.StopTags(Language.KOREAN));
                    rows.add(new Row.UnknownWords());
                }
                if (takes(PresetOption.COLLATION)) {
                    rows.add(new Row.CollationLocale());
                    rows.add(new Row.Strength());
                    rows.add(new Row.Decomposition());
                }
                if (takes(PresetOption.JAPANESE_COMPLETION)) {
                    rows.add(new Row.UserDictionary(Language.JAPANESE_COMPLETION));
                    rows.add(new Row.CompletionMode());
                }
            }
            case CUSTOM -> chainRows(rows);
        }
        rows.add(new Row.ShinglesRow());
        if (draft.shingles != null) {
            for (ShingleField field : ShingleField.values()) {
                rows.add(new Row.Shingle(field));
            }
        }
        rows.add(new Row.TokenLimit());
        if (draft.limitTokenCount != null) {
            for (LimitField field : LimitField.values()) {
                rows.add(new Row.Limit(field));
            }
        }
        return rows;
    }

    private void chainRows(List<Row> rows) {
        rows.add(new Row.ConfigDir());
        for (Section section : Section.values()) {
            List<AnalysisComponent> list = section == Section.TOKENIZER ? List.of(draft.tokenizer) : list(section);
            for (int index = 0; index < list.size(); index++) {
                rows.add(new Row.Component(section, index));
                for (int param = 0; param < list.get(index).params().size(); param++) {
                    rows.add(new Row.Param(section, index, param));
                }
            }
            if (section != Section.TOKENIZER) {
                rows.add(new Row.Add(section));
            }
        }
    }

    /** The draft's preset as listed; empty while the presets are not loaded, or for a preset that is not listed. */
    private Optional<AvailableAnalyzer> listedPreset() {
        return Optional.ofNullable(components)
                .flatMap(loaded -> loaded.analyzers().stream()
                        .filter(analyzer -> analyzer.className().equals(draft.preset))
                        .findFirst());
    }

    /** Whether the draft's preset takes the word set; empty while the presets are not loaded, or for a preset that is not listed. */
    private Optional<WordSetSupport> support(WordSetKind kind) {
        return listedPreset()
                .map(preset -> kind == WordSetKind.STOPWORDS ? preset.stopwords() : preset.stemExclusion());
    }

    /**
     * Whether the draft's preset takes the option. Without the list, the backend judges: every preset
     * may take the token length and stem overrides, Kuromoji's and Nori's analyzers, by class name,
     * their options, and an analyzer with {@code Collation} in its name a collator.
     */
    private boolean takes(PresetOption option) {
        Optional<AvailableAnalyzer> preset = listedPreset();
        if (preset.isPresent()) {
            return preset.get().options().contains(option);
        }
        return switch (option) {
            case JAPANESE -> draft.preset.equals(AnalyzerSettings.JAPANESE_ANALYZER);
            case KOREAN -> draft.preset.equals(AnalyzerSettings.KOREAN_ANALYZER);
            case JAPANESE_COMPLETION -> draft.preset.equals(AnalyzerSettings.JAPANESE_COMPLETION_ANALYZER);
            case COLLATION -> simpleName(draft.preset).contains("Collation");
            case MAX_TOKEN_LENGTH, STEM_OVERRIDE -> true;
        };
    }

    void select(Row row) {
        int index = rows().indexOf(row);
        if (index >= 0) {
            selected = index;
        }
    }

    /** The list of char filters or token filters; never the tokenizer's. */
    private List<AnalysisComponent> list(Section section) {
        return section == Section.CHAR_FILTERS ? draft.charFilters : draft.tokenFilters;
    }

    private Optional<AnalysisComponent> component(Section section, int index) {
        if (section == Section.TOKENIZER) {
            return Optional.of(draft.tokenizer);
        }
        List<AnalysisComponent> list = list(section);
        return index < list.size() ? Optional.of(list.get(index)) : Optional.empty();
    }

    private void setComponent(Section section, int index, AnalysisComponent component) {
        if (section == Section.TOKENIZER) {
            draft.tokenizer = component;
        } else {
            list(section).set(index, component);
        }
    }

    /** The parameter keys of a component, in key order. */
    private static List<String> keys(AnalysisComponent component) {
        return new ArrayList<>(new TreeMap<>(component.params()).keySet());
    }

    private void clampSelected() {
        selected = Math.min(selected, rows().size() - 1);
    }

    /**
     * While a picker or a line input is open, it takes the keys. Otherwise ↑↓ select a row; Space or →
     * changes it (standard, preset or custom, ← the other way round; the preset, a word set, an
     * option, the config directory, another component, a parameter; a wrapper on or off); {@code a}
     * adds a filter after the selected one, {@code p} a parameter to the selected component, {@code d}
     * deletes (a word set or an option goes back to its default, a wrapper off), {@code K}/{@code J}
     * move a filter up or down; Enter applies, Esc cancels.
     */
    public Form.Event<AnalyzerSettings> handleKey(KeyEvent key) {
        if (picker != null) {
            pickKey(key);
            return new Form.Event.None<>();
        }
        if (textEdit != null) {
            textKey(key);
            return new Form.Event.None<>();
        }
        List<Row> rows = rows();
        int last = rows.size() - 1;
        Row row = rows.get(Math.min(selected, last));
        switch (key.code()) {
            case ENTER -> {
                return new Form.Event.Apply<>(draft.copy());
            }
            case ESCAPE -> {
                return new Form.Event.Cancel<>();
            }
            case UP -> selected = Math.max(selected - 1, 0);
            case DOWN -> selected = Math.min(selected + 1, last);
            case PAGE_UP -> selected = Math.max(selected - 10, 0);
            case PAGE_DOWN -> selected = Math.min(selected + 10, last);
            case HOME -> selected = 0;
            case END -> selected = last;
            case RIGHT -> change(row, true);
            case LEFT -> change(row, false);
            case DELETE, BACKSPACE -> delete(row);
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'k' -> selected = Math.max(selected - 1, 0);
                    case 'j' -> selected = Math.min(selected + 1, last);
                    case 'g' -> selected = 0;
                    case 'G' -> selected = last;
                    case ' ', 'l' -> change(row, true);
                    case 'h' -> change(row, false);
                    case 'a' -> addAfter(row);
                    case 'p' -> {
                        if (row instanceof Row.Component(Section section, int index)) {
                            editText(new TextTarget.Param(section, index, null));
                        } else if (row instanceof Row.Param(Section section, int index, int _)) {
                            editText(new TextTarget.Param(section, index, null));
                        }
                    }
                    case 'd' -> delete(row);
                    case 'K' -> moveComponent(row, false);
                    case 'J' -> moveComponent(row, true);
                    default -> {}
                }
            }
            default -> {}
        }
        return new Form.Event.None<>();
    }

    private void change(Row row, boolean forward) {
        switch (row) {
            case Row.KindRow _ -> {
                draft.kind = draft.kind.cycled(forward);
                error = null;
            }
            case Row.Preset _ -> picker = new Picker(Pick.PRESET);
            case Row.WordSet(WordSetKind kind) -> editText(new TextTarget.WordSet(kind));
            case Row.ConfigDir _ -> editText(new TextTarget.ConfigDir());
            case Row.Component(Section section, int index) -> pick(section, new Target(false, index));
            case Row.Param(Section section, int index, int param) -> {
                Optional<String> key = component(section, index)
                        .map(AnalyzerForm::keys)
                        .filter(keys -> param < keys.size())
                        .map(keys -> keys.get(param));
                editText(new TextTarget.Param(section, index, key.orElse(null)));
            }
            case Row.Add(Section section) ->
                pick(section, new Target(true, list(section).size()));
            case Row.MaxTokenLength _ -> editText(new TextTarget.MaxTokenLength());
            case Row.StemOverride _ -> editText(new TextTarget.StemOverride());
            case Row.UserDictionary(Language language) -> editText(new TextTarget.UserDictionary(language));
            case Row.StopTags(Language language) -> editText(new TextTarget.StopTags(language));
            case Row.JapaneseModeRow _ -> {
                JapaneseOptions japanese = draft.japanese;
                JapaneseMode mode = cycled(
                        japanese.mode().orElse(null),
                        forward,
                        Arrays.asList(null, JapaneseMode.NORMAL, JapaneseMode.SEARCH, JapaneseMode.EXTENDED));
                draft.japanese =
                        new JapaneseOptions(japanese.userDictionary(), Optional.ofNullable(mode), japanese.stopTags());
                error = null;
            }
            case Row.DecompoundMode _ -> {
                KoreanOptions korean = draft.korean;
                KoreanDecompoundMode mode = cycled(
                        korean.decompoundMode().orElse(null),
                        forward,
                        Arrays.asList(
                                null,
                                KoreanDecompoundMode.NONE,
                                KoreanDecompoundMode.DISCARD,
                                KoreanDecompoundMode.MIXED));
                draft.korean = new KoreanOptions(
                        korean.userDictionary(),
                        Optional.ofNullable(mode),
                        korean.stopTags(),
                        korean.outputUnknownUnigrams());
                error = null;
            }
            case Row.UnknownWords _ -> {
                KoreanOptions korean = draft.korean;
                Boolean split =
                        cycled(korean.outputUnknownUnigrams().orElse(null), forward, Arrays.asList(null, true, false));
                draft.korean = new KoreanOptions(
                        korean.userDictionary(),
                        korean.decompoundMode(),
                        korean.stopTags(),
                        Optional.ofNullable(split));
                error = null;
            }
            case Row.CollationLocale _ -> editText(new TextTarget.CollationLocale());
            case Row.Strength _ -> {
                Collation collation = draft.collation;
                CollationStrength strength = cycled(
                        collation.strength().orElse(null),
                        forward,
                        Arrays.asList(
                                null,
                                CollationStrength.PRIMARY,
                                CollationStrength.SECONDARY,
                                CollationStrength.TERTIARY,
                                CollationStrength.QUATERNARY,
                                CollationStrength.IDENTICAL));
                draft.collation =
                        new Collation(collation.locale(), Optional.ofNullable(strength), collation.decomposition());
                error = null;
            }
            case Row.Decomposition _ -> {
                Collation collation = draft.collation;
                CollationDecomposition decomposition = cycled(
                        collation.decomposition().orElse(null),
                        forward,
                        Arrays.asList(
                                null,
                                CollationDecomposition.NO,
                                CollationDecomposition.CANONICAL,
                                CollationDecomposition.FULL));
                draft.collation =
                        new Collation(collation.locale(), collation.strength(), Optional.ofNullable(decomposition));
                error = null;
            }
            case Row.CompletionMode _ -> {
                JapaneseCompletionOptions completion = draft.japaneseCompletion;
                JapaneseCompletionMode mode = cycled(
                        completion.mode().orElse(null),
                        forward,
                        Arrays.asList(null, JapaneseCompletionMode.INDEX, JapaneseCompletionMode.QUERY));
                draft.japaneseCompletion =
                        new JapaneseCompletionOptions(completion.userDictionary(), Optional.ofNullable(mode));
                error = null;
            }
            case Row.ShinglesRow _ -> {
                draft.shingles = draft.shingles != null ? null : DEFAULT_SHINGLES;
                error = null;
            }
            case Row.Shingle(ShingleField field) -> {
                switch (field) {
                    case SIZE -> editText(new TextTarget.ShingleSize());
                    case SEPARATOR -> editText(new TextTarget.ShingleSeparator());
                    case FILLER -> editText(new TextTarget.ShingleFiller());
                    case UNIGRAMS -> {
                        if (draft.shingles != null) {
                            Shingles s = draft.shingles;
                            // Always, never, or only without shingles.
                            String unigrams = cycled(
                                    unigramsKey(s.outputUnigrams(), s.outputUnigramsIfNoShingles()),
                                    forward,
                                    List.of("always", "never", "without"));
                            draft.shingles = new Shingles(
                                    s.minShingleSize(),
                                    s.maxShingleSize(),
                                    s.tokenSeparator(),
                                    unigrams.equals("always"),
                                    unigrams.equals("without"),
                                    s.fillerToken());
                            error = null;
                        }
                    }
                }
            }
            case Row.TokenLimit _ -> {
                draft.limitTokenCount =
                        draft.limitTokenCount != null ? null : new LimitTokenCount(DEFAULT_MAX_TOKEN_COUNT, false);
                error = null;
            }
            case Row.Limit(LimitField field) -> {
                if (field == LimitField.MAX_TOKENS) {
                    editText(new TextTarget.MaxTokenCount());
                } else if (draft.limitTokenCount != null) {
                    LimitTokenCount limit = draft.limitTokenCount;
                    draft.limitTokenCount = new LimitTokenCount(limit.maxTokenCount(), !limit.consumeAllTokens());
                    error = null;
                }
            }
        }
    }

    /** The three ways of keeping unigrams, by name; any other pair counts as {@code always}. */
    private static String unigramsKey(boolean outputUnigrams, boolean ifNoShingles) {
        if (outputUnigrams) {
            return ifNoShingles ? "other" : "always";
        }
        return ifNoShingles ? "without" : "never";
    }

    /** Adds a filter after the selected one, or at the end of the selected section. */
    private void addAfter(Row row) {
        switch (row) {
            case Row.Component(Section section, int index)
            when section != Section.TOKENIZER -> pick(section, new Target(true, index + 1));
            case Row.Param(Section section, int index, int _)
            when section != Section.TOKENIZER -> pick(section, new Target(true, index + 1));
            case Row.Add _ -> change(row, true);
            default -> {}
        }
    }

    private void pick(Section section, Target target) {
        picker = new Picker(new Pick(section, target));
    }

    private void editText(TextTarget target) {
        String text = switch (target) {
            case TextTarget.ConfigDir _ -> Objects.requireNonNullElse(draft.configDir, "");
            case TextTarget.WordSet(WordSetKind kind) ->
                AnalyzerSettings.wordSetInput(draft.wordSet(kind).orElse(null));
            case TextTarget.Param(Section section, int index, String key) ->
                key == null
                        ? ""
                        : key + "="
                                + component(section, index)
                                        .map(c -> c.params().getOrDefault(key, ""))
                                        .orElse("");
            case TextTarget.MaxTokenLength _ ->
                draft.maxTokenLength != null ? Integer.toString(draft.maxTokenLength) : "";
            case TextTarget.StemOverride _ -> AnalyzerSettings.stemDictionaryInput(draft.stemOverride);
            case TextTarget.UserDictionary(Language language) ->
                draft.userDictionary(language).orElse("");
            case TextTarget.StopTags(Language language) ->
                AnalyzerSettings.wordSetInput(draft.stopTags(language).orElse(null));
            case TextTarget.CollationLocale _ -> draft.collation.locale();
            case TextTarget.ShingleSize _ ->
                Optional.ofNullable(draft.shingles)
                        .map(s -> s.minShingleSize() == s.maxShingleSize()
                                ? Integer.toString(s.minShingleSize())
                                : s.minShingleSize() + "-" + s.maxShingleSize())
                        .orElse("");
            case TextTarget.ShingleSeparator _ -> draft.shingles == null ? "" : draft.shingles.tokenSeparator();
            case TextTarget.ShingleFiller _ -> draft.shingles == null ? "" : draft.shingles.fillerToken();
            case TextTarget.MaxTokenCount _ ->
                draft.limitTokenCount == null ? "" : Integer.toString(draft.limitTokenCount.maxTokenCount());
        };
        textEdit = new TextEdit(target, text);
    }

    private void delete(Row row) {
        switch (row) {
            case Row.ConfigDir _ -> draft.configDir = null;
            case Row.WordSet(WordSetKind kind) -> draft.setWordSet(kind, null);
            case Row.Component(Section section, int index) -> {
                if (section == Section.TOKENIZER) {
                    return;
                }
                list(section).remove(index);
                // The selection stays on the row that moved up, or the add row.
                clampSelected();
            }
            case Row.Param(Section section, int index, int param) -> {
                Optional<AnalysisComponent> component = component(section, index);
                if (component.isPresent()) {
                    List<String> keys = keys(component.get());
                    if (param < keys.size()) {
                        TreeMap<String, String> params =
                                new TreeMap<>(component.get().params());
                        params.remove(keys.get(param));
                        setComponent(
                                section,
                                index,
                                new AnalysisComponent(component.get().name(), params));
                    }
                }
                clampSelected();
            }
            case Row.MaxTokenLength _ -> draft.maxTokenLength = null;
            case Row.StemOverride _ -> draft.stemOverride = null;
            case Row.UserDictionary(Language language) -> draft.setUserDictionary(language, null);
            case Row.StopTags(Language language) -> draft.setStopTags(language, null);
            case Row.JapaneseModeRow _ ->
                draft.japanese = new JapaneseOptions(
                        draft.japanese.userDictionary(), Optional.empty(), draft.japanese.stopTags());
            case Row.DecompoundMode _ ->
                draft.korean = new KoreanOptions(
                        draft.korean.userDictionary(),
                        Optional.empty(),
                        draft.korean.stopTags(),
                        draft.korean.outputUnknownUnigrams());
            case Row.UnknownWords _ ->
                draft.korean = new KoreanOptions(
                        draft.korean.userDictionary(),
                        draft.korean.decompoundMode(),
                        draft.korean.stopTags(),
                        Optional.empty());
            case Row.CollationLocale _ ->
                draft.collation = new Collation("", draft.collation.strength(), draft.collation.decomposition());
            case Row.Strength _ ->
                draft.collation =
                        new Collation(draft.collation.locale(), Optional.empty(), draft.collation.decomposition());
            case Row.Decomposition _ ->
                draft.collation = new Collation(draft.collation.locale(), draft.collation.strength(), Optional.empty());
            case Row.CompletionMode _ ->
                draft.japaneseCompletion =
                        new JapaneseCompletionOptions(draft.japaneseCompletion.userDictionary(), Optional.empty());
            case Row.ShinglesRow _ -> {
                draft.shingles = null;
                clampSelected();
            }
            case Row.Shingle(ShingleField field) -> {
                // Back to Lucene's default.
                if (draft.shingles == null) {
                    return;
                }
                Shingles s = draft.shingles;
                Shingles d = DEFAULT_SHINGLES;
                draft.shingles = switch (field) {
                    case SIZE ->
                        new Shingles(
                                d.minShingleSize(),
                                d.maxShingleSize(),
                                s.tokenSeparator(),
                                s.outputUnigrams(),
                                s.outputUnigramsIfNoShingles(),
                                s.fillerToken());
                    case SEPARATOR ->
                        new Shingles(
                                s.minShingleSize(),
                                s.maxShingleSize(),
                                d.tokenSeparator(),
                                s.outputUnigrams(),
                                s.outputUnigramsIfNoShingles(),
                                s.fillerToken());
                    case UNIGRAMS ->
                        new Shingles(
                                s.minShingleSize(),
                                s.maxShingleSize(),
                                s.tokenSeparator(),
                                d.outputUnigrams(),
                                d.outputUnigramsIfNoShingles(),
                                s.fillerToken());
                    case FILLER ->
                        new Shingles(
                                s.minShingleSize(),
                                s.maxShingleSize(),
                                s.tokenSeparator(),
                                s.outputUnigrams(),
                                s.outputUnigramsIfNoShingles(),
                                d.fillerToken());
                };
            }
            case Row.TokenLimit _ -> {
                draft.limitTokenCount = null;
                clampSelected();
            }
            case Row.Limit(LimitField field) -> {
                if (draft.limitTokenCount == null) {
                    return;
                }
                LimitTokenCount limit = draft.limitTokenCount;
                draft.limitTokenCount = field == LimitField.MAX_TOKENS
                        ? new LimitTokenCount(DEFAULT_MAX_TOKEN_COUNT, limit.consumeAllTokens())
                        : new LimitTokenCount(limit.maxTokenCount(), false);
            }
            case Row.KindRow _, Row.Preset _, Row.Add _ -> {
                return;
            }
        }
        error = null;
    }

    private void moveComponent(Row row, boolean down) {
        Section section;
        int index;
        if (row instanceof Row.Component(Section s, int i)) {
            section = s;
            index = i;
        } else if (row instanceof Row.Param(Section s, int i, int _)) {
            section = s;
            index = i;
        } else {
            return;
        }
        if (section == Section.TOKENIZER) {
            return;
        }
        List<AnalysisComponent> list = list(section);
        int to = down ? index + 1 : index - 1;
        if (to < 0 || to >= list.size()) {
            return;
        }
        AnalysisComponent moved = list.get(index);
        list.set(index, list.get(to));
        list.set(to, moved);
        select(new Row.Component(section, to));
        error = null;
    }

    /** The presets, or the components of the picker's section, whose name contains the filter, ignoring case. */
    private List<Choice> matches(Picker open) {
        if (components == null) {
            return List.of();
        }
        String filter = open.filter.text().toLowerCase(Locale.ROOT);
        List<Choice> choices = open.pick.preset()
                ? components.analyzers().stream()
                        .map(analyzer -> new Choice(analyzer.name(), analyzer.className()))
                        .toList()
                : Objects.requireNonNull(open.pick.section()).available(components);
        return choices.stream()
                .filter(choice -> choice.name().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
    }

    /**
     * ↑↓ select, typing filters, Enter takes the selected component or preset (or the typed name, or
     * class name, if none matches), Esc goes back.
     */
    private void pickKey(KeyEvent key) {
        Picker open = picker;
        if (open == null) {
            return;
        }
        List<Choice> matches = matches(open);
        int last = Math.max(matches.size() - 1, 0);
        switch (key.code()) {
            case ESCAPE -> picker = null;
            case UP -> open.selected = Math.max(open.selected - 1, 0);
            case DOWN -> open.selected = Math.min(open.selected + 1, last);
            case PAGE_UP -> open.selected = Math.max(open.selected - 10, 0);
            case PAGE_DOWN -> open.selected = Math.min(open.selected + 10, last);
            case ENTER -> {
                Pick pick = open.pick;
                String name = matches.isEmpty()
                        ? open.filter.text().strip()
                        : pick.preset()
                                ? matches.get(Math.min(open.selected, last)).className()
                                : matches.get(Math.min(open.selected, last)).name();
                if (name.isEmpty()) {
                    return;
                }
                picker = null;
                if (!pick.preset()) {
                    put(Objects.requireNonNull(pick.section()), Objects.requireNonNull(pick.target()), name);
                    return;
                }
                draft.preset = name;
                // A set or an option that the new preset doesn't take would fail.
                for (WordSetKind kind : WordSetKind.values()) {
                    if (support(kind).orElse(null) == WordSetSupport.NONE) {
                        draft.setWordSet(kind, null);
                    }
                }
                if (!takes(PresetOption.MAX_TOKEN_LENGTH)) {
                    draft.maxTokenLength = null;
                }
                if (!takes(PresetOption.STEM_OVERRIDE)) {
                    draft.stemOverride = null;
                }
                if (!takes(PresetOption.JAPANESE)) {
                    draft.japanese = AnalyzerSettings.noJapanese();
                }
                if (!takes(PresetOption.KOREAN)) {
                    draft.korean = AnalyzerSettings.noKorean();
                }
                if (!takes(PresetOption.COLLATION)) {
                    draft.collation = AnalyzerSettings.noCollation();
                }
                if (!takes(PresetOption.JAPANESE_COMPLETION)) {
                    draft.japaneseCompletion = AnalyzerSettings.noJapaneseCompletion();
                }
                error = null;
            }
            default -> {
                if (open.filter.handleKey(key, LineInput::wordStart) == LineInput.Edit.CHANGED) {
                    open.selected = 0;
                }
            }
        }
    }

    /** Puts the component {@code name}, without parameters, where the picker was opened for. */
    private void put(Section section, Target target, String name) {
        AnalysisComponent component = AnalyzerSettings.component(name);
        int index;
        if (section == Section.TOKENIZER) {
            draft.tokenizer = component;
            index = 0;
        } else if (!target.insert()) {
            index = target.index();
            if (index < list(section).size()) {
                list(section).set(index, component);
            }
        } else {
            index = Math.min(target.index(), list(section).size());
            list(section).add(index, component);
        }
        select(new Row.Component(section, index));
        error = null;
    }

    /** Enter takes the text, Esc goes back; any other key edits it. */
    private void textKey(KeyEvent key) {
        TextEdit edit = textEdit;
        if (edit == null) {
            return;
        }
        switch (key.code()) {
            case ESCAPE -> textEdit = null;
            case ENTER -> {
                try {
                    takeText(edit.target, edit.input.text());
                    textEdit = null;
                    error = null;
                } catch (IllegalArgumentException e) {
                    edit.error = e.getMessage();
                }
            }
            default -> edit.input.handleKey(key, LineInput::wordStart);
        }
    }

    /**
     * Applies an edited text: a config directory or a user dictionary (none if blank), a word set,
     * stop tags, stem overrides or the token length (the default if blank), a shingle size or token
     * count (the default if blank), a shingle separator or filler (as typed), or a parameter as {@code
     * name=value} (removed if blank).
     *
     * @throws IllegalArgumentException with the message to show
     */
    private void takeText(TextTarget target, String typed) {
        String text = typed.strip();
        switch (target) {
            case TextTarget.ConfigDir _ -> draft.configDir = text.isEmpty() ? null : text;
            case TextTarget.WordSet(WordSetKind kind) ->
                draft.setWordSet(kind, AnalyzerSettings.parseWordSet(text).orElse(null));
            case TextTarget.MaxTokenLength _ ->
                draft.maxTokenLength = Optionals.orNull(AnalyzerSettings.parseNumber(text, 1, MAX_TOKEN_LENGTH));
            case TextTarget.StemOverride _ ->
                draft.stemOverride = AnalyzerSettings.parseStemDictionary(text).orElse(null);
            case TextTarget.UserDictionary(Language language) ->
                draft.setUserDictionary(language, text.isEmpty() ? null : text);
            case TextTarget.StopTags(Language language) ->
                draft.setStopTags(language, AnalyzerSettings.parseWordSet(text).orElse(null));
            case TextTarget.CollationLocale _ ->
                draft.collation = new Collation(text, draft.collation.strength(), draft.collation.decomposition());
            case TextTarget.ShingleSize _ -> {
                int[] size = AnalyzerSettings.parseShingleSize(text)
                        .orElse(new int[] {DEFAULT_SHINGLES.minShingleSize(), DEFAULT_SHINGLES.maxShingleSize()});
                draft.shingles = Optional.ofNullable(draft.shingles)
                        .map(s -> new Shingles(
                                size[0],
                                size[1],
                                s.tokenSeparator(),
                                s.outputUnigrams(),
                                s.outputUnigramsIfNoShingles(),
                                s.fillerToken()))
                        .orElse(null);
            }
            case TextTarget.ShingleSeparator _ ->
                draft.shingles = Optional.ofNullable(draft.shingles)
                        .map(s -> new Shingles(
                                s.minShingleSize(),
                                s.maxShingleSize(),
                                typed,
                                s.outputUnigrams(),
                                s.outputUnigramsIfNoShingles(),
                                s.fillerToken()))
                        .orElse(null);
            case TextTarget.ShingleFiller _ ->
                draft.shingles = Optional.ofNullable(draft.shingles)
                        .map(s -> new Shingles(
                                s.minShingleSize(),
                                s.maxShingleSize(),
                                s.tokenSeparator(),
                                s.outputUnigrams(),
                                s.outputUnigramsIfNoShingles(),
                                typed))
                        .orElse(null);
            case TextTarget.MaxTokenCount _ -> {
                OptionalInt count = AnalyzerSettings.parseNumber(text, 1, Integer.MAX_VALUE);
                draft.limitTokenCount = Optional.ofNullable(draft.limitTokenCount)
                        .map(limit ->
                                new LimitTokenCount(count.orElse(DEFAULT_MAX_TOKEN_COUNT), limit.consumeAllTokens()))
                        .orElse(null);
            }
            case TextTarget.Param(Section section, int index, String old) -> takeParam(section, index, old, text);
        }
    }

    private void takeParam(Section section, int index, @Nullable String old, String text) {
        String key = null;
        String value = null;
        if (!text.isEmpty()) {
            int equals = text.indexOf('=');
            if (equals < 0 || text.substring(0, equals).isBlank()) {
                throw new IllegalArgumentException("Write the parameter as name=value.");
            }
            key = text.substring(0, equals).strip();
            value = text.substring(equals + 1).strip();
        }
        Optional<AnalysisComponent> component = component(section, index);
        if (component.isEmpty()) {
            return;
        }
        TreeMap<String, String> params = new TreeMap<>(component.get().params());
        if (old != null) {
            params.remove(old);
        }
        if (key != null) {
            params.put(key, value);
        }
        setComponent(section, index, new AnalysisComponent(component.get().name(), params));
        if (key == null) {
            clampSelected();
            return;
        }
        select(new Row.Param(section, index, new ArrayList<>(params.keySet()).indexOf(key)));
    }

    /** The keys for the footer. */
    public String keys() {
        if (picker != null) {
            return " type to filter   ↑↓ select   ⏎ take   Esc back";
        }
        if (textEdit != null) {
            return " ⏎ take   Esc back   ←→ Ctrl+A/E/U/K/W edit";
        }
        return switch (draft.kind) {
            case CUSTOM ->
                " ␣ change   a add filter   p add parameter   d delete   K/J move   ⏎ apply   Esc cancel   ↑↓";
            case PRESET -> " ␣ change   d default   ←→ standard/preset/custom   ⏎ apply   Esc cancel   ↑↓";
            case STANDARD -> " ␣ change   ←→ standard/preset/custom   d off   ⏎ apply   Esc cancel   ↑↓";
        };
    }

    public void render(Frame frame, Rect area) {
        List<Row> rows = rows();
        int shownRow = Math.min(selected, rows.size() - 1);
        List<Line> lines = new ArrayList<>();
        int selectedLine = 0;
        String lastHeading = null;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String heading = switch (row) {
                case Row.Component c -> c.section().heading;
                case Row.Add add -> add.section().heading;
                case Row.ShinglesRow _ -> "Wrappers";
                default -> null;
            };
            if (heading != null && !heading.equals(lastHeading)) {
                lastHeading = heading;
                lines.add(Line.empty());
                lines.add(Line.styled(heading, Palette.current().heading()));
            }
            if (i == shownRow) {
                selectedLine = lines.size();
            }
            Line line = rowLine(row);
            lines.add(i == shownRow ? line.patchStyle(Palette.current().selection()) : line);
        }
        List<String> notes = new ArrayList<>(
                switch (draft.kind) {
                    case STANDARD ->
                        List.of(
                                "Lucene's StandardAnalyzer: the standard tokenizer, then lowercase.",
                                "Space switches to a preset analyzer, such as EnglishAnalyzer, or to a custom analyzer of "
                                        + "your own chain.");
                    case PRESET ->
                        List.of(
                                "One of Lucene's analyzers, by class name, with its default settings.",
                                "Stop words and stem exclusions, for an analyzer that takes them: words separated by "
                                        + "spaces or commas, - for no words, or @ and a word list file (UTF-8, one word "
                                        + "per line, # for comments).");
                    case CUSTOM -> List.<String>of();
                });
        if (rows.contains(new Row.StemOverride())) {
            notes.add("Stem overrides: word=stem pairs, - for none, or @ and a file of word<TAB>stem lines.");
        }
        if (rows.contains(new Row.CollationLocale())) {
            notes.add("Locale: a BCP 47 language tag; ICU's collators also read its -u- keywords, such as "
                    + "de-u-co-phonebk. Quaternary strength is ICU's only, full decomposition the JDK's only.");
        }
        if (rows.stream().anyMatch(row -> row instanceof Row.StopTags)) {
            notes.add("Stop tags like stop words. A user dictionary is a file in Kuromoji's or Nori's own format.");
        } else if (rows.stream().anyMatch(row -> row instanceof Row.UserDictionary)) {
            notes.add("A user dictionary is a file in Kuromoji's own format.");
        }
        int width = Math.max(Math.min(area.width(), WIDTH) - 2, 1);
        if (!notes.isEmpty()) {
            lines.add(Line.empty());
            for (String note : notes) {
                for (String line : TextFormat.wrap(note, width, Integer.MAX_VALUE)) {
                    lines.add(Line.styled(line, Form.muted()));
                }
            }
        }
        if (error != null) {
            lines.add(Line.empty());
            for (String line : TextFormat.wrap(error, width, Integer.MAX_VALUE)) {
                lines.add(Line.styled(line, Palette.current().error()));
            }
            selectedLine = lines.size() - 1;
        }
        Form.renderPopup(frame, area, " Analyzer ", lines, selectedLine, WIDTH);
        if (picker != null) {
            renderPicker(frame, area, picker);
        } else if (textEdit != null) {
            renderText(frame, area, textEdit);
        }
    }

    /** A row's label, padded, then its value, or the default dimmed. */
    private static Line valueLine(String label, @Nullable String value, String fallback) {
        String padded = Form.padded(label, Math.max(LABEL, label.length()));
        return value != null
                ? Line.from(padded + value)
                : Line.from(Span.raw(padded), Span.styled(fallback, Form.muted()));
    }

    private Line rowLine(Row row) {
        return switch (row) {
            case Row.KindRow _ -> Line.from("Analyzer        " + draft.kind.name);
            case Row.Preset _ ->
                Line.from(
                        Span.raw("Class           " + TextFormat.escapeControl(simpleName(draft.preset)) + "  "),
                        Span.styled(TextFormat.escapeControl(packageOf(draft.preset)), Form.muted()));
            case Row.WordSet(WordSetKind kind) -> {
                String label = Form.padded(kind.label, LABEL);
                if (draft.wordSet(kind).isPresent()) {
                    yield Line.from(label + wordSetText(draft.wordSet(kind).get()));
                }
                String text;
                Style style = Form.muted();
                if (kind == WordSetKind.STOPWORDS && support(kind).orElse(null) == WordSetSupport.REQUIRED) {
                    text = "none: this analyzer needs some";
                    style = Palette.current().error();
                } else if (kind == WordSetKind.STOPWORDS) {
                    text = "default: the analyzer's own";
                } else {
                    text = "none";
                }
                yield Line.from(Span.raw(label), Span.styled(text, style));
            }
            case Row.MaxTokenLength _ ->
                valueLine(
                        "Token length",
                        draft.maxTokenLength != null ? Integer.toString(draft.maxTokenLength) : null,
                        "default: the analyzer's own");
            case Row.StemOverride _ ->
                valueLine(
                        "Stem overrides",
                        draft.stemOverride == null ? null : AnalyzerSettings.stemDictionaryText(draft.stemOverride),
                        "default: the analyzer's own");
            case Row.UserDictionary(Language language) ->
                valueLine(
                        "User dictionary",
                        draft.userDictionary(language)
                                .map(TextFormat::escapeControl)
                                .orElse(null),
                        "none");
            case Row.JapaneseModeRow _ ->
                valueLine(
                        "Mode",
                        draft.japanese
                                .mode()
                                .map(AnalyzerSettings::japaneseModeName)
                                .orElse(null),
                        "default: search");
            case Row.DecompoundMode _ ->
                valueLine(
                        "Decompound",
                        draft.korean
                                .decompoundMode()
                                .map(AnalyzerSettings::decompoundModeName)
                                .orElse(null),
                        "default: discard");
            case Row.StopTags(Language language) ->
                valueLine(
                        "Stop tags",
                        draft.stopTags(language)
                                .map(AnalyzerSettings::wordSetText)
                                .orElse(null),
                        "default: the analyzer's own");
            case Row.UnknownWords _ ->
                valueLine(
                        "Unknown words",
                        draft.korean
                                .outputUnknownUnigrams()
                                .map(AnalyzerSettings::unknownWordsText)
                                .orElse(null),
                        "default: kept whole");
            case Row.CollationLocale _ ->
                valueLine(
                        "Locale",
                        draft.collation.locale().isEmpty() ? null : TextFormat.escapeControl(draft.collation.locale()),
                        "root");
            case Row.Strength _ ->
                valueLine(
                        "Strength",
                        draft.collation
                                .strength()
                                .map(AnalyzerSettings::strengthName)
                                .orElse(null),
                        "default: the collator's own");
            case Row.Decomposition _ ->
                valueLine(
                        "Decomposition",
                        draft.collation
                                .decomposition()
                                .map(AnalyzerSettings::decompositionName)
                                .orElse(null),
                        "default: the collator's own");
            case Row.CompletionMode _ ->
                valueLine(
                        "Mode",
                        draft.japaneseCompletion
                                .mode()
                                .map(AnalyzerSettings::completionModeName)
                                .orElse(null),
                        "default: index");
            case Row.ShinglesRow _ -> valueLine("Shingles", draft.shingles != null ? "on" : null, "off");
            case Row.Shingle(ShingleField field) -> {
                if (draft.shingles == null) {
                    yield Line.empty();
                }
                Shingles s = draft.shingles;
                yield switch (field) {
                    case SIZE -> valueLine("  size", shingleSizeText(s), "");
                    case SEPARATOR -> valueLine("  separator", quoted(s.tokenSeparator()), "");
                    case UNIGRAMS -> valueLine("  unigrams", unigramsText(s), "");
                    case FILLER -> valueLine("  filler", quoted(s.fillerToken()), "");
                };
            }
            case Row.TokenLimit _ -> valueLine("Token limit", draft.limitTokenCount != null ? "on" : null, "off");
            case Row.Limit(LimitField field) -> {
                if (draft.limitTokenCount == null) {
                    yield Line.empty();
                }
                LimitTokenCount limit = draft.limitTokenCount;
                yield field == LimitField.MAX_TOKENS
                        ? valueLine("  max tokens", Integer.toString(limit.maxTokenCount()), "")
                        : valueLine("  consume all", limit.consumeAllTokens() ? "yes" : "no", "");
            }
            case Row.ConfigDir _ ->
                draft.configDir != null
                        ? Line.from("Config dir      " + TextFormat.escapeControl(draft.configDir))
                        : Line.from(
                                Span.raw("Config dir      "),
                                Span.styled("none: resources from the classpath", Form.muted()));
            case Row.Component(Section section, int index) ->
                Line.from("  "
                        + component(section, index)
                                .map(c -> TextFormat.escapeControl(c.name()))
                                .orElse(""));
            case Row.Param(Section section, int index, int param) -> {
                Optional<AnalysisComponent> component = component(section, index);
                String key = "";
                String value = "";
                if (component.isPresent()) {
                    List<String> keys = keys(component.get());
                    if (param < keys.size()) {
                        key = TextFormat.escapeControl(keys.get(param));
                        value = TextFormat.escapeControl(Objects.requireNonNullElse(
                                component.get().params().get(keys.get(param)), ""));
                    }
                }
                yield Line.from("      " + key + " = " + value);
            }
            case Row.Add(Section section) -> Line.styled("  + " + section.noun, Form.muted());
        };
    }

    private void renderPicker(Frame frame, Rect area, Picker open) {
        List<Choice> matches = matches(open);
        int shown = Math.min(open.selected, Math.max(matches.size() - 1, 0));
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from("Filter: " + open.filter.text()));
        lines.add(Line.empty());
        String typed = open.pick.preset() ? "class name" : "name";
        if (noAnalysis) {
            lines.add(Line.from(Analysis.NO_ANALYSIS + "."));
        } else if (components == null) {
            lines.add(Line.styled("The components are not loaded; Enter takes the typed " + typed + ".", Form.muted()));
        } else if (matches.isEmpty()) {
            lines.add(Line.styled("No match; Enter takes the typed " + typed + ".", Form.muted()));
        }
        for (int i = 0; i < matches.size(); i++) {
            Choice choice = matches.get(i);
            // A component's factory class, or a preset's package.
            String detail = open.pick.preset() ? packageOf(choice.className()) : simpleName(choice.className());
            String name = TextFormat.escapeControl(choice.name());
            Line line = Line.from(
                    Span.raw(Form.padded(name, Math.max(28, name.codePointCount(0, name.length()))) + " "),
                    Span.styled(TextFormat.escapeControl(detail), Form.muted()));
            lines.add(i == shown ? line.patchStyle(Palette.current().selection()) : line);
        }
        String title = open.pick.preset()
                ? " Pick a preset analyzer "
                : " Pick a " + Objects.requireNonNull(open.pick.section()).noun + " ";
        Form.renderPopup(frame, area, title, lines, shown + 2, WIDTH - 8);
    }

    /** A one-line input in a popup of its own, with the cursor. */
    private static void renderText(Frame frame, Rect area, TextEdit edit) {
        String title = switch (edit.target) {
            case TextTarget.ConfigDir _ -> " Config directory (blank for none) ";
            case TextTarget.WordSet(WordSetKind kind) ->
                kind == WordSetKind.STOPWORDS
                        ? " Stop words: words, - for none, or @file (blank for the default) "
                        : " Stem exclusions: words, or @file (blank for none) ";
            case TextTarget.Param _ -> " Parameter: name=value (blank removes it) ";
            case TextTarget.MaxTokenLength _ -> " Token length: 1 to 1048576 (blank for the default) ";
            case TextTarget.StemOverride _ ->
                " Stem overrides: word=stem …, - for none, or @file (blank for the default) ";
            case TextTarget.UserDictionary _ -> " User dictionary file (blank for none) ";
            case TextTarget.StopTags _ -> " Stop tags: tags, - for none, or @file (blank for the default) ";
            case TextTarget.CollationLocale _ -> " Locale: a language tag, such as de or de-AT (blank for root) ";
            case TextTarget.ShingleSize _ -> " Shingle size: min-max, such as 2-3 (blank for 2) ";
            case TextTarget.ShingleSeparator _ -> " Token separator, as typed ";
            case TextTarget.ShingleFiller _ -> " Filler token, as typed ";
            case TextTarget.MaxTokenCount _ -> " Max tokens (blank for 10000) ";
        };
        int width = Math.min(area.width(), WIDTH - 4);
        int height = Math.min(edit.error != null ? 4 : 3, area.height());
        Rect popup =
                new Rect(area.x() + (area.width() - width) / 2, area.y() + (area.height() - height) / 2, width, height);
        Block block = Panels.box(title);
        Rect inner = block.inner(popup);
        Panels.clear(frame, popup);
        frame.renderWidget(block, popup);
        LineInput.Visible visible = edit.input.visible(inner.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), new Rect(inner.x(), inner.y(), inner.width(), 1));
        if (edit.error != null && inner.height() > 1) {
            frame.renderWidget(
                    Paragraph.from(Text.from(Panels.wrap(
                            List.of(Line.styled(edit.error, Palette.current().error())), inner.width()))),
                    new Rect(inner.x(), inner.y() + 1, inner.width(), 1));
        }
        frame.setCursorPosition(inner.x() + visible.cursorColumn(), inner.y());
    }
}
