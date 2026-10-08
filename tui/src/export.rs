//! The export prompt of the terms screen (ADR 0022): asks for the file that the terms of a field
//! are written to, and the delimiter. The server writes the file; the prompt only resolves the
//! path and asks before overwriting a file that exists.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use ratatui::Frame;
use ratatui::crossterm::event::{KeyCode, KeyEvent};
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Block, Clear, Paragraph, Wrap};

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
    Submit(StartExportTermsParams),
    /// Esc: close the prompt.
    Cancel,
}

pub struct ExportPrompt {
    field: String,
    input: LineInput,
    delimiter: TermDelimiter,
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
        Self {
            field: field.to_owned(),
            input: LineInput::new(&default_file(field, millis)),
            delimiter: TermDelimiter::default(),
            error: None,
            overwrite: None,
            busy: false,
            home,
            cwd,
        }
    }

    pub fn field(&self) -> &str {
        &self.field
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
            KeyCode::Tab => {
                self.delimiter = match self.delimiter {
                    TermDelimiter::Tab => TermDelimiter::Comma,
                    TermDelimiter::Comma => TermDelimiter::Space,
                    TermDelimiter::Space => TermDelimiter::Tab,
                };
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

    /// The export of the typed path, or first the question whether to overwrite it.
    fn submit(&mut self) -> ExportEvent {
        let path = self.resolved();
        let exists = fs::symlink_metadata(&path).is_ok();
        if exists && self.overwrite.as_ref() != Some(&path) {
            self.error = Some(format!("File exists: {path}. ⏎ again overwrites it."));
            self.overwrite = Some(path);
            return ExportEvent::None;
        }
        ExportEvent::Submit(StartExportTermsParams {
            field: self.field.clone(),
            path,
            delimiter: Some(self.delimiter),
            overwrite: Some(exists),
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
        let block = Block::bordered()
            .title(format!(" Export the terms of {} ", self.field))
            .title_bottom(
                Line::from(format!(
                    " Tab delimiter: {} ",
                    delimiter_name(self.delimiter)
                ))
                .right_aligned(),
            );
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
        let hint_text = if self.busy {
            "Exporting…"
        } else {
            "Enter export   Tab delimiter   Esc cancel"
        };
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
    use ratatui::crossterm::event::KeyModifiers;

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
            ExportEvent::Submit(StartExportTermsParams {
                field: "body".to_owned(),
                path: "/work/out/t.txt".to_owned(),
                delimiter: Some(TermDelimiter::Comma),
                overwrite: Some(false),
            })
        );

        clear(&mut prompt);
        typed(&mut prompt, "~/t.txt");
        prompt.handle_key(key(KeyCode::Tab));
        prompt.handle_key(key(KeyCode::Tab));
        let ExportEvent::Submit(params) = prompt.handle_key(key(KeyCode::Enter)) else {
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
        let ExportEvent::Submit(params) = prompt.handle_key(key(KeyCode::Enter)) else {
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
    fn ignores_keys_while_busy_and_shows_errors() {
        let mut prompt = ExportPrompt::new("body", None, None);
        prompt.set_busy();
        assert_eq!(prompt.handle_key(key(KeyCode::Esc)), ExportEvent::None);

        prompt.set_error("Not writing into the index directory /i".to_owned());
        assert_eq!(prompt.handle_key(key(KeyCode::Esc)), ExportEvent::Cancel);
    }
}
