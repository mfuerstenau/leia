//! Records the build of leia (git short hash and commit time) in `LEIA_COMMIT` and
//! `LEIA_COMMIT_TIME`, like `leia-server/build.gradle.kts` does for the server. Outside a git
//! checkout both are `unknown`.

use std::path::Path;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    let commit = git(&["rev-parse", "--short", "HEAD"]);
    let commit_time = git(&["log", "-1", "--format=%cI"]);
    println!(
        "cargo:rustc-env=LEIA_COMMIT={}",
        commit.as_deref().unwrap_or("unknown")
    );
    println!(
        "cargo:rustc-env=LEIA_COMMIT_TIME={}",
        commit_time.as_deref().unwrap_or("unknown")
    );

    // Rebuild when HEAD moves: on a checkout (HEAD) or a new commit on the current branch (its
    // loose ref, or packed-refs). Cargo reruns on every build for a path that doesn't exist, so
    // only existing paths are watched.
    let mut watched = vec!["HEAD".to_owned(), "packed-refs".to_owned()];
    watched.extend(git(&["symbolic-ref", "-q", "HEAD"]));
    for name in watched {
        if let Some(path) = git(&["rev-parse", "--path-format=absolute", "--git-path", &name])
            && Path::new(&path).exists()
        {
            println!("cargo:rerun-if-changed={path}");
        }
    }
}

/// Runs git and returns its trimmed stdout, or `None` if git is missing or fails.
fn git(args: &[&str]) -> Option<String> {
    let output = Command::new("git").args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8(output.stdout).ok()?;
    Some(stdout.trim().to_owned()).filter(|s| !s.is_empty())
}
