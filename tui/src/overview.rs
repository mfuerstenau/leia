//! The overview screen: what `getIndexStats` reports about the open index, with a scrollable
//! table of its fields.

use ratatui::Frame;
use ratatui::crossterm::event::KeyCode;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Cell, Paragraph, Row, Table, TableState};

use crate::protocol::index::{DocValuesType, GetIndexStatsResult, IndexField, IndexOptions};

/// Rows that PageUp and PageDown move.
const PAGE: u16 = 10;

pub struct Overview {
    stats: GetIndexStatsResult,
    /// The text of every field's cells, formatted once.
    field_rows: Vec<[String; COLUMNS.len()]>,
    /// The widest cell of each column, header included.
    column_widths: [u16; COLUMNS.len()],
    fields: TableState,
}

impl Overview {
    pub fn new(stats: GetIndexStatsResult) -> Self {
        let field_rows: Vec<_> = stats.fields.iter().map(field_cells).collect();
        let column_widths = std::array::from_fn(|column| {
            field_rows
                .iter()
                .map(|row| row[column].as_str())
                .chain([COLUMNS[column].title])
                .map(|text| Line::from(text).width())
                .max()
                .unwrap_or_default() as u16
        });
        let selected = (!stats.fields.is_empty()).then_some(0);
        Self {
            stats,
            field_rows,
            column_widths,
            fields: TableState::new().with_selected(selected),
        }
    }

    /// The directory of the index.
    pub fn path(&self) -> &str {
        &self.stats.path
    }

    /// Moves the field selection. Returns whether the key was used.
    pub fn handle_key(&mut self, key: KeyCode) -> bool {
        match key {
            KeyCode::Down | KeyCode::Char('j') => self.fields.scroll_down_by(1),
            KeyCode::Up | KeyCode::Char('k') => self.fields.scroll_up_by(1),
            KeyCode::PageDown => self.fields.scroll_down_by(PAGE),
            KeyCode::PageUp => self.fields.scroll_up_by(PAGE),
            KeyCode::Home | KeyCode::Char('g') => self.fields.select_first(),
            KeyCode::End | KeyCode::Char('G') => self.fields.select_last(),
            _ => return false,
        }
        true
    }

    pub fn render(&mut self, frame: &mut Frame, area: Rect) {
        let summary = summary_lines(&self.stats);
        let [top, bottom] = Layout::vertical([
            // The lines plus the block's top and bottom border.
            Constraint::Length(summary.len() as u16 + 2),
            Constraint::Fill(1),
        ])
        .areas(area);
        frame.render_widget(
            Paragraph::new(summary).block(Block::bordered().title(" Index ")),
            top,
        );

        // Inside the block's left and right border.
        let shown = shown_columns(&self.column_widths, bottom.width.saturating_sub(2));
        let header = Row::new(
            COLUMNS[..shown]
                .iter()
                .map(|column| cell(column, column.title)),
        )
        .style(Style::new().add_modifier(Modifier::BOLD));
        let rows = self.field_rows.iter().map(|texts| {
            Row::new(
                COLUMNS[..shown]
                    .iter()
                    .zip(texts)
                    .map(|(column, text)| cell(column, text)),
            )
        });
        // Every column gets the width of its widest cell, and the name the rest of the line.
        let widths = self.column_widths[..shown]
            .iter()
            .enumerate()
            .map(|(i, &width)| {
                if i == NAME_COLUMN {
                    Constraint::Min(width.min(NAME_MIN_WIDTH))
                } else {
                    Constraint::Length(width)
                }
            });
        let table = Table::new(rows, widths)
            .header(header)
            .column_spacing(COLUMN_SPACING)
            .row_highlight_style(Style::new().add_modifier(Modifier::REVERSED))
            .block(Block::bordered().title(format!(" Fields ({}) ", self.stats.fields.len())));
        frame.render_stateful_widget(table, bottom, &mut self.fields);
    }
}

fn summary_lines(stats: &GetIndexStatsResult) -> Vec<Line<'static>> {
    let deleted = stats.max_doc - stats.num_docs;
    let merged = if stats.segment_count == 1 {
        " (fully merged)"
    } else {
        ""
    };
    let mut lucene = format!(
        "created by {}, commit written by {}",
        stats.created_version_major, stats.commit_lucene_version
    );
    if let Some(oldest) = &stats.min_segment_lucene_version {
        lucene.push_str(&format!(", oldest segment by {oldest}"));
    }
    let user_data = if stats.user_data.is_empty() {
        "-".to_owned()
    } else {
        stats
            .user_data
            .iter()
            .map(|(key, value)| format!("{key}={value}"))
            .collect::<Vec<_>>()
            .join(", ")
    };
    [
        ("Path", stats.path.clone()),
        ("Directory", stats.directory_impl.clone()),
        (
            "Commit",
            format!(
                "{} (generation {}, version {})",
                stats.segments_file, stats.generation, stats.version
            ),
        ),
        ("Segments", format!("{}{merged}", stats.segment_count)),
        (
            "Documents",
            format!(
                "{} live, {deleted} deleted, {} max",
                stats.num_docs, stats.max_doc
            ),
        ),
        ("Lucene", lucene),
        ("User data", user_data),
    ]
    .into_iter()
    .map(|(label, value)| {
        Line::from(vec![
            Span::styled(
                format!("{label:<10} "),
                Style::new().add_modifier(Modifier::BOLD),
            ),
            Span::raw(value),
        ])
    })
    .collect()
}

