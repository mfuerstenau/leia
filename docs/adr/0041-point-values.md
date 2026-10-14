# ADR 0041 — Point values: a document's points and a field's BKD tree

- **Status:** Accepted
- **Date:** 2026-10-11
- **Amends:** ADR 0029 §1, §4, §5 (the rebuild takes fields with points, and its progress counts terms and points), ADR 0030 §2, §6 (the export format becomes version 3, with points)

## Context

leia reported only a field's point dimension count and bytes per dimension (`IndexField`). ADR 0029 §5 and ADR 0030 §6 left points out. Two gaps remained (`docs/tasklist.md`, improvements over Luke §6):

- **A document's points.** Fields like the demo index's `price`, `quantity` and `location` are points, stored values and doc values at once. Nothing showed what was actually *indexed* as a point, and `r` refused such a field, since its `IndexOptions` is `NONE`.
- **A field's BKD tree.** There was no view of a point field's per-segment min, max and count, how its cells split the value space, or which documents sit in a leaf cell.

A segment keeps a field's points in a BKD tree, and nothing maps a document to its points: finding one document's points means going through every point of the field in the segment, as finding its terms means going through the postings (ADR 0029).

The index doesn't record what a point encodes. A 4-byte point is an `IntPoint` or a `FloatPoint`, an 8-byte one a `LongPoint` or a `DoublePoint`, two dimensions of 4 bytes may be a `LatLonPoint`, 16 bytes an `InetAddressPoint`. The bytes are sortable encodings, so they don't tell either. The search screen has the same problem for ranges and guesses (ADR 0011 §6).

## Decision

### A document's points

1. **The rebuild job of ADR 0029 rebuilds points too.** `startReconstruct {docId, fields?}` takes fields with postings **or points**; absent `fields` means every such field, in field number order. A field with neither is `INVALID_PARAMS` ("has no terms or points to rebuild"). There are no new methods and no new error codes.
   - Core goes through the field's points in the document's segment with `PointValues.getPointTree().visitDocValues(visitor)` from the root, and keeps the packed value of each point whose doc id is the document's. Deleted documents are rebuilt too, as their terms are.
   - The points come sorted by their bytes (unsigned), duplicates kept: a multi-valued field shows each value as often as it was added.
   - It is a job for the same reason the terms are (rule 7): the walk reads every leaf of the field's tree in the segment.

2. **`ReconstructedField` gains `points`: a list of `PointValue {dimensions}`**, each dimension's bytes in lowercase hex, as Lucene packs them. A field with only points has `indexOptions` `NONE`, `source` `POSTINGS` and no terms.
   - The server splits the packed bytes by `FieldInfo.getPointNumBytes()`.
   - Decoding is the TUI's job, as with the raw `long`s of numeric doc values: the wire carries what the index holds, not a guess.

3. **Progress counts terms and points.** `getReconstruct`'s `scannedTerms` and `totalTerms` become `scanned` and `total`. The total adds `PointValues.size()` of each field in the segment to the terms of ADR 0029 §4; progress is reported every 1024 terms or points, with the cancel check. The rename is safe: the UI and the backend are one JVM, compiled together (ADR 0034).

### The TUI's point formats

