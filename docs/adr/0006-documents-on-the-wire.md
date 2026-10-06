# ADR 0006 — Documents on the wire

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

The Documents area (`luke-features.md` §4.2) shows one document at a time: per field its norm, its stored values, its doc values and its term vector. Unlike the overview, this data is per document and much of it is untyped bytes. Several cross-cutting questions come up before the first method lands:

- **Where to read.** Luke reads through the composite reader: `MultiDocValues.getNormValues`, `getSortedDocValues`, and so on. For `SORTED` and `SORTED_SET`, `MultiDocValues` builds an `OrdinalMap` over the values of every segment. That costs time and memory proportional to the whole field, just to show the values of one document.
- **Deleted documents.** Luke shows a deleted document as empty. Its stored fields, norms, doc values and term vectors are still in the segment until a merge, and `luke-features.md` §6 lists deleted-document inspection as a gap.
- **Values JSON can't hold as is.** Stored values are strings, bytes, `int`, `long`, `float` or `double`. JSON numbers have no `NaN` or `Infinity`, and a float formatted on one side and parsed on the other can change. Binary doc values, sorted values and term vector terms are bytes. These are usually UTF-8, but not always.
- **Numeric doc values are opaque longs.** A `double` may be stored as raw bits (`DoubleDocValuesField`) or as a sortable long (`NumericUtils.doubleToSortableLong`). Nothing in the index records which encoding was used.
- **Size.** A stored field or a term vector can be large, but a single document is bounded. A scan of the whole field is not.

## Decision

1. **Two synchronous methods, no jobs.** `getDocument {docId}` returns every field's per-document data. `getTermVector {docId, field}` returns one field's term vector on demand, because a term vector can be much larger than the rest of the document. Both read a single document, so ADR 0005 does not apply. Browsing terms and postings comes later and gets its own methods.
2. **Read per segment.** The server finds the document's segment (`ReaderUtil.subIndex`) and reads norms, doc values, stored fields and term vectors from that leaf with the segment-local id. Nothing goes through `MultiDocValues`, so no `OrdinalMap` is built.
3. **Every field, per-document data only.** `getDocument` lists every field of the index in field-number order, the same list as `getIndexStats`. Each entry carries only what this document has: its norm, stored values, doc values, and whether it has a term vector. How a field is indexed is already in `getIndexStats`, and the TUI joins the two lists by name. Whether the document has postings in a field is not reported: finding out would mean scanning the field's terms.
4. **Deleted documents are readable.** `getDocument` on a deleted id returns its data with `deleted: true`. The TUI marks the document as deleted.
5. **Value encoding:**
   - **Byte strings** (binary stored values, `BINARY`/`SORTED`/`SORTED_SET` doc values, term vector terms) follow the rule `TopTerm` introduced: the text if the bytes are valid UTF-8, otherwise lowercase hex with `binary: true`. The server decides with a strict decoder.
   - **Stored values** carry their type (`STRING`, `BINARY`, `INT`, `LONG`, `FLOAT`, `DOUBLE`) and the value as a string. Numbers are formatted with Java's `toString`, which is exact for `float` and `double`, can express `NaN` and `Infinity`, and is parseable by Rust's `str::parse`.
   - **Numeric doc values** are sent as the raw `long`. The TUI decodes them for display (as a double or float from raw bits, or from a sortable long), because only the user knows the encoding.
   - **Payloads** are always hex. They carry no text convention.
6. **No size limits yet.** Values are sent whole. A cap, or paging through large values, comes with the stored-value viewer if real indexes need it.
7. **Error code `DOC_NOT_FOUND` (-32008)** for a document id outside `0..maxDoc-1`. A deleted document is not an error.

## Alternatives considered

- **Luke's composite-reader access** (`MultiDocValues`, `MultiTerms`). It is simpler, but it builds an `OrdinalMap` per sorted field on every request. Rejected.
- **Repeating the field schema in `getDocument`.** This makes each response self-contained, but it duplicates `getIndexStats` on every document. Rejected.
- **Stored numbers as JSON numbers.** These can't express `NaN` or `Infinity`, and float formatting differs between Java and Rust. Rejected.
- **A tagged union per value type** (Jackson polymorphism ↔ serde enums). This is stricter typing, but `{type, value}` is enough for display and keeps both sides' DTOs plain. Rejected for now.
- **Decoding numeric doc values on the server.** The server can't know the encoding, so it would have to send every interpretation. Rejected.
- **Term vectors inside `getDocument`.** A large text field's term vector, with positions, would be sent even when nobody looks at it. Rejected.

## Consequences

- The TUI needs the `getIndexStats` field list to label a document's fields. It already fetches it on open.
- `TopTerm`'s text-or-hex rule is now the general rule for byte strings, and the server has one helper for it.
- A document with huge stored values makes one large response line. This is acceptable until real indexes show otherwise (decision 6).
