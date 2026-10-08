//! The application state: what is on screen and what a key does. It makes no calls itself:
//! [`App::handle_key`] returns an [`Action`], the caller makes the RPC calls and reports back with
//! [`App::opened`], [`App::open_failed`], [`App::closed`], and the `scan_*`, `document_*`,
//! `stored_value_*`, `term_vector_*`, `terms_*`, `postings_*`, `positions_*`, `commits_*`,
//! `commit_*`, `search_*`, `explain_*`, `components_*` or `analy*` methods.

use std::collections::VecDeque;
use std::path::PathBuf;

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Constraint, Layout};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph, Tabs};

use crate::analysis::{self, Analysis};
use crate::build_info::{LEIA, java_line, server_line};
use crate::commits::{self, Commits};
use crate::documents::{self, Documents, Walk};
use crate::history::History;
use crate::overview::{DEFAULT_TOP_TERMS, FieldScan, Overview, step_top_terms};
use crate::prompt::{PathPrompt, PromptEvent, expand_home};
use crate::protocol::analysis::{AnalyzeParams, AnalyzeResult, GetAnalysisComponentsResult};
use crate::protocol::commits::{Commit, GetCommitResult};
use crate::protocol::document::{
    BytesValue, GetDocumentResult, StoredValue, TermPosition, TermVectorTerm,
};
use crate::protocol::index::{DirectoryImpl, GetIndexStatsResult, IndexField, ReopenIndexResult};
use crate::protocol::search::{
    GetExplainResult, GetSearchResult, StartExplainParams, StartSearchParams,
};
use crate::protocol::terms::{GetFieldTermScanResult, GetPostingsResult, GetTermsResult};
use crate::protocol::version::VersionResult;
use crate::search::{self, Search};
use crate::terms::{self, Terms};

/// What the caller has to do after a key.
#[derive(Debug, PartialEq)]
pub enum Action {
    Quit,
    /// Open this index through this `Directory` and fetch its statistics. The prompt shows
    /// "Opening…" until the outcome is reported.
    Open {
        path: String,
        directory_impl: DirectoryImpl,
    },
    /// Close the open index.
    Close,
    /// Reopen the open index if it changed, and report back with [`App::reopened`],
    /// [`App::reopen_unchanged`] or [`App::reopen_failed`] (ADR 0019).
    Reopen,
    /// Start the term scan of this field for its top `top_n` terms, and report back with
    /// [`App::scan_started`] or [`App::scan_failed`].
    StartScan {
        field: String,
        top_n: i32,
    },
    /// Cancel this scan job, and report back with [`App::scan_cancelled`].
    CancelScan(i64),
    /// Fetch this document, and report back with [`App::document_loaded`] or
    /// [`App::document_failed`].
    LoadDocument(i32),
    /// Fetch a stored value of a document whole, and report back with
    /// [`App::stored_value_loaded`] or [`App::stored_value_failed`].
    LoadStoredValue {
        doc_id: i32,
        field: String,
        index: i32,
    },
    /// Put this text on the clipboard.
    Copy(String),
    /// Fetch the term vector of a field of a document, and report back with
    /// [`App::term_vector_loaded`] or [`App::term_vector_failed`].
    LoadTermVector {
        doc_id: i32,
        field: String,
    },
    /// Fetch a page of the terms of a field, and report back with [`App::terms_loaded`] or
    /// [`App::terms_failed`].
    LoadTerms {
        field: String,
        from: Option<BytesValue>,
        /// Whether the page continues the shown terms.
        more: bool,
    },
    /// Fetch a page of the postings of a term, and report back with [`App::postings_loaded`] or
    /// [`App::postings_failed`].
    LoadPostings {
        field: String,
        term: BytesValue,
        from_doc: i32,
        /// Whether the page continues the shown postings.
        more: bool,
    },
    /// Fetch where a term occurs in a document, and report back with [`App::positions_loaded`]
    /// or [`App::positions_failed`].
    LoadPositions {
        doc_id: i32,
        field: String,
        term: BytesValue,
    },
    /// Fetch the commits of the index directory, and report back with [`App::commits_loaded`]
    /// or [`App::commits_failed`].
    LoadCommits,
    /// Fetch a commit whole, and report back with [`App::commit_loaded`] or
    /// [`App::commit_failed`].
    LoadCommit(i64),
    /// Cancel the jobs in `cancel`, then start this search, and report back with
    /// [`App::search_started`] or [`App::search_failed`].
    StartSearch {
        params: StartSearchParams,
        cancel: Vec<i64>,
    },
    /// Cancel the jobs in `cancel`, then start this explanation, and report back with
    /// [`App::explain_started`] or [`App::explain_failed`].
    StartExplain {
        params: StartExplainParams,
        cancel: Vec<i64>,
    },
    /// Cancel these search and explanation jobs, and report back with
    /// [`App::search_cancelled`].
    CancelSearch(Vec<i64>),
    /// Fetch what custom analyzers can be built from, and report back with
    /// [`App::components_loaded`] or [`App::components_failed`].
    LoadAnalysisComponents,
    /// Analyze a text, and report back with [`App::analyzed`] or [`App::analyze_failed`].
    Analyze(AnalyzeParams),
}

/// A running job to poll.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Poll {
    /// Report back with [`App::scan_progress`] or [`App::scan_failed`].
    Scan(i64),
    /// Report back with [`App::search_progress`] or [`App::search_failed`].
    Search(i64),
    /// Report back with [`App::explain_progress`] or [`App::explain_failed`].
    Explain(i64),
}

/// The screens; `1` to `6` switch between them. The first five need an open index, the
/// analysis screen doesn't.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Screen {
    Overview,
    Documents,
    Terms,
    Commits,
    Search,
    Analysis,
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
    /// The recently opened paths, for the prompt (ADR 0020).
    history: History,
    /// The `Directory` the next prompt opens with: `--directory`, then the last one chosen.
    directory: DirectoryImpl,
    overview: Option<Overview>,
    documents: Option<Documents>,
    terms: Option<Terms>,
    commits: Option<Commits>,
    search: Option<Search>,
    /// Kept when an index is opened or closed: its analyzer is the search screen's.
    analysis: Analysis,
    /// The screen shown; kept when another index is opened.
    screen: Screen,
    prompt: Option<PathPrompt>,
    scan: Option<RunningScan>,
    /// Fields whose scan waits for the running one.
    queue: VecDeque<String>,
    /// How many top terms a scan asks for; kept when another index is opened.
    top_n: i32,
    /// What the last reopen came to, on the tab line until the next key.
    notice: Option<Notice>,
}

/// What the tab line says about the last reopen.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Notice {
    Reopening,
    Unchanged(String),
    Reopened(String),
    Failed(String),
}

impl Notice {
    fn line(&self) -> Line<'_> {
        match self {
            Self::Reopening => Line::from("Reopening…"),
            Self::Unchanged(segments_file) => Line::from(format!("Unchanged: {segments_file}")),
            Self::Reopened(segments_file) => Line::from(format!("Reopened: {segments_file}")),
            Self::Failed(error) => Line::styled(
                format!("Reopen failed: {error}"),
                Style::new().fg(Color::Red),
            ),
        }
    }
}

impl App {
    pub fn new(version: VersionResult, home: Option<PathBuf>) -> Self {
        Self {
            version,
            home,
            history: History::in_memory(),
            directory: DirectoryImpl::default(),
            overview: None,
            documents: None,
            terms: None,
            commits: None,
            search: None,
            analysis: Analysis::new(),
            screen: Screen::Overview,
            prompt: None,
            scan: None,
            queue: VecDeque::new(),
            top_n: DEFAULT_TOP_TERMS,
            notice: None,
        }
    }

