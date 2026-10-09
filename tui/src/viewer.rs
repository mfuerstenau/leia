//! The value viewer: the values of one field of a document, one at a time, over the whole
//! documents screen, as text, as a hex dump, as pretty-printed JSON, or split at a delimiter. A
//! stored value that `getDocument` cut (ADR 0008) is fetched whole when it is shown. A value from
//! the regex scan shows its matches highlighted in the text mode, and `m`/`M` jump between them
//! (ADR 0027).

use std::borrow::Cow;

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, Paragraph};
use serde::de::IgnoredAny;
use unicode_width::{UnicodeWidthChar, UnicodeWidthStr};

use crate::protocol::document::{BytesValue, MAX_VALUE_LENGTH, StoredType, StoredValue};
use crate::protocol::search::MatchSpan;
use crate::text::{MATCH, escape_control};

/// Spaces a tab is shown as.
const TAB: &str = "    ";
/// From this many bytes on, copying warns that the terminal may drop the copy.
const LARGE_COPY: usize = 100 * 1024;
/// From this inner width on, a hex row has 16 bytes instead of 8.
const WIDE_HEX_WIDTH: u16 = 78;
/// Spaces per JSON nesting level.
const JSON_INDENT: &str = "  ";
/// Keys up to this display width are aligned in the delimited mode; longer ones are not padded.
const MAX_KEY_WIDTH: usize = 32;

const JSON_KEY: Style = Style::new().fg(Color::Cyan);
const JSON_STRING: Style = Style::new().fg(Color::Green);
const JSON_NUMBER: Style = Style::new().fg(Color::Yellow);
const JSON_LITERAL: Style = Style::new().fg(Color::Magenta);
const KEY: Style = Style::new().fg(Color::Cyan);
const DIM: Style = Style::new().add_modifier(Modifier::DIM);

/// What the viewer asks of the documents screen.
#[derive(Debug, PartialEq, Eq)]
pub enum Event {
    /// Fetch the whole stored value with this index, and report back with [`Viewer::loaded`] or
    /// [`Viewer::failed`].
    Fetch {
        doc_id: i32,
        field: String,
        index: i32,
    },
    /// Put this text on the clipboard.
    Copy(String),
    /// Analyze the shown value's text on the analysis screen.
    Analyze {
        doc_id: i32,
        field: String,
        text: String,
    },
    Close,
}

/// How the shown value is shown.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Text,
    Hex,
    /// Pretty-printed, as text if it is not JSON.
    Json,
    /// One item per line, split at the [`Delimiters`].
    Delimited,
}

impl Mode {
    fn name(self) -> &'static str {
        match self {
            Mode::Text => "text",
            Mode::Hex => "hex",
            Mode::Json => "json",
            Mode::Delimited => "delimited",
        }
    }
}

/// The delimiters of the delimited mode, as typed: `\t`, `\n`, `\r`, `\0`, `\\`, `\xHH` and
/// `\u{H…}` stand for characters.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Delimiters {
    /// Between two items; never empty.
    pub items: String,
    /// Between an item's key and its value; empty if items have no keys.
    pub key_value: String,
}

impl Default for Delimiters {
    fn default() -> Self {
        Self {
            items: ",".to_owned(),
            key_value: String::new(),
        }
    }
}

/// [`Delimiters`] with their escapes resolved.
#[derive(Debug, PartialEq, Eq)]
struct Split {
    items: String,
    key_value: Option<String>,
}

impl Delimiters {
    fn split(&self) -> Result<Split, String> {
        let items = unescape(&self.items).map_err(|e| format!("Items: {e}"))?;
        if items.is_empty() {
            return Err("Items: enter a delimiter.".to_owned());
        }
        let key_value = unescape(&self.key_value).map_err(|e| format!("Key/value: {e}"))?;
        Ok(Split {
            items,
            key_value: (!key_value.is_empty()).then_some(key_value),
        })
    }
}

/// `typed` with its escapes resolved.
fn unescape(typed: &str) -> Result<String, String> {
    let mut out = String::new();
    let mut chars = typed.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('0') => out.push('\0'),
            Some('\\') => out.push('\\'),
            Some('x') => {
                let hex: String = chars.by_ref().take(2).collect();
                let byte = u8::from_str_radix(&hex, 16)
                    .ok()
                    .filter(|byte| hex.len() == 2 && byte.is_ascii())
                    .ok_or("\\x takes two hex digits from 00 to 7f.")?;
                out.push(char::from(byte));
            }
            Some('u') => {
                let rest = chars.as_str();
                let c = rest
                    .strip_prefix('{')
                    .and_then(|rest| rest.split_once('}'))
                    .and_then(|(hex, _)| u32::from_str_radix(hex, 16).ok())
                    .and_then(char::from_u32)
                    .ok_or("\\u takes a character code in hex in braces, such as \\u{1f}.")?;
                out.push(c);
                // Skip past the closing brace.
                chars.by_ref().find(|&c| c == '}');
            }
            Some(other) => return Err(format!("unknown escape \\{other}; \\\\ is a backslash.")),
            None => return Err("a \\ at the end needs a character after it.".to_owned()),
        }
    }
    Ok(out)
}

#[derive(Debug, Clone, PartialEq)]
enum Content {
    /// Text; the hex dump shows its UTF-8.
    Text(String),
    /// Bytes that are not UTF-8; the text shows them with replacement characters.
    Bytes(Vec<u8>),
    /// A number: its decimal text, and the big-endian bytes of its Java type.
    Number { text: String, bytes: Vec<u8> },
}

impl Content {
    /// A byte string from the server: text, or hex if `binary`.
    fn from_wire(value: &str, binary: bool) -> Self {
        if binary && let Some(bytes) = parse_hex(value) {
            return Content::Bytes(bytes);
        }
        Content::Text(value.to_owned())
    }

    fn bytes(&self) -> &[u8] {
        match self {
            Content::Text(text) => text.as_bytes(),
            Content::Bytes(bytes) | Content::Number { bytes, .. } => bytes,
        }
    }

    fn text(&self) -> Cow<'_, str> {
        match self {
            Content::Text(text) | Content::Number { text, .. } => Cow::Borrowed(text),
            Content::Bytes(bytes) => String::from_utf8_lossy(bytes),
        }
    }
}

/// Where a value comes from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Source {
    /// The stored value with this index, as `getStoredValue` counts them.
    Stored(i32),
    DocValue,
}

/// One value of the field.
#[derive(Debug, Clone, PartialEq)]
pub struct Value {
    source: Source,
    /// The type: `string`, `long`, `sorted set`, …
    kind: String,
    content: Content,
    /// The whole value's length in bytes, while only the cut value is in.
    full_length: Option<i64>,
    fetching: bool,
    /// Why the whole value could not be fetched.
    error: Option<String>,
    /// Matches of a pattern, as UTF-8 byte ranges of the whole value, in order.
    matches: Vec<(usize, usize)>,
    /// How many matches the value has; more than `matches` if not all are known.
    match_count: usize,
}

impl Value {
    /// The stored value with this index.
    pub fn stored(index: usize, value: &StoredValue) -> Self {
        let content = match value.kind {
            StoredType::String | StoredType::Binary => {
                Content::from_wire(&value.value, value.binary)
            }
            StoredType::Int => number(&value.value, |text| {
                text.parse::<i32>().ok().map(|n| n.to_be_bytes().to_vec())
            }),
            StoredType::Long => number(&value.value, |text| {
                text.parse::<i64>().ok().map(|n| n.to_be_bytes().to_vec())
            }),
            StoredType::Float => number(&value.value, |text| {
                text.parse::<f32>().ok().map(|n| n.to_be_bytes().to_vec())
            }),
            StoredType::Double => number(&value.value, |text| {
                text.parse::<f64>().ok().map(|n| n.to_be_bytes().to_vec())
            }),
        };
        Self {
            source: Source::Stored(i32::try_from(index).unwrap_or(i32::MAX)),
            kind: stored_type(value.kind).to_owned(),
            content,
            full_length: value.full_length,
            fetching: false,
            error: None,
            matches: Vec::new(),
            match_count: 0,
        }
    }

