//! The check screen (ADR 0022): runs Lucene's `CheckIndex` on every index of the open path, with
//! or without a reader. The summary on top says what is broken per index, the output below shows
//! `CheckIndex`'s report as it arrives and follows its end.

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Paragraph, Wrap};

use crate::overview::{panel, relative};
use crate::protocol::check::{
    CheckIndexResult, DEFAULT_LEVEL, GetCheckIndexResult, IndexCheck, MAX_LEVEL, MIN_LEVEL,
};
use crate::text::count;
use crate::viewer::size;

/// The summary takes at most this share of the screen, in percent.
const SUMMARY_SHARE: u16 = 50;
const PAGE: usize = 10;
/// How far ←/→ scroll the output sideways.
const COLUMN_STEP: u16 = 20;

/// What the caller has to do for the check screen.
#[derive(Debug, PartialEq, Eq)]
pub enum Request {
    /// Start a check at this level, and report back with [`Check::started`] or
    /// [`Check::failed`].
    Start(i32),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Summary,
    Output,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum State {
    /// No check yet.
    Idle,
    /// Asked to start; `None` until the server has started the job.
    Running {
        job_id: Option<i64>,
    },
    Done(CheckIndexResult),
    Failed(String),
    /// Esc, or a reopen that stopped the server's jobs.
    Stopped(&'static str),
}

pub struct Check {
    /// The opened path, which the index directories are shown relative to.
    path: String,
    /// The level the next check runs at.
    level: i32,
    /// The level of the check shown.
    checked_level: i32,
    state: State,
    lines: Vec<String>,
    focus: Focus,
    summary_scroll: u16,
    /// The first output line shown.
    output_scroll: usize,
    /// Whether the output shows its end as lines arrive.
    follow: bool,
    /// The first column of the output shown, for lines wider than the panel.
    output_column: u16,
    /// How many output lines the last frame showed, for paging and following.
    output_rows: usize,
}

impl Check {
    /// The check screen of the path opened as `path`.
    pub fn new(path: &str) -> Self {
        Self {
            path: path.to_owned(),
            level: DEFAULT_LEVEL,
            checked_level: DEFAULT_LEVEL,
            state: State::Idle,
            lines: Vec::new(),
            focus: Focus::Output,
            summary_scroll: 0,
            output_scroll: 0,
            follow: true,
            output_column: 0,
            output_rows: 1,
        }
    }

    pub fn running(&self) -> bool {
        matches!(self.state, State::Running { .. })
    }

    /// The running check's job, once the server has started it.
    pub fn job(&self) -> Option<i64> {
        match self.state {
            State::Running { job_id } => job_id,
            _ => None,
        }
    }

    /// The first line the next poll asks for: the lines there are.
    pub fn from_line(&self) -> i32 {
        i32::try_from(self.lines.len()).unwrap_or(i32::MAX)
    }

    pub fn started(&mut self, job_id: i64) {
        if self.running() {
            self.state = State::Running {
                job_id: Some(job_id),
            };
        }
    }

    /// Adds the new lines, and shows the outcome once it is there.
    pub fn progress(&mut self, result: GetCheckIndexResult) {
        if !self.running() {
            return;
        }
        self.lines.extend(result.lines);
        if let Some(outcome) = result.result {
            self.state = State::Done(outcome);
        }
    }

    pub fn failed(&mut self, error: String) {
        self.state = State::Failed(error);
    }

    /// Esc cancelled the running check.
    pub fn cancelled(&mut self) {
        if self.running() {
            self.state = State::Stopped("Cancelled.");
        }
    }

    /// A reopen stopped the server's jobs, the running check among them.
    pub fn stopped_by_reopen(&mut self) {
        if self.running() {
            self.state = State::Stopped("Stopped: the index was reopened.");
        }
    }

    pub fn handle_key(&mut self, key: KeyCode) -> Option<Request> {
        match key {
            KeyCode::Enter if !self.running() => {
                self.state = State::Running { job_id: None };
                self.checked_level = self.level;
                self.lines.clear();
                self.summary_scroll = 0;
                self.output_scroll = 0;
                self.output_column = 0;
                self.follow = true;
                return Some(Request::Start(self.level));
            }
            KeyCode::Char('l') if !self.running() => {
                self.level = if self.level >= MAX_LEVEL {
                    MIN_LEVEL
                } else {
                    self.level + 1
                };
            }
            KeyCode::Tab | KeyCode::BackTab => {
                self.focus = match self.focus {
                    Focus::Summary => Focus::Output,
                    Focus::Output => Focus::Summary,
                };
            }
            key if self.focus == Focus::Summary => self.scroll_summary(key),
            key => self.scroll_output(key),
        }
        None
    }

    fn scroll_summary(&mut self, key: KeyCode) {
        let page = PAGE as u16;
        self.summary_scroll = match key {
            KeyCode::Down | KeyCode::Char('j') => self.summary_scroll.saturating_add(1),
            KeyCode::Up | KeyCode::Char('k') => self.summary_scroll.saturating_sub(1),
            KeyCode::PageDown => self.summary_scroll.saturating_add(page),
            KeyCode::PageUp => self.summary_scroll.saturating_sub(page),
            KeyCode::Home | KeyCode::Char('g') => 0,
            KeyCode::End | KeyCode::Char('G') => u16::MAX,
            _ => self.summary_scroll,
        };
    }

    fn scroll_output(&mut self, key: KeyCode) {
        let page = self.output_rows.max(1);
        let last = self.last_output_scroll();
        let scroll = match key {
            KeyCode::Down | KeyCode::Char('j') => self.output_scroll + 1,
            KeyCode::Up | KeyCode::Char('k') => self.output_scroll.saturating_sub(1),
            KeyCode::PageDown => self.output_scroll + page,
            KeyCode::PageUp => self.output_scroll.saturating_sub(page),
            KeyCode::Home | KeyCode::Char('g') => 0,
            KeyCode::End | KeyCode::Char('G') => last,
            KeyCode::Right => {
                self.output_column = self.output_column.saturating_add(COLUMN_STEP);
                return;
            }
            KeyCode::Left => {
                self.output_column = self.output_column.saturating_sub(COLUMN_STEP);
                return;
            }
            _ => return,
        };
        self.output_scroll = scroll.min(last);
        self.follow = self.output_scroll >= last;
    }

    /// The scroll that shows the last line at the bottom.
    fn last_output_scroll(&self) -> usize {
        self.lines.len().saturating_sub(self.output_rows)
    }

    /// The keys of the screen, for the footer.
    pub fn keys(&self) -> String {
        if self.running() {
            "Esc cancel check   Tab panel   ↑↓←→".to_owned()
        } else {
            format!(
                "⏎ check   l level: {} {}   Tab panel   ↑↓←→",
                self.level,
                level_name(self.level)
            )
        }
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let summary = self.summary_lines();
        let max_summary = (area.height * SUMMARY_SHARE / 100).max(3);
        // Wrapped, plus the borders.
        let inner_width = area.width.saturating_sub(2).max(1);
        let summary_rows = Paragraph::new(summary.clone())
            .wrap(Wrap { trim: false })
            .line_count(inner_width);
        let summary_height = (u16::try_from(summary_rows).unwrap_or(u16::MAX))
            .saturating_add(2)
            .min(max_summary);
        let [summary_area, output_area] =
            Layout::vertical([Constraint::Length(summary_height), Constraint::Fill(1)]).areas(area);

        let last = u16::try_from(summary_rows.saturating_sub(1)).unwrap_or(u16::MAX);
        self.summary_scroll = self.summary_scroll.min(last);
        frame.render_widget(
            Paragraph::new(summary)
                .wrap(Wrap { trim: false })
                .scroll((self.summary_scroll, 0))
                .block(panel(
                    format!(" Check ({}) ", self.title_level()),
                    self.focus == Focus::Summary,
                )),
            summary_area,
        );

        self.output_rows = usize::from(output_area.height.saturating_sub(2)).max(1);
        if self.follow {
            self.output_scroll = self.last_output_scroll();
        }
        self.output_scroll = self.output_scroll.min(self.last_output_scroll());
        let lines: Vec<Line> = self
            .lines
            .iter()
            .skip(self.output_scroll)
            .take(self.output_rows)
            .map(|line| output_line(line))
            .collect();
        let title = if self.lines.is_empty() {
            " Output ".to_owned()
        } else {
            format!(
                " Output: lines {}–{} of {} ",
                self.output_scroll + 1,
                (self.output_scroll + self.output_rows).min(self.lines.len()),
                self.lines.len()
            )
        };
        let widest = self.lines.iter().map(|line| line.chars().count()).max();
        let inner = output_area.width.saturating_sub(2);
        let last_column = u16::try_from(widest.unwrap_or_default())
            .unwrap_or(u16::MAX)
            .saturating_sub(inner);
        self.output_column = self.output_column.min(last_column);
        frame.render_widget(
            Paragraph::new(lines)
                .scroll((0, self.output_column))
                .block(panel(title, self.focus == Focus::Output)),
            output_area,
        );
    }

    fn title_level(&self) -> String {
        let level = match self.state {
            State::Idle => self.level,
            _ => self.checked_level,
        };
        format!("level {level}, {}", level_name(level))
    }

    fn summary_lines(&self) -> Vec<Line<'static>> {
        match &self.state {
            State::Idle => vec![
                Line::from(
                    "Lucene's CheckIndex reads every index of the path and reports what is \
                     broken. It writes nothing, and works without a reader too.",
                ),
                Line::from(""),
                Line::from(format!(
                    "⏎ checks at level {} ({}); l changes the level: 1 checksums of every file, \
                     2 also the integrity of every part of each segment, 3 also the slow checks.",
                    self.level,
                    level_name(self.level)
                )),
            ],
            State::Running { .. } => vec![Line::from(format!(
                "Checking… {} so far. Esc cancels.",
                count(self.lines.len(), "line")
            ))],
            State::Failed(error) => vec![Line::styled(
                format!("The check failed: {error}"),
                Style::new().fg(Color::Red),
            )],
            State::Stopped(message) => vec![Line::from(*message)],
            State::Done(result) => self.result_lines(result),
        }
    }

    fn result_lines(&self, result: &CheckIndexResult) -> Vec<Line<'static>> {
        let bad = result.indexes.iter().filter(|index| !index.clean).count();
        let took = format!("took {:.1} s", result.took_millis as f64 / 1000.0);
        let mut lines = vec![if bad == 0 {
            Line::styled(
                format!(
                    "OK: no problems found in {} ({took})",
                    index_count(result.indexes.len())
                ),
                Style::new().fg(Color::Green).add_modifier(Modifier::BOLD),
            )
        } else {
            Line::styled(
                format!(
                    "BAD: problems in {bad} of {} ({took})",
                    index_count(result.indexes.len())
                ),
                Style::new().fg(Color::Red).add_modifier(Modifier::BOLD),
            )
        }];
        let several = result.indexes.len() > 1;
        for index in &result.indexes {
            if several || !index.clean {
                lines.extend(self.index_lines(index, several));
            }
        }
        lines
    }

