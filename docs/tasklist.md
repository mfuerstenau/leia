# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`.

## M0 — Project skeleton

### Repo & docs
- [x] `.gitignore` (Gradle/Java, Rust, dist, IDEs, OS)
- [x] `AGENTS.md` + `CLAUDE.md` (imports `AGENTS.md`)
- [x] ADR 0001: JSON-RPC over stdio with mirrored, fixture-checked DTOs
- [x] ADR 0002: Java build conventions
- [x] ADR 0003: typed JSON-RPC envelope and DTO conventions
- [x] ADR 0004: release platforms (Linux, Windows) and distribution (folders, no bundled Java)
- [x] ADR 0005: long-running operations as polled server jobs (`start<Op>` / `get<Op>` / `cancelJob`)
- [x] ADR 0006: documents on the wire (per-segment reads, deleted documents readable, text-or-hex byte strings, raw numeric doc values)
- [x] `README.md`: what leia is, build and run instructions

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
- [x] Release builds for Linux and Windows (ADR 0004: no macOS, no bundled Java, unpacked folders)
  - [x] Linux and Windows (x86_64): `build-dist.sh` builds `dist/leia-<version>-<os>-x86_64/` (executable + `leia-server.jar`); Windows is cross-built with `cargo-zigbuild`

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
- [x] JVM discovery: `-j, --java <PATH>` (`java` executable or JDK/JRE dir) → `$JAVA_HOME/bin/java` → `java` on `PATH` (`java.rs`)
- [x] Locate `leia-server.jar` next to the executable, symlinks resolved; `LEIA_SERVER_JAR` overrides it for development (`server.rs`)
- [x] Spawn the server, typed RPC client (`client.rs`), server stderr captured in a ring buffer so it can't draw over the TUI and shows up in startup errors
- [x] Clean terminal restore on exit and on panic (`ratatui::run`); the server is shut down on quit, on panic (`Drop`) and when the TUI is killed (stdin EOF)

## M1 — First implementation

- [x] **`version`** RPC: server name/version/build plus the running Java version (`Runtime.version()`, vendor)
  - `VersionHandler` landed with the server skeleton, which needs one real handler
- [x] **`--version`** in the TUI: prints the leia name, version and build, plus the Java version reported by the server (starts the server briefly, no TUI)
  - `build.rs` records the git short hash and commit time, as Gradle does for the server; `build_info.rs` formats the lines, shared with the TUI screen
  - prints leia, leia-server and Java on three lines; leia's line comes first, so it is printed even if the server can't start
- [x] **One shared version** for the TUI and the server: `gradle.properties` is the source of truth, `tui/Cargo.toml` is kept in step by hand, and a Rust test (`build_info::tests::version_matches_gradle_properties`) fails on drift
- [x] **Open an index (read-only)**: `openIndex {path}` RPC → path, `segments_N`, numDocs/maxDoc, created/oldest Lucene version
  - `leia-core` `ReadOnlyIndex`: `FSDirectory` with `NoLockFactory` + `DirectoryReader`, never an `IndexWriter`; a test checks that opening leaves the directory untouched
  - Lucene 10 **and Lucene 9** indexes open (backward codecs); Lucene 8 is rejected as unsupported
  - Errors: index not found, not an index, unsupported format, corrupt index (checksum, missing file), and the new `INDEX_NOT_READABLE` (-32005) for permission/I/O errors
  - The server keeps one open index (`CurrentIndex`); a new `openIndex` replaces it only once the new index has opened
  - TUI: the index path from the CLI is opened before the UI starts; a failure is printed and exits 1
- [x] **Open-index prompt** in the TUI (open or switch an index without restarting; show open errors in the UI instead of exiting)
  - `o` opens a path prompt (popup) prefilled with the open index's path; line editing with Ctrl+A/E/U/K/W, `Tab` completes directories, `~` expands to the home directory
  - RPC errors are shown in the prompt, which keeps the path; the previous index stays open. A transport error (server gone) still ends leia
  - the command line's path goes through the same flow, so a bad path no longer exits with 1
  - `App` (`app.rs`) maps keys to actions; `main.rs` makes the calls and reports back
- [x] **Index statistics**: `getIndexStats` RPC + overview screen (subset of `luke-features.md` §4.1)
  - path, directory implementation, created/commit/oldest-segment Lucene version, #docs, maxDoc, #deleted
  - segment count (fully merged?), commit point (`segments_N`, generation, version), commit user data
  - field list ordered by field number: index options, norms, payloads, term vectors, doc values type, point and vector dimensions, and the per-segment postings stats summed (docCount, sumDocFreq, sumTotalTermFreq)
  - only what the reader loaded on open (`ReadOnlyIndex.stats()`), no term iteration; unique term counts and the total #terms are the lazy "field/term-count table" in the backlog
  - TUI: overview screen with a summary and a scrollable field table; columns that don't fit are dropped from the right
