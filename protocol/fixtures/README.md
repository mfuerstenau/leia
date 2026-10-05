# Protocol fixtures

Golden JSON files: the wire contract between `leia-server` and the TUI (ADR 0001, ADR 0003).
Both test suites deserialize every file into its DTO, serialize it again and compare the result to
the file as JSON (key order ignored).

| File | Content |
|---|---|
| `<method>.request.json` | a request for `<method>` |
| `<method>.response.json` | a successful response to `<method>` |
| `<method>.error-<case>.json` | a failed response to `<method>` |
| `rpc.error-<case>.json` | a failed response that belongs to no method (parse error, unknown method, ...) |

Every method in the registries (`Methods` in Java, `METHOD_NAMES` in Rust) needs a request and a
response fixture, and every fixture must belong to a registered method or to `rpc`. JSON-RPC
reserves method names starting with `rpc.`, so that prefix can't clash with a real method.

The error codes in `../error-codes.json` must match `ErrorCode` on both sides.

To try a request by hand: `jq -c . protocol/fixtures/version.request.json | java -jar leia-server.jar`.
