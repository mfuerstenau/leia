//! The terms screen: the fields with postings, the terms of the selected field and the postings
//! of the selected term, side by side (ADR 0007). Terms and postings come in pages, and the next
//! page is fetched once the selection reaches the last row. Enter on a posting shows its document
//! on the documents screen.

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Cell, Paragraph, Row, Table, TableState, Wrap};

use crate::overview::{highlight, panel};
use crate::protocol::document::BytesValue;
use crate::protocol::index::{IndexField, IndexOptions};
use crate::protocol::terms::{GetPostingsResult, GetTermsResult, Posting, TermEntry};
use crate::text::{bytes_text, escape_control, first_chars};

/// How many terms one `getTerms` call fetches.
pub const TERMS_PAGE: i32 = 100;
/// How many postings one `getPostings` call fetches.
pub const POSTINGS_PAGE: i32 = 100;
/// Rows that PageUp and PageDown move.
const PAGE: usize = 10;
/// The fields column is at most this wide, borders included.
const FIELDS_MAX_WIDTH: u16 = 32;
const POSTINGS_WIDTH: u16 = 30;
const COLUMN_SPACING: u16 = 2;

/// What the caller has to fetch or show for the terms screen.
#[derive(Debug, PartialEq, Eq)]
pub enum Request {
    /// A page of the terms of `field` from `from` on, reported back with [`Terms::terms_loaded`]
    /// or [`Terms::terms_failed`]. With `more`, it continues the shown terms; otherwise it
    /// replaces them.
    Terms {
        field: String,
        from: Option<BytesValue>,
        more: bool,
    },
    /// A page of the postings of a term from `from_doc` on, reported back with
    /// [`Terms::postings_loaded`] or [`Terms::postings_failed`]. With `more`, it continues the
    /// shown postings; otherwise it replaces them.
    Postings {
        field: String,
        term: BytesValue,
        from_doc: i32,
        more: bool,
    },
    /// Show this document on the documents screen.
    Document(TermHit),
}

/// A document that contains a term: a posting, as the documents screen shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TermHit {
    pub field: String,
    pub term: BytesValue,
    pub doc_id: i32,
    /// The posting's place among the term's postings, from 0.
    pub index: usize,
    pub doc_freq: i32,
    /// How often the term occurs in the document; `None` if frequencies are not indexed.
    pub freq: Option<i32>,
}

/// The columns, from left to right.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Column {
    Fields,
    Terms,
    Postings,
}

/// The terms of the selected field, as far as they are fetched.
#[derive(Debug)]
struct TermList {
    field: String,
    /// The text the list starts from, if it was sought.
    from: Option<String>,
    entries: Vec<TermEntry>,
    /// Where the next page starts; `None` after the last term.
    next: Option<BytesValue>,
    /// Why the last page could not be fetched; no more pages are fetched then.
    error: Option<String>,
    selected: usize,
    table: TableState,
}

impl TermList {
    fn selected_term(&self) -> Option<BytesValue> {
        self.entries.get(self.selected).map(|entry| BytesValue {
            value: entry.term.clone(),
            binary: entry.binary,
        })
    }
}

/// The postings of the selected term, as far as they are fetched.
#[derive(Debug)]
struct PostingList {
    field: String,
    term: BytesValue,
    doc_freq: i32,
    postings: Vec<Posting>,
    /// Where the next page starts; `None` after the last posting.
    next_doc: Option<i32>,
    /// Why the last page could not be fetched; no more pages are fetched then.
    error: Option<String>,
    selected: usize,
    table: TableState,
    /// Whether the posting after the last one is to be shown once the next page is in.
    step_pending: bool,
}

impl PostingList {
    fn is_of(&self, field: &str, term: &BytesValue) -> bool {
        self.field == field && self.term == *term
    }

    fn hit(&self) -> Option<TermHit> {
        let posting = self.postings.get(self.selected)?;
        Some(TermHit {
            field: self.field.clone(),
            term: self.term.clone(),
            doc_id: posting.doc_id,
            index: self.selected,
            doc_freq: self.doc_freq,
            freq: posting.freq,
        })
    }
}

pub struct Terms {
    /// The fields with postings, in field number order.
    fields: Vec<IndexField>,
    field_row: usize,
    field_table: TableState,
    terms: Option<TermList>,
    postings: Option<PostingList>,
    focus: Column,
    /// The text typed into the seek prompt while it is open.
    seek: Option<String>,
    /// The text that the first page asked for starts from, if any.
    requested_from: Option<String>,
}

