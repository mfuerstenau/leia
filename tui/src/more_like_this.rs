//! The More-Like-This settings of the search screen (ADR 0013), and the popup that edits them: the
//! fields to take terms from, Lucene's term and document frequency limits, how many terms the query
//! has, and whether they are boosted.

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::Rect;
use ratatui::style::{Color, Style};
use ratatui::text::{Line, Span};

use crate::form::{FormEvent, dim, render_popup, row, type_digit};
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

/// The More-Like-This settings, as the search screen sends them with a document id.
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

    /// The query for documents like `doc_id`, whose stored text `analyzer` analyzes; `None`
    /// without a field to take terms from.
    pub fn query(&self, doc_id: i32, analyzer: Analyzer) -> Option<MoreLikeThisQuery> {
        let fields = self.fields();
        (!fields.is_empty()).then(|| MoreLikeThisQuery {
            doc_id,
            fields,
            min_term_freq: self.min_term_freq,
            min_doc_freq: self.min_doc_freq,
            max_doc_freq: (self.max_doc_freq > 0).then_some(self.max_doc_freq),
            max_query_terms: self.max_query_terms,
            boost: self.boost,
            analyzer,
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

    /// The limits, such as `term freq ≥ 2 · doc freq 5 to 100 · 25 terms · boost`.
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
        text
    }
}

/// One line of the popup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Row {
    MinTermFreq,
    MinDocFreq,
    MaxDocFreq,
    MaxQueryTerms,
    Boost,
    Field(usize),
}

/// The popup: a draft of the settings, applied with Enter once a field is chosen.
#[derive(Debug)]
pub struct LikeForm {
    draft: LikeSettings,
    selected: usize,
    /// Whether Enter was refused because no field is chosen.
    no_field: bool,
}

impl LikeForm {
    pub fn new(settings: &LikeSettings) -> Self {
        Self {
            draft: settings.clone(),
            selected: 0,
            no_field: false,
        }
    }

    fn rows(&self) -> Vec<Row> {
        let mut rows = vec![
            Row::MinTermFreq,
            Row::MinDocFreq,
            Row::MaxDocFreq,
            Row::MaxQueryTerms,
            Row::Boost,
        ];
        rows.extend((0..self.draft.names.len()).map(Row::Field));
        rows
    }

