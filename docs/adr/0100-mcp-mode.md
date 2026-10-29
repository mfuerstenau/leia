# ADR 0100 — MCP mode

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0034 (§7, where stdout and stderr go); AGENTS.md, hard rules 2 and 4 and the CLI contract
- **Amended by:** ADR 0101 (§5, concurrent tool calls), ADR 0102 (§7 and §8, the indexes `open_index` may open), ADR 0103 (§6, the size of the results)

## Context

A coding agent that works on code using Lucene, Elasticsearch or OpenSearch often needs to look into an index: what a query finds, what a document holds, how a field was indexed. leia knows all of that, but only through a terminal UI that an agent can't drive. The Model Context Protocol (MCP) is how agents call tools. A client starts the tool's process and speaks JSON-RPC 2.0 with it over stdin and stdout.

`leia --mcp [INDEX_PATH]` serves an index that way, instead of starting the TUI. It goes through the same `LeiaBackend.call` and protocol records as the UI does, so it is read-only like the rest of leia, and it opens every index the TUI opens. The first tools search with paging, get a document by id, and get a document's whole field values, including the fields that aren't stored, which leia rebuilds from the postings.

## Decision

1. **Transport.** leia speaks MCP itself, without an SDK. That needs no new dependency, since Jackson 3 is in `leia-tui` already, and no change to the verification metadata (ADR 0089).
   - stdin and stdout carry one JSON object per line, in UTF-8. stdout carries protocol messages only.
   - `System.out` and `System.err` go into `leia.log` from the moment the log is open. That is earlier than in the TUI: before `refreshOwnJars` and before the backend starts.
   - leia handles `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call` and `notifications/cancelled`. Any other request gets `-32601`, and any other notification is ignored.
   - Invalid JSON gets `-32700`, and a message that isn't a JSON-RPC 2.0 request gets `-32600`. leia doesn't take batches.
   - leia knows the protocol versions `2025-03-26`, `2025-06-18` and `2025-11-25`. It echoes the client's version if it knows it, and otherwise answers with the latest.
   - Its capabilities are `{tools: {listChanged: false}}`. `serverInfo` has the name `leia` and the version that `Methods.VERSION` gives.
2. **Typed messages.**
   - The envelope reads a line as a Jackson tree only to tell requests from notifications and to keep the `id`, which may be a number or a string.
   - Every tool's arguments and result are records in `leia-mcp`. They are bound with `FAIL_ON_UNKNOWN_PROPERTIES` and `NON_ABSENT`, as in `DocumentExport`'s mapper.
   - Each tool's `inputSchema` and `outputSchema` are generated from its records, with `additionalProperties: false`. A component is required exactly when it isn't `Optional*`, as hard rule 3 has it, and leia checks that before it binds the arguments. A `@Description` annotation on the components gives the descriptions.
   - A golden `tools.json` pins the answer to `tools/list`, as `document-export.json` pins the export.
3. **Results.**
   - `structuredContent` holds the result record. One `text` content item holds the same JSON, for clients that don't read structured content.
   - An `RpcException` becomes a result with `isError: true` and the text `message (CODE)`, so that the agent can correct itself. Bad arguments are handled the same way.
   - An unknown tool gets the JSON-RPC error `-32602`.
4. **One call per job.**
   - A tool over a job (`search`, `reconstruct_fields`) calls `start<Op>`, then polls `get<Op>` as the TUI does: 10 polls at 10 ms, then one every 100 ms.
   - If the request has a `_meta.progressToken`, leia sends `notifications/progress` with what the job has scanned, and the total if it knows it.
   - The job is cancelled with `cancelJob` on a `notifications/cancelled` for its request, after 5 minutes, and on stdin EOF. Cancellation stays cooperative (hard rule 7).
   - A cancelled request gets no answer, as MCP has it. One past the limit gets `isError`.
5. **Threads.**
   - The main thread reads stdin, and handles `notifications/cancelled` itself.
   - Requests run one at a time, in order, on one worker thread `leia-mcp`. The backend serializes its calls anyway. *Amended by ADR 0101: up to four tool calls run at a time, on a pool of workers, and `open_index` alone; the backend keeps its one thread.*
   - Writes to stdout are synchronized, and flushed after each line.
   - On stdin EOF, the job of the request that runs is cancelled, the requests that wait are dropped, the backend is closed, and leia exits with 0.
6. **Limits.** Every tool bounds its output, by the limits below. Each is a named constant in its tool's class. A value over a maximum is refused with `isError`, not cut down silently.
7. **Index.**
   - INDEX_PATH is opened before the loop starts. If that fails, leia prints the message to stderr and exits with 1, since the user named the path.
   - `open_index` replaces the open index. If opening fails, the old index stays open, as the backend has it.
   - Without an index, a tool answers `NO_INDEX_OPEN`, with a hint to call `open_index`.
   - A leading `~` in a path is expanded, as in the open prompt.
   - An index is always opened with a reader.
