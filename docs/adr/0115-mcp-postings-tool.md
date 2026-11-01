# ADR 0115 — The MCP postings tool

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amended by:** ADR 0119 (§2, positions come with the postings in one call)
- **Amends:** ADR 0100 (§6, the Tools table: `postings`)

## Context

An agent can list a field's terms (`terms`) and run queries (`search`), but it can't ask which documents hold one exact term, how often, or where. It can't see a term's positions, offsets and payloads without a query or a whole `reconstruct_fields`, and it can't tell that a match comes from a deleted document. The backend has `getPostings` (a page, in document order, deleted documents marked) and `getPositions` (one document's occurrences of a term), so, as with ADR 0106, only `leia-mcp` changes.

## Decision

1. **One tool, `postings`**, a page of `getPostings` (not a job: a page is small and synchronous, like `getTerms`). Arguments: `field`, `term` (text, or hex with `binary?`), `fromDoc?` (0; the `nextDoc` of the last page), `limit?` (50, at most `GetPostingsParams.MAX_LIMIT`) and `positions?` (false). Out-of-range values are `INVALID_PARAMS`.
2. **Positions** are one `getPositions` call per posting, so `limit` is at most 100 with `positions`. A document with more than 100 occurrences returns the first 100 with `positionsCut`; `freq` still gives the full count. Each entry has `position?`, `startOffset?`, `endOffset?` and `payload?` (hex), as the index holds them.
3. **`positions` on a field whose index options hold none** (`DOCS`, `DOCS_AND_FREQS`) is `INVALID_PARAMS`, naming the options, which the tool reads from `getIndexStats`: the agent gets a clear answer, not a page of empty lists. A field that isn't indexed (`NONE`) is left to the server, which answers `TERM_NOT_FOUND`, so that the agent isn't told to drop `positions` for nothing. Without `POSTINGS`, or `POSITIONS` for `positions`, it is `NOT_SUPPORTED`. `FIELD_NOT_FOUND`, `TERM_NOT_FOUND` and `INDEX_NOT_READABLE` come from the server.
4. **Defaults are left out** (ADR 0103): `binary`, `deleted`, `positionsCut`, `freq` where frequencies aren't indexed, and `totalTermFreq` when it equals `docFreq`. The counts include deleted documents, as in `terms`.
5. **No filter.** The filters of the postings screen (ADR 0032) are among the things MCP mode doesn't plan.

## Consequences

- `tools/list` grows by one tool; `tools.json` pins it.
- On the Lucene 3 runtime a deleted document is missing from the postings, though `docFreq` counts it, and `totalTermFreq`, which Lucene 3 doesn't record, is counted over the live documents only, so it can be below `docFreq` (`docs/runtimes.md`).
- With `DOCS_AND_CUSTOM_FREQS` (such as a `FeatureField`), `freq` is a value of the field's own, not a count, and `totalTermFreq` their sum; the descriptions say so.
- Measured on the demo index (compact JSON, the text item), for `body` and `quick`: a page of 50 postings is 1.2 KB, and a page of 100 with positions 16 KB.
