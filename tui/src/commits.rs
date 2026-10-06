//! The commits screen: the commits of the index directory on top and, below, the segments of the
//! selected commit next to the details of the selected commit or segment (ADR 0009). Both come
//! from the directory, so `r` reads them again to show commits written since.

use std::collections::BTreeMap;

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Cell, Paragraph, Row, Table, TableState, Wrap};

use crate::overview::{highlight, panel};
use crate::protocol::commits::{Commit, CommitFile, GetCommitResult, Segment};
use crate::terms::{moved, number_width};
use crate::text::{count, escape_control, first_chars};
use crate::viewer::size;

/// The commits panel shows at most this many rows before it scrolls.
const COMMIT_ROWS: usize = 6;
const COLUMN_SPACING: u16 = 2;
/// Keys in the details are padded to the longest one, up to this width.
const KEY_WIDTH: usize = 28;
const PAGE: u16 = 10;

/// What the caller has to fetch for the commits screen.
#[derive(Debug, PartialEq, Eq)]
pub enum Request {
    /// The commits of the directory, reported back with [`Commits::commits_loaded`] or
    /// [`Commits::commits_failed`].
    Commits,
    /// One commit whole, reported back with [`Commits::commit_loaded`] or
    /// [`Commits::commit_failed`].
    Commit(i64),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Commits,
    Segments,
    Details,
}

pub struct Commits {
    /// `None` until the commits are fetched.
    commits: Option<Result<Vec<Commit>, String>>,
    commit_row: usize,
    commit_table: TableState,
    /// The commits fetched whole, or why they could not be, by generation.
    details: BTreeMap<i64, Result<GetCommitResult, String>>,
    segment_row: usize,
    segment_table: TableState,
    focus: Focus,
    /// Whether the details show the selected segment rather than the selected commit: the one
    /// of the two that had the focus last.
    segment_details: bool,
    details_scroll: u16,
}

impl Default for Commits {
    fn default() -> Self {
        Self::new()
    }
}

impl Commits {
    pub fn new() -> Self {
        Self {
            commits: None,
            commit_row: 0,
            commit_table: TableState::new(),
            details: BTreeMap::new(),
            segment_row: 0,
            segment_table: TableState::new(),
            focus: Focus::Commits,
            segment_details: false,
            details_scroll: 0,
        }
    }

    /// What to fetch when the screen is shown.
    pub fn initial_request(&self) -> Option<Request> {
        self.next_request()
    }

    /// The commit list first, then the selected commit whole. A fetch that failed is not
    /// repeated by itself; `r` repeats it.
    fn next_request(&self) -> Option<Request> {
        let Some(commits) = &self.commits else {
            return Some(Request::Commits);
        };
        let generation = commits.as_ref().ok()?.get(self.commit_row)?.generation;
        (!self.details.contains_key(&generation)).then_some(Request::Commit(generation))
    }

    /// Shows the commits; the selection stays on its commit, or else goes to the opened one.
    pub fn commits_loaded(&mut self, commits: Vec<Commit>) -> Option<Request> {
        let selected = self.selected_commit().map(|commit| commit.generation);
        self.commit_row = selected
            .and_then(|generation| commits.iter().position(|c| c.generation == generation))
            .or_else(|| commits.iter().position(|commit| commit.current))
            .unwrap_or_default();
        self.commits = Some(Ok(commits));
        self.next_request()
    }

    pub fn commits_failed(&mut self, error: String) {
        self.commits = Some(Err(error));
    }

    pub fn commit_loaded(&mut self, generation: i64, result: GetCommitResult) {
        self.details.insert(generation, Ok(result));
    }

    pub fn commit_failed(&mut self, generation: i64, error: String) {
        self.details.insert(generation, Err(error));
    }

    fn selected_commit(&self) -> Option<&Commit> {
        self.commits.as_ref()?.as_ref().ok()?.get(self.commit_row)
    }