    fn index_lines(&self, index: &IndexCheck, named: bool) -> Vec<Line<'static>> {
        let (mark, color) = if index.clean {
            ("✓", Color::Green)
        } else {
            ("✗", Color::Red)
        };
        let mut head = vec![Span::styled(format!("{mark} "), Style::new().fg(color))];
        if named {
            head.push(Span::styled(
                relative(&self.path, &index.path),
                Style::new().add_modifier(Modifier::BOLD),
            ));
            if index.skipped {
                head.push(Span::raw(" (skipped when opened)"));
            }
            head.push(Span::raw(": "));
        }
        head.push(Span::raw(index_summary(index)));
        let mut lines = vec![Line::from(head)];
        for problem in index_problems(index) {
            lines.push(Line::styled(
                format!("    {problem}"),
                Style::new().fg(Color::Red),
            ));
        }
        for segment in index.segments.iter().filter(|s| s.error.is_some()) {
            lines.push(Line::styled(
                format!(
                    "    {} ({}, {}): {}",
                    segment.name,
                    count(usize::try_from(segment.max_doc).unwrap_or(0), "doc"),
                    size(segment.size_bytes),
                    segment.error.as_deref().unwrap_or_default()
                ),
                Style::new().fg(Color::Red),
            ));
        }
        lines
    }
}