- [x] **Close index / quit**: `closeIndex` RPC; quitting the TUI closes the index and terminates the server (stdin EOF, then kill after a timeout)
  - `closeIndex {}` → `{path?}`: idempotent, no error when no index is open
  - `c` in the TUI closes the index; quitting calls `closeIndex` before shutting the server down
  - the server also closes the open index when its stdin ends
- [x] Test indexes: Lucene 10 indexes are written in the tests; Lucene 9.12 and 8.11 indexes are generated at build time by `LegacyIndexWriter` running on the old jars (`./gradlew :leia-core:lucene9TestIndex`, output in `leia-core/build/test-indexes/`), so no binary indexes are checked in
- [x] Protocol fixtures for `version`, `openIndex`, `getIndexStats`, `closeIndex` and their error cases (`closeIndex` has no error case)

## Backlog — future capabilities

From the read-only checklist in `luke-features.md` §7. Ordered roughly by value. Each area will need its own protocol methods, fixtures and probably an ADR for the cross-cutting parts.

- [ ] Open options: directory implementation choice, recursive multi-index (`MultiReader`), no-reader mode for corrupt indexes, reopen, recent-path history
- [x] Overview: field/term-count table, top-N terms per field
  - `startFieldTermScan {field, topN}` / `getFieldTermScan {jobId}`: one pass over the merged `TermsEnum` counts the unique terms and keeps the top N by docFreq (ties in term order); binary terms go over the wire as hex. Lazy: a scan runs only on request
  - TUI: "Terms" column, top-terms panel (next to the fields from 130 columns, below them otherwise), `Enter` scans the selected field, `a` all fields one after another, `Esc` cancels, `Tab` switches the panel; the total shows in the fields title once every field is counted
- [x] Overview: "%" column (each field's share of all terms, as in Luke, once every field is counted) and a configurable top N
  - `+`/`-` step N through 10, 20, 50, 100, 200, 500, 1000 (default 50); kept when another index is opened
  - a smaller N cuts the shown terms; a larger one marks fields scanned for fewer (`⏎ top N` in the panel title), and `Enter`/`a` scan them again
- [x] Overview: sort the field table (TUI only, no protocol change)
  - by number (default), name, or term count (starts descending), as in Luke (`luke-features.md` §4.1); fields without a count sort last in either direction, ties by number
  - `s` cycles the sort column, `S` flips the direction; the header marks the sorted column with ▲/▼; the selection stays on its field when rows move
- [ ] Overview → other screens: browse/search docs by a top term (`luke-features.md` §4.1, needs Documents and Search)
- [x] Documents: doc by id with flags/norm/value, term vectors, doc values (with decoding), stored values (ADR 0006)
  - `getDocument {docId}`: every field of the index with the document's norm, stored values (typed, numbers as text), doc values (numbers raw, byte strings as text or hex) and whether it has a term vector; read from the document's segment, deleted documents included (`deleted: true`); `DOC_NOT_FOUND` (-32008) outside 0..maxDoc-1
  - `getTermVector {docId, field}`: terms with freq, positions, offsets and payloads (hex), on demand
  - TUI: `1`/`2` switch screens (tab line on top); documents screen with a field table (flags, norm, first value) and a details panel; `←`/`→` step, `i` goes to an id, `Enter` loads the term vector, `x` decodes numeric doc values (long, double/float bits, sortable double/float), `e` hides empty fields
- [ ] Documents: term browse/seek per field, postings of the current term with positions/offsets/payloads (Luke's "first/next term", "first/next doc")
- [ ] Documents: stored value viewer/copy, norm decoding (field length), size limits for huge values (ADR 0006, decision 6)
- [ ] Search: parse/rewrite, term query, paging (configurable page size), exact/approx total hits, explain, query-parser options, similarity (BM25/TF-IDF), 2-level sort, fields to load, More-Like-This
- [ ] Analysis: custom analyzer builder (char filters/tokenizer/filters + params, config dir, external jars), token attributes, step-by-step
- [ ] Commits: commit list, files, segments, diagnostics/attributes/codec formats
- [ ] Tools: check index (streamed output), export terms
- [ ] Misc: log view with level filter, cross-screen navigation (`luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values, per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations
- [x] Cross-cutting: progress and cancellation for long-running RPCs (ADR 0005: polled server jobs, `cancelJob`, cooperative cancellation; the term scan is the first job)

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
