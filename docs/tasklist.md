# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Distribution

Goal: an option of `leia.jar` downloads the runtime jars from Maven Central into `runtimes/` beside it, checked against SHA-256s that the build bakes in, and the release folders ship without `runtimes/`. That pays off only once Lucene 10 is a runtime too, so the lane starts there (ADR 0065). Each step is a commit; steps 3 to 7 get their own plan when reached. Step 2, the interface for what only Lucene 10 had (ADR 0066 to ADR 0069), is done.

- [x] 1. `leia-lucene10`: Lucene 10 behind today's runtime interface, through leia-core, and `Lucene10ParityTest`, which lists what the interface lacks (ADR 0065 §6). Nothing is routed to it yet.
- [ ] 3. Switch the server, in three commits. 3a, runtime interface 20 with failure reasons and exact hit counts (ADR 0070), is done. The rest: `Runtimes`/`route` send every index to a runtime, leia-lucene10 claiming 9, 10 and versions it can't detect; `Runtimes.jarFor` names `leia-lucene10.jar`; `CurrentIndex` holds only `OpenRuntime`; the handlers' core branches go, and leia-server stops compiling against leia-core. The TUI gates the analysis tab when no runtime has analysis. Rewrite hard rules 2, 5 and 6 and the architecture section of `AGENTS.md`.
- [ ] 4. Packaging: `leia.jar` without Lucene; `runtimeJars` gains leia-lucene10; a `jarsTest` opens a Lucene 9/10 index from `runtimes/`; without `runtimes/`, leia starts and says what is missing. Fix `Cli.HELP`, which still says "runtimes for indexes of Lucene 2 and 3".
- [ ] 5. Publishing setup: `maven-publish` and `signing` for the five runtime shadow jars as `io.github.mfuerstenau.leia:leia-luceneN:<version>`, with POM metadata and sources/javadoc jars; a Central Portal bundle task; reproducible jars. The upload is the user's.
- [ ] 6. `--download-runtimes` in `leia.jar`: the baked-in SHA-256s, `repo1.maven.org` with a base URL for tests, a temp file moved into place, a jar with the right hash skipped, refused for a SNAPSHOT build, exit 1 with a message if `runtimes/` isn't writable or a download fails; listed in `--help`, tested against the JDK's `HttpServer`.
- [ ] 7. Slim dist: `build-dist.sh` leaves `runtimes/` out; the `MISSING_RUNTIME` hint and the startup message point to `leia --download-runtimes`; amend ADR 0004 and ADR 0044.

## Lucene 9.12 beyond classic search

Goal: an index created by Lucene 8.x (read with `leia-lucene9`, ADR 0049) gets the features that are greyed out for it today. 9.12 has the APIs that leia-core uses for them on Lucene 10, so most are ports. What needs no runtime change goes to every runtime at once. The others need runtime interface 7 (one ADR for the interface, amending ADR 0049 §4). They land in 9.12 first, and in 5.5 and 7.7 where the copy is the same (ADR 0050). Each item adds its feature in `RuntimeResults.features`, a `runtime()` branch in its handlers, and its row in `docs/runtimes.md` (checked by `RuntimeFeaturesTest`). The TUI already gates on the features.

- [ ] Doc-values, standard and More-Like-This queries (ADR 0066) on 7.7 and 5.5, as copies of 9.12's.
- [ ] Custom analyzers, shingles and preset options for search on 7.7 and 5.5 (ADR 0067), as copies of 9.12's `Lucene9Presets`, `Lucene9CustomAnalyzers` and `Lucene9Analyzers`.
- [ ] Similarity and sort (`SCORING`) on 7.7 and 5.5 (ADR 0064 §2): 7.7 has the API of 9.12 apart from `Pruning`; 5.5 has no `BooleanSimilarity`.
- [ ] Check on more threads on 9.12 (`CheckIndex.setThreadCount`, now fixed at 1, ADR 0049 §5). It goes with the backlog's "check thread count".

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format). Trie-encoded terms of 4.x to 7.x (7 bits a byte, so valid UTF-8) are written raw today, and one can hold a newline, which breaks the line format
- [ ] Misc: log view with level filter
- [ ] Misc: themes
- [ ] Misc: about
- [ ] Improvements over Luke (§6). Done already: deleted documents (ADR 0006, the filter's `includeDeleted`), binary values as text or hex (ADR 0006), cancellation of long operations (ADR 0005), KNN vectors (ADR 0040), point values (ADR 0041), highlighting of hits (ADR 0042), the per-segment document view (ADR 0051). Left:
  - [ ] KNN: HNSW graph inspection (a node's levels and neighbours through `HnswGraphProvider`, on 9.12 too, which has it), and a recall check of approximate against exact (left out of ADR 0040)

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
