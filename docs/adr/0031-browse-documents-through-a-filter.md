# ADR 0031 — Browse the documents through a filter

- **Status:** Accepted; amended by ADR 0032
- **Date:** 2026-10-07

## Context

On the documents screen, `←`/`→` step through every document id, and `[`/`]` walk the fetched search hits in score order (ADR 0010), at most 10,000 of them. Neither answers "show me the documents that match this query, one after another, in index order": to look at their neighbours, compare them by id, or see where the deleted ones are.

A search can't do it. Its hits come in score or sort order, a page at a time, and it is run again for every page. Stepping to the next match in id order needs every match at once.

Constraints:

- Finding every match can take long (a wildcard over a large field), so by rule 7 it is a job (ADR 0005), and a job forgets its outcome once a poll has handed it out.
- Doc-id iterators only go forward, and a query's rewrite can cost more than the iteration (a wildcard enumerates the terms first).
- Lucene's `IndexSearcher` always skips deleted documents.

## Decision

1. **Four methods.** The matches are found once, and the server keeps them as a filter:
   - `startFilter {query, includeDeleted}` checks the query at once, as `startSearch` does (the same `SearchQuery` alternatives and the same errors), and starts a job.
   - `getFilter {jobId}` returns `docsSearched` and, once the job is done, `{filterId, matchCount, query, rewrittenQuery, tookMillis}`. At that moment the server keeps the filter; the job is forgotten as usual.
   - `stepFilter {filterId, fromDoc, forward}` returns the first match from `fromDoc` on, or the last one up to it, as `{docId, index}`, or nothing. `fromDoc` itself counts, so the same call tells whether a document matches (`docId == fromDoc`). `index` is the number of matches before the document, for `n of M`. Any `fromDoc` is taken: below 0 or from maxDoc on, it finds nothing in its direction.
   - `releaseFilter {filterId}` forgets the filter. It is idempotent, like `cancelJob`, and says whether the filter was kept.
2. **The matches are a bit set over maxDoc** (`DocFilter` in core, a `FixedBitSet`): one bit per document, 12.5 MB at 100 million documents. Stepping uses `nextSetBit`/`prevSetBit`; `index` is a popcount of the words below the document.
3. **Found without scores.** The query is built (More-Like-This needs the reader, ADR 0013) and rewritten as for a search, then collected with `COMPLETE_NO_SCORES` into the set, by doc ids of the whole index (ADR 0021). It is cancellable through the same `QueryTimeout` and `ExitableDirectoryReader` as a search. Too many clauses fail the job with `INVALID_QUERY`.
4. **Deleted documents match with `includeDeleted`.** Each directory reader is wrapped in a `FilterDirectoryReader` whose leaves have no live docs, so the `IndexSearcher` has nothing to skip. The wrappers have no cache helpers and are never closed, like the exitable ones around them. *Amended by ADR 0056: the runtimes of 5.5, 7.7 and 9.12 wrap the leaves the same way; 3.6 can't, and refuses a query filter with `includeDeleted`.*
5. **The server keeps one filter.** A newer filter replaces it when `getFilter` collects it. A filter belongs to the open index: opening another one, closing it, or a reopen that finds a new commit forgets it (`CurrentIndex`), since doc ids may change. An unchanged reopen keeps it. *Amended by ADR 0032: after a reopen that finds a new commit, the TUI builds the filter again.*
6. **New error code `FILTER_NOT_FOUND` (-32018)**, for a filter that is unknown, released, replaced, or of an index that was closed or reopened.

### The TUI

7. **`b` on the search screen** turns the current query into a filter over the live documents, **`B`** over all of them, deleted ones included. The regex over stored values (ADR 0027) is no query and can't be a filter. *Amended by ADR 0032: `startFilter` takes a pattern too, and `b`/`B` work in regex mode.* The screen switches to the documents screen, which shows the filter in its title: `filter <query>: finding matches… 40%`, then `3 of 19`, `not a match, 19 in all`, or the error. `Esc` stops the job.
8. **Once built, it starts on the search screen's selected hit**, which matches, else on the first match after it, else on the last match.
9. **On the documents screen:**
   - `←`/`→` step to the previous or next match, and say `no previous match` or `no next match` at the ends;
   - `i` goes to the match at the id or after it, else the last one before it;
   - a document shown some other way (Enter on a hit, `[`/`]`, the terms screen) is located in the filter as it loads: `getDocument` and one `stepFilter`;
   - `b` clears the filter, and stepping goes through every document again; so does a filter that failed, until `b` clears it.

## Alternatives considered

- **Stateless stepping:** `nextMatch {query, fromDoc, forward}` runs the query from `fromDoc` on each time. It keeps no state in the server, but every step pays the rewrite again, a step back has to start over at the segment's beginning since iterators go forward only, and `n of M` needs a full count each time. Rejected.
- **The TUI holds the matching ids.** One round trip and no server state, but millions of ids as JSON, which the TUI then keeps. Rejected; a bit set in the server takes maxDoc / 8 bytes however many match, and nothing crosses the pipe.
- **A filter id per job, several kept.** The TUI browses through one filter at a time; keeping more only holds memory until the index closes. Rejected for one.
- **A `SparseFixedBitSet`.** Smaller for rare matches, but slower to step back through. A `FixedBitSet` of maxDoc bits is small enough. Rejected.

## Consequences

- Four more methods with fixtures and DTOs on both sides, `Filters` in the server, and `DocFilter` and `Searcher.matches` in core.
- `startFilter` reuses `SearchQueries`, which the server's search package now exposes.
- The terms screen doesn't know about the filter yet: marking or hiding postings outside it, the regex scan as a source of a filter, and building the filter again after a reopen stay on the task list. *All three came with ADR 0032.*
