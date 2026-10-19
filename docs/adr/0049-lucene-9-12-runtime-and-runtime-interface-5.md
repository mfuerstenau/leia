# ADR 0049 — The Lucene 9.12 runtime and runtime interface 5

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0044 §4–5 (the features and the search of a runtime); ADR 0048 §2 (the runtime interface); ADR 0040 (KNN search on an index that a runtime reads); ADR 0047 §4 (unmapping in a runtime of Lucene 9)

## Context

Lucene 10 reads only indexes that 9 or 10 created. leia routes an index by the major that created it (`SegmentsHeader`), so an index created by 8.x gave `MISSING_RUNTIME` naming `leia-lucene9.jar`; `Runtimes.jarFor(8)` was in place. That includes an index that 8 created and 9 later wrote to, which is how an index is after an upgrade, such as Elasticsearch 7 to 8. The 9.x segments of such an index can hold KNN vectors.

This is the last runtime of the "Lucene 2 to 10" lane (ADR 0044). Runtime interface 4 (ADR 0048) covers what 8.x adds to 7.x (soft deletes, index sorts, minimum versions, the created major). It can't carry KNN vectors.

Two things about Lucene 9.12 decide how it is built:

- **Its `lucene-core` is a multi-release jar.** It has `MemorySegmentIndexInputProvider` and its inputs in `META-INF/versions/{19,20,21}`. From Java 19 on, `MMapDirectory` requires that class, and otherwise throws `LinkageError("…missing in Lucene JAR file")`, unless `MMapDirectory.enableMemorySegments=false` is set. The classes of `versions/21` say "Java 21 or later", and they run on 25.
- **`MMapDirectory.setUseUnmap` throws** `UnsupportedOperationException` for any value but the global one ("use the global system property …enableUnmapHack"). The memory segment inputs unmap by closing their `Arena`, without `sun.misc.Unsafe`.

## Decision

1. **A runtime `leia-lucene9`** with lucene-core, backward-codecs (the codecs of 8.x), queryparser (which brings queries and sandbox) and analysis-common 9.12.3, relocated and built by `leia.lucene-runtime-conventions` as `leia-lucene7` is (ADR 0048 §1). `indexMajors()` is `{8}`. It is `runtimes/leia-lucene9.jar` beside `leia.jar`. It starts as a copy of `leia-lucene7`, adapted to 9.12.
2. **The multi-release classes are relocated.** Shadow 9.6 relocates the classes of `META-INF/versions/*`, both their paths and their bytecode, and keeps them there; the spike checked it, so the fallback (`enableMemorySegments=false`) isn't needed. The convention sets `Multi-Release: true` on every runtime's shadow jar, which does nothing for 3.6, 5.5 and 7.7. `relocationCheck` fails a jar that has classes in `META-INF/versions/` without that attribute, so that a missing one fails the build, not `MMapDirectory` at runtime. `ExtensionJars`' `URLClassLoader` reads multi-release jars.
   - The system properties of the relocated `MMapDirectory` are relocated too, such as `io.github.mfuerstenau.leia.lucene9.shaded.lucene.store.MMapDirectory.enableMemorySegments`, so they don't reach Lucene 10's.
   - The memory segment provider calls `madvise` through `Linker`. `leia.jar`'s `Enable-Native-Access: ALL-UNNAMED` covers the runtime loader's unnamed module: the manual run, through the scripts and through `java -jar`, printed nothing to stderr.