/// What the summary line of an index says.
fn index_summary(index: &IndexCheck) -> String {
    if index.error.is_some() {
        return "could not be checked".to_owned();
    }
    let Some(file) = index.segments_file.as_deref() else {
        return "no readable commit".to_owned();
    };
    let segments = count(usize::try_from(index.segment_count).unwrap_or(0), "segment");
    if index.bad_segments > 0 {
        format!(
            "{file}, {} of {segments} broken, {} would be lost",
            index.bad_segments,
            count(usize::try_from(index.lost_docs).unwrap_or(0), "document")
        )
    } else if index.clean {
        format!("{file}, {segments}, no problems")
    } else {
        format!("{file}, {segments}")
    }
}

/// What is wrong with an index besides its broken segments.
fn index_problems(index: &IndexCheck) -> Vec<String> {
    let mut problems = Vec::new();
    if let Some(error) = &index.error {
        problems.push(error.clone());
        return problems;
    }
    if index.missing_segments {
        problems.push("A commit can't be read: see the output for why.".to_owned());
    }
    if index.tool_out_of_date {
        problems.push("The index was written by a newer Lucene than leia's.".to_owned());
    }
    if !index.valid_counter && !index.missing_segments {
        problems.push(
            "The commit's counter for segment names is not above every segment's name.".to_owned(),
        );
    }
    problems
}

