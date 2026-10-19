# ADR 0059 — Runtime interface 11: rebuilding a document

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0049 §4 (the runtime interface); ADR 0044 §4 (the features of an index)

## Context

The documents screen's `r` (rebuild) and the export's "indexed terms" option need `RECONSTRUCT`, which only Lucene 10 reported: `StartReconstructHandler` had no branch for a runtime, so on an index that a runtime reads both were greyed out. The rebuild is leia-core's `Reconstruct` (ADR 0029, ADR 0041): per field, the terms in term order, read from the document's term vector when it covers the postings, or else by advancing every term of the field in the document's segment to the document; the points by going through every point of the field in the segment; deleted documents too; progress every 1024 terms or points, with a total when every segment knows its sizes.

5.5, 7.7 and 9.12 have the same postings and term vector API as Lucene 10, so the rebuild is a copy on each of them (ADR 0050). 3.6 has `TermEnum` and `TermPositions` instead, term vectors without payloads, and no `Terms.size()`, so it needs a port of its own.

## Decision

1. **A `Rebuild` group.** `RuntimeIndex.rebuild()` returns an optional `Rebuild`, as `points()` and `knn()` do, so the feature follows the group: `count(docId, fields)` gives the total, empty if a segment doesn't know a field's term count, and `rebuild(docId, fields, progress)` the `RebuiltField`s in the order of `fields`. A `RebuiltField` has the segment's `IndexOptions` (`NONE` if the segment lacks the field), its `Source` (`TERM_VECTOR` or `POSTINGS`), its terms as `Documents.VectorTerm`s (`freq` 1 without freqs, no positions without positions) and its points, packed, in unsigned order with duplicates kept. The progress throws to cancel, as for every other job of a runtime.
2. **The runtimes.** `Lucene5Rebuild`, `Lucene7Rebuild` and `Lucene9Rebuild` are copies of `Reconstruct`, and share the term vector reading with their index's `termVector` (`vectorTerms`, `position`). They differ where their Lucene does:
   - term vectors: `LeafReader.getTermVectors(doc)` on 5.5 and 7.7, `termVectors().get(doc)` on 9.12;
   - points: none on 5.5, which predates them; `PointValues.intersect` with a visitor that crosses every cell on 7.7, which has no `PointTree`; `getPointTree().visitDocValues` on 9.12, as on 10;
   - deleted documents: 5.5's `postings(reuse, flags)` takes no live docs, as on 10, so every runtime rebuilds them.
   3.6's `Lucene3Index.rebuild()` is empty, so 3.6 doesn't report `RECONSTRUCT`.
3. **The server.** `RuntimeResults.features` adds `RECONSTRUCT` with the group. `StartReconstructHandler` sends a runtime's index to `RuntimeResults.startReconstruct`, which checks the document (`DOC_NOT_FOUND`) and each field (`FIELD_NOT_FOUND`; `INVALID_PARAMS` for a field without postings or points, with the message of Lucene 10) before the job. Without fields it rebuilds every field with postings or points, in field number order, as `ReadOnlyIndex` does. The total is counted before the job starts and goes with it, so `getReconstruct` reports it unchanged. The job's description names the runtime's Lucene.
4. **Runtime interface 11.** `LuceneRuntime.API_VERSION` is 11, so a runtime jar built for interface 10 is refused (ADR 0044).

## Alternatives considered

- **`rebuild` as a method of `Documents`**: 3.6 has documents but no rebuild, so it would have to throw, and the feature couldn't follow the group.
- **Rebuilding through `Terms` and `Points`** in the server, one term's postings at a time: a call per term of the field over the whole index, not its segment, and no cancellation between them.
- **Porting 3.6 now**: its enums, its term vectors without payloads and its missing term counts make it a separate piece of work; it is on the tasklist.

## Consequences

- `RECONSTRUCT` is reported on 5.5, 7.7 and 9.12; `docs/runtimes.md` has its row and a Differences row: points are rebuilt on 7.7, 9.12 and 10 only.
- `r` and the export of indexed terms work on every index with a reader except one of 2.x or 3.x, with no change to the TUI.
- A fix to the rebuild belongs in `Reconstruct` and the three copies.