    /// ↑↓ select a row; ←→, Space, `-`/`+` change it; digits and Backspace edit a number; `a`/`n`
    /// choose all fields or none; Enter applies, Esc cancels.
    pub fn handle_key(&mut self, key: KeyCode) -> FormEvent<LikeSettings> {
        let rows = self.rows();
        let last = rows.len() - 1;
        let row = rows[self.selected.min(last)];
        match key {
            KeyCode::Enter => {
                if !self.draft.chosen.contains(&true) {
                    self.no_field = true;
                    return FormEvent::None;
                }
                let mut settings = self.draft.clone();
                settings.max_query_terms = settings.max_query_terms.max(1);
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
            Row::Boost | Row::Field(_) => None,
        }
    }

    /// Steps a number by `step`, or switches the boost or a field.
    fn change(&mut self, row: Row, step: i32) {
        let min = if row == Row::MaxQueryTerms { 1 } else { 0 };
        if let Some((number, max)) = self.number(row) {
            *number = (*number + step).clamp(min, max);
            return;
        }
        match row {
            Row::Boost => self.draft.boost = !self.draft.boost,
            Row::Field(i) => {
                if let Some(chosen) = self.draft.chosen.get_mut(i) {
                    *chosen = !*chosen;
                }
            }
            _ => {}
        }
    }

    /// The keys for the footer while the popup is open.
    pub fn keys() -> &'static str {
        " ⏎ apply   Esc cancel   ↑↓ select   ←→ Space change   0-9 number   a/n all/no fields"
    }

    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let rows = self.rows();
        let selected = self.selected.min(rows.len() - 1);
        let draft = &self.draft;
        let mut lines: Vec<Line> = Vec::new();
        let mut selected_line = 0;
        for (i, row_kind) in rows.iter().enumerate() {
            if *row_kind == Row::Field(0) {
                lines.push(Line::from(""));
                lines.push(Line::from(Span::styled("Fields to take terms from", dim())));
            }
            let (label, value) = match *row_kind {
                Row::MinTermFreq => ("Min term freq".to_owned(), draft.min_term_freq.to_string()),
                Row::MinDocFreq => ("Min doc freq".to_owned(), draft.min_doc_freq.to_string()),
                Row::MaxDocFreq => (
                    "Max doc freq".to_owned(),
                    if draft.max_doc_freq > 0 {
                        draft.max_doc_freq.to_string()
                    } else {
                        "no limit".to_owned()
                    },
                ),
                Row::MaxQueryTerms => (
                    "Max query terms".to_owned(),
                    draft.max_query_terms.to_string(),
                ),
                Row::Boost => (
                    "Boost by score".to_owned(),
                    if draft.boost { "yes" } else { "no" }.to_owned(),
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
                "Terms: term vector, or stored text analyzed",
                dim(),
            )));
        }
        render_popup(frame, area, " More like this ", lines, selected_line, 52);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::index::DocValuesType;

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

    fn applied(mut form: LikeForm) -> LikeSettings {
        match form.handle_key(KeyCode::Enter) {
            FormEvent::Apply(settings) => settings,
            other => panic!("expected Apply, got {other:?}"),
        }
    }

    #[test]
    fn starts_from_lucenes_defaults_with_the_text_fields() {
        let settings = settings();
        assert_eq!(
            settings.query(42, Analyzer::default()),
            Some(MoreLikeThisQuery {
                doc_id: 42,
                fields: vec!["title".to_owned(), "body".to_owned()],
                min_term_freq: 2,
                min_doc_freq: 5,
                max_doc_freq: None,
                max_query_terms: 25,
                boost: false,
                analyzer: Analyzer::default(),
            })
        );
        assert_eq!(settings.fields_text(), "fields title · body");
        assert_eq!(
            settings.limits_text(),
            "term freq ≥ 2 · doc freq ≥ 5 · 25 terms"
        );

        // Without a text field, the first indexed one.
        let keyword = LikeSettings::new(&[
            field("price", IndexOptions::None),
            field("id", IndexOptions::Docs),
        ]);
        assert_eq!(
            keyword
                .query(0, Analyzer::default())
                .map(|query| query.fields),
            Some(vec!["id".to_owned()])
        );
    }

    #[test]
    fn edits_the_limits_and_the_fields() {
        let mut form = LikeForm::new(&settings());
        form.handle_key(KeyCode::Backspace);
        form.handle_key(KeyCode::Char('1'));
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Left);
        form.handle_key(KeyCode::Down);
        for key in [KeyCode::Char('1'), KeyCode::Char('0'), KeyCode::Char('0')] {
            form.handle_key(key);
        }
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Backspace);
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Char(' '));
        // The first field row: id.
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Char(' '));
        let edited = applied(form);

        let query = edited
            .query(7, Analyzer::default())
            .expect("a field is chosen");
        assert_eq!(query.min_term_freq, 1);
        assert_eq!(query.min_doc_freq, 4);
        assert_eq!(query.max_doc_freq, Some(100));
        assert_eq!(query.max_query_terms, 2);
        assert!(query.boost);
        assert_eq!(query.fields, ["id", "title", "body"]);
        assert_eq!(edited.fields_text(), "fields id · title · body");
        assert_eq!(
            edited.limits_text(),
            "term freq ≥ 1 · doc freq 4 to 100 · 2 terms · boost"
        );
    }

    #[test]
    fn needs_a_field_and_a_query_term() {
        let mut form = LikeForm::new(&settings());
        form.handle_key(KeyCode::Char('n'));
        assert_eq!(form.handle_key(KeyCode::Enter), FormEvent::None);
        assert!(form.no_field);
        form.handle_key(KeyCode::Char('a'));
        assert!(!form.no_field);

        // Max query terms: Backspace twice leaves 0, which applies as 1; ← stops at 1.
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Down);
        form.handle_key(KeyCode::Backspace);
        form.handle_key(KeyCode::Backspace);
        let one = applied(form);
        assert_eq!(
            one.query(0, Analyzer::default())
                .map(|query| query.max_query_terms),
            Some(1)
        );
        assert_eq!(one.fields_text(), "fields id · title · body");
        let mut form = LikeForm::new(&one);
        form.handle_key(KeyCode::Char('n'));
        form.handle_key(KeyCode::End);
        form.handle_key(KeyCode::Char(' '));
        assert_eq!(applied(form).fields_text(), "field body");

        let mut form = LikeForm::new(&one);
        form.handle_key(KeyCode::End);
        for _ in 0..4 {
            form.handle_key(KeyCode::Up);
        }
        form.handle_key(KeyCode::Left);
        assert_eq!(form.draft.max_query_terms, 1);
        assert_eq!(form.handle_key(KeyCode::Esc), FormEvent::Cancel);
    }
}
