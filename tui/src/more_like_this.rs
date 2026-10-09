//! The More-Like-This settings of the search screen (ADR 0013, ADR 0028), and the popup that edits
//! them: the fields to take terms from, Lucene's term and document frequency limits, how many terms
//! the query has, whether they are boosted, and which words are left out by length or as stop
//! words.

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::Rect;
use ratatui::style::{Color, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear};

use crate::form::{FormEvent, dim, render_popup, row, type_digit};
use crate::input::{LineInput, word_start};
use crate::protocol::analysis::Analyzer;
use crate::protocol::index::{IndexField, IndexOptions};
use crate::protocol::search::{MAX_QUERY_TERMS, MoreLikeThisQuery};
use crate::text::escape_control;

/// Lucene's defaults.
const DEFAULT_MIN_TERM_FREQ: i32 = 2;
const DEFAULT_MIN_DOC_FREQ: i32 = 5;
const DEFAULT_MAX_QUERY_TERMS: i32 = 25;
/// The largest frequency the popup takes.
const MAX_FREQ: i32 = 99_999_999;
/// The longest word length the popup takes.
const MAX_WORD_LEN: i32 = 9_999;
/// The popup's width.
const WIDTH: u16 = 52;

/// Where the terms of a More-Like-This query come from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LikeSource {
    /// A document of the index, by its id.
    Doc(i32),
    /// A text, analyzed for each field.
    Text(String),
}

/// The More-Like-This settings, as the search screen sends them with a document id or a text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LikeSettings {
    /// The indexed fields, in field number order.
    names: Vec<String>,
    chosen: Vec<bool>,
    min_term_freq: i32,
    min_doc_freq: i32,
    /// 0 for no limit.
    max_doc_freq: i32,
    max_query_terms: i32,
    boost: bool,
    /// 0 for no limit.
    min_word_len: i32,
    /// 0 for no limit.
    max_word_len: i32,
    stop_words: Vec<String>,
}

impl LikeSettings {
    /// Lucene's defaults, with the fields that have positions, which are most likely text; the
    /// first indexed field if none has.
    pub fn new(fields: &[IndexField]) -> Self {
        let indexed: Vec<&IndexField> = fields
            .iter()
            .filter(|field| field.index_options != IndexOptions::None)
            .collect();
        let mut chosen: Vec<bool> = indexed
            .iter()
            .map(|field| {
                matches!(
                    field.index_options,
                    IndexOptions::DocsAndFreqsAndPositions
                        | IndexOptions::DocsAndFreqsAndPositionsAndOffsets
                )
            })
            .collect();
        if !chosen.contains(&true)
            && let Some(first) = chosen.first_mut()
        {
            *first = true;
        }
        Self {
            names: indexed.iter().map(|field| field.name.clone()).collect(),
            chosen,
            min_term_freq: DEFAULT_MIN_TERM_FREQ,
            min_doc_freq: DEFAULT_MIN_DOC_FREQ,
            max_doc_freq: 0,
            max_query_terms: DEFAULT_MAX_QUERY_TERMS,
            boost: false,
            min_word_len: 0,
            max_word_len: 0,
            stop_words: Vec::new(),
        }
    }

    fn fields(&self) -> Vec<String> {
        self.names
            .iter()
            .zip(&self.chosen)
            .filter(|(_, chosen)| **chosen)
            .map(|(name, _)| name.clone())
            .collect()
    }

    /// The query for documents like `source`, whose stored text or text `analyzer` analyzes;
    /// `None` without a field to take terms from.
    pub fn query(&self, source: LikeSource, analyzer: Analyzer) -> Option<MoreLikeThisQuery> {
        let fields = self.fields();
        let (doc_id, text) = match source {
            LikeSource::Doc(doc_id) => (Some(doc_id), None),
            LikeSource::Text(text) => (None, Some(text)),
        };
        (!fields.is_empty()).then(|| MoreLikeThisQuery {
            doc_id,
            text,
            fields,
            min_term_freq: self.min_term_freq,
            min_doc_freq: self.min_doc_freq,
            max_doc_freq: (self.max_doc_freq > 0).then_some(self.max_doc_freq),
            max_query_terms: self.max_query_terms,
            boost: self.boost,
            analyzer,
            min_word_len: self.min_word_len,
            max_word_len: (self.max_word_len > 0).then_some(self.max_word_len),
            stop_words: self.stop_words.clone(),
        })
    }

