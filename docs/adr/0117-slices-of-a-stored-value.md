# ADR 0117 — Slices of a stored value

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR 0008 (§5, `getStoredValue`), ADR 0100 (the row of `get_field_value`), ADR 0116 (the first of its three deferred costs)

## Context

`get_field_value` reads a stored value in slices, but each slice called `getStoredValue`, which read and encoded the whole value: a 50 MB `_source` read in slices of 16K characters took about 3,200 whole reads, which is quadratic. The read was also wider than needed: `RuntimeResults.storedValue` called `Documents.document`, which reads every field's stored values, doc values, norms and vectors, though ADR 0008 §5 had promised the stored values of that field alone.

No Lucene can read part of a stored string: `StoredFieldVisitor.stringField` hands over the whole `String`, and the block is decompressed whole. An offset in the protocol alone would not remove the repeated read.

## Decision

1. **`getStoredValue` takes an optional `offset` and `length`** (ADR 0008 §5). Both count characters of the value as the protocol carries it: UTF-16 chars of a text, hex digits of a binary value in hex, where the offset must be even. Without them the whole value comes, as before. The result has the slice in `value` (its `fullLength` stays empty), the `offset` and the `totalLength` of the whole value.
2. **The slice rule lives in the server**, in `ValueSlices.end`: a slice never splits a surrogate pair or a byte in hex, so it is one character shorter, or one longer where a shorter one would be empty. `Slices.end` of leia-mcp delegates to it. An offset past the end, an odd offset in hex and a negative offset or length are `INVALID_PARAMS`.
3. **Only the field is read:** `Documents.storedValues(docId, docId + 1, {field}, true)`, which every runtime has (ADR 0055). No runtime changes.
4. **The handler keeps the last value it read** in a `SoftReference`, keyed by the `OpenRuntime` (by identity), the document, the field and the index. The slices after the first are cut from it and read nothing. It is one entry: calls run one at a time on the backend thread, so it needs no lock, and `CurrentIndex` is unchanged. The garbage collector may drop a large value under memory pressure.
5. **`get_document` asks for the head** of a value the backend cut (offset 0, length `maxValueLength`), and gets the length in characters without the whole value crossing the backend boundary. The next `get_field_value` of that value is a cache hit. At `FINE` level the log has the slice only.

## Consequences

- `get_field_value` and `tools.json` are unchanged; the TUI calls `getStoredValue` for the whole value, as before.
- A value is read whole once per series of slices, and held in memory until another is read or the GC drops it.
- Left open (`docs/tasklist.md`): `get_document` still reads a cut value whole once, for its length in characters.