    /// A byte string from doc values of type `kind`.
    pub fn doc_value(kind: &str, value: &BytesValue) -> Self {
        Self::doc_value_content(kind, Content::from_wire(&value.value, value.binary))
    }

    /// A number from doc values of type `kind`, shown as `text`.
    pub fn doc_value_number(kind: &str, number: i64, text: String) -> Self {
        let bytes = number.to_be_bytes().to_vec();
        Self::doc_value_content(kind, Content::Number { text, bytes })
    }

    fn doc_value_content(kind: &str, content: Content) -> Self {
        Self {
            source: Source::DocValue,
            kind: format!("{kind} doc value"),
            content,
            full_length: None,
            fetching: false,
            error: None,
            matches: Vec::new(),
            match_count: 0,
        }
    }

    /// The size of the whole value in bytes.
    fn size(&self) -> i64 {
        self.full_length
            .unwrap_or(self.content.bytes().len() as i64)
    }

    /// Whether the value is a JSON object or array, or the beginning of one if it is cut.
    fn looks_like_json(&self) -> bool {
        let Content::Text(text) = &self.content else {
            return false;
        };
        text.trim_start().starts_with(['{', '['])
            && (self.full_length.is_some() || serde_json::from_str::<IgnoredAny>(text).is_ok())
    }
}

/// A number's text, with the bytes `encode` makes of it; its text's bytes if it can't.
fn number(text: &str, encode: impl Fn(&str) -> Option<Vec<u8>>) -> Content {
    let bytes = encode(text).unwrap_or_else(|| text.as_bytes().to_vec());
    Content::Number {
        text: text.to_owned(),
        bytes,
    }
}

pub fn stored_type(kind: StoredType) -> &'static str {
    match kind {
        StoredType::String => "string",
        StoredType::Binary => "binary",
        StoredType::Int => "int",
        StoredType::Long => "long",
        StoredType::Float => "float",
        StoredType::Double => "double",
    }
}

fn parse_hex(hex: &str) -> Option<Vec<u8>> {
    if !hex.len().is_multiple_of(2) {
        return None;
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(hex.get(i..i + 2)?, 16).ok())
        .collect()
}

/// A line before it is wrapped: pieces of text with their style.
type Segments = Vec<(Style, String)>;

/// The shown value in a mode other than hex, before it is wrapped.
#[derive(Debug)]
struct Formatted {
    lines: Vec<Segments>,
    /// What the mode label at the bottom adds, such as the number of items.
    detail: Option<String>,
    /// Why the value is not shown in the mode, such as JSON that does not parse.
    notice: Option<String>,
}

impl Formatted {
    fn text(text: &str, notice: Option<String>) -> Self {
        let lines = text
            .split('\n')
            .map(|line| {
                vec![(
                    Style::new(),
                    line.strip_suffix('\r').unwrap_or(line).to_owned(),
                )]
            })
            .collect();
        Self {
            lines,
            detail: None,
            notice,
        }
    }

    /// `text` with `matches`, UTF-8 byte ranges in order, highlighted. A match that is not
    /// within the text, or not between characters, is left out; one over a line break is
    /// highlighted on both lines.
    fn highlighted(text: &str, matches: &[(usize, usize)]) -> Self {
        let mut lines = Vec::new();
        let mut matches = matches.iter().peekable();
        let mut line_start = 0;
        for line in text.split('\n') {
            let shown = line.strip_suffix('\r').unwrap_or(line);
            let end = line_start + shown.len();
            let mut segments: Segments = Vec::new();
            let mut pos = line_start;
            // Matches that end before this line are done.
            while matches.next_if(|&&(_, to)| to <= line_start).is_some() {}
            for &(from, to) in matches.clone() {
                if from >= end {
                    break;
                }
                let (from, to) = (from.max(pos), to.min(end));
                if to <= from || !text.is_char_boundary(from) || !text.is_char_boundary(to) {
                    continue;
                }
                if from > pos {
                    segments.push((Style::new(), text[pos..from].to_owned()));
                }
                segments.push((MATCH, text[from..to].to_owned()));
                pos = to;
            }
            if pos < end || segments.is_empty() {
                segments.push((Style::new(), text[pos..end].to_owned()));
            }
            lines.push(segments);
            line_start += line.len() + 1;
        }
        Self {
            lines,
            detail: None,
            notice: None,
        }
    }

    /// The lines as plain text, as they are copied.
    fn plain(&self) -> String {
        let lines: Vec<String> = self
            .lines
            .iter()
            .map(|line| line.iter().map(|(_, text)| text.as_str()).collect())
            .collect();
        lines.join("\n")
    }
}

/// The shown value formatted and wrapped at a width.
#[derive(Debug)]
struct Wrapped {
    width: usize,
    lines: Vec<Line<'static>>,
    /// The row each highlighted match starts on, in order.
    match_rows: Vec<usize>,
    detail: Option<String>,
    notice: Option<String>,
}

/// The form that asks for the delimiters of the delimited mode.
#[derive(Debug)]
struct DelimiterForm {
    items: String,
    key_value: String,
    /// Whether the key/value delimiter is being edited, not the items delimiter.
    on_key_value: bool,
    error: Option<String>,
}

impl DelimiterForm {
    fn new(delimiters: &Delimiters) -> Self {
        Self {
            items: delimiters.items.clone(),
            key_value: delimiters.key_value.clone(),
            on_key_value: false,
            error: None,
        }
    }

    fn delimiters(&self) -> Delimiters {
        Delimiters {
            items: self.items.clone(),
            key_value: self.key_value.clone(),
        }
    }
}

/// What a key did to the delimiter form.
enum FormEvent {
    None,
    Apply(Delimiters),
    Cancel,
}

pub struct Viewer {
    doc_id: i32,
    field: String,
    values: Vec<Value>,
    current: usize,
    mode: Mode,
    delimiters: Delimiters,
    form: Option<DelimiterForm>,
    /// The first row shown.
    scroll: usize,
    /// The rows that fit, as of the last render; PageUp and PageDown move by that.
    page: usize,
    /// `None` after the shown value, the mode or the delimiters changed.
    wrapped: Option<Wrapped>,
    /// What the last key did, such as copying; cleared by the next key.
    message: Option<String>,
    /// The match `m`/`M` last jumped to.
    match_index: usize,
    /// Whether the next render scrolls to the match at `match_index`.
    to_match: bool,
}

impl Viewer {
    /// Views `values`, which must not be empty, from the first one on, splitting them at
    /// `delimiters` in the delimited mode. Returns the viewer and the fetch of the first value, if
    /// it is cut.
    pub fn new(
        doc_id: i32,
        field: String,
        values: Vec<Value>,
        delimiters: Delimiters,
    ) -> (Self, Option<Event>) {
        // Bytes that are not UTF-8 are easier to read as hex, and JSON pretty-printed.
        let mode = match values.first() {
            Some(value) if matches!(value.content, Content::Bytes(_)) => Mode::Hex,
            Some(value) if value.looks_like_json() => Mode::Json,
            _ => Mode::Text,
        };
        let mut viewer = Self {
            doc_id,
            field,
            values,
            current: 0,
            mode,
            delimiters,
            form: None,
            scroll: 0,
            page: 10,
            wrapped: None,
            message: None,
            match_index: 0,
            to_match: false,
        };
        let fetch = viewer.fetch();
        (viewer, fetch)
    }

