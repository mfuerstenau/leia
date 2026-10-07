//! The search screen (ADR 0010): a query line, the hits of the last query, and a details panel
//! with the parsed query or the explanation of a hit. Searches and explanations run as server
//! jobs; the next page of hits is fetched once the selection reaches the last row. Enter on a hit
//! shows its document on the documents screen.

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Cell, Paragraph, Row, Table, TableState, Wrap};

use crate::input::{Edit, LineInput, word_start};
use crate::overview::{highlight, panel};
use crate::protocol::document::{BytesValue, StoredValue};
use crate::protocol::index::{IndexField, IndexOptions};
use crate::protocol::search::{
    ClassicQuery, Explanation, GetExplainResult, GetSearchResult, Hit, MAX_WINDOW, QueryOperator,
    SearchQuery, SearchResults, StartExplainParams, StartSearchParams, TermQuery,
    TotalHitsRelation,
};
use crate::terms::{moved, number_width, tail_row};
use crate::text::{bytes_text, escape_control};

/// How many hits one `startSearch` call fetches.
pub const SEARCH_PAGE: i32 = 100;
/// Rows that PageUp and PageDown scroll the details.
const PAGE: u16 = 10;

/// What the caller has to start, stop or show for the search screen.
#[derive(Debug, PartialEq, Eq)]
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
    /// Cancel these jobs, and report back with [`Search::cancelled`].
    Cancel(Vec<i64>),
    /// Show this document on the documents screen.
    Document(i32),
}

/// How the query line is read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Mode {
    /// Lucene's classic query syntax.
    Classic,
    /// One term of the selected field, exactly as typed.
    Term,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Focus {
    Hits,
    Details,
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
    /// Whether more hits may follow: the last page was full.
    more: bool,
    /// Why the last page could not be fetched; no more pages are fetched then.
    error: Option<String>,
}