struct Column {
    title: &'static str,
    right_aligned: bool,
}

const fn column(title: &'static str, right_aligned: bool) -> Column {
    Column {
        title,
        right_aligned,
    }
}

const COLUMNS: [Column; 10] = [
    column("#", true),
    column("Name", false),
    column("Postings", false),
    column("Flags", false),
    column("Doc values", false),
    column("Points", false),
    column("Vector", false),
    column("Docs", true),
    column("Σ doc freq", true),
    column("Σ term freq", true),
];

const NAME_COLUMN: usize = 1;
const NAME_MIN_WIDTH: u16 = 16;
const COLUMN_SPACING: u16 = 2;

/// How many columns, from the left, fit into `available` cells. A column is shown whole or not
/// at all, so on a narrow terminal the columns on the right go first. The number and the name
/// are always shown; the name counts with at most `NAME_MIN_WIDTH`.
fn shown_columns(widths: &[u16], available: u16) -> usize {
    let mut used = 0;
    for (i, &width) in widths.iter().enumerate() {
        let width = if i == NAME_COLUMN {
            width.min(NAME_MIN_WIDTH)
        } else {
            width
        };
        let spacing = if i == 0 { 0 } else { COLUMN_SPACING };
        used += spacing + width;
        if used > available && i > NAME_COLUMN {
            return i;
        }
    }
    widths.len()
}

fn cell<'a>(column: &Column, text: &'a str) -> Cell<'a> {
    let line = Line::from(text);
    Cell::from(if column.right_aligned {
        line.right_aligned()
    } else {
        line
    })
}

fn field_cells(field: &IndexField) -> [String; COLUMNS.len()] {
    let points = if field.point_dimension_count == 0 {
        "-".to_owned()
    } else {
        format!("{}×{}B", field.point_dimension_count, field.point_num_bytes)
    };
    let vector = if field.vector_dimension == 0 {
        "-".to_owned()
    } else {
        format!("{}d", field.vector_dimension)
    };
    let (docs, sum_doc_freq, sum_total_term_freq) = match &field.terms {
        Some(terms) => (
            terms.doc_count.to_string(),
            terms.sum_doc_freq.to_string(),
            terms.sum_total_term_freq.to_string(),
        ),
        None => ("-".to_owned(), "-".to_owned(), "-".to_owned()),
    };
    [
        field.number.to_string(),
        field.name.clone(),
        postings(field.index_options).to_owned(),
        flags(field),
        doc_values(field.doc_values_type).to_owned(),
        points,
        vector,
        docs,
        sum_doc_freq,
        sum_total_term_freq,
    ]
}

fn postings(options: IndexOptions) -> &'static str {
    match options {
        IndexOptions::None => "-",
        IndexOptions::Docs => "docs",
        IndexOptions::DocsAndFreqs => "freqs",
        IndexOptions::DocsAndFreqsAndPositions => "positions",
        IndexOptions::DocsAndFreqsAndPositionsAndOffsets => "offsets",
        IndexOptions::DocsAndCustomFreqs => "custom freqs",
    }
}

/// `N` norms, `V` term vectors, `P` payloads; `-` where absent.
fn flags(field: &IndexField) -> String {
    [
        (field.has_norms, 'N'),
        (field.has_term_vectors, 'V'),
        (field.has_payloads, 'P'),
    ]
    .into_iter()
    .map(|(set, flag)| if set { flag } else { '-' })
    .collect()
}

