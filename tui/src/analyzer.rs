//! The current analyzer (ADR 0014): the analysis screen tries it, and the search screen parses
//! queries and analyzes More-Like-This text with it. It is Lucene's `StandardAnalyzer`, a preset
//! analyzer by class name (ADR 0015) with stop words and stem exclusions of its own if it takes
//! them (ADR 0017) and the options it takes (ADR 0023), or a custom analyzer of char filters, a
//! tokenizer and token filters, each with its parameters, and a config directory for resource
//! files. Any of them can be wrapped in shingles, then in a token limit (ADR 0023). The popup
//! builds it.

use std::collections::BTreeMap;

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, Paragraph, Wrap};

use crate::form::{FormEvent, dim, render_popup};
use crate::input::{Edit, LineInput, word_start};
use crate::protocol::analysis::{
    AnalysisComponent, Analyzer, AvailableAnalyzer, CustomAnalyzer, GetAnalysisComponentsResult,
    JapaneseMode, JapaneseOptions, KoreanDecompoundMode, KoreanOptions, LimitTokenCount,
    MAX_TOKEN_LENGTH, PresetAnalyzer, PresetOption, Shingles, StemDictionary, WordSet,
    WordSetSupport,
};
use crate::text::{escape_control, wrap};

/// How wide the popups are at most.
const WIDTH: u16 = 76;

/// The preset that the popup starts with.
const DEFAULT_PRESET: &str = "org.apache.lucene.analysis.en.EnglishAnalyzer";

/// Kuromoji's and Nori's analyzers, which take options of their own (ADR 0023).
const JAPANESE_ANALYZER: &str = "org.apache.lucene.analysis.ja.JapaneseAnalyzer";
const KOREAN_ANALYZER: &str = "org.apache.lucene.analysis.ko.KoreanAnalyzer";

/// The token limit that turning it on starts with: the old `IndexWriter` field length.
const DEFAULT_MAX_TOKEN_COUNT: i32 = 10_000;

/// How wide the labels of the rows are.
const LABEL: usize = 16;

/// Which alternative of the analyzer is chosen.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Standard,
    Preset,
    Custom,
}

impl Kind {
    fn name(self) -> &'static str {
        match self {
            Kind::Standard => "standard",
            Kind::Preset => "preset",
            Kind::Custom => "custom",
        }
    }

    /// The next kind, or the previous one.
    fn cycled(self, forward: bool) -> Self {
        match (self, forward) {
            (Kind::Standard, true) | (Kind::Custom, false) => Kind::Preset,
            (Kind::Preset, true) | (Kind::Standard, false) => Kind::Custom,
            (Kind::Custom, true) | (Kind::Preset, false) => Kind::Standard,
        }
    }
}

/// A preset's word set (ADR 0017).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WordSetKind {
    Stopwords,
    StemExclusion,
}

impl WordSetKind {
    const ALL: [WordSetKind; 2] = [WordSetKind::Stopwords, WordSetKind::StemExclusion];

    fn label(self) -> &'static str {
        match self {
            WordSetKind::Stopwords => "Stop words",
            WordSetKind::StemExclusion => "Stem exclusions",
        }
    }

    fn noun(self) -> &'static str {
        match self {
            WordSetKind::Stopwords => "stop words",
            WordSetKind::StemExclusion => "stem exclusions",
        }
    }
}

/// Kuromoji's or Nori's analyzer, for the options that both have (ADR 0023).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Language {
    Japanese,
    Korean,
}

/// A row of the shingles (ADR 0023).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ShingleField {
    /// The fewest and the most tokens in a shingle.
    Size,
    Separator,
    /// Whether the tokens are kept: always, never, or when there are no shingles.
    Unigrams,
    Filler,
}

impl ShingleField {
    const ALL: [ShingleField; 4] = [
        ShingleField::Size,
        ShingleField::Separator,
        ShingleField::Unigrams,
        ShingleField::Filler,
    ];
}

/// A row of the token limit (ADR 0023).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LimitField {
    MaxTokens,
    ConsumeAll,
}

impl LimitField {
    const ALL: [LimitField; 2] = [LimitField::MaxTokens, LimitField::ConsumeAll];
}

/// The current analyzer. The preset and the custom chain are kept while another kind is chosen,
/// so that switching back finds them again.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnalyzerSettings {
    kind: Kind,
    /// The preset's class name.
    preset: String,
    /// The preset's stop words; `None` for its default ones.
    stopwords: Option<WordSet>,
    /// The preset's stem exclusions; `None` for none.
    stem_exclusion: Option<WordSet>,
    /// The preset's longest token; `None` for its default.
    max_token_length: Option<i32>,
    /// The preset's stem overrides; `None` for its default ones.
    stem_override: Option<StemDictionary>,
    /// `JapaneseAnalyzer`'s options; sent only when one is set.
    japanese: JapaneseOptions,
    /// `KoreanAnalyzer`'s options; sent only when one is set.
    korean: KoreanOptions,
    chain: CustomAnalyzer,
    /// Wrap any kind; `None` for none.
    shingles: Option<Shingles>,
    limit_token_count: Option<LimitTokenCount>,
}

impl Default for AnalyzerSettings {
    /// The standard analyzer; `EnglishAnalyzer` as the preset; and a custom chain that does what
    /// the standard analyzer does: the standard tokenizer, then lowercase.
    fn default() -> Self {
        Self {
            kind: Kind::Standard,
            preset: DEFAULT_PRESET.to_owned(),
            stopwords: None,
            stem_exclusion: None,
            max_token_length: None,
            stem_override: None,
            japanese: JapaneseOptions::default(),
            korean: KoreanOptions::default(),
            chain: CustomAnalyzer {
                char_filters: Vec::new(),
                tokenizer: component("standard"),
                token_filters: vec![component("lowercase")],
                config_dir: None,
            },
            shingles: None,
            limit_token_count: None,
        }
    }
}

impl AnalyzerSettings {
    /// The analyzer as a request sends it.
    pub fn analyzer(&self) -> Analyzer {
        let mut analyzer = Analyzer {
            standard: None,
            preset: None,
            custom: None,
            shingles: self.shingles.clone(),
            limit_token_count: self.limit_token_count,
        };
        match self.kind {
            Kind::Standard => analyzer.standard = Analyzer::default().standard,
            Kind::Preset => {
                analyzer.preset = Some(PresetAnalyzer {
                    class_name: self.preset.clone(),
                    stopwords: self.stopwords.clone(),
                    stem_exclusion: self.stem_exclusion.clone(),
                    max_token_length: self.max_token_length,
                    stem_override: self.stem_override.clone(),
                    japanese: (self.japanese != JapaneseOptions::default())
                        .then(|| self.japanese.clone()),
                    korean: (self.korean != KoreanOptions::default()).then(|| self.korean.clone()),
                });
            }
            Kind::Custom => analyzer.custom = Some(self.chain.clone()),
        }
        analyzer
    }

    /// Whether this is the default: the standard analyzer, without wrappers.
    pub fn is_standard(&self) -> bool {
        self.kind == Kind::Standard && self.shingles.is_none() && self.limit_token_count.is_none()
    }

    pub fn is_custom(&self) -> bool {
        self.kind == Kind::Custom
    }

    fn word_set(&self, kind: WordSetKind) -> Option<&WordSet> {
        match kind {
            WordSetKind::Stopwords => self.stopwords.as_ref(),
            WordSetKind::StemExclusion => self.stem_exclusion.as_ref(),
        }
    }

    fn word_set_mut(&mut self, kind: WordSetKind) -> &mut Option<WordSet> {
        match kind {
            WordSetKind::Stopwords => &mut self.stopwords,
            WordSetKind::StemExclusion => &mut self.stem_exclusion,
        }
    }

    fn user_dictionary(&self, language: Language) -> Option<&String> {
        match language {
            Language::Japanese => self.japanese.user_dictionary.as_ref(),
            Language::Korean => self.korean.user_dictionary.as_ref(),
        }
    }

    fn user_dictionary_mut(&mut self, language: Language) -> &mut Option<String> {
        match language {
            Language::Japanese => &mut self.japanese.user_dictionary,
            Language::Korean => &mut self.korean.user_dictionary,
        }
    }

    fn stop_tags(&self, language: Language) -> Option<&WordSet> {
        match language {
            Language::Japanese => self.japanese.stop_tags.as_ref(),
            Language::Korean => self.korean.stop_tags.as_ref(),
        }
    }

    fn stop_tags_mut(&mut self, language: Language) -> &mut Option<WordSet> {
        match language {
            Language::Japanese => &mut self.japanese.stop_tags,
            Language::Korean => &mut self.korean.stop_tags,
        }
    }

    /// The analyzer on one line: `standard analyzer`, the preset's simple class name with its word
    /// sets and options, such as `EnglishAnalyzer · stop words none`, or the chain, such as
    /// `htmlStrip › standard › lowercase › stop(ignoreCase=true)`, and its config directory; then
    /// the wrappers, such as `· shingles 2–3 · at most 10000 tokens`.
    pub fn text(&self) -> String {
        let mut text = match self.kind {
            Kind::Standard => "standard analyzer".to_owned(),
            Kind::Preset => self.preset_text(),
            Kind::Custom => self.chain_text(),
        };
        if let Some(shingles) = &self.shingles {
            text.push_str(&format!(" · shingles {}", shingle_size_text(shingles)));
        }
        if let Some(limit) = &self.limit_token_count {
            text.push_str(&format!(" · at most {} tokens", limit.max_token_count));
        }
        text
    }

    fn preset_text(&self) -> String {
        let mut text = escape_control(simple_name(&self.preset));
        for kind in WordSetKind::ALL {
            if let Some(set) = self.word_set(kind) {
                text.push_str(&format!(" · {} {}", kind.noun(), word_set_text(set)));
            }
        }
        if let Some(length) = self.max_token_length {
            text.push_str(&format!(" · token length {length}"));
        }
        if let Some(dictionary) = &self.stem_override {
            text.push_str(&format!(
                " · stem overrides {}",
                stem_dictionary_text(dictionary)
            ));
        }
        for language in [Language::Japanese, Language::Korean] {
            if let Some(path) = self.user_dictionary(language) {
                text.push_str(&format!(" · user dictionary {}", escape_control(path)));
            }
            if language == Language::Japanese
                && let Some(mode) = self.japanese.mode
            {
                text.push_str(&format!(" · mode {}", japanese_mode_name(mode)));
            }
            if language == Language::Korean
                && let Some(mode) = self.korean.decompound_mode
            {
                text.push_str(&format!(" · decompound {}", decompound_mode_name(mode)));
            }
            if let Some(tags) = self.stop_tags(language) {
                text.push_str(&format!(" · stop tags {}", word_set_text(tags)));
            }
        }
        if let Some(split) = self.korean.output_unknown_unigrams {
            text.push_str(&format!(" · unknown words {}", unknown_words_text(split)));
        }
        text
    }

