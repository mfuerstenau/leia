# ADR 0106 — The MCP terms tool

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: `terms`)
- **Amended by:** ADR 0116 (§2, a prefix and binary terms)

## Context

An agent can't list a field's terms, yet it needs them to find the values a field holds and to see why a term query misses. The backend has `getTerms` (a page) and `startFieldTermScan` (the top terms, a job), so only `leia-mcp` changes.

## Decision

1. **One tool with two modes**, so that `tools/list` stays small. `field` is needed; with `top`, the tool returns the N terms in the most documents, otherwise a page.
2. **A page** is one `getTerms` call: `prefix?` (text), `from?` (`value`, `binary?`, the `next` of the previous page) and `limit?` (50, at most `GetTermsParams.MAX_LIMIT`). The page starts at `from`, else at `prefix`. `getTerms` knows no prefix, so the tool keeps the leading terms that start with it, and `next` is the server's only while the whole page matched and `next` starts with the prefix too. A binary term never matches a text prefix. *Amended by ADR 0116: a binary term matches if its bytes start with the prefix's UTF-8 bytes.* The comparison is the tool's own, since `ByteStrings` is server-side.
3. **`top`** (at most `StartFieldTermScanParams.MAX_TOP_N`) is a job, run behind one call with progress (ADR 0100 §4). It scans every term, so it takes long on a large field, and it excludes `prefix`, `from` and `limit` (`INVALID_PARAMS`). The result has `uniqueTerms` and no `next`.
4. **Per term `term`, `binary?`, `docFreq` and `totalTermFreq?`**, the last absent when it equals `docFreq` (ADR 0103). The counts include deleted documents, as the terms screen's do.

## Consequences

- `tools/list` grows by one tool; `tools.json` pins it.
- On a runtime without postings, `getTerms` answers `NOT_SUPPORTED`; the tool does not check features itself.
