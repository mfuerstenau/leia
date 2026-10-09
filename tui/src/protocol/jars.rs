//! Jars added while leia runs (ADR 0024). Mirrors `io.github.mfuerstenau.leia.protocol.jars`.

use serde::{Deserialize, Serialize};

use super::method::RpcMethod;

/// `addJars`: loads more jars with analysis components, codecs or formats, as `--jar` does at
/// startup, and says what they added. A jar that is already loaded isn't loaded again. Needs no
/// open index. Fails with `INVALID_PARAMS` for no paths, or `INVALID_JAR` if a path is neither a
/// jar nor a directory with jars, or the jars can't be loaded; nothing is loaded then.
pub enum AddJars {}

impl RpcMethod for AddJars {
    const NAME: &'static str = "addJars";
    type Params = AddJarsParams;
    type Result = AddJarsResult;
}

/// `getJars`: the loaded jars, in the order they were loaded. Needs no open index.
pub enum GetJars {}

impl RpcMethod for GetJars {
    const NAME: &'static str = "getJars";
    type Params = GetJarsParams;
    type Result = GetJarsResult;
}

/// Params of `addJars`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AddJarsParams {
    /// Jars, or directories that stand for the jars directly in them; at least one. A relative
    /// path is resolved against the server's working directory, which is the TUI's.
    pub paths: Vec<String>,
}

/// Result of `addJars`.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AddJarsResult {
    /// The jars that were loaded, as absolute paths, in the order they were given.
    pub jars: Vec<String>,
    /// The jars that had been loaded before, and weren't loaded again.
    pub already_loaded: Vec<String>,
    /// The names that the new jars added.
    pub added: JarComponents,
}

/// The names that jars added, each sorted, ignoring case. A name that was taken before isn't one
/// of them.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JarComponents {
    /// The class names of the new preset analyzers.
    pub analyzers: Vec<String>,
    /// The SPI names of the new char filter factories.
    pub char_filters: Vec<String>,
    /// The SPI names of the new tokenizer factories.
    pub tokenizers: Vec<String>,
    /// The SPI names of the new token filter factories.
    pub token_filters: Vec<String>,
    pub codecs: Vec<String>,
    pub postings_formats: Vec<String>,
    pub doc_values_formats: Vec<String>,
    pub knn_vectors_formats: Vec<String>,
}

/// Params of `getJars`: none.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GetJarsParams {}

/// Result of `getJars`.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetJarsResult {
    /// The loaded jars, as absolute paths: those of the command line first, then the added ones.
    pub jars: Vec<String>,
}
