//! The open-index prompt: a one-line path editor with directory completion, drawn as a popup. It
//! also shows why the last open failed.

use std::fs;
use std::path::{self, MAIN_SEPARATOR, Path, PathBuf};

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, Paragraph, Wrap};

/// The popup is at most this wide, borders included.
const MAX_WIDTH: u16 = 100;

/// What a key did to the prompt.
#[derive(Debug, PartialEq, Eq)]
pub enum PromptEvent {
    /// Nothing for the caller to do: the key edited the input, or was ignored.
    None,
    /// Enter on a non-blank input: open this path, exactly as typed.
    Submit(String),
    /// Esc: close the prompt.
    Cancel,
}

pub struct PathPrompt {
    input: String,
    /// Byte offset of the cursor in `input`, always on a char boundary.
    cursor: usize,
    /// The directories that the last Tab found, if it found more than one.
    candidates: Vec<String>,
    /// Why the last open failed.
    error: Option<String>,
    /// Set while the path is being opened.
    busy: bool,
    /// The directory that `~` stands for.
    home: Option<PathBuf>,
}

impl PathPrompt {
    pub fn new(input: &str, home: Option<PathBuf>) -> Self {
        Self {
            input: input.to_owned(),
            cursor: input.len(),
            candidates: Vec::new(),
            error: None,
            busy: false,
            home,
        }
    }

    pub fn input(&self) -> &str {
        &self.input
    }

    pub fn error(&self) -> Option<&str> {
        self.error.as_deref()
    }

    pub fn set_busy(&mut self) {
        self.busy = true;
        self.error = None;
        self.candidates.clear();
    }

    pub fn set_error(&mut self, error: String) {
        self.busy = false;
        self.error = Some(error);
    }

    pub fn handle_key(&mut self, key: KeyEvent) -> PromptEvent {
        let ctrl = key.modifiers.contains(KeyModifiers::CONTROL);
        let alt = key.modifiers.contains(KeyModifiers::ALT);
        match key.code {
            KeyCode::Enter if !self.input.trim().is_empty() => {
                return PromptEvent::Submit(self.input.clone());
            }
            KeyCode::Esc => return PromptEvent::Cancel,
            KeyCode::Tab => {
                let (input, candidates) = complete(&self.input, self.home.as_deref());
                self.candidates = candidates;
                self.set_input(input);
                return PromptEvent::None;
            }
            KeyCode::Left => self.cursor = self.previous_boundary(),
            KeyCode::Right => self.cursor = self.next_boundary(),
            KeyCode::Home => self.cursor = 0,
            KeyCode::End => self.cursor = self.input.len(),
            KeyCode::Char('a') if ctrl => self.cursor = 0,
            KeyCode::Char('e') if ctrl => self.cursor = self.input.len(),
            KeyCode::Backspace => self.delete(self.previous_boundary()..self.cursor),
            KeyCode::Delete => self.delete(self.cursor..self.next_boundary()),
            KeyCode::Char('u') if ctrl => self.delete(0..self.cursor),
            KeyCode::Char('k') if ctrl => self.delete(self.cursor..self.input.len()),
            KeyCode::Char('w') if ctrl => self.delete(self.component_start()..self.cursor),
            KeyCode::Char(c) if !ctrl && !alt => {
                self.input.insert(self.cursor, c);
                self.cursor += c.len_utf8();
                self.edited();
            }
            _ => {}
        }
        PromptEvent::None
    }

    fn set_input(&mut self, input: String) {
        if input != self.input {
            self.input = input;
            self.error = None;
        }
        self.cursor = self.input.len();
    }

    fn delete(&mut self, range: std::ops::Range<usize>) {
        if !range.is_empty() {
            self.input.replace_range(range.clone(), "");
            self.cursor = range.start;
            self.edited();
        }
    }

    /// The error and the candidates describe the input before the edit.
    fn edited(&mut self) {
        self.error = None;
        self.candidates.clear();
    }

    fn previous_boundary(&self) -> usize {
        self.input[..self.cursor]
            .char_indices()
            .next_back()
            .map_or(0, |(i, _)| i)
    }

    fn next_boundary(&self) -> usize {
        self.input[self.cursor..]
            .chars()
            .next()
            .map_or(self.cursor, |c| self.cursor + c.len_utf8())
    }