impl Terms {
    pub fn new(fields: Vec<IndexField>) -> Self {
        Self {
            fields: fields
                .into_iter()
                .filter(|field| field.index_options != IndexOptions::None)
                .collect(),
            field_row: 0,
            field_table: TableState::new(),
            terms: None,
            postings: None,
            focus: Column::Fields,
            seek: None,
            requested_from: None,
        }
    }

    /// What to fetch when the screen is shown.
    pub fn initial_request(&mut self) -> Option<Request> {
        self.next_request()
    }

    fn selected_field(&self) -> Option<&IndexField> {
        self.fields.get(self.field_row)
    }

    /// What the screen still lacks: the first page of the selected field's terms, the next page
    /// once the selection is on the last term, the postings of the selected term, and the next
    /// page of those once the selection is on the last posting. A page that failed is not
    /// fetched again by itself.
    fn next_request(&mut self) -> Option<Request> {
        let field = self.selected_field()?.name.clone();
        let Some(terms) = self.terms.as_ref().filter(|terms| terms.field == field) else {
            self.postings = None;
            self.requested_from = None;
            return Some(Request::Terms {
                field,
                from: None,
                more: false,
            });
        };
        if terms.error.is_none()
            && terms.selected + 1 >= terms.entries.len()
            && let Some(next) = &terms.next
        {
            return Some(Request::Terms {
                field,
                from: Some(next.clone()),
                more: true,
            });
        }
        let term = terms.selected_term()?;
        let Some(postings) = self
            .postings
            .as_ref()
            .filter(|postings| postings.is_of(&field, &term))
        else {
            return Some(Request::Postings {
                field,
                term,
                from_doc: 0,
                more: false,
            });
        };
        if postings.error.is_none()
            && postings.selected + 1 >= postings.postings.len()
            && let Some(from_doc) = postings.next_doc
        {
            return Some(Request::Postings {
                field,
                term,
                from_doc,
                more: true,
            });
        }
        None
    }

    pub fn terms_loaded(
        &mut self,
        field: &str,
        more: bool,
        result: GetTermsResult,
    ) -> Option<Request> {
        match &mut self.terms {
            Some(terms) if more && terms.field == field => {
                terms.entries.extend(result.terms);
                terms.next = result.next;
            }
            _ if more => return None,
            _ => {
                self.terms = Some(TermList {
                    field: field.to_owned(),
                    from: self.requested_from.clone(),
                    entries: result.terms,
                    next: result.next,
                    error: None,
                    selected: 0,
                    table: TableState::new(),
                });
            }
        }
        self.next_request()
    }

    pub fn terms_failed(&mut self, field: &str, more: bool, error: String) -> Option<Request> {
        match &mut self.terms {
            // The selection may have moved on to a term whose postings are still missing.
            Some(terms) if more && terms.field == field => terms.error = Some(error),
            _ if more => return None,
            _ => {
                self.terms = Some(TermList {
                    field: field.to_owned(),
                    from: self.requested_from.clone(),
                    entries: Vec::new(),
                    next: None,
                    error: Some(error),
                    selected: 0,
                    table: TableState::new(),
                });
                self.postings = None;
                return None;
            }
        }
        self.next_request()
    }

    pub fn postings_loaded(
        &mut self,
        field: &str,
        term: &BytesValue,
        more: bool,
        result: GetPostingsResult,
    ) -> Option<Request> {
        match &mut self.postings {
            Some(postings) if more && postings.is_of(field, term) => {
                postings.postings.extend(result.postings);
                postings.next_doc = result.next_doc;
                postings.doc_freq = result.doc_freq;
                if postings.step_pending {
                    postings.step_pending = false;
                    if postings.selected + 1 < postings.postings.len() {
                        postings.selected += 1;
                        return postings.hit().map(Request::Document);
                    }
                }
            }
            _ if more => return None,
            _ => {
                self.postings = Some(PostingList {
                    field: field.to_owned(),
                    term: term.clone(),
                    doc_freq: result.doc_freq,
                    postings: result.postings,
                    next_doc: result.next_doc,
                    error: None,
                    selected: 0,
                    table: TableState::new(),
                    step_pending: false,
                });
            }
        }
        self.next_request()
    }

