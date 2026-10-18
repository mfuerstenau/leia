# ADR 0056 — Runtime interface 8: matches for the filter

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0049 §4 (the runtime interface); ADR 0031 §4 (deleted documents in a query filter, on 3.6); ADR 0032 (postings through a filter on a runtime)

## Context

The filter (`FILTER`, ADR 0031, ADR 0032) worked only on Lucene 10 indexes. On an index that a runtime reads (3.6, 5.5, 7.7, 9.12), the documents screen's `b`, the search screen's `b`/`B` and the terms screen's `b` were greyed out.

The pattern filter already had its data: `RuntimeResults.storedSource` with `StoredScan.matches` (ADR 0055). The query filter had no way to the runtime: `Search.search` scores and pages, but a filter needs every match, unscored, and the deleted documents too on request. The postings through a filter were refused on runtimes (`RuntimeResults.postings` threw `NOT_SUPPORTED`).

## Decision

1. **`Search.matches(query, includeDeleted, progress)` and `Search.matchesDeleted()`.** `matches` returns `Matches(BitSet docs, query, rewrittenQuery, tookMillis)`: every document id that matches a query of `Search.parse` or `Knn`, unscored, the deleted ones too if `includeDeleted`. `progress` gets the documents searched so far and throws to stop. `matchesDeleted()` says whether `includeDeleted` is taken; without it, `matches(…, true, …)` throws `IllegalArgumentException`. `java.util.BitSet` keeps the interface to JDK types; the copy into core's `FixedBitSet` (`DocFilter.of`) costs maxDoc / 8 bytes a second time while it is made.
2. **The runtimes.**
   - 9.12, 7.7 and 5.5: an `IndexSearcher` without query cache, a no-score collector that sets `docBase + doc`. With `includeDeleted`, the searcher reads a `MultiReader` (not closing its subreaders) over the index's leaves, each wrapped in a `FilterLeafReader` without live docs, as core's `UndeletedLeaf` (ADR 0031 §4): the same document ids, nothing to skip. The wrapper is never closed: closing it would decrement only the wrappers, and it holds nothing.
   - Cancellation: 9.12 asks the progress through the searcher's `QueryTimeout`, as its search does (ADR 0049 §5). 7.7 and 5.5 have no such hook, so their collector asks it per segment and every 1024 matches.
   - 3.6: `matchesDeleted()` is false. Its scorers read postings through `TermDocs`, which skip deleted documents, and 3.6 has no raw postings that a wrapping reader could give them instead.
3. **The server.** `RuntimeResults.features` adds `FILTER` with the `search()` group. `startFilter` branches on the runtime after the "exactly one of query and pattern" check, as `startSearch` does. The query goes through `parsedQuery` (the classic/KNN choice that `startSearch` uses too); the job is "filter by <query>[, deleted documents too] with Lucene <version>", and builds `DocFilter.of(matches, maxDoc, …)`. The pattern filter runs `StoredScan.matches` over `storedSource`. `getFilter`, `stepFilter`, `releaseFilter` and `Filters` are unchanged: they only see a `DocFilter`.
4. **3.6 refuses a query filter with deleted documents.** `startFilter` answers `NOT_SUPPORTED` at once: "Not supported with Lucene 3.6.2: it doesn't read the postings of deleted documents". `B` on the search screen shows it as its error line. The pattern filter, `b` and the postings through a filter work on 3.6.
5. **Postings through a filter on a runtime.** `getPostings` resolves the kept filter before it branches. Without `onlyMatches`, it reads one runtime page and marks each posting with `inFilter`. With `onlyMatches`, the server leapfrogs as core's `TermReader` does: from the filter's next match on, it reads a page of the runtime's postings, keeps those in the filter, and goes on from the filter's next match after the page's `nextDoc`, until it has `limit + 1` postings or either side ends. It returns `limit` of them, and `nextDoc` is the extra one's id, as on Lucene 10. With no match left, it still reads an empty page, for `docFreq` and `totalTermFreq`.
6. **Runtime interface 8.** `LuceneRuntime.API_VERSION` is 8, one bump for this item (ADR 0055 §5).

## Alternatives considered

- **Doc ids as an `int[]`**: a dense filter of a large index would take 32 times the memory of a bit set.
- **Paging matches through the interface**, as the stored values are paged (ADR 0055 §2): a query can't resume from a document id without re-running from the start, so each page would cost a full search.
- **Leapfrogging inside the runtime** (`Terms.postings` with a doc-id set): one more interface method and a bit set across the boundary per page, for what the server does with the existing pages.
- **Refusing deleted documents on 3.6 by leaving `FILTER` out**: would also take away the pattern filter and the live query filter, which work.

## Consequences

- `FILTER` is reported on every runtime with a reader; `docs/runtimes.md` has it ✓ in each column, with a footnote for 3.6.
- A runtime jar built for interface 7 is refused (ADR 0044).
- The TUI is unchanged: it already gates `b`/`B` on `FILTER`.
