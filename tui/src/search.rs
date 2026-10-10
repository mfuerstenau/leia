//! The search screen (ADR 0010 to ADR 0014): a query line, the hits of the last query, and a
//! details panel with the parsed query or the explanation of a hit. Searches and explanations run
//! as server jobs; the next page of hits is fetched once the selection reaches the last row. Enter
//! on a hit shows its document on the documents screen, where `[`/`]` step through the hits.
//! Popups set the query parser options (point types included), the More-Like-This settings, the
//! similarity and the sort, and the stored fields per hit. The parsers and More-Like-This analyze
//! with the analysis screen's analyzer. A fifth mode scans the stored values with a regular
//! expression ([`StoredScan`], ADR 0027).

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Cell, Paragraph, Row, Table, TableState, Wrap};

use crate::analyzer::AnalyzerSettings;
use crate::form::FormEvent;
use crate::hit_fields::{FieldsForm, HitFields};
use crate::input::{Edit, LineInput, typed_char, word_start};
use crate::more_like_this::{LikeForm, LikeSettings, LikeSource};
use crate::overview::{highlight, panel};
use crate::parser_options::{OptionsForm, ParserSettings};
use crate::protocol::document::{BytesValue, StoredValue};
use crate::protocol::filter::{FilterPattern, StartFilterParams};
use crate::protocol::index::{IndexField, IndexOptions};
use crate::protocol::search::{
    ClassicQuery, Explanation, GetExplainResult, GetSearchResult, GetStoredScanResult, Hit,
    LikeTerm, MAX_WINDOW, MatchSpan, QueryOperator, SearchQuery, SearchResults, SortValue,
    StandardQuery, StartExplainParams, StartSearchParams, StartStoredScanParams, TermQuery,
    TotalHitsRelation,
};
use crate::scoring::{ScoringForm, ScoringSettings};
use crate::stored_scan::StoredScan;
use crate::terms::{moved, number_width, tail_row};
use crate::text::{bytes_text, escape_control};

/// How many hits one `startSearch` call fetches.
pub const SEARCH_PAGE: i32 = 100;
/// Rows that PageUp and PageDown scroll the details.
const PAGE: u16 = 10;
/// The widest the sort values of a hit get in the hit list.
const MAX_SORT_WIDTH: usize = 32;
/// Marks the source document among the hits of a More-Like-This query.
const SOURCE_MARK: &str = "◆";

/// What the caller has to start, stop or show for the search screen.
#[derive(Debug, PartialEq)]
pub enum Request {
    /// Cancel the jobs in `cancel`, then start this search and report back with
    /// [`Search::search_started`] or [`Search::search_failed`].
    Search {
        params: StartSearchParams,
        cancel: Vec<i64>,
    },
    /// Cancel the jobs in `cancel`, then start this explanation and report back with
    /// [`Search::explain_started`] or [`Search::explain_failed`].
    Explain {
        params: StartExplainParams,
        cancel: Vec<i64>,
    },
    /// Cancel the jobs in `cancel`, then start this scan of the stored values and report back
    /// with [`Search::stored_scan_started`] or [`Search::stored_scan_failed`].
    StoredScan {
        params: StartStoredScanParams,
        cancel: Vec<i64>,
    },
    /// Cancel these jobs, and report back with [`Search::cancelled`].
    Cancel(Vec<i64>),
    /// Show this document on the documents screen.
    Document(i32),
    /// Browse the documents screen through the documents that match this query (ADR 0031),
    /// from `start_doc` on.
    Filter {
        params: StartFilterParams,
        label: String,
        start_doc: i32,
    },
    /// Show this stored value of a document in the viewer of the documents screen, with its
    /// matches highlighted.
    View {
        doc_id: i32,
        field: String,
        index: i32,
        spans: Vec<MatchSpan>,
        match_count: i32,
    },
}

/// How the query line is read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Mode {
    /// Lucene's classic query syntax.
    Classic,
    /// Lucene's standard query syntax, which knows point fields.
    Standard,
    /// One term of the selected field, exactly as typed.
    Term,
    /// Documents like the document whose id the line holds (More-Like-This).
    Like,
    /// Documents like the text the line holds (More-Like-This, ADR 0028).
    LikeText,
    /// A regular expression over the stored values of every document (ADR 0027).
    Regex,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Hits,
    Details,
}

/// The popup that is open, if any.
#[derive(Debug)]
enum Popup {
    Parser(OptionsForm),
    Like(LikeForm),
    Scoring(ScoringForm),
    Fields(FieldsForm),
}

/// The hits of a query, as far as they are fetched.
#[derive(Debug)]
struct HitList {
    query: String,
    rewritten_query: String,
    total_hits: i64,
    relation: TotalHitsRelation,
    took_millis: i64,
    hits: Vec<Hit>,
    /// The terms a More-Like-This query chose, best first.
    more_like_this_terms: Option<Vec<LikeTerm>>,
    /// Whether more hits may follow: the last page was full.
    more: bool,
    /// Why the last page could not be fetched; no more pages are fetched then.
    error: Option<String>,
}

/// A submitted search and what came of it.
#[derive(Debug)]
struct Submitted {
    /// The first page's params; later pages differ only in their offset and limit.
    params: StartSearchParams,
    /// `None` until the first page is in.
    outcome: Option<Result<HitList, String>>,
}

/// The search job that runs in the server.
#[derive(Debug)]
struct Running {
    /// `None` until the server has started the job.
    job_id: Option<i64>,
    /// Whether the page continues the shown hits.
    more: bool,
    limit: i32,
    docs_searched: i64,
}

/// The explanation of one hit for one query and similarity.
#[derive(Debug)]
struct Explain {
    params: StartExplainParams,
    /// `None` until the server has started the job.
    job_id: Option<i64>,
    /// `None` while the job runs.
    outcome: Option<Result<Explanation, String>>,
}

pub struct Search {
    /// The fields with postings, in field number order.
    fields: Vec<String>,
    max_doc: i32,
    mode: Mode,
    /// The default field of a classic query, or the field of a term query.
    field: usize,
    operator: QueryOperator,
    parser: ParserSettings,
    like: LikeSettings,
    scoring: ScoringSettings,
    hit_fields: HitFields,
    /// Analyzes classic and standard queries and More-Like-This text.
    analyzer: AnalyzerSettings,
    /// The popup that takes the keys, while it is open.
    form: Option<Popup>,
    exact: bool,
    /// Whether the regex scan ignores case.
    case_insensitive: bool,
    /// Whether the regex scan reads deleted documents too.
    include_deleted: bool,
    /// The last regex scan, while the mode is [`Mode::Regex`].
    scan: Option<StoredScan>,
    input: LineInput,
    /// Whether the input holds a binary term in hex, as the overview's top terms bring it. An
    /// edit makes it text again.
    binary: bool,
    editing: bool,
    focus: Focus,
    submitted: Option<Submitted>,
    running: Option<Running>,
    explain: Option<Explain>,
    /// Whether the documents screen waits for the next page to step to its first hit.
    step_pending: bool,
    table: TableState,
    /// The first line of the details panel that is shown.
    details_scroll: u16,
}

impl Search {
    /// The default field is the first one with positions, which is most likely text.
    pub fn new(fields: &[IndexField], max_doc: i32) -> Self {
        let searchable: Vec<&IndexField> = fields
            .iter()
            .filter(|field| field.index_options != IndexOptions::None)
            .collect();
        let field = searchable
            .iter()
            .position(|field| {
                matches!(
                    field.index_options,
                    IndexOptions::DocsAndFreqsAndPositions
                        | IndexOptions::DocsAndFreqsAndPositionsAndOffsets
                )
            })
            .unwrap_or_default();
        Self {
            fields: searchable.iter().map(|field| field.name.clone()).collect(),
            max_doc,
            mode: Mode::Classic,
            field,
            operator: QueryOperator::Or,
            parser: ParserSettings::new(fields),
            like: LikeSettings::new(fields),
            scoring: ScoringSettings::new(fields),
            hit_fields: HitFields::new(fields),
            analyzer: AnalyzerSettings::default(),
            form: None,
            exact: false,
            case_insensitive: false,
            include_deleted: false,
            scan: None,
            input: LineInput::new(""),
            binary: false,
            editing: false,
            focus: Focus::Hits,
            submitted: None,
            running: None,
            explain: None,
            step_pending: false,
            table: TableState::new(),
            details_scroll: 0,
        }
    }

    /// The screen for the reopened index (ADR 0019): the same query line, mode, field, operator,
    /// exact count and analyzer, without hits; a search is not run again by itself. The parser,
    /// More-Like-This, scoring and hit field settings are kept if `same_fields`, otherwise they
    /// start over for the new fields.
    pub fn reopened(&self, fields: &[IndexField], max_doc: i32, same_fields: bool) -> Self {
        let mut search = Self::new(fields, max_doc);
        if let Some(field) = self.fields.get(self.field)
            && let Some(row) = search.fields.iter().position(|f| f == field)
        {
            search.field = row;
        }
        search.mode = self.mode;
        search.operator = self.operator;
        search.exact = self.exact;
        search.case_insensitive = self.case_insensitive;
        search.include_deleted = self.include_deleted;
        search.input = self.input.clone();
        search.binary = self.binary;
        search.analyzer = self.analyzer.clone();
        if same_fields {
            search.parser = self.parser.clone();
            search.like = self.like.clone();
            search.scoring = self.scoring.clone();
            search.hit_fields = self.hit_fields.clone();
        }
        search
    }

    /// From now on, queries are analyzed with `analyzer`; the shown hits stay as they are.
    pub fn set_analyzer(&mut self, analyzer: &AnalyzerSettings) {
        self.analyzer = analyzer.clone();
    }

    /// Whether the query line or a popup takes the keys, Esc included.
    pub fn prompting(&self) -> bool {
        self.editing || self.form.is_some()
    }

    /// The search job to poll, while one runs.
    pub fn search_job(&self) -> Option<i64> {
        self.running.as_ref()?.job_id
    }

    /// The explanation job to poll, while one runs.
    pub fn explain_job(&self) -> Option<i64> {
        self.explain
            .as_ref()
            .filter(|explain| explain.outcome.is_none())?
            .job_id
    }

    /// The regex scan job to poll, while one runs.
    pub fn stored_scan_job(&self) -> Option<i64> {
        self.scan.as_ref()?.job()
    }

    /// The first hit the next poll of the regex scan asks for.
    pub fn stored_scan_from_hit(&self) -> i32 {
        self.scan.as_ref().map_or(0, StoredScan::from_hit)
    }

    pub fn stored_scan_started(&mut self, job_id: i64) {
        if let Some(scan) = &mut self.scan {
            scan.started(job_id);
        }
    }

    pub fn stored_scan_progress(&mut self, result: GetStoredScanResult) {
        if let Some(scan) = &mut self.scan {
            scan.progress(result);
        }
    }

    /// Shows why the regex scan could not start or failed.
    pub fn stored_scan_failed(&mut self, error: String) {
        if let Some(scan) = &mut self.scan {
            scan.failed(error);
        }
    }