    /// Keeps the recently opened paths in `history` instead of in memory.
    pub fn with_history(mut self, history: History) -> Self {
        self.history = history;
        self
    }

    /// Opens with `directory` until another one is chosen in the prompt.
    pub fn with_directory(mut self, directory: DirectoryImpl) -> Self {
        self.directory = directory;
        self
    }

    /// The `Directory` the next open uses.
    pub fn directory(&self) -> DirectoryImpl {
        self.directory
    }

    fn path_prompt(&self, input: &str) -> PathPrompt {
        PathPrompt::new(input, self.home.clone())
            .with_recent(self.history.paths())
            .with_directory(self.directory)
    }

    pub fn has_index(&self) -> bool {
        self.overview.is_some()
    }

    /// Shows `path` being opened, for an open that no key started (the command line's path).
    pub fn opening(&mut self, path: &str) {
        let mut prompt = self.path_prompt(path);
        prompt.set_busy();
        self.prompt = Some(prompt);
    }

    /// Shows the new index. The server stopped the jobs of the previous one. Returns what the
    /// shown screen has to fetch first.
    pub fn opened(&mut self, stats: GetIndexStatsResult) -> Option<Action> {
        self.history.add(&stats.path);
        self.documents = Some(Documents::new(stats.fields.clone(), stats.max_doc));
        self.terms = Some(Terms::new(stats.fields.clone()));
        self.commits = Some(Commits::new());
        let mut search = Search::new(&stats.fields, stats.max_doc);
        search.set_analyzer(self.analysis.settings());
        self.search = Some(search);
        self.overview = Some(Overview::new(stats, self.top_n));
        self.prompt = None;
        self.forget_scans();
        self.initial_request()
    }

    /// Shows the reopened index (ADR 0019) where the user was: the screens are new, as after
    /// opening it, but keep their place. The server stopped the jobs of the old index. Returns
    /// what the shown screen has to fetch first.
    pub fn reopened(&mut self, stats: GetIndexStatsResult) -> Option<Action> {
        let Some(overview) = &self.overview else {
            return self.opened(stats);
        };
        let same_fields = schema(overview.fields()) == schema(&stats.fields);
        self.notice = Some(Notice::Reopened(stats.segments_file.clone()));
        self.documents = Some(match &self.documents {
            Some(documents) => documents.reopened(stats.fields.clone(), stats.max_doc),
            None => Documents::new(stats.fields.clone(), stats.max_doc),
        });
        self.terms = Some(match &self.terms {
            Some(terms) => terms.reopened(stats.fields.clone()),
            None => Terms::new(stats.fields.clone()),
        });
        self.commits = Some(Commits::new());
        self.search = Some(match &self.search {
            Some(search) => search.reopened(&stats.fields, stats.max_doc, same_fields),
            None => {
                let mut search = Search::new(&stats.fields, stats.max_doc);
                search.set_analyzer(self.analysis.settings());
                search
            }
        });
        self.overview = Some(overview.reopened(stats, self.top_n));
        self.forget_scans();
        self.initial_request()
    }

    /// The index has no newer commit; everything stays as it is.
    pub fn reopen_unchanged(&mut self, result: &ReopenIndexResult) {
        self.notice = Some(Notice::Unchanged(result.segments_file.clone()));
    }

    /// The open index stays open, and is shown as it was.
    pub fn reopen_failed(&mut self, error: String) {
        self.notice = Some(Notice::Failed(error));
    }

    /// Shows `error` in the prompt, which keeps the path, so it can be corrected.
    pub fn open_failed(&mut self, error: String) {
        if self.prompt.is_none() {
            self.prompt = Some(self.path_prompt(""));
        }
        if let Some(prompt) = &mut self.prompt {
            prompt.set_error(error);
        }
    }

    /// The server stopped the jobs of the closed index.
    pub fn closed(&mut self) {
        self.overview = None;
        self.documents = None;
        self.terms = None;
        self.commits = None;
        self.search = None;
        self.forget_scans();
    }

    /// What the shown screen has to fetch first, if anything.
    fn initial_request(&mut self) -> Option<Action> {
        match self.screen {
            Screen::Overview => None,
            Screen::Documents => {
                let request = self.documents.as_ref()?.initial_request();
                self.documents_request(request)
            }
            Screen::Terms => {
                let request = self.terms.as_mut()?.initial_request();
                self.terms_request(request)
            }
            Screen::Commits => commits_action(self.commits.as_ref()?.initial_request()),
            Screen::Search => None,
            Screen::Analysis => analysis_action(self.analysis.initial_request()),
        }
    }

    /// Turns what the search screen needs into an action; a document to show switches to the
    /// documents screen.
    fn search_request(&mut self, request: Option<search::Request>) -> Option<Action> {
        match request? {
            search::Request::Search { params, cancel } => {
                Some(Action::StartSearch { params, cancel })
            }
            search::Request::Explain { params, cancel } => {
                Some(Action::StartExplain { params, cancel })
            }
            search::Request::Cancel(jobs) => Some(Action::CancelSearch(jobs)),
            search::Request::Document(doc_id) => {
                self.documents.as_mut()?.show_search_hit();
                self.screen = Screen::Documents;
                Some(Action::LoadDocument(doc_id))
            }
        }
    }

    fn documents_request(&mut self, request: Option<documents::Request>) -> Option<Action> {
        match request? {
            documents::Request::Document(doc_id) => Some(Action::LoadDocument(doc_id)),
            documents::Request::TermVector { doc_id, field } => {
                Some(Action::LoadTermVector { doc_id, field })
            }
            documents::Request::Positions {
                doc_id,
                field,
                term,
            } => Some(Action::LoadPositions {
                doc_id,
                field,
                term,
            }),
            documents::Request::StoredValue {
                doc_id,
                field,
                index,
            } => Some(Action::LoadStoredValue {
                doc_id,
                field,
                index,
            }),
            documents::Request::Copy(text) => Some(Action::Copy(text)),
            documents::Request::Analyze {
                doc_id,
                field,
                text,
            } => {
                self.screen = Screen::Analysis;
                let label = format!("doc {doc_id} · {field}");
                analysis_action(self.analysis.analyze_value(label, &text))
            }
            documents::Request::MoreLikeThis(doc_id) => {
                let request = self.search.as_mut()?.more_like_this(doc_id);
                self.screen = Screen::Search;
                self.search_request(request)
            }
            documents::Request::Step {
                walk: Walk::TermDocs,
                forward,
            } => {
                let request = self.terms.as_mut()?.step(forward);
                self.terms_request(request)
            }
            documents::Request::Step {
                walk: Walk::SearchHits,
                forward,
            } => {
                let request = self.search.as_mut()?.step(forward);
                self.search_request(request)
            }
        }
    }

    /// Turns what the terms screen needs into an action; a document to show switches to the
    /// documents screen.
    fn terms_request(&mut self, request: Option<terms::Request>) -> Option<Action> {
        match request? {
            terms::Request::Terms { field, from, more } => {
                Some(Action::LoadTerms { field, from, more })
            }
            terms::Request::Postings {
                field,
                term,
                from_doc,
                more,
            } => Some(Action::LoadPostings {
                field,
                term,
                from_doc,
                more,
            }),
            terms::Request::Document(hit) => {
                let doc_id = hit.doc_id;
                self.documents.as_mut()?.show_hit(hit);
                self.screen = Screen::Documents;
                Some(Action::LoadDocument(doc_id))
            }
        }
    }

