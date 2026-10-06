//! The documents screen: one document of the open index at a time, field by field, as
//! `getDocument` reports it (ADR 0006), with a panel for the selected field's values and its
//! term vector. A document opened from the terms screen also shows where the term occurs in it,
//! and `[`/`]` step through the term's other documents. `v` opens the selected field's values in
//! the [`Viewer`].

use std::collections::HashMap;

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Cell, Clear, Paragraph, Row, Table, TableState, Wrap};

use crate::overview::{doc_values, highlight, panel, postings};
use crate::protocol::document::{
    BytesValue, DocValues, DocumentField, GetDocumentResult, StoredValue, TermPosition,
    TermVectorTerm,
};
use crate::protocol::index::{IndexField, IndexOptions};
use crate::terms::TermHit;
use crate::text::{bytes_text, count, escape_control, first_chars};
use crate::viewer::{self, Delimiters, Viewer, cut_size, size, stored_type};

/// Rows or lines that PageUp and PageDown move.
const PAGE: u16 = 10;
/// From this width on, the details panel is next to the fields instead of below them.
const SIDE_BY_SIDE_WIDTH: u16 = 130;
/// The width of the details panel next to the fields.
const DETAILS_PANEL_WIDTH: u16 = 64;
/// The value column shows at most this many characters of a value.
const SUMMARY_CHARS: usize = 200;
/// The details show at most this many characters of a stored value, and this many lines.
const DETAIL_VALUE_CHARS: usize = 2000;
const DETAIL_VALUE_LINES: usize = 20;
/// The name column is at most this wide.
const NAME_MAX_WIDTH: u16 = 32;
const COLUMN_SPACING: u16 = 2;

/// What the caller has to fetch for the documents screen.
#[derive(Debug, PartialEq, Eq)]
pub enum Request {
    /// The document with this id, reported back with [`Documents::loaded`] or
    /// [`Documents::failed`].
    Document(i32),
    /// The term vector of a field of the shown document, reported back with
    /// [`Documents::term_vector_loaded`] or [`Documents::term_vector_failed`].
    TermVector { doc_id: i32, field: String },
    /// Where the term of the [`TermHit`] occurs in its document, reported back with
    /// [`Documents::positions_loaded`] or [`Documents::positions_failed`].
    Positions {
        doc_id: i32,
        field: String,
        term: BytesValue,
    },
    /// The previous or next document of the term of the [`TermHit`].
    StepTerm { forward: bool },
    /// A stored value whole, which `getDocument` cut, reported back with
    /// [`Documents::stored_value_loaded`] or [`Documents::stored_value_failed`].
    StoredValue {
        doc_id: i32,
        field: String,
        index: i32,
    },
    /// Put this text on the clipboard.
    Copy(String),
}

/// How the raw numbers of numeric doc values are shown. The index doesn't record what they
/// encode, so `x` cycles through the encodings Lucene's fields use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NumberFormat {
    Long,
    /// `DoubleDocValuesField`: the raw bits of a double.
    Double,
    /// `NumericUtils.doubleToSortableLong`, as for sorted numeric doubles.
    SortableDouble,
    /// `FloatDocValuesField`: the raw bits of a float.
    Float,
    /// `NumericUtils.floatToSortableInt`.
    SortableFloat,
}

impl NumberFormat {
    fn next(self) -> Self {
        match self {
            NumberFormat::Long => NumberFormat::Double,
            NumberFormat::Double => NumberFormat::SortableDouble,
            NumberFormat::SortableDouble => NumberFormat::Float,
            NumberFormat::Float => NumberFormat::SortableFloat,
            NumberFormat::SortableFloat => NumberFormat::Long,
        }
    }

    fn label(self) -> &'static str {
        match self {
            NumberFormat::Long => "long",
            NumberFormat::Double => "double bits",
            NumberFormat::SortableDouble => "sortable double",
            NumberFormat::Float => "float bits",
            NumberFormat::SortableFloat => "sortable float",
        }
    }

    /// `number` decoded as this format. Floats use Rust's shortest exact form.
    pub fn format(self, number: i64) -> String {
        match self {
            NumberFormat::Long => number.to_string(),
            NumberFormat::Double => format!("{:?}", f64::from_bits(number as u64)),
            NumberFormat::SortableDouble => {
                let bits = number ^ ((number >> 63) & i64::MAX);
                format!("{:?}", f64::from_bits(bits as u64))
            }
            NumberFormat::Float => format!("{:?}", f32::from_bits(number as u32)),
            NumberFormat::SortableFloat => {
                let int = number as i32;
                let bits = int ^ ((int >> 31) & i32::MAX);
                format!("{:?}", f32::from_bits(bits as u32))
            }
        }
    }
}

/// The term vector of a field of the shown document.
#[derive(Debug)]
enum TermVector {
    Loaded(Vec<TermVectorTerm>),
    Failed(String),
}

/// What the navigation keys move.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Fields,
    Details,
}

/// The go-to-document prompt.
#[derive(Debug, Default)]
struct DocIdPrompt {
    input: String,
    error: Option<String>,
}

pub struct Documents {
    /// How each field of the index is indexed, by name, from `getIndexStats`.
    schema: HashMap<String, IndexField>,
    max_doc: i32,
    /// The document shown, or the one that failed to load.
    doc_id: i32,
    document: Option<GetDocumentResult>,
    /// Why `doc_id` could not be read.
    error: Option<String>,
    /// The rows of the field table, as indexes into `document.fields`.
    rows: Vec<usize>,
    table: TableState,
    /// Whether fields in which the document has nothing are left out.
    hide_empty: bool,
    numbers: NumberFormat,
    /// Term vectors of the shown document, by field name.
    term_vectors: HashMap<String, TermVector>,
    focus: Focus,
    /// The first line of the details panel that is shown.
    details_scroll: u16,
    prompt: Option<DocIdPrompt>,
    /// The term and document that the terms screen opened, if any.
    hit: Option<TermHit>,
    /// Where the hit's term occurs in its document, once fetched.
    positions: Option<Result<Vec<TermPosition>, String>>,
    /// The field to select once the next document is in.
    select_field: Option<String>,
    /// The values of the selected field, viewed over the whole screen.
    viewer: Option<Viewer>,
    /// The delimiters the viewer last split values at, for the next viewer.
    delimiters: Delimiters,
}

impl Documents {
    pub fn new(fields: Vec<IndexField>, max_doc: i32) -> Self {
        Self {
            schema: fields
                .into_iter()
                .map(|field| (field.name.clone(), field))
                .collect(),
            max_doc,
            doc_id: 0,
            document: None,
            error: None,
            rows: Vec::new(),
            table: TableState::new(),
            hide_empty: false,
            numbers: NumberFormat::Long,
            term_vectors: HashMap::new(),
            focus: Focus::Fields,
            details_scroll: 0,
            prompt: None,
            hit: None,
            positions: None,
            select_field: None,
            viewer: None,
            delimiters: Delimiters::default(),
        }
    }