fn doc_values(doc_values_type: DocValuesType) -> &'static str {
    match doc_values_type {
        DocValuesType::None => "-",
        DocValuesType::Numeric => "numeric",
        DocValuesType::Binary => "binary",
        DocValuesType::Sorted => "sorted",
        DocValuesType::SortedNumeric => "sorted numeric",
        DocValuesType::SortedSet => "sorted set",
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    use super::*;
    use crate::protocol::index::TermStats;

    fn field(name: &str, number: i32) -> IndexField {
        IndexField {
            name: name.to_owned(),
            number,
            index_options: IndexOptions::None,
            has_norms: false,
            has_payloads: false,
            has_term_vectors: false,
            doc_values_type: DocValuesType::None,
            point_dimension_count: 0,
            point_num_bytes: 0,
            vector_dimension: 0,
            terms: None,
        }
    }

    fn stats(fields: Vec<IndexField>) -> GetIndexStatsResult {
        GetIndexStatsResult {
            path: "/data/products".to_owned(),
            directory_impl: "MMapDirectory".to_owned(),
            segments_file: "segments_2".to_owned(),
            generation: 2,
            version: 7,
            segment_count: 1,
            num_docs: 2,
            max_doc: 3,
            created_version_major: 9,
            commit_lucene_version: "9.12.3".to_owned(),
            min_segment_lucene_version: Some("9.12.3".to_owned()),
            user_data: BTreeMap::from([("writer".to_owned(), "test".to_owned())]),
            fields,
        }
    }

    fn render(overview: &mut Overview, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| overview.render(frame, frame.area()))
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

    #[test]
    fn shows_summary_and_fields() {
        let body = IndexField {
            index_options: IndexOptions::DocsAndFreqsAndPositions,
            has_norms: true,
            has_term_vectors: true,
            terms: Some(TermStats {
                doc_count: 3,
                sum_doc_freq: 6,
                sum_total_term_freq: 9,
            }),
            ..field("body", 1)
        };
        let price = IndexField {
            doc_values_type: DocValuesType::SortedNumeric,
            point_dimension_count: 1,
            point_num_bytes: 4,
            ..field("price", 2)
        };
        let mut overview = Overview::new(stats(vec![field("id", 0), body, price]));

        let screen = render(&mut overview, 120, 20);

        for expected in [
            "Path       /data/products",
            "Commit     segments_2 (generation 2, version 7)",
            "Segments   1 (fully merged)",
            "Documents  2 live, 1 deleted, 3 max",
            "Lucene     created by 9, commit written by 9.12.3, oldest segment by 9.12.3",
            "User data  writer=test",
            "Fields (3)",
        ] {
            assert!(screen.contains(expected), "{expected:?} in\n{screen}");
        }
        let body_row = screen.lines().find(|line| line.contains("body")).unwrap();
        for expected in ["positions", "NV-", "3", "6", "9"] {
            assert!(body_row.contains(expected), "{expected:?} in {body_row:?}");
        }
        let price_row = screen.lines().find(|line| line.contains("price")).unwrap();
        assert!(price_row.contains("sorted numeric"), "{price_row:?}");
        assert!(price_row.contains("1×4B"), "{price_row:?}");
    }

    #[test]
    fn moves_the_selection_within_the_fields() {
        let mut overview = Overview::new(stats((0..30).map(|i| field("f", i)).collect()));
        render(&mut overview, 120, 30);
        assert_eq!(overview.fields.selected(), Some(0));

        assert!(overview.handle_key(KeyCode::Down));
        assert_eq!(overview.fields.selected(), Some(1));
        overview.handle_key(KeyCode::Up);
        overview.handle_key(KeyCode::Up);
        assert_eq!(overview.fields.selected(), Some(0));
        overview.handle_key(KeyCode::PageDown);
        assert_eq!(overview.fields.selected(), Some(10));
        overview.handle_key(KeyCode::End);
        // The table clamps the selection to the last row when it renders.
        render(&mut overview, 120, 30);
        assert_eq!(overview.fields.selected(), Some(29));
        assert!(!overview.handle_key(KeyCode::Char('x')));
    }

    #[test]
    fn drops_whole_columns_from_the_right_when_narrow() {
        // "#" 1, "Name" 20 (counts as 16), then 9 and 5, with 2 cells between columns.
        let widths = [1, 20, 9, 5];
        assert_eq!(shown_columns(&widths, 40), 4);
        assert_eq!(shown_columns(&widths, 1 + 2 + 16 + 2 + 9 + 2 + 5), 4);
        assert_eq!(shown_columns(&widths, 1 + 2 + 16 + 2 + 9 + 2 + 4), 3);
        assert_eq!(shown_columns(&widths, 1 + 2 + 16 + 2 + 8), 2);
        // The number and the name stay, however narrow.
        assert_eq!(shown_columns(&widths, 5), 2);
    }

    #[test]
    fn hides_columns_that_do_not_fit() {
        let mut overview = Overview::new(stats(vec![field("id", 0)]));

        let screen = render(&mut overview, 60, 15);

        assert!(screen.contains("Postings"), "{screen}");
        assert!(!screen.contains("Σ term freq"), "{screen}");
    }

    #[test]
    fn handles_index_without_fields() {
        let mut overview = Overview::new(stats(Vec::new()));

        let screen = render(&mut overview, 80, 15);

        assert!(screen.contains("Fields (0)"), "{screen}");
        assert_eq!(overview.fields.selected(), None);
    }
}
