//! The regex scan of the search screen (ADR 0027): a Java regular expression over the stored
//! values of every document, run as a server job. Its hits are values, not documents: each shows
//! a snippet around its first match, with the matches highlighted. They arrive while the scan
//! runs, so the list grows until it is done or stopped.

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Cell, Paragraph, Row, Table, TableState, Wrap};

use crate::overview::{highlight, panel};
use crate::protocol::search::{
    GetStoredScanResult, MAX_STORED_SCAN_HITS, StartStoredScanParams, StoredScanHit,
    StoredScanResult,
};
use crate::terms::{moved, number_width};
use crate::text::{MATCH, count, escape_control};
use crate::viewer::size;

/// Marks a deleted document in the hit list.
const DELETED_MARK: &str = "✗";

/// A scan of the stored values and what it found so far.
#[derive(Debug)]
pub struct StoredScan {
    params: StartStoredScanParams,
    /// `None` until the server has started the job.
    job_id: Option<i64>,
    /// Whether the job runs: until its outcome, a failure or a cancel.
    running: bool,
    scanned_docs: i64,
    matched_values: i64,
    hits: Vec<StoredScanHit>,
    result: Option<StoredScanResult>,
    /// Why the scan could not start, failed, or was stopped.
    error: Option<String>,
    table: TableState,
}

impl StoredScan {
    /// A scan with these params, about to start.
    pub fn new(params: StartStoredScanParams) -> Self {
        Self {
            params,
            job_id: None,
            running: true,
            scanned_docs: 0,
            matched_values: 0,
            hits: Vec::new(),
            result: None,
            error: None,
            table: TableState::new(),
        }
    }

    pub fn started(&mut self, job_id: i64) {
        self.job_id = Some(job_id);
    }

    /// The job to poll, while it runs.
    pub fn job(&self) -> Option<i64> {
        self.job_id.filter(|_| self.running)
    }

    /// The first hit the next poll asks for: the hits that are in.
    pub fn from_hit(&self) -> i32 {
        i32::try_from(self.hits.len()).unwrap_or(i32::MAX)
    }

    /// Adds the hits that came in, and takes the outcome once it is there.
    pub fn progress(&mut self, result: GetStoredScanResult) {
        if !self.running {
            return;
        }
        self.scanned_docs = result.scanned_docs;
        self.matched_values = result.matched_values;
        self.hits.extend(result.hits);
        if self.table.selected().is_none() && !self.hits.is_empty() {
            self.table.select(Some(0));
        }
        if let Some(outcome) = result.result {
            self.matched_values = outcome.matched_values;
            self.result = Some(outcome);
            self.running = false;
        }
    }

    /// Shows why the scan could not start or failed; the hits that are in stay.
    pub fn failed(&mut self, error: String) {
        self.running = false;
        self.error = Some(error);
    }

    /// Marks a running scan as stopped; the hits that are in stay.
    pub fn cancelled(&mut self) {
        if self.running {
            self.failed("Stopped.".to_owned());
        }
    }

    pub fn selected(&self) -> Option<&StoredScanHit> {
        let last = self.hits.len().checked_sub(1)?;
        self.hits.get(self.table.selected()?.min(last))
    }

    /// Moves the selection with a navigation key; false for any other key.
    pub fn move_selection(&mut self, key: KeyCode) -> bool {
        let Some(row) = moved(
            self.table.selected().unwrap_or_default(),
            self.hits.len(),
            key,
        ) else {
            return false;
        };
        self.table.select(Some(row));
        true
    }