    fn chain_text(&self) -> String {
        let chain = &self.chain;
        let mut text = chain
            .char_filters
            .iter()
            .chain([&chain.tokenizer])
            .chain(&chain.token_filters)
            .map(component_text)
            .collect::<Vec<_>>()
            .join(" › ");
        if let Some(dir) = &chain.config_dir {
            text.push_str(&format!(" · config {}", escape_control(dir)));
        }
        text
    }
}

/// The words, separated by commas; `none` for no words; or `file` and the path.
fn word_set_text(set: &WordSet) -> String {
    match (&set.words, &set.file) {
        (Some(words), _) if words.is_empty() => "none".to_owned(),
        (Some(words), _) => escape_control(&words.join(", ")),
        (None, Some(file)) => format!("file {}", escape_control(file)),
        (None, None) => String::new(),
    }
}

/// The text that edits a word set: the words, `-` for no words, `@` and the path for a file, or
/// blank for none.
fn word_set_input(set: Option<&WordSet>) -> String {
    match set {
        None => String::new(),
        Some(WordSet {
            words: Some(words), ..
        }) if words.is_empty() => "-".to_owned(),
        Some(WordSet {
            words: Some(words), ..
        }) => words.join(", "),
        Some(WordSet {
            file: Some(file), ..
        }) => format!("@{file}"),
        Some(_) => String::new(),
    }
}

/// A word set from its text (see [`word_set_input`]): words are separated by commas or white
/// space.
fn parse_word_set(text: &str) -> Result<Option<WordSet>, String> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(None);
    }
    if text == "-" {
        return Ok(Some(WordSet::words(Vec::new())));
    }
    if let Some(path) = text.strip_prefix('@') {
        let path = path.trim();
        if path.is_empty() {
            return Err("Write the word list file's path after @.".to_owned());
        }
        return Ok(Some(WordSet::file(path.to_owned())));
    }
    let words = text
        .split(|c: char| c == ',' || c.is_whitespace())
        .filter(|word| !word.is_empty())
        .map(str::to_owned)
        .collect();
    Ok(Some(WordSet::words(words)))
}

/// The overrides as `word → stem`, separated by commas; `none` for none; or `file` and the path.
fn stem_dictionary_text(dictionary: &StemDictionary) -> String {
    match (&dictionary.entries, &dictionary.file) {
        (Some(entries), _) if entries.is_empty() => "none".to_owned(),
        (Some(entries), _) => entries
            .iter()
            .map(|(word, stem)| format!("{} → {}", escape_control(word), escape_control(stem)))
            .collect::<Vec<_>>()
            .join(", "),
        (None, Some(file)) => format!("file {}", escape_control(file)),
        (None, None) => String::new(),
    }
}

/// The text that edits stem overrides: `word=stem` pairs, `-` for none, `@` and the path for a
/// file, or blank for the default.
fn stem_dictionary_input(dictionary: Option<&StemDictionary>) -> String {
    match dictionary {
        None => String::new(),
        Some(StemDictionary {
            entries: Some(entries),
            ..
        }) if entries.is_empty() => "-".to_owned(),
        Some(StemDictionary {
            entries: Some(entries),
            ..
        }) => entries
            .iter()
            .map(|(word, stem)| format!("{word}={stem}"))
            .collect::<Vec<_>>()
            .join(", "),
        Some(StemDictionary {
            file: Some(file), ..
        }) => format!("@{file}"),
        Some(_) => String::new(),
    }
}

/// Stem overrides from their text (see [`stem_dictionary_input`]): pairs are separated by commas
/// or white space.
fn parse_stem_dictionary(text: &str) -> Result<Option<StemDictionary>, String> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(None);
    }
    if text == "-" {
        return Ok(Some(StemDictionary {
            entries: Some(BTreeMap::new()),
            file: None,
        }));
    }
    if let Some(path) = text.strip_prefix('@') {
        let path = path.trim();
        if path.is_empty() {
            return Err("Write the dictionary file's path after @.".to_owned());
        }
        return Ok(Some(StemDictionary {
            entries: None,
            file: Some(path.to_owned()),
        }));
    }
    let mut entries = BTreeMap::new();
    for pair in text
        .split(|c: char| c == ',' || c.is_whitespace())
        .filter(|pair| !pair.is_empty())
    {
        match pair.split_once('=') {
            Some((word, stem)) if !word.is_empty() && !stem.is_empty() => {
                entries.insert(word.to_owned(), stem.to_owned());
            }
            _ => return Err("Write each override as word=stem.".to_owned()),
        }
    }
    Ok(Some(StemDictionary {
        entries: Some(entries),
        file: None,
    }))
}

/// A whole number from `min` to `max`; `None` for a blank text.
fn parse_number(text: &str, min: i32, max: i32) -> Result<Option<i32>, String> {
    if text.is_empty() {
        return Ok(None);
    }
    match text.parse::<i32>() {
        Ok(number) if (min..=max).contains(&number) => Ok(Some(number)),
        _ => Err(format!("Write a whole number from {min} to {max}.")),
    }
}

/// The shingle size as `min-max`, or one number if they are the same; `None` for a blank text.
fn parse_shingle_size(text: &str) -> Result<Option<(i32, i32)>, String> {
    if text.is_empty() {
        return Ok(None);
    }
    let error = || "Write the size as min-max, such as 2-3, both at least 2.".to_owned();
    let (min, max) = match text.split_once(['-', '–']) {
        Some((min, max)) => (min.trim(), max.trim()),
        None => (text, text),
    };
    let min = parse_number(min, 2, i32::MAX).map_err(|_| error())?;
    let max = parse_number(max, 2, i32::MAX).map_err(|_| error())?;
    match (min, max) {
        (Some(min), Some(max)) if min <= max => Ok(Some((min, max))),
        _ => Err(error()),
    }
}

/// `2`, or `2–3` for a range.
fn shingle_size_text(shingles: &Shingles) -> String {
    if shingles.min_shingle_size == shingles.max_shingle_size {
        shingles.min_shingle_size.to_string()
    } else {
        format!(
            "{}–{}",
            shingles.min_shingle_size, shingles.max_shingle_size
        )
    }
}

/// When the shingles keep the tokens themselves.
fn unigrams_text(shingles: &Shingles) -> &'static str {
    match (
        shingles.output_unigrams,
        shingles.output_unigrams_if_no_shingles,
    ) {
        (true, _) => "yes",
        (false, false) => "no",
        (false, true) => "only if there are no shingles",
    }
}

/// A text as typed, in quotes, so that blanks show.
fn quoted(text: &str) -> String {
    format!("\"{}\"", escape_control(text))
}

fn japanese_mode_name(mode: JapaneseMode) -> &'static str {
    match mode {
        JapaneseMode::Normal => "normal",
        JapaneseMode::Search => "search",
        JapaneseMode::Extended => "extended",
    }
}

fn decompound_mode_name(mode: KoreanDecompoundMode) -> &'static str {
    match mode {
        KoreanDecompoundMode::None => "none",
        KoreanDecompoundMode::Discard => "discard",
        KoreanDecompoundMode::Mixed => "mixed",
    }
}

fn unknown_words_text(split: bool) -> &'static str {
    if split {
        "split into unigrams"
    } else {
        "kept whole"
    }
}

/// The value after `current` in `values`, or the one before it, round and round.
fn cycled<T: Copy + PartialEq>(values: &[T], current: T, forward: bool) -> T {
    let index = values
        .iter()
        .position(|value| *value == current)
        .unwrap_or(0);
    let len = values.len();
    values[if forward {
        (index + 1) % len
    } else {
        (index + len - 1) % len
    }]
}

/// A row's label, padded, then its value, or the default dimmed.
fn value_line(label: &str, value: Option<String>, default: &str) -> Line<'static> {
    let label = format!("{label:<LABEL$}");
    match value {
        Some(value) => Line::from(format!("{label}{value}")),
        None => Line::from(vec![
            Span::raw(label),
            Span::styled(default.to_owned(), dim()),
        ]),
    }
}

/// The class name without its package.
fn simple_name(class_name: &str) -> &str {
    class_name.rsplit('.').next().unwrap_or(class_name)
}

/// The package of a class name; empty for none.
fn package(class_name: &str) -> &str {
    class_name
        .rsplit_once('.')
        .map_or("", |(package, _)| package)
}

fn component(name: &str) -> AnalysisComponent {
    AnalysisComponent {
        name: name.to_owned(),
        params: BTreeMap::new(),
    }
}

/// `name`, or `name(key=value, …)` with its parameters.
fn component_text(component: &AnalysisComponent) -> String {
    let name = escape_control(&component.name);
    if component.params.is_empty() {
        return name;
    }
    let params: Vec<String> = component
        .params
        .iter()
        .map(|(key, value)| format!("{}={}", escape_control(key), escape_control(value)))
        .collect();
    format!("{name}({})", params.join(", "))
}

/// A part of the custom chain.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Section {
    CharFilters,
    Tokenizer,
    TokenFilters,
}

impl Section {
    fn heading(self) -> &'static str {
        match self {
            Section::CharFilters => "Char filters",
            Section::Tokenizer => "Tokenizer",
            Section::TokenFilters => "Token filters",
        }
    }

    /// What one component of the section is called.
    fn noun(self) -> &'static str {
        match self {
            Section::CharFilters => "char filter",
            Section::Tokenizer => "tokenizer",
            Section::TokenFilters => "token filter",
        }
    }

    /// The names of the section's components, and their class names.
    fn available(self, components: &GetAnalysisComponentsResult) -> Vec<Choice> {
        let available = match self {
            Section::CharFilters => &components.char_filters,
            Section::Tokenizer => &components.tokenizers,
            Section::TokenFilters => &components.token_filters,
        };
        available
            .iter()
            .map(|component| Choice {
                name: component.name.clone(),
                class_name: component.class_name.clone(),
            })
            .collect()
    }
}

/// What the picker offers: a component by its SPI name, or a preset by its simple class name.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Choice {
    name: String,
    class_name: String,
}

/// One selectable line of the popup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Row {
    /// Standard, preset or custom.
    Kind,
    /// The preset's class.
    Preset,
    /// A preset's stop words or stem exclusions.
    WordSet(WordSetKind),
    /// A preset's options (ADR 0023).
    MaxTokenLength,
    StemOverride,
    UserDictionary(Language),
    JapaneseMode,
    DecompoundMode,
    StopTags(Language),
    UnknownWords,
    ConfigDir,
    /// A component: its section and its index there (0 for the tokenizer).
    Component(Section, usize),
    /// A parameter of a component, by its index in key order.
    Param(Section, usize, usize),
    /// Adds a char filter or token filter at the end.
    Add(Section),
    /// The wrappers, on or off, and their fields while on (ADR 0023).
    Shingles,
    Shingle(ShingleField),
    TokenLimit,
    Limit(LimitField),
}

/// Where a picked component goes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Target {
    Replace(usize),
    Insert(usize),
}

/// What a picker picks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Pick {
    Component(Section, Target),
    Preset,
}

/// The list of components or presets to pick from, filtered as you type.
#[derive(Debug)]
struct Picker {
    pick: Pick,
    filter: LineInput,
    selected: usize,
}

