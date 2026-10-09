//! The jars (ADR 0024): the default folder, whose jars are loaded at every start, and the jars
//! popup, which lists the loaded jars and adds more while leia runs.

use std::ffi::OsStr;
use std::fs;
use std::path::{Path, PathBuf};

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Clear, Paragraph, Wrap};

use crate::input::{Edit, LineInput};
use crate::prompt::{complete_with_files, component_start};
use crate::protocol::jars::AddJarsResult;
use crate::text::count;

/// The popup is at most this wide, borders included.
const MAX_WIDTH: u16 = 100;

/// What the input line starts with.
const INPUT_LABEL: &str = "Add: ";

/// The default folder of the platform: `%APPDATA%\leia\jars` on Windows; elsewhere
/// `$XDG_CONFIG_HOME/leia/jars`, or `~/.config/leia/jars` without it. `None` if the platform's
/// variables don't say where.
pub fn default_dir(home: Option<&Path>) -> Option<PathBuf> {
    if cfg!(windows) {
        app_data_dir(std::env::var_os("APPDATA").as_deref())
    } else {
        xdg_config_dir(std::env::var_os("XDG_CONFIG_HOME").as_deref(), home)
    }
}

fn app_data_dir(app_data: Option<&OsStr>) -> Option<PathBuf> {
    let dir = Path::new(app_data?);
    dir.is_absolute().then(|| dir.join("leia").join("jars"))
}

/// The XDG Base Directory spec says that a relative `XDG_CONFIG_HOME` is to be ignored.
fn xdg_config_dir(xdg_config_home: Option<&OsStr>, home: Option<&Path>) -> Option<PathBuf> {
    let config = xdg_config_home
        .map(Path::new)
        .filter(|dir| dir.is_absolute())
        .map(Path::to_path_buf)
        .or_else(|| home.map(|home| home.join(".config")))?;
    Some(config.join("leia").join("jars"))
}

/// Whether a file name is a jar's, as the server sees it: it ends with `.jar`, in any case.
pub fn is_jar_name(name: &str) -> bool {
    name.to_lowercase().ends_with(".jar")
}

/// Whether `dir` has a jar directly in it.
fn has_jars(dir: &Path) -> bool {
    fs::read_dir(dir).is_ok_and(|entries| {
        entries.filter_map(Result::ok).any(|entry| {
            entry.path().is_file() && entry.file_name().to_str().is_some_and(is_jar_name)
        })
    })
}

/// The default folder, and whether its jars were loaded.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DefaultJars {
    /// `None` if the platform doesn't say where it is.
    pub dir: Option<PathBuf>,
    pub state: DefaultJarsState,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum DefaultJarsState {
    /// Passed to the server as one more `--jar`.
    Loaded,
    /// Missing, or without a jar directly in it.
    #[default]
    NoJars,
    /// Left out by `--no-default-jars`.
    Skipped,
}

impl DefaultJars {
    /// Looks into `dir`: its jars are loaded if it has any, unless `skip`.
    pub fn find(dir: Option<PathBuf>, skip: bool) -> Self {
        let state = match &dir {
            _ if skip => DefaultJarsState::Skipped,
            Some(dir) if has_jars(dir) => DefaultJarsState::Loaded,
            _ => DefaultJarsState::NoJars,
        };
        Self { dir, state }
    }

    /// The folder to pass to the server, if its jars are loaded.
    pub fn loaded_dir(&self) -> Option<&Path> {
        self.dir
            .as_deref()
            .filter(|_| self.state == DefaultJarsState::Loaded)
    }
}

/// What a key did to the popup.
#[derive(Debug, PartialEq, Eq)]
pub enum JarsEvent {
    /// Nothing for the caller to do.
    None,
    /// Enter on a non-blank input: add the jar or directory at this path, exactly as typed.
    Submit(String),
    /// Esc: close the popup.
    Close,
}

