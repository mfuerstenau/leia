//! Talks to the real `leia-server.jar`. Needs a built jar, Java 25, and the test indexes,
//! analysis jars and codec jar that `./gradlew build` writes to `leia-core/build/`:
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
use leia::doc_export;
use leia::java::find_java;
use leia::protocol::ErrorCode;
use leia::protocol::analysis::{
    AnalysisComponent, Analyze, AnalyzeParams, Analyzer, CustomAnalyzer, GetAnalysisComponents,
    GetAnalysisComponentsParams, JapaneseMode, JapaneseOptions, KoreanDecompoundMode,
    KoreanOptions, LimitTokenCount, PresetAnalyzer, PresetOption, Shingles, WordSet,
    WordSetSupport,
};
use leia::protocol::check::{
    CheckIndexResult, GetCheckIndex, GetCheckIndexParams, StartCheckIndex, StartCheckIndexParams,
};
use leia::protocol::commits::{GetCommit, GetCommitParams, GetCommits, GetCommitsParams};
use leia::protocol::document::{
    BytesValue, DocumentField, GetDocument, GetDocumentParams, GetDocumentResult, GetReconstruct,
    GetStoredValue, GetStoredValueParams, GetTermVector, GetTermVectorParams, ReconstructResult,
    ReconstructSource, StartReconstruct, StartReconstructParams, StoredType, StoredValue,
    TermPosition,
};
use leia::protocol::filter::{
    FilterPattern, FilterResult, GetFilter, ReleaseFilter, ReleaseFilterParams, StartFilter,
    StartFilterParams, StepFilter, StepFilterParams, StepFilterResult,
};
use leia::protocol::index::{
    CloseIndex, CloseIndexParams, DirectoryImpl, DocValuesType, GetIndexStats, GetIndexStatsParams,
    IndexEntry, IndexOptions, OpenIndex, OpenIndexParams, OpenMode, ReopenIndex, ReopenIndexParams,
};
use leia::protocol::jars::{AddJars, AddJarsParams, GetJars, GetJarsParams};
use leia::protocol::job::{CancelJob, JobParams};
use leia::protocol::search::{
    Bm25Similarity, ClassicQuery, ClassicSimilarity, DateResolution, DocSort, DocValuesNumber,
    FieldSort, GetExplain, GetSearch, GetStoredScan, GetStoredScanParams, MAX_FUZZY_EDITS,
    MoreLikeThisQuery, ParserOptions, PointField, PointType, QueryOperator, ScoreSort, SearchQuery,
    Similarity, SortKey, SortMissing, SortSelector, SortType, StandardQuery, StartExplain,
    StartExplainParams, StartSearch, StartSearchParams, StartStoredScan, StartStoredScanParams,
    StoredScanHit, StoredScanResult, TotalHitsRelation,
};
use leia::protocol::terms::{
    ExportTermsResult, FieldTerms, GetExportTerms, GetFieldTermScan, GetPositions,
    GetPositionsParams, GetPostings, GetPostingsParams, GetPostingsResult, GetTerms,
    GetTermsParams, Posting, PostingsFilter, StartExportTerms, StartExportTermsParams,
    StartFieldTermScan, StartFieldTermScanParams, TermDelimiter, TermEntry, TopTerm,
};
use leia::protocol::version::{Version, VersionParams};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, find_server_jar};

fn start() -> Server {
    start_with(&[])
}

fn start_with(jars: &[PathBuf]) -> Server {
    let java = find_java(None, env::var_os("JAVA_HOME").as_deref()).unwrap();
    let jar = find_server_jar(env::var_os(SERVER_JAR_ENV).as_deref()).unwrap();
    Server::spawn(&java.executable, &jar, jars).unwrap()
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
    test_index("lucene9", "lucene9TestIndex")
}

/// A test index in `leia-core/build/test-indexes/`, written by the Gradle task `task`.
fn test_index(name: &str, task: &str) -> String {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../leia-core/build/test-indexes")
        .join(name);
    std::fs::canonicalize(dir)
        .unwrap_or_else(|_| panic!("run ./gradlew :leia-core:{task} first"))
        .to_str()
        .unwrap()
        .to_owned()
}

