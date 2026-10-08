//! The overview screen: what `getIndexStats` reports about the open index, with a scrollable
//! table of its fields, and the term counts and top terms of the fields that were scanned.

use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};
use std::path::Path;

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, BorderType, Cell, Paragraph, Row, Table, TableState, Wrap};

use crate::protocol::document::BytesValue;
use crate::protocol::index::{
    DocValuesType, GetIndexStatsResult, IndexCommitStats, IndexField, IndexOptions, SkippedIndex,
};
use crate::protocol::terms::{FieldTerms, TopTerm};
use crate::text::bytes_text;

/// Rows that PageUp and PageDown move.
const PAGE: u16 = 10;

/// How many top terms a scan asks for at first, as in Luke.
pub const DEFAULT_TOP_TERMS: i32 = 50;
/// The numbers of top terms that `+` and `-` step through; the server allows up to 1000.
const TOP_TERMS_STEPS: [i32; 7] = [10, 20, 50, 100, 200, 500, 1000];

/// The next number of top terms up or down from `top_n`, or `top_n` at either end.
pub fn step_top_terms(top_n: i32, up: bool) -> i32 {
    let next = if up {
        TOP_TERMS_STEPS.iter().find(|&&step| step > top_n)
    } else {
        TOP_TERMS_STEPS.iter().rev().find(|&&step| step < top_n)
    };
    next.copied().unwrap_or(top_n)
}

/// From this width on, the terms panel is next to the fields instead of below them.
const SIDE_BY_SIDE_WIDTH: u16 = 130;
/// The width of the terms panel next to the fields.
const TERMS_PANEL_WIDTH: u16 = 46;

/// Where a field's term scan is.
#[derive(Debug)]
pub enum FieldScan {
    /// Waits for the scan before it.
    Queued,
    Running {
        terms_scanned: i64,
    },
    Done {
        terms: FieldTerms,
        /// The number of top terms the scan asked for.
        top_n: i32,
        /// The selection in the top terms.
        table: TableState,
    },
    Failed(String),
}

impl FieldScan {
    pub fn done(terms: FieldTerms, top_n: i32) -> Self {
        let selected = (!terms.top_terms.is_empty()).then_some(0);
        FieldScan::Done {
            terms,
            top_n,
            table: TableState::new().with_selected(selected),
        }
    }
}

/// Whether a scan that asked for `asked` top terms has the top `top_n`: it asked for as many, or
/// it got every term of the field.
fn has_top_terms(terms: &FieldTerms, asked: i32, top_n: i32) -> bool {
    asked >= top_n || terms.top_terms.len() as i64 >= terms.unique_terms
}

/// What the field table is sorted by.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SortKey {
    Number,
    Name,
    /// The unique term count; fields without one come last in either direction.
    Terms,
}

impl SortKey {
    const ALL: [SortKey; 3] = [SortKey::Number, SortKey::Name, SortKey::Terms];

    fn next(self) -> Self {
        match self {
            SortKey::Number => SortKey::Name,
            SortKey::Name => SortKey::Terms,
            SortKey::Terms => SortKey::Number,
        }
    }

    /// The direction a key starts in: the largest term counts first, everything else ascending.
    fn starts_descending(self) -> bool {
        self == SortKey::Terms
    }

    /// The column whose header shows the sort.
    fn column(self) -> usize {
        match self {
            SortKey::Number => NUMBER_COLUMN,
            SortKey::Name => NAME_COLUMN,
            SortKey::Terms => TERMS_COLUMN,
        }
    }
}

/// The table that the navigation keys move.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Fields,
    Terms,
}

pub struct Overview {
    stats: GetIndexStatsResult,
    /// The index directories that were found below the opened path but left out (ADR 0021).
    skipped: Vec<SkippedIndex>,
    /// The fields in table order, as indexes into `stats.fields`.
    order: Vec<usize>,
    /// The text of every row's cells in table order, formatted again when a scan or the sort
    /// changes.
    field_rows: Vec<[String; COLUMNS.len()]>,
    /// The widest cell of each column, header included.
    column_widths: [u16; COLUMNS.len()],
    fields: TableState,
    sort: SortKey,
    descending: bool,
    /// Term scans by field name; a field that was never scanned has none.
    scans: HashMap<String, FieldScan>,
    /// How many top terms to show, and to scan for.
    top_n: i32,
    focus: Focus,
}

impl Overview {
    pub fn new(stats: GetIndexStatsResult, top_n: i32) -> Self {
        let selected = (!stats.fields.is_empty()).then_some(0);
        let mut overview = Self {
            stats,
            skipped: Vec::new(),
            order: Vec::new(),
            field_rows: Vec::new(),
            column_widths: [0; COLUMNS.len()],
            fields: TableState::new().with_selected(selected),
            sort: SortKey::Number,
            descending: false,
            scans: HashMap::new(),
            top_n,
            focus: Focus::Fields,
        };
        overview.format_rows();
        overview
    }

    /// Lists the index directories that were found below the opened path but left out.
    pub fn with_skipped(mut self, skipped: Vec<SkippedIndex>) -> Self {
        self.skipped = skipped;
        self
    }

    /// The statistics shown.
    pub fn stats(&self) -> &GetIndexStatsResult {
        &self.stats
    }

    /// The overview of the reopened index (ADR 0019): sorted as before, with the same field
    /// selected if it is still there. The scans are not kept; their counts are stale. The
    /// skipped index directories stay, as a reopen doesn't look for indexes again.
    pub fn reopened(&self, stats: GetIndexStatsResult, top_n: i32) -> Self {
        let selected = self.selected_field().map(|field| field.name.clone());
        let mut overview = Self::new(stats, top_n).with_skipped(self.skipped.clone());
        overview.sort = self.sort;
        overview.descending = self.descending;
        overview.format_rows();
        let fields = &overview.stats.fields;
        let row = selected
            .and_then(|name| {
                overview
                    .order
                    .iter()
                    .position(|&index| fields[index].name == name)
            })
            .or((!fields.is_empty()).then_some(0));
        overview.fields.select(row);
        overview
    }