    /// Where the path component before the cursor starts, for Ctrl+W. Separators right before
    /// the cursor belong to that component, so repeated Ctrl+W walks up the path.
    fn component_start(&self) -> usize {
        let before = self.input[..self.cursor].trim_end_matches(is_separator);
        before.rfind(is_separator).map_or(0, |i| i + 1)
    }

    /// Draws the popup, centered in `area`.
    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let width = area.width.saturating_sub(4).min(MAX_WIDTH);
        let inner_width = width.saturating_sub(2).max(1);
        let error = self.error.as_deref().map(|message| {
            Paragraph::new(message)
                .style(Style::new().fg(Color::Red))
                .wrap(Wrap { trim: false })
        });
        let error_lines = error.as_ref().map_or(0, |error| {
            u16::try_from(error.line_count(inner_width)).unwrap_or(u16::MAX)
        });
        let candidate_lines = u16::from(!self.candidates.is_empty());
        // Input, candidates, error, hint, and the top and bottom border.
        let height = (1 + candidate_lines)
            .saturating_add(error_lines)
            .saturating_add(1 + 2)
            .min(area.height);
        let popup = area.centered(Constraint::Length(width), Constraint::Length(height));
        frame.render_widget(Clear, popup);
        let block = Block::bordered().title(" Open index ");
        let inner = block.inner(popup);
        frame.render_widget(block, popup);

        let [input, candidates, error_area, hint] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Length(candidate_lines),
            Constraint::Fill(1),
            Constraint::Length(1),
        ])
        .areas(inner);

        let (shown, cursor_x) = self.visible_input(input.width);
        frame.render_widget(Line::from(shown), input);
        if !self.busy {
            frame.set_cursor_position((input.x + cursor_x, input.y));
        }
        frame.render_widget(
            Line::from(self.candidates.join("  ")).style(Style::new().add_modifier(Modifier::DIM)),
            candidates,
        );
        if let Some(paragraph) = error {
            frame.render_widget(paragraph, error_area);
        }
        let hint_text = if self.busy {
            "Opening…"
        } else {
            "Enter open   Tab complete   Esc cancel"
        };
        frame.render_widget(
            Line::from(hint_text).style(Style::new().add_modifier(Modifier::DIM)),
            hint,
        );
    }

    /// The part of the input that fits into `width` cells with the cursor visible, and the
    /// cursor's column in it. Long input scrolls so that the cursor stays in the last cell.
    fn visible_input(&self, width: u16) -> (&str, u16) {
        let width = usize::from(width.max(1));
        let before = &self.input[..self.cursor];
        let mut start = 0;
        while Span::raw(&before[start..]).width() >= width {
            start += before[start..].chars().next().map_or(1, char::len_utf8);
        }
        (
            &self.input[start..],
            Span::raw(&before[start..]).width() as u16,
        )
    }
}

fn is_separator(c: char) -> bool {
    // `/` works on Windows too.
    c == '/' || path::is_separator(c)
}

/// Replaces a leading `~` with `home`. Paths in the prompt are typed, so no shell has expanded
/// them.
pub fn expand_home(path: &str, home: Option<&Path>) -> String {
    match (path.strip_prefix('~'), home.and_then(Path::to_str)) {
        (Some(rest), Some(home)) if rest.is_empty() || rest.starts_with(is_separator) => {
            format!("{home}{rest}")
        }
        _ => path.to_owned(),
    }
}

