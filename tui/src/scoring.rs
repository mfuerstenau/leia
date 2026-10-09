//! The similarity and the sort of the search screen (ADR 0012, ADR 0025), and the popup that
//! edits them: BM25 with its `k1` and `b`, classic TF-IDF or boolean, and up to two sort levels,
//! each by the score, the document id or a field with doc values, with a selector for
//! multi-valued fields and a place for documents without a value.

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::Rect;
use ratatui::text::{Line, Span};

use crate::form::{FormEvent, dim, render_popup, row, type_digit};
use crate::protocol::index::{DocValuesType, IndexField};
use crate::protocol::search::{
    Bm25Similarity, BooleanSimilarity, ClassicSimilarity, DocSort, FieldSort, ScoreSort,
    Similarity, SortKey, SortMissing, SortSelector, SortType,
};
use crate::text::escape_control;

/// How many sort levels the popup offers, as Luke does.
const LEVELS: usize = 2;
/// BM25's defaults in hundredths.
const DEFAULT_K1: i32 = 120;
const DEFAULT_B: i32 = 75;
/// The largest `k1`, in hundredths, that the popup takes.
const MAX_K1: i32 = 999;
/// The largest `b`, in hundredths.
const MAX_B: i32 = 100;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimilarityKind {
    Bm25,
    Classic,
    Boolean,
}

/// A field that can be sorted by: one with doc values other than binary.
#[derive(Debug, Clone, PartialEq, Eq)]
struct SortableField {
    name: String,
    /// Numeric, sorted numeric, sorted or sorted set.
    doc_values: DocValuesType,
}

impl SortableField {
    /// Numeric or sorted numeric doc values; otherwise sorted or sorted set.
    fn numeric(&self) -> bool {
        matches!(
            self.doc_values,
            DocValuesType::Numeric | DocValuesType::SortedNumeric
        )
    }

    /// The selectors its doc values take: only `Min` for single-valued ones.
    fn selectors(&self) -> &'static [SortSelector] {
        match self.doc_values {
            DocValuesType::SortedNumeric => &[SortSelector::Min, SortSelector::Max],
            DocValuesType::SortedSet => &[
                SortSelector::Min,
                SortSelector::Max,
                SortSelector::MiddleMin,
                SortSelector::MiddleMax,
            ],
            _ => &[SortSelector::Min],
        }
    }
}

/// What a sort level sorts by.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Target {
    Score,
    Doc,
    /// A field of [`ScoringSettings::fields`].
    Field(usize),
}

/// One sort level: what it sorts by and, for a field, how to read it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct SortLevel {
    target: Target,
    sort_type: SortType,
    reverse: bool,
    selector: SortSelector,
    missing: SortMissing,
}

/// The similarity and the sort, as the search screen sends them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScoringSettings {
    kind: SimilarityKind,
    /// BM25's `k1` and `b` in hundredths, so that the popup edits them as whole numbers.
    k1: i32,
    b: i32,
    fields: Vec<SortableField>,
    /// The second level only with the first.
    levels: [Option<SortLevel>; LEVELS],
}

impl ScoringSettings {
    /// BM25 with Lucene's defaults, sorted by score.
    pub fn new(fields: &[IndexField]) -> Self {
        let fields = fields
            .iter()
            .filter(|field| {
                !matches!(
                    field.doc_values_type,
                    DocValuesType::None | DocValuesType::Binary
                )
            })
            .map(|field| SortableField {
                name: field.name.clone(),
                doc_values: field.doc_values_type,
            })
            .collect();
        Self {
            kind: SimilarityKind::Bm25,
            k1: DEFAULT_K1,
            b: DEFAULT_B,
            fields,
            levels: [None; LEVELS],
        }
    }

    pub fn similarity(&self) -> Similarity {
        match self.kind {
            SimilarityKind::Bm25 => Similarity {
                bm25: Some(Bm25Similarity {
                    k1: f64::from(self.k1) / 100.0,
                    b: f64::from(self.b) / 100.0,
                }),
                ..Similarity::default()
            },
            SimilarityKind::Classic => Similarity {
                classic: Some(ClassicSimilarity {}),
                ..Similarity::default()
            },
            SimilarityKind::Boolean => Similarity {
                boolean: Some(BooleanSimilarity {}),
                ..Similarity::default()
            },
        }
    }