    /// Sorts the rows and formats their cells. The selection stays on its field.
    fn format_rows(&mut self) {
        let selected = self.selected_index();
        self.order = self.sorted_order();
        if let Some(selected) = selected {
            self.fields
                .select(self.order.iter().position(|&index| index == selected));
        }
        let total = self.total_terms();
        self.field_rows = self
            .order
            .iter()
            .map(|&index| {
                let field = &self.stats.fields[index];
                field_cells(field, self.scans.get(&field.name), total)
            })
            .collect();
        self.column_widths = std::array::from_fn(|column| {
            let cells = self
                .field_rows
                .iter()
                .map(|row| Line::from(row[column].as_str()).width());
            // Room for the sort marker, so that the columns don't move when the sort changes.
            let marker = if SortKey::ALL.iter().any(|key| key.column() == column) {
                SORT_MARKER_WIDTH
            } else {
                0
            };
            let header = Line::from(COLUMNS[column].title).width() + marker;
            cells.chain([header]).max().unwrap_or_default() as u16
        });
    }

    fn sorted_order(&self) -> Vec<usize> {
        let fields = &self.stats.fields;
        let direction = |ordering: Ordering| {
            if self.descending {
                ordering.reverse()
            } else {
                ordering
            }
        };
        let mut order: Vec<usize> = (0..fields.len()).collect();
        order.sort_by(|&a, &b| {
            let (a, b) = (&fields[a], &fields[b]);
            let ordering = match self.sort {
                SortKey::Number => direction(a.number.cmp(&b.number)),
                SortKey::Name => direction(a.name.cmp(&b.name)),
                SortKey::Terms => match (self.term_count(a), self.term_count(b)) {
                    (Some(a), Some(b)) => direction(a.cmp(&b)),
                    (Some(_), None) => Ordering::Less,
                    (None, Some(_)) => Ordering::Greater,
                    (None, None) => Ordering::Equal,
                },
            };
            ordering.then(a.number.cmp(&b.number))
        });
        order
    }

    /// The unique term count of a scanned field.
    fn term_count(&self, field: &IndexField) -> Option<i64> {
        match self.scans.get(&field.name) {
            Some(FieldScan::Done { terms, .. }) => Some(terms.unique_terms),
            _ => None,
        }
    }

    /// The number of terms of all fields, once every field with postings has been scanned.
    fn total_terms(&self) -> Option<i64> {
        let mut indexed = self
            .stats
            .fields
            .iter()
            .filter(|field| field.terms.is_some())
            .peekable();
        indexed.peek()?;
        indexed.map(|field| self.term_count(field)).sum()
    }

    /// Changes how many top terms are shown. A scan for fewer can be repeated, a scan for more is
    /// cut short.
    pub fn set_top_n(&mut self, top_n: i32) {
        self.top_n = top_n;
    }

    /// The fields of the index, by field number.
    pub fn fields(&self) -> &[IndexField] {
        &self.stats.fields
    }

    /// The field under the selection.
    pub fn selected_field(&self) -> Option<&IndexField> {
        self.selected_index().map(|index| &self.stats.fields[index])
    }

    /// The index into `stats.fields` of the field under the selection.
    fn selected_index(&self) -> Option<usize> {
        let last = self.order.len().checked_sub(1)?;
        // The table clamps the selection only when it renders.
        self.fields
            .selected()
            .map(|selected| self.order[selected.min(last)])
    }

    /// The field and the top term under the selection, while the top terms have the focus.
    pub fn selected_top_term(&self) -> Option<(String, BytesValue)> {
        if self.focus != Focus::Terms {
            return None;
        }
        let field = self.selected_field()?;
        let Some(FieldScan::Done { terms, table, .. }) = self.scans.get(&field.name) else {
            return None;
        };
        let shown = terms.top_terms.len().min(self.top_n as usize);
        // The table clamps the selection only when it renders.
        let top = &terms.top_terms[table.selected()?.min(shown.checked_sub(1)?)];
        Some((
            field.name.clone(),
            BytesValue {
                value: top.term.clone(),
                binary: top.binary,
            },
        ))
    }

    pub fn scan(&self, field: &str) -> Option<&FieldScan> {
        self.scans.get(field)
    }

    /// Sets or, with `None`, forgets the term scan of `field`.
    pub fn set_scan(&mut self, field: &str, scan: Option<FieldScan>) {
        match scan {
            Some(scan) => self.scans.insert(field.to_owned(), scan),
            None => self.scans.remove(field),
        };
        self.format_rows();
    }

    /// Whether `field` can be scanned: it has postings, and no scan, a failed one, or one for
    /// fewer than the top N terms.
    pub fn needs_scan(&self, field: &IndexField) -> bool {
        field.terms.is_some()
            && match self.scans.get(&field.name) {
                None | Some(FieldScan::Failed(_)) => true,
                Some(FieldScan::Done { terms, top_n, .. }) => {
                    !has_top_terms(terms, *top_n, self.top_n)
                }
                Some(FieldScan::Queued | FieldScan::Running { .. }) => false,
            }
    }

    /// The fields that [need a scan](Self::needs_scan), in field order.
    pub fn fields_to_scan(&self) -> Vec<String> {
        self.stats
            .fields
            .iter()
            .filter(|field| self.needs_scan(field))
            .map(|field| field.name.clone())
            .collect()
    }

