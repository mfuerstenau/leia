# ADR 0027 — A regular expression over stored values

- **Status:** Accepted, amended by ADR 0042 (the viewer also marks a query's matches, and the snippet code is shared)
- **Date:** 2026-10-07

## Context

Every search mode so far (ADR 0010 to ADR 0014) finds terms, so it only finds what was indexed, as the analyzer left it. Some values can't be found that way: those stored but never indexed (Elasticsearch's `_source`), and those whose terms the analyzer changed (stemmed, lowercased, split, dropped). The only way to find them is to read every document's stored fields and look at the text. That is slow by design: a full pass over the stored fields, which are compressed in blocks. By rule 7, it has to be a job (ADR 0005).

Several questions are new:

- **The shape of a hit.** A search returns documents with a score. Here a match is in one value of one field, and its position in the value is what the user wants to see.
- **Paging.** `startSearch` runs the query again for every page (ADR 0010 §3). Running a full scan again for every page would cost the whole scan each time.
- **Runaway patterns.** Java's regex engine backtracks. Java 9+ memoizes simple cases like `(a+)+b`, but others, such as `(.*a){20}b` or a pattern with a back reference, still take exponential time on some values. `Matcher` has no timeout and no cancel flag. Interrupting the thread is not allowed (rule 7).
- **Positions.** Java counts UTF-16 units and Rust counts UTF-8 bytes. Lucene stores text as UTF-8, and lengths are already in UTF-8 bytes on the wire (ADR 0008).

## Decision

1. **Its own job: `startStoredScan` / `getStoredScan`**, not a `SearchQuery` alternative, because it has no score, no sort and no pages. `cancelJob` stops it.
   - `startStoredScan {pattern, caseInsensitive, fields?, includeDeleted}` checks its params at once:
     - no reader → `NO_READER`;
     - an unknown field → `FIELD_NOT_FOUND`;
     - a pattern that doesn't compile → `INVALID_QUERY`, with Java's description and the index where it fails (`Unclosed group at index 6`).
   - `caseInsensitive` is `CASE_INSENSITIVE | UNICODE_CASE`. Other flags go inline in the pattern: `(?s)`, `(?m)`, `(?x)`.
2. **The hits stream out like check index's lines (ADR 0022 §6).** The server keeps the job's hits, and `getStoredScan {jobId, fromHit}` returns:
   - the hits from `fromHit` on, and how many there are (`hitCount`);
   - the document ids gone through so far (`scannedDocs`, of maxDoc) and the values that matched so far;
   - the outcome once the scan is done.

   The final response carries the remaining hits. After it, the job is gone, as for every job.
3. **What is scanned.**
   - **Values:** `STRING` values, and `BINARY` values that decode as valid UTF-8 (such as `_source`). Other binary values and numbers are skipped.
   - **Fields:** the stored fields named in `fields`, or all of them. Each document is read through a `StoredFieldVisitor` that decodes only those fields.
   - **Order:** document id order, segment by segment. Doc ids are those of the whole index, over every index below the path (ADR 0021).
   - **Deleted documents:** skipped unless `includeDeleted` is set. A hit says whether its document is deleted.

   *Amended (ADR 0055):* the scan works on an index read with a runtime too. Core's matching reads a `StoredSource` a page of 64 document ids at a time; on a runtime, the server pages through `Documents.storedValues`.
4. **A hit is one value with at least one match** (`Matcher.find`, not a whole-value match). It carries:
   - `docId`, `deleted`, `field`, and `valueIndex` (as `getStoredValue` counts values);
   - `length`, the value's size in bytes;
   - `matchCount`;
   - `spans`, the first 100 matches as `{start, end}`;
   - `snippet {start, text}`: the value from 40 UTF-16 units before the first match to 160 after its start, never between the halves of a surrogate pair.

   **All positions are UTF-8 byte offsets into the whole value**, the unit of ADR 0008. Rust slices the text with them directly. A binary value that is valid UTF-8 round-trips exactly, so its offsets are byte-exact too.
