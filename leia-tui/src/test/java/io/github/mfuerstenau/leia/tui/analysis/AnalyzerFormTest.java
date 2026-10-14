package io.github.mfuerstenau.leia.tui.analysis;

import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationDecomposition;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseCompletionMode;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseCompletionOptions;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseMode;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseOptions;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanDecompoundMode;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanOptions;
import io.github.mfuerstenau.leia.protocol.analysis.LimitTokenCount;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.PresetOption;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.StemDictionary;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.protocol.analysis.WordSetSupport;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerForm.LimitField;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerForm.Row;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerForm.Section;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerForm.ShingleField;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.Kind;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.Language;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.WordSetKind;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The tests of {@code analyzer.rs}, ported. */
class AnalyzerFormTest {

    private static final Row STOPWORDS = new Row.WordSet(WordSetKind.STOPWORDS);
    private static final Row STEM_EXCLUSION = new Row.WordSet(WordSetKind.STEM_EXCLUSION);

    private static Form.Event<AnalyzerSettings> press(AnalyzerForm form, KeyEvent key) {
        return form.handleKey(key);
    }

    private static void typed(AnalyzerForm form, String text) {
        text.codePoints()
                .forEach(c -> assertThat(press(form, KeyEvent.ofChar(c))).isEqualTo(new Form.Event.None<>()));
    }

    private static List<AvailableComponent> available(String... names) {
        return List.of(names).stream()
                .map(name -> new AvailableComponent(name, "org.apache.lucene.analysis." + name + "Factory"))
                .toList();
    }

    private static AvailableAnalyzer analyzer(String name, WordSetSupport stopwords, WordSetSupport stemExclusion) {
        List<PresetOption> options = switch (name) {
            case "WhitespaceAnalyzer" -> List.of(PresetOption.MAX_TOKEN_LENGTH);
            case "DutchAnalyzer" -> List.of(PresetOption.STEM_OVERRIDE);
            case "JapaneseAnalyzer" -> List.of(PresetOption.JAPANESE);
            case "KoreanAnalyzer" -> List.of(PresetOption.KOREAN);
            case "CollationKeyAnalyzer" -> List.of(PresetOption.COLLATION);
            case "JapaneseCompletionAnalyzer" -> List.of(PresetOption.JAPANESE_COMPLETION);
            default -> List.of();
        };
        return new AvailableAnalyzer(name, "org.apache.lucene.analysis.x." + name, stopwords, stemExclusion, options);
    }

    static GetAnalysisComponentsResult components() {
        WordSetSupport optional = WordSetSupport.OPTIONAL;
        WordSetSupport none = WordSetSupport.NONE;
        return new GetAnalysisComponentsResult(
                List.of(
                        analyzer("EnglishAnalyzer", optional, optional),
                        analyzer("GermanAnalyzer", optional, optional),
                        analyzer("StopAnalyzer", WordSetSupport.REQUIRED, none),
                        analyzer("WhitespaceAnalyzer", none, none),
                        analyzer("DutchAnalyzer", optional, optional),
                        analyzer("JapaneseAnalyzer", optional, none),
                        analyzer("KoreanAnalyzer", none, none),
                        analyzer("CollationKeyAnalyzer", none, none),
                        analyzer("JapaneseCompletionAnalyzer", none, none)),
                available("htmlStrip", "mapping"),
                available("keyword", "standard", "whitespace"),
                available("asciiFolding", "lowercase", "stop", "stemmerOverride"));
    }

    /** ← cycles back from standard to custom. */
    private static AnalyzerForm customForm() {
        AnalyzerForm form = new AnalyzerForm(new AnalyzerSettings(), Optional.of(components()));
        press(form, key(KeyCode.LEFT));
        return form;
    }

    private static AnalyzerSettings applied(AnalyzerForm form) {
        Form.Event<AnalyzerSettings> event = press(form, key(KeyCode.ENTER));
        assertThat(event).isInstanceOf(Form.Event.Apply.class);
        return ((Form.Event.Apply<AnalyzerSettings>) event).draft();
    }

