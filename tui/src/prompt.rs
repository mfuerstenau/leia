//! The open-index prompt: a one-line path editor with directory completion and the recent paths,
//! drawn as a popup. It also shows the `Directory` to open with, and why the last open failed.

use std::fs;
use std::path::{self, MAIN_SEPARATOR, Path, PathBuf};

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Block, Clear, Paragraph, Wrap};

use crate::input::{Edit, LineInput};
use crate::protocol::index::DirectoryImpl;

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
    input: LineInput,
    /// The directories that the last Tab found, if it found more than one.
    candidates: Vec<String>,
    /// Why the last open failed.
    error: Option<String>,
    /// Set while the path is being opened.
    busy: bool,
    /// The directory that `~` stands for.
    home: Option<PathBuf>,
    /// The recently opened paths, newest first (ADR 0020).
    recent: Vec<String>,
    /// While Up and Down step through `recent`: the input before the first Up, and the position
    /// among the recent paths other than that input.
    browsing: Option<(String, usize)>,
    /// The `Directory` to open with; Ctrl+D cycles it.
    directory: DirectoryImpl,
}

impl PathPrompt {
    pub fn new(input: &str, home: Option<PathBuf>) -> Self {
        Self {
            input: LineInput::new(input),
            candidates: Vec::new(),
            error: None,
            busy: false,
            home,
            recent: Vec::new(),
            browsing: None,
            directory: DirectoryImpl::default(),
        }
    }

    /// Up and Down step through `recent`, newest first.
    pub fn with_recent(mut self, recent: &[String]) -> Self {
        self.recent = recent.to_vec();
        self
    }

    pub fn with_directory(mut self, directory: DirectoryImpl) -> Self {
        self.directory = directory;
        self
    }

    pub fn directory(&self) -> DirectoryImpl {
        self.directory
    }

    pub fn input(&self) -> &str {
        self.input.text()
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
        match key.code {
            KeyCode::Enter if !self.input().trim().is_empty() => {
                return PromptEvent::Submit(self.input().to_owned());
            }
            KeyCode::Esc => return PromptEvent::Cancel,
            KeyCode::Up => {
                self.browse(true);
                return PromptEvent::None;
            }
            KeyCode::Down => {
                self.browse(false);
                return PromptEvent::None;
            }
            KeyCode::Char('d') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.directory = match self.directory {
                    DirectoryImpl::Fs => DirectoryImpl::Mmap,
                    DirectoryImpl::Mmap => DirectoryImpl::Nio,
                    DirectoryImpl::Nio => DirectoryImpl::Fs,
                };
                return PromptEvent::None;
            }
            KeyCode::Tab => {
                self.browsing = None;
                let (input, candidates) = complete(self.input(), self.home.as_deref());
                self.candidates = candidates;
                if input != self.input() {
                    self.error = None;
                }
                self.input.set(input);
                return PromptEvent::None;
            }
            _ => {}
        }
        if self.input.handle_key(key, component_start) == Edit::Changed {
            // The error and the candidates describe the input before the edit. The next Up starts
            // from the edited input.
            self.error = None;
            self.candidates.clear();
            self.browsing = None;
        }
        PromptEvent::None
    }

    /// Steps to an older (`older`) or newer recent path, like a shell's history. The path that was
    /// in the input before the first Up is skipped, and Down past the newest brings it back.
    fn browse(&mut self, older: bool) {
        let (typed, position) = match self.browsing.take() {
            Some((typed, position)) => (typed, Some(position)),
            None => (self.input().to_owned(), None),
        };
        let entries: Vec<&String> = self.recent.iter().filter(|path| **path != typed).collect();
        let next = match (older, position) {
            (true, None) => (!entries.is_empty()).then_some(0),
            (true, Some(position)) => Some((position + 1).min(entries.len() - 1)),
            (false, None | Some(0)) => None,
            (false, Some(position)) => Some(position - 1),
        };
        if position.is_some() || next.is_some() {
            let input = next.map_or_else(|| typed.clone(), |next| entries[next].clone());
            if input != self.input() {
                self.error = None;
                self.candidates.clear();
            }
            self.input.set(input);
        }
        self.browsing = next.map(|next| (typed, next));
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
        let block = Block::bordered().title(" Open index ").title_bottom(
            Line::from(format!(
                " ^D directory: {} ",
                directory_label(self.directory)
            ))
            .right_aligned(),
        );
        let inner = block.inner(popup);
        frame.render_widget(block, popup);

        let [input, candidates, error_area, hint] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Length(candidate_lines),
            Constraint::Fill(1),
            Constraint::Length(1),
        ])
        .areas(inner);

        let (shown, cursor_x) = self.input.visible(input.width);
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
        } else if self.browsing.is_some() || self.recent.iter().any(|path| path != self.input()) {
            "Enter open   Tab complete   ↑↓ recent   Esc cancel"
        } else {
            "Enter open   Tab complete   Esc cancel"
        };
        frame.render_widget(
            Line::from(hint_text).style(Style::new().add_modifier(Modifier::DIM)),
            hint,
        );
    }
}