/// The jar with the codec `LeiaTest` and a postings, doc values and KNN vectors format, written
/// by `./gradlew :leia-core:testCodecJar` (ADR 0018). The indexes `custom-codec` and
/// `custom-formats` need it.
fn test_codec_jar() -> PathBuf {
    let jar = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../leia-core/build/test-codec-jar/leia-test-codec.jar");
    std::fs::canonicalize(jar).expect("run ./gradlew :leia-core:testCodecJar first")
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn reports_a_missing_codec() {
    let mut server = start();

    for (name, missing) in [
        ("custom-codec", "the codec 'LeiaTest'"),
        ("custom-formats", "the postings format 'LeiaTestPostings'"),
    ] {
        let error = server
            .call::<OpenIndex>(OpenIndexParams {
                path: test_index(name, "testCodecIndexes"),
                directory_impl: None,
                mode: None,
            })
            .unwrap_err();

        let CallError::Rpc(error) = error else {
            panic!("expected an RPC error, got {error:?}");
        };
        assert_eq!(error.code, ErrorCode::MissingCodec);
        assert_eq!(
            error.message,
            format!("Missing codec: the index needs {missing}, which isn't loaded")
        );
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn opens_indexes_with_codecs_from_a_jar() {
    let mut server = start_with(&[test_codec_jar()]);

    for name in ["custom-codec", "custom-formats"] {
        let index = server
            .call::<OpenIndex>(OpenIndexParams {
                path: test_index(name, "testCodecIndexes"),
                directory_impl: None,
                mode: None,
            })
            .unwrap();
        assert_eq!(index.max_doc, Some(3), "{name}");

        let document = server
            .call::<GetDocument>(GetDocumentParams { doc_id: 2 })
            .unwrap();
        let rank = document.fields.iter().find(|f| f.name == "rank").unwrap();
        assert!(rank.doc_values.is_some(), "{name}: {rank:?}");
        let commits = server
            .call::<GetCommits>(GetCommitsParams { index: None })
            .unwrap();
        assert_eq!(commits.commits.len(), 1, "{name}");
    }
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn adds_jars_while_running() {
    let mut server = start_with(&[test_analysis_jars("phonetic")]);
    let started = server.call::<GetJars>(GetJarsParams {}).unwrap().jars;
    assert_eq!(started.len(), 2, "{started:?}");
    let open = |server: &mut Server| {
        server.call::<OpenIndex>(OpenIndexParams {
            path: test_index("custom-codec", "testCodecIndexes"),
            directory_impl: None,
            mode: None,
        })
    };
    let Err(CallError::Rpc(error)) = open(&mut server) else {
        panic!("opens without the codec");
    };
    assert_eq!(error.code, ErrorCode::MissingCodec);

    let codec_jar = test_codec_jar().to_str().unwrap().to_owned();
    let added = server
        .call::<AddJars>(AddJarsParams {
            paths: vec![codec_jar.clone()],
        })
        .unwrap();

    assert_eq!(added.jars.len(), 1);
    assert!(added.jars[0].ends_with("leia-test-codec.jar"), "{added:?}");
    assert!(added.already_loaded.is_empty());
    assert_eq!(added.added.codecs, ["LeiaTest"]);
    assert_eq!(added.added.postings_formats, ["LeiaTestPostings"]);
    assert_eq!(added.added.doc_values_formats.len(), 1, "{added:?}");
    assert_eq!(added.added.knn_vectors_formats.len(), 1, "{added:?}");
    assert!(added.added.token_filters.is_empty());
    assert_eq!(open(&mut server).unwrap().max_doc, Some(3));

    // Loaded jars aren't loaded again.
    let again = server
        .call::<AddJars>(AddJarsParams {
            paths: vec![
                codec_jar,
                test_analysis_jars("phonetic").to_str().unwrap().to_owned(),
            ],
        })
        .unwrap();
    assert!(again.jars.is_empty());
    assert_eq!(again.already_loaded.len(), 3);

    // A jar's analyzers become presets, and analyze with the jar's resources.
    let stempel = server
        .call::<AddJars>(AddJarsParams {
            paths: vec![test_analysis_jars("stempel").to_str().unwrap().to_owned()],
        })
        .unwrap();
    assert!(
        stempel
            .added
            .analyzers
            .contains(&"org.apache.lucene.analysis.pl.PolishAnalyzer".to_owned()),
        "{stempel:?}"
    );
    let terms = analyzed(
        &mut server,
        preset(PresetAnalyzer {
            class_name: "org.apache.lucene.analysis.pl.PolishAnalyzer".to_owned(),
            ..Default::default()
        }),
        "kot i pies",
    );
    assert_eq!(terms, ["kot", "pies"]);

    let Err(CallError::Rpc(error)) = server.call::<AddJars>(AddJarsParams {
        paths: vec!["/no/such.jar".to_owned()],
    }) else {
        panic!("adds a jar that doesn't exist");
    };
    assert_eq!(error.code, ErrorCode::InvalidJar);
    assert_eq!(
        error.message,
        "Cannot add the jars: no such jar or directory: /no/such.jar"
    );
    let jars = server.call::<GetJars>(GetJarsParams {}).unwrap().jars;
    assert_eq!(jars.len(), 3 + stempel.jars.len());
    assert_eq!(jars[..2], started[..]);
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn a_loaded_jar_that_is_gone_stops_adding() {
    let dir = env::temp_dir().join(format!("leia-gone-jar-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let copy = dir.join("leia-test-codec.jar");
    std::fs::copy(test_codec_jar(), &copy).unwrap();
    let mut server = start_with(std::slice::from_ref(&copy));
    // Answered once the jars are loaded.
    let jars = server.call::<GetJars>(GetJarsParams {}).unwrap().jars;
    assert_eq!(jars.len(), 1);
    std::fs::remove_file(&copy).unwrap();

    let added = server.call::<AddJars>(AddJarsParams {
        paths: vec![test_analysis_jars("stempel").to_str().unwrap().to_owned()],
    });

    let Err(CallError::Rpc(error)) = added else {
        panic!("adds a jar after a loaded one is gone: {added:?}");
    };
    assert_eq!(error.code, ErrorCode::InvalidJar);
    assert!(
        error.message.starts_with(&format!(
            "Cannot add the jars: the jar {} was loaded before and is gone",
            copy.display()
        )),
        "{}",
        error.message
    );
    let _ = std::fs::remove_dir_all(&dir);
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
}

/// The `segments_N` file of the only index that a reader opened.
fn segments_file(indexes: &[IndexEntry]) -> &str {
    let [index] = indexes else {
        panic!("expected one index: {indexes:?}");
    };
    &index.reader.as_ref().unwrap().segments_file
}

/// A copy of the demo index without its last commit, `segments_4`, which the demo index keeps
/// with all the files of the earlier ones (`./gradlew :leia-core:demoIndex`).
fn demo_index_before_its_last_commit(dir: &Path) -> PathBuf {
    let demo = PathBuf::from(test_index("demo", "demoIndex"));
    let _ = std::fs::remove_dir_all(dir);
    std::fs::create_dir_all(dir).unwrap();
    for entry in std::fs::read_dir(&demo).unwrap() {
        let name = entry.unwrap().file_name();
        if name != "segments_4" && name != "write.lock" {
            std::fs::copy(demo.join(&name), dir.join(&name)).unwrap();
        }
    }
    demo.join("segments_4")
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn reopens_an_index_that_has_a_new_commit() {
    let mut server = start();
    let not_open = server.call::<ReopenIndex>(ReopenIndexParams {});
    assert!(
        matches!(&not_open, Err(CallError::Rpc(error)) if error.code == ErrorCode::NoIndexOpen),
        "{not_open:?}"
    );
    let dir = env::temp_dir().join(format!("leia-reopen-{}", std::process::id()));
    let last_commit = demo_index_before_its_last_commit(&dir);
    let opened = server
        .call::<OpenIndex>(OpenIndexParams {
            path: dir.to_str().unwrap().to_owned(),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    assert_eq!(segments_file(&opened.indexes), "segments_3");

    let unchanged = server.call::<ReopenIndex>(ReopenIndexParams {}).unwrap();
    assert!(!unchanged.changed);
    assert_eq!(segments_file(&unchanged.indexes), "segments_3");

    std::fs::copy(last_commit, dir.join("segments_4")).unwrap();
    let reopened = server.call::<ReopenIndex>(ReopenIndexParams {}).unwrap();
    assert!(reopened.changed);
    assert_eq!(segments_file(&reopened.indexes), "segments_4");
    assert_eq!(reopened.path, opened.path);
    let stats = server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .unwrap();
    assert_eq!(stats.indexes[0].generation, 4);

    server.call::<CloseIndex>(CloseIndexParams {}).unwrap();
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
    std::fs::remove_dir_all(&dir).unwrap();
}

/// Copies the files of the index `from` into `to`, leaving out those `skip` matches.
fn copy_index(from: &str, to: &Path, skip: impl Fn(&str) -> bool) {
    std::fs::create_dir_all(to).unwrap();
    for entry in std::fs::read_dir(from).unwrap() {
        let name = entry.unwrap().file_name();
        let text = name.to_str().unwrap();
        if text != "write.lock" && !skip(text) {
            std::fs::copy(Path::new(from).join(&name), to.join(&name)).unwrap();
        }
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn opens_several_indexes_and_one_without_a_reader() {
    let mut server = start();
    let dir = env::temp_dir().join(format!("leia-multi-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    copy_index(&lucene9_index(), &dir.join("a"), |_| false);
    copy_index(&lucene9_index(), &dir.join("b/index"), |_| false);
    // A reader can't open an index without its .si files.
    copy_index(&lucene9_index(), &dir.join("c"), |name| {
        name.ends_with(".si")
    });
    // The demo index indexes `body` with offsets, the lucene9 index without.
    copy_index(&test_index("demo", "demoIndex"), &dir.join("d"), |_| false);
    let path = |sub: &str| dir.join(sub).to_str().unwrap().to_owned();

    let opened = server
        .call::<OpenIndex>(OpenIndexParams {
            path: path(""),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    assert_eq!(opened.mode, OpenMode::Reader);
    let paths: Vec<&str> = opened
        .indexes
        .iter()
        .map(|index| index.path.as_str())
        .collect();
    assert_eq!(paths, [path("a"), path("b/index")], "{opened:?}");
    let second = opened.indexes[1].reader.as_ref().unwrap();
    assert_eq!(second.doc_base, 3);
    assert_eq!(opened.max_doc, Some(3 + second.max_doc));
    let [corrupt, conflicting] = opened.skipped.as_slice() else {
        panic!("expected two skipped indexes: {opened:?}");
    };
    assert_eq!(corrupt.path, path("c"));
    assert!(corrupt.message.starts_with("Corrupt index"), "{corrupt:?}");
    assert_eq!(conflicting.path, path("d"));
    assert!(
        conflicting
            .message
            .starts_with("Its fields conflict with those of the indexes before it"),
        "{conflicting:?}"
    );

    let stats = server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .unwrap();
    assert_eq!(stats.indexes.len(), 2);
    assert_eq!(stats.max_doc, opened.max_doc.unwrap());
    let document = server
        .call::<GetDocument>(GetDocumentParams { doc_id: 3 })
        .unwrap();
    assert_eq!(document.doc_id, 3);
    let commits = server
        .call::<GetCommits>(GetCommitsParams { index: Some(1) })
        .unwrap();
    assert_eq!(commits.commits[0].segments_file, second.segments_file);

    let without_reader = server
        .call::<OpenIndex>(OpenIndexParams {
            path: path("c"),
            directory_impl: None,
            mode: Some(OpenMode::DirectoryOnly),
        })
        .unwrap();
    assert_eq!(without_reader.mode, OpenMode::DirectoryOnly);
    assert_eq!(without_reader.max_doc, None);
    assert_eq!(without_reader.indexes[0].reader, None);
    let stats = server.call::<GetIndexStats>(GetIndexStatsParams {});
    assert!(
        matches!(&stats, Err(CallError::Rpc(error)) if error.code == ErrorCode::NoReader),
        "{stats:?}"
    );
    let commits = server
        .call::<GetCommits>(GetCommitsParams { index: None })
        .unwrap();
    assert!(commits.commits.is_empty(), "{commits:?}");
    let [unreadable] = commits.unreadable.as_slice() else {
        panic!("expected one unreadable commit: {commits:?}");
    };
    assert_eq!(unreadable.segments_file, "segments_2");
    assert!(
        unreadable
            .message
            .starts_with("Corrupt index, missing file"),
        "{unreadable:?}"
    );

    server.call::<CloseIndex>(CloseIndexParams {}).unwrap();
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn opens_lucene9_index() {
    let mut server = start();
    let path = lucene9_index();

    let index = server
        .call::<OpenIndex>(OpenIndexParams {
            path: path.clone(),
            directory_impl: None,
            mode: None,
        })
        .unwrap();

    assert_eq!(index.path, path);
    assert_eq!(index.mode, OpenMode::Reader);
    assert_eq!((index.num_docs, index.max_doc), (Some(2), Some(3)));
    assert!(index.skipped.is_empty());
    let [entry] = index.indexes.as_slice() else {
        panic!("expected one index: {index:?}");
    };
    assert_eq!(entry.path, path);
    let reader = entry.reader.as_ref().unwrap();
    assert_eq!(reader.segments_file, "segments_2");
    assert_eq!(reader.created_version_major, 9);
    assert!(
        reader
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
            directory_impl: None,
            mode: None,
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
        .call::<OpenIndex>(OpenIndexParams {
            path: path.clone(),
            directory_impl: None,
            mode: None,
        })
        .unwrap();

    let stats = server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .unwrap();

    assert_eq!(stats.path, path);
    let commit = &stats.indexes[0];
    assert_eq!(
        (commit.segments_file.as_str(), commit.generation),
        ("segments_2", 2)
    );
    assert_eq!(
        (stats.segment_count, stats.num_docs, stats.max_doc),
        (1, 2, 3)
    );
    assert!(commit.commit_lucene_version.starts_with("9."), "{stats:?}");
    assert_eq!(
        commit.user_data.get("writer").map(String::as_str),
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
fn opens_through_the_chosen_directory() {
    let mut server = start();
    for (directory_impl, class) in [
        (None, "MMapDirectory"),
        (Some(DirectoryImpl::Nio), "NIOFSDirectory"),
        (Some(DirectoryImpl::Mmap), "MMapDirectory"),
    ] {
        server
            .call::<OpenIndex>(OpenIndexParams {
                path: lucene9_index(),
                directory_impl,
                mode: None,
            })
            .unwrap();

        let stats = server
            .call::<GetIndexStats>(GetIndexStatsParams {})
            .unwrap();

        assert_eq!(stats.directory_impl, class, "{directory_impl:?}");
    }
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
        .call::<OpenIndex>(OpenIndexParams {
            path: path.clone(),
            directory_impl: None,
            mode: None,
        })
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
            directory_impl: None,
            mode: None,
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
            directory_impl: None,
            mode: None,
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
            directory_impl: None,
            mode: None,
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
            directory_impl: None,
            mode: None,
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
            filter: None,
        })
        .unwrap();
    let posting = |doc_id: i32, deleted: bool| Posting {
        doc_id,
        deleted,
        freq: Some(1),
        in_filter: None,
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
            filter: None,
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
            directory_impl: None,
            mode: None,
        })
        .unwrap();

    let commits = server
        .call::<GetCommits>(GetCommitsParams { index: None })
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
        .call::<GetCommit>(GetCommitParams {
            generation: 2,
            index: None,
        })
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
        .call::<GetCommit>(GetCommitParams {
            generation: 1,
            index: None,
        })
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
            directory_impl: None,
            mode: None,
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
                field: Some(FieldSort {
                    field: "id".to_owned(),
                    sort_type: SortType::String,
                    reverse: false,
                    selector: SortSelector::Min,
                    missing: SortMissing::Default,
                }),
                ..SortKey::default()
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
    // The document id and the score need no doc values (ADR 0025).
    let job = server
        .call::<StartSearch>(StartSearchParams {
            query: classic("number"),
            similarity: bm25(),
            sort: vec![
                SortKey {
                    doc: Some(DocSort { reverse: true }),
                    ..SortKey::default()
                },
                SortKey {
                    score: Some(ScoreSort { reverse: false }),
                    ..SortKey::default()
                },
            ],
            offset: 0,
            limit: 10,
            exact_total_hits: true,
            fields: None,
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
    let ids: Vec<i32> = results.hits.iter().map(|hit| hit.doc_id).collect();
    let mut descending = ids.clone();
    descending.sort_unstable_by(|a, b| b.cmp(a));
    assert!(ids.len() > 1, "{ids:?}");
    assert_eq!(ids, descending);
    let first = &results.hits[0];
    assert_eq!(
        first.sort_values[0].number.as_deref(),
        Some(first.doc_id.to_string().as_str())
    );
    assert_eq!(
        first.sort_values[1]
            .number
            .as_ref()
            .unwrap()
            .parse::<f64>()
            .unwrap(),
        first.score
    );
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn searches_for_documents_like_one_of_a_lucene9_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    let like_query = |doc_id: Option<i32>, text: Option<&str>, fields: &[&str]| SearchQuery {
        more_like_this: Some(MoreLikeThisQuery {
            doc_id,
            text: text.map(str::to_owned),
            fields: fields.iter().map(|field| (*field).to_owned()).collect(),
            min_term_freq: 1,
            min_doc_freq: 2,
            max_doc_freq: None,
            max_query_terms: 25,
            boost: false,
            analyzer: Analyzer::default(),
            min_word_len: 0,
            max_word_len: None,
            stop_words: Vec::new(),
        }),
        ..SearchQuery::default()
    };
    let like = |doc_id: i32, fields: &[&str]| like_query(Some(doc_id), None, fields);
    let params = |query: SearchQuery| StartSearchParams {
        query,
        similarity: bm25(),
        sort: Vec::new(),
        offset: 0,
        limit: 10,
        exact_total_hits: true,
        fields: None,
    };

    let deadline = Instant::now() + Duration::from_secs(10);
    let mut search = |query: SearchQuery| {
        let job = server.call::<StartSearch>(params(query)).unwrap();
        loop {
            let poll = server
                .call::<GetSearch>(JobParams { job_id: job.job_id })
                .unwrap();
            if let Some(results) = poll.results {
                break results;
            }
            assert!(Instant::now() < deadline, "the search did not finish");
            thread::sleep(Duration::from_millis(10));
        }
    };
    let results = search(like(0, &["body"]));
    let text = search(like_query(None, Some("Number DOCUMENT number"), &["body"]));

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
    let chosen: Vec<&str> = results
        .more_like_this_terms
        .iter()
        .flatten()
        .map(|term| term.term.as_str())
        .collect();
    assert_eq!(chosen.len(), 2);

    // Like a text: "number" occurs twice in it, so it comes first.
    assert_eq!(
        text.query,
        "like(text \"Number DOCUMENT number\", fields [body], minTermFreq 1, minDocFreq 2, \
         maxQueryTerms 25)"
    );
    let terms = text.more_like_this_terms.expect("the chosen terms");
    let words: Vec<(&str, i32)> = terms
        .iter()
        .map(|term| (term.term.as_str(), term.tf))
        .collect();
    assert_eq!(words, [("number", 2), ("document", 1)]);
    assert!(
        terms
            .iter()
            .all(|term| term.field == "body" && term.doc_freq >= 2)
    );
    assert!(!text.hits.is_empty());

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

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn searches_date_ranges_of_the_demo_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: test_index("demo", "demoIndex"),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    let mut search = |query: SearchQuery| {
        let job = server
            .call::<StartSearch>(StartSearchParams {
                query,
                similarity: bm25(),
                sort: Vec::new(),
                offset: 0,
                limit: 10,
                exact_total_hits: true,
                fields: None,
            })
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let poll = server
                .call::<GetSearch>(JobParams { job_id: job.job_id })
                .unwrap();
            if let Some(results) = poll.results {
                break results;
            }
            assert!(Instant::now() < deadline, "the search did not finish");
            thread::sleep(Duration::from_millis(10));
        }
    };
    let dates = |expression: &str, options: ParserOptions| {
        let mut query = classic(expression);
        query.classic.as_mut().unwrap().options = options;
        query
    };
    let january_in_utc = ParserOptions {
        date_resolution: Some(DateResolution::Day),
        locale: Some("en-US".to_owned()),
        time_zone: Some("UTC".to_owned()),
        ..ParserOptions::default()
    };

    // `published` is the day of `timestamp` in UTC, as `DateTools` writes it.
    let published = search(dates("published:[1/1/26 TO 1/31/26]", january_in_utc));
    assert_eq!(published.query, "published:[20260101 TO 20260131]");
    let timestamps = search(SearchQuery {
        standard: Some(StandardQuery {
            expression: "timestamp:[1767225600000 TO 1769903999999]".to_owned(),
            default_field: "body".to_owned(),
            default_operator: QueryOperator::Or,
            options: ParserOptions::default(),
            points: vec![PointField {
                field: "timestamp".to_owned(),
                point_type: PointType::Long,
            }],
            analyzer: Analyzer::default(),
        }),
        ..SearchQuery::default()
    });
    assert!(published.total_hits > 0);
    assert_eq!(published.total_hits, timestamps.total_hits);
    // Without a resolution, the bounds are text, which no day matches.
    let text = search(dates(
        "published:[1/1/26 TO 1/31/26]",
        ParserOptions::default(),
    ));
    assert_eq!(text.total_hits, 0);
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn searches_the_doc_values_of_the_demo_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: test_index("demo", "demoIndex"),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    let checksum = server
        .call::<GetDocument>(GetDocumentParams { doc_id: 5 })
        .unwrap()
        .fields
        .into_iter()
        .find(|field| field.name == "checksum")
        .and_then(|field| field.doc_values)
        .unwrap()
        .values
        .remove(0);
    let mut search = |query: SearchQuery| {
        let job = server
            .call::<StartSearch>(StartSearchParams {
                query,
                similarity: bm25(),
                sort: Vec::new(),
                offset: 0,
                limit: 1000,
                exact_total_hits: true,
                fields: Some(Vec::new()),
            })
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let poll = server
                .call::<GetSearch>(JobParams { job_id: job.job_id })
                .unwrap();
            if let Some(results) = poll.results {
                break results;
            }
            assert!(Instant::now() < deadline, "the search did not finish");
            thread::sleep(Duration::from_millis(10));
        }
    };
    let doc_values =
        |field: &str, number: Option<DocValuesNumber>, line: &str, hex: bool| SearchQuery {
            doc_values: Some(leia::doc_values::query(field, number, hex, line).unwrap()),
            ..SearchQuery::default()
        };
    let points = |expression: &str, field: &str, point_type: PointType| SearchQuery {
        standard: Some(StandardQuery {
            expression: expression.to_owned(),
            default_field: "body".to_owned(),
            default_operator: QueryOperator::Or,
            options: ParserOptions::default(),
            points: vec![PointField {
                field: field.to_owned(),
                point_type,
            }],
            analyzer: Analyzer::default(),
        }),
        ..SearchQuery::default()
    };

    // Each field's doc values hold what its points or terms hold.
    let quantity = search(doc_values(
        "quantity",
        Some(DocValuesNumber::Long),
        "[-5 TO 5]",
        false,
    ));
    assert!(quantity.total_hits > 0);
    assert_eq!(
        quantity.total_hits,
        search(points("quantity:[-5 TO 5]", "quantity", PointType::Int)).total_hits
    );
    // price has raw double bits, which order negative numbers backwards.
    let price = search(doc_values(
        "price",
        Some(DocValuesNumber::Double),
        "{* TO 10]",
        false,
    ));
    assert!(price.total_hits > 0);
    assert_eq!(
        price.total_hits,
        search(points("price:[* TO 10]", "price", PointType::Double)).total_hits
    );
    let kitchen = search(doc_values("category", None, "kitchen", false));
    assert!(kitchen.total_hits > 0);
    assert_eq!(
        kitchen.total_hits,
        search(classic("category:kitchen")).total_hits
    );
    // A binary value is found by scanning every document's.
    let same = search(doc_values(
        "checksum",
        None,
        &leia::doc_values::quote(&checksum.value),
        checksum.binary,
    ));
    assert!(same.hits.iter().any(|hit| hit.doc_id == 5), "{same:?}");
    assert!(same.query.starts_with("checksum:["), "{}", same.query);
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn browses_a_lucene9_index_through_a_filter() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
            directory_impl: None,
            mode: None,
        })
        .unwrap();

    let query = |include_deleted: bool| StartFilterParams {
        query: Some(classic("number AND (1 OR 2 OR 3)")),
        pattern: None,
        include_deleted,
    };
    // "document number N" for N = 1..3; document 1 (id 2) is deleted.
    let live = build_filter(&mut server, query(false));
    let all = build_filter(&mut server, query(true));
    assert_eq!(live.query, "+body:number +(body:1 body:2 body:3)");
    assert_eq!((live.match_count, all.match_count), (2, 3));

    let mut step = |filter_id: i64, from_doc: i32, forward: bool| {
        server.call::<StepFilter>(StepFilterParams {
            filter_id,
            from_doc,
            forward,
        })
    };
    // The newer filter replaced the older one.
    let Err(CallError::Rpc(error)) = step(live.filter_id, 0, true) else {
        panic!("the replaced filter is still kept");
    };
    assert_eq!(error.code, ErrorCode::FilterNotFound);
    let found = |result: Result<StepFilterResult, CallError>| {
        result
            .unwrap()
            .found
            .map(|found| (found.doc_id, found.index))
    };
    assert_eq!(found(step(all.filter_id, 1, true)), Some((1, 1)));
    assert_eq!(found(step(all.filter_id, 3, true)), None);
    assert_eq!(found(step(all.filter_id, 3, false)), Some((2, 2)));

    let release = |server: &mut Server| {
        server
            .call::<ReleaseFilter>(ReleaseFilterParams {
                filter_id: all.filter_id,
            })
            .unwrap()
            .released
    };
    assert!(release(&mut server));
    assert!(!release(&mut server));

    // A pattern over the stored values, and the postings of a term through it (ADR 0032).
    let pattern = build_filter(
        &mut server,
        StartFilterParams {
            query: None,
            pattern: Some(FilterPattern {
                pattern: "NUMBER [13]".to_owned(),
                case_insensitive: true,
                fields: Some(vec!["body".to_owned()]),
            }),
            include_deleted: false,
        },
    );
    assert_eq!(pattern.query, "/NUMBER [13]/i");
    assert_eq!(pattern.match_count, 2);
    assert_eq!(pattern.timed_out_values, Some(0));
    let mut postings = |only_matches: bool| {
        server
            .call::<GetPostings>(GetPostingsParams {
                field: "body".to_owned(),
                term: BytesValue {
                    value: "number".to_owned(),
                    binary: false,
                },
                from_doc: 0,
                limit: 10,
                filter: Some(PostingsFilter {
                    filter_id: pattern.filter_id,
                    only_matches,
                }),
            })
            .unwrap()
            .postings
            .into_iter()
            .map(|posting| (posting.doc_id, posting.in_filter))
            .collect::<Vec<_>>()
    };
    assert_eq!(
        postings(false),
        vec![(0, Some(true)), (1, Some(false)), (2, Some(true))]
    );
    assert_eq!(postings(true), vec![(0, Some(true)), (2, Some(true))]);
}

/// Builds a filter and waits until the server keeps it.
fn build_filter(server: &mut Server, params: StartFilterParams) -> FilterResult {
    let job = server.call::<StartFilter>(params).unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let poll = server
            .call::<GetFilter>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(result) = poll.result {
            break result;
        }
        assert!(Instant::now() < deadline, "the filter was not built");
        thread::sleep(Duration::from_millis(10));
    }
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
                    stopwords: None,
                    stem_exclusion: None,
                    ..Default::default()
                }),
                custom: None,
                shingles: None,
                limit_token_count: None,
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

/// The terms of the last stage.
fn analyzed(server: &mut Server, analyzer: Analyzer, text: &str) -> Vec<String> {
    let result = server
        .call::<Analyze>(AnalyzeParams {
            analyzer,
            text: text.to_owned(),
        })
        .unwrap();
    result
        .stages
        .last()
        .unwrap()
        .tokens
        .iter()
        .map(|t| t.term.clone())
        .collect()
}

fn preset(preset: PresetAnalyzer) -> Analyzer {
    Analyzer {
        standard: None,
        preset: Some(preset),
        custom: None,
        shingles: None,
        limit_token_count: None,
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn analyzes_with_wrappers_and_preset_options() {
    let mut server = start_with(&[test_analysis_jars("kuromoji"), test_analysis_jars("nori")]);

    let components = server
        .call::<GetAnalysisComponents>(GetAnalysisComponentsParams {})
        .unwrap();
    let options = |name: &str| {
        let analyzer = components
            .analyzers
            .iter()
            .find(|a| a.name == name)
            .unwrap();
        (analyzer.stopwords, analyzer.options.clone())
    };
    assert_eq!(
        options("JapaneseAnalyzer"),
        (WordSetSupport::Optional, vec![PresetOption::Japanese])
    );
    assert_eq!(
        options("KoreanAnalyzer"),
        (WordSetSupport::None, vec![PresetOption::Korean])
    );
    assert_eq!(
        options("StandardAnalyzer").1,
        [PresetOption::MaxTokenLength]
    );
    assert_eq!(options("DutchAnalyzer").1, [PresetOption::StemOverride]);

    let japanese = PresetAnalyzer {
        class_name: "org.apache.lucene.analysis.ja.JapaneseAnalyzer".to_owned(),
        japanese: Some(JapaneseOptions {
            mode: Some(JapaneseMode::Normal),
            ..JapaneseOptions::default()
        }),
        ..PresetAnalyzer::default()
    };
    assert_eq!(
        analyzed(&mut server, preset(japanese), "関西国際空港に行った"),
        ["関西国際空港", "行く"]
    );

    let mut korean = PresetAnalyzer {
        class_name: "org.apache.lucene.analysis.ko.KoreanAnalyzer".to_owned(),
        korean: Some(KoreanOptions {
            decompound_mode: Some(KoreanDecompoundMode::Mixed),
            ..KoreanOptions::default()
        }),
        ..PresetAnalyzer::default()
    };
    assert_eq!(
        analyzed(&mut server, preset(korean.clone()), "백두산"),
        ["백두산", "백두", "산"]
    );
    korean.korean.as_mut().unwrap().stop_tags = Some(WordSet::words(vec!["NOPE".to_owned()]));
    let error = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: preset(korean),
            text: "백두산".to_owned(),
        })
        .unwrap_err();
    assert!(
        matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::InvalidAnalyzer
            && e.message == "Invalid analyzer: stop tags: no part of speech 'NOPE'"),
        "{error:?}"
    );

    // The longest token, then shingles of the pieces, then the first two tokens.
    let standard = PresetAnalyzer {
        class_name: "org.apache.lucene.analysis.standard.StandardAnalyzer".to_owned(),
        max_token_length: Some(3),
        ..PresetAnalyzer::default()
    };
    let wrapped = Analyzer {
        shingles: Some(Shingles::default()),
        limit_token_count: Some(LimitTokenCount {
            max_token_count: 2,
            consume_all_tokens: false,
        }),
        ..preset(standard)
    };
    let result = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: wrapped,
            text: "abcdef".to_owned(),
        })
        .unwrap();
    let stages: Vec<&str> = result.stages.iter().map(|s| s.name.as_str()).collect();
    assert_eq!(
        stages,
        [
            "StandardAnalyzer",
            "ShingleAnalyzerWrapper",
            "LimitTokenCountAnalyzer"
        ]
    );
    let terms: Vec<&str> = result.stages[2]
        .tokens
        .iter()
        .map(|t| t.term.as_str())
        .collect();
    assert_eq!(terms, ["abc", "abc def"]);
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
        message.contains("Cannot load the jars: cannot load the analysis factories")
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
        shingles: None,
        limit_token_count: None,
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
                    stopwords: None,
                    stem_exclusion: None,
                    ..Default::default()
                }),
                custom: None,
                shingles: None,
                limit_token_count: None,
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

    // Its own stop words and stem exclusions (ADR 0017).
    assert_eq!(english.stopwords, WordSetSupport::Optional);
    assert_eq!(english.stem_exclusion, WordSetSupport::Optional);
    let preset = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: Analyzer {
                standard: None,
                preset: Some(PresetAnalyzer {
                    class_name: english.class_name.clone(),
                    stopwords: Some(WordSet::words(vec!["foxes".to_owned()])),
                    stem_exclusion: Some(WordSet::words(vec!["running".to_owned()])),
                    ..Default::default()
                }),
                custom: None,
                shingles: None,
                limit_token_count: None,
            },
            text: "The Foxes running".to_owned(),
        })
        .unwrap();
    let terms: Vec<&str> = preset.stages[0]
        .tokens
        .iter()
        .map(|t| t.term.as_str())
        .collect();
    assert_eq!(terms, ["the", "running"]);
    let stop = components
        .analyzers
        .iter()
        .find(|a| a.name == "StopAnalyzer")
        .unwrap();
    assert_eq!(stop.stopwords, WordSetSupport::Required);
    let error = server
        .call::<Analyze>(AnalyzeParams {
            analyzer: Analyzer {
                standard: None,
                preset: Some(PresetAnalyzer {
                    class_name: stop.class_name.clone(),
                    stopwords: None,
                    stem_exclusion: None,
                    ..Default::default()
                }),
                custom: None,
                shingles: None,
                limit_token_count: None,
            },
            text: "x".to_owned(),
        })
        .unwrap_err();
    assert!(
        matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::InvalidAnalyzer
            && e.message.contains("StopAnalyzer needs stop words")),
        "{error:?}"
    );

    let broken = Analyzer {
        standard: None,
        preset: None,
        custom: Some(CustomAnalyzer {
            char_filters: Vec::new(),
            tokenizer: component("nope", &[]),
            token_filters: Vec::new(),
            config_dir: None,
        }),
        shingles: None,
        limit_token_count: None,
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
            directory_impl: None,
            mode: None,
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

/// Scans the stored values as the TUI does, collecting the hits, until the outcome is there.
fn await_stored_scan(
    server: &mut Server,
    params: StartStoredScanParams,
) -> (Vec<StoredScanHit>, StoredScanResult) {
    let job = server.call::<StartStoredScan>(params).unwrap();
    let deadline = Instant::now() + Duration::from_secs(30);
    let mut hits = Vec::new();
    loop {
        let poll = server
            .call::<GetStoredScan>(GetStoredScanParams {
                job_id: job.job_id,
                from_hit: i32::try_from(hits.len()).unwrap(),
            })
            .unwrap();
        hits.extend(poll.hits);
        assert_eq!(usize::try_from(poll.hit_count).unwrap(), hits.len());
        if let Some(result) = poll.result {
            return (hits, result);
        }
        assert!(Instant::now() < deadline, "the scan did not finish");
        thread::sleep(Duration::from_millis(10));
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn scans_the_stored_values_of_the_demo_index() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: test_index("demo", "demoIndex"),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    let scan = |pattern: &str, fields: &[&str], include_deleted: bool| StartStoredScanParams {
        pattern: pattern.to_owned(),
        case_insensitive: false,
        fields: Some(fields.iter().map(|&field| field.to_owned()).collect()),
        include_deleted,
    };

    let (hits, result) = await_stored_scan(&mut server, scan("sku\\d{4}", &["body"], false));
    assert!(!hits.is_empty());
    assert_eq!(result.scanned_docs, 500);
    assert_eq!(result.matched_values, i64::try_from(hits.len()).unwrap());
    assert!(hits.iter().all(|hit| !hit.deleted && hit.field == "body"));
    // The spans are byte offsets into the whole value, which getStoredValue returns.
    let hit = &hits[0];
    let value = server
        .call::<GetStoredValue>(GetStoredValueParams {
            doc_id: hit.doc_id,
            field: hit.field.clone(),
            index: hit.value_index,
        })
        .unwrap();
    let span = hit.spans[0];
    let matched = &value.value.value[span.start as usize..span.end as usize];
    assert!(
        matched.starts_with("sku") && matched.len() == 7,
        "{matched}"
    );

    // `deleted_only` is only in document 74, which is deleted.
    let (live, _) = await_stored_scan(&mut server, scan(".", &["deleted_only"], false));
    assert!(live.is_empty());
    let (deleted, _) = await_stored_scan(&mut server, scan(".", &["deleted_only"], true));
    assert_eq!(
        deleted
            .iter()
            .map(|hit| (hit.doc_id, hit.deleted))
            .collect::<Vec<_>>(),
        [(74, true)]
    );

    let error = server
        .call::<StartStoredScan>(scan("(sku", &["body"], false))
        .unwrap_err();
    assert!(
        matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::InvalidQuery),
        "{error:?}"
    );
}

/// Polls a check as the TUI does, collecting its lines, until the outcome is there.
fn await_check(server: &mut Server, job_id: i64) -> (Vec<String>, CheckIndexResult) {
    let deadline = Instant::now() + Duration::from_secs(30);
    let mut lines = Vec::new();
    loop {
        let poll = server
            .call::<GetCheckIndex>(GetCheckIndexParams {
                job_id,
                from_line: i32::try_from(lines.len()).unwrap(),
            })
            .unwrap();
        lines.extend(poll.lines);
        assert_eq!(usize::try_from(poll.line_count).unwrap(), lines.len());
        if let Some(result) = poll.result {
            return (lines, result);
        }
        assert!(Instant::now() < deadline, "the check did not finish");
        thread::sleep(Duration::from_millis(10));
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn checks_the_indexes_with_and_without_a_reader() {
    let mut server = start();
    let dir = env::temp_dir().join(format!("leia-check-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    copy_index(&lucene9_index(), &dir.join("a"), |_| false);
    copy_index(&lucene9_index(), &dir.join("b"), |name| {
        name.ends_with(".si")
    });
    let path = |sub: &str| dir.join(sub).to_str().unwrap().to_owned();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: path(""),
            directory_impl: Some(DirectoryImpl::Nio),
            mode: None,
        })
        .unwrap();

    let job = server
        .call::<StartCheckIndex>(StartCheckIndexParams { level: Some(2) })
        .unwrap();
    let (lines, result) = await_check(&mut server, job.job_id);

    assert!(
        lines.contains(&format!("Checking index 1 of 2: {}", path("a"))),
        "{lines:?}"
    );
    assert!(
        lines.contains(&"No problems were detected with this index.".to_owned()),
        "{lines:?}"
    );
    let [a, b] = result.indexes.as_slice() else {
        panic!("expected two indexes: {result:?}");
    };
    assert_eq!(
        (a.path.as_str(), a.skipped, a.clean),
        (path("a").as_str(), false, true)
    );
    assert!(!a.segments.is_empty());
    assert_eq!(
        (b.path.as_str(), b.skipped, b.clean),
        (path("b").as_str(), true, false)
    );
    assert!(b.missing_segments, "{b:?}");

    // A level out of range, and a check without a reader.
    let invalid = server.call::<StartCheckIndex>(StartCheckIndexParams { level: Some(4) });
    assert!(
        matches!(&invalid, Err(CallError::Rpc(e)) if e.code == ErrorCode::InvalidParams),
        "{invalid:?}"
    );
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: path("b"),
            directory_impl: None,
            mode: Some(OpenMode::DirectoryOnly),
        })
        .unwrap();
    let job = server
        .call::<StartCheckIndex>(StartCheckIndexParams { level: None })
        .unwrap();
    let (_, result) = await_check(&mut server, job.job_id);
    assert_eq!(result.indexes.len(), 1);
    assert!(result.indexes[0].missing_segments);

    // Cancelling forgets the job.
    let job = server
        .call::<StartCheckIndex>(StartCheckIndexParams { level: Some(3) })
        .unwrap();
    server
        .call::<CancelJob>(JobParams { job_id: job.job_id })
        .unwrap();
    let gone = server.call::<GetCheckIndex>(GetCheckIndexParams {
        job_id: job.job_id,
        from_line: 0,
    });
    assert!(
        matches!(&gone, Err(CallError::Rpc(e)) if e.code == ErrorCode::JobNotFound),
        "{gone:?}"
    );

    server.call::<CloseIndex>(CloseIndexParams {}).unwrap();
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn exports_the_terms_of_a_field() {
    let mut server = start();
    let dir = env::temp_dir().join(format!("leia-export-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    copy_index(&lucene9_index(), &dir.join("index"), |_| false);
    let path = |sub: &str| dir.join(sub).to_str().unwrap().to_owned();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: path("index"),
            directory_impl: None,
            mode: None,
        })
        .unwrap();

    let job = server
        .call::<StartExportTerms>(StartExportTermsParams {
            field: "body".to_owned(),
            path: path("body.out"),
            delimiter: Some(TermDelimiter::Comma),
            overwrite: None,
        })
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    let result = loop {
        let poll = server
            .call::<GetExportTerms>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(result) = poll.result {
            break result;
        }
        assert!(Instant::now() < deadline, "the export did not finish");
        thread::sleep(Duration::from_millis(10));
    };

    // "document number N" for N = 1..3; the deleted document 2 still counts.
    let written = std::fs::read_to_string(dir.join("body.out")).unwrap();
    assert_eq!(written, "1,1\n2,1\n3,1\ndocument,3\nnumber,3\n");
    assert_eq!(
        result,
        ExportTermsResult {
            path: path("body.out"),
            terms: 5,
            bytes: i64::try_from(written.len()).unwrap(),
        }
    );

    for (file, overwrite) in [("body.out", None), ("index/body.out", Some(true))] {
        let refused = server.call::<StartExportTerms>(StartExportTermsParams {
            field: "body".to_owned(),
            path: path(file),
            delimiter: None,
            overwrite,
        });
        assert!(
            matches!(&refused, Err(CallError::Rpc(e)) if e.code == ErrorCode::OutputNotWritable),
            "{refused:?}"
        );
    }
    assert!(!dir.join("index/body.out").exists());

    server.call::<CloseIndex>(CloseIndexParams {}).unwrap();
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
    std::fs::remove_dir_all(&dir).unwrap();
}

/// Rebuilds the fields of a document and waits for the outcome.
fn reconstruct(server: &mut Server, doc_id: i32, fields: &[&str]) -> ReconstructResult {
    let fields = Some(fields.iter().map(|&field| field.to_owned()).collect());
    reconstruct_fields(server, StartReconstructParams { doc_id, fields })
}

/// Starts the rebuild and waits for its outcome.
fn reconstruct_fields(server: &mut Server, params: StartReconstructParams) -> ReconstructResult {
    let job = server.call::<StartReconstruct>(params).unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let poll = server
            .call::<GetReconstruct>(JobParams { job_id: job.job_id })
            .unwrap();
        if let Some(result) = poll.result {
            return result;
        }
        assert!(Instant::now() < deadline, "the rebuild did not finish");
        thread::sleep(Duration::from_millis(10));
    }
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn exports_a_document_with_everything_leia_knows() {
    let mut server = start();
    let path = test_index("demo", "demoIndex");
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: path.clone(),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    let stats = server
        .call::<GetIndexStats>(GetIndexStatsParams {})
        .unwrap();
    let schema = stats
        .fields
        .into_iter()
        .map(|field| (field.name.clone(), field))
        .collect();

    // Document 42 has the blob, which getDocument cuts, and 5000 words of body.
    let data = doc_export::load(&mut server, 42).unwrap();
    let rebuilt = reconstruct_fields(
        &mut server,
        StartReconstructParams {
            doc_id: 42,
            fields: None,
        },
    );
    let export = doc_export::assemble(data, Some(rebuilt.fields), &schema, &path, None);
    let field = |name: &str| {
        export
            .fields
            .iter()
            .find(|field| field.name == name)
            .unwrap_or_else(|| panic!("no {name}"))
    };

    assert_eq!((export.doc_id, export.indexed_terms), (42, true));
    let blob = &field("blob").stored[0];
    assert!(blob.binary);
    assert_eq!(blob.full_length, None);
    assert_eq!(blob.value.len(), 200_000);
    let body = field("body");
    assert!(
        body.term_vector
            .as_ref()
            .is_some_and(|terms| !terms.is_empty())
    );
    let indexed = body.indexed.as_ref().unwrap();
    assert_eq!(indexed.source, ReconstructSource::TermVector);
    assert_eq!(Some(&indexed.terms), body.term_vector.as_ref());
    for name in ["labels", "keywords"] {
        let unstored = field(name);
        assert!(unstored.stored.is_empty(), "{name}");
        let indexed = unstored.indexed.as_ref().unwrap();
        assert_eq!(indexed.source, ReconstructSource::Postings, "{name}");
        assert!(!indexed.terms.is_empty(), "{name}");
    }
    let json = doc_export::to_json(&export);
    let parsed: doc_export::ExportedDocument = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed, export);

    // Never into the index directory.
    let refused = doc_export::write_file(
        &Path::new(&path).join("doc-42.json"),
        &json,
        false,
        std::slice::from_ref(&path),
    );
    assert!(
        refused
            .unwrap_err()
            .starts_with("Not writing into the index directory")
    );

    server.call::<CloseIndex>(CloseIndexParams {}).unwrap();
    assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
}

#[test]
#[ignore = "needs leia-server.jar and Java 25: set LEIA_SERVER_JAR and JAVA_HOME, run with --ignored"]
fn rebuilds_the_indexed_terms_of_a_document() {
    let mut server = start();
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: lucene9_index(),
            directory_impl: None,
            mode: None,
        })
        .unwrap();

    // "document number 2", deleted, without term vectors.
    let result = reconstruct(&mut server, 1, &["body", "id"]);
    let [body, id] = result.fields.as_slice() else {
        panic!("two fields: {result:?}");
    };
    assert_eq!(body.source, ReconstructSource::Postings);
    assert_eq!(body.index_options, IndexOptions::DocsAndFreqsAndPositions);
    let positions: Vec<(&str, Vec<i32>)> = body
        .terms
        .iter()
        .map(|term| {
            let positions = term.positions.iter().filter_map(|p| p.position).collect();
            (term.term.as_str(), positions)
        })
        .collect();
    assert_eq!(
        positions,
        vec![("2", vec![2]), ("document", vec![0]), ("number", vec![1])]
    );
    assert_eq!(id.index_options, IndexOptions::Docs);
    assert_eq!(id.terms.len(), 1);
    assert_eq!(id.terms[0].term, "2");

    let unknown = server
        .call::<StartReconstruct>(StartReconstructParams {
            doc_id: 0,
            fields: Some(vec!["nope".to_owned()]),
        })
        .unwrap_err();
    assert!(
        matches!(&unknown, CallError::Rpc(e) if e.code == ErrorCode::FieldNotFound),
        "{unknown:?}"
    );

    // The demo index's body has term vectors with positions and offsets, as its postings.
    server
        .call::<OpenIndex>(OpenIndexParams {
            path: test_index("demo", "demoIndex"),
            directory_impl: None,
            mode: None,
        })
        .unwrap();
    let result = reconstruct(&mut server, 3, &["body"]);
    assert_eq!(result.fields[0].source, ReconstructSource::TermVector);
    let vector = server
        .call::<GetTermVector>(GetTermVectorParams {
            doc_id: 3,
            field: "body".to_owned(),
        })
        .unwrap();
    assert_eq!(result.fields[0].terms, vector.terms);
}
