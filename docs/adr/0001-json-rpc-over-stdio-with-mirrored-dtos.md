# ADR 0001 — JSON-RPC over stdio with mirrored, fixture-checked DTOs

- **Status:** Superseded by ADR 0034 (one JVM, no transport); the JSON-RPC path was removed on 2026-10-11
- **Date:** 2026-10-05

## Context

leia is split into a Java backend (Lucene runs on the JVM) and a Rust/ratatui frontend (a native, cross-platform terminal UI). The two run as separate processes and need a wire protocol.

Requirements:

- The frontend starts the backend itself, using a user-selectable JVM (`-j, --java`, default `JAVA_HOME`). No network ports and no separate daemon.
- Serialization must be orderly: defined DTOs on both sides, with no ad-hoc JSON building or parsing.
- Contract drift between Java and Rust must be caught automatically.

`../json-rpc-sample` already shows newline-delimited JSON-RPC 2.0 over a child process's stdin/stdout working between ratatui and a Jackson server. However, its params and results are untyped (`serde_json::Value`).

## Decision

1. **Transport.** The TUI spawns `<java> -jar leia-server.jar` and speaks **JSON-RPC 2.0** to it over the child's stdin/stdout, one JSON object per line (UTF-8, `\n`-terminated).
   - The server's stdout carries protocol messages only. Logs go to stderr.
   - The server exits on stdin EOF.
2. **DTOs.** Every method has explicitly declared param and result types on both sides:
   - **Java:** records in the `leia-protocol` module, (de)serialized with Jackson.
   - **Rust:** `serde` structs/enums in `tui/src/protocol/`.

   Untyped JSON (`JsonNode`, `Map<String, Object>`, `serde_json::Value`, `json!`) is not allowed in method signatures. The JSON-RPC envelope (`jsonrpc`, `id`, `method`, `params`/`result`/`error`) is also a typed DTO on both sides.
3. **Contract check.** Golden JSON files in `protocol/fixtures/` are the reference for the wire format:
   - `<method>.request.json` and `<method>.response.json`, plus `<method>.error-<case>.json` where relevant.

   Both the Java and Rust test suites load every fixture, deserialize it into the DTO, serialize it again, and assert JSON equality with the fixture (ignoring key order). Every method must have fixtures, and a test fails if a DTO has no fixture.
4. **Wire conventions.**
   - Field names are `camelCase`.
   - Enums are serialized as `SCREAMING_SNAKE_CASE` strings.
   - Optional fields are omitted when absent: Jackson `NON_ABSENT`, serde `skip_serializing_if = "Option::is_none"`.
   - Unknown fields are rejected on both sides (Jackson `FAIL_ON_UNKNOWN_PROPERTIES`, serde `deny_unknown_fields`). The TUI and the server always ship together, so strictness surfaces drift early.
5. **Error codes.** Application errors use JSON-RPC error codes from `-32000` downward. They are defined once in `leia-protocol` and mirrored in Rust, e.g. index not found, not an index, unsupported index format.

## Alternatives considered

- **Java-first with a generated schema.** A Gradle task would generate JSON Schema from the records, and Rust types would be generated from it (e.g. `typify`). There'd be one authority, but it adds codegen tooling to both builds, and generated Rust types are awkward to shape for the UI. Rejected for now. The fixtures make it easy to adopt later.
- **Schema-first codegen** (hand-written JSON Schema/OpenRPC, with types generated on both sides). The most formal option, but it needs the most tooling and churn for a small protocol. Rejected.
- **gRPC / a binary protocol.** Needs a port or a custom pipe framing, and is harder to debug by hand. Rejected.
- **Untyped JSON** (as in `json-rpc-sample`). This violates the requirement. Rejected.

## Consequences

- Every protocol change touches four places at once: Java DTO, Rust DTO, fixtures and tests (see the hard rules in `AGENTS.md`).
- DTOs are hand-written twice. That's acceptable at the expected protocol size, and drift fails CI instead of failing at runtime.
- The protocol can be exercised manually by piping a fixture into `java -jar leia-server.jar`.
- Long-running operations (e.g. check index, top terms on large indexes) need a cancellation/progress mechanism. ADR 0005 settles it with polled server jobs instead of notifications.
