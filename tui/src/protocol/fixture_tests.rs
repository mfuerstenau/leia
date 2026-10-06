//! Checks the DTOs against the golden files in `protocol/fixtures/` (ADR 0001, ADR 0003): every
//! fixture round-trips unchanged, every method has fixtures, every fixture belongs to a method,
//! and the error codes match `protocol/error-codes.json`.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;

use super::commits::{GetCommit, GetCommits};
use super::document::{GetDocument, GetStoredValue, GetTermVector};
use super::index::{CloseIndex, GetIndexStats, OpenIndex};
use super::job::CancelJob;
use super::terms::{GetFieldTermScan, GetPositions, GetPostings, GetTerms, StartFieldTermScan};
use super::version::Version;
use super::{ErrorCode, METHOD_NAMES, Request, Response, RpcMethod};

/// Pseudo-method for errors that belong to no method.
const RPC: &str = "rpc";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Request,
    Response,
    Error,
}

#[derive(Debug)]
struct Fixture {
    path: PathBuf,
    method: String,
    kind: Kind,
}

impl Fixture {
    fn name(&self) -> String {
        self.path
            .file_name()
            .unwrap()
            .to_string_lossy()
            .into_owned()
    }
}

fn protocol_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../protocol")
}

/// Parses `<method>.<kind>.json`, where kind is `request`, `response` or `error-<case>`.
fn parse_name(file_name: &str) -> Option<(String, Kind)> {
    let (method, rest) = file_name.strip_suffix(".json")?.split_once('.')?;
    let kind = match rest {
        "request" => Kind::Request,
        "response" => Kind::Response,
        _ => {
            let case = rest.strip_prefix("error-")?;
            let valid_case = !case.is_empty()
                && case
                    .chars()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
            if !valid_case {
                return None;
            }
            Kind::Error
        }
    };
    let valid_method = method.starts_with(|c: char| c.is_ascii_lowercase())
        && method.chars().all(|c| c.is_ascii_alphabetic());
    valid_method.then(|| (method.to_owned(), kind))
}

fn fixtures() -> Vec<Fixture> {
    let mut fixtures: Vec<Fixture> = fs::read_dir(protocol_dir().join("fixtures"))
        .expect("protocol/fixtures/ is readable")
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "json"))
        .map(|path| {
            let file_name = path.file_name().unwrap().to_string_lossy();
            let (method, kind) = parse_name(&file_name).unwrap_or_else(|| {
                panic!("fixture name doesn't match <method>.<kind>.json: {file_name}")
            });
            Fixture { path, method, kind }
        })
        .collect();
    fixtures.sort_by(|a, b| a.path.cmp(&b.path));
    fixtures
}

/// Deserializes `json` into `T`, serializes it again and compares both as JSON values.
fn round_trip<T: Serialize + DeserializeOwned>(json: &str) -> Result<T, String> {
    let message: T = serde_json::from_str(json).map_err(|e| format!("deserialize: {e}"))?;
    let expected: Value = serde_json::from_str(json).map_err(|e| format!("parse: {e}"))?;
    let actual = serde_json::to_value(&message).map_err(|e| format!("serialize: {e}"))?;
    if actual != expected {
        return Err(format!(
            "round trip changed the JSON\nexpected: {expected}\nactual:   {actual}"
        ));
    }
    Ok(message)
}

fn check_response<R: Serialize + DeserializeOwned>(
    fixture: &Fixture,
    json: &str,
) -> Result<(), String> {
    let response = round_trip::<Response<R>>(json)?;
    match (fixture.kind == Kind::Error, response.into_result().is_err()) {
        (false, true) => Err("expected a result, found an error".to_owned()),
        (true, false) => Err("expected an error, found a result".to_owned()),
        _ => Ok(()),
    }
}

fn check_method<M: RpcMethod>(fixture: &Fixture, json: &str) -> Result<(), String> {
    match fixture.kind {
        Kind::Request => {
            let request = round_trip::<Request<M::Params>>(json)?;
            if request.method != M::NAME {
                return Err(format!(
                    "method is {:?}, expected {:?}",
                    request.method,
                    M::NAME
                ));
            }
            Ok(())
        }
        Kind::Response | Kind::Error => check_response::<M::Result>(fixture, json),
    }
}