5. **The scan keeps the first 10,000 hits** (`MAX_HITS`, the same number as the search window) **and counts the rest.** After the cap, it goes on counting matching values and documents to the end, so the totals are exact. This costs no more than scanning without matches. The outcome says `hitsTruncated`.
6. **Each value has a time limit of 1 s** (a server constant, which core takes as a `Duration`). The matcher reads the value through a `CharSequence` wrapper. Every 4096 characters read, the wrapper checks the value's deadline, and asks the job's `ScanProgress`, which throws once the job is cancelled. So a value that runs past its deadline is skipped, and a cancel stops the scan even inside one slow value; no thread is interrupted. Skipped values don't fail the scan. The outcome counts them (`timedOutValues`) and names the first 100 (`timedOut`, as `{docId, field, valueIndex}`).
7. **The outcome:** `scannedDocs`, `matchedDocs`, `matchedValues`, `hitsTruncated`, `timedOutValues`, `timedOut` and `tookMillis`.
8. **No new error codes.** `INVALID_QUERY`, `FIELD_NOT_FOUND`, `NO_READER`, `INVALID_PARAMS` (a negative `fromHit`) and `JOB_NOT_FOUND` cover it.

### The TUI

9. **A fifth mode on the search screen.** `m` cycles to "Regex over stored values" after More-Like-This. Switching into or out of it starts nothing, since a scan reads every document.
   - **Keys:**
     - `/` edits the pattern;
     - `i` ignores case (global `c` closes the index), `d` includes deleted documents;
     - `l` limits the fields, with the stored-fields popup of the other modes;
     - a changed setting scans again;
     - the parser, scoring, field, operator, count and explain keys do nothing.
   - **Hit list:** document (`✗` if deleted), `field[index]`, match count, and the snippet with the matches inside it highlighted, with `…` where the value goes on.
   - **Polling:** the hits are polled from the last one received, so the list grows while the scan runs. `Esc` stops it and keeps them.
   - **Details panel:** the selected hit, the pattern and settings, the outcome and the values that timed out.
10. **From a hit:**
    - `Enter` opens the document. `[`/`]` on the documents screen step through the hits' documents, skipping further hits in the same document.
    - `v` opens the value in the documents screen's viewer, in text mode, with its spans highlighted and scrolled to the first one. A value that `getDocument` cut is fetched whole as usual, so spans past 64 KiB land once it is in.
    - `m`/`M` jump to the next or previous match: `n` is already the viewer's next value. The status line says which match is shown, and how many of all the value's matches are marked.

## Alternatives considered

- **A `regex` alternative in `SearchQuery`.** One mode list on the wire, but `startSearch` runs the query again for every page, and a scan has no score or sort for the hit list to show. Rejected.
- **A hit per document**, with the matching fields inside. The spans belong to a value, and a document with many matching values would make one huge hit. Rejected.
- **Stopping at the hit cap.** It saves the rest of the scan, but the counts would only be lower bounds. Counting to the end costs no more than a scan without matches, and `Esc` stops it anyway. Rejected.
- **Failing the scan on a slow value.** It is simpler, but one pathological value would hide every other hit. Rejected; the slow values are skipped and listed.
- **A wall-clock timeout with `Thread.interrupt`.** It breaks `NIOFSDirectory` readers (ADR 0005), and `Matcher` doesn't check interrupts anyway. Rejected for the `CharSequence` wrapper.
- **Positions in UTF-16 units or characters.** UTF-16 units are Java's, and characters need a count over the text. UTF-8 bytes are what the TUI slices with and what lengths already use (ADR 0008). Rejected.
- **Numbers as text.** Matching the decimal text of numbers is rarely what one wants, and the point and doc values screens show them better. Left out.

## Consequences

- Two more job methods, with fixtures, DTOs on both sides and round-trip tests, and a core scan (`StoredScan`) that takes a listener for its hits and a `ScanProgress`.
- A pathological pattern costs up to a second per value, so over every value of a large index it can take very long. The progress shows it, and `Esc` stops it.
- The viewer gains highlighted matches. The highlighting is applied before wrapping, so tabs, control characters and wide characters stay aligned.
- More-Like-This with like-text, planned as ADR 0027, becomes ADR 0028.
