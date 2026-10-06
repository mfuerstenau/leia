//! Talks to the real `leia-server.jar`. Needs a built jar and Java 25:
//!
//! ```sh
//! ./gradlew :leia-server:shadowJar
//! LEIA_SERVER_JAR=$PWD/leia-server/build/libs/leia-server.jar JAVA_HOME=<jdk 25> \
//!     cargo test --manifest-path tui/Cargo.toml -- --ignored
//! ```

use std::env;
use std::thread;
use std::time::{Duration, Instant};

use leia::client::CallError;
use leia::java::find_java;
use leia::protocol::ErrorCode;
use leia::protocol::document::{
    BytesValue, DocumentField, GetDocument, GetDocumentParams, GetDocumentResult, GetStoredValue,
    GetStoredValueParams, GetTermVector, GetTermVectorParams, StoredType, StoredValue,
    TermPosition,
};
use leia::protocol::index::{
    CloseIndex, CloseIndexParams, DocValuesType, GetIndexStats, GetIndexStatsParams, IndexOptions,
    OpenIndex, OpenIndexParams,
};
use leia::protocol::job::{CancelJob, JobParams};
use leia::protocol::terms::{
    FieldTerms, GetFieldTermScan, GetPositions, GetPositionsParams, GetPostings, GetPostingsParams,
    GetPostingsResult, GetTerms, GetTermsParams, Posting, StartFieldTermScan,
    StartFieldTermScanParams, TermEntry, TopTerm,
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

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn closes_the_open_index() {
    let mut server = start();
    let path = lucene9_index();
    server
        .call::<OpenIndex>(OpenIndexParams { path: path.clone() })
        .unwrap();

    let closed = server.call::<CloseIndex>(CloseIndexParams {}).unwrap();
    let again = server.call::<CloseIndex>(CloseIndexParams {}).unwrap();

    assert_eq!(closed.path, Some(path));
    assert_eq!(again.path, None);
    let error = server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .unwrap_err();
    assert!(
        matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::NoIndexOpen),
        "{error:?}"
    );
}

