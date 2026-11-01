# ADR 0118 — The length in characters of a cut value

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR 0008 (§2, `fullLength`), ADR 0117 (§5, what `get_document` asks for)

## Context

`getDocument` cuts a stored value over 64 KiB (ADR 0008) and gives its `fullLength` in bytes of UTF-8, the unit the TUI shows. `get_document` of MCP mode reports a cut value's length in characters, the unit of `get_field_value`'s `offset` and `length`. ADR 0117 §5 had it call `getStoredValue` for the head of every cut value, which read the field again and turned the whole value into a `String` only for its length. The backend holds the whole value when it cuts, so the length costs nothing there.

## Decision

1. **`StoredValue` gets `OptionalLong fullChars`,** present exactly when `fullLength` is: the length of the whole value in the characters the protocol carries, UTF-16 chars of a text and hex digits of a binary value in hex. That is the unit of `getStoredValue`'s `offset`, `length` and `totalLength`. It is an `OptionalLong` because hex digits are twice the bytes. The constructor of a value that isn't cut passes two empties; none takes only one of the two.
2. **`GetDocumentHandler.storedValue` fills it:** `text.length()` for a text, and `2 × bytes.length` for a binary value cut in hex. Hits of `search` go through the same method.
3. **`get_document` cuts the backend's head itself** to `maxValueLength` and takes `fullChars` as the length, with no call. It calls `getStoredValue` only when the head is shorter than `maxValueLength`: a 64 KiB head holds at least 21,845 characters of text and 131,072 hex digits, so that happens only for text that isn't ASCII with a `maxValueLength` above about 21K. The default of 2048 never needs it.

## Consequences

- `tools.json` is unchanged (`OptionalInt` and `OptionalLong` are both `integer`); `Value.fullLength` of `get_document` is a `long`.
- The TUI reads `fullLength` only and is unchanged.
- A `get_document` with a cut value no longer reads the field a second time, nor fills the slice cache of ADR 0117.