    /// Moves the selection in the focused table, switches the focus with Tab, cycles the sort
    /// column with `s` and flips its direction with `S`. Returns whether the key was used.
    pub fn handle_key(&mut self, key: KeyCode) -> bool {
        match key {
            KeyCode::Tab => {
                self.focus = match self.focus {
                    Focus::Fields => Focus::Terms,
                    Focus::Terms => Focus::Fields,
                };
                return true;
            }
            KeyCode::Char('s') => {
                self.sort = self.sort.next();
                self.descending = self.sort.starts_descending();
                self.format_rows();
                return true;
            }
            KeyCode::Char('S') => {
                self.descending = !self.descending;
                self.format_rows();
                return true;
            }
            _ => {}
        }
        let selected = self.selected_field().map(|field| field.name.clone());
        let table = match (
            self.focus,
            selected.and_then(|name| self.scans.get_mut(&name)),
        ) {
            (Focus::Terms, Some(FieldScan::Done { table, .. })) => table,
            _ => &mut self.fields,
        };
        match key {
            KeyCode::Down | KeyCode::Char('j') => table.scroll_down_by(1),
            KeyCode::Up | KeyCode::Char('k') => table.scroll_up_by(1),
            KeyCode::PageDown => table.scroll_down_by(PAGE),
            KeyCode::PageUp => table.scroll_up_by(PAGE),
            KeyCode::Home | KeyCode::Char('g') => table.select_first(),
            KeyCode::End | KeyCode::Char('G') => table.select_last(),
            _ => return false,
        }
        true
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let summary = summary_lines(&self.stats, &self.skipped);
        let [top, bottom] = Layout::vertical([
            // The lines plus the block's top and bottom border.
            Constraint::Length(summary.len() as u16 + 2),
            Constraint::Fill(1),
        ])
        .areas(area);
        frame.render_widget(
            Paragraph::new(summary).block(Block::bordered().title(" Index ")),
            top,
        );

        let [fields_area, terms_area] = if bottom.width >= SIDE_BY_SIDE_WIDTH {
            Layout::horizontal([Constraint::Fill(1), Constraint::Length(TERMS_PANEL_WIDTH)])
                .areas(bottom)
        } else {
            Layout::vertical([Constraint::Fill(1), Constraint::Percentage(45)]).areas(bottom)
        };
        self.render_fields(frame, fields_area);
        self.render_terms(frame, terms_area);
    }

    fn render_fields(&mut self, frame: &mut Frame, area: Rect) {
        // Inside the block's left and right border.
        let shown = shown_columns(&self.column_widths, area.width.saturating_sub(2));
        let titles: Vec<String> = COLUMNS
            .iter()
            .enumerate()
            .map(|(i, column)| {
                if i == self.sort.column() {
                    let marker = if self.descending { '▼' } else { '▲' };
                    format!("{} {marker}", column.title)
                } else {
                    column.title.to_owned()
                }
            })
            .collect();
        let header = Row::new(
            COLUMNS[..shown]
                .iter()
                .zip(&titles)
                .map(|(column, title)| cell(column, title)),
        )
        .style(Style::new().add_modifier(Modifier::BOLD));
        let rows = self.field_rows.iter().map(|texts| {
            Row::new(
                COLUMNS[..shown]
                    .iter()
                    .zip(texts)
                    .map(|(column, text)| cell(column, text)),
            )
        });
        // Every column gets the width of its widest cell, and the name the rest of the line.
        let widths = self.column_widths[..shown]
            .iter()
            .enumerate()
            .map(|(i, &width)| {
                if i == NAME_COLUMN {
                    Constraint::Min(width.min(NAME_MIN_WIDTH))
                } else {
                    Constraint::Length(width)
                }
            });
        let table = Table::new(rows, widths)
            .header(header)
            .column_spacing(COLUMN_SPACING)
            .row_highlight_style(highlight(self.focus == Focus::Fields))
            .block(panel(self.fields_title(), self.focus == Focus::Fields));
        frame.render_stateful_widget(table, area, &mut self.fields);
    }

    /// The number of fields, and the total number of terms once every field with postings has
    /// been scanned.
    fn fields_title(&self) -> String {
        let count = self.stats.fields.len();
        match self.total_terms() {
            Some(total) => format!(" Fields ({count}, {total} terms) "),
            None => format!(" Fields ({count}) "),
        }
    }

    fn render_terms(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Terms;
        let Some(field) = self.selected_field() else {
            frame.render_widget(panel(" Terms ".to_owned(), focused), area);
            return;
        };
        let name = field.name.clone();
        let has_postings = field.terms.is_some();
        let top_n = self.top_n;
        let mut title = format!(" Terms: {name} ");
        let message = match self.scans.get_mut(&name) {
            Some(FieldScan::Done {
                terms,
                top_n: asked,
                table,
            }) => {
                let top_terms = &terms.top_terms[..terms.top_terms.len().min(top_n as usize)];
                title = format!(
                    " {name}: {} terms, top {} ",
                    terms.unique_terms,
                    top_terms.len()
                );
                if !has_top_terms(terms, *asked, top_n) {
                    title.push_str(&format!("(⏎ top {top_n}) "));
                }
                if !top_terms.is_empty() {
                    let widget = top_terms_table(top_terms)
                        .row_highlight_style(highlight(focused))
                        .block(panel(title, focused));
                    frame.render_stateful_widget(widget, area, table);
                    return;
                }
                vec![Line::from("The field has no terms.")]
            }
            Some(FieldScan::Running { terms_scanned }) => vec![
                Line::from(format!("Scanning… {terms_scanned} terms so far.")),
                Line::from("Esc cancels."),
            ],
            Some(FieldScan::Queued) => vec![
                Line::from("Queued after the running scan."),
                Line::from("Esc cancels."),
            ],
            Some(FieldScan::Failed(error)) => vec![
                Line::from(format!("The scan failed: {error}")),
                Line::from("Enter tries again."),
            ],
            None if has_postings => vec![
                Line::from(format!("Enter counts the terms and finds the top {top_n}.")),
                Line::from("a does so for every field."),
            ],
            None => vec![Line::from("No postings: the field is not indexed.")],
        };
        frame.render_widget(
            Paragraph::new(message)
                .wrap(Wrap { trim: true })
                .block(panel(title, focused)),
            area,
        );
    }
}

