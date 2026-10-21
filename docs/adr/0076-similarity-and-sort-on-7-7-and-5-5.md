# ADR 0076 — Similarity and sort on 7.7 and 5.5

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0064 §2 (9.12 only) and its consequence "Indexes of 2.x to 7.x keep the runtime's default similarity" (only 2.x and 3.x do)

## Context

ADR 0064 added the optional `Scoring` group, the similarity and sort of `startSearch` and the similarity of `startExplain`, and implemented it on 9.12 only. On indexes of Lucene 4.x to 7.x, the TUI's `s` popup was off, a search sorted by the best score only, and `x` explained with the runtime's default similarity.

`javap` on 7.7.3 and 5.5.5 shows what the 9.12 code uses, with these differences:

| 9.12 uses | 7.7.3 | 5.5.5 |
|---|---|---|
| `BM25Similarity(k1, b)`, `ClassicSimilarity`, `BooleanSimilarity` | ✓ | no `BooleanSimilarity` (it came in 6.4) |
| default similarity: BM25 | BM25 | `DefaultSimilarity`, TF-IDF |
| `SortField.getComparator(int, Pruning)` | `(int numHits, int sortPos)`: nothing skips documents through points before 8.7 | as 7.7 |
| `TopFieldCollector.create(sort, numHits, threshold)` and `populateScores` | `create(sort, numHits, fillFields, trackDocScores, trackMaxScore, trackTotalHits)` | `create(sort, numHits, fillFields, trackDocScores, trackMaxScore)`, always counting every hit |
| `SortedNumericSortField`, `SortedSetSortField` with `MIDDLE_*`, `setMissingValue` | ✓ | ✓ |
| has a document a number: `advanceExact` | ✓ | `setDocument` and `count() > 0`, numeric doc values given with their documents |
| `TopDocs.totalHits` | `long` | `int` |

## Decision

1. **7.7 and 5.5 port 9.12** (ADR 0050 §1). `Lucene7Index` and `Lucene5Index` implement `Scoring`, with `Lucene7Sorts` and `Lucene5Sorts` as copies of `Lucene9Sorts` and its messages. Their `Search.search` and `Search.explain` call the `Scoring` methods with the runtime's own default: `Bm25(1.2, 0.75)` on 7.7, `Classic` on 5.5, so their scores don't change. The runtime interface, the protocol, the server and the TUI stay as they are: `RuntimeResults` uses the group wherever a runtime has it.
2. **No `Pruning`.** The sort fields are plain `SortField`, `SortedNumericSortField` and `SortedSetSortField`. Lucene skips documents through the points of the field sorted by only since 8.7, so a sort reads every match's doc value anyway (ADR 0012, ADR 0064 §3).
3. **`trackDocScores` instead of `populateScores`.** A field sort collects with `fillFields` and `trackDocScores` true, so every collected hit carries its score, without a second pass.
4. **Every hit counted.** On 7.7, `trackTotalHits` is true, which also keeps the index sort of `lucene6` from ending a sorted search early; 5.5 has no index sort and always counts. `exactTotalHits` stays ignored, as before.
5. **A port of `BooleanSimilarity` on 5.5.** `Lucene5BooleanSimilarity` is 6.4's class on 5.5's `Similarity` API, which has the same shape: the weight keeps the boost of `normalize(queryNorm, boost)`, a matching term scores it, the slop and payload factors are 1, and the explanation is the "query boost". The coordination factor and the query norm are `Similarity`'s 1, so `beta^2 gamma` scores 3.0, as on 9.12. Its `computeNorm` is BM25's and never called, as leia only reads.
6. **The TUI's default stays BM25.** It asks for `Bm25(1.2, 0.75)` on every index, so on 4.x and 5.x indexes the default scores change from 5.5's TF-IDF to BM25 unless the user picks Classic. One default across runtimes was the user's choice. `Search.search`, without the group, keeps TF-IDF.

## Alternatives considered

- **Default to Classic on 5.5,** keeping 5.5's own scores. Rejected: the user chose one default, BM25, on every runtime.
- **Leave Boolean out on 5.5,** refusing it with `NOT_SUPPORTED`. The port is small, and the popup would offer a similarity that fails.

## Consequences

- On indexes of Lucene 4.x to 7.x, `s` opens the popup; the hit list shows the sort column, and `x` gives the hit's score with the similarity chosen.
- On 5.5, as on 7.7, a BM25 explanation may differ from the score in the last bits: its explanation computes in another order than its scorer.
- Only 3.6 returns an empty `Scoring` group; `RuntimeScorings.doesNotSort` runs on it.
- A fix to the sort belongs in core's `SortType`, `Lucene9Sorts`, `Lucene7Sorts`, `Lucene5Sorts` and `RuntimeScoring`.
