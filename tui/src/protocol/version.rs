//! `version`: the server's name, version and build, and the JVM it runs on. Mirrors
//! `io.github.mfuerstenau.leia.protocol.version`.

use serde::{Deserialize, Serialize};

use super::method::RpcMethod;

pub enum Version {}

impl RpcMethod for Version {
    const NAME: &'static str = "version";
    type Params = VersionParams;
    type Result = VersionResult;
}

/// Params of `version`: none.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VersionParams {}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VersionResult {
    pub name: String,
    pub version: String,
    /// Short git hash of the build.
    pub commit: String,
    /// ISO-8601 timestamp of that commit.
    pub commit_time: String,
    pub java: JavaRuntime,
}

/// The JVM the server runs on.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JavaRuntime {
    /// `Runtime.version()`.
    pub version: String,
    /// The `java.vendor` system property.
    pub vendor: String,
    /// The `java.home` system property.
    pub home: String,
}
