# ADR 0021 — Several indexes at once, and opening without a reader

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

leia opens one index directory, the one the path names, with a `DirectoryReader`. Luke has two more open options, which are the last of its read-only ones (`docs/luke-features.md` §3):

- **Multi-reader mode.** Luke walks the path recursively and opens every directory that holds an index. One index opens as usual, several open as one `MultiReader`. Applications often keep several indexes next to each other: one per tenant, per day, per Solr core. Their documents can then be searched, browsed and counted together.
- **No-reader mode.** Luke opens only the `Directory`, without an `IndexReader`, for an index that a reader can't open: a corrupt segment, a missing file, a codec that isn't loaded. Only the commits (and check index) work. leia's commits screen (ADR 0009) reads `segments_N` and `.si` files from the directory and doesn't need a reader, so it can show what such an index holds and which file is missing.

Luke's multi-reader mode always walks the whole tree below the path, logs and drops the directories that fail to open, and leaves its commits tab empty. Its no-reader mode is a checkbox in the open dialog.

Directory choice and recent paths (ADR 0020) and reopen (ADR 0019) are settled. Elasticsearch/OpenSearch indexes stay deferred (ADR 0018).

## Decision

### Finding the indexes

1. **A path that holds an index opens as before.** "Holds an index" means it has a `segments_N` file; `pending_segments_N` doesn't count. Nothing below it is looked at.
2. **Otherwise leia looks for indexes below the path.** It walks the tree depth-first in name order and doesn't follow symbolic links. A directory that holds an index is taken, and nothing below it is looked at. A directory below the path that can't be listed is passed over (and logged). The indexes keep the order of the walk, so their order, and with it every document id, is the same on every open.
3. **The walk is bounded, not a job.** It only lists directories, and stops after 10,000 of them with `NOT_AN_INDEX` ("more than 10000 directories below …; open a path closer to the indexes"). Opening has always been a request, not a job (rule 7). A bounded walk keeps it short enough to stay one, and nobody means to open their home directory as an index. With no index found, the open fails with `NOT_AN_INDEX` ("No Lucene index in or below …").

### Opening with a reader (the default)

4. **Each index found is opened with a `DirectoryReader`,** through the chosen `Directory` (ADR 0020), without locking (rule 1). One index is the open index as before. Several are combined into one `MultiReader`, so documents, terms, search and field statistics cover all of them: document ids run through the indexes in order, and terms and statistics are merged.
5. **An index that fails to open is skipped and reported.** The others open, and the result lists each skipped directory with the error message. Only if none opens does the open fail, with the error of the first one, and its message names its directory. An index whose fields are indexed differently from those of the indexes before it (other index options, doc values type, point or vector dimensions) can't be combined with them, as Lucene's `FieldInfos.getMergedFieldInfos` refuses, so it is skipped and reported too.
    - *Amended with the backlog item of ADR 0090:* every runtime but 3.6's skips such an index, its Lucene deciding what conflicts: 9.12 and 10 refuse any change of a field's schema, such as a field that has doc values in one index and none in another, and an index whose parent field (of an index sort over blocks) differs; 5.5 and 7.7 merge a field that gains doc values or points and refuse only a change of a type already set. Lucene 3.6 merges the flags of a field and never refuses (`IndexParts.compatible`, ADR 0050).

### Opening without a reader

6. **`openIndex` takes an optional `mode`:** `READER` (absent) or `DIRECTORY_ONLY`. `DIRECTORY_ONLY` finds the indexes the same way, but opens only a `Directory` for each, which reads no index file, so it fails only if no index is found. Only the commits can be read: `getCommits` and `getCommit`. Every method that needs a reader fails with a new error, **`NO_READER` (-32015)**: `getIndexStats`, the document, term and search methods, and `reopenIndex`, since the commits are read fresh on every call anyway. The commit list marks no commit as current, as none is opened.
    - *Amended by ADR 0045 §4, §5:* an index of Lucene 2.x or 3.x opens without a reader too; its commits are read by its runtime, and the open fails with `MISSING_RUNTIME` if none is loaded.
7. **The TUI enters it in two ways.** Ctrl+N in the open prompt switches "without a reader" on or off for that open; it is shown in the prompt's bottom border with the directory, and is off again in the next prompt. `--no-reader` opens the index of the command line that way. When an open fails with `CORRUPT_INDEX` or `MISSING_CODEC`, the prompt's error says that Ctrl+N and Enter open it without a reader.

### On the wire

