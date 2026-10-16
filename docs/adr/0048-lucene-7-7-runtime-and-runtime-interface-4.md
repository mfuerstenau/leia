# ADR 0048 — The Lucene 7.7 runtime and runtime interface 4

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0044 §4 (the features per runtime); ADR 0046 §3 (the runtime interface); ADR 0041 §8–9 (the point methods on a runtime)

## Context

Lucene 10 reads indexes of 9 and 10 only. Since ADR 0044, a 6.x or 7.x index failed with `MISSING_RUNTIME` naming `leia-lucene7.jar`. The routing was ready: `SegmentsHeader` reads the created major from 7.0 on, and `Runtimes.jarFor(6, 7)` names the jar.

Runtime interface 3 (ADR 0046) can't carry what 6.x and 7.x add:

- points (6.0)
- the index sort (6.2)
- soft deletes (7.4)
- a segment's minimum version (7.0)
- the major of the Lucene that created the index (7.0)

The created major matters beyond the overview. Since the fixes after the manual run on 4.x/5.x, the documents screen decodes norms by the major: before 7 they are `floatToByte315(1/√length)`, from 7 on the length. The server passed `IndexStats.oldestMajor()`, the major of the oldest segment. For an index created by 6 whose segments 7 has rewritten, that is 7, but its norms are still in the encoding of 6.

## Decision

1. **A runtime `leia-lucene7`** with lucene-core, backward-codecs (the codecs of 6.x), queryparser and analyzers-common 7.7.3, relocated and built by `leia.lucene-runtime-conventions` as `leia-lucene5` is (ADR 0046 §1–2). `indexMajors()` is `{6, 7}`. It is `runtimes/leia-lucene7.jar` beside `leia.jar`. It starts as a copy of `leia-lucene5`, adapted to 7.7; sharing sources between the runtimes for 5, 7 and 9 stays a task once 9 exists.
   - *Amended by ADR 0050:* the facades stay copies; the Lucene-free helpers are shared in `leia-runtime`'s `support` package.
2. **Runtime interface 4.** `LuceneRuntime.API_VERSION` is 4.
   - `RuntimeIndex.Field` gains `pointDimensionCount` and `pointNumBytes`, 0 without points.
   - `IndexStats` gains `OptionalInt createdVersionMajor`, empty before 7.0 (7.7 reports 6 for a commit of 6.x), and `createdMajor()`, which falls back to `oldestMajor()`.
   - `Commits.Segment` gains `softDelCount`, `Optional<String> minLuceneVersion` and `Optional<String> indexSort` (`Sort.toString()`, as leia-core gives it). The constructor of interface 3, without them, fills in 0, empty, empty, and so does the one before codecs.
   - A new group, `Optional<Points> points()`: `tree(field)` and `cell(field, segment, path)`, with the records of leia-core's `Points` in JDK types (`byte[]` for `BytesRef`) and `MAX_POINTS = 10_000`. `cell` throws `IllegalArgumentException` for a bad segment or path.
   - `leia-lucene3` and `leia-lucene5` follow: no points group, fields with `0, 0`, no created major, segments with `0`, empty, empty.
   - *Amended by ADR 0049 §4:* interface 5 adds KNN vectors: `Field.vectors`, `DocumentField.vector` and the group `knn()`.
3. **The server.**
   - `ReaderInfo` and `IndexCommitStats` take `createdMajor()`, so the norms are decoded by the encoding of the Lucene that created the index, not that of the oldest segment.
   - Segments map soft deletes, the minimum version and the index sort; fields their point dimensions and bytes.
   - `POINTS` comes with the `Points` group if a field has points, as `DOC_VALUES` does with documents (amends ADR 0044 §4).
   - `getPointTree` and `getPointCell` go to `RuntimeResults.pointTree` and `pointCell` when a runtime reads the index (amends ADR 0041 §8). They check the field against `RuntimeIndex.fields()` (`FIELD_NOT_FOUND`, `INVALID_PARAMS` without points), map a segment's index directory from the directories' `docBase` as for Lucene 10, and refuse with `NOT_SUPPORTED` when the group is missing.