/// What a line input edits.
#[derive(Debug, Clone, PartialEq, Eq)]
enum TextTarget {
    ConfigDir,
    WordSet(WordSetKind),
    MaxTokenLength,
    StemOverride,
    UserDictionary(Language),
    StopTags(Language),
    ShingleSize,
    ShingleSeparator,
    ShingleFiller,
    MaxTokenCount,
    /// A parameter of a component as `name=value`; `key` is the one it replaces, if any.
    Param {
        section: Section,
        index: usize,
        key: Option<String>,
    },
}

#[derive(Debug)]
struct TextEdit {
    target: TextTarget,
    input: LineInput,
    error: Option<String>,
}

#[derive(Debug)]
enum Editing {
    None,
    Pick(Picker),
    Text(TextEdit),
}

/// The popup: a draft of the analyzer, which the caller tries before it takes it.
#[derive(Debug)]
pub struct AnalyzerForm {
    draft: AnalyzerSettings,
    /// The presets, and what a custom analyzer can be built from; `None` while they are not
    /// loaded.
    components: Option<GetAnalysisComponentsResult>,
    selected: usize,
    editing: Editing,
    /// Why the draft was refused, until the next change.
    error: Option<String>,
}

impl AnalyzerForm {
    pub fn new(
        settings: &AnalyzerSettings,
        components: Option<GetAnalysisComponentsResult>,
    ) -> Self {
        Self {
            draft: settings.clone(),
            components,
            selected: 0,
            editing: Editing::None,
            error: None,
        }
    }

    /// Shows why the server refused the draft; the popup stays open to fix it.
    pub fn set_error(&mut self, error: String) {
        self.error = Some(error);
    }

    pub fn set_components(&mut self, components: GetAnalysisComponentsResult) {
        self.components = Some(components);
    }

    fn rows(&self) -> Vec<Row> {
        let mut rows = vec![Row::Kind];
        match self.draft.kind {
            Kind::Standard => {}
            Kind::Preset => {
                rows.push(Row::Preset);
                rows.extend(
                    WordSetKind::ALL
                        .into_iter()
                        .filter(|kind| self.support(*kind) != Some(WordSetSupport::None))
                        .map(Row::WordSet),
                );
                if self.takes(PresetOption::MaxTokenLength) {
                    rows.push(Row::MaxTokenLength);
                }
                if self.takes(PresetOption::StemOverride) {
                    rows.push(Row::StemOverride);
                }
                if self.takes(PresetOption::Japanese) {
                    rows.extend([
                        Row::UserDictionary(Language::Japanese),
                        Row::JapaneseMode,
                        Row::StopTags(Language::Japanese),
                    ]);
                }
                if self.takes(PresetOption::Korean) {
                    rows.extend([
                        Row::UserDictionary(Language::Korean),
                        Row::DecompoundMode,
                        Row::StopTags(Language::Korean),
                        Row::UnknownWords,
                    ]);
                }
            }
            Kind::Custom => self.chain_rows(&mut rows),
        }
        rows.push(Row::Shingles);
        if self.draft.shingles.is_some() {
            rows.extend(ShingleField::ALL.map(Row::Shingle));
        }
        rows.push(Row::TokenLimit);
        if self.draft.limit_token_count.is_some() {
            rows.extend(LimitField::ALL.map(Row::Limit));
        }
        rows
    }

    fn chain_rows(&self, rows: &mut Vec<Row>) {
        rows.push(Row::ConfigDir);
        let chain = &self.draft.chain;
        for (section, components) in [
            (Section::CharFilters, &chain.char_filters),
            (Section::Tokenizer, &vec![chain.tokenizer.clone()]),
            (Section::TokenFilters, &chain.token_filters),
        ] {
            for (index, component) in components.iter().enumerate() {
                rows.push(Row::Component(section, index));
                rows.extend(
                    (0..component.params.len()).map(|param| Row::Param(section, index, param)),
                );
            }
            if section != Section::Tokenizer {
                rows.push(Row::Add(section));
            }
        }
    }

    /// The draft's preset as listed; `None` while the presets are not loaded, or for a preset
    /// that is not listed.
    fn listed_preset(&self) -> Option<&AvailableAnalyzer> {
        self.components
            .as_ref()?
            .analyzers
            .iter()
            .find(|analyzer| analyzer.class_name == self.draft.preset)
    }

    /// Whether the draft's preset takes the word set; `None` while the presets are not loaded, or
    /// for a preset that is not listed.
    fn support(&self, kind: WordSetKind) -> Option<WordSetSupport> {
        let preset = self.listed_preset()?;
        Some(match kind {
            WordSetKind::Stopwords => preset.stopwords,
            WordSetKind::StemExclusion => preset.stem_exclusion,
        })
    }

    /// Whether the draft's preset takes the option. Without the list, the server judges: every
    /// preset may take the token length and stem overrides, and Kuromoji's and Nori's analyzers,
    /// by class name, their options.
    fn takes(&self, option: PresetOption) -> bool {
        match self.listed_preset() {
            Some(preset) => preset.options.contains(&option),
            None => match option {
                PresetOption::Japanese => self.draft.preset == JAPANESE_ANALYZER,
                PresetOption::Korean => self.draft.preset == KOREAN_ANALYZER,
                PresetOption::MaxTokenLength | PresetOption::StemOverride => true,
            },
        }
    }

    fn select(&mut self, row: Row) {
        if let Some(index) = self.rows().iter().position(|r| *r == row) {
            self.selected = index;
        }
    }

    fn list_mut(&mut self, section: Section) -> Option<&mut Vec<AnalysisComponent>> {
        match section {
            Section::CharFilters => Some(&mut self.draft.chain.char_filters),
            Section::Tokenizer => None,
            Section::TokenFilters => Some(&mut self.draft.chain.token_filters),
        }
    }

    fn component(&self, section: Section, index: usize) -> Option<&AnalysisComponent> {
        let chain = &self.draft.chain;
        match section {
            Section::CharFilters => chain.char_filters.get(index),
            Section::Tokenizer => Some(&chain.tokenizer),
            Section::TokenFilters => chain.token_filters.get(index),
        }
    }

    fn component_mut(&mut self, section: Section, index: usize) -> Option<&mut AnalysisComponent> {
        let chain = &mut self.draft.chain;
        match section {
            Section::CharFilters => chain.char_filters.get_mut(index),
            Section::Tokenizer => Some(&mut chain.tokenizer),
            Section::TokenFilters => chain.token_filters.get_mut(index),
        }
    }

    /// While a picker or a line input is open, it takes the keys. Otherwise ↑↓ select a row;
    /// Space or → changes it (standard, preset or custom, ← the other way round; the preset, a
    /// word set, an option, the config directory, another component, a parameter; a wrapper on or
    /// off); `a` adds a filter after the selected one, `p` a parameter to the selected component,
    /// `d` deletes (a word set or an option goes back to its default, a wrapper off), `K`/`J`
    /// move a filter up or down; Enter applies, Esc cancels.
    pub fn handle_key(&mut self, key: KeyEvent) -> FormEvent<AnalyzerSettings> {
        match &mut self.editing {
            Editing::Pick(_) => {
                self.pick_key(key);
                return FormEvent::None;
            }
            Editing::Text(_) => {
                self.text_key(key);
                return FormEvent::None;
            }
            Editing::None => {}
        }
        let rows = self.rows();
        let last = rows.len() - 1;
        let row = rows[self.selected.min(last)];
        match key.code {
            KeyCode::Enter => return FormEvent::Apply(self.draft.clone()),
            KeyCode::Esc => return FormEvent::Cancel,
            KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
            KeyCode::Down | KeyCode::Char('j') => self.selected = (self.selected + 1).min(last),
            KeyCode::PageUp => self.selected = self.selected.saturating_sub(10),
            KeyCode::PageDown => self.selected = (self.selected + 10).min(last),
            KeyCode::Home | KeyCode::Char('g') => self.selected = 0,
            KeyCode::End | KeyCode::Char('G') => self.selected = last,
            KeyCode::Char(' ') | KeyCode::Right | KeyCode::Char('l') => self.change(row, true),
            KeyCode::Left | KeyCode::Char('h') => self.change(row, false),
            KeyCode::Char('a') => self.add_after(row),
            KeyCode::Char('p') => {
                if let Row::Component(section, index) | Row::Param(section, index, _) = row {
                    self.edit_text(TextTarget::Param {
                        section,
                        index,
                        key: None,
                    });
                }
            }
            KeyCode::Char('d') | KeyCode::Delete | KeyCode::Backspace => self.delete(row),
            KeyCode::Char('K') => self.move_component(row, false),
            KeyCode::Char('J') => self.move_component(row, true),
            _ => {}
        }
        FormEvent::None
    }

    fn change(&mut self, row: Row, forward: bool) {
        match row {
            Row::Kind => {
                self.draft.kind = self.draft.kind.cycled(forward);
                self.error = None;
            }
            Row::Preset => self.open_picker(Pick::Preset),
            Row::WordSet(kind) => self.edit_text(TextTarget::WordSet(kind)),
            Row::ConfigDir => self.edit_text(TextTarget::ConfigDir),
            Row::Component(section, index) => self.pick(section, Target::Replace(index)),
            Row::Param(section, index, param) => {
                let key = self
                    .component(section, index)
                    .and_then(|component| component.params.keys().nth(param))
                    .cloned();
                self.edit_text(TextTarget::Param {
                    section,
                    index,
                    key,
                });
            }
            Row::Add(section) => {
                let end = self.list_mut(section).map_or(0, |list| list.len());
                self.pick(section, Target::Insert(end));
            }
            Row::MaxTokenLength => self.edit_text(TextTarget::MaxTokenLength),
            Row::StemOverride => self.edit_text(TextTarget::StemOverride),
            Row::UserDictionary(language) => self.edit_text(TextTarget::UserDictionary(language)),
            Row::StopTags(language) => self.edit_text(TextTarget::StopTags(language)),
            Row::JapaneseMode => {
                let modes = [
                    None,
                    Some(JapaneseMode::Normal),
                    Some(JapaneseMode::Search),
                    Some(JapaneseMode::Extended),
                ];
                self.draft.japanese.mode = cycled(&modes, self.draft.japanese.mode, forward);
                self.error = None;
            }
            Row::DecompoundMode => {
                let modes = [
                    None,
                    Some(KoreanDecompoundMode::None),
                    Some(KoreanDecompoundMode::Discard),
                    Some(KoreanDecompoundMode::Mixed),
                ];
                let korean = &mut self.draft.korean;
                korean.decompound_mode = cycled(&modes, korean.decompound_mode, forward);
                self.error = None;
            }
            Row::UnknownWords => {
                let korean = &mut self.draft.korean;
                korean.output_unknown_unigrams = cycled(
                    &[None, Some(true), Some(false)],
                    korean.output_unknown_unigrams,
                    forward,
                );
                self.error = None;
            }
            Row::Shingles => {
                self.draft.shingles = match self.draft.shingles {
                    Some(_) => None,
                    None => Some(Shingles::default()),
                };
                self.error = None;
            }
            Row::Shingle(field) => match field {
                ShingleField::Size => self.edit_text(TextTarget::ShingleSize),
                ShingleField::Separator => self.edit_text(TextTarget::ShingleSeparator),
                ShingleField::Filler => self.edit_text(TextTarget::ShingleFiller),
                ShingleField::Unigrams => {
                    if let Some(shingles) = &mut self.draft.shingles {
                        // Always, never, or only without shingles.
                        let unigrams = [(true, false), (false, false), (false, true)];
                        (
                            shingles.output_unigrams,
                            shingles.output_unigrams_if_no_shingles,
                        ) = cycled(
                            &unigrams,
                            (
                                shingles.output_unigrams,
                                shingles.output_unigrams_if_no_shingles,
                            ),
                            forward,
                        );
                        self.error = None;
                    }
                }
            },
            Row::TokenLimit => {
                self.draft.limit_token_count = match self.draft.limit_token_count {
                    Some(_) => None,
                    None => Some(LimitTokenCount {
                        max_token_count: DEFAULT_MAX_TOKEN_COUNT,
                        consume_all_tokens: false,
                    }),
                };
                self.error = None;
            }
            Row::Limit(LimitField::MaxTokens) => self.edit_text(TextTarget::MaxTokenCount),
            Row::Limit(LimitField::ConsumeAll) => {
                if let Some(limit) = &mut self.draft.limit_token_count {
                    limit.consume_all_tokens = !limit.consume_all_tokens;
                    self.error = None;
                }
            }
        }
    }

