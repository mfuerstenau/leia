//! The query parser options of the search screen (ADR 0011, ADR 0026), and the popup that edits
//! them: the options both parsers share, the classic parser's own, how range bounds are read as
//! dates, and the type of each point field for the standard parser.

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::Rect;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear};

use crate::form::{FormEvent, dim, render_popup, row, type_digit};
use crate::input::{LineInput, word_start};
use crate::protocol::index::IndexField;
use crate::protocol::search::{
    DateResolution, MAX_FUZZY_EDITS, ParserOptions, PointField, PointType,
};
use crate::text::escape_control;

/// The popup's width.
const WIDTH: u16 = 48;

/// The largest slop or prefix length the popup takes.
const MAX_NUMBER: i32 = 999;

/// The parser options, as the search screen sends them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParserSettings {
    pub options: ParserOptions,
    /// Classic only.
    pub fuzzy_max_edits: i32,
    /// Classic only.
    pub split_on_whitespace: bool,
    /// Classic only; needs `split_on_whitespace`.
    pub auto_generate_phrase_queries: bool,
    /// The one-dimensional point fields of the index, for the standard parser.
    pub points: Vec<PointSetting>,
}

/// A point field and the type its values are read as; `None` leaves the field to text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PointSetting {
    pub field: String,
    /// 4 or 8.
    bytes: i32,
    pub point_type: Option<PointType>,
}

impl ParserSettings {
    /// Lucene's defaults. Every one-dimensional point field of 4 or 8 bytes is guessed to hold
    /// ints or longs, since the index doesn't say (ADR 0011).
    pub fn new(fields: &[IndexField]) -> Self {
        let points = fields
            .iter()
            .filter(|field| field.point_dimension_count == 1)
            .filter_map(|field| {
                let point_type = match field.point_num_bytes {
                    4 => PointType::Int,
                    8 => PointType::Long,
                    _ => return None,
                };
                Some(PointSetting {
                    field: field.name.clone(),
                    bytes: field.point_num_bytes,
                    point_type: Some(point_type),
                })
            })
            .collect();
        Self {
            options: ParserOptions::default(),
            fuzzy_max_edits: MAX_FUZZY_EDITS,
            split_on_whitespace: false,
            auto_generate_phrase_queries: false,
            points,
        }
    }

    /// The point fields with a type, as `startSearch` takes them.
    pub fn point_fields(&self) -> Vec<PointField> {
        self.points
            .iter()
            .filter_map(|point| {
                Some(PointField {
                    field: point.field.clone(),
                    point_type: point.point_type?,
                })
            })
            .collect()
    }

    /// The options that differ from Lucene's defaults, for the details panel; the classic ones
    /// only for the classic parser.
    pub fn changed(&self, classic: bool) -> Vec<String> {
        let defaults = ParserOptions::default();
        let options = &self.options;
        let mut changed = Vec::new();
        if options.allow_leading_wildcard != defaults.allow_leading_wildcard {
            changed.push("leading wildcards".to_owned());
        }
        if options.phrase_slop != defaults.phrase_slop {
            changed.push(format!("phrase slop {}", options.phrase_slop));
        }
        if classic && self.fuzzy_max_edits != MAX_FUZZY_EDITS {
            changed.push(format!("fuzzy max edits {}", self.fuzzy_max_edits));
        }
        if options.fuzzy_prefix_length != defaults.fuzzy_prefix_length {
            changed.push(format!("fuzzy prefix {}", options.fuzzy_prefix_length));
        }
        if options.enable_position_increments != defaults.enable_position_increments {
            changed.push("no position increments".to_owned());
        }
        if classic && self.split_on_whitespace {
            changed.push("split on whitespace".to_owned());
        }
        if classic && self.auto_generate_phrase_queries {
            changed.push("auto phrases".to_owned());
        }
        if let Some(resolution) = options.date_resolution {
            changed.push(format!("dates {}", resolution.name()));
        }
        if let Some(locale) = &options.locale {
            changed.push(format!("locale {}", escape_control(locale)));
        }
        if let Some(zone) = &options.time_zone {
            changed.push(format!("zone {}", escape_control(zone)));
        }
        changed
    }