    private static String render(AnalyzerForm form) {
        return TestScreens.render(frame -> form.render(frame, frame.area()), 80, 24);
    }

    @Test
    void theStandardAnalyzerIsTheDefault() {
        AnalyzerSettings settings = new AnalyzerSettings();
        assertThat(settings.analyzer().standard()).isPresent();
        assertThat(settings.analyzer().custom()).isEmpty();
        assertThat(settings.text()).isEqualTo("standard analyzer");
        assertThat(settings.isCustom()).isFalse();
        assertThat(settings.isStandard()).isTrue();
    }

    @Test
    void leftSwitchesToTheCustomChainOfTheStandardAnalyzer() {
        AnalyzerSettings settings = applied(customForm());
        assertThat(settings.isCustom()).isTrue();
        assertThat(settings.text()).isEqualTo("standard › lowercase");
        CustomAnalyzer custom = settings.analyzer().custom().orElseThrow();
        assertThat(custom.tokenizer().name()).isEqualTo("standard");
        assertThat(custom.tokenFilters().getFirst().name()).isEqualTo("lowercase");
        assertThat(settings.analyzer().standard()).isEmpty();
    }

    @Test
    void addsFiltersFromTheFilteredList() {
        AnalyzerForm form = customForm();
        // Kind, config dir, + char filter, standard, lowercase, + token filter, then the wrappers.
        form.select(new Row.Add(Section.TOKEN_FILTERS));
        press(form, key(' '));
        typed(form, "st");
        // "stop" and "stemmerOverride" match; ↓ selects the second.
        press(form, key(KeyCode.DOWN));
        press(form, key(KeyCode.ENTER));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase › stemmerOverride");

        // A char filter goes into its own section.
        form = customForm();
        form.select(new Row.Add(Section.CHAR_FILTERS));
        press(form, key(' '));
        typed(form, "html");
        press(form, key(KeyCode.ENTER));
        assertThat(applied(form).text()).isEqualTo("htmlStrip › standard › lowercase");

        // a on the tokenizer does nothing; on a filter, it inserts after it.
        form.select(new Row.Component(Section.TOKENIZER, 0));
        press(form, key('a'));
        assertThat(form.picking()).isFalse();
        form.select(new Row.Add(Section.TOKEN_FILTERS));
        press(form, key('a'));
        typed(form, "stop");
        press(form, key(KeyCode.ENTER));
        form.select(new Row.Component(Section.TOKEN_FILTERS, 0));
        press(form, key('a'));
        typed(form, "ascii");
        press(form, key(KeyCode.ENTER));
        assertThat(applied(form).text()).isEqualTo("htmlStrip › standard › lowercase › asciiFolding › stop");
    }

    @Test
    void takesATypedNameThatIsNotListed() {
        AnalyzerForm form = new AnalyzerForm(new AnalyzerSettings(), Optional.empty());
        press(form, key(KeyCode.LEFT));
        form.select(new Row.Add(Section.TOKEN_FILTERS));
        press(form, key(KeyCode.UP));
        // The tokenizer row: Space picks another.
        press(form, key(KeyCode.UP));
        press(form, key(' '));
        typed(form, "icu");
        press(form, key(KeyCode.ENTER));
        assertThat(applied(form).text()).isEqualTo("icu › lowercase");
    }

    @Test
    void editsParametersAsNameValue() {
        AnalyzerForm form = customForm();
        // The lowercase filter.
        form.select(new Row.Add(Section.TOKEN_FILTERS));
        press(form, key(KeyCode.UP));
        press(form, key('p'));
        typed(form, "nope");
        press(form, key(KeyCode.ENTER));
        assertThat(form.textError()).isPresent();
        for (int i = 0; i < 4; i++) {
            press(form, key(KeyCode.BACKSPACE));
        }
        typed(form, " b = 2 ");
        press(form, key(KeyCode.ENTER));
        press(form, key('p'));
        typed(form, "a=1");
        press(form, key(KeyCode.ENTER));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase(a=1, b=2)");

        // The selection is on a=1; Space edits it, and a blank text removes it.
        press(form, key(' '));
        press(form, key('u'));
        press(form, ctrl('u'));
        press(form, key(KeyCode.ENTER));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase(b=2)");

        // d deletes the selected parameter.
        form.select(new Row.Add(Section.TOKEN_FILTERS));
        press(form, key(KeyCode.UP));
        press(form, key('d'));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase");
    }

