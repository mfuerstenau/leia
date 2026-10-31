# ADR 0114 — Stored values in segment order, and what the stored fields cache spares

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR 0055 (how the scan reads the stored values), ADR 0032 §1 (the params of `startFilter`), ADR 0113 (its last consequence)

## Context

ADR 0113 reads the stored fields of a segment through the codec's merge instance, which decompresses each block of documents once, where the reader's `storedFields()` decompresses a block again for each document: ten times faster with LZ4, ninety with DEFLATE. Two other readers of stored values didn't benefit:

- **The regex scan and the pattern filter** (`startStoredScan`, `startFilter` with a `pattern`, MCP `scan_stored`) read through `Documents.storedValues(fromDoc, toDoc, …)` of ADR 0055, 64 documents at a time. Each call opened the reader's random-access stored fields anew. The merge instance is for one pass in order on one thread, so it can't outlive a stateless call.
- **Neither used the cache.** With `fields`, a scan read every document, also those that store none of the fields. With the merge instance, a document that isn't read costs nothing, and a block none of whose documents is read isn't decompressed: a scan of a rare field costs the blocks that hold it.

And the cache holds exactly "the documents that store field X", which nothing offered as a filter.

## Decision

1. **Runtime interface 25** (`LuceneRuntime.API_VERSION`): `Documents.storedValues(segment, fields, includeDeleted, next, sink, progress)` reads one segment in document order and passes each document, with its segment-local id, to `sink`. `segment` is as `storedFields` takes it. `next` (an `IntUnaryOperator`) gives the first document at or after the one it gets that is to be read, or the segment's maxDoc for none; the documents it passes over aren't read. `progress` gets the documents gone through and throws to stop the read (rule 7). Lucene 4 and later read through the merge instance where the leaf is a segment's, as ADR 0113 §2 does, and a server's Lucene 9.0 to 9.4 through the leaf. Lucene 3.6 reads through the reader, since 3.x stores fields uncompressed. The paged `storedValues` stays, for the highlights, which read one document.
2. **The scan's source pushes.** The server's `StoredScan.StoredSource` gives its documents to a sink, segment by segment, and the documents gone through to a second consumer. The scan reports progress from those, and every 64 documents it gets, so `Esc` stops it as soon as before. The hits, their order and the summary don't change; `scannedDocs` still counts every document gone through, those passed over too.
3. **The cache spares documents, never fills.** For each segment the `StoredFieldsCache` knows (in memory or on disk, with as many documents as the segment has now), the source reads only the documents that store one of the scanned fields, or any field without `fields`. It doesn't call the runtime for a segment where none does. A segment the cache doesn't know is read whole, and the scan doesn't add it to the cache: with `fields` it doesn't see the other fields, and filling the cache would mean decompressing every block. The lookup runs in the job, not on the backend thread, since it may read the disk.
4. **A filter of the documents that store a field.** `StartFilterParams` gains `storedField?`; exactly one of `query`, `pattern` and `storedField` is given, else `INVALID_PARAMS`. A field the index doesn't have fails with `FIELD_NOT_FOUND`, and a runtime without the documents with `NOT_SUPPORTED`. The job goes through the segments as `startStoredFieldsScan` does: a segment the cache doesn't know is read without its values and cached. It sets the field's documents, minus the deleted ones unless `includeDeleted`. Its `query` and `rewrittenQuery` are `stored:<field>`, and `docsSearched` counts the documents gone through, a cached segment's at once.
5. **TUI:** `b` on the overview browses the documents that store the selected field on the documents screen, as `b` on the search screen does with its query (ADR 0031), and `B` includes the deleted ones (ADR 0032 §6). Once `f` has found the stored fields, `b` on a field no document stores says so instead. The footer offers `b/B docs storing it` where the runtime has the stored fields. A reopen rebuilds the filter from its params, as for any filter.
6. **MCP** gains no tool: filters are not planned for MCP mode. `scan_stored` gets the speed-up of §1 and §3 without a change to its schema.

## Consequences

- A regex scan, whether the search screen's, a pattern filter or `scan_stored`, decompresses each block once. After `f` or `stored_fields`, a scan of a rare field reads only the blocks that hold it.
- Every runtime implements the per-segment `storedValues`. The adapters of runtime interface 24 are refused as outdated, and `leia.jar` rewrites them at startup (ADR 0098).
- `StartFilterParams` has a third, optional source. The TUI's other filters pass none.
- The stored-field filter costs a stored fields scan the first time, and then the reading of the deletions alone.
