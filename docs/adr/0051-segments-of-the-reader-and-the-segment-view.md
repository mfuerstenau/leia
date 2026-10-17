# ADR 0051 — Segments of the reader and the segment view

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0044 (runtime interface 6); ADR 0009 (where the documents screen gets its segments)

## Context

Luke shows no document's segment (leaf) (`docs/luke-features.md` §6). The backlog asks for a document's segment and its id within it, for stepping within one segment, and for a jump from a segment on the commits screen to its documents.

The documents screen knows where each index directory starts (`IndexStart`, from `getIndexStats`). `getCommit` has each segment's `docBase` and `maxDoc` (ADR 0009), but it reads the directory again on every call, and the directory may have moved on since the reader opened: a newer commit, a merge that removed the opened commit's segments, a commit that `R` hasn't opened yet. Only the open reader can say which segments the documents are read from.

## Decision

1. **`getIndexStats` sends the open reader's segments.** `IndexCommitStats` gets `segments`, a list of `ReaderSegment(name, docBase, maxDoc, numDocs)` per index directory, in document order: the leaves of the directory's reader, not the `segments_N` file. `docBase` counts across the whole opened path, as `IndexCommitStats.docBase` does; `numDocs` counts live documents. The name is the `SegmentReader`'s, unwrapped as for points (ADR 0041); a leaf that isn't one would be `?`, which a `DirectoryReader` doesn't have.
2. **No new method, and no field on `getDocument` or `stepFilter`.** The UI fetches `getIndexStats` after every open and reopen already, so it works out a document's segment, its local id, the bounds and the jumps itself, by a binary search over the segments' `docBase`.
3. **Runtime interface 6.** `RuntimeIndex.IndexStats` gets `segments` (`RuntimeIndex.Segment`), with `docBase` counted within the directory; the server adds the directory's `docBase`. Every runtime fills it: 3.6 from `getSequentialSubReaders`, 5.5, 7.7 and 9.12 from `leaves()` with `FilterLeafReader.unwrap`. No `Feature`: every runtime has it. A runtime jar built for 5 is refused, as before (ADR 0044).
4. **The documents screen**:
   - The header shows the segment, the local id and the segment's range after the index: `#417 of 0–499   _2 #17 of 0–99`. Without segments (no reader), nothing.
   - `S` pins the segment of the shown document, and unpins it. The panel title says `segment _2`.
   - While pinned, ←/→ stop at the pin's bounds (`first document of _2`, `last document of _2`). With a filter, `stepFilter` is unchanged; `DocFilter.stepped` takes the pin's range, and a match outside it counts as none (`no next match in _2`), so the located match stays right. A forward `NEAREST` step (`i`, a new filter's start) that finds nothing in the pin looks back within it, as it looks back without a pin.
   - `<`/`>` go to the first document of the previous or next segment, across index directories; the pin, if any, moves along. With a built filter, they step to the segment's first match, kept within that segment even without a pin; a segment without a match shows its first document.
   - The pin ends when a document outside it is shown, whatever showed it (`i`, a term hit, a point, a search hit or value, `[`/`]`), and the title says `left segment _2` until the next key. `i` is global: a document outside the pin unpins before a filter steps to it.
   - A reopen keeps the pin if the same index directory's reader still has a segment of that name, with its new bounds, and shows the same document of it (segments don't change, only their `docBase`). Otherwise the pin is dropped, and the title says `segment _2 is gone`.
5. **The commits screen**: ⏎ on a segment, with the segments panel focused, shows its documents, pinned on its first one (`Request.ShowDocuments`). Only with a reader open. A segment of an older commit is found by name if the opened commit shares it; one that was merged away gives the notice `_5 isn't in the opened commit`. The legend says `⏎ documents` while the segments have the focus.

## Consequences

- `getIndexStats` grows with the segment count, which stays small next to the fields.
- The reverse jump, from a document to its segment on the commits screen, is left to the cross-screen navigation item. ADR 0052 adds it as `C` on the documents screen.
- The segment list is as old as the last `getIndexStats`; it is fetched again on every reopen, which is the only time the reader changes.