pub struct JarsPopup {
    default: DefaultJars,
    /// The directory that `~` stands for.
    home: Option<PathBuf>,
    /// The loaded jars, or why they couldn't be listed; `None` until they are fetched.
    jars: Option<Result<Vec<String>, String>>,
    input: LineInput,
    /// The names that the last Tab found, if it found more than one.
    candidates: Vec<String>,
    /// What the last addition did, or why it failed.
    outcome: Option<Result<AddJarsResult, String>>,
    /// Set while the jars are being added.
    busy: bool,
    /// The first jar shown.
    scroll: usize,
    /// How far the list could scroll when it was last drawn.
    max_scroll: usize,
    /// How many jars the list showed when it was last drawn.
    page: usize,
}

impl JarsPopup {
    pub fn new(default: DefaultJars, home: Option<PathBuf>) -> Self {
        Self {
            default,
            home,
            jars: None,
            input: LineInput::new(""),
            candidates: Vec::new(),
            outcome: None,
            busy: false,
            scroll: 0,
            max_scroll: 0,
            page: 1,
        }
    }

    pub fn input(&self) -> &str {
        self.input.text()
    }

    pub fn jars_loaded(&mut self, jars: Vec<String>) {
        self.jars = Some(Ok(jars));
    }

    pub fn jars_failed(&mut self, error: String) {
        self.jars = Some(Err(error));
    }

    pub fn set_busy(&mut self) {
        self.busy = true;
        self.outcome = None;
        self.candidates.clear();
    }

    /// Shows what the addition did, lists the new jars and empties the input.
    pub fn added(&mut self, result: AddJarsResult) {
        self.busy = false;
        if let Some(Ok(jars)) = &mut self.jars {
            jars.extend(result.jars.iter().cloned());
            // The new jars are last; the next draw clamps this to the end of the list.
            self.scroll = usize::MAX;
        }
        self.input.set(String::new());
        self.outcome = Some(Ok(result));
    }

    /// Shows why the jars couldn't be added; the input can be edited again.
    pub fn add_failed(&mut self, error: String) {
        self.busy = false;
        self.outcome = Some(Err(error));
    }

    pub fn handle_key(&mut self, key: KeyEvent) -> JarsEvent {
        match key.code {
            KeyCode::Enter if !self.input().trim().is_empty() => {
                return JarsEvent::Submit(self.input().to_owned());
            }
            KeyCode::Esc => return JarsEvent::Close,
            KeyCode::Up => self.scroll_to(self.shown_scroll().saturating_sub(1)),
            KeyCode::Down => self.scroll_to(self.shown_scroll().saturating_add(1)),
            KeyCode::PageUp => self.scroll_to(self.shown_scroll().saturating_sub(self.page)),
            KeyCode::PageDown => self.scroll_to(self.shown_scroll().saturating_add(self.page)),
            KeyCode::Tab => {
                let (input, candidates) =
                    complete_with_files(self.input(), self.home.as_deref(), is_jar_name);
                self.candidates = candidates;
                if input != self.input() {
                    self.outcome = None;
                }
                self.input.set(input);
            }
            _ => {
                if self.input.handle_key(key, component_start) == Edit::Changed {
                    // The outcome and the candidates describe the input before the edit.
                    self.outcome = None;
                    self.candidates.clear();
                }
            }
        }
        JarsEvent::None
    }

    /// The first jar that the last draw showed.
    fn shown_scroll(&self) -> usize {
        self.scroll.min(self.max_scroll)
    }

    fn scroll_to(&mut self, scroll: usize) {
        self.scroll = scroll.min(self.max_scroll);
    }

    /// Draws the popup, centered in `area`.
    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let width = area.width.saturating_sub(4).min(MAX_WIDTH);
        let inner_width = width.saturating_sub(2).max(1);
        let outcome = self.outcome.as_ref().map(|outcome| {
            let lines = match outcome {
                Ok(result) => outcome_lines(result),
                Err(error) => vec![Line::styled(error.clone(), Style::new().fg(Color::Red))],
            };
            Paragraph::new(lines).wrap(Wrap { trim: false })
        });
        let outcome_lines = outcome.as_ref().map_or(0, |outcome| {
            u16::try_from(outcome.line_count(inner_width)).unwrap_or(u16::MAX)
        });
        let candidate_lines = u16::from(!self.candidates.is_empty());
        let (header, jar_lines) = self.jar_lines();
        // Default folder, header, input, candidates, outcome, hint, and the top and bottom border.
        let fixed = (1 + 1 + 1 + candidate_lines)
            .saturating_add(outcome_lines)
            .saturating_add(1 + 2);
        let wanted = u16::try_from(jar_lines.len()).unwrap_or(u16::MAX);
        let height = fixed.saturating_add(wanted).min(area.height);
        let list_height = height.saturating_sub(fixed).min(wanted);
        let popup = area.centered(Constraint::Length(width), Constraint::Length(height));
        frame.render_widget(Clear, popup);
        let block = Block::bordered().title(" Jars ");
        let inner = block.inner(popup);
        frame.render_widget(block, popup);

