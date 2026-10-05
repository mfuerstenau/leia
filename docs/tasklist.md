# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`.

## M0 — Project skeleton

### Repo & docs
- [x] `.gitignore` (Gradle/Java, Rust, dist, IDEs, OS)
- [x] `AGENTS.md` + `CLAUDE.md` (imports `AGENTS.md`)
- [x] ADR 0001: JSON-RPC over stdio with mirrored, fixture-checked DTOs
- [x] ADR 0002: Java build conventions
- [x] ADR 0003: typed JSON-RPC envelope and DTO conventions
- [ ] `README.md`: what leia is, build and run instructions

### Java build (Gradle 9, Java 25)
- [x] Gradle wrapper 9.8.0, foojay resolver, daemon JVM pinned to Temurin 25 (`gradle/gradle-daemon-jvm.properties`)
- [x] `settings.gradle.kts` includes `leia-core`, `leia-protocol`, `leia-server`
- [x] Version catalog `gradle/libs.versions.toml`: Lucene 10.5, `lucene-backward-codecs`, Dagger 2.60.1, Jackson 3, JUnit 6, AssertJ
- [x] Java 25 toolchain for all modules (convention plugins in `build-logic/`, ADR 0002)
- [x] Spotless with Palantir Java Format; `spotlessCheck` wired into `check`
- [x] Dagger 2.60 annotation processing in `leia-server`
- [x] Fat jar for `leia-server` → `leia-server.jar`, including `lucene-backward-codecs` and the merged `META-INF/services` (codec SPI)
- [x] Version/build info available at runtime: version from `gradle.properties`, build = git short hash + commit timestamp, in the `build-info.properties` resource (`BuildInfo`)

### Rust build
- [x] Crate `tui/` (binary `leia`): edition 2024, `rust-toolchain.toml` (stable, rustfmt, clippy)
- [x] Dependencies: ratatui, serde/serde_json, anyhow, clap (derive)
- [x] `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` pass on an empty skeleton
- [ ] Release builds for Linux, macOS and Windows (approach: see Open questions)

### Protocol foundation (ADR 0001)
- [x] Typed JSON-RPC 2.0 envelope (request, response, error) in `leia-protocol` and `tui/src/protocol/`
- [x] Error code catalogue (`-32000` downward), mirrored on both sides and checked against `protocol/error-codes.json`
- [x] Fixture round-trip test harness in Java (JUnit, parameterized over `protocol/fixtures/`)
- [x] Fixture round-trip test harness in Rust (`cargo test`, same directory)
- [x] "Every DTO has a fixture" check (per method, plus no orphan fixtures; ADR 0003)

### Server skeleton
- [x] Stdio loop: read line → dispatch → write line; exit on stdin EOF (`RpcServer`, `Dispatcher`)
- [x] All logging to stderr (`java.util.logging`, level via `-Dleia.log.level`), and `System.out` redirected to stderr so only the protocol writer reaches stdout
- [x] Method registry wired via Dagger (one `MethodHandler` per method, `@IntoSet` in `HandlerModule`; `Dispatcher` fails fast if a method in `Methods` has no handler)

### TUI skeleton
- [ ] JVM discovery: `-j, --java <PATH>` (`java` executable or JDK/JRE dir) → `$JAVA_HOME/bin/java` → `java` on `PATH`
- [ ] Locate `leia-server.jar` next to the executable
- [ ] Spawn the server, typed RPC client, discard or capture server stderr so it can't draw over the TUI
- [ ] Clean terminal restore on exit and on panic

## M1 — First implementation

- [x] **`version`** RPC: server name/version/build plus the running Java version (`Runtime.version()`, vendor)
  - `VersionHandler` landed with the server skeleton, which needs one real handler
- [ ] **`--version`** in the TUI: prints the leia name, version and build, plus the Java version reported by the server (starts the server briefly, no TUI)
- [ ] **Open an index (read-only)**: `openIndex {path}` RPC
  - `leia-core`: open `FSDirectory` + `DirectoryReader` read-only, never an `IndexWriter`
  - Lucene 10 **and Lucene 9** indexes open (backward codecs)
  - Errors: path not found, not an index, unsupported/too old format, corrupt index
  - TUI: index path as a CLI argument and/or an open-index prompt
- [ ] **Index statistics**: `getIndexStats` RPC + overview screen (subset of `luke-features.md` §4.1)
  - path, directory implementation, index format/version, #docs, maxDoc, #deleted / has deletions
  - #fields, segment count (optimized?), commit point (`segments_N`, generation), commit user data
  - field list with per-field info. Avoid Luke's eager full term scan (see §6), so compute expensive counts lazily or on demand.
- [ ] **Close index / quit**: `closeIndex` RPC; quitting the TUI closes the index and terminates the server (stdin EOF, then kill after a timeout)
- [ ] Test fixtures: small generated Lucene 10 and Lucene 9 test indexes (built in tests or checked in under `leia-core/src/test/resources/`)
- [ ] Protocol fixtures for `version`, `openIndex`, `getIndexStats`, `closeIndex` and their error cases

## Backlog — future capabilities

From the read-only checklist in `luke-features.md` §7. Ordered roughly by value. Each area will need its own protocol methods, fixtures and probably an ADR for the cross-cutting parts.

- [ ] Open options: directory implementation choice, recursive multi-index (`MultiReader`), no-reader mode for corrupt indexes, reopen, recent-path history
- [ ] Overview: field/term-count table (lazy), top-N terms per field
- [ ] Documents: doc by id with flags/norm/value, term vectors, doc values (with decoding), stored values, term browse/seek, postings with positions/offsets/payloads
- [ ] Search: parse/rewrite, term query, paging (configurable page size), exact/approx total hits, explain, query-parser options, similarity (BM25/TF-IDF), 2-level sort, fields to load, More-Like-This
- [ ] Analysis: custom analyzer builder (char filters/tokenizer/filters + params, config dir, external jars), token attributes, step-by-step
- [ ] Commits: commit list, files, segments, diagnostics/attributes/codec formats
- [ ] Tools: check index (streamed output), export terms
- [ ] Misc: log view with level filter, cross-screen navigation (`luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values, per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations
- [ ] Cross-cutting: progress and cancellation for long-running RPCs (ADR needed)

## Open questions

- **macOS release builds:** cross-compile from Linux (`cargo-zigbuild` needs a macOS SDK) or build natively in CI (GitHub Actions macOS runner)?
- **Distribution:** ship the TUI binary + `leia-server.jar` per OS like `../json-rpc-sample/dist`, optionally with a bundled JRE (jlink)?
- **Versioning:** one shared version for the TUI and the server (they ship together), with the source of truth in Gradle or in a shared `VERSION` file?
