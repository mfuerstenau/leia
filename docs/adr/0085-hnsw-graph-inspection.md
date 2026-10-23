# ADR 0085 — HNSW graph inspection

- **Status:** Accepted
- **Date:** 2026-10-17
- **Amends:** ADR 0040 (the graph behind an approximate KNN search, left out there), ADR 0049 (the 9.12 runtime reads it too)

## Context

leia shows a document's KNN vector and searches by vector, approximately or exactly (ADR 0040, ADR 0049), but not the HNSW graph behind the approximate search: how many levels each segment's graph has, which node a search starts from, and which neighbours a node has. Luke shows none of it either (`docs/luke-features.md` §6). It is the item "HNSW graph inspection" of `docs/tasklist.md`; the recall check, the other half of that item, is ADR 0086.

What Lucene offers:

- `org.apache.lucene.codecs.hnsw.HnswGraphProvider.getGraph(field)`, in 9.12 and 10.x. Lucene's own `HnswUtil.graphIsRooted` reaches it through `FilterLeafReader.unwrap(leaf)` → `CodecReader.getVectorReader()` → `PerFieldKnnVectorsFormat.FieldsReader.getFieldReader(field)`. The readers of 9.5's and 9.9's HNSW formats implement it (`Lucene95HnswVectorsReader`, and `Lucene99HnswVectorsReader`, which the scalar- and binary-quantized HNSW formats use too); those of 9.0 to 9.4 and the flat formats don't.
- An `HnswGraph` has `numLevels()`, `entryNode()`, `size()`, `getNodesOnLevel(level)` (level 0 is every node), and `seek(level, node)` then `nextNeighbor()` until `NO_MORE_DOCS`. It is stateful. `seek` on a level the node isn't on is only asserted. 10.x adds `maxConn()`, Lucene's M.
- A node is a **vector ordinal**: the position of a document's vector among the segment's vectors of the field, in document order. 10.x's `KnnVectorValues` maps an ordinal to its document (`ordToDoc`) and vector (`vectorValue(ord)`). 9.12's `FloatVectorValues` is an iterator without ordinals, but the values its readers return are `RandomAccessVectorValues` too, with both.
- **Lucene 10 builds no graph for a small segment.** 10.5.2's `Lucene99HnswVectorsFormat` has a `tinySegmentsThreshold` (default 100): a segment of `n` vectors gets a graph only if `n > ln(n)·100`, about 650 vectors; a search then compares every vector. 9.12 always builds one.

## Decision

1. **A runtime group `KnnGraph`** (`leia-runtime`), `RuntimeIndex.knnGraph()`; runtime interface 21. Lucene 3.6, 5.5 and 7.7 have none. Like the point trees (ADR 0041), a segment is the leaf's position in the open reader, the handle until a reopen.
   - `graph(field)` → per segment with vectors in the field, `SegmentGraph {segment, name?, docBase, maxDoc, vectors, shape?}`; `Shape {levelSizes, entry, maxConn?}`. A segment without a graph leia can read (a format without one, or one too small for Lucene 10 to build one) has no shape.
   - `nodes(field, segment, level, offset, limit)` → `NodePage {total, nodes}`, in ordinal order; a limit of 1 to `MAX_NODES` (1000).
   - `node(field, segment, ord)` → the `Node` and, from its top level down to 0, `LevelNeighbours {level, neighbours}`, each `Neighbour {node, score}` in the graph's order.
   - `find(field, docId)` → `NodeRef {segment, ord}`; empty if the document has no vector there.
   - `Node {ord, docId, deleted, topLevel}`: `docId` index-wide, `deleted` from the leaf's live documents (a deleted document's node stays in the graph until a merge), `topLevel` the highest level the node is on.
   - What doesn't exist (a field, a field without vectors, a segment, a segment without a graph, a level, an ordinal, an offset or limit out of range) is an `IllegalArgumentException`; a read that fails an `IOException`.

2. **The score is the field's similarity of the two raw vectors**, `VectorSimilarityFunction.compare`, as an exact search scores them, so a neighbour's score is the one the search screen shows for it (`DemoIndexTest` checks this). A quantized format keeps the raw vectors and leia compares those; the graph itself was built from the quantized ones, so a score can differ a little from what the graph compared.

3. **No job.** Each call reads the node lists of the levels above 0, which are on the heap and hold about one in M of the nodes below, sorted once for a binary search of a node's top level, and then at most one page of nodes or one node's neighbours (at most 2·M on level 0, M above) and their vectors. As with the point trees (ADR 0041 §9), that keeps rule 7 without the job plumbing. A level's membership is checked before `seek`, which Lucene only asserts. Each call takes a fresh graph from the reader, since an `HnswGraph` is stateful.

4. **Implementations.** leia-core's `KnnGraphs` reads Lucene 10's through `ReadOnlyIndex` (`knnGraph`, `knnGraphNodes`, `knnGraphNode`, `knnGraphFind`), with field checks as `pointField`'s, and `leia-lucene10`'s `Lucene10KnnGraph` maps its records. `leia-lucene9`'s `Lucene9KnnGraph` is a near copy over 9.12's API: `HnswGraph.NodesIterator.getSortedNodes`, no M, and an ordinal's document and vector through `RandomAccessVectorValues`, or else the ordinal as the position of the document among those with a vector; a document's ordinal is a binary search of `ordToDoc`, which ordinals in document order allow. A reader that refuses the field in `getGraph` (an `IllegalArgumentException`) is a segment without a graph.