    /// Shows the document. Returns where the term occurs in it, if the terms screen opened it.
    pub fn document_loaded(&mut self, document: GetDocumentResult) -> Option<Action> {
        let documents = self.documents.as_mut()?;
        documents.loaded(document);
        let request = documents.positions_request();
        self.documents_request(request)
    }

    pub fn document_failed(&mut self, doc_id: i32, error: String) {
        if let Some(documents) = &mut self.documents {
            documents.failed(doc_id, error);
        }
    }

    pub fn stored_value_loaded(
        &mut self,
        doc_id: i32,
        field: &str,
        index: i32,
        value: &StoredValue,
    ) {
        if let Some(documents) = &mut self.documents {
            documents.stored_value_loaded(doc_id, field, index, value);
        }
    }

    pub fn stored_value_failed(&mut self, doc_id: i32, field: &str, index: i32, error: String) {
        if let Some(documents) = &mut self.documents {
            documents.stored_value_failed(doc_id, field, index, error);
        }
    }

    pub fn term_vector_loaded(&mut self, doc_id: i32, field: &str, terms: Vec<TermVectorTerm>) {
        if let Some(documents) = &mut self.documents {
            documents.term_vector_loaded(doc_id, field, terms);
        }
    }

    pub fn term_vector_failed(&mut self, doc_id: i32, field: &str, error: String) {
        if let Some(documents) = &mut self.documents {
            documents.term_vector_failed(doc_id, field, error);
        }
    }

    pub fn positions_loaded(
        &mut self,
        doc_id: i32,
        field: &str,
        term: &BytesValue,
        positions: Vec<TermPosition>,
    ) {
        if let Some(documents) = &mut self.documents {
            documents.positions_loaded(doc_id, field, term, positions);
        }
    }

    pub fn positions_failed(&mut self, doc_id: i32, field: &str, term: &BytesValue, error: String) {
        if let Some(documents) = &mut self.documents {
            documents.positions_failed(doc_id, field, term, error);
        }
    }

    /// Shows a page of terms. Returns what the terms screen needs next.
    pub fn terms_loaded(
        &mut self,
        field: &str,
        more: bool,
        result: GetTermsResult,
    ) -> Option<Action> {
        let request = self.terms.as_mut()?.terms_loaded(field, more, result);
        self.terms_request(request)
    }

    pub fn terms_failed(&mut self, field: &str, more: bool, error: String) -> Option<Action> {
        let request = self.terms.as_mut()?.terms_failed(field, more, error);
        self.terms_request(request)
    }

    /// Shows a page of postings. Returns what the terms screen needs next, or the document to
    /// show if `]` waited for this page.
    pub fn postings_loaded(
        &mut self,
        field: &str,
        term: &BytesValue,
        more: bool,
        result: GetPostingsResult,
    ) -> Option<Action> {
        let request = self
            .terms
            .as_mut()?
            .postings_loaded(field, term, more, result);
        self.terms_request(request)
    }

    pub fn postings_failed(&mut self, field: &str, term: &BytesValue, more: bool, error: String) {
        if let Some(terms) = &mut self.terms {
            terms.postings_failed(field, term, more, error);
        }
    }

    /// Shows the commits. Returns the selected commit to fetch whole.
    pub fn commits_loaded(&mut self, commits: Vec<Commit>) -> Option<Action> {
        commits_action(self.commits.as_mut()?.commits_loaded(commits))
    }

    pub fn commits_failed(&mut self, error: String) {
        if let Some(commits) = &mut self.commits {
            commits.commits_failed(error);
        }
    }

    pub fn commit_loaded(&mut self, generation: i64, result: GetCommitResult) {
        if let Some(commits) = &mut self.commits {
            commits.commit_loaded(generation, result);
        }
    }

    pub fn commit_failed(&mut self, generation: i64, error: String) {
        if let Some(commits) = &mut self.commits {
            commits.commit_failed(generation, error);
        }
    }

    pub fn search_started(&mut self, job_id: i64) {
        if let Some(search) = &mut self.search {
            search.search_started(job_id);
        }
    }

    /// Shows the progress of the running search, or its hits. Returns the next page to fetch.
    pub fn search_progress(&mut self, result: GetSearchResult) -> Option<Action> {
        let request = self.search.as_mut()?.search_progress(result);
        self.search_request(request)
    }

    pub fn search_failed(&mut self, error: String) {
        if let Some(search) = &mut self.search {
            search.search_failed(error);
        }
    }

    /// Forgets the cancelled search and explanation jobs.
    pub fn search_cancelled(&mut self) {
        if let Some(search) = &mut self.search {
            search.cancelled();
        }
    }

    pub fn explain_started(&mut self, job_id: i64) {
        if let Some(search) = &mut self.search {
            search.explain_started(job_id);
        }
    }

    pub fn explain_progress(&mut self, result: GetExplainResult) {
        if let Some(search) = &mut self.search {
            search.explain_progress(result);
        }
    }

    pub fn explain_failed(&mut self, error: String) {
        if let Some(search) = &mut self.search {
            search.explain_failed(error);
        }
    }

    pub fn components_loaded(&mut self, components: GetAnalysisComponentsResult) {
        self.analysis.components_loaded(components);
    }

    pub fn components_failed(&mut self, error: String) {
        self.analysis.components_failed(error);
    }

    /// Shows what the analyzer made of `text`. An analyzer that the builder applied is now the
    /// current one, which the search screen parses with from now on. Returns the components to
    /// fetch if there are none yet, as when `a` on the documents screen opened the screen.
    pub fn analyzed(&mut self, text: String, result: AnalyzeResult) -> Option<Action> {
        self.analysis.analyzed(text, result);
        if let Some(search) = &mut self.search {
            search.set_analyzer(self.analysis.settings());
        }
        analysis_action(self.analysis.initial_request())
    }

    pub fn analyze_failed(&mut self, error: String) -> Option<Action> {
        self.analysis.analyze_failed(error);
        analysis_action(self.analysis.initial_request())
    }

    fn forget_scans(&mut self) {
        self.scan = None;
        self.queue.clear();
    }

    /// The scan job, while one runs.
    fn scan_job(&self) -> Option<i64> {
        self.scan.as_ref().and_then(|scan| scan.job_id)
    }

