//! The application state: what is on screen and what a key does. It makes no calls itself:
//! [`App::handle_key`] returns an [`Action`], the caller makes the RPC calls and reports back with
//! [`App::opened`], [`App::open_failed`] or [`App::closed`].

use std::path::PathBuf;

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Constraint, Layout};
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph};

use crate::build_info::{LEIA, java_line, server_line};
use crate::overview::Overview;
use crate::prompt::{PathPrompt, PromptEvent, expand_home};
use crate::protocol::index::GetIndexStatsResult;
use crate::protocol::version::VersionResult;

/// What the caller has to do after a key.
#[derive(Debug, PartialEq, Eq)]
pub enum Action {
    Quit,
    /// Open this index and fetch its statistics. The prompt shows "Opening…" until the outcome
    /// is reported.
    Open(String),
    /// Close the open index.
    Close,
}

pub struct App {
    version: VersionResult,
    /// The directory that `~` stands for in the prompt.
    home: Option<PathBuf>,
    overview: Option<Overview>,
    prompt: Option<PathPrompt>,
}

impl App {
    pub fn new(version: VersionResult, home: Option<PathBuf>) -> Self {
        Self {
            version,
            home,
            overview: None,
            prompt: None,
        }
    }

    pub fn has_index(&self) -> bool {
        self.overview.is_some()
    }

    /// Shows `path` being opened, for an open that no key started (the command line's path).
    pub fn opening(&mut self, path: &str) {
        let mut prompt = PathPrompt::new(path, self.home.clone());
        prompt.set_busy();
        self.prompt = Some(prompt);
    }

    pub fn opened(&mut self, stats: GetIndexStatsResult) {
        self.overview = Some(Overview::new(stats));
        self.prompt = None;
    }

    /// Shows `error` in the prompt, which keeps the path, so it can be corrected.
    pub fn open_failed(&mut self, error: String) {
        self.prompt
            .get_or_insert_with(|| PathPrompt::new("", self.home.clone()))
            .set_error(error);
    }

    pub fn closed(&mut self) {
        self.overview = None;
    }

