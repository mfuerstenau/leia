# ADR 0005 — Long-running operations as polled server jobs

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

Until now every method was quick: the server handles one request at a time (`RpcServer`), and the TUI blocks in `RpcClient::call` until the response arrives. Counting the unique terms of a field, or finding its top terms by document frequency, needs a full scan of the field's merged `TermsEnum`. On a large index that takes seconds to minutes. As a blocking call, it would freeze the TUI, even for Ctrl+C, with no progress and no way to stop it. Check index, export terms and search on large indexes will have the same problem, so the backlog asked for a cross-cutting decision first. ADR 0001 expected "JSON-RPC notifications" for this.

Constraints:

- The protocol is strictly typed (ADR 0001, ADR 0003), and the TUI client is synchronous: one request, one response.
- Cancelling must not damage the open index. With `NIOFSDirectory`, interrupting a thread that is reading closes the shared `FileChannel` (`ClosedByInterruptException`), which breaks the reader for every later request. `MMapDirectory` is not affected, but `FSDirectory.open` does not always pick it.
- Closing the reader while another thread still reads from it fails that thread with `AlreadyClosedException`. A memory-mapped segment may also be unmapped under it.

## Decision

1. **A long-running operation is a server job, driven by plain request/response methods.** For an operation `<op>`:
   - `start<Op> {…}` validates its params synchronously (so a bad field name fails at once), starts the job and returns `{jobId}`.
   - `get<Op> {jobId}` returns the job's progress while it runs and its typed result once it is done. If the job failed, the response is the job's JSON-RPC error. The final outcome (result or error) is handed out once. After that, the job is forgotten and the id answers `JOB_NOT_FOUND`.
   - `cancelJob {jobId}` stops a job of any kind and forgets it. It is idempotent: an unknown or already finished id is not an error. The result says whether a running job was stopped.

   Polling per operation keeps every result typed by its method (no polymorphic job result on the wire). The first operation is `startFieldTermScan` / `getFieldTermScan`.
2. **No transport change.** No notifications and no concurrent requests: the stdin loop still handles one request at a time, and every request is answered quickly. The TUI keeps its synchronous client. While a job runs, it waits for keys with `event::poll` and a 100 ms timeout instead of `event::read`, and polls the job in between.
3. **Jobs run on their own daemon threads** (`Jobs` in the server, one platform thread per job). Lucene readers are thread-safe, so a job and the request loop can read the same index at the same time.
4. **Cancellation is cooperative.** A job reports progress through a callback (`ScanProgress` in `leia-core`). Every 1024 terms, the callback checks the job's cancelled flag and throws `CancellationException` if it is set. Threads are never interrupted (see the `NIOFSDirectory` constraint above).
5. **Jobs belong to the open index.** `closeIndex`, and an `openIndex` that replaces the index, cancel every job and wait for it to stop (at most 5 s each) before the old reader is closed. Stdin EOF closes the index, so it stops the jobs too.
6. **Error codes:** `JOB_NOT_FOUND` (-32007) for an unknown, cancelled or already collected job id. `FIELD_NOT_FOUND` (-32006) is added for the field term scan.

## Alternatives considered

- **Synchronous methods now, cancellation later.** This is the smallest change, but it freezes the TUI (even Ctrl+C waits). The next long operation would bring the same problem back. Rejected.
- **Asynchronous transport:** progress as JSON-RPC notifications, `$/cancelRequest` as in LSP, a concurrent dispatcher, and a TUI client with a reader thread and a channel. This is the most general option, but it reworks ADR 0001, the envelope DTOs (server-sent notifications), the client and the server loop before any feature lands. Polling every 100 ms costs a few hundred bytes per round trip over a local pipe. Rejected for now. Jobs can move onto notifications later without changing the job model.
- **A generic `getJob` with a polymorphic result** (Jackson `WRAPPER_OBJECT`, serde's externally tagged enums). The two libraries are compatible here, but it would make the result type depend on server state instead of the method, which ADR 0003 avoids. Rejected.
- **`Thread.interrupt` for cancellation.** It breaks `NIOFSDirectory` readers, as described above. Rejected.

## Consequences

- A long-running operation means two methods (`start<Op>` and `get<Op>`) with fixtures, plus a core API that takes a progress callback and checks it regularly.
- The server now has threads besides the request loop. `CurrentIndex` is still touched only by the request loop. Shared job state is published through `volatile` fields and a `CompletableFuture`.
- The TUI tracks at most one running job and polls it. It stops polling when the outcome arrives, and on quit it relies on `closeIndex` to stop the job.
- ADR 0001's expectation of notifications for long-running operations is replaced by this ADR.