    /// Views `values` from the stored value with this index on, as text with `spans`
    /// highlighted, scrolled to the first of them. `match_count` counts all matches of the value,
    /// also those after `spans`. Returns the viewer and the fetch of the value, if it is cut.
    pub fn with_matches(
        doc_id: i32,
        field: String,
        mut values: Vec<Value>,
        delimiters: Delimiters,
        index: i32,
        spans: &[MatchSpan],
        match_count: i32,
    ) -> (Self, Option<Event>) {
        let current = values
            .iter()
            .position(|value| value.source == Source::Stored(index))
            .unwrap_or_default();
        if let Some(value) = values.get_mut(current) {
            value.matches = spans
                .iter()
                .filter_map(|span| {
                    Some((
                        usize::try_from(span.start).ok()?,
                        usize::try_from(span.end).ok()?,
                    ))
                })
                .collect();
            value.match_count = usize::try_from(match_count).unwrap_or_default();
        }
        let mut viewer = Self {
            doc_id,
            field,
            values,
            current,
            mode: Mode::Text,
            delimiters,
            form: None,
            scroll: 0,
            page: 10,
            wrapped: None,
            message: None,
            match_index: 0,
            to_match: true,
        };
        let fetch = viewer.fetch();
        (viewer, fetch)
    }

    /// Fetches the shown value whole, if it is cut and not fetched yet.
    fn fetch(&mut self) -> Option<Event> {
        let value = self.values.get_mut(self.current)?;
        let Source::Stored(index) = value.source else {
            return None;
        };
        if value.full_length.is_none() || value.fetching || value.error.is_some() {
            return None;
        }
        value.fetching = true;
        Some(Event::Fetch {
            doc_id: self.doc_id,
            field: self.field.clone(),
            index,
        })
    }

    fn stored_mut(&mut self, doc_id: i32, field: &str, index: i32) -> Option<&mut Value> {
        if doc_id != self.doc_id || field != self.field {
            return None;
        }
        self.values
            .iter_mut()
            .find(|value| value.source == Source::Stored(index))
    }

    /// Shows the whole value instead of the cut one.
    pub fn loaded(&mut self, doc_id: i32, field: &str, index: i32, value: &StoredValue) {
        let whole = Value::stored(0, value);
        if let Some(shown) = self.stored_mut(doc_id, field, index) {
            shown.content = whole.content;
            shown.full_length = whole.full_length;
            shown.fetching = false;
            self.wrapped = None;
        }
    }

    pub fn failed(&mut self, doc_id: i32, field: &str, index: i32, error: String) {
        if let Some(shown) = self.stored_mut(doc_id, field, index) {
            shown.fetching = false;
            shown.error = Some(error);
        }
    }

    pub fn mode(&self) -> Mode {
        self.mode
    }

    /// The delimiters last applied, to open the next viewer with.
    pub fn delimiters(&self) -> &Delimiters {
        &self.delimiters
    }

    /// The keys for the footer.
    pub fn keys(&self) -> &'static str {
        if self.form.is_some() {
            " type a delimiter   Tab/↑↓ other delimiter   ⏎ apply   Esc cancel"
        } else if !self.values[self.current].matches.is_empty() {
            " Esc close   m/M next/previous match   ←/→ value   t text   h hex   J json   \
             d delimited   y copy   a analyze   ↑↓ PgUp/PgDn Home/End"
        } else {
            " Esc close   ←/→ value   t text   h hex   J json   d delimited   y copy   \
             a analyze   ↑↓ PgUp/PgDn Home/End"
        }
    }

    pub fn handle_key(&mut self, key: KeyCode) -> Option<Event> {
        self.message = None;
        if let Some(form) = &mut self.form {
            match form_key(form, key) {
                FormEvent::None => {}
                FormEvent::Apply(delimiters) => {
                    self.form = None;
                    self.delimiters = delimiters;
                    self.set_mode(Mode::Delimited);
                }
                FormEvent::Cancel => self.form = None,
            }
            return None;
        }
        match key {
            KeyCode::Esc | KeyCode::Char('q' | 'v') => return Some(Event::Close),
            KeyCode::Left | KeyCode::Char('p') if self.current > 0 => {
                self.show(self.current - 1);
                return self.fetch();
            }
            KeyCode::Right | KeyCode::Char('n') if self.current + 1 < self.values.len() => {
                self.show(self.current + 1);
                return self.fetch();
            }
            KeyCode::Char('m' | 'M') if !self.values[self.current].matches.is_empty() => {
                let last = self.values[self.current].matches.len() - 1;
                self.match_index = if key == KeyCode::Char('m') {
                    (self.match_index + 1).min(last)
                } else {
                    self.match_index.saturating_sub(1)
                };
                if self.mode != Mode::Text {
                    self.set_mode(Mode::Text);
                }
                self.to_match = true;
            }
            KeyCode::Char('t') => self.set_mode(Mode::Text),
            KeyCode::Char('h') => self.toggle_mode(Mode::Hex),
            KeyCode::Char('J') => self.toggle_mode(Mode::Json),
            KeyCode::Char('d') => self.form = Some(DelimiterForm::new(&self.delimiters)),
            KeyCode::Char('y') => return self.copy(),
            KeyCode::Char('a') => {
                return Some(Event::Analyze {
                    doc_id: self.doc_id,
                    field: self.field.clone(),
                    text: self.values[self.current].content.text().into_owned(),
                });
            }
            KeyCode::Down | KeyCode::Char('j') => self.scroll = self.scroll.saturating_add(1),
            KeyCode::Up | KeyCode::Char('k') => self.scroll = self.scroll.saturating_sub(1),
            KeyCode::PageDown | KeyCode::Char(' ') => {
                self.scroll = self.scroll.saturating_add(self.page);
            }
            KeyCode::PageUp => self.scroll = self.scroll.saturating_sub(self.page),
            KeyCode::Home | KeyCode::Char('g') => self.scroll = 0,
            // Clamped when rendered.
            KeyCode::End | KeyCode::Char('G') => self.scroll = usize::MAX,
            _ => {}
        }
        None
    }

    /// Switches to `mode`, or back to text if it is shown already.
    fn toggle_mode(&mut self, mode: Mode) {
        self.set_mode(if self.mode == mode { Mode::Text } else { mode });
    }

    fn set_mode(&mut self, mode: Mode) {
        self.mode = mode;
        self.scroll = 0;
        self.wrapped = None;
    }

    fn show(&mut self, index: usize) {
        self.current = index;
        self.scroll = 0;
        self.wrapped = None;
    }

    /// Copies the shown value as it is shown: the text, the bytes in hex, the pretty-printed
    /// JSON, or the items one per line.
    fn copy(&mut self) -> Option<Event> {
        let value = &self.values[self.current];
        if value.full_length.is_some() {
            self.message = Some("Not copied: the whole value is not in.".to_owned());
            return None;
        }
        let text = match self.mode {
            Mode::Text => value.content.text().into_owned(),
            Mode::Hex => hex(value.content.bytes()),
            Mode::Json | Mode::Delimited => {
                let formatted = format(&value.content, self.mode, &self.delimiters);
                if formatted.notice.is_some() {
                    value.content.text().into_owned()
                } else {
                    formatted.plain()
                }
            }
        };
        // The terminal may drop a long escape sequence without a word: tmux takes about 1 MiB.
        let caveat = if text.len() > LARGE_COPY {
            "; terminals may drop copies this large"
        } else {
            ""
        };
        self.message = Some(format!(
            "Copied {} as {} (OSC 52){caveat}.",
            size(text.len() as i64),
            self.mode.name()
        ));
        Some(Event::Copy(text))
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let value = &self.values[self.current];
        let title = format!(
            " {} · {} of {} · {} · {} ",
            self.field,
            self.current + 1,
            self.values.len(),
            value.kind,
            size(value.size())
        );
        let block = Block::bordered()
            .title(title)
            .border_style(Style::new().add_modifier(Modifier::BOLD));
        let inner = block.inner(area);
        let height = usize::from(inner.height);
        self.page = height.saturating_sub(1).max(1);

        let bytes_per_row = if inner.width >= WIDE_HEX_WIDTH { 16 } else { 8 };
        let rows = if self.mode == Mode::Hex {
            value.content.bytes().len().div_ceil(bytes_per_row).max(1)
        } else {
            self.wrapped(usize::from(inner.width)).lines.len()
        };
        if self.to_match && self.mode == Mode::Text {
            let value = &self.values[self.current];
            let row = self
                .wrapped
                .as_ref()
                .and_then(|wrapped| wrapped.match_rows.get(self.match_index).copied());
            match row {
                // Two rows of context above the match.
                Some(row) => {
                    self.scroll = row.saturating_sub(2);
                    self.to_match = false;
                }
                // The match is past the cut: once the whole value is in.
                None if value.full_length.is_some() && value.error.is_none() => {}
                None => self.to_match = false,
            }
        }
        self.scroll = self.scroll.min(rows.saturating_sub(height));
        let shown = self.scroll..rows.min(self.scroll + height);

        let value = &self.values[self.current];
        let wrapped = self.wrapped.as_ref().filter(|_| self.mode != Mode::Hex);
        let lines: Vec<Line> = match wrapped {
            None => shown
                .clone()
                .map(|row| hex_row(value.content.bytes(), row, bytes_per_row))
                .collect(),
            Some(wrapped) => wrapped.lines[shown.clone()].to_vec(),
        };

        let status = if let Some(message) = &self.message {
            message.clone()
        } else if let Some(error) = &value.error {
            format!("Cut; cannot fetch it whole: {error}")
        } else if let Some(full) = value.full_length {
            format!(
                "Cut at {} of {}; fetching it whole…",
                size(value.content.bytes().len() as i64),
                size(full)
            )
        } else if let Some(notice) = wrapped.and_then(|w| w.notice.as_ref()) {
            notice.clone()
        } else if !value.matches.is_empty() {
            let marked = value.matches.len();
            let more = if value.match_count > marked {
                format!(" ({marked} of {} marked)", value.match_count)
            } else {
                String::new()
            };
            let hidden = if self.mode == Mode::Text {
                ""
            } else {
                "; t shows them"
            };
            format!("match {} of {marked}{more}{hidden}", self.match_index + 1)
        } else {
            String::new()
        };
        let mode = match wrapped.and_then(|w| w.detail.as_ref()) {
            Some(detail) => format!("{} · {detail}", self.mode.name()),
            None => self.mode.name().to_owned(),
        };
        let position = format!(
            " {mode} · rows {}–{} of {rows} ",
            shown.start + 1,
            shown.end
        );
        let block = block
            .title_bottom(Line::from(Span::raw(if status.is_empty() {
                String::new()
            } else {
                format!(" {status} ")
            })))
            .title_bottom(Line::from(position).right_aligned());
        frame.render_widget(Clear, area);
        frame.render_widget(Paragraph::new(lines).block(block), area);
        if let Some(form) = &self.form {
            render_form(frame, area, form);
        }
    }

    /// The shown value formatted in the mode and wrapped at `width`, formatted again only when
    /// needed.
    fn wrapped(&mut self, width: usize) -> &Wrapped {
        if self.wrapped.as_ref().is_none_or(|w| w.width != width) {
            let value = &self.values[self.current];
            let formatted = if self.mode == Mode::Text && !value.matches.is_empty() {
                Formatted::highlighted(&value.content.text(), &value.matches)
            } else {
                format(&value.content, self.mode, &self.delimiters)
            };
            let mut lines = Vec::new();
            let mut match_rows = Vec::new();
            for line in &formatted.lines {
                wrap_marking(line, width, &mut lines, &mut match_rows);
            }
            self.wrapped = Some(Wrapped {
                width,
                lines,
                match_rows,
                detail: formatted.detail,
                notice: formatted.notice,
            });
        }
        self.wrapped.as_ref().expect("just set")
    }
}