    /// The chosen fields, such as `fields title · body`, or how many of them there are.
    pub fn fields_text(&self) -> String {
        let fields = self.fields();
        let names = || {
            fields
                .iter()
                .map(|name| escape_control(name))
                .collect::<Vec<_>>()
                .join(" · ")
        };
        match fields.len() {
            0 => "no field".to_owned(),
            1 => format!("field {}", names()),
            2..=3 => format!("fields {}", names()),
            n => format!("{n} fields"),
        }
    }

    /// The limits, such as `term freq ≥ 2 · doc freq 5 to 100 · 25 terms · boost · words 3 to 20 ·
    /// stop words a with`.
    pub fn limits_text(&self) -> String {
        let doc_freq = if self.max_doc_freq > 0 {
            format!("doc freq {} to {}", self.min_doc_freq, self.max_doc_freq)
        } else {
            format!("doc freq ≥ {}", self.min_doc_freq)
        };
        let mut text = format!(
            "term freq ≥ {} · {doc_freq} · {} terms",
            self.min_term_freq, self.max_query_terms
        );
        if self.boost {
            text.push_str(" · boost");
        }
        match (self.min_word_len, self.max_word_len) {
            (0, 0) => {}
            (min, 0) => text.push_str(&format!(" · words ≥ {min}")),
            (0, max) => text.push_str(&format!(" · words ≤ {max}")),
            (min, max) => text.push_str(&format!(" · words {min} to {max}")),
        }
        if !self.stop_words.is_empty() {
            text.push_str(&format!(" · {}", stop_words_text(&self.stop_words)));
        }
        text
    }
}

/// The stop words, such as `stop words a with`, or how many there are.
fn stop_words_text(words: &[String]) -> String {
    match words.len() {
        1..=4 => format!(
            "stop words {}",
            words
                .iter()
                .map(|word| escape_control(word))
                .collect::<Vec<_>>()
                .join(" ")
        ),
        n => format!("{n} stop words"),
    }
}

/// The stop words typed in a line, split on whitespace and commas, each once.
fn split_stop_words(line: &str) -> Vec<String> {
    let mut words: Vec<String> = Vec::new();
    for word in line.split(|c: char| c.is_whitespace() || c == ',') {
        if !word.is_empty() && !words.iter().any(|known| known == word) {
            words.push(word.to_owned());
        }
    }
    words
}

/// One line of the popup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Row {
    MinTermFreq,
    MinDocFreq,
    MaxDocFreq,
    MaxQueryTerms,
    Boost,
    MinWordLen,
    MaxWordLen,
    StopWords,
    Field(usize),
}

/// The popup: a draft of the settings, applied with Enter once a field is chosen.
#[derive(Debug)]
pub struct LikeForm {
    draft: LikeSettings,
    selected: usize,
    /// Whether Enter was refused because no field is chosen.
    no_field: bool,
    /// The stop words, while they are typed.
    editing: Option<LineInput>,
}

impl LikeForm {
    pub fn new(settings: &LikeSettings) -> Self {
        Self {
            draft: settings.clone(),
            selected: 0,
            no_field: false,
            editing: None,
        }
    }

    fn rows(&self) -> Vec<Row> {
        let mut rows = vec![
            Row::MinTermFreq,
            Row::MinDocFreq,
            Row::MaxDocFreq,
            Row::MaxQueryTerms,
            Row::Boost,
            Row::MinWordLen,
            Row::MaxWordLen,
            Row::StopWords,
        ];
        rows.extend((0..self.draft.names.len()).map(Row::Field));
        rows
    }