    fn selected_details(&self) -> Option<&Result<GetCommitResult, String>> {
        self.details.get(&self.selected_commit()?.generation)
    }

    fn selected_segment(&self) -> Option<&Segment> {
        self.selected_details()?
            .as_ref()
            .ok()?
            .segments
            .get(self.segment_row)
    }

    /// Switches panels with Tab, moves the selection or scrolls the details, goes to the
    /// segments with Enter, and reads the commits again with `r`.
    pub fn handle_key(&mut self, key: KeyCode) -> Option<Request> {
        match key {
            KeyCode::Char('r') => {
                // The commits stay until the new ones are in, so the selection can stay on its
                // commit.
                self.details.clear();
                self.details_scroll = 0;
                Some(Request::Commits)
            }
            KeyCode::Tab => {
                self.focus(match self.focus {
                    Focus::Commits => Focus::Segments,
                    Focus::Segments => Focus::Details,
                    Focus::Details => Focus::Commits,
                });
                None
            }
            KeyCode::BackTab => {
                self.focus(match self.focus {
                    Focus::Commits => Focus::Details,
                    Focus::Segments => Focus::Commits,
                    Focus::Details => Focus::Segments,
                });
                None
            }
            KeyCode::Enter if self.focus == Focus::Commits => {
                self.focus(Focus::Segments);
                None
            }
            _ => match self.focus {
                Focus::Commits => {
                    let len = self.commits.as_ref()?.as_ref().ok()?.len();
                    let row = moved(self.commit_row, len, key)?;
                    if row != self.commit_row {
                        self.commit_row = row;
                        self.segment_row = 0;
                        self.details_scroll = 0;
                    }
                    self.next_request()
                }
                Focus::Segments => {
                    let len = self.selected_details()?.as_ref().ok()?.segments.len();
                    let row = moved(self.segment_row, len, key)?;
                    if row != self.segment_row {
                        self.segment_row = row;
                        self.details_scroll = 0;
                    }
                    None
                }
                Focus::Details => {
                    self.scroll_details(key);
                    None
                }
            },
        }
    }

    fn focus(&mut self, focus: Focus) {
        let segment_details = match focus {
            Focus::Commits => false,
            Focus::Segments => true,
            Focus::Details => self.segment_details,
        };
        if segment_details != self.segment_details {
            self.details_scroll = 0;
        }
        self.segment_details = segment_details;
        self.focus = focus;
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

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let rows = match &self.commits {
            Some(Ok(commits)) => commits.len().clamp(1, COMMIT_ROWS),
            _ => 1,
        };
        // Borders and the header.
        let [commits_area, below] =
            Layout::vertical([Constraint::Length(rows as u16 + 3), Constraint::Fill(1)])
                .areas(area);
        let [segments_area, details_area] =
            Layout::horizontal([Constraint::Fill(3), Constraint::Fill(2)]).areas(below);
        self.render_commits(frame, commits_area);
        self.render_segments(frame, segments_area);
        self.render_details(frame, details_area);
    }