    /// The jobs that Esc stops.
    pub fn jobs(&self) -> Vec<i64> {
        self.search_job()
            .into_iter()
            .chain(self.explain_job())
            .chain(self.stored_scan_job())
            .collect()
    }

    /// Searches for exactly `term` in `field`, as the overview's top terms ask for.
    pub fn term_query(&mut self, field: &str, term: BytesValue) -> Option<Request> {
        self.field = self.fields.iter().position(|f| f == field)?;
        self.mode = Mode::Term;
        self.input.set(term.value);
        self.binary = term.binary;
        self.editing = false;
        self.focus = Focus::Hits;
        self.submit()
    }

    /// Searches for documents like `doc_id`, as the documents screen asks for.
    pub fn more_like_this(&mut self, doc_id: i32) -> Option<Request> {
        self.mode = Mode::Like;
        self.input.set(doc_id.to_string());
        self.binary = false;
        self.editing = false;
        self.focus = Focus::Hits;
        self.submit()
    }

    /// The query that the query line and the settings describe; `None` while the line is blank,
    /// or in More-Like-This mode while it is no document id.
    fn query(&self) -> Option<SearchQuery> {
        let field = self.fields.get(self.field)?.clone();
        let text = self.input.text();
        if text.trim().is_empty() {
            return None;
        }
        let parser = &self.parser;
        Some(match self.mode {
            Mode::Classic => SearchQuery {
                classic: Some(ClassicQuery {
                    expression: text.to_owned(),
                    default_field: field,
                    default_operator: self.operator,
                    options: parser.options.clone(),
                    fuzzy_max_edits: parser.fuzzy_max_edits,
                    split_on_whitespace: parser.split_on_whitespace,
                    auto_generate_phrase_queries: parser.auto_generate_phrase_queries,
                    analyzer: self.analyzer.analyzer(),
                }),
                ..SearchQuery::default()
            },
            Mode::Standard => SearchQuery {
                standard: Some(StandardQuery {
                    expression: text.to_owned(),
                    default_field: field,
                    default_operator: self.operator,
                    options: parser.options.clone(),
                    points: parser.point_fields(),
                    analyzer: self.analyzer.analyzer(),
                }),
                ..SearchQuery::default()
            },
            Mode::Term => SearchQuery {
                term: Some(TermQuery {
                    field,
                    term: BytesValue {
                        value: text.to_owned(),
                        binary: self.binary,
                    },
                }),
                ..SearchQuery::default()
            },
            Mode::Like => SearchQuery {
                more_like_this: Some(self.like.query(
                    LikeSource::Doc(text.trim().parse().ok()?),
                    self.analyzer.analyzer(),
                )?),
                ..SearchQuery::default()
            },
            Mode::LikeText => SearchQuery {
                more_like_this: Some(
                    self.like
                        .query(LikeSource::Text(text.to_owned()), self.analyzer.analyzer())?,
                ),
                ..SearchQuery::default()
            },
            Mode::Regex => return None,
        })
    }

    /// Narrows the documents screen to the documents that match the query (ADR 0031), or have a
    /// stored value in which the pattern is found (ADR 0032), starting on the selected hit if
    /// there is one.
    fn filter(&self, include_deleted: bool) -> Option<Request> {
        if self.mode == Mode::Regex {
            return self.pattern_filter(include_deleted);
        }
        let query = self.query()?;
        let text = self.input.text().trim();
        let label = match self.mode {
            Mode::Term => format!("{}:{text}", self.fields.get(self.field)?),
            Mode::Like => format!("like #{text}"),
            Mode::LikeText => format!("like \"{text}\""),
            _ => text.to_owned(),
        };
        Some(Request::Filter {
            params: StartFilterParams {
                query: Some(query),
                pattern: None,
                include_deleted,
            },
            label,
            start_doc: self.selected_hit().map_or(0, |hit| hit.doc_id),
        })
    }

    /// The regex over stored values as a filter, with its fields and case setting; `None` while
    /// the line is empty. The scan's own setting for deleted documents doesn't carry over.
    fn pattern_filter(&self, include_deleted: bool) -> Option<Request> {
        let pattern = self.input.text();
        if pattern.is_empty() {
            return None;
        }
        let flags = if self.case_insensitive { "i" } else { "" };
        Some(Request::Filter {
            params: StartFilterParams {
                query: None,
                pattern: Some(FilterPattern {
                    pattern: pattern.to_owned(),
                    case_insensitive: self.case_insensitive,
                    fields: self.hit_fields.param(),
                }),
                include_deleted,
            },
            label: format!("/{pattern}/{flags}"),
            start_doc: self
                .scan
                .as_ref()
                .and_then(StoredScan::selected)
                .map_or(0, |hit| hit.doc_id),
        })
    }

    /// Starts a search for the query line, stopping the jobs of the previous one.
    fn submit(&mut self) -> Option<Request> {
        if self.mode == Mode::Regex {
            return self.submit_scan();
        }
        let params = StartSearchParams {
            query: self.query()?,
            similarity: self.scoring.similarity(),
            sort: self.scoring.sort_keys(),
            offset: 0,
            limit: SEARCH_PAGE,
            exact_total_hits: self.exact,
            fields: self.hit_fields.param(),
        };
        let cancel = self.jobs();
        self.explain = None;
        self.step_pending = false;
        self.submitted = Some(Submitted {
            params: params.clone(),
            outcome: None,
        });
        self.running = Some(Running {
            job_id: None,
            more: false,
            limit: SEARCH_PAGE,
            docs_searched: 0,
        });
        self.table = TableState::new();
        self.details_scroll = 0;
        Some(Request::Search { params, cancel })
    }

    /// Starts a regex scan for the query line, stopping the jobs of the previous one; `None`
    /// while the line is empty.
    fn submit_scan(&mut self) -> Option<Request> {
        let pattern = self.input.text();
        if pattern.is_empty() {
            return None;
        }
        let params = StartStoredScanParams {
            pattern: pattern.to_owned(),
            case_insensitive: self.case_insensitive,
            fields: self.hit_fields.param(),
            include_deleted: self.include_deleted,
        };
        let cancel = self.jobs();
        self.scan = Some(StoredScan::new(params.clone()));
        self.details_scroll = 0;
        Some(Request::StoredScan { params, cancel })
    }

    /// Searches or scans again once a setting changed, if a query was submitted.
    fn resubmit(&mut self) -> Option<Request> {
        if self.mode == Mode::Regex {
            self.scan.as_ref()?;
        } else {
            self.submitted.as_ref()?;
        }
        self.submit()
    }

    /// Searches again in another mode; forgets the hits, and stops their jobs, if the line is no
    /// query in that mode, so that they don't pass for its hits. Into or out of the regex mode,
    /// nothing runs by itself: a scan reads every document.
    fn switch_mode(&mut self, mode: Mode) -> Option<Request> {
        let regex = self.mode == Mode::Regex || mode == Mode::Regex;
        self.mode = mode;
        if regex {
            let cancel = self.jobs();
            self.scan = None;
            self.forget_hits();
            return (!cancel.is_empty()).then_some(Request::Cancel(cancel));
        }
        self.submitted.as_ref()?;
        if self.query().is_some() {
            return self.submit();
        }
        let cancel = self.jobs();
        self.forget_hits();
        (!cancel.is_empty()).then_some(Request::Cancel(cancel))
    }

    /// Forgets the submitted search, its hits and its explanation.
    fn forget_hits(&mut self) {
        self.submitted = None;
        self.running = None;
        self.explain = None;
        self.step_pending = false;
        self.table = TableState::new();
        self.details_scroll = 0;
    }

    fn hit_list(&self) -> Option<&HitList> {
        self.submitted.as_ref()?.outcome.as_ref()?.as_ref().ok()
    }

    fn selected_hit(&self) -> Option<&Hit> {
        let hits = &self.hit_list()?.hits;
        hits.get(self.table.selected()?.min(hits.len().checked_sub(1)?))
    }

    /// The next page of hits, once the selection is on the last row and more may follow.
    fn next_page(&mut self) -> Option<Request> {
        if self.running.is_some() {
            return None;
        }
        let submitted = self.submitted.as_ref()?;
        let list = submitted.outcome.as_ref()?.as_ref().ok()?;
        let selected = self.table.selected().unwrap_or_default();
        if list.error.is_some() || !list.more || selected + 1 < list.hits.len() {
            return None;
        }
        let offset = i32::try_from(list.hits.len()).ok()?;
        let limit = SEARCH_PAGE.min(MAX_WINDOW - offset);
        if limit < 1 {
            return None;
        }
        let params = StartSearchParams {
            offset,
            limit,
            ..submitted.params.clone()
        };
        self.running = Some(Running {
            job_id: None,
            more: true,
            limit,
            docs_searched: 0,
        });
        Some(Request::Search {
            params,
            cancel: Vec::new(),
        })
    }

    pub fn search_started(&mut self, job_id: i64) {
        if let Some(running) = &mut self.running {
            running.job_id = Some(job_id);
        }
    }

    /// Shows the progress of the running search, or its page of hits. Returns the next page if
    /// the selection already waits for it.
    pub fn search_progress(&mut self, result: GetSearchResult) -> Option<Request> {
        let Some(results) = result.results else {
            if let Some(running) = &mut self.running {
                running.docs_searched = result.docs_searched;
            }
            return None;
        };
        let running = self.running.take()?;
        let submitted = self.submitted.as_mut()?;
        let full = results.hits.len() == usize::try_from(running.limit).unwrap_or_default();
        match &mut submitted.outcome {
            Some(Ok(list)) if running.more => {
                list.hits.extend(results.hits);
                list.total_hits = results.total_hits;
                list.relation = results.total_hits_relation;
                list.more = more_hits(full, list.hits.len(), list.total_hits, list.relation);
                if std::mem::take(&mut self.step_pending) {
                    return self.step(true);
                }
            }
            _ if running.more => return None,
            outcome => {
                let more = more_hits(
                    full,
                    results.hits.len(),
                    results.total_hits,
                    results.total_hits_relation,
                );
                *outcome = Some(Ok(HitList::new(results, more)));
                self.table.select(Some(0));
            }
        }
        self.next_page()
    }

    /// Shows why the search could not start or failed.
    pub fn search_failed(&mut self, error: String) {
        self.step_pending = false;
        let Some(running) = self.running.take() else {
            return;
        };
        let Some(submitted) = &mut self.submitted else {
            return;
        };
        match &mut submitted.outcome {
            Some(Ok(list)) if running.more => list.error = Some(error),
            _ if running.more => {}
            outcome => *outcome = Some(Err(error)),
        }
    }

    /// Forgets the stopped jobs: a first page that was not in yet becomes an error, a regex scan
    /// keeps the hits it has.
    pub fn cancelled(&mut self) {
        if let Some(scan) = &mut self.scan {
            scan.cancelled();
        }
        if self.running.is_some() {
            self.search_failed("Cancelled.".to_owned());
        }
        if let Some(explain) = &mut self.explain
            && explain.outcome.is_none()
        {
            explain.outcome = Some(Err("Cancelled.".to_owned()));
        }
    }

