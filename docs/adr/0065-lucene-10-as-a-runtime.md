# ADR 0065 — Lucene 10 as a runtime

- **Status:** Accepted (steps 1 to 3 landed: `leia-lucene10` and the parity test; the interface, ADR 0066 to ADR 0070; the switch, ADR 0071, which ended the parity test)
- **Date:** 2026-10-14
- **Amended by:** ADR 0073 (§4: step 5, publishing, is dropped; steps 6 and 7 download the official Lucene jars from Maven Central)
- **Will amend:** ADR 0044 (the runtimes read majors below 9 only; then every major), ADR 0034 (the contents of `leia.jar`) and AGENTS.md's hard rules 2, 5 and 6, at steps 3 and 4

## Context

The task-list item "Distribution" asks for an option that downloads the runtime jars from Maven Central into `runtimes/` beside `leia.jar`. With the user, it was settled that the download lives in `leia.jar`, checks a SHA-256 that the build bakes in, and fails with exit 1 if `runtimes/` isn't writable. The release folders would then ship without `runtimes/`.

That only pays off if Lucene 10 is a runtime too. Lucene 10 is about 12 MB of the 17 MB `leia.jar`. Today the server reads indexes of Lucene 9 and 10 through leia-core directly: `IndexDirectories.route` sends majors 9 and 10, and any index whose version it can't detect, to `Route.Core`. `CurrentIndex` holds either leia-core's `OpenedIndex` or a runtime's `OpenRuntime`. Of the about 42 handlers, 20 have a `runtime()` branch into `RuntimeResults`.

"Runtime" means a real one: a `LuceneRuntime` that the server reaches only through the runtime interface, so that `leia.jar` holds no Lucene.

## Decision

1. **The goal:** `leia.jar` without Lucene, and every index read through a `LuceneRuntime`. `leia-lucene10` is the runtime for indexes created by Lucene 9.x and 10.x, and for those whose version the server can't detect.
2. **Lucene 10 stays unrelocated.** The jars of `--jar` (ADR 0016, ADR 0018) are compiled against `org.apache.lucene`, so Lucene 10 must keep its package names, and must sit above those jars in the class-loader chain of `ExtensionJars`. It stays the only unrelocated Lucene, so hard rule 5 still holds. `leia-lucene10` therefore doesn't take `leia.lucene-runtime-conventions`, which relocates. It copies that convention's shadow-jar setup without `relocate`. `ExcludedPackagesCheck` (build-logic) keeps `io/github/mfuerstenau/leia/runtime/` and `…/server/` out of its jar, as `RelocationCheck` keeps unrelocated classes out of the others.
3. **The runtime delegates to leia-core.** `Lucene10Index` wraps a `ReadOnlyIndex` and maps leia-core's records to those of `leia-runtime`. It doesn't copy Lucene code, as the other runtimes must (ADR 0050). The groups:
   - points, rebuild and highlight are classes of their own (`Lucene10Points`, `Lucene10Rebuild`, `Lucene10Highlight`), since `Points.Segment` and `RuntimeIndex.Segment` clash in one class;
   - commits go through a `DirectoryOnlyIndex` of the one directory, and the check through `IndexCheck`;
   - a named analyzer is leia-core's preset of that simple class name.

   leia-core gains two public entries for it:
   - `ReadOnlyIndex.open(List<Path>, DirectoryImpl)` opens exactly the directories given, combined as for those found below a path. It also skips one whose fields conflict with those before it, which no other runtime does.
   - `Highlights.lucene` is public. The highlighter runs once per field of each document, so that the progress is asked before each.
4. **The lane, a commit per step:**
   1. `leia-lucene10` behind today's interface, and a parity test. Nothing is routed to it.
   2. The interface for what only Lucene 10 has. Each part is an optional group, an `API_VERSION` bump and an ADR, as `Highlight` and `Scoring` were:
      - (a) doc-values queries, the standard parser with point fields, and More-Like-This, which are also the open 9.12 items;
      - (b) analysis on `LuceneRuntime`: the components, `analyze`, and custom analyzers for search;
      - (c) extension jars: the class-loader chain moves into leia-server, with a hook through which a runtime reloads its SPI lists and reports what each load added;
      - (d) the other gaps of §6.
   3. Switch the server: every index goes to a runtime, `CurrentIndex` holds only `OpenRuntime`, and leia-server no longer compiles against leia-core.
   4. Packaging: `leia.jar` without Lucene, and `runtimes/leia-lucene10.jar`.
   5. The publishing setup for Maven Central, with reproducible jars. The upload is the user's.
   6. `--download-runtimes` in `leia.jar`.
   7. The release folders without `runtimes/`.
