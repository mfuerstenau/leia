# ADR 0062 — Rebuilding a document on Lucene 3.6

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0059 §2 (3.6 has no rebuild) and its Consequences

## Context

`r` (rebuild) and the export of indexed terms need `RECONSTRUCT`. Since ADR 0059 every runtime reports it except 3.6, whose `Lucene3Index.rebuild()` was empty, so both were greyed out on indexes of Lucene 2.x and 3.x. The runtime interface already has the `Rebuild` group, and the server and the TUI already handle it; what was missing is a 3.6 implementation over `TermEnum`, `TermPositions` and `TermFreqVector`.

3.6 differs from 5.5 and later in three ways that matter here:

- **Its term vectors have no payloads**, and its postings have no offsets.
- **It has no `Terms.size()`**: a segment doesn't know how many terms a field has.
- **Its postings skip deleted documents.** `SegmentTermDocs` and `SegmentTermPositions` take the segment's `deletedDocs`; `SegmentReader.getTermFreqVector` doesn't check them. The only public way around the deletions, `IndexReader.rawTermDocs(Term)`, has no positions.

## Decision

1. **`Lucene3Rebuild`**, after `Lucene5Rebuild` (ADR 0059 §2), over the segments that `ReaderUtil.gatherSubReaders` gives, so it works through the `MultiReader` of several directories too. Per field of the document's segment:
   - not there or not indexed: `NONE`, no terms;
   - a term vector that covers the postings: `TERM_VECTOR`, read with `Lucene3Index.vectorTerms`, which the index's `termVector` uses too. A vector covers its field if the field stores no payloads and, for a field with positions, the vector has positions (an empty vector counts as covering);
   - otherwise `POSTINGS`: every term of the field in the segment, one `TermDocs` or `TermPositions` per field moved to it with `seek(TermEnum)` and `skipTo(doc)`; `freq` 1 without freqs, the positions with their payloads through `Lucene3Index.position`.
   Points are always empty. The progress is reported every 1024 terms and at the end.
2. **A field with payloads is always rebuilt from the postings**, since 3.6's term vectors can't hold them.
3. **No total.** `count` is empty as soon as one field is rebuilt from the postings, and 0 when every field comes from a term vector.
4. **Raw postings through a helper in Lucene's package.** `org.apache.lucene.index.RawPostings`, compiled in leia-lucene3, builds a `SegmentTermDocs(segment, true)` and a `SegmentTermPositions(segment)` whose `deletedDocs` it clears. Both are package-private; the lucene-core 3.6.2 jar isn't sealed, so a class of the same package can reach them. Shadow relocates it with the rest of Lucene into `…lucene3.shaded.lucene.index`, so `relocationCheck` still finds no `org/apache/lucene` in the jar. Deleted documents are so rebuilt on 3.6 too, as on every other runtime.
5. **No interface change.** `Lucene3Index.rebuild()` returns the rebuild, so 3.6 reports `RECONSTRUCT` through the existing group; `API_VERSION` stays.
6. **The test index.** The 3.x index has a field `payload`, "alpha document <i>" with the payload `p<i>` on every position and term vectors with positions, so the rebuild reads it from the postings, deleted document included.

## Alternatives considered

- **`rawTermDocs(Term)`**: public and raw, but without positions; a field with positions would lose them for deleted documents only.
- **Live documents only**: the postings as 3.6 gives them. A deleted document would lose every field that its term vector doesn't cover, unlike on every other runtime.
- **Reflection on `deletedDocs`**: works as well, but needs `setAccessible` and fails only at run time if the field changes; the in-package class is checked by the compiler against 3.6.2.

## Consequences

- `RECONSTRUCT` is reported on every runtime; `docs/runtimes.md` shows it, and its Differences row says what 3.6 lacks: points, a total and payloads in term vectors.
- `r` and the export of indexed terms work on indexes of Lucene 2.x and 3.x, with no change to the TUI; the progress there has no total.
- `RawPostings` depends on package-private internals of lucene-core 3.6.2, which is pinned and won't change.
