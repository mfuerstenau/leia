//! The application state: what is on screen and what a key does. It makes no calls itself:
//! [`App::handle_key`] returns an [`Action`], the caller makes the RPC calls and reports back with
//! [`App::opened`], [`App::open_failed`], [`App::closed`], and the `scan_*`, `document_*`,
//! `stored_value_*`, `term_vector_*`, `terms_*`, `postings_*`, `positions_*`, `commits_*`,
//! `commit_*`, `search_*`, `explain_*`, `components_*`, `analy*`, `check_*`, `export_*` or
//! `*jars*` methods.

use std::collections::VecDeque;
use std::path::PathBuf;

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph, Tabs, Wrap};

use crate::analysis::{self, Analysis};
use crate::build_info::{java_line, server_line};
use crate::check::{self, Check};
use crate::commits::{self, Commits};
use crate::doc_export::{DocumentData, assemble, to_json};
use crate::documents::{self, Documents, Walk};
use crate::export::{ExportEvent, ExportPrompt, ExportRequest, ExportTarget};
use crate::history::History;
use crate::jars::{DefaultJars, JarsEvent, JarsPopup};
use crate::logo;
use crate::overview::{DEFAULT_TOP_TERMS, FieldScan, Overview, step_top_terms};
use crate::overview::{label_line, relative};
use crate::prompt::{PathPrompt, PromptEvent, expand_home};
use crate::protocol::analysis::{AnalyzeParams, AnalyzeResult, GetAnalysisComponentsResult};
use crate::protocol::check::GetCheckIndexResult;
use crate::protocol::commits::{GetCommitResult, GetCommitsResult};
use crate::protocol::document::{
    BytesValue, GetDocumentResult, GetReconstructResult, ReconstructedField,
    StartReconstructParams, StoredValue, TermPosition, TermVectorTerm,
};
use crate::protocol::index::{
    DirectoryImpl, GetIndexStatsResult, IndexField, OpenIndexResult, OpenMode, ReopenIndexResult,
    SkippedIndex,
};
use crate::protocol::jars::{AddJarsParams, AddJarsResult};
use crate::protocol::search::{
    GetExplainResult, GetSearchResult, GetStoredScanResult, StartExplainParams, StartSearchParams,
    StartStoredScanParams,
};
use crate::protocol::terms::{
    GetExportTermsResult, GetFieldTermScanResult, GetPostingsResult, GetTermsResult,
    StartExportTermsParams,
};
use crate::protocol::version::VersionResult;
use crate::search::{self, Search};
use crate::terms::{self, Terms};
use crate::text::count;
use crate::viewer::size;

/// What the caller has to do after a key.
#[derive(Debug, PartialEq)]
pub enum Action {
    Quit,
    /// Open this index through this `Directory`, with or without a reader, and fetch its
    /// statistics if it has a reader. The prompt shows "Opening…" until the outcome is reported.
    Open {
        path: String,
        directory_impl: DirectoryImpl,
        mode: OpenMode,
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
    /// Cancel the jobs in `cancel`, then fetch this document, and report back with
    /// [`App::document_loaded`] or [`App::document_failed`].
    LoadDocument {
        doc_id: i32,
        cancel: Vec<i64>,
    },
    /// Fetch a stored value of a document whole, and report back with
    /// [`App::stored_value_loaded`] or [`App::stored_value_failed`].
    LoadStoredValue {
        doc_id: i32,
        field: String,
        index: i32,
    },
    /// Put this text on the clipboard.
    Copy(String),
    /// Cancel the jobs in `cancel`, then start rebuilding the terms a document was indexed with,
    /// and report back with [`App::reconstruct_started`] or [`App::reconstruct_failed`] (ADR
    /// 0029).
    StartReconstruct {
        params: StartReconstructParams,
        cancel: Vec<i64>,
    },
    /// Cancel this rebuild job, and report back with [`App::reconstruct_cancelled`].
    CancelReconstruct(i64),
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
    /// Fetch the commits of the index directory at this position, and report back with
    /// [`App::commits_loaded`] or [`App::commits_failed`].
    LoadCommits(usize),
    /// Fetch a commit of the index directory at this position whole, by generation, and report
    /// back with [`App::commit_loaded`] or [`App::commit_failed`].
    LoadCommit(usize, i64),
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
    /// Cancel the jobs in `cancel`, then start this scan of the stored values, and report back
    /// with [`App::stored_scan_started`] or [`App::stored_scan_failed`] (ADR 0027).
    StartStoredScan {
        params: StartStoredScanParams,
        cancel: Vec<i64>,
    },
    /// Cancel these search, explanation and scan jobs, and report back with
    /// [`App::search_cancelled`].
    CancelSearch(Vec<i64>),
    /// Fetch what custom analyzers can be built from, and report back with
    /// [`App::components_loaded`] or [`App::components_failed`].
    LoadAnalysisComponents,
    /// Analyze a text, and report back with [`App::analyzed`] or [`App::analyze_failed`].
    Analyze(AnalyzeParams),
    /// Start a check of the open path at this level, and report back with
    /// [`App::check_started`] or [`App::check_failed`] (ADR 0022).
    StartCheck(i32),
    /// Cancel this check job, and report back with [`App::check_cancelled`].
    CancelCheck(i64),
    /// Start this export of terms, and report back with [`App::export_started`] or
    /// [`App::export_failed`] (ADR 0022).
    StartExport(StartExportTermsParams),
    /// Cancel this export job, and report back with [`App::export_cancelled`].
    CancelExport(i64),
    /// Fetch a document with its stored values whole and its term vectors, and report back with
    /// [`App::document_export_loaded`] or [`App::export_failed`] (ADR 0030).
    LoadDocumentExport {
        doc_id: i32,
        indexed_terms: bool,
        target: ExportTarget,
    },
    /// Start rebuilding the indexed terms of the exported document, and report back with
    /// [`App::export_started`] or [`App::export_failed`].
    StartDocumentExport(StartReconstructParams),
    /// Write the exported document to `path`, never into `index_dirs`, and report back with
    /// [`App::document_exported`] or [`App::export_failed`].
    WriteExport {
        doc_id: i32,
        path: String,
        text: String,
        overwrite: bool,
        index_dirs: Vec<String>,
    },
    /// Fetch the loaded jars, and report back with [`App::jars_loaded`] or [`App::jars_failed`]
    /// (ADR 0024).
    LoadJars,
    /// Add these jars, and report back with [`App::jars_added`] or [`App::add_jars_failed`]. The
    /// popup shows "Loading…" until the outcome is reported.
    AddJars(AddJarsParams),
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
    /// Ask for the hits from [`App::stored_scan_from_hit`] on, and report back with
    /// [`App::stored_scan_progress`] or [`App::stored_scan_failed`].
    StoredScan(i64),
    /// Ask for the lines from [`App::check_from_line`] on, and report back with
    /// [`App::check_progress`] or [`App::check_failed`].
    Check(i64),
    /// Report back with [`App::export_progress`] or [`App::export_failed`].
    Export(i64),
    /// Report back with [`App::document_export_progress`] or [`App::export_failed`].
    DocumentExport(i64),
    /// Report back with [`App::reconstruct_progress`] or [`App::reconstruct_failed`].
    Reconstruct(i64),
}

/// The screens; `1` to `7` switch between them. All but the analysis screen need an open index.
/// Without a reader, only the commits and check screens show the index (ADR 0021, ADR 0022).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Screen {
    Overview,
    Documents,
    Terms,
    Commits,
    Search,
    Analysis,
    Check,
}

/// The scan that runs in the server. There is at most one; the others wait in the queue.
struct RunningScan {
    field: String,
    top_n: i32,
    /// `None` until the server has started the job.
    job_id: Option<i64>,
}

/// The export that runs. There is at most one.
enum RunningExport {
    /// The terms of a field, written by the server (ADR 0022).
    Terms {
        field: String,
        /// `None` until the server has started the job.
        job_id: Option<i64>,
        terms_written: i64,
    },
    /// A document, whose indexed terms are being rebuilt (ADR 0030).
    Document {
        data: Box<DocumentData>,
        target: ExportTarget,
        /// `None` until the server has started the job.
        job_id: Option<i64>,
        scanned: i64,
        total: Option<i64>,
    },
    /// A document, being written to its file.
    Writing(i32),
}

impl RunningExport {
    fn job_id(&self) -> Option<i64> {
        match self {
            Self::Terms { job_id, .. } | Self::Document { job_id, .. } => *job_id,
            Self::Writing(_) => None,
        }
    }

    fn poll(&self) -> Option<Poll> {
        match self {
            Self::Terms { job_id, .. } => job_id.map(Poll::Export),
            Self::Document { job_id, .. } => job_id.map(Poll::DocumentExport),
            Self::Writing(_) => None,
        }
    }

    /// The tab line while it runs.
    fn line(&self) -> String {
        match self {
            Self::Terms {
                field,
                terms_written,
                ..
            } => format!(
                "Exporting {field}: {}",
                count(usize::try_from(*terms_written).unwrap_or(0), "term")
            ),
            Self::Document {
                data,
                scanned,
                total,
                ..
            } => {
                let doc_id = data.document.doc_id;
                match total {
                    Some(total) => {
                        format!("Exporting doc {doc_id}: {scanned}/{total} terms")
                    }
                    None => format!("Exporting doc {doc_id}: {scanned} terms"),
                }
            }
            Self::Writing(doc_id) => format!("Exporting doc {doc_id}: writing…"),
        }
    }
}

/// What was opened (ADR 0021).
struct Opened {
    /// The absolute path that was opened.
    path: String,
    /// Whether it was opened without a reader: only the commits screen works.
    no_reader: bool,
    /// The `Directory` it was opened through.
    directory: DirectoryImpl,
    /// The index directories, in order; at least one.
    indexes: Vec<String>,
    /// The index directories that were found but left out.
    skipped: Vec<String>,
}