        let [
            default_area,
            header_area,
            list,
            input,
            candidates,
            outcome_area,
            hint,
        ] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Length(1),
            Constraint::Length(list_height),
            Constraint::Length(1),
            Constraint::Length(candidate_lines),
            Constraint::Fill(1),
            Constraint::Length(1),
        ])
        .areas(inner);

        frame.render_widget(self.default_line(), default_area);
        frame.render_widget(header, header_area);
        self.page = usize::from(list_height).max(1);
        self.max_scroll = jar_lines.len().saturating_sub(usize::from(list_height));
        self.scroll = self.shown_scroll();
        let shown: Vec<Line> = jar_lines
            .into_iter()
            .skip(self.scroll)
            .take(usize::from(list_height))
            .collect();
        frame.render_widget(Paragraph::new(shown), list);

        let label = u16::try_from(INPUT_LABEL.len()).unwrap_or(0);
        let [label_area, input_area] =
            Layout::horizontal([Constraint::Length(label), Constraint::Fill(1)]).areas(input);
        frame.render_widget(Line::from(INPUT_LABEL), label_area);
        let (text, cursor_x) = self.input.visible(input_area.width);
        frame.render_widget(Line::from(text), input_area);
        if !self.busy {
            frame.set_cursor_position((input_area.x + cursor_x, input_area.y));
        }
        frame.render_widget(
            Line::from(self.candidates.join("  ")).style(Style::new().add_modifier(Modifier::DIM)),
            candidates,
        );
        if let Some(paragraph) = outcome {
            frame.render_widget(paragraph, outcome_area);
        }
        let hint_text = if self.busy {
            "Loading…"
        } else if self.max_scroll > 0 {
            "Enter add   Tab complete   ↑↓ scroll   Esc close"
        } else {
            "Enter add   Tab complete   Esc close"
        };
        frame.render_widget(
            Line::from(hint_text).style(Style::new().add_modifier(Modifier::DIM)),
            hint,
        );
    }

    fn default_line(&self) -> Line<'static> {
        let dim = Style::new().add_modifier(Modifier::DIM);
        let Some(dir) = &self.default.dir else {
            return Line::from(vec![
                Span::raw("Default folder: "),
                Span::styled("none (no home directory)", dim),
            ]);
        };
        let note = match self.default.state {
            DefaultJarsState::Loaded => "",
            DefaultJarsState::NoJars => "  (no jars in it)",
            DefaultJarsState::Skipped => "  (left out: --no-default-jars)",
        };
        Line::from(vec![
            Span::raw("Default folder: "),
            Span::raw(self.shorten(dir)),
            Span::styled(note, dim),
        ])
    }

    /// The line above the list, and a line for each jar: its file name, then where it is.
    fn jar_lines(&self) -> (Line<'static>, Vec<Line<'static>>) {
        let jars = match &self.jars {
            None => return (Line::from("Listing the jars…"), Vec::new()),
            Some(Err(error)) => {
                let line = Line::styled(
                    format!("Cannot list the jars: {error}"),
                    Style::new().fg(Color::Red),
                );
                return (line, Vec::new());
            }
            Some(Ok(jars)) if jars.is_empty() => {
                return (Line::from("No jars loaded"), Vec::new());
            }
            Some(Ok(jars)) => jars,
        };
        let dim = Style::new().add_modifier(Modifier::DIM);
        let default_dir = self.default.loaded_dir();
        let lines = jars
            .iter()
            .map(|jar| {
                let path = Path::new(jar);
                let name = path
                    .file_name()
                    .map_or_else(|| jar.clone(), |name| name.to_string_lossy().into_owned());
                let place = match path.parent() {
                    Some(parent) if default_dir == Some(parent) => "default folder".to_owned(),
                    Some(parent) => self.shorten(parent),
                    None => String::new(),
                };
                Line::from(vec![
                    Span::raw(format!("  {name}  ")),
                    Span::styled(place, dim),
                ])
            })
            .collect();
        (
            Line::from(format!("Loaded: {}", count(jars.len(), "jar"))),
            lines,
        )
    }

    /// `path` with the home directory as `~`.
    fn shorten(&self, path: &Path) -> String {
        match self
            .home
            .as_deref()
            .and_then(|home| path.strip_prefix(home).ok())
        {
            Some(rest) if rest.as_os_str().is_empty() => "~".to_owned(),
            Some(rest) => Path::new("~").join(rest).display().to_string(),
            None => path.display().to_string(),
        }
    }
}

