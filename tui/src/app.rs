//! The application state: what is on screen and what a key does. It makes no calls itself:
//! [`App::handle_key`] returns an [`Action`], the caller makes the RPC calls and reports back with
//! [`App::opened`], [`App::open_failed`], [`App::closed`] or the `scan_*` methods.

use std::collections::VecDeque;
use std::path::PathBuf;

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Constraint, Layout};
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph};

use crate::build_info::{LEIA, java_line, server_line};
use crate::overview::{DEFAULT_TOP_TERMS, FieldScan, Overview, step_top_terms};
use crate::prompt::{PathPrompt, PromptEvent, expand_home};
use crate::protocol::index::GetIndexStatsResult;
use crate::protocol::terms::GetFieldTermScanResult;
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
    /// Start the term scan of this field for its top `top_n` terms, and report back with
    /// [`App::scan_started`] or [`App::scan_failed`].
    StartScan {
        field: String,
        top_n: i32,
    },
    /// Cancel this scan job, and report back with [`App::scan_cancelled`].
    CancelScan(i64),
}

/// The scan that runs in the server. There is at most one; the others wait in the queue.
struct RunningScan {
    field: String,
    top_n: i32,
    /// `None` until the server has started the job.
    job_id: Option<i64>,
}

pub struct App {
    version: VersionResult,
    /// The directory that `~` stands for in the prompt.
    home: Option<PathBuf>,
    overview: Option<Overview>,
    prompt: Option<PathPrompt>,
    scan: Option<RunningScan>,
    /// Fields whose scan waits for the running one.
    queue: VecDeque<String>,
    /// How many top terms a scan asks for; kept when another index is opened.
    top_n: i32,
}

