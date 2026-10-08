//! How text from the index is shown: control characters escaped, so that they can't break the
//! layout, and byte strings that are not UTF-8 as `0x` and their hex.

use unicode_width::{UnicodeWidthChar, UnicodeWidthStr};

/// `text` with control characters, line breaks included, escaped.
pub fn escape_control(text: &str) -> String {
    text.chars()
        .map(|c| {
            if c.is_control() {
                c.escape_default().to_string()
            } else {
                c.to_string()
            }
        })
        .collect()
}

/// A byte string from the server (ADR 0006): its text escaped, or `0x` and the hex if `binary`.
pub fn bytes_text(value: &str, binary: bool) -> String {
    if binary {
        format!("0x{value}")
    } else {
        escape_control(value)
    }
}

/// At most the first `max` characters of `text`, and `…` if it was cut.
pub fn first_chars(text: &str, max: usize) -> String {
    match text.char_indices().nth(max) {
        Some((end, _)) => format!("{}…", &text[..end]),
        None => text.to_owned(),
    }
}

/// `text` in lines at most `width` columns wide, broken at spaces, and inside a word only where
/// the word alone is wider; at most `max` lines, the last one ending in `…` if the text was cut.
/// At least one line, empty for an empty text.
pub fn wrap(text: &str, width: usize, max: usize) -> Vec<String> {
    wrap_hanging(text, width, width, max)
}

/// [`wrap`], with the first line `first` columns wide and the others `width`.
pub fn wrap_hanging(text: &str, first: usize, width: usize, max: usize) -> Vec<String> {
    let max = max.max(1);
    let mut lines: Vec<String> = Vec::new();
    let mut line = String::new();
    let mut line_width = 0;
    let fits = |lines: &Vec<String>, columns: usize| {
        let width = if lines.is_empty() { first } else { width };
        columns <= width.max(1)
    };
    for word in text.split(' ') {
        let word_width = word.width();
        if line_width > 0 && fits(&lines, line_width + 1 + word_width) {
            line.push(' ');
            line.push_str(word);
            line_width += 1 + word_width;
            continue;
        }
        if line_width > 0 {
            lines.push(std::mem::take(&mut line));
            line_width = 0;
        }
        for c in word.chars() {
            let c_width = c.width().unwrap_or_default();
            if line_width > 0 && !fits(&lines, line_width + c_width) {
                lines.push(std::mem::take(&mut line));
                line_width = 0;
            }
            line.push(c);
            line_width += c_width;
        }
    }
    if !line.is_empty() || lines.is_empty() {
        lines.push(line);
    }
    if lines.len() > max {
        lines.truncate(max);
        let last_width = if max == 1 { first } else { width };
        let last = lines.last_mut().expect("at least one line");
        while last.width() + 1 > last_width.max(1) && last.pop().is_some() {}
        last.push('…');
    }
    lines
}

/// `n` and the noun, in the plural unless `n` is 1: `1 segment`, `3 segments`.
pub fn count(n: usize, noun: &str) -> String {
    if n == 1 {
        format!("1 {noun}")
    } else {
        format!("{n} {noun}s")
    }
}

/// The UTC date and time of `millis` since the Unix epoch: year, month, day, hour, minute and
/// second.
pub fn utc_parts(millis: i64) -> [i64; 6] {
    let seconds = millis.div_euclid(1000);
    let (days, time) = (seconds.div_euclid(86_400), seconds.rem_euclid(86_400));
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    [year, month, day, time / 3600, time % 3600 / 60, time % 60]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escapes_control_characters() {
        assert_eq!(escape_control("a\tb\nc\u{1}"), "a\\tb\\nc\\u{1}");
        assert_eq!(escape_control("grüße"), "grüße");
    }

    #[test]
    fn shows_binary_as_hex() {
        assert_eq!(bytes_text("ff00", true), "0xff00");
        assert_eq!(bytes_text("ff\n", false), "ff\\n");
    }

    #[test]
    fn cuts_at_characters() {
        assert_eq!(first_chars("grüße", 3), "grü…");
        assert_eq!(first_chars("grüße", 5), "grüße");
    }

    #[test]
    fn wraps_at_spaces() {
        assert_eq!(wrap("one two three", 7, 5), ["one two", "three"]);
        assert_eq!(wrap("one two three", 13, 5), ["one two three"]);
        assert_eq!(wrap("", 10, 5), [""]);
    }

    #[test]
    fn breaks_words_wider_than_the_line() {
        assert_eq!(
            wrap("a /very/long/path b", 6, 5),
            ["a", "/very/", "long/p", "ath b"]
        );
        // By columns: each of these characters takes two.
        assert_eq!(wrap("索引索引", 4, 5), ["索引", "索引"]);
    }

    #[test]
    fn cuts_after_the_last_line() {
        assert_eq!(wrap("one two three four", 7, 2), ["one two", "three…"]);
        assert_eq!(wrap("one two three four", 5, 2), ["one", "two…"]);
        assert_eq!(wrap("abcdefghij", 5, 1), ["abcd…"]);
    }

    #[test]
    fn wraps_the_first_line_narrower() {
        assert_eq!(
            wrap_hanging("one two three four", 3, 9, 5),
            ["one", "two three", "four"]
        );
        assert_eq!(
            wrap_hanging("one two three four", 3, 9, 2),
            ["one", "two thre…"]
        );
    }
}