/// What an addition did: how many jars were loaded, then the names they added by kind.
fn outcome_lines(result: &AddJarsResult) -> Vec<Line<'static>> {
    let already = result.already_loaded.len();
    if result.jars.is_empty() {
        let text = if already == 1 {
            "The jar is already loaded; nothing was loaded".to_owned()
        } else {
            format!("All {already} jars are already loaded; nothing was loaded")
        };
        return vec![Line::styled(text, Style::new().fg(Color::Yellow))];
    }
    let mut summary = format!("Loaded {}", count(result.jars.len(), "jar"));
    if already > 0 {
        summary.push_str(&format!(" ({already} already loaded)"));
    }
    let added = &result.added;
    let presets: Vec<String> = added
        .analyzers
        .iter()
        .map(|class_name| {
            class_name
                .rsplit_once('.')
                .map_or(class_name.as_str(), |(_, name)| name)
                .to_owned()
        })
        .collect();
    let kinds: [(&str, &[String]); 8] = [
        ("preset", &presets),
        ("char filter", &added.char_filters),
        ("tokenizer", &added.tokenizers),
        ("token filter", &added.token_filters),
        ("codec", &added.codecs),
        ("postings format", &added.postings_formats),
        ("doc values format", &added.doc_values_formats),
        ("KNN vectors format", &added.knn_vectors_formats),
    ];
    let details: Vec<Line<'static>> = kinds
        .into_iter()
        .filter(|(_, names)| !names.is_empty())
        .map(|(noun, names)| {
            Line::from(format!(
                "  {}: {}",
                count(names.len(), noun),
                names.join(", ")
            ))
        })
        .collect();
    let green = Style::new().fg(Color::Green);
    if details.is_empty() {
        summary.push_str(if result.jars.len() == 1 {
            ", which adds nothing new: each of its names was taken"
        } else {
            ", which add nothing new: each of their names was taken"
        });
        return vec![Line::styled(summary, Style::new().fg(Color::Yellow))];
    }
    summary.push(':');
    let mut lines = vec![Line::styled(summary, green)];
    lines.extend(details);
    lines
}

#[cfg(test)]
mod tests {
    use std::path::MAIN_SEPARATOR;

    use ratatui::Terminal;
    use ratatui::backend::TestBackend;
    use ratatui::crossterm::event::KeyModifiers;

    use super::*;
    use crate::protocol::jars::JarComponents;
    use crate::test_support::TempDir;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn typed(popup: &mut JarsPopup, text: &str) {
        for c in text.chars() {
            popup.handle_key(key(KeyCode::Char(c)));
        }
    }

    fn render(popup: &mut JarsPopup, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| popup.render(frame, frame.area()))
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

    fn strings(values: &[&str]) -> Vec<String> {
        values.iter().map(|value| (*value).to_owned()).collect()
    }

    fn loaded(dir: &str) -> DefaultJars {
        DefaultJars {
            dir: Some(PathBuf::from(dir)),
            state: DefaultJarsState::Loaded,
        }
    }

    #[test]
    fn finds_the_default_folder_of_the_platform() {
        let home = Some(Path::new("/home/me"));

        assert_eq!(
            xdg_config_dir(Some(OsStr::new("/cfg")), home),
            Some(PathBuf::from("/cfg/leia/jars"))
        );
        assert_eq!(
            xdg_config_dir(Some(OsStr::new("relative")), home),
            Some(PathBuf::from("/home/me/.config/leia/jars"))
        );
        assert_eq!(xdg_config_dir(None, None), None);
        assert_eq!(app_data_dir(Some(OsStr::new("relative"))), None);
        assert_eq!(app_data_dir(None), None);
    }

