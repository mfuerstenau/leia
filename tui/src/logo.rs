//! leia's logo: the pixel art of `docs/leia2.png`, drawn with half blocks so that one terminal cell
//! holds two pixels, one above the other, which keeps the pixels about square. Next to it go leia's
//! name, version and build.

use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Paragraph, Wrap};

use crate::build_info::LEIA;

/// The pixels, one character each: `.` black, `#` white, `W` skin, `s` hair, `H` grey, `g`
/// transparent.
const SPRITE: [&str; 15] = [
    "ggggssssggggggg",
    "gsgssssssgsgggg",
    "ssssssssssssggg",
    "sssWWWWWWsssggg",
    "sssWW.W.Wsssggg",
    "sssWW.W.Wsssggg",
    "gsgWWWWWWgsgggg",
    "ggggWWWWggggggg",
    "ggg######gggHHH",
    "gg#########WHgg",
    "gg#########WWgg",
    "gg#WWHHHHgg#ggg",
    "gggWW####gggggg",
    "ggg#######ggggg",
    "gg########ggggg",
];

/// Columns the logo takes.
pub const WIDTH: u16 = 15;
/// Rows the logo takes: two pixel rows each.
pub const HEIGHT: u16 = 8;
/// Columns between the logo and the text next to it.
const GAP: u16 = 2;

/// The colour of a pixel; `None` for a transparent one.
fn color(pixel: char) -> Option<Color> {
    match pixel {
        '.' => Some(Color::Rgb(0, 0, 0)),
        '#' => Some(Color::Rgb(255, 255, 255)),
        'W' => Some(Color::Rgb(255, 232, 189)),
        's' => Some(Color::Rgb(105, 53, 0)),
        'H' => Some(Color::Rgb(126, 126, 126)),
        _ => None,
    }
}

/// The logo as `HEIGHT` lines of `WIDTH` cells. Transparent pixels leave the background as it is.
pub fn lines() -> Vec<Line<'static>> {
    let pixel = |row: usize, column: usize| {
        SPRITE
            .get(row)
            .and_then(|pixels| pixels.chars().nth(column))
            .and_then(color)
    };
    (0..usize::from(HEIGHT))
        .map(|row| {
            (0..usize::from(WIDTH))
                .map(
                    |column| match (pixel(2 * row, column), pixel(2 * row + 1, column)) {
                        (Some(top), Some(bottom)) => {
                            Span::styled("▀", Style::new().fg(top).bg(bottom))
                        }
                        (Some(top), None) => Span::styled("▀", Style::new().fg(top)),
                        (None, Some(bottom)) => Span::styled("▄", Style::new().fg(bottom)),
                        (None, None) => Span::raw(" "),
                    },
                )
                .collect::<Line>()
        })
        .collect()
}

/// leia's name and version, then its build: `leia 0.1.0`, `build 9229a55`, the commit time.
pub fn about_lines() -> Vec<Line<'static>> {
    vec![
        Line::from(vec![
            Span::styled(LEIA.name, Style::new().add_modifier(Modifier::BOLD)),
            Span::raw(format!(" {}", LEIA.version)),
        ]),
        Line::from(format!("build {}", LEIA.commit)),
        Line::from(LEIA.commit_time),
    ]
}

/// The columns the logo takes with `text` next to it.
pub fn width_beside(text: &[Line]) -> u16 {
    let text_width = text.iter().map(Line::width).max().unwrap_or(0);
    WIDTH + GAP + u16::try_from(text_width).unwrap_or(u16::MAX)
}

/// Draws the logo at the left of `area` and `text` next to it, centred on the logo's height if it
/// is shorter, wrapped if it is too wide.
pub fn render_beside(frame: &mut Frame, area: Rect, text: Vec<Line<'static>>) {
    let [logo_area, _, text_area] = Layout::horizontal([
        Constraint::Length(WIDTH),
        Constraint::Length(GAP),
        Constraint::Fill(1),
    ])
    .areas(area);
    frame.render_widget(Paragraph::new(lines()), logo_area);
    let offset = HEIGHT.saturating_sub(text.len() as u16) / 2;
    let [_, text_area] =
        Layout::vertical([Constraint::Length(offset), Constraint::Fill(1)]).areas(text_area);
    frame.render_widget(Paragraph::new(text).wrap(Wrap { trim: false }), text_area);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_pixel_has_a_known_character() {
        for row in SPRITE {
            assert_eq!(row.chars().count(), usize::from(WIDTH), "{row}");
            assert!(row.chars().all(|c| c == 'g' || color(c).is_some()), "{row}");
        }
        assert_eq!(SPRITE.len().div_ceil(2), usize::from(HEIGHT));
    }

    #[test]
    fn two_pixels_go_in_one_cell() {
        let lines = lines();
        assert_eq!(lines.len(), usize::from(HEIGHT));
        assert!(lines.iter().all(|line| line.width() == usize::from(WIDTH)));

        // The eyes: black above black.
        let eye = &lines[2].spans[5];
        assert_eq!(eye.content, "▀");
        assert_eq!(eye.style.fg, Some(Color::Rgb(0, 0, 0)));
        assert_eq!(eye.style.bg, Some(Color::Rgb(0, 0, 0)));

        // Top left: transparent above transparent.
        assert_eq!(lines[0].spans[0], Span::raw(" "));
        // The dress above nothing, in the last row, which has only one pixel row.
        let hem = &lines[7].spans[2];
        assert_eq!(hem.content, "▀");
        assert_eq!(hem.style.fg, Some(Color::Rgb(255, 255, 255)));
        assert_eq!(hem.style.bg, None);
        // Hair below nothing: the bun's top.
        let bun = &lines[0].spans[1];
        assert_eq!(bun.content, "▄");
        assert_eq!(bun.style.fg, Some(Color::Rgb(105, 53, 0)));
    }

    #[test]
    fn about_names_version_and_build() {
        let about: Vec<String> = about_lines().iter().map(ToString::to_string).collect();
        assert_eq!(
            about,
            [
                format!("leia {}", LEIA.version),
                format!("build {}", LEIA.commit),
                LEIA.commit_time.to_owned(),
            ]
        );
        assert_eq!(
            width_beside(&about_lines()),
            WIDTH + GAP + about.iter().map(|line| line.len() as u16).max().unwrap()
        );
    }
}