4. **`PointFormat` decodes a point** as `INT`, `LONG`, `FLOAT`, `DOUBLE` (the sortable encodings of Lucene's point fields, per dimension), `LAT_LON` (two dimensions of 4 bytes, `encoded × 180/2³²` and `× 360/2³²`, shown to 6 decimals), `INET` (16 bytes; an IPv4-mapped address shows as IPv4) or `HEX`. `leia-tui` can't use Lucene, so it decodes the bytes itself, as `NumberFormat` does for doc values. Floats print as the doc values do (`NumberFormat.debug`).
   - One dimension shows as `42`, several as `[1, 2, 3]`, a latitude and longitude as `52.52, 13.405`. A point that doesn't fit the format shows in hex.

5. **The TUI guesses, and `t` corrects.** The formats that fit a field's size are, in the order `t` cycles them:
   - 4 bytes: `INT`, `FLOAT`, `HEX`; with 2 dimensions `LAT_LON` first;
   - 8 bytes: `LONG`, `DOUBLE`, `HEX`;
   - 16 bytes, 1 dimension: `INET`, `HEX`;
   - anything else: `HEX`.

   The first is the guess, as ADR 0011 §6 guesses `INT` and `LONG`; it is wrong for float and double fields, but a visible guess is easy to fix. The choice is kept per field in `PointFormats`, which the documents and terms screens share. A reopen keeps it while it still fits the field's size; a new index starts over.

6. **The documents screen:**
   - `r` works on a field with postings or points.
   - The "Rebuilt" section says `rebuilding… N of M terms` (or `points`, or `terms and points`, by what the field has), and once done `1 term, from the postings · 2 points`.
   - A field with only points shows `Rebuilt  1 point, as int (t)`, then one decoded point per line. A field with both shows its terms, then a `Points  2 points, as int (t)` header and the points.
   - `t` cycles the selected field's format.

7. **The export (ADR 0030) gains `points` per field and becomes version 3.** It holds the `PointValue`s, raw hex as the doc values hold raw `long`s, and is there when the points were rebuilt and the field has points. `indexed` is now there only for a field with postings, so a field with only points has `points` and no `indexed`. The tab line says `Exporting doc N: scanned/total terms and points`, and the prompt's `Tab` toggle `terms and points`. Versions 1 and 2 still read, without points.

### A field's BKD tree

8. **Two synchronous methods, in the new package `protocol.points`:**
   - `getPointTree {field}` → `{field, dimensions, indexDimensions, bytesPerDimension, size, docCount, min?, max?, segments}`. `size` and `docCount` are summed over the segments; `min` and `max` cover the index dimensions over the index, absent without points.
   - Each `PointSegment` is `{segment, index, name?, docBase, maxDoc, size, docCount, min, max}`, only for segments with points in the field. `segment` is the leaf's position in the open reader, the handle for the next method; `index` the position of its index directory (ADR 0021), found from the directories' `docBase`; `name` the segment's name when the leaf is a `SegmentReader`.
   - `getPointCell {field, segment, path}` → `{cell, children, points, pointsCut}`. `path` lists child positions from the segment's root: `[]` is the root, `[0, 1]` its first child's second child. A `PointCell` is `{min, max, size, leaf}`. `points` are `LeafPoint {docId, deleted, value}`, only for a leaf, by global document id, then value. At most `PointCell.MAX_POINTS` (10,000) come back, with `pointsCut`; Lucene's leaves hold 512 at most by default.
   - Errors: `NO_INDEX_OPEN`, `NO_READER`, `FIELD_NOT_FOUND`, and `INVALID_PARAMS` for a field without points, a segment out of range or without points in the field, or a path to no cell. No new error codes.

9. **They are no jobs.** Core walks `PointValues.getPointTree()` with `moveToChild` and `moveToSibling` along the path, then lists the children the same way; `size()` of a BKD node is arithmetic. That reads the tree's index only, O(depth). A leaf's points are one block, read with `visitDocValues`, and marked deleted from the leaf's live docs. So rule 7 holds without the job plumbing.

10. **The terms screen shows the tree.**
    - **Fields column:** a field with points has a row of its own, after its postings row if it has postings, marked dim `pt 1×8` (dimensions × bytes).
    - **Middle column:** a row per segment, `▸ _0  199 pts  1.07 … 499.23`, and below an expanded one its cells, indented two columns per level: `▸` collapsed, `▾` expanded, `•` a leaf. `Enter` fetches a cell's children through `getPointCell` the first time, then expands or collapses it; on a leaf, it fetches the leaf's points and moves the focus to them.
    - **Right column:** the leaf's points, `Doc  Value`, deleted ones in red; `Enter` shows the document on the documents screen, with the field selected.
    - **Title:** `price · 3 segments · 499 points in 499 docs · as double`. `/` and `e` say they work on terms only.
    - **`t`** cycles the field's format, shared with the documents screen (decision 5).
    - **Replies:** each `getPointTree` call has a serial, and a cell's reply names it, so a reply for a tree no longer shown is dropped. A reopen that finds a new commit changes the segments' positions; the tree is fetched again, collapsed.

11. **The demo index writes leaves of 16 points.** Its segments have at most 200 points per field, below Lucene's 512, so each tree would be one leaf. `DemoIndexWriter` wraps the default codec under its own name, with a points format whose writer is `Lucene90PointsWriter` with 16 points per leaf. The leaf size is in the files, so the default codec reads them, and nothing else of the index changes.

## Alternatives considered

- **A separate points job**, `startDocPoints`. It would duplicate the job plumbing, the progress and the cancel paths of the rebuild for one more kind of indexed data, and the export would need two jobs. Rejected.
- **Decoding on the server**, with a type per field in the params. The server would decode by a guess it can't check, and every change of format would be a call. The TUI has the bytes and decodes them at once. Rejected.
- **A synchronous field of `getDocument`.** Finding a document's points reads every leaf of the field's tree in its segment, which can take long on a large segment (rule 7). Rejected.
- **A dedicated points screen.** The terms screen already has a field column and a middle and right column that page through what a field holds; a point field's tree fits the same shape, and an eighth screen would take a number key. Rejected.
- **The whole tree in one call.** A segment of millions of points has thousands of cells. Fetching a level at a time keeps every call small. Rejected.
- **Cells by an id of their own,** such as Lucene's node id. `PointTree` doesn't expose it; a path of child positions works on any `PointTree`. Rejected.
- **The search screen's point types** (`ParserSettings`) as the format. Those map a field to a parser's `PointsConfig`, only for one-dimensional 4- and 8-byte fields, and only in standard mode. A format of its own covers lat/lon, addresses and several dimensions. The two guesses are made independently. Rejected.

## Consequences

- New methods `getPointTree` and `getPointCell`, with handlers in `server.points`, and records `GetPointTreeParams`, `GetPointTreeResult`, `PointSegment`, `GetPointCellParams`, `GetPointCellResult`, `PointCell`, `LeafPoint`.
- Core has `Points`, which reads the tree through `PointValues.PointTree`. A Lucene upgrade that changes how that interface moves needs a look at it.

- `ReconstructedField` gains `points`; `GetReconstructResult`'s fields are renamed. New record: `PointValue` in the new package `protocol.points`.
- Core's `Reconstruct.Field` gains the packed points; `ReadOnlyIndex.reconstructTermCount` is now `reconstructCount`.
- The Lucene 9 test index now has an int point field, `number` (N × 10), written only when `LegacyIndexWriter` runs on Lucene 9. So the backward codecs' points are read in the tests (hard rule 5).
- The export's golden file is version 3.