/// Edits the delimiter form.
fn form_key(form: &mut DelimiterForm, key: KeyCode) -> FormEvent {
    let input = if form.on_key_value {
        &mut form.key_value
    } else {
        &mut form.items
    };
    match key {
        KeyCode::Esc => return FormEvent::Cancel,
        KeyCode::Enter => {
            let delimiters = form.delimiters();
            match delimiters.split() {
                Ok(_) => return FormEvent::Apply(delimiters),
                Err(error) => form.error = Some(error),
            }
        }
        KeyCode::Tab | KeyCode::BackTab | KeyCode::Up | KeyCode::Down => {
            form.on_key_value = !form.on_key_value;
        }
        KeyCode::Char(c) => {
            input.push(c);
            form.error = None;
        }
        KeyCode::Backspace => {
            input.pop();
            form.error = None;
        }
        _ => {}
    }
    FormEvent::None
}

fn render_form(frame: &mut Frame, area: Rect, form: &DelimiterForm) {
    let width = area.width.min(56);
    let height = 5;
    let popup = Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + area.height.saturating_sub(height) / 2,
        width,
        height: height.min(area.height),
    };
    let field = |label: &str, input: &str, active: bool| {
        let cursor = if active { "▏" } else { "" };
        let label_style = if active {
            Style::new().add_modifier(Modifier::BOLD)
        } else {
            DIM
        };
        Line::from(vec![
            Span::styled(format!("{label:<10} "), label_style),
            Span::raw(format!("{input}{cursor}")),
        ])
    };
    let message = form
        .error
        .clone()
        .unwrap_or_else(|| "Escapes \\t \\n \\xHH \\u{H…}; no key/value: empty".to_owned());
    frame.render_widget(Clear, popup);
    frame.render_widget(
        Paragraph::new(vec![
            field("Items", &form.items, !form.on_key_value),
            field("Key/value", &form.key_value, form.on_key_value),
            Line::from(Span::styled(message, DIM)),
        ])
        .block(Block::bordered().title(" Delimited ")),
        popup,
    );
}

/// `content` in `mode`, which must not be hex.
fn format(content: &Content, mode: Mode, delimiters: &Delimiters) -> Formatted {
    let text = content.text();
    match mode {
        Mode::Text | Mode::Hex => Formatted::text(&text, None),
        Mode::Json => match json_lines(&text) {
            Ok(lines) => Formatted {
                lines,
                detail: None,
                notice: None,
            },
            Err(error) => Formatted::text(&text, Some(format!("Not JSON: {error}."))),
        },
        Mode::Delimited => match delimiters.split() {
            Ok(split) => delimited(&text, &split),
            Err(error) => Formatted::text(&text, Some(error)),
        },
    }
}

