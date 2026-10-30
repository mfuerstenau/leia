# ADR 0108 — Sort in MCP search

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: `search`)

## Context

`search` always returned the best score first, so an agent couldn't ask for the most expensive lamps, the newest documents or the order of document ids. The backend sorts already (`StartSearchParams.sort`, a list of `SortKey`), and checks each key against the field's doc values. Only `leia-mcp` changes.

## Decision

1. **`sort?`** is a list of keys, applied one after the other: `field`, `type?`, `reverse?`, `selector?`, `missing?`.
2. **`_score` and `_doc` are reserved names** for the score and the document id, as in Elasticsearch, in place of a three-way alternative record. `type`, `selector` or `missing` with them is `INVALID_PARAMS`. `reverse` on `_score` means the lowest first.
3. **The type is guessed** from `getIndexStats`'s `docValuesType`, as the search screen does: SORTED and SORTED_SET give STRING, everything else LONG. `type` replaces the guess, and is needed for DOUBLE and FLOAT fields (`get_document`'s `asStored` says which). For a field that is missing, or has BINARY doc values, the server answers `FIELD_NOT_FOUND` or `INVALID_PARAMS`. One `getIndexStats` call serves the sort, the point fields and the doc values query (`Stats`), made only if one needs it.
4. **`missing` is LAST** by default, not Lucene's DEFAULT, which sorts a missing number as 0: LAST is Elasticsearch's default and what an agent expects. `selector` is MIN; the server checks that it fits the doc values.
5. **`sortValues?`** per hit, one `value?` (absent for a document without one) and `binary?` per key, from `SortValue.number` or `term`; absent without `sort`. `score` stays the BM25 score.
6. **`NOT_SUPPORTED`** without `SCORING` comes from the server, as it does for the search screen.

## Consequences

- `tools/list` grows by the `sort` and `SortValue` records; `tools.json` pins them.
- A hit with `sortValues` costs a few tokens more per key.