    #[test]
    fn the_default_folder_is_loaded_only_with_a_jar_in_it() {
        let tmp = TempDir::new();
        let dir = tmp.path().join("jars");
        assert_eq!(
            DefaultJars::find(Some(dir.clone()), false).state,
            DefaultJarsState::NoJars
        );
        fs::create_dir(&dir).unwrap();
        fs::create_dir(dir.join("sub.jar")).unwrap();
        fs::write(dir.join("notes.txt"), "").unwrap();
        assert_eq!(
            DefaultJars::find(Some(dir.clone()), false).loaded_dir(),
            None
        );

        fs::write(dir.join("Analyzers.JAR"), "").unwrap();

        let found = DefaultJars::find(Some(dir.clone()), false);
        assert_eq!(found.state, DefaultJarsState::Loaded);
        assert_eq!(found.loaded_dir(), Some(dir.as_path()));
        let skipped = DefaultJars::find(Some(dir), true);
        assert_eq!(skipped.state, DefaultJarsState::Skipped);
        assert_eq!(skipped.loaded_dir(), None);
        assert_eq!(DefaultJars::find(None, false).loaded_dir(), None);
    }

    #[test]
    fn lists_the_jars_and_marks_the_default_folder() {
        let mut popup = JarsPopup::new(
            loaded("/home/me/.config/leia/jars"),
            Some(PathBuf::from("/home/me")),
        );
        assert!(render(&mut popup, 100, 20).contains("Listing the jars…"));

        popup.jars_loaded(strings(&[
            "/opt/modules/kuromoji/lucene-analysis-kuromoji-10.5.2.jar",
            "/home/me/.config/leia/jars/mine.jar",
        ]));

        let screen = render(&mut popup, 100, 20);
        assert!(
            screen.contains("Default folder: ~/.config/leia/jars "),
            "{screen}"
        );
        assert!(screen.contains("Loaded: 2 jars"), "{screen}");
        assert!(
            screen.contains("lucene-analysis-kuromoji-10.5.2.jar  /opt/modules/kuromoji"),
            "{screen}"
        );
        assert!(screen.contains("mine.jar  default folder"), "{screen}");
        assert!(screen.contains("Enter add   Tab complete   Esc close"));
    }

    #[test]
    fn says_why_the_default_folder_was_not_loaded() {
        let mut popup = JarsPopup::new(
            DefaultJars {
                dir: Some(PathBuf::from("/cfg/leia/jars")),
                state: DefaultJarsState::Skipped,
            },
            None,
        );
        popup.jars_loaded(Vec::new());

        let screen = render(&mut popup, 100, 12);

        assert!(
            screen.contains("/cfg/leia/jars  (left out: --no-default-jars)"),
            "{screen}"
        );
        assert!(screen.contains("No jars loaded"), "{screen}");
    }

    #[test]
    fn tab_completes_jars_and_directories() {
        let tmp = TempDir::new();
        fs::create_dir(tmp.path().join("modules")).unwrap();
        fs::write(tmp.path().join("mine.jar"), "").unwrap();
        fs::write(tmp.path().join("mine.txt"), "").unwrap();
        let base = format!("{}{MAIN_SEPARATOR}", tmp.path().to_str().unwrap());
        let mut popup = JarsPopup::new(DefaultJars::default(), None);

        typed(&mut popup, &format!("{base}mi"));
        popup.handle_key(key(KeyCode::Tab));
        assert_eq!(popup.input(), format!("{base}mine.jar"));

        popup.input.set(format!("{base}mo"));
        popup.handle_key(key(KeyCode::Tab));
        assert_eq!(popup.input(), format!("{base}modules{MAIN_SEPARATOR}"));

        popup.input.set(base.clone());
        popup.handle_key(key(KeyCode::Tab));
        assert!(render(&mut popup, 100, 12).contains("mine.jar  modules"));
    }

