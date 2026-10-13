# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Backlog — future capabilities

From the read-only checklist in `luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Search: a date resolution per field (ADR 0026 has one for all fields)
- [ ] Search: a prefix match on sorted, sorted-set and binary doc values (left out of ADR 0033)
- [ ] Analysis: `PerFieldAnalyzerWrapper`, `QueryAutoStopWordAnalyzer` (needs an `IndexReader`), the collation analyzers (need a `Collator`) and `JapaneseCompletionAnalyzer` (left out of ADR 0023)
- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Commits: per-field postings, doc values and KNN vectors formats (from each segment's field infos; ADR 0009 leaves them out)
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter, cross-screen navigation (`luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values (also a document's own, which ADR 0029 and the JSON export of ADR 0030 leave out), per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