    /// Selects the previous or next hit in another document, for `[`/`]` on the documents
    /// screen, and returns its document id.
    pub fn step(&mut self, forward: bool) -> Option<i32> {
        let selected = self.table.selected()?.min(self.hits.len().checked_sub(1)?);
        let doc_id = self.hits[selected].doc_id;
        let row = if forward {
            (selected + 1..self.hits.len()).find(|&row| self.hits[row].doc_id != doc_id)?
        } else {
            let before = (0..selected)
                .rev()
                .find(|&row| self.hits[row].doc_id != doc_id)?;
            // The first hit of that document.
            let other = self.hits[before].doc_id;
            (0..=before)
                .rev()
                .take_while(|&row| self.hits[row].doc_id == other)
                .last()?
        };
        self.table.select(Some(row));
        Some(self.hits[row].doc_id)
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect, focused: bool, max_doc: i32) {
        let title = self.title(max_doc);
        if self.hits.is_empty() {
            let (text, style) = match (&self.error, &self.result) {
                (Some(error), _) => (error.clone(), Style::new().fg(Color::Red)),
                (None, Some(_)) => ("No stored value matches.".to_owned(), Style::new()),
                (None, None) => (String::new(), Style::new()),
            };
            frame.render_widget(
                Paragraph::new(text)
                    .style(style)
                    .wrap(Wrap { trim: false })
                    .block(panel(title, focused)),
                area,
            );
            return;
        }
        let rank_width = self.hits.len().to_string().len() as u16;
        let doc_width = number_width(self.hits.iter().map(|hit| i64::from(hit.doc_id)));
        let fields: Vec<String> = self.hits.iter().map(field_text).collect();
        let field_width = fields
            .iter()
            .map(|field| field.chars().count())
            .max()
            .unwrap_or_default()
            .clamp(5, 32) as u16;
        let matches_width = number_width(self.hits.iter().map(|hit| i64::from(hit.match_count)));
        let rows: Vec<Row> = self
            .hits
            .iter()
            .zip(fields)
            .enumerate()
            .map(|(i, (hit, field))| {
                let mark = if hit.deleted { DELETED_MARK } else { " " };
                Row::new([
                    Cell::from(format!("{:>width$}", i + 1, width = rank_width.into())),
                    Cell::from(Line::from(vec![
                        Span::raw(format!("{:>width$}", hit.doc_id, width = doc_width.into())),
                        Span::styled(mark, Style::new().fg(Color::Red)),
                    ])),
                    Cell::from(field),
                    Cell::from(format!(
                        "{:>width$}",
                        hit.match_count,
                        width = matches_width.into()
                    )),
                    Cell::from(snippet_line(hit)),
                ])
            })
            .collect();
        let header = Row::new(["#", "Doc", "Field", "Matches", "Value"])
            .style(Style::new().add_modifier(Modifier::BOLD));
        let widths = [
            Constraint::Length(rank_width.max(1)),
            Constraint::Length(doc_width + 1),
            Constraint::Length(field_width),
            Constraint::Length(matches_width.max(7)),
            Constraint::Fill(1),
        ];
        let table = Table::new(rows, widths)
            .header(header)
            .row_highlight_style(highlight(focused))
            .block(panel(title, focused));
        frame.render_stateful_widget(table, area, &mut self.table);
    }

    fn title(&self, max_doc: i32) -> String {
        let values = count(
            usize::try_from(self.matched_values).unwrap_or_default(),
            "value",
        );
        let mut title = match &self.result {
            Some(result) => format!(
                " {values} in {} in {} ms ",
                count(
                    usize::try_from(result.matched_docs).unwrap_or_default(),
                    "doc"
                ),
                result.took_millis
            ),
            None if self.running => {
                let percent = if max_doc > 0 {
                    self.scanned_docs * 100 / i64::from(max_doc)
                } else {
                    0
                };
                format!(" {values} · scanning… {percent}% of {max_doc} docs ",)
            }
            None => format!(" {values} so far · stopped "),
        };
        if self
            .result
            .as_ref()
            .is_some_and(|result| result.hits_truncated)
        {
            title.push_str(&format!("· first {MAX_STORED_SCAN_HITS} shown "));
        }
        if let Some(result) = &self.result
            && result.timed_out_values > 0
        {
            title.push_str(&format!("· {} timed out ", result.timed_out_values));
        }
        title
    }

