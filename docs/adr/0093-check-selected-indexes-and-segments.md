# ADR 0093 — Check selected indexes and segments, on a number of threads

- **Status:** Accepted
- **Date:** 2026-10-22
- **Amends:** ADR 0022 (§1, §2, §5, §12)

## Context

ADR 0022 left out two things of Lucene's `CheckIndex` command line: checking only some of the segments, and the thread count. `startCheckIndex` checked every index of the open path (opened, then skipped) and every segment of each latest commit, on as many threads as there are processors on Lucene 9 and 10. On a large index, or a path with many indexes, a slow level 2 or 3 check can't be narrowed to the suspect index or segment, and it takes every core.

Every runtime can do the first: `checkIndex(List<String> onlySegments)` is in 3.6, 5.5 and 7.7, and `checkIndex(List, ExecutorService)` in 9 and 10. Lucene skips a name that isn't in the latest commit without a word, and still validates the segment name counter over all segments. Only 9 and 10 check in parallel.

## Decision

1. **`startCheckIndex {level?, threads?, only?}`.** `only` is a list of `{path, segments?}`: an index of the open path as `openIndex` gives it in `indexes[].path` or `skipped[].path`, and optionally the names of the segments of its latest commit to check. Without `only`, everything is checked as before; without `segments`, the whole index. The indexes checked keep the order of the open path (opened, then skipped), not that of the request. `threads` is 1 to the processors of the server's JVM; absent means all of them.
2. **Invalid params.** `INVALID_PARAMS` for `threads` outside `1..availableProcessors`, an `only` path that isn't one of the open path's indexes (compared as normalized paths), a path twice, an empty `segments` list, or a segment twice. A segment that isn't in the latest commit isn't an error of the request, since the commit may have changed since the list was read: it comes back in the outcome.
3. **The outcome says what was asked.** `IndexCheck` gains `onlySegments` (the names asked for; absent means all) and `segmentsNotFound` (those not in the latest commit, worked out by the server as the names asked for minus those of `segments`, so no runtime needs new logic). A non-empty `segmentsNotFound` makes `clean` false: a check that skipped what was asked for didn't pass. `segments` holds the segments that were checked; `segmentCount` still counts the commit's.
4. **`getCheckTargets`** lists what `only` can name: each index of the open path, opened and then skipped, with its `segments_N` and the segments of its latest commit (`name`, `maxDoc`, `sizeBytes`), or an `error` why it can't be listed (a runtime that isn't loaded, no commit, a newer `segments_N` that can't be read: the check would read that one, so listing an older commit would mislead). It runs synchronously on the backend thread, since it reads only `segments_N` and stats the files, as `getCommit` does; it works with or without a reader and fails only with `NO_INDEX_OPEN`. It goes through `Commits` of the runtime that reads each directory (ADR 0045), so no runtime interface was added for it.
5. **Runtime interface 23.** `Check.check` takes `Check.Options(level, threads, segments)` instead of the level, and `Check.hasThreads()` says whether `threads` counts. `LuceneRuntime.API_VERSION` is 23.
6. **Threads.** `leia-core`'s `IndexCheck.check` and `Lucene9Check` use `min(threads, segments to check)`, and the job's own thread below 2 (so a report streams as before). 3.6, 5.5 and 7.7 check one segment after the other and ignore `threads`. The feature `CHECK_THREADS` is on the runtimes of 9.12 and 10 and on an index opened without a reader if its first index goes to one, as `CHECK_LEVELS` is (ADR 0045 §4). `docs/runtimes.md` and the README capability matrix have the row.
7. **The header line says what is checked:** `Checking with Lucene 9.12.3, 4 threads, segments _0 _4`. The level and the threads stay the requested values, so a runtime without them ignores them.
8. **The check screen is a list of targets** (amending ADR 0022 §12). The top panel lists each index with its `segments_N` and segment count, and under it its segments with their documents and size. Space marks an index or a segment (`[ ]`, `[-]` for part of an index, `[x]`), `a` marks everything or clears it, ⏎ checks what is marked, or everything if nothing is. An index with every segment marked is checked whole. `t` cycles the threads 1, 2, 4, 8, … up to the processors and back to 1 (the default is all of them), and says "not for …" without `CHECK_THREADS`; `l` is as before. `r` reads the list again. The list is fetched the first time the screen is shown after an open or reopen. After a check, the OK or BAD line stands above the list, and the rows that were checked get ✓ or ✗, with a failed segment's error and an index's problems under them; rows not checked stay as they were. A check started before the list came, or after it failed, runs unscoped, and its outcome is shown as before. Esc, reopen and the polling are unchanged. While a check runs, the arrow keys scroll the report and the marks can't change.

## Alternatives considered

- **Select by index position, as `getCommits` does.** Skipped indexes have none, and a path is stable across a reopen. Rejected.
- **A list of segment names for the whole path.** Segment names (`_0`) repeat across indexes. Rejected.
- **Make an unknown segment an `INVALID_PARAMS`.** The commit could have changed since `getCheckTargets`; the outcome is the place to say so. Rejected.
- **A `CHECK_SEGMENTS` feature.** Every runtime has it. Rejected.
- **Honour `threads` on 3.6 to 7.7 by running segments in leia's own threads.** Their `CheckIndex` is single-threaded and not made for it. Rejected.

## Consequences

- `leia-protocol`: `CheckTarget`, `GetCheckTargetsParams`/`Result`, `CheckTargetIndex`, `CheckTargetSegment`, `Methods.GET_CHECK_TARGETS`, `Feature.CHECK_THREADS`, and the new fields of `StartCheckIndexParams` and `IndexCheck`.
- `leia-runtime`: `Check.Options`, `hasThreads()`; interface version 23, so a runtime built for 22 is refused until rebuilt (the adapters in this repository are).
- `leia-server`: `GetCheckTargetsHandler`, the checks in `StartCheckIndexHandler`, `RuntimeResults.check`.
- `leia-tui`: the check screen's list and keys; `Action.LoadCheckTargets`.
