# ADR 0040 — KNN vectors: a document's vector and search by vector

- **Status:** Accepted
- **Date:** 2026-10-11
- **Amends:** ADR 0006 §3 (`getDocument` gains each field's vector), ADR 0010 (`SearchQuery` gains a sixth alternative), ADR 0030 §2 (the export format becomes version 2)

## Context

Luke has no KNN vector inspection or search (`docs/luke-features.md` §6). leia only reported a field's vector dimension (`IndexField.vectorDimension`, the overview's "Vector" column). Three things were missing:

- **How a field compares its vectors.** Lucene records each field's encoding (`FLOAT32` or `BYTE`) and similarity function (`EUCLIDEAN`, `DOT_PRODUCT`, `COSINE`, `MAXIMUM_INNER_PRODUCT`) in its `FieldInfo`. Neither was shown.
- **A document's vector.** The documents screen showed stored values, doc values, norms and term vectors, but not the vector.
- **Search by vector.**
  - Lucene's `KnnFloatVectorQuery` and `KnnByteVectorQuery` search the field's HNSW graph for the `k` nearest documents, optionally only among the documents of a filter query.
  - The search runs when the query is rewritten. It is approximate, and quantized formats score with quantized vectors.
  - To check a graph's recall, or how much quantization changes the ranking, you need an exact ranking to compare against. Lucene has no public exact KNN query: `AbstractKnnVectorQuery.exactSearch` is protected, and it is used only behind a filter.

A vector is bounded by its dimension: at most a few thousand numbers, unlike a term vector.

## Decision

1. **`IndexField` gains `vectorEncoding?` and `vectorSimilarity?`** (protocol enums `VectorEncoding`, `VectorSimilarity`). Both are present exactly when `vectorDimension` is not 0. Core's `FieldStats` carries Lucene's own types, and the server leaves them out for a field without vectors.

2. **`DocumentField` gains `vector?`: a `KnnVector {encoding, floats, bytes}`.** Only the list for the encoding is filled, as in `DocValues`.
   - Core reads it from the document's segment (ADR 0006 §2) through `get{Float,Byte}VectorValues`, `iterator().advance(doc)` and `vectorValue(index())`. It copies the array, which the reader reuses.
   - Deleted documents are read too.
   - It is sent whole, with the rest of the document.