    /// The selected hit, the pattern and the settings, and the outcome.
    pub fn detail_lines(&self) -> Vec<Line<'static>> {
        let mut lines = Vec::new();
        if let Some(hit) = self.selected() {
            let deleted = if hit.deleted { " · deleted" } else { "" };
            lines.push(Line::from(format!(
                "Doc {} · {} · {} · {}{deleted}",
                hit.doc_id,
                field_text(hit),
                size(i64::from(hit.length)),
                match hit.match_count {
                    1 => "1 match".to_owned(),
                    n => format!("{n} matches"),
                },
            )));
            lines.push(
                Line::from("⏎ opens the document · v views the value with its matches")
                    .style(dim()),
            );
            lines.push(Line::from(""));
        }
        lines.push(Line::from(format!(
            "Pattern    /{}/{}",
            escape_control(&self.params.pattern),
            if self.params.case_insensitive {
                "i"
            } else {
                ""
            }
        )));
        if let Some(fields) = &self.params.fields {
            let fields: Vec<String> = fields.iter().map(|field| escape_control(field)).collect();
            lines.push(Line::from(format!("Fields     {}", fields.join(" · "))));
        }
        if self.params.include_deleted {
            lines.push(Line::from("Deleted    scanned too"));
        }
        if let Some(result) = &self.result {
            lines.push(Line::from(format!(
                "Scanned    {} in {} ms",
                count(
                    usize::try_from(result.scanned_docs).unwrap_or_default(),
                    "doc"
                ),
                result.took_millis
            )));
            if result.hits_truncated {
                lines.push(
                    Line::from(format!(
                        "Only the first {MAX_STORED_SCAN_HITS} values are listed; the counts \
                         are of all of them."
                    ))
                    .style(dim()),
                );
            }
            if result.timed_out_values > 0 {
                lines.push(
                    Line::from(format!(
                        "{} took too long to match and {} skipped:",
                        count(
                            usize::try_from(result.timed_out_values).unwrap_or_default(),
                            "value"
                        ),
                        if result.timed_out_values == 1 {
                            "was"
                        } else {
                            "were"
                        }
                    ))
                    .style(Style::new().fg(Color::Yellow)),
                );
                for value in &result.timed_out {
                    lines.push(Line::from(format!(
                        "  doc {} · {}[{}]",
                        value.doc_id,
                        escape_control(&value.field),
                        value.value_index
                    )));
                }
                let listed = result.timed_out.len() as i64;
                if result.timed_out_values > listed {
                    lines.push(Line::from(format!(
                        "  and {} more",
                        result.timed_out_values - listed
                    )));
                }
            }
        }
        if let Some(error) = &self.error {
            lines.push(Line::from(error.clone()).style(Style::new().fg(Color::Red)));
        }
        lines
    }
}

/// `field[index]` of a hit.
fn field_text(hit: &StoredScanHit) -> String {
    format!("{}[{}]", escape_control(&hit.field), hit.value_index)
}

/// The snippet of a hit, with the matches in it highlighted, and `…` where the value goes on.
fn snippet_line(hit: &StoredScanHit) -> Line<'static> {
    let text = hit.snippet.text.as_str();
    let start = i64::from(hit.snippet.start);
    let len = text.len() as i64;
    let mut spans = Vec::new();
    if start > 0 {
        spans.push(Span::styled("…", dim()));
    }
    let mut pos = 0;
    for span in &hit.spans {
        let from = (i64::from(span.start) - start).clamp(0, len) as usize;
        let to = (i64::from(span.end) - start).clamp(0, len) as usize;
        if to <= from || from < pos || !text.is_char_boundary(from) || !text.is_char_boundary(to) {
            continue;
        }
        if from > pos {
            spans.push(Span::raw(escape_control(&text[pos..from])));
        }
        spans.push(Span::styled(escape_control(&text[from..to]), MATCH));
        pos = to;
    }
    if pos < text.len() {
        spans.push(Span::raw(escape_control(&text[pos..])));
    }
    if start + len < i64::from(hit.length) {
        spans.push(Span::styled("…", dim()));
    }
    Line::from(spans)
}