    /// The point fields and their types, for the details panel.
    pub fn points_text(&self) -> String {
        if self.points.is_empty() {
            return "no one-dimensional point fields".to_owned();
        }
        self.points
            .iter()
            .map(|point| {
                format!(
                    "{} {}",
                    escape_control(&point.field),
                    type_name(point.point_type)
                )
            })
            .collect::<Vec<_>>()
            .join(" · ")
    }
}

/// One line of the popup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Row {
    LeadingWildcard,
    PhraseSlop,
    FuzzyMaxEdits,
    FuzzyPrefixLength,
    PositionIncrements,
    SplitOnWhitespace,
    AutoPhrases,
    DateResolution,
    Locale,
    TimeZone,
    Point(usize),
}

/// The locale or the time zone, while it is typed.
#[derive(Debug)]
struct TextEdit {
    row: Row,
    input: LineInput,
}

/// The popup: a draft of the settings, applied with Enter.
#[derive(Debug)]
pub struct OptionsForm {
    draft: ParserSettings,
    /// Whether the rows are the classic parser's, not the standard parser's.
    classic: bool,
    selected: usize,
    editing: Option<TextEdit>,
}

impl OptionsForm {
    pub fn new(settings: &ParserSettings, classic: bool) -> Self {
        Self {
            draft: settings.clone(),
            classic,
            selected: 0,
            editing: None,
        }
    }

    fn rows(&self) -> Vec<Row> {
        if self.classic {
            vec![
                Row::LeadingWildcard,
                Row::PhraseSlop,
                Row::FuzzyMaxEdits,
                Row::FuzzyPrefixLength,
                Row::PositionIncrements,
                Row::SplitOnWhitespace,
                Row::AutoPhrases,
                Row::DateResolution,
                Row::Locale,
                Row::TimeZone,
            ]
        } else {
            [
                Row::LeadingWildcard,
                Row::PhraseSlop,
                Row::FuzzyPrefixLength,
                Row::PositionIncrements,
                Row::DateResolution,
                Row::Locale,
                Row::TimeZone,
            ]
            .into_iter()
            .chain((0..self.draft.points.len()).map(Row::Point))
            .collect()
        }
    }

