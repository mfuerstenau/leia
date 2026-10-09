//! Rebuilding what a field of the shown document was indexed with (ADR 0029): the state of the
//! rebuild on the documents screen, and the field's tokens put back in position order.

use crate::protocol::document::{ReconstructSource, ReconstructedField, TermVectorTerm};
use crate::protocol::index::IndexOptions;
use crate::text::{bytes_text, count};

/// The rebuild of one field of the shown document.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reconstruction {
    /// Asked for; the server hasn't started the job yet.
    Starting,
    Running {
        job_id: i64,
        scanned: i64,
        /// The terms the job goes through, if the server knows.
        total: Option<i64>,
    },
    Done(ReconstructedField),
    Failed(String),
}

impl Reconstruction {
    /// Whether the rebuild is asked for or running.
    pub fn active(&self) -> bool {
        matches!(self, Self::Starting | Self::Running { .. })
    }

    /// The job, while it runs.
    pub fn job(&self) -> Option<i64> {
        match self {
            Self::Running { job_id, .. } => Some(*job_id),
            _ => None,
        }
    }

    /// One line on where the rebuild stands, or what it found.
    pub fn summary(&self) -> String {
        match self {
            Self::Starting => "rebuilding…".to_owned(),
            Self::Running {
                scanned,
                total: Some(total),
                ..
            } => format!("rebuilding… {scanned} of {total} terms, Esc stops"),
            Self::Running { scanned, .. } => format!("rebuilding… {scanned} terms, Esc stops"),
            Self::Done(field) => {
                let source = match field.source {
                    ReconstructSource::TermVector => "the term vector",
                    ReconstructSource::Postings => "the postings",
                };
                format!("{}, from {source}", count(field.terms.len(), "term"))
            }
            Self::Failed(error) => format!("cannot rebuild it: {error}"),
        }
    }
}

/// Whether the freqs of a rebuilt field count occurrences: postings without freqs give 1 for
/// every term, and custom freqs are something else, such as scores.
pub fn has_freqs(field: &ReconstructedField) -> bool {
    field.source == ReconstructSource::TermVector
        || matches!(
            field.index_options,
            IndexOptions::DocsAndFreqs
                | IndexOptions::DocsAndFreqsAndPositions
                | IndexOptions::DocsAndFreqsAndPositionsAndOffsets
        )
}

/// The field's tokens in position order, as the analyzer left them: tokens at the same position,
/// such as synonyms, joined by `|`, and `·` where positions were skipped, such as for a removed
/// stop word. `None` if the terms have no positions.
pub fn token_line(terms: &[TermVectorTerm]) -> Option<String> {
    let mut tokens: Vec<(i32, i32, String)> = terms
        .iter()
        .flat_map(|term| {
            let text = bytes_text(&term.term, term.binary);
            term.positions.iter().filter_map(move |position| {
                Some((
                    position.position?,
                    position.start_offset.unwrap_or(0),
                    text.clone(),
                ))
            })
        })
        .collect();
    if tokens.is_empty() {
        return None;
    }
    tokens.sort();
    let mut line = String::new();
    let mut last: Option<i32> = None;
    for (position, _, text) in tokens {
        match last {
            Some(last) if last == position => line.push('|'),
            Some(last) => line.push_str(if position > last + 1 { " · " } else { " " }),
            None if position > 0 => line.push_str("· "),
            None => {}
        }
        line.push_str(&text);
        last = Some(position);
    }
    Some(line)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::document::TermPosition;

    fn term(text: &str, positions: &[i32]) -> TermVectorTerm {
        TermVectorTerm {
            term: text.to_owned(),
            binary: false,
            freq: i32::try_from(positions.len()).unwrap(),
            positions: positions
                .iter()
                .map(|&position| TermPosition {
                    position: Some(position),
                    start_offset: None,
                    end_offset: None,
                    payload: None,
                })
                .collect(),
        }
    }

    #[test]
    fn puts_the_tokens_back_in_position_order() {
        let terms = [
            term("dog", &[5]),
            term("fox", &[2]),
            term("quick", &[1]),
            term("saw", &[3]),
            term("the", &[0, 4]),
        ];
        assert_eq!(
            token_line(&terms).as_deref(),
            Some("the quick fox saw the dog")
        );
    }

    #[test]
    fn marks_stacked_tokens_and_gaps() {
        let terms = [term("car", &[3]), term("fast", &[1]), term("quick", &[1])];
        assert_eq!(token_line(&terms).as_deref(), Some("· fast|quick · car"));
    }

    #[test]
    fn stacked_tokens_follow_their_offsets() {
        let mut wifi = term("wifi", &[0]);
        wifi.positions[0].start_offset = Some(0);
        let mut wi = term("wi", &[0]);
        wi.positions[0].start_offset = Some(0);
        let mut fi = term("fi", &[1]);
        fi.positions[0].start_offset = Some(3);
        assert_eq!(token_line(&[fi, wi, wifi]).as_deref(), Some("wi|wifi fi"));
    }

    #[test]
    fn shows_binary_terms_as_hex() {
        let mut binary = term("ff00", &[0]);
        binary.binary = true;
        assert_eq!(token_line(&[binary]).as_deref(), Some("0xff00"));
    }

    #[test]
    fn has_no_line_without_positions() {
        assert_eq!(token_line(&[term("black", &[]), term("kettle", &[])]), None);
    }

    #[test]
    fn summarizes_the_state() {
        let running = Reconstruction::Running {
            job_id: 4,
            scanned: 1024,
            total: Some(80000),
        };
        assert_eq!(
            running.summary(),
            "rebuilding… 1024 of 80000 terms, Esc stops"
        );
        assert_eq!(running.job(), Some(4));
        assert!(running.active());
        let done = Reconstruction::Done(ReconstructedField {
            field: "title".to_owned(),
            index_options: IndexOptions::Docs,
            source: ReconstructSource::Postings,
            terms: vec![term("a", &[])],
        });
        assert_eq!(done.summary(), "1 term, from the postings");
        assert!(!done.active());
        let Reconstruction::Done(field) = &done else {
            unreachable!()
        };
        assert!(!has_freqs(field));
    }
}
