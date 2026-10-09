# ADR 0029 — Reconstruct a document's indexed values

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

The documents screen shows what a document stored, its doc values and, if the field has them, its term vector (ADR 0006). For a field without term vectors, nothing shows what the document was indexed with: which terms the analyzer left, at which positions and offsets, with which payloads. That is often the question when a search doesn't find a document whose stored text looks right. Luke had "Reconstruct & Edit" (`DocReconstructor`) and dropped it.

The index has no forward index from a document to its terms. The only way back is through the postings: walk every term of the field and see whether its postings hold the document. That costs a pass over all the terms of the field, so by rule 7 it has to be a job (ADR 0005). The JSON export of a document (backlog) needs the rebuilt values too.

## Decision

1. **A job: `startReconstruct {docId, fields?}` / `getReconstruct {jobId}`.** `cancelJob` stops it.
   - `fields` are the indexed fields to rebuild, in that order. Absent means every indexed field, in field number order: that is for the export.
   - `startReconstruct` checks its params at once:
     - no reader → `NO_READER`;
     - an id outside 0 to maxDoc - 1 → `DOC_NOT_FOUND`;
     - an unknown field → `FIELD_NOT_FOUND`;
     - a field that isn't indexed → `INVALID_PARAMS`.
   - No new error codes.
2. **The work happens in the document's segment**, with the segment's own doc id, as `getDocument` reads it. Per field:
   - **A term vector that holds all that the postings hold is read instead.** "All" means positions if the field indexes them, offsets if it indexes them, and payloads if it has them. This takes no scan.
   - **Otherwise, the postings are scanned.** For every term of the field in the segment, `postings(flags)` is called, then `advance(doc)`. A term whose postings hold the document is kept, with its freq and, where indexed, its positions, offsets and payloads. The flags follow the segment's `IndexOptions`.
   - The postings don't skip deleted documents, so **a deleted document is rebuilt too**.
3. **The result is in term order**, as `getTermVector` returns terms. It reuses the `TermVectorTerm` and `TermPosition` DTOs. Per field it carries:
   - `indexOptions`: of the segment, `NONE` if the segment lacks the field;
   - `source`: `TERM_VECTOR` or `POSTINGS`.

   Without freqs (`DOCS`), `freq` is 1, as Lucene reports it. Binary terms are hex, as everywhere. Putting the terms back in position order is the TUI's job (rule 6).
4. **Progress:**
   - `getReconstruct` returns `scannedTerms`, reported every 1024 terms as ADR 0005 does, and with the cancel check.
   - It also returns `totalTerms`: the terms of the fields that are scanned, from `Terms.size()` of the segment. `startReconstruct` counts them, so every poll has them. It is absent if a segment doesn't know.
5. **Point values are left out.** The points of one document need a walk of the field's BKD tree. They belong to the backlog item on point values.

### The TUI

6. **`r` on the documents screen rebuilds the selected field**, if it is indexed. A field already rebuilt or being rebuilt does nothing; a failed one can be tried again. Only one rebuild runs at a time: `r` on another field stops the running one.
7. **The details panel gets a "Rebuilt" section** after the term vector:
   - before a rebuild: `r rebuilds the terms it was indexed with`;
   - while it runs: `rebuilding… N of M terms, Esc stops`;
   - on failure: `cannot rebuild it: …`;
   - once done: `N terms, from the postings` or `from the term vector`, then:
     - **the tokens in position order**, when there are positions: tokens at one position joined by `|` (synonyms, decompounding); `·` where positions were skipped (a removed stop word), as the analysis screen marks gaps;
     - **the terms one per line**, with `×freq` where freqs count occurrences, and the positions as the term vector shows them.
8. **Stopping:**
   - Going to another document stops the rebuild: `LoadDocument` takes the jobs to cancel first, as `StartSearch` does.
   - `Esc` on the documents screen stops it before it quits.
9. **The details panel now scrolls by wrapped rows.** The token line of a long text wraps over many rows, and End has to reach the terms after it.

## Alternatives considered

- **A synchronous method.** It is simple, but on a field with millions of terms it would block the request loop and the TUI. That breaks rule 7. Rejected.
- **Every indexed field on `r`.** One key for the whole document, but a document with a UUID or timestamp field would pay a full term pass for each. The wire allows it for the export; the TUI asks per field. Rejected for the TUI.
- **Uninverting through `UninvertingReader`/`FieldCache`.** It builds per-field structures for the whole index, held in memory, to answer for one document, and it supports only single-token fields well. Rejected.
- **Always scanning the postings.** It is simpler, but slower for no gain when the term vector holds the same. Rejected. A term vector that holds less, such as one without offsets for a field indexed with them, is not used.
- **The token line from the server.** It is a UI concern (rule 6), and the export wants the term-ordered data. Rejected.

## Consequences

- Two more job methods, with fixtures, DTOs on both sides, round-trip tests and a real-server test. The core gains `Reconstruct`, which takes a `ScanProgress`.
- Rebuilding a field from the postings costs one pass over the field's terms in one segment, not the whole index. Each term costs a postings lookup and an `advance`.
- The JSON export of a document can ask for every indexed field in one job.