    /// Adds a filter after the selected one, or at the end of the selected section.
    fn add_after(&mut self, row: Row) {
        match row {
            Row::Component(section, index) | Row::Param(section, index, _)
                if section != Section::Tokenizer =>
            {
                self.pick(section, Target::Insert(index + 1));
            }
            Row::Add(_) => self.change(row, true),
            _ => {}
        }
    }

    fn pick(&mut self, section: Section, target: Target) {
        self.open_picker(Pick::Component(section, target));
    }

    fn open_picker(&mut self, pick: Pick) {
        self.editing = Editing::Pick(Picker {
            pick,
            filter: LineInput::new(""),
            selected: 0,
        });
    }

    fn edit_text(&mut self, target: TextTarget) {
        let text = match &target {
            TextTarget::ConfigDir => self.draft.chain.config_dir.clone().unwrap_or_default(),
            TextTarget::WordSet(kind) => word_set_input(self.draft.word_set(*kind)),
            TextTarget::Param {
                section,
                index,
                key: Some(key),
            } => {
                let value = self
                    .component(*section, *index)
                    .and_then(|component| component.params.get(key))
                    .cloned()
                    .unwrap_or_default();
                format!("{key}={value}")
            }
            TextTarget::Param { key: None, .. } => String::new(),
            TextTarget::MaxTokenLength => self
                .draft
                .max_token_length
                .map(|length| length.to_string())
                .unwrap_or_default(),
            TextTarget::StemOverride => stem_dictionary_input(self.draft.stem_override.as_ref()),
            TextTarget::UserDictionary(language) => self
                .draft
                .user_dictionary(*language)
                .cloned()
                .unwrap_or_default(),
            TextTarget::StopTags(language) => word_set_input(self.draft.stop_tags(*language)),
            TextTarget::ShingleSize => self
                .draft
                .shingles
                .as_ref()
                .map(|shingles| {
                    if shingles.min_shingle_size == shingles.max_shingle_size {
                        shingles.min_shingle_size.to_string()
                    } else {
                        format!(
                            "{}-{}",
                            shingles.min_shingle_size, shingles.max_shingle_size
                        )
                    }
                })
                .unwrap_or_default(),
            TextTarget::ShingleSeparator => self
                .draft
                .shingles
                .as_ref()
                .map(|shingles| shingles.token_separator.clone())
                .unwrap_or_default(),
            TextTarget::ShingleFiller => self
                .draft
                .shingles
                .as_ref()
                .map(|shingles| shingles.filler_token.clone())
                .unwrap_or_default(),
            TextTarget::MaxTokenCount => self
                .draft
                .limit_token_count
                .map(|limit| limit.max_token_count.to_string())
                .unwrap_or_default(),
        };
        self.editing = Editing::Text(TextEdit {
            target,
            input: LineInput::new(&text),
            error: None,
        });
    }

    fn delete(&mut self, row: Row) {
        match row {
            Row::ConfigDir => self.draft.chain.config_dir = None,
            Row::WordSet(kind) => *self.draft.word_set_mut(kind) = None,
            Row::Component(section, index) => {
                let Some(list) = self.list_mut(section) else {
                    return;
                };
                list.remove(index);
                // The selection stays on the row that moved up, or the add row.
                self.selected = self.selected.min(self.rows().len() - 1);
            }
            Row::Param(section, index, param) => {
                if let Some(component) = self.component_mut(section, index)
                    && let Some(key) = component.params.keys().nth(param).cloned()
                {
                    component.params.remove(&key);
                }
                self.selected = self.selected.min(self.rows().len() - 1);
            }
            Row::MaxTokenLength => self.draft.max_token_length = None,
            Row::StemOverride => self.draft.stem_override = None,
            Row::UserDictionary(language) => *self.draft.user_dictionary_mut(language) = None,
            Row::StopTags(language) => *self.draft.stop_tags_mut(language) = None,
            Row::JapaneseMode => self.draft.japanese.mode = None,
            Row::DecompoundMode => self.draft.korean.decompound_mode = None,
            Row::UnknownWords => self.draft.korean.output_unknown_unigrams = None,
            Row::Shingles => {
                self.draft.shingles = None;
                self.selected = self.selected.min(self.rows().len() - 1);
            }
            Row::Shingle(field) => {
                // Back to Lucene's default.
                let default = Shingles::default();
                let Some(shingles) = &mut self.draft.shingles else {
                    return;
                };
                match field {
                    ShingleField::Size => {
                        shingles.min_shingle_size = default.min_shingle_size;
                        shingles.max_shingle_size = default.max_shingle_size;
                    }
                    ShingleField::Separator => shingles.token_separator = default.token_separator,
                    ShingleField::Unigrams => {
                        shingles.output_unigrams = default.output_unigrams;
                        shingles.output_unigrams_if_no_shingles =
                            default.output_unigrams_if_no_shingles;
                    }
                    ShingleField::Filler => shingles.filler_token = default.filler_token,
                }
            }
            Row::TokenLimit => {
                self.draft.limit_token_count = None;
                self.selected = self.selected.min(self.rows().len() - 1);
            }
            Row::Limit(field) => {
                let Some(limit) = &mut self.draft.limit_token_count else {
                    return;
                };
                match field {
                    LimitField::MaxTokens => limit.max_token_count = DEFAULT_MAX_TOKEN_COUNT,
                    LimitField::ConsumeAll => limit.consume_all_tokens = false,
                }
            }
            Row::Kind | Row::Preset | Row::Add(_) => return,
        }
        self.error = None;
    }

    fn move_component(&mut self, row: Row, down: bool) {
        let (Row::Component(section, index) | Row::Param(section, index, _)) = row else {
            return;
        };
        let Some(list) = self.list_mut(section) else {
            return;
        };
        let to = if down {
            index + 1
        } else {
            let Some(to) = index.checked_sub(1) else {
                return;
            };
            to
        };
        if to >= list.len() {
            return;
        }
        list.swap(index, to);
        self.select(Row::Component(section, to));
        self.error = None;
    }

    /// The presets, or the components of the picker's section, whose name contains the filter,
    /// ignoring case.
    fn matches(&self, picker: &Picker) -> Vec<Choice> {
        let filter = picker.filter.text().to_lowercase();
        let Some(components) = &self.components else {
            return Vec::new();
        };
        let choices = match picker.pick {
            Pick::Component(section, _) => section.available(components),
            Pick::Preset => components
                .analyzers
                .iter()
                .map(|analyzer| Choice {
                    name: analyzer.name.clone(),
                    class_name: analyzer.class_name.clone(),
                })
                .collect(),
        };
        choices
            .into_iter()
            .filter(|choice| choice.name.to_lowercase().contains(&filter))
            .collect()
    }

    /// ↑↓ select, typing filters, Enter takes the selected component or preset (or the typed
    /// name, or class name, if none matches), Esc goes back.
    fn pick_key(&mut self, key: KeyEvent) {
        let Editing::Pick(picker) = &self.editing else {
            return;
        };
        let matches = self.matches(picker);
        let Editing::Pick(picker) = &mut self.editing else {
            return;
        };
        let last = matches.len().saturating_sub(1);
        match key.code {
            KeyCode::Esc => self.editing = Editing::None,
            KeyCode::Up => picker.selected = picker.selected.saturating_sub(1),
            KeyCode::Down => picker.selected = (picker.selected + 1).min(last),
            KeyCode::PageUp => picker.selected = picker.selected.saturating_sub(10),
            KeyCode::PageDown => picker.selected = (picker.selected + 10).min(last),
            KeyCode::Enter => {
                let pick = picker.pick;
                let chosen = matches
                    .get(picker.selected.min(last))
                    .map(|choice| match pick {
                        Pick::Component(..) => choice.name.clone(),
                        Pick::Preset => choice.class_name.clone(),
                    });
                let name = chosen.unwrap_or_else(|| picker.filter.text().trim().to_owned());
                if name.is_empty() {
                    return;
                }
                self.editing = Editing::None;
                match pick {
                    Pick::Component(section, target) => self.put(section, target, name),
                    Pick::Preset => {
                        self.draft.preset = name;
                        // A set or an option that the new preset doesn't take would fail.
                        for kind in WordSetKind::ALL {
                            if self.support(kind) == Some(WordSetSupport::None) {
                                *self.draft.word_set_mut(kind) = None;
                            }
                        }
                        if !self.takes(PresetOption::MaxTokenLength) {
                            self.draft.max_token_length = None;
                        }
                        if !self.takes(PresetOption::StemOverride) {
                            self.draft.stem_override = None;
                        }
                        if !self.takes(PresetOption::Japanese) {
                            self.draft.japanese = JapaneseOptions::default();
                        }
                        if !self.takes(PresetOption::Korean) {
                            self.draft.korean = KoreanOptions::default();
                        }
                        self.error = None;
                    }
                }
            }
            _ => {
                if picker.filter.handle_key(key, word_start) == Edit::Changed {
                    picker.selected = 0;
                }
            }
        }
    }

    /// Puts the component `name`, without parameters, where the picker was opened for.
    fn put(&mut self, section: Section, target: Target, name: String) {
        let new = AnalysisComponent {
            name,
            params: BTreeMap::new(),
        };
        let index = match (target, section) {
            (Target::Replace(_), Section::Tokenizer) => {
                self.draft.chain.tokenizer = new;
                0
            }
            (Target::Replace(index), _) => {
                if let Some(list) = self.list_mut(section)
                    && let Some(component) = list.get_mut(index)
                {
                    *component = new;
                }
                index
            }
            (Target::Insert(index), _) => {
                let Some(list) = self.list_mut(section) else {
                    return;
                };
                let index = index.min(list.len());
                list.insert(index, new);
                index
            }
        };
        self.select(Row::Component(section, index));
        self.error = None;
    }