/// How the prompt names a `Directory`.
fn directory_label(directory: DirectoryImpl) -> &'static str {
    match directory {
        DirectoryImpl::Fs => "FS (auto)",
        DirectoryImpl::Mmap => "MMap",
        DirectoryImpl::Nio => "NIO",
    }
}

/// Where the path component before the cursor starts, for Ctrl+W. Separators right before the
/// cursor belong to that component, so repeated Ctrl+W walks up the path.
fn component_start(before: &str) -> usize {
    let before = before.trim_end_matches(is_separator);
    before.rfind(is_separator).map_or(0, |i| i + 1)
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

    use ratatui::crossterm::event::KeyModifiers;

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

    fn recent(paths: &[&str]) -> Vec<String> {
        paths.iter().map(|path| (*path).to_owned()).collect()
    }

    #[test]
    fn up_and_down_step_through_the_recent_paths_but_skip_the_input() {
        let mut prompt = PathPrompt::new("/b", None).with_recent(&recent(&["/a", "/b", "/c"]));
        prompt.set_error("Not a Lucene index".to_owned());

        prompt.handle_key(key(KeyCode::Up));
        assert_eq!(prompt.input(), "/a");
        assert_eq!(prompt.error(), None);
        prompt.handle_key(key(KeyCode::Up));
        assert_eq!(prompt.input(), "/c");
        prompt.handle_key(key(KeyCode::Up));
        assert_eq!(prompt.input(), "/c", "the oldest stays");
        prompt.handle_key(key(KeyCode::Down));
        assert_eq!(prompt.input(), "/a");
        prompt.handle_key(key(KeyCode::Down));
        assert_eq!(prompt.input(), "/b", "back to the input");
        prompt.handle_key(key(KeyCode::Down));
        assert_eq!(prompt.input(), "/b");
        prompt.handle_key(key(KeyCode::Up));
        assert_eq!(prompt.input(), "/a");
    }

    #[test]
    fn an_edit_starts_over_from_the_edited_input() {
        let mut prompt = PathPrompt::new("", None).with_recent(&recent(&["/a", "/b"]));

        prompt.handle_key(key(KeyCode::Up));
        prompt.handle_key(key(KeyCode::Up));
        typed(&mut prompt, "x");
        assert_eq!(prompt.input(), "/bx");
        prompt.handle_key(key(KeyCode::Up));
        assert_eq!(prompt.input(), "/a");
        prompt.handle_key(key(KeyCode::Down));
        assert_eq!(prompt.input(), "/bx");
    }

    #[test]
    fn up_without_recent_paths_keeps_the_input() {
        let mut prompt = PathPrompt::new("/x", None).with_recent(&recent(&["/x"]));

        prompt.handle_key(key(KeyCode::Up));
        prompt.handle_key(key(KeyCode::Down));

        assert_eq!(prompt.input(), "/x");
        assert!(!render(&prompt, 80, 8).contains("recent"));
    }

    #[test]
    fn ctrl_d_cycles_the_directory() {
        let mut prompt = PathPrompt::new("/x", None).with_directory(DirectoryImpl::Mmap);
        assert!(render(&prompt, 80, 8).contains("^D directory: MMap"));

        prompt.handle_key(ctrl('d'));
        assert_eq!(prompt.directory(), DirectoryImpl::Nio);
        assert!(render(&prompt, 80, 8).contains("^D directory: NIO"));
        prompt.handle_key(ctrl('d'));
        assert_eq!(prompt.directory(), DirectoryImpl::Fs);
        assert!(render(&prompt, 80, 8).contains("^D directory: FS (auto)"));
        assert_eq!(prompt.input(), "/x");
    }

    #[test]
    fn the_hint_fits_with_the_recent_paths() {
        let prompt = PathPrompt::new("/x", None).with_recent(&recent(&["/a"]));

        let screen = render(&prompt, 60, 8);

        assert!(screen.contains("↑↓ recent   Esc cancel"), "{screen}");
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
}
