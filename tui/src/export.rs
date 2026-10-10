//! The export prompts: of the terms screen (ADR 0022), for the file that the terms of a field are
//! written to and the delimiter, and of the documents screen (ADR 0030), for the file that a
//! document is written to as JSON and whether its indexed terms are rebuilt. The server writes
//! the terms; the prompt only resolves the path and asks before overwriting a file that exists.
//! The TUI writes the document, so its prompt checks the path first (rule 1).

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Block, Clear, Paragraph, Wrap};

use crate::doc_export::{check_target, default_file as document_file};
use crate::input::{Edit, LineInput};
use crate::prompt::{component_start, expand_home};
use crate::protocol::terms::{StartExportTermsParams, TermDelimiter};
use crate::text::utc_parts;

/// The popup is at most this wide, borders included.
const MAX_WIDTH: u16 = 100;

/// What a key did to the prompt.
#[derive(Debug, PartialEq, Eq)]
pub enum ExportEvent {
    /// Nothing for the caller to do.
    None,
    /// Start the export.
    Submit(ExportRequest),
    /// Esc: close the prompt.
    Cancel,
}

/// The export a prompt asks for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExportRequest {
    /// The terms of a field, written by the server.
    Terms(StartExportTermsParams),
    /// A document as JSON, written by the TUI (ADR 0030).
    Document {
        doc_id: i32,
        /// Whether its indexed terms are rebuilt.
        indexed_terms: bool,
        target: ExportTarget,
    },
}

/// Where a document export goes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExportTarget {
    /// This absolute file, which exists if `overwrite`.
    File { path: String, overwrite: bool },
    /// The clipboard, through OSC 52.
    Clipboard,
}

/// What is exported, and the prompt's choices for it.
enum Kind {
    Terms {
        field: String,
        delimiter: TermDelimiter,
    },
    Document {
        doc_id: i32,
        indexed_terms: bool,
        /// The index directories of the open path, opened or skipped, which it never writes to.
        index_dirs: Vec<String>,
    },
}

pub struct ExportPrompt {
    kind: Kind,
    input: LineInput,
    /// Why the last export couldn't start, or that the file exists.
    error: Option<String>,
    /// The file that the prompt said exists: Enter on it again overwrites it.
    overwrite: Option<String>,
    /// Set while the export is being started.
    busy: bool,
    /// The directory that `~` stands for.
    home: Option<PathBuf>,
    /// The directory a relative path is resolved against.
    cwd: Option<PathBuf>,
}