    /// Explains the selected hit for the shown query, unless it is explained already.
    fn explain_selected(&mut self) -> Option<Request> {
        let submitted = &self.submitted.as_ref()?.params;
        let params = StartExplainParams {
            query: submitted.query.clone(),
            similarity: submitted.similarity,
            doc_id: self.selected_hit()?.doc_id,
        };
        if self.explain.as_ref().is_some_and(|explain| {
            explain.params == params && !matches!(explain.outcome, Some(Err(_)))
        }) {
            return None;
        }
        let cancel = self.explain_job().into_iter().collect();
        self.explain = Some(Explain {
            params: params.clone(),
            job_id: None,
            outcome: None,
        });
        self.details_scroll = 0;
        Some(Request::Explain { params, cancel })
    }

    pub fn explain_started(&mut self, job_id: i64) {
        if let Some(explain) = &mut self.explain {
            explain.job_id = Some(job_id);
        }
    }

    pub fn explain_progress(&mut self, result: GetExplainResult) {
        if let (Some(explain), Some(explanation)) = (&mut self.explain, result.explanation) {
            explain.outcome = Some(Ok(explanation));
        }
    }

    pub fn explain_failed(&mut self, error: String) {
        if let Some(explain) = &mut self.explain {
            explain.outcome = Some(Err(error));
        }
    }

    /// The explanation that belongs to the selected hit and the shown search, if any.
    fn shown_explanation(&self) -> Option<&Explain> {
        let explain = self.explain.as_ref()?;
        let submitted = &self.submitted.as_ref()?.params;
        let shows = Some(explain.params.doc_id) == self.selected_hit().map(|hit| hit.doc_id)
            && explain.params.query == submitted.query
            && explain.params.similarity == submitted.similarity;
        shows.then_some(explain)
    }

    /// Selects the previous or next hit and shows its document, for `[`/`]` on the documents
    /// screen. Past the last hit, the next page is fetched first, if there is one.
    pub fn step(&mut self, forward: bool) -> Option<Request> {
        if self.mode == Mode::Regex {
            return self.scan.as_mut()?.step(forward).map(Request::Document);
        }
        let len = self.hit_list()?.hits.len();
        let selected = self.table.selected().unwrap_or_default();
        let row = if forward {
            selected + 1
        } else {
            selected.checked_sub(1)?
        };
        if row < len {
            self.table.select(Some(row));
            self.details_scroll = 0;
            return self.selected_hit().map(|hit| Request::Document(hit.doc_id));
        }
        let request = self.next_page()?;
        self.step_pending = true;
        Some(request)
    }

    /// Edits the query line while it is open (Enter searches, Esc closes it), or a popup (Enter
    /// applies and searches again, Esc cancels). Otherwise `/` opens the line, `m` cycles classic
    /// syntax, standard syntax, a term, More-Like-This for a document or a text, and a regex over
    /// stored values, `p` opens the parser options or the
    /// More-Like-This settings, `s` the similarity and the sort, `l` the stored fields per hit,
    /// `f`/`F` step the field, `a` switches between OR and AND, `t` between an exact and an
    /// approximate count, `x` explains the selected hit, Enter shows its document, `b`/`B`
    /// browse the documents that match without or with the deleted ones (ADR 0031, ADR 0032 for
    /// the regex), and Tab
    /// switches between the hits and the details.
    pub fn handle_key(&mut self, key: KeyEvent) -> Option<Request> {
        if let Some(form) = &mut self.form {
            let changed = match form {
                Popup::Parser(form) => applied(form.handle_key(key), &mut self.parser),
                Popup::Like(form) => applied(form.handle_key(key), &mut self.like),
                Popup::Scoring(form) => applied(form.handle_key(key.code), &mut self.scoring),
                Popup::Fields(form) => applied(form.handle_key(key.code), &mut self.hit_fields),
            };
            return match changed {
                Applied::Open => None,
                Applied::Closed => {
                    self.form = None;
                    None
                }
                Applied::Changed => {
                    self.form = None;
                    self.resubmit()
                }
            };
        }
        if self.editing {
            match key.code {
                KeyCode::Enter => {
                    self.editing = false;
                    return self.submit();
                }
                KeyCode::Esc => self.editing = false,
                // A document id has digits only.
                _ if self.mode == Mode::Like
                    && typed_char(key).is_some_and(|c| !c.is_ascii_digit()) => {}
                _ => {
                    if self.input.handle_key(key, word_start) == Edit::Changed {
                        self.binary = false;
                    }
                }
            }
            return None;
        }
        if self.mode == Mode::Regex
            && let Some(request) = self.regex_key(key.code)
        {
            return request;
        }
        match key.code {
            KeyCode::Char('/') if !self.fields.is_empty() => self.editing = true,
            KeyCode::Char('m') => {
                return self.switch_mode(match self.mode {
                    Mode::Classic => Mode::Standard,
                    Mode::Standard => Mode::Term,
                    Mode::Term => Mode::Like,
                    Mode::Like => Mode::LikeText,
                    Mode::LikeText => Mode::Regex,
                    Mode::Regex => Mode::Classic,
                });
            }
            KeyCode::Char('p') if matches!(self.mode, Mode::Like | Mode::LikeText) => {
                self.form = Some(Popup::Like(LikeForm::new(&self.like)));
            }
            KeyCode::Char('p') if self.mode != Mode::Term => {
                let form = OptionsForm::new(&self.parser, self.mode == Mode::Classic);
                self.form = Some(Popup::Parser(form));
            }
            KeyCode::Char('s') => {
                self.form = Some(Popup::Scoring(ScoringForm::new(&self.scoring)));
            }
            KeyCode::Char('l') => {
                self.form = Some(Popup::Fields(FieldsForm::new(&self.hit_fields)));
            }
            KeyCode::Char('f') | KeyCode::Char('F')
                if !self.fields.is_empty() && !matches!(self.mode, Mode::Like | Mode::LikeText) =>
            {
                let len = self.fields.len();
                self.field = if key.code == KeyCode::Char('f') {
                    (self.field + 1) % len
                } else {
                    (self.field + len - 1) % len
                };
                return self.resubmit();
            }
            KeyCode::Char('a') if matches!(self.mode, Mode::Classic | Mode::Standard) => {
                self.operator = match self.operator {
                    QueryOperator::Or => QueryOperator::And,
                    QueryOperator::And => QueryOperator::Or,
                };
                return self.resubmit();
            }
            KeyCode::Char('t') => {
                self.exact = !self.exact;
                return self.resubmit();
            }
            KeyCode::Char('x') => return self.explain_selected(),
            KeyCode::Char('b') | KeyCode::Char('B') => {
                return self.filter(key.code == KeyCode::Char('B'));
            }
            KeyCode::Enter => return self.selected_hit().map(|hit| Request::Document(hit.doc_id)),
            KeyCode::Tab | KeyCode::BackTab => {
                self.focus = match self.focus {
                    Focus::Hits => Focus::Details,
                    Focus::Details => Focus::Hits,
                };
            }
            code if self.focus == Focus::Details => self.scroll_details(code),
            code => {
                let len = self.hit_list()?.hits.len();
                let row = moved(self.table.selected().unwrap_or_default(), len, code)?;
                if Some(row) != self.table.selected() {
                    self.table.select(Some(row));
                    self.details_scroll = 0;
                }
                return self.next_page();
            }
        }
        None
    }

    /// The keys of the regex mode: `/` edits the pattern, `i` switches case-insensitivity, `d`
    /// whether deleted documents are scanned, Enter shows the selected hit's document, `v` its
    /// value with the matches, and the navigation keys move through the hits. The parser,
    /// scoring, field, operator, count and explain keys do nothing. `None` for the keys the
    /// other modes share: `m`, `l` and Tab.
    fn regex_key(&mut self, key: KeyCode) -> Option<Option<Request>> {
        Some(match key {
            KeyCode::Char('/') => {
                self.editing = true;
                None
            }
            KeyCode::Char('i') => {
                self.case_insensitive = !self.case_insensitive;
                self.resubmit()
            }
            KeyCode::Char('d') => {
                self.include_deleted = !self.include_deleted;
                self.resubmit()
            }
            KeyCode::Char('p' | 's' | 'f' | 'F' | 'a' | 't' | 'x') => None,
            KeyCode::Enter => self
                .scan
                .as_ref()?
                .selected()
                .map(|hit| Request::Document(hit.doc_id)),
            KeyCode::Char('v') => {
                let hit = self.scan.as_ref()?.selected()?;
                Some(Request::View {
                    doc_id: hit.doc_id,
                    field: hit.field.clone(),
                    index: hit.value_index,
                    spans: hit.spans.clone(),
                    match_count: hit.match_count,
                })
            }
            code if self.focus == Focus::Hits => {
                if !self.scan.as_mut()?.move_selection(code) {
                    return None;
                }
                None
            }
            _ => return None,
        })
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

    /// The keys for the footer.
    pub fn keys(&self) -> &'static str {
        match &self.form {
            Some(Popup::Parser(form)) => form.keys(),
            Some(Popup::Like(form)) => form.keys(),
            Some(Popup::Scoring(_)) => ScoringForm::keys(),
            Some(Popup::Fields(_)) => FieldsForm::keys(),
            None if self.editing => " ⏎ search   Esc done   ←→ Ctrl+A/E/U/K/W edit",
            None if self.mode == Mode::Regex => {
                "/ pattern   m mode   l fields   i case   d deleted   ⏎ open doc   v view value   \
                 Tab panel   ↑↓"
            }
            None if self.mode == Mode::Like => {
                "/ doc id   m mode   p/s/l settings   t count   x explain   ⏎ open doc   b/B browse   \
                 Tab panel   ↑↓"
            }
            None if self.mode == Mode::LikeText => {
                "/ text   m mode   p/s/l settings   t count   x explain   ⏎ open doc   b/B browse   \
                 Tab panel   ↑↓"
            }
            None if self.mode != Mode::Term => {
                "/ query   m mode   p/s/l settings   f/F field   a AND/OR   t count   x explain   \
                 ⏎ open doc   b/B browse   Tab panel   ↑↓"
            }
            None => {
                "/ term   m mode   s/l settings   f/F field   t count   x explain   ⏎ open doc   \
                 b/B browse   Tab panel   ↑↓"
            }
        }
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        if self.fields.is_empty() && self.mode != Mode::Regex {
            frame.render_widget(
                Paragraph::new("No field has postings, so there is nothing to search.")
                    .wrap(Wrap { trim: false })
                    .block(panel(" Query ".to_owned(), true)),
                area,
            );
            return;
        }
        let [query_area, hits_area, details_area] = Layout::vertical([
            Constraint::Length(3),
            Constraint::Fill(3),
            Constraint::Fill(2),
        ])
        .areas(area);
        self.render_query(frame, query_area);
        self.render_hits(frame, hits_area);
        self.render_details(frame, details_area);
        match &self.form {
            Some(Popup::Parser(form)) => form.render(frame, area),
            Some(Popup::Like(form)) => form.render(frame, area),
            Some(Popup::Scoring(form)) => form.render(frame, area),
            Some(Popup::Fields(form)) => form.render(frame, area),
            None => {}
        }
    }