8. **CLI.**
   - `--mcp` works with `--jar`, `--server`, `--no-default-jars` and `--directory`, whose `Directory` is `open_index`'s default.
   - It is refused (exit 2) with `--download-runtimes`, `--runtime`, `--theme` and `--no-reader`. `--no-reader` is refused because no tool works without a reader.
9. **Not in this ADR:**
   - sort, similarity, highlighting, the standard parser, More-Like-This, doc-values and KNN queries;
   - term vectors and payloads;
   - resources and prompts;
   - concurrent requests.

   Each can come later without a change to the transport.

## Tools

The records are in `io.github.mfuerstenau.leia.mcp.tools`, one class per tool.

| Tool | Arguments | Backend calls | Result |
|---|---|---|---|
| `open_index` | `path`, `directory?` (`FS`, `MMAP`, `NIO`; default from `--directory`) | `openIndex`, then `getIndexStats` | as `index_info` |
| `index_info` | none | `getIndexStats`, with the last `OpenIndexResult` | `path`, `luceneRuntime`, `server?`, `numDocs`, `maxDoc`, `segmentCount`, `skipped`, `features`, and the `fields` with `name`, `indexOptions`, `docValuesType?`, `pointDimensionCount?`, `vectorDimension?`, `hasTermVectors?`, `docCount?` *(defaults left out, ADR 0103)*. The index doesn't record which fields are stored. |
| `search` | exactly one of `query` (the classic syntax) and `term` (`field`, `value`, `binary?`: exact and not analyzed, for keyword fields); `defaultField` (needed with `query`), `defaultOperator?` (`OR`), `fields?` (as many stored fields as fit a budget per hit, ADR 0103), `maxValueLength?` (100), `offset?` (0), `limit?` (10, at most 100), `exactTotalHits?` (false) | `startSearch` with Lucene's defaults of the classic parser, `StandardAnalyzer` and BM25, polled `getSearch` | `query`, `rewrittenQuery`, `totalHits`, `totalHitsRelation`, `offset`, `nextOffset?`, `tookMillis`, and the `hits` with `docId`, `score`, their `fields`, each value with `type?`, `value`, `binary?` and `cut?`, and `moreFields?` *(ADR 0103)* |
| `get_document` | `docId`, `fields?`, `maxValueLength?` (2,048 characters, at most 65,536), `withVectors?` (false) | `getDocument`, and `getStoredValue` for the length of a value the backend cut | `docId`, `deleted`, and the `fields` the document has something in, with `norm?`, `stored?` (`type?`, `value`, `binary?`, `fullLength?` when cut), `docValues?` (at most 100 entries, and their count), `hasTermVector?`, `vector?` (its dimension, and its components only with `withVectors`) |
| `get_field_value` | `docId`, `field`, `index?` (0), `offset?` (0), `length?` (16,384, at most 65,536) | `getStoredValue` | `field`, `index`, `type`, `binary`, `value` (the slice), `offset`, `totalLength`, `nextOffset?` |
| `reconstruct_fields` | `docId`, `fields?` (every field with postings or points), `maxTerms?` (500 per field, at most 10,000), `withTerms?` (false) | `startReconstruct`, polled `getReconstruct` | per field `field`, `source` (`TERM_VECTOR` or `POSTINGS`), `text?`, `textCut?`, the `terms?` (`term`, `binary?`, `freq`, `positions?`; only with `withTerms` or without `text`), `termCount`, `cut?`, the `points?` (hex, at most 100) and `pointCount?`; `tookMillis` |

Lengths and offsets of values count the characters of `value`, as Java's `String` has them, and never split a surrogate pair. A binary value that isn't UTF-8 is lowercase hex, and its slices keep whole bytes. `text` is the terms in position order, the terms at one position joined with `|`, a gap of positions shown as `…`; it is present only when the terms have positions, and cut at 65,536 characters.

## Alternatives considered

- **The MCP Java SDK.** It brings Reactor, its own Jackson and a servlet or HTTP layer that stdio doesn't need, all of it into `leia.jar` and the verification metadata. The part leia needs, newline-delimited JSON-RPC with five methods, is small. Rejected.
- **The code in `leia-tui`.** It would make the TUI module larger and mix two front ends. A module of its own, `leia-mcp`, depends on `leia-server`, `leia-protocol` and Jackson, and `Main` hands `--mcp` over to it.
- **The protocol records as the tools' arguments and results.** They are complete, but large: a search's params have a dozen nested records, and a document's result has every field of the index. An agent works better with fewer, bounded fields, and the tool records can change without a change to the protocol.
- **A job id in the result, polled by the agent.** It would give the agent work that leia does better, and MCP has progress and cancellation for long requests. Rejected.
- **The open index only from INDEX_PATH.** An agent often learns the path during its work. `open_index` lets it open one, and INDEX_PATH saves the step when the user knows it.
- **HTTP transport.** Clients start a local tool over stdio, and leia is one process per user. It can come later.