impl ExportPrompt {
    /// The prompt for the terms of `field`, prefilled with `terms_<field>_<UTC time>.out`.
    pub fn new(field: &str, home: Option<PathBuf>, cwd: Option<PathBuf>) -> Self {
        let millis = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |since| i64::try_from(since.as_millis()).unwrap_or(0));
        let kind = Kind::Terms {
            field: field.to_owned(),
            delimiter: TermDelimiter::default(),
        };
        Self::with_kind(kind, &default_file(field, millis), home, cwd)
    }

    /// The prompt for document `doc_id`, prefilled with `doc-<id>.json`, with its indexed terms
    /// rebuilt. It never writes into `index_dirs`.
    pub fn document(
        doc_id: i32,
        index_dirs: Vec<String>,
        home: Option<PathBuf>,
        cwd: Option<PathBuf>,
    ) -> Self {
        let kind = Kind::Document {
            doc_id,
            indexed_terms: true,
            index_dirs,
        };
        Self::with_kind(kind, &document_file(doc_id), home, cwd)
    }

    fn with_kind(kind: Kind, file: &str, home: Option<PathBuf>, cwd: Option<PathBuf>) -> Self {
        Self {
            kind,
            input: LineInput::new(file),
            error: None,
            overwrite: None,
            busy: false,
            home,
            cwd,
        }
    }

    /// The field whose terms are exported; `None` for a document.
    pub fn field(&self) -> Option<&str> {
        match &self.kind {
            Kind::Terms { field, .. } => Some(field),
            Kind::Document { .. } => None,
        }
    }

    pub fn set_busy(&mut self) {
        self.busy = true;
    }

    /// Shows why the export couldn't start; the prompt can be edited again.
    pub fn set_error(&mut self, error: String) {
        self.busy = false;
        self.overwrite = None;
        self.error = Some(error);
    }

    pub fn handle_key(&mut self, key: KeyEvent) -> ExportEvent {
        if self.busy {
            return ExportEvent::None;
        }
        match key.code {
            KeyCode::Esc => return ExportEvent::Cancel,
            KeyCode::Enter if !self.input.text().trim().is_empty() => return self.submit(),
            KeyCode::Char('y') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                if let Kind::Document {
                    doc_id,
                    indexed_terms,
                    ..
                } = self.kind
                {
                    return ExportEvent::Submit(ExportRequest::Document {
                        doc_id,
                        indexed_terms,
                        target: ExportTarget::Clipboard,
                    });
                }
                return ExportEvent::None;
            }
            KeyCode::Tab => {
                match &mut self.kind {
                    Kind::Terms { delimiter, .. } => {
                        *delimiter = match delimiter {
                            TermDelimiter::Tab => TermDelimiter::Comma,
                            TermDelimiter::Comma => TermDelimiter::Space,
                            TermDelimiter::Space => TermDelimiter::Tab,
                        };
                    }
                    Kind::Document { indexed_terms, .. } => *indexed_terms = !*indexed_terms,
                }
                return ExportEvent::None;
            }
            _ => {}
        }
        if self.input.handle_key(key, component_start) == Edit::Changed {
            self.error = None;
            self.overwrite = None;
        }
        ExportEvent::None
    }

    /// The export of the typed path, or first the question whether to overwrite it. A document
    /// isn't written where it mustn't be.
    fn submit(&mut self) -> ExportEvent {
        let path = self.resolved();
        if let Kind::Document { index_dirs, .. } = &self.kind
            && let Err(error) = check_target(Path::new(&path), true, index_dirs)
        {
            self.overwrite = None;
            self.error = Some(error);
            return ExportEvent::None;
        }
        let exists = fs::symlink_metadata(&path).is_ok();
        if exists && self.overwrite.as_ref() != Some(&path) {
            self.error = Some(format!("File exists: {path}. ⏎ again overwrites it."));
            self.overwrite = Some(path);
            return ExportEvent::None;
        }
        ExportEvent::Submit(match &self.kind {
            Kind::Terms { field, delimiter } => ExportRequest::Terms(StartExportTermsParams {
                field: field.clone(),
                path,
                delimiter: Some(*delimiter),
                overwrite: Some(exists),
            }),
            Kind::Document {
                doc_id,
                indexed_terms,
                ..
            } => ExportRequest::Document {
                doc_id: *doc_id,
                indexed_terms: *indexed_terms,
                target: ExportTarget::File {
                    path,
                    overwrite: exists,
                },
            },
        })
    }

    /// The typed path with `~` expanded, against the working directory if it is relative.
    fn resolved(&self) -> String {
        let typed = expand_home(self.input.text().trim(), self.home.as_deref());
        match &self.cwd {
            Some(cwd) if Path::new(&typed).is_relative() => cwd.join(&typed).display().to_string(),
            _ => typed,
        }
    }

    /// Draws the popup, centered in `area`.
    pub fn render(&self, frame: &mut Frame, area: Rect) {
        let width = area.width.saturating_sub(4).min(MAX_WIDTH);
        let inner_width = width.saturating_sub(2).max(1);
        let error = self.error.as_deref().map(|message| {
            let color = if self.overwrite.is_some() {
                Color::Yellow
            } else {
                Color::Red
            };
            Paragraph::new(message)
                .style(Style::new().fg(color))
                .wrap(Wrap { trim: false })
        });
        let error_lines = error.as_ref().map_or(0, |error| {
            u16::try_from(error.line_count(inner_width)).unwrap_or(u16::MAX)
        });
        // Input, error, hint, and the top and bottom border.
        let height = 1u16
            .saturating_add(error_lines)
            .saturating_add(1 + 2)
            .min(area.height);
        let popup = area.centered(Constraint::Length(width), Constraint::Length(height));
        frame.render_widget(Clear, popup);
        let (title, choice, keys) = match &self.kind {
            Kind::Terms { field, delimiter } => (
                format!(" Export the terms of {field} "),
                format!(" Tab delimiter: {} ", delimiter_name(*delimiter)),
                "Enter export   Tab delimiter   Esc cancel",
            ),
            Kind::Document {
                doc_id,
                indexed_terms,
                ..
            } => (
                format!(" Export document {doc_id} as JSON "),
                format!(
                    " Tab indexed terms: {} ",
                    if *indexed_terms { "on" } else { "off" }
                ),
                "Enter export   Ctrl+Y copy   Tab indexed terms   Esc cancel",
            ),
        };
        let block = Block::bordered()
            .title(title)
            .title_bottom(Line::from(choice).right_aligned());
        let inner = block.inner(popup);
        frame.render_widget(block, popup);
        let [input, error_area, hint] = Layout::vertical([
            Constraint::Length(1),
            Constraint::Fill(1),
            Constraint::Length(1),
        ])
        .areas(inner);

        let (shown, cursor_x) = self.input.visible(input.width);
        frame.render_widget(Line::from(shown), input);
        if !self.busy {
            frame.set_cursor_position((input.x + cursor_x, input.y));
        }
        if let Some(paragraph) = error {
            frame.render_widget(paragraph, error_area);
        }
        let hint_text = if self.busy { "Exporting…" } else { keys };
        frame.render_widget(
            Line::from(hint_text).style(Style::new().add_modifier(Modifier::DIM)),
            hint,
        );
    }
}