/// A bordered block; the focused one has a thick border.
pub(crate) fn panel(title: String, focused: bool) -> Block<'static> {
    let block = Block::bordered().title(title);
    if focused {
        block.border_type(BorderType::Thick)
    } else {
        block
    }
}

/// The selected row: reversed in the focused table, bold in the other.
pub(crate) fn highlight(focused: bool) -> Style {
    Style::new().add_modifier(if focused {
        Modifier::REVERSED
    } else {
        Modifier::BOLD
    })
}

fn top_terms_table(top_terms: &[TopTerm]) -> Table<'static> {
    let rows: Vec<[String; 4]> = top_terms
        .iter()
        .enumerate()
        .map(|(rank, term)| {
            [
                (rank + 1).to_string(),
                term.doc_freq.to_string(),
                term.total_term_freq.to_string(),
                term_text(term),
            ]
        })
        .collect();
    let titles = ["#", "Docs", "Freq", "Term"];
    let width = |column: usize| {
        rows.iter()
            .map(|row| row[column].len())
            .chain([titles[column].len()])
            .max()
            .unwrap_or_default() as u16
    };
    let widths = [
        Constraint::Length(width(0)),
        Constraint::Length(width(1)),
        Constraint::Length(width(2)),
        Constraint::Fill(1),
    ];
    let right = |text: String| Cell::from(Line::from(text).right_aligned());
    let header = Row::new(titles.map(|title| {
        if title == "Term" {
            Cell::from(title)
        } else {
            right(title.to_owned())
        }
    }))
    .style(Style::new().add_modifier(Modifier::BOLD));
    let rows = rows.into_iter().map(|[rank, docs, freq, term]| {
        Row::new([right(rank), right(docs), right(freq), Cell::from(term)])
    });
    Table::new(rows, widths)
        .header(header)
        .column_spacing(COLUMN_SPACING)
}

/// The term as shown: control characters escaped, and a binary term as `0x` and its hex.
fn term_text(term: &TopTerm) -> String {
    bytes_text(&term.term, term.binary)
}

/// The index panel shows at most this many index directories, and as many skipped ones.
const SUMMARY_INDEXES: usize = 8;

fn summary_lines(stats: &GetIndexStatsResult, skipped: &[SkippedIndex]) -> Vec<Line<'static>> {
    let deleted = stats.max_doc - stats.num_docs;
    let merged = if stats.segment_count == 1 {
        " (fully merged)"
    } else {
        ""
    };
    let documents = format!(
        "{} live, {deleted} deleted, {} max",
        stats.num_docs, stats.max_doc
    );
    let mut lines: Vec<(&str, String)> = vec![
        ("Path", stats.path.clone()),
        ("Directory", stats.directory_impl.clone()),
    ];
    match stats.indexes.as_slice() {
        [index] if skipped.is_empty() => lines.extend([
            (
                "Commit",
                format!(
                    "{} (generation {}, version {})",
                    index.segments_file, index.generation, index.version
                ),
            ),
            ("Segments", format!("{}{merged}", stats.segment_count)),
            ("Documents", documents),
            ("Lucene", lucene(index)),
            ("User data", user_data(&index.user_data)),
        ]),
        indexes => {
            let count = if skipped.is_empty() {
                indexes.len().to_string()
            } else {
                format!("{} ({} skipped)", indexes.len(), skipped.len())
            };
            lines.extend([
                ("Indexes", count),
                ("Segments", format!("{}{merged}", stats.segment_count)),
                ("Documents", documents),
            ]);
        }
    }
    let mut lines: Vec<Line<'static>> = lines
        .into_iter()
        .map(|(label, value)| label_line(label, value))
        .collect();
    if stats.indexes.len() > 1 || !skipped.is_empty() {
        lines.extend(index_lines(stats, skipped));
    }
    lines
}

/// One line per index directory, below the opened path, then one per skipped one with why.
fn index_lines(stats: &GetIndexStatsResult, skipped: &[SkippedIndex]) -> Vec<Line<'static>> {
    let names: Vec<String> = stats
        .indexes
        .iter()
        .map(|index| relative(&stats.path, &index.path))
        .chain(
            skipped
                .iter()
                .map(|index| relative(&stats.path, &index.path)),
        )
        .collect();
    let width = names
        .iter()
        .map(|name| name.chars().count())
        .max()
        .unwrap_or_default();
    let mut names = names.into_iter();
    let mut lines = Vec::new();
    for (i, index) in stats.indexes.iter().enumerate() {
        let name = names.next().unwrap_or_default();
        if i < SUMMARY_INDEXES {
            lines.push(Line::from(format!(
                "  {name:<width$}  {}  {} live, {} max  {}",
                index.segments_file,
                index.num_docs,
                index.max_doc,
                lucene(index)
            )));
        }
    }
    if stats.indexes.len() > SUMMARY_INDEXES {
        lines.push(Line::from(format!(
            "  … and {} more",
            stats.indexes.len() - SUMMARY_INDEXES
        )));
    }
    for (i, index) in skipped.iter().enumerate() {
        let name = names.next().unwrap_or_default();
        if i < SUMMARY_INDEXES {
            lines.push(Line::styled(
                format!("  {name:<width$}  skipped: {}", index.message),
                Style::new().fg(Color::Red),
            ));
        }
    }
    if skipped.len() > SUMMARY_INDEXES {
        lines.push(Line::styled(
            format!("  … and {} more skipped", skipped.len() - SUMMARY_INDEXES),
            Style::new().fg(Color::Red),
        ));
    }
    lines
}

/// `path` below `root`, or `path` itself if it isn't below it; `.` for `root` itself.
pub fn relative(root: &str, path: &str) -> String {
    match Path::new(path).strip_prefix(root) {
        Ok(rest) if rest.as_os_str().is_empty() => ".".to_owned(),
        Ok(rest) => rest.display().to_string(),
        Err(_) => path.to_owned(),
    }
}

