# ADR 0101 — Concurrent tool calls in MCP mode

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§5, threads)

## Context

ADR 0100 §5 runs the tool calls of MCP mode one at a time, on one worker thread `leia-mcp`. An MCP client may send several requests on one connection without waiting for the answers, and a coding agent does so when it runs subagents side by side, which share the session's MCP servers. Then every call waits for the one before it.

The calls that take long are `search` and `reconstruct_fields`. Each runs a backend job (ADR 0005) and polls it until it is done (ADR 0100 §4), for up to 5 minutes. The job runs on a thread of its own. The backend thread of `LeiaBackend` only starts it and answers the polls, which takes milliseconds. So the worker that sleeps between polls holds back the calls behind it, while the backend is idle. Several jobs already read one open index side by side, as in the TUI.

Running the backend on several threads would not help. `CurrentIndex`, the server's `Jobs` registry, `Filters` and `AddedJars` rely on the one thread and would need locks. `CurrentIndex.replace` sets the context class loader of the backend thread, which the job threads inherit (ADR 0090). And the calls that run on the backend thread are short.

## Decision

1. **A pool of workers.** The tool calls run on a fixed pool of `McpServer.MAX_CALLS` (4) platform threads, `leia-mcp-1` to `leia-mcp-4`. A call beyond that waits in the queue, as every call waited before. The bound keeps the jobs that run at once, and the results they hold, in check. Their calls of the backend take turns on its thread, and their jobs run side by side.
2. **`open_index` runs alone.** It replaces the open index, which stops every job (`jobs.cancelAll()`), so a call that polls one would fail with `JOB_NOT_FOUND`. A tool says so with `McpTool.exclusive()`, which only `open_index` returns `true` for. Each call takes a fair `ReentrantReadWriteLock`: the write lock for an exclusive tool, the read lock for any other. `open_index` waits until the calls that run have ended, and the calls after it wait for it. Fairness keeps a stream of searches from holding it back.
3. **A call cancelled while it waits for the lock** starts nothing and gets no answer, as one cancelled in the queue.
4. **Nothing else changes.** `LeiaBackend` keeps its one thread. The answers on stdout were synchronized already, and cancellation and the end of stdin work per request: at the end of stdin every request is marked cancelled, and the pool is shut down and awaited, without interrupting a thread (hard rule 7).

## Consequences

- Parallel subagents get their answers as their own jobs end, not after the calls before them.
- Answers can come in another order than the requests, which JSON-RPC allows.
- An `open_index` sent while a long search runs waits for it, up to the search's 5 minutes.

## Alternatives considered

- **A multithreaded `LeiaBackend`.** Locks in every holder of state, and a context class loader per thread, for calls that are short already. Rejected; see the context.
- **`open_index` cancels the calls that run, and they answer with an error.** That frees the index at once, but it needs a third outcome for a call besides an answer and a cancellation, and agents rarely open an index while they search. Waiting is simpler. Rejected for now.
- **A thread per call, unbounded.** Virtual threads would do, but nothing would bound the jobs on the index. Rejected.
