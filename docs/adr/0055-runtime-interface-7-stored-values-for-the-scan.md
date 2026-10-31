# ADR 0055 — Runtime interface 7: stored values for the scan

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0049 §4 (the runtime interface); ADR 0027 (where the scan's loop runs)
- **Amended by:** ADR 0114 (the scan reads a segment at a time, through the codec's merge instance)

## Context

The regex scan over stored values (ADR 0027) worked only on Lucene 10 indexes. Core's `StoredScan` ran its own Lucene 10 loop over `IndexReader.leaves()`, so on an index that a runtime reads (3.6, 5.5, 7.7, 9.12) `STORED_SCAN` was greyed out. The runtime interface had only `Documents.document(docId)`, which also reads norms, doc values, term vectors and KNN vectors: too heavy to call for every document of a scan.

The filter (`FILTER`, ADR 0032) needs the same values for its pattern filter, so the read should serve both.

## Decision

1. **`Documents.storedValues(fromDoc, toDoc, fields, includeDeleted)`.** It returns a `StoredDocument(docId, deleted, values)` for every document id in `[fromDoc, toDoc)`, in order: the live ones, and the deleted ones too if `includeDeleted`. `values` has only the fields of `fields` (all if empty), each field's values in stored order, as `DocumentField.stored` has them, since a hit's `valueIndex` addresses `getStoredValue`. Numbers stay in the list, so that they count for `valueIndex`. Nothing else of a document is read. Outside `0 <= fromDoc <= toDoc <= maxDoc` it throws `IllegalArgumentException`.
2. **Ranges, no callback.** The runtime returns a page, as the terms and postings pages do (ADR 0007). The server loops over pages and asks the scan's progress between them, so cancellation stays cooperative (AGENTS.md, hard rule 7) without a callback across the runtime boundary. Each call opens the stored-fields reader and the live docs once:
   - 9.12: one `reader.storedFields()` and `MultiBits.getLiveDocs`, with `DocumentStoredFieldVisitor(fields)` for a field filter.
   - 7.7 and 5.5: `reader.document(doc, DocumentStoredFieldVisitor)` and `MultiFields.getLiveDocs`; the two copies are the same (ADR 0050).
   - 3.6: `reader.document(doc, MapFieldSelector)` and `reader.isDeleted(doc)`. Its `document()` reads deleted documents too, so 3.6 has the feature like the others.
3. **Core splits `StoredScan`.** Matching (the time-limited matcher, spans, snippets, UTF-8 offsets) reads a `StoredScan.StoredSource` (`maxDoc()`, `read(from, to)`), as `TermExport` reads a `TermSource`. `StoredScan.scan` and `StoredScan.matches` are public and take a source; `run` reads 64 ids at a time and reports progress before each page. `scannedDocs` counts every id of a page, skipped deleted ones too, as before. The Lucene 10 source (`ReadOnlyIndex.storedValues`) keeps each leaf's `StoredFields` across pages, so Lucene 10 reads as it did.
4. **The server.** `RuntimeResults.storedSource` maps a runtime's values to core's (`Numeric` by its type to `IntValue`, `LongValue`, `FloatValue` or `DoubleValue`) and checks the fields (`FIELD_NOT_FOUND`). `startStoredScan` branches on the runtime as `startExportTerms` does, and its job says "with Lucene <version>". `getStoredScan`, the protocol and the TUI are unchanged; the TUI already gates regex mode on `STORED_SCAN`.
5. **Runtime interface 7, one bump per item.** `LuceneRuntime.API_VERSION` is 7. Each later item of the lane that changes the interface bumps it again, as ADRs 0048, 0049 and 0051 did, rather than collecting the lane's changes into one version.

## Alternatives considered

- **A callback per document** (`forEachStored(fields, consumer)`): one call per scan, but the runtime would have to ask the progress itself, and a callback across the relocated runtime's boundary hides where the time goes. Pages keep the runtime stateless between calls.
- **`document(docId)` per document**: no interface change, but it reads norms, doc values and vectors for every document.
- **One interface version for the whole lane**: fewer bumps, but a lane half done would ship a version whose methods are partly unimplemented.

## Consequences

- `STORED_SCAN` is reported on every runtime with documents; `docs/runtimes.md` has it ✓ in every column.
- A runtime jar built for interface 6 is refused, as before (ADR 0044).
- The filter item uses `RuntimeResults.storedSource` with `StoredScan.matches` for its pattern filter.
