# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Analysis: `PerFieldAnalyzerWrapper`, `QueryAutoStopWordAnalyzer` (needs an `IndexReader`), the collation analyzers (need a `Collator`) and `JapaneseCompletionAnalyzer` (left out of ADR 0023)
- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Commits: the "Field formats" section of a segment is hard to read once a field list wraps. The wrapped line starts at column 0, under the kind, so it's unclear where one format ends and the next begins (`fieldFormatSection` in `tui/commits/Commits.java`, wrapped later by `Panels.wrap`). Proposals, which can be combined:
  - (a) a blank line between kinds, or between formats: the smallest change, but wrapped lines still start at column 0
  - (b) a hanging indent: wrapped field lists continue under the field column, not at column 0. This needs a `Panels.wrap` that knows each line's indent, or the panel width while the lines are built
  - (c) a format on its own line (`postings  Lucene104`), with its fields wrapped on the lines below, indented by 2. This reads well in narrow panels too
  - (d) styles: the kind bold, the format in a color, the fields plain, so that a new row stands out even when wrapped

  Suggested: (b) with (d), or (c) if (b) makes `Panels.wrap` too complex. (a) on its own works as a stopgap.
- [ ] Misc: log view with level filter, cross-screen navigation (`docs/luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values (also a document's own, which ADR 0029 and the JSON export of ADR 0030 leave out), per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