fn lucene(index: &IndexCommitStats) -> String {
    let mut lucene = format!(
        "created by {}, commit written by {}",
        index.created_version_major, index.commit_lucene_version
    );
    if let Some(oldest) = &index.min_segment_lucene_version {
        lucene.push_str(&format!(", oldest segment by {oldest}"));
    }
    lucene
}

fn user_data(user_data: &BTreeMap<String, String>) -> String {
    if user_data.is_empty() {
        "-".to_owned()
    } else {
        user_data
            .iter()
            .map(|(key, value)| format!("{key}={value}"))
            .collect::<Vec<_>>()
            .join(", ")
    }
}

/// A line of the index panel: the label in bold, padded, then the value.
pub fn label_line(label: &str, value: String) -> Line<'static> {
    Line::from(vec![
        Span::styled(
            format!("{label:<10} "),
            Style::new().add_modifier(Modifier::BOLD),
        ),
        Span::raw(value),
    ])
}

struct Column {
    title: &'static str,
    right_aligned: bool,
}

const fn column(title: &'static str, right_aligned: bool) -> Column {
    Column {
        title,
        right_aligned,
    }
}

const COLUMNS: [Column; 12] = [
    column("#", true),
    column("Name", false),
    column("Postings", false),
    column("Flags", false),
    column("Doc values", false),
    column("Points", false),
    column("Vector", false),
    column("Terms", true),
    column("%", true),
    column("Docs", true),
    column("Σ doc freq", true),
    column("Σ term freq", true),
];

const NUMBER_COLUMN: usize = 0;
const NAME_COLUMN: usize = 1;
const TERMS_COLUMN: usize = 7;
/// A space and `▲` or `▼` after the title of the sorted column.
const SORT_MARKER_WIDTH: usize = 2;
const NAME_MIN_WIDTH: u16 = 16;
const COLUMN_SPACING: u16 = 2;

/// How many columns, from the left, fit into `available` cells. A column is shown whole or not
/// at all, so on a narrow terminal the columns on the right go first. The number and the name
/// are always shown; the name counts with at most `NAME_MIN_WIDTH`.
fn shown_columns(widths: &[u16], available: u16) -> usize {
    let mut used = 0;
    for (i, &width) in widths.iter().enumerate() {
        let width = if i == NAME_COLUMN {
            width.min(NAME_MIN_WIDTH)
        } else {
            width
        };
        let spacing = if i == 0 { 0 } else { COLUMN_SPACING };
        used += spacing + width;
        if used > available && i > NAME_COLUMN {
            return i;
        }
    }
    widths.len()
}

fn cell<'a>(column: &Column, text: &'a str) -> Cell<'a> {
    let line = Line::from(text);
    Cell::from(if column.right_aligned {
        line.right_aligned()
    } else {
        line
    })
}

/// The cells of a field's row. `total_terms` is the number of terms of all fields, once known.
fn field_cells(
    field: &IndexField,
    scan: Option<&FieldScan>,
    total_terms: Option<i64>,
) -> [String; COLUMNS.len()] {
    let points = if field.point_dimension_count == 0 {
        "-".to_owned()
    } else {
        format!("{}×{}B", field.point_dimension_count, field.point_num_bytes)
    };
    let vector = if field.vector_dimension == 0 {
        "-".to_owned()
    } else {
        format!("{}d", field.vector_dimension)
    };
    let (docs, sum_doc_freq, sum_total_term_freq) = match &field.terms {
        Some(terms) => (
            terms.doc_count.to_string(),
            terms.sum_doc_freq.to_string(),
            terms.sum_total_term_freq.to_string(),
        ),
        None => ("-".to_owned(), "-".to_owned(), "-".to_owned()),
    };
    let terms = match scan {
        _ if field.terms.is_none() => "-".to_owned(),
        None => "?".to_owned(),
        Some(FieldScan::Queued) => "queued".to_owned(),
        Some(FieldScan::Running { terms_scanned }) => format!("{terms_scanned}…"),
        Some(FieldScan::Done { terms, .. }) => terms.unique_terms.to_string(),
        Some(FieldScan::Failed(_)) => "failed".to_owned(),
    };
    // The share of all terms, as Luke shows it.
    let share = match (scan, total_terms) {
        _ if field.terms.is_none() => "-".to_owned(),
        (Some(FieldScan::Done { terms, .. }), Some(total)) if total > 0 => {
            format!("{:.2}", terms.unique_terms as f64 / total as f64 * 100.0)
        }
        (_, Some(_)) => "0.00".to_owned(),
        (_, None) => "?".to_owned(),
    };
    [
        field.number.to_string(),
        field.name.clone(),
        postings(field.index_options).to_owned(),
        flags(field),
        doc_values(field.doc_values_type).to_owned(),
        points,
        vector,
        terms,
        share,
        docs,
        sum_doc_freq,
        sum_total_term_freq,
    ]
}

pub(crate) fn postings(options: IndexOptions) -> &'static str {
    match options {
        IndexOptions::None => "-",
        IndexOptions::Docs => "docs",
        IndexOptions::DocsAndFreqs => "freqs",
        IndexOptions::DocsAndFreqsAndPositions => "positions",
        IndexOptions::DocsAndFreqsAndPositionsAndOffsets => "offsets",
        IndexOptions::DocsAndCustomFreqs => "custom freqs",
    }
}

/// `N` norms, `V` term vectors, `P` payloads; `-` where absent.
fn flags(field: &IndexField) -> String {
    [
        (field.has_norms, 'N'),
        (field.has_term_vectors, 'V'),
        (field.has_payloads, 'P'),
    ]
    .into_iter()
    .map(|(set, flag)| if set { flag } else { '-' })
    .collect()
}