pub struct App {
    version: VersionResult,
    /// The directory that `~` stands for in the prompt.
    home: Option<PathBuf>,
    /// The recently opened paths, for the prompt (ADR 0020).
    history: History,
    /// The `Directory` the next prompt opens with: `--directory`, then the last one chosen.
    directory: DirectoryImpl,
    /// What is open, if anything. The screens that need a reader are `None` without one.
    opened: Option<Opened>,
    overview: Option<Overview>,
    documents: Option<Documents>,
    terms: Option<Terms>,
    commits: Option<Commits>,
    search: Option<Search>,
    /// Kept when an index is opened or closed: its analyzer is the search screen's.
    analysis: Analysis,
    /// The check screen of the open path, with or without a reader.
    check: Option<Check>,
    /// Where to export the terms of a field or a document, while it is asked.
    export_prompt: Option<ExportPrompt>,
    export: Option<RunningExport>,
    /// The directory that a relative export path is resolved against.
    cwd: Option<PathBuf>,
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
    /// The default jar folder, and whether its jars were loaded (ADR 0024).
    default_jars: DefaultJars,
    /// The jars popup, while it is open.
    jars: Option<JarsPopup>,
    /// The path of a prompt that was closed after it failed to open it: the next prompt starts
    /// from it, so that it can be tried again once a jar is added (ADR 0024).
    failed_path: Option<String>,
}

/// What the tab line says about the last reopen, or the footer about the last export, until the
/// next key.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Notice {
    Reopening,
    Unchanged(String),
    Reopened(String),
    Failed(String),
    /// `R` without a reader.
    NoReader,
    /// The terms or the document were written: what, and where.
    Exported(String),
    ExportFailed(String),
    /// `e` while an export runs.
    ExportRunning,
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
            Self::NoReader => Line::from("No reader to reopen"),
            Self::Exported(text) => Line::styled(text.clone(), Style::new().fg(Color::Green)),
            Self::ExportFailed(error) => Line::styled(
                format!("Export failed: {error}"),
                Style::new().fg(Color::Red),
            ),
            Self::ExportRunning => Line::from("An export runs; Esc cancels it"),
        }
    }

    /// Whether the notice takes the footer, which is wider than the tab line: the outcome of an
    /// export names its file.
    fn in_footer(&self) -> bool {
        matches!(self, Self::Exported(_) | Self::ExportFailed(_))
    }
}

impl App {
    pub fn new(version: VersionResult, home: Option<PathBuf>) -> Self {
        Self {
            version,
            home,
            history: History::in_memory(),
            directory: DirectoryImpl::default(),
            opened: None,
            overview: None,
            documents: None,
            terms: None,
            commits: None,
            search: None,
            analysis: Analysis::new(),
            check: None,
            export_prompt: None,
            export: None,
            cwd: None,
            screen: Screen::Overview,
            prompt: None,
            scan: None,
            queue: VecDeque::new(),
            top_n: DEFAULT_TOP_TERMS,
            notice: None,
            default_jars: DefaultJars::default(),
            jars: None,
            failed_path: None,
        }
    }

    /// The jars popup shows the default folder, and marks the jars that came from it.
    pub fn with_default_jars(mut self, default_jars: DefaultJars) -> Self {
        self.default_jars = default_jars;
        self
    }

    /// Keeps the recently opened paths in `history` instead of in memory.
    pub fn with_history(mut self, history: History) -> Self {
        self.history = history;
        self
    }

    /// Resolves relative export paths against `cwd`.
    pub fn with_cwd(mut self, cwd: Option<PathBuf>) -> Self {
        self.cwd = cwd;
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
        self.opened.is_some()
    }

    /// Shows `path` being opened with `mode`, for an open that no key started (the command
    /// line's path).
    pub fn opening(&mut self, path: &str, mode: OpenMode) {
        let mut prompt = self.path_prompt(path).with_mode(mode);
        prompt.set_busy();
        self.prompt = Some(prompt);
    }

    /// Shows the new index, opened with a reader, and the index directories left out of it. The
    /// server stopped the jobs of the previous one. Returns what the shown screen has to fetch
    /// first.
    pub fn opened(
        &mut self,
        stats: GetIndexStatsResult,
        skipped: Vec<SkippedIndex>,
    ) -> Option<Action> {
        self.history.add(&stats.path);
        let paths: Vec<String> = stats
            .indexes
            .iter()
            .map(|index| index.path.clone())
            .collect();
        let doc_bases = stats.indexes.iter().map(|index| index.doc_base);
        self.documents = Some(
            Documents::new(stats.fields.clone(), stats.max_doc)
                .with_indexes(doc_bases.zip(index_names(&stats.path, &paths)).collect()),
        );
        self.terms = Some(Terms::new(stats.fields.clone()));
        self.commits = Some(Commits::new(index_names(&stats.path, &paths)));
        let mut search = Search::new(&stats.fields, stats.max_doc);
        search.set_analyzer(self.analysis.settings());
        self.search = Some(search);
        self.opened = Some(Opened {
            path: stats.path.clone(),
            no_reader: false,
            directory: self.directory,
            indexes: paths,
            skipped: skipped.iter().map(|index| index.path.clone()).collect(),
        });
        self.check = Some(Check::new(&stats.path));
        self.overview = Some(Overview::new(stats, self.top_n).with_skipped(skipped));
        self.prompt = None;
        self.forget_scans();
        self.forget_export();
        self.initial_request()
    }

    /// Shows the index opened without a reader (ADR 0021): only the commits screen works, and
    /// it is shown unless the analysis screen is. The server stopped the jobs of the previous
    /// index. Returns what the shown screen has to fetch first.
    pub fn opened_without_reader(&mut self, result: OpenIndexResult) -> Option<Action> {
        self.history.add(&result.path);
        let paths: Vec<String> = result.indexes.into_iter().map(|index| index.path).collect();
        self.overview = None;
        self.documents = None;
        self.terms = None;
        self.search = None;
        self.commits = Some(Commits::new(index_names(&result.path, &paths)));
        self.check = Some(Check::new(&result.path));
        self.opened = Some(Opened {
            path: result.path,
            no_reader: true,
            directory: self.directory,
            indexes: paths,
            skipped: result.skipped.into_iter().map(|index| index.path).collect(),
        });
        if !matches!(self.screen, Screen::Analysis | Screen::Check) {
            self.screen = Screen::Commits;
        }
        self.prompt = None;
        self.forget_scans();
        self.forget_export();
        self.initial_request()
    }

    /// Shows the reopened index (ADR 0019) where the user was: the screens are new, as after
    /// opening it, but keep their place. The server stopped the jobs of the old index. Returns
    /// what the shown screen has to fetch first.
    pub fn reopened(&mut self, stats: GetIndexStatsResult) -> Option<Action> {
        let Some(overview) = &self.overview else {
            return self.opened(stats, Vec::new());
        };
        let same_fields = schema(overview.fields()) == schema(&stats.fields);
        self.notice = Some(Notice::Reopened(match stats.indexes.as_slice() {
            [index] => index.segments_file.clone(),
            indexes => {
                let old = &overview.stats().indexes;
                let changed = indexes
                    .iter()
                    .zip(old)
                    .filter(|(new, old)| new.segments_file != old.segments_file)
                    .count();
                format!("{changed} of {} indexes", indexes.len())
            }
        }));
        self.documents = Some(match &self.documents {
            Some(documents) => documents.reopened(stats.fields.clone(), stats.max_doc),
            None => Documents::new(stats.fields.clone(), stats.max_doc),
        });
        self.terms = Some(match &self.terms {
            Some(terms) => terms.reopened(stats.fields.clone()),
            None => Terms::new(stats.fields.clone()),
        });
        let paths: Vec<String> = stats
            .indexes
            .iter()
            .map(|index| index.path.clone())
            .collect();
        self.commits = Some(Commits::new(index_names(&stats.path, &paths)));
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
        if let Some(check) = &mut self.check {
            check.stopped_by_reopen();
        }
        if self.export.is_some()
            && let Some(Notice::Reopened(text)) = &mut self.notice
        {
            text.push_str("; export stopped");
        }
        self.forget_export();
        self.initial_request()
    }

    /// The index has no newer commit; everything stays as it is.
    pub fn reopen_unchanged(&mut self, result: &ReopenIndexResult) {
        self.notice = Some(Notice::Unchanged(match result.indexes.as_slice() {
            [index] => index
                .reader
                .as_ref()
                .map(|reader| reader.segments_file.clone())
                .unwrap_or_default(),
            indexes => format!("{} indexes", indexes.len()),
        }));
    }

    /// The open index stays open, and is shown as it was.
    pub fn reopen_failed(&mut self, error: String) {
        self.notice = Some(Notice::Failed(error));
    }

    /// Shows `error` in the prompt, which keeps the path, so it can be corrected. If
    /// `no_reader_helps`, it says that opening without a reader may still show the commits (ADR
    /// 0021).
    pub fn open_failed(&mut self, error: String, no_reader_helps: bool) {
        if self.prompt.is_none() {
            self.prompt = Some(self.path_prompt(""));
        }
        if let Some(prompt) = &mut self.prompt {
            if no_reader_helps {
                prompt.set_error_offering_no_reader(error);
            } else {
                prompt.set_error(error);
            }
        }
    }

