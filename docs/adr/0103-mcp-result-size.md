# ADR 0103 — The size of MCP results

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: the results of `index_info`, `search`, `get_document` and `reconstruct_fields`)

## Context

Every result of a tool costs the agent tokens, and most of what the tools returned was the same on every call. A default `search` of 10 hits on the demo index was 45.7 KB, about 13k tokens, nearly all of it stored values the agent had not asked for: a hit showed every stored field, each value up to the backend's 256 bytes. `reconstruct_fields` listed every term with its positions after `text` had already given the same terms in order. And `type: "STRING"`, `binary: false`, `cut: false`, `hasTermVector: false`, `pointDimensionCount: 0` and the like repeated on every value and field.

Only `leia-mcp` changes. The protocol, the server and the runtimes stay as they are.

## Decision

1. **Defaults are left out.** A component that holds the default of its type is `Optional*`, and absent when it has it (hard rule 3; `Messages.MAPPER` leaves an empty `Optional` out, and `Schemas` doesn't require it). The component's description says what its absence means. `Absent` holds the conversions.
   - A value's `type` (absent: `STRING`), `binary` and `cut`, in `search` and `get_document`; `fullLength` was optional already.
   - `get_document`: `stored`, `hasTermVector`, and a doc values entry's `numbers` and `values`, when empty or false; `Bytes.binary`.
   - `index_info`: `docValuesType` (absent: `NONE`), `pointDimensionCount` and `vectorDimension` (absent: 0), `hasTermVectors`. `indexOptions` stays, since it says whether the field is indexed at all.
   - `reconstruct_fields`: `textCut`, `cut` and a term's `binary` only when true, `points` and `pointCount` without points, a term's `positions` without positions.
2. **`search` shows brief values.** Hits are for finding documents; `get_document` and `get_field_value` are for reading them.
   - `maxValueLength` (from 1 to 256, default 100) cuts every value, with `Slices.head`, which keeps whole bytes of hex and never splits a surrogate pair. `cut` is true if either the backend or this cut shortened the value.
   - Without `fields`, a hit has a budget `HIT_BUDGET` of 250 characters of values. Fields are taken whole, in the order the hit has them, while the sum of the value lengths so far is under it; the value that crosses it stays, so a hit always shows a field. The fields that don't fit are named in `moreFields`, absent if none was left out.
   - With `fields`, there is no budget: the agent asked for those fields.
3. **`reconstruct_fields` without the terms that `text` repeats.** `terms` is absent where `text` is present, unless `withTerms` is true. A field without positions has no `text`, so its `terms` stay. `termCount` stays. `maxTerms` limits `terms`, and `cut` is set only where `terms` is present.

## Measured

On the demo index, as compact JSON of the `text` item over stdio:

| Call | Before | After |
|---|---|---|
| `search` `lamp` in `title`, 10 hits | 45.7 KB | 11.2 KB |
| `get_document` 42 | 12.4 KB | 10.6 KB |
| `index_info` | 5.7 KB | 2.9 KB |
| `reconstruct_fields` 42, every field | 61.7 KB | 29.6 KB |
| `tools/list` | 18.8 KB | 19.8 KB |

The default search is about 3k tokens. `get_document` 42 and `reconstruct_fields` 42 are large because document 42 is the demo index's big one (a 200,000-byte blob, a 64 KiB source, many terms); the other documents are much smaller. `tools/list` grows by the descriptions of the new arguments and of what absence means.

## Consequences

- A client that read `type`, `binary` or `cut` as always there must treat their absence as the default. The output schemas say which components are optional.
- An agent that wants a hit's other fields names them in `fields`, or reads the document.
- `Hit.moreFields` and `withTerms` are new in the schemas; `tools.json` pins them.

## Not in this ADR

- Decoding numeric doc values, and checking whether the model gets both `structuredContent` and the `text` item: later items of the task list.