    #[test]
    fn enter_submits_unless_blank_and_esc_closes() {
        let mut popup = JarsPopup::new(DefaultJars::default(), None);
        assert_eq!(popup.handle_key(key(KeyCode::Enter)), JarsEvent::None);

        typed(&mut popup, "~/x.jar");

        assert_eq!(
            popup.handle_key(key(KeyCode::Enter)),
            JarsEvent::Submit("~/x.jar".to_owned())
        );
        assert_eq!(popup.handle_key(key(KeyCode::Esc)), JarsEvent::Close);
    }

    #[test]
    fn shows_what_the_jars_added_and_lists_them() {
        let mut popup = JarsPopup::new(DefaultJars::default(), None);
        popup.jars_loaded(strings(&["/a/old.jar"]));
        typed(&mut popup, "/m");
        popup.set_busy();
        assert!(render(&mut popup, 100, 20).contains("Loading…"));

        popup.added(AddJarsResult {
            jars: strings(&["/m/kuromoji.jar", "/m/codecs.jar"]),
            already_loaded: strings(&["/a/old.jar"]),
            added: JarComponents {
                analyzers: strings(&["org.apache.lucene.analysis.ja.JapaneseAnalyzer"]),
                token_filters: strings(&["japaneseBaseForm", "japaneseKatakanaStem"]),
                codecs: strings(&["ES87"]),
                ..JarComponents::default()
            },
        });

        assert_eq!(popup.input(), "");
        let screen = render(&mut popup, 100, 20);
        assert!(
            screen.contains("Loaded 2 jars (1 already loaded):"),
            "{screen}"
        );
        assert!(screen.contains("  1 preset: JapaneseAnalyzer"), "{screen}");
        assert!(
            screen.contains("  2 token filters: japaneseBaseForm, japaneseKatakanaStem"),
            "{screen}"
        );
        assert!(screen.contains("  1 codec: ES87"), "{screen}");
        assert!(screen.contains("Loaded: 3 jars"), "{screen}");
        assert!(screen.contains("codecs.jar  /m"), "{screen}");
        typed(&mut popup, "x");
        assert!(!render(&mut popup, 100, 20).contains("1 codec: ES87"));
    }

    #[test]
    fn says_when_nothing_was_loaded_or_added() {
        let nothing = outcome_lines(&AddJarsResult {
            already_loaded: strings(&["/a.jar", "/b.jar"]),
            ..AddJarsResult::default()
        });
        assert_eq!(
            nothing[0].to_string(),
            "All 2 jars are already loaded; nothing was loaded"
        );

        let nothing_new = outcome_lines(&AddJarsResult {
            jars: strings(&["/a.jar"]),
            ..AddJarsResult::default()
        });
        assert_eq!(
            nothing_new[0].to_string(),
            "Loaded 1 jar, which adds nothing new: each of its names was taken"
        );
    }

    #[test]
    fn shows_why_adding_failed() {
        let mut popup = JarsPopup::new(DefaultJars::default(), None);
        popup.jars_loaded(Vec::new());
        typed(&mut popup, "/x.jar");
        popup.set_busy();

        popup.add_failed("Cannot add the jars: no such jar or directory: /x.jar".to_owned());

        assert_eq!(popup.input(), "/x.jar");
        assert!(render(&mut popup, 100, 12).contains("no such jar or directory: /x.jar"));
    }

    #[test]
    fn scrolls_a_long_list() {
        let mut popup = JarsPopup::new(DefaultJars::default(), None);
        let jars: Vec<String> = (0..30).map(|i| format!("/jars/j{i:02}.jar")).collect();
        popup.jars_loaded(jars);

        let screen = render(&mut popup, 80, 12);
        assert!(screen.contains("j00.jar"), "{screen}");
        assert!(!screen.contains("j29.jar"), "{screen}");
        assert!(screen.contains("↑↓ scroll"), "{screen}");

        popup.handle_key(key(KeyCode::PageDown));
        popup.handle_key(key(KeyCode::Down));
        let screen = render(&mut popup, 80, 12);
        assert!(!screen.contains("j00.jar"), "{screen}");
        for _ in 0..40 {
            popup.handle_key(key(KeyCode::Down));
        }
        assert!(render(&mut popup, 80, 12).contains("j29.jar"));
        popup.handle_key(key(KeyCode::Up));
        assert!(!render(&mut popup, 80, 12).contains("j29.jar"));
    }
}