    /// ↑↓ select a row; ←→, Space, `-`/`+` change it; digits and Backspace edit a number; →,
    /// Space or `+` types the stop words, `d` or Backspace clears them; `a`/`n` choose all fields
    /// or none; Enter applies, Esc cancels.
    pub fn handle_key(&mut self, key: KeyEvent) -> FormEvent<LikeSettings> {
        if let Some(input) = &mut self.editing {
            match key.code {
                KeyCode::Esc => self.editing = None,
                KeyCode::Enter => {
                    self.draft.stop_words = split_stop_words(input.text());
                    self.editing = None;
                }
                _ => {
                    input.handle_key(key, word_start);
                }
            }
            return FormEvent::None;
        }
        let rows = self.rows();
        let last = rows.len() - 1;
        let row = rows[self.selected.min(last)];
        match key.code {
            KeyCode::Enter => {
                if !self.draft.chosen.contains(&true) {
                    self.no_field = true;
                    return FormEvent::None;
                }
                let mut settings = self.draft.clone();
                settings.max_query_terms = settings.max_query_terms.max(1);
                if settings.max_word_len > 0 {
                    settings.max_word_len = settings.max_word_len.max(settings.min_word_len);
                }
                return FormEvent::Apply(settings);
            }
            KeyCode::Esc => return FormEvent::Cancel,
            KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
            KeyCode::Down | KeyCode::Char('j') => self.selected = (self.selected + 1).min(last),
            KeyCode::PageUp => self.selected = self.selected.saturating_sub(10),
            KeyCode::PageDown => self.selected = (self.selected + 10).min(last),
            KeyCode::Home | KeyCode::Char('g') => self.selected = 0,
            KeyCode::End | KeyCode::Char('G') => self.selected = last,
            KeyCode::Left | KeyCode::Char('h') | KeyCode::Char('-') => self.change(row, -1),
            KeyCode::Right | KeyCode::Char('l') | KeyCode::Char('+') | KeyCode::Char(' ') => {
                self.change(row, 1);
            }
            KeyCode::Char(c) if c.is_ascii_digit() => {
                if let Some((number, max)) = self.number(row) {
                    type_digit(number, c, max);
                }
            }
            KeyCode::Backspace | KeyCode::Char('d') | KeyCode::Delete if row == Row::StopWords => {
                self.draft.stop_words.clear();
            }
            KeyCode::Backspace => {
                if let Some((number, _)) = self.number(row) {
                    *number /= 10;
                }
            }
            KeyCode::Char('a') => self.draft.chosen.fill(true),
            KeyCode::Char('n') => self.draft.chosen.fill(false),
            _ => {}
        }
        if self.draft.chosen.contains(&true) {
            self.no_field = false;
        }
        FormEvent::None
    }

    fn number(&mut self, row: Row) -> Option<(&mut i32, i32)> {
        let draft = &mut self.draft;
        match row {
            Row::MinTermFreq => Some((&mut draft.min_term_freq, MAX_FREQ)),
            Row::MinDocFreq => Some((&mut draft.min_doc_freq, MAX_FREQ)),
            Row::MaxDocFreq => Some((&mut draft.max_doc_freq, MAX_FREQ)),
            Row::MaxQueryTerms => Some((&mut draft.max_query_terms, MAX_QUERY_TERMS)),
            Row::MinWordLen => Some((&mut draft.min_word_len, MAX_WORD_LEN)),
            Row::MaxWordLen => Some((&mut draft.max_word_len, MAX_WORD_LEN)),
            Row::Boost | Row::StopWords | Row::Field(_) => None,
        }
    }

    /// Steps a number by `step`, switches the boost or a field, or starts typing the stop words
    /// forward.
    fn change(&mut self, row: Row, step: i32) {
        let min = if row == Row::MaxQueryTerms { 1 } else { 0 };
        if let Some((number, max)) = self.number(row) {
            *number = (*number + step).clamp(min, max);
            return;
        }
        match row {
            Row::Boost => self.draft.boost = !self.draft.boost,
            Row::StopWords if step > 0 => {
                self.editing = Some(LineInput::new(&self.draft.stop_words.join(" ")));
            }
            Row::Field(i) => {
                if let Some(chosen) = self.draft.chosen.get_mut(i) {
                    *chosen = !*chosen;
                }
            }
            _ => {}
        }
    }