/// `text` pretty-printed as it is written: keys stay in their order, numbers and strings keep
/// their text, and empty objects and arrays stay on one line. An error if it is not JSON.
fn json_lines(text: &str) -> Result<Vec<Segments>, String> {
    serde_json::from_str::<IgnoredAny>(text).map_err(|e| e.to_string())?;

    let bytes = text.as_bytes();
    let is_space = |byte: u8| matches!(byte, b' ' | b'\t' | b'\n' | b'\r');
    let mut lines = Vec::new();
    let mut line: Segments = Vec::new();
    // Whether each open container is an object.
    let mut open: Vec<bool> = Vec::new();
    let mut expect_key = false;
    let new_line = |lines: &mut Vec<Segments>, line: &mut Segments, depth: usize| {
        lines.push(std::mem::take(line));
        line.push((Style::new(), JSON_INDENT.repeat(depth)));
    };
    let mut i = 0;
    while i < bytes.len() {
        let byte = bytes[i];
        if is_space(byte) {
            i += 1;
            continue;
        }
        match byte {
            b'{' | b'[' => {
                let close = if byte == b'{' { b'}' } else { b']' };
                let next = (i + 1..bytes.len()).find(|&j| !is_space(bytes[j]));
                if next.is_some_and(|j| bytes[j] == close) {
                    line.push((Style::new(), format!("{}{}", byte as char, close as char)));
                    i = next.unwrap_or(i) + 1;
                    continue;
                }
                line.push((Style::new(), (byte as char).to_string()));
                open.push(byte == b'{');
                expect_key = byte == b'{';
                new_line(&mut lines, &mut line, open.len());
                i += 1;
            }
            b'}' | b']' => {
                open.pop();
                new_line(&mut lines, &mut line, open.len());
                line.push((Style::new(), (byte as char).to_string()));
                i += 1;
            }
            b',' => {
                line.push((Style::new(), ",".to_owned()));
                expect_key = open.last() == Some(&true);
                new_line(&mut lines, &mut line, open.len());
                i += 1;
            }
            b':' => {
                line.push((Style::new(), ": ".to_owned()));
                expect_key = false;
                i += 1;
            }
            b'"' => {
                let mut end = i + 1;
                while end < bytes.len() && bytes[end] != b'"' {
                    end += if bytes[end] == b'\\' { 2 } else { 1 };
                }
                let end = end.min(bytes.len() - 1);
                let style = if expect_key { JSON_KEY } else { JSON_STRING };
                line.push((style, text[i..=end].to_owned()));
                i = end + 1;
            }
            _ => {
                let end = (i..bytes.len())
                    .find(|&j| is_space(bytes[j]) || b",:]}".contains(&bytes[j]))
                    .unwrap_or(bytes.len());
                let style = if byte == b'-' || byte.is_ascii_digit() {
                    JSON_NUMBER
                } else {
                    JSON_LITERAL
                };
                line.push((style, text[i..end].to_owned()));
                i = end;
            }
        }
    }
    lines.push(line);
    Ok(lines)
}

/// `text` split at the delimiters, one item per line: numbered, or with aligned keys if items
/// have keys. Control characters in items are escaped, so an item stays on its line.
fn delimited(text: &str, split: &Split) -> Formatted {
    let items: Vec<&str> = text.split(split.items.as_str()).collect();
    let detail = Some(match items.len() {
        1 => "1 item".to_owned(),
        n => format!("{n} items"),
    });
    let lines = match &split.key_value {
        None => {
            let digits = items.len().to_string().len();
            items
                .iter()
                .enumerate()
                .map(|(i, item)| {
                    vec![
                        (DIM, format!("{:>digits$}  ", i + 1)),
                        (Style::new(), escape_control(item)),
                    ]
                })
                .collect()
        }
        Some(key_value) => {
            let pairs: Vec<(String, Option<String>)> = items
                .iter()
                .map(|item| match item.split_once(key_value.as_str()) {
                    Some((key, value)) => (escape_control(key), Some(escape_control(value))),
                    None => (escape_control(item), None),
                })
                .collect();
            let key_width = pairs
                .iter()
                .filter(|(_, value)| value.is_some())
                .map(|(key, _)| key.width())
                .filter(|&width| width <= MAX_KEY_WIDTH)
                .max()
                .unwrap_or(0);
            let separator = format!(" {} ", escape_control(key_value));
            pairs
                .into_iter()
                .map(|(key, value)| match value {
                    Some(value) => {
                        let padding = " ".repeat(key_width.saturating_sub(key.width()));
                        vec![
                            (KEY, key),
                            (Style::new(), padding),
                            (DIM, separator.clone()),
                            (Style::new(), value),
                        ]
                    }
                    None => vec![(Style::new(), key)],
                })
                .collect()
        }
    };
    Formatted {
        lines,
        detail,
        notice: None,
    }
}

/// Appends `line` to `out` as lines no wider than `width`: tabs as spaces, control characters
/// escaped.
#[cfg(test)]
fn wrap(line: &[(Style, String)], width: usize, out: &mut Vec<Line<'static>>) {
    wrap_marking(line, width, out, &mut Vec::new());
}

/// As `wrap`, and appends the row each highlighted match starts on to `match_rows`.
fn wrap_marking(
    line: &[(Style, String)],
    width: usize,
    out: &mut Vec<Line<'static>>,
    match_rows: &mut Vec<usize>,
) {
    let width = width.max(1);
    let mut spans: Vec<Span<'static>> = Vec::new();
    let mut current = String::new();
    let mut used = 0;
    for (style, text) in line {
        let mut first = *style == MATCH;
        for c in text.chars() {
            let shown: Cow<str> = if c == '\t' {
                Cow::Borrowed(TAB)
            } else if c.is_control() {
                Cow::Owned(c.escape_default().collect())
            } else {
                Cow::Owned(c.to_string())
            };
            for c in shown.chars() {
                let w = c.width().unwrap_or(0);
                if used + w > width && used > 0 {
                    if !current.is_empty() {
                        spans.push(Span::styled(std::mem::take(&mut current), *style));
                    }
                    out.push(Line::from(std::mem::take(&mut spans)));
                    used = 0;
                }
                if std::mem::take(&mut first) {
                    match_rows.push(out.len());
                }
                current.push(c);
                used += w;
            }
        }
        if !current.is_empty() {
            spans.push(Span::styled(std::mem::take(&mut current), *style));
        }
    }
    out.push(Line::from(spans));
}

/// One row of the hex dump: the offset, the bytes in hex, and the printable ASCII ones.
fn hex_row(bytes: &[u8], row: usize, bytes_per_row: usize) -> Line<'static> {
    let start = row * bytes_per_row;
    let chunk = &bytes[start.min(bytes.len())..(start + bytes_per_row).min(bytes.len())];
    let mut hex = String::with_capacity(bytes_per_row * 3 + 1);
    for i in 0..bytes_per_row {
        if i == 8 {
            hex.push(' ');
        }
        match chunk.get(i) {
            Some(byte) => hex.push_str(&format!("{byte:02x} ")),
            None => hex.push_str("   "),
        }
    }
    let ascii: String = chunk
        .iter()
        .map(|&byte| {
            if byte.is_ascii_graphic() || byte == b' ' {
                char::from(byte)
            } else {
                '.'
            }
        })
        .collect();
    Line::from(vec![
        Span::styled(format!("{start:08x}  "), DIM),
        Span::raw(hex),
        Span::styled(" |", DIM),
        Span::raw(ascii),
        Span::styled("|", DIM),
    ])
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// `512 B`, `1.5 KiB`, `5.0 MiB`, `2.0 GiB`.
pub fn size(bytes: i64) -> String {
    const KIB: f64 = 1024.0;
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KiB", bytes as f64 / KIB)
    } else if bytes < 1024 * 1024 * 1024 {
        format!("{:.1} MiB", bytes as f64 / KIB / KIB)
    } else {
        format!("{:.1} GiB", bytes as f64 / KIB / KIB / KIB)
    }
}