    /// Enter takes the text, Esc goes back; any other key edits it.
    fn text_key(&mut self, key: KeyEvent) {
        let Editing::Text(edit) = &mut self.editing else {
            return;
        };
        match key.code {
            KeyCode::Esc => self.editing = Editing::None,
            KeyCode::Enter => {
                let text = edit.input.text().to_owned();
                let target = edit.target.clone();
                match self.take_text(target, &text) {
                    Ok(()) => {
                        self.editing = Editing::None;
                        self.error = None;
                    }
                    Err(error) => {
                        if let Editing::Text(edit) = &mut self.editing {
                            edit.error = Some(error);
                        }
                    }
                }
            }
            _ => {
                edit.input.handle_key(key, word_start);
            }
        }
    }

    /// Applies an edited text: a config directory or a user dictionary (none if blank), a word
    /// set, stop tags, stem overrides or the token length (the default if blank), a shingle size
    /// or token count (the default if blank), a shingle separator or filler (as typed), or a
    /// parameter as `name=value` (removed if blank).
    fn take_text(&mut self, target: TextTarget, typed: &str) -> Result<(), String> {
        let text = typed.trim();
        let (section, index, old) = match target {
            TextTarget::ConfigDir => {
                self.draft.chain.config_dir = (!text.is_empty()).then(|| text.to_owned());
                return Ok(());
            }
            TextTarget::WordSet(kind) => {
                *self.draft.word_set_mut(kind) = parse_word_set(text)?;
                return Ok(());
            }
            TextTarget::MaxTokenLength => {
                self.draft.max_token_length = parse_number(text, 1, MAX_TOKEN_LENGTH)?;
                return Ok(());
            }
            TextTarget::StemOverride => {
                self.draft.stem_override = parse_stem_dictionary(text)?;
                return Ok(());
            }
            TextTarget::UserDictionary(language) => {
                *self.draft.user_dictionary_mut(language) =
                    (!text.is_empty()).then(|| text.to_owned());
                return Ok(());
            }
            TextTarget::StopTags(language) => {
                *self.draft.stop_tags_mut(language) = parse_word_set(text)?;
                return Ok(());
            }
            TextTarget::ShingleSize => {
                let default = Shingles::default();
                let (min, max) = parse_shingle_size(text)?
                    .unwrap_or((default.min_shingle_size, default.max_shingle_size));
                if let Some(shingles) = &mut self.draft.shingles {
                    shingles.min_shingle_size = min;
                    shingles.max_shingle_size = max;
                }
                return Ok(());
            }
            TextTarget::ShingleSeparator => {
                if let Some(shingles) = &mut self.draft.shingles {
                    shingles.token_separator = typed.to_owned();
                }
                return Ok(());
            }
            TextTarget::ShingleFiller => {
                if let Some(shingles) = &mut self.draft.shingles {
                    shingles.filler_token = typed.to_owned();
                }
                return Ok(());
            }
            TextTarget::MaxTokenCount => {
                let count = parse_number(text, 1, i32::MAX)?.unwrap_or(DEFAULT_MAX_TOKEN_COUNT);
                if let Some(limit) = &mut self.draft.limit_token_count {
                    limit.max_token_count = count;
                }
                return Ok(());
            }
            TextTarget::Param {
                section,
                index,
                key,
            } => (section, index, key),
        };
        let parsed = if text.is_empty() {
            None
        } else {
            match text.split_once('=') {
                Some((key, value)) if !key.trim().is_empty() => {
                    Some((key.trim().to_owned(), value.trim().to_owned()))
                }
                _ => return Err("Write the parameter as name=value.".to_owned()),
            }
        };
        let Some(component) = self.component_mut(section, index) else {
            return Ok(());
        };
        if let Some(old) = &old {
            component.params.remove(old);
        }
        let Some((key, value)) = parsed else {
            self.selected = self.selected.min(self.rows().len() - 1);
            return Ok(());
        };
        component.params.insert(key.clone(), value);
        let param = component
            .params
            .keys()
            .position(|k| *k == key)
            .unwrap_or_default();
        self.select(Row::Param(section, index, param));
        Ok(())
    }

    /// The keys for the footer.
    pub fn keys(&self) -> &'static str {
        match &self.editing {
            Editing::Pick(_) => " type to filter   ↑↓ select   ⏎ take   Esc back",
            Editing::Text(_) => " ⏎ take   Esc back   ←→ Ctrl+A/E/U/K/W edit",
            Editing::None => match self.draft.kind {
                Kind::Custom => {
                    " ␣ change   a add filter   p add parameter   d delete   K/J move   ⏎ apply   \
                     Esc cancel   ↑↓"
                }
                Kind::Preset => {
                    " ␣ change   d default   ←→ standard/preset/custom   ⏎ apply   Esc cancel   ↑↓"
                }
                Kind::Standard => {
                    " ␣ change   ←→ standard/preset/custom   d off   ⏎ apply   Esc cancel   ↑↓"
                }
            },
        }
    }

    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let rows = self.rows();
        let selected = self.selected.min(rows.len() - 1);
        let mut lines = Vec::new();
        let mut selected_line = 0;
        let mut last_heading = None;
        for (i, row) in rows.iter().enumerate() {
            let heading = match row {
                Row::Component(section, _) | Row::Add(section) => Some(section.heading()),
                Row::Shingles => Some("Wrappers"),
                _ => None,
            };
            if let Some(heading) = heading
                && last_heading != Some(heading)
            {
                last_heading = Some(heading);
                lines.push(Line::from(""));
                lines.push(Line::from(heading).style(Style::new().add_modifier(Modifier::BOLD)));
            }
            if i == selected {
                selected_line = lines.len();
            }
            let line = self.row_line(*row);
            lines.push(if i == selected {
                line.style(Style::new().add_modifier(Modifier::REVERSED))
            } else {
                line
            });
        }
        let mut notes: Vec<&str> = match self.draft.kind {
            Kind::Standard => vec![
                "Lucene's StandardAnalyzer: the standard tokenizer, then lowercase.",
                "Space switches to a preset analyzer, such as EnglishAnalyzer, or to a custom \
                 analyzer of your own chain.",
            ],
            Kind::Preset => vec![
                "One of Lucene's analyzers, by class name, with its default settings.",
                "Stop words and stem exclusions, for an analyzer that takes them: words separated \
                 by spaces or commas, - for no words, or @ and a word list file (UTF-8, one word \
                 per line, # for comments).",
            ],
            Kind::Custom => Vec::new(),
        };
        if rows.contains(&Row::StemOverride) {
            notes.push(
                "Stem overrides: word=stem pairs, - for none, or @ and a file of word<TAB>stem \
                 lines.",
            );
        }
        if rows.iter().any(|row| matches!(row, Row::StopTags(_))) {
            notes.push(
                "Stop tags like stop words. A user dictionary is a file in Kuromoji's or Nori's \
                 own format.",
            );
        }
        if !notes.is_empty() {
            lines.push(Line::from(""));
            let width = usize::from(area.width.min(WIDTH).saturating_sub(2));
            for note in &notes {
                lines.extend(
                    wrap(note, width, usize::MAX)
                        .into_iter()
                        .map(|line| Line::from(line).style(dim())),
                );
            }
        }
        if let Some(error) = &self.error {
            lines.push(Line::from(""));
            let width = usize::from(area.width.min(WIDTH).saturating_sub(2));
            for line in wrap(error, width, usize::MAX) {
                lines.push(Line::from(line).style(Style::new().fg(Color::Red)));
            }
            selected_line = lines.len() - 1;
        }
        render_popup(frame, area, " Analyzer ", lines, selected_line, WIDTH);
        match &self.editing {
            Editing::None => {}
            Editing::Pick(picker) => self.render_picker(frame, area, picker),
            Editing::Text(edit) => render_text(frame, area, edit),
        }
    }

    fn row_line(&self, row: Row) -> Line<'static> {
        match row {
            Row::Kind => Line::from(format!("Analyzer        {}", self.draft.kind.name())),
            Row::Preset => Line::from(vec![
                Span::raw(format!(
                    "Class           {}  ",
                    escape_control(simple_name(&self.draft.preset))
                )),
                Span::styled(escape_control(package(&self.draft.preset)), dim()),
            ]),
            Row::WordSet(kind) => {
                let label = format!("{:<16}", kind.label());
                match self.draft.word_set(kind) {
                    Some(set) => Line::from(format!("{label}{}", word_set_text(set))),
                    None => {
                        let (text, style) = match (kind, self.support(kind)) {
                            (WordSetKind::Stopwords, Some(WordSetSupport::Required)) => (
                                "none: this analyzer needs some",
                                Style::new().fg(Color::Red),
                            ),
                            (WordSetKind::Stopwords, _) => ("default: the analyzer's own", dim()),
                            (WordSetKind::StemExclusion, _) => ("none", dim()),
                        };
                        Line::from(vec![Span::raw(label), Span::styled(text, style)])
                    }
                }
            }
            Row::MaxTokenLength => value_line(
                "Token length",
                self.draft.max_token_length.map(|length| length.to_string()),
                "default: the analyzer's own",
            ),
            Row::StemOverride => value_line(
                "Stem overrides",
                self.draft.stem_override.as_ref().map(stem_dictionary_text),
                "default: the analyzer's own",
            ),
            Row::UserDictionary(language) => value_line(
                "User dictionary",
                self.draft
                    .user_dictionary(language)
                    .map(|path| escape_control(path)),
                "none",
            ),
            Row::JapaneseMode => value_line(
                "Mode",
                self.draft
                    .japanese
                    .mode
                    .map(|mode| japanese_mode_name(mode).to_owned()),
                "default: search",
            ),
            Row::DecompoundMode => value_line(
                "Decompound",
                self.draft
                    .korean
                    .decompound_mode
                    .map(|mode| decompound_mode_name(mode).to_owned()),
                "default: discard",
            ),
            Row::StopTags(language) => value_line(
                "Stop tags",
                self.draft.stop_tags(language).map(word_set_text),
                "default: the analyzer's own",
            ),
            Row::UnknownWords => value_line(
                "Unknown words",
                self.draft
                    .korean
                    .output_unknown_unigrams
                    .map(|split| unknown_words_text(split).to_owned()),
                "default: kept whole",
            ),
            Row::Shingles => value_line(
                "Shingles",
                self.draft.shingles.as_ref().map(|_| "on".to_owned()),
                "off",
            ),
            Row::Shingle(field) => {
                let Some(shingles) = &self.draft.shingles else {
                    return Line::from("");
                };
                let (label, value) = match field {
                    ShingleField::Size => ("  size", shingle_size_text(shingles)),
                    ShingleField::Separator => ("  separator", quoted(&shingles.token_separator)),
                    ShingleField::Unigrams => ("  unigrams", unigrams_text(shingles).to_owned()),
                    ShingleField::Filler => ("  filler", quoted(&shingles.filler_token)),
                };
                value_line(label, Some(value), "")
            }
            Row::TokenLimit => value_line(
                "Token limit",
                self.draft.limit_token_count.map(|_| "on".to_owned()),
                "off",
            ),
            Row::Limit(field) => {
                let Some(limit) = &self.draft.limit_token_count else {
                    return Line::from("");
                };
                let (label, value) = match field {
                    LimitField::MaxTokens => ("  max tokens", limit.max_token_count.to_string()),
                    LimitField::ConsumeAll => (
                        "  consume all",
                        if limit.consume_all_tokens {
                            "yes"
                        } else {
                            "no"
                        }
                        .to_owned(),
                    ),
                };
                value_line(label, Some(value), "")
            }
            Row::ConfigDir => match &self.draft.chain.config_dir {
                Some(dir) => Line::from(format!("Config dir      {}", escape_control(dir))),
                None => Line::from(vec![
                    Span::raw("Config dir      "),
                    Span::styled("none: resources from the classpath", dim()),
                ]),
            },
            Row::Component(section, index) => {
                let name = self
                    .component(section, index)
                    .map(|component| escape_control(&component.name))
                    .unwrap_or_default();
                Line::from(format!("  {name}"))
            }
            Row::Param(section, index, param) => {
                let (key, value) = self
                    .component(section, index)
                    .and_then(|component| component.params.iter().nth(param))
                    .map(|(key, value)| (escape_control(key), escape_control(value)))
                    .unwrap_or_default();
                Line::from(format!("      {key} = {value}"))
            }
            Row::Add(section) => Line::from(format!("  + {}", section.noun())).style(dim()),
        }
    }

    fn render_picker(&self, frame: &mut Frame, area: Rect, picker: &Picker) {
        let matches = self.matches(picker);
        let selected = picker.selected.min(matches.len().saturating_sub(1));
        let mut lines = vec![
            Line::from(format!("Filter: {}", picker.filter.text())),
            Line::from(""),
        ];
        let typed = match picker.pick {
            Pick::Component(..) => "name",
            Pick::Preset => "class name",
        };
        if self.components.is_none() {
            lines.push(
                Line::from(format!(
                    "The components are not loaded; Enter takes the typed {typed}."
                ))
                .style(dim()),
            );
        } else if matches.is_empty() {
            lines
                .push(Line::from(format!("No match; Enter takes the typed {typed}.")).style(dim()));
        }
        for (i, choice) in matches.iter().enumerate() {
            // A component's factory class, or a preset's package.
            let detail = match picker.pick {
                Pick::Component(..) => simple_name(&choice.class_name),
                Pick::Preset => package(&choice.class_name),
            };
            let line = Line::from(vec![
                Span::raw(format!("{:<28} ", escape_control(&choice.name))),
                Span::styled(escape_control(detail), dim()),
            ]);
            lines.push(if i == selected {
                line.style(Style::new().add_modifier(Modifier::REVERSED))
            } else {
                line
            });
        }
        let title = match picker.pick {
            Pick::Component(section, _) => format!(" Pick a {} ", section.noun()),
            Pick::Preset => " Pick a preset analyzer ".to_owned(),
        };
        render_popup(frame, area, &title, lines, selected + 2, WIDTH - 8);
    }
}