/// Completes the last component of `input` to the directories that start with it. One match
/// is completed with a trailing separator; several are completed to their common prefix and
/// returned as candidates. Hidden directories only match a component that starts with `.`.
pub fn complete(input: &str, home: Option<&Path>) -> (String, Vec<String>) {
    if input == "~" && home.is_some() {
        return (format!("~{MAIN_SEPARATOR}"), Vec::new());
    }
    let split = input.rfind(is_separator).map_or(0, |i| i + 1);
    let (dir, prefix) = input.split_at(split);
    let listed = if dir.is_empty() {
        PathBuf::from(".")
    } else {
        PathBuf::from(expand_home(dir, home))
    };
    let Ok(entries) = fs::read_dir(&listed) else {
        return (input.to_owned(), Vec::new());
    };
    let mut names: Vec<String> = entries
        .filter_map(Result::ok)
        // `Path::is_dir` follows symlinks, so a link to an index directory counts.
        .filter(|entry| entry.path().is_dir())
        .filter_map(|entry| entry.file_name().into_string().ok())
        .filter(|name| {
            name.starts_with(prefix) && (prefix.starts_with('.') || !name.starts_with('.'))
        })
        .collect();
    names.sort();
    match names.as_slice() {
        [] => (input.to_owned(), Vec::new()),
        [only] => (format!("{dir}{only}{MAIN_SEPARATOR}"), Vec::new()),
        [first, rest @ ..] => {
            let common = rest.iter().fold(first.as_str(), |common, name| {
                let len = common
                    .char_indices()
                    .zip(name.chars())
                    .find(|((_, a), b)| a != b)
                    .map_or(common.len().min(name.len()), |((i, _), _)| i);
                &common[..len]
            });
            (format!("{dir}{common}"), names)
        }
    }
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::test_support::TempDir;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn ctrl(c: char) -> KeyEvent {
        KeyEvent::new(KeyCode::Char(c), KeyModifiers::CONTROL)
    }

    fn typed(prompt: &mut PathPrompt, text: &str) {
        for c in text.chars() {
            prompt.handle_key(key(KeyCode::Char(c)));
        }
    }

    fn render(prompt: &PathPrompt, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| prompt.render(frame, frame.area()))
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
    fn edits_at_the_cursor() {
        let mut prompt = PathPrompt::new("/data/idx", None);

        prompt.handle_key(key(KeyCode::Left));
        prompt.handle_key(key(KeyCode::Left));
        typed(&mut prompt, "é");
        prompt.handle_key(key(KeyCode::Backspace));
        prompt.handle_key(key(KeyCode::Backspace));
        typed(&mut prompt, "ü");
        prompt.handle_key(key(KeyCode::Delete));

        assert_eq!(prompt.input(), "/data/üx");
        prompt.handle_key(key(KeyCode::Home));
        prompt.handle_key(key(KeyCode::Delete));
        prompt.handle_key(key(KeyCode::End));
        typed(&mut prompt, "y");
        assert_eq!(prompt.input(), "data/üxy");
    }

    #[test]
    fn control_keys_delete_by_component_and_line() {
        let mut prompt = PathPrompt::new("/data/indexes/products/", None);

        prompt.handle_key(ctrl('w'));
        assert_eq!(prompt.input(), "/data/indexes/");
        prompt.handle_key(ctrl('w'));
        assert_eq!(prompt.input(), "/data/");
        prompt.handle_key(ctrl('a'));
        prompt.handle_key(ctrl('k'));
        assert_eq!(prompt.input(), "");

        let mut prompt = PathPrompt::new("/data/index", None);
        prompt.handle_key(key(KeyCode::Left));
        prompt.handle_key(ctrl('u'));
        assert_eq!(prompt.input(), "x");
    }

    #[test]
    fn enter_submits_unless_blank_and_esc_cancels() {
        let mut prompt = PathPrompt::new("  ", None);
        assert_eq!(prompt.handle_key(key(KeyCode::Enter)), PromptEvent::None);

        typed(&mut prompt, "x");
        assert_eq!(
            prompt.handle_key(key(KeyCode::Enter)),
            PromptEvent::Submit("  x".to_owned())
        );
        assert_eq!(prompt.handle_key(key(KeyCode::Esc)), PromptEvent::Cancel);
    }

    #[test]
    fn control_characters_are_not_typed() {
        let mut prompt = PathPrompt::new("", None);

        prompt.handle_key(ctrl('x'));
        prompt.handle_key(KeyEvent::new(KeyCode::Char('b'), KeyModifiers::ALT));
        prompt.handle_key(KeyEvent::new(KeyCode::Char('B'), KeyModifiers::SHIFT));

        assert_eq!(prompt.input(), "B");
    }

    #[test]
    fn an_edit_clears_the_error() {
        let mut prompt = PathPrompt::new("/x", None);
        prompt.set_busy();
        prompt.set_error("No such file or directory: /x".to_owned());
        assert_eq!(prompt.error(), Some("No such file or directory: /x"));

        prompt.handle_key(key(KeyCode::Left));
        assert!(prompt.error().is_some());
        typed(&mut prompt, "y");
        assert_eq!(prompt.error(), None);
    }

    #[test]
    fn expands_the_home_directory() {
        let home = Some(Path::new("/home/me"));

        assert_eq!(expand_home("~", home), "/home/me");
        assert_eq!(expand_home("~/idx", home), "/home/me/idx");
        assert_eq!(expand_home("~other/idx", home), "~other/idx");
        assert_eq!(expand_home("/data/~", home), "/data/~");
        assert_eq!(expand_home("~/idx", None), "~/idx");
    }

    #[test]
    fn completes_directories() {
        let tmp = TempDir::new();
        for dir in ["products", "production", "orders", ".hidden"] {
            fs::create_dir(tmp.path().join(dir)).unwrap();
        }
        fs::write(tmp.path().join("prod.txt"), "").unwrap();
        let base = format!("{}{MAIN_SEPARATOR}", tmp.path().to_str().unwrap());

        assert_eq!(
            complete(&format!("{base}o"), None),
            (format!("{base}orders{MAIN_SEPARATOR}"), Vec::new())
        );
        assert_eq!(
            complete(&format!("{base}pr"), None),
            (
                format!("{base}product"),
                vec!["production".to_owned(), "products".to_owned()]
            )
        );
        assert_eq!(
            complete(&format!("{base}x"), None),
            (format!("{base}x"), Vec::new())
        );
        assert_eq!(
            complete(&format!("{base}."), None),
            (format!("{base}.hidden{MAIN_SEPARATOR}"), Vec::new())
        );
        let (_, all) = complete(&base, None);
        assert_eq!(all, ["orders", "production", "products"]);
    }

    #[test]
    fn completes_under_the_home_directory_but_keeps_the_tilde() {
        let home = TempDir::new();
        fs::create_dir(home.path().join("indexes")).unwrap();

        assert_eq!(
            complete("~", Some(home.path())),
            (format!("~{MAIN_SEPARATOR}"), Vec::new())
        );
        assert_eq!(
            complete("~/ind", Some(home.path())),
            (format!("~/indexes{MAIN_SEPARATOR}"), Vec::new())
        );
    }

    #[test]
    fn tab_completes_and_shows_candidates() {
        let tmp = TempDir::new();
        for dir in ["alpha", "alps"] {
            fs::create_dir(tmp.path().join(dir)).unwrap();
        }
        let base = format!("{}{MAIN_SEPARATOR}", tmp.path().to_str().unwrap());
        let mut prompt = PathPrompt::new(&format!("{base}a"), None);

        prompt.handle_key(key(KeyCode::Tab));

        assert_eq!(prompt.input(), format!("{base}alp"));
        let screen = render(&prompt, 200, 10);
        assert!(screen.contains("alpha  alps"), "{screen}");
        typed(&mut prompt, "h");
        assert!(!render(&prompt, 200, 10).contains("alpha  alps"));
    }

    #[test]
    fn shows_the_error_and_scrolls_long_input() {
        let mut prompt = PathPrompt::new(&format!("/{}/end", "x".repeat(100)), None);
        prompt.set_error("No such file or directory (INDEX_NOT_FOUND -32001)".to_owned());

        let screen = render(&prompt, 60, 12);

        assert!(screen.contains("Open index"), "{screen}");
        assert!(screen.contains("xxx/end"), "{screen}");
        assert!(screen.contains("INDEX_NOT_FOUND"), "{screen}");
        assert!(screen.contains("Esc cancel"), "{screen}");
    }

    #[test]
    fn the_popup_fits_a_word_wrapped_error() {
        let mut prompt = PathPrompt::new("/x", None);
        // Word wrapping leaves gaps at line ends, so this needs more lines than its width alone
        // suggests.
        prompt.set_error(format!("{} LAST", "abcdefghij ".repeat(12)));

        let screen = render(&prompt, 40, 30);

        assert!(screen.contains("│LAST"), "{screen}");
        assert!(screen.contains("│Enter open"), "{screen}");
    }

    #[test]
    fn visible_input_keeps_the_cursor_in_view() {
        let mut prompt = PathPrompt::new("abcdefghij", None);

        assert_eq!(prompt.visible_input(20), ("abcdefghij", 10));
        assert_eq!(prompt.visible_input(5), ("ghij", 4));
        prompt.handle_key(key(KeyCode::Home));
        assert_eq!(prompt.visible_input(5), ("abcdefghij", 0));
    }
}