    @Test
    void deletesAndMovesFiltersButKeepsTheTokenizer() {
        AnalyzerForm form = customForm();
        form.select(new Row.Add(Section.TOKEN_FILTERS));
        press(form, key(' '));
        typed(form, "stop");
        press(form, key(KeyCode.ENTER));
        // stop is selected; K moves it before lowercase.
        press(form, key('K'));
        assertThat(applied(form).text()).isEqualTo("standard › stop › lowercase");
        press(form, key('J'));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase › stop");
        press(form, key('d'));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase");

        // The tokenizer can't be deleted.
        form.select(new Row.Component(Section.TOKENIZER, 0));
        press(form, key('d'));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase");
    }

    @Test
    void setsAndClearsTheConfigDirectory() {
        AnalyzerForm form = customForm();
        press(form, key(KeyCode.DOWN));
        press(form, key(' '));
        typed(form, " /etc/solr/conf ");
        press(form, key(KeyCode.ENTER));
        AnalyzerSettings settings = applied(form);
        assertThat(settings.text()).isEqualTo("standard › lowercase · config /etc/solr/conf");
        assertThat(settings.analyzer().custom().orElseThrow().configDir()).hasValue("/etc/solr/conf");
        press(form, key('d'));
        assertThat(applied(form).analyzer().custom().orElseThrow().configDir()).isEmpty();
    }

    @Test
    void keepsTheChainWhileTheStandardAnalyzerIsChosen() {
        AnalyzerForm form = customForm();
        form.select(new Row.Add(Section.TOKEN_FILTERS));
        press(form, key(' '));
        typed(form, "stop");
        press(form, key(KeyCode.ENTER));
        press(form, key(KeyCode.HOME));
        press(form, key(' '));
        assertThat(applied(form).analyzer()).isEqualTo(AnalyzerSettings.standardAnalyzer());
        press(form, key(KeyCode.LEFT));
        assertThat(applied(form).text()).isEqualTo("standard › lowercase › stop");
        assertThat(press(form, key(KeyCode.ESCAPE))).isEqualTo(new Form.Event.Cancel<>());
    }