    pub fn postings_failed(&mut self, field: &str, term: &BytesValue, more: bool, error: String) {
        match &mut self.postings {
            Some(postings) if more && postings.is_of(field, term) => {
                postings.error = Some(error);
                postings.step_pending = false;
            }
            _ if more => {}
            _ => {
                self.postings = Some(PostingList {
                    field: field.to_owned(),
                    term: term.clone(),
                    doc_freq: 0,
                    postings: Vec::new(),
                    next_doc: None,
                    error: Some(error),
                    selected: 0,
                    table: TableState::new(),
                    step_pending: false,
                });
            }
        }
    }

    /// Moves to the previous (`forward` false) or next posting of the selected term, and shows
    /// its document; fetches the next page first if the next posting is not in yet.
    pub fn step(&mut self, forward: bool) -> Option<Request> {
        let postings = self.postings.as_mut()?;
        if !forward {
            postings.selected = postings.selected.checked_sub(1)?;
            return postings.hit().map(Request::Document);
        }
        if postings.selected + 1 < postings.postings.len() {
            postings.selected += 1;
            return postings.hit().map(Request::Document);
        }
        let from_doc = postings.next_doc.filter(|_| postings.error.is_none())?;
        postings.step_pending = true;
        Some(Request::Postings {
            field: postings.field.clone(),
            term: postings.term.clone(),
            from_doc,
            more: true,
        })
    }

    /// Shows the terms of `field` from `term` on, with the focus on the term's postings.
    pub fn browse(&mut self, field: &str, term: BytesValue) -> Option<Request> {
        self.field_row = self.fields.iter().position(|f| f.name == field)?;
        self.seek = None;
        self.focus = Column::Postings;
        self.requested_from = Some(bytes_text(&term.value, term.binary));
        Some(Request::Terms {
            field: field.to_owned(),
            from: Some(term),
            more: false,
        })
    }

    /// Whether the seek prompt takes the keys, Esc included.
    pub fn prompting(&self) -> bool {
        self.seek.is_some()
    }

    /// Moves the selection in the focused column, switches columns with ←/→ and Tab, seeks with
    /// `/`, and with Enter goes one column to the right or shows the selected posting's document.
    pub fn handle_key(&mut self, key: KeyCode) -> Option<Request> {
        if self.seek.is_some() {
            return self.seek_key(key);
        }
        match key {
            KeyCode::Char('/') if self.selected_field().is_some() => {
                self.seek = Some(String::new());
                self.focus = Column::Terms;
                None
            }
            KeyCode::Enter if self.focus == Column::Postings => {
                self.postings.as_ref()?.hit().map(Request::Document)
            }
            KeyCode::Enter | KeyCode::Right | KeyCode::Char('l') | KeyCode::Tab => {
                self.focus = match self.focus {
                    Column::Fields => Column::Terms,
                    Column::Terms => Column::Postings,
                    Column::Postings if key == KeyCode::Tab => Column::Fields,
                    Column::Postings => Column::Postings,
                };
                None
            }
            KeyCode::Left | KeyCode::Char('h') | KeyCode::BackTab => {
                self.focus = match self.focus {
                    Column::Fields if key == KeyCode::BackTab => Column::Postings,
                    Column::Fields | Column::Terms => Column::Fields,
                    Column::Postings => Column::Terms,
                };
                None
            }
            _ => self.move_selection(key),
        }
    }

    fn seek_key(&mut self, key: KeyCode) -> Option<Request> {
        let text = self.seek.as_mut()?;
        match key {
            KeyCode::Enter | KeyCode::Esc => {
                self.seek = None;
                return None;
            }
            KeyCode::Char(c) => text.push(c),
            KeyCode::Backspace => {
                text.pop();
            }
            _ => return None,
        }
        let from = (!text.is_empty()).then(|| BytesValue {
            value: text.clone(),
            binary: false,
        });
        self.requested_from = (!text.is_empty()).then(|| text.clone());
        Some(Request::Terms {
            field: self.selected_field()?.name.clone(),
            from,
            more: false,
        })
    }

