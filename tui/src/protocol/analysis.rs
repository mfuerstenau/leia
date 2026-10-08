//! Analyzers and what they make of a text (ADR 0014). Mirrors
//! `io.github.mfuerstenau.leia.protocol.analysis`.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::method::RpcMethod;

/// `getAnalysisComponents`: the analyzers that a preset can name (ADR 0015), and the char
/// filters, tokenizers and token filters that a custom analyzer can be built from (ADR 0014).
/// Needs no open index.
pub enum GetAnalysisComponents {}

impl RpcMethod for GetAnalysisComponents {
    const NAME: &'static str = "getAnalysisComponents";
    type Params = GetAnalysisComponentsParams;
    type Result = GetAnalysisComponentsResult;
}

/// `analyze`: what an analyzer makes of a text, the text after each char filter, and the tokens
/// with their attributes after the tokenizer and each token filter (ADR 0014). Needs no open
/// index. Fails with `INVALID_ANALYZER` if the analyzer can't be built, or `INVALID_PARAMS` for an
/// analyzer without exactly one alternative or a text longer than [`MAX_TEXT_LENGTH`].
pub enum Analyze {}

impl RpcMethod for Analyze {
    const NAME: &'static str = "analyze";
    type Params = AnalyzeParams;
    type Result = AnalyzeResult;
}

/// Params of `getAnalysisComponents`: none.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GetAnalysisComponentsParams {}

/// Result of `getAnalysisComponents`: the analyzers that a preset can name, and what a custom
/// analyzer can be built from, each sorted by name, ignoring case.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetAnalysisComponentsResult {
    pub analyzers: Vec<AvailableAnalyzer>,
    pub char_filters: Vec<AvailableComponent>,
    pub tokenizers: Vec<AvailableComponent>,
    pub token_filters: Vec<AvailableComponent>,
}

/// An analyzer that a preset can name (ADR 0015).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AvailableAnalyzer {
    /// The simple class name, such as `EnglishAnalyzer`.
    pub name: String,
    /// The fully qualified class name, which [`PresetAnalyzer`] takes.
    pub class_name: String,
    /// Whether the preset takes stop words (ADR 0017).
    pub stopwords: WordSetSupport,
    /// Whether it takes stem exclusions; never [`WordSetSupport::Required`].
    pub stem_exclusion: WordSetSupport,
}

/// Whether a preset analyzer takes stop words or stem exclusions (ADR 0017).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WordSetSupport {
    /// It doesn't take the set.
    None,
    /// It takes the set, and has a default without it.
    Optional,
    /// It can't be created without the set.
    Required,
}

/// A factory that a custom analyzer can name.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AvailableComponent {
    /// The SPI name, such as `lowercase`.
    pub name: String,
    /// The factory class, whose documentation lists the parameters.
    pub class_name: String,
}

/// An analyzer, as one of several alternatives: exactly one is present (ADR 0014). The server
/// builds it for the request.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Analyzer {
    /// Lucene's `StandardAnalyzer`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub standard: Option<StandardAnalyzer>,
    /// An analyzer by class name, such as `EnglishAnalyzer` (ADR 0015).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub preset: Option<PresetAnalyzer>,
    /// A `CustomAnalyzer` of char filters, a tokenizer and token filters.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom: Option<CustomAnalyzer>,
}

impl Default for Analyzer {
    /// Lucene's `StandardAnalyzer`.
    fn default() -> Self {
        Self {
            standard: Some(StandardAnalyzer {}),
            preset: None,
            custom: None,
        }
    }
}

/// Lucene's `StandardAnalyzer`; it has no parameters.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StandardAnalyzer {}

/// An analyzer created by class name (ADR 0015), with its default settings and, if given, stop
/// words and stem exclusions of its own (ADR 0017).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PresetAnalyzer {
    /// The fully qualified class name, one that `getAnalysisComponents` lists in `analyzers`.
    pub class_name: String,
    /// The stop words; `None` for the analyzer's default ones.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stopwords: Option<WordSet>,
    /// The words kept from stemming; `None` for none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stem_exclusion: Option<WordSet>,
}