5. **Three synchronous methods**, in the new package `protocol.knn`:
   - `getKnnGraph {field}` → `{field, dimension, encoding, similarity, segments}`, each `KnnGraphSegment {segment, index, name?, docBase, maxDoc, vectors, shape?}`, `KnnGraphShape {levelSizes, entry, maxConn?}`; `index` as `PointSegment`'s.
   - `getKnnGraphNodes {field, segment, level, offset, limit}` → `{total, nodes}`, a limit of 1 to `MAX_NODES` (1000).
   - `getKnnGraphNode {field, segment?, ord?, docId?}`, either a document or a segment and an ordinal → `{segment, node, levels}`, each `KnnGraphLevel {level, neighbours}` of `KnnGraphNeighbour {node, score}`.
   - `KnnGraphNode {ord, docId, deleted, topLevel}`.
   - Errors: `NO_INDEX_OPEN`, `NO_READER`, `NOT_SUPPORTED`, `FIELD_NOT_FOUND`, `DOC_NOT_FOUND`, `INDEX_NOT_READABLE`, and `INVALID_PARAMS` for a field without vectors, a segment out of range or without a graph, a level, ordinal, offset or limit out of range, a wrong combination of `getKnnGraphNode`'s params, or a document without a vector. No new error codes. The server's `RuntimeKnnGraph` maps them, the handlers are in `…server.knn`.

6. **`Feature.KNN_GRAPH`**: the runtime has the group and a field has vectors. `docs/runtimes.md` has its row (9.12.3 and 10.5.2, with the segments that show no graph in a footnote), and the user README puts it in the row of KNN vectors.

7. **The terms screen shows the graphs**, as it shows a BKD tree (ADR 0041 §10):
   - **Fields column:** a field with vectors has a row of its own, after its terms and points rows, marked dim `hnsw 8d`.
   - **Middle column:** a row per segment, `▸ _0  199 vectors · 3 levels · M 16`, or `_2  80 vectors · no graph`. Expanded: `entry  ord 17 · doc 42`, then a row per level, top first, `▸ L1  14 nodes`, each of which expands to its nodes (`ord 3 · doc 4 · top L1`, deleted ones in red), fetched 1000 at a time with a `⏎ more (1000 of 4321)` row. The title says `embedding · 3 segments · 499 vectors · cosine`.
   - **Right column**, as wide as the graphs' largest ordinal and document need, from the postings' 34 columns to 42: Enter on the entry or a node shows its neighbours and moves the focus there: the node itself first (`node`), then each level's neighbours with the level on the first of them, `Lvl Ord Doc Top Score`; a score has four decimals, and below 0.001 shows as `1.2e-5`, so that a euclidean field over bytes, scored about `1e-5`, shows more than zeros. Enter on a neighbour walks on to it; the title shows the walk's ordinals, `ord 17 › 3 › 9`, its start left out as far as the panel needs. `<` goes back a step without a fetch. `d` shows the selected node's or neighbour's document on the documents screen, with the field selected.
   - `/`, `e` and `s` say they work on terms only. Without `KNN_GRAPH`, the middle column says the runtime doesn't read graphs, and Enter and `<` are refused.
   - Replies carry the graphs' serial, as the point trees' do, and the node asked for last is the only one shown; a reopen fetches the graphs again, collapsed.

8. **The documents screen's `T` on a vector field** shows the document's node on the terms screen (`getKnnGraphNode` by `docId`), as `T` on a point field shows its tree (ADR 0052). The plan had `n`, which is the next document there already. The vector's line says `N finds the nearest, T its graph node`. A document without a vector in the field, or an index without `KNN_GRAPH`, gets a note.

9. **The demo index builds a graph in every segment.** Its segments of 200 and 100 documents are below Lucene 10's threshold, so it writes its vectors with `Lucene99HnswVectorsFormat(…, tinySegmentsThreshold 0)`, through the codec that already writes its small point leaves: `embedding` with Lucene's M of 16 (levels 199/14/1, 200/14/1 and 100/6), `fingerprint` with an M of 4 for more levels (up to 6). The threshold only decides whether a graph is written, and M is in the files, so the default codec reads them. The KNN results `DemoIndexTest` checks didn't change.

## Alternatives considered

- **A KNN screen of its own.** The terms screen already pages through what a field holds in three columns, and a graph's segments, levels and nodes fit that shape as a BKD tree does; an eighth screen would take a number key. Rejected.
- **The whole graph of a segment in one call.** A segment of millions of vectors has millions of nodes on level 0 and up to 32 neighbours each. Pages and one node at a time keep every call small. Rejected.
- **The scores the graph compared** (through the reader's `RandomVectorScorer`). For a quantized field those are the quantized vectors' scores, which no search shows the user, and the scorer's API differs between 9.12 and 10.x. The raw vectors' scores match the exact search. Rejected.
- **A job, for a large level.** Reading a level's node list is on the heap already, and a page reads at most 1000 nodes' documents; nothing here scales with the index beyond a level's list. Rejected.
- **Hiding the vector row of a segment without a graph.** A segment of the demo index's size has none on Lucene 10 by default; saying so explains why an approximate search there compares every vector. Rejected.