fn dim() -> Style {
    Style::new().add_modifier(Modifier::DIM)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::search::{MatchSpan, Snippet, ValueRef};

    fn params() -> StartStoredScanParams {
        StartStoredScanParams {
            pattern: "red".to_owned(),
            case_insensitive: false,
            fields: None,
            include_deleted: false,
        }
    }

    fn hit(doc_id: i32, field: &str, text: &str, spans: &[(i32, i32)]) -> StoredScanHit {
        StoredScanHit {
            doc_id,
            deleted: false,
            field: field.to_owned(),
            value_index: 0,
            length: text.len() as i32,
            match_count: spans.len() as i32,
            spans: spans
                .iter()
                .map(|&(start, end)| MatchSpan { start, end })
                .collect(),
            snippet: Snippet {
                start: 0,
                text: text.to_owned(),
            },
        }
    }

    fn poll(hits: Vec<StoredScanHit>, result: Option<StoredScanResult>) -> GetStoredScanResult {
        GetStoredScanResult {
            scanned_docs: 50,
            matched_values: 7,
            hit_count: 0,
            hits,
            result,
        }
    }

    fn outcome() -> StoredScanResult {
        StoredScanResult {
            scanned_docs: 100,
            matched_docs: 2,
            matched_values: 3,
            hits_truncated: false,
            timed_out_values: 1,
            timed_out: vec![ValueRef {
                doc_id: 7,
                field: "odd".to_owned(),
                value_index: 0,
            }],
            took_millis: 12,
        }
    }

    #[test]
    fn appends_the_hits_of_each_poll_until_the_outcome() {
        let mut scan = StoredScan::new(params());
        scan.started(4);
        assert_eq!(scan.job(), Some(4));

        scan.progress(poll(vec![hit(1, "a", "red", &[(0, 3)])], None));
        assert_eq!(scan.from_hit(), 1);
        assert_eq!(scan.selected().map(|hit| hit.doc_id), Some(1));
        scan.progress(poll(vec![hit(2, "a", "red", &[(0, 3)])], Some(outcome())));

        assert_eq!(scan.from_hit(), 2);
        assert_eq!(scan.job(), None);
        assert_eq!(scan.matched_values, 3);
        let details: Vec<String> = scan.detail_lines().iter().map(Line::to_string).collect();
        assert!(
            details.contains(&"  doc 7 · odd[0]".to_owned()),
            "{details:?}"
        );
    }

    #[test]
    fn a_stopped_scan_keeps_its_hits() {
        let mut scan = StoredScan::new(params());
        scan.started(4);
        scan.progress(poll(vec![hit(1, "a", "red", &[(0, 3)])], None));

        scan.cancelled();

        assert_eq!(scan.job(), None);
        assert_eq!(scan.hits.len(), 1);
        assert!(scan.title(100).contains("so far · stopped"));
        // A late poll changes nothing.
        scan.progress(poll(vec![hit(2, "a", "red", &[(0, 3)])], None));
        assert_eq!(scan.hits.len(), 1);
    }

    #[test]
    fn steps_through_the_documents_of_the_hits() {
        let mut scan = StoredScan::new(params());
        scan.progress(poll(
            vec![
                hit(1, "a", "red", &[(0, 3)]),
                hit(1, "b", "red", &[(0, 3)]),
                hit(4, "a", "red", &[(0, 3)]),
                hit(4, "b", "red", &[(0, 3)]),
                hit(9, "a", "red", &[(0, 3)]),
            ],
            None,
        ));

        assert_eq!(scan.step(true), Some(4));
        assert_eq!(scan.table.selected(), Some(2));
        assert_eq!(scan.step(true), Some(9));
        assert_eq!(scan.step(true), None);
        assert_eq!(scan.step(false), Some(4));
        // The first hit of the document.
        assert_eq!(scan.table.selected(), Some(2));
        assert_eq!(scan.step(false), Some(1));
        assert_eq!(scan.table.selected(), Some(0));
        assert_eq!(scan.step(false), None);
    }

    #[test]
    fn highlights_the_matches_in_the_snippet() {
        let mut hit = hit(1, "a", "ä red\tred", &[(3, 6), (7, 10), (40, 43)]);
        hit.snippet.start = 0;
        hit.length = 100;

        let line = snippet_line(&hit);

        let parts: Vec<(String, bool)> = line
            .spans
            .iter()
            .map(|span| (span.content.to_string(), span.style == MATCH))
            .collect();
        assert_eq!(
            parts,
            vec![
                ("ä ".to_owned(), false),
                ("red".to_owned(), true),
                ("\\t".to_owned(), false),
                ("red".to_owned(), true),
                ("…".to_owned(), false),
            ]
        );
    }

    #[test]
    fn a_snippet_from_inside_the_value_starts_with_an_ellipsis() {
        let mut hit = hit(1, "a", "xx red", &[(13, 16)]);
        hit.snippet.start = 10;
        hit.length = 16;

        let text = snippet_line(&hit).to_string();

        assert_eq!(text, "…xx red");
    }
}