    /// The keys for the footer while the popup is open.
    pub fn keys(&self) -> &'static str {
        if self.editing.is_some() {
            " ⏎ take   Esc back   ←→ Ctrl+A/E/U/K/W edit"
        } else {
            " ⏎ apply   Esc cancel   ↑↓ select   ←→ Space change   0-9 number   a/n all/no fields"
        }
    }

    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let rows = self.rows();
        let selected = self.selected.min(rows.len() - 1);
        let draft = &self.draft;
        let mut lines: Vec<Line> = Vec::new();
        let mut selected_line = 0;
        for (i, row_kind) in rows.iter().enumerate() {
            if *row_kind == Row::MinWordLen {
                lines.push(Line::from(""));
                lines.push(Line::from(Span::styled(
                    "Words left out, after analysis",
                    dim(),
                )));
            }
            if *row_kind == Row::Field(0) {
                lines.push(Line::from(""));
                lines.push(Line::from(Span::styled("Fields to take terms from", dim())));
            }
            let (label, value) = match *row_kind {
                Row::MinTermFreq => ("Min term freq".to_owned(), draft.min_term_freq.to_string()),
                Row::MinDocFreq => ("Min doc freq".to_owned(), draft.min_doc_freq.to_string()),
                Row::MaxDocFreq => ("Max doc freq".to_owned(), limit(draft.max_doc_freq)),
                Row::MaxQueryTerms => (
                    "Max query terms".to_owned(),
                    draft.max_query_terms.to_string(),
                ),
                Row::Boost => (
                    "Boost by score".to_owned(),
                    if draft.boost { "yes" } else { "no" }.to_owned(),
                ),
                Row::MinWordLen => ("Min word length".to_owned(), limit(draft.min_word_len)),
                Row::MaxWordLen => ("Max word length".to_owned(), limit(draft.max_word_len)),
                Row::StopWords => (
                    "Stop words".to_owned(),
                    if draft.stop_words.is_empty() {
                        "none".to_owned()
                    } else {
                        stop_words_text(&draft.stop_words)
                            .trim_start_matches("stop words ")
                            .to_owned()
                    },
                ),
                Row::Field(field) => (
                    format!("  {}", escape_control(&draft.names[field])),
                    if draft.chosen[field] { "[x]" } else { "[ ]" }.to_owned(),
                ),
            };
            if i == selected {
                selected_line = lines.len();
            }
            lines.push(row(&label, &value, i == selected));
        }
        lines.push(Line::from(""));
        if self.no_field {
            lines.push(Line::from(Span::styled(
                "Choose at least one field",
                Style::new().fg(Color::Red),
            )));
        } else {
            lines.push(Line::from(Span::styled(
                "Terms: a document's term vector or analyzed",
                dim(),
            )));
            lines.push(Line::from(Span::styled(
                "stored text, or the text, analyzed",
                dim(),
            )));
        }
        render_popup(frame, area, " More like this ", lines, selected_line, WIDTH);
        if let Some(input) = &self.editing {
            render_stop_words(frame, area, input);
        }
    }
}

/// A number, or `no limit` for 0.
fn limit(number: i32) -> String {
    if number > 0 {
        number.to_string()
    } else {
        "no limit".to_owned()
    }
}

