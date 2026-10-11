# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Now — move the TUI to TamboUI (feature freeze)

**Feature freeze:** nothing from the backlog below starts until this section is done. Fixes to the Rust TUI are fine; new features are not, in either UI. Settled on 2026-10-09: one JVM, with the TamboUI app calling the server's handlers in-process; launcher scripts `leia`/`leia.cmd` that run `java -jar leia.jar`; no Rust left.

- [ ] In-process backend (ADR 0034 §2–5): `leia-server` becomes a library (`api` on `leia-protocol`, `implementation` on `leia-core`); `call(RpcMethod<P, R>, P)` over the Dagger-built handler set (from `ServerComponent`/`HandlerModule`, without `Dispatcher`'s JSON), run in order on one backend thread that returns futures, with `RpcException` mapped to the UI's error display; extension jars and the context class loader set up as in `LeiaServer.main`
- [ ] Java CLI: the arguments of the TUI CLI contract (`--jar`, `--no-default-jars`, `--directory`, `--no-reader`, `INDEX_PATH`, `--version`), parsed in `leia-tui`; logs to `leia.log` in the state folder, stderr only before the UI starts (ADR 0034 §7)
- [ ] Launcher scripts `leia` (sh) and `leia.cmd`: `-j/--java` (an executable or a JDK/JRE directory), `$JAVA_HOME`, `PATH`, as `tui/src/java.rs` does now; `leia.jar` next to the script (symlinks resolved); the remaining arguments passed through. The Java side checks the Java version (the entry class of ADR 0034 §8) and prints `--version`
- [ ] Port the screens, each with TamboUI Pilot tests in place of the Rust tests, keeping keys and behaviour:
  - [ ] app shell: tabs, open prompt (directory choice, no-reader, recent paths), status line, help, reopen (`R`), the logo
  - [ ] overview (index stats, fields)
  - [ ] documents: viewer, stored values, term vectors, doc values, reconstruct, export, filter (ADR 0031/0032)
  - [ ] terms: terms, postings, positions, field term scan
  - [ ] search: parser options, scoring/similarity, sort, More-Like-This, explain, stored scan, doc-values search, hit fields
  - [ ] analysis: analyzer builder, presets and word sets, analyze
  - [ ] commits and segments
  - [ ] tools: check index, export terms
  - [ ] jars: `X`, the default folder
- [ ] Parity check against `docs/demo-index.md` and the real-server tests that `cargo test -- --ignored` runs now
- [ ] Remove the old path: the whole `tui/` crate (and the Rust steps of the build), `protocol/fixtures/` and the fixture tests, the JSON-RPC transport (`RpcServer`, `Dispatcher`, `RequestScanner`, `LeiaServer`'s stdio loop), the envelope and Jackson setup in `leia-protocol` (`Request`, `Response`, `JsonRpcVersion`, `ProtocolJson`, `ProtocolAnnotationIntrospector`, the Jackson annotations), `protocol/error-codes.json`, and Jackson if nothing else needs it (ADR 0034 §6)
- [ ] Update `AGENTS.md` (architecture, hard rules 2–4 → the one rule of ADR 0034 §6, tech stack, commands, CLI contract), `README.md` and `build-dist.sh` (one `leia.jar` plus `leia` and `leia.cmd`; no cargo, cargo-zigbuild or zig)

## Backlog — future capabilities

Frozen until the TamboUI migration above is done.

From the read-only checklist in `luke-features.md` §7. Ordered roughly by value. Each area will need its own protocol methods, fixtures and probably an ADR for the cross-cutting parts.

- [ ] Search: a date resolution per field (ADR 0026 has one for all fields)
- [ ] Search: a prefix match on sorted, sorted-set and binary doc values (left out of ADR 0033)
- [ ] Analysis: `PerFieldAnalyzerWrapper`, `QueryAutoStopWordAnalyzer` (needs an `IndexReader`), the collation analyzers (need a `Collator`) and `JapaneseCompletionAnalyzer` (left out of ADR 0023)
- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Commits: per-field postings, doc values and KNN vectors formats (from each segment's field infos; ADR 0009 leaves them out)
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter, cross-screen navigation (`luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values (also a document's own, which ADR 0029 and the JSON export of ADR 0030 leave out), per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