    fn render_query(&self, frame: &mut Frame, area: Rect) {
        let field = self
            .fields
            .get(self.field)
            .map(|field| escape_control(field))
            .unwrap_or_default();
        let count = if self.exact {
            "exact count"
        } else {
            "count to 1000"
        };
        let operator = match self.operator {
            QueryOperator::Or => "OR",
            QueryOperator::And => "AND",
        };
        let title = match self.mode {
            Mode::Classic => {
                format!(" Classic query · default field {field} · {operator} · {count} ")
            }
            Mode::Standard => {
                format!(" Standard query · default field {field} · {operator} · {count} ")
            }
            Mode::Term => {
                let hex = if self.binary { " · hex" } else { "" };
                format!(" Term · field {field}{hex} · {count} ")
            }
            Mode::Like => format!(
                " More like this document · {} · {count} ",
                self.like.fields_text()
            ),
            Mode::LikeText => format!(
                " More like this text · {} · {count} ",
                self.like.fields_text()
            ),
            Mode::Regex => {
                let fields = self.hit_fields.text().unwrap_or_else(|| "all".to_owned());
                let case = if self.case_insensitive {
                    "ignore case"
                } else {
                    "match case"
                };
                let deleted = if self.include_deleted {
                    " · deleted docs too"
                } else {
                    ""
                };
                format!(" Regex over stored values · fields {fields} · {case}{deleted} ")
            }
        };
        let block = panel(title, self.editing);
        let inner = block.inner(area);
        frame.render_widget(block, area);
        if self.editing {
            let (shown, cursor_x) = self.input.visible(inner.width);
            frame.render_widget(Line::from(shown.to_owned()), inner);
            frame.set_cursor_position((inner.x + cursor_x, inner.y));
        } else if self.input.text().is_empty() {
            let hint = match self.mode {
                Mode::Like => "Press / to type a document id, or m on the documents screen.",
                Mode::LikeText => "Press / to type a text to find documents like it.",
                Mode::Regex => "Press / to type a regular expression.",
                _ => "Press / to type a query.",
            };
            frame.render_widget(Line::from(hint).style(dim()), inner);
        } else {
            frame.render_widget(Line::from(escape_control(self.input.text())), inner);
        }
    }

    fn render_hits(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Hits && !self.editing;
        if self.mode == Mode::Regex {
            match &mut self.scan {
                Some(scan) => scan.render(frame, area, focused, self.max_doc),
                None => frame.render_widget(panel(" Hits ".to_owned(), focused), area),
            }
            return;
        }
        let searching = self.running.as_ref().map(|running| {
            let percent = if self.max_doc > 0 {
                running.docs_searched * 100 / i64::from(self.max_doc)
            } else {
                0
            };
            format!("searching… {percent}%")
        });
        let Some(submitted) = &self.submitted else {
            frame.render_widget(panel(" Hits ".to_owned(), focused), area);
            return;
        };
        let list = match &submitted.outcome {
            None => {
                let title = format!(" {} ", searching.unwrap_or_default());
                frame.render_widget(panel(title, focused), area);
                return;
            }
            Some(Err(error)) => {
                frame.render_widget(
                    Paragraph::new(error.clone())
                        .style(Style::new().fg(Color::Red))
                        .wrap(Wrap { trim: false })
                        .block(panel(" Search failed ".to_owned(), focused)),
                    area,
                );
                return;
            }
            Some(Ok(list)) => list,
        };
        let mut title = format!(" {} in {} ms ", list.total(), list.took_millis);
        if let Some(sort) = self.scoring.sort_text() {
            title.push_str(&format!("· sorted by {sort} "));
        }
        if let Some(searching) = searching {
            title.push_str(&format!("· {searching} "));
        } else if list.error.is_some() {
            title.push_str("· cannot read more ");
        } else if !list.more && list.hits.len() >= MAX_WINDOW as usize {
            title.push_str(&format!("· first {MAX_WINDOW} only "));
        }
        let block = panel(title, focused);
        if list.hits.is_empty() {
            frame.render_widget(Paragraph::new("No document matches.").block(block), area);
            return;
        }
        let rank_width = list.hits.len().to_string().len().max(1) as u16;
        let doc_width = number_width(list.hits.iter().map(|hit| i64::from(hit.doc_id)));
        let sort_texts: Vec<String> = list.hits.iter().map(sort_text).collect();
        let sort_width = sort_texts
            .iter()
            .map(|text| text.chars().count())
            .max()
            .unwrap_or_default()
            .min(MAX_SORT_WIDTH);
        let sorted = sort_width > 0;
        let source = submitted
            .params
            .query
            .more_like_this
            .as_ref()
            .and_then(|like| like.doc_id);
        let mut rows: Vec<Row> = list
            .hits
            .iter()
            .zip(sort_texts)
            .enumerate()
            .map(|(i, (hit, sort))| {
                let mut cells = vec![
                    Cell::from(format!(
                        "{:>rank_width$}",
                        i + 1,
                        rank_width = rank_width.into()
                    )),
                    Cell::from(format!(
                        "{:>doc_width$}",
                        hit.doc_id,
                        doc_width = doc_width.into()
                    )),
                    Cell::from(format!("{:>9.4}", hit.score)),
                ];
                if sorted {
                    cells.push(Cell::from(sort));
                }
                if source == Some(hit.doc_id) {
                    cells.push(Cell::from(format!("{SOURCE_MARK} {}", fields_text(hit))));
                } else {
                    cells.push(Cell::from(fields_text(hit)));
                }
                Row::new(cells)
            })
            .collect();
        rows.extend(tail_row(list.more, list.error.as_deref()));
        let mut header = vec!["#", "Doc", "    Score"];
        let mut widths = vec![
            Constraint::Length(rank_width.max(1)),
            Constraint::Length(doc_width),
            Constraint::Length(9),
        ];
        if sorted {
            header.push("Sorted by");
            widths.push(Constraint::Length(sort_width.max(9) as u16));
        }
        header.push("Fields");
        widths.push(Constraint::Fill(1));
        let header = Row::new(header).style(Style::new().add_modifier(Modifier::BOLD));
        let table = Table::new(rows, widths)
            .header(header)
            .row_highlight_style(highlight(focused))
            .block(block);
        frame.render_stateful_widget(table, area, &mut self.table);
    }

    fn render_details(&self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Details && !self.editing;
        let (title, lines) = match self.shown_explanation() {
            Some(explain) => (
                format!(" Explanation of doc {} ", explain.params.doc_id),
                match &explain.outcome {
                    None => vec![Line::from("Explaining…").style(dim())],
                    Some(Err(error)) => {
                        vec![Line::from(error.clone()).style(Style::new().fg(Color::Red))]
                    }
                    Some(Ok(explanation)) => {
                        let mut lines = Vec::new();
                        explanation_lines(explanation, 0, &mut lines);
                        lines
                    }
                },
            ),
            None if self.mode == Mode::Regex => (" Scan ".to_owned(), self.scan_lines()),
            None => (" Query ".to_owned(), self.query_lines()),
        };
        let height = area.height.saturating_sub(2);
        let total = Paragraph::new(lines.clone())
            .wrap(Wrap { trim: false })
            .line_count(area.width.saturating_sub(2));
        let max_scroll = u16::try_from(total)
            .unwrap_or(u16::MAX)
            .saturating_sub(height);
        frame.render_widget(
            Paragraph::new(lines)
                .wrap(Wrap { trim: false })
                .scroll((self.details_scroll.min(max_scroll), 0))
                .block(panel(title, focused)),
            area,
        );
    }

    /// The regex scan, or how it works.
    fn scan_lines(&self) -> Vec<Line<'static>> {
        if let Some(scan) = &self.scan {
            return scan.detail_lines();
        }
        [
            "A Java regular expression, found anywhere in each stored text value and in each",
            "binary value that is UTF-8, for example:",
            "  sku\\d{4}  (?i)lamp  \"name\":\\s*\"[^\"]*\"  (?s)begin.*end  ^$",
            "Every document is read, which is slow on a large index: Esc stops the scan.",
            "i ignores case · d scans deleted documents too · l chooses the fields",
        ]
        .into_iter()
        .map(|line| Line::from(line).style(dim()))
        .collect()
    }

    /// The parsed and rewritten query, or how to write one.
    fn query_lines(&self) -> Vec<Line<'static>> {
        let Some(list) = self.hit_list() else {
            let mut lines: Vec<Line<'static>> = match self.mode {
                Mode::Classic => vec![
                    Line::from("Classic syntax, for example:"),
                    Line::from("  fox  title:fox  +fox -dog  fox AND (red OR blue)"),
                    Line::from("  \"quick fox\"  \"quick fox\"~2  qu*  f?x  fox~  /f[ao]x/"),
                    Line::from("  title:[a TO m]  fox^2"),
                ],
                Mode::Standard => vec![
                    Line::from("Standard syntax: the classic one, plus point fields, for example:"),
                    Line::from("  price:[10 TO 20]  quantity>=5  rating:{2.5 TO *]  quantity:7"),
                    Line::from("Point fields need the type of their values: p sets it."),
                ],
                Mode::Term => vec![Line::from(
                    "The line is one term of the field, as it is: nothing is analyzed or split.",
                )],
                Mode::Regex => Vec::new(),
                Mode::Like => vec![
                    Line::from(
                        "More like this: the line is a document id. Its terms come from each",
                    ),
                    Line::from(
                        "field's term vector, or else from its stored text, analyzed with the",
                    ),
                    Line::from(
                        "analyzer of the analysis screen (6). p chooses the fields and the limits.",
                    ),
                ],
                Mode::LikeText => vec![
                    Line::from(
                        "More like this: the line is a text. Each chosen field analyzes it with the",
                    ),
                    Line::from(
                        "analyzer of the analysis screen (6), and the best terms of all fields make",
                    ),
                    Line::from("the query. p chooses the fields and the limits."),
                ],
            }
            .into_iter()
            .map(|line| line.style(dim()))
            .collect();
            lines.extend(self.parser_lines());
            lines.push(Line::from(""));
            lines.push(self.settings_line());
            return lines;
        };
        let mut lines = vec![
            Line::from(format!("Parsed     {}", escape_control(&list.query))),
            Line::from(format!(
                "Rewritten  {}",
                escape_control(&list.rewritten_query)
            )),
        ];
        lines.extend(self.parser_lines());
        lines.push(Line::from(""));
        let source = self
            .submitted
            .as_ref()
            .and_then(|submitted| submitted.params.query.more_like_this.as_ref());
        if let Some(like) = source {
            if let Some(terms) = &list.more_like_this_terms
                && !terms.is_empty()
            {
                lines.extend(like_term_lines(terms));
                lines.push(Line::from(""));
            }
            let doc_id = like.doc_id;
            if list.rewritten_query.starts_with("MatchNoDocsQuery") {
                lines.push(
                    Line::from(if doc_id.is_some() {
                        "No term of the document passed the limits (p), or its fields have \
                         neither a term vector nor stored text."
                    } else {
                        "No term of the text passed the limits (p), or none is in the chosen \
                         fields."
                    })
                    .style(dim()),
                );
            } else if let Some(doc_id) = doc_id
                && list.hits.iter().any(|hit| hit.doc_id == doc_id)
            {
                lines.push(
                    Line::from(format!(
                        "{SOURCE_MARK} marks document {doc_id}, the source."
                    ))
                    .style(dim()),
                );
            }
        }
        if !list.hits.is_empty() {
            lines.push(Line::from("x explains the selected hit.").style(dim()));
        }
        lines.push(self.settings_line());
        lines
    }

    /// What the settings popups set, for the footer's `p/s/l settings`.
    fn settings_line(&self) -> Line<'static> {
        let popups = match self.mode {
            Mode::Term => "s similarity and sort · l stored fields per hit",
            Mode::Regex => "l fields to scan",
            Mode::Like | Mode::LikeText => {
                "p more like this · s similarity and sort · l stored fields per hit"
            }
            Mode::Classic | Mode::Standard => {
                "p parser options · s similarity and sort · l stored fields per hit"
            }
        };
        Line::from(popups).style(dim())
    }
}