    fn move_selection(&mut self, key: KeyCode) -> Option<Request> {
        match self.focus {
            Column::Fields => {
                let row = moved(self.field_row, self.fields.len(), key)?;
                if row == self.field_row {
                    return None;
                }
                self.field_row = row;
            }
            Column::Terms => {
                let terms = self.terms.as_mut()?;
                terms.selected = moved(terms.selected, terms.entries.len(), key)?;
            }
            Column::Postings => {
                let postings = self.postings.as_mut()?;
                postings.selected = moved(postings.selected, postings.postings.len(), key)?;
            }
        }
        self.next_request()
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        if self.fields.is_empty() {
            frame.render_widget(
                Paragraph::new("No field has postings.")
                    .wrap(Wrap { trim: false })
                    .block(panel(" Fields ".to_owned(), true)),
                area,
            );
            return;
        }
        let name_width = self
            .fields
            .iter()
            .map(|field| field.name.chars().count())
            .max()
            .unwrap_or_default() as u16;
        let [fields_area, terms_area, postings_area] = Layout::horizontal([
            Constraint::Length((name_width + 4).clamp(12, FIELDS_MAX_WIDTH)),
            Constraint::Fill(1),
            Constraint::Length(POSTINGS_WIDTH),
        ])
        .areas(area);
        self.render_fields(frame, fields_area);
        self.render_terms(frame, terms_area);
        self.render_postings(frame, postings_area);
    }

    fn render_fields(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Column::Fields;
        let block = panel(" Fields ".to_owned(), focused);
        let rows = self
            .fields
            .iter()
            .map(|field| Row::new([escape_control(&field.name)]));
        self.field_table.select(Some(self.field_row));
        let table = Table::new(rows, [Constraint::Fill(1)])
            .row_highlight_style(highlight(focused))
            .block(block);
        frame.render_stateful_widget(table, area, &mut self.field_table);
    }

    fn render_terms(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Column::Terms;
        let seek = self.seek.clone();
        let Some(terms) = &mut self.terms else {
            frame.render_widget(panel(" Terms ".to_owned(), focused), area);
            return;
        };
        let field = first_chars(&escape_control(&terms.field), 30);
        let title = match (&seek, &terms.from) {
            (Some(text), _) => format!(" {field} from {}▏ ", escape_control(text)),
            (None, Some(from)) => format!(" {field} from {} (/ seeks) ", escape_control(from)),
            (None, None) => format!(" {field} (/ seeks) "),
        };
        let block = panel(title, focused);
        if terms.entries.is_empty() {
            let text = match (&terms.error, &terms.from) {
                (Some(error), _) => format!("Cannot read the terms: {error}"),
                (None, Some(_)) => "No term from there on.".to_owned(),
                (None, None) => "The field has no terms.".to_owned(),
            };
            frame.render_widget(
                Paragraph::new(text).wrap(Wrap { trim: false }).block(block),
                area,
            );
            return;
        }
        let header = Row::new([
            Cell::from("Term"),
            Cell::from(Line::from("Docs").right_aligned()),
            Cell::from(Line::from("Freq").right_aligned()),
        ])
        .style(Style::new().add_modifier(Modifier::BOLD));
        let mut rows: Vec<Row> = terms
            .entries
            .iter()
            .map(|entry| {
                Row::new([
                    Cell::from(bytes_text(&entry.term, entry.binary)),
                    Cell::from(Line::from(entry.doc_freq.to_string()).right_aligned()),
                    Cell::from(Line::from(entry.total_term_freq.to_string()).right_aligned()),
                ])
            })
            .collect();
        rows.extend(tail_row(terms.next.is_some(), terms.error.as_deref()));
        let widths = [
            Constraint::Fill(1),
            Constraint::Length(number_width(
                terms.entries.iter().map(|entry| i64::from(entry.doc_freq)),
            )),
            Constraint::Length(number_width(
                terms.entries.iter().map(|entry| entry.total_term_freq),
            )),
        ];
        terms.table.select(Some(terms.selected));
        let table = Table::new(rows, widths)
            .header(header)
            .column_spacing(COLUMN_SPACING)
            .row_highlight_style(highlight(focused))
            .block(block);
        frame.render_stateful_widget(table, area, &mut terms.table);
    }