    /// The server stopped the jobs of the closed index.
    pub fn closed(&mut self) {
        self.opened = None;
        self.overview = None;
        self.documents = None;
        self.terms = None;
        self.commits = None;
        self.search = None;
        self.check = None;
        self.forget_scans();
        self.forget_export();
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
            Screen::Search | Screen::Check => None,
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
            search::Request::StoredScan { params, cancel } => {
                Some(Action::StartStoredScan { params, cancel })
            }
            search::Request::Cancel(jobs) => Some(Action::CancelSearch(jobs)),
            search::Request::Document(doc_id) => {
                self.documents.as_mut()?.show_search_hit();
                self.screen = Screen::Documents;
                self.load_document(doc_id)
            }
            search::Request::View {
                doc_id,
                field,
                index,
                spans,
                match_count,
            } => {
                let documents = self.documents.as_mut()?;
                documents.show_search_hit();
                self.screen = Screen::Documents;
                let request = documents.view_matches(doc_id, field, index, spans, match_count);
                self.documents_request(request)
            }
        }
    }

    /// Fetches a document for the documents screen, which stops rebuilding a field of the shown
    /// one.
    fn load_document(&self, doc_id: i32) -> Option<Action> {
        let cancel = self
            .documents
            .as_ref()
            .and_then(Documents::reconstruct_job)
            .into_iter()
            .collect();
        Some(Action::LoadDocument { doc_id, cancel })
    }

    fn documents_request(&mut self, request: Option<documents::Request>) -> Option<Action> {
        match request? {
            documents::Request::Document(doc_id) => self.load_document(doc_id),
            documents::Request::Reconstruct {
                doc_id,
                field,
                cancel,
            } => Some(Action::StartReconstruct {
                params: StartReconstructParams {
                    doc_id,
                    fields: Some(vec![field]),
                },
                cancel,
            }),
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
            documents::Request::Export(doc_id) => {
                if self.export.is_some() {
                    self.notice = Some(Notice::ExportRunning);
                } else {
                    self.export_prompt = Some(ExportPrompt::document(
                        doc_id,
                        self.index_dirs(),
                        self.home.clone(),
                        self.cwd.clone(),
                    ));
                }
                None
            }
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
                self.load_document(doc_id)
            }
            terms::Request::Export(field) => {
                if self.export.is_some() {
                    self.notice = Some(Notice::ExportRunning);
                } else {
                    self.export_prompt = Some(ExportPrompt::new(
                        &field,
                        self.home.clone(),
                        self.cwd.clone(),
                    ));
                }
                None
            }
        }
    }

    /// Shows the document. Returns where the term occurs in it, if the terms screen opened it.
    pub fn document_loaded(&mut self, document: GetDocumentResult) -> Option<Action> {
        let documents = self.documents.as_mut()?;
        documents.loaded(document);
        let request = documents
            .positions_request()
            .or_else(|| documents.open_pending_view());
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

    pub fn reconstruct_started(&mut self, job_id: i64) {
        if let Some(documents) = &mut self.documents {
            documents.reconstruct_started(job_id);
        }
    }

    pub fn reconstruct_progress(&mut self, result: GetReconstructResult) {
        if let Some(documents) = &mut self.documents {
            documents.reconstruct_progress(result);
        }
    }

    pub fn reconstruct_failed(&mut self, error: String) {
        if let Some(documents) = &mut self.documents {
            documents.reconstruct_failed(error);
        }
    }

    pub fn reconstruct_cancelled(&mut self) {
        if let Some(documents) = &mut self.documents {
            documents.reconstruct_cancelled();
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

    /// Shows the commits of the index directory at `index`. Returns the selected commit to
    /// fetch whole.
    pub fn commits_loaded(&mut self, index: usize, result: GetCommitsResult) -> Option<Action> {
        commits_action(self.commits.as_mut()?.commits_loaded(index, result))
    }

    pub fn commits_failed(&mut self, index: usize, error: String) {
        if let Some(commits) = &mut self.commits {
            commits.commits_failed(index, error);
        }
    }

    pub fn commit_loaded(&mut self, index: usize, generation: i64, result: GetCommitResult) {
        if let Some(commits) = &mut self.commits {
            commits.commit_loaded(index, generation, result);
        }
    }

    pub fn commit_failed(&mut self, index: usize, generation: i64, error: String) {
        if let Some(commits) = &mut self.commits {
            commits.commit_failed(index, generation, error);
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

    pub fn stored_scan_started(&mut self, job_id: i64) {
        if let Some(search) = &mut self.search {
            search.stored_scan_started(job_id);
        }
    }

    /// The first hit the next poll of the regex scan asks for.
    pub fn stored_scan_from_hit(&self) -> i32 {
        self.search.as_ref().map_or(0, Search::stored_scan_from_hit)
    }

    /// Adds the hits of the regex scan that came in, and its outcome once it is there.
    pub fn stored_scan_progress(&mut self, result: GetStoredScanResult) {
        if let Some(search) = &mut self.search {
            search.stored_scan_progress(result);
        }
    }

    pub fn stored_scan_failed(&mut self, error: String) {
        if let Some(search) = &mut self.search {
            search.stored_scan_failed(error);
        }
    }

    /// Forgets the cancelled search, explanation and scan jobs.
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

    pub fn jars_loaded(&mut self, jars: Vec<String>) {
        if let Some(popup) = &mut self.jars {
            popup.jars_loaded(jars);
        }
    }

    pub fn jars_failed(&mut self, error: String) {
        if let Some(popup) = &mut self.jars {
            popup.jars_failed(error);
        }
    }

    /// Shows what the jars added. Returns the analysis components to fetch again if new jars
    /// were loaded and the components had been fetched: the jars may have added some.
    pub fn jars_added(&mut self, result: AddJarsResult) -> Option<Action> {
        let loaded = !result.jars.is_empty();
        if let Some(popup) = &mut self.jars {
            popup.added(result);
        }
        (loaded && self.analysis.has_components()).then_some(Action::LoadAnalysisComponents)
    }

    pub fn add_jars_failed(&mut self, error: String) {
        if let Some(popup) = &mut self.jars {
            popup.add_failed(error);
        }
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

    /// The jobs to poll: the running term scan, search, explanation, regex scan, check, export
    /// and rebuild of a field.
    pub fn polls(&self) -> Vec<Poll> {
        let search = self.search.as_ref();
        self.scan_job()
            .map(Poll::Scan)
            .into_iter()
            .chain(search.and_then(Search::search_job).map(Poll::Search))
            .chain(search.and_then(Search::explain_job).map(Poll::Explain))
            .chain(
                search
                    .and_then(Search::stored_scan_job)
                    .map(Poll::StoredScan),
            )
            .chain(self.check.as_ref().and_then(Check::job).map(Poll::Check))
            .chain(self.export.as_ref().and_then(RunningExport::poll))
            .chain(
                self.documents
                    .as_ref()
                    .and_then(Documents::reconstruct_job)
                    .map(Poll::Reconstruct),
            )
            .collect()
    }

    pub fn check_started(&mut self, job_id: i64) {
        if let Some(check) = &mut self.check {
            check.started(job_id);
        }
    }

    /// The first line of output the next poll of the check asks for.
    pub fn check_from_line(&self) -> i32 {
        self.check.as_ref().map_or(0, Check::from_line)
    }

    pub fn check_progress(&mut self, result: GetCheckIndexResult) {
        if let Some(check) = &mut self.check {
            check.progress(result);
        }
    }

    pub fn check_failed(&mut self, error: String) {
        if let Some(check) = &mut self.check {
            check.failed(error);
        }
    }

    pub fn check_cancelled(&mut self) {
        if let Some(check) = &mut self.check {
            check.cancelled();
        }
    }

    fn export_job(&self) -> Option<i64> {
        self.export.as_ref().and_then(RunningExport::job_id)
    }

    /// The index directories of the open path, opened or skipped: an export never writes there.
    fn index_dirs(&self) -> Vec<String> {
        self.opened.as_ref().map_or_else(Vec::new, |opened| {
            opened
                .indexes
                .iter()
                .chain(&opened.skipped)
                .cloned()
                .collect()
        })
    }

    /// The file as it would be typed: below the working directory, relative to it.
    fn shown_path(&self, path: &str) -> String {
        match self.cwd.as_ref().and_then(|cwd| cwd.to_str()) {
            Some(cwd) => relative(cwd, path),
            None => path.to_owned(),
        }
    }

    fn forget_export(&mut self) {
        self.export_prompt = None;
        self.export = None;
    }

    /// The export runs: the prompt closes, and the tab line shows the progress.
    pub fn export_started(&mut self, job_id: i64) {
        let Some(prompt) = self.export_prompt.take() else {
            return;
        };
        match (&mut self.export, prompt.field()) {
            (Some(RunningExport::Document { job_id: job, .. }), _) => *job = Some(job_id),
            (None, Some(field)) => {
                self.export = Some(RunningExport::Terms {
                    field: field.to_owned(),
                    job_id: Some(job_id),
                    terms_written: 0,
                });
            }
            _ => {}
        }
    }

    /// Shows the progress of the export, or where it wrote the terms.
    pub fn export_progress(&mut self, result: GetExportTermsResult) {
        let Some(RunningExport::Terms { terms_written, .. }) = &mut self.export else {
            return;
        };
        *terms_written = result.terms_written;
        if let Some(done) = result.result {
            self.export = None;
            self.notice = Some(Notice::Exported(format!(
                "Exported {} to {}",
                count(usize::try_from(done.terms).unwrap_or(0), "term"),
                self.shown_path(&done.path)
            )));
        }
    }

    /// The document to export is in. Returns the rebuild of its indexed terms, or, without them,
    /// where the export goes.
    pub fn document_export_loaded(
        &mut self,
        data: DocumentData,
        indexed_terms: bool,
        target: ExportTarget,
    ) -> Option<Action> {
        // The index was closed meanwhile.
        self.export_prompt.as_ref()?;
        if !indexed_terms {
            return self.finish_document_export(data, None, target);
        }
        let doc_id = data.document.doc_id;
        self.export = Some(RunningExport::Document {
            data: Box::new(data),
            target,
            job_id: None,
            scanned: 0,
            total: None,
        });
        Some(Action::StartDocumentExport(StartReconstructParams {
            doc_id,
            fields: None,
        }))
    }

    /// Shows the progress of the rebuild. Returns where the export goes once it is done.
    pub fn document_export_progress(&mut self, result: GetReconstructResult) -> Option<Action> {
        let Some(RunningExport::Document { scanned, total, .. }) = &mut self.export else {
            return None;
        };
        *scanned = result.scanned_terms;
        *total = result.total_terms;
        let done = result.result?;
        let Some(RunningExport::Document { data, target, .. }) = self.export.take() else {
            return None;
        };
        self.finish_document_export(*data, Some(done.fields), target)
    }

    /// Puts the export together: the file to write, or the text to copy.
    fn finish_document_export(
        &mut self,
        data: DocumentData,
        rebuilt: Option<Vec<ReconstructedField>>,
        target: ExportTarget,
    ) -> Option<Action> {
        let documents = self.documents.as_ref()?;
        let opened = self.opened.as_ref()?;
        let doc_id = data.document.doc_id;
        let index_directory = documents.index_of(doc_id).map(str::to_owned);
        let document = assemble(
            data,
            rebuilt,
            documents.schema(),
            &opened.path,
            index_directory,
        );
        let text = to_json(&document);
        match target {
            ExportTarget::File { path, overwrite } => {
                if self.export_prompt.is_none() {
                    self.export = Some(RunningExport::Writing(doc_id));
                }
                Some(Action::WriteExport {
                    doc_id,
                    path,
                    text,
                    overwrite,
                    index_dirs: self.index_dirs(),
                })
            }
            ExportTarget::Clipboard => {
                self.export_prompt = None;
                self.notice = Some(Notice::Exported(format!(
                    "Copied document {doc_id} as JSON, {}",
                    size(i64::try_from(text.len()).unwrap_or(i64::MAX))
                )));
                Some(Action::Copy(text))
            }
        }
    }

    /// The document was written to `path`.
    pub fn document_exported(&mut self, doc_id: i32, path: &str) {
        self.export_prompt = None;
        self.export = None;
        self.notice = Some(Notice::Exported(format!(
            "Exported document {doc_id} to {}",
            self.shown_path(path)
        )));
    }

    /// Shows why the export couldn't start in its prompt, or why the running one failed.
    pub fn export_failed(&mut self, error: String) {
        if let Some(prompt) = &mut self.export_prompt {
            prompt.set_error(error);
            self.export = None;
        } else if self.export.take().is_some() {
            self.notice = Some(Notice::ExportFailed(error));
        }
    }

    pub fn export_cancelled(&mut self) {
        self.export = None;
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
        if let Some(popup) = &mut self.jars {
            return match popup.handle_key(key) {
                JarsEvent::Submit(path) => {
                    popup.set_busy();
                    Some(Action::AddJars(AddJarsParams {
                        paths: vec![expand_home(&path, self.home.as_deref())],
                    }))
                }
                JarsEvent::Close => {
                    self.jars = None;
                    None
                }
                JarsEvent::None => None,
            };
        }
        if let Some(prompt) = &mut self.prompt {
            return match prompt.handle_key(key) {
                PromptEvent::Submit(path) => {
                    prompt.set_busy();
                    self.directory = prompt.directory();
                    Some(Action::Open {
                        path: expand_home(&path, self.home.as_deref()),
                        directory_impl: self.directory,
                        mode: prompt.mode(),
                    })
                }
                PromptEvent::Cancel => {
                    self.failed_path = self
                        .prompt
                        .take()
                        .filter(|prompt| prompt.error().is_some())
                        .map(|prompt| prompt.input().to_owned());
                    None
                }
                PromptEvent::None => None,
            };
        }
        if let Some(prompt) = &mut self.export_prompt {
            return match prompt.handle_key(key) {
                ExportEvent::Submit(ExportRequest::Terms(params)) => {
                    prompt.set_busy();
                    Some(Action::StartExport(params))
                }
                ExportEvent::Submit(ExportRequest::Document {
                    doc_id,
                    indexed_terms,
                    target,
                }) => {
                    prompt.set_busy();
                    Some(Action::LoadDocumentExport {
                        doc_id,
                        indexed_terms,
                        target,
                    })
                }
                ExportEvent::Cancel => {
                    self.export_prompt = None;
                    None
                }
                ExportEvent::None => None,
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
        let checking =
            self.screen == Screen::Check && self.check.as_ref().is_some_and(Check::running);
        let rebuilding = match (&self.documents, self.screen) {
            (Some(documents), Screen::Documents) => documents.reconstruct_job(),
            _ => None,
        };
        match key.code {
            // Esc stops the scans first, then the export, then the shown screen's jobs, and quits
            // only when there are none.
            KeyCode::Esc if self.scan.is_some() => match self.scan_job() {
                Some(job_id) => Some(Action::CancelScan(job_id)),
                None => {
                    self.scan_cancelled();
                    None
                }
            },
            KeyCode::Esc if self.export.is_some() => match self.export_job() {
                Some(job_id) => Some(Action::CancelExport(job_id)),
                None => {
                    self.export_cancelled();
                    None
                }
            },
            KeyCode::Esc if !search_jobs.is_empty() => Some(Action::CancelSearch(search_jobs)),
            KeyCode::Esc if let Some(job_id) = rebuilding => {
                Some(Action::CancelReconstruct(job_id))
            }
            KeyCode::Esc if checking => match self.check.as_ref().and_then(Check::job) {
                Some(job_id) => Some(Action::CancelCheck(job_id)),
                None => {
                    self.check_cancelled();
                    None
                }
            },
            KeyCode::Char('q') | KeyCode::Esc => Some(Action::Quit),
            KeyCode::Char('1') if self.has_index() => {
                self.screen = Screen::Overview;
                None
            }
            KeyCode::Char('2') if self.has_index() => {
                self.screen = Screen::Documents;
                self.initial_request()
            }
            KeyCode::Char('3') if self.has_index() => {
                self.screen = Screen::Terms;
                self.initial_request()
            }
            KeyCode::Char('4') if self.commits.is_some() => {
                self.screen = Screen::Commits;
                self.initial_request()
            }
            KeyCode::Char('5') if self.has_index() => {
                self.screen = Screen::Search;
                None
            }
            KeyCode::Char('6') => {
                self.screen = Screen::Analysis;
                self.initial_request()
            }
            KeyCode::Char('7') if self.check.is_some() => {
                self.screen = Screen::Check;
                None
            }
            KeyCode::Char('o') => {
                // Starts from the path that failed to open, then from the open index, so a sibling
                // is a Ctrl+W away.
                let failed = self.failed_path.take();
                let path = failed.as_deref().unwrap_or_else(|| {
                    self.opened
                        .as_ref()
                        .map_or("", |opened| opened.path.as_str())
                });
                self.prompt = Some(self.path_prompt(path));
                None
            }
            KeyCode::Char('X') => {
                self.jars = Some(JarsPopup::new(self.default_jars.clone(), self.home.clone()));
                Some(Action::LoadJars)
            }
            KeyCode::Char('c') if self.has_index() => Some(Action::Close),
            KeyCode::Char('R') if self.overview.is_some() => {
                self.notice = Some(Notice::Reopening);
                Some(Action::Reopen)
            }
            KeyCode::Char('R') if self.has_index() => {
                self.notice = Some(Notice::NoReader);
                None
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
            code if self.screen == Screen::Check => match self.check.as_mut()?.handle_key(code)? {
                check::Request::Start(level) => Some(Action::StartCheck(level)),
            },
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
        let tabs_shown = self.has_index() || self.screen == Screen::Analysis;
        let [tabs, body, footer] = Layout::vertical([
            Constraint::Length(u16::from(tabs_shown)),
            Constraint::Fill(1),
            Constraint::Length(1),
        ])
        .areas(frame.area());
        let cancel = if self.scan.is_some() {
            "Esc cancel scan   "
        } else if self.export.is_some() {
            "Esc cancel export   "
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
            (overview, _, _, _, _, Screen::Check) if let Some(check) = &mut self.check => {
                check.render(frame, body);
                let reopen = if overview.is_some() {
                    "R reopen   "
                } else {
                    ""
                };
                Line::from(format!(
                    " {cancel}q quit   o open   c close   {reopen}1-7 screen   {}",
                    check.keys()
                ))
            }
            (overview, _, _, _, _, Screen::Analysis) => {
                self.analysis.render(frame, body);
                if self.analysis.prompting() {
                    Line::from(self.analysis.keys())
                } else if overview.is_some() || self.opened.is_some() {
                    Line::from(format!(
                        " {cancel}q quit   o open   c close   1-7 screen   {}",
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
                        " {cancel}q quit   o open   c close   1-7 screen   {}",
                        search.keys()
                    ))
                }
            }
            (overview, _, _, Some(commits), _, Screen::Commits) => {
                commits.render(frame, body);
                let reopen = if overview.is_some() {
                    "R reopen   "
                } else {
                    ""
                };
                Line::from(format!(
                    " {cancel}q quit   o open   c close   {reopen}1-7 screen   r read again   ⏎ segments   \
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
                let cancel = if cancel.is_empty() && documents.reconstruct_job().is_some() {
                    "Esc stop rebuild   "
                } else {
                    cancel
                };
                match documents.viewer() {
                    Some(viewer) => Line::from(viewer.keys()),
                    None if documents.seeking() => Line::from(" type to seek a field   ⏎/Esc done"),
                    None => Line::from(format!(
                        " {cancel}q quit   o open   c close   1-7 screen   ←→ doc   {steps}i go to   \
                         ⏎ term vector   v view   a analyze   x numbers   e empty   m similar   r rebuild   E export   \
                         Tab panel   ↑↓"
                    )),
                }
            }
            (Some(_), _, Some(terms), _, _, Screen::Terms) => {
                terms.render(frame, body);
                if terms.prompting() {
                    Line::from(" type to seek   ⏎/Esc done")
                } else {
                    Line::from(format!(
                        " {cancel}q quit   o open   c close   1-7 screen   / seek   \
                         ⏎ open doc   e export   ←/→ column   ↑↓"
                    ))
                }
            }
            (Some(overview), _, _, _, _, _) => {
                overview.render(frame, body);
                if self.scan.is_some() {
                    Line::from(
                        " Esc cancel scan   q quit   o open   c close   R reopen   1-7 screen   s/S sort   \
                         Tab panel   ↑↓",
                    )
                } else if overview.selected_top_term().is_some() {
                    Line::from(
                        " q quit   o open   c close   R reopen   1-7 screen   ⏎ term's docs   / search term   \
                         +/- top N   s/S sort   Tab panel   ↑↓",
                    )
                } else {
                    Line::from(
                        " q quit   o open   c close   R reopen   1-7 screen   ⏎ terms   a all terms   \
                         +/- top N   s/S sort   Tab panel   ↑↓",
                    )
                }
            }
            (None, _, _, _, _, screen) if let Some(opened) = &self.opened => {
                render_without_reader(frame, body, opened, screen);
                Line::from(" q quit   o open   c close   1-7 screen")
            }
            (None, _, _, _, _, _) => {
                let mut lines = logo::about_lines();
                lines.extend([
                    Line::from(""),
                    Line::from(server_line(&self.version)),
                    Line::from(java_line(&self.version.java)),
                    Line::from(""),
                    Line::from(
                        "No index open. Press o to open one, 6 to try an analyzer, or X to add \
                         jars.",
                    ),
                ]);
                let block = Block::bordered().title(" leia ");
                let inner = block.inner(body);
                frame.render_widget(block, body);
                // The logo only where it leaves room for the text.
                if inner.height >= logo::HEIGHT && inner.width >= logo::WIDTH + 2 + 40 {
                    logo::render_beside(frame, inner, lines);
                } else {
                    frame.render_widget(Paragraph::new(lines), inner);
                }
                Line::from(" q quit   o open   6 analysis   X jars")
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
                Screen::Check => 6,
            };
            let notice = self.notice.as_ref().filter(|notice| !notice.in_footer());
            let line = match (notice, &self.export, &self.opened) {
                (Some(notice), _, _) => Some(notice.line()),
                (None, Some(export), _) => Some(Line::from(export.line())),
                (None, None, Some(opened)) => marker(opened),
                (None, None, None) => None,
            };
            // Without padding, the tabs leave the line more room when it needs it.
            let compact = line.as_ref().is_some_and(|line| {
                usize::from(tabs.width.saturating_sub(TABS_WIDTH)) < line.width()
            });
            let tabs_width = if compact {
                COMPACT_TABS_WIDTH
            } else {
                TABS_WIDTH
            };
            let tabs = if let Some(line) = line {
                let [tabs, notice] =
                    Layout::horizontal([Constraint::Length(tabs_width), Constraint::Fill(1)])
                        .areas(tabs);
                frame.render_widget(line.right_aligned(), notice);
                tabs
            } else {
                tabs
            };
            let widget = Tabs::new(TAB_TITLES)
                .select(selected)
                .highlight_style(Style::new().add_modifier(Modifier::REVERSED));
            let widget = if compact {
                widget.padding("", "")
            } else {
                widget
            };
            frame.render_widget(widget, tabs);
        }
        let keys = match &self.notice {
            Some(notice) if notice.in_footer() => {
                let mut line = notice.line();
                line.spans.insert(0, " ".into());
                line
            }
            _ => keys,
        };
        frame.render_widget(keys, footer);
        if let Some(prompt) = &self.export_prompt {
            prompt.render(frame, body);
        }
        if let Some(prompt) = &self.prompt {
            prompt.render(frame, body);
        }
        if let Some(popup) = &mut self.jars {
            popup.render(frame, body);
        }
    }
}

const TAB_TITLES: [&str; 7] = [
    "1 Overview",
    "2 Documents",
    "3 Terms",
    "4 Commits",
    "5 Search",
    "6 Analysis",
    "7 Check",
];

/// How wide the tabs are: each title with a space on either side, and a divider between two.
/// The notice takes the rest of the line.
const TABS_WIDTH: u16 = tabs_width(2);
/// How wide the tabs are without the spaces around the titles.
const COMPACT_TABS_WIDTH: u16 = tabs_width(0);

const fn tabs_width(padding: usize) -> u16 {
    let mut width = TAB_TITLES.len() - 1;
    let mut i = 0;
    while i < TAB_TITLES.len() {
        width += TAB_TITLES[i].len() + padding;
        i += 1;
    }
    width as u16
}

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

/// The names of the index directories as shown: below the opened path.
fn index_names(path: &str, indexes: &[String]) -> Vec<String> {
    indexes.iter().map(|index| relative(path, index)).collect()
}

/// What the tab line says about how the index was opened, if not as usual: without a reader,
/// or with several index directories.
fn marker(opened: &Opened) -> Option<Line<'static>> {
    let count = opened.indexes.len();
    let text = match (opened.no_reader, count) {
        (true, 1) => "no reader".to_owned(),
        (true, _) => format!("no reader, {count} indexes"),
        (false, 1) => return None,
        (false, _) => format!("{count} indexes"),
    };
    Some(Line::styled(text, Style::new().fg(Color::Yellow)))
}

/// A screen of an index opened without a reader: the overview says how it was opened, and the
/// screens that need a reader say so.
fn render_without_reader(frame: &mut Frame, area: Rect, opened: &Opened, screen: Screen) {
    let (title, lines) = if screen == Screen::Overview {
        let mut lines = vec![
            label_line("Path", opened.path.clone()),
            label_line("Directory", directory_name(opened.directory).to_owned()),
            label_line(
                "Reader",
                "none: opened without a reader, so only the commits (4) and check (7) work"
                    .to_owned(),
            ),
        ];
        if opened.indexes.len() > 1 {
            lines.push(label_line("Indexes", opened.indexes.len().to_string()));
            lines.extend(
                index_names(&opened.path, &opened.indexes)
                    .into_iter()
                    .map(|name| Line::from(format!("  {name}"))),
            );
        }
        (" Index ", lines)
    } else {
        let lines = vec![Line::from(
            "This screen needs a reader, and the index was opened without one. The commits (4) \
             and check (7) screens work; o opens the index again, Ctrl+N switches the reader on.",
        )];
        (" No reader ", lines)
    };
    frame.render_widget(
        Paragraph::new(lines)
            .wrap(Wrap { trim: false })
            .block(Block::bordered().title(title)),
        area,
    );
}

/// How the overview names the `Directory` that was asked for.
fn directory_name(directory: DirectoryImpl) -> &'static str {
    match directory {
        DirectoryImpl::Fs => "FSDirectory.open (Lucene's choice)",
        DirectoryImpl::Mmap => "MMapDirectory",
        DirectoryImpl::Nio => "NIOFSDirectory",
    }
}

fn analysis_action(request: Option<analysis::Request>) -> Option<Action> {
    match request? {
        analysis::Request::Components => Some(Action::LoadAnalysisComponents),
        analysis::Request::Analyze(params) => Some(Action::Analyze(*params)),
    }
}

fn commits_action(request: Option<commits::Request>) -> Option<Action> {
    match request? {
        commits::Request::Commits(index) => Some(Action::LoadCommits(index)),
        commits::Request::Commit(index, generation) => Some(Action::LoadCommit(index, generation)),
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::analysis::{AnalyzeResult, Analyzer};
    use crate::protocol::commits::Commit;
    use crate::protocol::document::{
        DocumentField, ReconstructResult, ReconstructSource, ReconstructedField, StoredType,
        StoredValue,
    };
    use crate::protocol::index::{
        DocValuesType, IndexCommitStats, IndexEntry, IndexField, IndexOptions, ReaderInfo,
        TermStats,
    };
    use crate::protocol::search::{
        Explanation, Hit, MatchSpan, SearchQuery, SearchResults, Snippet, StoredScanHit, TermQuery,
        TotalHitsRelation,
    };
    use crate::protocol::terms::{
        ExportTermsResult, FieldTerms, Posting, TermDelimiter, TermEntry, TopTerm,
    };
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
            segment_count: 0,
            num_docs: 0,
            max_doc: 0,
            indexes: vec![IndexCommitStats {
                path: path.to_owned(),
                doc_base: 0,
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
            }],
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
        app.opened(
            GetIndexStatsResult {
                fields: vec![
                    field("id", 0, true),
                    field("body", 1, true),
                    field("rank", 2, false),
                ],
                ..stats("/data/idx")
            },
            Vec::new(),
        );
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
    fn x_opens_the_jars_popup_from_any_screen_and_adds_jars() {
        let mut app =
            App::new(version(), Some(PathBuf::from("/home/me"))).with_default_jars(DefaultJars {
                dir: Some(PathBuf::from("/home/me/.config/leia/jars")),
                state: crate::jars::DefaultJarsState::Loaded,
            });
        assert!(render(&mut app).contains("X jars"));
        app.handle_key(key(KeyCode::Char('6')));
        app.components_loaded(GetAnalysisComponentsResult::default());

        assert_eq!(
            app.handle_key(key(KeyCode::Char('X'))),
            Some(Action::LoadJars)
        );
        app.jars_loaded(vec!["/home/me/.config/leia/jars/mine.jar".to_owned()]);
        let screen = render(&mut app);
        assert!(screen.contains("mine.jar  default folder"), "{screen}");
        // The popup takes the keys.
        assert_eq!(app.handle_key(key(KeyCode::Char('q'))), None);
        app.handle_key(key(KeyCode::Backspace));
        for c in "~/kuromoji".chars() {
            app.handle_key(key(KeyCode::Char(c)));
        }

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::AddJars(AddJarsParams {
                paths: vec!["/home/me/kuromoji".to_owned()]
            }))
        );
        // The analysis components are fetched again only if the jars were loaded.
        assert_eq!(app.jars_added(AddJarsResult::default()), None);
        let result = AddJarsResult {
            jars: vec!["/home/me/kuromoji/lucene-analysis-kuromoji.jar".to_owned()],
            ..AddJarsResult::default()
        };
        assert_eq!(app.jars_added(result), Some(Action::LoadAnalysisComponents));
        assert!(render(&mut app).contains("Loaded: 2 jars"));
        app.add_jars_failed("Cannot add the jars: not a jar: /x".to_owned());
        assert!(render(&mut app).contains("not a jar: /x"));

        assert_eq!(app.handle_key(key(KeyCode::Esc)), None);
        assert!(!render(&mut app).contains("Default folder"));
        assert_eq!(app.handle_key(key(KeyCode::Char('q'))), Some(Action::Quit));
    }

    #[test]
    fn the_next_prompt_starts_from_a_path_that_failed_to_open() {
        let mut app = App::new(version(), None);
        app.handle_key(key(KeyCode::Char('o')));
        for c in "/data/es".chars() {
            app.handle_key(key(KeyCode::Char(c)));
        }
        app.handle_key(key(KeyCode::Enter));
        app.open_failed("Missing codec".to_owned(), false);
        app.handle_key(key(KeyCode::Esc));

        app.handle_key(key(KeyCode::Char('o')));
        assert!(render(&mut app).contains("/data/es"));
        // Only once, and not after a prompt that was cancelled without a failure.
        app.handle_key(key(KeyCode::Esc));
        app.handle_key(key(KeyCode::Char('o')));
        assert!(!render(&mut app).contains("/data/es"));
    }

    #[test]
    fn jars_added_before_the_analysis_screen_was_shown_fetch_nothing() {
        let mut app = App::new(version(), None);
        app.handle_key(key(KeyCode::Char('X')));
        let result = AddJarsResult {
            jars: vec!["/a.jar".to_owned()],
            ..AddJarsResult::default()
        };

        assert_eq!(app.jars_added(result), None);
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
            app.opened(
                GetIndexStatsResult {
                    fields: vec![field("body", 0, true)],
                    ..stats("/data/idx")
                },
                Vec::new()
            ),
            None
        );
        assert!(render(&mut app).contains("1-7 screen"));
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
            mode: OpenMode::Reader,
        })
    }

    #[test]
    fn the_prompt_offers_the_opened_paths() {
        let mut app = App::new(version(), None);
        app.opened(stats("/data/a"), Vec::new());
        app.opened(stats("/data/b"), Vec::new());

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
                mode: OpenMode::Reader,
            })
        );
        app.open_failed("No such file or directory: /data/idx".to_owned(), false);
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
        app.opened(stats("/data/idx"), Vec::new());

        let screen = render(&mut app);
        assert!(app.has_index());
        assert!(screen.contains("/data/idx"), "{screen}");
        assert!(!screen.contains("Open index"), "{screen}");
    }

    #[test]
    fn shows_the_logo_without_an_index_where_it_fits() {
        let mut app = App::new(version(), None);
        let screen = render(&mut app);
        assert!(screen.contains('▀'), "{screen}");
        assert!(screen.contains("leia-server 1 (abc, t)"), "{screen}");

        let mut terminal = Terminal::new(TestBackend::new(50, 20)).unwrap();
        terminal.draw(|frame| app.draw(frame)).unwrap();
        let narrow = terminal.backend().buffer();
        assert!(narrow.content().iter().all(|cell| cell.symbol() != "▀"));
    }

    #[test]
    fn a_failed_open_keeps_the_prompt_and_the_previous_index() {
        let mut app = App::new(version(), None);
        app.opened(stats("/data/first"), Vec::new());
        app.handle_key(key(KeyCode::Char('o')));
        typed(&mut app, "x");

        assert_eq!(app.handle_key(key(KeyCode::Enter)), open("/data/firstx"));
        app.open_failed("No such file or directory: /data/firstx".to_owned(), false);

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

        app.opening("/data/missing", OpenMode::Reader);
        app.open_failed("No such file or directory: /data/missing".to_owned(), false);

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

        app.opened(stats("/data/idx"), Vec::new());
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
        app.opened(stats("/data/idx"), Vec::new());
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
        app.opened(stats("/data/other"), Vec::new());
        app.opened(
            GetIndexStatsResult {
                fields: vec![field("id", 0, true)],
                ..stats("/data/idx")
            },
            Vec::new(),
        );
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

    /// Fetching a document, with no job to stop first.
    fn load(doc_id: i32) -> Action {
        Action::LoadDocument {
            doc_id,
            cancel: Vec::new(),
        }
    }

    fn app_with_documents() -> App {
        let mut app = App::new(version(), None);
        app.opened(
            GetIndexStatsResult {
                num_docs: 3,
                max_doc: 3,
                fields: vec![field("id", 0, true)],
                ..stats("/data/idx")
            },
            Vec::new(),
        );
        app
    }

    #[test]
    fn switches_to_the_documents_and_fetches_the_first() {
        let mut app = app_with_documents();
        assert!(render(&mut app).contains("1 Overview"));

        assert_eq!(app.handle_key(key(KeyCode::Char('2'))), Some(load(0)));
        app.document_loaded(document(0));
        let screen = render(&mut app);
        assert!(screen.contains("#0 of 0–2"), "{screen}");
        assert!(screen.contains("doc-0"), "{screen}");

        assert_eq!(app.handle_key(key(KeyCode::Right)), Some(load(1)));
        // Back and forth fetches nothing new.
        assert_eq!(app.handle_key(key(KeyCode::Char('1'))), None);
        assert!(render(&mut app).contains("Path       /data/idx"));
        assert_eq!(app.handle_key(key(KeyCode::Char('2'))), None);
    }

    #[test]
    fn rebuilds_a_field_and_stops_when_the_document_changes() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(1));

        assert_eq!(
            app.handle_key(key(KeyCode::Char('r'))),
            Some(Action::StartReconstruct {
                params: StartReconstructParams {
                    doc_id: 1,
                    fields: Some(vec!["id".to_owned()]),
                },
                cancel: Vec::new(),
            })
        );
        app.reconstruct_started(7);
        assert_eq!(app.polls(), vec![Poll::Reconstruct(7)]);
        app.reconstruct_progress(GetReconstructResult {
            scanned_terms: 1024,
            total_terms: Some(3000),
            result: None,
        });
        // The details panel at its end.
        app.handle_key(key(KeyCode::Tab));
        app.handle_key(key(KeyCode::End));
        let screen = render(&mut app);
        assert!(screen.contains("Esc stop rebuild"), "{screen}");
        assert!(
            screen.contains("rebuilding… 1024 of 3000 terms"),
            "{screen}"
        );

        // Another document stops it.
        assert_eq!(
            app.handle_key(key(KeyCode::Right)),
            Some(Action::LoadDocument {
                doc_id: 2,
                cancel: vec![7],
            })
        );
        app.document_loaded(document(2));
        assert_eq!(app.polls(), Vec::new());

        // So does Esc, before it quits.
        app.handle_key(key(KeyCode::Char('r')));
        app.reconstruct_started(8);
        assert_eq!(
            app.handle_key(key(KeyCode::Esc)),
            Some(Action::CancelReconstruct(8))
        );
        app.reconstruct_cancelled();
        assert_eq!(app.polls(), Vec::new());
        app.handle_key(key(KeyCode::End));
        let screen = render(&mut app);
        assert!(screen.contains("r rebuilds the terms"), "{screen}");
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    #[test]
    fn shows_the_rebuilt_field() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(1));
        app.handle_key(key(KeyCode::Char('r')));
        app.reconstruct_started(7);

        app.reconstruct_progress(GetReconstructResult {
            scanned_terms: 3,
            total_terms: Some(3),
            result: Some(ReconstructResult {
                fields: vec![ReconstructedField {
                    field: "id".to_owned(),
                    index_options: IndexOptions::Docs,
                    source: ReconstructSource::Postings,
                    terms: vec![TermVectorTerm {
                        term: "doc-1".to_owned(),
                        binary: false,
                        freq: 1,
                        positions: Vec::new(),
                    }],
                }],
                took_millis: 2,
            }),
        });

        assert_eq!(app.polls(), Vec::new());
        app.handle_key(key(KeyCode::Tab));
        app.handle_key(key(KeyCode::End));
        // Drawing clamps End to the last line.
        render(&mut app);
        app.handle_key(key(KeyCode::Up));
        let screen = render(&mut app);
        assert!(screen.contains("1 term, from the postings"), "{screen}");
        assert!(screen.contains("  doc-1"), "{screen}");
        assert!(!screen.contains("×1"), "{screen}");
        // Rebuilt once is enough.
        assert_eq!(app.handle_key(key(KeyCode::Char('r'))), None);
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
            app.opened(
                GetIndexStatsResult {
                    max_doc: 1,
                    ..stats("/data/other")
                },
                Vec::new()
            ),
            Some(load(0))
        );
        assert_eq!(app.opened(stats("/data/empty"), Vec::new()), None);
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
        assert_eq!(app.handle_key(key(KeyCode::Enter)), Some(load(2)));
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

        app.opened(stats("/data/other"), Vec::new());

        assert_eq!(app.polls(), Vec::new());
        assert_eq!(app.scan_progress(done("id", 1)), None);
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    fn reopen_result(changed: bool) -> ReopenIndexResult {
        ReopenIndexResult {
            changed,
            path: "/data/idx".to_owned(),
            mode: OpenMode::Reader,
            num_docs: Some(0),
            max_doc: Some(0),
            indexes: vec![IndexEntry {
                path: "/data/idx".to_owned(),
                reader: Some(ReaderInfo {
                    doc_base: 0,
                    segments_file: "segments_1".to_owned(),
                    num_docs: 0,
                    max_doc: 0,
                    created_version_major: 10,
                    min_segment_lucene_version: None,
                }),
            }],
            skipped: Vec::new(),
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
        let mut changed = stats("/data/idx");
        changed.indexes[0].segments_file = "segments_2".to_owned();
        app.reopened(changed);
        assert!(render(&mut app).contains("Reopened: segments_2"));
    }

    /// Two index directories below `/data`, `a` with 2 documents and `b` with 3.
    fn multi_stats() -> GetIndexStatsResult {
        let one = stats("/data").indexes.remove(0);
        GetIndexStatsResult {
            num_docs: 5,
            max_doc: 5,
            fields: vec![field("id", 0, true)],
            indexes: vec![
                IndexCommitStats {
                    path: "/data/a".to_owned(),
                    num_docs: 2,
                    max_doc: 2,
                    ..one.clone()
                },
                IndexCommitStats {
                    path: "/data/b".to_owned(),
                    doc_base: 2,
                    num_docs: 3,
                    max_doc: 3,
                    ..one
                },
            ],
            ..stats("/data")
        }
    }

    #[test]
    fn opens_several_indexes() {
        let mut app = App::new(version(), None);
        let skipped = vec![SkippedIndex {
            path: "/data/c".to_owned(),
            message: "Corrupt index".to_owned(),
        }];
        app.opened(multi_stats(), skipped);

        let screen = render(&mut app);
        assert!(screen.contains("2 indexes"), "{screen}");
        assert!(screen.contains("Indexes    2 (1 skipped)"), "{screen}");
        assert!(screen.contains("c  skipped: Corrupt index"), "{screen}");

        assert_eq!(
            app.handle_key(key(KeyCode::Char('4'))),
            Some(Action::LoadCommits(0))
        );
        assert!(render(&mut app).contains("Indexes (2)"));
        app.commits_loaded(0, list(vec![commit(1, true)]));
        // From the commits back to the indexes.
        app.handle_key(key(KeyCode::BackTab));
        assert_eq!(
            app.handle_key(key(KeyCode::Down)),
            Some(Action::LoadCommits(1))
        );
        assert_eq!(
            app.commits_loaded(1, list(vec![commit(3, true)])),
            Some(Action::LoadCommit(1, 3))
        );
    }

    #[test]
    fn the_documents_screen_says_which_index_a_document_is_in() {
        let mut app = App::new(version(), None);
        app.opened(multi_stats(), Vec::new());
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(0));
        assert!(render(&mut app).contains("#0 of 0–4   in a"));
    }

    #[test]
    fn reopening_several_indexes_says_how_many_changed() {
        let mut app = App::new(version(), None);
        app.opened(multi_stats(), Vec::new());
        app.handle_key(key(KeyCode::Char('R')));
        let mut changed = multi_stats();
        changed.indexes[1].segments_file = "segments_2".to_owned();
        app.reopened(changed);
        assert!(render(&mut app).contains("Reopened: 1 of 2 indexes"));

        let mut result = reopen_result(false);
        result.indexes.push(result.indexes[0].clone());
        app.reopen_unchanged(&result);
        assert!(render(&mut app).contains("Unchanged: 2 indexes"));
    }

    fn opened_without_reader(app: &mut App) -> Option<Action> {
        app.opened_without_reader(OpenIndexResult {
            path: "/data/broken".to_owned(),
            mode: OpenMode::DirectoryOnly,
            num_docs: None,
            max_doc: None,
            indexes: vec![IndexEntry {
                path: "/data/broken".to_owned(),
                reader: None,
            }],
            skipped: Vec::new(),
        })
    }

    #[test]
    fn without_a_reader_only_the_commits_screen_works() {
        let mut app = app_with_documents();
        app.handle_key(key(KeyCode::Char('2')));

        assert_eq!(
            opened_without_reader(&mut app),
            Some(Action::LoadCommits(0))
        );
        let screen = render(&mut app);
        assert!(screen.contains("no reader"), "{screen}");
        assert!(!screen.contains("R reopen"), "{screen}");

        app.handle_key(key(KeyCode::Char('2')));
        assert!(render(&mut app).contains("This screen needs a reader"));
        assert_eq!(app.handle_key(key(KeyCode::Char('R'))), None);
        assert!(render(&mut app).contains("No reader to reopen"));
        app.handle_key(key(KeyCode::Char('1')));
        let screen = render(&mut app);
        assert!(screen.contains("Reader     none"), "{screen}");

        assert_eq!(app.handle_key(key(KeyCode::Char('c'))), Some(Action::Close));
        app.closed();
        assert!(!app.has_index());
    }

    #[test]
    fn opening_without_a_reader_keeps_the_analysis_screen() {
        let mut app = App::new(version(), None);
        app.handle_key(key(KeyCode::Char('6')));
        opened_without_reader(&mut app);
        assert!(render(&mut app).contains("Analyzer"));
    }

    #[test]
    fn opens_without_a_reader_from_the_prompt() {
        let mut app = App::new(version(), None);
        app.handle_key(key(KeyCode::Char('o')));
        typed(&mut app, "/data/broken");
        app.handle_key(KeyEvent::new(KeyCode::Char('n'), KeyModifiers::CONTROL));
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::Open {
                path: "/data/broken".to_owned(),
                directory_impl: DirectoryImpl::Fs,
                mode: OpenMode::DirectoryOnly,
            })
        );
    }

    #[test]
    fn a_corrupt_index_offers_to_open_it_without_a_reader() {
        let mut app = App::new(version(), None);
        app.opening("/data/broken", OpenMode::Reader);
        app.open_failed("Corrupt index".to_owned(), true);
        let screen = render(&mut app);
        assert!(
            screen.contains("^N and Enter open it without a reader."),
            "{screen}"
        );
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

        assert_eq!(app.reopened(with_docs(5)), Some(load(2)));
        assert_eq!(app.reopened(with_docs(2)), Some(load(1)));
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

        assert_eq!(app.handle_key(key(KeyCode::Enter)), Some(load(0)));
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

        assert_eq!(app.handle_key(key(KeyCode::Char(']'))), Some(load(2)));
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
            app.opened(
                GetIndexStatsResult {
                    fields: vec![field("title", 0, true)],
                    ..stats("/data/other")
                },
                Vec::new()
            ),
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

    fn list(commits: Vec<Commit>) -> GetCommitsResult {
        GetCommitsResult {
            commits,
            unreadable: Vec::new(),
        }
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
            Some(Action::LoadCommits(0))
        );
        assert_eq!(
            app.commits_loaded(0, list(vec![commit(2, true), commit(1, false)])),
            Some(Action::LoadCommit(0, 2))
        );
        app.commit_loaded(
            0,
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
            Some(Action::LoadCommit(0, 1))
        );
        assert_eq!(
            app.handle_key(key(KeyCode::Char('r'))),
            Some(Action::LoadCommits(0))
        );
    }

    #[test]
    fn opening_another_index_on_the_commits_screen_fetches_its_commits() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('4')));
        app.commits_failed(0, "unreadable".to_owned());
        assert!(render(&mut app).contains("Cannot list the commits: unreadable"));

        assert_eq!(
            app.opened(stats("/data/other"), Vec::new()),
            Some(Action::LoadCommits(0))
        );
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
                more_like_this_terms: None,
            }),
        }
    }

    #[test]
    fn a_regex_scan_is_polled_from_its_last_hit_and_opens_a_value() {
        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('5')));
        for _ in 0..5 {
            assert_eq!(app.handle_key(key(KeyCode::Char('m'))), None);
        }
        app.handle_key(key(KeyCode::Char('/')));
        typed(&mut app, "q+");
        let Some(Action::StartStoredScan { params, cancel }) = app.handle_key(key(KeyCode::Enter))
        else {
            panic!("Enter scans");
        };
        assert_eq!(params.pattern, "q+");
        assert!(cancel.is_empty());
        app.stored_scan_started(3);
        assert_eq!(app.polls(), vec![Poll::StoredScan(3)]);
        assert_eq!(app.stored_scan_from_hit(), 0);

        let hit = StoredScanHit {
            doc_id: 2,
            deleted: false,
            field: "body".to_owned(),
            value_index: 0,
            length: 2,
            match_count: 1,
            spans: vec![MatchSpan { start: 0, end: 2 }],
            snippet: Snippet {
                start: 0,
                text: "qq".to_owned(),
            },
        };
        app.stored_scan_progress(GetStoredScanResult {
            scanned_docs: 1,
            matched_values: 1,
            hit_count: 1,
            hits: vec![hit],
            result: None,
        });
        assert_eq!(app.stored_scan_from_hit(), 1);
        assert_eq!(
            app.handle_key(key(KeyCode::Esc)),
            Some(Action::CancelSearch(vec![3]))
        );
        app.search_cancelled();
        assert_eq!(app.polls(), vec![]);

        assert_eq!(app.handle_key(key(KeyCode::Char('v'))), Some(load(2)));
        assert!(render(&mut app).contains("2 Documents"));
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

        assert_eq!(app.handle_key(key(KeyCode::Enter)), Some(load(2)));
        assert!(render(&mut app).contains("2 Documents"));
        assert!(!render(&mut app).contains("[ ] term docs"));

        // `[`/`]` step through the hits, as far as they go.
        assert!(render(&mut app).contains("[ ] hits"));
        assert_eq!(app.handle_key(key(KeyCode::Char(']'))), Some(load(0)));
        assert_eq!(app.handle_key(key(KeyCode::Char(']'))), None);
        assert_eq!(app.handle_key(key(KeyCode::Char('['))), Some(load(2)));
        assert!(render(&mut app).contains("2 Documents"));
    }

    #[test]
    fn m_on_a_document_searches_for_documents_like_it() {
        let mut app = App::new(version(), None);
        app.opened(
            GetIndexStatsResult {
                fields: vec![field("id", 0, true), field("body", 1, true)],
                max_doc: 3,
                ..stats("/data/idx")
            },
            Vec::new(),
        );
        assert_eq!(app.handle_key(key(KeyCode::Char('2'))), Some(load(0)));

        let Some(Action::StartSearch { params, cancel }) = app.handle_key(key(KeyCode::Char('m')))
        else {
            panic!("m starts a search");
        };
        assert!(cancel.is_empty());
        let like = params.query.more_like_this.expect("a More-Like-This query");
        assert_eq!(like.doc_id, Some(0));
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
    #[test]
    fn checks_the_index_on_screen_7() {
        let mut app = app_with_fields();
        assert_eq!(app.handle_key(key(KeyCode::Char('7'))), None);
        let screen = render(&mut app);
        assert!(screen.contains("Lucene's CheckIndex"), "{screen}");
        assert!(
            screen.contains("⏎ check   l level: 1 checksums"),
            "{screen}"
        );

        app.handle_key(key(KeyCode::Char('l')));
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::StartCheck(2))
        );
        app.check_started(5);
        assert_eq!(app.polls(), vec![Poll::Check(5)]);
        app.check_progress(GetCheckIndexResult {
            line_count: 2,
            lines: vec![
                "Checking /data/idx".to_owned(),
                "1 of 1: name=_0".to_owned(),
            ],
            result: None,
        });
        assert_eq!(app.check_from_line(), 2);
        assert!(render(&mut app).contains("Esc cancel check"));

        // Esc cancels the check rather than quitting.
        assert_eq!(
            app.handle_key(key(KeyCode::Esc)),
            Some(Action::CancelCheck(5))
        );
        app.check_cancelled();
        assert!(app.polls().is_empty());
        assert!(render(&mut app).contains("Cancelled."));
        assert_eq!(app.handle_key(key(KeyCode::Esc)), Some(Action::Quit));
    }

    #[test]
    fn checks_without_a_reader_and_stops_on_reopen() {
        let mut app = App::new(version(), None);
        opened_without_reader(&mut app);
        app.handle_key(key(KeyCode::Char('1')));
        assert!(render(&mut app).contains("only the commits (4) and check (7) work"));
        app.handle_key(key(KeyCode::Char('7')));
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::StartCheck(1))
        );
        app.check_started(1);

        // Opening the path again keeps the screen, with a new check.
        opened_without_reader(&mut app);
        assert!(app.polls().is_empty());
        assert!(render(&mut app).contains("Lucene's CheckIndex"));

        let mut app = app_with_fields();
        app.handle_key(key(KeyCode::Char('7')));
        app.handle_key(key(KeyCode::Enter));
        app.check_started(2);
        app.reopened(stats("/data/idx"));
        assert!(app.polls().is_empty());
        assert!(render(&mut app).contains("Stopped: the index was reopened."));
    }

    #[test]
    fn exports_the_terms_of_the_selected_field() {
        let mut app = app_on_terms().with_cwd(Some(PathBuf::from("/work")));
        assert_eq!(app.handle_key(key(KeyCode::Char('e'))), None);
        let screen = render(&mut app);
        assert!(screen.contains("Export the terms of id"), "{screen}");
        assert!(screen.contains("Tab delimiter: tab"), "{screen}");
        app.handle_key(KeyEvent::new(KeyCode::Char('u'), KeyModifiers::CONTROL));
        typed(&mut app, "ids.out");

        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::StartExport(StartExportTermsParams {
                field: "id".to_owned(),
                path: "/work/ids.out".to_owned(),
                delimiter: Some(TermDelimiter::Tab),
                overwrite: Some(false),
            }))
        );
        assert!(render(&mut app).contains("Exporting…"));
        app.export_started(9);
        assert_eq!(app.polls(), vec![Poll::Export(9)]);
        app.export_progress(GetExportTermsResult {
            terms_written: 1024,
            result: None,
        });
        let screen = render(&mut app);
        assert!(screen.contains("Exporting id: 1024 terms"), "{screen}");
        assert!(screen.contains("Esc cancel export"), "{screen}");
        // One export at a time.
        app.handle_key(key(KeyCode::Char('e')));
        assert!(render(&mut app).contains("An export runs; Esc cancels it"));

        app.export_progress(GetExportTermsResult {
            terms_written: 2,
            result: Some(ExportTermsResult {
                path: "/work/ids.out".to_owned(),
                terms: 2,
                bytes: 6,
            }),
        });
        assert!(app.polls().is_empty());
        let screen = render(&mut app);
        assert!(screen.contains(" Exported 2 terms to ids.out"), "{screen}");
        // Until the next key.
        app.handle_key(key(KeyCode::Down));
        assert!(!render(&mut app).contains("Exported"));
    }

    #[test]
    fn the_tabs_make_room_for_a_long_notice() {
        let mut app = app_with_fields();
        assert!(render(&mut app).contains("6 Analysis │ 7 Check"));
        app.reopen_failed("Corrupt index".to_owned());
        let screen = render(&mut app);
        assert!(screen.contains("6 Analysis│7 Check"), "{screen}");
        assert!(screen.contains("Reopen failed: Corrupt index"), "{screen}");
    }

    #[test]
    fn an_export_that_cant_start_says_why_in_its_prompt() {
        let mut app = app_on_terms().with_cwd(Some(PathBuf::from("/work")));
        app.handle_key(key(KeyCode::Char('e')));
        app.handle_key(key(KeyCode::Enter));
        app.export_failed("Not writing into the index directory /work".to_owned());
        assert!(render(&mut app).contains("Not writing into the index directory /work"));
        assert_eq!(app.handle_key(key(KeyCode::Esc)), None);
        assert!(!render(&mut app).contains("Export the terms"));
    }

    #[test]
    fn esc_cancels_a_running_export_and_a_failure_is_shown() {
        let mut app = app_on_terms().with_cwd(Some(PathBuf::from("/work")));
        app.handle_key(key(KeyCode::Char('e')));
        app.handle_key(key(KeyCode::Enter));
        app.export_started(4);
        assert_eq!(
            app.handle_key(key(KeyCode::Esc)),
            Some(Action::CancelExport(4))
        );
        app.export_cancelled();
        assert!(app.polls().is_empty());

        app.handle_key(key(KeyCode::Char('e')));
        app.handle_key(key(KeyCode::Enter));
        app.export_started(5);
        app.export_failed("Cannot write /work/x: disk full".to_owned());
        assert!(app.polls().is_empty());
        assert!(render(&mut app).contains("Export failed: Cannot write /work/x: disk full"));
    }

    /// The documents screen on document 1, with relative export paths against a new directory.
    fn app_exporting(name: &str) -> (App, PathBuf) {
        let dir = std::env::temp_dir().join(format!("leia-app-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let mut app = app_with_documents().with_cwd(Some(dir.clone()));
        app.handle_key(key(KeyCode::Char('2')));
        app.document_loaded(document(1));
        (app, dir)
    }

    fn data(doc_id: i32) -> DocumentData {
        DocumentData {
            document: document(doc_id),
            term_vectors: std::collections::HashMap::new(),
        }
    }

    fn rebuilt_id(doc_id: i32) -> GetReconstructResult {
        GetReconstructResult {
            scanned_terms: 3,
            total_terms: Some(3),
            result: Some(ReconstructResult {
                fields: vec![ReconstructedField {
                    field: "id".to_owned(),
                    index_options: IndexOptions::Docs,
                    source: ReconstructSource::Postings,
                    terms: vec![TermVectorTerm {
                        term: format!("doc-{doc_id}"),
                        binary: false,
                        freq: 1,
                        positions: Vec::new(),
                    }],
                }],
                took_millis: 2,
            }),
        }
    }

    #[test]
    fn exports_a_document_with_its_indexed_terms() {
        let (mut app, dir) = app_exporting("export");
        assert_eq!(app.handle_key(key(KeyCode::Char('E'))), None);
        let screen = render(&mut app);
        assert!(screen.contains("Export document 1 as JSON"), "{screen}");
        assert!(screen.contains("Tab indexed terms: on"), "{screen}");

        let path = dir.join("doc-1.json").display().to_string();
        let target = ExportTarget::File {
            path: path.clone(),
            overwrite: false,
        };
        assert_eq!(
            app.handle_key(key(KeyCode::Enter)),
            Some(Action::LoadDocumentExport {
                doc_id: 1,
                indexed_terms: true,
                target: target.clone(),
            })
        );
        assert!(render(&mut app).contains("Exporting…"));
        assert_eq!(
            app.document_export_loaded(data(1), true, target),
            Some(Action::StartDocumentExport(StartReconstructParams {
                doc_id: 1,
                fields: None,
            }))
        );
        app.export_started(7);
        assert_eq!(app.polls(), vec![Poll::DocumentExport(7)]);
        let progress = GetReconstructResult {
            scanned_terms: 1024,
            total_terms: Some(3000),
            result: None,
        };
        assert_eq!(app.document_export_progress(progress), None);
        let screen = render(&mut app);
        assert!(
            screen.contains("Exporting doc 1: 1024/3000 terms"),
            "{screen}"
        );
        assert!(screen.contains("Esc cancel export"), "{screen}");
        // One export at a time.
        app.handle_key(key(KeyCode::Char('E')));
        assert!(render(&mut app).contains("An export runs; Esc cancels it"));

        let Some(Action::WriteExport {
            doc_id,
            path: written,
            text,
            overwrite,
            index_dirs,
        }) = app.document_export_progress(rebuilt_id(1))
        else {
            panic!("no write");
        };
        assert_eq!(
            (doc_id, written.as_str(), overwrite),
            (1, path.as_str(), false)
        );
        assert_eq!(index_dirs, vec!["/data/idx".to_owned()]);
        assert!(text.contains("\"indexedTerms\": true"), "{text}");
        assert!(text.contains("\"source\": \"POSTINGS\""), "{text}");
        assert!(app.polls().is_empty());

        app.document_exported(1, &path);
        let screen = render(&mut app);
        assert!(
            screen.contains(" Exported document 1 to doc-1.json"),
            "{screen}"
        );
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn copies_a_document_without_its_indexed_terms() {
        let (mut app, dir) = app_exporting("copy");
        app.handle_key(key(KeyCode::Char('E')));
        app.handle_key(key(KeyCode::Tab));
        assert!(render(&mut app).contains("Tab indexed terms: off"));
        assert_eq!(
            app.handle_key(KeyEvent::new(KeyCode::Char('y'), KeyModifiers::CONTROL)),
            Some(Action::LoadDocumentExport {
                doc_id: 1,
                indexed_terms: false,
                target: ExportTarget::Clipboard,
            })
        );
        let Some(Action::Copy(text)) =
            app.document_export_loaded(data(1), false, ExportTarget::Clipboard)
        else {
            panic!("no copy");
        };
        assert!(text.contains("\"indexedTerms\": false"), "{text}");
        assert!(text.contains("\"value\": \"doc-1\""), "{text}");
        let screen = render(&mut app);
        assert!(!screen.contains("Export document"), "{screen}");
        assert!(screen.contains(" Copied document 1 as JSON, "), "{screen}");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_document_export_that_fails_says_why() {
        let (mut app, dir) = app_exporting("fails");
        // Without indexed terms, the prompt stays open until the file is written.
        app.handle_key(key(KeyCode::Char('E')));
        app.handle_key(key(KeyCode::Tab));
        let Some(Action::LoadDocumentExport { target, .. }) = app.handle_key(key(KeyCode::Enter))
        else {
            panic!("no export");
        };
        assert!(matches!(
            app.document_export_loaded(data(1), false, target),
            Some(Action::WriteExport { .. })
        ));
        app.export_failed("Cannot write doc-1.json: disk full".to_owned());
        assert!(render(&mut app).contains("Cannot write doc-1.json: disk full"));
        assert_eq!(app.handle_key(key(KeyCode::Esc)), None);
        assert!(!render(&mut app).contains("Export document"));

        // With them, Esc stops the rebuild.
        app.handle_key(key(KeyCode::Char('E')));
        let Some(Action::LoadDocumentExport { target, .. }) = app.handle_key(key(KeyCode::Enter))
        else {
            panic!("no export");
        };
        app.document_export_loaded(data(1), true, target.clone());
        app.export_started(4);
        assert_eq!(
            app.handle_key(key(KeyCode::Esc)),
            Some(Action::CancelExport(4))
        );
        app.export_cancelled();
        assert!(app.polls().is_empty());

        // A write that fails after the rebuild is a notice.
        app.handle_key(key(KeyCode::Char('E')));
        app.handle_key(key(KeyCode::Enter));
        app.document_export_loaded(data(1), true, target);
        app.export_started(5);
        app.document_export_progress(rebuilt_id(1));
        assert!(render(&mut app).contains("Exporting doc 1: writing…"));
        app.export_failed("Cannot write doc-1.json: disk full".to_owned());
        assert!(render(&mut app).contains("Export failed: Cannot write doc-1.json: disk full"));
        std::fs::remove_dir_all(dir).unwrap();
    }
}
