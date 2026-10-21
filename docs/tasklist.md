# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Runtime download: a TamboUI dialogue

Goal: `leia --download-runtimes` (ADR 0073, ADR 0077) lets the user choose which runtimes to install in a TamboUI dialogue, not a `[y/N]` line, and the runtimes come from a catalog instead of code.

- [ ] A TamboUI dialogue for `--download-runtimes`: a checkbox per runtime (`lucene3` … `lucene10`), not per file, with **none selected by default**, plus "select all" and "select none". Each runtime still shows its details: the jars it would download, each with its URL, SHA-256 and size, and what is already there and matching. Only the checked runtimes are installed; nothing checked means nothing downloaded. Keep `--yes` (all runtimes, no dialogue) and the refusal without a terminal (ADR 0077 §3, §4); decide whether `--yes` should take a list of runtimes. A new ADR amending ADR 0077.
- [ ] A spinner while downloading, in the dialogue: per jar and overall progress (bytes of the total), and what failed. The download runs off the UI thread, as a job does (hard rule 7), and can be cancelled.
- [ ] The runtimes in a JSON catalog in `leia.jar`'s resources instead of hardcoded in `RuntimeFolders.KNOWN`: folder, adapter jar, the index versions it reads (`List.of(9, 10)` today) and a display name. `RuntimeFolders` (server) and `RuntimeInstall` (TUI) read it; a build check keeps it in line with the runtime projects. leia-server reads it without Jackson (it is only in `leia-tui`), or the parsing lives in the TUI and the server gets the records; decide in the ADR.

## Lucene 9.12 beyond classic search

Goal: an index created by Lucene 8.x (read with `leia-lucene9`, ADR 0049) gets the features that are greyed out for it today. 9.12 has the APIs that leia-core uses for them on Lucene 10, so most are ports. What needs no runtime change goes to every runtime at once. The others need runtime interface 7 (one ADR for the interface, amending ADR 0049 §4). They land in 9.12 first, and in 5.5 and 7.7 where the copy is the same (ADR 0050). Each item adds its feature in `RuntimeResults.features`, a `runtime()` branch in its handlers, and its row in `docs/runtimes.md` (checked by `RuntimeFeaturesTest`). The TUI already gates on the features.

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