    fn render_commits(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Commits;
        let commits = match &self.commits {
            None => return message(frame, area, " Commits ", focused, "Reading the commits…"),
            Some(Err(error)) => {
                let text = format!("Cannot list the commits: {error}");
                return message(frame, area, " Commits ", focused, &text);
            }
            Some(Ok(commits)) => commits,
        };
        let title = format!(" Commits ({}; ● opened, r reads again) ", commits.len());
        if commits.is_empty() {
            return message(frame, area, &title, focused, "No readable commit.");
        }
        let header = Row::new([
            Cell::from(""),
            right("Gen"),
            Cell::from("File"),
            right("Segs"),
            right("Docs"),
            right("Deleted"),
            Cell::from("Lucene"),
            Cell::from("User data"),
        ])
        .style(Style::new().add_modifier(Modifier::BOLD));
        let rows = commits.iter().map(|commit| {
            Row::new([
                Cell::from(if commit.current { "●" } else { "" }),
                right(commit.generation.to_string()),
                Cell::from(commit.segments_file.clone()),
                right(commit.segment_count.to_string()),
                right(commit.max_doc.to_string()),
                right(commit.del_count.to_string()),
                Cell::from(commit.lucene_version.clone()),
                Cell::from(user_data(&commit.user_data)),
            ])
        });
        let widths = [
            Constraint::Length(1),
            Constraint::Length(number_width(commits.iter().map(|c| c.generation)).min(3)),
            Constraint::Length(
                commits
                    .iter()
                    .map(|c| c.segments_file.len())
                    .max()
                    .unwrap_or_default() as u16,
            ),
            Constraint::Length(4),
            Constraint::Length(number_width(commits.iter().map(|c| i64::from(c.max_doc)))),
            Constraint::Length(7),
            Constraint::Length(
                commits
                    .iter()
                    .map(|c| c.lucene_version.len())
                    .max()
                    .unwrap_or_default()
                    .max(6) as u16,
            ),
            Constraint::Fill(1),
        ];
        self.commit_table.select(Some(self.commit_row));
        let table = Table::new(rows, widths)
            .header(header)
            .column_spacing(COLUMN_SPACING)
            .row_highlight_style(highlight(focused))
            .block(panel(title, focused));
        frame.render_stateful_widget(table, area, &mut self.commit_table);
    }

    fn render_segments(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Segments;
        let details = match self.selected_details() {
            None if self.selected_commit().is_some() => {
                return message(frame, area, " Segments ", focused, "Reading the commit…");
            }
            None => return frame.render_widget(panel(" Segments ".to_owned(), focused), area),
            Some(Err(error)) => {
                let text = format!("Cannot read the commit: {error}");
                return message(frame, area, " Segments ", focused, &text);
            }
            Some(Ok(details)) => details,
        };
        let segments = &details.segments;
        let title = format!(
            " Segments of {} ({}) ",
            details.commit.segments_file,
            segments.len()
        );
        if segments.is_empty() {
            return message(frame, area, &title, focused, "No segments.");
        }
        let header = Row::new([
            Cell::from("Name"),
            right("Docs"),
            right("Base"),
            right("Deleted"),
            right("Size"),
            Cell::from("Lucene"),
            Cell::from("Codec"),
            Cell::from("CFS"),
        ])
        .style(Style::new().add_modifier(Modifier::BOLD));
        let rows: Vec<Row> = segments
            .iter()
            .map(|segment| {
                Row::new([
                    Cell::from(segment.name.clone()),
                    right(segment.max_doc.to_string()),
                    right(segment.doc_base.to_string()),
                    right(segment.del_count.to_string()),
                    right(segment.size_in_bytes.map_or("?".to_owned(), size)),
                    Cell::from(segment.lucene_version.clone()),
                    Cell::from(segment.codec.clone()),
                    Cell::from(if segment.compound { "yes" } else { "no" }),
                ])
            })
            .collect();
        let text_width = |text: fn(&Segment) -> &str, min: usize| {
            segments
                .iter()
                .map(|segment| text(segment).chars().count())
                .max()
                .unwrap_or_default()
                .max(min) as u16
        };
        let widths = [
            Constraint::Length(text_width(|s| &s.name, 4)),
            Constraint::Length(number_width(segments.iter().map(|s| i64::from(s.max_doc)))),
            Constraint::Length(number_width(segments.iter().map(|s| i64::from(s.doc_base)))),
            Constraint::Length(7),
            Constraint::Length(10),
            Constraint::Length(text_width(|s| &s.lucene_version, 6)),
            Constraint::Length(text_width(|s| &s.codec, 5)),
            Constraint::Length(3),
        ];
        self.segment_table.select(Some(self.segment_row));
        let table = Table::new(rows, widths)
            .header(header)
            .column_spacing(COLUMN_SPACING)
            .row_highlight_style(highlight(focused))
            .block(panel(title, focused));
        frame.render_stateful_widget(table, area, &mut self.segment_table);
    }

