# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Lucene 9.12 beyond classic search

Goal: an index created by Lucene 8.x (read with `leia-lucene9`, ADR 0049) gets the features that are greyed out for it today. 9.12 has the APIs that leia-core uses for them on Lucene 10, so most are ports. What needs no runtime change goes to every runtime at once. The others need runtime interface 7 (one ADR for the interface, amending ADR 0049 §4). They land in 9.12 first, and in 5.5 and 7.7 where the copy is the same (ADR 0050). Each item adds its feature in `RuntimeResults.features`, a `runtime()` branch in its handlers, and its row in `docs/runtimes.md` (checked by `RuntimeFeaturesTest`). The TUI already gates on the features.

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