    /// The first document to fetch when the screen is shown, if nothing was fetched yet.
    pub fn initial_request(&self) -> Option<Request> {
        (self.document.is_none() && self.error.is_none() && self.max_doc > 0)
            .then_some(Request::Document(self.doc_id))
    }

    /// Shows the document of `hit` once it is loaded, with the hit's field selected and where
    /// its term occurs in it.
    pub fn show_hit(&mut self, hit: TermHit) {
        self.select_field = Some(hit.field.clone());
        self.hit = Some(hit);
        self.positions = None;
    }

    /// Whether a document was opened from the terms screen, so `[`/`]` step through its term's
    /// documents.
    pub fn has_hit(&self) -> bool {
        self.hit.is_some()
    }

    /// The positions to fetch, if the shown document is the hit's and they are not in yet.
    pub fn positions_request(&self) -> Option<Request> {
        let hit = self.hit.as_ref()?;
        (self.shows(hit.doc_id) && self.positions.is_none()).then(|| Request::Positions {
            doc_id: hit.doc_id,
            field: hit.field.clone(),
            term: hit.term.clone(),
        })
    }

    pub fn positions_loaded(
        &mut self,
        doc_id: i32,
        field: &str,
        term: &BytesValue,
        positions: Vec<TermPosition>,
    ) {
        if self.is_hit(doc_id, field, term) {
            self.positions = Some(Ok(positions));
        }
    }

    pub fn positions_failed(&mut self, doc_id: i32, field: &str, term: &BytesValue, error: String) {
        if self.is_hit(doc_id, field, term) {
            self.positions = Some(Err(error));
        }
    }

    fn is_hit(&self, doc_id: i32, field: &str, term: &BytesValue) -> bool {
        self.hit
            .as_ref()
            .is_some_and(|hit| hit.doc_id == doc_id && hit.field == field && hit.term == *term)
    }

    /// The hit, if the shown document is its document.
    fn shown_hit(&self) -> Option<&TermHit> {
        self.hit.as_ref().filter(|hit| self.shows(hit.doc_id))
    }

    /// Shows `document`. The selection stays on its field if the new document shows it.
    pub fn loaded(&mut self, document: GetDocumentResult) {
        let selected = self
            .select_field
            .take()
            .or_else(|| self.selected_field().map(|field| field.name.clone()));
        self.doc_id = document.doc_id;
        self.document = Some(document);
        self.error = None;
        self.close_viewer();
        self.term_vectors.clear();
        self.details_scroll = 0;
        self.update_rows(selected);
    }

    /// Shows why `doc_id` could not be read.
    pub fn failed(&mut self, doc_id: i32, error: String) {
        self.doc_id = doc_id;
        self.document = None;
        self.error = Some(error);
        self.close_viewer();
        self.term_vectors.clear();
        self.rows.clear();
        self.details_scroll = 0;
    }

    pub fn term_vector_loaded(&mut self, doc_id: i32, field: &str, terms: Vec<TermVectorTerm>) {
        if self.shows(doc_id) {
            self.term_vectors
                .insert(field.to_owned(), TermVector::Loaded(terms));
        }
    }

    pub fn term_vector_failed(&mut self, doc_id: i32, field: &str, error: String) {
        if self.shows(doc_id) {
            self.term_vectors
                .insert(field.to_owned(), TermVector::Failed(error));
        }
    }

    pub fn stored_value_loaded(
        &mut self,
        doc_id: i32,
        field: &str,
        index: i32,
        value: &StoredValue,
    ) {
        if let Some(viewer) = &mut self.viewer {
            viewer.loaded(doc_id, field, index, value);
        }
    }

    pub fn stored_value_failed(&mut self, doc_id: i32, field: &str, index: i32, error: String) {
        if let Some(viewer) = &mut self.viewer {
            viewer.failed(doc_id, field, index, error);
        }
    }

    fn shows(&self, doc_id: i32) -> bool {
        self.document
            .as_ref()
            .is_some_and(|doc| doc.doc_id == doc_id)
    }

    /// Fills `rows` from the document, and selects `selected` if it is among them.
    fn update_rows(&mut self, selected: Option<String>) {
        let Some(document) = &self.document else {
            self.rows.clear();
            return;
        };
        // The hit's field has the term, even if nothing else is in it.
        let hit_field = self.shown_hit().map(|hit| hit.field.as_str());
        self.rows = document
            .fields
            .iter()
            .enumerate()
            .filter(|(_, field)| {
                !self.hide_empty || !is_empty(field) || Some(field.name.as_str()) == hit_field
            })
            .map(|(i, _)| i)
            .collect();
        let row = selected
            .and_then(|name| {
                self.rows
                    .iter()
                    .position(|&i| document.fields[i].name == name)
            })
            .unwrap_or(0);
        self.table.select((!self.rows.is_empty()).then_some(row));
    }

    /// The field under the selection.
    fn selected_field(&self) -> Option<&DocumentField> {
        let document = self.document.as_ref()?;
        let last = self.rows.len().checked_sub(1)?;
        // The table clamps the selection only when it renders.
        let row = self.table.selected()?.min(last);
        Some(&document.fields[self.rows[row]])
    }

    /// Whether the go-to-document prompt or the viewer takes the keys, Esc included.
    pub fn prompting(&self) -> bool {
        self.prompt.is_some() || self.viewer.is_some()
    }

    /// The viewer, if it is open.
    pub fn viewer(&self) -> Option<&Viewer> {
        self.viewer.as_ref()
    }

    /// Closes the viewer, keeping its delimiters for the next one.
    fn close_viewer(&mut self) {
        if let Some(viewer) = self.viewer.take() {
            self.delimiters = viewer.delimiters().clone();
        }
    }

    /// Opens the viewer on the selected field's stored values and doc values, if it has any.
    fn view(&mut self) -> Option<Request> {
        let field = self.selected_field()?;
        let mut values: Vec<viewer::Value> = field
            .stored
            .iter()
            .enumerate()
            .map(|(i, value)| viewer::Value::stored(i, value))
            .collect();
        if let Some(dv) = &field.doc_values {
            let kind = doc_values(dv.kind);
            values.extend(dv.numbers.iter().map(|&number| {
                viewer::Value::doc_value_number(kind, number, self.numbers.format(number))
            }));
            values.extend(
                dv.values
                    .iter()
                    .map(|value| viewer::Value::doc_value(kind, value)),
            );
        }
        if values.is_empty() {
            return None;
        }
        let (viewer, fetch) = Viewer::new(
            self.doc_id,
            field.name.clone(),
            values,
            self.delimiters.clone(),
        );
        self.viewer = Some(viewer);
        fetch.and_then(viewer_request)
    }

