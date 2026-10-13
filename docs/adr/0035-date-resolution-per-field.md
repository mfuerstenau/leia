# ADR 0035 — A date resolution per field

- **Status:** Accepted
- **Date:** 2026-10-11
- **Amends:** ADR 0026 decision 2 (one resolution for every field)

## Context

ADR 0026 gave both query parsers one date resolution for every field, as Luke does, and left Lucene's per-field resolutions for later. An index often has dates at more than one resolution, for example a day next to an hour. One resolution can't read both. With DAY, a range on the hour field becomes `[20260110 TO 20260120]`, and every hour of the last day (`2026012000` to `2026012023`) sorts after the upper bound. With HOUR, a range on the day field compares day terms with hour terms in the same way.

Lucene has a resolution per field in both parsers: `QueryParser.setDateResolution(String, Resolution)` and `StandardQueryParser.setDateResolutionMap`. Both look a field up the same way: its own resolution, otherwise the one for every field, otherwise none, and the bounds stay text. A field can't say "none" while there is one for every field. `DateRangeQueryParser` and `DateRangeQueryNodeProcessor` (ADR 0026) already ask the parser for the field's resolution, so they need no change.

## Decision

1. **`ParserOptions` gains `fieldDateResolutions`**, a required list of `FieldDateResolution {field, resolution}`, which is empty for none. It is required, like `StandardQuery.points`: an empty list already says "none". Both parsers take it.
2. **A field's own resolution wins**, as in Lucene. It works without a resolution for every field. Locale and time zone are still shared by all fields.
3. **The server checks the fields:** `FIELD_NOT_FOUND` for a field the index doesn't have, `INVALID_PARAMS` for a field listed twice, as for the point fields.
4. **TUI:** the parser popup (`p`) has a "Per field" group below the date settings, for both parsers. It has one row per indexed field (index options other than `NONE`), cycling *default → YEAR … MILLISECOND*. `d`, Backspace or Delete set a row back to default. The details panel lists each one that is set as `dates updated:HOUR`.
5. **The demo index has an `updated` field**: the hour of `timestamp` in UTC, as `DateTools` writes it at HOUR (`2026011014`).

## Alternatives considered

- **An optional map** (`Optional<Map<String, DateResolution>>`). Rule 3 forbids untyped maps, and absence would say no more than an empty list does. Rejected.
- **"None" per field** to keep one field's bounds as text while there is a resolution for every field. Lucene can't express it: a missing field falls through to the one for every field. leia would have to change both parsers further. Rejected.
- **Typing field names into the popup** instead of a row per indexed field. This would give a shorter popup, but it would need more keys and an edit mode. The popup already scrolls. Rejected.

## Consequences

- `ParserOptions` (protocol and core) gains a field, and `FieldDateResolution` is a new record. Every caller passes `List.of()` or `Map.of()` when it has none.
- An index with many indexed fields gives a long parser popup.