    fn render_details(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Details;
        let content = match self.selected_details() {
            Some(Ok(details)) if self.segment_details => self.selected_segment().map(|segment| {
                (
                    format!(" Segment {} ", segment.name),
                    segment_lines(segment, details),
                )
            }),
            Some(Ok(details)) => Some((
                format!(" {} ", details.commit.segments_file),
                commit_lines(details),
            )),
            _ => None,
        };
        let Some((title, lines)) = content else {
            frame.render_widget(panel(" Details ".to_owned(), focused), area);
            return;
        };
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

fn message(frame: &mut Frame, area: Rect, title: &str, focused: bool, text: &str) {
    frame.render_widget(
        Paragraph::new(text.to_owned())
            .wrap(Wrap { trim: false })
            .block(panel(title.to_owned(), focused)),
        area,
    );
}

fn right(text: impl Into<String>) -> Cell<'static> {
    Cell::from(Line::from(text.into()).right_aligned())
}

/// The user data in one line: `key=value` pairs, separated by commas.
fn user_data(user_data: &BTreeMap<String, String>) -> String {
    user_data
        .iter()
        .map(|(key, value)| {
            format!(
                "{}={}",
                escape_control(key),
                first_chars(&escape_control(value), 40)
            )
        })
        .collect::<Vec<_>>()
        .join(", ")
}

/// The details of a commit: what it is, where it stands to the opened one, its user data and
/// all of its files.
fn commit_lines(details: &GetCommitResult) -> Vec<Line<'static>> {
    let commit = &details.commit;
    let mut lines = vec![
        Line::from(format!(
            "Generation {} · version {} · Lucene {}",
            commit.generation, commit.version, commit.lucene_version
        )),
        Line::from(format!(
            "{} · {} · {} deleted",
            count(commit.segment_count as usize, "segment"),
            count(commit.max_doc as usize, "doc"),
            commit.del_count
        )),
        Line::from(if commit.current {
            "The opened commit.".to_owned()
        } else {
            "Not the opened commit: its documents are not shown on the other screens.".to_owned()
        }),
    ];
    let user_data: Vec<(String, String)> = commit
        .user_data
        .iter()
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect();
    section(&mut lines, "User data", &user_data);
    file_section(&mut lines, &details.files, true);
    lines
}

