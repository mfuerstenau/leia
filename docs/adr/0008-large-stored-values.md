# ADR 0008 — Large stored values

- **Status:** Accepted
- **Date:** 2026-10-06
- **Supersedes:** ADR 0006, decision 6
- **Amended by:** ADR 0117 (§5, `getStoredValue` takes an optional slice and reads only its field)

## Context

ADR 0006 sent every stored value whole and left a cap for later. Real indexes do have large stored values. Elasticsearch and OpenSearch store each document's JSON source in `_source`, and other applications store whole files. A value of several MB has the following costs:

- **Every document step.** `←`/`→` fetch a whole document. Every large value is sent again, though the field table shows only 200 characters of it.
- **Rendering.** The details panel wrapped every stored value in full on every frame.
- **The viewer** needs the whole value, but only when someone opens it.

Doc values and term vectors are not the problem. Sorted doc values are limited to 32 KiB, a binary doc value is rarely large, and a term vector is fetched on request.

## Decision

1. **`getDocument` cuts long values.** A `STRING` or `BINARY` stored value longer than `GetDocumentResult.MAX_VALUE_LENGTH` (64 KiB) is cut, and the stored value gets an optional `fullLength`. Its absence means the value is whole. Numbers are never cut.
2. **Lengths are in bytes,** UTF-8 for text. That is how Lucene stores the value, and it is the same unit for text and bytes.
3. **Text is cut between two characters**, so the cut text is still valid UTF-8 and stays text on the wire. A surrogate pair is never split.
4. **Bytes that are not UTF-8 stay hex when cut.** The cut is made after the whole value has been checked. Otherwise the first 64 KiB could happen to be valid UTF-8 and go as text, while the whole value goes as hex.
5. **`getStoredValue {docId, field, index}` sends one stored value whole**, however long. *Amended by ADR 0117: with an optional `offset` and `length` it sends a slice of the value, and reads only that field.* `index` counts the document's stored values in the field, in the order `getDocument` lists them. The core reads only that field's stored values (`StoredFieldVisitor.needsField`). It is synchronous: one value of one document is bounded, so ADR 0005 does not apply.
6. **New error `VALUE_NOT_FOUND` (-32010)** for an `index` outside the document's values in the field. A deleted document is readable, as in ADR 0006.
7. **The cap is a server constant, not a parameter.** Nothing needs another limit yet, and the TUI does not depend on its exact value.

## Alternatives considered

- **Keep sending values whole** and only cap the rendering. This needs no protocol change. But every document step would send megabytes the screen never shows. Rejected.
- **Paging through a value** (`offset`, `length`). It handles values too large for one response. But it makes the viewer stateful and complicates UTF-8 boundaries. Values of a few MB fit in one response easily. Deferred until an index needs it.
- **A cap parameter on `getDocument`.** Rejected for now (decision 7).
- **Counting the cap in characters.** Rejected: the unit would differ between text and bytes, and a UTF-16 length means nothing to the TUI.

## Consequences

- The TUI shows a cut value's beginning and offers the viewer, which calls `getStoredValue` when it shows a cut value.
- Copying (OSC 52) is whole only once the whole value is in. The viewer refuses to copy a cut value.
- A value of hundreds of MB would still be one response line in `getStoredValue`. Paging is the way out if that happens.