/// Dispatches on the method name. Add a line here for every new method.
fn check(fixture: &Fixture, json: &str) -> Result<(), String> {
    match fixture.method.as_str() {
        RPC if fixture.kind == Kind::Error => check_response::<()>(fixture, json),
        RPC => Err("rpc fixtures must be errors".to_owned()),
        Version::NAME => check_method::<Version>(fixture, json),
        OpenIndex::NAME => check_method::<OpenIndex>(fixture, json),
        GetIndexStats::NAME => check_method::<GetIndexStats>(fixture, json),
        CloseIndex::NAME => check_method::<CloseIndex>(fixture, json),
        StartFieldTermScan::NAME => check_method::<StartFieldTermScan>(fixture, json),
        GetFieldTermScan::NAME => check_method::<GetFieldTermScan>(fixture, json),
        CancelJob::NAME => check_method::<CancelJob>(fixture, json),
        GetDocument::NAME => check_method::<GetDocument>(fixture, json),
        GetStoredValue::NAME => check_method::<GetStoredValue>(fixture, json),
        GetTermVector::NAME => check_method::<GetTermVector>(fixture, json),
        GetTerms::NAME => check_method::<GetTerms>(fixture, json),
        GetPostings::NAME => check_method::<GetPostings>(fixture, json),
        GetPositions::NAME => check_method::<GetPositions>(fixture, json),
        GetCommits::NAME => check_method::<GetCommits>(fixture, json),
        GetCommit::NAME => check_method::<GetCommit>(fixture, json),
        other => Err(format!("no method {other:?} in METHOD_NAMES")),
    }
}

#[test]
fn every_fixture_round_trips() {
    let fixtures = fixtures();
    assert!(!fixtures.is_empty(), "no fixtures found");
    let failures: Vec<String> = fixtures
        .iter()
        .filter_map(|fixture| {
            let json = fs::read_to_string(&fixture.path).unwrap();
            check(fixture, &json)
                .err()
                .map(|e| format!("{}: {e}", fixture.name()))
        })
        .collect();
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_method_has_request_and_response_fixtures() {
    let fixtures = fixtures();
    for method in METHOD_NAMES {
        for kind in [Kind::Request, Kind::Response] {
            assert!(
                fixtures
                    .iter()
                    .any(|f| f.method == *method && f.kind == kind),
                "no {kind:?} fixture for {method}"
            );
        }
    }
}

#[test]
fn every_fixture_belongs_to_a_method() {
    for fixture in fixtures() {
        assert!(
            fixture.method == RPC || METHOD_NAMES.contains(&fixture.method.as_str()),
            "{}: no method {:?} in METHOD_NAMES",
            fixture.name(),
            fixture.method
        );
    }
}

#[test]
fn method_names_are_unique() {
    let mut names = METHOD_NAMES.to_vec();
    names.sort_unstable();
    names.dedup();
    assert_eq!(names.len(), METHOD_NAMES.len());
}

#[test]
fn error_codes_match_the_catalogue() {
    let json = fs::read_to_string(protocol_dir().join("error-codes.json")).unwrap();
    let catalogue: BTreeMap<String, i32> = serde_json::from_str(&json).unwrap();
    let ours: BTreeMap<String, i32> = ErrorCode::ALL
        .into_iter()
        .map(|code| (code.name().to_owned(), code.code()))
        .collect();
    assert_eq!(ours, catalogue);
}

#[test]
fn parses_fixture_names() {
    assert_eq!(
        parse_name("openIndex.error-not-found.json"),
        Some(("openIndex".to_owned(), Kind::Error))
    );
    assert_eq!(
        parse_name("version.request.json"),
        Some(("version".to_owned(), Kind::Request))
    );
    assert_eq!(parse_name("version.reply.json"), None);
    assert_eq!(parse_name("Version.request.json"), None);
    assert_eq!(parse_name("version.error-.json"), None);
}