    fn render_postings(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Column::Postings;
        let Some(postings) = &mut self.postings else {
            frame.render_widget(panel(" Postings ".to_owned(), focused), area);
            return;
        };
        let title = format!(" Postings ({}) ", postings.doc_freq);
        let block = panel(title, focused);
        if postings.postings.is_empty() {
            let text = match &postings.error {
                Some(error) => format!("Cannot read the postings: {error}"),
                None => "No postings.".to_owned(),
            };
            frame.render_widget(
                Paragraph::new(text).wrap(Wrap { trim: false }).block(block),
                area,
            );
            return;
        }
        let header = Row::new([
            Cell::from(Line::from("Doc").right_aligned()),
            Cell::from(Line::from("Freq").right_aligned()),
            Cell::from(""),
        ])
        .style(Style::new().add_modifier(Modifier::BOLD));
        let mut rows: Vec<Row> = postings
            .postings
            .iter()
            .map(|posting| {
                Row::new([
                    Cell::from(Line::from(posting.doc_id.to_string()).right_aligned()),
                    Cell::from(
                        Line::from(posting.freq.map_or("-".to_owned(), |freq| freq.to_string()))
                            .right_aligned(),
                    ),
                    if posting.deleted {
                        Cell::from("deleted").style(Style::new().fg(Color::Red))
                    } else {
                        Cell::from("")
                    },
                ])
            })
            .collect();
        rows.extend(tail_row(
            postings.next_doc.is_some(),
            postings.error.as_deref(),
        ));
        let widths = [
            Constraint::Length(number_width(
                postings
                    .postings
                    .iter()
                    .map(|posting| i64::from(posting.doc_id)),
            )),
            Constraint::Length(4),
            Constraint::Fill(1),
        ];
        postings.table.select(Some(postings.selected));
        let table = Table::new(rows, widths)
            .header(header)
            .column_spacing(COLUMN_SPACING)
            .row_highlight_style(highlight(focused))
            .block(block);
        frame.render_stateful_widget(table, area, &mut postings.table);
    }
}

/// The selection after a navigation key in a list of `len` rows; `None` for any other key.
fn moved(selected: usize, len: usize, key: KeyCode) -> Option<usize> {
    let last = len.saturating_sub(1);
    let row = match key {
        KeyCode::Down | KeyCode::Char('j') => selected + 1,
        KeyCode::Up | KeyCode::Char('k') => selected.saturating_sub(1),
        KeyCode::PageDown => selected + PAGE,
        KeyCode::PageUp => selected.saturating_sub(PAGE),
        KeyCode::Home | KeyCode::Char('g') => 0,
        KeyCode::End | KeyCode::Char('G') => last,
        _ => return None,
    };
    Some(row.min(last))
}

/// The row after the last one: `…` while there are more, or why the next page failed.
fn tail_row(more: bool, error: Option<&str>) -> Option<Row<'static>> {
    let text = match error {
        Some(error) => format!("Cannot read more: {error}"),
        None if more => "…".to_owned(),
        None => return None,
    };
    Some(Row::new([Cell::from(text)]).style(Style::new().add_modifier(Modifier::DIM)))
}

