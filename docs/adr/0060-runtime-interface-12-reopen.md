# ADR 0060 — Runtime interface 12: reopen

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0019 (reopen); ADR 0049 §4 (the runtime interface); ADR 0044 §4 (the features of an index)

## Context

`R` (reopen, ADR 0019) worked only on indexes that Lucene 10 reads. On an index that a runtime reads, `ReopenIndexHandler` asked `CurrentIndex.readable()`, which refuses with `NOT_SUPPORTED`, and `RuntimeResults.features` never reported `REOPEN`. The user had to open the path again with `o`, which resets every screen.

Every runtime's Lucene can reopen: 3.6 has `IndexReader.openIfChanged`, 5.5, 7.7 and 9.12 `DirectoryReader.openIfChanged`. Like Lucene 10's, each reads the latest `segments_N`, returns `null` if it is the reader's commit, shares the readers of the segments in both commits, and takes no lock (rule 1). So reopen is a port of `ReadOnlyIndex.reopen()` to each runtime.

On Lucene 10, the features of an index are all of them, whatever its fields. On a runtime, `DOC_VALUES`, `POINTS` and `KNN_VECTORS` depend on the fields (ADR 0046 §6, ADR 0048 §3, ADR 0049 §6), and a new commit can add such a field: `lucene8to9`'s commit of 9.12 adds the KNN vectors to an index of 8.11.

## Decision

1. **`RuntimeIndex.reopen()`, a method, not a group.** It returns the new `RuntimeIndex`, or empty if every directory's latest commit is the opened one. Every runtime has it, so an optional group (as `points()` or `rebuild()`) would only ever be present. The contract is core's: the new index shares the directories, the readers of the directories that didn't change and the readers of the segments in both commits, and owns the directories from then on; the old one stays usable until it is closed; a failure (`IOException`) leaves it unaffected, with the directory in front of the message if there are several; a second reopen of the same index throws `IllegalStateException`. The directories and the skipped ones don't change: a directory added below the path needs `o`.
2. **Reference counting, as in leia-core.** Closing a reader runs only once, so a reader shared by two indexes can't be closed by each. Each runtime index now holds one reference to each part's reader: the `MultiReader` of several parts is built with `closeSubReaders = false`, which takes a reference of its own, and `close()` closes the `MultiReader`, releases each part's reader with `decRef`, and closes the directories only if no reopened index owns them. `reopen()` takes a reference to each unchanged part's reader for the new index. If the new index can't be built, the shared readers are released and the reopened ones closed. The four indexes are copies of each other (ADR 0050); 3.6 differs only in `IndexReader.openIfChanged` and its reader type. `IndexParts.closeAll` closes a list and keeps the failures.
3. **`ReopenIndexResult` has `features`**, the features of the index as it is now, as in `OpenIndexResult`. On Lucene 10 it is `Feature.all()`; on a runtime, `RuntimeResults.features` of the new index. `RuntimeResults.features` adds `REOPEN` on every runtime. The TUI's `App.reopened` takes the features, makes them the open index's before anything reads them, hands them to the search, terms and documents screens with `withFeatures`, and shows the overview if the shown screen lost its feature.
4. **The server.** `ReopenIndexHandler` sends an index that a runtime reads to `reopenRuntime`: a directory that is no longer a directory fails with `INDEX_NOT_FOUND`, as in core; a failed reopen with `INDEX_NOT_READABLE` ("Cannot reopen … with Lucene x: …"), and the old index stays open with its jobs and filter. Unchanged, it returns the index as it is. Changed, `CurrentIndex.replace` makes the new index the open one, which stops the jobs, forgets the filter (ADR 0031 §5) and closes the old index; the log says "Reopened … with Lucene x". `RuntimeResults.reopened` builds the result, sharing the index entries and skipped indexes with `opened`.
5. **Runtime interface 12.** `LuceneRuntime.API_VERSION` is 12, so a runtime jar built for interface 11 is refused (ADR 0044).

## Alternatives considered

- **`reopen` as an optional group**: no runtime lacks it, so the group would be present on all of them, and the feature would follow nothing.
- **Reopen in the server by opening the paths again** through `LuceneRuntime.open`: no change to the runtime interface, but every segment is read again and "unchanged" can't be told apart cheaply — the reason ADR 0019 rejected the same for Lucene 10.
- **Keep `closeSubReaders = true` and copy the shared readers' references by hand**: a `MultiReader` that closes its parts can't share them with another index, so reference counting is needed anyway, as in core.
- **The TUI asks for the features with a second call**: `getIndexStats` has no features, and `openIndex` already returns them in its result; `reopenIndex` doing the same keeps one shape.

## Consequences

- `REOPEN` is reported on every runtime; `docs/runtimes.md` has ✓ in every column of its row.
- `R` works on every index with a reader, with the screens keeping their place, and a commit that adds doc values, points or vectors brings their keys and modes.
- A fix to the reopen or the reference counting belongs in `ReadOnlyIndex` and the four runtime indexes.