    @Test
    void picksAPresetByItsSimpleName() {
        AnalyzerForm form = new AnalyzerForm(new AnalyzerSettings(), Optional.of(components()));
        press(form, key(' '));
        AnalyzerSettings settings = applied(form);
        assertThat(settings.text()).isEqualTo("EnglishAnalyzer");
        assertThat(settings.isStandard() || settings.isCustom()).isFalse();
        assertThat(settings.analyzer().preset())
                .hasValue(new PresetAnalyzer(
                        AnalyzerSettings.DEFAULT_PRESET,
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThat(settings.analyzer().standard()).isEmpty();

        // The class row opens the list; the filter matches the simple name.
        press(form, key(KeyCode.DOWN));
        press(form, key(' '));
        typed(form, "MAN");
        String screen = render(form);
        assertThat(screen).contains("Pick a preset analyzer", "org.apache.lucene.analysis.x");
        assertThat(screen).doesNotContain("Whitespace");
        press(form, key(KeyCode.ENTER));
        settings = applied(form);
        assertThat(settings.text()).isEqualTo("GermanAnalyzer");
        assertThat(settings.analyzer().preset().orElseThrow().className())
                .isEqualTo("org.apache.lucene.analysis.x.GermanAnalyzer");
        assertThat(render(form)).contains("Class           GermanAnalyzer  org.apache.lucene.analysis.x");

        // A class name that is not listed is taken as typed.
        press(form, key(' '));
        typed(form, "com.example.MyAnalyzer");
        press(form, key(KeyCode.ENTER));
        assertThat(applied(form).text()).isEqualTo("MyAnalyzer");

        // The preset is kept while another kind is chosen.
        press(form, key(KeyCode.HOME));
        press(form, key(KeyCode.RIGHT));
        assertThat(applied(form).isCustom()).isTrue();
        press(form, key(KeyCode.LEFT));
        assertThat(applied(form).text()).isEqualTo("MyAnalyzer");
    }

    /** The preset form, with {@code EnglishAnalyzer}. */
    private static AnalyzerForm presetForm() {
        AnalyzerForm form = new AnalyzerForm(new AnalyzerSettings(), Optional.of(components()));
        press(form, key(' '));
        form.draft().preset = "org.apache.lucene.analysis.x.EnglishAnalyzer";
        return form;
    }

    private static void edit(AnalyzerForm form, Row row, String text) {
        form.select(row);
        press(form, key(' '));
        press(form, ctrl('u'));
        typed(form, text);
        press(form, key(KeyCode.ENTER));
    }

    @Test
    void editsAPresetsWordSets() {
        AnalyzerForm form = presetForm();
        assertThat(form.rows())
                .containsExactly(
                        new Row.KindRow(),
                        new Row.Preset(),
                        STOPWORDS,
                        STEM_EXCLUSION,
                        new Row.ShinglesRow(),
                        new Row.TokenLimit());

        edit(form, STOPWORDS, "-");
        edit(form, STEM_EXCLUSION, " running, news  foxes ");
        AnalyzerSettings settings = applied(form);
        assertThat(settings.text())
                .isEqualTo("EnglishAnalyzer · stop words none · stem exclusions running, news, foxes");
        PresetAnalyzer preset = settings.analyzer().preset().orElseThrow();
        assertThat(preset.stopwords()).hasValue(AnalyzerSettings.words(List.of()));
        assertThat(preset.stemExclusion()).hasValue(AnalyzerSettings.words(List.of("running", "news", "foxes")));

        // The editor starts with the set as it is typed.
        form.select(STEM_EXCLUSION);
        press(form, key(' '));
        assertThat(form.editedText()).hasValue("running, news, foxes");
        press(form, key(KeyCode.ESCAPE));

        // @ and a path is a file; @ alone is refused.
        edit(form, STOPWORDS, "@");
        assertThat(form.textError()).isPresent();
        typed(form, " /etc/stop.txt");
        press(form, key(KeyCode.ENTER));
        preset = applied(form).analyzer().preset().orElseThrow();
        assertThat(preset.stopwords()).hasValue(AnalyzerSettings.wordFile("/etc/stop.txt"));
        assertThat(render(form)).contains("Stop words      file /etc/stop.txt");

        // d goes back to the default, and so does a blank text.
        form.select(STOPWORDS);
        press(form, key('d'));
        edit(form, STEM_EXCLUSION, "");
        settings = applied(form);
        assertThat(settings.text()).isEqualTo("EnglishAnalyzer");
        assertThat(settings.analyzer().preset())
                .hasValue(new PresetAnalyzer(
                        "org.apache.lucene.analysis.x.EnglishAnalyzer",
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThat(render(form)).contains("Stop words      default: the analyzer's own");
    }

    @Test
    void showsOnlyTheWordSetsThatAPresetTakes() {
        AnalyzerForm form = presetForm();
        edit(form, STOPWORDS, "a");
        edit(form, STEM_EXCLUSION, "b");

        // StopAnalyzer takes stop words only, and needs them.
        form.select(new Row.Preset());
        press(form, key(' '));
        typed(form, "Stop");
        press(form, key(KeyCode.ENTER));
        assertThat(form.rows())
                .containsExactly(
                        new Row.KindRow(), new Row.Preset(), STOPWORDS, new Row.ShinglesRow(), new Row.TokenLimit());
        assertThat(applied(form).text()).isEqualTo("StopAnalyzer · stop words a");
        form.select(STOPWORDS);
        press(form, key('d'));
        assertThat(render(form)).contains("none: this analyzer needs some");

        // WhitespaceAnalyzer takes neither, but the token length.
        form.select(new Row.Preset());
        press(form, key(' '));
        typed(form, "White");
        press(form, key(KeyCode.ENTER));
        assertThat(form.rows())
                .containsExactly(
                        new Row.KindRow(),
                        new Row.Preset(),
                        new Row.MaxTokenLength(),
                        new Row.ShinglesRow(),
                        new Row.TokenLimit());

        // Without the list, the rows that any preset may have are there, and the backend judges.
        AnalyzerForm unlisted = new AnalyzerForm(applied(form), Optional.empty());
        assertThat(unlisted.rows())
                .containsExactly(
                        new Row.KindRow(),
                        new Row.Preset(),
                        STOPWORDS,
                        STEM_EXCLUSION,
                        new Row.MaxTokenLength(),
                        new Row.StemOverride(),
                        new Row.ShinglesRow(),
                        new Row.TokenLimit());
    }

    @Test
    void parsesWordSets() {
        assertThat(AnalyzerSettings.parseWordSet("  ")).isEmpty();
        assertThat(AnalyzerSettings.parseWordSet(" - ")).hasValue(AnalyzerSettings.words(List.of()));
        assertThat(AnalyzerSettings.parseWordSet("a,b c,,\td"))
                .hasValue(AnalyzerSettings.words(List.of("a", "b", "c", "d")));
        assertThat(AnalyzerSettings.parseWordSet("@ my stop.txt ")).hasValue(AnalyzerSettings.wordFile("my stop.txt"));
        assertThatThrownBy(() -> AnalyzerSettings.parseWordSet("@ ")).isInstanceOf(IllegalArgumentException.class);
        for (WordSet set : List.of(
                AnalyzerSettings.words(List.of()),
                AnalyzerSettings.words(List.of("a", "b")),
                AnalyzerSettings.wordFile("/x"))) {
            assertThat(AnalyzerSettings.parseWordSet(AnalyzerSettings.wordSetInput(Optional.of(set))))
                    .hasValue(set);
        }
    }

    /** The form with the preset of this simple name, picked from the list. */
    private static AnalyzerForm presetNamed(String name) {
        AnalyzerForm form = presetForm();
        form.select(new Row.Preset());
        press(form, key(' '));
        typed(form, name);
        press(form, key(KeyCode.ENTER));
        return form;
    }

    @Test
    void wrapsAnyAnalyzerInShinglesAndATokenLimit() {
        AnalyzerForm form = new AnalyzerForm(new AnalyzerSettings(), Optional.of(components()));
        assertThat(form.rows()).containsExactly(new Row.KindRow(), new Row.ShinglesRow(), new Row.TokenLimit());
        assertThat(render(form)).contains("Wrappers", "Shingles        off");

        form.select(new Row.ShinglesRow());
        press(form, key(' '));
        assertThat(form.rows())
                .containsExactly(
                        new Row.KindRow(),
                        new Row.ShinglesRow(),
                        new Row.Shingle(ShingleField.SIZE),
                        new Row.Shingle(ShingleField.SEPARATOR),
                        new Row.Shingle(ShingleField.UNIGRAMS),
                        new Row.Shingle(ShingleField.FILLER),
                        new Row.TokenLimit());
        AnalyzerSettings settings = applied(form);
        assertThat(settings.isStandard()).isFalse();
        assertThat(settings.text()).isEqualTo("standard analyzer · shingles 2");
        assertThat(settings.analyzer().shingles()).hasValue(AnalyzerForm.DEFAULT_SHINGLES);
        assertThat(settings.analyzer().standard()).isPresent();

        edit(form, new Row.Shingle(ShingleField.SIZE), "2-3");
        // The separator and the filler are taken as typed.
        edit(form, new Row.Shingle(ShingleField.SEPARATOR), " ");
        edit(form, new Row.Shingle(ShingleField.FILLER), "");
        form.select(new Row.Shingle(ShingleField.UNIGRAMS));
        press(form, key(' '));
        assertThat(render(form)).contains("  separator     \" \"", "  unigrams      no");
        assertThat(applied(form).analyzer().shingles()).hasValue(new Shingles(2, 3, " ", false, false, ""));
        // Only without shingles, then always again.
        press(form, key(' '));
        assertThat(render(form)).contains("only if there are no shingles");
        press(form, key(' '));
        assertThat(applied(form).analyzer().shingles().orElseThrow().outputUnigrams())
                .isTrue();

        // A size below 2 is refused.
        edit(form, new Row.Shingle(ShingleField.SIZE), "1-2");
        assertThat(form.textError()).isPresent();
        press(form, key(KeyCode.ESCAPE));

        form.select(new Row.TokenLimit());
        press(form, key(' '));
        edit(form, new Row.Limit(LimitField.MAX_TOKENS), "5");
        form.select(new Row.Limit(LimitField.CONSUME_ALL));
        press(form, key(' '));
        settings = applied(form);
        assertThat(settings.text()).isEqualTo("standard analyzer · shingles 2–3 · at most 5 tokens");
        assertThat(settings.analyzer().limitTokenCount()).hasValue(new LimitTokenCount(5, true));

        // They wrap a custom analyzer too, and d turns them off.
        form.select(new Row.KindRow());
        press(form, key(KeyCode.LEFT));
        assertThat(applied(form).analyzer().shingles()).isPresent();
        form.select(new Row.ShinglesRow());
        press(form, key('d'));
        form.select(new Row.TokenLimit());
        press(form, key('d'));
        settings = applied(form);
        assertThat(settings.text()).isEqualTo("standard › lowercase");
        assertThat(settings.analyzer().limitTokenCount()).isEmpty();
    }

    @Test
    void editsAPresetsTokenLengthAndStemOverrides() {
        AnalyzerForm form = presetNamed("Dutch");
        assertThat(form.rows()).contains(new Row.StemOverride()).doesNotContain(new Row.MaxTokenLength());

        edit(form, new Row.StemOverride(), "fietsen=rijwiel, kinderen=kind");
        AnalyzerSettings settings = applied(form);
        assertThat(settings.text()).isEqualTo("DutchAnalyzer · stem overrides fietsen → rijwiel, kinderen → kind");
        StemDictionary overrides =
                settings.analyzer().preset().orElseThrow().stemOverride().orElseThrow();
        assertThat(overrides.entries()).hasValue(Map.of("fietsen", "rijwiel", "kinderen", "kind"));
        edit(form, new Row.StemOverride(), "fietsen");
        assertThat(form.textError()).isPresent();
        press(form, key(KeyCode.ESCAPE));
        edit(form, new Row.StemOverride(), "@/etc/stemdict.txt");
        assertThat(applied(form).text()).isEqualTo("DutchAnalyzer · stem overrides file /etc/stemdict.txt");

        // A preset that doesn't take them drops them.
        form = new AnalyzerForm(applied(form), Optional.of(components()));
        form.select(new Row.Preset());
        press(form, key(' '));
        typed(form, "White");
        press(form, key(KeyCode.ENTER));
        edit(form, new Row.MaxTokenLength(), "0");
        assertThat(form.textError()).isPresent();
        press(form, key(KeyCode.ESCAPE));
        edit(form, new Row.MaxTokenLength(), "255");
        settings = applied(form);
        assertThat(settings.text()).isEqualTo("WhitespaceAnalyzer · token length 255");
        PresetAnalyzer preset = settings.analyzer().preset().orElseThrow();
        assertThat(preset.maxTokenLength()).hasValue(255);
        assertThat(preset.stemOverride()).isEmpty();
        assertThat(render(form)).contains("Token length    255");
    }

    @Test
    void editsKuromojisAndNorisOptions() {
        AnalyzerForm form = presetNamed("Japanese");
        assertThat(form.rows().subList(0, 6))
                .containsExactly(
                        new Row.KindRow(),
                        new Row.Preset(),
                        STOPWORDS,
                        new Row.UserDictionary(Language.JAPANESE),
                        new Row.JapaneseModeRow(),
                        new Row.StopTags(Language.JAPANESE));
        assertThat(render(form)).contains("Mode            default: search");
        // Nothing set sends no options.
        assertThat(applied(form).analyzer().preset().orElseThrow().japanese()).isEmpty();

        edit(form, new Row.UserDictionary(Language.JAPANESE), "/x/userdict.txt");
        form.select(new Row.JapaneseModeRow());
        press(form, key(' '));
        edit(form, new Row.StopTags(Language.JAPANESE), "-");
        AnalyzerSettings settings = applied(form);
        assertThat(settings.text())
                .isEqualTo("JapaneseAnalyzer · user dictionary /x/userdict.txt · mode normal · stop tags none");
        assertThat(settings.analyzer().preset().orElseThrow().japanese())
                .hasValue(new JapaneseOptions(
                        Optional.of("/x/userdict.txt"),
                        Optional.of(JapaneseMode.NORMAL),
                        Optional.of(AnalyzerSettings.words(List.of()))));
        // ← cycles back to the default.
        form.select(new Row.JapaneseModeRow());
        press(form, key(KeyCode.LEFT));
        assertThat(form.draft().japanese.mode()).isEmpty();

        // Korean drops the Japanese options.
        form = new AnalyzerForm(applied(form), Optional.of(components()));
        form.select(new Row.Preset());
        press(form, key(' '));
        typed(form, "Korean");
        press(form, key(KeyCode.ENTER));
        assertThat(form.draft().japanese).isEqualTo(AnalyzerSettings.noJapanese());
        form.select(new Row.DecompoundMode());
        press(form, key(KeyCode.LEFT));
        form.select(new Row.UnknownWords());
        press(form, key(' '));
        edit(form, new Row.StopTags(Language.KOREAN), "JX NNP");
        settings = applied(form);
        assertThat(settings.text())
                .isEqualTo("KoreanAnalyzer · decompound mixed · stop tags JX, NNP · unknown words split into unigrams");
        assertThat(settings.analyzer().preset().orElseThrow().korean())
                .hasValue(new KoreanOptions(
                        Optional.empty(),
                        Optional.of(KoreanDecompoundMode.MIXED),
                        Optional.of(AnalyzerSettings.words(List.of("JX", "NNP"))),
                        Optional.of(true)));

        // Without the list, the class name says which options there are.
        AnalyzerSettings japanese = new AnalyzerSettings();
        japanese.kind = Kind.PRESET;
        japanese.preset = AnalyzerSettings.JAPANESE_ANALYZER;
        AnalyzerForm unlisted = new AnalyzerForm(japanese, Optional.empty());
        assertThat(unlisted.rows()).contains(new Row.JapaneseModeRow()).doesNotContain(new Row.DecompoundMode());
    }

    @Test
    void editsACollationAndTheCompletionOptions() {
        AnalyzerForm form = presetNamed("CollationKey");
        assertThat(form.rows().subList(0, 5))
                .containsExactly(
                        new Row.KindRow(),
                        new Row.Preset(),
                        new Row.CollationLocale(),
                        new Row.Strength(),
                        new Row.Decomposition());
        assertThat(render(form))
                .contains("Locale          root")
                .contains("Strength        default: the collator's own");
        // Nothing set sends no collation: the root locale.
        assertThat(applied(form).analyzer().preset().orElseThrow().collation()).isEmpty();

        edit(form, new Row.CollationLocale(), "de-AT");
        form.select(new Row.Strength());
        press(form, key(' '));
        form.select(new Row.Decomposition());
        press(form, key(KeyCode.LEFT));
        AnalyzerSettings settings = applied(form);
        assertThat(settings.text()).isEqualTo("CollationKeyAnalyzer · de-AT · primary · decomposition full");
        assertThat(settings.analyzer().preset().orElseThrow().collation())
                .hasValue(new Collation(
                        "de-AT", Optional.of(CollationStrength.PRIMARY), Optional.of(CollationDecomposition.FULL)));
        // The strength cycles through every one, then back to the default.
        form.select(new Row.Strength());
        for (int i = 0; i < 4; i++) {
            press(form, key(' '));
        }
        assertThat(form.draft().collation.strength()).hasValue(CollationStrength.IDENTICAL);
        press(form, key(' '));
        assertThat(form.draft().collation.strength()).isEmpty();
        form.select(new Row.CollationLocale());
        press(form, key('d'));
        assertThat(form.draft().collation.locale()).isEmpty();

        // Another preset drops the collation, and has no collation rows.
        form = new AnalyzerForm(applied(form), Optional.of(components()));
        form.select(new Row.Preset());
        press(form, key(' '));
        typed(form, "JapaneseCompletion");
        press(form, key(KeyCode.ENTER));
        assertThat(form.draft().collation).isEqualTo(AnalyzerSettings.noCollation());
        assertThat(form.rows())
                .doesNotContain(new Row.CollationLocale(), new Row.StopTags(Language.JAPANESE))
                .contains(new Row.UserDictionary(Language.JAPANESE_COMPLETION), new Row.CompletionMode());
        assertThat(render(form)).contains("Mode            default: index");
        edit(form, new Row.UserDictionary(Language.JAPANESE_COMPLETION), "/x/userdict.txt");
        form.select(new Row.CompletionMode());
        press(form, key(KeyCode.LEFT));
        settings = applied(form);
        assertThat(settings.text())
                .isEqualTo("JapaneseCompletionAnalyzer · user dictionary /x/userdict.txt · mode query");
        assertThat(settings.analyzer().preset().orElseThrow().japaneseCompletion())
                .hasValue(new JapaneseCompletionOptions(
                        Optional.of("/x/userdict.txt"), Optional.of(JapaneseCompletionMode.QUERY)));
        assertThat(settings.analyzer().preset().orElseThrow().japanese()).isEmpty();

        // Without the list, the class name says which options there are.
        AnalyzerSettings icu = new AnalyzerSettings();
        icu.kind = Kind.PRESET;
        icu.preset = "org.apache.lucene.analysis.icu.ICUCollationKeyAnalyzer";
        assertThat(new AnalyzerForm(icu, Optional.empty()).rows()).contains(new Row.Strength());
    }

    @Test
    void parsesStemOverridesAndShingleSizes() {
        assertThat(AnalyzerSettings.parseStemDictionary(" ")).isEmpty();
        assertThat(AnalyzerSettings.parseStemDictionary("-").orElseThrow().entries())
                .hasValue(Map.of());
        for (String bad : List.of("a=", "=b", "@")) {
            assertThatThrownBy(() -> AnalyzerSettings.parseStemDictionary(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String text : List.of("a=b, c=d", "@/x", "-")) {
            Optional<StemDictionary> dictionary = AnalyzerSettings.parseStemDictionary(text);
            assertThat(AnalyzerSettings.parseStemDictionary(AnalyzerSettings.stemDictionaryInput(dictionary)))
                    .isEqualTo(dictionary);
        }

        assertThat(AnalyzerSettings.parseShingleSize("")).isEmpty();
        assertThat(AnalyzerSettings.parseShingleSize("3").orElseThrow()).containsExactly(3, 3);
        assertThat(AnalyzerSettings.parseShingleSize("2 - 4").orElseThrow()).containsExactly(2, 4);
        assertThat(AnalyzerSettings.parseShingleSize("2–4").orElseThrow()).containsExactly(2, 4);
        for (String bad : List.of("1", "3-2", "x", "2-", "2-3-4")) {
            assertThatThrownBy(() -> AnalyzerSettings.parseShingleSize(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rendersTheChainAndTheError() {
        AnalyzerForm form = customForm();
        form.setError("Invalid analyzer: no token filter 'nope'");
        assertThat(render(form))
                .contains(
                        "Analyzer        custom",
                        "Config dir      none",
                        "Char filters",
                        "+ char filter",
                        "Tokenizer",
                        "standard",
                        "Token filters",
                        "lowercase",
                        "Invalid analyzer: no token filter 'nope'");
    }
}
