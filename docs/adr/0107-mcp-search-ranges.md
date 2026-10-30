# ADR 0107 — Ranges in MCP search

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: `search`, `index_info`), ADR 0104

## Context

`search` used the classic parser, which makes no point queries: `price:[10 TO 20]` on a point field found nothing, silently. Fields with doc values only, such as `scores` or Elasticsearch's `index: false` fields, couldn't be searched at all. The backend has `StandardQuery` with `PointField`, and `DocValuesQuery`; only `leia-mcp` changes.

## Decision

1. **The standard parser, by feature.** `query` goes through `StandardQuery` whenever the open index's runtime has `STANDARD_SEARCH`, and through `ClassicQuery` otherwise (Lucene 3). The options are the same Lucene defaults as before.
2. **Point fields are guessed by width**, as the search screen does (`ParserSettings.of`): every one-dimensional field with 4 bytes is INT, with 8 bytes LONG, from one `getIndexStats` call, made only with the `POINTS` feature. `pointTypes?` (`field`, `type`) replaces the guess for its fields and may name FLOAT or DOUBLE. The server checks that a type fits its field's width (`INVALID_PARAMS`, `FIELD_NOT_FOUND`). `pointTypes` without `query` is invalid, and so is it where the parser is the classic one.
3. **`docValues?`** is a third alternative beside `query` and `term`: `field`, `number?`, one of `values?`, `range?` (`lower?`, `upper?`, `includeLower?`, `includeUpper?`, both true by default) and `prefix?`, and `binary?` for values in hex. It maps to `DocValuesQuery`. Without `number`, a field whose `docValuesType` is NUMERIC or SORTED_NUMERIC gets LONG. The server checks that exactly one of values, range and prefix is given, and that the field has doc values. `analyzer` and `fieldAnalyzers` are refused with it, as with `term`.
4. **`index_info` gets `pointBytes?`** per field (absent without points), so that an agent can tell which point fields may be FLOAT or DOUBLE.
5. **Dates.** A date on a point field is epoch milliseconds; a date indexed as text is written as `DateTools` wrote it (`published:[20260131 TO 20260228]`). There is no `dateResolution` argument: Lucene reads such bounds in the locale's SHORT format (ADR 0026), which an agent can't use well, and a range over the text itself already matches.

## Consequences

- Searches that went through the classic parser now go through the standard one on Lucene 5 and later. The differences are `splitOnWhitespace`, `autoGeneratePhraseQueries` and fuzzy edits.
- A FLOAT or DOUBLE field searched without `pointTypes` finds nothing, rather than failing. The `query` description and `pointBytes` say so.
- `tools/list` grows by the `docValues` and `pointTypes` records; `tools.json` pins them.
