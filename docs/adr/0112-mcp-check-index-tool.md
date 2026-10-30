# ADR 0112 — The MCP check_index tool, and the commit's user data in index_info

- **Status:** Accepted
- **Date:** 2026-10-26
- **Amends:** ADR 0100 (§6, the Tools table: `check_index`, and `index_info` with `indexes`)

## Context

An agent can't ask whether an index is corrupt, and can't see the user data of the commit, such as Elasticsearch's `history_uuid`, `max_seq_no` and `local_checkpoint`. The backend has both: `startCheckIndex` / `getCheckIndex` is a job (ADR 0022, ADR 0093), and `getCommits` returns each commit's user data. Only `leia-mcp` changes.

## Decision

1. **`check_index`** takes `level?` (1 checksums of every file, the default; 2 also every part of each segment; 3 also the slow checks) and `only?`, a list of `{path, segments?}` that maps one to one to `CheckTarget`. `threads` isn't exposed: every processor is right for an agent. A level outside `MIN_LEVEL`..`MAX_LEVEL`, an unknown path, a duplicate and an empty list of segments go to the server, which answers `INVALID_PARAMS`.
2. **Feature checks.** Without `CHECK_INDEX` the tool answers `NOT_SUPPORTED`. A `level` without `CHECK_LEVELS` is `NOT_SUPPORTED` too: the server would check at its one level and say nothing, and an agent that asked for level 3 should not believe it got it.
3. **The job** polls with `GetCheckIndexParams(jobId, lineCount)`, so a poll never resends old lines; progress is the lines of output so far, with no total. The lines themselves are dropped: they are large, and the structured result carries every failure and its cause (`SegmentCheck.error`, `IndexCheck.error`).
4. **The result** is `clean` (every index is), `indexes` and `tookMillis`. An index has `path`, `skipped?`, `clean`, `error?`, `segmentsFile?`, `missingSegments?`, `toolOutOfDate?`, `invalidCounter?` (true only if the counter is invalid), `segmentCount`, `badSegments?` and `lostDocs?` (absent if 0), `failedSegments?` (`name`, `maxDoc`, `codec?`, `error`), `onlySegments?` and `segmentsNotFound?`. Only the segments that failed are listed: an Elasticsearch shard has tens of them, and the ones that passed tell nothing (ADR 0103).
5. **The 5-minute `Jobs.LIMIT`** (ADR 0100 §4) applies: a level 3 check of a large index may not finish. The job is cancelled then, and the message says to narrow the call, now "such as with fewer fields or segments". `only` is the way around it, one segment at a time.
6. **`index_info`** gains `indexCount` and `indexes`, at most 100 (`MAX_INDEXES`), so that an Elasticsearch data folder costs a bounded amount. An index has `path`, `segmentsFile?` (absent without a reader), `luceneVersion?` and `userData?` (sorted by key; absent if empty). The paths are what `check_index` takes in `only`. `open_index` returns the same.
7. **The current commit, not the latest.** The user data comes from `getCommits` for the commit with `current == true`: the one the reader sees, which every other tool reads. A newer commit written after the open is not what the agent is looking at. If the session lacks `COMMITS`, there is no current commit or the call fails, `luceneVersion` and `userData` are absent. `getCommits` reads the `segments_N` files only, so `index_info` stays fast.
8. **`Schemas`** knows `Map<String, V>`, as `{"type":"object","additionalProperties":<V>}`: fewer tokens than a list of `{key, value}`.

## Consequences

- `tools/list` grows by `check_index`'s schema and the new `index_info` result; `tools.json` pins them.
- A demo index checks in about 40 ms and the answer is 170 bytes; `index_info` gains about 250 bytes per index.
- This closes the MCP lane of the task list.
