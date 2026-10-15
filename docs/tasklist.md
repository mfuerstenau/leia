# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Bugs

None right now.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Old indexes (ADR 0044, spike on `lucene-2.4-spike`): decide on the spike; then, for Lucene 3.6, commits and `CheckIndex`, a 3.x test index, several old indexes under one path, top terms, and hiding the keys of features an index lacks; fold the no-reader checks into `features`; further runtimes 5.5.5 (4.x, 5.x), 7.7.3 (6.x, 7.x) and 9.12.3 (8.x)
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter, cross-screen navigation (`docs/luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6). Done already: deleted documents (ADR 0006, the filter's `includeDeleted`), binary values as text or hex (ADR 0006), cancellation of long operations (ADR 0005), KNN vectors (ADR 0040), point values (ADR 0041), highlighting of hits (ADR 0042). Left:
  - [ ] Per-segment document view: a document's segment and local id, stepping within one segment, and a jump from a segment on the commits screen to its documents
  - [ ] KNN: HNSW graph inspection (a node's levels and neighbours through `HnswGraphProvider`), and a recall check of approximate against exact (left out of ADR 0040)

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
