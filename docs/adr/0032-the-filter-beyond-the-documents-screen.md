# ADR 0032 — The filter beyond the documents screen

- **Status:** Accepted
- **Date:** 2026-10-08
- **Amended by:** ADR 0114 (a third source of `startFilter`, `storedField`)

## Context

ADR 0031 narrows the documents screen to the matches of a query: the server keeps them as a bit set (`DocFilter`), and `←`/`→` step through them. It left three things out:

- **The terms screen doesn't know about the filter.** Its postings list every document that contains the selected term, so to see which of them the filter holds, one has to open each one.
- **The regex over stored values (ADR 0027) can't be a filter.** It finds values, not documents, and it is no `SearchQuery`. Yet "the documents whose `_source` mentions X" is exactly what one wants to step through.
- **A reopen that finds a new commit forgets the filter**, since doc ids may change (ADR 0031 §5). After `R`, the user has to go back to the search screen and press `b` again.

## Decision

### The regex as a source

1. **`startFilter` takes a query or a pattern.** `StartFilterParams` becomes `{query?, pattern?, includeDeleted}`. Exactly one of the two must be given, or the call fails with `INVALID_PARAMS`.
   - `query` is unchanged, so earlier requests stay valid.
   - `pattern` is `FilterPattern {pattern, caseInsensitive, fields?}`: the same as `startStoredScan` without `includeDeleted`, which stays on the filter. The checks are the same too: `FIELD_NOT_FOUND` for an unknown field, and `INVALID_QUERY` for a pattern that doesn't compile.
2. **A pattern filter is a full scan of its own.** Core's `StoredScan` gets a second entry point, `ReadOnlyIndex.storedScanMatches`. It sets the bit of every document with a value in which the pattern is found, and passes no hits on. The same `ScanProgress`, per-value time limit (1 s) and cancellation apply as in ADR 0027 §6, and `getFilter`'s `docsSearched` counts the documents gone through.
3. **`FilterResult` gains `timedOutValues`**: present for a pattern, absent for a query. The documents of those values may be missing from the filter. For a pattern, `query` and `rewrittenQuery` are both `/pattern/`, with an `i` after it if case doesn't matter.

### Postings through the filter

4. **`getPostings` takes an optional `filter {filterId, onlyMatches}`.**
   - Without `onlyMatches`, every posting carries `inFilter`.
   - With it, the page has only the postings of documents in the filter. The postings enum and the bit set leapfrog (`advance` to the filter's next match, and back), so a sparse filter over a large term costs about as many steps as there are matches, and the call stays synchronous.
   - `docFreq` and `totalTermFreq` stay the term's own. `nextDoc` is the next posting that is shown.
   - An unknown filter fails with `FILTER_NOT_FOUND`.

### Rebuilding after a reopen

5. **The TUI builds the filter again after a reopen that finds a new commit.**
   - **What it remembers:** the TUI keeps the `StartFilterParams` that built the filter.
   - **How it rebuilds:** after the screens are rebuilt, it calls `startFilter` again. The tab line says `Reopened: segments_N; rebuilding the filter`, and the title shows the progress as for a new filter.
   - **No server change:** the server still forgets the filter with the old index (ADR 0031 §5).
   - **Once built, the filter only locates the shown document.** The title says `n of M` or `not a match`; the screen doesn't move, since the reopen already kept the document.
   - **Failed filters stay failed:** a filter that failed is not built again, since it would fail again.

### The TUI

6. **`b`/`B` on the search screen work in regex mode as well.** They take the pattern line, `i` (ignore case) and the fields of `l`, and start on the selected scan hit's document. As in the other modes, `B` includes deleted documents; the scan's own `d` doesn't carry over. The title shows `filter /pattern/i: …`, plus `· N values timed out` if any were skipped.
7. **The terms screen reads its postings through the built filter.**
   - **Marking:** postings in the filter get a `•` before the document id, and the others are dimmed. The title says `Postings (N) · • filter`.
   - **`b`** switches to only the postings in the filter (`Postings (N) · filter only`) and back. The footer says which.
   - **Changing the filter:** building a new filter, clearing it or a failure fetches the postings again; without a filter, all of them are shown.
   - The postings column is 4 characters wider, for these titles.
   - `[`/`]` on the documents screen walk the term's postings as the terms screen shows them, so with `b` only those in the filter.

## Alternatives considered

- **Reusing the scan's matches.** The stored scan could record its matching documents too, and `b` after a finished scan could keep them as the filter without a second pass. It saves a full scan on a large index. However, the scan's outcome would have to outlive its job (ADR 0005), and its `includeDeleted` would fix the filter's. Rejected for now; a query filter runs its query again too.
- **A method that says which ids of a page are in the filter** (`filterContains {filterId, docIds}`): no change to `getPostings`, and usable for search hits too. It can't hide postings, though, since a page of 100 postings might hold none in the filter. Rejected for an optional `filter` on `getPostings`.
- **Jumping to the nearest match after a rebuild**, as a new filter does. It moves the user off the document the reopen kept. Rejected.
- **Not rebuilding, but saying the filter is stale and offering a key.** It spares an unasked full scan for a pattern filter. Rejected: `Esc` stops a rebuild that takes too long, and a filter that silently goes away is the reason for this ADR.
- **The server rebuilding the filter itself on reopen.** The server would have to keep the query and its analyzer, and start a job no request asked for, whose id the TUI doesn't know. Rejected.

## Consequences

- `StartFilterParams.query` becomes optional, and `FilterPattern`, `PostingsFilter` and the optional `FilterResult.timedOutValues`, `GetPostingsParams.filter` and `Posting.inFilter` are new, with fixtures on both sides. No new methods and no new error codes.
- `Filters.get`/`put` and `GetPostingsHandler` now share the kept filter. `DocFilter` gains `contains` and `next`, and `PostingsPage.Posting` gains `inFilter`.
- After a reopen, a pattern filter scans every stored value again, unasked. The title shows the progress, and `Esc` stops it.
