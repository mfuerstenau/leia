# AGENTS.md — leia

leia is a **read-only Lucene index inspector**, an alternative to Luke (the Swing tool shipped with Lucene).
It has two parts: a Java backend that does all the Lucene work, and a Rust/ratatui terminal UI that talks to it over JSON-RPC on a pipe.

- Work queue and milestones: `docs/tasklist.md`
- Architecture decisions: `docs/adr/`
- Future features (Luke feature analysis): `luke-features.md`. Only features *not* tagged `[write]` are in scope.

## Architecture

```
leia (Rust TUI)  ──spawns──▶  <java> -jar leia-server.jar
   child stdin   ── JSON-RPC 2.0 request  (one JSON object per line) ──▶
   child stdout  ◀── JSON-RPC 2.0 response (one JSON object per line) ──
   child stderr  ── server logs (never protocol data)
```

| Path | Role |
|---|---|
| `leia-core/` | Java library with the Lucene functionality. No JSON-RPC, Jackson or I/O-transport code. |
| `leia-protocol/` | Java DTOs (records) for every JSON-RPC method's params/result, and Jackson configuration. |
| `leia-server/` | JSON-RPC server over stdin/stdout, with Dagger wiring. Maps core results to protocol DTOs. Builds the **fat jar** `leia-server.jar`. |
| `tui/` | Rust crate `leia`: ratatui frontend that spawns the server and holds the protocol DTOs as serde structs in `tui/src/protocol/`. |
| `protocol/fixtures/` | Golden JSON files shared by both sides: the wire contract. |
| `docs/` | `adr/`, `tasklist.md`, `SESSION-SUMMARIES.md`. |

Dependency direction: `leia-server → leia-protocol`, `leia-server → leia-core`. `leia-core` and `leia-protocol` do not depend on each other.

## Hard rules

1. **Read-only.** Never open an `IndexWriter`, never write a lock, and never modify files in an index directory. Luke's `[write]` features are out of scope.
2. **stdout belongs to the protocol.** In the server, nothing may write to `System.out` except the JSON-RPC writer. All logging goes to stderr.
3. **No ad-hoc serialization.** Every method has explicit DTOs on both sides: a Java record in `leia-protocol` and a serde struct/enum in `tui/src/protocol/`. Don't use `JsonNode`/`Map<String, Object>` or `serde_json::Value`/`json!` for params or results.
4. **Protocol changes are atomic.** If you add or change a method or DTO, update in the same change:
   - the Java DTO
   - the Rust DTO
   - `protocol/fixtures/<method>.request.json` and `<method>.response.json` (plus error fixtures where relevant)
   - the round-trip tests on both sides

   Both test suites deserialize each fixture, re-serialize it and compare it to the fixture as JSON, so drift fails the build. See `docs/adr/0001-json-rpc-over-stdio-with-mirrored-dtos.md`.
5. **Lucene 9 and 10 indexes must open.** Keep `lucene-backward-codecs` on the server classpath and keep a test that opens a Lucene 9 index.
6. **Keep core Lucene-only.** UI concepts belong in the TUI, and wire concepts belong in protocol/server.

## Tech stack

**Java:** Java 25 via a Gradle toolchain · Gradle 9 (Kotlin DSL, version catalog in `gradle/libs.versions.toml`) · Lucene 10 (+ backward-codecs) · Dagger 2.60 (annotation processor, no reflection DI) · Jackson · Spotless with Palantir Java Format.

**Rust:** standard tooling — stable toolchain, edition 2024, `rustfmt`, `clippy` (warnings are errors in CI), `cargo test`. Crates: ratatui, serde/serde_json, anyhow, clap. Release builds for Linux, macOS and Windows.

## Commands

Planned, to be kept in sync once the build exists:

```sh
./gradlew build              # compile, test, spotlessCheck
./gradlew spotlessApply      # format Java
./gradlew :leia-server:shadowJar   # fat jar → leia-server/build/libs/leia-server.jar

cd tui
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
cargo build --release
```

Run the formatters and linters before calling work done.

## TUI CLI contract

- `leia [OPTIONS] [INDEX_PATH]`
- `-j, --java <PATH>`: the JVM, given as a `java` executable or a JDK/JRE directory. Default: `$JAVA_HOME/bin/java`, then `java` on `PATH`.
- `leia-server.jar` is looked up next to the executable.
- `--version` prints the name, version and build of leia, plus the Java version actually in use. The Java version comes from the server, which needs a `version` RPC method.

## Reference material (read-only, sibling directories)

- `../json-rpc-sample`: working example of the transport, JVM discovery (`--java`) and cross-builds. Reuse its patterns, but replace its `serde_json::Value` params with typed DTOs.
- `../lucene-cli/lucene/luke`: source for *what* to compute (`models/**`, `models/util/IndexUtils.java`). Do **not** copy its architecture (Swing, global state, eager term counting).

## Project workflow

- `docs/tasklist.md` is the work queue.
- Significant decisions become numbered ADRs in `docs/adr/`.
- After a unit of work, append an entry to `docs/SESSION-SUMMARIES.md`. The `/session-summary` skill writes it, and `/catch-up` reads it.
- Don't commit unless asked.
