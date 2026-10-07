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
- [x] ADR 0007: terms and postings on the wire (stateless pages, no jobs, positions on demand, `TERM_NOT_FOUND`)
- [x] ADR 0008: large stored values (cut at 64 KiB in `getDocument`, `getStoredValue` fetches one whole, `VALUE_NOT_FOUND`)
- [x] ADR 0009: commits and segments on the wire (read from the directory on every call, one commit whole per call, `COMMIT_NOT_FOUND`)
- [x] ADR 0010: search on the wire (search and explain as jobs, the query as alternatives `classic` / `term`, stateless offset paging up to 10,000, cancellation through `QueryTimeout`, `INVALID_QUERY`)
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
- [x] Demo index for manual testing: `./gradlew :leia-core:demoIndex` (`DemoIndexWriter`, `docs/demo-index.md`): 500 documents in 3 segments, 13 deleted, every index option, payloads, term vectors, each doc values type, points, KNN vectors, binary terms, every stored type, JSON and delimited values, values over 64 KiB, sparse and empty fields
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
- [x] Overview → other screens: browse/search docs by a top term (`luke-features.md` §4.1, needs Documents and Search)
  - [x] browse: `Enter` on a top term opens the terms screen at that term, with its postings focused
  - [x] search: `/` on a top term runs a term query for it on the search screen (binary terms as hex)
- [x] Documents: doc by id with flags/norm/value, term vectors, doc values (with decoding), stored values (ADR 0006)
  - `getDocument {docId}`: every field of the index with the document's norm, stored values (typed, numbers as text), doc values (numbers raw, byte strings as text or hex) and whether it has a term vector; read from the document's segment, deleted documents included (`deleted: true`); `DOC_NOT_FOUND` (-32008) outside 0..maxDoc-1
  - `getTermVector {docId, field}`: terms with freq, positions, offsets and payloads (hex), on demand
  - TUI: `1`/`2` switch screens (tab line on top); documents screen with a field table (flags, norm, first value) and a details panel; `←`/`→` step, `i` goes to an id, `Enter` loads the term vector, `x` decodes numeric doc values (long, double/float bits, sortable double/float), `e` hides empty fields
- [x] Documents: term browse/seek per field, postings of the current term with positions/offsets/payloads (Luke's "first/next term", "first/next doc") (ADR 0007)
  - `getTerms {field, from?, limit}`, `getPostings {field, term, fromDoc, limit}` (deleted documents marked) and `getPositions {docId, field, term}`: stateless pages, terms as text or hex both ways; `TERM_NOT_FOUND` (-32009)
  - TUI: terms screen (`3`) with fields | terms | postings columns, pages of 100 fetched at the last row, `/` seeks as you type; `Enter` on a posting opens the document, whose details show the term's positions; `[`/`]` step through the term's documents
- [x] Documents: stored value viewer/copy, norm decoding (field length), size limits for huge values (ADR 0008)
  - `getDocument` cuts `STRING`/`BINARY` stored values over 64 KiB between two characters and gives their `fullLength`; `getStoredValue {docId, field, index}` sends one whole; `VALUE_NOT_FOUND` (-32010)
  - TUI: `v` opens the viewer on the selected field's stored values and doc values (←/→ value, `h` text/hex dump, `y` copy via OSC 52, scrolling); a cut value is fetched whole when shown
  - the details panel shows a value's first 2000 characters or 20 lines; the norm shows the field length it encodes (`SmallFloat.byte4ToInt`, exact below 40, a range above)
- [x] Search, first slice: parse/rewrite, term query, paging (configurable page size), exact/approx total hits, explain, fields to load (ADR 0010)
  - `startSearch {query, offset, limit, exactTotalHits, fields?}` / `getSearch`: the query is `classic {expression, defaultField, defaultOperator}` (classic `QueryParser`, `StandardAnalyzer`) or `term {field, term}` (raw `TermQuery`, any bytes); offset paging, `limit` ≤ 1000, `offset + limit` ≤ 10,000; hits with score and stored fields cut at 256 bytes; parsed and rewritten query, total hits and relation, time taken
  - `startExplain {query, docId}` / `getExplain`: Lucene's `Explanation` tree, values as text
  - both are jobs; cancellation through a `QueryTimeout` on an `ExitableDirectoryReader` plus `IndexSearcher.setTimeout`; `INVALID_QUERY` (-32012)
  - TUI: search screen (`5`) with a query line (`/`, shared `LineInput` with the open prompt), mode/field/operator/count settings (`m`, `f`/`F`, `a`, `t`) that search again, hits fetched 100 at a time, `x` explains into the details panel, `Enter` opens the document; the TUI now polls the scan, search and explain jobs side by side
- [ ] Search: query-parser options (Luke's list: phrase slop, leading wildcard, fuzzy settings, …) and the standard query parser with point fields (numeric ranges on points)
- [ ] Search: similarity (BM25 k1/b, classic TF-IDF), 2-level sort on doc values fields, choice of the stored fields per hit in the TUI, More-Like-This
- [ ] Search: `[`/`]` on the documents screen to step through the search hits, as for a term's postings
- [ ] Analysis: custom analyzer builder (char filters/tokenizer/filters + params, config dir, external jars), token attributes, step-by-step
- [x] Commits: commit list, files, segments, diagnostics/attributes/codec formats (ADR 0009)
  - `getCommits {}`: the commits of the directory, newest first, `current` marks the opened one; unreadable commits are skipped and logged
  - `getCommit {generation}`: segments in commit order (docBase, deletions, generations, versions, codec and its formats, compound, blocks, index sort, size, diagnostics, attributes) and every file with its segment and size; `COMMIT_NOT_FOUND` (-32011)
  - TUI: commits screen (`4`) with commits on top, segments and details below; `r` reads the directory again; the demo index keeps all 4 commits and has one non-compound segment
- [ ] Commits: per-field postings, doc values and KNN vectors formats (from each segment's field infos; ADR 0009 leaves them out)
- [ ] Tools: check index (streamed output), export terms
- [ ] Misc: log view with level filter, cross-screen navigation (`luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values, per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations
- [x] Cross-cutting: progress and cancellation for long-running RPCs (ADR 0005: polled server jobs, `cancelJob`, cooperative cancellation; the term scan is the first job)
- [x] Value viewer: hex view for values, switchable from the normal view (`h` in the viewer)
- [x] Value viewer: pretty-printed JSON (`J`; TUI only)
  - reindents the value as written (key order, number and string text kept), validated with `serde_json` into `IgnoredAny`; keys, strings, numbers and literals in colors; not JSON → the text and the parse error
  - JSON objects and arrays open in this mode, and so does a cut value that starts with `{` or `[`
- [x] Value viewer: delimited data, line by line, with a definable delimiter; optionally pretty-formats delimited key/value data, with a definable key/value delimiter (`d`; TUI only)
  - a form asks for the item and key/value delimiters, with escapes (`\t`, `\n`, `\xHH`, `\u{H…}`, …); items are numbered, or keys aligned (up to 32 columns); the delimiters are kept for the next viewer

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