fn top(term: &str, doc_freq: i32) -> TopTerm {
    TopTerm {
        term: term.to_owned(),
        binary: false,
        doc_freq,
        total_term_freq: i64::from(doc_freq),
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn scans_the_terms_of_a_field() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();

    let job = server
        .call::<StartFieldTermScan>(StartFieldTermScanParams {
            field: "body".to_owned(),
            top_n: 3,
        })
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    let terms = loop {
        let poll = server
            .call::<GetFieldTermScan>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(terms) = poll.terms {
            break terms;
        }
        assert!(Instant::now() < deadline, "the scan did not finish");
        thread::sleep(Duration::from_millis(10));
    };

    // "document number N" for N = 1..3; the deleted document 2 still counts.
    assert_eq!(
        terms,
        FieldTerms {
            field: "body".to_owned(),
            unique_terms: 5,
            top_terms: vec![top("document", 3), top("number", 3), top("1", 1)],
        }
    );
    let gone = server
        .call::<GetFieldTermScan>(JobParams { job_id: job.job_id })
        .unwrap_err();
    assert!(
        matches!(&gone, CallError::Rpc(e) if e.code == ErrorCode::JobNotFound),
        "{gone:?}"
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn cancels_a_scan_and_rejects_an_unknown_field() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();

    let unknown = server
        .call::<StartFieldTermScan>(StartFieldTermScanParams {
            field: "bdy".to_owned(),
            top_n: 3,
        })
        .unwrap_err();
    assert!(
        matches!(&unknown, CallError::Rpc(e) if e.code == ErrorCode::FieldNotFound),
        "{unknown:?}"
    );

    let job = server
        .call::<StartFieldTermScan>(StartFieldTermScanParams {
            field: "id".to_owned(),
            top_n: 3,
        })
        .unwrap();
    // The scan of three terms may be done already; either way the job is gone afterwards.
    server
        .call::<CancelJob>(JobParams { job_id: job.job_id })
        .unwrap();
    let gone = server
        .call::<GetFieldTermScan>(JobParams { job_id: job.job_id })
        .unwrap_err();
    assert!(
        matches!(&gone, CallError::Rpc(e) if e.code == ErrorCode::JobNotFound),
        "{gone:?}"
    );
    let again = server
        .call::<CancelJob>(JobParams { job_id: job.job_id })
        .unwrap();
    assert!(!again.cancelled);
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn reads_a_deleted_document_of_a_lucene9_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();

    // "document number N" for N = 1..3; document 2 is deleted.
    let document = server
        .call::<GetDocument>(GetDocumentParams { doc_id: 1 })
        .unwrap();

    let string = |value: &str| StoredValue {
        kind: StoredType::String,
        value: value.to_owned(),
        binary: false,
        full_length: None,
    };
    assert_eq!(
        document,
        GetDocumentResult {
            doc_id: 1,
            deleted: true,
            fields: vec![
                DocumentField {
                    name: "id".to_owned(),
                    norm: None,
                    stored: vec![string("2")],
                    doc_values: None,
                    has_term_vector: false,
                },
                DocumentField {
                    name: "body".to_owned(),
                    norm: Some(3),
                    stored: vec![string("document number 2")],
                    doc_values: None,
                    has_term_vector: false,
                },
            ],
        }
    );
    let vector = server
        .call::<GetTermVector>(GetTermVectorParams {
            doc_id: 1,
            field: "body".to_owned(),
        })
        .unwrap();
    assert!(vector.terms.is_empty(), "{vector:?}");
    let mut value = |index: i32| {
        server.call::<GetStoredValue>(GetStoredValueParams {
            doc_id: 1,
            field: "body".to_owned(),
            index,
        })
    };
    assert_eq!(value(0).unwrap().value, string("document number 2"));
    let no_value = value(1).unwrap_err();
    assert!(
        matches!(&no_value, CallError::Rpc(e) if e.code == ErrorCode::ValueNotFound),
        "{no_value:?}"
    );

    let missing = server
        .call::<GetDocument>(GetDocumentParams { doc_id: 3 })
        .unwrap_err();
    assert!(
        matches!(&missing, CallError::Rpc(e) if e.code == ErrorCode::DocNotFound),
        "{missing:?}"
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn browses_the_terms_and_postings_of_a_lucene9_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();
    let text = |value: &str| BytesValue {
        value: value.to_owned(),
        binary: false,
    };

    // "document number N" for N = 1..3; document 2 is deleted.
    let first = server
        .call::<GetTerms>(GetTermsParams {
            field: "body".to_owned(),
            from: Some(text("2")),
            limit: 2,
        })
        .unwrap();
    let entry = |term: &str, doc_freq: i32| TermEntry {
        term: term.to_owned(),
        binary: false,
        doc_freq,
        total_term_freq: i64::from(doc_freq),
    };
    assert_eq!(first.terms, vec![entry("2", 1), entry("3", 1)]);
    assert_eq!(first.next, Some(text("document")));

    let postings = server
        .call::<GetPostings>(GetPostingsParams {
            field: "body".to_owned(),
            term: text("number"),
            from_doc: 1,
            limit: 10,
        })
        .unwrap();
    let posting = |doc_id: i32, deleted: bool| Posting {
        doc_id,
        deleted,
        freq: Some(1),
    };
    assert_eq!(
        postings,
        GetPostingsResult {
            doc_freq: 3,
            total_term_freq: 3,
            postings: vec![posting(1, true), posting(2, false)],
            next_doc: None,
        }
    );

    // Offsets are not indexed.
    let positions = server
        .call::<GetPositions>(GetPositionsParams {
            doc_id: 2,
            field: "body".to_owned(),
            term: text("number"),
        })
        .unwrap();
    assert_eq!(
        positions.positions,
        vec![TermPosition {
            position: Some(1),
            start_offset: None,
            end_offset: None,
            payload: None,
        }]
    );

    let missing = server
        .call::<GetPostings>(GetPostingsParams {
            field: "body".to_owned(),
            term: text("nothing"),
            from_doc: 0,
            limit: 10,
        })
        .unwrap_err();
    assert!(
        matches!(&missing, CallError::Rpc(e) if e.code == ErrorCode::TermNotFound),
        "{missing:?}"
    );
}