impl Search {
    /// The parser options that differ from Lucene's defaults, the point types of the standard
    /// parser or the More-Like-This limits, a similarity other than Lucene's default, an analyzer
    /// other than the standard one, and the stored fields per hit unless they are all of them.
    fn parser_lines(&self) -> Vec<Line<'static>> {
        let mut lines = Vec::new();
        if let Some(similarity) = self.scoring.similarity_text() {
            lines.push(Line::from(format!("Similarity {similarity}")));
        }
        if let Some(fields) = self.hit_fields.text() {
            lines.push(Line::from(format!("Fields     {fields}")));
        }
        if self.mode != Mode::Term && !self.analyzer.is_standard() {
            lines.push(Line::from(format!("Analyzer   {}", self.analyzer.text())));
        }
        match self.mode {
            Mode::Term | Mode::Regex => return lines,
            Mode::Like | Mode::LikeText => {
                lines.push(Line::from(format!(
                    "Like       {}",
                    self.like.limits_text()
                )));
                return lines;
            }
            Mode::Classic | Mode::Standard => {}
        }
        let changed = self.parser.changed(self.mode == Mode::Classic);
        if !changed.is_empty() {
            lines.push(Line::from(format!("Options    {}", changed.join(" · "))));
        }
        if self.mode == Mode::Standard {
            lines.push(Line::from(format!(
                "Points     {}",
                self.parser.points_text()
            )));
        }
        lines
    }
}

/// The terms a More-Like-This query chose, best first, as a table: score, term frequency in the
/// source, document frequency, field and term (ADR 0028).
fn like_term_lines(terms: &[LikeTerm]) -> Vec<Line<'static>> {
    let tf_width = number_width(terms.iter().map(|term| i64::from(term.tf))) as usize;
    let df_width = number_width(terms.iter().map(|term| i64::from(term.doc_freq))) as usize;
    let fields: Vec<String> = terms
        .iter()
        .map(|term| escape_control(&term.field))
        .collect();
    let field_width = fields
        .iter()
        .map(|field| field.chars().count())
        .max()
        .unwrap_or_default()
        .max("field".len());
    let mut lines = vec![
        Line::from(format!(
            "Terms      {:>9}  {:>tf_width$}  {:>df_width$}  {:<field_width$}  term",
            "score", "tf", "df", "field"
        ))
        .style(dim()),
    ];
    lines.extend(terms.iter().zip(fields).map(|(term, field)| {
        Line::from(format!(
            "           {:>9.4}  {:>tf_width$}  {:>df_width$}  {field:<field_width$}  {}",
            term.score,
            term.tf,
            term.doc_freq,
            escape_control(&term.term)
        ))
    }));
    lines
}

impl HitList {
    fn new(results: SearchResults, more: bool) -> Self {
        Self {
            query: results.query,
            rewritten_query: results.rewritten_query,
            total_hits: results.total_hits,
            relation: results.total_hits_relation,
            took_millis: results.took_millis,
            hits: results.hits,
            more_like_this_terms: results.more_like_this_terms,
            more,
            error: None,
        }
    }

    /// The total hit count: exact, or a lower bound.
    fn total(&self) -> String {
        match (self.relation, self.total_hits) {
            (TotalHitsRelation::EqualTo, 1) => "1 hit".to_owned(),
            (TotalHitsRelation::EqualTo, n) => format!("{n} hits"),
            (TotalHitsRelation::GreaterThanOrEqualTo, n) => format!("at least {n} hits"),
        }
    }
}

/// Whether hits may follow a page: it was full, the window is not used up, and the count says so.
fn more_hits(full: bool, fetched: usize, total: i64, relation: TotalHitsRelation) -> bool {
    full && fetched < MAX_WINDOW as usize
        && (relation == TotalHitsRelation::GreaterThanOrEqualTo
            || i64::try_from(fetched).unwrap_or(i64::MAX) < total)
}

/// The values a hit was sorted by, one after the other; `–` for a missing value.
fn sort_text(hit: &Hit) -> String {
    hit.sort_values
        .iter()
        .map(|value| match value {
            SortValue {
                number: Some(number),
                ..
            } => number.clone(),
            SortValue {
                term: Some(term), ..
            } => bytes_text(&term.value, term.binary),
            SortValue { .. } => "–".to_owned(),
        })
        .collect::<Vec<_>>()
        .join(" · ")
}

/// What a key did to a popup, once its outcome is applied.
enum Applied {
    /// The popup stays open.
    Open,
    /// The popup closed without a change.
    Closed,
    /// The popup closed and changed a setting.
    Changed,
}

/// Applies a popup's outcome to `settings`.
fn applied<T: PartialEq>(event: FormEvent<T>, settings: &mut T) -> Applied {
    match event {
        FormEvent::None => Applied::Open,
        FormEvent::Cancel => Applied::Closed,
        FormEvent::Apply(new) if new == *settings => Applied::Closed,
        FormEvent::Apply(new) => {
            *settings = new;
            Applied::Changed
        }
    }
}

/// The stored fields of a hit on one line: `name=value, value  name=value`. A value that the
/// server cut ends with `…`.
fn fields_text(hit: &Hit) -> String {
    hit.fields
        .iter()
        .map(|field| {
            let values: Vec<String> = field.values.iter().map(value_text).collect();
            format!("{}={}", escape_control(&field.name), values.join(", "))
        })
        .collect::<Vec<_>>()
        .join("  ")
}

fn value_text(value: &StoredValue) -> String {
    let text = bytes_text(&value.value, value.binary);
    if value.full_length.is_some() {
        format!("{text}…")
    } else {
        text
    }
}

/// The explanation as an indented tree: each node's value, then what it is. Parts that don't
/// match are dimmed.
fn explanation_lines(explanation: &Explanation, depth: usize, lines: &mut Vec<Line<'static>>) {
    let line = Line::from(format!(
        "{}{}  {}",
        "  ".repeat(depth),
        explanation.value,
        escape_control(&explanation.description)
    ));
    lines.push(if explanation.matches {
        line
    } else {
        line.style(dim())
    });
    for detail in &explanation.details {
        explanation_lines(detail, depth + 1, lines);
    }
}

fn dim() -> Style {
    Style::new().add_modifier(Modifier::DIM)
}

#[cfg(test)]
mod tests {
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;
    use ratatui::crossterm::event::KeyModifiers;

    use super::*;
    use crate::protocol::analysis::Analyzer;
    use crate::protocol::document::StoredType;
    use crate::protocol::index::{DocValuesType, TermStats};
    use crate::protocol::search::{
        Bm25Similarity, ClassicSimilarity, FieldSort, HitField, MoreLikeThisQuery, ParserOptions,
        PointField, PointType, Similarity, Snippet, SortKey, SortMissing, SortSelector, SortType,
        StoredScanHit, StoredScanResult,
    };

    fn bm25() -> Similarity {
        Similarity {
            bm25: Some(Bm25Similarity { k1: 1.2, b: 0.75 }),
            ..Similarity::default()
        }
    }