    pub fn handle_key(&mut self, key: KeyEvent) -> Option<Action> {
        if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
            return Some(Action::Quit);
        }
        if let Some(prompt) = &mut self.prompt {
            return match prompt.handle_key(key) {
                PromptEvent::Submit(path) => {
                    prompt.set_busy();
                    Some(Action::Open(expand_home(&path, self.home.as_deref())))
                }
                PromptEvent::Cancel => {
                    self.prompt = None;
                    None
                }
                PromptEvent::None => None,
            };
        }
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc => Some(Action::Quit),
            KeyCode::Char('o') => {
                // Starts from the open index, so a sibling is a Ctrl+W away.
                let path = self.overview.as_ref().map_or("", Overview::path);
                self.prompt = Some(PathPrompt::new(path, self.home.clone()));
                None
            }
            KeyCode::Char('c') if self.overview.is_some() => Some(Action::Close),
            code => {
                if let Some(overview) = &mut self.overview {
                    overview.handle_key(code);
                }
                None
            }
        }
    }

    pub fn draw(&mut self, frame: &mut Frame) {
        let [body, footer] =
            Layout::vertical([Constraint::Fill(1), Constraint::Length(1)]).areas(frame.area());
        let keys = match &mut self.overview {
            Some(overview) => {
                overview.render(frame, body);
                Line::from(" q quit   o open   c close   ↑↓ PgUp PgDn Home End  fields")
            }
            None => {
                let lines = vec![
                    Line::from("No index open. Press o to open one."),
                    Line::from(""),
                    Line::from(LEIA.line()),
                    Line::from(server_line(&self.version)),
                    Line::from(java_line(&self.version.java)),
                ];
                frame.render_widget(
                    Paragraph::new(lines).block(Block::bordered().title(" leia ")),
                    body,
                );
                Line::from(" q quit   o open")
            }
        };
        let build = Line::from(format!("{} ", LEIA.line())).right_aligned();
        // The build only if it fits next to the keys.
        if keys.width() + 2 + build.width() <= usize::from(footer.width) {
            frame.render_widget(build, footer);
        }
        frame.render_widget(keys, footer);
        if let Some(prompt) = &self.prompt {
            prompt.render(frame, body);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::version::JavaRuntime;

    fn version() -> VersionResult {
        VersionResult {
            name: "leia-server".to_owned(),
            version: "1".to_owned(),
            commit: "abc".to_owned(),
            commit_time: "t".to_owned(),
            java: JavaRuntime {
                version: "25".to_owned(),
                vendor: "v".to_owned(),
                home: "/h".to_owned(),
            },
        }
    }

    fn stats(path: &str) -> GetIndexStatsResult {
        GetIndexStatsResult {
            path: path.to_owned(),
            directory_impl: "MMapDirectory".to_owned(),
            segments_file: "segments_1".to_owned(),
            generation: 1,
            version: 1,
            segment_count: 0,
            num_docs: 0,
            max_doc: 0,
            created_version_major: 10,
            commit_lucene_version: "10.5.2".to_owned(),
            min_segment_lucene_version: None,
            user_data: BTreeMap::new(),
            fields: Vec::new(),
        }
    }

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn typed(app: &mut App, text: &str) {
        for c in text.chars() {
            assert_eq!(app.handle_key(key(KeyCode::Char(c))), None);
        }
    }

    fn render(app: &mut App) -> String {
        let (width, height) = (100, 20);
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal.draw(|frame| app.draw(frame)).unwrap();
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
    fn opens_an_index_from_the_prompt() {
        let mut app = App::new(version(), None);
        assert!(render(&mut app).contains("No index open"));

        app.handle_key(key(KeyCode::Char('o')));
        typed(&mut app, "/data/idx");
        assert!(render(&mut app).contains("Open index"));

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::Open("/data/idx".to_owned()))
        );
        assert!(render(&mut app).contains("Opening…"));
        app.opened(stats("/data/idx"));

        let screen = render(&mut app);
        assert!(app.has_index());
        assert!(screen.contains("/data/idx"), "{screen}");
        assert!(!screen.contains("Open index"), "{screen}");
    }

    #[test]
    fn a_failed_open_keeps_the_prompt_and_the_previous_index() {
        let mut app = App::new(version(), None);
        app.opened(stats("/data/first"));
        app.handle_key(key(KeyCode::Char('o')));
        typed(&mut app, "x");

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::Open("/data/firstx".to_owned()))
        );
        app.open_failed("No such file or directory: /data/firstx".to_owned());

        let screen = render(&mut app);
        assert!(screen.contains("No such file or directory"), "{screen}");
        assert!(screen.contains("Path       /data/first"), "{screen}");
        // Esc closes the prompt, not leia.
        assert_eq!(app.handle_key(key(KeyCode::Esc)), None);
        assert!(!render(&mut app).contains("Open index"));
        assert!(app.has_index());
    }

    #[test]
    fn a_failed_open_from_the_command_line_shows_the_path() {
        let mut app = App::new(version(), None);

        app.opening("/data/missing");
        app.open_failed("No such file or directory: /data/missing".to_owned());

        let screen = render(&mut app);
        assert!(screen.contains("│/data/missing"), "{screen}");
        assert!(
            screen.contains("No such file or directory: /data/missing"),
            "{screen}"
        );
    }

    #[test]
    fn expands_the_home_directory_on_submit() {
        let mut app = App::new(version(), Some(PathBuf::from("/home/me")));
        app.handle_key(key(KeyCode::Char('o')));
        typed(&mut app, "~/idx");

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::Open("/home/me/idx".to_owned()))
        );
    }

    #[test]
    fn closes_and_quits() {
        let mut app = App::new(version(), None);
        assert_eq!(app.handle_key(key(KeyCode::Char('c'))), None);

        app.opened(stats("/data/idx"));
        assert_eq!(app.handle_key(key(KeyCode::Char('c'))), Some(Action::Close));
        app.closed();
        assert!(!app.has_index());
        assert!(render(&mut app).contains("No index open"));

        assert_eq!(app.handle_key(key(KeyCode::Char('q'))), Some(Action::Quit));
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    #[test]
    fn typing_q_in_the_prompt_does_not_quit_but_ctrl_c_does() {
        let mut app = App::new(version(), None);
        app.handle_key(key(KeyCode::Char('o')));

        typed(&mut app, "q");

        assert_eq!(
            app.handle_key(KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL)),
            Some(Action::Quit)
        );
    }

    #[test]
    fn the_prompt_starts_from_the_open_index() {
        let mut app = App::new(version(), None);
        app.opened(stats("/data/idx"));
        app.handle_key(key(KeyCode::Char('o')));

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::Open("/data/idx".to_owned()))
        );
    }
}