    /// The sort keys, as `startSearch` takes them; empty to sort by score.
    pub fn sort_keys(&self) -> Vec<SortKey> {
        self.levels
            .iter()
            .flatten()
            .map(|level| match level.target {
                Target::Score => SortKey {
                    score: Some(ScoreSort {
                        reverse: level.reverse,
                    }),
                    ..SortKey::default()
                },
                Target::Doc => SortKey {
                    doc: Some(DocSort {
                        reverse: level.reverse,
                    }),
                    ..SortKey::default()
                },
                Target::Field(field) => SortKey {
                    field: Some(FieldSort {
                        field: self.fields[field].name.clone(),
                        sort_type: level.sort_type,
                        reverse: level.reverse,
                        selector: level.selector,
                        missing: level.missing,
                    }),
                    ..SortKey::default()
                },
            })
            .collect()
    }

    /// The similarity, unless it is Lucene's default, for the details panel.
    pub fn similarity_text(&self) -> Option<String> {
        match self.kind {
            SimilarityKind::Bm25 if self.k1 == DEFAULT_K1 && self.b == DEFAULT_B => None,
            SimilarityKind::Bm25 => Some(format!(
                "BM25 k1 {} b {}",
                hundredths(self.k1),
                hundredths(self.b)
            )),
            SimilarityKind::Classic => Some("classic TF-IDF".to_owned()),
            SimilarityKind::Boolean => Some("boolean".to_owned()),
        }
    }

    /// The sort levels, such as `tags MAX last ↑ · price DOUBLE ↓ · score`; `None` to sort by
    /// score. A string sort and the guessed `LONG` leave the type out, `MIN` and Lucene's missing
    /// values are left out too. The score comes best first, or `↑` for the worst first.
    pub fn sort_text(&self) -> Option<String> {
        let levels: Vec<String> = self
            .levels
            .iter()
            .flatten()
            .map(|level| {
                let arrow = if level.reverse { "↓" } else { "↑" };
                let field = match level.target {
                    Target::Score if level.reverse => return "score ↑".to_owned(),
                    Target::Score => return "score".to_owned(),
                    Target::Doc => return format!("doc {arrow}"),
                    Target::Field(field) => &self.fields[field],
                };
                let mut parts = vec![escape_control(&field.name)];
                if !matches!(level.sort_type, SortType::String | SortType::Long) {
                    parts.push(type_name(level.sort_type).to_owned());
                }
                if level.selector != SortSelector::Min {
                    parts.push(selector_name(level.selector).to_owned());
                }
                match level.missing {
                    SortMissing::Default => {}
                    SortMissing::First => parts.push("first".to_owned()),
                    SortMissing::Last => parts.push("last".to_owned()),
                }
                parts.push(arrow.to_owned());
                parts.join(" ")
            })
            .collect();
        (!levels.is_empty()).then(|| levels.join(" · "))
    }
}

/// One line of the popup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Row {
    Similarity,
    K1,
    B,
    /// What a level sorts by.
    Target(usize),
    Type(usize),
    Selector(usize),
    Missing(usize),
    Order(usize),
}

/// The popup: a draft of the settings, applied with Enter.
#[derive(Debug)]
pub struct ScoringForm {
    draft: ScoringSettings,
    selected: usize,
}

impl ScoringForm {
    pub fn new(settings: &ScoringSettings) -> Self {
        Self {
            draft: settings.clone(),
            selected: 0,
        }
    }

    /// `k1` and `b` for BM25 only; a level's order once it sorts by something, its type and
    /// missing values for a field, its selector for a multi-valued field, and the second level
    /// once the first sorts by something.
    fn rows(&self) -> Vec<Row> {
        let mut rows = vec![Row::Similarity];
        if self.draft.kind == SimilarityKind::Bm25 {
            rows.extend([Row::K1, Row::B]);
        }
        for level in 0..LEVELS {
            rows.push(Row::Target(level));
            let Some(sort) = self.draft.levels[level] else {
                break;
            };
            if let Target::Field(field) = sort.target {
                let field = &self.draft.fields[field];
                if field.numeric() {
                    rows.push(Row::Type(level));
                }
                if field.selectors().len() > 1 {
                    rows.push(Row::Selector(level));
                }
                rows.push(Row::Missing(level));
            }
            rows.push(Row::Order(level));
        }
        rows
    }