    /// The jobs to poll: the running scan, search and explanation.
    pub fn polls(&self) -> Vec<Poll> {
        let search = self.search.as_ref();
        self.scan_job()
            .map(Poll::Scan)
            .into_iter()
            .chain(search.and_then(Search::search_job).map(Poll::Search))
            .chain(search.and_then(Search::explain_job).map(Poll::Explain))
            .collect()
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
        self.notice = None;
        if let Some(prompt) = &mut self.prompt {
            return match prompt.handle_key(key) {
                PromptEvent::Submit(path) => {
                    prompt.set_busy();
                    self.directory = prompt.directory();
                    Some(Action::Open {
                        path: expand_home(&path, self.home.as_deref()),
                        directory_impl: self.directory,
                    })
                }
                PromptEvent::Cancel => {
                    self.prompt = None;
                    None
                }
                PromptEvent::None => None,
            };
        }
        if let Some(documents) = &mut self.documents
            && self.screen == Screen::Documents
            && documents.prompting()
        {
            let request = documents.handle_key(key.code);
            return self.documents_request(request);
        }
        if let Some(terms) = &mut self.terms
            && self.screen == Screen::Terms
            && terms.prompting()
        {
            let request = terms.handle_key(key.code);
            return self.terms_request(request);
        }
        if let Some(search) = &mut self.search
            && self.screen == Screen::Search
            && search.prompting()
        {
            let request = search.handle_key(key);
            return self.search_request(request);
        }
        if self.screen == Screen::Analysis && self.analysis.prompting() {
            return analysis_action(self.analysis.handle_key(key));
        }
        let search_jobs = match (&self.search, self.screen) {
            (Some(search), Screen::Search) => search.jobs(),
            _ => Vec::new(),
        };
        match key.code {
            // Esc stops the scans first, then the search screen's jobs, and quits only when there
            // are none.
            KeyCode::Esc if self.scan.is_some() => match self.scan_job() {
                Some(job_id) => Some(Action::CancelScan(job_id)),
                None => {
                    self.scan_cancelled();
                    None
                }
            },
            KeyCode::Esc if !search_jobs.is_empty() => Some(Action::CancelSearch(search_jobs)),
            KeyCode::Char('q') | KeyCode::Esc => Some(Action::Quit),
            KeyCode::Char('1') if self.overview.is_some() => {
                self.screen = Screen::Overview;
                None
            }
            KeyCode::Char('2') if self.documents.is_some() => {
                self.screen = Screen::Documents;
                self.initial_request()
            }
            KeyCode::Char('3') if self.terms.is_some() => {
                self.screen = Screen::Terms;
                self.initial_request()
            }
            KeyCode::Char('4') if self.commits.is_some() => {
                self.screen = Screen::Commits;
                self.initial_request()
            }
            KeyCode::Char('5') if self.search.is_some() => {
                self.screen = Screen::Search;
                None
            }
            KeyCode::Char('6') => {
                self.screen = Screen::Analysis;
                self.initial_request()
            }
            KeyCode::Char('o') => {
                // Starts from the open index, so a sibling is a Ctrl+W away.
                let path = self.overview.as_ref().map_or("", Overview::path);
                self.prompt = Some(self.path_prompt(path));
                None
            }
            KeyCode::Char('c') if self.overview.is_some() => Some(Action::Close),
            KeyCode::Char('R') if self.overview.is_some() => {
                self.notice = Some(Notice::Reopening);
                Some(Action::Reopen)
            }
            code if self.screen == Screen::Documents => {
                let request = self.documents.as_mut()?.handle_key(code);
                self.documents_request(request)
            }
            code if self.screen == Screen::Terms => {
                let request = self.terms.as_mut()?.handle_key(code);
                self.terms_request(request)
            }
            code if self.screen == Screen::Commits => {
                commits_action(self.commits.as_mut()?.handle_key(code))
            }
            _ if self.screen == Screen::Search => {
                let request = self.search.as_mut()?.handle_key(key);
                self.search_request(request)
            }
            _ if self.screen == Screen::Analysis => analysis_action(self.analysis.handle_key(key)),
            KeyCode::Char('/') => {
                let (field, term) = self.overview.as_ref()?.selected_top_term()?;
                self.screen = Screen::Search;
                let request = self.search.as_mut()?.term_query(&field, term);
                self.search_request(request)
            }
            KeyCode::Enter => {
                if let Some((field, term)) = self.overview.as_ref()?.selected_top_term() {
                    self.screen = Screen::Terms;
                    let request = self.terms.as_mut()?.browse(&field, term);
                    return self.terms_request(request);
                }
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
            code => {
                if let Some(overview) = &mut self.overview {
                    overview.handle_key(code);
                }
                None
            }
        }
    }

    pub fn draw(&mut self, frame: &mut Frame) {
        // The analysis screen needs no index, so its tab shows without one.
        let tabs_shown = self.overview.is_some() || self.screen == Screen::Analysis;
        let [tabs, body, footer] = Layout::vertical([
            Constraint::Length(u16::from(tabs_shown)),
            Constraint::Fill(1),
            Constraint::Length(1),
        ])
        .areas(frame.area());
        let cancel = if self.scan.is_some() {
            "Esc cancel scan   "
        } else {
            ""
        };
        let keys = match (
            &mut self.overview,
            &mut self.documents,
            &mut self.terms,
            &mut self.commits,
            &mut self.search,
            self.screen,
        ) {
            (overview, _, _, _, _, Screen::Analysis) => {
                self.analysis.render(frame, body);
                if self.analysis.prompting() {
                    Line::from(self.analysis.keys())
                } else if overview.is_some() {
                    Line::from(format!(
                        " {cancel}q quit   o open   c close   1-6 screen   {}",
                        self.analysis.keys()
                    ))
                } else {
                    Line::from(format!(" q quit   o open   {}", self.analysis.keys()))
                }
            }
            (Some(_), _, _, _, Some(search), Screen::Search) => {
                search.render(frame, body);
                if search.prompting() {
                    Line::from(search.keys())
                } else {
                    let cancel = if self.scan.is_some() || !search.jobs().is_empty() {
                        "Esc cancel   "
                    } else {
                        ""
                    };
                    Line::from(format!(
                        " {cancel}q quit   o open   c close   1-6 screen   {}",
                        search.keys()
                    ))
                }
            }
            (Some(_), _, _, Some(commits), _, Screen::Commits) => {
                commits.render(frame, body);
                Line::from(format!(
                    " {cancel}q quit   o open   c close   R reopen   1-6 screen   r read again   ⏎ segments   \
                     Tab panel   ↑↓"
                ))
            }
            (Some(_), Some(documents), _, _, _, Screen::Documents) => {
                documents.render(frame, body);
                let steps = match documents.walk() {
                    Some(Walk::TermDocs) => "[ ] term docs   ",
                    Some(Walk::SearchHits) => "[ ] hits   ",
                    None => "",
                };
                match documents.viewer() {
                    Some(viewer) => Line::from(viewer.keys()),
                    None => Line::from(format!(
                        " {cancel}q quit   o open   c close   1-6 screen   ←→ doc   {steps}i go to   \
                         ⏎ term vector   v view   a analyze   x numbers   e empty   m similar   Tab panel   ↑↓"
                    )),
                }
            }
            (Some(_), _, Some(terms), _, _, Screen::Terms) => {
                terms.render(frame, body);
                if terms.prompting() {
                    Line::from(" type to seek   ⏎/Esc done")
                } else {
                    Line::from(format!(
                        " {cancel}q quit   o open   c close   1-6 screen   / seek   \
                         ⏎ open doc   ←/→ column   ↑↓"
                    ))
                }
            }
            (Some(overview), _, _, _, _, _) => {
                overview.render(frame, body);
                if self.scan.is_some() {
                    Line::from(
                        " Esc cancel scan   q quit   o open   c close   R reopen   1-6 screen   s/S sort   \
                         Tab panel   ↑↓",
                    )
                } else if overview.selected_top_term().is_some() {
                    Line::from(
                        " q quit   o open   c close   R reopen   1-6 screen   ⏎ term's docs   / search term   \
                         +/- top N   s/S sort   Tab panel   ↑↓",
                    )
                } else {
                    Line::from(
                        " q quit   o open   c close   R reopen   1-6 screen   ⏎ terms   a all terms   \
                         +/- top N   s/S sort   Tab panel   ↑↓",
                    )
                }
            }
            (None, _, _, _, _, _) => {
                let lines = vec![
                    Line::from("No index open. Press o to open one, or 6 to try an analyzer."),
                    Line::from(""),
                    Line::from(LEIA.line()),
                    Line::from(server_line(&self.version)),
                    Line::from(java_line(&self.version.java)),
                ];
                frame.render_widget(
                    Paragraph::new(lines).block(Block::bordered().title(" leia ")),
                    body,
                );
                Line::from(" q quit   o open   6 analysis")
            }
        };
        if tabs_shown {
            let selected = match self.screen {
                Screen::Overview => 0,
                Screen::Documents => 1,
                Screen::Terms => 2,
                Screen::Commits => 3,
                Screen::Search => 4,
                Screen::Analysis => 5,
            };
            let tabs = if let Some(notice) = &self.notice {
                let line = notice.line();
                let [tabs, notice] =
                    Layout::horizontal([Constraint::Length(TABS_WIDTH), Constraint::Fill(1)])
                        .areas(tabs);
                frame.render_widget(line.right_aligned(), notice);
                tabs
            } else {
                tabs
            };
            frame.render_widget(
                Tabs::new(TAB_TITLES)
                    .select(selected)
                    .highlight_style(Style::new().add_modifier(Modifier::REVERSED)),
                tabs,
            );
        }
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

const TAB_TITLES: [&str; 6] = [
    "1 Overview",
    "2 Documents",
    "3 Terms",
    "4 Commits",
    "5 Search",
    "6 Analysis",
];

/// How wide the tabs are: each title with a space on either side, and a divider between two.
/// The notice takes the rest of the line.
const TABS_WIDTH: u16 = {
    let mut width = TAB_TITLES.len() - 1;
    let mut i = 0;
    while i < TAB_TITLES.len() {
        width += TAB_TITLES[i].len() + 2;
        i += 1;
    }
    width as u16
};

/// What the screens build on from each field: everything but the postings statistics, which
/// change with the documents.
fn schema(fields: &[IndexField]) -> Vec<IndexField> {
    fields
        .iter()
        .map(|field| IndexField {
            terms: None,
            ..field.clone()
        })
        .collect()
}

fn analysis_action(request: Option<analysis::Request>) -> Option<Action> {
    match request? {
        analysis::Request::Components => Some(Action::LoadAnalysisComponents),
        analysis::Request::Analyze(params) => Some(Action::Analyze(*params)),
    }
}

fn commits_action(request: Option<commits::Request>) -> Option<Action> {
    match request? {
        commits::Request::Commits => Some(Action::LoadCommits),
        commits::Request::Commit(generation) => Some(Action::LoadCommit(generation)),
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::analysis::{AnalyzeResult, Analyzer};
    use crate::protocol::document::{DocumentField, StoredType, StoredValue};
    use crate::protocol::index::{DocValuesType, IndexField, IndexOptions, TermStats};
    use crate::protocol::search::{
        Explanation, Hit, SearchQuery, SearchResults, TermQuery, TotalHitsRelation,
    };
    use crate::protocol::terms::{FieldTerms, Posting, TermEntry, TopTerm};
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
    fn the_analysis_screen_needs_no_index_and_its_analyzer_parses_queries() {
        let mut app = App::new(version(), None);
        assert!(render(&mut app).contains("6 analysis"));
        assert_eq!(
            app.handle_key(key(KeyCode::Char('6'))),
            Some(Action::LoadAnalysisComponents)
        );
        app.components_failed("down".to_owned());
        let screen = render(&mut app);
        assert!(screen.contains("6 Analysis"), "{screen}");
        assert!(screen.contains("standard analyzer"), "{screen}");
        assert!(screen.contains("q quit   o open   / text"), "{screen}");
        // The other screens need an index.
        assert_eq!(app.handle_key(key(KeyCode::Char('5'))), None);
        assert!(render(&mut app).contains("standard analyzer"));

        // b builds a custom analyzer, which the server tries first.
        assert_eq!(app.handle_key(key(KeyCode::Char('b'))), None);
        assert_eq!(app.handle_key(key(KeyCode::Left)), None);
        let Some(Action::Analyze(params)) = app.handle_key(key(KeyCode::Enter)) else {
            panic!("applying the analyzer analyzes");
        };
        let custom = params.analyzer.clone();
        assert!(custom.custom.is_some());
        assert_eq!(app.analyzed(params.text, AnalyzeResult::default()), None);
        assert!(render(&mut app).contains("standard › lowercase"));

        // An index opened now parses with it, and keeps the analysis screen shown.
        assert_eq!(
            app.opened(GetIndexStatsResult {
                fields: vec![field("body", 0, true)],
                ..stats("/data/idx")
            }),
            None
        );
        assert!(render(&mut app).contains("1-6 screen"));
        app.handle_key(key(KeyCode::Char('5')));
        app.handle_key(key(KeyCode::Char('/')));
        typed(&mut app, "red");
        let Some(Action::StartSearch { params, .. }) = app.handle_key(key(KeyCode::Enter)) else {
            panic!("Enter searches");
        };
        assert_eq!(params.query.classic.unwrap().analyzer, custom);
    }

    fn open(path: &str) -> Option<Action> {
        Some(Action::Open {
            path: path.to_owned(),
            directory_impl: DirectoryImpl::Fs,
        })
    }

    #[test]
    fn the_prompt_offers_the_opened_paths() {
        let mut app = App::new(version(), None);
        app.opened(stats("/data/a"));
        app.opened(stats("/data/b"));

        app.handle_key(key(KeyCode::Char('o')));
        app.handle_key(key(KeyCode::Up));

        assert_eq!(app.handle_key(key(KeyCode::Enter)), open("/data/a"));
    }

    #[test]
    fn opens_with_the_directory_chosen_last() {
        let mut app = App::new(version(), None).with_directory(DirectoryImpl::Nio);
        app.handle_key(key(KeyCode::Char('o')));
        typed(&mut app, "/data/idx");
        let ctrl_d = KeyEvent::new(KeyCode::Char('d'), KeyModifiers::CONTROL);

        app.handle_key(ctrl_d);
        app.handle_key(ctrl_d);
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::Open {
                path: "/data/idx".to_owned(),
                directory_impl: DirectoryImpl::Mmap,
            })
        );
        app.open_failed("No such file or directory: /data/idx".to_owned());
        assert!(render(&mut app).contains("directory: MMap"));
        app.handle_key(key(KeyCode::Esc));
        assert_eq!(app.directory(), DirectoryImpl::Mmap);
        app.handle_key(key(KeyCode::Char('o')));
        assert!(render(&mut app).contains("directory: MMap"));
    }

    #[test]
    fn opens_an_index_from_the_prompt() {
        let mut app = App::new(version(), None);
        assert!(render(&mut app).contains("No index open"));

        app.handle_key(key(KeyCode::Char('o')));
        typed(&mut app, "/data/idx");
        assert!(render(&mut app).contains("Open index"));

        assert_eq!(app.handle_key(key(KeyCode::Enter)), open("/data/idx"));
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

        assert_eq!(app.handle_key(key(KeyCode::Enter)), open("/data/firstx"));
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

        assert_eq!(app.handle_key(key(KeyCode::Enter)), open("/home/me/idx"));
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

        assert_eq!(app.handle_key(key(KeyCode::Enter)), open("/data/idx"));
    }

    #[test]
    fn scans_the_selected_field_and_polls_it() {
        let mut app = app_with_fields();

        assert_eq!(app.handle_key(key(KeyCode::Enter)), start("id"));
        assert_eq!(app.polls(), Vec::new());
        app.scan_started(7);
        assert_eq!(app.polls(), vec![Poll::Scan(7)]);

        assert_eq!(app.scan_progress(running(1024)), None);
        let screen = render(&mut app);
        assert!(screen.contains("1024…"), "{screen}");
        assert!(screen.contains("Esc cancel scan"), "{screen}");
        // A running scan is not started again.
        assert_eq!(app.handle_key(key(KeyCode::Enter)), None);

        assert_eq!(app.scan_progress(done("id", 3)), None);
        assert_eq!(app.polls(), Vec::new());
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
        assert_eq!(app.polls(), Vec::new());
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

        assert_eq!(app.polls(), Vec::new());
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
        assert_eq!(app.polls(), Vec::new());
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

    fn document(doc_id: i32) -> GetDocumentResult {
        GetDocumentResult {
            doc_id,
            deleted: false,
            fields: vec![DocumentField {
                name: "id".to_owned(),
                norm: None,
                stored: vec![StoredValue {
                    kind: StoredType::String,
                    value: format!("doc-{doc_id}"),
                    binary: false,
                    full_length: None,
                }],
                doc_values: None,
                has_term_vector: false,
            }],
        }
    }

    fn app_with_documents() -> App {
        let mut app = App::new(version(), None);
        app.opened(GetIndexStatsResult {
            num_docs: 3,
            max_doc: 3,
            fields: vec![field("id", 0, true)],
            ..stats("/data/idx")
        });
        app
    }

    #[test]
    fn switches_to_the_documents_and_fetches_the_first() {
        let mut app = app_with_documents();
        assert!(render(&mut app).contains("1 Overview"));

        assert_eq!(
            app.handle_key(key(KeyCode::Char('2'))),
            Some(Action::LoadDocument(0))
        );
        app.document_loaded(document(0));
        let screen = render(&mut app);
        assert!(screen.contains("#0 of 0–2"), "{screen}");
        assert!(screen.contains("doc-0"), "{screen}");

        assert_eq!(
            app.handle_key(key(KeyCode::Right)),
            Some(Action::LoadDocument(1))
        );
        // Back and forth fetches nothing new.
        assert_eq!(app.handle_key(key(KeyCode::Char('1'))), None);
        assert!(render(&mut app).contains("Path       /data/idx"));
        assert_eq!(app.handle_key(key(KeyCode::Char('2'))), None);
    }

    #[test]
    fn analyzes_a_stored_value_on_the_analysis_screen() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(1));
        assert!(render(&mut app).contains("a analyze"));

        let Some(Action::Analyze(params)) = app.handle_key(key(KeyCode::Char('a'))) else {
            panic!("a analyzes the stored value");
        };
        assert_eq!(params.text, "doc-1");
        assert_eq!(params.analyzer, Analyzer::default());
        // The screen came without its components, which follow the analysis.
        assert_eq!(
            app.analyzed(params.text, AnalyzeResult::default()),
            Some(Action::LoadAnalysisComponents)
        );
        let screen = render(&mut app);
        assert!(screen.contains("6 Analysis"), "{screen}");
        assert!(screen.contains("Text · doc 1 · id"), "{screen}");

        // So does the viewer, for the value it shows.
        app.handle_key(key(KeyCode::Char('2')));
        app.handle_key(key(KeyCode::Char('v')));
        assert!(matches!(
            app.handle_key(key(KeyCode::Char('a'))),
            Some(Action::Analyze(params)) if params.text == "doc-1"
        ));
    }

    #[test]
    fn views_a_value_fetches_it_whole_and_copies_it() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        let mut doc = document(0);
        doc.fields[0].stored[0].full_length = Some(100_000);
        app.document_loaded(doc);

        assert_eq!(
            app.handle_key(key(KeyCode::Char('v'))),
            Some(Action::LoadStoredValue {
                doc_id: 0,
                field: "id".to_owned(),
                index: 0,
            })
        );
        assert!(
            render(&mut app)
                .contains("Esc close   ←/→ value   t text   h hex   J json   d delimited   y copy")
        );
        // The viewer takes q and the screen keys.
        assert_eq!(app.handle_key(key(KeyCode::Char('1'))), None);
        let whole = StoredValue {
            kind: StoredType::String,
            value: "doc-0, whole".to_owned(),
            binary: false,
            full_length: None,
        };
        app.stored_value_loaded(0, "id", 0, &whole);
        assert!(render(&mut app).contains("doc-0, whole"));
        assert_eq!(
            app.handle_key(key(KeyCode::Char('y'))),
            Some(Action::Copy("doc-0, whole".to_owned()))
        );
        assert_eq!(app.handle_key(key(KeyCode::Char('q'))), None);
        assert!(render(&mut app).contains("v view"));
        app.stored_value_failed(0, "id", 0, "late".to_owned());
        assert_eq!(app.handle_key(key(KeyCode::Char('q'))), Some(Action::Quit));
    }

