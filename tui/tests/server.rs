//! Talks to the real `leia-server.jar`. Needs a built jar and Java 25:
//!
//! ```sh
//! ./gradlew :leia-server:shadowJar
//! LEIA_SERVER_JAR=$PWD/leia-server/build/libs/leia-server.jar JAVA_HOME=<jdk 25> \
//!     cargo test --manifest-path tui/Cargo.toml -- --ignored
//! ```

use std::env;

use leia::client::CallError;
use leia::java::find_java;
use leia::protocol::ErrorCode;
use leia::protocol::index::{
    DocValuesType, GetIndexStats, GetIndexStatsParams, IndexOptions, OpenIndex, OpenIndexParams,
};
use leia::protocol::version::{Version, VersionParams};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, find_server_jar};

fn start() -> Server {
    let java = find_java(None, env::var_os("JAVA_HOME").as_deref()).unwrap();
    let jar = find_server_jar(env::var_os(SERVER_JAR_ENV).as_deref()).unwrap();
    Server::spawn(&java.executable, &jar).unwrap()
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn version_handshake_and_clean_shutdown() {
    let mut server = start();

    let version = server.call::<Version>(VersionParams {}).unwrap();
    let again = server.call::<Version>(VersionParams {}).unwrap();

    assert_eq!(version.name, "leia-server");
    assert_eq!(version, again);
    assert!(version.java.version.starts_with("25"), "{version:?}");
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
    assert!(
        server
            .stderr_lines()
            .iter()
            .any(|line| line.contains("ready on Java")),
        "{:?}",
        server.stderr_lines()
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn shutdown_server_rejects_calls() {
    let mut server = start();
    server.shutdown(SHUTDOWN_TIMEOUT).unwrap();

    assert!(matches!(
        server.call::<Version>(VersionParams {}),
        Err(CallError::Transport(_))
    ));
}

/// A Lucene 9 index written by `./gradlew :leia-core:lucene9TestIndex` (also part of `build`).
fn lucene9_index() -> String {
    let dir = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../leia-core/build/test-indexes/lucene9"
    );
    std::fs::canonicalize(dir)
        .expect("run ./gradlew :leia-core:lucene9TestIndex first")
        .to_str()
        .unwrap()
        .to_owned()
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn opens_lucene9_index() {
    let mut server = start();
    let path = lucene9_index();

    let index = server
        .call::<OpenIndex>(OpenIndexParams { path: path.clone() })
        .unwrap();

    assert_eq!(index.path, path);
    assert_eq!(index.segments_file, "segments_2");
    assert_eq!((index.num_docs, index.max_doc), (2, 3));
    assert_eq!(index.created_version_major, 9);
    assert!(
        index
            .min_segment_lucene_version
            .as_deref()
            .is_some_and(|version| version.starts_with("9.")),
        "{index:?}"
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn reports_missing_index() {
    let mut server = start();

    let error = server
        .call::<OpenIndex>(OpenIndexParams {
            path: "/nonexistent/index".to_owned(),
        })
        .unwrap_err();

    let CallError::Rpc(error) = error else {
        panic!("expected an RPC error, got {error:?}");
    };
    assert_eq!(error.code, ErrorCode::IndexNotFound);
    assert_eq!(
        error.message,
        "No such file or directory: /nonexistent/index"
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn reports_lucene9_index_stats() {
    let mut server = start();
    let path = lucene9_index();
    server
        .call::<OpenIndex>(OpenIndexParams { path: path.clone() })
        .unwrap();

    let stats = server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .unwrap();

    assert_eq!(stats.path, path);
    assert_eq!(
        (stats.segments_file.as_str(), stats.generation),
        ("segments_2", 2)
    );
    assert_eq!(
        (stats.segment_count, stats.num_docs, stats.max_doc),
        (1, 2, 3)
    );
    assert!(stats.commit_lucene_version.starts_with("9."), "{stats:?}");
    assert_eq!(
        stats.user_data.get("writer").map(String::as_str),
        Some("leia LegacyIndexWriter")
    );
    let fields: Vec<_> = stats
        .fields
        .iter()
        .map(|field| {
            (
                field.name.as_str(),
                field.index_options,
                field.doc_values_type,
            )
        })
        .collect();
    assert_eq!(
        fields,
        [
            ("id", IndexOptions::Docs, DocValuesType::None),
            (
                "body",
                IndexOptions::DocsAndFreqsAndPositions,
                DocValuesType::None
            ),
        ]
    );
    // Postings statistics count the deleted document too.
    assert_eq!(stats.fields[0].terms.map(|terms| terms.doc_count), Some(3));
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn index_stats_need_an_open_index() {
    let mut server = start();

    let error = server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .unwrap_err();

    assert!(
        matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::NoIndexOpen),
        "{error:?}"
    );
}