    /// ↑↓ select a row; ←→, Space, `-`/`+` change it; digits and Backspace edit a number; →,
    /// Space or `+` types a locale or time zone, `d` or Backspace clears it; Enter applies, Esc
    /// cancels.
    pub fn handle_key(&mut self, key: KeyEvent) -> FormEvent<ParserSettings> {
        if self.editing.is_some() {
            self.text_key(key);
            return FormEvent::None;
        }
        let rows = self.rows();
        let row = rows[self.selected.min(rows.len() - 1)];
        match key.code {
            KeyCode::Enter => return FormEvent::Apply(self.draft.clone()),
            KeyCode::Esc => return FormEvent::Cancel,
            KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
            KeyCode::Down | KeyCode::Char('j') => {
                self.selected = (self.selected + 1).min(rows.len() - 1);
            }
            KeyCode::Home | KeyCode::Char('g') => self.selected = 0,
            KeyCode::End | KeyCode::Char('G') => self.selected = rows.len() - 1,
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
                } else if let Some(text) = self.text(row) {
                    *text = None;
                }
            }
            KeyCode::Char('d') | KeyCode::Delete => {
                if let Some(text) = self.text(row) {
                    *text = None;
                }
            }
            _ => {}
        }
        FormEvent::None
    }

    /// Enter takes the text, blank for the server's default; Esc goes back; any other key edits
    /// it.
    fn text_key(&mut self, key: KeyEvent) {
        let Some(edit) = &mut self.editing else {
            return;
        };
        match key.code {
            KeyCode::Esc => self.editing = None,
            KeyCode::Enter => {
                let typed = edit.input.text().trim();
                let typed = (!typed.is_empty()).then(|| typed.to_owned());
                let row = edit.row;
                if let Some(text) = self.text(row) {
                    *text = typed;
                }
                self.editing = None;
            }
            _ => {
                edit.input.handle_key(key, word_start);
            }
        }
    }

    /// The text a row holds: the locale or the time zone.
    fn text(&mut self, row: Row) -> Option<&mut Option<String>> {
        match row {
            Row::Locale => Some(&mut self.draft.options.locale),
            Row::TimeZone => Some(&mut self.draft.options.time_zone),
            _ => None,
        }
    }

    /// The number a row holds, with its largest value.
    fn number(&mut self, row: Row) -> Option<(&mut i32, i32)> {
        match row {
            Row::PhraseSlop => Some((&mut self.draft.options.phrase_slop, MAX_NUMBER)),
            Row::FuzzyMaxEdits => Some((&mut self.draft.fuzzy_max_edits, MAX_FUZZY_EDITS)),
            Row::FuzzyPrefixLength => {
                Some((&mut self.draft.options.fuzzy_prefix_length, MAX_NUMBER))
            }
            _ => None,
        }
    }

    /// Steps a number by `step`, flips a switch, cycles the date resolution or a point type, or
    /// starts typing a text forward.
    fn change(&mut self, row: Row, step: i32) {
        if let Some((number, max)) = self.number(row) {
            *number = (*number + step).clamp(0, max);
            return;
        }
        if let Some(text) = self.text(row) {
            if step > 0 {
                let input = LineInput::new(text.as_deref().unwrap_or_default());
                self.editing = Some(TextEdit { row, input });
            }
            return;
        }
        let draft = &mut self.draft;
        match row {
            Row::LeadingWildcard => {
                draft.options.allow_leading_wildcard = !draft.options.allow_leading_wildcard;
            }
            Row::PositionIncrements => {
                draft.options.enable_position_increments =
                    !draft.options.enable_position_increments;
            }
            // Lucene generates phrases only while it splits on whitespace.
            Row::SplitOnWhitespace => {
                draft.split_on_whitespace = !draft.split_on_whitespace;
                draft.auto_generate_phrase_queries &= draft.split_on_whitespace;
            }
            Row::AutoPhrases => {
                draft.auto_generate_phrase_queries = !draft.auto_generate_phrase_queries;
                draft.split_on_whitespace |= draft.auto_generate_phrase_queries;
            }
            Row::DateResolution => {
                let cycle: Vec<Option<DateResolution>> = std::iter::once(None)
                    .chain(DateResolution::ALL.into_iter().map(Some))
                    .collect();
                draft.options.date_resolution = cycled(&cycle, draft.options.date_resolution, step);
            }
            Row::Point(i) => {
                let point = &mut draft.points[i];
                let cycle = if point.bytes == 4 {
                    [Some(PointType::Int), Some(PointType::Float), None]
                } else {
                    [Some(PointType::Long), Some(PointType::Double), None]
                };
                point.point_type = cycled(&cycle, point.point_type, step);
            }
            Row::PhraseSlop
            | Row::FuzzyMaxEdits
            | Row::FuzzyPrefixLength
            | Row::Locale
            | Row::TimeZone => {}
        }
    }

    /// The keys for the footer while the popup is open.
    pub fn keys(&self) -> &'static str {
        if self.editing.is_some() {
            " ⏎ take   Esc back   ←→ Ctrl+A/E/U/K/W edit"
        } else {
            " ⏎ apply   Esc cancel   ↑↓ select   ←→ Space change   0-9 number   d default"
        }
    }

    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let rows = self.rows();
        let selected = self.selected.min(rows.len() - 1);
        let draft = &self.draft;
        let mut lines: Vec<Line> = Vec::new();
        let mut selected_line = 0;
        for (i, row_kind) in rows.iter().enumerate() {
            if *row_kind == Row::DateResolution {
                lines.push(Line::from(""));
                lines.push(Line::from(Span::styled("Range bounds as dates", dim())));
            }
            if *row_kind == Row::Point(0) {
                lines.push(Line::from(""));
                lines.push(Line::from(Span::styled(
                    "Point types, guessed: the index doesn't say",
                    dim(),
                )));
            }
            let (label, value) = match *row_kind {
                Row::LeadingWildcard => (
                    "Leading wildcard".to_owned(),
                    switch(draft.options.allow_leading_wildcard),
                ),
                Row::PhraseSlop => (
                    "Phrase slop".to_owned(),
                    draft.options.phrase_slop.to_string(),
                ),
                Row::FuzzyMaxEdits => (
                    "Fuzzy max edits".to_owned(),
                    draft.fuzzy_max_edits.to_string(),
                ),
                Row::FuzzyPrefixLength => (
                    "Fuzzy prefix length".to_owned(),
                    draft.options.fuzzy_prefix_length.to_string(),
                ),
                Row::PositionIncrements => (
                    "Position increments".to_owned(),
                    switch(draft.options.enable_position_increments),
                ),
                Row::SplitOnWhitespace => (
                    "Split on whitespace".to_owned(),
                    switch(draft.split_on_whitespace),
                ),
                Row::AutoPhrases => (
                    "Auto phrase queries".to_owned(),
                    switch(draft.auto_generate_phrase_queries),
                ),
                Row::DateResolution => (
                    "Resolution".to_owned(),
                    draft
                        .options
                        .date_resolution
                        .map_or("none: text", DateResolution::name)
                        .to_owned(),
                ),
                Row::Locale => (
                    "Locale".to_owned(),
                    text_value(draft.options.locale.as_deref()),
                ),
                Row::TimeZone => (
                    "Time zone".to_owned(),
                    text_value(draft.options.time_zone.as_deref()),
                ),
                Row::Point(p) => (
                    escape_control(&draft.points[p].field),
                    type_name(draft.points[p].point_type).to_owned(),
                ),
            };
            if i == selected {
                selected_line = lines.len();
            }
            lines.push(row(&label, &value, i == selected));
        }
        if !self.classic && draft.points.is_empty() {
            lines.push(Line::from(""));
            lines.push(Line::from(Span::styled(
                "No one-dimensional point fields",
                dim(),
            )));
        }
        let title = if self.classic {
            " Classic parser options "
        } else {
            " Standard parser options "
        };
        render_popup(frame, area, title, lines, selected_line, WIDTH);
        if let Some(edit) = &self.editing {
            render_text(frame, area, edit);
        }
    }
}