    #[test]
    fn opening_another_index_on_the_documents_screen_fetches_its_first_document() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(0));

        assert_eq!(
            app.opened(GetIndexStatsResult {
                max_doc: 1,
                ..stats("/data/other")
            }),
            Some(Action::LoadDocument(0))
        );
        assert_eq!(app.opened(stats("/data/empty")), None);
    }

    #[test]
    fn the_document_prompt_takes_the_keys() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(0));
        app.handle_key(key(KeyCode::Char('i')));

        // Digits go to the prompt, not to the screen switch; Esc closes the prompt, not leia.
        assert_eq!(app.handle_key(key(KeyCode::Char('1'))), None);
        assert!(render(&mut app).contains("Go to document"));
        assert_eq!(app.handle_key(key(KeyCode::Esc)), None);
        assert!(!render(&mut app).contains("Go to document"));

        app.handle_key(key(KeyCode::Char('i')));
        app.handle_key(key(KeyCode::Char('2')));
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::LoadDocument(2))
        );
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    #[test]
    fn a_failed_document_shows_the_error() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));

        app.document_failed(0, "Cannot read document 0".to_owned());

        assert!(render(&mut app).contains("Cannot read it: Cannot read document 0"));
    }

    #[test]
    fn opening_another_index_forgets_the_scans() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('a')));
        app.scan_started(4);

        app.opened(stats("/data/other"));

        assert_eq!(app.polls(), Vec::new());
        assert_eq!(app.scan_progress(done("id", 1)), None);
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    fn reopen_result(changed: bool) -> ReopenIndexResult {
        ReopenIndexResult {
            changed,
            path: "/data/idx".to_owned(),
            segments_file: "segments_1".to_owned(),
            num_docs: 0,
            max_doc: 0,
            created_version_major: 10,
            min_segment_lucene_version: None,
        }
    }

    #[test]
    fn r_reopens_and_the_tab_line_says_what_came_of_it() {
        let mut app = App::new(version(), None);
        assert_eq!(app.handle_key(key(KeyCode::Char('R'))), None);
        let mut app = app_with_fields();
        assert!(render(&mut app).contains("R reopen"));

        assert_eq!(
            app.handle_key(key(KeyCode::Char('R'))),
            Some(Action::Reopen)
        );
        assert!(render(&mut app).contains("Reopening…"));
        app.reopen_unchanged(&reopen_result(false));
        assert!(render(&mut app).contains("Unchanged: segments_1"));

        // The next key clears it.
        app.handle_key(key(KeyCode::Down));
        assert!(!render(&mut app).contains("Unchanged"));

        app.handle_key(key(KeyCode::Char('R')));
        app.reopened(GetIndexStatsResult {
            segments_file: "segments_2".to_owned(),
            ..stats("/data/idx")
        });
        assert!(render(&mut app).contains("Reopened: segments_2"));
    }

    #[test]
    fn a_failed_reopen_keeps_the_index_and_says_why() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(1));
        app.handle_key(key(KeyCode::Char('R')));

        app.reopen_failed("Corrupt index".to_owned());

        let screen = render(&mut app);
        assert!(screen.contains("Reopen failed: Corrupt index"), "{screen}");
        assert!(screen.contains("doc-1"), "{screen}");
    }

    #[test]
    fn reopening_keeps_the_scan_of_an_unchanged_index_and_forgets_it_otherwise() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('a')));
        app.scan_started(4);
        app.handle_key(key(KeyCode::Char('R')));

        app.reopen_unchanged(&reopen_result(false));
        assert_eq!(app.polls().len(), 1);

        app.reopened(stats("/data/idx"));
        assert_eq!(app.polls(), Vec::new());
    }

    #[test]
    fn reopening_keeps_the_document_shown_within_the_new_max_doc() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(2));
        let with_docs = |max_doc| GetIndexStatsResult {
            max_doc,
            fields: vec![field("id", 0, true)],
            ..stats("/data/idx")
        };

        assert_eq!(app.reopened(with_docs(5)), Some(Action::LoadDocument(2)));
        assert_eq!(app.reopened(with_docs(2)), Some(Action::LoadDocument(1)));
        assert_eq!(app.reopened(with_docs(0)), None);
    }

    #[test]
    fn reopening_keeps_the_field_of_the_terms_screen() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('3')));
        assert_eq!(
            app.handle_key(key(KeyCode::Down)),
            Some(Action::LoadTerms {
                field: "body".to_owned(),
                from: None,
                more: false,
            })
        );

        assert_eq!(
            app.reopened(GetIndexStatsResult {
                fields: vec![
                    field("title", 0, true),
                    field("id", 1, true),
                    field("body", 2, true),
                ],
                ..stats("/data/idx")
            }),
            Some(Action::LoadTerms {
                field: "body".to_owned(),
                from: None,
                more: false,
            })
        );
    }

    #[test]
    fn reopening_keeps_the_query_of_the_search_screen_without_running_it() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('5')));
        app.handle_key(key(KeyCode::Char('/')));
        typed(&mut app, "red");
        let Some(Action::StartSearch { .. }) = app.handle_key(key(KeyCode::Enter)) else {
            panic!("Enter searches");
        };

        // `title` would be the default field of a new search screen.
        assert_eq!(
            app.reopened(GetIndexStatsResult {
                fields: vec![field("title", 0, true), field("id", 1, true)],
                ..stats("/data/idx")
            }),
            None
        );

        assert_eq!(app.polls(), Vec::new());
        app.handle_key(key(KeyCode::Char('/')));
        let Some(Action::StartSearch { params, .. }) = app.handle_key(key(KeyCode::Enter)) else {
            panic!("Enter searches");
        };
        let classic = params.query.classic.unwrap();
        assert_eq!(classic.expression, "red");
        assert_eq!(classic.default_field, "id");
    }

    fn text(value: &str) -> BytesValue {
        BytesValue {
            value: value.to_owned(),
            binary: false,
        }
    }

    /// The documents index on the terms screen, with the terms "1" and "2" of `id` and the
    /// postings of "1": documents 0 and 2.
    fn app_on_terms() -> App {
        let mut app = app_with_documents();
        assert_eq!(
            app.handle_key(key(KeyCode::Char('3'))),
            Some(Action::LoadTerms {
                field: "id".to_owned(),
                from: None,
                more: false,
            })
        );
        let entry = |term: &str| TermEntry {
            term: term.to_owned(),
            binary: false,
            doc_freq: 1,
            total_term_freq: 1,
        };
        assert_eq!(
            app.terms_loaded(
                "id",
                false,
                GetTermsResult {
                    terms: vec![entry("1"), entry("2")],
                    next: None,
                }
            ),
            Some(Action::LoadPostings {
                field: "id".to_owned(),
                term: text("1"),
                from_doc: 0,
                more: false,
            })
        );
        let posting = |doc_id| Posting {
            doc_id,
            deleted: false,
            freq: None,
        };
        let postings = GetPostingsResult {
            doc_freq: 2,
            total_term_freq: 2,
            postings: vec![posting(0), posting(2)],
            next_doc: None,
        };
        assert_eq!(app.postings_loaded("id", &text("1"), false, postings), None);
        app
    }

    #[test]
    fn opens_a_posting_on_the_documents_screen() {
        let mut app = app_on_terms();
        assert!(render(&mut app).contains("3 Terms"));
        app.handle_key(key(KeyCode::Enter));
        app.handle_key(key(KeyCode::Enter));

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::LoadDocument(0))
        );
        assert_eq!(
            app.document_loaded(document(0)),
            Some(Action::LoadPositions {
                doc_id: 0,
                field: "id".to_owned(),
                term: text("1"),
            })
        );
        app.positions_loaded(0, "id", &text("1"), Vec::new());
        let screen = render(&mut app);
        assert!(screen.contains("id:1  1 of 2"), "{screen}");
        assert!(screen.contains("[ ] term docs"), "{screen}");

        assert_eq!(
            app.handle_key(key(KeyCode::Char(']'))),
            Some(Action::LoadDocument(2))
        );
        assert_eq!(app.handle_key(key(KeyCode::Char(']'))), None);
        // The terms screen has everything it shows.
        assert_eq!(app.handle_key(key(KeyCode::Char('3'))), None);
    }

    #[test]
    fn the_seek_prompt_takes_the_keys() {
        let mut app = app_on_terms();
        app.handle_key(key(KeyCode::Char('/')));

        let seek = |from: &str| {
            Some(Action::LoadTerms {
                field: "id".to_owned(),
                from: Some(text(from)),
                more: false,
            })
        };
        assert_eq!(app.handle_key(key(KeyCode::Char('q'))), seek("q"));
        assert_eq!(app.handle_key(key(KeyCode::Char('1'))), seek("q1"));
        assert!(render(&mut app).contains("type to seek"));
        assert_eq!(app.handle_key(key(KeyCode::Esc)), None);
        assert!(render(&mut app).contains("3 Terms"));
        assert_eq!(app.handle_key(key(KeyCode::Char('q'))), Some(Action::Quit));
    }

    #[test]
    fn opening_another_index_on_the_terms_screen_fetches_its_terms() {
        let mut app = app_on_terms();

        assert_eq!(
            app.opened(GetIndexStatsResult {
                fields: vec![field("title", 0, true)],
                ..stats("/data/other")
            }),
            Some(Action::LoadTerms {
                field: "title".to_owned(),
                from: None,
                more: false,
            })
        );
    }

    #[test]
    fn enter_on_a_top_term_browses_its_documents() {
        let mut app = app_with_documents();
        assert_eq!(app.handle_key(key(KeyCode::Enter)), start("id"));
        app.scan_started(1);
        let top = |term: &str| TopTerm {
            term: term.to_owned(),
            binary: false,
            doc_freq: 1,
            total_term_freq: 1,
        };
        app.scan_progress(GetFieldTermScanResult {
            terms_scanned: 2,
            terms: Some(FieldTerms {
                field: "id".to_owned(),
                unique_terms: 2,
                top_terms: vec![top("1"), top("2")],
            }),
        });
        app.handle_key(key(KeyCode::Tab));
        app.handle_key(key(KeyCode::Down));
        assert!(render(&mut app).contains("⏎ term's docs"));

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::LoadTerms {
                field: "id".to_owned(),
                from: Some(text("2")),
                more: false,
            })
        );
        let entry = TermEntry {
            term: "2".to_owned(),
            binary: false,
            doc_freq: 1,
            total_term_freq: 1,
        };
        let page = GetTermsResult {
            terms: vec![entry],
            next: None,
        };
        app.terms_loaded("id", false, page);
        assert!(render(&mut app).contains("id from 2 (/ seeks)"));
    }

    fn commit(generation: i64, current: bool) -> Commit {
        Commit {
            generation,
            segments_file: format!("segments_{generation}"),
            current,
            version: 1,
            segment_count: 0,
            max_doc: 0,
            del_count: 0,
            lucene_version: "10.5.2".to_owned(),
            user_data: BTreeMap::new(),
        }
    }

    #[test]
    fn switches_to_the_commits_and_fetches_them() {
        let mut app = app_with_fields();

        assert_eq!(
            app.handle_key(key(KeyCode::Char('4'))),
            Some(Action::LoadCommits)
        );
        assert_eq!(
            app.commits_loaded(vec![commit(2, true), commit(1, false)]),
            Some(Action::LoadCommit(2))
        );
        app.commit_loaded(
            2,
            GetCommitResult {
                commit: commit(2, true),
                segments: Vec::new(),
                files: Vec::new(),
            },
        );
        let screen = render(&mut app);
        assert!(screen.contains("4 Commits"), "{screen}");
        assert!(screen.contains("No segments."), "{screen}");
        assert!(screen.contains("r read again"), "{screen}");

        assert_eq!(
            app.handle_key(key(KeyCode::Down)),
            Some(Action::LoadCommit(1))
        );
        assert_eq!(
            app.handle_key(key(KeyCode::Char('r'))),
            Some(Action::LoadCommits)
        );
    }

    #[test]
    fn opening_another_index_on_the_commits_screen_fetches_its_commits() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('4')));
        app.commits_failed("unreadable".to_owned());
        assert!(render(&mut app).contains("Cannot list the commits: unreadable"));

        assert_eq!(app.opened(stats("/data/other")), Some(Action::LoadCommits));
    }

    /// The search screen of [`app_with_fields`] after `query` was submitted, as job 1.
    fn searching(query: &str) -> App {
        let mut app = app_with_fields();
        assert_eq!(app.handle_key(key(KeyCode::Char('5'))), None);
        app.handle_key(key(KeyCode::Char('/')));
        typed(&mut app, query);
        assert!(matches!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::StartSearch { cancel, .. }) if cancel.is_empty()
        ));
        app.search_started(1);
        app
    }

    fn page(doc_ids: &[i32]) -> GetSearchResult {
        GetSearchResult {
            docs_searched: 3,
            results: Some(SearchResults {
                query: "body:q".to_owned(),
                rewritten_query: "body:q".to_owned(),
                total_hits: doc_ids.len() as i64,
                total_hits_relation: TotalHitsRelation::EqualTo,
                hits: doc_ids
                    .iter()
                    .map(|&doc_id| Hit {
                        doc_id,
                        score: 1.0,
                        sort_values: Vec::new(),
                        fields: Vec::new(),
                    })
                    .collect(),
                took_millis: 1,
            }),
        }
    }

    #[test]
    fn the_query_line_takes_every_key() {
        // `q` and `1` are typed, not handled as quit and screen keys.
        let mut app = searching("q1");

        let screen = render(&mut app);
        assert!(screen.contains("5 Search"), "{screen}");
        assert!(screen.contains("│q1"), "{screen}");
        assert!(screen.contains("Esc cancel"), "{screen}");
        assert_eq!(app.polls(), vec![Poll::Search(1)]);
    }

    #[test]
    fn esc_stops_the_search_jobs_before_it_quits() {
        let mut app = searching("q");
        app.search_progress(page(&[2]));
        app.handle_key(key(KeyCode::Char('x')));
        app.explain_started(2);
        assert_eq!(app.polls(), vec![Poll::Explain(2)]);

        assert_eq!(
            app.handle_key(key(KeyCode::Esc)),
            Some(Action::CancelSearch(vec![2]))
        );
        app.search_cancelled();
        assert_eq!(app.polls(), Vec::new());
        assert!(render(&mut app).contains("Cancelled."));
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    #[test]
    fn enter_on_a_hit_opens_its_document() {
        let mut app = searching("q");
        assert_eq!(app.search_progress(page(&[2, 0])), None);
        app.explain_progress(GetExplainResult {
            explanation: Some(Explanation {
                matches: true,
                value: "1.0".to_owned(),
                description: "d".to_owned(),
                details: Vec::new(),
            }),
        });

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::LoadDocument(2))
        );
        assert!(render(&mut app).contains("2 Documents"));
        assert!(!render(&mut app).contains("[ ] term docs"));

        // `[`/`]` step through the hits, as far as they go.
        assert!(render(&mut app).contains("[ ] hits"));
        assert_eq!(
            app.handle_key(key(KeyCode::Char(']'))),
            Some(Action::LoadDocument(0))
        );
        assert_eq!(app.handle_key(key(KeyCode::Char(']'))), None);
        assert_eq!(
            app.handle_key(key(KeyCode::Char('['))),
            Some(Action::LoadDocument(2))
        );
        assert!(render(&mut app).contains("2 Documents"));
    }

    #[test]
    fn m_on_a_document_searches_for_documents_like_it() {
        let mut app = App::new(version(), None);
        app.opened(GetIndexStatsResult {
            fields: vec![field("id", 0, true), field("body", 1, true)],
            max_doc: 3,
            ..stats("/data/idx")
        });
        assert_eq!(
            app.handle_key(key(KeyCode::Char('2'))),
            Some(Action::LoadDocument(0))
        );

        let Some(Action::StartSearch { params, cancel }) = app.handle_key(key(KeyCode::Char('m')))
        else {
            panic!("m starts a search");
        };
        assert!(cancel.is_empty());
        let like = params.query.more_like_this.expect("a More-Like-This query");
        assert_eq!(like.doc_id, 0);
        // No field has positions: the first indexed one.
        assert_eq!(like.fields, ["id"]);
        assert!(render(&mut app).contains("More like this document · field id"));
    }

    #[test]
    fn slash_on_a_top_term_searches_for_it() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('j')));
        assert_eq!(app.handle_key(key(KeyCode::Enter)), start("body"));
        app.scan_started(1);
        app.scan_progress(GetFieldTermScanResult {
            terms_scanned: 1,
            terms: Some(FieldTerms {
                field: "body".to_owned(),
                unique_terms: 1,
                top_terms: vec![TopTerm {
                    term: "ff".to_owned(),
                    binary: true,
                    doc_freq: 1,
                    total_term_freq: 1,
                }],
            }),
        });
        app.handle_key(key(KeyCode::Tab));
        assert!(render(&mut app).contains("/ search term"));

        let Some(Action::StartSearch { params, cancel }) = app.handle_key(key(KeyCode::Char('/')))
        else {
            panic!("/ starts a search");
        };
        assert!(cancel.is_empty());
        assert_eq!(
            params.query,
            SearchQuery {
                term: Some(TermQuery {
                    field: "body".to_owned(),
                    term: BytesValue {
                        value: "ff".to_owned(),
                        binary: true,
                    },
                }),
                ..SearchQuery::default()
            }
        );
        assert!(render(&mut app).contains("Term · field body · hex"));
    }
}
