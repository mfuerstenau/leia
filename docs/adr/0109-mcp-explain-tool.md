# ADR 0109 — The MCP explain tool

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: `explain`)

## Context

An agent that finds a document ranked too low, or missing from a search, couldn't ask why. The backend answers already: `startExplain` / `getExplain` take the search's query, a similarity and a document id, and return Lucene's explanation tree. The server checks the id (`DOC_NOT_FOUND`), parses the query as `startSearch` does, and answers `NOT_SUPPORTED` without `EXPLAIN` or a search. Only `leia-mcp` changes.

## Decision

1. **The arguments are `search`'s query arguments**: `query`, `term` or `docValues` (exactly one), `pointTypes?`, `defaultField`, `analyzer?`, `fieldAnalyzers?` and `defaultOperator?`, so that a call carries over from `search`. `docId` (the hit's id) is required. `sort`, `fields`, `maxValueLength`, `offset`, `limit` and `exactTotalHits` don't apply. The code that makes the query moved from `SearchTool` to `Queries`, and both tools' `Arguments` records implement `Queries.Arguments`.
2. **BM25(1.2, 0.75), the constant of `search`**, so the score is the hit's. A runtime without `SCORING` uses its default; the server handles that.
3. **The explanation is text**, in Lucene's `Explanation.toString` form: one line per node, `value = description`, indented two spaces per level. Nested JSON would cost more tokens, and an agent reads indentation better than nested brackets. `matches` (the root's `match`) and `score?` (its value, present only if it matches) are fields of their own.
4. **`maxLines?`** (200, at most 10,000) cuts the text at whole lines, and `cut?` is true only then; a call with a higher `maxLines` has the rest.
5. **A document that doesn't match** carries Lucene's own reason, such as `no matching term`. Lucene leaves out the `SHOULD` clauses that don't match, so the text stays short.
6. **A job** (`START_EXPLAIN`, `GET_EXPLAIN`) without a progress counter.

## Consequences

- `tools/list` grows by `explain`'s schema, about the size of `search`'s query arguments; `tools.json` pins it.
- `search` is unchanged in `tools.json`: the schema inlines the moved records.