3. **No unmap switch** (amends ADR 0047 §4). `Directories.open` opens `FS`, `MMAP` and `NIO` with `NoLockFactory` and nothing else. ADR 0047 §4 expected a runtime of Lucene 9 not to need `Unsafe`: with memory segments, it doesn't, so the scripts' `-Dsun.misc.unsafe.memory.access=allow` doesn't matter to it.
4. **Runtime interface 5.** `LuceneRuntime.API_VERSION` is 5.
   - `RuntimeIndex` gains `VectorEncoding {BYTE, FLOAT32}`, `VectorSimilarity {EUCLIDEAN, DOT_PRODUCT, COSINE, MAXIMUM_INNER_PRODUCT}`, the record `VectorFormat(dimension, encoding, similarity)` and a sealed `Vector` with `Floats(float[])` and `Bytes(byte[])`.
   - `RuntimeIndex.Field` gains `Optional<VectorFormat> vectors`, and `Documents.DocumentField` `Optional<Vector> vector`. A constructor with the signature of interface 4 fills in `empty`, as `Commits.Segment`'s do (ADR 0048 §2).
   - A new group, `Optional<Knn> knn()`: `approximate(field, target, k, filter)` and `exact(field, target, filter)` return a `Search.ParsedQuery`, so `Search.search` runs them with paging, stored fields and progress as it runs classic ones; the filter is a query of `Search.parse`. `vector(docId, field)` reads a document's vector for a `docId` target, a deleted document's too, as ADR 0040 allows.
   - `leia-lucene3`, `leia-lucene5` and `leia-lucene7` follow: no `knn()` group, and fields and document fields without vectors.

   *Amended (ADR 0051, ADR 0055, ADR 0056, ADR 0057, ADR 0058, ADR 0059):* interface 6 adds the reader's segments, interface 7 `Documents.storedValues` for the stored-values scan, interface 8 `Search.matches` and `matchesDeleted` for the filter, interface 9 `Search.explain`, interface 10 `Search.term`, interface 11 the `Rebuild` group.
5. **9.12 implements all of it** (`Lucene9Index`, `Lucene9Commits`, `Lucene9Check`, `Lucene9Analyzers`, `Lucene9Points`, `ExactKnnQuery`):
   - Documents through `storedFields()` and `termVectors()`; `MultiTerms`, `MultiBits` and `FieldInfos.getMergedFieldInfos` in place of `MultiFields`; `TotalHits.value`, every hit counted.
   - A field's `VectorFormat` from its `FieldInfo`. A document's vector from its segment, through a fresh `FloatVectorValues` or `ByteVectorValues` (in 9.x themselves iterators), `advance(doc)` and a copy of `vectorValue()`. A segment of 8.x has none.
   - The KNN queries are 9.12's `KnnFloatVectorQuery` and `KnnByteVectorQuery`, and leia-core's `ExactKnnQuery` (a `FunctionScoreQuery` over `FieldExistsQuery` and `DoubleValuesSource.similarityToQueryVector`, both in 9.12).
   - **Cancellation.** The searcher's `QueryTimeout` is the search's progress: every 1024th `shouldExit` asks it, and it throws to stop the search, which the searcher passes on. 9.12's KNN queries take the searcher's timeout into their graph search (`TimeLimitingKnnCollectorManager`), so the approximate search, which runs when the query is rewritten, can be stopped too.
   - Commits as in 7.7, plus each field's KNN vectors format (`PerFieldKnnVectorsFormat`'s attribute) and the codec's. A segment of 8.x has the placeholder `KnnVectorsFormat.EMPTY`, reported as none.
   - The check is 9.12's `CheckIndex`, read-only as 7.7's (ADR 0048 §4), on one thread (`setThreadCount(1)`), with the levels of ADR 0046 §5. 9.12 has no `setCrossCheckTermVectors`: level 3 is `setDoSlowChecks(true)`, which includes that check. The vectors and soft deletes tests count in a segment's error.
   - The analyzers are 7.7's plus Estonian, Nepali, Serbian, Tamil and Telugu, 47 in all. `ClassicAnalyzer` and `UAX29URLEmailAnalyzer` moved to their own packages. 9.x's `StopAnalyzer` has no default stop words, so leia gives it 7.7's, `EnglishAnalyzer.ENGLISH_STOP_WORDS_SET`.
   - **Points.** 9.12 has Lucene 10's `PointValues.PointTree`, with each cell's box and size, so `Lucene9Points` ports leia-core's `Points` rather than `Lucene7Points`' computed sizes. 9.12's `BKDReader` computes the sizes of a tree written before 8.6 itself.
