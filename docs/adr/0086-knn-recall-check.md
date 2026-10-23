# ADR 0086 — KNN recall check

- **Status:** Accepted
- **Date:** 2026-10-17
- **Amends:** ADR 0040 (a recall check, left out there), ADR 0085 (the second half of its backlog item)

## Context

An approximate KNN search walks a segment's HNSW graph (ADR 0085) and may miss some of the true nearest documents; how many depends on M, the beam width, quantization, a filter and the data. leia could search approximately and exactly (ADR 0040, ADR 0049), but a user had to compare the two hit lists by eye. The item "a recall check of approximate against exact" of `docs/tasklist.md` asks for the comparison: per target, and over a sample of the field's documents.

## Decision

1. **A runtime method `Knn.sample(field, n)`**, runtime interface 22: up to `n` live documents with a vector, spread evenly by vector ordinal over the segments, so each segment gives its share by its number of vectors. Position `i` of `n` is ordinal `⌊(i + ½)·T/n⌋` of all `T` vectors; one that falls on a deleted document, or on one taken already, takes the next live one of its segment. In document order; every live document with a vector when there are no more than `n`. leia-core's `KnnGraphs.sample` (through `ReadOnlyIndex.knnSample`) and `Lucene9KnnGraph.sample` share the algorithm and the ordinal-to-document mapping of ADR 0085. Everything else reuses `Knn.approximate`, `Knn.exact` and `Search.search`.

2. **A job, `startKnnRecall {query, sample?}` / `getKnnRecall`**, cancelled through `cancelJob` (rule 7): a sample runs two searches per target, and the exact one scores every vector. `getKnnRecall` reports `targetsChecked`. The server's `RuntimeKnnRecall` runs it; the handlers are in `…server.knn`.
   - **Without `sample`:** the query's target is checked: a document or a vector, with `KnnChecks` as `startSearch` checks it; `exact` must be false and `k` is required.
   - **With `sample`** (1 to `MAX_SAMPLE`, 1000): the query gives the field, `k` (up to `MAX_K` − 1) and the filter, but no target. Each sampled document's vector is a target, and the document itself is left out of both rankings: each search asks for `k + 1` and the first `k` others count.
   - The filter applies to both searches; the targets need not match it.

3. **Per target**, the approximate top `k` and the exact top `k`, both through `Search.search` as `startSearch` runs them, without stored fields. Recall is `|A ∩ E| / |E|`, 1 if `E` is empty. For the documents in both, `|approximate − exact score|` is the score drift: 0 for raw float vectors, what quantization changes otherwise.

4. **`KnnRecallResult {field, k, targets, meanRecall, minRecall, perfectTargets, meanScoreDrift?, maxScoreDrift?, worst}`**, in `protocol.knn`. `worst` holds at most `MAX_WORST` (10) `KnnRecallTarget {docId?, recall, missed}`, lowest recall first, then by document; a single target is its own entry. Each `KnnRecallMiss {rank, docId, score}` is an exact nearest document the approximate search missed, with its exact rank from 1 and score. The drifts are absent if no document was found by both. Errors: `NO_INDEX_OPEN`, `NO_READER`, `NOT_SUPPORTED`, `FIELD_NOT_FOUND`, `DOC_NOT_FOUND`, `INVALID_QUERY` for a filter that doesn't parse, `INVALID_PARAMS` otherwise; `INDEX_NOT_READABLE` from the job. No new codes, and no new feature: it needs `KNN_VECTORS` and the classic search, as a KNN search does.

5. **The search screen's KNN mode, `r`** (`KnnRecall` in `…tui.search`, as `StoredScan` is for the regex scan):
   - With a target on the line, it checks that target with the mode's `k` and filter, of the approximate search even while `e` is on. The hits panel lists the exact nearest documents approximate missed, `Rank Doc Exact score`; Enter opens one on the documents screen.
   - With a blank line, it samples `Search.RECALL_SAMPLE` (100) documents. The hits panel lists the worst targets, `Doc Recall Missed` (`#9 doc 363`), and its title the mean; Enter searches the nearest to the selected target.
   - The details panel, titled `Recall`, gives the mean and lowest recall, the perfect targets, the score drift and what was compared. While it runs, the title says `checking… 37 of 100 targets`; Esc stops it, as the other jobs. The next search, a mode switch or a jump into the screen replaces it. The footer says `r recall`, and the mode's help says what it does.

6. **On the demo index**, 100 sampled `embedding` targets at `k` 10 have a recall of 1.0: graphs of M 16 over at most 200 vectors find every exact neighbour. `fingerprint`, with an M of 4 over random bytes, has 0.997: three targets miss one of their ten (`DemoIndexTest`). On `lucene8to9`, the three 9.x vectors through 9.12 have 1.0.

## Alternatives considered

- **A check in the TUI, from two searches.** The TUI would run 2·n searches and poll each, and a sample of 100 would take 200 round trips with their own progress and cancel. One job keeps the progress, the cancel and the arithmetic in one place. Rejected.
- **Random sampling.** A fixed spread gives the same targets every run, so a test can pin the numbers and a user can compare two indexes; a random one doesn't. Rejected.
- **Keeping the sampled document in its rankings.** It is its own nearest in both, with a score of 1, so every target would find at least one of its `k` and the recall would look better than it is. Rejected.
- **Recall over the whole field.** Every document a target, two searches each: on a large index that is millions of searches. A sample of up to 1000 bounds it. Rejected.