pub fn delimiter_name(delimiter: TermDelimiter) -> &'static str {
    match delimiter {
        TermDelimiter::Tab => "tab",
        TermDelimiter::Comma => "comma",
        TermDelimiter::Space => "space",
    }
}

/// `terms_<field>_<UTC time>.out`, as Luke names it, with every character of the field that
/// doesn't belong in a file name as `_`.
fn default_file(field: &str, millis: i64) -> String {
    let field: String = field
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, '-' | '.') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let [year, month, day, hour, minute, second] = utc_parts(millis);
    format!("terms_{field}_{year:04}{month:02}{day:02}T{hour:02}{minute:02}{second:02}Z.out")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn typed(prompt: &mut ExportPrompt, text: &str) {
        for c in text.chars() {
            prompt.handle_key(key(KeyCode::Char(c)));
        }
    }

    fn clear(prompt: &mut ExportPrompt) {
        prompt.handle_key(KeyEvent::new(KeyCode::Char('u'), KeyModifiers::CONTROL));
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("leia-export-{name}-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn names_the_file_after_the_field_and_the_time() {
        assert_eq!(
            default_file("body", 1_791_369_000_000),
            "terms_body_20261007T103000Z.out"
        );
        assert_eq!(default_file("a/b c", 0), "terms_a_b_c_19700101T000000Z.out");
    }

    #[test]
    fn resolves_the_path_and_cycles_the_delimiter() {
        let mut prompt = ExportPrompt::new(
            "body",
            Some(PathBuf::from("/home/u")),
            Some(PathBuf::from("/work")),
        );
        assert!(prompt.input.text().starts_with("terms_body_"));
        clear(&mut prompt);
        typed(&mut prompt, "out/t.txt");
        prompt.handle_key(key(KeyCode::Tab));

        assert_eq!(
            prompt.handle_key(key(KeyCode::Enter)),
            ExportEvent::Submit(ExportRequest::Terms(StartExportTermsParams {
                field: "body".to_owned(),
                path: "/work/out/t.txt".to_owned(),
                delimiter: Some(TermDelimiter::Comma),
                overwrite: Some(false),
            }))
        );

        clear(&mut prompt);
        typed(&mut prompt, "~/t.txt");
        prompt.handle_key(key(KeyCode::Tab));
        prompt.handle_key(key(KeyCode::Tab));
        let ExportEvent::Submit(ExportRequest::Terms(params)) =
            prompt.handle_key(key(KeyCode::Enter))
        else {
            panic!("no submit");
        };
        assert_eq!(params.path, "/home/u/t.txt");
        assert_eq!(params.delimiter, Some(TermDelimiter::Tab));
    }

    #[test]
    fn asks_before_overwriting_a_file() {
        let dir = temp_dir("overwrite");
        fs::write(dir.join("t.out"), "old").unwrap();
        let mut prompt = ExportPrompt::new("body", None, Some(dir.clone()));
        clear(&mut prompt);
        typed(&mut prompt, "t.out");

        assert_eq!(prompt.handle_key(key(KeyCode::Enter)), ExportEvent::None);
        let path = dir.join("t.out").display().to_string();
        assert_eq!(
            prompt.error.as_deref(),
            Some(format!("File exists: {path}. ⏎ again overwrites it.").as_str())
        );
        let ExportEvent::Submit(ExportRequest::Terms(params)) =
            prompt.handle_key(key(KeyCode::Enter))
        else {
            panic!("no submit");
        };
        assert_eq!(params.overwrite, Some(true));

        // Editing the path asks again.
        typed(&mut prompt, "x");
        prompt.handle_key(key(KeyCode::Backspace));
        assert_eq!(prompt.handle_key(key(KeyCode::Enter)), ExportEvent::None);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn exports_a_document_to_a_file_or_the_clipboard() {
        let dir = temp_dir("document");
        let mut prompt = ExportPrompt::document(7, Vec::new(), None, Some(dir.clone()));
        assert_eq!(prompt.input.text(), "doc-7.json");
        assert_eq!(prompt.field(), None);
        assert_eq!(
            prompt.handle_key(key(KeyCode::Enter)),
            ExportEvent::Submit(ExportRequest::Document {
                doc_id: 7,
                indexed_terms: true,
                target: ExportTarget::File {
                    path: dir.join("doc-7.json").display().to_string(),
                    overwrite: false,
                },
            })
        );

        prompt.handle_key(key(KeyCode::Tab));
        assert_eq!(
            prompt.handle_key(KeyEvent::new(KeyCode::Char('y'), KeyModifiers::CONTROL)),
            ExportEvent::Submit(ExportRequest::Document {
                doc_id: 7,
                indexed_terms: false,
                target: ExportTarget::Clipboard,
            })
        );
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn does_not_write_a_document_into_an_index_directory() {
        let dir = temp_dir("index");
        let mut prompt =
            ExportPrompt::document(1, vec![dir.display().to_string()], None, Some(dir.clone()));
        assert_eq!(prompt.handle_key(key(KeyCode::Enter)), ExportEvent::None);
        assert_eq!(
            prompt.error,
            Some(format!(
                "Not writing into the index directory {}: leia never changes an index",
                dir.display()
            ))
        );
        // The terms prompt has no Ctrl+Y.
        let mut terms = ExportPrompt::new("body", None, None);
        assert_eq!(
            terms.handle_key(KeyEvent::new(KeyCode::Char('y'), KeyModifiers::CONTROL)),
            ExportEvent::None
        );
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn ignores_keys_while_busy_and_shows_errors() {
        let mut prompt = ExportPrompt::new("body", None, None);
        prompt.set_busy();
        assert_eq!(prompt.handle_key(key(KeyCode::Esc)), ExportEvent::None);

        prompt.set_error("Not writing into the index directory /i".to_owned());
        assert_eq!(prompt.handle_key(key(KeyCode::Esc)), ExportEvent::Cancel);
    }
}
