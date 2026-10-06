//! The value viewer: the values of one field of a document, one at a time, over the whole
//! documents screen, as text or as a hex dump. A stored value that `getDocument` cut (ADR 0008)
//! is fetched whole when it is shown.

use std::borrow::Cow;

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::Rect;
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, Paragraph};
use unicode_width::UnicodeWidthChar;

use crate::protocol::document::{BytesValue, MAX_VALUE_LENGTH, StoredType, StoredValue};

/// Spaces a tab is shown as.
const TAB: &str = "    ";
/// From this many bytes on, copying warns that the terminal may drop the copy.
const LARGE_COPY: usize = 100 * 1024;
/// From this inner width on, a hex row has 16 bytes instead of 8.
const WIDE_HEX_WIDTH: u16 = 78;

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
    Close,
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
        }
    }

    /// The size of the whole value in bytes.
    fn size(&self) -> i64 {
        self.full_length
            .unwrap_or(self.content.bytes().len() as i64)
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

/// The text of the shown value, wrapped at a width.
#[derive(Debug)]
struct Wrapped {
    width: usize,
    lines: Vec<String>,
}

pub struct Viewer {
    doc_id: i32,
    field: String,
    values: Vec<Value>,
    current: usize,
    hex: bool,
    /// The first row shown.
    scroll: usize,
    /// The rows that fit, as of the last render; PageUp and PageDown move by that.
    page: usize,
    /// `None` after the shown value or the mode changed.
    wrapped: Option<Wrapped>,
    /// What the last key did, such as copying; cleared by the next key.
    message: Option<String>,
}

impl Viewer {
    /// Views `values`, which must not be empty, from the first one on. Returns the viewer and
    /// the fetch of the first value, if it is cut.
    pub fn new(doc_id: i32, field: String, values: Vec<Value>) -> (Self, Option<Event>) {
        // Bytes that are not UTF-8 are easier to read as hex.
        let hex = matches!(
            values.first().map(|value| &value.content),
            Some(Content::Bytes(_))
        );
        let mut viewer = Self {
            doc_id,
            field,
            values,
            current: 0,
            hex,
            scroll: 0,
            page: 10,
            wrapped: None,
            message: None,
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

    pub fn hex(&self) -> bool {
        self.hex
    }

    pub fn handle_key(&mut self, key: KeyCode) -> Option<Event> {
        self.message = None;
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
            KeyCode::Char('h') => {
                self.hex = !self.hex;
                self.scroll = 0;
            }
            KeyCode::Char('y') => return self.copy(),
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

    fn show(&mut self, index: usize) {
        self.current = index;
        self.scroll = 0;
        self.wrapped = None;
    }

    /// Copies the shown value as it is shown: the text, or the bytes in hex.
    fn copy(&mut self) -> Option<Event> {
        let value = &self.values[self.current];
        if value.full_length.is_some() {
            self.message = Some("Not copied: the whole value is not in.".to_owned());
            return None;
        }
        let text = if self.hex {
            hex(value.content.bytes())
        } else {
            value.content.text().into_owned()
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
            if self.hex { "hex" } else { "text" }
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
        let rows = if self.hex {
            value.content.bytes().len().div_ceil(bytes_per_row).max(1)
        } else {
            self.wrapped_lines(usize::from(inner.width)).len()
        };
        self.scroll = self.scroll.min(rows.saturating_sub(height));
        let shown = self.scroll..rows.min(self.scroll + height);

        let value = &self.values[self.current];
        let lines: Vec<Line> = if self.hex {
            shown
                .clone()
                .map(|row| hex_row(value.content.bytes(), row, bytes_per_row))
                .collect()
        } else {
            let wrapped = self.wrapped.as_ref().map_or(&[][..], |w| &w.lines[..]);
            wrapped[shown.clone()]
                .iter()
                .map(|line| Line::from(line.as_str()))
                .collect()
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
        } else {
            String::new()
        };
        let position = format!(
            " {} · rows {}–{} of {rows} ",
            if self.hex { "hex" } else { "text" },
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
    }

    /// The shown value's text wrapped at `width`, wrapped again only when needed.
    fn wrapped_lines(&mut self, width: usize) -> &[String] {
        if self.wrapped.as_ref().is_none_or(|w| w.width != width) {
            let lines = wrap(&self.values[self.current].content.text(), width);
            self.wrapped = Some(Wrapped { width, lines });
        }
        self.wrapped.as_ref().map_or(&[], |w| &w.lines)
    }
}

/// `text` as lines no wider than `width`: line breaks kept, tabs as spaces, other control
/// characters escaped.
fn wrap(text: &str, width: usize) -> Vec<String> {
    let width = width.max(1);
    let mut lines = Vec::new();
    for line in text.split('\n') {
        let line = line.strip_suffix('\r').unwrap_or(line);
        let mut current = String::new();
        let mut used = 0;
        let mut push = |c: char, current: &mut String, used: &mut usize| {
            let w = c.width().unwrap_or(0);
            if *used + w > width && *used > 0 {
                lines.push(std::mem::take(current));
                *used = 0;
            }
            current.push(c);
            *used += w;
        };
        for c in line.chars() {
            if c == '\t' {
                TAB.chars().for_each(|c| push(c, &mut current, &mut used));
            } else if c.is_control() {
                c.escape_default()
                    .for_each(|c| push(c, &mut current, &mut used));
            } else {
                push(c, &mut current, &mut used);
            }
        }
        lines.push(current);
    }
    lines
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
    let dim = Style::new().add_modifier(Modifier::DIM);
    Line::from(vec![
        Span::styled(format!("{start:08x}  "), dim),
        Span::raw(hex),
        Span::styled(" |", dim),
        Span::raw(ascii),
        Span::styled("|", dim),
    ])
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// `512 B`, `1.5 KiB`, `5.0 MiB`.
pub fn size(bytes: i64) -> String {
    const KIB: f64 = 1024.0;
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KiB", bytes as f64 / KIB)
    } else {
        format!("{:.1} MiB", bytes as f64 / KIB / KIB)
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
        let (viewer, fetch) = Viewer::new(4, "body".to_owned(), values);
        assert_eq!(fetch, None);
        viewer
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

    #[test]
    fn wraps_at_the_width() {
        assert_eq!(wrap("abcdef", 4), ["abcd", "ef"]);
        assert_eq!(wrap("", 4), [""]);
        assert_eq!(wrap("a\n\nb", 4), ["a", "", "b"]);
        // Wide characters take two columns.
        assert_eq!(wrap("日本語", 4), ["日本", "語"]);
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
        assert!(viewer.hex());
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
        let (mut viewer, fetch) = Viewer::new(1, "f".to_owned(), vec![Value::stored(0, &cut)]);
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

    #[test]
    fn formats_sizes() {
        assert_eq!(size(0), "0 B");
        assert_eq!(size(1023), "1023 B");
        assert_eq!(size(1536), "1.5 KiB");
        assert_eq!(size(5 * 1024 * 1024), "5.0 MiB");
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