/// A one-line input in a popup of its own, with the cursor.
fn render_text(frame: &mut Frame, area: Rect, edit: &TextEdit) {
    let title = match edit.target {
        TextTarget::ConfigDir => " Config directory (blank for none) ",
        TextTarget::WordSet(WordSetKind::Stopwords) => {
            " Stop words: words, - for none, or @file (blank for the default) "
        }
        TextTarget::WordSet(WordSetKind::StemExclusion) => {
            " Stem exclusions: words, or @file (blank for none) "
        }
        TextTarget::Param { .. } => " Parameter: name=value (blank removes it) ",
        TextTarget::MaxTokenLength => " Token length: 1 to 1048576 (blank for the default) ",
        TextTarget::StemOverride => {
            " Stem overrides: word=stem …, - for none, or @file (blank for the default) "
        }
        TextTarget::UserDictionary(_) => " User dictionary file (blank for none) ",
        TextTarget::StopTags(_) => {
            " Stop tags: tags, - for none, or @file (blank for the default) "
        }
        TextTarget::ShingleSize => " Shingle size: min-max, such as 2-3 (blank for 2) ",
        TextTarget::ShingleSeparator => " Token separator, as typed ",
        TextTarget::ShingleFiller => " Filler token, as typed ",
        TextTarget::MaxTokenCount => " Max tokens (blank for 10000) ",
    };
    let width = area.width.min(WIDTH - 4);
    let height = if edit.error.is_some() { 4 } else { 3 }.min(area.height);
    let popup = Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + (area.height - height) / 2,
        width,
        height,
    };
    let block = Block::bordered().title(title);
    let inner = block.inner(popup);
    frame.render_widget(Clear, popup);
    frame.render_widget(block, popup);
    let (shown, cursor_x) = edit.input.visible(inner.width);
    frame.render_widget(Line::from(shown.to_owned()), inner);
    if let Some(error) = &edit.error
        && inner.height > 1
    {
        let error_area = Rect {
            y: inner.y + 1,
            height: 1,
            ..inner
        };
        frame.render_widget(
            Paragraph::new(error.clone())
                .style(Style::new().fg(Color::Red))
                .wrap(Wrap { trim: false }),
            error_area,
        );
    }
    frame.set_cursor_position((inner.x + cursor_x, inner.y));
}

#[cfg(test)]
mod tests {
    use ratatui::crossterm::event::KeyModifiers;

    use super::*;
    use crate::protocol::analysis::WordSetSupport::{Optional, Required};
    use crate::protocol::analysis::{AvailableAnalyzer, AvailableComponent};

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn press(form: &mut AnalyzerForm, code: KeyCode) -> FormEvent<AnalyzerSettings> {
        form.handle_key(key(code))
    }

    fn typed(form: &mut AnalyzerForm, text: &str) {
        for c in text.chars() {
            assert_eq!(press(form, KeyCode::Char(c)), FormEvent::None);
        }
    }

    fn available(names: &[&str]) -> Vec<AvailableComponent> {
        names
            .iter()
            .map(|name| AvailableComponent {
                name: (*name).to_owned(),
                class_name: format!("org.apache.lucene.analysis.{name}Factory"),
            })
            .collect()
    }

    fn components() -> GetAnalysisComponentsResult {
        GetAnalysisComponentsResult {
            analyzers: [
                ("EnglishAnalyzer", Optional, Optional),
                ("GermanAnalyzer", Optional, Optional),
                ("StopAnalyzer", Required, WordSetSupport::None),
                (
                    "WhitespaceAnalyzer",
                    WordSetSupport::None,
                    WordSetSupport::None,
                ),
                ("DutchAnalyzer", Optional, Optional),
                ("JapaneseAnalyzer", Optional, WordSetSupport::None),
                ("KoreanAnalyzer", WordSetSupport::None, WordSetSupport::None),
            ]
            .into_iter()
            .map(|(name, stopwords, stem_exclusion)| AvailableAnalyzer {
                name: name.to_owned(),
                class_name: format!("org.apache.lucene.analysis.x.{name}"),
                stopwords,
                stem_exclusion,
                options: match name {
                    "StandardAnalyzer" | "WhitespaceAnalyzer" => vec![PresetOption::MaxTokenLength],
                    "DutchAnalyzer" => vec![PresetOption::StemOverride],
                    "JapaneseAnalyzer" => vec![PresetOption::Japanese],
                    "KoreanAnalyzer" => vec![PresetOption::Korean],
                    _ => Vec::new(),
                },
            })
            .collect(),
            char_filters: available(&["htmlStrip", "mapping"]),
            tokenizers: available(&["keyword", "standard", "whitespace"]),
            token_filters: available(&["asciiFolding", "lowercase", "stop", "stemmerOverride"]),
        }
    }

    /// ← cycles back from standard to custom.
    fn custom_form() -> AnalyzerForm {
        let mut form = AnalyzerForm::new(&AnalyzerSettings::default(), Some(components()));
        press(&mut form, KeyCode::Left);
        form
    }

    fn applied(form: &mut AnalyzerForm) -> AnalyzerSettings {
        match press(form, KeyCode::Enter) {
            FormEvent::Apply(settings) => settings,
            other => panic!("expected Apply, got {other:?}"),
        }
    }

    #[test]
    fn the_standard_analyzer_is_the_default() {
        let settings = AnalyzerSettings::default();
        assert_eq!(settings.analyzer(), Analyzer::default());
        assert_eq!(settings.text(), "standard analyzer");
        assert!(!settings.is_custom());
    }

    #[test]
    fn left_switches_to_the_custom_chain_of_the_standard_analyzer() {
        let mut form = custom_form();
        let settings = applied(&mut form);
        assert!(settings.is_custom());
        assert_eq!(settings.text(), "standard › lowercase");
        let custom = settings.analyzer().custom.unwrap();
        assert_eq!(custom.tokenizer.name, "standard");
        assert_eq!(custom.token_filters[0].name, "lowercase");
        assert_eq!(settings.analyzer().standard, None);
    }

