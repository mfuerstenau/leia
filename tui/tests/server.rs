//! Talks to the real `leia-server.jar`. Needs a built jar, Java 25, and the test indexes and
//! analysis jars that `./gradlew build` writes to `leia-core/build/`:
//!
//! ```sh
//! ./gradlew :leia-server:shadowJar
//! LEIA_SERVER_JAR=$PWD/leia-server/build/libs/leia-server.jar JAVA_HOME=<jdk 25> \
//!     cargo test --manifest-path tui/Cargo.toml -- --ignored
//! ```

use std::env;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant};

use leia::client::CallError;
use leia::java::find_java;
use leia::protocol::ErrorCode;
use leia::protocol::analysis::{
    AnalysisComponent, Analyze, AnalyzeParams, Analyzer, CustomAnalyzer, GetAnalysisComponents,
    GetAnalysisComponentsParams, PresetAnalyzer,
};
use leia::protocol::commits::{GetCommit, GetCommitParams, GetCommits, GetCommitsParams};
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
use leia::protocol::search::{
    Bm25Similarity, ClassicQuery, ClassicSimilarity, GetExplain, GetSearch, MAX_FUZZY_EDITS,
    MoreLikeThisQuery, ParserOptions, PointField, PointType, QueryOperator, SearchQuery,
    Similarity, SortKey, SortType, StandardQuery, StartExplain, StartExplainParams, StartSearch,
    StartSearchParams, TotalHitsRelation,
};
use leia::protocol::terms::{
    FieldTerms, GetFieldTermScan, GetPositions, GetPositionsParams, GetPostings, GetPostingsParams,
    GetPostingsResult, GetTerms, GetTermsParams, Posting, StartFieldTermScan,
    StartFieldTermScanParams, TermEntry, TopTerm,
};
use leia::protocol::version::{Version, VersionParams};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, find_server_jar};

fn start() -> Server {
    start_with(&[])
}