    /// Goes to another document with ←/→, the `i` prompt, or `[`/`]` through the documents of
    /// the hit's term; loads the selected field's term vector with Enter, views its values with
    /// `v`, cycles the number format with `x`, hides empty fields with `e`, switches the focus
    /// with Tab, and moves the selection or scrolls the details.
    pub fn handle_key(&mut self, key: KeyCode) -> Option<Request> {
        if let Some(viewer) = &mut self.viewer {
            let event = viewer.handle_key(key)?;
            if event == viewer::Event::Close {
                self.close_viewer();
            }
            return viewer_request(event);
        }
        if let Some(prompt) = &mut self.prompt {
            if key == KeyCode::Esc {
                self.prompt = None;
                return None;
            }
            return Self::prompt_key(prompt, key, self.max_doc).map(|doc_id| {
                self.prompt = None;
                Request::Document(doc_id)
            });
        }
        match key {
            KeyCode::Left | KeyCode::Char('p') if self.doc_id > 0 => {
                return Some(Request::Document(self.doc_id - 1));
            }
            KeyCode::Right | KeyCode::Char('n') if self.doc_id < self.max_doc - 1 => {
                return Some(Request::Document(self.doc_id + 1));
            }
            KeyCode::Char('i') if self.max_doc > 0 => self.prompt = Some(DocIdPrompt::default()),
            KeyCode::Char('[') if self.hit.is_some() => {
                return Some(Request::StepTerm { forward: false });
            }
            KeyCode::Char(']') if self.hit.is_some() => {
                return Some(Request::StepTerm { forward: true });
            }
            KeyCode::Enter => {
                let field = self.selected_field()?;
                let wanted = field.has_term_vector && !self.term_vectors.contains_key(&field.name);
                return wanted.then(|| Request::TermVector {
                    doc_id: self.doc_id,
                    field: field.name.clone(),
                });
            }
            KeyCode::Char('v') => return self.view(),
            KeyCode::Char('x') => self.numbers = self.numbers.next(),
            KeyCode::Char('e') => {
                self.hide_empty = !self.hide_empty;
                let selected = self.selected_field().map(|field| field.name.clone());
                self.update_rows(selected);
            }
            KeyCode::Tab => {
                self.focus = match self.focus {
                    Focus::Fields => Focus::Details,
                    Focus::Details => Focus::Fields,
                };
            }
            _ if self.focus == Focus::Details => self.scroll_details(key),
            _ => {
                let before = self.table.selected();
                match key {
                    KeyCode::Down | KeyCode::Char('j') => self.table.scroll_down_by(1),
                    KeyCode::Up | KeyCode::Char('k') => self.table.scroll_up_by(1),
                    KeyCode::PageDown => self.table.scroll_down_by(PAGE),
                    KeyCode::PageUp => self.table.scroll_up_by(PAGE),
                    KeyCode::Home | KeyCode::Char('g') => self.table.select_first(),
                    KeyCode::End | KeyCode::Char('G') => self.table.select_last(),
                    _ => {}
                }
                if self.table.selected() != before {
                    self.details_scroll = 0;
                }
            }
        }
        None
    }

    fn scroll_details(&mut self, key: KeyCode) {
        let scroll = &mut self.details_scroll;
        match key {
            KeyCode::Down | KeyCode::Char('j') => *scroll = scroll.saturating_add(1),
            KeyCode::Up | KeyCode::Char('k') => *scroll = scroll.saturating_sub(1),
            KeyCode::PageDown => *scroll = scroll.saturating_add(PAGE),
            KeyCode::PageUp => *scroll = scroll.saturating_sub(PAGE),
            KeyCode::Home | KeyCode::Char('g') => *scroll = 0,
            KeyCode::End | KeyCode::Char('G') => *scroll = u16::MAX,
            _ => {}
        }
    }

    /// Edits the prompt. Returns the document id once a valid one is submitted.
    fn prompt_key(prompt: &mut DocIdPrompt, key: KeyCode, max_doc: i32) -> Option<i32> {
        match key {
            KeyCode::Char(c) if c.is_ascii_digit() && prompt.input.len() < 10 => {
                prompt.input.push(c);
                prompt.error = None;
            }
            KeyCode::Backspace => {
                prompt.input.pop();
                prompt.error = None;
            }
            KeyCode::Enter => match prompt.input.parse::<i32>() {
                Ok(doc_id) if doc_id < max_doc => return Some(doc_id),
                _ => prompt.error = Some(format!("Enter a number from 0 to {}.", max_doc - 1)),
            },
            _ => {}
        }
        None
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let [top, bottom] =
            Layout::vertical([Constraint::Length(3), Constraint::Fill(1)]).areas(area);
        frame.render_widget(
            Paragraph::new(self.header()).block(Block::bordered().title(" Document ")),
            top,
        );
        let [fields_area, details_area] = if bottom.width >= SIDE_BY_SIDE_WIDTH {
            Layout::horizontal([Constraint::Fill(1), Constraint::Length(DETAILS_PANEL_WIDTH)])
                .areas(bottom)
        } else {
            Layout::vertical([Constraint::Fill(1), Constraint::Percentage(50)]).areas(bottom)
        };
        self.render_fields(frame, fields_area);
        self.render_details(frame, details_area);
        if let Some(prompt) = &self.prompt {
            render_prompt(frame, area, prompt, self.max_doc);
        }
        if let Some(viewer) = &mut self.viewer {
            viewer.render(frame, area);
        }
    }

