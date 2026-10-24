# ADR 0091 — The Lucene 9 adapter on every 9.x

- **Status:** Accepted
- **Date:** 2026-10-18
- **Amends:** ADR 0090 (§6, and its rejected alternative "Make the Lucene 9 adapter link on Lucene 9.0 to 9.4")

## Context

`--server` loads leia's Lucene 9 adapter, built for 9.12.3, over a server's `lib/` (ADR 0090). On Elasticsearch 8.0 to 8.6 and OpenSearch 2.0 to 2.5, whose Lucene is 9.0 to 9.4, no index opened with the server: the JVM couldn't verify `Lucene9Index`, which names the KNN classes of 9.5 (`AbstractKnnVectorQuery`, as the common type of `KnnFloatVectorQuery` and `KnnByteVectorQuery`). Their shards with Lucene's own formats opened with leia's Lucene 10 instead; those with Elasticsearch's, such as the `ES85BloomFilter` on `_id` of a time series index of 8.6, not at all.

A probe that resolves every reference of the adapter's classes against the `lib/` of Elasticsearch 8.0.1 (Lucene 9.0.0) and 8.6.2 (9.4.2), walking supertypes, gave what they lack:

| API | Missing in | Used by |
|---|---|---|
| `StoredFields`, `TermVectors`, `IndexReader.storedFields()`, `termVectors()` (9.5) | 9.0, 9.4 | documents, stored scan, search hits, More-Like-This, rebuild |
| `KnnFloatVectorQuery`, `KnnByteVectorQuery`, `FloatVectorValues`, `ByteVectorValues` (9.5) | 9.0, 9.4 | KNN search, a document's vector |
| `FieldInfo.getVectorEncoding()` (9.4) | 9.0 | the vector format of a field |
| `SortedSetDocValues.docValueCount()` | 9.0 | a document's sorted set doc values |
| `IndexSearcher.setTimeout(QueryTimeout)` | 9.0 | asking the progress during a search |
| `PointValues.PointTree` | 9.0 | points, rebuild |
| `UnifiedHighlighter.Builder` | 9.0 | highlighting |
| the `FieldHighlighter` constructor with the passages' order | 9.0, 9.4 | highlighting |
| `SegmentInfo.getHasBlocks()` (9.9) | 9.0, 9.4 | commits (also Elasticsearch 8.7 to 8.11) |
| `Pruning` (9.10) | 9.0, 9.4 | sort |
| `HnswGraphProvider` (9.11) | 9.0, 9.4 | the HNSW graph |
| `newSlowSetQuery` of the doc values fields | 9.0, 9.4 | doc values queries of several values |

## Decision

1. **`Lucene9Index`, `Lucene9Runtime`, `Lucene9Commits` and `Lucene9Check` load and verify on Lucene 9.0.** No API newer than 9.0 is named where the verifier would have to load its class (an assignment to another type, such as a common supertype or `Enum`); a method of 9.x newer than 9.0 is called only behind a flag, since the JVM resolves a method when it is first called.
2. **`Lucene9Api` holds the flags**, found once by reflection on the runtime's loader, by the presence of a class or method rather than by version, so that they hold for any minor: `STORED_FIELDS`, `KNN`, `VECTOR_ENCODING`, `DOC_VALUE_COUNT`, `SEARCH_TIMEOUT`, `POINT_TREE`, `HIGHLIGHTER`, `HAS_BLOCKS`. It names no Lucene type. On 9.12 every flag is true (`Lucene9ApiTest`).
3. **What needs a newer API moves into a class of its own**, loaded only behind its flag:
   - `Lucene9Stored`: stored fields and term vectors through `storedFields()` / `termVectors()`, one instance per scan as before, or else the reader's `document(int, StoredFieldVisitor)` and `getTermVectors(int)` of 9.0, which 9.12 still has; for `Lucene9Index`, `Lucene9LikeTerms` and `Lucene9Rebuild`;
   - `Lucene9Knn implements Knn`: the KNN queries, the sample and a document's vector, out of `Lucene9Index`, which no longer implements `Knn`;
   - `Lucene9VectorEncoding`: the encoding by name, so that no switch map names 9.4's enum. Before 9.4 every vector is of floats.
4. **A feature the server's Lucene can't serve is left out**, as on an index without its data, rather than failing at the call: `knn()` and `knnGraph()` are empty without `KNN`, `points()` without `POINT_TREE`, `highlight()` without `HIGHLIGHTER`, and a document has no vector without `KNN`. So `openIndex` doesn't report `KNN_VECTORS`, `KNN_GRAPH`, `POINTS` or `HIGHLIGHT`, and the screens don't offer them. KNN is not emulated through 9.0's `VectorValues`.
5. **The rest falls back within the call:** the sorted set doc values are read up to `NO_MORE_ORDS` without `docValueCount()`; without `setTimeout`, a search asks the progress only at the start of each segment, so a cancel takes effect there; the rebuild goes through the points with `PointValues.intersect` without the `PointTree`; and `hasBlocks` is false without `getHasBlocks()`, as for a segment before 9.9.
6. **What's left stays `NOT_SUPPORTED` at the call** (ADR 0090 §6): a sort below 9.10 (`Pruning`), the HNSW graph from 9.5 below 9.11, and doc values queries of several values where `newSlowSetQuery` is missing (9.4 and older).

## Alternatives considered

- **Check by Lucene's version.** A server can ship any minor, and a flag by presence says exactly whether the code can run. Rejected.
- **Report a feature that fails as `NOT_SUPPORTED`, as today.** The screens would offer KNN, points and highlighting on every index of such a server, only to fail. Rejected for leaving them out.
- **Emulate KNN through 9.0's `VectorValues`.** An exact search of floats could run on it, but the formats of 9.0 to 9.4 differ (no byte vectors before 9.4) and the adapter would need a second KNN path for a few versions. Rejected; decided with the user.
- **An adapter of its own for 9.0 to 9.4.** A sixth runtime folder to build and download for a few server versions. Rejected.

## Consequences

- leia-lucene9: `Lucene9Api`, `Lucene9Stored`, `Lucene9Knn`, `Lucene9VectorEncoding`; `Lucene9Index`'s `Lucene9Query`, `vectorField`, `leaf`, `checkDoc`, `reader()` and `fieldInfos()` are package-visible.
- leia-test-servers: `fakeServer` takes a name besides the Lucene version, and a third server, `fake-server-90`, an Elasticsearch on Lucene 9.0.0 (`serverLucene90`), writes vectors, two sorted set values per document, points and term vectors. `Server90BackendTest` reads it in `serverTest`.
- Elasticsearch 8.0.1 and 8.6.2 read all their shards with `--server`, also the time series ones with `ES85BloomFilter` on `_id` (`docs/runtimes.md` § Servers).
