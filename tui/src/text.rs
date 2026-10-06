//! How text from the index is shown: control characters escaped, so that they can't break the
//! layout, and byte strings that are not UTF-8 as `0x` and their hex.

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

/// `n` and the noun, in the plural unless `n` is 1: `1 segment`, `3 segments`.
pub fn count(n: usize, noun: &str) -> String {
    if n == 1 {
        format!("1 {noun}")
    } else {
        format!("{n} {noun}s")
    }
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
}