5. **The parity test.** `Lucene10ParityTest` (leia-server) runs two `LeiaBackend`s on the same index, `demo` and `lucene9`. One routes as leia does. The other has `Routing(lucene10Runtime = true)`, which sends indexes of Lucene 9 and 10 to a runtime that reads them. That routing option is bound through the Dagger component's factory, is off by default, and only this test sets it. Both backends make the same calls, and the test compares the protocol results with timings and filter ids left out.

   The calls cover:
   - `openIndex`, `getIndexStats`, documents, stored values and term vectors;
   - terms, postings and positions, the point tree and cells, and the field term scan;
   - searches: classic, term and KNN (approximate, and exact with a filter), each with BM25 and TF-IDF and explained for each document; with a field sort; highlighted;
   - the classic parser's other options and analyzers, the standard parser, More-Like-This and doc-values queries;
   - `startHighlight`, `startReconstruct`, the stored scan, both filters (with deleted documents and without), the term export with its file, auto stop words, and every commit and check level.

   Maps are compared as maps, as the UI orders them itself. Every call must succeed on leia-core at least once, so that no call compares equal by failing on both. Each call that differs is listed in the test, with its reason.
6. **What the test found** (input for step 2):

   | Call | Difference | Step |
   |---|---|---|
   | `openIndex` | No `SEARCH`. `DOC_VALUES` only where a field has doc values, as on every runtime, while leia-core always reports it | 2a |
   | `getIndexStats` | `directoryImpl` is the kind (`FS`), not the class (`MMapDirectory`): `RuntimeIndex.Stats` doesn't name it | 2d |
   | classic, `fuzzyMaxEdits` | `Search.ParserOptions` has none: a runtime always allows 2 edits | 2d |
   | classic, dates | `Search.ParserOptions` has no date resolution, locale or time zone (ADR 0026) | 2d |
   | custom analyzer, shingles | `AnalyzerSpec` has neither | 2b |
   | preset with stem exclusion | `AnalyzerSpec.Named` has stop words and the longest token only | 2b |
   | standard parser, More-Like-This, doc-values queries | Refused: no such part in the interface | 2a |

   These compare equal in the test, but only because its indexes and analyzers hide the difference:
   - **`splitOnWhitespace`** is dropped too. With the standard analyzer it changes nothing.
   - **`hasBlocks`** of a commit's segment is always false on a runtime (`RuntimeResults.segment`). The test indexes have no document blocks, which came with 9.9.
   - **`consumeAllTokens`** is dropped. It changes no token.
   - **Two presets with the same simple name**, as extension jars can bring, are told apart only by leia-core: `AnalyzerSpec.Named` names one by its simple name.

   The interface also has nothing for `getAnalysisComponents`, `analyze`, `addJars` and `getJars`. Their handlers are core-only today, so the test doesn't route them (2b, 2c).

   *Amended (ADR 0069):* the 2d rows are done, `splitOnWhitespace` and `hasBlocks` with them; only the `openIndex` features differ until step 3.

## Alternatives considered

- **Relocate Lucene 10 too,** as the other runtimes are. The jars of `--jar` link against `org.apache.lucene`, so they would stop working. Rejected.
- **Copy Lucene code into `leia-lucene10`,** as the other runtimes do. That would be leia-core twice. Delegating keeps one implementation; the runtime is a mapping.
- **Switch the server in the same step.** Rejected: the parity test shows what the interface lacks before anything depends on it. Without it, the gaps would show up as lost features after the switch.
- **Make the routing option a system property.** That is global state, and it would reach a user. A Dagger binding that only a test sets keeps it out of the CLI.

## Consequences

- `leia-lucene10` is built and tested but neither routed to nor packaged. `leia-server`'s tests take it as a plain project dependency, since Lucene 10 is on their classpath already. `leia-tui`'s `runtimeJars` leaves it out until `leia.jar` drops leia-core (step 4); until then its classes would be in `leia.jar` twice.
- `Runtimes` finds `Lucene10Runtime` on the test classpath (`RuntimesTest`), but `route` still sends 9 and 10 to leia-core unless the routing option says otherwise.
- `Runtimes.jarFor` doesn't name `leia-lucene10.jar` yet (step 3).