/// `1 index`, `3 indexes`.
fn index_count(n: usize) -> String {
    if n == 1 {
        "1 index".to_owned()
    } else {
        format!("{n} indexes")
    }
}

pub fn level_name(level: i32) -> &'static str {
    match level {
        1 => "checksums",
        2 => "integrity",
        _ => "slow",
    }
}

/// An output line: tabs as spaces, other control characters dropped; errors in red, warnings in
/// yellow.
fn output_line(line: &str) -> Line<'static> {
    let text: String = line
        .chars()
        .flat_map(|c| match c {
            '\t' => vec![' '; 4],
            c if c.is_control() => Vec::new(),
            c => vec![c],
        })
        .collect();
    let trimmed = text.trim_start();
    let style = if trimmed.starts_with("ERROR") || text.contains("FAILED") {
        Style::new().fg(Color::Red)
    } else if trimmed.starts_with("WARNING") {
        Style::new().fg(Color::Yellow)
    } else {
        Style::new()
    };
    Line::styled(text, style)
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::check::SegmentCheck;

    fn index(path: &str, clean: bool) -> IndexCheck {
        IndexCheck {
            path: path.to_owned(),
            skipped: false,
            clean,
            error: None,
            segments_file: Some("segments_2".to_owned()),
            missing_segments: false,
            tool_out_of_date: false,
            segment_count: 2,
            bad_segments: 0,
            lost_docs: 0,
            valid_counter: true,
            segments: Vec::new(),
        }
    }

    fn broken(path: &str) -> IndexCheck {
        IndexCheck {
            bad_segments: 1,
            lost_docs: 3,
            segments: vec![SegmentCheck {
                name: "_1".to_owned(),
                max_doc: 3,
                codec: Some("Lucene103".to_owned()),
                compound: true,
                files: 4,
                size_bytes: 2048,
                has_deletions: false,
                open_reader_passed: true,
                error: Some("checksum failed".to_owned()),
            }],
            ..index(path, false)
        }
    }

    fn lines(range: std::ops::Range<usize>) -> Vec<String> {
        range.map(|i| format!("line {i}")).collect()
    }

    fn progress(lines: Vec<String>, result: Option<CheckIndexResult>) -> GetCheckIndexResult {
        GetCheckIndexResult {
            line_count: 0,
            lines,
            result,
        }
    }

    fn render(check: &mut Check, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| check.render(frame, frame.area()))
            .unwrap();
        let buffer = terminal.backend().buffer();
        (0..height)
            .map(|y| {
                (0..width)
                    .map(|x| buffer[(x, y)].symbol())
                    .collect::<String>()
                    .trim_end()
                    .to_owned()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    #[test]
    fn enter_starts_a_check_at_the_chosen_level() {
        let mut check = Check::new("/data");
        assert_eq!(check.handle_key(KeyCode::Char('l')), None);
        assert_eq!(check.handle_key(KeyCode::Enter), Some(Request::Start(2)));
        assert!(check.running());
        assert_eq!(check.job(), None);
        // No second check while one runs, and the level stays.
        assert_eq!(check.handle_key(KeyCode::Enter), None);
        check.handle_key(KeyCode::Char('l'));
        assert!(check.keys().starts_with("Esc cancel"));

        check.started(7);
        assert_eq!(check.job(), Some(7));
    }

    #[test]
    fn the_level_cycles() {
        let mut check = Check::new("/data");
        for level in [2, 3, 1] {
            check.handle_key(KeyCode::Char('l'));
            assert_eq!(check.level, level);
        }
    }

    #[test]
    fn collects_the_lines_until_the_outcome() {
        let mut check = Check::new("/data");
        check.handle_key(KeyCode::Enter);
        check.started(1);
        check.progress(progress(lines(0..3), None));
        assert_eq!(check.from_line(), 3);
        check.progress(progress(
            lines(3..5),
            Some(CheckIndexResult {
                indexes: vec![index("/data", true)],
                took_millis: 1200,
            }),
        ));

        assert_eq!(check.from_line(), 5);
        assert!(!check.running());
        assert_eq!(check.job(), None);
        let screen = render(&mut check, 60, 12);
        assert!(
            screen.contains("OK: no problems found in 1 index (took 1.2 s)"),
            "{screen}"
        );
        assert!(screen.contains("line 4"), "{screen}");
    }

    #[test]
    fn shows_what_is_broken_per_index() {
        let mut check = Check::new("/data");
        check.handle_key(KeyCode::Enter);
        check.started(1);
        check.progress(progress(
            Vec::new(),
            Some(CheckIndexResult {
                indexes: vec![
                    index("/data/a", true),
                    broken("/data/b"),
                    IndexCheck {
                        skipped: true,
                        error: Some("no segments* file found".to_owned()),
                        segments_file: None,
                        ..index("/data/c", false)
                    },
                ],
                took_millis: 50,
            }),
        ));

        let screen = render(&mut check, 100, 20);
        for expected in [
            "BAD: problems in 2 of 3 indexes (took 0.1 s)",
            "✓ a: segments_2, 2 segments, no problems",
            "✗ b: segments_2, 1 of 2 segments broken, 3 documents would be lost",
            "    _1 (3 docs, 2.0 KiB): checksum failed",
            "✗ c (skipped when opened): could not be checked",
            "    no segments* file found",
        ] {
            assert!(screen.contains(expected), "{expected:?} in\n{screen}");
        }
    }

    #[test]
    fn the_output_follows_its_end_until_scrolled_up() {
        let mut check = Check::new("/data");
        check.handle_key(KeyCode::Enter);
        check.started(1);
        check.progress(progress(lines(0..50), None));
        // 20 rows: the summary takes 3, the output 15 inside its borders.
        let screen = render(&mut check, 60, 20);
        assert!(screen.contains("line 49"), "{screen}");
        assert!(screen.contains("lines 36–50 of 50"), "{screen}");

        check.handle_key(KeyCode::PageUp);
        check.progress(progress(lines(50..60), None));
        let screen = render(&mut check, 60, 20);
        assert!(screen.contains("lines 21–35 of 60"), "{screen}");

        check.handle_key(KeyCode::End);
        check.progress(progress(lines(60..61), None));
        let screen = render(&mut check, 60, 20);
        assert!(screen.contains("line 60"), "{screen}");
    }

    #[test]
    fn left_and_right_scroll_wide_output() {
        let mut check = Check::new("/data");
        check.handle_key(KeyCode::Enter);
        check.progress(progress(vec![format!("{}END", "x".repeat(60))], None));
        assert!(!render(&mut check, 40, 12).contains("END"));

        for _ in 0..5 {
            check.handle_key(KeyCode::Right);
        }
        // No further than the end of the widest line.
        let screen = render(&mut check, 40, 12);
        assert!(screen.contains("xEND┃"), "{screen}");
        check.handle_key(KeyCode::Left);
        assert!(!render(&mut check, 40, 12).contains("END"));
    }

    #[test]
    fn cancelling_or_reopening_stops_a_running_check() {
        let mut check = Check::new("/data");
        check.handle_key(KeyCode::Enter);
        check.started(3);
        check.cancelled();
        assert!(!check.running());
        assert!(render(&mut check, 60, 10).contains("Cancelled."));

        check.handle_key(KeyCode::Enter);
        check.stopped_by_reopen();
        assert!(render(&mut check, 60, 10).contains("Stopped: the index was reopened."));
        // A check that is done stays as it is.
        check.stopped_by_reopen();
        assert!(render(&mut check, 60, 10).contains("Stopped: the index was reopened."));
    }

    #[test]
    fn shows_why_the_check_failed() {
        let mut check = Check::new("/data");
        check.handle_key(KeyCode::Enter);
        check.failed("No index is open (NO_INDEX_OPEN -32004)".to_owned());

        assert!(!check.running());
        assert!(render(&mut check, 80, 10).contains("The check failed: No index is open"));
    }

    #[test]
    fn output_lines_show_tabs_as_spaces_and_mark_errors() {
        assert_eq!(output_line("\tat x").to_string(), "    at x");
        assert_eq!(
            output_line("ERROR: x").style.fg,
            Some(Color::Red),
            "errors are red"
        );
        assert_eq!(
            output_line("    test: open reader.........FAILED").style.fg,
            Some(Color::Red)
        );
        assert_eq!(output_line("WARNING: y").style.fg, Some(Color::Yellow));
        assert_eq!(output_line("OK").style.fg, None);
    }
}