6. **The server** (amends ADR 0044 §4–5 and ADR 0040).
   - Fields map their `VectorFormat` to `IndexField`'s `vectorDimension`, `vectorEncoding` and `vectorSimilarity`; document fields their vector to the protocol's `KnnVector`.
   - `KNN_VECTORS` comes with the `Knn` group if a field has vectors, as `POINTS` does with points. `lucene8` has none, so it doesn't get it; `lucene8to9` does.
   - `startSearch` on a runtime takes a `knn` query as well as a `classic` one. It is checked as for Lucene 10: `FIELD_NOT_FOUND`, `INVALID_PARAMS` (no vectors, not exactly one target, `k`, the target's encoding, dimension, finite floats, zeros under cosine, a document without a vector), `DOC_NOT_FOUND`, and `INVALID_QUERY` for the filter. The checks that need no Lucene moved from `SearchQueries.knn` into `KnnChecks`, which both paths use, so they refuse in the same words; the target's checks are worded as leia-core's `KnnQueries.check`, which still checks for Lucene 10. The runtime parses the filter and builds the query.
   - `Feature.CLASSIC_SEARCH` now reads "a classic query only, or with `KNN_VECTORS` a KNN query too"; no record changes.
   - Routing stays: `Runtimes.jarFor(8)` names the jar, and the norms are decoded by `createdMajor()`, 8, as lengths.
7. **The TUI** offers the KNN mode and the documents screen's `N` with `KNN_VECTORS` and either `SEARCH` or `CLASSIC_SEARCH`. On a runtime they are as a classic search is: no similarity or sort, no explain, no highlights. *Amended (ADR 0057):* explain works on every runtime.
8. **Test indexes**, written by `leia-lucene9`'s build:
   - `lucene8`, by `Lucene8IndexWriter` on 8.11.4: `Lucene7IndexWriter`'s index (two commits, a plain and a compound segment, a deletion, offsets and payloads, doc values of every type and an update, `pt`/`loc`/`amount` points written 16 to a leaf under `Lucene87`'s name, `__soft_deletes` and a soft update), sorted by `order`. The sort puts the soft update of id 3 first in `_1`, as document 4.
   - `lucene8to9`, by `Lucene8to9IndexWriter` on 9.12.3 with backward-codecs: a copy of `lucene8` (the writer's second argument, an input of its task) with a third commit: ids 9 to 11 in a 9.x segment `_2`, with `title`, a `KnnFloatVectorField` `embedding` (4 floats, cosine) and a `KnnByteVectorField` `code` (4 bytes, Euclidean, ids 9 and 10 only), and id 5 deleted. 9.x keeps a field's schema, so the new documents have only `id` and `order` of the old fields. `SegmentsHeader` reads it as `(8, "9.12.3, created with 8")`.
   - `leia-server` and `leia-tui` take both from `leia-lucene9`. leia-core keeps its own small `lucene8` for its tests that Lucene 10 refuses it; `leia-server`'s `lucene8` is now `leia-lucene9`'s. The `MISSING_RUNTIME` test moved to `JarsBackendTest`, whose classpath has no runtimes: it is refused there, then opens once `leia-lucene9.jar` is added through `addJars`.

## Consequences

- A runtime jar built for interface 4 is refused and has to be built again.
- An index created by 8.x opens with every screen that 7.x has, plus the KNN vectors of the segments that 9.x wrote: the overview's vector column, a document's vector, `N`, and the KNN mode with approximate and exact search and a filter.
- `runtimes/` grows by 8.8 MB.
- Caveats:
  - An index created by 8.x that still has 7.x segments can't be read by either runtime: 9.12 refuses segments older than 8, and 7.7 the 8.x `segments_N`.
  - Soft-deleted documents are live, as in the other runtimes and Lucene 10 (ADR 0048).
  - Two test indexes are called `lucene8`: leia-core's and leia-lucene9's. Each module's tests see one of them.
  - leia-lucene5, leia-lucene7 and leia-lucene9 are copies that differ in their Lucene's APIs; sharing their sources stays a task (ADR 0048 §1).
