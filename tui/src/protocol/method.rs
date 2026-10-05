//! Method descriptors, mirroring `RpcMethod` and `Methods` in `leia-protocol`.

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::version::Version;

/// One JSON-RPC method: its name and the DTOs of its params and result.
pub trait RpcMethod {
    const NAME: &'static str;
    type Params: Serialize + DeserializeOwned;
    type Result: Serialize + DeserializeOwned;
}

/// Names of all methods. Each one needs fixtures in `protocol/fixtures/`.
pub const METHOD_NAMES: &[&str] = &[Version::NAME];