/// The entry after (or before) `current` in `cycle`, wrapping around.
fn cycled<T: Copy + PartialEq>(cycle: &[T], current: T, step: i32) -> T {
    let at = cycle.iter().position(|t| *t == current).unwrap_or_default();
    cycle[(at as i32 + step).rem_euclid(cycle.len() as i32) as usize]
}

fn text_value(text: Option<&str>) -> String {
    text.map_or_else(|| "server default".to_owned(), escape_control)
}

/// The line that the locale or the time zone is typed in, over the popup.
fn render_text(frame: &mut Frame, area: Rect, edit: &TextEdit) {
    let title = match edit.row {
        Row::Locale => " Locale, such as en-US or de-DE (blank for the default) ",
        _ => " Time zone, such as UTC or Europe/Berlin (blank for the default) ",
    };
    let width = area.width.min(WIDTH + 20);
    let height = 3.min(area.height);
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
    frame.set_cursor_position((inner.x + cursor_x, inner.y));
}

fn switch(on: bool) -> String {
    if on { "on" } else { "off" }.to_owned()
}

fn type_name(point_type: Option<PointType>) -> &'static str {
    match point_type {
        Some(PointType::Int) => "INT",
        Some(PointType::Long) => "LONG",
        Some(PointType::Float) => "FLOAT",
        Some(PointType::Double) => "DOUBLE",
        None => "text",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::index::{DocValuesType, IndexOptions};

    fn field(name: &str, dimensions: i32, bytes: i32) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number: 0,
            index_options: IndexOptions::None,
            has_norms: false,
            has_payloads: false,
            has_term_vectors: false,
            doc_values_type: DocValuesType::None,
            point_dimension_count: dimensions,
            point_num_bytes: bytes,
            vector_dimension: 0,
            terms: None,
        }
    }

    fn settings() -> ParserSettings {
        ParserSettings::new(&[
            field("body", 0, 0),
            field("quantity", 1, 4),
            field("price", 1, 8),
            field("location", 2, 4),
            field("ip", 1, 16),
        ])
    }

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::from(code)
    }

    fn press(form: &mut OptionsForm, keys: &[KeyCode]) {
        for code in keys {
            assert_eq!(form.handle_key(key(*code)), FormEvent::None);
        }
    }

    fn type_text(form: &mut OptionsForm, text: &str) {
        for c in text.chars() {
            press(form, &[KeyCode::Char(c)]);
        }
    }

    fn applied(mut form: OptionsForm) -> ParserSettings {
        match form.handle_key(key(KeyCode::Enter)) {
            FormEvent::Apply(settings) => settings,
            other => panic!("expected Apply, got {other:?}"),
        }
    }

    #[test]
    fn guesses_integer_types_for_one_dimensional_points() {
        let settings = settings();
        assert_eq!(
            settings.point_fields(),
            vec![
                PointField {
                    field: "quantity".to_owned(),
                    point_type: PointType::Int,
                },
                PointField {
                    field: "price".to_owned(),
                    point_type: PointType::Long,
                },
            ]
        );
        assert_eq!(settings.points_text(), "quantity INT · price LONG");
        assert!(settings.changed(true).is_empty());
    }

    #[test]
    fn cycles_a_point_type_through_its_width() {
        let mut form = OptionsForm::new(&settings(), false);
        // Leading wildcard, slop, prefix, increments, then quantity and price.
        press(&mut form, &[KeyCode::End, KeyCode::Char(' ')]);
        assert_eq!(form.draft.points[1].point_type, Some(PointType::Double));
        press(&mut form, &[KeyCode::Char(' ')]);
        assert_eq!(form.draft.points[1].point_type, None);
        press(&mut form, &[KeyCode::Up, KeyCode::Left]);
        assert_eq!(form.draft.points[0].point_type, None);
        let settings = applied(form);
        assert!(settings.point_fields().is_empty());
        assert_eq!(settings.points_text(), "quantity text · price text");
    }

    #[test]
    fn edits_numbers_within_their_range() {
        let mut form = OptionsForm::new(&settings(), true);
        // Phrase slop: digits append, Backspace drops the last one, ← stops at 0.
        press(
            &mut form,
            &[
                KeyCode::Down,
                KeyCode::Char('1'),
                KeyCode::Char('2'),
                KeyCode::Char('3'),
                KeyCode::Char('4'),
                KeyCode::Backspace,
            ],
        );
        assert_eq!(form.draft.options.phrase_slop, 0);
        press(&mut form, &[KeyCode::Char('1'), KeyCode::Char('2')]);
        assert_eq!(form.draft.options.phrase_slop, 12);
        // Fuzzy max edits stays within 0 to 2.
        press(
            &mut form,
            &[KeyCode::Down, KeyCode::Right, KeyCode::Char('7')],
        );
        assert_eq!(form.draft.fuzzy_max_edits, 2);
        press(
            &mut form,
            &[KeyCode::Char('1'), KeyCode::Left, KeyCode::Left],
        );
        assert_eq!(form.draft.fuzzy_max_edits, 0);
        let settings = applied(form);
        assert_eq!(
            settings.changed(true),
            vec!["phrase slop 12", "fuzzy max edits 0"]
        );
        // The standard parser has no fuzzy max edits.
        assert_eq!(settings.changed(false), vec!["phrase slop 12"]);
    }

    #[test]
    fn auto_phrases_need_whitespace_splitting() {
        let mut form = OptionsForm::new(&settings(), true);
        // The last rows are the dates': resolution, locale and time zone.
        press(
            &mut form,
            &[
                KeyCode::End,
                KeyCode::Up,
                KeyCode::Up,
                KeyCode::Up,
                KeyCode::Char(' '),
            ],
        );
        assert!(form.draft.auto_generate_phrase_queries);
        assert!(form.draft.split_on_whitespace);
        press(&mut form, &[KeyCode::Up, KeyCode::Char(' ')]);
        assert!(!form.draft.split_on_whitespace);
        assert!(!form.draft.auto_generate_phrase_queries);
    }

    #[test]
    fn cancelling_keeps_nothing() {
        let mut form = OptionsForm::new(&settings(), true);
        press(&mut form, &[KeyCode::Char(' ')]);
        assert_eq!(form.handle_key(key(KeyCode::Esc)), FormEvent::Cancel);
    }

    #[test]
    fn cycles_the_date_resolution() {
        let mut form = OptionsForm::new(&settings(), true);
        // Leading wildcard, slop, fuzzy max edits, prefix, increments, split, auto phrases.
        let resolution = [KeyCode::Down; 7];
        press(&mut form, &resolution);
        press(&mut form, &[KeyCode::Right]);
        assert_eq!(
            form.draft.options.date_resolution,
            Some(DateResolution::Year)
        );
        press(&mut form, &[KeyCode::Left, KeyCode::Left]);
        assert_eq!(
            form.draft.options.date_resolution,
            Some(DateResolution::Millisecond)
        );
        press(&mut form, &[KeyCode::Char(' ')]);
        assert_eq!(form.draft.options.date_resolution, None);
        press(&mut form, &[KeyCode::Char('+'); 3]);
        let settings = applied(form);
        assert_eq!(settings.options.date_resolution, Some(DateResolution::Day));
        assert_eq!(settings.changed(true), vec!["dates DAY"]);
        assert_eq!(settings.changed(false), vec!["dates DAY"]);
    }

    #[test]
    fn types_the_locale_and_the_time_zone() {
        let mut form = OptionsForm::new(&settings(), false);
        // Leading wildcard, slop, prefix, increments, resolution, then locale and time zone.
        press(&mut form, &[KeyCode::Down; 5]);
        press(&mut form, &[KeyCode::Char(' ')]);
        // While typing, letters are text, not keys of the popup.
        type_text(&mut form, "de-DEx");
        press(&mut form, &[KeyCode::Backspace, KeyCode::Enter]);
        assert_eq!(form.draft.options.locale.as_deref(), Some("de-DE"));
        press(&mut form, &[KeyCode::Down, KeyCode::Right]);
        type_text(&mut form, "  Europe/Berlin ");
        press(&mut form, &[KeyCode::Enter]);
        assert_eq!(
            form.draft.options.time_zone.as_deref(),
            Some("Europe/Berlin")
        );
        // Esc goes back to the popup without taking the text.
        press(&mut form, &[KeyCode::Right]);
        type_text(&mut form, "UTC");
        press(&mut form, &[KeyCode::Esc]);
        assert_eq!(
            form.draft.options.time_zone.as_deref(),
            Some("Europe/Berlin")
        );
        let settings = applied(form);
        assert_eq!(
            settings.changed(false),
            vec!["locale de-DE", "zone Europe/Berlin"]
        );
    }

    #[test]
    fn clears_the_locale_and_the_time_zone() {
        let mut settings = settings();
        settings.options.locale = Some("de-DE".to_owned());
        settings.options.time_zone = Some("UTC".to_owned());
        let mut form = OptionsForm::new(&settings, false);
        press(&mut form, &[KeyCode::Down; 5]);
        press(&mut form, &[KeyCode::Char('d'), KeyCode::Down]);
        // A blank text is the server's default too.
        press(&mut form, &[KeyCode::Right]);
        assert_eq!(
            form.editing.as_ref().map(|edit| edit.input.text()),
            Some("UTC")
        );
        press(&mut form, &[KeyCode::Backspace; 3]);
        type_text(&mut form, "  ");
        press(&mut form, &[KeyCode::Enter]);
        let settings = applied(form);
        assert_eq!(settings.options.locale, None);
        assert_eq!(settings.options.time_zone, None);
        assert!(settings.changed(false).is_empty());
    }
}