8. **`openIndex`' result describes every index.** It has `path` (the path that was opened), `mode`, `numDocs` and `maxDoc` over all of them (absent without a reader), `indexes` and `skipped`. Each entry of `indexes` has its `path` and, with a reader, `reader`: `docBase`, `segmentsFile`, `numDocs`, `maxDoc`, `createdVersionMajor` and `minSegmentLuceneVersion`. Each entry of `skipped` has `path` and `message`. `reopenIndex`' result is the same plus `changed`, and its `skipped` is the one of the open.
9. **`getIndexStats` splits what is per commit from what is merged.** The top level has `path`, `directoryImpl`, `segmentCount`, `numDocs`, `maxDoc` and `fields`, over all indexes. `indexes` has, per index, what was the commit part before: `path`, `docBase`, `segmentsFile`, `generation`, `version`, `segmentCount`, `numDocs`, `maxDoc`, `createdVersionMajor`, `commitLuceneVersion`, `minSegmentLuceneVersion` and `userData`.
10. **`getCommits` and `getCommit` take an optional `index`:** the position of the index in `indexes`, 0 if absent. An index that doesn't exist fails with `INVALID_PARAMS`.
11. **`getCommits` lists the commits it can't read** in `unreadable`, each with its `segmentsFile` and `message`, worded as opening the index would word it ("Corrupt index, missing file: …"). Before, the server only logged them, but they are what a user opening a corrupt index without a reader needs to see.

These change the results of `openIndex`, `reopenIndex` and `getIndexStats` for one index too. The TUI and the server ship together, so there is no older client to keep working, and one shape for one or many indexes keeps both sides simpler than a second set of optional fields.

### Reopening several indexes

12. **`reopenIndex` reopens each index with `openIfChanged`** (ADR 0019). If any changed, a new `MultiReader` is built from the new readers and the unchanged ones, which the two share. The walk isn't repeated: indexes added or removed below the path need `o`.

### The TUI

13. **Overview:** with several indexes, the index panel has the totals, then one line per index (its path below the opened one, segments file, documents, Lucene version), then the skipped directories with their errors. With one index it looks as before.
14. **Documents:** with several indexes, the document's index is shown with its id.
15. **Commits:** with several indexes, an `Indexes` panel comes first. The commits below are those of the selected index, and Tab goes through indexes, commits, segments and details. The commits that can't be read follow the others, marked `✗`, with the reason.
16. **Without a reader:** the overview has only the index panel, which says that the index was opened without a reader. Documents, terms and search say that they need a reader. Opening switches to the commits screen unless the analysis screen is shown. `R` says that there is no reader to reopen. The tab line shows "no reader", or with several indexes how many there are.
    - *Amended by ADR 0044 §6:* the features of `openIndex` decide instead. The tabs of documents, terms and search are dimmed, and their keys and `R` give a notice on the tab line: "Documents: needs a reader", "Reopen: needs a reader".
17. **Reopen notice:** with several indexes, "Reopened: 2 of 3 indexes" and "Unchanged: 3 indexes".

## Alternatives considered

- **Always walk, as Luke does.** An index directory never holds another index, so the walk would only cost time and could take in stray copies, such as a backup in a subdirectory. Rejected.
- **A toggle for the walk.** Opening a directory of indexes would need a key that the user must know about, for no gain: without the walk, such a path only fails with `NOT_AN_INDEX`. Rejected.
- **Fail the open if any index fails.** One broken index would hide all the good ones. Skipping and reporting shows both. Rejected.
- **The walk as a job, with progress and `cancelJob`.** Correct for an unbounded walk, but it would turn opening into a job, which every caller would have to poll. Bounding the walk is simpler and enough. Rejected.
- **Fall back to no-reader mode by itself.** A user who mistyped the path, or who lacks a codec jar, would land in a mode where little works without having asked for it. Offering it on the errors where it helps is enough. Rejected.
- **No-reader mode for one index only.** But a corrupt index skipped in a walk could then only be looked at by opening its own directory. Opening the same path without a reader, with the same walk, shows the commits of all of them. Rejected.
- **Keep the old result fields for one index and add optional ones for several.** No fixture changes for the single case, but every field that is per commit would be wrong or empty with several indexes. Rejected for one shape.
- **Commits off with several indexes, as in Luke.** Simpler, but the commits of each index are as readable as with one. Rejected.

## Consequences

- `leia-core`:
  - `IndexFinder`, which finds the indexes;
  - `ReadOnlyIndex`, which holds one or more indexes, each with its `Directory`, `DirectoryReader` and commit, behind one `IndexReader`, plus the skipped directories;
  - `DirectoryOnlyIndex`;
  - the sealed `OpenedIndex`, which both implement, for the commits and for closing.

  The readers that read documents, terms and searches take an `IndexReader`. Search wraps each `DirectoryReader` in an `ExitableDirectoryReader`, which keeps cancellation working with several indexes.
- `leia-protocol` and the TUI:
  - `OpenMode`;
  - the new shapes of `OpenIndexResult`, `ReopenIndexResult` and `GetIndexStatsResult`;
  - `index` in `GetCommitsParams` and `GetCommitParams`, and `unreadable` in `GetCommitsResult`;
  - `NO_READER` in both `ErrorCode` enums and `protocol/error-codes.json`;
  - fixtures for several indexes, for no-reader mode, for the commits of another index and for `NO_READER`.
- `leia-server`: `CurrentIndex` holds an `OpenedIndex`, and gives the handlers a `ReadOnlyIndex`, or fails with `NO_INDEX_OPEN` or `NO_READER`.
- TUI:
  - `--no-reader` and Ctrl+N in the prompt;
  - the multi-index overview, documents and commits;
  - the screens without a reader;
  - the tab line marker.
