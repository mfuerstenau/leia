# ADR 0071 — Every index through a runtime

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0065 (step 3 lands); ADR 0044 (the runtimes read every major, not only those below 9); ADR 0021 (an index opened without a reader); ADR 0058, ADR 0066 (`SEARCH` is gone); ADR 0067 §9, ADR 0068 §5, ADR 0069 §6; AGENTS.md hard rules 2, 5 and 6

## Context

This is step 3 of the Distribution lane (ADR 0065 §4). Until now the server read indexes of Lucene 9 and 10, and those whose version it couldn't detect, through leia-core directly (`Route.Core`). `CurrentIndex` held either leia-core's `OpenedIndex` or an `OpenRuntime`, and 26 handlers had a branch for each. Step 2 (ADR 0066 to ADR 0069) and step 3a (ADR 0070) put on the runtime interface what only leia-core had, and `Lucene10ParityTest` showed that leia-lucene10 answers every call as leia-core does, apart from the features of `openIndex`.

Step 4 is to ship `leia.jar` without Lucene. That needs a server that reaches every index through the runtime interface first.

## Decision

1. **Routing.** `IndexDirectories.route` sends a directory, by the version of its newest `segments_N` (ADR 0044 §3):
   - with a detected major, to the runtime that reads it, or else to `Route.Missing`;
   - with a major newer than any runtime reads, or a version it can't detect, to `Runtimes.latest()`: the runtime with the highest major in its `indexMajors`, which then reports the format error itself, as leia-core did.

   `Routing`, its Dagger binding, `Route.Core` and the use of `Version.MIN_SUPPORTED_MAJOR` go. `Runtimes.jarFor` names `leia-lucene10.jar` for 9 and 10, and `Route.Missing` names it for an index without a version, so `MISSING_RUNTIME` covers every major.
2. **leia-lucene10 is a runtime dependency of leia-server** (`runtimeOnly`, the plain jar, which brings leia-core and Lucene 10 with it). `leia.jar` gets it through leia-server, so `ServiceLoader` finds `Lucene10Runtime` as it finds the others. The tests take it as `testImplementation`, to open an index through `Lucene10Runtime` directly (`TestIndexes`).
3. **`CurrentIndex` holds a sealed `OpenIndex`** (`server.runtime`): an `OpenRuntime`, with a reader, or an `OpenDirectories(path, directories, kind)` without one. `OpenDirectories` holds no Lucene object, as the runtimes read commits and check a directory from its path (ADR 0045). `CurrentIndex.runtime()` returns the `OpenRuntime`, or refuses with `NO_INDEX_OPEN` or `NO_READER`; `opened()` returns either.
4. **`openIndex`.** With a reader, the runtime routed for the first directory opens every directory it reads, and the others are skipped, as before (ADR 0045); one of the first's major is tried too, so that a runtime that only reports a format error reports it. Without a reader, the directories open even if the first one's runtime isn't loaded: `getCommits` and the check then say `MISSING_RUNTIME` for it, which is what the TUI suggests opening without a reader for. `CHECK_LEVELS` follows the runtime of the first directory, which fixes the old rule that gave it to an index of 4.x to 8.x whose runtime wasn't loaded. `luceneRuntime` is that runtime's version, or empty if it isn't loaded.
5. **Every handler calls the runtime.** The core branches go, and with them `SearchQueries`, `AnalyzerSpecs`, `CoreExtensions`, the core halves of `Highlighting`, `TermParams` and `PointMapper`, and `GetIndexStatsHandler`'s mappings. What `SearchQueries` checked and the runtime path didn't, the runtime path checks now, in the same words:
   - a query with not exactly one alternative (`INVALID_PARAMS`), and the classic parser's default field (`FIELD_NOT_FOUND`);
   - a negative `phraseSlop` or `fuzzyPrefixLength` (`ParserChecks.numbers`, `INVALID_PARAMS`);
   - the similarity, with BM25's ranges in the words of Lucene's `BM25Similarity`, in `RuntimeScoring`, which also names it in the job's description;
   - a posting of a field with documents only has no frequency, as leia-core showed it, though the runtimes report Lucene's 1.
6. **`Feature.SEARCH` is removed.** It stood for "every part of search" on leia-core. The TUI checks the parts (`CLASSIC_SEARCH`, `TERM_SEARCH`, `SCORING`, `DOC_VALUES_SEARCH`, `STANDARD_SEARCH`, `MORE_LIKE_THIS`) alone. `DOC_VALUES`, `POINTS` and `KNN_VECTORS` follow the index's data on Lucene 9 and 10 too, as on every runtime. The tab line names the Lucene that reads an index unless it is the newest one, Lucene 10 (`App.NEWEST_LUCENE_MAJOR`), as it named every runtime before.
7. **Analysis.** `getAnalysisComponents` and `analyze` always go to the first runtime with an `Analysis` (ADR 0067). Without one, they are `NOT_SUPPORTED`, and the analysis tab says "No loaded runtime has analysis" in normal text, not as an error; so does the analyzer form's picker. There is no protocol change.
8. **Extension jars.** `JarChain` reloads the lists of every runtime's `Extensions` (ADR 0068), without the rule of ADR 0068 §5 that skipped the runtime of Lucene 10's indexes; `CoreExtensions` is gone.
9. **leia-server still compiles against leia-core** for the algorithms the runtimes' results go through (the stored scan, the highlighter's text, auto stop words, the term export, the filters). Moving them into the server, so that leia-server compiles without leia-core and Lucene, is the next commit (step 3c).

## Alternatives considered

- **Keep `Route.Core` as a fallback** for an index whose runtime isn't loaded. It would keep Lucene 10 in `leia.jar` for step 4. The runtime for the newest indexes takes that place.
- **Fail without a reader too** when the first directory's runtime is missing. The TUI offers the open without a reader for exactly that error, so it must succeed.
- **A protocol field for whether a runtime has analysis,** to grey the tab out. The tab can say it on `NOT_SUPPORTED`; no new record is needed.
- **Keep `SEARCH`** for indexes of Lucene 9 and 10. Every runtime reports the parts, so `SEARCH` would mean "the runtime of Lucene 10", which the parts say better.

## Consequences

- `Lucene10ParityTest` is gone: its job, leia-core against the runtime, ends here. `DemoIndexTest`, `Lucene9BackendTest`, `JarsBackendTest` and `RuntimeFeaturesTest` go through leia-lucene10 with their assertions, so they are the parity check now; `RuntimeFeaturesTest` reads the 10.5.2 column from `demo`, which has doc values, points and vectors.
- The handler tests open an index through `TestIndexes`, with `Lucene10Runtime`.
- `docs/runtimes.md`: the 10.5.2 column is leia-lucene10, without `SEARCH`, with the data-gated features footnoted.
- An index of 4.x to 8.x opened without a reader and without its runtime no longer lists its commits as unreadable with Lucene 10: `getCommits` says which runtime jar is missing.
