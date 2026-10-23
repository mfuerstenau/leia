# ADR 0022 — Check index and export terms

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

Luke's tools tab has two read-only tools (`docs/luke-features.md` §4.7):

- **Check index** runs Lucene's `CheckIndex` on the index and streams its output. The outcome is OK or BAD: missing segments, the number of bad segments, the documents that would be lost, or a tool that is older than the index. Luke also offers "Try to repair" (`exorciseIndex`), which drops broken segments. That writes, so it is out of scope (rule 1).
- **Export terms** writes the terms of a field to `terms_<field>_<timestamp>.out`, one `term<delimiter>docFreq` per line, with a comma, space or tab as the delimiter.

Both can take long on a large index, so both are jobs (rule 7, ADR 0005). Check index is the one tool that works without a reader: ADR 0021 opened corrupt indexes without one for this.

What `CheckIndex` (Lucene 10.5) does that matters here:

- `new CheckIndex(dir)` asks the directory for the write lock. leia's directories use `NoLockFactory` (ADR 0020), so that lock is a no-op and no `write.lock` is created. Without `exorciseIndex`, `CheckIndex` writes nothing.
- It checks the **latest** commit of the directory (and only tries to read the older `segments_N` files), with its own `SegmentReader`s. It never needs an `IndexReader` from the caller, so it works for the indexes that a reader can't open. It reads commits without a minimum version, so with `lucene-backward-codecs` it even checks a Lucene 8 index, which a reader refuses.
- It has levels: 1 checks the checksums of every file (Lucene's default), 2 adds the integrity checks of every part of each segment (postings, stored fields, doc values, points, vectors, …), 3 adds slow checks.
- It writes its report to an `infoStream`. With more than one thread, it checks segments in parallel on an executor, buffers each segment's report, and writes it out when that segment is done.
- It can't be cancelled. Each test of a segment catches `Throwable`, records it as the test's error, and goes on with the next test. `checkIndex(List)` makes its own thread pool and, on its way out, calls `shutdownNow`, which interrupts its threads (rule 7).

## Decision

### Check index

1. **`startCheckIndex {level?}` / `getCheckIndex {jobId, fromLine}`**, cancelled with `cancelJob`. `level` is `CheckIndex`'s level, 1 to 3, and 1 if absent. A level outside that range fails with `INVALID_PARAMS`. `startCheckIndex` needs an open path, with or without a reader; without one it fails with `NO_INDEX_OPEN`.
    - *Amended by ADR 0045 §5, §6:* each index is checked by the Lucene that reads it; a runtime has one level and ignores `level`. The feature `CHECK_LEVELS` says whether the level counts, and without it the check screen has no `l`.
    - *Amended by ADR 0046 §5:* a runtime may have levels: on 5.5, 1 checks the checksums only, 2 is the default, 3 cross-checks the term vectors.
2. **Every index found is checked, one after the other:** the opened ones in order, then the skipped ones (ADR 0021), since those are often the broken ones. Each one is opened for the check through its own `Directory`, of the `DirectoryImpl` the path was opened with, and closed after it. The check never touches the open readers or their directories.
3. **The check's directory is read-only.** It wraps the directory opened for the check, refuses every method that writes (`createOutput`, `deleteFile`, `rename`, `sync`, …) with an exception, and passes reads on. Together with `NoLockFactory`, nothing can be written, even by a `CheckIndex` that changes in a later Lucene version (rule 1).
4. **The check is cancelled cooperatively** (rule 7). The read-only directory wraps each `IndexInput` too. It checks the job's cancellation on `seek`, `readBytes`, `skipBytes`, `clone` and `slice`, but not on the reads of single values, which stay as fast as the wrapped input's. It asks the job, through `ScanProgress`, only on every 1024th of those calls per input, and after the first "cancelled" it throws at once. So a check stops within a few reads, in whichever thread reads. Writing a line of output asks the job too. After cancelling, `CheckIndex` either throws or returns a report full of failures; either way the job ends cancelled, and the report is dropped.
5. **leia passes its own executor** to `checkIndex(List, ExecutorService)`, with as many threads as there are processors, and calls only `shutdown()` on it, never `shutdownNow()`: no reading thread is interrupted. An index whose latest commit has one segment is checked in the job's own thread, so its report streams as it is written instead of arriving all at once at the end.
    - *Amended (commit order):* the parallel check returns the segments sorted by size; leia puts them back in the order of the latest commit, which it reads anyway to count them, so that `segments` is in commit order as §7 says.
6. **The output is streamed as lines.** The server keeps every line of the job, and `getCheckIndex` returns the lines from `fromLine` on, and how many there are so far as the job's progress. Unlike the other `get<Op>` methods, its params have a field besides `jobId`. A line that `CheckIndex` writes in parts (`test: open reader.........` then `OK`) is handed out once it is complete. Before each index, leia writes a header line: `Checking <path>`, or with several indexes `Checking index 2 of 3: <path>`.
7. **The outcome is typed, per index**, when the job is done:
   - `path`, `skipped` (whether the index was skipped when the path was opened), `clean`;
   - `error`, if `CheckIndex` could not check it at all (no `segments_N`, a directory that can't be listed), and then nothing else is known;
   - `segmentsFile`, `missingSegments` (the latest commit can't be read), `toolOutOfDate`, `segmentCount`, `badSegments`, `lostDocs` (the documents in the bad segments), `validCounter` (whether the commit's segment name counter is above every segment's name);
   - `segments`: per segment, `name`, `maxDoc`, `codec`, `compound`, `files`, `sizeBytes`, `hasDeletions`, `openReaderPassed` and `error`, the message of the test that failed, with its causes.

   And `tookMillis` over all of them.

### Export terms

8. **`startExportTerms {field, path, delimiter?, overwrite?}` / `getExportTerms {jobId}`**, cancelled with `cancelJob`. It writes every term of the field, over all indexes, in term order, with its document frequency, as Luke does: `term<delimiter>docFreq` and `\n`, in UTF-8, without a header. `delimiter` is `COMMA`, `SPACE` or `TAB`, and `TAB` if absent, because terms seldom contain tabs. Like the field statistics, document frequencies include deleted documents. A term that isn't valid UTF-8 is written as lowercase hex, as on the wire (ADR 0006); the file doesn't mark it. A term may contain the delimiter, so a reader of the file should split at the last one.
9. **The server writes the file.** The terms never cross the pipe: a field with millions of terms takes one job instead of thousands of pages. The progress is the number of terms written so far. The outcome is the `path`, the number of `terms` and the file's size in `bytes`.
10. **The path is checked when the job starts.** It must be absolute (`INVALID_PARAMS`). The new error **`OUTPUT_NOT_WRITABLE` (-32016)** is returned if it lies in an index directory of the open path, opened or skipped, or below one (rule 1); if its directory doesn't exist; if it is a directory; or if the file exists and `overwrite` isn't true. The comparison uses real paths, so a symbolic link into an index directory is caught. An I/O error while writing fails the job with `OUTPUT_NOT_WRITABLE` too. A job that fails or is cancelled deletes what it wrote, so no half-written file is left behind; with `overwrite`, the old file is then gone too.
11. **Export terms needs a reader:** without one, `NO_READER`. An unknown field fails with `FIELD_NOT_FOUND`. A field without postings exports an empty file.

    *Amended (export on every runtime):* the export works on an index read with a runtime too (ADR 0044). The server reads its terms page by page through the runtime's `Terms.terms` and writes them as above, in the same format.

### The TUI

12. **A new screen, `7 Check`,** for the open path, with or without a reader. `⏎` starts a check, `l` cycles the level (1 checksums, 2 integrity, 3 slow), Esc cancels a running check. The summary panel on top says OK or what is broken per index, with the failed segments and their errors. The output panel below shows the lines as they arrive and follows the end while it is at the end. Tab switches between the panels, ↑/↓, PgUp/PgDn and g/G scroll them, and ←/→ scroll the output sideways, since `CheckIndex`'s lines are often wider than the screen. The last check stays until another path is opened or the index is closed. A reopen, which stops the server's jobs, marks a running check as stopped.
13. **Export on the terms screen:** `e` asks for the file of the selected field, prefilled with `terms_<field>_<UTC timestamp>.out` in the current directory. The path may be relative to it or start with `~`. Tab cycles the delimiter. If the file exists, the prompt says so, and a second `⏎` overwrites it. While the export runs, the tab line shows how many terms are written, and Esc cancels it. Then the footer, which is wider than the tab line, says until the next key where the file was written (relative to the current directory if below it), or why it couldn't be.
14. **The tab line makes room.** With seven tabs, a notice that doesn't fit next to them makes the tabs drop the spaces around their titles.

## Alternatives considered

- **Check with the open reader's directories.** It saves opening a directory, but a check that runs on after closing the index would read from a closed directory, and the skipped indexes have none. Rejected.
- **Cancel only through the output.** `CheckIndex` writes a line per test, so cancelling would wait for the test that runs, such as the postings of a large segment at level 2, or the checksums of a large file. In parallel it doesn't write to leia's output at all until a segment is done. Rejected.
- **Check cancellation on every read of the wrapped inputs.** Simpler, but it puts a volatile read and a counter behind every `readByte` and `readVInt` of the hottest loops of a check. Rejected for the bulk methods and positioning, which every scan calls often enough.
- **Return the whole output with the outcome, or hand out each line once.** The first holds the output back until the end. The second loses lines if a poll fails between the server and the TUI. Rejected for `fromLine`.
- **Output only, without a typed outcome** (an option when scoping). The TUI would have to parse `CheckIndex`'s text to say what is broken, and that text changes between Lucene versions. Rejected.
- **The TUI pages through `getTerms` and writes the file.** No new methods, but thousands of round trips, a polling loop of its own in the TUI, and no cancellation in the server. Rejected.
- **Mark binary terms in the export, or add `totalTermFreq`** (an option when scoping). Luke's format is what the export is for. Rejected for now.
- **Write the export to a temporary file and move it into place.** It keeps an old file until the new one is complete, but the temporary file must lie in the same directory, and a crash leaves it there. Rejected as more than the overwrite prompt needs.

## Consequences

- `leia-core`: `IndexCheck`, which checks one index directory with `CheckIndex` through a read-only, cancellable `Directory` (`CheckDirectory`), with the outcome as records; `TermExport`, which writes a field's terms to a file after checking the path; `OpenedIndex.directoryImpl()` and `skippedDirectories()`, so the server knows what to check and where not to write.
- `leia-protocol` and the TUI: `startCheckIndex`, `getCheckIndex`, `startExportTerms`, `getExportTerms` with their DTOs and fixtures, and `OUTPUT_NOT_WRITABLE` in both `ErrorCode` enums and `protocol/error-codes.json`.
- `leia-server`: `Jobs` can attach an object to a job, which the job writes and polls read, for the lines of a check.
- TUI: the check screen (`7`), and export on the terms screen. The tab line has one tab more, so less room for its notices; it gets compact when a notice needs the room.