impl App {
    pub fn new(version: VersionResult, home: Option<PathBuf>) -> Self {
        Self {
            version,
            home,
            overview: None,
            prompt: None,
            scan: None,
            queue: VecDeque::new(),
            top_n: DEFAULT_TOP_TERMS,
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

    /// Shows the new index. The server stopped the scans of the previous one.
    pub fn opened(&mut self, stats: GetIndexStatsResult) {
        self.overview = Some(Overview::new(stats, self.top_n));
        self.prompt = None;
        self.forget_scans();
    }

    /// Shows `error` in the prompt, which keeps the path, so it can be corrected.
    pub fn open_failed(&mut self, error: String) {
        self.prompt
            .get_or_insert_with(|| PathPrompt::new("", self.home.clone()))
            .set_error(error);
    }

    /// The server stopped the scans of the closed index.
    pub fn closed(&mut self) {
        self.overview = None;
        self.forget_scans();
    }

    fn forget_scans(&mut self) {
        self.scan = None;
        self.queue.clear();
    }

    /// The job to poll, while a scan runs.
    pub fn polling(&self) -> Option<i64> {
        self.scan.as_ref().and_then(|scan| scan.job_id)
    }

    pub fn scan_started(&mut self, job_id: i64) {
        if let Some(scan) = &mut self.scan {
            scan.job_id = Some(job_id);
        }
    }

    /// Shows the progress of the running scan, or its outcome. Returns the next scan to start,
    /// once this one is done.
    pub fn scan_progress(&mut self, result: GetFieldTermScanResult) -> Option<Action> {
        let scan = self.scan.as_ref()?;
        let (field, top_n) = (scan.field.clone(), scan.top_n);
        let overview = self.overview.as_mut()?;
        match result.terms {
            Some(terms) => {
                overview.set_scan(&field, Some(FieldScan::done(terms, top_n)));
                self.scan = None;
                self.start_next_scan()
            }
            None => {
                overview.set_scan(
                    &field,
                    Some(FieldScan::Running {
                        terms_scanned: result.terms_scanned,
                    }),
                );
                None
            }
        }
    }

    /// Shows why the running scan failed. Returns the next scan to start.
    pub fn scan_failed(&mut self, error: String) -> Option<Action> {
        let scan = self.scan.take()?;
        if let Some(overview) = &mut self.overview {
            overview.set_scan(&scan.field, Some(FieldScan::Failed(error)));
        }
        self.start_next_scan()
    }

    /// Forgets the cancelled scan and the queued ones.
    pub fn scan_cancelled(&mut self) {
        let fields = self.scan.take().map(|scan| scan.field);
        if let Some(overview) = &mut self.overview {
            for field in fields.iter().chain(&self.queue) {
                overview.set_scan(field, None);
            }
        }
        self.queue.clear();
    }

    /// Queues the scans of `fields`, and returns the scan to start if none runs.
    fn request_scans(&mut self, fields: Vec<String>) -> Option<Action> {
        let overview = self.overview.as_mut()?;
        for field in fields {
            overview.set_scan(&field, Some(FieldScan::Queued));
            self.queue.push_back(field);
        }
        if self.scan.is_some() {
            return None;
        }
        self.start_next_scan()
    }

    fn start_next_scan(&mut self) -> Option<Action> {
        let field = self.queue.pop_front()?;
        if let Some(overview) = &mut self.overview {
            overview.set_scan(&field, Some(FieldScan::Running { terms_scanned: 0 }));
        }
        self.scan = Some(RunningScan {
            field: field.clone(),
            top_n: self.top_n,
            job_id: None,
        });
        Some(Action::StartScan {
            field,
            top_n: self.top_n,
        })
    }

    /// The selected field, if Enter can scan it.
    fn scannable_selected_field(&self) -> Option<String> {
        let overview = self.overview.as_ref()?;
        let field = overview.selected_field()?;
        overview.needs_scan(field).then(|| field.name.clone())
    }

    /// Shows, and from now on scans for, more or fewer top terms.
    fn step_top_terms(&mut self, up: bool) {
        self.top_n = step_top_terms(self.top_n, up);
        if let Some(overview) = &mut self.overview {
            overview.set_top_n(self.top_n);
        }
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
            // Esc stops the scans first, and quits only when there are none.
            KeyCode::Esc if self.scan.is_some() => match self.polling() {
                Some(job_id) => Some(Action::CancelScan(job_id)),
                None => {
                    self.scan_cancelled();
                    None
                }
            },
            KeyCode::Char('q') | KeyCode::Esc => Some(Action::Quit),
            KeyCode::Enter => {
                let field = self.scannable_selected_field()?;
                self.request_scans(vec![field])
            }
            KeyCode::Char('a') => {
                let fields = self.overview.as_ref()?.fields_to_scan();
                self.request_scans(fields)
            }
            KeyCode::Char('+' | '=') => {
                self.step_top_terms(true);
                None
            }
            KeyCode::Char('-') => {
                self.step_top_terms(false);
                None
            }
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
                if self.scan.is_some() {
                    Line::from(
                        " Esc cancel scan   q quit   o open   c close   s/S sort   Tab panel   ↑↓",
                    )
                } else {
                    Line::from(
                        " q quit   o open   c close   ⏎ terms   a all terms   +/- top N   \
                         s/S sort   Tab panel   ↑↓",
                    )
                }
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
    use crate::protocol::index::{DocValuesType, IndexField, IndexOptions, TermStats};
    use crate::protocol::terms::FieldTerms;
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

    fn field(name: &str, number: i32, has_postings: bool) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number,
            index_options: if has_postings {
                IndexOptions::Docs
            } else {
                IndexOptions::None
            },
            has_norms: false,
            has_payloads: false,
            has_term_vectors: false,
            doc_values_type: DocValuesType::None,
            point_dimension_count: 0,
            point_num_bytes: 0,
            vector_dimension: 0,
            terms: has_postings.then_some(TermStats {
                doc_count: 1,
                sum_doc_freq: 1,
                sum_total_term_freq: 1,
            }),
        }
    }

    /// An index with the fields `id` and `body`, which have postings, and `rank`, which has none.
    fn app_with_fields() -> App {
        let mut app = App::new(version(), None);
        app.opened(GetIndexStatsResult {
            fields: vec![
                field("id", 0, true),
                field("body", 1, true),
                field("rank", 2, false),
            ],
            ..stats("/data/idx")
        });
        app
    }

    fn running(terms_scanned: i64) -> GetFieldTermScanResult {
        GetFieldTermScanResult {
            terms_scanned,
            terms: None,
        }
    }

    fn done(field: &str, unique_terms: i64) -> GetFieldTermScanResult {
        GetFieldTermScanResult {
            terms_scanned: unique_terms,
            terms: Some(FieldTerms {
                field: field.to_owned(),
                unique_terms,
                top_terms: Vec::new(),
            }),
        }
    }

    fn start(field: &str) -> Option<Action> {
        Some(Action::StartScan {
            field: field.to_owned(),
            top_n: DEFAULT_TOP_TERMS,
        })
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

    #[test]
    fn scans_the_selected_field_and_polls_it() {
        let mut app = app_with_fields();

        assert_eq!(app.handle_key(key(KeyCode::Enter)), start("id"));
        assert_eq!(app.polling(), None);
        app.scan_started(7);
        assert_eq!(app.polling(), Some(7));

        assert_eq!(app.scan_progress(running(1024)), None);
        let screen = render(&mut app);
        assert!(screen.contains("1024…"), "{screen}");
        assert!(screen.contains("Esc cancel scan"), "{screen}");
        // A running scan is not started again.
        assert_eq!(app.handle_key(key(KeyCode::Enter)), None);

        assert_eq!(app.scan_progress(done("id", 3)), None);
        assert_eq!(app.polling(), None);
        assert!(render(&mut app).contains("id: 3 terms, top 0"));
    }

    #[test]
    fn scans_all_fields_one_after_another() {
        let mut app = app_with_fields();

        assert_eq!(app.handle_key(key(KeyCode::Char('a'))), start("id"));
        app.scan_started(1);
        assert!(render(&mut app).contains("queued"));

        assert_eq!(app.scan_progress(done("id", 3)), start("body"));
        app.scan_started(2);
        assert_eq!(app.scan_failed("Cannot read".to_owned()), None);
        assert_eq!(app.polling(), None);
        assert!(render(&mut app).contains("failed"));

        // A failed field is scanned again; the others are done or have no postings.
        assert_eq!(app.handle_key(key(KeyCode::Char('a'))), start("body"));
    }

    #[test]
    fn a_scan_the_server_refuses_starts_the_next() {
        let mut app = app_with_fields();
        assert_eq!(app.handle_key(key(KeyCode::Char('a'))), start("id"));

        assert_eq!(app.scan_failed("No field 'id'".to_owned()), start("body"));
    }

    #[test]
    fn esc_cancels_the_scans_before_it_quits() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('a')));
        app.scan_started(3);

