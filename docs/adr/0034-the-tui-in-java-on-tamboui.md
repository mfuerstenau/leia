# ADR 0034 — The TUI in Java on TamboUI

- **Status:** Proposed (the spike of `docs/tasklist.md` decides: Accepted on go, Rejected on no-go)
- **Date:** 2026-10-09
- **Supersedes, once accepted:** ADR 0001, ADR 0003
- **Amends, once accepted:** ADR 0004 (the folders and the launch), ADR 0005 decision 2 (the transport)

## Context

leia is two processes in two languages: the Rust/ratatui TUI spawns `leia-server.jar` and talks JSON-RPC to it over a pipe (ADR 0001). That has a price:

- **Every DTO is written twice**, as a Java record and a serde struct, and golden fixtures with round-trip tests on both sides keep the two in step (ADR 0003). A new method touches six places (`AGENTS.md`, hard rule 4).
- **Two toolchains.** The Windows build needs `cargo-zigbuild` and zig. The tests against the real server run as `cargo test -- --ignored`, with indexes, jars and a Java 25 runtime that Gradle has to build first.
- **Two languages for one UI's logic.** Anything the UI parses or formats (doc values lines, Lucene ranges, byte strings) exists in Rust next to the Java that understands it.

TamboUI (https://tamboui.dev) is a terminal UI library for Java modelled on ratatui and Textual: immediate-mode widgets and layouts like ratatui's, a toolkit DSL, TCSS styling, and Pilot for driving an app in tests. Its backends are JLine 3, Panama (Java 22+) and Aesh. What is known so far:

- The latest release is `dev.tamboui:tamboui-toolkit:0.5.0` (2026-09-14). The docs are at 0.6.0-SNAPSHOT and call the library experimental, with APIs that may change.
- JLine 3 is documented as the reliable default on all platforms. Windows support per backend isn't stated.

The server already has the seam an in-process UI needs: each method is a `MethodHandler<P, R>` keyed by its `RpcMethod<P, R>`, bound into a set in `HandlerModule`. `Dispatcher` only adds the JSON around it.

What has to carry over:

- read-only (hard rule 1), and Lucene 9 and 10 indexes (rule 5);
- long work as jobs, cancelled cooperatively and never by `Thread.interrupt`, which breaks `NIOFSDirectory` (rule 7, ADR 0005);
- `CurrentIndex` touched by one thread only, the request loop (ADR 0005, Consequences);
- the extension jars and the context class loader (ADR 0016, ADR 0018, ADR 0024);
- the CLI contract in `AGENTS.md`.

## Decision

1. **The TUI moves to Java, on TamboUI 0.5.0 with the JLine 3 backend.** The version is pinned, and an upgrade is a change of its own. Panama stays a later option, if the spike gives a reason, such as startup time.
2. **One JVM, no transport.** The UI calls the handlers in-process, through `call(RpcMethod<P, R>, P)` over the Dagger-built `Set<MethodHandler<?, ?>>`. The `leia-protocol` records are its model. There is no JSON, no envelope, no request ids and no fixtures.
   - An expected failure is still an `RpcException` with its `ErrorCode`, and the UI shows it as the Rust TUI shows an error response now.
   - Any other `RuntimeException` becomes an `INTERNAL_ERROR` and is logged, as `Dispatcher.invoke` does.
3. **One backend thread.** Every call runs in order on a single executor thread, which takes the place of the request loop:
   - `CurrentIndex` keeps its one thread.
   - The UI thread never waits for Lucene: `call` returns a future, and the UI draws the result when it completes.
   - That thread's context class loader is set as `LeiaServer.main` sets it now, and `addJars` sets it again there.
4. **Jobs stay as in ADR 0005.** `start<Op>`, `get<Op>` and `cancelJob` are unchanged. The UI polls `get<Op>` on a timer, as the Rust TUI polls every 100 ms. Cancellation stays cooperative, through `ScanProgress` and `QueryTimeout`. This replaces ADR 0005's decision 2 (the stdin loop and the synchronous client), not its job model.
5. **Modules:**
   - **`leia-tui`, new**, an application: the UI, the CLI and the main class. It depends on `leia-server` and `leia-protocol`, and builds the fat jar `leia.jar`.
   - **`leia-server` becomes a library**, with `api` on `leia-protocol` and `implementation` on `leia-core`, and a public class for `call`. So the UI can't compile against core or Lucene, and the build enforces hard rule 6.
   - **Nothing is renamed.** `leia-server`, `RpcMethod`, `RpcException` and `Methods` keep their names, although they are now an in-process API.
6. **The protocol records are the API between the UI and the backend.** A field is optional exactly when it is `Optional*`, as before. The wire conventions of ADR 0003 have nothing left to serve once the Rust crate is gone, so these go with the old path:
   - `protocol/fixtures/` and the tests that replay them;
   - `Request`, `Response`, `JsonRpcVersion`, `ProtocolJson`, `ProtocolAnnotationIntrospector` and the Jackson annotations in `leia-protocol`;
   - `protocol/error-codes.json`, and Jackson itself if nothing else needs it.

   One rule replaces hard rules 2–4: **the UI reaches an index only through `call` and the protocol records.**
7. **Logs go to a file**, since the terminal belongs to the UI:
   - `$XDG_STATE_HOME/leia/leia.log`, or `~/.local/state/leia/leia.log` without `XDG_STATE_HOME`, on Linux; `%LOCALAPPDATA%\leia\leia.log` on Windows. That is the folder of ADR 0020's history.
   - The file is overwritten on each start. The level comes from `-Dleia.log.level`, as now (`ServerLogging`). If the file can't be written, the logs are dropped.
   - Until the UI takes the terminal, messages go to stderr: a bad argument, jars that fail to load, `--version`. An error exits with a non-zero code.
   - While the UI runs, `System.out` and `System.err` go into the log, so a stray print can't break the screen. The terminal takes its streams before that.
8. **The launch.**
   - Two scripts, `leia` (sh) and `leia.cmd`, find the JVM as `tui/src/java.rs` does now: `-j/--java` (a `java` executable, or a JDK/JRE directory), then `$JAVA_HOME/bin/java`, then `java` on `PATH`. They run `leia.jar` from the script's own folder, symlinks resolved, and pass the other arguments on.
   - The Java side parses the rest of the CLI contract, unchanged: `--jar`, `--no-default-jars`, `--directory`, `--no-reader`, `INDEX_PATH` and `--version`.
   - **The Java version is checked by an entry class compiled for an old `--release`.** It compares `Runtime.version()` with 25 and says what is needed, instead of an `UnsupportedClassVersionError`, before it loads the rest. The spike checks that this works.
9. **Distribution, amending ADR 0004.** Still Linux and Windows on x86_64, unpacked folders and no bundled Java. Each folder holds `leia.jar`, `leia` and `leia.cmd`, and the jar is the same in both. `build-dist.sh` needs no cargo, cargo-zigbuild or zig. macOS would work through the sh script, but stays untested and unsupported.
10. **The way there.**
    - The Rust TUI and the JSON-RPC path keep working, under their rules, until the port reaches parity: `docs/demo-index.md` and what the real-server tests check now. Then they are removed in one step.
    - No new features until then (the freeze in `docs/tasklist.md`).
    - A spike decides go or no-go, and so this ADR's status. It checks:
      - the keys leia binds (Ctrl+D/N, AltGr characters, F-keys);
      - wide characters, resizing, and a table of 10,000 rows;
      - the OSC 52 clipboard;
      - startup time;
      - Windows Terminal;
      - the version-check entry class.

## Alternatives considered

- **Keep Rust and ratatui.** It works. But the DTOs stay written twice, with the fixture machinery, two toolchains and the Windows cross-build. Rejected.
- **TamboUI over the JSON-RPC child process.** Nothing in the backend changes, but it keeps two JVMs and all the serialization when both sides are Java. Rejected.
- **Another Java library: Lanterna, or JLine alone.** Lanterna's widget model is far from ratatui's, so the port would be a rewrite. JLine alone means writing the widgets. TamboUI's model maps onto the current screens one by one. Rejected.
- **The Panama backend.** No extra dependency, but less proven, and its Windows support isn't documented. Deferred until the spike has numbers.
- **The UI and `leia-server` in one module.** One module less, but nothing would stop the UI from importing core or Lucene. Rejected.
- **A jlink-bundled runtime, now that everything is Java.** ADR 0004's reasons still hold. Rejected.
- **The scripts check the version from `java -version`.** Its output differs between vendors, and the check would be written twice, in sh and in cmd. Rejected for the entry class.

## Consequences

- **One language and one build.** Gradle only. The tests against the real server become ordinary JUnit and Pilot tests. A new method is a record pair, a handler and its UI, with no mirror and no fixture.
- **The backend thread could later run calls side by side**, for example two screens loading at once. Not used for now.
- **Risks:**
  - TamboUI is experimental and young, so an upgrade may break the UI; the pin contains this.
  - How it behaves in Windows terminals is unknown until the spike.
- **On acceptance:**
  - ADR 0001 and ADR 0003 get "Superseded by ADR 0034".
  - ADR 0004 gets "Amended by ADR 0034" for the folders and the launch.
  - ADR 0005 gets a note on its decision 2.
  - `AGENTS.md` changes with the removal of the old path: the architecture, hard rules 2–4, the tech stack, the commands and the CLI contract.
- **Lost:** talking to the server by piping a fixture into it. A JUnit test, or the log at `FINE`, takes its place.