/// The stop words or stem exclusions of a preset, as one of two alternatives: exactly one is
/// present (ADR 0017).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WordSet {
    /// The words themselves; empty for a set without words.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub words: Option<Vec<String>>,
    /// A word list file that the server reads: UTF-8, one word per line, and lines starting with
    /// `#` are skipped.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file: Option<String>,
}

impl WordSet {
    pub fn words(words: Vec<String>) -> Self {
        Self {
            words: Some(words),
            file: None,
        }
    }

    pub fn file(path: String) -> Self {
        Self {
            words: None,
            file: Some(path),
        }
    }
}

/// A `CustomAnalyzer`: the text goes through the char filters, the tokenizer and the token
/// filters, in that order.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CustomAnalyzer {
    pub char_filters: Vec<AnalysisComponent>,
    pub tokenizer: AnalysisComponent,
    pub token_filters: Vec<AnalysisComponent>,
    /// The directory that resource files, such as stop words, are read from before the
    /// classpath; `None` for the classpath only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub config_dir: Option<String>,
}

/// A char filter, tokenizer or token filter of a custom analyzer.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AnalysisComponent {
    /// The factory's SPI name, such as `lowercase`; case doesn't matter.
    pub name: String,
    /// The factory's parameters, such as `words: stopwords.txt`.
    pub params: BTreeMap<String, String>,
}

/// Params of `analyze`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AnalyzeParams {
    pub analyzer: Analyzer,
    /// The text to analyze, at most [`MAX_TEXT_LENGTH`] UTF-16 code units.
    pub text: String,
}

/// The longest `text` of `analyze` the server accepts, in UTF-16 code units.
pub const MAX_TEXT_LENGTH: usize = 10_000;

/// Result of `analyze`: what the analyzer makes of the text, step by step.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AnalyzeResult {
    /// The text after each char filter of a custom analyzer; empty for a standard one.
    pub char_filters: Vec<CharFilterStep>,
    /// The tokens after the tokenizer and after each token filter of a custom analyzer; one
    /// stage, `StandardAnalyzer`, for a standard one.
    pub stages: Vec<TokenStage>,
}

/// The most tokens a stage of `analyze` has.
pub const MAX_TOKENS: usize = 10_000;

/// The text after a char filter.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CharFilterStep {
    /// The char filter's SPI name.
    pub name: String,
    /// The text after this char filter and every one before it.
    pub text: String,
}

/// The tokens after a tokenizer or token filter: what the chain up to it makes of the text.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TokenStage {
    /// The SPI name of the tokenizer or token filter, or the analyzer's class name.
    pub name: String,
    /// The tokens, at most [`MAX_TOKENS`].
    pub tokens: Vec<Token>,
    /// Whether the stage stopped at [`MAX_TOKENS`] with more to come.
    pub truncated: bool,
}

/// A token with its attributes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Token {
    pub term: String,
    /// The absolute position: -1 plus every position increment so far.
    pub position: i32,
    /// More than 1 after a removed token.
    pub position_increment: i32,
    /// How many positions the token spans.
    pub position_length: i32,
    /// Where the token starts in the original text, in UTF-16 code units, before any char filter.
    pub start_offset: i32,
    /// Where it ends in the original text.
    pub end_offset: i32,
    /// The token type, such as `<ALPHANUM>`.
    #[serde(rename = "type")]
    pub token_type: String,
    /// Every other value of the token's attributes.
    pub attributes: Vec<TokenAttribute>,
}

/// One value of a token's attribute, as Lucene's `AttributeImpl.reflectWith` reports it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TokenAttribute {
    /// The attribute interface's simple name, such as `KeywordAttribute`.
    pub attribute: String,
    /// The value's name, such as `keyword`.
    pub key: String,
    /// Lucene's `toString` of the value, such as `[66 6f 78]` for bytes.
    pub value: String,
}
