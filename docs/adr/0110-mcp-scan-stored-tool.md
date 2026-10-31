# ADR 0110 — The MCP scan_stored tool

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: `scan_stored`)
- **Amended by:** ADR 0116 (§2, when the scan stops)

## Context

An agent can search only what is indexed. A field that is stored but not indexed (`_source`, `note`, `json`, …), or a value that analysis changed beyond recognition, can't be found by `search`. The backend scans every stored value for a Java regular expression already: `startStoredScan` / `getStoredScan` (ADR 0027) is a job whose hits carry a snippet around the first match. Every runtime has `STORED_SCAN`, so the server's own errors are enough. Only `leia-mcp` changes.

## Decision

1. **The arguments**: `pattern` (a Java regex, found anywhere in a value, flags inline), `caseInsensitive?`, `fields?` (all stored fields without it), `includeDeleted?`, `offset?` (0) and `limit?` (20, at most 100). `offset + limit` is at most `StartStoredScanParams.MAX_HITS` (10,000), which the scan keeps.
2. **The scan stops once the page is full.** The tool polls with the number of hits it has, and cancels the job as soon as it holds `offset + limit + 1` hits: the extra hit tells that a next page exists. *Amended by ADR 0116: a page that ends at `MAX_HITS` stops the scan once it is full, since no hit more comes.* A page costs a scan up to its last hit, not the whole index; paging rescans from document 0, since the hits come in document order and the backend keeps no cursor. `nextOffset?` is `offset + limit` when more hits exist, whether the scan stopped or ran to its end.
3. **Counts cover what was scanned.** `scannedDocs` and `matchedValues` always say how far the scan went. `stopped?` is true only if the scan was stopped, and then `scannedDocs` is a lower bound, since the scan reports its progress every 64 documents and the tool counts at least up to the last hit; `matchedDocs?` and `timedOutValues?` (absent if 0) come from the backend's outcome and so only after a full scan. The values that timed out (matching took more than 1 s) aren't named, since a count is enough to tell the agent to simplify the pattern.
4. **A hit** is `docId`, `deleted?`, `field`, `valueIndex?` (absent if 0, as `get_field_value` takes it), `length` (bytes), `matchCount?` (absent if 1), `snippet` (about 40 characters before the first match to 160 after) and `match`, the first match's text. The backend's spans are UTF-8 byte offsets; the tool converts them, relative to the snippet's start, to a slice of the snippet, and cuts at its end if the match runs past it. Byte offsets stay out of the result.
5. **`Jobs` and `Call`** poll with params of their own (`getStoredScan` takes `fromHit`) and may take an outcome from a poll that isn't finished, which cancels the job. The existing `job(...)` delegates to the new overload.
6. **Errors** come from the backend through `ToolError.of`: `INVALID_QUERY` for a bad regex, `NO_INDEX_OPEN`, and the server's message for an unknown field.

## Consequences

- `tools/list` grows by `scan_stored`'s schema; `tools.json` pins it.
- A page deep into a large index costs a scan up to its hits. `fields` narrows it, and the description says so.