    #[test]
    fn adds_filters_from_the_filtered_list() {
        let mut form = custom_form();
        // Kind, config dir, + char filter, standard, lowercase, + token filter, then the wrappers.
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "st");
        // "stop" and "stemmerOverride" match; ↓ selects the second.
        press(&mut form, KeyCode::Down);
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            applied(&mut form).text(),
            "standard › lowercase › stemmerOverride"
        );

        // A char filter goes into its own section.
        let mut form = custom_form();
        form.select(Row::Add(Section::CharFilters));
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "html");
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            applied(&mut form).text(),
            "htmlStrip › standard › lowercase"
        );

        // `a` on the tokenizer does nothing; on a filter, it inserts after it.
        form.select(Row::Component(Section::Tokenizer, 0));
        press(&mut form, KeyCode::Char('a'));
        assert!(matches!(form.editing, Editing::None));
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Char('a'));
        typed(&mut form, "stop");
        press(&mut form, KeyCode::Enter);
        form.select(Row::Component(Section::TokenFilters, 0));
        press(&mut form, KeyCode::Char('a'));
        typed(&mut form, "ascii");
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            applied(&mut form).text(),
            "htmlStrip › standard › lowercase › asciiFolding › stop"
        );
    }

    #[test]
    fn takes_a_typed_name_that_is_not_listed() {
        let mut form = AnalyzerForm::new(&AnalyzerSettings::default(), None);
        press(&mut form, KeyCode::Left);
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Up);
        // The tokenizer row: Space picks another.
        press(&mut form, KeyCode::Up);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "icu");
        press(&mut form, KeyCode::Enter);
        assert_eq!(applied(&mut form).text(), "icu › lowercase");
    }

    #[test]
    fn edits_parameters_as_name_value() {
        let mut form = custom_form();
        // The lowercase filter.
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Up);
        press(&mut form, KeyCode::Char('p'));
        typed(&mut form, "nope");
        press(&mut form, KeyCode::Enter);
        assert!(matches!(&form.editing, Editing::Text(edit) if edit.error.is_some()));
        press(&mut form, KeyCode::Backspace);
        press(&mut form, KeyCode::Backspace);
        press(&mut form, KeyCode::Backspace);
        press(&mut form, KeyCode::Backspace);
        typed(&mut form, " b = 2 ");
        press(&mut form, KeyCode::Enter);
        press(&mut form, KeyCode::Char('p'));
        typed(&mut form, "a=1");
        press(&mut form, KeyCode::Enter);
        assert_eq!(applied(&mut form).text(), "standard › lowercase(a=1, b=2)");

        // The selection is on a=1; Space edits it, and a blank text removes it.
        press(&mut form, KeyCode::Char(' '));
        press(&mut form, KeyCode::Char('u'));
        form.handle_key(KeyEvent::new(KeyCode::Char('u'), KeyModifiers::CONTROL));
        press(&mut form, KeyCode::Enter);
        assert_eq!(applied(&mut form).text(), "standard › lowercase(b=2)");

        // d deletes the selected parameter.
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Up);
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase");
    }

    #[test]
    fn deletes_and_moves_filters_but_keeps_the_tokenizer() {
        let mut form = custom_form();
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "stop");
        press(&mut form, KeyCode::Enter);
        // stop is selected; K moves it before lowercase.
        press(&mut form, KeyCode::Char('K'));
        assert_eq!(applied(&mut form).text(), "standard › stop › lowercase");
        press(&mut form, KeyCode::Char('J'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase › stop");
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase");

        // The tokenizer can't be deleted.
        form.select(Row::Component(Section::Tokenizer, 0));
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(applied(&mut form).text(), "standard › lowercase");
    }

    #[test]
    fn sets_and_clears_the_config_directory() {
        let mut form = custom_form();
        press(&mut form, KeyCode::Down);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, " /etc/solr/conf ");
        press(&mut form, KeyCode::Enter);
        let settings = applied(&mut form);
        assert_eq!(
            settings.text(),
            "standard › lowercase · config /etc/solr/conf"
        );
        assert_eq!(
            settings.analyzer().custom.unwrap().config_dir.as_deref(),
            Some("/etc/solr/conf")
        );
        press(&mut form, KeyCode::Char('d'));
        assert_eq!(
            applied(&mut form).analyzer().custom.unwrap().config_dir,
            None
        );
    }

    #[test]
    fn keeps_the_chain_while_the_standard_analyzer_is_chosen() {
        let mut form = custom_form();
        form.select(Row::Add(Section::TokenFilters));
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "stop");
        press(&mut form, KeyCode::Enter);
        press(&mut form, KeyCode::Home);
        press(&mut form, KeyCode::Char(' '));
        let standard = applied(&mut form);
        assert_eq!(standard.analyzer(), Analyzer::default());
        press(&mut form, KeyCode::Left);
        assert_eq!(applied(&mut form).text(), "standard › lowercase › stop");
        assert_eq!(press(&mut form, KeyCode::Esc), FormEvent::Cancel);
    }

    #[test]
    fn picks_a_preset_by_its_simple_name() {
        let mut form = AnalyzerForm::new(&AnalyzerSettings::default(), Some(components()));
        press(&mut form, KeyCode::Char(' '));
        let settings = applied(&mut form);
        assert_eq!(settings.text(), "EnglishAnalyzer");
        assert!(!settings.is_standard() && !settings.is_custom());
        assert_eq!(
            settings.analyzer(),
            Analyzer {
                standard: None,
                preset: Some(PresetAnalyzer {
                    class_name: DEFAULT_PRESET.to_owned(),
                    stopwords: None,
                    stem_exclusion: None,
                    ..Default::default()
                }),
                custom: None,
                shingles: None,
                limit_token_count: None,
            }
        );

        // The class row opens the list; the filter matches the simple name.
        press(&mut form, KeyCode::Down);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "MAN");
        let screen = render(&form);
        assert!(screen.contains("Pick a preset analyzer"), "{screen}");
        assert!(screen.contains("org.apache.lucene.analysis.x"), "{screen}");
        assert!(!screen.contains("Whitespace"), "{screen}");
        press(&mut form, KeyCode::Enter);
        let settings = applied(&mut form);
        assert_eq!(settings.text(), "GermanAnalyzer");
        assert_eq!(
            settings.analyzer().preset.unwrap().class_name,
            "org.apache.lucene.analysis.x.GermanAnalyzer"
        );
        assert!(
            render(&form).contains("Class           GermanAnalyzer  org.apache.lucene.analysis.x")
        );

        // A class name that is not listed is taken as typed.
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "com.example.MyAnalyzer");
        press(&mut form, KeyCode::Enter);
        assert_eq!(applied(&mut form).text(), "MyAnalyzer");

        // The preset is kept while another kind is chosen.
        press(&mut form, KeyCode::Home);
        press(&mut form, KeyCode::Right);
        assert!(applied(&mut form).is_custom());
        press(&mut form, KeyCode::Left);
        assert_eq!(applied(&mut form).text(), "MyAnalyzer");
    }

    /// The preset form, with `EnglishAnalyzer`.
    fn preset_form() -> AnalyzerForm {
        let mut form = AnalyzerForm::new(&AnalyzerSettings::default(), Some(components()));
        press(&mut form, KeyCode::Char(' '));
        form.draft.preset = "org.apache.lucene.analysis.x.EnglishAnalyzer".to_owned();
        form
    }

    fn edit(form: &mut AnalyzerForm, row: Row, text: &str) {
        form.select(row);
        press(form, KeyCode::Char(' '));
        form.handle_key(KeyEvent::new(KeyCode::Char('u'), KeyModifiers::CONTROL));
        typed(form, text);
        press(form, KeyCode::Enter);
    }

    const STOPWORDS: Row = Row::WordSet(WordSetKind::Stopwords);
    const STEM_EXCLUSION: Row = Row::WordSet(WordSetKind::StemExclusion);

    #[test]
    fn edits_a_presets_word_sets() {
        let mut form = preset_form();
        assert_eq!(
            form.rows(),
            [
                Row::Kind,
                Row::Preset,
                STOPWORDS,
                STEM_EXCLUSION,
                Row::Shingles,
                Row::TokenLimit
            ]
        );

        edit(&mut form, STOPWORDS, "-");
        edit(&mut form, STEM_EXCLUSION, " running, news  foxes ");
        let settings = applied(&mut form);
        assert_eq!(
            settings.text(),
            "EnglishAnalyzer · stop words none · stem exclusions running, news, foxes"
        );
        let preset = settings.analyzer().preset.unwrap();
        assert_eq!(preset.stopwords, Some(WordSet::words(Vec::new())));
        assert_eq!(
            preset.stem_exclusion,
            Some(WordSet::words(vec![
                "running".to_owned(),
                "news".to_owned(),
                "foxes".to_owned()
            ]))
        );

        // The editor starts with the set as it is typed.
        form.select(STEM_EXCLUSION);
        press(&mut form, KeyCode::Char(' '));
        assert!(
            matches!(&form.editing, Editing::Text(edit) if edit.input.text() == "running, news, foxes")
        );
        press(&mut form, KeyCode::Esc);

        // @ and a path is a file; @ alone is refused.
        edit(&mut form, STOPWORDS, "@");
        assert!(matches!(&form.editing, Editing::Text(edit) if edit.error.is_some()));
        typed(&mut form, " /etc/stop.txt");
        press(&mut form, KeyCode::Enter);
        let preset = applied(&mut form).analyzer().preset.unwrap();
        assert_eq!(
            preset.stopwords,
            Some(WordSet::file("/etc/stop.txt".to_owned()))
        );
        let screen = render(&form);
        assert!(
            screen.contains("Stop words      file /etc/stop.txt"),
            "{screen}"
        );

        // d goes back to the default, and so does a blank text.
        form.select(STOPWORDS);
        press(&mut form, KeyCode::Char('d'));
        edit(&mut form, STEM_EXCLUSION, "");
        let settings = applied(&mut form);
        assert_eq!(settings.text(), "EnglishAnalyzer");
        assert_eq!(
            settings.analyzer().preset.unwrap(),
            PresetAnalyzer {
                class_name: "org.apache.lucene.analysis.x.EnglishAnalyzer".to_owned(),
                stopwords: None,
                stem_exclusion: None,
                ..Default::default()
            }
        );
        assert!(render(&form).contains("Stop words      default: the analyzer's own"));
    }

    #[test]
    fn shows_only_the_word_sets_that_a_preset_takes() {
        let mut form = preset_form();
        edit(&mut form, STOPWORDS, "a");
        edit(&mut form, STEM_EXCLUSION, "b");

        // StopAnalyzer takes stop words only, and needs them.
        form.select(Row::Preset);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "Stop");
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            form.rows(),
            [
                Row::Kind,
                Row::Preset,
                STOPWORDS,
                Row::Shingles,
                Row::TokenLimit
            ]
        );
        assert_eq!(applied(&mut form).text(), "StopAnalyzer · stop words a");
        form.select(STOPWORDS);
        press(&mut form, KeyCode::Char('d'));
        let screen = render(&form);
        assert!(
            screen.contains("none: this analyzer needs some"),
            "{screen}"
        );

        // WhitespaceAnalyzer takes neither, but the token length.
        form.select(Row::Preset);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "White");
        press(&mut form, KeyCode::Enter);
        assert_eq!(
            form.rows(),
            [
                Row::Kind,
                Row::Preset,
                Row::MaxTokenLength,
                Row::Shingles,
                Row::TokenLimit
            ]
        );

        // Without the list, the rows that any preset may have are there, and the server judges.
        let form = AnalyzerForm::new(&applied(&mut form), None);
        assert_eq!(
            form.rows(),
            [
                Row::Kind,
                Row::Preset,
                STOPWORDS,
                STEM_EXCLUSION,
                Row::MaxTokenLength,
                Row::StemOverride,
                Row::Shingles,
                Row::TokenLimit
            ]
        );
    }

    #[test]
    fn parses_word_sets() {
        assert_eq!(parse_word_set("  "), Ok(None));
        assert_eq!(parse_word_set(" - "), Ok(Some(WordSet::words(Vec::new()))));
        assert_eq!(
            parse_word_set("a,b c,,\td"),
            Ok(Some(WordSet::words(
                ["a", "b", "c", "d"].map(str::to_owned).to_vec()
            )))
        );
        assert_eq!(
            parse_word_set("@ my stop.txt "),
            Ok(Some(WordSet::file("my stop.txt".to_owned())))
        );
        assert!(parse_word_set("@ ").is_err());
        for set in [
            WordSet::words(Vec::new()),
            WordSet::words(vec!["a".to_owned(), "b".to_owned()]),
            WordSet::file("/x".to_owned()),
        ] {
            assert_eq!(
                parse_word_set(&word_set_input(Some(&set))),
                Ok(Some(set.clone()))
            );
        }
    }

    /// The form with the preset of this simple name, picked from the list.
    fn preset_named(name: &str) -> AnalyzerForm {
        let mut form = preset_form();
        form.select(Row::Preset);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, name);
        press(&mut form, KeyCode::Enter);
        form
    }

    #[test]
    fn wraps_any_analyzer_in_shingles_and_a_token_limit() {
        let mut form = AnalyzerForm::new(&AnalyzerSettings::default(), Some(components()));
        assert_eq!(form.rows(), [Row::Kind, Row::Shingles, Row::TokenLimit]);
        let screen = render(&form);
        assert!(screen.contains("Wrappers"), "{screen}");
        assert!(screen.contains("Shingles        off"), "{screen}");

        form.select(Row::Shingles);
        press(&mut form, KeyCode::Char(' '));
        assert_eq!(
            form.rows(),
            [
                Row::Kind,
                Row::Shingles,
                Row::Shingle(ShingleField::Size),
                Row::Shingle(ShingleField::Separator),
                Row::Shingle(ShingleField::Unigrams),
                Row::Shingle(ShingleField::Filler),
                Row::TokenLimit
            ]
        );
        let settings = applied(&mut form);
        assert!(!settings.is_standard());
        assert_eq!(settings.text(), "standard analyzer · shingles 2");
        assert_eq!(settings.analyzer().shingles, Some(Shingles::default()));
        assert_eq!(settings.analyzer().standard, Analyzer::default().standard);

        edit(&mut form, Row::Shingle(ShingleField::Size), "2-3");
        // The separator and the filler are taken as typed.
        edit(&mut form, Row::Shingle(ShingleField::Separator), " ");
        edit(&mut form, Row::Shingle(ShingleField::Filler), "");
        form.select(Row::Shingle(ShingleField::Unigrams));
        press(&mut form, KeyCode::Char(' '));
        let screen = render(&form);
        assert!(screen.contains("  separator     \" \""), "{screen}");
        assert!(screen.contains("  unigrams      no"), "{screen}");
        let shingles = applied(&mut form).analyzer().shingles.unwrap();
        assert_eq!(
            shingles,
            Shingles {
                min_shingle_size: 2,
                max_shingle_size: 3,
                token_separator: " ".to_owned(),
                output_unigrams: false,
                output_unigrams_if_no_shingles: false,
                filler_token: String::new(),
            }
        );
        // Only without shingles, then always again.
        press(&mut form, KeyCode::Char(' '));
        assert!(render(&form).contains("only if there are no shingles"));
        press(&mut form, KeyCode::Char(' '));
        assert!(
            applied(&mut form)
                .analyzer()
                .shingles
                .unwrap()
                .output_unigrams
        );

        // A size below 2 is refused.
        edit(&mut form, Row::Shingle(ShingleField::Size), "1-2");
        assert!(matches!(&form.editing, Editing::Text(edit) if edit.error.is_some()));
        press(&mut form, KeyCode::Esc);

        form.select(Row::TokenLimit);
        press(&mut form, KeyCode::Char(' '));
        edit(&mut form, Row::Limit(LimitField::MaxTokens), "5");
        form.select(Row::Limit(LimitField::ConsumeAll));
        press(&mut form, KeyCode::Char(' '));
        let settings = applied(&mut form);
        assert_eq!(
            settings.text(),
            "standard analyzer · shingles 2–3 · at most 5 tokens"
        );
        assert_eq!(
            settings.analyzer().limit_token_count,
            Some(LimitTokenCount {
                max_token_count: 5,
                consume_all_tokens: true,
            })
        );

        // They wrap a custom analyzer too, and d turns them off.
        form.select(Row::Kind);
        press(&mut form, KeyCode::Left);
        assert!(applied(&mut form).analyzer().shingles.is_some());
        form.select(Row::Shingles);
        press(&mut form, KeyCode::Char('d'));
        form.select(Row::TokenLimit);
        press(&mut form, KeyCode::Char('d'));
        let settings = applied(&mut form);
        assert_eq!(settings.text(), "standard › lowercase");
        assert_eq!(settings.analyzer().limit_token_count, None);
    }

    #[test]
    fn edits_a_presets_token_length_and_stem_overrides() {
        let mut form = preset_named("Dutch");
        assert!(form.rows().contains(&Row::StemOverride));
        assert!(!form.rows().contains(&Row::MaxTokenLength));

        edit(
            &mut form,
            Row::StemOverride,
            "fietsen=rijwiel, kinderen=kind",
        );
        let settings = applied(&mut form);
        assert_eq!(
            settings.text(),
            "DutchAnalyzer · stem overrides fietsen → rijwiel, kinderen → kind"
        );
        let entries = settings.analyzer().preset.unwrap().stem_override.unwrap();
        assert_eq!(
            entries.entries.unwrap(),
            BTreeMap::from([
                ("fietsen".to_owned(), "rijwiel".to_owned()),
                ("kinderen".to_owned(), "kind".to_owned())
            ])
        );
        edit(&mut form, Row::StemOverride, "fietsen");
        assert!(matches!(&form.editing, Editing::Text(edit) if edit.error.is_some()));
        press(&mut form, KeyCode::Esc);
        edit(&mut form, Row::StemOverride, "@/etc/stemdict.txt");
        assert_eq!(
            applied(&mut form).text(),
            "DutchAnalyzer · stem overrides file /etc/stemdict.txt"
        );

        // A preset that doesn't take them drops them.
        let mut form = AnalyzerForm::new(&applied(&mut form), Some(components()));
        form.select(Row::Preset);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "White");
        press(&mut form, KeyCode::Enter);
        edit(&mut form, Row::MaxTokenLength, "0");
        assert!(matches!(&form.editing, Editing::Text(edit) if edit.error.is_some()));
        press(&mut form, KeyCode::Esc);
        edit(&mut form, Row::MaxTokenLength, "255");
        let settings = applied(&mut form);
        assert_eq!(settings.text(), "WhitespaceAnalyzer · token length 255");
        let preset = settings.analyzer().preset.unwrap();
        assert_eq!(preset.max_token_length, Some(255));
        assert_eq!(preset.stem_override, None);
        assert!(render(&form).contains("Token length    255"));
    }

    #[test]
    fn edits_kuromojis_and_noris_options() {
        let mut form = preset_named("Japanese");
        assert_eq!(
            form.rows()[..6],
            [
                Row::Kind,
                Row::Preset,
                STOPWORDS,
                Row::UserDictionary(Language::Japanese),
                Row::JapaneseMode,
                Row::StopTags(Language::Japanese),
            ]
        );
        assert!(render(&form).contains("Mode            default: search"));
        // Nothing set sends no options.
        assert_eq!(applied(&mut form).analyzer().preset.unwrap().japanese, None);

        edit(
            &mut form,
            Row::UserDictionary(Language::Japanese),
            "/x/userdict.txt",
        );
        form.select(Row::JapaneseMode);
        press(&mut form, KeyCode::Char(' '));
        edit(&mut form, Row::StopTags(Language::Japanese), "-");
        let settings = applied(&mut form);
        assert_eq!(
            settings.text(),
            "JapaneseAnalyzer · user dictionary /x/userdict.txt · mode normal · stop tags none"
        );
        assert_eq!(
            settings.analyzer().preset.unwrap().japanese,
            Some(JapaneseOptions {
                user_dictionary: Some("/x/userdict.txt".to_owned()),
                mode: Some(JapaneseMode::Normal),
                stop_tags: Some(WordSet::words(Vec::new())),
            })
        );
        // ← cycles back to the default.
        form.select(Row::JapaneseMode);
        press(&mut form, KeyCode::Left);
        assert_eq!(form.draft.japanese.mode, None);

        // Korean drops the Japanese options.
        let mut form = AnalyzerForm::new(&applied(&mut form), Some(components()));
        form.select(Row::Preset);
        press(&mut form, KeyCode::Char(' '));
        typed(&mut form, "Korean");
        press(&mut form, KeyCode::Enter);
        assert_eq!(form.draft.japanese, JapaneseOptions::default());
        form.select(Row::DecompoundMode);
        press(&mut form, KeyCode::Left);
        form.select(Row::UnknownWords);
        press(&mut form, KeyCode::Char(' '));
        edit(&mut form, Row::StopTags(Language::Korean), "JX NNP");
        let settings = applied(&mut form);
        assert_eq!(
            settings.text(),
            "KoreanAnalyzer · decompound mixed · stop tags JX, NNP · unknown words split into unigrams"
        );
        assert_eq!(
            settings.analyzer().preset.unwrap().korean,
            Some(KoreanOptions {
                user_dictionary: None,
                decompound_mode: Some(KoreanDecompoundMode::Mixed),
                stop_tags: Some(WordSet::words(vec!["JX".to_owned(), "NNP".to_owned()])),
                output_unknown_unigrams: Some(true),
            })
        );

        // Without the list, the class name says which options there are.
        let settings = AnalyzerSettings {
            kind: Kind::Preset,
            preset: JAPANESE_ANALYZER.to_owned(),
            ..Default::default()
        };
        let form = AnalyzerForm::new(&settings, None);
        assert!(form.rows().contains(&Row::JapaneseMode));
        assert!(!form.rows().contains(&Row::DecompoundMode));
    }

    #[test]
    fn parses_stem_overrides_and_shingle_sizes() {
        assert_eq!(parse_stem_dictionary(" "), Ok(None));
        assert_eq!(
            parse_stem_dictionary("-").unwrap().unwrap().entries,
            Some(BTreeMap::new())
        );
        assert!(parse_stem_dictionary("a=").is_err());
        assert!(parse_stem_dictionary("=b").is_err());
        assert!(parse_stem_dictionary("@").is_err());
        for text in ["a=b, c=d", "@/x", "-"] {
            let dictionary = parse_stem_dictionary(text).unwrap();
            assert_eq!(
                parse_stem_dictionary(&stem_dictionary_input(dictionary.as_ref())),
                Ok(dictionary)
            );
        }

        assert_eq!(parse_shingle_size(""), Ok(None));
        assert_eq!(parse_shingle_size("3"), Ok(Some((3, 3))));
        assert_eq!(parse_shingle_size("2 - 4"), Ok(Some((2, 4))));
        assert_eq!(parse_shingle_size("2–4"), Ok(Some((2, 4))));
        for bad in ["1", "3-2", "x", "2-", "2-3-4"] {
            assert!(parse_shingle_size(bad).is_err(), "{bad}");
        }
    }

    fn render(form: &AnalyzerForm) -> String {
        let mut terminal =
            ratatui::Terminal::new(ratatui::backend::TestBackend::new(80, 24)).unwrap();
        terminal
            .draw(|frame| form.render(frame, frame.area()))
            .unwrap();
        format!("{}", terminal.backend())
    }

    #[test]
    fn renders_the_chain_and_the_error() {
        let mut form = custom_form();
        form.set_error("Invalid analyzer: no token filter 'nope'".to_owned());
        let screen = render(&form);
        for text in [
            "Analyzer        custom",
            "Config dir      none",
            "Char filters",
            "+ char filter",
            "Tokenizer",
            "standard",
            "Token filters",
            "lowercase",
            "Invalid analyzer: no token filter 'nope'",
        ] {
            assert!(screen.contains(text), "{text:?} missing:\n{screen}");
        }
    }
}