pub(crate) fn doc_values(doc_values_type: DocValuesType) -> &'static str {
    match doc_values_type {
        DocValuesType::None => "-",
        DocValuesType::Numeric => "numeric",
        DocValuesType::Binary => "binary",
        DocValuesType::Sorted => "sorted",
        DocValuesType::SortedNumeric => "sorted numeric",
        DocValuesType::SortedSet => "sorted set",
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::index::TermStats;

    fn indexed(name: &str, number: i32) -> IndexField {
        IndexField {
            index_options: IndexOptions::Docs,
            terms: Some(TermStats {
                doc_count: 3,
                sum_doc_freq: 3,
                sum_total_term_freq: 3,
            }),
            ..field(name, number)
        }
    }

    fn top(term: &str, binary: bool, doc_freq: i32) -> TopTerm {
        TopTerm {
            term: term.to_owned(),
            binary,
            doc_freq,
            total_term_freq: i64::from(doc_freq) + 1,
        }
    }

    fn terms(field: &str, unique_terms: i64, top_terms: Vec<TopTerm>) -> FieldTerms {
        FieldTerms {
            field: field.to_owned(),
            unique_terms,
            top_terms,
        }
    }

    fn row<'a>(screen: &'a str, name: &str) -> &'a str {
        screen
            .lines()
            // The name cell is padded; a panel title is followed by its border.
            .find(|line| line.contains(&format!(" {name}  ")))
            .unwrap_or_else(|| panic!("no row {name:?} in\n{screen}"))
    }

    fn field(name: &str, number: i32) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number,
            index_options: IndexOptions::None,
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

    fn stats(fields: Vec<IndexField>) -> GetIndexStatsResult {
        GetIndexStatsResult {
            path: "/data/products".to_owned(),
            directory_impl: "MMapDirectory".to_owned(),
            segment_count: 1,
            num_docs: 2,
            max_doc: 3,
            indexes: vec![IndexCommitStats {
                path: "/data/products".to_owned(),
                doc_base: 0,
                segments_file: "segments_2".to_owned(),
                generation: 2,
                version: 7,
                segment_count: 1,
                num_docs: 2,
                max_doc: 3,
                created_version_major: 9,
                commit_lucene_version: "9.12.3".to_owned(),
                min_segment_lucene_version: Some("9.12.3".to_owned()),
                user_data: BTreeMap::from([("writer".to_owned(), "test".to_owned())]),
            }],
            fields,
        }
    }

    fn render(overview: &mut Overview, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| overview.render(frame, frame.area()))
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
    fn shows_summary_and_fields() {
        let body = IndexField {
            index_options: IndexOptions::DocsAndFreqsAndPositions,
            has_norms: true,
            has_term_vectors: true,
            terms: Some(TermStats {
                doc_count: 3,
                sum_doc_freq: 6,
                sum_total_term_freq: 9,
            }),
            ..field("body", 1)
        };
        let price = IndexField {
            doc_values_type: DocValuesType::SortedNumeric,
            point_dimension_count: 1,
            point_num_bytes: 4,
            ..field("price", 2)
        };
        let mut overview = Overview::new(stats(vec![field("id", 0), body, price]), 50);

        let screen = render(&mut overview, 120, 20);

        for expected in [
            "Path       /data/products",
            "Commit     segments_2 (generation 2, version 7)",
            "Segments   1 (fully merged)",
            "Documents  2 live, 1 deleted, 3 max",
            "Lucene     created by 9, commit written by 9.12.3, oldest segment by 9.12.3",
            "User data  writer=test",
            "Fields (3)",
        ] {
            assert!(screen.contains(expected), "{expected:?} in\n{screen}");
        }
        let body_row = screen.lines().find(|line| line.contains("body")).unwrap();
        for expected in ["positions", "NV-", "3", "6", "9"] {
            assert!(body_row.contains(expected), "{expected:?} in {body_row:?}");
        }
        let price_row = screen.lines().find(|line| line.contains("price")).unwrap();
        assert!(price_row.contains("sorted numeric"), "{price_row:?}");
        assert!(price_row.contains("1×4B"), "{price_row:?}");
    }

    #[test]
    fn moves_the_selection_within_the_fields() {
        let mut overview = Overview::new(stats((0..30).map(|i| field("f", i)).collect()), 50);
        render(&mut overview, 120, 30);
        assert_eq!(overview.fields.selected(), Some(0));

        assert!(overview.handle_key(KeyCode::Down));
        assert_eq!(overview.fields.selected(), Some(1));
        overview.handle_key(KeyCode::Up);
        overview.handle_key(KeyCode::Up);
        assert_eq!(overview.fields.selected(), Some(0));
        overview.handle_key(KeyCode::PageDown);
        assert_eq!(overview.fields.selected(), Some(10));
        overview.handle_key(KeyCode::End);
        // The table clamps the selection to the last row when it renders.
        render(&mut overview, 120, 30);
        assert_eq!(overview.fields.selected(), Some(29));
        assert!(!overview.handle_key(KeyCode::Char('x')));
    }

    #[test]
    fn drops_whole_columns_from_the_right_when_narrow() {
        // "#" 1, "Name" 20 (counts as 16), then 9 and 5, with 2 cells between columns.
        let widths = [1, 20, 9, 5];
        assert_eq!(shown_columns(&widths, 40), 4);
        assert_eq!(shown_columns(&widths, 1 + 2 + 16 + 2 + 9 + 2 + 5), 4);
        assert_eq!(shown_columns(&widths, 1 + 2 + 16 + 2 + 9 + 2 + 4), 3);
        assert_eq!(shown_columns(&widths, 1 + 2 + 16 + 2 + 8), 2);
        // The number and the name stay, however narrow.
        assert_eq!(shown_columns(&widths, 5), 2);
    }

    #[test]
    fn hides_columns_that_do_not_fit() {
        let mut overview = Overview::new(stats(vec![field("id", 0)]), 50);

        let screen = render(&mut overview, 60, 15);

        assert!(screen.contains("Postings"), "{screen}");
        assert!(!screen.contains("Σ term freq"), "{screen}");
    }

    #[test]
    fn handles_index_without_fields() {
        let mut overview = Overview::new(stats(Vec::new()), 50);

        let screen = render(&mut overview, 80, 15);

        assert!(screen.contains("Fields (0)"), "{screen}");
        assert_eq!(overview.fields.selected(), None);
    }

    #[test]
    fn shows_term_count_and_top_terms_next_to_the_fields() {
        let mut overview = Overview::new(stats(vec![indexed("body", 0), field("price", 1)]), 50);
        overview.set_scan(
            "body",
            Some(FieldScan::done(
                terms(
                    "body",
                    1234,
                    vec![
                        top("red", false, 7),
                        top("ff0041", true, 2),
                        top("a\nb", false, 1),
                    ],
                ),
                50,
            )),
        );

        let screen = render(&mut overview, 140, 30);

        assert!(row(&screen, "body").contains("1234"), "{screen}");
        for expected in [
            "Fields (2, 1234 terms)",
            "body: 1234 terms, top 3",
            "#  Docs  Freq  Term",
            "1     7     8  red",
            "2     2     3  0xff0041",
            "3     1     2  a\\nb",
        ] {
            assert!(screen.contains(expected), "{expected:?} in\n{screen}");
        }
    }

    #[test]
    fn puts_the_terms_below_the_fields_when_narrow() {
        let mut overview = Overview::new(stats(vec![indexed("body", 0)]), 50);

        let screen = render(&mut overview, 100, 30);

        let fields = screen.lines().position(|line| line.contains("Fields (1)"));
        let terms = screen.lines().position(|line| line.contains("Terms: body"));
        assert!(fields < terms, "{screen}");
    }

    #[test]
    fn terms_column_shows_the_scan() {
        let mut overview = Overview::new(
            stats(
                ["none", "queued", "running", "failed"]
                    .iter()
                    .zip(0..)
                    .map(|(name, number)| indexed(name, number))
                    .chain([field("price", 4)])
                    .collect(),
            ),
            50,
        );
        overview.set_scan("queued", Some(FieldScan::Queued));
        overview.set_scan(
            "running",
            Some(FieldScan::Running {
                terms_scanned: 1024,
            }),
        );
        overview.set_scan("failed", Some(FieldScan::Failed("boom".to_owned())));

        let screen = render(&mut overview, 140, 30);

        for (name, cell) in [
            ("none", "?"),
            ("queued", "queued"),
            ("running", "1024…"),
            ("failed", "failed"),
            ("price", "-"),
        ] {
            let row = row(&screen, name);
            assert!(row.contains(cell), "{cell:?} in {row:?}");
        }
        // Not every field has been counted.
        assert!(screen.contains("Fields (5)"), "{screen}");
    }

    #[test]
    fn terms_panel_says_what_to_do() {
        let mut overview = Overview::new(stats(vec![indexed("body", 0), field("price", 1)]), 50);
        assert!(
            render(&mut overview, 140, 30).contains("Enter counts the terms and finds the top 50.")
        );

        overview.set_scan(
            "body",
            Some(FieldScan::Running {
                terms_scanned: 2048,
            }),
        );
        assert!(render(&mut overview, 140, 30).contains("Scanning… 2048 terms so far."));

        overview.set_scan("body", Some(FieldScan::Failed("Cannot read".to_owned())));
        assert!(render(&mut overview, 140, 30).contains("The scan failed: Cannot read"));

        overview.handle_key(KeyCode::Down);
        assert!(render(&mut overview, 140, 30).contains("No postings: the field is not indexed."));
    }

    #[test]
    fn tab_moves_the_focus_to_the_top_terms() {
        let mut overview = Overview::new(stats(vec![indexed("body", 0), indexed("id", 1)]), 50);
        overview.set_scan(
            "body",
            Some(FieldScan::done(
                terms(
                    "body",
                    3,
                    vec![top("a", false, 3), top("b", false, 2), top("c", false, 1)],
                ),
                50,
            )),
        );
        let terms_selection = |overview: &Overview| match overview.scan("body") {
            Some(FieldScan::Done { table, .. }) => table.selected(),
            other => panic!("{other:?}"),
        };

        assert!(overview.handle_key(KeyCode::Tab));
        overview.handle_key(KeyCode::Down);
        overview.handle_key(KeyCode::Down);

        assert_eq!(terms_selection(&overview), Some(2));
        assert_eq!(overview.fields.selected(), Some(0));

        overview.handle_key(KeyCode::Tab);
        overview.handle_key(KeyCode::Down);
        assert_eq!(overview.fields.selected(), Some(1));
        assert_eq!(terms_selection(&overview), Some(2));
    }

    #[test]
    fn selected_field_is_clamped_before_rendering() {
        let mut overview = Overview::new(stats(vec![indexed("a", 0), indexed("b", 1)]), 50);

        overview.handle_key(KeyCode::End);

        assert_eq!(
            overview.selected_field().map(|field| field.name.as_str()),
            Some("b")
        );
        assert!(
            Overview::new(stats(Vec::new()), 50)
                .selected_field()
                .is_none()
        );
    }

    #[test]
    fn lists_the_fields_to_scan() {
        let mut overview = Overview::new(
            stats(vec![
                indexed("a", 0),
                field("b", 1),
                indexed("c", 2),
                indexed("d", 3),
                indexed("e", 4),
            ]),
            50,
        );
        overview.set_scan("c", Some(FieldScan::done(terms("c", 0, Vec::new()), 50)));
        overview.set_scan("d", Some(FieldScan::Failed("boom".to_owned())));
        overview.set_scan("e", Some(FieldScan::Queued));

        assert_eq!(overview.fields_to_scan(), ["a", "d"]);
    }

    /// The field names in table order.
    fn names(overview: &Overview) -> Vec<&str> {
        overview
            .order
            .iter()
            .map(|&index| overview.stats.fields[index].name.as_str())
            .collect()
    }

    fn done(overview: &mut Overview, field: &str, unique_terms: i64) {
        overview.set_scan(
            field,
            Some(FieldScan::done(terms(field, unique_terms, Vec::new()), 50)),
        );
    }

    #[test]
    fn sorts_by_number_name_and_term_count() {
        let mut overview = Overview::new(
            stats(vec![
                indexed("c", 0),
                indexed("a", 1),
                field("d", 2),
                indexed("b", 3),
                indexed("e", 4),
            ]),
            50,
        );
        done(&mut overview, "c", 5);
        done(&mut overview, "a", 9);
        done(&mut overview, "e", 5);
        assert_eq!(names(&overview), ["c", "a", "d", "b", "e"]);
        assert!(render(&mut overview, 140, 30).contains("# ▲"));

        overview.handle_key(KeyCode::Char('S'));
        assert_eq!(names(&overview), ["e", "b", "d", "a", "c"]);

        overview.handle_key(KeyCode::Char('s'));
        assert_eq!(names(&overview), ["a", "b", "c", "d", "e"]);
        assert!(render(&mut overview, 140, 30).contains("Name ▲"));

        // The largest count first; equal counts by number; fields without a count last.
        overview.handle_key(KeyCode::Char('s'));
        assert_eq!(names(&overview), ["a", "c", "e", "d", "b"]);
        assert!(render(&mut overview, 140, 30).contains("Terms ▼"));
        overview.handle_key(KeyCode::Char('S'));
        assert_eq!(names(&overview), ["c", "e", "a", "d", "b"]);

        // A new count moves its field.
        done(&mut overview, "b", 1);
        assert_eq!(names(&overview), ["b", "c", "e", "a", "d"]);

        overview.handle_key(KeyCode::Char('s'));
        assert_eq!(names(&overview), ["c", "a", "d", "b", "e"]);
    }

    #[test]
    fn the_selection_stays_on_its_field_when_the_rows_move() {
        let mut overview = Overview::new(stats(vec![indexed("b", 0), indexed("a", 1)]), 50);
        overview.handle_key(KeyCode::Down);
        assert_eq!(overview.selected_field().unwrap().name, "a");

        overview.handle_key(KeyCode::Char('s'));

        assert_eq!(overview.fields.selected(), Some(0));
        assert_eq!(overview.selected_field().unwrap().name, "a");
    }

    #[test]
    fn reopening_keeps_the_sort_and_the_selected_field_but_not_the_scans() {
        let mut overview = Overview::new(
            stats(vec![indexed("b", 0), indexed("a", 1), indexed("c", 2)]),
            50,
        );
        done(&mut overview, "a", 3);
        // Sorted by name, the selection stays on `b`, in the second row.
        overview.handle_key(KeyCode::Char('s'));
        assert_eq!(overview.selected_field().unwrap().name, "b");

        let reopened = overview.reopened(
            stats(vec![
                indexed("c", 0),
                indexed("b", 1),
                indexed("a", 2),
                indexed("d", 3),
            ]),
            20,
        );

        assert_eq!(names(&reopened), ["a", "b", "c", "d"]);
        assert_eq!(reopened.selected_field().unwrap().name, "b");
        assert!(reopened.scan("a").is_none());

        // A field that is gone leaves the selection on the first row.
        let reopened = reopened.reopened(stats(vec![indexed("x", 0), indexed("a", 1)]), 20);
        assert_eq!(reopened.selected_field().unwrap().name, "a");
    }

    #[test]
    fn shows_the_share_of_all_terms_once_every_field_is_counted() {
        let mut overview = Overview::new(
            stats(vec![
                indexed("body", 0),
                indexed("id", 1),
                field("price", 2),
            ]),
            50,
        );
        done(&mut overview, "body", 3);
        let screen = render(&mut overview, 140, 30);
        assert!(row(&screen, "body").contains("  3  ?  "), "{screen}");

        done(&mut overview, "id", 1);
        let screen = render(&mut overview, 140, 30);
        assert!(row(&screen, "body").contains("  3  75.00  "), "{screen}");
        assert!(row(&screen, "id").contains("  1  25.00  "), "{screen}");
        assert!(row(&screen, "price").contains("  -      -  "), "{screen}");
    }

    #[test]
    fn steps_the_number_of_top_terms() {
        assert_eq!(step_top_terms(50, true), 100);
        assert_eq!(step_top_terms(50, false), 20);
        assert_eq!(step_top_terms(10, false), 10);
        assert_eq!(step_top_terms(1000, true), 1000);
    }

    #[test]
    fn shows_at_most_the_top_n_and_rescans_for_more() {
        let three = vec![top("a", false, 3), top("b", false, 2), top("c", false, 1)];
        let mut overview = Overview::new(stats(vec![indexed("body", 0), indexed("id", 1)]), 3);
        overview.set_scan(
            "body",
            Some(FieldScan::done(terms("body", 10, three.clone()), 3)),
        );
        // Every term of the field is in.
        overview.set_scan("id", Some(FieldScan::done(terms("id", 3, three), 3)));
        assert!(overview.fields_to_scan().is_empty());

        overview.set_top_n(2);
        let screen = render(&mut overview, 140, 30);
        assert!(screen.contains("body: 10 terms, top 2 "), "{screen}");
        assert!(!screen.contains("3     4  c"), "{screen}");
        assert!(overview.fields_to_scan().is_empty());

        overview.set_top_n(5);
        let screen = render(&mut overview, 140, 30);
        assert!(
            screen.contains("body: 10 terms, top 3 (⏎ top 5)"),
            "{screen}"
        );
        assert_eq!(overview.fields_to_scan(), ["body"]);
    }
}
