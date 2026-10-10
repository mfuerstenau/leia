//! A filter that narrows the documents screen to the documents that match a query (ADR 0031):
//! ←/→ step to the previous or next match in document id order, and the screen's title says
//! which match is shown. The server keeps the matches; this tracks the filter's state.

use crate::protocol::filter::{FilterMatch, GetFilterResult};
use crate::text::{escape_control, first_chars};

/// What the documents screen does with the match that `stepFilter` finds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FilterStep {
    /// Show it: ←/→.
    Move,
    /// Show it, or if there is none forward, the last match before it: `i`, and the first
    /// document once the filter is built.
    Nearest,
    /// Only note whether the shown document matches, and which match it is.
    Locate,
}

/// What follows a step.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stepped {
    /// Show this document.
    Show(i32),
    /// Look back from this document id: there is no match forward.
    LookBack(i32),
    Nothing,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum State {
    /// The server finds the matches; `job_id` is `None` until it has started the job.
    Building {
        job_id: Option<i64>,
        searched: i64,
    },
    Ready {
        filter_id: i64,
        match_count: i32,
    },
    Failed(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DocFilter {
    /// The query as typed, for the title.
    label: String,
    include_deleted: bool,
    /// The document to start on once the filter is built.
    start_doc: i32,
    state: State,
    /// The document last located, and which match it is: `None` if it doesn't match.
    located: Option<(i32, Option<i32>)>,
    /// Said until the next key, such as that there is no next match.
    note: Option<&'static str>,
}

impl DocFilter {
    /// A filter about to be built, which starts on `start_doc` if it matches, else on the first
    /// match after it, else on the last match.
    pub fn new(label: String, include_deleted: bool, start_doc: i32) -> Self {
        Self {
            label,
            include_deleted,
            start_doc,
            state: State::Building {
                job_id: None,
                searched: 0,
            },
            located: None,
            note: None,
        }
    }

    /// The job that builds the filter, while it runs.
    pub fn job_id(&self) -> Option<i64> {
        match self.state {
            State::Building { job_id, .. } => job_id,
            _ => None,
        }
    }

    /// The filter the server keeps, once it is built.
    pub fn filter_id(&self) -> Option<i64> {
        match self.state {
            State::Ready { filter_id, .. } => Some(filter_id),
            _ => None,
        }
    }

    pub fn building(&self) -> bool {
        matches!(self.state, State::Building { .. })
    }

    pub fn started(&mut self, job_id: i64) {
        if let State::Building { job_id: id, .. } = &mut self.state {
            *id = Some(job_id);
        }
    }

    /// Takes the progress of the job. Once the filter is built, returns its id and the document
    /// to start from.
    pub fn progress(&mut self, result: GetFilterResult) -> Option<(i64, i32)> {
        let State::Building { searched, .. } = &mut self.state else {
            return None;
        };
        *searched = result.docs_searched;
        let built = result.result?;
        self.state = State::Ready {
            filter_id: built.filter_id,
            match_count: built.match_count,
        };
        Some((built.filter_id, self.start_doc))
    }

    pub fn failed(&mut self, error: String) {
        self.state = State::Failed(error);
        self.located = None;
    }

    /// Takes what `stepFilter` found from `from_doc` for a `step`.
    pub fn stepped(
        &mut self,
        from_doc: i32,
        forward: bool,
        step: FilterStep,
        found: Option<FilterMatch>,
    ) -> Stepped {
        match (step, found) {
            (FilterStep::Locate, found) => {
                let index = found
                    .filter(|found| found.doc_id == from_doc)
                    .map(|found| found.index);
                self.located = Some((from_doc, index));
                Stepped::Nothing
            }
            (_, Some(found)) => {
                self.located = Some((found.doc_id, Some(found.index)));
                Stepped::Show(found.doc_id)
            }
            (FilterStep::Nearest, None) if forward => Stepped::LookBack(from_doc),
            (_, None) => {
                self.note = Some(
                    if matches!(self.state, State::Ready { match_count: 0, .. }) {
                        "no matches"
                    } else if forward {
                        "no next match"
                    } else {
                        "no previous match"
                    },
                );
                Stepped::Nothing
            }
        }
    }

    /// Whether `doc_id` has to be located: the filter is built and doesn't know about it yet.
    pub fn needs_locating(&self, doc_id: i32) -> bool {
        self.filter_id().is_some() && self.located.map(|(located, _)| located) != Some(doc_id)
    }

    pub fn clear_note(&mut self) {
        self.note = None;
    }

    /// What the title says about the filter while `doc_id` is shown, and whether that is an error.
    pub fn title(&self, doc_id: i32, max_doc: i32) -> (String, bool) {
        let deleted = if self.include_deleted {
            ", deleted too"
        } else {
            ""
        };
        let label = first_chars(&escape_control(&self.label), 40);
        let (state, error) = match &self.state {
            State::Building { searched, .. } => {
                let percent = (searched * 100)
                    .checked_div(i64::from(max_doc))
                    .unwrap_or(0);
                (format!("finding matches… {percent}%"), false)
            }
            State::Ready { match_count: 0, .. } => ("no matches".to_owned(), false),
            State::Ready { match_count, .. } => (
                match self.located {
                    Some((located, Some(index))) if located == doc_id => {
                        format!("{} of {match_count}", index + 1)
                    }
                    Some((located, None)) if located == doc_id => {
                        format!("not a match, {match_count} in all")
                    }
                    _ => matches(*match_count),
                },
                false,
            ),
            State::Failed(error) => (first_chars(error, 80), true),
        };
        let note = self
            .note
            .map(|note| format!(" · {note}"))
            .unwrap_or_default();
        (format!("filter {label}{deleted}: {state}{note}"), error)
    }
}

fn matches(count: i32) -> String {
    if count == 1 {
        "1 match".to_owned()
    } else {
        format!("{count} matches")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::filter::FilterResult;

    fn built(filter_id: i64, match_count: i32) -> GetFilterResult {
        GetFilterResult {
            docs_searched: 10,
            result: Some(FilterResult {
                filter_id,
                match_count,
                query: "body:red".to_owned(),
                rewritten_query: "body:red".to_owned(),
                took_millis: 1,
            }),
        }
    }

    fn found(doc_id: i32, index: i32) -> Option<FilterMatch> {
        Some(FilterMatch { doc_id, index })
    }

    #[test]
    fn builds_then_steps_and_locates() {
        let mut filter = DocFilter::new("red".to_owned(), false, 4);
        filter.started(7);
        assert_eq!(filter.job_id(), Some(7));
        let running = GetFilterResult {
            docs_searched: 5,
            result: None,
        };
        assert_eq!(filter.progress(running), None);
        assert_eq!(filter.title(0, 10).0, "filter red: finding matches… 50%");

        assert_eq!(filter.progress(built(7, 3)), Some((7, 4)));
        assert_eq!(filter.job_id(), None);
        assert_eq!(filter.filter_id(), Some(7));
        assert!(filter.needs_locating(4));
        assert_eq!(
            filter.stepped(4, true, FilterStep::Nearest, found(6, 1)),
            Stepped::Show(6)
        );
        assert!(!filter.needs_locating(6));
        assert_eq!(filter.title(6, 10).0, "filter red: 2 of 3");

        assert_eq!(
            filter.stepped(2, true, FilterStep::Locate, found(3, 1)),
            Stepped::Nothing
        );
        assert_eq!(filter.title(2, 10).0, "filter red: not a match, 3 in all");
        assert_eq!(filter.title(9, 10).0, "filter red: 3 matches");
    }

    #[test]
    fn looks_back_when_nothing_matches_forward_and_says_when_nothing_is_left() {
        let mut filter = DocFilter::new("red".to_owned(), true, 8);
        filter.progress(built(2, 1));

        assert_eq!(
            filter.stepped(8, true, FilterStep::Nearest, None),
            Stepped::LookBack(8)
        );
        assert_eq!(
            filter.stepped(9, true, FilterStep::Move, None),
            Stepped::Nothing
        );
        assert_eq!(
            filter.title(9, 10).0,
            "filter red, deleted too: 1 match · no next match"
        );
        filter.clear_note();
        assert_eq!(filter.title(9, 10).0, "filter red, deleted too: 1 match");
    }

    #[test]
    fn says_why_it_failed() {
        let mut filter = DocFilter::new("title:(".to_owned(), false, 0);
        filter.failed("Invalid query".to_owned());

        assert_eq!(filter.job_id(), None);
        assert_eq!(filter.filter_id(), None);
        assert_eq!(
            filter.title(0, 10),
            ("filter title:(: Invalid query".to_owned(), true)
        );
    }
}
