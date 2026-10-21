# ADR 0064 — Runtime interface 15: similarity and sort

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0049 §4 (the runtime interface); ADR 0057 §4 (the runtime's similarity in explain); ADR 0058 §4–5 (the parts of search, and the similarity and sort of a term query)
- **Amended by:** ADR 0076 (§2 and the consequence on 2.x to 7.x: 7.7 and 5.5 have similarity and sort)

## Context

On an index that a runtime reads, `startSearch` and `startExplain` ignored the similarity of the params, and `startSearch` refused any sort other than the best score first with `NOT_SUPPORTED` (`RuntimeResults.bestScoreFirst`, ADR 0057 §4, ADR 0058 §5). The TUI gated the `s` popup (similarity and sort) on `SEARCH`, which only Lucene 10 reports, so it was off in every mode of a runtime: classic, term and KNN.

The tasklist item "Search beyond classic on 9.12" has four parts: similarity and sort, doc-values queries, the standard parser with point fields, and More-Like-This. The user chose to take them one per step, on 9.12 only, in that order. Similarity and sort comes first because it unlocks `s` in every mode a runtime already has.

Lucene 9.12.3 has everything leia-core uses for this on Lucene 10: `BM25Similarity(k1, b)`, `ClassicSimilarity` and `BooleanSimilarity`; `SortedNumericSortField` and `SortedSetSortField` with their selectors; `SortField.getComparator(int, Pruning)`; `TopFieldCollector.create(sort, numHits, threshold)` and `TopFieldCollector.populateScores`. So the Lucene part is a port.

## Decision

1. **A `Scoring` group in `leia-runtime`,** optional as `Highlight` is (ADR 0063). `RuntimeIndex.scoring()` gives:
   - `search(query, Similarity, List<SortKey> sort, offset, limit, fields, progress)`, with an empty sort for the best score first;
   - `explain(query, Similarity, docId, progress)`.

   It has its own JDK types: the sealed `Similarity` (`Bm25(k1, b)`, `Classic`, `Bool`), the sealed `SortKey` (`Score(reverse)`, `Doc(reverse)`, `Field(field, type, reverse, selector, missing)`), the enums `SortType`, `SortSelector` and `SortMissing` (as core's), and `SortValue(Optional<Number> number, Optional<byte[]> term)`. `Search.Hit` gains `List<SortValue> sortValues`; a three-argument constructor leaves it empty, so the other searches stay as they are.
2. **9.12 only.** `Lucene9Index` implements `Scoring`. Its `Search.search` and `Search.explain` call the `Scoring` methods with 9.12's default, `Bm25(1.2, 0.75)`, so their scores don't change. The one search path sets the searcher's similarity and collects with `TopScoreDocCollector` without a sort, and with `TopFieldCollector` and then `populateScores` for the page's hits with one. Every hit is counted either way, which also keeps the index sort of `lucene8` from ending a sorted search early. `Lucene9Sorts` is a copy of core's `SortType.sortField`, `SortSelector` and `Similarities`, with the same messages (ADR 0050 §1). 3.6, 5.5 and 7.7 return an empty group:
   - 7.7 has the same API apart from `Pruning`. It is left for later, as the user asked for one runtime per step.
   - 5.5 has no `BooleanSimilarity`.

   *Amended by ADR 0076:* 7.7 and 5.5 implement `Scoring` too, 5.5 with a port of 6.4's `BooleanSimilarity`; only 3.6 returns an empty group.
3. **A sort never prunes.** As in core (ADR 0012), each sort field's comparator is asked for with `Pruning.NONE`: Lucene would otherwise skip documents through the points or terms of the field, assuming they hold the values in the type sorted by, while that type is the user's guess.
4. **A missing number is reported missing.** Lucene puts the missing value in a numeric sort field's place. As core does (ADR 0025), a numeric sort value is empty when the document has no sorted-numeric value in the field.
5. **The server checks first, with Lucene 10's errors.** `server.runtime.RuntimeScoring`:
   - maps the similarity after `SearchQueries.similarity` (public now) has checked it, so "exactly one" and the BM25 ranges fail with `INVALID_PARAMS` as on Lucene 10;
   - maps the sort, checking that each key has exactly one alternative (`INVALID_PARAMS`), that the field exists (`FIELD_NOT_FOUND`), and that its doc-values type sorts as the key's type with the key's selector, by core `SortType`'s rules and messages (`INVALID_PARAMS`): `NUMERIC` and `SORTED` take `MIN` only, `SORTED_NUMERIC` takes `MIN` or `MAX`, `SORTED_SET` any selector, and `BINARY` and none are refused;
   - maps the sort values to the protocol's, as `StartSearchHandler` does.

   `RuntimeResults.startSearch` and `startExplain` use the group when the index has it, and name the sort and the similarity in the job. Without it, nothing changes: the similarity is ignored and another sort is `NOT_SUPPORTED`. So `x` explains with the similarity the hits were scored with, and its value is the hit's score again.
6. **`SCORING`.** `Feature.SCORING`, after `TERM_SEARCH`, is the similarity and sort of `startSearch` and the similarity of `startExplain`. `RuntimeResults.features` reports it where the runtime scores and searches; Lucene 10 has it with every feature. The TUI's `s` key, its footer entries `p/s/l settings` and `s/l settings`, and the settings line follow `SEARCH || SCORING`. This takes the similarity and sort popup off `SEARCH` (ADR 0058 §4).
7. **Runtime interface 15.** `LuceneRuntime.API_VERSION` is 15, so a runtime jar built for interface 14 is refused (ADR 0044).

## Alternatives considered

- **Pass the similarity and sort to `Search.search` and `Search.explain`.** Every runtime would have to take them, or refuse them through a new exception. Rejected: an optional group says what a runtime has, as the other groups do.
- **Check the sort only in the runtime,** relying on its `IllegalArgumentException`. The errors would come from the job, not at once, and a runtime's messages could drift from Lucene 10's. The runtime still checks, as a guard.
- **Sort on 7.7 in the same step.** Its port is small, but the user chose one part and one runtime per step.

## Consequences

- On `lucene8` and `lucene8to9`, `s` opens the popup in the classic, term and KNN modes; the hit list shows the sort column, and `x` gives the hit's score with Classic or Boolean too.
- A sorted search on 9.12 reads every match's doc value, as on Lucene 10.
- Indexes of 2.x to 7.x keep the runtime's default similarity and the best score first. *Amended by ADR 0076:* only those of 2.x and 3.x do.
- `runtimes/leia-lucene9.jar` grows by a few kilobytes: no new dependency.
- A fix to the sort belongs in core's `SortType`, `Lucene9Sorts` and `RuntimeScoring`.
- `docs/runtimes.md` has a `SCORING` row, and its differences show the similarity and sort per runtime.