/// The line that the stop words are typed in, over the popup.
fn render_stop_words(frame: &mut Frame, area: Rect, input: &LineInput) {
    let width = area.width.min(WIDTH + 20);
    let height = 3.min(area.height);
    let popup = Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + (area.height - height) / 2,
        width,
        height,
    };
    let block = Block::bordered().title(" Stop words, as analyzed terms: a the with ");
    let inner = block.inner(popup);
    frame.render_widget(Clear, popup);
    frame.render_widget(block, popup);
    let (shown, cursor_x) = input.visible(inner.width);
    frame.render_widget(Line::from(shown.to_owned()), inner);
    frame.set_cursor_position((inner.x + cursor_x, inner.y));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::index::DocValuesType;
    use ratatui::crossterm::event::KeyModifiers;

    fn field(name: &str, index_options: IndexOptions) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number: 0,
            index_options,
            has_norms: false,
            has_payloads: false,
            has_term_vectors: false,
            doc_values_type: DocValuesType::None,
            point_dimension_count: 0,
            point_num_bytes: 0,
            vector_dimension: 0,
            terms: None,
        }
    }

    fn settings() -> LikeSettings {
        LikeSettings::new(&[
            field("id", IndexOptions::Docs),
            field("title", IndexOptions::DocsAndFreqsAndPositions),
            field("price", IndexOptions::None),
            field("body", IndexOptions::DocsAndFreqsAndPositionsAndOffsets),
        ])
    }

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn press(form: &mut LikeForm, codes: &[KeyCode]) {
        for code in codes {
            form.handle_key(key(*code));
        }
    }

    fn applied(mut form: LikeForm) -> LikeSettings {
        match form.handle_key(key(KeyCode::Enter)) {
            FormEvent::Apply(settings) => settings,
            other => panic!("expected Apply, got {other:?}"),
        }
    }

    #[test]
    fn starts_from_lucenes_defaults_with_the_text_fields() {
        let settings = settings();
        assert_eq!(
            settings.query(LikeSource::Doc(42), Analyzer::default()),
            Some(MoreLikeThisQuery {
                doc_id: Some(42),
                text: None,
                fields: vec!["title".to_owned(), "body".to_owned()],
                min_term_freq: 2,
                min_doc_freq: 5,
                max_doc_freq: None,
                max_query_terms: 25,
                boost: false,
                analyzer: Analyzer::default(),
                min_word_len: 0,
                max_word_len: None,
                stop_words: Vec::new(),
            })
        );
        assert_eq!(settings.fields_text(), "fields title · body");
        assert_eq!(
            settings.limits_text(),
            "term freq ≥ 2 · doc freq ≥ 5 · 25 terms"
        );
        let text = settings
            .query(LikeSource::Text("red lamp".to_owned()), Analyzer::default())
            .expect("a field is chosen");
        assert_eq!(
            (text.doc_id, text.text.as_deref()),
            (None, Some("red lamp"))
        );

        // Without a text field, the first indexed one.
        let keyword = LikeSettings::new(&[
            field("price", IndexOptions::None),
            field("id", IndexOptions::Docs),
        ]);
        assert_eq!(
            keyword
                .query(LikeSource::Doc(0), Analyzer::default())
                .map(|query| query.fields),
            Some(vec!["id".to_owned()])
        );
    }

    #[test]
    fn edits_the_limits_and_the_fields() {
        let mut form = LikeForm::new(&settings());
        press(&mut form, &[KeyCode::Backspace, KeyCode::Char('1')]);
        press(&mut form, &[KeyCode::Down, KeyCode::Left, KeyCode::Down]);
        press(
            &mut form,
            &[KeyCode::Char('1'), KeyCode::Char('0'), KeyCode::Char('0')],
        );
        press(&mut form, &[KeyCode::Down, KeyCode::Backspace]);
        press(&mut form, &[KeyCode::Down, KeyCode::Char(' ')]);
        // Min and max word length.
        press(&mut form, &[KeyCode::Down, KeyCode::Char('3')]);
        press(
            &mut form,
            &[KeyCode::Down, KeyCode::Char('2'), KeyCode::Char('0')],
        );
        // Past the stop words, the first field row: id.
        press(
            &mut form,
            &[KeyCode::Down, KeyCode::Down, KeyCode::Char(' ')],
        );
        let edited = applied(form);

        let query = edited
            .query(LikeSource::Doc(7), Analyzer::default())
            .expect("a field is chosen");
        assert_eq!(query.min_term_freq, 1);
        assert_eq!(query.min_doc_freq, 4);
        assert_eq!(query.max_doc_freq, Some(100));
        assert_eq!(query.max_query_terms, 2);
        assert!(query.boost);
        assert_eq!(query.min_word_len, 3);
        assert_eq!(query.max_word_len, Some(20));
        assert_eq!(query.fields, ["id", "title", "body"]);
        assert_eq!(edited.fields_text(), "fields id · title · body");
        assert_eq!(
            edited.limits_text(),
            "term freq ≥ 1 · doc freq 4 to 100 · 2 terms · boost · words 3 to 20"
        );

        // A max word length below the min one applies as the min one.
        let mut form = LikeForm::new(&edited);
        press(&mut form, &[KeyCode::Down; 6]);
        press(
            &mut form,
            &[KeyCode::Backspace, KeyCode::Backspace, KeyCode::Char('2')],
        );
        let raised = applied(form);
        assert_eq!(
            raised.limits_text().rsplit(" · ").next(),
            Some("words 3 to 3")
        );
    }

    #[test]
    fn types_the_stop_words() {
        let mut form = LikeForm::new(&settings());
        press(&mut form, &[KeyCode::Down; 7]);
        form.handle_key(key(KeyCode::Right));
        assert!(form.editing.is_some());
        assert!(form.keys().contains("take"));
        for c in "a, the  with,a".chars() {
            form.handle_key(key(KeyCode::Char(c)));
        }
        form.handle_key(key(KeyCode::Enter));
        assert!(form.editing.is_none());
        let words = applied(form);
        assert_eq!(
            words
                .query(LikeSource::Doc(0), Analyzer::default())
                .map(|query| query.stop_words),
            Some(vec!["a".to_owned(), "the".to_owned(), "with".to_owned()])
        );
        assert!(words.limits_text().ends_with(" · stop words a the with"));

        // Editing starts from the words; Esc keeps them, d clears them.
        let mut form = LikeForm::new(&words);
        press(&mut form, &[KeyCode::Down; 7]);
        press(
            &mut form,
            &[KeyCode::Char(' '), KeyCode::Backspace, KeyCode::Esc],
        );
        assert_eq!(form.draft.stop_words.len(), 3);
        form.handle_key(key(KeyCode::Char('d')));
        assert!(applied(form).stop_words.is_empty());

        let many: Vec<String> = (0..5).map(|i| format!("w{i}")).collect();
        assert_eq!(stop_words_text(&many), "5 stop words");
    }

    #[test]
    fn needs_a_field_and_a_query_term() {
        let mut form = LikeForm::new(&settings());
        form.handle_key(key(KeyCode::Char('n')));
        assert_eq!(form.handle_key(key(KeyCode::Enter)), FormEvent::None);
        assert!(form.no_field);
        form.handle_key(key(KeyCode::Char('a')));
        assert!(!form.no_field);

        // Max query terms: Backspace twice leaves 0, which applies as 1; ← stops at 1.
        press(&mut form, &[KeyCode::Down; 3]);
        press(&mut form, &[KeyCode::Backspace, KeyCode::Backspace]);
        let one = applied(form);
        assert_eq!(
            one.query(LikeSource::Doc(0), Analyzer::default())
                .map(|query| query.max_query_terms),
            Some(1)
        );
        assert_eq!(one.fields_text(), "fields id · title · body");
        let mut form = LikeForm::new(&one);
        press(
            &mut form,
            &[KeyCode::Char('n'), KeyCode::End, KeyCode::Char(' ')],
        );
        assert_eq!(applied(form).fields_text(), "field body");

        let mut form = LikeForm::new(&one);
        form.handle_key(key(KeyCode::End));
        press(&mut form, &[KeyCode::Up; 7]);
        form.handle_key(key(KeyCode::Left));
        assert_eq!(form.draft.max_query_terms, 1);
        assert_eq!(form.handle_key(key(KeyCode::Esc)), FormEvent::Cancel);
    }
}