        assert_eq!(
            app.handle_key(key(KeyCode::Esc)),
            Some(Action::CancelScan(3))
        );
        app.scan_cancelled();

        assert_eq!(app.polling(), None);
        let screen = render(&mut app);
        assert!(!screen.contains("queued"), "{screen}");
        assert!(!screen.contains('…'), "{screen}");
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    #[test]
    fn enter_ignores_a_field_without_postings() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::End));

        assert_eq!(app.handle_key(key(KeyCode::Enter)), None);
        assert_eq!(app.polling(), None);
    }

    #[test]
    fn plus_and_minus_change_the_top_n_of_the_next_scans() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('-')));
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::StartScan {
                field: "id".to_owned(),
                top_n: 20,
            })
        );
        app.scan_started(1);
        // id has more terms than the 20 it got.
        app.scan_progress(GetFieldTermScanResult {
            terms_scanned: 30,
            terms: Some(FieldTerms {
                field: "id".to_owned(),
                unique_terms: 30,
                top_terms: Vec::new(),
            }),
        });
        assert_eq!(app.handle_key(key(KeyCode::Enter)), None);

        app.handle_key(key(KeyCode::Char('+')));
        assert!(render(&mut app).contains("id: 30 terms, top 0 (⏎ top 50)"));
        // A scan for 20 isn't enough any more.
        assert_eq!(app.handle_key(key(KeyCode::Enter)), start("id"));

        app.handle_key(key(KeyCode::Char('+')));
        app.opened(stats("/data/other"));
        app.opened(GetIndexStatsResult {
            fields: vec![field("id", 0, true)],
            ..stats("/data/idx")
        });
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::StartScan {
                field: "id".to_owned(),
                top_n: 100,
            })
        );
    }

    #[test]
    fn opening_another_index_forgets_the_scans() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('a')));
        app.scan_started(4);

        app.opened(stats("/data/other"));

        assert_eq!(app.polling(), None);
        assert_eq!(app.scan_progress(done("id", 1)), None);
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }
}
