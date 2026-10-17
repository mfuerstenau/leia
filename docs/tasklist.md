# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Bugs

From the manual run of the per-segment document view (ADR 0051) on the demo index, `lucene3` and `lucene24` + `lucene3` as one; every check of `docs/demo-index.md` passed.

- [ ] Documents: a segment or filter note outlives the move it was about. `segmentNote` is cleared only by a step key and `showSegment`, the filter's note only by a step key. Demo index: `2`, `i 37` (title `left segment _2`), then on the search screen `title:lamp AND body:qu*` and `b`: document 223 still says `left segment _2`. Then `S`, → to `no next match in _1`, and on the commits screen ⏎ on `_0` of `segments_1`: the title says `segment _0 … · no next match in _1`. Clear both notes when another screen shows a document (`showHit`, `showPoint`, `showSearchHit`, `showSegment`) and when a filter starts.
- [ ] Commits: at 100 columns the details panel's file list wraps each size onto the next line (`_0.cfe   842` / `B  _0`). Keep a file's name, size and segment on one row, or drop the segment column when it doesn't fit.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter, cross-screen navigation (`docs/luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6). Done already: deleted documents (ADR 0006, the filter's `includeDeleted`), binary values as text or hex (ADR 0006), cancellation of long operations (ADR 0005), KNN vectors (ADR 0040), point values (ADR 0041), highlighting of hits (ADR 0042), the per-segment document view (ADR 0051). Left:
  - [ ] KNN: HNSW graph inspection (a node's levels and neighbours through `HnswGraphProvider`), and a recall check of approximate against exact (left out of ADR 0040)

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