    fn header(&self) -> Line<'static> {
        if self.max_doc == 0 {
            return Line::from("The index has no documents.");
        }
        let mut spans = vec![Span::styled(
            format!("#{}", self.doc_id),
            Style::new().add_modifier(Modifier::BOLD),
        )];
        spans.push(Span::raw(format!(" of 0–{}", self.max_doc - 1)));
        if let Some(error) = &self.error {
            spans.push(Span::raw(format!("   Cannot read it: {error}")));
        } else if self.document.as_ref().is_some_and(|doc| doc.deleted) {
            spans.push(Span::raw("   "));
            spans.push(Span::styled(
                "deleted",
                Style::new().fg(Color::Red).add_modifier(Modifier::BOLD),
            ));
        }
        if let Some(hit) = &self.hit {
            // Dimmed while another document is shown.
            let style = if self.shows(hit.doc_id) {
                Style::new()
            } else {
                Style::new().add_modifier(Modifier::DIM)
            };
            spans.push(Span::styled(
                format!(
                    "   {}:{}  {} of {}  [ ] steps",
                    first_chars(&escape_control(&hit.field), 30),
                    first_chars(&bytes_text(&hit.term.value, hit.term.binary), 40),
                    hit.index + 1,
                    hit.doc_freq
                ),
                style,
            ));
        }
        Line::from(spans)
    }

    fn render_fields(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Fields;
        let Some(document) = &self.document else {
            frame.render_widget(panel(" Fields ".to_owned(), focused), area);
            return;
        };
        let rows: Vec<(bool, [String; 4])> = self
            .rows
            .iter()
            .map(|&i| {
                let field = &document.fields[i];
                (
                    is_empty(field),
                    [
                        field.name.clone(),
                        flags(self.schema.get(&field.name), field),
                        field.norm.map_or("-".to_owned(), |norm| norm.to_string()),
                        value_summary(field, self.numbers),
                    ],
                )
            })
            .collect();
        let titles = ["Field", "Flags", "Norm", "Value"];
        let width = |column: usize| {
            rows.iter()
                .map(|(_, cells)| cells[column].chars().count())
                .chain([titles[column].len()])
                .max()
                .unwrap_or_default() as u16
        };
        let widths = [
            Constraint::Length(width(0).min(NAME_MAX_WIDTH)),
            Constraint::Length(width(1)),
            Constraint::Length(width(2)),
            Constraint::Fill(1),
        ];
        let header = Row::new(titles).style(Style::new().add_modifier(Modifier::BOLD));
        let rows = rows.into_iter().map(|(empty, [name, flags, norm, value])| {
            let row = Row::new([
                Cell::from(name),
                Cell::from(flags),
                Cell::from(Line::from(norm).right_aligned()),
                Cell::from(value),
            ]);
            if empty {
                row.style(Style::new().add_modifier(Modifier::DIM))
            } else {
                row
            }
        });
        let shown = self.rows.len();
        let total = document.fields.len();
        let title = if shown == total {
            format!(" Fields ({total}) ")
        } else {
            format!(" Fields ({shown} of {total}, e shows all) ")
        };
        let table = Table::new(rows, widths)
            .header(header)
            .column_spacing(COLUMN_SPACING)
            .row_highlight_style(highlight(focused))
            .block(panel(title, focused));
        frame.render_stateful_widget(table, area, &mut self.table);
    }

    fn render_details(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Details;
        let Some(field) = self.selected_field() else {
            frame.render_widget(panel(" Details ".to_owned(), focused), area);
            return;
        };
        let title = format!(" {} ", first_chars(&escape_control(&field.name), 40));
        let hit = self
            .shown_hit()
            .filter(|hit| hit.field == field.name)
            .map(|hit| (hit, self.positions.as_ref()));
        let lines = detail_lines(
            self.schema.get(&field.name),
            field,
            hit,
            self.term_vectors.get(&field.name),
            self.numbers,
        );
        // Keeps the last line reachable, however far End scrolled.
        let last = u16::try_from(lines.len().saturating_sub(1)).unwrap_or(u16::MAX);
        self.details_scroll = self.details_scroll.min(last);
        frame.render_widget(
            Paragraph::new(lines)
                .wrap(Wrap { trim: false })
                .scroll((self.details_scroll, 0))
                .block(panel(title, focused)),
            area,
        );
    }
}

fn viewer_request(event: viewer::Event) -> Option<Request> {
    match event {
        viewer::Event::Fetch {
            doc_id,
            field,
            index,
        } => Some(Request::StoredValue {
            doc_id,
            field,
            index,
        }),
        viewer::Event::Copy(text) => Some(Request::Copy(text)),
        viewer::Event::Close => None,
    }
}

/// Whether the document has nothing that `getDocument` reports in the field. It may still have
/// postings there.
fn is_empty(field: &DocumentField) -> bool {
    field.norm.is_none()
        && field.stored.is_empty()
        && field.doc_values.is_none()
        && !field.has_term_vector
}

/// `I` indexed, `N` norm, `P` payloads, `S` stored, `V` term vector, `D` doc values, `T` points,
/// `K` KNN vectors; `-` where absent. `N`, `S`, `V` and `D` are about this document, the others
/// about the field.
fn flags(schema: Option<&IndexField>, field: &DocumentField) -> String {
    let indexed = schema.is_some_and(|f| f.index_options != IndexOptions::None);
    [
        (indexed, 'I'),
        (field.norm.is_some(), 'N'),
        (schema.is_some_and(|f| f.has_payloads), 'P'),
        (!field.stored.is_empty(), 'S'),
        (field.has_term_vector, 'V'),
        (field.doc_values.is_some(), 'D'),
        (schema.is_some_and(|f| f.point_dimension_count > 0), 'T'),
        (schema.is_some_and(|f| f.vector_dimension > 0), 'K'),
    ]
    .into_iter()
    .map(|(set, flag)| if set { flag } else { '-' })
    .collect()
}

/// One line for the value column: the first stored value, or else the doc values.
fn value_summary(field: &DocumentField, numbers: NumberFormat) -> String {
    if let Some(first) = field.stored.first() {
        let text = escape_control(&first_chars(&stored_text(first), SUMMARY_CHARS));
        return match field.stored.len() {
            1 => text,
            n => format!("{text} (+{} more)", n - 1),
        };
    }
    match &field.doc_values {
        Some(values) => {
            let text = doc_value_texts(values, numbers).join(", ");
            format!("dv: {}", escape_control(&first_chars(&text, SUMMARY_CHARS)))
        }
        None => String::new(),
    }
}

/// A stored value as text, unescaped: binary values that are not UTF-8 as `0x` and their hex.
fn stored_text(value: &StoredValue) -> String {
    if value.binary {
        format!("0x{}", value.value)
    } else {
        value.value.clone()
    }
}

/// Each doc value as text: numbers in `numbers`' format, byte strings as text or hex.
fn doc_value_texts(values: &DocValues, numbers: NumberFormat) -> Vec<String> {
    values
        .numbers
        .iter()
        .map(|&number| numbers.format(number))
        .chain(
            values
                .values
                .iter()
                .map(|value| bytes_text(&value.value, value.binary)),
        )
        .collect()
}

fn label(text: &str) -> Span<'static> {
    Span::styled(
        format!("{text:<12}"),
        Style::new().add_modifier(Modifier::BOLD),
    )
}

/// The hit in the shown document and field, and where its term occurs once that is fetched.
type HitPositions<'a> = (&'a TermHit, Option<&'a Result<Vec<TermPosition>, String>>);

