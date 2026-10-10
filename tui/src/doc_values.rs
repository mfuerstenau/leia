//! The query line of the search screen's doc values mode (ADR 0033): one value, several values
//! (a set), or a range in Lucene's syntax.
//!
//! ```text
//! 42          -1 0 1          "new york" boston
//! [10 TO 20]  {0 TO *]        [* TO "m"}
//! ```
//!
//! Values are separated by whitespace. A value in quotes may hold whitespace, `\"` and `\\`; an
//! unquoted `*` is an open bound.

use crate::protocol::document::BytesValue;
use crate::protocol::search::{DocValuesNumber, DocValuesQuery, DocValuesRange};

/// What the line says, before it is typed for a field.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Parsed {
    Values(Vec<String>),
    Range {
        lower: Option<String>,
        upper: Option<String>,
        include_lower: bool,
        include_upper: bool,
    },
}

/// The query for `line` on `field`. `number` says how a number field encodes its numbers, and is
/// `None` for a field of bytes, whose values are hex if `hex`.
pub fn query(
    field: &str,
    number: Option<DocValuesNumber>,
    hex: bool,
    line: &str,
) -> Result<DocValuesQuery, String> {
    let hex = hex && number.is_none();
    let value = |text: String| -> Result<BytesValue, String> {
        if hex {
            check_hex(&text)?;
        }
        Ok(BytesValue {
            value: if hex { text.to_ascii_lowercase() } else { text },
            binary: hex,
        })
    };
    let (values, range) = match parse(line)? {
        Parsed::Values(values) => (
            Some(values.into_iter().map(value).collect::<Result<_, _>>()?),
            None,
        ),
        Parsed::Range {
            lower,
            upper,
            include_lower,
            include_upper,
        } => (
            None,
            Some(DocValuesRange {
                lower: lower.map(value).transpose()?,
                upper: upper.map(value).transpose()?,
                include_lower,
                include_upper,
            }),
        ),
    };
    Ok(DocValuesQuery {
        field: field.to_owned(),
        number,
        values,
        range,
    })
}

/// Reads the line as values or a range.
pub fn parse(line: &str) -> Result<Parsed, String> {
    let line = line.trim();
    if let Some(rest) = line.strip_prefix(['[', '{']) {
        let include_lower = line.starts_with('[');
        let (inner, include_upper) = match rest.strip_suffix(']') {
            Some(inner) => (inner, true),
            None => match rest.strip_suffix('}') {
                Some(inner) => (inner, false),
                None => return Err("A range ends with ] or }.".to_owned()),
            },
        };
        let tokens = tokens(inner)?;
        let [lower, to, upper] = tokens.as_slice() else {
            return Err("A range is [lower TO upper], with * for no bound.".to_owned());
        };
        if to.quoted || to.text != "TO" {
            return Err("A range is [lower TO upper], with * for no bound.".to_owned());
        }
        return Ok(Parsed::Range {
            lower: lower.bound(),
            upper: upper.bound(),
            include_lower,
            include_upper,
        });
    }
    let values: Vec<String> = tokens(line)?.into_iter().map(|token| token.text).collect();
    if values.is_empty() {
        return Err("Type a value, several values, or a range.".to_owned());
    }
    Ok(Parsed::Values(values))
}

/// `value` as one value of the line: in quotes if it would read otherwise as it is.
pub fn quote(value: &str) -> String {
    let plain = !value.is_empty()
        && value != "*"
        && !value.starts_with(['[', '{', '"'])
        && !value
            .chars()
            .any(|c| c.is_whitespace() || c == '"' || c == '\\');
    if plain {
        return value.to_owned();
    }
    let mut quoted = String::from('"');
    for c in value.chars() {
        if c == '"' || c == '\\' {
            quoted.push('\\');
        }
        quoted.push(c);
    }
    quoted.push('"');
    quoted
}

#[derive(Debug)]
struct Token {
    text: String,
    quoted: bool,
}

impl Token {
    /// The bound the token stands for: `None` for an unquoted `*`.
    fn bound(&self) -> Option<String> {
        (self.quoted || self.text != "*").then(|| self.text.clone())
    }
}

