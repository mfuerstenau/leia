# ADR 0019 — Reopening the index

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

leia opens the latest commit of an index and keeps reading it. Another process can write the index at the same time: a test run, an application under development, a search server. To see its new commits today, the user opens the same path again with `o`. That reads every segment again, resets every screen and can't say whether anything changed.

Lucene has `DirectoryReader.openIfChanged(reader)`. It reads the latest `segments_N` and returns a new reader only if that commit differs from the reader's. The new reader shares the readers of the segments that are in both commits, so a reopen after a small change is cheap even on a large index. It returns `null` if nothing changed. It needs no `IndexWriter` and no lock (rule 1). Luke has the same as "Reopen".

The other open options (directory choice, `MultiReader`, no-reader mode, recent paths, Elasticsearch/OpenSearch) are not part of this ADR.

## Decision

1. **A method of its own: `reopenIndex {}`.** It reopens the open index with `openIfChanged`. The result has the fields of `openIndex`'s result plus `changed`. If nothing changed, `changed` is `false`, the fields describe the index as it is, and nothing else happens: the jobs keep running. If it changed, the new reader becomes the open index, and the jobs of the old one are stopped before the old reader is closed, as for `openIndex`. With no index open, it fails with `NO_INDEX_OPEN`.
2. **A failed reopen keeps the old index.** The errors are those of `openIndex` (`INDEX_NOT_FOUND`, `NOT_AN_INDEX`, `CORRUPT_INDEX`, `MISSING_CODEC`, …), and the index that was open stays open, with its jobs.
3. **Core: `ReadOnlyIndex.reopen()`** returns the new `ReadOnlyIndex`, or nothing if the index is unchanged. Both share the `Directory`. The new one owns it from then on, so closing the old one closes only its reader.
4. **The TUI reopens with `R`,** on every screen with an index open. The overview and the commits screen list it with their keys; the other screens' key lines are full. A notice on the tab line says what came of it, until the next key: "Unchanged: segments_N", "Reopened: segments_N", or why it failed. No reopen happens by itself.
5. **The TUI keeps the user's place.** An unchanged index changes nothing. A changed one is shown as after opening it, with the term scans forgotten, since their counts are stale. But these are kept:
   - the screen;
   - the overview's sort and its selected field;
   - the documents screen's document id, clamped to the new `maxDoc`, and how it shows values (empty fields, number format, delimiters);
   - the terms screen's field and focused column;
   - the search screen's mode, field, operator, query text, exact count and analyzer. Its parser, More-Like-This, scoring and hit field settings are kept if the fields are the same, ignoring their postings statistics. A search is not run again by itself.

   Like the terms screen's term list, the shown hits and postings are fetched again or dropped, because their document ids may refer to other documents now.

## Alternatives considered

- **Let the TUI call `openIndex` with the same path.** No protocol change, but every segment is read again, and the TUI can't tell "unchanged" from "changed". Rejected.
- **Reset the screens as `openIndex` does.** Simplest, but a reopen is meant to be done often, and every one would throw away the user's place. Rejected.
- **Keep the user's place and run everything again** (the last search, the terms page and its postings). Every screen would show new data at once, but a slow search would start without being asked for. Rejected.
- **Reopen by itself,** every few seconds or on a key toggle. Useful for watching a live index, but screens would change under the cursor. Left out; `R` can be pressed as often as needed.
- **Make `ReadOnlyIndex` swap its reader in place.** One object for the server to hold, but jobs read the index from their own threads, and swapping under them needs locking. A new object, made the open index as `openIndex` does, needs none. Rejected.

## Consequences

- `leia-core`: `ReadOnlyIndex.reopen()`. The index that reopened hands the directory on and closes only its reader.
- `leia-protocol` and the TUI: `reopenIndex` with `ReopenIndexParams`/`ReopenIndexResult`, and the fixtures `reopenIndex.request.json`, `reopenIndex.response.json`, `reopenIndex.response-unchanged.json` and `reopenIndex.error-no-index-open.json`.
- `leia-server`: `ReopenIndexHandler`, and `CurrentIndex.replace` for the new index.
- TUI: the `R` key, the notice on the tab line, and a "reopened" path through every screen that keeps the state named above.
