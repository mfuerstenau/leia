# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Backlog — future capabilities

From the read-only checklist in `luke-features.md` §7. Ordered roughly by value. Each area will need its own protocol methods, fixtures and probably an ADR for the cross-cutting parts.

- [ ] Search: a date resolution per field (ADR 0026 has one for all fields)
- [ ] Search: chosen MLT terms as data (`{field, term, tf, docFreq, score}`), like-text instead of a document (the analyzer is in place since ADR 0014), MLT word lengths / stop words (left out of ADR 0013)
- [ ] Search: regex over stored values — a brute-force scan of every document's stored fields with a Java regex (`find`, not whole-value match), for values that were never indexed or were analyzed away; slow by design, so it is a job (rule 7) with progress (documents read of maxDoc) and `cancelJob`
  - `regex {pattern, fields?, caseInsensitive, includeDeleted}` as a further query alternative, or its own `startStoredScan`/`getStoredScan`; `INVALID_QUERY` with the position for a bad pattern; a cap on hits, and on matching time per value against catastrophic backtracking
  - hits: doc id, field, value index and the match spans, so the TUI can highlight them; `STRING` values, and `BINARY` ones that are UTF-8; partial results visible while the scan runs
  - TUI: `m` cycles to "regex (stored values)" on the search screen, the field popup limits the fields; `Enter` opens the document, `v` the value in the viewer
- [ ] Analysis: `PerFieldAnalyzerWrapper`, `QueryAutoStopWordAnalyzer` (needs an `IndexReader`), the collation analyzers (need a `Collator`) and `JapaneseCompletionAnalyzer` (left out of ADR 0023)
- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Commits: per-field postings, doc values and KNN vectors formats (from each segment's field infos; ADR 0009 leaves them out)
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter, cross-screen navigation (`luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values, per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations
- [ ] Documents: reconstruct a document's indexed values (uninvert) — per field, the terms the document was indexed with, rebuilt from the postings, so fields without term vectors show what was indexed too (Luke dropped its "Reconstruct")
  - a job (rule 7): `startReconstruct {docId, fields?}` / `getReconstruct {jobId}`, cancellable through `ScanProgress`; it walks every term of each indexed field in the document's segment and `advance`s the postings to the document, so it costs a full term pass per field
  - per field: terms in position order with positions and offsets where indexed (gaps and stacked tokens visible, as on the analysis screen), else terms with freq; term vectors used directly when present; binary terms as hex; point values (decoded by width) as a possible extra
  - TUI: a key on the documents screen (e.g. `r`) fills the details panel or a viewer with the rebuilt field text; progress and `Esc` like the term scan
- [ ] Documents: export a document as JSON, with everything leia knows about it: doc id, deleted flag, and per field the flags, norm, stored values (whole, not cut), doc values, term vector and the reconstructed indexed values (needs the item above)
  - the TUI assembles it from `getDocument`, `getStoredValue`, `getTermVector` and the reconstruct job, so no new data method; the export format is a typed serde struct of its own (rule 3), versioned, binary as hex
  - TUI: a key on the documents screen (e.g. `E`) asks for a file path (default `doc-<id>.json` in the working directory) and writes it, or copies it via OSC 52; never into the index directory (rule 1); progress and `Esc` while the reconstruction runs
- [ ] Documents: browse through a filter — a query (any of the search alternatives) narrows the documents screen to the matching documents until it is cleared, so ←/→ step to the previous/next match instead of the next id, unlike `[`/`]`, which only walk the fetched hits in score order
  - a job (rule 7): `startFilter {query, includeDeleted}` / `getFilter {jobId}` collects the matches into a bit set once and gives their count; `filterStep {filterId, fromDoc, direction, count}` returns the next matching ids in doc-id order; `releaseFilter` (or replacing it) frees the set
  - `i` (go to id) jumps to the nearest match; the terms screen's postings can mark or hide documents outside the filter
  - TUI: a free key on the search screen (`f`/`F` are taken by the field) turns the current query into the filter; the tab line and the documents title show it with `n of M`; a key clears it

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