/// The whitespace-separated values of `text`, a quoted one taking `\"` and `\\`.
fn tokens(text: &str) -> Result<Vec<Token>, String> {
    let mut tokens = Vec::new();
    let mut chars = text.chars().peekable();
    while let Some(&c) = chars.peek() {
        if c.is_whitespace() {
            chars.next();
            continue;
        }
        if c != '"' {
            let mut token = String::new();
            while let Some(&c) = chars.peek() {
                if c.is_whitespace() {
                    break;
                }
                token.push(c);
                chars.next();
            }
            tokens.push(Token {
                text: token,
                quoted: false,
            });
            continue;
        }
        chars.next();
        let mut token = String::new();
        loop {
            match chars.next() {
                None => return Err("A quote is not closed.".to_owned()),
                Some('"') => break,
                Some('\\') => match chars.next() {
                    Some(c @ ('"' | '\\')) => token.push(c),
                    _ => return Err("In quotes, \\ is followed by \" or \\.".to_owned()),
                },
                Some(c) => token.push(c),
            }
        }
        if chars.peek().is_some_and(|c| !c.is_whitespace()) {
            return Err("A closing quote is followed by a space or the end.".to_owned());
        }
        tokens.push(Token {
            text: token,
            quoted: true,
        });
    }
    Ok(tokens)
}

fn check_hex(text: &str) -> Result<(), String> {
    if !text.len().is_multiple_of(2) || !text.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(format!("'{text}' is not hex: two of 0-9 and a-f per byte."));
    }
    Ok(())
}

/// The hex of `bytes`, as the server writes binary values.
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn values(values: &[&str]) -> Parsed {
        Parsed::Values(values.iter().map(|&value| value.to_owned()).collect())
    }

    #[test]
    fn reads_values_and_quotes() {
        assert_eq!(parse("42"), Ok(values(&["42"])));
        assert_eq!(parse("  -1 0\t1 "), Ok(values(&["-1", "0", "1"])));
        assert_eq!(
            parse(r#""new york" boston "say \"hi\"" "a\\b" """#),
            Ok(values(&["new york", "boston", "say \"hi\"", "a\\b", ""]))
        );
        // A quote inside a value is just a character.
        assert_eq!(parse(r#"a"b"#), Ok(values(&["a\"b"])));
        assert_eq!(parse("*"), Ok(values(&["*"])));
    }

    #[test]
    fn reads_ranges() {
        assert_eq!(
            parse("[10 TO 20]"),
            Ok(Parsed::Range {
                lower: Some("10".to_owned()),
                upper: Some("20".to_owned()),
                include_lower: true,
                include_upper: true,
            })
        );
        assert_eq!(
            parse(r#"{* TO "*"}"#),
            Ok(Parsed::Range {
                lower: None,
                upper: Some("*".to_owned()),
                include_lower: false,
                include_upper: false,
            })
        );
        assert_eq!(
            parse(r#"[ "a b"  TO * }"#),
            Ok(Parsed::Range {
                lower: Some("a b".to_owned()),
                upper: None,
                include_lower: true,
                include_upper: false,
            })
        );
    }

    #[test]
    fn says_what_is_wrong() {
        assert!(parse("").is_err());
        assert!(parse("   ").is_err());
        assert!(parse("[1 TO 2").is_err());
        assert!(parse("[1 to 2]").is_err());
        assert!(parse("[1 2]").is_err());
        assert!(parse(r#"[1 "TO" 2]"#).is_err());
        assert!(parse(r#""open"#).is_err());
        assert!(parse(r#""a"b"#).is_err());
        assert!(parse(r#""\n""#).is_err());
    }

    #[test]
    fn quotes_what_would_read_otherwise() {
        for value in [
            "plain", "-1.5", "a\"b", "new york", "", "*", "[x", "{x", "\"q", "a\\b",
        ] {
            let line = quote(value);
            assert_eq!(parse(&line), Ok(values(&[value])), "{line}");
        }
        assert_eq!(quote("plain"), "plain");
        assert_eq!(quote("new york"), "\"new york\"");
    }

    #[test]
    fn types_the_values_for_the_field() {
        let query = query("price", Some(DocValuesNumber::Double), true, "1.5 -2").unwrap();
        assert_eq!(query.number, Some(DocValuesNumber::Double));
        assert_eq!(
            query.values,
            Some(vec![
                BytesValue {
                    value: "1.5".to_owned(),
                    binary: false
                },
                BytesValue {
                    value: "-2".to_owned(),
                    binary: false
                },
            ])
        );
        assert_eq!(query.range, None);

        let query = super::query("checksum", None, true, "[80FF TO *]").unwrap();
        assert_eq!(
            query.range,
            Some(DocValuesRange {
                lower: Some(BytesValue {
                    value: "80ff".to_owned(),
                    binary: true
                }),
                upper: None,
                include_lower: true,
                include_upper: true,
            })
        );
        assert!(super::query("checksum", None, true, "8").is_err());
        assert!(super::query("checksum", None, true, "zz").is_err());
        assert!(super::query("category", None, false, "zz").is_ok());
    }

    #[test]
    fn writes_hex() {
        assert_eq!(hex(&[0, 0x7f, 0xff]), "007fff");
    }
}