/// The details of a segment: its documents and versions, then its diagnostics, attributes,
/// codec formats and files.
fn segment_lines(segment: &Segment, details: &GetCommitResult) -> Vec<Line<'static>> {
    let docs = match segment.max_doc {
        0 => "No documents".to_owned(),
        1 => format!("Document {}", segment.doc_base),
        n => format!(
            "Documents {}–{}",
            segment.doc_base,
            segment.doc_base + n - 1
        ),
    };
    let min_version = segment
        .min_lucene_version
        .as_ref()
        .filter(|min| **min != segment.lucene_version)
        .map_or(String::new(), |min| format!(" (parts from {min})"));
    let generation =
        |generation: Option<i64>| generation.map_or("–".to_owned(), |g| g.to_string());
    let mut lines = vec![
        Line::from(format!(
            "{docs} · {} deleted · {} soft-deleted",
            segment.del_count, segment.soft_del_count
        )),
        Line::from(format!(
            "Lucene {}{min_version} · codec {}",
            segment.lucene_version, segment.codec
        )),
        Line::from(format!(
            "Compound file {} · blocks {} · size {}",
            if segment.compound { "yes" } else { "no" },
            if segment.has_blocks { "yes" } else { "no" },
            segment.size_in_bytes.map_or("unknown".to_owned(), size),
        )),
        Line::from(format!(
            "Sorted by {}",
            segment
                .index_sort
                .as_deref()
                .unwrap_or("document id (no index sort)")
        )),
        Line::from(format!(
            "Generations: deletes {} · field infos {} · doc values {}",
            generation(segment.del_gen),
            generation(segment.field_infos_gen),
            generation(segment.doc_values_gen),
        )),
        Line::from(format!("Id {}", segment.id)),
    ];
    let diagnostics: Vec<(String, String)> = segment
        .diagnostics
        .iter()
        .map(|(key, value)| (key.clone(), diagnostic(key, value)))
        .collect();
    section(&mut lines, "Diagnostics", &diagnostics);
    let attributes: Vec<(String, String)> = segment
        .attributes
        .iter()
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect();
    section(&mut lines, "Attributes", &attributes);
    let formats = &segment.formats;
    let formats: Vec<(String, String)> = [
        ("postings", &formats.postings),
        ("doc values", &formats.doc_values),
        ("stored fields", &formats.stored_fields),
        ("term vectors", &formats.term_vectors),
        ("norms", &formats.norms),
        ("points", &formats.points),
        ("KNN vectors", &formats.knn_vectors),
        ("field infos", &formats.field_infos),
        ("segment info", &formats.segment_info),
        ("live docs", &formats.live_docs),
        ("compound", &formats.compound),
    ]
    .into_iter()
    .map(|(key, value)| (key.to_owned(), value.clone()))
    .collect();
    section(&mut lines, "Codec formats", &formats);
    let files: Vec<CommitFile> = details
        .files
        .iter()
        .filter(|file| file.segment.as_ref() == Some(&segment.name))
        .cloned()
        .collect();
    file_section(&mut lines, &files, false);
    lines
}

/// A diagnostic's value; Lucene's `timestamp` in milliseconds also as a UTC time.
fn diagnostic(key: &str, value: &str) -> String {
    match value.parse::<i64>() {
        Ok(millis) if key == "timestamp" => format!("{value} ({})", utc_time(millis)),
        _ => value.to_owned(),
    }
}

/// A blank line, the bold heading, and the pairs with their keys aligned; "none" if there are
/// none.
fn section(lines: &mut Vec<Line<'static>>, heading: &str, pairs: &[(String, String)]) {
    lines.push(Line::from(""));
    lines.push(Line::from(heading.to_owned()).style(Style::new().add_modifier(Modifier::BOLD)));
    if pairs.is_empty() {
        lines.push(Line::from("none").style(Style::new().add_modifier(Modifier::DIM)));
        return;
    }
    let width = pairs
        .iter()
        .map(|(key, _)| escape_control(key).chars().count())
        .max()
        .unwrap_or_default()
        .min(KEY_WIDTH);
    for (key, value) in pairs {
        lines.push(Line::from(format!(
            "{:width$}  {}",
            escape_control(key),
            escape_control(value)
        )));
    }
}

/// The files with their sizes, and with `with_segment` the segment they belong to.
fn file_section(lines: &mut Vec<Line<'static>>, files: &[CommitFile], with_segment: bool) {
    let total: Option<i64> = files.iter().map(|file| file.size).sum();
    let heading = format!(
        "Files ({}, {})",
        files.len(),
        total.map_or("size unknown".to_owned(), size)
    );
    let pairs: Vec<(String, String)> = files
        .iter()
        .map(|file| {
            let mut value = format!("{:>10}", file.size.map_or("missing".to_owned(), size));
            if with_segment && let Some(segment) = &file.segment {
                value.push_str("  ");
                value.push_str(segment);
            }
            (file.name.clone(), value)
        })
        .collect();
    section(lines, &heading, &pairs);
}

