//! The JSON-RPC 2.0 envelope, mirroring `Request`, `Response` and `RpcError` in `leia-protocol`.

use serde::{Deserialize, Serialize};

use super::error::ErrorCode;
use super::method::RpcMethod;

/// The `jsonrpc` member of every message. Only JSON-RPC 2.0 exists.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum JsonRpcVersion {
    #[serde(rename = "2.0")]
    V2_0,
}

/// A JSON-RPC request. `params` is always an object, an empty one for methods without parameters.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Request<P> {
    pub jsonrpc: JsonRpcVersion,
    pub id: u64,
    pub method: String,
    pub params: P,
}

impl<P> Request<P> {
    pub fn new<M: RpcMethod<Params = P>>(id: u64, params: P) -> Self {
        Self {
            jsonrpc: JsonRpcVersion::V2_0,
            id,
            method: M::NAME.to_owned(),
            params,
        }
    }
}

/// A JSON-RPC response: exactly one of `result` and `error` is present.
///
/// `id` is the request's id, or `None` (serialized as `null`) if the server could not read the
/// request far enough to know it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    rename_all = "camelCase",
    try_from = "RawResponse<R>",
    bound(deserialize = "R: Deserialize<'de>")
)]
pub struct Response<R> {
    jsonrpc: JsonRpcVersion,
    id: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<R>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<RpcError>,
}

impl<R> Response<R> {
    pub fn success(id: u64, result: R) -> Self {
        Self {
            jsonrpc: JsonRpcVersion::V2_0,
            id: Some(id),
            result: Some(result),
            error: None,
        }
    }

    pub fn failure(id: Option<u64>, error: RpcError) -> Self {
        Self {
            jsonrpc: JsonRpcVersion::V2_0,
            id,
            result: None,
            error: Some(error),
        }
    }

    pub fn id(&self) -> Option<u64> {
        self.id
    }

    pub fn into_result(self) -> Result<R, RpcError> {
        match (self.result, self.error) {
            (Some(result), None) => Ok(result),
            (None, Some(error)) => Err(error),
            _ => unreachable!("a response has exactly one of result and error"),
        }
    }
}

/// The wire shape of [`Response`], before its invariant is checked.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawResponse<R> {
    jsonrpc: JsonRpcVersion,
    id: Option<u64>,
    result: Option<R>,
    error: Option<RpcError>,
}

impl<R> TryFrom<RawResponse<R>> for Response<R> {
    type Error = &'static str;

    fn try_from(raw: RawResponse<R>) -> Result<Self, Self::Error> {
        if raw.result.is_some() == raw.error.is_some() {
            return Err("a response needs exactly one of result and error");
        }
        Ok(Self {
            jsonrpc: raw.jsonrpc,
            id: raw.id,
            result: raw.result,
            error: raw.error,
        })
    }
}

/// The `error` member of a failed response.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RpcError {
    pub code: ErrorCode,
    pub message: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::version::{JavaRuntime, Version, VersionParams, VersionResult};

    type VersionResponse = Response<VersionResult>;

    #[test]
    fn writes_request() {
        let json = serde_json::to_string(&Request::new::<Version>(1, VersionParams {})).unwrap();
        assert_eq!(
            json,
            r#"{"jsonrpc":"2.0","id":1,"method":"version","params":{}}"#
        );
    }

    #[test]
    fn writes_null_id_and_omits_empty_result() {
        let response = VersionResponse::failure(
            None,
            RpcError {
                code: ErrorCode::ParseError,
                message: "Parse error".to_owned(),
            },
        );
        assert_eq!(
            serde_json::to_string(&response).unwrap(),
            r#"{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}"#
        );
    }

    #[test]
    fn reads_success_response() {
        let response: VersionResponse = serde_json::from_str(
            r#"{"jsonrpc":"2.0","id":1,"result":{"name":"leia-server","version":"1","commit":"abc",
                "commitTime":"t","java":{"version":"25","vendor":"v","home":"/h"}}}"#,
        )
        .unwrap();
        assert_eq!(response.id(), Some(1));
        assert_eq!(
            response.into_result().unwrap(),
            VersionResult {
                name: "leia-server".to_owned(),
                version: "1".to_owned(),
                commit: "abc".to_owned(),
                commit_time: "t".to_owned(),
                java: JavaRuntime {
                    version: "25".to_owned(),
                    vendor: "v".to_owned(),
                    home: "/h".to_owned(),
                },
            }
        );
    }

    #[test]
    fn reads_error_response() {
        let response: VersionResponse = serde_json::from_str(
            r#"{"jsonrpc":"2.0","id":2,"error":{"code":-32004,"message":"No index open"}}"#,
        )
        .unwrap();
        assert_eq!(
            response.into_result().unwrap_err().code,
            ErrorCode::NoIndexOpen
        );
    }

    fn assert_request_rejected(json: &str) {
        assert!(serde_json::from_str::<Request<VersionParams>>(json).is_err());
    }

    fn assert_response_rejected(json: &str) {
        assert!(serde_json::from_str::<VersionResponse>(json).is_err());
    }

    #[test]
    fn rejects_unknown_property() {
        assert_request_rejected(
            r#"{"jsonrpc":"2.0","id":1,"method":"version","params":{"verbose":true}}"#,
        );
        assert_response_rejected(
            r#"{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"m"},"extra":1}"#,
        );
    }

    #[test]
    fn rejects_missing_required_property() {
        assert_request_rejected(r#"{"jsonrpc":"2.0","method":"version","params":{}}"#);
    }

    #[test]
    fn rejects_null_required_property() {
        assert_request_rejected(r#"{"jsonrpc":"2.0","id":1,"method":null,"params":{}}"#);
    }

    #[test]
    fn rejects_other_json_rpc_version() {
        assert_request_rejected(r#"{"jsonrpc":"1.0","id":1,"method":"version","params":{}}"#);
    }

    #[test]
    fn rejects_trailing_tokens() {
        assert_request_rejected(r#"{"jsonrpc":"2.0","id":1,"method":"version","params":{}} {}"#);
    }

    #[test]
    fn rejects_response_with_both_result_and_error() {
        assert_response_rejected(
            r#"{"jsonrpc":"2.0","id":1,"result":{"name":"n","version":"1","commit":"c",
                "commitTime":"t","java":{"version":"25","vendor":"v","home":"/h"}},
                "error":{"code":-32603,"message":"m"}}"#,
        );
    }

    #[test]
    fn rejects_response_with_neither_result_nor_error() {
        assert_response_rejected(r#"{"jsonrpc":"2.0","id":1}"#);
    }

    #[test]
    fn rejects_unknown_error_code() {
        assert_response_rejected(
            r#"{"jsonrpc":"2.0","id":1,"error":{"code":-31999,"message":"m"}}"#,
        );
    }
}