fn start_with(analysis_jars: &[PathBuf]) -> Server {
    let java = find_java(None, env::var_os("JAVA_HOME").as_deref()).unwrap();
    let jar = find_server_jar(env::var_os(SERVER_JAR_ENV).as_deref()).unwrap();
    Server::spawn(&java.executable, &jar, analysis_jars).unwrap()
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

/// A directory of analysis jars written by `./gradlew :leia-core:testAnalysisJars` (also part of
/// `build`): `phonetic` (with commons-codec) or `stempel` (ADR 0016).
fn test_analysis_jars(module: &str) -> PathBuf {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../leia-core/build/test-analysis-jars")
        .join(module);
    std::fs::canonicalize(dir).expect("run ./gradlew :leia-core:testAnalysisJars first")
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

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn reads_the_commit_of_a_lucene9_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();

    let commits = server
        .call::<GetCommits>(GetCommitsParams {})
        .unwrap()
        .commits;
    // The default deletion policy kept only the last commit.
    assert_eq!(commits.len(), 1, "{commits:?}");
    let commit = &commits[0];
    assert_eq!(
        (
            commit.generation,
            commit.segments_file.as_str(),
            commit.current
        ),
        (2, "segments_2", true)
    );
    assert!(commit.lucene_version.starts_with("9."), "{commit:?}");
    assert_eq!(
        commit.user_data.get("writer").map(String::as_str),
        Some("leia LegacyIndexWriter")
    );

    let details = server
        .call::<GetCommit>(GetCommitParams { generation: 2 })
        .unwrap();
    assert_eq!(details.commit, *commit);
    let max_doc: i32 = details.segments.iter().map(|segment| segment.max_doc).sum();
    let deleted: i32 = details
        .segments
        .iter()
        .map(|segment| segment.del_count)
        .sum();
    assert_eq!((max_doc, deleted), (3, 1));
    assert!(
        details
            .segments
            .iter()
            .all(|segment| segment.codec.starts_with("Lucene9")),
        "{details:?}"
    );
    assert!(
        details
            .files
            .iter()
            .any(|file| file.name == "segments_2" && file.segment.is_none() && file.size.is_some()),
        "{details:?}"
    );

    let missing = server
        .call::<GetCommit>(GetCommitParams { generation: 1 })
        .unwrap_err();
    assert!(
        matches!(&missing, CallError::Rpc(e) if e.code == ErrorCode::CommitNotFound),
        "{missing:?}"
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn searches_and_explains_a_lucene9_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();
    let query = classic("number AND (1 OR 2 OR 3)");
    // TF-IDF, for the search and the explanation alike.
    let tf_idf = Similarity {
        classic: Some(ClassicSimilarity {}),
        ..Similarity::default()
    };

    let job = server
        .call::<StartSearch>(StartSearchParams {
            query: query.clone(),
            similarity: tf_idf,
            sort: Vec::new(),
            offset: 0,
            limit: 10,
            exact_total_hits: true,
            fields: Some(vec!["id".to_owned()]),
        })
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    let results = loop {
        let poll = server
            .call::<GetSearch>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(results) = poll.results {
            break results;
        }
        assert!(Instant::now() < deadline, "the search did not finish");
        thread::sleep(Duration::from_millis(10));
    };

    // "document number N" for N = 1..3; document 1 (id 2) is deleted.
    assert_eq!(results.query, "+body:number +(body:1 body:2 body:3)");
    assert_eq!(
        (results.total_hits, results.total_hits_relation),
        (2, TotalHitsRelation::EqualTo)
    );
    let hits: Vec<(i32, &str)> = results
        .hits
        .iter()
        .map(|hit| (hit.doc_id, hit.fields[0].values[0].value.as_str()))
        .collect();
    assert_eq!(hits, vec![(0, "1"), (2, "3")]);
    assert!(results.hits.iter().all(|hit| hit.sort_values.is_empty()));

    let job = server
        .call::<StartExplain>(StartExplainParams {
            query: query.clone(),
            similarity: tf_idf,
            doc_id: 2,
        })
        .unwrap();
    let explanation = loop {
        let poll = server
            .call::<GetExplain>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(explanation) = poll.explanation {
            break explanation;
        }
        assert!(Instant::now() < deadline, "the explanation did not finish");
        thread::sleep(Duration::from_millis(10));
    };
    assert!(explanation.matches, "{explanation:?}");
    assert!(
        explanation.details[0]
            .description
            .contains("ClassicSimilarity"),
        "{explanation:?}"
    );
    assert_eq!(
        explanation.value.parse::<f64>().unwrap(),
        results.hits[1].score
    );

    let broken = server
        .call::<StartSearch>(StartSearchParams {
            query: classic("body:(number"),
            similarity: bm25(),
            sort: Vec::new(),
            offset: 0,
            limit: 10,
            exact_total_hits: false,
            fields: None,
        })
        .unwrap_err();
    assert!(
        matches!(&broken, CallError::Rpc(e) if e.code == ErrorCode::InvalidQuery),
        "{broken:?}"
    );

    // The standard parser, with AND as the default operator.
    let standard = |points: Vec<PointField>| SearchQuery {
        standard: Some(StandardQuery {
            expression: "number (1 OR 3)".to_owned(),
            default_field: "body".to_owned(),
            default_operator: QueryOperator::And,
            options: ParserOptions::default(),
            points,
            analyzer: Analyzer::default(),
        }),
        ..SearchQuery::default()
    };
    let job = server
        .call::<StartSearch>(StartSearchParams {
            query: standard(Vec::new()),
            similarity: bm25(),
            sort: Vec::new(),
            offset: 0,
            limit: 10,
            exact_total_hits: true,
            fields: None,
        })
        .unwrap();
    let results = loop {
        let poll = server
            .call::<GetSearch>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(results) = poll.results {
            break results;
        }
        assert!(Instant::now() < deadline, "the search did not finish");
        thread::sleep(Duration::from_millis(10));
    };
    assert_eq!(results.query, "+body:number +(body:1 body:3)");
    assert_eq!(results.total_hits, 2);
    // `body` has no points, so it can't be read as ints.
    let not_points = server
        .call::<StartSearch>(StartSearchParams {
            query: standard(vec![PointField {
                field: "body".to_owned(),
                point_type: PointType::Int,
            }]),
            similarity: bm25(),
            sort: Vec::new(),
            offset: 0,
            limit: 10,
            exact_total_hits: false,
            fields: None,
        })
        .unwrap_err();
    assert!(
        matches!(&not_points, CallError::Rpc(e) if e.code == ErrorCode::InvalidParams),
        "{not_points:?}"
    );
    // The index has no doc values to sort by.
    let unsortable = server
        .call::<StartSearch>(StartSearchParams {
            query: classic("number"),
            similarity: bm25(),
            sort: vec![SortKey {
                field: "id".to_owned(),
                sort_type: SortType::String,
                reverse: false,
            }],
            offset: 0,
            limit: 10,
            exact_total_hits: false,
            fields: None,
        })
        .unwrap_err();
    assert!(
        matches!(&unsortable, CallError::Rpc(e) if e.code == ErrorCode::InvalidParams
            && e.message.contains("no doc values")),
        "{unsortable:?}"
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn searches_for_documents_like_one_of_a_lucene9_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();
    let like = |doc_id: i32, fields: &[&str]| SearchQuery {
        more_like_this: Some(MoreLikeThisQuery {
            doc_id,
            fields: fields.iter().map(|field| (*field).to_owned()).collect(),
            min_term_freq: 1,
            min_doc_freq: 2,
            max_doc_freq: None,
            max_query_terms: 25,
            boost: false,
            analyzer: Analyzer::default(),
        }),
        ..SearchQuery::default()
    };
    let params = |query: SearchQuery| StartSearchParams {
        query,
        similarity: bm25(),
        sort: Vec::new(),
        offset: 0,
        limit: 10,
        exact_total_hits: true,
        fields: None,
    };

    let job = server
        .call::<StartSearch>(params(like(0, &["body"])))
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    let results = loop {
        let poll = server
            .call::<GetSearch>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(results) = poll.results {
            break results;
        }
        assert!(Instant::now() < deadline, "the search did not finish");
        thread::sleep(Duration::from_millis(10));
    };

    // "document number 1": the stored text is analyzed, and "1" occurs in only one document.
    assert_eq!(
        results.query,
        "like(doc 0, fields [body], minTermFreq 1, minDocFreq 2, maxQueryTerms 25)"
    );
    let mut terms: Vec<&str> = results.rewritten_query.split(' ').collect();
    terms.sort_unstable();
    assert_eq!(terms, ["body:document", "body:number"]);
    // Document 1 is deleted.
    let mut docs: Vec<i32> = results.hits.iter().map(|hit| hit.doc_id).collect();
    docs.sort_unstable();
    assert_eq!(docs, [0, 2]);

    let job = server
        .call::<StartExplain>(StartExplainParams {
            query: like(0, &["body"]),
            similarity: bm25(),
            doc_id: 2,
        })
        .unwrap();
    let explanation = loop {
        let poll = server
            .call::<GetExplain>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(explanation) = poll.explanation {
            break explanation;
        }
        assert!(Instant::now() < deadline, "the explanation did not finish");
        thread::sleep(Duration::from_millis(10));
    };
    assert!(explanation.matches, "{explanation:?}");

    let missing = server
        .call::<StartSearch>(params(like(3, &["body"])))
        .unwrap_err();
    assert!(
        matches!(&missing, CallError::Rpc(e) if e.code == ErrorCode::DocNotFound),
        "{missing:?}"
    );
    let unknown = server
        .call::<StartSearch>(params(like(0, &["nope"])))
        .unwrap_err();
    assert!(
        matches!(&unknown, CallError::Rpc(e) if e.code == ErrorCode::FieldNotFound),
        "{unknown:?}"
    );
}

fn bm25() -> Similarity {
    Similarity {
        bm25: Some(Bm25Similarity { k1: 1.2, b: 0.75 }),
        ..Similarity::default()
    }
}

fn classic(expression: &str) -> SearchQuery {
    SearchQuery {
        classic: Some(ClassicQuery {
            expression: expression.to_owned(),
            default_field: "body".to_owned(),
            default_operator: QueryOperator::Or,
            options: ParserOptions::default(),
            fuzzy_max_edits: MAX_FUZZY_EDITS,
            split_on_whitespace: false,
            auto_generate_phrase_queries: false,
            analyzer: Analyzer::default(),
        }),
        ..SearchQuery::default()
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn analyzes_with_analysis_jars() {
    let mut server = start_with(&[
        test_analysis_jars("phonetic"),
        test_analysis_jars("stempel"),
    ]);

    let components = server
        .call::<GetAnalysisComponents>(GetAnalysisComponentsParams {})
        .unwrap();
    assert!(
        components
            .token_filters
            .iter()
            .any(|c| c.name == "doubleMetaphone")
    );
    let polish = components
        .analyzers
        .iter()
        .find(|a| a.name == "PolishAnalyzer")
        .unwrap();

    let result = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: Analyzer {
                standard: None,
                preset: Some(PresetAnalyzer {
                    class_name: polish.class_name.clone(),
                }),
                custom: None,
            },
            text: "kot i pies".to_owned(),
        })
        .unwrap();
    let terms: Vec<&str> = result.stages[0]
        .tokens
        .iter()
        .map(|t| t.term.as_str())
        .collect();
    assert_eq!(terms, ["kot", "pies"]);
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn a_jar_without_its_dependencies_stops_the_server() {
    let phonetic = std::fs::read_dir(test_analysis_jars("phonetic"))
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .find(|path| {
            path.file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("lucene-analysis-phonetic-")
        })
        .unwrap();
    let mut server = start_with(&[phonetic]);

    let Err(CallError::Transport(error)) = server.call::<Version>(VersionParams {}) else {
        panic!("the server answered");
    };

    let message = format!("{error:#}");
    assert!(
        message.contains("Cannot load the analysis jars: cannot load the analysis factories")
            && message.contains("org/apache/commons/codec/"),
        "{message}"
    );
}

fn component(name: &str, params: &[(&str, &str)]) -> AnalysisComponent {
    AnalysisComponent {
        name: name.to_owned(),
        params: params
            .iter()
            .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
            .collect(),
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn analyzes_without_an_open_index() {
    let mut server = start();

    let components = server
        .call::<GetAnalysisComponents>(GetAnalysisComponentsParams {})
        .unwrap();
    assert!(components.tokenizers.iter().any(|c| c.name == "standard"));
    assert!(
        components
            .token_filters
            .iter()
            .any(|c| c.name == "porterStem")
    );
    assert!(
        components
            .char_filters
            .iter()
            .any(|c| c.name == "htmlStrip")
    );

    let custom = Analyzer {
        standard: None,
        preset: None,
        custom: Some(CustomAnalyzer {
            char_filters: vec![component("htmlStrip", &[])],
            tokenizer: component("whitespace", &[]),
            token_filters: vec![
                component("lowercase", &[]),
                component("stop", &[("ignoreCase", "true")]),
                component("porterStem", &[]),
            ],
            config_dir: None,
        }),
    };
    let result = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: custom,
            text: "<p>The Foxes running</p>".to_owned(),
        })
        .unwrap();
    assert_eq!(result.char_filters[0].text.trim(), "The Foxes running");
    let names: Vec<&str> = result.stages.iter().map(|s| s.name.as_str()).collect();
    assert_eq!(names, ["whitespace", "lowercase", "stop", "porterStem"]);
    let terms: Vec<&str> = result.stages[3]
        .tokens
        .iter()
        .map(|t| t.term.as_str())
        .collect();
    assert_eq!(terms, ["fox", "run"]);
    assert_eq!(result.stages[3].tokens[0].position, 1);

    // A preset from the list, by class name (ADR 0015).
    let english = components
        .analyzers
        .iter()
        .find(|a| a.name == "EnglishAnalyzer")
        .unwrap();
    let preset = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: Analyzer {
                standard: None,
                preset: Some(PresetAnalyzer {
                    class_name: english.class_name.clone(),
                }),
                custom: None,
            },
            text: "The Foxes running".to_owned(),
        })
        .unwrap();
    assert_eq!(preset.stages.len(), 1);
    assert_eq!(preset.stages[0].name, "EnglishAnalyzer");
    let terms: Vec<&str> = preset.stages[0]
        .tokens
        .iter()
        .map(|t| t.term.as_str())
        .collect();
    assert_eq!(terms, ["fox", "run"]);

    let broken = Analyzer {
        standard: None,
        preset: None,
        custom: Some(CustomAnalyzer {
            char_filters: Vec::new(),
            tokenizer: component("nope", &[]),
            token_filters: Vec::new(),
            config_dir: None,
        }),
    };
    let error = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: broken.clone(),
            text: "x".to_owned(),
        })
        .unwrap_err();
    assert!(
        matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::InvalidAnalyzer),
        "{error:?}"
    );

    // A search parses with the query's analyzer, and refuses one that can't be built.
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
        })
        .unwrap();
    let mut query = classic("x");
    query.classic.as_mut().unwrap().analyzer = broken;
    let error = server
        .call::<StartSearch>(StartSearchParams {
            query,
            similarity: bm25(),
            sort: Vec::new(),
            offset: 0,
            limit: 10,
            exact_total_hits: false,
            fields: None,
        })
        .unwrap_err();
    assert!(
        matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::InvalidAnalyzer),
        "{error:?}"
    );
}
