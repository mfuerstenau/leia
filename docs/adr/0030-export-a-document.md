# ADR 0030 — Export a document as JSON

- **Status:** Accepted, amended by ADR 0040 (version 2: each field's KNN vector) and ADR 0041 (version 3: each field's points)
- **Date:** 2026-10-07

## Context

The documents screen shows everything leia knows about a document, but one field at a time, with stored values over 64 KiB cut (ADR 0008) and the rebuilt indexed terms (ADR 0029) only for the selected field. Nothing takes a document out of leia whole: to compare it with another tool, attach it to a bug report, or diff two versions of an index.

Every piece is already on the wire: `getDocument`, `getStoredValue` for the cut values, `getTermVector`, and `startReconstruct` without `fields`, which rebuilds every indexed field in one job.

## Decision

1. **The TUI puts the export together; the protocol doesn't change.** It fetches the document, then every stored value `getDocument` cut and the term vector of every field that has one. Then, unless the rebuild is turned off, it runs `startReconstruct {docId}` for every indexed field. No new method, DTO or fixture (rule 4 isn't touched).
2. **The format is leia's own, versioned:** `{"format": "leia-document", "version": 1, …}`, pretty-printed, camelCase like the wire.
   - Top level: `index` (the opened path), `indexDirectory` (only with several, as shown), `docId`, `deleted`, `indexedTerms` (whether the terms were rebuilt, so a missing `indexed` isn't read as "no terms"), and `fields`.
   - Per field, in field number order, only where the document has something: `name`, `number`, `flags` (as the documents screen shows them), `indexOptions`, `norm`, `stored` (whole), `docValues` (raw numbers), `termVector`, and `indexed` (`source` and the terms).
   - It is a serde struct of its own (rule 3), `doc_export::ExportedDocument`. Its leaf values are the protocol's: `StoredValue`, `DocValues`, `TermVectorTerm`, so binary values are lowercase hex with a `binary` flag, as on the wire. A golden file (`tui/tests/fixtures/document-export.json`) pins the format: a change to it, or to one of those protocol types, fails the test and bumps `version`.
3. **`E` on the documents screen** opens a prompt like the terms export's (ADR 0022):
   - prefilled with `doc-<id>.json`, resolved against the working directory, `~` expanded;
   - `Tab` turns the indexed terms on or off; they are on by default;
   - `Enter` writes the file, and a file that exists takes a second `Enter`;
   - `Ctrl+Y` copies the JSON to the clipboard through OSC 52 instead.
4. **The TUI writes the file, so it guards rule 1 itself**, with the same checks and wording as the server's `TermExport.checkTarget`:
   - the path must be absolute and its directory must exist;
   - it must not be in or below an index directory of the open path, opened or skipped, compared by real paths, so a link into one is caught;
   - it must not be a directory or a symbolic link.

   It checks when `Enter` is pressed, so the prompt shows the error, and again right before writing. The file is created new, or truncated only after the second `Enter`; a write that fails deletes what it wrote.
5. **Progress and stopping.** The fetch is synchronous, like opening the viewer. The rebuild is a job (rule 7): the tab line shows `Exporting doc N: scanned/total terms`, and `Esc` stops it, before it quits, as for the terms export. Only one export, of terms or a document, runs at a time.
6. **Left out:**
   - point values, as in ADR 0029 (the point-values backlog item);
   - KNN vectors;
   - decoded doc-value numbers: only the raw `long`s, since the index doesn't record what they encode.

## Alternatives considered

- **A server method that writes the file.** The server already checks paths for the terms export, and it would keep the format in one place. But the format would become a wire DTO on both sides, with fixtures, for data the TUI already fetches. Clipboard copying would need the JSON on the wire anyway. Rejected.
- **The rebuild always on.** It walks every term of every indexed field in the document's segment. On a large index that is slow for a user who only wants the stored values. Kept as the default, with a toggle.
- **The protocol DTOs as the whole format.** `getDocument`'s shape has `fullLength` and `hasTermVector`, which mean nothing in a file of whole values, and no place for the rebuilt terms. Rejected for the outer structure; the leaf types are reused.
- **Clipboard only.** OSC 52 payloads over some size are cut or dropped by many terminals and by tmux. A document with a 100 KB value doesn't survive. Rejected; the file is the default.

## Consequences

- The TUI gains `doc_export` (format, assembly, path check, writing) and a document kind of the export prompt. The terms export keeps its behaviour.
- `App` keeps the skipped index directories of the open path, which the path check needs.
- Changing what the export holds means bumping `version` and the golden file.