/// A submitted query and what came of it.
#[derive(Debug)]
struct Submitted {
    query: SearchQuery,
    exact: bool,
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

/// The explanation of one hit for one query.
#[derive(Debug)]
struct Explain {
    doc_id: i32,
    query: SearchQuery,
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
    exact: bool,
    input: LineInput,
    /// Whether the input holds a binary term in hex, as the overview's top terms bring it. An
    /// edit makes it text again.
    binary: bool,
    editing: bool,
    focus: Focus,
    submitted: Option<Submitted>,
    running: Option<Running>,
    explain: Option<Explain>,
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
            exact: false,
            input: LineInput::new(""),
            binary: false,
            editing: false,
            focus: Focus::Hits,
            submitted: None,
            running: None,
            explain: None,
            table: TableState::new(),
            details_scroll: 0,
        }
    }

    /// Whether the query line takes the keys, Esc included.
    pub fn prompting(&self) -> bool {
        self.editing
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

    /// The jobs that Esc stops.
    pub fn jobs(&self) -> Vec<i64> {
        self.search_job()
            .into_iter()
            .chain(self.explain_job())
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

    /// The query that the query line and the settings describe; `None` while the line is blank.
    fn query(&self) -> Option<SearchQuery> {
        let field = self.fields.get(self.field)?.clone();
        let text = self.input.text();
        if text.trim().is_empty() {
            return None;
        }
        Some(match self.mode {
            Mode::Classic => SearchQuery {
                classic: Some(ClassicQuery {
                    expression: text.to_owned(),
                    default_field: field,
                    default_operator: self.operator,
                }),
                term: None,
            },
            Mode::Term => SearchQuery {
                classic: None,
                term: Some(TermQuery {
                    field,
                    term: BytesValue {
                        value: text.to_owned(),
                        binary: self.binary,
                    },
                }),
            },
        })
    }

    /// Starts a search for the query line, stopping the jobs of the previous one.
    fn submit(&mut self) -> Option<Request> {
        let query = self.query()?;
        let cancel = self.jobs();
        self.explain = None;
        self.submitted = Some(Submitted {
            query: query.clone(),
            exact: self.exact,
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
        Some(Request::Search {
            params: StartSearchParams {
                query,
                offset: 0,
                limit: SEARCH_PAGE,
                exact_total_hits: self.exact,
                fields: None,
            },
            cancel,
        })
    }

    /// Searches again once a setting changed, if a query was submitted.
    fn resubmit(&mut self) -> Option<Request> {
        self.submitted.as_ref()?;
        self.submit()
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
            query: submitted.query.clone(),
            offset,
            limit,
            exact_total_hits: submitted.exact,
            fields: None,
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

    /// Forgets the stopped jobs: a first page that was not in yet becomes an error.
    pub fn cancelled(&mut self) {
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
        let query = self.submitted.as_ref()?.query.clone();
        let doc_id = self.selected_hit()?.doc_id;
        if self.explain.as_ref().is_some_and(|explain| {
            explain.doc_id == doc_id
                && explain.query == query
                && !matches!(explain.outcome, Some(Err(_)))
        }) {
            return None;
        }
        let cancel = self.explain_job().into_iter().collect();
        self.explain = Some(Explain {
            doc_id,
            query: query.clone(),
            job_id: None,
            outcome: None,
        });
        self.details_scroll = 0;
        Some(Request::Explain {
            params: StartExplainParams { query, doc_id },
            cancel,
        })
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

    /// The explanation that belongs to the selected hit and the shown query, if any.
    fn shown_explanation(&self) -> Option<&Explain> {
        let explain = self.explain.as_ref()?;
        let shows = Some(explain.doc_id) == self.selected_hit().map(|hit| hit.doc_id)
            && Some(&explain.query) == self.submitted.as_ref().map(|s| &s.query);
        shows.then_some(explain)
    }

    /// Edits the query line while it is open (Enter searches, Esc closes it). Otherwise `/` opens
    /// it, `m` switches between classic syntax and a term, `f`/`F` step the field, `a` switches
    /// between OR and AND, `t` between an exact and an approximate count, `x` explains the
    /// selected hit, Enter shows its document, and Tab switches between the hits and the details.
    pub fn handle_key(&mut self, key: KeyEvent) -> Option<Request> {
        if self.editing {
            match key.code {
                KeyCode::Enter => {
                    self.editing = false;
                    return self.submit();
                }
                KeyCode::Esc => self.editing = false,
                _ => {
                    if self.input.handle_key(key, word_start) == Edit::Changed {
                        self.binary = false;
                    }
                }
            }
            return None;
        }
        match key.code {
            KeyCode::Char('/') if !self.fields.is_empty() => self.editing = true,
            KeyCode::Char('m') => {
                self.mode = match self.mode {
                    Mode::Classic => Mode::Term,
                    Mode::Term => Mode::Classic,
                };
                return self.resubmit();
            }
            KeyCode::Char('f') | KeyCode::Char('F') if !self.fields.is_empty() => {
                let len = self.fields.len();
                self.field = if key.code == KeyCode::Char('f') {
                    (self.field + 1) % len
                } else {
                    (self.field + len - 1) % len
                };
                return self.resubmit();
            }
            KeyCode::Char('a') if self.mode == Mode::Classic => {
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
        if self.editing {
            " ⏎ search   Esc done   ←→ Ctrl+A/E/U/K/W edit"
        } else if self.mode == Mode::Classic {
            "/ query   m mode   f/F field   a AND/OR   t count   x explain   ⏎ open doc   \
             Tab panel   ↑↓"
        } else {
            "/ term   m mode   f/F field   t count   x explain   ⏎ open doc   Tab panel   ↑↓"
        }
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        if self.fields.is_empty() {
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
    }

    fn render_query(&self, frame: &mut Frame, area: Rect) {
        let field = escape_control(&self.fields[self.field]);
        let count = if self.exact {
            "exact count"
        } else {
            "count to 1000"
        };
        let title = match self.mode {
            Mode::Classic => {
                let operator = match self.operator {
                    QueryOperator::Or => "OR",
                    QueryOperator::And => "AND",
                };
                format!(" Classic query · default field {field} · {operator} · {count} ")
            }
            Mode::Term => {
                let hex = if self.binary { " · hex" } else { "" };
                format!(" Term · field {field}{hex} · {count} ")
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
            frame.render_widget(Line::from("Press / to type a query.").style(dim()), inner);
        } else {
            frame.render_widget(Line::from(escape_control(self.input.text())), inner);
        }
    }

    fn render_hits(&mut self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Hits && !self.editing;
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
        let mut rows: Vec<Row> = list
            .hits
            .iter()
            .enumerate()
            .map(|(i, hit)| {
                Row::new([
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
                    Cell::from(fields_text(hit)),
                ])
            })
            .collect();
        rows.extend(tail_row(list.more, list.error.as_deref()));
        let header = Row::new(["#", "Doc", "    Score", "Fields"])
            .style(Style::new().add_modifier(Modifier::BOLD));
        let table = Table::new(
            rows,
            [
                Constraint::Length(rank_width.max(1)),
                Constraint::Length(doc_width),
                Constraint::Length(9),
                Constraint::Fill(1),
            ],
        )
        .header(header)
        .row_highlight_style(highlight(focused))
        .block(block);
        frame.render_stateful_widget(table, area, &mut self.table);
    }

    fn render_details(&self, frame: &mut Frame, area: Rect) {
        let focused = self.focus == Focus::Details && !self.editing;
        let (title, lines) = match self.shown_explanation() {
            Some(explain) => (
                format!(" Explanation of doc {} ", explain.doc_id),
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

    /// The parsed and rewritten query, or how to write one.
    fn query_lines(&self) -> Vec<Line<'static>> {
        let Some(list) = self.hit_list() else {
            return match self.mode {
                Mode::Classic => vec![
                    Line::from("Classic syntax, for example:"),
                    Line::from("  fox  title:fox  +fox -dog  fox AND (red OR blue)"),
                    Line::from("  \"quick fox\"  \"quick fox\"~2  qu*  f?x  fox~  /f[ao]x/"),
                    Line::from("  title:[a TO m]  fox^2"),
                ],
                Mode::Term => vec![Line::from(
                    "The line is one term of the field, as it is: nothing is analyzed or split.",
                )],
            }
            .into_iter()
            .map(|line| line.style(dim()))
            .collect();
        };
        let mut lines = vec![
            Line::from(format!("Parsed     {}", escape_control(&list.query))),
            Line::from(format!(
                "Rewritten  {}",
                escape_control(&list.rewritten_query)
            )),
        ];
        if !list.hits.is_empty() {
            lines.push(Line::from(""));
            lines.push(Line::from("x explains the selected hit.").style(dim()));
        }
        lines
    }
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
    use crate::protocol::document::StoredType;
    use crate::protocol::index::{DocValuesType, TermStats};
    use crate::protocol::search::HitField;

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

    /// `id` (docs only), `stored` (no postings), `title` and `body` (positions).
    fn search() -> Search {
        Search::new(
            &[
                field("id", IndexOptions::Docs),
                field("stored", IndexOptions::None),
                field("title", IndexOptions::DocsAndFreqsAndPositions),
                field("body", IndexOptions::DocsAndFreqsAndPositionsAndOffsets),
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
            }),
            term: None,
        }
    }

    fn request(query: SearchQuery, offset: i32, limit: i32, cancel: Vec<i64>) -> Option<Request> {
        Some(Request::Search {
            params: StartSearchParams {
                query,
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
            }),
        }
    }

    fn render(search: &mut Search) -> String {
        let (width, height) = (100, 24);
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

        let Some(Request::Search { params, .. }) = press(&mut search, KeyCode::Char('m')) else {
            panic!("m searches again");
        };
        assert_eq!(
            params.query,
            SearchQuery {
                classic: None,
                term: Some(TermQuery {
                    field: "title".to_owned(),
                    term: BytesValue {
                        value: "red".to_owned(),
                        binary: false,
                    },
                }),
            }
        );
        assert!(render(&mut search).contains("Term · field title"));
        // There is no operator for a term.
        assert_eq!(press(&mut search, KeyCode::Char('a')), None);
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