    /// ↑↓ select a row; ←→, Space, `-`/`+` change it; digits and Backspace edit a number; Enter
    /// applies, Esc cancels.
    pub fn handle_key(&mut self, key: KeyCode) -> FormEvent<ScoringSettings> {
        let rows = self.rows();
        let row = rows[self.selected.min(rows.len() - 1)];
        match key {
            KeyCode::Enter => return FormEvent::Apply(self.draft.clone()),
            KeyCode::Esc => return FormEvent::Cancel,
            KeyCode::Up | KeyCode::Char('k') => self.selected = self.selected.saturating_sub(1),
            KeyCode::Down | KeyCode::Char('j') => {
                self.selected = (self.selected + 1).min(rows.len() - 1);
            }
            KeyCode::Home | KeyCode::Char('g') => self.selected = 0,
            KeyCode::End | KeyCode::Char('G') => self.selected = rows.len() - 1,
            KeyCode::Left | KeyCode::Char('h') | KeyCode::Char('-') => self.change(row, -1),
            KeyCode::Right | KeyCode::Char('l') | KeyCode::Char('+') | KeyCode::Char(' ') => {
                self.change(row, 1);
            }
            KeyCode::Char(c) if c.is_ascii_digit() => {
                if let Some((number, max)) = self.number(row) {
                    type_digit(number, c, max);
                }
            }
            KeyCode::Backspace => {
                if let Some((number, _)) = self.number(row) {
                    *number /= 10;
                }
            }
            _ => {}
        }
        FormEvent::None
    }

    fn number(&mut self, row: Row) -> Option<(&mut i32, i32)> {
        match row {
            Row::K1 => Some((&mut self.draft.k1, MAX_K1)),
            Row::B => Some((&mut self.draft.b, MAX_B)),
            _ => None,
        }
    }

    /// Steps a number by `step` hundredths times 5, or cycles a choice.
    fn change(&mut self, row: Row, step: i32) {
        if let Some((number, max)) = self.number(row) {
            *number = (*number + 5 * step).clamp(0, max);
            return;
        }
        let draft = &mut self.draft;
        match row {
            Row::Similarity => {
                let kinds = [
                    SimilarityKind::Bm25,
                    SimilarityKind::Classic,
                    SimilarityKind::Boolean,
                ];
                draft.kind = kinds[cycled(&kinds, draft.kind, step)];
            }
            Row::Target(level) => {
                // Nothing, the score, the document id, then each sortable field; a new field
                // starts from its guessed type, its smallest value and Lucene's missing values.
                let choices: Vec<Option<Target>> = [None, Some(Target::Score), Some(Target::Doc)]
                    .into_iter()
                    .chain((0..draft.fields.len()).map(|field| Some(Target::Field(field))))
                    .collect();
                let current = draft.levels[level].map(|level| level.target);
                let target = choices[cycled(&choices, current, step)];
                let reverse = draft.levels[level].is_some_and(|level| level.reverse);
                draft.levels[level] = target.map(|target| SortLevel {
                    target,
                    sort_type: match target {
                        Target::Field(field) if !draft.fields[field].numeric() => SortType::String,
                        _ => SortType::Long,
                    },
                    reverse,
                    selector: SortSelector::Min,
                    missing: SortMissing::Default,
                });
                if target.is_none() {
                    draft.levels[level..].fill(None);
                }
            }
            Row::Type(level) => {
                if let Some(sort) = &mut draft.levels[level]
                    && let Target::Field(field) = sort.target
                    && draft.fields[field].numeric()
                {
                    let types = [
                        SortType::Int,
                        SortType::Long,
                        SortType::Float,
                        SortType::Double,
                    ];
                    sort.sort_type = types[cycled(&types, sort.sort_type, step)];
                }
            }
            Row::Selector(level) => {
                if let Some(sort) = &mut draft.levels[level]
                    && let Target::Field(field) = sort.target
                {
                    let selectors = draft.fields[field].selectors();
                    sort.selector = selectors[cycled(selectors, sort.selector, step)];
                }
            }
            Row::Missing(level) => {
                if let Some(sort) = &mut draft.levels[level] {
                    let choices = [SortMissing::Default, SortMissing::First, SortMissing::Last];
                    sort.missing = choices[cycled(&choices, sort.missing, step)];
                }
            }
            Row::Order(level) => {
                if let Some(sort) = &mut draft.levels[level] {
                    sort.reverse = !sort.reverse;
                }
            }
            Row::K1 | Row::B => {}
        }
    }

