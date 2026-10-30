# ADR 0105 — The MCP analyze tool

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: `analyze`)

## Context

Since ADR 0104 an agent can name an analyzer for `search`. What it can't see is what that analyzer makes of a text, for example why `id:doc-0042` doesn't match with `StandardAnalyzer`. The backend's `analyze` method does that already, so only `leia-mcp` changes.

## Decision

1. **Arguments `text`, `analyzer?` and `maxTokens?`.** `analyzer` is a simple or class name as in `search`, `StandardAnalyzer` by default. Lucene doesn't record a field's analyzer, so for a field the agent names that field's analyzer: there is no `field` and no `fieldAnalyzers`. `text` is at most 10,000 characters (`AnalyzeParams.MAX_TEXT_LENGTH`), which the backend checks (`INVALID_PARAMS`).
2. **The name resolution is shared with `search`** (`tools/Analyzers.java`, ADR 0104): the same lookup through `getAnalysisComponents`, the same errors.
3. **One stage only.** A standard or preset analyzer gives one `TokenStage`, which is the result; custom analyzers aren't nameable from MCP, so there are no char filters or filter stages.
4. **Per token `term`, `position`, `startOffset`, `endOffset`, `type` and `positionLength?`** (absent if 1, ADR 0103). The token attributes and `positionIncrement` are left out: positions show the gaps.
5. **`maxTokens`** is 200 by default, at most 10,000 (`AnalyzeResult.MAX_TOKENS`). `tokenCount` is the tokens the text makes, counted up to 10,000, and `cut` is present and true only if tokens are left out.
6. **Lucene 10 only:** the other runtimes have no analysis, so the call fails with `NOT_SUPPORTED`. It needs no open index.

## Consequences

- `tools/list` grows by one tool; `tools.json` pins it.
- An agent can compare the tokens of the query with those of a document's field (`reconstruct_fields`) to see why they don't match.
