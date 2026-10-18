# ADR 0057 — Runtime interface 9: explain

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0049 §4 (the runtime interface) and §7 (no explain on a runtime)

## Context

Explain (`EXPLAIN`, `x` on the search screen) worked only on Lucene 10 indexes. On an index that a runtime reads (3.6, 5.5, 7.7, 9.12), `x` was greyed out: `RuntimeResults.features` never reported `EXPLAIN`, and `StartExplainHandler` knew only `ReadOnlyIndex.explain`. Every runtime's Lucene has `IndexSearcher.explain`, and the protocol's `Explanation` (match, value as a string, description, details) fits all of them.

## Decision

1. **`Search.explain(query, docId, progress)`** returns `Search.Explanation(boolean match, Number value, String description, List<Explanation> details)`, a tree of JDK types. The query is one of `Search.parse` or `Knn`. `progress` gets the documents searched so far and throws to stop. `value` is a `Number` because 9.12 gives one; 7.7, 5.5 and 3.6 give a `float`, boxed.
2. **The runtimes.** Each facade unwraps its query as `search` does, makes an `IndexSearcher` (without query cache on 5.5, 7.7 and 9.12, closed in `finally` on 3.6) and calls `searcher.explain(query, docId)`, which rewrites the query, so an approximate KNN query runs its graph search there. `ExactKnnQuery` of 9.12 rewrites to a `FunctionScoreQuery`, which explains itself. 3.6's `getDetails()` may be `null`, mapped to an empty list.
3. **Cancellation.** 9.12 asks the progress through the searcher's `QueryTimeout`, as its search does (ADR 0049 §5), which covers the KNN rewrite. 7.7, 5.5 and 3.6 have no hook during the call: the progress is told 0 before and maxDoc after, so a cancelled job stops at either point.
4. **The runtime's similarity.** The similarity of `startExplain` is ignored on a runtime, as `startSearch` ignores it there: the runtime explains with its default, BM25 on 7.7 and 9.12, classic TF-IDF on 5.5 and 3.6, so the explained value is the hit's score. On 7.7 the two may differ in the last bits of the float: its `BM25Similarity` explains as `idf · tfNorm`, in another order than its scorer computes (Lucene 8 aligned them).
5. **Deleted documents** don't match. 5.5, 7.7 and 9.12's `IndexSearcher.explain` answers `noMatch("Document N is deleted")` itself; 3.6 would explain a deleted document as a live one, so its facade checks `isDeleted` first and answers the same.
6. **The server.** `RuntimeResults.features` adds `EXPLAIN` with the `search()` group. `StartExplainHandler` branches on the runtime first, as `StartSearchHandler` does, to `RuntimeResults.startExplain`: `checkDocId` (`DOC_NOT_FOUND`), then `parsedQuery` (the classic/KNN choice and errors of search and filter), and a job "explain <query> for document <id> with Lucene <version>". An `IOException` is `INDEX_NOT_READABLE`. `getExplain` is unchanged.
7. **Runtime interface 9.** `LuceneRuntime.API_VERSION` is 9.

## Alternatives considered

- **Honouring the chosen similarity on a runtime**: the runtimes have no similarity in the interface, and an explanation that doesn't match the search's score (which ignores it) would mislead.
- **Explanation values as `float`**: would lose 9.12's `Number`, which can be a `long` or `double` for some queries.

## Consequences

- `EXPLAIN` is reported on every runtime with a reader; `docs/runtimes.md` has it ✓ in each column, and rows for the similarity, cancellation, deleted documents and 7.7's rounding.
- A runtime jar built for interface 8 is refused (ADR 0044).
- The TUI is unchanged: it already gates `x` on `EXPLAIN`.