    /// The keys for the footer while the popup is open.
    pub fn keys() -> &'static str {
        " ⏎ apply   Esc cancel   ↑↓ select   ←→ Space change   0-9 number"
    }

    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let rows = self.rows();
        let selected = self.selected.min(rows.len() - 1);
        let draft = &self.draft;
        let mut lines: Vec<Line> = Vec::new();
        let mut selected_line = 0;
        for (i, row_kind) in rows.iter().enumerate() {
            if *row_kind == Row::Target(0) {
                lines.push(Line::from(""));
            }
            let (label, value) = match *row_kind {
                Row::Similarity => (
                    "Similarity".to_owned(),
                    match draft.kind {
                        SimilarityKind::Bm25 => "BM25",
                        SimilarityKind::Classic => "classic TF-IDF",
                        SimilarityKind::Boolean => "boolean",
                    }
                    .to_owned(),
                ),
                Row::K1 => ("  k1".to_owned(), hundredths(draft.k1)),
                Row::B => ("  b".to_owned(), hundredths(draft.b)),
                Row::Target(level) => (
                    if level == 0 { "Sort by" } else { "Then by" }.to_owned(),
                    match draft.levels[level].map(|sort| sort.target) {
                        None if level == 0 => "best score (default)".to_owned(),
                        None => "nothing".to_owned(),
                        Some(Target::Score) => "score".to_owned(),
                        Some(Target::Doc) => "document id".to_owned(),
                        Some(Target::Field(field)) => escape_control(&draft.fields[field].name),
                    },
                ),
                Row::Type(level) => (
                    "  read as".to_owned(),
                    draft.levels[level]
                        .map_or("", |sort| type_name(sort.sort_type))
                        .to_owned(),
                ),
                Row::Selector(level) => (
                    "  value".to_owned(),
                    draft.levels[level]
                        .map_or("", |sort| selector_name(sort.selector))
                        .to_owned(),
                ),
                Row::Missing(level) => (
                    "  missing".to_owned(),
                    match draft.levels[level].map(|sort| sort.missing) {
                        Some(SortMissing::First) => "first",
                        Some(SortMissing::Last) => "last",
                        _ => "Lucene's default",
                    }
                    .to_owned(),
                ),
                Row::Order(level) => (
                    "  order".to_owned(),
                    match draft.levels[level] {
                        Some(sort) if sort.target == Target::Score => {
                            if sort.reverse {
                                "worst first"
                            } else {
                                "best first"
                            }
                        }
                        Some(sort) if sort.reverse => "descending",
                        _ => "ascending",
                    }
                    .to_owned(),
                ),
            };
            if i == selected {
                selected_line = lines.len();
            }
            lines.push(row(&label, &value, i == selected));
        }
        if draft.fields.is_empty() {
            lines.push(Line::from(""));
            lines.push(Line::from(Span::styled(
                "No field has doc values: score or document id only",
                dim(),
            )));
        } else if draft.levels.iter().flatten().any(
            |sort| matches!(sort.target, Target::Field(field) if draft.fields[field].numeric()),
        ) {
            lines.push(Line::from(""));
            lines.push(Line::from(Span::styled(
                "Number types, guessed: the index doesn't say",
                dim(),
            )));
        }
        render_popup(
            frame,
            area,
            " Similarity and sort ",
            lines,
            selected_line,
            52,
        );
    }
}

/// The index of the choice `step` places after `current` in `choices`, wrapping around.
fn cycled<T: PartialEq>(choices: &[T], current: T, step: i32) -> usize {
    let at = choices
        .iter()
        .position(|c| *c == current)
        .unwrap_or_default() as i32;
    (at + step).rem_euclid(choices.len() as i32) as usize
}

/// Hundredths as a decimal with two places: `120` is `1.20`.
fn hundredths(n: i32) -> String {
    format!("{}.{:02}", n / 100, n % 100)
}

fn selector_name(selector: SortSelector) -> &'static str {
    match selector {
        SortSelector::Min => "MIN",
        SortSelector::Max => "MAX",
        SortSelector::MiddleMin => "MIDDLE_MIN",
        SortSelector::MiddleMax => "MIDDLE_MAX",
    }
}

