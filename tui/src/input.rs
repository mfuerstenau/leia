//! A one-line text editor, shared by the open-index prompt and the search query: the cursor
//! moves by character, and Ctrl+A/E/U/K/W edit as in a shell.

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::text::Span;

/// What a key did to the input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Edit {
    /// The text changed.
    Changed,
    /// Only the cursor moved, or the key did nothing.
    Unchanged,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LineInput {
    text: String,
    /// Byte offset of the cursor in `text`, always on a char boundary.
    cursor: usize,
}

impl LineInput {
    /// The input with the cursor at the end.
    pub fn new(text: &str) -> Self {
        Self {
            text: text.to_owned(),
            cursor: text.len(),
        }
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    /// Replaces the text and puts the cursor at the end.
    pub fn set(&mut self, text: String) {
        self.cursor = text.len();
        self.text = text;
    }

    /// Moves the cursor or edits the text. Ctrl+W deletes back to `word_start(text before the
    /// cursor)`. Keys that don't edit, such as Enter, Esc and Tab, are left to the caller.
    pub fn handle_key(&mut self, key: KeyEvent, word_start: fn(&str) -> usize) -> Edit {
        let ctrl = key.modifiers.contains(KeyModifiers::CONTROL);
        let alt = key.modifiers.contains(KeyModifiers::ALT);
        match key.code {
            KeyCode::Left => self.cursor = self.previous_boundary(),
            KeyCode::Right => self.cursor = self.next_boundary(),
            KeyCode::Home => self.cursor = 0,
            KeyCode::End => self.cursor = self.text.len(),
            KeyCode::Char('a') if ctrl => self.cursor = 0,
            KeyCode::Char('e') if ctrl => self.cursor = self.text.len(),
            KeyCode::Backspace => return self.delete(self.previous_boundary()..self.cursor),
            KeyCode::Delete => return self.delete(self.cursor..self.next_boundary()),
            KeyCode::Char('u') if ctrl => return self.delete(0..self.cursor),
            KeyCode::Char('k') if ctrl => return self.delete(self.cursor..self.text.len()),
            KeyCode::Char('w') if ctrl => {
                return self.delete(word_start(&self.text[..self.cursor])..self.cursor);
            }
            KeyCode::Char(c) if !ctrl && !alt => {
                self.text.insert(self.cursor, c);
                self.cursor += c.len_utf8();
                return Edit::Changed;
            }
            _ => {}
        }
        Edit::Unchanged
    }

    fn delete(&mut self, range: std::ops::Range<usize>) -> Edit {
        if range.is_empty() {
            return Edit::Unchanged;
        }
        self.text.replace_range(range.clone(), "");
        self.cursor = range.start;
        Edit::Changed
    }

    fn previous_boundary(&self) -> usize {
        self.text[..self.cursor]
            .char_indices()
            .next_back()
            .map_or(0, |(i, _)| i)
    }

    fn next_boundary(&self) -> usize {
        self.text[self.cursor..]
            .chars()
            .next()
            .map_or(self.cursor, |c| self.cursor + c.len_utf8())
    }

    /// The part of the text that fits into `width` cells with the cursor visible, and the
    /// cursor's column in it. Long text scrolls so that the cursor stays in the last cell.
    pub fn visible(&self, width: u16) -> (&str, u16) {
        let width = usize::from(width.max(1));
        let before = &self.text[..self.cursor];
        let mut start = 0;
        while Span::raw(&before[start..]).width() >= width {
            start += before[start..].chars().next().map_or(1, char::len_utf8);
        }
        (
            &self.text[start..],
            Span::raw(&before[start..]).width() as u16,
        )
    }
}

/// Where the word before the cursor starts, for Ctrl+W: after the last whitespace, ignoring
/// whitespace right before the cursor.
pub fn word_start(before: &str) -> usize {
    let trimmed = before.trim_end();
    trimmed
        .char_indices()
        .rev()
        .find(|(_, c)| c.is_whitespace())
        .map_or(0, |(i, c)| i + c.len_utf8())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn ctrl(c: char) -> KeyEvent {
        KeyEvent::new(KeyCode::Char(c), KeyModifiers::CONTROL)
    }

    #[test]
    fn reports_whether_the_text_changed() {
        let mut input = LineInput::new("ab");

        assert_eq!(
            input.handle_key(key(KeyCode::Left), word_start),
            Edit::Unchanged
        );
        assert_eq!(
            input.handle_key(key(KeyCode::Char('é')), word_start),
            Edit::Changed
        );
        assert_eq!(input.text(), "aéb");
        assert_eq!(
            input.handle_key(key(KeyCode::Delete), word_start),
            Edit::Changed
        );
        assert_eq!(
            input.handle_key(key(KeyCode::Delete), word_start),
            Edit::Unchanged
        );
        assert_eq!(
            input.handle_key(key(KeyCode::Enter), word_start),
            Edit::Unchanged
        );
        assert_eq!(input.text(), "aé");
    }

    #[test]
    fn ctrl_w_deletes_the_word_before_the_cursor() {
        let mut input = LineInput::new("title:fox AND\u{a0}body:qu*  ");

        input.handle_key(ctrl('w'), word_start);
        assert_eq!(input.text(), "title:fox AND\u{a0}");
        input.handle_key(ctrl('w'), word_start);
        assert_eq!(input.text(), "title:fox ");
        input.handle_key(ctrl('a'), word_start);
        input.handle_key(ctrl('k'), word_start);
        assert_eq!(input.text(), "");
    }

    #[test]
    fn visible_text_keeps_the_cursor_in_view() {
        let mut input = LineInput::new("abcdefgh");
        assert_eq!(input.visible(4), ("fgh", 3));
        assert_eq!(input.visible(20), ("abcdefgh", 8));

        input.handle_key(key(KeyCode::Home), word_start);
        assert_eq!(input.visible(4), ("abcdefgh", 0));

        // Wide characters take two cells each.
        let wide = LineInput::new("日本語");
        assert_eq!(wide.visible(4), ("語", 2));
    }
}
