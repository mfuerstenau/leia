//! Wire types of the JSON-RPC protocol: the envelope, error codes and every method's DTOs.
//!
//! These mirror the records in `leia-protocol` (ADR 0001, ADR 0003). Conventions:
//! - every struct has `#[serde(rename_all = "camelCase", deny_unknown_fields)]`
//! - optional fields are `Option<T>` with `skip_serializing_if = "Option::is_none"`; all others
//!   are required
//! - the golden files in `protocol/fixtures/` must round-trip (see `fixture_tests`)

pub mod analysis;
pub mod commits;
pub mod document;
mod envelope;
mod error;
pub mod index;
pub mod job;
mod method;
pub mod search;
pub mod terms;
pub mod version;

pub use envelope::{JsonRpcVersion, Request, Response, RpcError};
pub use error::ErrorCode;
pub use method::{METHOD_NAMES, RpcMethod};

#[cfg(test)]
mod fixture_tests;
