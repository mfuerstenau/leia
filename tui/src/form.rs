//! What the search screen's popups share: how a key ends them, how a number row takes digits, and
//! how they are drawn.

use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::style::{Modifier, Style};
use ratatui::text::Line;
use ratatui::widgets::{Block, Clear, Paragraph};

/// What a key did to a popup that edits a draft of `T`.
#[derive(Debug, PartialEq, Eq)]
pub enum FormEvent<T> {
    None,
    Apply(T),
    Cancel,
}

/// A digit typed into a number: appended while the number stays within `max`, otherwise the
/// number starts again from the digit.
pub fn type_digit(number: &mut i32, digit: char, max: i32) {
    let digit = digit.to_digit(10).map_or(0, |d| d as i32);
    let typed = *number * 10 + digit;
    *number = if typed <= max { typed } else { digit.min(max) };
}

/// A popup row: `label`, padded, then `value`; reversed while selected.
pub fn row(label: &str, value: &str, selected: bool) -> Line<'static> {
    let line = Line::from(format!("{label:<22.22} {value}"));
    if selected {
        line.style(Style::new().add_modifier(Modifier::REVERSED))
    } else {
        line
    }
}

/// Draws `lines` in a bordered popup in the middle of `area`, at most `width` wide, scrolled so
/// that line `selected_line` shows.
pub fn render_popup(
    frame: &mut Frame,
    area: Rect,
    title: &str,
    lines: Vec<Line>,
    selected_line: usize,
    width: u16,
) {
    let width = area.width.min(width);
    let height = (lines.len() as u16 + 2).min(area.height);
    let popup = Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + (area.height - height) / 2,
        width,
        height,
    };
    let inner_height = height.saturating_sub(2) as usize;
    let scroll = (selected_line + 1).saturating_sub(inner_height);
    frame.render_widget(Clear, popup);
    frame.render_widget(
        Paragraph::new(lines)
            .scroll((scroll as u16, 0))
            .block(Block::bordered().title(title.to_owned())),
        popup,
    );
}

pub fn dim() -> Style {
    Style::new().add_modifier(Modifier::DIM)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digits_append_within_the_maximum() {
        let mut number = 1;
        type_digit(&mut number, '2', 100);
        assert_eq!(number, 12);
        type_digit(&mut number, '3', 100);
        assert_eq!(number, 3);
        type_digit(&mut number, '7', 2);
        assert_eq!(number, 2);
    }
}
