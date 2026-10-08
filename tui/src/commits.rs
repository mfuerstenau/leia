//! The commits screen: the commits of the index directory on top and, below, the segments of the
//! selected commit next to the details of the selected commit or segment (ADR 0009). Both come
//! from the directory, so `r` reads them again to show commits written since. With several index
//! directories, a panel above chooses whose commits are shown (ADR 0021).

use std::collections::BTreeMap;

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Cell, Paragraph, Row, Table, TableState, Wrap};

use crate::overview::{highlight, panel};
use crate::protocol::commits::{
    Commit, CommitFile, GetCommitResult, GetCommitsResult, Segment, UnreadableCommit,
};
use crate::terms::{moved, number_width};
use crate::text::{count, escape_control, first_chars};
use crate::viewer::size;

/// The commits panel shows at most this many rows before it scrolls.
const COMMIT_ROWS: usize = 6;
/// The indexes panel shows at most this many rows before it scrolls.
const INDEX_ROWS: usize = 4;
const COLUMN_SPACING: u16 = 2;
/// Keys in the details are padded to the longest one, up to this width.
const KEY_WIDTH: usize = 28;
const PAGE: u16 = 10;

/// What the caller has to fetch for the commits screen.
#[derive(Debug, PartialEq, Eq)]
pub enum Request {
    /// The commits of the index directory at this position, reported back with
    /// [`Commits::commits_loaded`] or [`Commits::commits_failed`].
    Commits(usize),
    /// One commit of the index directory at this position whole, by generation, reported back
    /// with [`Commits::commit_loaded`] or [`Commits::commit_failed`].
    Commit(usize, i64),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    /// Only with several index directories.
    Indexes,
    Commits,
    Segments,
    Details,
}

pub struct Commits {
    /// The index directories as shown: below the opened path.
    indexes: Vec<String>,
    /// The index directory whose commits are shown.
    index_row: usize,
    index_table: TableState,
    /// The commits of each index directory; `None` until they are fetched.
    lists: Vec<Option<Result<Vec<Commit>, String>>>,
    /// The `segments_N` files of each index directory that could not be read.
    unreadable: Vec<Vec<UnreadableCommit>>,
    /// The selected row of the commits panel: a commit, or after them an unreadable one.
    commit_row: usize,
    commit_table: TableState,
    /// The commits fetched whole, or why they could not be, by index directory and generation.
    details: BTreeMap<(usize, i64), Result<GetCommitResult, String>>,
    segment_row: usize,
    segment_table: TableState,
    focus: Focus,
    /// Whether the details show the selected segment rather than the selected commit: the one
    /// of the two that had the focus last.
    segment_details: bool,
    details_scroll: u16,
}

