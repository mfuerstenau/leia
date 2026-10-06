//! Version and build of leia, and the version lines printed by `--version` and shown in the UI.

use crate::protocol::version::{JavaRuntime, VersionResult};

/// Name, version and build of this binary. The build comes from `build.rs`.
pub const LEIA: BuildInfo = BuildInfo {
    name: env!("CARGO_PKG_NAME"),
    version: env!("CARGO_PKG_VERSION"),
    commit: env!("LEIA_COMMIT"),
    commit_time: env!("LEIA_COMMIT_TIME"),
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BuildInfo {
    pub name: &'static str,
    pub version: &'static str,
    /// Short git hash, or `unknown` outside a git checkout.
    pub commit: &'static str,
    /// ISO-8601 timestamp of that commit, or `unknown`.
    pub commit_time: &'static str,
}

impl BuildInfo {
    /// `leia 0.1.0 (9229a55, 2026-10-05T22:45:00+02:00)`
    pub fn line(&self) -> String {
        build_line(self.name, self.version, self.commit, self.commit_time)
    }
}

/// `leia-server 0.1.0-SNAPSHOT (9229a55, 2026-10-05T22:45:00+02:00)`
pub fn server_line(server: &VersionResult) -> String {
    build_line(
        &server.name,
        &server.version,
        &server.commit,
        &server.commit_time,
    )
}

/// `Java 25.0.1+8-LTS (Eclipse Adoptium) in /opt/java/temurin-25`
pub fn java_line(java: &JavaRuntime) -> String {
    format!("Java {} ({}) in {}", java.version, java.vendor, java.home)
}

fn build_line(name: &str, version: &str, commit: &str, commit_time: &str) -> String {
    format!("{name} {version} ({commit}, {commit_time})")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn server() -> VersionResult {
        VersionResult {
            name: "leia-server".to_owned(),
            version: "0.1.0-SNAPSHOT".to_owned(),
            commit: "56dfc76".to_owned(),
            commit_time: "2026-10-05T22:07:13+02:00".to_owned(),
            java: JavaRuntime {
                version: "25.0.1+8-LTS".to_owned(),
                vendor: "Eclipse Adoptium".to_owned(),
                home: "/opt/java/temurin-25".to_owned(),
            },
        }
    }

    #[test]
    fn leia_line_names_this_binary() {
        assert_eq!(LEIA.name, "leia");
        assert_eq!(LEIA.version, env!("CARGO_PKG_VERSION"));
        assert!(!LEIA.commit.is_empty());
        assert!(!LEIA.commit_time.is_empty());
        assert_eq!(
            LEIA.line(),
            format!(
                "leia {} ({}, {})",
                LEIA.version, LEIA.commit, LEIA.commit_time
            )
        );
    }

    /// leia and leia-server ship together, so they share one version. `gradle.properties` is
    /// the source of truth; `Cargo.toml` has to be kept in step by hand.
    #[test]
    fn version_matches_gradle_properties() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../gradle.properties");
        let properties = std::fs::read_to_string(path).unwrap();
        let gradle_version = properties
            .lines()
            .find_map(|line| line.strip_prefix("version="))
            .expect("no version= in gradle.properties")
            .trim();
        assert_eq!(
            env!("CARGO_PKG_VERSION"),
            gradle_version,
            "tui/Cargo.toml and gradle.properties disagree on the version"
        );
    }

    #[test]
    fn server_line_shows_name_version_and_build() {
        assert_eq!(
            server_line(&server()),
            "leia-server 0.1.0-SNAPSHOT (56dfc76, 2026-10-05T22:07:13+02:00)"
        );
    }

    #[test]
    fn java_line_shows_version_vendor_and_home() {
        assert_eq!(
            java_line(&server().java),
            "Java 25.0.1+8-LTS (Eclipse Adoptium) in /opt/java/temurin-25"
        );
    }
}
