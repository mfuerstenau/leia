//! A typed JSON-RPC client: one request per line out, one response per line back (ADR 0001).

use std::fmt;
use std::io::{BufRead, Write};

use anyhow::{Context, anyhow};

use crate::protocol::{Request, Response, RpcError, RpcMethod};

/// Why a call failed.
#[derive(Debug)]
pub enum CallError {
    /// The server answered with an error.
    Rpc(RpcError),
    /// Talking to the server failed: it is gone, or it sent something that is not a valid
    /// response to the request.
    Transport(anyhow::Error),
}

impl fmt::Display for CallError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CallError::Rpc(error) => write!(
                f,
                "{} ({} {})",
                error.message,
                error.code.name(),
                error.code.code()
            ),
            CallError::Transport(error) => write!(f, "{error}"),
        }
    }
}

impl std::error::Error for CallError {
    /// The rest of a transport error's chain, so `{:#}` and anyhow's "Caused by" show it.
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            CallError::Rpc(_) => None,
            CallError::Transport(error) => error.source(),
        }
    }
}

impl From<anyhow::Error> for CallError {
    fn from(error: anyhow::Error) -> Self {
        CallError::Transport(error)
    }
}

/// Sends requests to `writer` and reads the responses from `reader`, one call at a time.
pub struct RpcClient<W, R> {
    writer: W,
    reader: R,
    next_id: u64,
}

impl<W: Write, R: BufRead> RpcClient<W, R> {
    pub fn new(writer: W, reader: R) -> Self {
        Self {
            writer,
            reader,
            next_id: 1,
        }
    }

    /// Calls `M` and waits for its response.
    pub fn call<M: RpcMethod>(&mut self, params: M::Params) -> Result<M::Result, CallError> {
        let id = self.next_id;
        self.next_id += 1;

        let mut line = serde_json::to_string(&Request::new::<M>(id, params))
            .with_context(|| format!("cannot serialize the {} request", M::NAME))?;
        line.push('\n');
        self.writer
            .write_all(line.as_bytes())
            .and_then(|()| self.writer.flush())
            .with_context(|| format!("cannot send the {} request to the server", M::NAME))?;

        let mut line = String::new();
        let read = self
            .reader
            .read_line(&mut line)
            .with_context(|| format!("cannot read the response to {}", M::NAME))?;
        if read == 0 {
            return Err(anyhow!("the server closed the connection during {}", M::NAME).into());
        }

        let response: Response<M::Result> = serde_json::from_str(&line).with_context(|| {
            format!(
                "invalid response to {}: {}",
                M::NAME,
                abbreviate(line.trim_end())
            )
        })?;
        let response_id = response.id();
        match response.into_result() {
            Ok(result) if response_id == Some(id) => Ok(result),
            // A response without an id is an error about a request the server couldn't read.
            Err(error) if response_id == Some(id) || response_id.is_none() => {
                Err(CallError::Rpc(error))
            }
            _ => Err(anyhow!(
                "response id {response_id:?} does not match request id {id} ({})",
                M::NAME
            )
            .into()),
        }
    }
}

fn abbreviate(text: &str) -> String {
    const MAX_CHARS: usize = 200;
    match text.char_indices().nth(MAX_CHARS) {
        Some((end, _)) => format!("{}…", &text[..end]),
        None => text.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;
    use crate::protocol::ErrorCode;
    use crate::protocol::version::{JavaRuntime, Version, VersionParams, VersionResult};

    const VERSION_RESULT: &str = r#"{"name":"leia-server","version":"1","commit":"abc","commitTime":"t","java":{"version":"25","vendor":"v","home":"/h"}}"#;

    fn client(responses: &str) -> RpcClient<Vec<u8>, Cursor<Vec<u8>>> {
        RpcClient::new(Vec::new(), Cursor::new(responses.as_bytes().to_vec()))
    }

    fn version(
        client: &mut RpcClient<Vec<u8>, Cursor<Vec<u8>>>,
    ) -> Result<VersionResult, CallError> {
        client.call::<Version>(VersionParams {})
    }

    #[test]
    fn sends_one_request_per_line_with_increasing_ids() {
        let mut client = client(&format!(
            "{{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{VERSION_RESULT}}}\n\
             {{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{VERSION_RESULT}}}\n"
        ));

        version(&mut client).unwrap();
        version(&mut client).unwrap();

        assert_eq!(
            String::from_utf8(client.writer).unwrap(),
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"version\",\"params\":{}}\n\
             {\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"version\",\"params\":{}}\n"
        );
    }

    #[test]
    fn returns_the_result() {
        let mut client = client(&format!(
            "{{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{VERSION_RESULT}}}\n"
        ));

        assert_eq!(
            version(&mut client).unwrap(),
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
    fn returns_the_servers_error() {
        let mut client = client(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32004,\"message\":\"No index is open\"}}\n",
        );

        let error = version(&mut client).unwrap_err();

        assert!(
            matches!(&error, CallError::Rpc(e) if e.code == ErrorCode::NoIndexOpen),
            "{error:?}"
        );
        assert_eq!(error.to_string(), "No index is open (NO_INDEX_OPEN -32004)");
    }

    #[test]
    fn accepts_an_error_without_id() {
        let mut client = client(
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}\n",
        );

        assert!(matches!(version(&mut client), Err(CallError::Rpc(_))));
    }

    #[test]
    fn rejects_a_response_to_another_request() {
        let mut client = client(&format!(
            "{{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{VERSION_RESULT}}}\n"
        ));

        let error = version(&mut client).unwrap_err();

        assert!(matches!(error, CallError::Transport(_)));
        assert!(error.to_string().contains("does not match"), "{error}");
    }

    #[test]
    fn reports_end_of_input() {
        let error = version(&mut client("")).unwrap_err();

        assert!(
            error.to_string().contains("closed the connection"),
            "{error}"
        );
    }

    #[test]
    fn reports_an_invalid_response_with_its_text() {
        let error = version(&mut client("debug: stray output\n")).unwrap_err();

        assert!(matches!(error, CallError::Transport(_)));
        assert!(error.to_string().contains("stray output"), "{error}");
    }

    #[test]
    fn abbreviates_long_text() {
        assert_eq!(abbreviate("short"), "short");
        assert_eq!(abbreviate(&"é".repeat(300)).chars().count(), 201);
    }
}