/// `MAX_VALUE_LENGTH` for messages.
pub fn cut_size() -> String {
    size(MAX_VALUE_LENGTH as i64)
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::index::DocValuesType;

    fn stored(kind: StoredType, value: &str, binary: bool) -> StoredValue {
        StoredValue {
            kind,
            value: value.to_owned(),
            binary,
            full_length: None,
        }
    }

    fn text(value: &str) -> StoredValue {
        stored(StoredType::String, value, false)
    }

    fn render(viewer: &mut Viewer, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| viewer.render(frame, frame.area()))
            .unwrap();
        let buffer = terminal.backend().buffer();
        (0..height)
            .map(|y| {
                (0..width)
                    .map(|x| buffer[(x, y)].symbol())
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    fn viewer(values: Vec<Value>) -> Viewer {
        let (viewer, fetch) = Viewer::new(4, "body".to_owned(), values, Delimiters::default());
        assert_eq!(fetch, None);
        viewer
    }

    #[test]
    fn highlights_matches_through_tabs_control_characters_and_line_breaks() {
        // "red" at 2..5, "re\u{1}d" at 6..10, and one over the line break at 4..7.
        let text = "a\tred\nre\u{1}d x";
        let formatted = Formatted::highlighted(text, &[(2, 5), (6, 10)]);
        let mut lines = Vec::new();
        let mut rows = Vec::new();
        for line in &formatted.lines {
            wrap_marking(line, 40, &mut lines, &mut rows);
        }

        let marked: Vec<Vec<(String, bool)>> = lines
            .iter()
            .map(|line| {
                line.spans
                    .iter()
                    .map(|span| (span.content.to_string(), span.style == MATCH))
                    .collect()
            })
            .collect();
        assert_eq!(
            marked,
            [
                vec![("a    ".to_owned(), false), ("red".to_owned(), true)],
                vec![("re\\u{1}d".to_owned(), true), (" x".to_owned(), false)],
            ]
        );
        assert_eq!(rows, [0, 1]);

        let over = Formatted::highlighted("ab\ncd", &[(1, 4)]);
        assert_eq!(
            over.lines,
            [
                vec![(Style::new(), "a".to_owned()), (MATCH, "b".to_owned())],
                vec![(MATCH, "c".to_owned()), (Style::new(), "d".to_owned())],
            ]
        );
        // Out of range or inside a character: left out.
        let odd = Formatted::highlighted("äx", &[(1, 2), (5, 9)]);
        assert_eq!(odd.lines, [vec![(Style::new(), "äx".to_owned())]]);
    }

    #[test]
    fn opens_on_the_value_with_matches_and_jumps_between_them() {
        let long: String = (1..=30)
            .map(|i| {
                if i == 20 || i == 26 {
                    "a red line"
                } else {
                    "line"
                }
            })
            .collect::<Vec<_>>()
            .join("\n");
        let start = long.find("red").unwrap() as i32;
        let second = long.rfind("red").unwrap() as i32;
        let (mut viewer, fetch) = Viewer::with_matches(
            4,
            "body".to_owned(),
            vec![
                Value::stored(0, &text("{\"json\": true}")),
                Value::stored(1, &text(&long)),
            ],
            Delimiters::default(),
            1,
            &[
                MatchSpan {
                    start,
                    end: start + 3,
                },
                MatchSpan {
                    start: second,
                    end: second + 3,
                },
            ],
            5,
        );
        assert_eq!(fetch, None);
        assert_eq!(viewer.mode(), Mode::Text);
        assert!(viewer.keys().contains("m/M next/previous match"));

        let screen = render(&mut viewer, 70, 8);
        assert!(screen.contains("body · 2 of 2"), "{screen}");
        // Two rows of context above the first match, on row 20.
        assert!(screen.contains("rows 18–23 of 30"), "{screen}");
        assert!(screen.contains("match 1 of 2 (2 of 5 marked)"), "{screen}");

        viewer.handle_key(KeyCode::Char('m'));
        let screen = render(&mut viewer, 70, 8);
        assert!(screen.contains("rows 24–29 of 30"), "{screen}");
        assert!(screen.contains("match 2 of 2"), "{screen}");
        // The last match stays the last.
        viewer.handle_key(KeyCode::Char('m'));
        viewer.handle_key(KeyCode::Char('M'));
        let screen = render(&mut viewer, 70, 8);
        assert!(screen.contains("rows 18–23 of 30"), "{screen}");

        // From another mode, m comes back to the text.
        viewer.handle_key(KeyCode::Char('h'));
        viewer.handle_key(KeyCode::Char('m'));
        assert_eq!(viewer.mode(), Mode::Text);
    }

    #[test]
    fn values_without_matches_have_no_match_keys() {
        let mut viewer = viewer(vec![Value::stored(0, &text("red"))]);

        assert!(!viewer.keys().contains("match"));
        assert_eq!(viewer.handle_key(KeyCode::Char('m')), None);
        assert!(!render(&mut viewer, 40, 6).contains("match"));
    }

    #[test]
    fn shows_text_with_its_lines() {
        let mut viewer = viewer(vec![Value::stored(0, &text("one\r\ntwo\tthree\u{1}"))]);

        let screen = render(&mut viewer, 40, 6);
        assert!(screen.contains("body · 1 of 1 · string · 15 B"), "{screen}");
        assert!(screen.contains("│one "), "{screen}");
        assert!(screen.contains("│two    three\\u{1}"), "{screen}");
        assert!(screen.contains("text · rows 1–2 of 2"), "{screen}");
    }

    /// `text` in the text mode, wrapped at `width`.
    fn wrap_text(text: &str, width: usize) -> Vec<String> {
        let mut lines = Vec::new();
        for line in Formatted::text(text, None).lines {
            wrap(&line, width, &mut lines);
        }
        lines.iter().map(ToString::to_string).collect()
    }

    #[test]
    fn wraps_at_the_width() {
        assert_eq!(wrap_text("abcdef", 4), ["abcd", "ef"]);
        assert_eq!(wrap_text("", 4), [""]);
        assert_eq!(wrap_text("a\n\nb", 4), ["a", "", "b"]);
        // Wide characters take two columns.
        assert_eq!(wrap_text("日本語", 4), ["日本", "語"]);
    }

    #[test]
    fn wraps_across_styles() {
        let line = vec![
            (KEY, "key".to_owned()),
            (DIM, " = ".to_owned()),
            (Style::new(), "value".to_owned()),
        ];
        let mut lines = Vec::new();
        wrap(&line, 5, &mut lines);
        assert_eq!(
            lines,
            [
                Line::from(vec![Span::styled("key", KEY), Span::styled(" =", DIM)]),
                Line::from(vec![Span::styled(" ", DIM), Span::raw("valu")]),
                Line::from(vec![Span::raw("e")]),
            ]
        );
    }

    #[test]
    fn shows_a_hex_dump_and_switches_back() {
        let mut viewer = viewer(vec![Value::stored(0, &text("grüße, Welt! 0123456789"))]);
        assert_eq!(viewer.handle_key(KeyCode::Char('h')), None);

        let screen = render(&mut viewer, 80, 6);
        assert!(
            screen.contains(
                "00000000  67 72 c3 bc c3 9f 65 2c  20 57 65 6c 74 21 20 30  |gr....e, Welt! 0|"
            ),
            "{screen}"
        );
        assert!(
            screen.contains("00000010  31 32 33 34 35 36 37 38  39"),
            "{screen}"
        );
        assert!(screen.contains("|123456789|"), "{screen}");
        // 8 bytes a row when 16 don't fit.
        let screen = render(&mut viewer, 60, 6);
        assert!(
            screen.contains("00000008  20 57 65 6c 74 21 20 30  | Welt! 0|"),
            "{screen}"
        );

        viewer.handle_key(KeyCode::Char('h'));
        assert!(render(&mut viewer, 80, 6).contains("grüße, Welt!"));
    }

    #[test]
    fn opens_bytes_as_hex_and_numbers_with_their_bits() {
        let mut viewer = viewer(vec![
            Value::stored(0, &stored(StoredType::Binary, "ff0041", true)),
            Value::stored(1, &stored(StoredType::Int, "-2", false)),
            Value::stored(2, &stored(StoredType::Double, "NaN", false)),
            Value::doc_value_number("sorted numeric", 4612811918334230528, "2.5".to_owned()),
        ]);
        assert_eq!(viewer.mode(), Mode::Hex);
        assert!(render(&mut viewer, 80, 5).contains("ff 00 41"));

        viewer.handle_key(KeyCode::Right);
        let screen = render(&mut viewer, 80, 5);
        assert!(screen.contains("2 of 4 · int · 4 B"), "{screen}");
        assert!(screen.contains("ff ff ff fe"), "{screen}");
        viewer.handle_key(KeyCode::Right);
        assert!(render(&mut viewer, 80, 5).contains("7f f8 00 00 00 00 00 00"));
        viewer.handle_key(KeyCode::Right);
        let screen = render(&mut viewer, 80, 5);
        assert!(
            screen.contains("sorted numeric doc value · 8 B"),
            "{screen}"
        );
        assert!(screen.contains("40 04 00 00 00 00 00 00"), "{screen}");
        // The last value: → stays.
        assert_eq!(viewer.handle_key(KeyCode::Right), None);
        viewer.handle_key(KeyCode::Char('h'));
        assert!(render(&mut viewer, 80, 5).contains("│2.5"));
        // Bytes that are not UTF-8 as text.
        viewer.handle_key(KeyCode::Home);
        for _ in 0..3 {
            viewer.handle_key(KeyCode::Left);
        }
        assert!(render(&mut viewer, 80, 5).contains("│�\\u{0}A"));
    }

    #[test]
    fn scrolls_within_the_value() {
        let lines: Vec<String> = (1..=50).map(|n| format!("line {n}")).collect();
        let mut viewer = viewer(vec![Value::stored(0, &text(&lines.join("\n")))]);
        // 10 rows inside the border.
        render(&mut viewer, 40, 12);

        viewer.handle_key(KeyCode::PageDown);
        let screen = render(&mut viewer, 40, 12);
        assert!(screen.contains("│line 10 "), "{screen}");
        assert!(screen.contains("rows 10–19 of 50"), "{screen}");
        viewer.handle_key(KeyCode::End);
        let screen = render(&mut viewer, 40, 12);
        assert!(screen.contains("│line 50"), "{screen}");
        assert!(screen.contains("rows 41–50 of 50"), "{screen}");
        viewer.handle_key(KeyCode::Up);
        viewer.handle_key(KeyCode::Char('g'));
        assert!(render(&mut viewer, 40, 12).contains("rows 1–10 of 50"));
    }

    #[test]
    fn fetches_a_cut_value_whole() {
        let mut cut = text("abc");
        cut.full_length = Some(70_000);
        let (mut viewer, fetch) = Viewer::new(
            4,
            "body".to_owned(),
            vec![
                Value::stored(0, &text("small")),
                Value::stored(1, &cut),
                Value::doc_value(
                    "sorted set",
                    &BytesValue {
                        value: "a".to_owned(),
                        binary: false,
                    },
                ),
            ],
            Delimiters::default(),
        );
        assert_eq!(fetch, None);

        assert_eq!(
            viewer.handle_key(KeyCode::Right),
            Some(Event::Fetch {
                doc_id: 4,
                field: "body".to_owned(),
                index: 1,
            })
        );
        let screen = render(&mut viewer, 80, 6);
        assert!(screen.contains("string · 68.4 KiB"), "{screen}");
        assert!(
            screen.contains("Cut at 3 B of 68.4 KiB; fetching it whole…"),
            "{screen}"
        );
        assert_eq!(viewer.handle_key(KeyCode::Char('y')), None);
        assert!(render(&mut viewer, 80, 6).contains("Not copied: the whole value is not in."));
        // Fetched once.
        viewer.handle_key(KeyCode::Left);
        assert_eq!(viewer.handle_key(KeyCode::Right), None);

        // Answers for another document or field are dropped.
        viewer.loaded(5, "body", 1, &text("wrong"));
        viewer.loaded(4, "title", 1, &text("wrong"));
        viewer.loaded(4, "body", 1, &text("abcdefg"));
        let screen = render(&mut viewer, 80, 6);
        assert!(screen.contains("│abcdefg"), "{screen}");
        assert!(screen.contains("string · 7 B"), "{screen}");
        assert!(!screen.contains("Cut"), "{screen}");
        assert_eq!(
            viewer.handle_key(KeyCode::Char('y')),
            Some(Event::Copy("abcdefg".to_owned()))
        );
        assert!(render(&mut viewer, 80, 6).contains("Copied 7 B as text (OSC 52)."));
    }

    #[test]
    fn shows_why_a_cut_value_is_not_whole() {
        let mut cut = text("abc");
        cut.full_length = Some(70_000);
        let (mut viewer, fetch) = Viewer::new(
            1,
            "f".to_owned(),
            vec![Value::stored(0, &cut)],
            Delimiters::default(),
        );
        assert!(matches!(fetch, Some(Event::Fetch { index: 0, .. })));

        viewer.failed(1, "f", 0, "gone".to_owned());
        assert!(render(&mut viewer, 80, 6).contains("Cut; cannot fetch it whole: gone"));
    }

    #[test]
    fn warns_that_a_large_copy_may_be_dropped() {
        let mut viewer = viewer(vec![Value::stored(0, &text(&"x".repeat(LARGE_COPY + 1)))]);
        assert!(matches!(
            viewer.handle_key(KeyCode::Char('y')),
            Some(Event::Copy(_))
        ));
        assert!(
            render(&mut viewer, 120, 6).contains(
                "Copied 100.0 KiB as text (OSC 52); terminals may drop copies this large."
            )
        );
    }

    #[test]
    fn copies_hex_and_closes() {
        let mut viewer = viewer(vec![Value::stored(0, &text("hi"))]);
        viewer.handle_key(KeyCode::Char('h'));
        assert_eq!(
            viewer.handle_key(KeyCode::Char('y')),
            Some(Event::Copy("6869".to_owned()))
        );
        for key in [KeyCode::Esc, KeyCode::Char('q'), KeyCode::Char('v')] {
            assert_eq!(viewer.handle_key(key), Some(Event::Close));
        }
    }

    fn plain(lines: &[Segments]) -> Vec<String> {
        lines
            .iter()
            .map(|line| line.iter().map(|(_, text)| text.as_str()).collect())
            .collect()
    }

    #[test]
    fn pretty_prints_json_as_it_is_written() {
        let lines = json_lines(r#" {"b":1,"a":[1, 2.50, true,null],"e":{ },"s":"x\"y,}","n":[]} "#)
            .unwrap();
        assert_eq!(
            plain(&lines),
            [
                "{",
                r#"  "b": 1,"#,
                r#"  "a": ["#,
                "    1,",
                "    2.50,",
                "    true,",
                "    null",
                "  ],",
                r#"  "e": {},"#,
                r#"  "s": "x\"y,}","#,
                r#"  "n": []"#,
                "}",
            ]
        );
        assert_eq!(lines[1][1], (JSON_KEY, r#""b""#.to_owned()));
        assert_eq!(lines[1][3], (JSON_NUMBER, "1".to_owned()));
        assert_eq!(lines[5][1], (JSON_LITERAL, "true".to_owned()));
        assert_eq!(lines[9][3], (JSON_STRING, r#""x\"y,}""#.to_owned()));
        // Strings in arrays are values, keys of nested objects are keys.
        let lines = json_lines(r#"[{"k":"v"},"w"]"#).unwrap();
        assert_eq!(lines[2][1], (JSON_KEY, r#""k""#.to_owned()));
        assert_eq!(lines[2][3], (JSON_STRING, r#""v""#.to_owned()));
        assert_eq!(lines[4][1], (JSON_STRING, r#""w""#.to_owned()));

        assert_eq!(plain(&json_lines("-1.5e3").unwrap()), ["-1.5e3"]);
        assert_eq!(
            json_lines(r#"{"a":1,}"#),
            Err("key must be a string at line 1 column 8".to_owned())
        );
        assert!(json_lines("{} x").is_err());
    }

    #[test]
    fn opens_json_pretty_printed_and_says_when_a_value_is_not_json() {
        let mut viewer = viewer(vec![
            Value::stored(0, &text(r#"{"id":7,"tags":["a","b"]}"#)),
            Value::stored(1, &text("plain")),
        ]);
        assert_eq!(viewer.mode(), Mode::Json);
        let screen = render(&mut viewer, 60, 10);
        assert!(screen.contains(r#"│  "id": 7,"#), "{screen}");
        assert!(screen.contains(r#"│    "a","#), "{screen}");
        assert!(screen.contains("json · rows 1–7 of 7"), "{screen}");
        assert_eq!(
            viewer.handle_key(KeyCode::Char('y')),
            Some(Event::Copy(
                "{\n  \"id\": 7,\n  \"tags\": [\n    \"a\",\n    \"b\"\n  ]\n}".to_owned()
            ))
        );

        viewer.handle_key(KeyCode::Right);
        let screen = render(&mut viewer, 80, 10);
        assert!(screen.contains("│plain"), "{screen}");
        assert!(
            screen.contains("Not JSON: expected value at line 1 column 1."),
            "{screen}"
        );
        // Copies it as it is shown: as text.
        assert_eq!(
            viewer.handle_key(KeyCode::Char('y')),
            Some(Event::Copy("plain".to_owned()))
        );

        viewer.handle_key(KeyCode::Left);
        viewer.handle_key(KeyCode::Char('J'));
        assert_eq!(viewer.mode(), Mode::Text);
        assert!(render(&mut viewer, 60, 10).contains(r#"│{"id":7,"tags":["a","b"]}"#));
        viewer.handle_key(KeyCode::Char('J'));
        assert_eq!(viewer.mode(), Mode::Json);
        viewer.handle_key(KeyCode::Char('t'));
        assert_eq!(viewer.mode(), Mode::Text);
    }

    #[test]
    fn opens_a_cut_json_value_as_json() {
        let mut cut = text(r#"{"id":7,"bo"#);
        cut.full_length = Some(70_000);
        let (source, _) = Viewer::new(
            1,
            "_source".to_owned(),
            vec![Value::stored(0, &cut)],
            Delimiters::default(),
        );
        assert_eq!(source.mode(), Mode::Json);
        // Text that only starts like JSON stays text.
        let text = viewer(vec![Value::stored(0, &text("[1] and more"))]);
        assert_eq!(text.mode(), Mode::Text);
    }

    fn split(items: &str, key_value: Option<&str>) -> Split {
        Split {
            items: items.to_owned(),
            key_value: key_value.map(str::to_owned),
        }
    }

    #[test]
    fn numbers_delimited_items() {
        let formatted = delimited("a,b\tc,,d", &split(",", None));
        assert_eq!(formatted.detail.as_deref(), Some("4 items"));
        assert_eq!(plain(&formatted.lines), ["1  a", "2  b\\tc", "3  ", "4  d"]);

        let items: Vec<String> = (1..=10).map(|n| n.to_string()).collect();
        let formatted = delimited(&items.join("\n"), &split("\n", None));
        assert_eq!(plain(&formatted.lines)[0], " 1  1");
        assert_eq!(plain(&formatted.lines)[9], "10  10");
        assert_eq!(
            delimited("x", &split(",", None)).detail.as_deref(),
            Some("1 item")
        );
    }

    #[test]
    fn aligns_keys_of_delimited_items() {
        let long = "k".repeat(MAX_KEY_WIDTH + 1);
        let text = format!("id=7;name=a=b;junk;{long}=x;tab\t=y");
        let formatted = delimited(&text, &split(";", Some("=")));
        assert_eq!(
            plain(&formatted.lines),
            [
                "id    = 7",
                "name  = a=b",
                "junk",
                &format!("{long} = x"),
                "tab\\t = y",
            ]
        );
        assert_eq!(formatted.lines[0][0], (KEY, "id".to_owned()));
        assert_eq!(formatted.lines[0][2], (DIM, " = ".to_owned()));
    }

    #[test]
    fn resolves_escapes_in_delimiters() {
        assert_eq!(unescape(r"a\t\n\r\0\\b"), Ok("a\t\n\r\0\\b".to_owned()));
        assert_eq!(
            unescape(r"\x1f|\u{1E}\u{e9}x"),
            Ok("\u{1f}|\u{1e}éx".to_owned())
        );
        assert!(unescape(r"\x8").is_err());
        assert!(unescape(r"\x80").is_err());
        assert!(unescape(r"\u{110000}").is_err());
        assert!(unescape(r"\u1f").is_err());
        assert!(unescape(r"\q").is_err());
        assert!(unescape("\\").is_err());

        let delimiters = Delimiters {
            items: r"\x01".to_owned(),
            key_value: String::new(),
        };
        assert_eq!(delimiters.split(), Ok(split("\u{1}", None)));
        let delimiters = Delimiters {
            items: String::new(),
            key_value: "=".to_owned(),
        };
        assert_eq!(
            delimiters.split(),
            Err("Items: enter a delimiter.".to_owned())
        );
    }

    #[test]
    fn asks_for_the_delimiters_and_splits_at_them() {
        let mut viewer = viewer(vec![Value::stored(0, &text("a=1|b=22"))]);
        assert_eq!(viewer.handle_key(KeyCode::Char('d')), None);
        let screen = render(&mut viewer, 80, 12);
        assert!(screen.contains("Delimited"), "{screen}");
        assert!(screen.contains("Items      ,▏"), "{screen}");
        assert!(
            viewer
                .keys()
                .contains("Tab/↑↓ other delimiter   ⏎ apply   Esc cancel")
        );

        // The form takes the keys, q included.
        viewer.handle_key(KeyCode::Backspace);
        assert_eq!(viewer.handle_key(KeyCode::Enter), None);
        assert!(render(&mut viewer, 80, 12).contains("Items: enter a delimiter."));
        viewer.handle_key(KeyCode::Char('|'));
        viewer.handle_key(KeyCode::Tab);
        viewer.handle_key(KeyCode::Char('='));
        assert!(render(&mut viewer, 80, 12).contains("Key/value  =▏"));
        assert_eq!(viewer.mode(), Mode::Text);
        viewer.handle_key(KeyCode::Enter);
        assert_eq!(viewer.mode(), Mode::Delimited);
        let screen = render(&mut viewer, 80, 12);
        assert!(screen.contains("│a = 1"), "{screen}");
        assert!(screen.contains("│b = 22"), "{screen}");
        assert!(
            screen.contains("delimited · 2 items · rows 1–2 of 2"),
            "{screen}"
        );
        assert_eq!(
            viewer.delimiters(),
            &Delimiters {
                items: "|".to_owned(),
                key_value: "=".to_owned(),
            }
        );
        assert_eq!(
            viewer.handle_key(KeyCode::Char('y')),
            Some(Event::Copy("a = 1\nb = 22".to_owned()))
        );

        // Esc leaves the form, not the viewer, and keeps the delimiters.
        viewer.handle_key(KeyCode::Char('d'));
        viewer.handle_key(KeyCode::Char('x'));
        assert_eq!(viewer.handle_key(KeyCode::Esc), None);
        assert_eq!(viewer.delimiters().items, "|");
        assert_eq!(viewer.mode(), Mode::Delimited);
        assert_eq!(viewer.handle_key(KeyCode::Esc), Some(Event::Close));
    }

    #[test]
    fn formats_sizes() {
        assert_eq!(size(0), "0 B");
        assert_eq!(size(1023), "1023 B");
        assert_eq!(size(1536), "1.5 KiB");
        assert_eq!(size(5 * 1024 * 1024), "5.0 MiB");
        assert_eq!(size(2 * 1024 * 1024 * 1024), "2.0 GiB");
        assert_eq!(cut_size(), "64.0 KiB");
    }

    #[test]
    fn doc_value_types_name_the_value() {
        let value = Value::doc_value(
            crate::overview::doc_values(DocValuesType::Binary),
            &BytesValue {
                value: "ff".to_owned(),
                binary: true,
            },
        );
        assert_eq!(value.kind, "binary doc value");
        assert_eq!(value.content, Content::Bytes(vec![0xff]));
    }
}
