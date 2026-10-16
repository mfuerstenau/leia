# ADR 0050 — Sharing between the runtime facades

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0044 (Consequences: "their facades may share sources"); ADR 0048 §1 (sharing sources stays a task)

## Context

ADR 0044 expected the facades of `leia-lucene5`, `leia-lucene7` and `leia-lucene9` to share sources, since their Lucene APIs are close, and ADR 0048 §1 left it as a task once 9 existed. With all four runtimes done, the sources were compared with the version numbers normalized (`lucene[579]` → `luceneN`):

| File | 5→7 changed lines | 7→9 changed lines |
|---|---|---|
| Runtime | 4 | 4 |
| Directories | 4 | 25 (9 has no unmap switch) |
| Analyzers | 8 | 29 (packages moved) |
| Commits | 29 | 24 |
| Check | 57 | 24 |
| Index | 72 | 213 |
| Points | – | 286 (`IndexTree` against `PointTree`) |

The differences are many small API changes spread over almost every method: random-access doc values against iterators; `MultiFields` against `MultiTerms`, `MultiBits` and `FieldInfos`; `storedFields()` and `termVectors()`; `TotalHits`; the `CheckIndex` setters; the abstract methods of `Directory`; analyzer classes in other packages. On top come the features only some versions have (points, soft deletes, index sort, KNN vectors).

One shared source set would need abstract classes with roughly 15 to 30 hooks per version, a package substitution or relocation per module, and a source root used by three modules, which IDE import doesn't allow. No further runtime is coming.

What does repeat, identical or nearly so, in all four runtimes (3, 5, 7, 9) is code without Lucene types: the message of a failure and its causes, the cancellation latch of the check, the stream that splits the check's report into lines, and the loop that opens the directories of an index, skipping those that fail (ADR 0045).

## Decision

1. **The Lucene-facing facades stay as copies**, one per runtime. A fix to a Lucene-facing method lands in each runtime that has it.
2. **The Lucene-free helpers go to `leia-runtime`, package `io.github.mfuerstenau.leia.runtime.support`**:
   - `Failures`: `message(Throwable)` and `describe(Throwable)`, which names at most 8 causes;
   - `Cancellation`: `line()`, `check()` and `rethrow()`, which remember the first time `progress` threw;
   - `CheckReport`: an `OutputStream` that splits the report into lines without `\r`, and counts each in the `Cancellation`. 5.5 passes a consumer that rewrites the `-fast` hint (ADR 0047);
   - `IndexParts.open(dirs, opener)`: refuses an empty list, throws a single directory's failure as is, skips each failing one of several, and throws the first failure if none opens. The close-on-failure of the opened parts stays in each runtime, as it uses Lucene types.

   `leia-runtime` is JDK-only, part of `leia.jar` and `compileOnly` in every runtime, so nothing new is shaded and `relocationCheck` is unaffected. The read-only directory, the cancellable input, the lock that locks nothing, the progress collectors, `formatName` and `openPart` stay per runtime: they use Lucene types.
3. **`API_VERSION` stays 5.** It changes when a change breaks a runtime built before; a runtime built against 5 without the helpers still runs with the new `leia.jar`. The opposite, a new runtime with an old `leia.jar`, doesn't happen: `runtimes/` ships from the same build as `leia.jar`.
4. **A further shared helper** must be JDK-only and identical in at least two runtimes.

## Consequences

- About 500 lines fewer in the runtimes; the helpers have tests of their own in `leia-runtime`.
- The facades can still drift apart where they are copies. The feature matrix per runtime (task list) is how that gets seen.
- `leia-runtime` holds a little implementation beside the interface, kept apart in `support`.
