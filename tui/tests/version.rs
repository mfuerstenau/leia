//! `leia --version` against the real `leia-server.jar`. Needs a built jar and Java 25, like
//! `tests/server.rs`.

use std::process::Command;

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn version_prints_leia_server_and_java() {
    let output = Command::new(env!("CARGO_BIN_EXE_leia"))
        .arg("--version")
        .output()
        .unwrap();

    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8(output.stdout).unwrap();
    let lines: Vec<&str> = stdout.lines().collect();
    assert_eq!(lines.len(), 3, "{stdout}");
    assert!(
        lines[0].starts_with(&format!("leia {} (", env!("CARGO_PKG_VERSION"))),
        "{stdout}"
    );
    assert!(lines[1].starts_with("leia-server "), "{stdout}");
    assert!(lines[2].starts_with("Java 25"), "{stdout}");
}

#[test]
fn version_without_a_server_still_prints_leia() {
    let output = Command::new(env!("CARGO_BIN_EXE_leia"))
        .arg("--version")
        .env("LEIA_SERVER_JAR", "/nonexistent/leia-server.jar")
        .output()
        .unwrap();

    assert!(!output.status.success(), "{output:?}");
    let stdout = String::from_utf8(output.stdout).unwrap();
    let stderr = String::from_utf8(output.stderr).unwrap();
    assert_eq!(stdout.lines().count(), 1, "{stdout}");
    assert!(
        stdout.starts_with(&format!("leia {} (", env!("CARGO_PKG_VERSION"))),
        "{stdout}"
    );
    assert!(stderr.contains("/nonexistent/leia-server.jar"), "{stderr}");
}
