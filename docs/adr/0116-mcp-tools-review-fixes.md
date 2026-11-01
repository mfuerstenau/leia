# ADR 0116 — Fixes to the MCP tools from a review

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amended by:** ADR 0117 (the first of the deferred costs, `getStoredValue` with an offset and a length), ADR 0119 (the last, `postings` with positions in one call)
- **Amends:** ADR 0100 (§2, the check of the required arguments), ADR 0106 (§2, a prefix and binary terms), ADR 0110 (§2, when the scan stops), ADR 0112 (§7, where the user data comes from)

## Context

A review of every MCP tool found three bugs and a needless cost, each from a decision of an earlier ADR or a gap in it:

- Required components were checked in nested objects only. Jackson binds a missing component of a list item, and a `null` item, as `null`, so `search` with `sort: [{}]` threw a `NullPointerException`, which the agent saw as `INTERNAL_ERROR` instead of `INVALID_PARAMS`. `fieldAnalyzers`, `check_index`'s `only` and `get_document`'s `fields: [null]` failed the same way.
- `scan_stored` stopped the scan once it held `offset + limit + 1` hits. The scan passes on `StartStoredScanParams.MAX_HITS` hits only and goes on counting, so a page that ends at `MAX_HITS` never stopped it, and the scan ran to the end of the index, up to the 5-minute limit of a job.
- `terms` with a prefix ended the page at the first binary term, though in term order a binary term whose bytes start with the prefix's comes among the terms with the prefix (`ab` then `0x80` before `abé`). The page was cut and `next` left out, so the terms after it couldn't be reached.
- `index_info` and `open_index` called `getCommits` once per index directory, up to 100 times, though `getIndexStats`, which they call anyway, has the user data and Lucene version of the commit each reader opened.

## Decision

1. **The check of the arguments goes into lists and maps** (ADR 0100 §2): a `null` item of a list or value of a map is missing, as `list[0]` or `map.key`, and so is a required component of a record in one, as `list[0].field`. The answer is `Missing argument …` with `INVALID_PARAMS`, as for a component missing at the top.
2. **A page that ends at `MAX_HITS` stops the scan once it is full** (ADR 0110 §2): no hit more can come, so there is no next page to tell of. `stopped` is then true, and the counts cover `scannedDocs` only, as for any stopped scan.
3. **A binary term has the prefix if its bytes start with the prefix's UTF-8 bytes** (ADR 0106 §2), compared in lowercase hex, and is listed with `binary`. ADR 0106 had it never match; listing it keeps the page whole and tells the agent of a term it would otherwise not see.
4. **The user data comes from `getIndexStats`** (ADR 0112 §7): `IndexCommitStats` of the index directory, by its path, gives `luceneVersion` and `userData` of the commit its reader opened, which is the current one. Without a reader both are absent, as before; `COMMITS` is no longer needed.

## Consequences

- `tools.json` changes in two descriptions: `terms`' `prefix` and `index_info`'s `luceneVersion`.
- `ToolException` carries its `ErrorCode`, so that `Analyzers` falls back to simple names on `NOT_SUPPORTED` without parsing the message.
- Three costs need a protocol change and stay in `docs/tasklist.md`: `getStoredValue` without an offset and length, so that `get_field_value` reads the whole value for every slice and `get_document` reads a cut value whole for its length; and `postings` with one `getPositions` call per posting (ADR 0115).