/// Everything about the selected field: how it is indexed, then what the document has in it.
fn detail_lines(
    schema: Option<&IndexField>,
    field: &DocumentField,
    hit: Option<HitPositions>,
    term_vector: Option<&TermVector>,
    numbers: NumberFormat,
) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    if let Some(schema) = schema {
        let mut indexed = vec![postings(schema.index_options).to_owned()];
        if schema.index_options == IndexOptions::None {
            indexed = vec!["not indexed".to_owned()];
        }
        if schema.has_payloads {
            indexed.push("payloads".to_owned());
        }
        lines.push(Line::from(vec![
            label("Indexed"),
            Span::raw(indexed.join(", ")),
        ]));
        let mut other = Vec::new();
        if schema.has_norms {
            other.push("norms".to_owned());
        }
        if schema.has_term_vectors {
            other.push("term vectors".to_owned());
        }
        if schema.doc_values_type != crate::protocol::index::DocValuesType::None {
            other.push(format!("{} doc values", doc_values(schema.doc_values_type)));
        }
        if schema.point_dimension_count > 0 {
            other.push(format!(
                "points {}×{}B",
                schema.point_dimension_count, schema.point_num_bytes
            ));
        }
        if schema.vector_dimension > 0 {
            other.push(format!("vectors {}d", schema.vector_dimension));
        }
        if !other.is_empty() {
            lines.push(Line::from(vec![
                label("Field has"),
                Span::raw(other.join(", ")),
            ]));
        }
    }
    lines.push(Line::from(vec![
        label("Norm"),
        Span::raw(norm_text(field.norm)),
    ]));
    if let Some((hit, positions)) = hit {
        lines.push(Line::from(""));
        lines.extend(hit_lines(hit, positions));
    }

    lines.push(Line::from(""));
    if field.stored.is_empty() {
        lines.push(Line::from(vec![label("Stored"), Span::raw("-")]));
    } else {
        lines.push(Line::from(vec![
            label("Stored"),
            Span::raw(count(field.stored.len(), "value")),
        ]));
        let dim = Style::new().add_modifier(Modifier::DIM);
        for value in &field.stored {
            // Multi-line text keeps its lines, indented under the first; a long value shows its
            // beginning, and the viewer the rest.
            let text = stored_text(value);
            let shown = first_chars(&text, DETAIL_VALUE_CHARS);
            let mut more = text.chars().nth(DETAIL_VALUE_CHARS).is_some();
            for (i, line) in shown.split('\n').enumerate() {
                if i == DETAIL_VALUE_LINES {
                    more = true;
                    break;
                }
                let prefix = if i == 0 {
                    format!("  {:<8}", stored_type(value.kind))
                } else {
                    " ".repeat(10)
                };
                lines.push(Line::from(vec![
                    Span::styled(prefix, dim),
                    Span::raw(escape_control(line)),
                ]));
            }
            if let Some(full) = value.full_length {
                lines.push(Line::styled(
                    format!(
                        "{}cut at {} of {}, v views it whole",
                        " ".repeat(10),
                        cut_size(),
                        size(full)
                    ),
                    dim,
                ));
            } else if more {
                lines.push(Line::styled(
                    format!("{}… v views it whole", " ".repeat(10)),
                    dim,
                ));
            }
        }
    }

    lines.push(Line::from(""));
    match &field.doc_values {
        None => lines.push(Line::from(vec![label("Doc values"), Span::raw("-")])),
        Some(values) => {
            let mut heading = doc_values(values.kind).to_owned();
            if !values.numbers.is_empty() {
                heading.push_str(&format!(", as {} (x)", numbers.label()));
            }
            lines.push(Line::from(vec![label("Doc values"), Span::raw(heading)]));
            for text in doc_value_texts(values, numbers) {
                lines.push(Line::from(format!("  {text}")));
            }
        }
    }

    lines.push(Line::from(""));
    if !field.has_term_vector {
        lines.push(Line::from(vec![label("Term vector"), Span::raw("-")]));
        return lines;
    }
    match term_vector {
        None => lines.push(Line::from(vec![
            label("Term vector"),
            Span::raw("⏎ loads it"),
        ])),
        Some(TermVector::Failed(error)) => lines.push(Line::from(vec![
            label("Term vector"),
            Span::raw(format!("cannot read it: {error}")),
        ])),
        Some(TermVector::Loaded(terms)) => {
            lines.push(Line::from(vec![
                label("Term vector"),
                Span::raw(count(terms.len(), "term")),
            ]));
            for term in terms {
                let mut spans = vec![
                    Span::raw(format!("  {}", bytes_text(&term.term, term.binary))),
                    Span::styled(
                        format!(" ×{}", term.freq),
                        Style::new().add_modifier(Modifier::DIM),
                    ),
                ];
                if !term.positions.is_empty() {
                    let positions: Vec<String> = term.positions.iter().map(position_text).collect();
                    spans.push(Span::raw(format!("  {}", positions.join(", "))));
                }
                lines.push(Line::from(spans));
            }
        }
    }
    lines
}

/// The term of the hit, how often it occurs, and where.
fn hit_lines(
    hit: &TermHit,
    positions: Option<&Result<Vec<TermPosition>, String>>,
) -> Vec<Line<'static>> {
    let mut heading = vec![Span::raw(bytes_text(&hit.term.value, hit.term.binary))];
    if let Some(freq) = hit.freq {
        heading.push(Span::styled(
            format!(" ×{freq}"),
            Style::new().add_modifier(Modifier::DIM),
        ));
    }
    heading.push(Span::raw(format!(
        ", doc {} of {} ([ ])",
        hit.index + 1,
        hit.doc_freq
    )));
    let mut lines = vec![Line::from([vec![label("Term")], heading].concat())];
    match positions {
        // Fetched right after the document, so this shows only for a moment.
        None => {}
        Some(Err(error)) => lines.push(Line::from(format!("  cannot read the positions: {error}"))),
        Some(Ok(positions)) if positions.is_empty() => {
            lines.push(Line::from("  no positions indexed"));
        }
        Some(Ok(positions)) => {
            let texts: Vec<String> = positions.iter().map(position_text).collect();
            lines.push(Line::from(format!("  {}", texts.join(", "))));
        }
    }
    lines
}

/// `3`, `3 [9–12]`, `[9–12]` or with a payload `3 [9–12] ⟨0a0b⟩`.
fn position_text(position: &TermPosition) -> String {
    let mut parts = Vec::new();
    if let Some(position) = position.position {
        parts.push(position.to_string());
    }
    if let (Some(start), Some(end)) = (position.start_offset, position.end_offset) {
        parts.push(format!("[{start}–{end}]"));
    }
    if let Some(payload) = &position.payload {
        parts.push(format!("⟨{payload}⟩"));
    }
    parts.join(" ")
}

/// The norm, and the field length it stands for if Lucene's similarities wrote it.
fn norm_text(norm: Option<i64>) -> String {
    let Some(norm) = norm else {
        return "-".to_owned();
    };
    match norm_length(norm) {
        Some((low, high)) if low == high => format!("{norm} → length {low}"),
        Some((low, high)) => format!("{norm} → length {low}–{high}"),
        None => norm.to_string(),
    }
}

/// The field lengths a norm stands for if Lucene's similarities wrote it: they all encode the
/// number of terms with `SmallFloat.intToByte4`, which is exact below 40 and a range above.
/// `None` for a norm that is not a byte, which some other similarity wrote.
pub fn norm_length(norm: i64) -> Option<(u32, u32)> {
    let byte = i8::try_from(norm).ok()? as u8;
    let low = byte4_to_int(byte);
    let high = match byte.checked_add(1) {
        Some(next) => byte4_to_int(next) - 1,
        None => low,
    };
    Some((low, high))
}

/// `SmallFloat.byte4ToInt`: values below 24 stand for themselves, the others are a 4-bit float
/// (`int4ToLong`) on top of 24.
fn byte4_to_int(byte: u8) -> u32 {
    const NUM_FREE_VALUES: u8 = 24;
    if byte < NUM_FREE_VALUES {
        return u32::from(byte);
    }
    let int4 = u32::from(byte - NUM_FREE_VALUES);
    let bits = int4 & 0x07;
    let decoded = match int4 >> 3 {
        0 => bits,
        exponent => (bits | 0x08) << (exponent - 1),
    };
    u32::from(NUM_FREE_VALUES) + decoded
}