/// The width of a right-aligned number column: the widest number, or the 4 of its title.
fn number_width(numbers: impl Iterator<Item = i64>) -> u16 {
    numbers
        .map(|number| number.to_string().len())
        .max()
        .unwrap_or_default()
        .max(4) as u16
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::index::{DocValuesType, TermStats};

    fn field(name: &str, number: i32, index_options: IndexOptions) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number,
            index_options,
            has_norms: false,
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

    /// `id` and `body` have postings, `rank` has none.
    fn terms() -> Terms {
        Terms::new(vec![
            field("id", 0, IndexOptions::Docs),
            field("rank", 1, IndexOptions::None),
            field("body", 2, IndexOptions::DocsAndFreqsAndPositions),
        ])
    }

    fn text(value: &str) -> BytesValue {
        BytesValue {
            value: value.to_owned(),
            binary: false,
        }
    }

    fn entry(term: &str, doc_freq: i32) -> TermEntry {
        TermEntry {
            term: term.to_owned(),
            binary: false,
            doc_freq,
            total_term_freq: i64::from(doc_freq) * 2,
        }
    }

    fn page(terms: &[&str], next: Option<&str>) -> GetTermsResult {
        GetTermsResult {
            terms: terms.iter().map(|term| entry(term, 3)).collect(),
            next: next.map(text),
        }
    }

    fn posting(doc_id: i32, deleted: bool) -> Posting {
        Posting {
            doc_id,
            deleted,
            freq: Some(2),
        }
    }

    fn postings(doc_ids: &[i32], next_doc: Option<i32>) -> GetPostingsResult {
        GetPostingsResult {
            doc_freq: 5,
            total_term_freq: 10,
            postings: doc_ids
                .iter()
                .map(|&doc_id| posting(doc_id, false))
                .collect(),
            next_doc,
        }
    }

    fn terms_request(field: &str, from: Option<&str>, more: bool) -> Option<Request> {
        Some(Request::Terms {
            field: field.to_owned(),
            from: from.map(text),
            more,
        })
    }

    fn postings_request(field: &str, term: &str, from_doc: i32, more: bool) -> Option<Request> {
        Some(Request::Postings {
            field: field.to_owned(),
            term: text(term),
            from_doc,
            more,
        })
    }

    fn hit(term: &str, doc_id: i32, index: usize) -> Option<Request> {
        Some(Request::Document(TermHit {
            field: "id".to_owned(),
            term: text(term),
            doc_id,
            index,
            doc_freq: 5,
            freq: Some(2),
        }))
    }

    /// The terms of `id` and the postings of its first term, `a`, are in.
    fn loaded() -> Terms {
        let mut terms = terms();
        assert_eq!(terms.initial_request(), terms_request("id", None, false));
        assert_eq!(
            terms.terms_loaded("id", false, page(&["a", "b", "c"], Some("d"))),
            postings_request("id", "a", 0, false)
        );
        assert_eq!(
            terms.postings_loaded("id", &text("a"), false, postings(&[1, 4], Some(7))),
            None
        );
        terms
    }

    fn render(terms: &mut Terms, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| terms.render(frame, frame.area()))
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
    fn lists_the_fields_with_postings_the_terms_and_the_postings() {
        let mut terms = loaded();

        let screen = render(&mut terms, 100, 12);
        assert!(screen.contains("┃id"), "{screen}");
        assert!(screen.contains("┃body"), "{screen}");
        assert!(!screen.contains("rank"), "{screen}");
        assert!(screen.contains("id (/ seeks)"), "{screen}");
        assert!(screen.contains("│a "), "{screen}");
        assert!(screen.contains("3     6│"), "{screen}");
        assert!(screen.contains("Postings (5)"), "{screen}");
        assert!(screen.contains("1     2"), "{screen}");
        // More terms and postings follow.
        assert_eq!(screen.matches('…').count(), 2, "{screen}");
    }

    #[test]
    fn another_field_fetches_its_terms() {
        let mut terms = loaded();

        assert_eq!(
            terms.handle_key(KeyCode::Down),
            terms_request("body", None, false)
        );
        // The postings of the other field's term are gone.
        assert!(terms.postings.is_none());
        assert_eq!(terms.handle_key(KeyCode::Down), None);
    }

    #[test]
    fn fetches_the_next_page_at_the_last_row() {
        let mut terms = loaded();
        terms.handle_key(KeyCode::Right);

        assert_eq!(
            terms.handle_key(KeyCode::Down),
            postings_request("id", "b", 0, false)
        );
        terms.postings_loaded("id", &text("b"), false, postings(&[2], None));
        assert_eq!(
            terms.handle_key(KeyCode::End),
            terms_request("id", Some("d"), true)
        );
        // The page continues the list; the selection stays on "c", whose postings follow.
        assert_eq!(
            terms.terms_loaded("id", true, page(&["d", "e"], None)),
            postings_request("id", "c", 0, false)
        );
        terms.postings_loaded("id", &text("c"), false, postings(&[3], None));
        assert_eq!(
            terms.handle_key(KeyCode::End),
            postings_request("id", "e", 0, false)
        );
        let screen = render(&mut terms, 100, 12);
        assert!(!screen.contains('…'), "{screen}");
    }

    #[test]
    fn fetches_more_postings_at_the_last_row() {
        let mut terms = loaded();
        terms.handle_key(KeyCode::Tab);
        terms.handle_key(KeyCode::Tab);

        assert_eq!(
            terms.handle_key(KeyCode::Down),
            postings_request("id", "a", 7, true)
        );
        assert_eq!(
            terms.postings_loaded("id", &text("a"), true, postings(&[7], None)),
            None
        );
        assert_eq!(terms.postings.as_ref().unwrap().postings.len(), 3);
    }

    #[test]
    fn enter_shows_the_selected_document() {
        let mut terms = loaded();
        assert_eq!(terms.handle_key(KeyCode::Enter), None);
        assert_eq!(terms.handle_key(KeyCode::Enter), None);
        terms.handle_key(KeyCode::Down);

        assert_eq!(terms.handle_key(KeyCode::Enter), hit("a", 4, 1));
    }

    #[test]
    fn steps_through_the_postings_across_pages() {
        let mut terms = loaded();

        assert_eq!(terms.step(false), None);
        assert_eq!(terms.step(true), hit("a", 4, 1));
        assert_eq!(terms.step(true), postings_request("id", "a", 7, true));
        assert_eq!(
            terms.postings_loaded("id", &text("a"), true, postings(&[7], None)),
            hit("a", 7, 2)
        );
        assert_eq!(terms.step(true), None);
        assert_eq!(terms.step(false), hit("a", 4, 1));
    }

    #[test]
    fn seeks_while_typing() {
        let mut terms = loaded();

        assert_eq!(terms.handle_key(KeyCode::Char('/')), None);
        assert!(terms.prompting());
        assert_eq!(
            terms.handle_key(KeyCode::Char('q')),
            terms_request("id", Some("q"), false)
        );
        assert_eq!(
            terms.terms_loaded("id", false, page(&["quick"], None)),
            postings_request("id", "quick", 0, false)
        );
        let screen = render(&mut terms, 100, 12);
        assert!(screen.contains("id from q▏"), "{screen}");

        assert_eq!(terms.handle_key(KeyCode::Enter), None);
        assert!(!terms.prompting());
        assert!(render(&mut terms, 100, 12).contains("id from q (/ seeks)"));

        // An empty text starts from the first term again.
        terms.handle_key(KeyCode::Char('/'));
        assert_eq!(
            terms.handle_key(KeyCode::Backspace),
            terms_request("id", None, false)
        );
        terms.handle_key(KeyCode::Esc);
        terms.terms_loaded("id", false, page(&[], None));
        assert!(render(&mut terms, 100, 12).contains("The field has no terms."));
    }

    #[test]
    fn shows_why_a_page_failed_and_does_not_fetch_it_again() {
        let mut terms = loaded();
        terms.handle_key(KeyCode::Right);
        terms.handle_key(KeyCode::Down);
        terms.postings_loaded("id", &text("b"), false, postings(&[2], None));
        assert_eq!(
            terms.handle_key(KeyCode::End),
            terms_request("id", Some("d"), true)
        );

        assert_eq!(
            terms.terms_failed("id", true, "broken".to_owned()),
            postings_request("id", "c", 0, false)
        );
        terms.postings_failed("id", &text("c"), false, "gone".to_owned());

        let screen = render(&mut terms, 100, 12);
        assert!(screen.contains("Cannot read more: broken"), "{screen}");
        assert!(screen.contains("Cannot read the postings:"), "{screen}");
        assert!(screen.contains("│gone"), "{screen}");
        assert_eq!(terms.handle_key(KeyCode::End), None);
    }

    #[test]
    fn an_index_without_postings_has_nothing_to_fetch() {
        let mut terms = Terms::new(vec![field("rank", 0, IndexOptions::None)]);

        assert_eq!(terms.initial_request(), None);
        assert_eq!(terms.handle_key(KeyCode::Char('/')), None);
        assert!(!terms.prompting());
        assert!(render(&mut terms, 100, 12).contains("No field has postings."));
    }

    #[test]
    fn browses_a_term_of_another_field() {
        let mut terms = loaded();

        assert_eq!(
            terms.browse("body", text("quick")),
            terms_request("body", Some("quick"), false)
        );
        assert_eq!(
            terms.terms_loaded("body", false, page(&["quick", "red"], None)),
            Some(Request::Postings {
                field: "body".to_owned(),
                term: text("quick"),
                from_doc: 0,
                more: false,
            })
        );
        terms.postings_loaded("body", &text("quick"), false, postings(&[9], None));
        let screen = render(&mut terms, 100, 12);
        assert!(screen.contains("body from quick (/ seeks)"), "{screen}");
        // The postings have the focus.
        assert_eq!(
            terms.handle_key(KeyCode::Enter),
            Some(Request::Document(TermHit {
                field: "body".to_owned(),
                term: text("quick"),
                doc_id: 9,
                index: 0,
                doc_freq: 5,
                freq: Some(2),
            }))
        );
        assert_eq!(terms.browse("rank", text("1")), None);
    }
}
