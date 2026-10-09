# ADR 0012 — Similarity and sort

- **Status:** Accepted, amended by ADR 0025 (sort keys by score and document id, selectors, and missing values first or last)
- **Date:** 2026-10-06

## Context

ADR 0010 scores every search with Lucene's default similarity, BM25 with `k1 = 1.2` and `b = 0.75`, and orders the hits by score. Luke's Search tab can change both (`luke-features.md` §4.3):

- **Similarity:** BM25 with its `k1` and `b`, or classic TF-IDF, both with "discount overlaps".
- **Sort:** a primary and a secondary sort on fields with doc values. The sort type is guessed from the doc values type: `INT`/`LONG`/`FLOAT`/`DOUBLE` for numeric doc values, `STRING`/`STRING_VAL` for sorted ones, and only the default for sorted sets.

Several things need deciding:

- **Explain.** An explanation describes a score. If the search scores with one similarity and the explanation with another, the explanation explains a number that the hit list doesn't show.
- **Discount overlaps.** Luke offers it for both similarities. Lucene reads it only in `Similarity.computeNorm`, which runs while a document is indexed (checked with `javap -c` on Lucene 10.5.2: no scorer reads it). On an index that is already written, it changes nothing.
- **Boolean similarity.** `BooleanSimilarity` scores a matching term with its boost alone. Luke doesn't offer it, but it shows the match structure of a query without term statistics, which suits an inspector.
- **Which sort field class.** Lucene sorts each doc values type with its own `SortField` class: `SortField` for `NUMERIC` and `SORTED`, `SortedNumericSortField` for `SORTED_NUMERIC`, and `SortedSetSortField` for `SORTED_SET`. `BINARY` doc values can't be sorted on. Luke maps `BINARY` to a doc id sort.
- **What a sorted hit shows.** A hit sorted by a field has `FieldDoc.fields` with the value it was sorted by: an `Integer`, `Long`, `Float`, `Double` or `BytesRef`, or `null` for a document without a value in a `STRING` sort. Its score is `NaN`, unless scores are asked for.
- **Number types.** Numeric doc values are always 64-bit longs. The type says how to read them: `INT` casts to int, `FLOAT` and `DOUBLE` read the bits as a float or a double. In a `SORTED_NUMERIC` field, `FLOAT` and `DOUBLE` expect the sortable bits of `NumericUtils`, which is what Lucene's `FloatField` and `DoubleField` write. In a `NUMERIC` field they expect the raw bits, which is what `FloatDocValuesField` and `DoubleDocValuesField` write. As for points (ADR 0011), the index doesn't record which type the values are.
- **Pruning.** For the first sort field, Lucene's comparators skip documents that can't make the top hits. They do that through the points, the terms or the doc values skipper of the field with the same name, and they assume these hold the same values as the doc values, in the type sorted by. A numeric comparator throws an `IllegalArgumentException` if the field's points are wider or narrower than the type, or have more than one dimension. On the demo index, sorting `price` (8-byte `DoublePoint`) as `INT` failed as `INTERNAL_ERROR`, and so would `location` (2-dimensional `LatLonPoint`) in any numeric type. If only the encoding differs, for example `price` with `DoublePoint` points and raw double bits in its doc values, read as `LONG`, nothing throws, and the wrong documents may be skipped.
- **Missing values.** A document without a value sorts as 0 in a numeric sort, and first in an ascending `STRING` sort. Those are Lucene's defaults.

## Decision

1. **`similarity` is a required param of `startSearch` and `startExplain`.** It is one of three alternatives, and exactly one must be present (`INVALID_PARAMS` otherwise):
   - `bm25 {k1, b}`: `BM25Similarity`. `k1` is finite and 0 or more, and `b` is from 0 to 1. Lucene's own checks reject anything else, and that is `INVALID_PARAMS`.
   - `classic {}`: `ClassicSimilarity`, TF-IDF. It has no parameters.
   - `boolean {}`: `BooleanSimilarity`. It has no parameters.

   `k1` and `b` are `float` in Java and `f64` in Rust, as scores are (ADR 0010). The TUI keeps them in hundredths and sends the shortest decimal, which Java reads as the nearest `float`. An `f32` would read the same, but the fixture tests compare through `serde_json::Value`, which widens an `f32` to `1.2000000476837158`. The TUI starts from Lucene's defaults: BM25, `1.2`, `0.75`. "Discount overlaps" is left out: it only matters while indexing (see Context), and an option that does nothing would only mislead (ADR 0011). The TUI sends the same similarity to `startExplain` as to `startSearch`. Requiring the param makes that visible in every request. The norms don't depend on it: in Lucene 10, every similarity in Lucene encodes the field length in the norm the same way, so a norm written for BM25 reads the same for classic TF-IDF.