fn render_prompt(frame: &mut Frame, area: Rect, prompt: &DocIdPrompt, max_doc: i32) {
    let width = area.width.min(48);
    let height = 4;
    let popup = Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + area.height.saturating_sub(height) / 2,
        width,
        height: height.min(area.height),
    };
    let message = prompt
        .error
        .clone()
        .unwrap_or_else(|| format!("0 to {}; Enter goes there, Esc cancels.", max_doc - 1));
    frame.render_widget(Clear, popup);
    frame.render_widget(
        Paragraph::new(vec![
            Line::from(format!("{}▏", prompt.input)),
            Line::from(Span::styled(
                message,
                Style::new().add_modifier(Modifier::DIM),
            )),
        ])
        .block(Block::bordered().title(" Go to document ")),
        popup,
    );
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::document::{BytesValue, StoredType};
    use crate::protocol::index::{DocValuesType, TermStats};

    fn schema(name: &str, number: i32) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number,
            index_options: IndexOptions::DocsAndFreqsAndPositions,
            has_norms: true,
            has_payloads: false,
            has_term_vectors: false,
            doc_values_type: DocValuesType::None,
            point_dimension_count: 0,
            point_num_bytes: 0,
            vector_dimension: 0,
            terms: Some(TermStats {
                doc_count: 1,
                sum_doc_freq: 1,
                sum_total_term_freq: 1,
            }),
        }
    }

    fn empty(name: &str) -> DocumentField {
        DocumentField {
            name: name.to_owned(),
            norm: None,
            stored: Vec::new(),
            doc_values: None,
            has_term_vector: false,
        }
    }

    fn string(value: &str) -> StoredValue {
        StoredValue {
            kind: StoredType::String,
            value: value.to_owned(),
            binary: false,
            full_length: None,
        }
    }

    /// Fields `id` (stored), `body` (norm, stored, term vector), `rank` (doc values) and
    /// `extra` (nothing).
    fn document(doc_id: i32, deleted: bool) -> GetDocumentResult {
        GetDocumentResult {
            doc_id,
            deleted,
            fields: vec![
                DocumentField {
                    stored: vec![string(&format!("doc-{doc_id}"))],
                    ..empty("id")
                },
                DocumentField {
                    norm: Some(3),
                    stored: vec![string("red blue\nred"), string("second")],
                    has_term_vector: true,
                    ..empty("body")
                },
                DocumentField {
                    doc_values: Some(DocValues {
                        kind: DocValuesType::SortedNumeric,
                        numbers: vec![-1, 4612811918334230528],
                        values: Vec::new(),
                    }),
                    ..empty("rank")
                },
                empty("extra"),
            ],
        }
    }

    fn documents() -> Documents {
        let mut body = schema("body", 1);
        body.has_term_vectors = true;
        Documents::new(
            vec![schema("id", 0), body, schema("rank", 2), schema("extra", 3)],
            5,
        )
    }

    fn render(documents: &mut Documents, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| documents.render(frame, frame.area()))
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

    #[test]
    fn asks_for_the_first_document_once() {
        let mut documents = documents();
        assert_eq!(documents.initial_request(), Some(Request::Document(0)));

        documents.loaded(document(0, false));
        assert_eq!(documents.initial_request(), None);
        assert_eq!(Documents::new(Vec::new(), 0).initial_request(), None);
    }

    #[test]
    fn shows_the_fields_and_the_selected_one() {
        let mut documents = documents();
        documents.loaded(document(0, false));

        let screen = render(&mut documents, 140, 30);
        assert!(screen.contains("#0 of 0–4"), "{screen}");
        assert!(screen.contains("id     I--S----     -  doc-0"), "{screen}");
        assert!(screen.contains("body   IN-SV---     3"), "{screen}");
        assert!(screen.contains("extra  I-------"), "{screen}");
        assert!(screen.contains("red blue\\nred (+1 more)"), "{screen}");
        assert!(screen.contains("dv: -1, 4612811918334230528"), "{screen}");
        // The details of the first field.
        assert!(screen.contains("string  doc-0"), "{screen}");
        assert!(screen.contains("Term vector -"), "{screen}");
    }

    #[test]
    fn marks_a_deleted_document() {
        let mut documents = documents();
        documents.loaded(document(1, true));

        assert!(render(&mut documents, 100, 20).contains("#1 of 0–4   deleted"));
    }

    #[test]
    fn steps_through_the_documents() {
        let mut documents = documents();
        documents.loaded(document(0, false));

        assert_eq!(documents.handle_key(KeyCode::Left), None);
        assert_eq!(
            documents.handle_key(KeyCode::Right),
            Some(Request::Document(1))
        );
        documents.loaded(document(4, false));
        assert_eq!(documents.handle_key(KeyCode::Right), None);
        assert_eq!(
            documents.handle_key(KeyCode::Char('p')),
            Some(Request::Document(3))
        );
    }

    #[test]
    fn goes_to_a_document_id() {
        let mut documents = documents();
        documents.loaded(document(0, false));

        assert_eq!(documents.handle_key(KeyCode::Char('i')), None);
        assert!(documents.prompting());
        for key in [KeyCode::Char('9'), KeyCode::Char('x'), KeyCode::Enter] {
            assert_eq!(documents.handle_key(key), None);
        }
        assert!(render(&mut documents, 100, 20).contains("Enter a number from 0 to 4."));

        documents.handle_key(KeyCode::Backspace);
        documents.handle_key(KeyCode::Char('3'));
        assert_eq!(
            documents.handle_key(KeyCode::Enter),
            Some(Request::Document(3))
        );
        assert!(!documents.prompting());

        documents.handle_key(KeyCode::Char('i'));
        assert_eq!(documents.handle_key(KeyCode::Esc), None);
        assert!(!documents.prompting());
    }

    #[test]
    fn the_selection_stays_on_its_field() {
        let mut documents = documents();
        documents.loaded(document(0, false));
        documents.handle_key(KeyCode::Down);
        documents.handle_key(KeyCode::Down);

        documents.loaded(document(1, false));

        assert_eq!(documents.selected_field().unwrap().name, "rank");
    }

    #[test]
    fn hides_and_shows_empty_fields() {
        let mut documents = documents();
        documents.loaded(document(0, false));
        documents.handle_key(KeyCode::End);
        assert_eq!(documents.selected_field().unwrap().name, "extra");

        documents.handle_key(KeyCode::Char('e'));

        let screen = render(&mut documents, 100, 20);
        assert!(screen.contains("Fields (3 of 4, e shows all)"), "{screen}");
        assert!(!screen.contains("extra"), "{screen}");
        // The hidden field's selection falls back to the first row.
        assert_eq!(documents.selected_field().unwrap().name, "id");
    }

    #[test]
    fn loads_the_term_vector_on_enter() {
        let mut documents = documents();
        documents.loaded(document(2, false));
        // id has no term vector.
        assert_eq!(documents.handle_key(KeyCode::Enter), None);
        documents.handle_key(KeyCode::Down);
        assert!(render(&mut documents, 140, 30).contains("Term vector ⏎ loads it"));

        assert_eq!(
            documents.handle_key(KeyCode::Enter),
            Some(Request::TermVector {
                doc_id: 2,
                field: "body".to_owned(),
            })
        );
        documents.term_vector_loaded(
            2,
            "body",
            vec![
                TermVectorTerm {
                    term: "red".to_owned(),
                    binary: false,
                    freq: 2,
                    positions: vec![
                        TermPosition {
                            position: Some(0),
                            start_offset: Some(0),
                            end_offset: Some(3),
                            payload: Some("0a0b".to_owned()),
                        },
                        TermPosition {
                            position: Some(2),
                            start_offset: None,
                            end_offset: None,
                            payload: None,
                        },
                    ],
                },
                TermVectorTerm {
                    term: "ff".to_owned(),
                    binary: true,
                    freq: 1,
                    positions: Vec::new(),
                },
            ],
        );

        let screen = render(&mut documents, 140, 30);
        assert!(screen.contains("Term vector 2 terms"), "{screen}");
        assert!(screen.contains("red ×2  0 [0–3] ⟨0a0b⟩, 2"), "{screen}");
        assert!(screen.contains("0xff ×1"), "{screen}");
        // Loaded once.
        assert_eq!(documents.handle_key(KeyCode::Enter), None);
        // A late answer for another document is dropped.
        documents.loaded(document(3, false));
        documents.term_vector_failed(2, "body", "gone".to_owned());
        assert!(!render(&mut documents, 140, 30).contains("gone"));
    }

    #[test]
    fn shows_multi_line_stored_values_and_decoded_numbers() {
        let mut documents = documents();
        documents.loaded(document(0, false));
        documents.handle_key(KeyCode::Down);
        let screen = render(&mut documents, 140, 30);
        assert!(screen.contains("string  red blue"), "{screen}");
        assert!(screen.contains("          red "), "{screen}");
        assert!(screen.contains("string  second"), "{screen}");

        documents.handle_key(KeyCode::Down);
        documents.handle_key(KeyCode::Char('x'));
        let screen = render(&mut documents, 140, 30);
        assert!(
            screen.contains("sorted numeric, as double bits (x)"),
            "{screen}"
        );
        assert!(screen.contains("  2.5"), "{screen}");
        assert!(screen.contains("dv: NaN, 2.5"), "{screen}");
    }

    #[test]
    fn decodes_numbers() {
        assert_eq!(NumberFormat::Long.format(-3), "-3");
        assert_eq!(NumberFormat::Double.format(2.5f64.to_bits() as i64), "2.5");
        // NumericUtils.doubleToSortableLong(-2.5) and floatToSortableInt(1.5f).
        let bits = (-2.5f64).to_bits() as i64;
        let sortable = bits ^ ((bits >> 63) & i64::MAX);
        assert_eq!(NumberFormat::SortableDouble.format(sortable), "-2.5");
        assert_eq!(
            NumberFormat::Float.format(i64::from(1.5f32.to_bits() as i32)),
            "1.5"
        );
        let bits = (-1.5f32).to_bits() as i32;
        let sortable = bits ^ ((bits >> 31) & i32::MAX);
        assert_eq!(
            NumberFormat::SortableFloat.format(i64::from(sortable)),
            "-1.5"
        );
    }

    #[test]
    fn decodes_norms_to_field_lengths() {
        // From Lucene's SmallFloat.intToByte4 and byte4ToInt.
        assert_eq!(norm_length(0), Some((0, 0)));
        assert_eq!(norm_length(23), Some((23, 23)));
        assert_eq!(norm_length(39), Some((39, 39)));
        assert_eq!(norm_length(40), Some((40, 41)));
        assert_eq!(norm_length(57), Some((96, 103)));
        assert_eq!(norm_length(88), Some((1048, 1175)));
        assert_eq!(norm_length(-89), Some((983_064, 1_048_599)));
        assert_eq!(norm_length(-1), Some((2_013_265_944, 2_013_265_944)));
        assert_eq!(norm_length(128), None);
        assert_eq!(norm_text(Some(88)), "88 → length 1048–1175");
        assert_eq!(norm_text(Some(1000)), "1000");
        assert_eq!(norm_text(None), "-");

        let mut documents = documents();
        documents.loaded(document(0, false));
        documents.handle_key(KeyCode::Down);
        let screen = render(&mut documents, 140, 30);
        assert!(screen.contains("Norm        3 → length 3"), "{screen}");
    }

    #[test]
    fn views_the_values_of_the_selected_field() {
        let mut documents = documents();
        documents.loaded(document(0, false));
        // extra has no values.
        documents.handle_key(KeyCode::End);
        render(&mut documents, 100, 20);
        assert_eq!(documents.handle_key(KeyCode::Char('v')), None);
        assert_eq!(documents.viewer().map(Viewer::mode), None);

        documents.handle_key(KeyCode::Up);
        assert_eq!(documents.handle_key(KeyCode::Char('v')), None);
        assert_eq!(
            documents.viewer().map(Viewer::mode),
            Some(viewer::Mode::Text)
        );
        assert!(documents.prompting());
        let screen = render(&mut documents, 100, 20);
        assert!(
            screen.contains("rank · 1 of 2 · sorted numeric doc value · 8 B"),
            "{screen}"
        );
        assert!(screen.contains("│-1"), "{screen}");
        // ← and → step through the values now, not the documents.
        assert_eq!(documents.handle_key(KeyCode::Right), None);
        assert!(render(&mut documents, 100, 20).contains("│4612811918334230528"));
        assert_eq!(
            documents.handle_key(KeyCode::Char('y')),
            Some(Request::Copy("4612811918334230528".to_owned()))
        );

        assert_eq!(documents.handle_key(KeyCode::Esc), None);
        assert_eq!(documents.viewer().map(Viewer::mode), None);
        assert_eq!(
            documents.handle_key(KeyCode::Right),
            Some(Request::Document(1))
        );
    }

    #[test]
    fn the_next_viewer_splits_at_the_last_delimiters() {
        let mut documents = documents();
        documents.loaded(document(0, false));
        documents.handle_key(KeyCode::Char('v'));
        documents.handle_key(KeyCode::Char('d'));
        documents.handle_key(KeyCode::Backspace);
        documents.handle_key(KeyCode::Char('-'));
        documents.handle_key(KeyCode::Enter);
        documents.handle_key(KeyCode::Esc);

        documents.handle_key(KeyCode::Char('v'));
        documents.handle_key(KeyCode::Char('d'));
        assert!(render(&mut documents, 100, 20).contains("Items      -▏"));
        documents.handle_key(KeyCode::Enter);
        assert!(render(&mut documents, 100, 20).contains("│2  0"));

        // Also when another document closes the viewer.
        documents.handle_key(KeyCode::Char('d'));
        documents.handle_key(KeyCode::Char('c'));
        documents.handle_key(KeyCode::Enter);
        documents.loaded(document(1, false));
        documents.handle_key(KeyCode::Char('v'));
        documents.handle_key(KeyCode::Char('d'));
        assert!(render(&mut documents, 100, 20).contains("Items      -c▏"));
    }

    #[test]
    fn fetches_a_cut_value_for_the_viewer() {
        let mut documents = documents();
        let mut doc = document(2, false);
        doc.fields[1].stored[1].full_length = Some(70_000);
        documents.loaded(doc);
        documents.handle_key(KeyCode::Down);
        let screen = render(&mut documents, 140, 30);
        assert!(
            screen.contains("cut at 64.0 KiB of 68.4 KiB, v views it whole"),
            "{screen}"
        );

        assert_eq!(documents.handle_key(KeyCode::Char('v')), None);
        assert_eq!(
            documents.handle_key(KeyCode::Right),
            Some(Request::StoredValue {
                doc_id: 2,
                field: "body".to_owned(),
                index: 1,
            })
        );
        documents.stored_value_loaded(2, "body", 1, &string("second, whole"));
        assert!(render(&mut documents, 100, 20).contains("│second, whole"));
        // Another document closes the viewer.
        documents.loaded(document(3, false));
        assert_eq!(documents.viewer().map(Viewer::mode), None);
    }

    #[test]
    fn shows_the_beginning_of_a_long_value() {
        let mut documents = documents();
        let lines: Vec<String> = (1..=30).map(|n| format!("line {n}")).collect();
        let mut doc = document(0, false);
        doc.fields[0].stored = vec![string(&lines.join("\n"))];
        documents.loaded(doc);
        documents.handle_key(KeyCode::Tab);
        documents.handle_key(KeyCode::PageDown);

        let screen = render(&mut documents, 140, 40);
        assert!(screen.contains("          line 20"), "{screen}");
        assert!(!screen.contains("line 21"), "{screen}");
        assert!(screen.contains("          … v views it whole"), "{screen}");
    }

    #[test]
    fn shows_binary_values_as_hex() {
        let mut documents = documents();
        let mut doc = document(0, false);
        doc.fields[0].stored = vec![StoredValue {
            kind: StoredType::Binary,
            value: "ff00".to_owned(),
            binary: true,
            full_length: None,
        }];
        doc.fields[2].doc_values = Some(DocValues {
            kind: DocValuesType::SortedSet,
            numbers: Vec::new(),
            values: vec![BytesValue {
                value: "a\tb".to_owned(),
                binary: false,
            }],
        });
        documents.loaded(doc);

        let screen = render(&mut documents, 140, 30);
        assert!(screen.contains("binary  0xff00"), "{screen}");
        assert!(screen.contains("dv: a\\tb"), "{screen}");
    }

    #[test]
    fn shows_a_failed_document() {
        let mut documents = documents();
        documents.failed(3, "No document 3 (DOC_NOT_FOUND -32008)".to_owned());

        let screen = render(&mut documents, 100, 20);
        assert!(
            screen.contains("#3 of 0–4   Cannot read it: No document 3"),
            "{screen}"
        );
        assert_eq!(documents.initial_request(), None);
        assert_eq!(
            documents.handle_key(KeyCode::Right),
            Some(Request::Document(4))
        );
    }

    #[test]
    fn scrolls_the_details() {
        let mut documents = documents();
        documents.loaded(document(0, false));
        documents.handle_key(KeyCode::Tab);
        documents.handle_key(KeyCode::End);
        let screen = render(&mut documents, 140, 30);
        // Scrolled to the last line.
        assert!(!screen.contains("Indexed"), "{screen}");
        assert!(screen.contains("Term vector -"), "{screen}");

        documents.handle_key(KeyCode::Home);
        assert!(render(&mut documents, 140, 30).contains("Indexed"));
    }

    fn text(value: &str) -> BytesValue {
        BytesValue {
            value: value.to_owned(),
            binary: false,
        }
    }

    fn hit(field: &str, doc_id: i32, index: usize) -> TermHit {
        TermHit {
            field: field.to_owned(),
            term: text("red"),
            doc_id,
            index,
            doc_freq: 3,
            freq: Some(2),
        }
    }

    #[test]
    fn shows_where_the_term_of_a_hit_occurs() {
        let mut documents = documents();
        documents.loaded(document(0, false));
        assert_eq!(documents.handle_key(KeyCode::Char(']')), None);

        documents.show_hit(hit("body", 2, 1));
        assert_eq!(documents.positions_request(), None);
        documents.loaded(document(2, false));

        assert_eq!(documents.selected_field().unwrap().name, "body");
        assert_eq!(
            documents.positions_request(),
            Some(Request::Positions {
                doc_id: 2,
                field: "body".to_owned(),
                term: text("red"),
            })
        );
        documents.positions_loaded(
            2,
            "body",
            &text("red"),
            vec![
                TermPosition {
                    position: Some(0),
                    start_offset: Some(0),
                    end_offset: Some(3),
                    payload: None,
                },
                TermPosition {
                    position: Some(2),
                    start_offset: None,
                    end_offset: None,
                    payload: None,
                },
            ],
        );
        assert_eq!(documents.positions_request(), None);

        let screen = render(&mut documents, 140, 30);
        assert!(
            screen.contains("#2 of 0–4   body:red  2 of 3  [ ] steps"),
            "{screen}"
        );
        assert!(
            screen.contains("Term        red ×2, doc 2 of 3 ([ ])"),
            "{screen}"
        );
        assert!(screen.contains("  0 [0–3], 2"), "{screen}");
        assert_eq!(
            documents.handle_key(KeyCode::Char('[')),
            Some(Request::StepTerm { forward: false })
        );
        assert_eq!(
            documents.handle_key(KeyCode::Char(']')),
            Some(Request::StepTerm { forward: true })
        );

        // Another document has no positions of the term.
        documents.loaded(document(3, false));
        assert_eq!(documents.positions_request(), None);
        assert!(!render(&mut documents, 140, 30).contains("Term        red"));
    }

    #[test]
    fn keeps_the_field_of_a_hit_among_the_empty_ones() {
        let mut documents = documents();
        documents.handle_key(KeyCode::Char('e'));
        documents.show_hit(hit("extra", 1, 0));
        documents.loaded(document(1, false));

        assert_eq!(documents.selected_field().unwrap().name, "extra");
        documents.positions_failed(1, "extra", &text("red"), "broken".to_owned());
        let screen = render(&mut documents, 140, 30);
        assert!(screen.contains("Fields (4) "), "{screen}");
        assert!(
            screen.contains("cannot read the positions: broken"),
            "{screen}"
        );
    }
}
