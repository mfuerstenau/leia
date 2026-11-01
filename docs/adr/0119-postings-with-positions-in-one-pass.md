# ADR 0119 — Postings with positions in one pass

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR 0115 (§2, how `postings` reads positions), ADR 0116 (the deferred cost of one `getPositions` call per posting)

## Context

The MCP `postings` tool with `positions: true` made one `getPostings` call and then one `getPositions` call per posting: up to 101 backend round trips for a page of 100. Each `getPositions` also seeked the term again in the document's segment and advanced to the document. It was the last protocol-level cost that ADR 0116 left open.

## Decision

1. **`getPostings` reads the positions in the same pass.** `GetPostingsParams.maxPositions?` (1 to `MAX_POSITIONS`, 1000; otherwise `INVALID_PARAMS`) makes each `Posting` carry `positions?`, its first `maxPositions` occurrences with offsets and payloads as far as they were indexed. `positions` is present exactly when `maxPositions` was given, and an empty list where the field has no positions, as `getPositions` has it. A list shorter than `freq` was cut, so there is no flag for it. `getPositions` stays, for the TUI's one document.
2. **Runtime interface 26** (`LuceneRuntime.API_VERSION`): `Terms.postings(field, term, fromDoc, limit, maxPositions)`, where 0 means none, and `Terms.Posting.positions`. A runtime opens its postings enum with positions, offsets and payloads only when asked, and reads `min(freq, maxPositions)` occurrences before the next document. Lucene 3.6 opens `termPositions` instead of `termDocs`, for a field indexed with positions. leia-core's `ReadOnlyIndex.postings` takes `maxPositions` too and `PostingsPage.Posting` has `positions`.
3. **The filter path** (`onlyMatches`, ADR 0056) passes `maxPositions` on to every page of the runtime it leapfrogs through, so the documents it reads and drops cost their positions too; the pages are small (`limit + 1`).
4. **`postings` asks for `MAX_POSITIONS + 1` (101) positions** and returns 100 with `positionsCut` where it got 101. That is the cut rule the tool had, and it doesn't depend on `freq`, which is a value of its own with `DOCS_AND_CUSTOM_FREQS`. The tool makes one `getPostings` call per page. `limit` stays at most 100 with positions, now for the size of the output (16 KB, ADR 0115), not the number of calls. Neither `tools.json` nor the tool's output changes.

## Consequences

- Old adapters are refused as outdated (ADR 0098), and leia rewrites them at startup.
- No new feature: `POSITIONS` is listed exactly when `terms()` is present. No new row in `docs/runtimes.md`: Lucene 3 still has no offsets and doesn't read deleted documents.
- A page with positions is one pass over the postings, not one seek per document.