2. **`sort` is a required param of `startSearch`: a list of `{field, type, reverse}`.** The list is empty to sort by score, as before. `type` is `INT`, `LONG`, `FLOAT`, `DOUBLE` or `STRING`. The server picks the `SortField` class from the field's doc values type, with Lucene's default selectors (`MIN`) and missing values:

   | Doc values | Types | Sort field |
   |---|---|---|
   | `NUMERIC` | `INT`, `LONG`, `FLOAT`, `DOUBLE` | `SortField` |
   | `SORTED_NUMERIC` | `INT`, `LONG`, `FLOAT`, `DOUBLE` | `SortedNumericSortField` |
   | `SORTED` | `STRING` | `SortField` (`STRING`, by ordinal) |
   | `SORTED_SET` | `STRING` | `SortedSetSortField` |

   An unknown field is `FIELD_NOT_FOUND`. A field without doc values, with `BINARY` doc values or with a type that doesn't fit is `INVALID_PARAMS`. Ties are broken by document id, as Lucene does, so offset paging (ADR 0010) stays stable. The server takes any number of sort keys, and the TUI offers two, as Luke does.
3. **The sort never prunes.** Each sort field overrides `getComparator(numHits, pruning)` to pass `Pruning.NONE`, so the sort reads every match's doc value. The type is the user's guess (decision 7), and pruning is only right when the guess matches what was indexed. `SortField.setOptimizeSortWithIndexedData(false)` does the same, but it is deprecated in Lucene 10.5. This costs as much as an exact count of the matches, and the search stays cancellable.
4. **Sorted hits keep their scores.** After a sorted search, `TopFieldCollector.populateScores` computes the scores of the page's hits, so every hit has a score, and explain still matches it. That costs one more pass over at most the page's documents, at most 1000 of them.
5. **Each hit carries its sort values.** `Hit` gains `sortValues`: one `SortValue {number?, term?}` per sort key, in sort-key order. The list is empty without a sort.
   - A number is `number`, as `Number.toString()`, as for explanation values (ADR 0010).
   - A `STRING` sort value is `term`, a byte string as text or hex (`BytesValue`, ADR 0007).
   - Neither is present for a document without a value in a `STRING` sort.
6. **Counting stays as it is.** `TopFieldCollectorManager` takes the same total hits threshold as the score collector. Luke always counts exactly when it sorts. leia doesn't need to.
7. **The TUI guesses the sort type.** It guesses `LONG` for numeric doc values and `STRING` for sorted ones, and cycles a numeric field through `INT`, `LONG`, `FLOAT` and `DOUBLE`. `LONG` orders every encoding that Lucene's own fields write correctly, sortable float and double bits included. Only raw double bits (`DoubleDocValuesField`) need `DOUBLE`, and raw float bits need `FLOAT`, to order negative values right. The type also decides how the sort value reads. Since nothing is pruned (decision 3), a wrong guess can order the hits wrongly, but it never fails or drops a hit.
8. **The stored fields per hit stay as they are on the wire.** `startSearch` already takes `fields` (ADR 0010). The TUI gets a popup that chooses them.

## Alternatives considered

- **Similarity as one record with a `kind` enum**, as Luke's `SimilarityConfig` does. `k1` and `b` would then be fields that classic and boolean ignore. Rejected, for the reason that ADR 0010 gives for queries.
- **An optional similarity, absent meaning BM25 with Lucene's defaults.** That keeps the old requests valid. But the TUI always sends one, and the TUI and the server ship together (ADR 0004). Rejected, as for the parser options (ADR 0011).
- **All of Lucene's similarities** (language models, DFR, IB, axiomatic). Each needs its own set of parameters and its own popup rows, and they are rare in real indexes. Rejected for now. A further alternative can be added without breaking these.
- **`STRING_VAL`**, as Luke offers it. It compares the values instead of the ordinals, and orders them the same way, only more slowly. Rejected.
- **A doc id sort for `BINARY` doc values**, as in Luke. It is the order without any sort. Rejected: `INVALID_PARAMS` says why the field can't sort.
- **`SCORE` and `DOC` as sort keys.** A score after a field sort would change the tie-break. Luke offers neither, and no one has asked. Deferred. They can come as further types without breaking the field keys.
- **Choosing the selector (`MIN`, `MAX`, middle) and the missing value.** These are useful for multi-valued fields, but they are more options than the first slice needs. Deferred, with Lucene's defaults until then.
- **Exact counts whenever there is a sort**, as in Luke. Lucene 10 counts to a threshold with a sort too. Rejected.

## Consequences

- `startSearch` takes `similarity` and `sort`, and `startExplain` takes `similarity`. Every request fixture changes. `Hit` gains `sortValues`.
- The TUI's search screen gets a popup for similarity and sort (`s`), and another for the stored fields per hit (`l`). Both search again when applied. With a sort, the hit list shows the sort values.
- A document opened from the hit list steps through the hits with `[`/`]` on the documents screen, as one opened from a term's postings does. That is TUI only.
- Sorting reads doc values through the `ExitableDirectoryReader` (ADR 0010), so a cancelled sorted search stops too.