3. **`SearchQuery` gains `knn`: `KnnQuery {field, docId?, vector?, k?, exact, filter?}`.**
   - **The target:** exactly one of `docId` (the document's vector in the field, read at once; deleted documents allowed, as for More-Like-This) and `vector`.
   - **Approximate** (`exact: false`) builds Lucene's `KnnFloatVectorQuery` or `KnnByteVectorQuery` with `k`, which is required, from 1 to `MAX_K` (10,000, the search window).
   - **Exact** (`exact: true`) refuses `k`. Leia's own `ExactKnnQuery` rewrites to a `FunctionScoreQuery` over `FieldExistsQuery(field)` (and the filter, as `FILTER` clauses). It is scored by a `DoubleValuesSource` that calls `DoubleValuesSource.similarityToQueryVector`, the same similarity the KNN queries score with.
     - Every document with a vector is a hit, best first, and the search's top N pages through them. The first `k` exact hits are the true top `k` to compare an approximate search with.
   - **`filter`** is a `ClassicQuery`, parsed as the classic alternative is. The approximate queries take it as Lucene's pre-filter, which scores every candidate exactly when there are few.
   - **Errors, when the search starts:**
     - `FIELD_NOT_FOUND` for an unknown field.
     - `INVALID_PARAMS` for:
       - a field without vectors;
       - not exactly one target;
       - a missing, out-of-range or needless `k`;
       - a vector of another encoding or dimension, a float that is NaN or infinite, or a vector of zeros on a cosine field;
       - a source document without a vector in the field.
     - `DOC_NOT_FOUND` for a `docId` outside 0 to maxDoc - 1.
     - `INVALID_QUERY` for a filter that doesn't parse.
     - There are no new error codes and no new methods.

4. **Cancellation comes with the search job** (ADR 0010 §8).
   - The searcher's `setTimeout` makes Lucene wrap the KNN collector in a `TimeLimitingKnnCollectorManager`, and `ExitableDirectoryReader` wraps both `searchNearestVectors` and the vector values, whose iterator and scorer the exact query reads.
   - A graph search stopped early returns partial hits. The job's cancellation check after the search turns them into a cancellation, so they never pass for a result.

5. **The parsed query is Lucene's `toString`.** That is `KnnFloatVectorQuery:embedding[1.86,...][10]`, rewritten to `DocAndScoreQuery[42,...][1.0,...],1.0`, or leia's `ExactKnnQuery:embedding[1.86,...][category:kitchen]`, which rewrites to the function score query.

6. **The export (ADR 0030) gains `vector` per field and becomes version 2.**
   - ADR 0030 bumps the version for any change to the format, and its reader refuses keys it doesn't know. So a version 1 reader would fail on a file with vectors.
   - A version 1 file still reads, without vectors.

### The TUI

7. **The overview's Vector column** says the dimension, the encoding and the similarity: `8d f32 cos`, `16d byte euc` (`euc`, `dot`, `cos`, `mip`).

8. **The documents screen:**
   - **Value column:** shows the vector as `[1.86, -0.93, …] 8d` when the field has no stored values or doc values.
   - **Flag `K`:** now says whether this document has a vector, as `D` does for doc values.
   - **Details panel:** lists the components four to a line, each line after the index of its first component, under `Vector 8d float, length 2.63876`. The length shows whether a dot-product field's vectors are unit length.
   - **`v`:** the viewer gets the vector as one value, a component to a line, with the big-endian bytes of each in the hex view.
   - **`N`:** searches for the document's nearest neighbours in that field on the search screen.

9. **The search screen's KNN mode:** `m` cycles classic → standard → term → doc values → **KNN** → like → like text → regex.
   - **The line** holds a document id (`42`) or a vector in brackets (`[0.5, -1, 2.25]`, separated by commas or spaces; whole numbers from -128 to 127 for a byte field). `KnnSyntax` reads it, and a line it can't read shows why in place of the hits.
   - **A second line** holds the filter (`where …`), in classic syntax with the classic mode's default field, operator (`a`) and parser options (`p`).
   - **Keys:**
     - `f`/`F` step through the vector fields;
     - `k` types k (digits only, clamped to 1–10,000; 10 at first);
     - `e` switches between approximate and exact;
     - `w` edits the filter.
   - **Title:** `KNN · embedding (8d float, cosine) · k 10 · approximate`, or `· exact`.
   - Sort, stored fields, count, explain, `b`/`B` and Enter work as in the other modes. A reopen keeps the field, k, exactness and filter.

## Alternatives considered

- **The vector on demand, as `getTermVector`.** It costs one more call per field for something bounded by the dimension, which a term vector isn't. Rejected.
- **Exact search through Lucene's KNN queries,** by overriding `approximateSearch` to report an incomplete result, so that Lucene falls back to `exactSearch`. Lucene only falls back with a filter, and the trick depends on the private control flow of `AbstractKnnVectorQuery.getLeafResults`. Rejected for a query of leia's own over public API.
- **Exact search limited to `k` hits.** The function score query ranks every document with a vector. Cutting it at `k` would need a collector or a total of leia's own. The page shows the first `k` anyway, and the total says how many documents have a vector. Rejected.
- **The filter as a whole `SearchQuery`.** That would make the record recursive (a KNN filter could hold a KNN query) and need a second mode switch in the TUI. Classic syntax covers terms, ranges and booleans. Rejected.
- **The built document filter (ADR 0031) as the pre-filter.** You would have to build a filter on another screen first, and it can hold deleted documents. Rejected.
- **The export staying at version 1** with an optional key. ADR 0030's reader would refuse such a file. Rejected.

## Consequences

- `IndexField`, `DocumentField` and `SearchQuery` gain fields. New records: `KnnVector`, `KnnQuery`, `VectorEncoding`, `VectorSimilarity`.
- Core has `KnnVector`, `KnnQueries` and `ExactKnnQuery`. The last is leia's own Lucene `Query`, so a Lucene upgrade that changes `FunctionScoreQuery` or `DoubleValuesSource.similarityToQueryVector` needs a look at it.
- The Lucene 9 test index now has a float vector field, `vector` ([N, 0, 1]), written only when `LegacyIndexWriter` runs on Lucene 9. So the backward codecs' vectors are read and searched in the tests (hard rule 5).
- The search screen's `m` cycle is one step longer, and its query panel is a line taller in KNN mode.
- Later (`docs/tasklist.md`): HNSW graph inspection (a node's levels and neighbours), and a recall check of approximate against exact.