fn type_name(sort_type: SortType) -> &'static str {
    match sort_type {
        SortType::Int => "INT",
        SortType::Long => "LONG",
        SortType::Float => "FLOAT",
        SortType::Double => "DOUBLE",
        SortType::String => "STRING",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::index::IndexOptions;

    fn field(name: &str, doc_values_type: DocValuesType) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number: 0,
            index_options: IndexOptions::None,
            has_norms: false,
            has_payloads: false,
            has_term_vectors: false,
            doc_values_type,
            point_dimension_count: 0,
            point_num_bytes: 0,
            vector_dimension: 0,
            terms: None,
        }
    }

    /// `body` (no doc values), `checksum` (binary), `price` (numeric), `tags` (sorted set): the
    /// sortable fields are `price` and `tags`.
    fn settings() -> ScoringSettings {
        ScoringSettings::new(&[
            field("body", DocValuesType::None),
            field("checksum", DocValuesType::Binary),
            field("price", DocValuesType::Numeric),
            field("tags", DocValuesType::SortedSet),
        ])
    }

    fn press(form: &mut ScoringForm, keys: &[KeyCode]) {
        for key in keys {
            assert_eq!(form.handle_key(*key), FormEvent::None);
        }
    }

    fn applied(mut form: ScoringForm) -> ScoringSettings {
        match form.handle_key(KeyCode::Enter) {
            FormEvent::Apply(settings) => settings,
            other => panic!("expected Apply, got {other:?}"),
        }
    }

    #[test]
    fn starts_from_bm25_by_score() {
        let settings = settings();
        assert_eq!(
            settings.similarity(),
            Similarity {
                bm25: Some(Bm25Similarity { k1: 1.2, b: 0.75 }),
                ..Similarity::default()
            }
        );
        assert!(settings.sort_keys().is_empty());
        assert_eq!(settings.similarity_text(), None);
        assert_eq!(settings.sort_text(), None);
    }

    #[test]
    fn edits_bm25_in_hundredths() {
        let mut form = ScoringForm::new(&settings());
        // k1: 1.20 + 0.05, then typed as 0.9; b: typed 150 stays within 1.00.
        press(&mut form, &[KeyCode::Down, KeyCode::Right]);
        assert_eq!(form.draft.k1, 125);
        press(&mut form, &[KeyCode::Char('9'), KeyCode::Char('0')]);
        assert_eq!(form.draft.k1, 90);
        press(
            &mut form,
            &[
                KeyCode::Down,
                KeyCode::Backspace,
                KeyCode::Backspace,
                KeyCode::Char('1'),
                KeyCode::Char('5'),
                KeyCode::Char('0'),
            ],
        );
        assert_eq!(form.draft.b, 0);
        press(&mut form, &[KeyCode::Char('4')]);
        let settings = applied(form);
        assert_eq!(
            settings.similarity().bm25,
            Some(Bm25Similarity { k1: 0.9, b: 0.04 })
        );
        assert_eq!(
            settings.similarity_text().as_deref(),
            Some("BM25 k1 0.90 b 0.04")
        );
    }

    #[test]
    fn classic_and_boolean_have_no_parameters() {
        let mut form = ScoringForm::new(&settings());
        press(&mut form, &[KeyCode::Char(' ')]);
        assert_eq!(form.rows()[1], Row::Target(0));
        assert_eq!(form.draft.similarity().classic, Some(ClassicSimilarity {}));
        press(&mut form, &[KeyCode::Char(' ')]);
        assert_eq!(form.draft.similarity().boolean, Some(BooleanSimilarity {}));
        press(&mut form, &[KeyCode::Char(' ')]);
        assert_eq!(form.draft.kind, SimilarityKind::Bm25);
        press(&mut form, &[KeyCode::Left]);
        assert_eq!(applied(form).similarity_text().as_deref(), Some("boolean"));
    }

    #[test]
    fn sorts_by_up_to_two_fields_with_guessed_types() {
        let mut form = ScoringForm::new(&settings());
        // Similarity, k1, b, then "Sort by": score, doc, then price, guessed LONG, read as DOUBLE,
        // missing values last, descending.
        press(&mut form, &[KeyCode::Down, KeyCode::Down, KeyCode::Down]);
        assert_eq!(form.rows().len(), 4);
        press(&mut form, &[KeyCode::Right, KeyCode::Right, KeyCode::Right]);
        // A single-valued number: no selector.
        assert_eq!(
            form.rows()[4..],
            [Row::Type(0), Row::Missing(0), Row::Order(0), Row::Target(1)]
        );
        press(
            &mut form,
            &[
                KeyCode::Down,
                KeyCode::Right,
                KeyCode::Right,
                KeyCode::Down,
                KeyCode::Left,
                KeyCode::Down,
                KeyCode::Char(' '),
            ],
        );
        // "Then by": tags, a string, so its type doesn't show; by its largest value.
        press(
            &mut form,
            &[
                KeyCode::Down,
                KeyCode::Right,
                KeyCode::Right,
                KeyCode::Right,
                KeyCode::Right,
            ],
        );
        assert_eq!(
            form.rows()[8..],
            [Row::Selector(1), Row::Missing(1), Row::Order(1)]
        );
        press(&mut form, &[KeyCode::Down, KeyCode::Right]);
        let settings = applied(form);
        assert_eq!(
            settings.sort_keys(),
            vec![
                SortKey {
                    field: Some(FieldSort {
                        field: "price".to_owned(),
                        sort_type: SortType::Double,
                        reverse: true,
                        selector: SortSelector::Min,
                        missing: SortMissing::Last,
                    }),
                    ..SortKey::default()
                },
                SortKey {
                    field: Some(FieldSort {
                        field: "tags".to_owned(),
                        sort_type: SortType::String,
                        reverse: false,
                        selector: SortSelector::Max,
                        missing: SortMissing::Default,
                    }),
                    ..SortKey::default()
                },
            ]
        );
        assert_eq!(
            settings.sort_text().as_deref(),
            Some("price DOUBLE last ↓ · tags MAX ↑")
        );
    }

    #[test]
    fn sorts_by_the_score_and_the_document_id() {
        let mut form = ScoringForm::new(&settings());
        // Sort by the score, worst first, then by the document id, descending.
        press(
            &mut form,
            &[
                KeyCode::End,
                KeyCode::Right,
                KeyCode::Down,
                KeyCode::Char(' '),
                KeyCode::Down,
                KeyCode::Right,
                KeyCode::Right,
                KeyCode::Down,
                KeyCode::Char(' '),
            ],
        );
        let settings = applied(form);
        assert_eq!(
            settings.sort_keys(),
            vec![
                SortKey {
                    score: Some(ScoreSort { reverse: true }),
                    ..SortKey::default()
                },
                SortKey {
                    doc: Some(DocSort { reverse: true }),
                    ..SortKey::default()
                },
            ]
        );
        assert_eq!(settings.sort_text().as_deref(), Some("score ↑ · doc ↓"));
    }

    #[test]
    fn no_first_level_clears_the_second() {
        let mut form = ScoringForm::new(&settings());
        press(&mut form, &[KeyCode::End, KeyCode::Right]);
        // Then by: tags.
        press(&mut form, &[KeyCode::End, KeyCode::Right, KeyCode::Right]);
        assert!(form.draft.levels[1].is_some());
        // Sort by: back to score.
        press(&mut form, &[KeyCode::Home]);
        for _ in 0..3 {
            press(&mut form, &[KeyCode::Down]);
        }
        press(&mut form, &[KeyCode::Left]);
        assert_eq!(form.draft.levels, [None, None]);
        assert!(applied(form).sort_keys().is_empty());
    }

    #[test]
    fn an_index_without_doc_values_sorts_by_score_or_id() {
        let settings = ScoringSettings::new(&[field("body", DocValuesType::None)]);
        let mut form = ScoringForm::new(&settings);
        assert_eq!(
            form.rows(),
            vec![Row::Similarity, Row::K1, Row::B, Row::Target(0)]
        );
        // The score, the document id, and back to nothing.
        press(&mut form, &[KeyCode::End, KeyCode::Right, KeyCode::Right]);
        assert_eq!(
            form.draft.levels[0].map(|level| level.target),
            Some(Target::Doc)
        );
        press(&mut form, &[KeyCode::Left, KeyCode::Left]);
        assert_eq!(form.draft.levels[0], None);
    }
}