    fn field(name: &str, index_options: IndexOptions) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number: 0,
            index_options,
            has_norms: false,
            has_payloads: false,
            has_term_vectors: false,
            doc_values_type: DocValuesType::None,
            point_dimension_count: 0,
            point_num_bytes: 0,
            vector_dimension: 0,
            terms: Some(TermStats {
                doc_count: 1,
                sum_doc_freq: 1,
                sum_total_term_freq: 1,
            }),
        }
    }

    fn point(name: &str, bytes: i32) -> IndexField {
        IndexField {
            point_dimension_count: 1,
            point_num_bytes: bytes,
            terms: None,
            ..field(name, IndexOptions::None)
        }
    }

    /// `id` (docs only), `stored` (no postings), `title` and `body` (positions), the points
    /// `price` (8 bytes, with numeric doc values) and `quantity` (4 bytes), and `category`
    /// (sorted doc values).
    fn search() -> Search {
        Search::new(
            &[
                field("id", IndexOptions::Docs),
                field("stored", IndexOptions::None),
                field("title", IndexOptions::DocsAndFreqsAndPositions),
                field("body", IndexOptions::DocsAndFreqsAndPositionsAndOffsets),
                IndexField {
                    doc_values_type: DocValuesType::Numeric,
                    ..point("price", 8)
                },
                point("quantity", 4),
                IndexField {
                    doc_values_type: DocValuesType::Sorted,
                    terms: None,
                    ..field("category", IndexOptions::None)
                },
            ],
            500,
        )
    }

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn press(search: &mut Search, code: KeyCode) -> Option<Request> {
        search.handle_key(key(code))
    }

    fn typed(search: &mut Search, text: &str) {
        for c in text.chars() {
            assert_eq!(press(search, KeyCode::Char(c)), None);
        }
    }

    fn classic(expression: &str, field: &str, operator: QueryOperator) -> SearchQuery {
        SearchQuery {
            classic: Some(ClassicQuery {
                expression: expression.to_owned(),
                default_field: field.to_owned(),
                default_operator: operator,
                options: ParserOptions::default(),
                fuzzy_max_edits: 2,
                split_on_whitespace: false,
                auto_generate_phrase_queries: false,
                analyzer: Analyzer::default(),
            }),
            ..SearchQuery::default()
        }
    }

    fn request(query: SearchQuery, offset: i32, limit: i32, cancel: Vec<i64>) -> Option<Request> {
        Some(Request::Search {
            params: StartSearchParams {
                query,
                similarity: bm25(),
                sort: vec![],
                offset,
                limit,
                exact_total_hits: false,
                fields: None,
            },
            cancel,
        })
    }

    fn hit(doc_id: i32) -> Hit {
        Hit {
            doc_id,
            score: 1.5,
            sort_values: vec![],
            fields: vec![HitField {
                name: "id".to_owned(),
                values: vec![StoredValue {
                    kind: StoredType::String,
                    value: format!("doc-{doc_id}"),
                    binary: false,
                    full_length: None,
                }],
            }],
        }
    }

    fn results(docs: std::ops::Range<i32>, total_hits: i64) -> GetSearchResult {
        GetSearchResult {
            docs_searched: 500,
            results: Some(SearchResults {
                query: "title:red".to_owned(),
                rewritten_query: "title:red".to_owned(),
                total_hits,
                total_hits_relation: TotalHitsRelation::EqualTo,
                hits: docs.map(hit).collect(),
                took_millis: 3,
                more_like_this_terms: None,
            }),
        }
    }

    fn render(search: &mut Search) -> String {
        render_sized(search, 100, 24)
    }

    fn render_sized(search: &mut Search, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| search.render(frame, frame.area()))
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

    /// A search for `red` whose first page is in: hits 0 to `count - 1` of `total_hits`.
    fn searched(count: i32, total_hits: i64) -> Search {
        let mut search = search();
        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, "red");
        press(&mut search, KeyCode::Enter);
        search.search_started(1);
        search.search_progress(results(0..count, total_hits));
        search
    }

    /// The search screen in regex mode, with `pattern` submitted.
    fn regex(pattern: &str) -> (Search, StartStoredScanParams) {
        let mut search = search();
        for _ in 0..5 {
            assert_eq!(press(&mut search, KeyCode::Char('m')), None);
        }
        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, pattern);
        let Some(Request::StoredScan { params, cancel }) = press(&mut search, KeyCode::Enter)
        else {
            panic!("Enter scans");
        };
        assert!(cancel.is_empty());
        (search, params)
    }

    fn scan_hit(doc_id: i32, field: &str) -> StoredScanHit {
        StoredScanHit {
            doc_id,
            deleted: doc_id == 37,
            field: field.to_owned(),
            value_index: 1,
            length: 12,
            match_count: 1,
            spans: vec![MatchSpan { start: 4, end: 7 }],
            snippet: Snippet {
                start: 0,
                text: "the red lamp".to_owned(),
            },
        }
    }

    fn scan_poll(hits: Vec<StoredScanHit>, done: bool) -> GetStoredScanResult {
        GetStoredScanResult {
            scanned_docs: 250,
            matched_values: 2,
            hit_count: 2,
            hits,
            result: done.then(|| StoredScanResult {
                scanned_docs: 500,
                matched_docs: 2,
                matched_values: 2,
                hits_truncated: false,
                timed_out_values: 0,
                timed_out: vec![],
                took_millis: 9,
            }),
        }
    }

    #[test]
    fn regex_mode_scans_the_stored_values() {
        let (mut search, params) = regex("re+d (x)");

        assert_eq!(
            params,
            StartStoredScanParams {
                pattern: "re+d (x)".to_owned(),
                case_insensitive: false,
                fields: None,
                include_deleted: false,
            }
        );
        search.stored_scan_started(6);
        assert_eq!(search.stored_scan_job(), Some(6));
        assert_eq!(search.jobs(), vec![6]);
        search.stored_scan_progress(scan_poll(vec![scan_hit(3, "body")], false));
        assert_eq!(search.stored_scan_from_hit(), 1);
        let screen = render(&mut search);
        assert!(screen.contains("Regex over stored values · fields all · match case"));
        assert!(
            screen.contains("2 values · scanning… 50% of 500 docs"),
            "{screen}"
        );
        assert!(screen.contains("the red lamp"));

        search.stored_scan_progress(scan_poll(vec![scan_hit(37, "title")], true));
        assert_eq!(search.stored_scan_job(), None);
        let screen = render(&mut search);
        assert!(screen.contains("2 values in 2 docs in 9 ms"), "{screen}");
        assert!(screen.contains("37✗"), "{screen}");
    }

    #[test]
    fn regex_mode_settings_scan_again_and_other_keys_do_nothing() {
        let (mut search, _) = regex("red");
        search.stored_scan_started(6);

        let Some(Request::StoredScan { params, cancel }) = press(&mut search, KeyCode::Char('i'))
        else {
            panic!("i scans again");
        };
        assert!(params.case_insensitive);
        assert_eq!(cancel, vec![6]);
        let Some(Request::StoredScan { params, .. }) = press(&mut search, KeyCode::Char('d'))
        else {
            panic!("d scans again");
        };
        assert!(params.include_deleted);
        for c in ['p', 's', 'f', 'F', 'a', 't', 'x'] {
            assert_eq!(press(&mut search, KeyCode::Char(c)), None, "{c}");
            assert!(search.form.is_none(), "{c}");
        }
        assert!(render(&mut search).contains("ignore case · deleted docs too"));
    }

    #[test]
    fn regex_hits_open_their_document_and_value() {
        let (mut search, _) = regex("red");
        search.stored_scan_started(6);
        search.stored_scan_progress(scan_poll(
            vec![
                scan_hit(3, "body"),
                scan_hit(3, "title"),
                scan_hit(8, "body"),
            ],
            true,
        ));

        assert_eq!(
            press(&mut search, KeyCode::Enter),
            Some(Request::Document(3))
        );
        press(&mut search, KeyCode::Down);
        assert_eq!(
            press(&mut search, KeyCode::Char('v')),
            Some(Request::View {
                doc_id: 3,
                field: "title".to_owned(),
                index: 1,
                spans: vec![MatchSpan { start: 4, end: 7 }],
                match_count: 1,
            })
        );
        assert_eq!(search.step(true), Some(Request::Document(8)));
        assert_eq!(search.step(true), None);
    }

    #[test]
    fn leaving_regex_mode_stops_the_scan() {
        let (mut search, _) = regex("red");
        search.stored_scan_started(6);

        assert_eq!(
            press(&mut search, KeyCode::Char('m')),
            Some(Request::Cancel(vec![6]))
        );
        assert_eq!(search.mode, Mode::Classic);
        assert!(search.scan.is_none());
        // Back in regex mode, nothing runs by itself.
        for _ in 0..5 {
            assert_eq!(press(&mut search, KeyCode::Char('m')), None);
        }
        assert!(search.scan.is_none());
    }

    #[test]
    fn a_stopped_scan_keeps_its_hits() {
        let (mut search, _) = regex("red");
        search.stored_scan_started(6);
        search.stored_scan_progress(scan_poll(vec![scan_hit(3, "body")], false));

        search.cancelled();

        assert_eq!(search.jobs(), Vec::<i64>::new());
        let screen = render(&mut search);
        assert!(screen.contains("2 values so far · stopped"), "{screen}");
        assert!(screen.contains("the red lamp"));
    }

    #[test]
    fn types_a_query_for_the_first_text_field() {
        let mut search = search();
        assert!(render(&mut search).contains("Press / to type a query."));
        assert!(render(&mut search).contains("default field title · OR · count to 1000"));

        assert_eq!(press(&mut search, KeyCode::Char('/')), None);
        assert!(search.prompting());
        typed(&mut search, "red qx");
        search.handle_key(KeyEvent::new(KeyCode::Char('w'), KeyModifiers::CONTROL));
        typed(&mut search, "fox");

        assert_eq!(
            press(&mut search, KeyCode::Enter),
            request(
                classic("red fox", "title", QueryOperator::Or),
                0,
                SEARCH_PAGE,
                vec![]
            )
        );
        assert!(!search.prompting());
        // Esc leaves the line without searching; a blank line searches nothing.
        press(&mut search, KeyCode::Char('/'));
        assert_eq!(press(&mut search, KeyCode::Esc), None);
        assert!(!search.prompting());
    }

    #[test]
    fn a_blank_query_searches_nothing() {
        let mut search = search();
        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, "  ");

        assert_eq!(press(&mut search, KeyCode::Enter), None);
        assert_eq!(press(&mut search, KeyCode::Char('t')), None);
    }

    #[test]
    fn shows_the_progress_then_the_hits() {
        let mut search = search();
        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, "red");
        press(&mut search, KeyCode::Enter);
        search.search_started(1);
        assert_eq!(search.search_job(), Some(1));
        search.search_progress(GetSearchResult {
            docs_searched: 200,
            results: None,
        });
        assert!(render(&mut search).contains("searching… 40%"));

        assert_eq!(search.search_progress(results(0..2, 2)), None);
        assert_eq!(search.search_job(), None);
        let screen = render(&mut search);
        assert!(screen.contains("2 hits in 3 ms"), "{screen}");
        assert!(screen.contains("id=doc-1"), "{screen}");
        assert!(screen.contains("Parsed     title:red"), "{screen}");
        assert_eq!(press(&mut search, KeyCode::Down), None);
        assert_eq!(
            press(&mut search, KeyCode::Enter),
            Some(Request::Document(1))
        );
    }

    #[test]
    fn fetches_the_next_page_at_the_last_row() {
        let mut search = searched(SEARCH_PAGE, 150);
        let query = classic("red", "title", QueryOperator::Or);

        assert_eq!(
            press(&mut search, KeyCode::End),
            request(query.clone(), SEARCH_PAGE, SEARCH_PAGE, vec![])
        );
        search.search_started(2);
        // No second request while the page is on its way.
        assert_eq!(press(&mut search, KeyCode::End), None);
        assert_eq!(search.search_progress(results(100..150, 150)), None);
        assert!(render(&mut search).contains("150 hits"));

        // That was the last page.
        assert_eq!(press(&mut search, KeyCode::End), None);
        assert!(!render(&mut search).contains('…'));
    }

    #[test]
    fn a_failed_page_keeps_the_hits() {
        let mut search = searched(SEARCH_PAGE, 150);
        press(&mut search, KeyCode::End);
        search.search_started(2);

        search.search_failed("Cannot search the index: gone".to_owned());

        let screen = render(&mut search);
        assert!(
            screen.contains("150 hits in 3 ms · cannot read more"),
            "{screen}"
        );
        assert_eq!(press(&mut search, KeyCode::Up), None);
        assert_eq!(press(&mut search, KeyCode::End), None);
    }

    #[test]
    fn shows_why_a_search_failed() {
        let mut search = search();
        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, "title:(red");
        press(&mut search, KeyCode::Enter);

        search.search_failed("Invalid query: Cannot parse 'title:(red'".to_owned());

        let screen = render(&mut search);
        assert!(screen.contains("Search failed"), "{screen}");
        assert!(screen.contains("Cannot parse 'title:(red'"), "{screen}");
        assert_eq!(search.search_job(), None);
    }

    #[test]
    fn a_changed_setting_searches_again_and_stops_the_running_search() {
        let mut search = searched(2, 2);
        press(&mut search, KeyCode::Char('/'));
        press(&mut search, KeyCode::Enter);
        search.search_started(7);

        assert_eq!(
            press(&mut search, KeyCode::Char('a')),
            request(
                classic("red", "title", QueryOperator::And),
                0,
                SEARCH_PAGE,
                vec![7]
            )
        );
        assert_eq!(
            press(&mut search, KeyCode::Char('f')),
            request(
                classic("red", "body", QueryOperator::And),
                0,
                SEARCH_PAGE,
                vec![]
            )
        );
        // `f` wraps around, and `F` steps back.
        press(&mut search, KeyCode::Char('f'));
        assert_eq!(
            press(&mut search, KeyCode::Char('F')),
            request(
                classic("red", "body", QueryOperator::And),
                0,
                SEARCH_PAGE,
                vec![]
            )
        );
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Char('t')) else {
            panic!("t searches again");
        };
        assert!(params.exact_total_hits);
        assert!(render(&mut search).contains("AND · exact count"));
    }

    #[test]
    fn term_mode_takes_the_line_as_one_term() {
        let mut search = searched(2, 2);

        press(&mut search, KeyCode::Char('m'));
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Char('m')) else {
            panic!("m searches again");
        };
        assert_eq!(
            params.query,
            SearchQuery {
                term: Some(TermQuery {
                    field: "title".to_owned(),
                    term: BytesValue {
                        value: "red".to_owned(),
                        binary: false,
                    },
                }),
                ..SearchQuery::default()
            }
        );
        assert!(render(&mut search).contains("Term · field title"));
        // There is no operator and there are no parser options for a term.
        assert_eq!(press(&mut search, KeyCode::Char('a')), None);
        assert_eq!(press(&mut search, KeyCode::Char('p')), None);
        assert!(!search.prompting());
    }

    fn like(doc_id: i32) -> MoreLikeThisQuery {
        MoreLikeThisQuery {
            doc_id: Some(doc_id),
            text: None,
            fields: vec!["title".to_owned(), "body".to_owned()],
            min_term_freq: 2,
            min_doc_freq: 5,
            max_doc_freq: None,
            max_query_terms: 25,
            boost: false,
            analyzer: Analyzer::default(),
            min_word_len: 0,
            max_word_len: None,
            stop_words: Vec::new(),
        }
    }

    fn like_term(field: &str, term: &str, tf: i32, doc_freq: i32, score: f64) -> LikeTerm {
        LikeTerm {
            field: field.to_owned(),
            term: term.to_owned(),
            tf,
            doc_freq,
            idf: score / f64::from(tf),
            score,
        }
    }

    #[test]
    fn like_text_mode_takes_the_line_as_a_text_and_shows_the_chosen_terms() {
        let mut search = search();
        for _ in 0..4 {
            assert_eq!(press(&mut search, KeyCode::Char('m')), None);
        }
        let screen = render(&mut search);
        assert!(screen.contains("More like this text · fields title · body · count to 1000"));
        assert!(screen.contains("Press / to type a text"));
        assert!(search.keys().starts_with("/ text"));

        press(&mut search, KeyCode::Char('/'));
        // Anything goes into the line, unlike a document id.
        typed(&mut search, "Red lamp 42");
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("Enter searches");
        };
        assert_eq!(
            params.query.more_like_this,
            Some(MoreLikeThisQuery {
                doc_id: None,
                text: Some("Red lamp 42".to_owned()),
                ..like(0)
            })
        );
        // The popup is the same as for a document; f/F don't step a field.
        assert_eq!(press(&mut search, KeyCode::Char('f')), None);
        assert_eq!(press(&mut search, KeyCode::Char('p')), None);
        assert!(search.keys().contains("a/n all/no fields"));
        press(&mut search, KeyCode::Esc);

        search.search_started(1);
        let mut page = results(2..4, 2);
        if let Some(results) = &mut page.results {
            results.query = "like(text \"Red lamp 42\", …)".to_owned();
            results.more_like_this_terms = Some(vec![
                like_term("title", "lamp", 1, 19, 4.218876),
                like_term("body", "red\u{7}", 12, 1234, 2.5),
            ]);
        }
        search.search_progress(page);
        let screen = render_sized(&mut search, 100, 40);
        assert!(
            screen.contains("Terms          score    tf    df  field  term"),
            "{screen}"
        );
        assert!(
            screen.contains("              4.2189     1    19  title  lamp"),
            "{screen}"
        );
        assert!(
            screen.contains("              2.5000    12  1234  body   red\\u{7}"),
            "{screen}"
        );
        // A text has no source among the hits.
        assert!(!screen.contains("◆"));

        // m goes on to the regex mode, which runs nothing by itself: the search is done.
        assert_eq!(press(&mut search, KeyCode::Char('m')), None);
        assert_eq!(search.mode, Mode::Regex);
    }

    #[test]
    fn more_like_this_mode_takes_the_line_as_a_document_id() {
        let mut search = search();
        for _ in 0..3 {
            assert_eq!(press(&mut search, KeyCode::Char('m')), None);
        }
        let screen = render(&mut search);
        assert!(screen.contains("More like this document · fields title · body · count to 1000"));
        assert!(screen.contains("Press / to type a document id"));

        press(&mut search, KeyCode::Char('/'));
        // Only digits go into the line.
        typed(&mut search, "4x2 ");
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("Enter searches");
        };
        assert_eq!(
            params.query,
            SearchQuery {
                more_like_this: Some(like(42)),
                ..SearchQuery::default()
            }
        );
        // The fields are the popup's, and there is no operator.
        assert_eq!(press(&mut search, KeyCode::Char('f')), None);
        assert_eq!(press(&mut search, KeyCode::Char('a')), None);

        assert_eq!(press(&mut search, KeyCode::Char('p')), None);
        assert!(search.prompting());
        assert!(search.keys().contains("a/n all/no fields"));
        for _ in 0..4 {
            press(&mut search, KeyCode::Down);
        }
        press(&mut search, KeyCode::Char(' '));
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("applying the popup searches again");
        };
        assert_eq!(
            params.query.more_like_this,
            Some(MoreLikeThisQuery {
                boost: true,
                ..like(42)
            })
        );
    }

    #[test]
    fn marks_the_source_among_the_hits() {
        let mut search = search();
        let Some(Request::Search { params, cancel }) = search.more_like_this(3) else {
            panic!("a search starts");
        };
        assert!(cancel.is_empty());
        assert_eq!(params.query.more_like_this, Some(like(3)));
        search.search_started(1);
        search.search_progress(results(2..5, 3));
        let screen = render(&mut search);
        assert!(screen.contains("◆ id=doc-3"));
        assert!(screen.contains("◆ marks document 3, the source."));

        search.more_like_this(4);
        search.search_started(2);
        search.search_progress(GetSearchResult {
            docs_searched: 500,
            results: Some(SearchResults {
                query: "like(doc 4, …)".to_owned(),
                rewritten_query: "MatchNoDocsQuery(\"empty BooleanQuery\")".to_owned(),
                total_hits: 0,
                total_hits_relation: TotalHitsRelation::EqualTo,
                hits: vec![],
                took_millis: 1,
                more_like_this_terms: Some(Vec::new()),
            }),
        });
        assert!(render(&mut search).contains("No term of the document passed the limits"));
    }

    #[test]
    fn a_line_that_is_no_document_id_forgets_the_hits() {
        let mut search = searched(2, 2);
        press(&mut search, KeyCode::Char('m'));
        let running = press(&mut search, KeyCode::Char('m'));
        assert!(matches!(running, Some(Request::Search { .. })));
        search.search_started(7);

        // "red" is no document id: the term search's hits and job go.
        assert_eq!(
            press(&mut search, KeyCode::Char('m')),
            Some(Request::Cancel(vec![7]))
        );
        assert_eq!(search.search_job(), None);
        assert!(!render(&mut search).contains("Search failed"));
        let screen = render(&mut search);
        assert!(screen.contains("More like this document"));
        assert!(!screen.contains("doc-0"));
    }

    #[test]
    fn standard_mode_sends_the_point_types() {
        let mut search = searched(2, 2);

        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Char('m')) else {
            panic!("m searches again");
        };
        assert_eq!(
            params.query,
            SearchQuery {
                standard: Some(StandardQuery {
                    expression: "red".to_owned(),
                    default_field: "title".to_owned(),
                    default_operator: QueryOperator::Or,
                    options: ParserOptions::default(),
                    points: vec![
                        PointField {
                            field: "price".to_owned(),
                            point_type: PointType::Long,
                        },
                        PointField {
                            field: "quantity".to_owned(),
                            point_type: PointType::Int,
                        },
                    ],
                    analyzer: Analyzer::default(),
                }),
                ..SearchQuery::default()
            }
        );
        search.search_started(2);
        search.search_progress(results(0..2, 2));
        let screen = render(&mut search);
        assert!(
            screen.contains("Standard query · default field title"),
            "{screen}"
        );
        assert!(
            screen.contains("Points     price LONG · quantity INT"),
            "{screen}"
        );
    }

    #[test]
    fn the_options_popup_searches_again_once_applied() {
        let mut search = searched(2, 2);

        assert_eq!(press(&mut search, KeyCode::Char('p')), None);
        assert!(search.prompting());
        assert!(render(&mut search).contains("Classic parser options"));
        // Esc cancels: nothing changes, nothing is searched.
        press(&mut search, KeyCode::Char(' '));
        assert_eq!(press(&mut search, KeyCode::Esc), None);
        assert!(!search.prompting());
        // Enter without a change searches nothing either.
        press(&mut search, KeyCode::Char('p'));
        assert_eq!(press(&mut search, KeyCode::Enter), None);

        // Leading wildcards on, phrase slop 2.
        press(&mut search, KeyCode::Char('p'));
        press(&mut search, KeyCode::Char(' '));
        press(&mut search, KeyCode::Down);
        press(&mut search, KeyCode::Char('2'));
        let Some(Request::Search { params, cancel }) = press(&mut search, KeyCode::Enter) else {
            panic!("applying searches again");
        };
        assert!(cancel.is_empty());
        let classic = params.query.classic.unwrap();
        assert!(classic.options.allow_leading_wildcard);
        assert_eq!(classic.options.phrase_slop, 2);
        assert!(!search.prompting());
        search.search_started(3);
        search.search_progress(results(0..2, 2));
        let screen = render(&mut search);
        assert!(
            screen.contains("Options    leading wildcards · phrase slop 2"),
            "{screen}"
        );
    }

    #[test]
    fn the_options_popup_lists_the_point_types_for_the_standard_parser() {
        let mut search = searched(2, 2);
        press(&mut search, KeyCode::Char('m'));
        search.search_started(2);
        search.search_progress(results(0..2, 2));

        press(&mut search, KeyCode::Char('p'));
        let screen = render(&mut search);
        assert!(screen.contains("Standard parser options"), "{screen}");
        assert!(screen.contains("price                  LONG"), "{screen}");
        assert!(!screen.contains("Fuzzy max edits"), "{screen}");
        // The last row is quantity: INT becomes FLOAT.
        press(&mut search, KeyCode::End);
        press(&mut search, KeyCode::Right);
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("applying searches again");
        };
        assert_eq!(
            params.query.standard.unwrap().points[1],
            PointField {
                field: "quantity".to_owned(),
                point_type: PointType::Float,
            }
        );
    }

    #[test]
    fn searches_a_top_term_until_it_is_edited() {
        let mut search = search();
        let binary = BytesValue {
            value: "ff00".to_owned(),
            binary: true,
        };

        let Some(Request::Search { params, .. }) = search.term_query("body", binary.clone()) else {
            panic!("a top term searches");
        };
        assert_eq!(params.query.term.unwrap().term, binary);
        assert!(render(&mut search).contains("Term · field body · hex"));

        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, "1");
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("Enter searches");
        };
        assert_eq!(
            params.query.term.unwrap().term,
            BytesValue {
                value: "ff001".to_owned(),
                binary: false,
            }
        );
        assert_eq!(search.term_query("stored", binary), None);
    }

    #[test]
    fn explains_the_selected_hit() {
        let mut search = searched(2, 2);
        let query = classic("red", "title", QueryOperator::Or);

        assert_eq!(
            press(&mut search, KeyCode::Char('x')),
            Some(Request::Explain {
                params: StartExplainParams {
                    query: query.clone(),
                    similarity: bm25(),
                    doc_id: 0,
                },
                cancel: vec![],
            })
        );
        search.explain_started(4);
        assert_eq!(search.explain_job(), Some(4));
        assert_eq!(search.jobs(), vec![4]);
        assert!(render(&mut search).contains("Explaining…"));
        // Asked for already.
        assert_eq!(press(&mut search, KeyCode::Char('x')), None);

        search.explain_progress(GetExplainResult {
            explanation: Some(Explanation {
                matches: true,
                value: "1.5".to_owned(),
                description: "sum of:".to_owned(),
                details: vec![Explanation {
                    matches: false,
                    value: "0.0".to_owned(),
                    description: "no matching term".to_owned(),
                    details: vec![],
                }],
            }),
        });
        assert_eq!(search.explain_job(), None);
        let screen = render(&mut search);
        assert!(screen.contains("Explanation of doc 0"), "{screen}");
        assert!(screen.contains("1.5  sum of:"), "{screen}");
        assert!(screen.contains("  0.0  no matching term"), "{screen}");

        // Another hit shows the query until it is explained too.
        press(&mut search, KeyCode::Down);
        assert!(render(&mut search).contains("x explains the selected hit."));
        assert!(matches!(
            press(&mut search, KeyCode::Char('x')),
            Some(Request::Explain { params, .. }) if params.doc_id == 1
        ));
    }

    #[test]
    fn the_sort_popup_searches_again_and_the_hits_show_their_sort_values() {
        let mut search = searched(2, 2);

        assert_eq!(press(&mut search, KeyCode::Char('s')), None);
        assert!(search.prompting());
        assert!(render(&mut search).contains("Similarity and sort"));
        // Similarity, k1, b, then "Sort by": past the score and the document id to price, read
        // as LONG.
        for code in [
            KeyCode::Down,
            KeyCode::Down,
            KeyCode::Down,
            KeyCode::Right,
            KeyCode::Right,
            KeyCode::Right,
        ] {
            assert_eq!(press(&mut search, code), None);
        }
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("applying searches again");
        };
        assert_eq!(
            params.sort,
            vec![SortKey {
                field: Some(FieldSort {
                    field: "price".to_owned(),
                    sort_type: SortType::Long,
                    reverse: false,
                    selector: SortSelector::Min,
                    missing: SortMissing::Default,
                }),
                ..SortKey::default()
            }]
        );
        assert_eq!(params.similarity, bm25());
        search.search_started(2);
        let mut page = results(0..2, 2);
        let found = page.results.as_mut().unwrap();
        found.hits[0].sort_values = vec![SortValue {
            number: Some("4621819117588971520".to_owned()),
            term: None,
        }];
        found.hits[1].sort_values = vec![SortValue {
            number: None,
            term: None,
        }];
        search.search_progress(page);

        let screen = render(&mut search);
        assert!(screen.contains("sorted by price ↑"), "{screen}");
        assert!(screen.contains("Sorted by"), "{screen}");
        assert!(screen.contains("4621819117588971520"), "{screen}");
        assert!(screen.contains("–"), "{screen}");
    }

    #[test]
    fn the_similarity_goes_to_the_search_and_its_explanations() {
        let mut search = searched(2, 2);

        press(&mut search, KeyCode::Char('s'));
        press(&mut search, KeyCode::Char(' '));
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("applying searches again");
        };
        let classic = Similarity {
            classic: Some(ClassicSimilarity {}),
            ..Similarity::default()
        };
        assert_eq!(params.similarity, classic);
        assert!(params.sort.is_empty());
        search.search_started(2);
        search.search_progress(results(0..2, 2));
        assert!(
            render(&mut search).contains("Similarity classic TF-IDF"),
            "{}",
            render(&mut search)
        );
        assert!(matches!(
            press(&mut search, KeyCode::Char('x')),
            Some(Request::Explain { params, .. }) if params.similarity == classic
        ));
        // Esc in the popup changes nothing.
        press(&mut search, KeyCode::Char('s'));
        press(&mut search, KeyCode::Char(' '));
        assert_eq!(press(&mut search, KeyCode::Esc), None);
        assert!(!search.prompting());
    }

    #[test]
    fn the_fields_popup_chooses_the_stored_fields_per_hit() {
        let mut search = searched(2, 2);

        assert_eq!(press(&mut search, KeyCode::Char('l')), None);
        assert!(render(&mut search).contains("Stored fields per hit"));
        press(&mut search, KeyCode::Char('n'));
        press(&mut search, KeyCode::Char(' '));
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Enter) else {
            panic!("applying searches again");
        };
        assert_eq!(params.fields, Some(vec!["id".to_owned()]));
        search.search_started(2);
        search.search_progress(results(0..2, 2));
        assert!(render(&mut search).contains("Fields     id"));

        // The next page asks for the same fields.
        let mut search = searched(SEARCH_PAGE, 150);
        press(&mut search, KeyCode::Char('l'));
        press(&mut search, KeyCode::Char(' '));
        press(&mut search, KeyCode::Enter);
        search.search_started(2);
        search.search_progress(results(0..SEARCH_PAGE, 150));
        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::End) else {
            panic!("the last row fetches the next page");
        };
        assert_eq!(params.offset, SEARCH_PAGE);
        assert_eq!(params.fields.unwrap().len(), 6);
    }

    #[test]
    fn browses_the_documents_that_match_from_the_selected_hit() {
        let mut search = searched(3, 3);
        press(&mut search, KeyCode::Down);

        assert_eq!(
            press(&mut search, KeyCode::Char('b')),
            Some(Request::Filter {
                params: StartFilterParams {
                    query: Some(classic("red", "title", QueryOperator::Or)),
                    pattern: None,
                    include_deleted: false,
                },
                label: "red".to_owned(),
                start_doc: 1,
            })
        );
        let Some(Request::Filter { params, .. }) = press(&mut search, KeyCode::Char('B')) else {
            panic!("no filter");
        };
        assert!(params.include_deleted);

        // Without a query, there is nothing to browse.
        let mut blank = self::search();
        assert_eq!(press(&mut blank, KeyCode::Char('b')), None);
    }

    #[test]
    fn browses_the_documents_with_a_value_that_the_regex_finds() {
        let (mut search, _) = regex("re[d]");
        press(&mut search, KeyCode::Char('i'));
        search.stored_scan_started(6);
        search.stored_scan_progress(scan_poll(
            vec![scan_hit(3, "body"), scan_hit(8, "title")],
            true,
        ));
        press(&mut search, KeyCode::Down);

        assert_eq!(
            press(&mut search, KeyCode::Char('B')),
            Some(Request::Filter {
                params: StartFilterParams {
                    query: None,
                    pattern: Some(FilterPattern {
                        pattern: "re[d]".to_owned(),
                        case_insensitive: true,
                        fields: None,
                    }),
                    include_deleted: true,
                },
                label: "/re[d]/i".to_owned(),
                start_doc: 8,
            })
        );
    }

    #[test]
    fn steps_through_the_hits_and_into_the_next_page() {
        let mut search = searched(SEARCH_PAGE, 150);

        assert_eq!(search.step(false), None);
        assert_eq!(search.step(true), Some(Request::Document(1)));
        for doc_id in 2..SEARCH_PAGE {
            assert_eq!(search.step(true), Some(Request::Document(doc_id)));
        }
        // Past the last hit: the next page first, then its first hit.
        let next = search.step(true);
        assert_eq!(
            next,
            request(
                classic("red", "title", QueryOperator::Or),
                SEARCH_PAGE,
                SEARCH_PAGE,
                vec![]
            )
        );
        assert_eq!(search.step(true), None);
        search.search_started(2);
        assert_eq!(
            search.search_progress(results(100..150, 150)),
            Some(Request::Document(100))
        );
        assert_eq!(search.step(false), Some(Request::Document(99)));
        assert!(render(&mut search).contains("150 hits"));
    }

    #[test]
    fn cancelling_turns_a_missing_first_page_into_an_error() {
        let mut search = search();
        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, "red");
        press(&mut search, KeyCode::Enter);
        search.search_started(1);
        assert_eq!(search.jobs(), vec![1]);

        search.cancelled();

        assert_eq!(search.jobs(), Vec::<i64>::new());
        assert!(render(&mut search).contains("Cancelled."));
    }

    #[test]
    fn marks_cut_values_and_approximate_counts() {
        let mut search = search();
        press(&mut search, KeyCode::Char('/'));
        typed(&mut search, "red");
        press(&mut search, KeyCode::Enter);
        let mut page = results(0..1, 1000);
        let found = page.results.as_mut().unwrap();
        found.total_hits_relation = TotalHitsRelation::GreaterThanOrEqualTo;
        found.hits[0].fields.push(HitField {
            name: "key".to_owned(),
            values: vec![StoredValue {
                kind: StoredType::Binary,
                value: "ff00".to_owned(),
                binary: true,
                full_length: Some(300),
            }],
        });

        search.search_progress(page);

        let screen = render(&mut search);
        assert!(screen.contains("at least 1000 hits"), "{screen}");
        assert!(screen.contains("id=doc-0  key=0xff00…"), "{screen}");
    }

    #[test]
    fn more_hits_follow_a_full_page_within_the_window() {
        let equal = TotalHitsRelation::EqualTo;
        let at_least = TotalHitsRelation::GreaterThanOrEqualTo;
        assert!(more_hits(true, 100, 150, equal));
        assert!(!more_hits(true, 100, 100, equal));
        assert!(!more_hits(false, 50, 150, equal));
        assert!(more_hits(true, 1000, 1000, at_least));
        assert!(!more_hits(true, MAX_WINDOW as usize, 20_000, at_least));
    }

    #[test]
    fn an_index_without_postings_has_nothing_to_search() {
        let mut search = Search::new(&[field("stored", IndexOptions::None)], 1);

        assert_eq!(press(&mut search, KeyCode::Char('/')), None);
        assert!(!search.prompting());
        assert!(render(&mut search).contains("nothing to search"));
    }
}