/// Milliseconds since the epoch as `2001-09-09 01:46:40 UTC`.
fn utc_time(millis: i64) -> String {
    let seconds = millis.div_euclid(1000);
    let (days, time) = (seconds.div_euclid(86_400), seconds.rem_euclid(86_400));
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02} {:02}:{:02}:{:02} UTC",
        time / 3600,
        time % 3600 / 60,
        time % 60
    )
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::commits::CodecFormats;

    fn commit(generation: i64, current: bool) -> Commit {
        Commit {
            generation,
            segments_file: format!("segments_{generation}"),
            current,
            version: generation * 3,
            segment_count: 2,
            max_doc: 500,
            del_count: if current { 13 } else { 0 },
            lucene_version: "10.5.2".to_owned(),
            user_data: if current {
                BTreeMap::from([("writer".to_owned(), "demo".to_owned())])
            } else {
                BTreeMap::new()
            },
        }
    }

    fn segment(name: &str, doc_base: i32, max_doc: i32) -> Segment {
        Segment {
            name: name.to_owned(),
            id: "ab52725f4ed21286179cc2af33ded2b1".to_owned(),
            max_doc,
            doc_base,
            del_count: 3,
            soft_del_count: 0,
            del_gen: Some(1),
            field_infos_gen: None,
            doc_values_gen: None,
            lucene_version: "10.5.2".to_owned(),
            min_lucene_version: Some("10.5.2".to_owned()),
            codec: "Lucene104".to_owned(),
            formats: CodecFormats {
                postings: "PerField40".to_owned(),
                doc_values: "PerFieldDV40".to_owned(),
                stored_fields: "Lucene90StoredFieldsFormat".to_owned(),
                term_vectors: "Lucene90TermVectorsFormat".to_owned(),
                norms: "Lucene90NormsFormat".to_owned(),
                points: "Lucene90PointsFormat".to_owned(),
                knn_vectors: "PerFieldVectors90".to_owned(),
                field_infos: "Lucene94FieldInfosFormat".to_owned(),
                segment_info: "Lucene99SegmentInfoFormat".to_owned(),
                live_docs: "Lucene90LiveDocsFormat".to_owned(),
                compound: "Lucene90CompoundFormat".to_owned(),
            },
            compound: name == "_0",
            has_blocks: false,
            index_sort: None,
            size_in_bytes: Some(2048),
            diagnostics: BTreeMap::from([
                ("source".to_owned(), "flush".to_owned()),
                ("timestamp".to_owned(), "1000000000000".to_owned()),
            ]),
            attributes: BTreeMap::new(),
        }
    }

    fn file(name: &str, segment: Option<&str>, size: Option<i64>) -> CommitFile {
        CommitFile {
            name: name.to_owned(),
            segment: segment.map(str::to_owned),
            size,
        }
    }

    fn details(generation: i64, current: bool) -> GetCommitResult {
        GetCommitResult {
            commit: commit(generation, current),
            segments: vec![segment("_0", 0, 400), segment("_1", 400, 100)],
            files: vec![
                file("_0.cfs", Some("_0"), Some(1024)),
                file("_0.si", Some("_0"), Some(1024)),
                file("_1.fdt", Some("_1"), None),
                file(&format!("segments_{generation}"), None, Some(300)),
            ],
        }
    }

    /// The commits 4 (opened) and 3, as the server lists them.
    fn loaded() -> Commits {
        let mut commits = Commits::new();
        assert_eq!(
            commits.commits_loaded(vec![commit(4, true), commit(3, false)]),
            Some(Request::Commit(4))
        );
        commits.commit_loaded(4, details(4, true));
        commits
    }

    fn render(commits: &mut Commits, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| commits.render(frame, frame.area()))
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
    fn fetches_the_commits_then_the_selected_one() {
        let mut commits = Commits::new();
        assert_eq!(commits.initial_request(), Some(Request::Commits));
        assert_eq!(
            commits.commits_loaded(vec![commit(4, true), commit(3, false)]),
            Some(Request::Commit(4))
        );
        commits.commit_loaded(4, details(4, true));
        assert_eq!(commits.initial_request(), None);

        assert_eq!(commits.handle_key(KeyCode::Down), Some(Request::Commit(3)));
        commits.commit_failed(3, "gone".to_owned());
        // A failed commit is not fetched again by moving back to it.
        assert_eq!(commits.handle_key(KeyCode::Up), None);
        assert_eq!(commits.handle_key(KeyCode::Down), None);
    }

    #[test]
    fn selects_the_opened_commit_first() {
        let mut commits = Commits::new();
        assert_eq!(
            commits.commits_loaded(vec![commit(5, false), commit(4, true)]),
            Some(Request::Commit(4))
        );
    }

    #[test]
    fn reads_the_commits_again_and_keeps_the_selection() {
        let mut commits = loaded();
        commits.handle_key(KeyCode::Down);
        commits.commit_loaded(3, details(3, false));

        assert_eq!(
            commits.handle_key(KeyCode::Char('r')),
            Some(Request::Commits)
        );
        // A newer commit came in; the selection stays on commit 3 and fetches it again.
        assert_eq!(
            commits.commits_loaded(vec![commit(5, false), commit(4, true), commit(3, false)]),
            Some(Request::Commit(3))
        );
    }

    #[test]
    fn shows_the_commits_and_the_segments() {
        let mut commits = loaded();
        let screen = render(&mut commits, 120, 24);

        assert!(
            screen.contains("Commits (2; ● opened, r reads again)"),
            "{screen}"
        );
        assert!(screen.contains("●    4  segments_4"), "{screen}");
        assert!(screen.contains("writer=demo"), "{screen}");
        assert!(screen.contains("Segments of segments_4 (2)"), "{screen}");
        assert!(screen.contains("_1     100   400"), "{screen}");
        assert!(screen.contains("Lucene104"), "{screen}");
    }

    #[test]
    fn the_details_show_the_commit_or_the_segment() {
        let mut commits = loaded();
        let screen = render(&mut commits, 140, 30);
        assert!(screen.contains("Generation 4 · version 12"), "{screen}");
        assert!(screen.contains("The opened commit."), "{screen}");
        assert!(screen.contains("Files (4, size unknown)"), "{screen}");
        assert!(screen.contains("_1.fdt         missing  _1"), "{screen}");

        commits.handle_key(KeyCode::Enter);
        commits.handle_key(KeyCode::Down);
        let screen = render(&mut commits, 140, 40);
        assert!(screen.contains("Segment _1"), "{screen}");
        assert!(screen.contains("Documents 400–499 · 3 deleted"), "{screen}");
        assert!(
            screen.contains("timestamp  1000000000000 (2001-09-09 01:46:40 UTC)"),
            "{screen}"
        );
        assert!(screen.contains("postings       PerField40"), "{screen}");
        // Only the segment's own files.
        assert!(screen.contains("Files (1, size unknown)"), "{screen}");

        // Tab to the details keeps the segment.
        commits.handle_key(KeyCode::Tab);
        let screen = render(&mut commits, 140, 40);
        assert!(screen.contains("Segment _1"), "{screen}");
    }

    #[test]
    fn shows_why_a_commit_could_not_be_read() {
        let mut commits = loaded();
        commits.handle_key(KeyCode::Down);
        commits.commit_failed(3, "Corrupt commit".to_owned());

        let screen = render(&mut commits, 120, 20);
        assert!(
            screen.contains("Cannot read the commit: Corrupt commit"),
            "{screen}"
        );
    }

    #[test]
    fn formats_utc_times() {
        assert_eq!(utc_time(0), "1970-01-01 00:00:00 UTC");
        assert_eq!(utc_time(1_000_000_000_000), "2001-09-09 01:46:40 UTC");
        assert_eq!(utc_time(951_782_400_000), "2000-02-29 00:00:00 UTC");
        assert_eq!(utc_time(-1000), "1969-12-31 23:59:59 UTC");
    }
}
