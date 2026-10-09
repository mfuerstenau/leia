# Task list — done

Finished items, moved here from `docs/tasklist.md` (the open work queue).

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
- [x] ADR 0011: query parser options and the standard query parser (shared `ParserOptions`, classic-only fuzzy max edits / whitespace splitting / auto phrases, `standard` as a third query alternative with a type per point field, checked against the field's width)
- [x] ADR 0012: similarity and sort (`similarity` as alternatives `bm25 {k1, b}` / `classic` / `boolean`, required on search and explain; `sort` as `{field, type, reverse}` keys mapped by doc values type, never pruned; sorted hits keep their scores and carry their sort values)
- [x] ADR 0013: More-Like-This (`moreLikeThis {docId, fields, minTermFreq, minDocFreq, maxDocFreq?, maxQueryTerms, boost}` as a fourth query alternative, checked in the handler and built by the query's rewrite inside the job, cancellable through the `ExitableDirectoryReader`; terms from term vectors or stored strings with `StandardAnalyzer`; the source document stays in the hits)
- [x] ADR 0014: analysis on the wire (the analyzer as data in every request, `standard {}` / `custom {charFilters, tokenizer, tokenFilters, configDir?}`; `getAnalysisComponents` and `analyze` without an open index; each stage runs the chain again from the factories; typed token fields plus every other attribute; `analyze` is no job, with 10,000 characters and 10,000 tokens per stage; `INVALID_ANALYZER`; the analyzer on `classic`, `standard` and `moreLikeThis`; only `lucene-analysis-common`)
- [x] ADR 0015: preset analyzers (`preset {className}` as a third analyzer alternative; `getAnalysisComponents` lists them as `analyzers`, found once in the jars of `Analyzer` and the registered factories: public, concrete, with a public no-argument constructor; only a listed class is created, `INVALID_ANALYZER` otherwise; one stage named by the simple class name)
- [x] ADR 0016: analysis jars (no analysis module bundled; `leia --analysis-jar <jar|dir>`, repeatable, passed to the server, which loads the jars once at startup through a child `URLClassLoader` and reloads Lucene's factory lists; presets and resource files go through that loader; a jar that can't be loaded stops the server with exit status 1; no protocol change; `:leia-server:analysisModules` fetches Lucene's modules)
- [x] ADR 0017: stop words and stem exclusions for presets (`preset` gains `stopwords?` and `stemExclusion?`, each a `WordSet` of `words` or `file`; the constructor goes by Lucene's convention, `(CharArraySet)` and `(CharArraySet, CharArraySet)`, with the default stop words filled in for stem exclusions alone; `getAnalysisComponents` gives each preset's `stopwords`/`stemExclusion` as `NONE`/`OPTIONAL`/`REQUIRED`; a `(CharArraySet)` constructor makes a preset too, which adds `StopAnalyzer`)
- [x] ADR 0018: codecs and formats from jars (`--analysis-jar` becomes `--jar`, one loader that also reloads Lucene's codecs, postings, doc values and KNN vectors formats; `lucene-codecs` and `lucene-suggest` bundled; `MISSING_CODEC` (-32014) for a codec or format that isn't loaded, `UNSUPPORTED_INDEX_FORMAT` for one of Lucene's own)
- [x] ADR 0019: reopening the index (`reopenIndex {}` with `openIfChanged`, `changed` plus the `openIndex` fields; a failed reopen keeps the open index; the TUI's `R` keeps each screen's place)
- [x] ADR 0020: directory choice and recent paths (`openIndex`' optional `directoryImpl` `FS`/`MMAP`/`NIO`; the TUI's `--directory` and Ctrl+D in the open prompt; the last 10 paths in a state-dir file, Up/Down in the prompt)
- [x] ADR 0021: several indexes and opening without a reader (indexes below a path without `segments_N` open as one `MultiReader`, failures and field conflicts skipped and listed; `openIndex`' optional `mode` `READER`/`DIRECTORY_ONLY`, `NO_READER`; per-index results and commits)
- [x] ADR 0022: check index and export terms (`startCheckIndex`/`getCheckIndex` with streamed output, with or without a reader; `startExportTerms`/`getExportTerms` in Luke's format; `OUTPUT_NOT_WRITABLE`)
- [x] ADR 0023: analyzer wrappers and preset options (`Analyzer`'s `shingles?` and `limitTokenCount?` around any alternative, in that order; `preset`'s `maxTokenLength?`, `stemOverride?`, `japanese?` and `korean?`; `AvailableAnalyzer`'s `options`; Kuromoji and Nori by reflection)
- [x] ADR 0024: jars at runtime and a default folder (`addJars {paths}` → the new jars, `alreadyLoaded` and the added names per kind, all or nothing through a dry run; `getJars`; `INVALID_JAR`; the TUI's default folder, `--no-default-jars` and the jars popup on `X`)
- [x] ADR 0025: sort keys, selectors and missing values (`SortKey` as alternatives `field {field, type, reverse, selector, missing}` / `score {reverse}` / `doc {reverse}`; selectors checked against the doc values type; `FIRST`/`LAST` in the order shown; a missing number has no sort value)
- [x] ADR 0026: date ranges (optional `dateResolution`/`locale`/`timeZone` in `ParserOptions`; with a time zone the bounds are read in it, without one as Lucene reads them, in the JVM's default zone)
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

## Backlog — done

- [x] Open options: several indexes below a path (`MultiReader`), and opening without a reader for corrupt indexes (ADR 0021)
  - a path without `segments_N` opens every index below it (depth-first by name, no symlinks, not below an index, at most 10,000 directories) as one `MultiReader`; one that fails to open or whose fields conflict is skipped and listed (`openIndex.skipped`); fails with the first error only if none opens
  - `openIndex {mode?: READER | DIRECTORY_ONLY}`: without a reader only `getCommits`/`getCommit` work, everything else fails with `NO_READER` (-32015)
  - `openIndex`/`reopenIndex` results list `indexes` (each with its `reader` info: `docBase`, `segmentsFile`, counts, versions); `getIndexStats` has the merged counts and fields, and per-commit data in `indexes`; `getCommits`/`getCommit` take `index`; `getCommits` lists `unreadable` commits with the reason
  - `reopenIndex` reopens each index with `openIfChanged` and shares the unchanged readers
  - follow-ups: the overview wraps why an index was skipped under the start of the reason (at most 3 lines, `…` past them); the selection on the commits screen goes on to the unreadable commits, whose details show the whole reason with its line breaks
  - TUI: `--no-reader` and Ctrl+N in the prompt, offered after `CORRUPT_INDEX`/`MISSING_CODEC`; overview lists the indexes and skipped ones; documents say a document's index; commits get an `Indexes` panel and show unreadable commits; screens without a reader say so; tab line marker (`no reader`, `N indexes`)
- [x] Open options: directory implementation choice and recent paths (ADR 0020)
  - `openIndex {path, directoryImpl?}` with `FS` (absent; `FSDirectory.open`), `MMAP` or `NIO`, each with `NoLockFactory`; the class in use is `getIndexStats`' `directoryImpl`; `reopenIndex` keeps the directory. No third-party `Directory` classes
  - TUI: `--directory fs|mmap|nio` is the default, and Ctrl+D in the open prompt cycles it (shown in the prompt's bottom border) for that open and the next prompts
  - TUI: the last 10 opened paths (absolute, from the server), newest first and without duplicates; Up/Down in the open prompt step through them like a shell's history, skipping the input, and Down past the newest brings back what was typed. Kept in `$XDG_STATE_HOME/leia/history` (or `~/.local/state/…`) / `%LOCALAPPDATA%\leia\history`, one path per line, written through a temporary file; read or write failures are ignored
  - tests: core (each implementation, NIO writes nothing, reopen keeps NIO), server handler (each implementation, the fixture), TUI (prompt, history file, app, CLI), real server (each implementation)
- [x] Open options: reopen (ADR 0019)
  - `reopenIndex {}` → `{changed, path, segmentsFile, numDocs, maxDoc, createdVersionMajor, minSegmentLuceneVersion?}`: `DirectoryReader.openIfChanged`, so unchanged segments are shared; unchanged → nothing happens, the jobs keep running; changed → the new reader becomes the open index, the old one's jobs stop and its reader closes (the directory is handed on); `NO_INDEX_OPEN`, or `openIndex`'s errors with the open index kept
  - TUI: `R` on every screen (listed on the overview and the commits screen); the tab line says "Reopening…", "Unchanged: segments_N", "Reopened: segments_N" or "Reopen failed: …" until the next key
  - a changed index keeps: the screen, the overview's sort and selected field, the document id (clamped) with its selected field, hidden empty fields, number format and delimiters, the terms screen's field and column, the search screen's query line, mode, field, operator, exact count and analyzer (its parser, MLT, scoring and hit field settings if the fields are the same, ignoring their postings statistics); term scans are forgotten, and nothing is searched again by itself
  - tests: core (unchanged, changed with shared segments, once only, directory gone, corrupt new commit), server handler, real server (a copy of the demo index without `segments_4`, which is then added)
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
- [x] Search: query-parser options and the standard query parser with point fields (ADR 0011)
  - `ParserOptions {allowLeadingWildcard, phraseSlop, fuzzyPrefixLength, enablePositionIncrements}` on both parsers; `classic` adds `fuzzyMaxEdits` (0–2), `splitOnWhitespace` and `autoGeneratePhraseQueries` (needs splitting); the standard parser ignores `setFuzzyMinSim`, so it has no fuzzy max edits
  - `standard {expression, defaultField, defaultOperator, options, points: [{field, type}]}`: `StandardQueryParser` with a `PointsConfig` per field (`INT`/`LONG`/`FLOAT`/`DOUBLE`); the server rejects unknown fields (`FIELD_NOT_FOUND`) and types that don't fit a one-dimensional point of the field's width (`INVALID_PARAMS`); syntax errors say what was found and where
  - fixture harnesses accept `<method>.request-<case>.json` / `.response-<case>.json`
  - TUI: `m` cycles classic → standard → term; `p` opens the options popup (`Enter` applies and searches again, `Esc` cancels), with each point field guessed as INT/LONG by width and cycled to FLOAT/DOUBLE/text; the details panel lists changed options and the point types
- [x] Search: similarity, sort, stored fields per hit, and `[`/`]` through the hits (ADR 0012)
  - `startSearch`/`startExplain` take a required `similarity`: `bm25 {k1, b}` (Lucene's checks → `INVALID_PARAMS`), `classic {}` or `boolean {}`; "discount overlaps" is left out, since Lucene reads it only while indexing
  - `startSearch` takes a required `sort` list of `{field, type, reverse}` (`INT`/`LONG`/`FLOAT`/`DOUBLE`/`STRING`), mapped to `SortField`, `SortedNumericSortField` or `SortedSetSortField` by the doc values type; `BINARY`, no doc values or a type that doesn't fit is `INVALID_PARAMS`. Comparators never prune (`Pruning.NONE`): same-name points of another width made Lucene throw, and another encoding would skip the wrong hits
  - sorted hits get their scores (`TopFieldCollector.populateScores`), and every hit has `sortValues` (`{number?, term?}`, neither for a missing value)
  - TUI: `s` popup for similarity (BM25 `k1`/`b` in hundredths) and up to two sort levels (type guessed LONG/STRING, cycled for numbers); `l` popup for the stored fields per hit; a "Sorted by" column; `[`/`]` on the documents screen step through the search hits and fetch the next page
- [x] Search: More-Like-This as a fourth query alternative (ADR 0013)
  - `moreLikeThis {docId, fields, minTermFreq, minDocFreq, maxDocFreq?, maxQueryTerms, boost}` on `startSearch` and `startExplain`: `DOC_NOT_FOUND` for the document (deleted ones allowed), `FIELD_NOT_FOUND`/`INVALID_PARAMS` for unknown, unindexed or repeated fields, `INVALID_PARAMS` for numbers out of range (`maxQueryTerms` 1–1024)
  - core: `MoreLikeDocumentQuery` rewrites to Lucene's `MoreLikeThis` query from the searcher's (exitable) reader, so the build runs in the job and can be cancelled; `query` shows the settings, `rewrittenQuery` the chosen terms (weakest first, with boosts); no terms → `MatchNoDocsQuery`
  - TUI: `m` cycles to "more like this" (the line is a document id, digits only), `p` opens the fields-and-limits popup (Lucene's defaults, fields with positions chosen), `◆` marks the source among the hits; `m` on the documents screen starts it for the shown document
- [x] Search: `SCORE`/`DOC` sort keys, sort selectors and missing values (ADR 0025)
- [x] Search: date resolution, locale and time zone for date ranges (ADR 0026); the demo index's `published` field
  - `SortKey {field?, score?, doc?}`; `FieldSort` adds `selector` (`MIN`/`MAX`/`MIDDLE_MIN`/`MIDDLE_MAX`, checked: single-valued `MIN` only, sorted numeric `MIN`/`MAX`) and `missing` (`DEFAULT`/`FIRST`/`LAST`, in the order shown: core picks the type's lowest or highest value by `reverse`)
  - a numeric hit without a value has no sort value (checked with `advanceExact` for the page's hits) instead of the substituted 0
  - TUI: a level cycles nothing → score → document id → fields; value and missing rows for fields; "best first"/"worst first" for the score
- [x] Analysis: custom analyzer builder (char filters, tokenizer, filters with params, config dir), token attributes, step by step, and the analyzer for search and More-Like-This (ADR 0014)
  - `getAnalysisComponents {}`: the char filters, tokenizers and token filters as `{name, className}`; `analyze {analyzer, text}`: the text after each char filter, and per tokenizer and token filter the tokens (`term`, `position`, increment, length, offsets, `type`, and every other attribute as `{attribute, key, value}`), `truncated` past 10,000; `INVALID_ANALYZER` (-32013) with the failing component and Lucene's message
  - `ClassicQuery`, `StandardQuery` and `MoreLikeThisQuery` take a required `analyzer`; the handler builds it, the search job closes it
  - TUI: analysis screen (`6`, also without an index): analyzer, text line (`/`), steps, tokens (positions with `·` gaps and `1–2` spans, stacked tokens in italics) and the selected token's attributes; `b` builds the analyzer (standard or custom; filterable component list, `name=value` parameters, config dir, `a`/`p`/`d`/`K`/`J`), which the server tries before it becomes current; the search screen parses and likes with it and shows a custom one
- [x] Analysis: preset analyzers by class name, such as `EnglishAnalyzer` (ADR 0015)
  - `getAnalysisComponents` adds `analyzers: [{name, className}]` (47 in `lucene-core` + `lucene-analysis-common` 10.5.2, scanned once in about 60 ms); `Analyzer` adds `preset {className}`
  - TUI: the `b` popup cycles standard → preset → custom with Space/→ (← back); the preset row opens a filterable list of the analyzers with their packages; a class name that isn't listed is taken as typed; the preset and the chain are kept while another kind is chosen; the search screen shows any analyzer but the standard one
- [x] Analysis: analyze a stored value of the shown document from the documents screen
  - `a` on the documents screen analyzes the selected field's first stored text (`STRING`, or `BINARY` that is UTF-8); `a` in the viewer analyzes the shown value, doc values included; the text is cut to 10,000 UTF-16 units, and the text title names the document and field while the line holds the value
- [x] Analysis: external jars with factories and analyzers of their own, and Luke's other analysis modules (ADR 0016)
  - none bundled (together about 38 MB, ICU alone 14 MB); `--analysis-jar <PATH>` (a jar or a directory of jars, repeatable) loads them when leia starts; `--version` leaves them out
  - core `AnalysisJars.load`: one `URLClassLoader` over the jars, `reloadCharFilters`/`reloadTokenizers`/`reloadTokenFilters`; the preset scan covers the jars and runs again for a new loader; a custom analyzer reads resources through that loader; a `LinkageError` while building is `INVALID_ANALYZER`
  - the server takes `--analysis-jar <PATH>` arguments (anything else: exit 2), stops with exit 1 and the reason as the last stderr line if a jar can't be loaded (such as a missing dependency), and makes the loader the context class loader
  - `./gradlew :leia-server:analysisModules` writes ICU, Kuromoji, Morfologik, Nori, OpenNLP, phonetic, SmartCN and Stempel with their dependencies to `leia-server/build/analysis-modules/<module>/`; with all of them, 54 presets
  - tests: `:leia-core:analysisJarsTest` (own JVM, phonetic and stempel from `:leia-core:testAnalysisJars`), server process tests for the exit codes, real-server tests with and without commons-codec
- [x] Analysis: stop words and stem exclusions for presets (ADR 0017)
  - `PresetAnalyzer` adds `stopwords?`/`stemExclusion?` (`WordSet {words?, file?}`, exactly one; a file is read as `WordlistLoader.getWordSet`, UTF-8, case-sensitive); `AvailableAnalyzer` adds `stopwords`/`stemExclusion` (`WordSetSupport`); with all eight analysis modules, 55 presets (`StopAnalyzer` is new), 46 take stop words and 38 stem exclusions
  - stem exclusions without stop words keep the default ones: `getStopwordSet()` of a default instance of a `StopwordAnalyzerBase`, otherwise the static `getDefaultStopSet()` (`DutchAnalyzer`)
  - `INVALID_ANALYZER` for a set the preset doesn't take, missing required stop words, or a file that can't be read or isn't UTF-8; `INVALID_PARAMS` for a word set without exactly one alternative
  - TUI: the `b` popup shows a stop words row and a stem exclusions row for a preset that takes them (both while the list isn't loaded); words separated by spaces or commas, `-` for none, `@path` for a file, blank or `d` for the default; picking a preset drops sets it doesn't take; the search screen shows the sets with the preset
- [x] Analysis: analyzer wrappers and preset options (ADR 0023)
  - `Analyzer` adds `shingles?` (`Shingles {minShingleSize, maxShingleSize, tokenSeparator, outputUnigrams, outputUnigramsIfNoShingles, fillerToken}`) and `limitTokenCount?` (`LimitTokenCount {maxTokenCount, consumeAllTokens}`) for any alternative, applied as `LimitTokenCountAnalyzer(ShingleAnalyzerWrapper(analyzer))`; `analyze` shows each wrapper as one more stage named by its class
  - `PresetAnalyzer` adds `maxTokenLength?` (1 to 1,048,576; a public `setMaxTokenLength(int)`, or `WhitespaceAnalyzer(int)`), `stemOverride?` (`StemDictionary {entries?, file?}`, a `(CharArraySet, CharArraySet, CharArrayMap)` constructor: `DutchAnalyzer`), `japanese?` (`{userDictionary?, mode?, stopTags?}`) and `korean?` (`{userDictionary?, decompoundMode?, stopTags?, outputUnknownUnigrams?}`), by reflection through the extension jars' loader; `JapaneseAnalyzer`'s stop words become `OPTIONAL`
  - `AvailableAnalyzer` adds `options: [PresetOption]` (`MAX_TOKEN_LENGTH`, `STEM_OVERRIDE`, `JAPANESE`, `KOREAN`); an option a preset doesn't take, or out of range, is `INVALID_ANALYZER`
  - core `WrappedAnalyzer` (a `DelegatingAnalyzerWrapper`) closes the wrappers and the analyzer; `KuromojiNori`; `:leia-core:testAnalysisJars` adds Kuromoji and Nori
  - TUI: the `b` popup has *Wrappers* rows for every kind (shingles: size `2-3`, separator and filler as typed, unigrams yes/no/only without shingles; token limit: max tokens, consume all) and rows for the options a preset takes (token length, stem overrides as `word=stem`/`-`/`@file`, user dictionary, mode, stop tags, unknown words); picking a preset drops options it doesn't take; the steps panel widens to fit the wrappers' names
- [x] Analysis: adding jars at runtime, and a default folder for them (ADR 0024)
- [x] Open options: codecs, postings, doc values and vector formats from jars, so that indexes with custom codecs open (ADR 0018)
  - `--jar <PATH>` (was `--analysis-jar`) on the TUI and the server; `ExtensionJars` (was `AnalysisJars`) reloads the three factory lists and the four codec lists from one loader; a failing reload stops the server
  - bundled: `lucene-codecs` (SimpleText, BlockTreeOrds, FST50, UniformSplit, …) and `lucene-suggest` (the `Completion*` postings formats), +0.7 MB
  - `openIndex`: `MISSING_CODEC` (-32014) "Missing codec: the index needs the <kind> '<name>', which isn't loaded"; Lucene's own names (`Lucene\d+`) are `UNSUPPORTED_INDEX_FORMAT` (another Lucene version); `getCommits` skips such a commit, `getCommit` fails with the same codes
  - TUI: adds "start leia with --jar and a jar that has it" to a `MISSING_CODEC` message
  - tests: a test codec jar (`:leia-core:testCodecJar`: codec `LeiaTest`, formats `LeiaTestPostings`/`LeiaTestDocValues`/`LeiaTestKnnVectors`) and two indexes (`:leia-core:testCodecIndexes`: `custom-codec`, `custom-formats`); `:leia-core:jarsTest` (was `analysisJarsTest`) opens them with the jar, the plain tests and the real-server tests without and with it
- [x] Commits: commit list, files, segments, diagnostics/attributes/codec formats (ADR 0009)
  - `getCommits {}`: the commits of the directory, newest first, `current` marks the opened one; unreadable commits are skipped and logged (listed in `unreadable` since ADR 0021)
  - `getCommit {generation}`: segments in commit order (docBase, deletions, generations, versions, codec and its formats, compound, blocks, index sort, size, diagnostics, attributes) and every file with its segment and size; `COMMIT_NOT_FOUND` (-32011)
  - TUI: commits screen (`4`) with commits on top, segments and details below; `r` reads the directory again; the demo index keeps all 4 commits and has one non-compound segment
- [x] Tools: check index (streamed output), export terms (ADR 0022)
  - `startCheckIndex {level?}` / `getCheckIndex {jobId, fromLine}`: `CheckIndex` on every index of the path (opened, then skipped), with or without a reader, through a read-only `CheckDirectory` that checks cancellation on positioning and bulk reads; the lines from `fromLine` on, then per index `clean`, `error`, `missingSegments`, bad segments, lost documents and per segment its error
  - `startExportTerms {field, path, delimiter?, overwrite?}` / `getExportTerms {jobId}`: the server writes `term<delimiter>docFreq` lines; `OUTPUT_NOT_WRITABLE` (-32016) for a file in or below an index directory, in a missing directory, a directory, a symbolic link, or one that exists without `overwrite`; a failed or cancelled export deletes its file
  - TUI: check screen (`7`) with level (`l`), summary and following output (`←`/`→` sideways); `e` on the terms screen asks for the file (`Tab` delimiter, second `Enter` overwrites), progress on the tab line, the outcome in the footer
- [x] Cross-cutting: progress and cancellation for long-running RPCs (ADR 0005: polled server jobs, `cancelJob`, cooperative cancellation; the term scan is the first job)
- [x] Value viewer: hex view for values, switchable from the normal view (`h` in the viewer)
- [x] Value viewer: pretty-printed JSON (`J`; TUI only)
  - reindents the value as written (key order, number and string text kept), validated with `serde_json` into `IgnoredAny`; keys, strings, numbers and literals in colors; not JSON → the text and the parse error
  - JSON objects and arrays open in this mode, and so does a cut value that starts with `{` or `[`
- [x] Value viewer: delimited data, line by line, with a definable delimiter; optionally pretty-formats delimited key/value data, with a definable key/value delimiter (`d`; TUI only)
  - a form asks for the item and key/value delimiters, with escapes (`\t`, `\n`, `\xHH`, `\u{H…}`, …); items are numbered, or keys aligned (up to 32 columns); the delimiters are kept for the next viewer
- [x] Search: regex over stored values (ADR 0027): `startStoredScan {pattern, caseInsensitive, fields?, includeDeleted}` / `getStoredScan {jobId, fromHit}`, its own job instead of a query alternative
  - a Java regex `find` over text values and UTF-8 binary values; hits per value with UTF-8 byte spans (first 100), match count and a snippet; the first 10,000 hits kept, the counts exact; 1 s per value, then skipped and listed (first 100)
  - TUI: `m` cycles to the regex mode; `/` pattern, `i` ignore case, `d` deleted documents, `l` fields; hits stream in, `Esc` keeps them; `Enter` opens the document, `[`/`]` step through the hits' documents, `v` opens the value with its matches highlighted and `m`/`M` jump between them
- [x] Search: More-Like-This for a text, the chosen terms as data, word lengths and stop words (ADR 0028)
  - `moreLikeThis` takes exactly one of `docId` and `text` (at most 64 KiB), plus `minWordLen`, `maxWordLen?` and `stopWords`; the text is analyzed for each chosen field, and the fields share one queue
  - `SearchResults.moreLikeThisTerms`: `{field, term, tf, docFreq, idf, score}` best first, rebuilt by `LikeTerms` with Lucene's rules for the clauses Lucene chose; with `boost`, the clause boosts are the scores over the weakest
  - TUI: `m` cycles to a like-text mode; the `p` popup gets min/max word length and stop words; both MLT modes show the term table in the details panel