4. **7.7 implements all of it** (`Lucene7Index`, `Lucene7Commits`, `Lucene7Check`, `Lucene7Analyzers`, `Lucene7Points`):
   - Doc values and norms are read through 7.0's iterators: a fresh one for each read, `advanceExact(doc)`, then the value. There is no `getDocsWithField`. `TopDocs.totalHits` is a `long`.
   - The created major is `SegmentInfos.getIndexCreatedVersionMajor()`; a segment's minimum version is `SegmentInfo.getMinVersion()`, its sort `getIndexSort()`, its soft deletes `SegmentCommitInfo.getSoftDelCount()`, and `Formats.points` the codec's points format.
   - The check is 7.7's `CheckIndex`, read-only as 5.5's (ADR 0046 §4), with the levels of ADR 0046 §5. Every segment of 6.x and 7.x has checksums, so 7.7 gives no `-fast` hint and leia has none to rewrite. The read-only directory also refuses 7.x's `createTempOutput`, `syncMetaData` and `rename`. The points and index sort tests count in a segment's error.
   - The analyzers are those of 5.5 plus `BengaliAnalyzer`, 42 in all. `StandardAnalyzer` and `CharArraySet` moved to core's `org.apache.lucene.analysis` in 7.0.
5. **The point tree on 7.7** (amends ADR 0041 §9). 7.7 has no `PointTree`, but a segment's `getPointValues` is its `BKDReader`, whose `IndexTree` is public: `getIntersectState(visitor).index`, `pushLeft`, `pushRight`, `pop`, `isLeafNode`, `nodeExists`, `getNodeID`, `getNumLeaves` and `getSplitDim`/`getSplitDimValue`; `visitLeafBlockValues` reads a leaf. So:
   - **Boxes.** A cell's box starts as the segment's minimum and maximum. Going left sets the maximum of the split dimension to the node's split value, going right the minimum, as `BKDReader.intersect` does.
   - **Sizes.** The tree doesn't record them, so they are computed as Lucene 9's `BKDReader` computes them for trees written before 8.6. Nodes are numbered as in a heap, and the leaves are those from `leafNodeOffset`, the number of leaves, on. `maxPointsInLeafNode` is `estimatePointCount` with every cell inside, divided by the leaves. A tree is *balanced* if it has more than one dimension, or one dimension, leaves that are a power of two, and a last leaf that doesn't hold `size % maxPointsInLeafNode` points (Lucene 9's `isTreeBalanced`, which reads that one leaf):
     - A balanced tree, which 6.x/7.x write for several dimensions, has leaves of `maxPointsInLeafNode` or one less, spread as `sizeFromBalancedTree` says.
     - Otherwise every leaf is full but the last, as `OneDimensionBKDWriter` writes one dimension from 6.4 on, and the leaves may be at two depths.
   - **Leaves.** The visitor answers `CELL_CROSSES_QUERY`, so 7.7 gives every point with its value. Points are marked deleted from the leaf's live docs, sorted by document, then value, and cut at `MAX_POINTS`.
   - It reads the tree's index and one leaf beyond the cell itself, so the methods stay synchronous.
6. **Test indexes** `lucene6` (6.6.6) and `lucene7` (7.7.3), written by `Lucene6IndexWriter` and `Lucene7IndexWriter`, are like those of 4.x/5.x (ADR 0046 §8), with a doc values update in both. Both add points, written through a `FilterCodec` under the default codec's own name whose points writer puts at most 16 points into a leaf (as ADR 0041 §11):
   - `pt`, 15 ints per document: 60 points in `_0` (leaves of 16, 16, 16 and 12); 45 in the `_1` of 6.x, three leaves at two depths.
   - `loc`, 11 two-dimensional ints per document: 44 points in `_0` (four leaves of 11); 33 in the `_1` of 6.x (9, 8, 8 and 8).
   - `amount`, a long, one leaf.
   - 6.x keeps the trie-encoded `price`/`time` (`LegacyIntField`, `LegacyLongField`) and sorts its documents by the numeric doc values `order` (`setIndexSort`).
   - 7.x has the soft deletes field `__soft_deletes` and soft-updates document 3 in the second commit: `_0` gets a `softDelCount` of 1, and `_1` a document 8.

## Consequences

- A runtime jar built for interface 3 is refused and has to be built again.
- The points of 6.x and 7.x can be browsed as those of 9 and 10. Each cell's computed size is checked in the tests against the points of its leaves.
- The commits screen shows soft deletes, "Sorted by" and the points format for 6.x/7.x; it did so for Lucene 10 already.
- Caveats:
  - A 6.x index that still has 5.x segments can't be read by either runtime: 7.7 refuses the 5.x segments, and 5.5 the 6.x `segments_N`.
  - Soft-deleted documents count as live, as in Lucene 10's plain `DirectoryReader`, which leia also opens without a soft deletes wrapper.
  - 7.7 reports a created major of 6 for every commit of 6.x, even one whose index was created by 5.x and whose segments 6 rewrote. Its norms would then be decoded in the encoding they have anyway, since 5.x and 6.x share it.