impl Commits {
    /// The commits screen of these index directories, as shown; at least one.
    pub fn new(indexes: Vec<String>) -> Self {
        Self {
            lists: vec![None; indexes.len()],
            unreadable: vec![Vec::new(); indexes.len()],
            indexes,
            index_row: 0,
            index_table: TableState::new(),
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
        let index = self.index_row;
        let Some(commits) = &self.lists[index] else {
            return Some(Request::Commits(index));
        };
        let generation = commits.as_ref().ok()?.get(self.commit_row)?.generation;
        (!self.details.contains_key(&(index, generation)))
            .then_some(Request::Commit(index, generation))
    }

    /// The commits of the shown index directory; `None` until they are fetched.
    fn commits(&self) -> Option<&Result<Vec<Commit>, String>> {
        self.lists[self.index_row].as_ref()
    }

    /// Shows the commits of the index directory at `index`, and those that could not be read;
    /// the selection stays on its commit, or else goes to the opened one.
    pub fn commits_loaded(&mut self, index: usize, result: GetCommitsResult) -> Option<Request> {
        let commits = result.commits;
        let unreadable = result.unreadable;
        if index == self.index_row {
            let generation = self.selected_commit().map(|commit| commit.generation);
            let file = self
                .selected_unreadable()
                .map(|commit| commit.segments_file.clone());
            self.commit_row = generation
                .and_then(|generation| commits.iter().position(|c| c.generation == generation))
                .or_else(|| {
                    let at = unreadable
                        .iter()
                        .position(|c| Some(&c.segments_file) == file.as_ref());
                    at.map(|at| commits.len() + at)
                })
                .or_else(|| commits.iter().position(|commit| commit.current))
                .unwrap_or_default();
        }
        self.lists[index] = Some(Ok(commits));
        self.unreadable[index] = unreadable;
        self.next_request()
    }

    pub fn commits_failed(&mut self, index: usize, error: String) {
        self.lists[index] = Some(Err(error));
    }

    pub fn commit_loaded(&mut self, index: usize, generation: i64, result: GetCommitResult) {
        self.details.insert((index, generation), Ok(result));
    }

    pub fn commit_failed(&mut self, index: usize, generation: i64, error: String) {
        self.details.insert((index, generation), Err(error));
    }

    fn selected_commit(&self) -> Option<&Commit> {
        self.commits()?.as_ref().ok()?.get(self.commit_row)
    }

    /// The selected unreadable commit, if the selection is below the commits.
    fn selected_unreadable(&self) -> Option<&UnreadableCommit> {
        let commits = self.commits()?.as_ref().ok()?;
        let row = self.commit_row.checked_sub(commits.len())?;
        self.unreadable[self.index_row].get(row)
    }

    fn selected_details(&self) -> Option<&Result<GetCommitResult, String>> {
        self.details
            .get(&(self.index_row, self.selected_commit()?.generation))
    }

    /// Shows the commits of another index directory, with the opened commit selected.
    fn select_index(&mut self, row: usize) {
        if row == self.index_row {
            return;
        }
        self.index_row = row;
        self.commit_row = match self.commits() {
            Some(Ok(commits)) => commits
                .iter()
                .position(|commit| commit.current)
                .unwrap_or_default(),
            _ => 0,
        };
        self.segment_row = 0;
        self.details_scroll = 0;
    }

    /// The panels that Tab goes through, in order.
    fn panels(&self) -> &'static [Focus] {
        if self.indexes.len() > 1 {
            &[
                Focus::Indexes,
                Focus::Commits,
                Focus::Segments,
                Focus::Details,
            ]
        } else {
            &[Focus::Commits, Focus::Segments, Focus::Details]
        }
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
                let index = self.index_row;
                self.details.retain(|&(i, _), _| i != index);
                self.details_scroll = 0;
                Some(Request::Commits(index))
            }
            KeyCode::Tab | KeyCode::BackTab => {
                let panels = self.panels();
                let at = panels
                    .iter()
                    .position(|&panel| panel == self.focus)
                    .unwrap_or_default();
                let next = if key == KeyCode::Tab {
                    (at + 1) % panels.len()
                } else {
                    (at + panels.len() - 1) % panels.len()
                };
                self.focus(panels[next]);
                None
            }
            KeyCode::Enter if self.focus == Focus::Indexes => {
                self.focus(Focus::Commits);
                None
            }
            KeyCode::Enter if self.focus == Focus::Commits => {
                self.focus(Focus::Segments);
                None
            }
            _ => match self.focus {
                Focus::Indexes => {
                    let row = moved(self.index_row, self.indexes.len(), key)?;
                    self.select_index(row);
                    self.next_request()
                }
                Focus::Commits => {
                    let len = self.commits()?.as_ref().ok()?.len()
                        + self.unreadable[self.index_row].len();
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
            Focus::Indexes | Focus::Commits => false,
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
        let area = if self.indexes.len() > 1 {
            let rows = self.indexes.len().min(INDEX_ROWS);
            // Borders.
            let [indexes_area, rest] =
                Layout::vertical([Constraint::Length(rows as u16 + 2), Constraint::Fill(1)])
                    .areas(area);
            self.render_indexes(frame, indexes_area);
            rest
        } else {
            area
        };
        let unreadable = self.unreadable[self.index_row].len();
        let rows = match self.commits() {
            Some(Ok(commits)) => (commits.len() + unreadable).clamp(1, COMMIT_ROWS),
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

    fn render_indexes(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Indexes;
        let title = format!(" Indexes ({}) ", self.indexes.len());
        let rows = self
            .indexes
            .iter()
            .map(|index| Row::new([Cell::from(index.clone())]));
        self.index_table.select(Some(self.index_row));
        let table = Table::new(rows, [Constraint::Fill(1)])
            .row_highlight_style(highlight(focused))
            .block(panel(title, focused));
        frame.render_stateful_widget(table, area, &mut self.index_table);
    }

    fn render_commits(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Commits;
        // The list itself, not through `commits()`, which would borrow all of `self`.
        let commits = match self.lists[self.index_row].as_ref() {
            None => return message(frame, area, " Commits ", focused, "Reading the commits…"),
            Some(Err(error)) => {
                let text = format!("Cannot list the commits: {error}");
                return message(frame, area, " Commits ", focused, &text);
            }
            Some(Ok(commits)) => commits,
        };
        let unreadable = &self.unreadable[self.index_row];
        let title = if unreadable.is_empty() {
            format!(" Commits ({}; ● opened, r reads again) ", commits.len())
        } else {
            format!(
                " Commits ({}, {} unreadable; ● opened, r reads again) ",
                commits.len(),
                unreadable.len()
            )
        };
        if commits.is_empty() && unreadable.is_empty() {
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
        // Below the commits.
        let unreadable_rows = unreadable.iter().map(|commit| {
            Row::new([
                Cell::from("✗"),
                Cell::from(""),
                Cell::from(commit.segments_file.clone()),
                Cell::from(""),
                Cell::from(""),
                Cell::from(""),
                Cell::from(""),
                Cell::from(format!("unreadable: {}", escape_control(&commit.message))),
            ])
            .style(Style::new().fg(Color::Red))
        });
        let rows = rows.chain(unreadable_rows);
        let widths = [
            Constraint::Length(1),
            Constraint::Length(number_width(commits.iter().map(|c| c.generation)).min(3)),
            Constraint::Length(
                commits
                    .iter()
                    .map(|c| c.segments_file.len())
                    .chain(unreadable.iter().map(|c| c.segments_file.len()))
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
        if let Some(commit) = self.selected_unreadable() {
            let text = format!("{} could not be read.", commit.segments_file);
            return message(frame, area, " Segments ", focused, &text);
        }
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
            _ => self.selected_unreadable().map(|commit| {
                (
                    format!(" {} ", commit.segments_file),
                    unreadable_lines(commit),
                )
            }),
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

/// The details of an unreadable commit: why it could not be read, its line breaks kept.
fn unreadable_lines(commit: &UnreadableCommit) -> Vec<Line<'static>> {
    let mut lines = vec![
        Line::from("Could not be read:").style(Style::new().fg(Color::Red)),
        Line::from(""),
    ];
    lines.extend(
        commit
            .message
            .lines()
            .map(|line| Line::from(escape_control(line))),
    );
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

    fn list(commits: Vec<Commit>) -> GetCommitsResult {
        GetCommitsResult {
            commits,
            unreadable: Vec::new(),
        }
    }

    /// The commits 4 (opened) and 3, as the server lists them.
    fn loaded() -> Commits {
        let mut commits = Commits::new(vec!["index".to_owned()]);
        assert_eq!(
            commits.commits_loaded(0, list(vec![commit(4, true), commit(3, false)])),
            Some(Request::Commit(0, 4))
        );
        commits.commit_loaded(0, 4, details(4, true));
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
        let mut commits = Commits::new(vec!["index".to_owned()]);
        assert_eq!(commits.initial_request(), Some(Request::Commits(0)));
        assert_eq!(
            commits.commits_loaded(0, list(vec![commit(4, true), commit(3, false)])),
            Some(Request::Commit(0, 4))
        );
        commits.commit_loaded(0, 4, details(4, true));
        assert_eq!(commits.initial_request(), None);

        assert_eq!(
            commits.handle_key(KeyCode::Down),
            Some(Request::Commit(0, 3))
        );
        commits.commit_failed(0, 3, "gone".to_owned());
        // A failed commit is not fetched again by moving back to it.
        assert_eq!(commits.handle_key(KeyCode::Up), None);
        assert_eq!(commits.handle_key(KeyCode::Down), None);
    }

    #[test]
    fn selects_the_opened_commit_first() {
        let mut commits = Commits::new(vec!["index".to_owned()]);
        assert_eq!(
            commits.commits_loaded(0, list(vec![commit(5, false), commit(4, true)])),
            Some(Request::Commit(0, 4))
        );
    }

    #[test]
    fn reads_the_commits_again_and_keeps_the_selection() {
        let mut commits = loaded();
        commits.handle_key(KeyCode::Down);
        commits.commit_loaded(0, 3, details(3, false));

        assert_eq!(
            commits.handle_key(KeyCode::Char('r')),
            Some(Request::Commits(0))
        );
        // A newer commit came in; the selection stays on commit 3 and fetches it again.
        assert_eq!(
            commits.commits_loaded(
                0,
                list(vec![commit(5, false), commit(4, true), commit(3, false)])
            ),
            Some(Request::Commit(0, 3))
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
        commits.commit_failed(0, 3, "Corrupt commit".to_owned());

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

    #[test]
    fn shows_the_commits_that_could_not_be_read() {
        let mut commits = Commits::new(vec!["index".to_owned()]);
        let result = GetCommitsResult {
            commits: Vec::new(),
            unreadable: vec![UnreadableCommit {
                segments_file: "segments_2".to_owned(),
                message: "Corrupt index, missing file: _0.si".to_owned(),
            }],
        };
        assert_eq!(commits.commits_loaded(0, result), None);

        let screen = render(&mut commits, 120, 24);
        assert!(
            screen.contains("Commits (0, 1 unreadable; ● opened, r reads again)"),
            "{screen}"
        );
        assert!(
            screen.contains("segments_2")
                && screen.contains("unreadable: Corrupt index, missing file: _0.si"),
            "{screen}"
        );
        // The selection is on it, and the details say why, whole and with its line breaks.
        assert!(screen.contains("segments_2 could not be read."), "{screen}");
        assert!(screen.contains("Could not be read:"), "{screen}");
        assert_eq!(commits.handle_key(KeyCode::Down), None);
    }

    #[test]
    fn the_details_show_an_unreadable_commit_whole() {
        let mut commits = loaded();
        let message = "Corrupt index, missing file: /data/products/broken/_0.si \
                       (resource=MMapIndexInput(path=\"/data/products/broken/_0.si\"))\n\
                       Suppressed: checksum failed";
        commits.commits_loaded(
            0,
            GetCommitsResult {
                commits: vec![commit(4, true), commit(3, false)],
                unreadable: vec![UnreadableCommit {
                    segments_file: "segments_5".to_owned(),
                    message: message.to_owned(),
                }],
            },
        );
        // Past the two commits to the unreadable one, which needs nothing fetched.
        assert_eq!(
            commits.handle_key(KeyCode::Down),
            Some(Request::Commit(0, 3))
        );
        assert_eq!(commits.handle_key(KeyCode::Down), None);
        assert_eq!(commits.handle_key(KeyCode::Down), None);

        let screen = render(&mut commits, 100, 24);
        assert!(screen.contains("┌ segments_5 "), "{screen}");
        // Wrapped in the details, not cut at their width, and the line break kept.
        assert!(screen.contains("││roducts/broken/_0.si\"))"), "{screen}");
        assert!(screen.contains("││Suppressed: checksum failed"), "{screen}");

        // Reading the commits again keeps the selection on it.
        assert_eq!(
            commits.handle_key(KeyCode::Char('r')),
            Some(Request::Commits(0))
        );
        commits.commits_loaded(
            0,
            GetCommitsResult {
                commits: vec![commit(6, false), commit(4, true)],
                unreadable: vec![UnreadableCommit {
                    segments_file: "segments_5".to_owned(),
                    message: message.to_owned(),
                }],
            },
        );
        assert!(render(&mut commits, 100, 24).contains("┌ segments_5 "));
        // Back up to the commits.
        assert_eq!(commits.handle_key(KeyCode::Up), Some(Request::Commit(0, 4)));
    }

    #[test]
    fn shows_the_commits_of_the_selected_index() {
        let mut commits = Commits::new(vec!["a".to_owned(), "b".to_owned()]);
        assert_eq!(commits.initial_request(), Some(Request::Commits(0)));
        commits.commits_loaded(0, list(vec![commit(4, true)]));
        commits.handle_key(KeyCode::BackTab);
        assert_eq!(commits.handle_key(KeyCode::Down), Some(Request::Commits(1)));
        assert_eq!(
            commits.commits_loaded(1, list(vec![commit(7, false), commit(6, true)])),
            Some(Request::Commit(1, 6))
        );
        commits.commit_loaded(1, 6, details(6, true));
        let screen = render(&mut commits, 120, 30);
        assert!(screen.contains("Indexes (2)"), "{screen}");
        assert!(screen.contains("Segments of segments_6"), "{screen}");

        // Back to the first, whose commit was not fetched yet.
        assert_eq!(commits.handle_key(KeyCode::Up), Some(Request::Commit(0, 4)));
        // Tab goes round the four panels, back to the indexes; b has all it needs.
        for _ in 0..4 {
            commits.handle_key(KeyCode::Tab);
        }
        assert_eq!(commits.handle_key(KeyCode::Down), None);
        assert!(render(&mut commits, 120, 30).contains("Segments of segments_6"));
    }
}
