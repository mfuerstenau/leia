# ADR 0043 — Matches in a document opened from a hit

- **Status:** Accepted
- **Date:** 2026-10-12
- **Amends:** ADR 0042 §3 (`startHighlight` takes an optional field and gives a result per field)

## Context

ADR 0042 highlights the hits of a search: the hit list and its details show the best passages, and `v` finds every match in one value and opens the viewer on it. It left one thing for later: a document opened from a hit (`Enter`, then `[` `]` from hit to hit) shows nothing of the query on the documents screen. That screen is where a document is read field by field, so it is where the matches help most.

`startHighlight` already finds every match in the values of one field of a document. Marking a whole document needs it for every stored field, and running one job per field would mean a dozen jobs for an ordinary document.

## Decision

1. **`startHighlight`'s `field` becomes optional.**
   - `StartHighlightParams {query, docId, field?, options}`. Without `field`, every stored field of the document with a text value is highlighted. The fields are read in one pass over the stored fields, and Lucene's `UnifiedHighlighter` highlights them in one call (`highlightFieldsAsObjects`, with a passage count per field, one per value).
   - `FIELD_NOT_FOUND` applies only to a named field. The other errors are those of ADR 0042 §3.
2. **The result has one entry per field.**
   - `DocumentHighlight {fields}`, and `FieldMatches {field, offsetSource, values, truncated}` takes the fields that `DocumentHighlight` had. A field's `values` may be empty.
   - The fields come in the order of the document's stored fields. A named field gives one entry if it has a text value; a doc values or KNN query gives none.
   - The 1 MiB limit applies to each field, as before.
   - Cancellation still goes through the searcher's `QueryTimeout`.
3. **The search screen hands its query to the documents screen.**
   - `Search.matchesQuery()` is the submitted query with its highlight options. It is absent while `H` is off, in the regex, doc values and KNN modes, and before a search.
   - `Enter` on a hit, `[` `]` stepping to the next hit, and a search hit's `v` all pass it to `Documents.showSearchHit(docId, query)`.
   - `showHit` and `showPoint` from the terms screen forget the matches, and so do opening, closing and reopening an index.
4. **The matches load with the document.**
   - `Action.LoadDocument` gains `matches?`: a `MatchesStart {matchesId, params}`. The session starts `startHighlight` together with `getDocument`, reports back with `documentMatchesStarted` or `documentMatchesFailed`, and polls the job (`Poll.DocumentMatches`) until `documentMatchesProgress` takes the result.
   - The matches have an id, like a search's highlight: a reply for replaced ones is dropped, and their job is cancelled. A running job that a new hit replaces is cancelled with the next load.
   - A hit already shown with the same query and options keeps its matches. A `v` from the search screen on the document that is shown doesn't start a job.
5. **The documents screen marks the matches.**
   - They are kept per document. `←` `→` to another document show none, and they come back with the hit.
   - **Fields table:** the names of fields with a match are marked (`TextFormat.MATCH`). The title says `finding matches…` while the job runs, then `matches in N`, or `cannot find the matches`.
   - **Details:** a `Matches` line after `Norm`, such as `3 in 1 value, offsets from the postings, v views them`, plus `(cut at 1.0 MiB)` when the field was truncated. Each stored value marks its matches in the lines shown.
   - **Viewer:** `v` on a field with matches opens on its first value with a match, with the matches of every value marked; `m` `M` step through them, and `←` `→` to another value start at its first match.
   - The UTF-8 offsets are turned into characters by `TextFormat.charRanges` and marked by `TextFormat.marked`, which the regex scan's snippets use too.

## Alternatives considered

- **One job per field.** Simpler on the wire, but a document with a dozen stored fields would start a dozen jobs, and each would read the stored fields again. Rejected.
- **A new method, `startDocumentHighlight`.** It would duplicate `startHighlight` but for its field. An optional field keeps one method.
- **Highlighting every document the documents screen shows while a search is active.** A document reached with `←` `→` is not a hit, so marking it would suggest that it matched. Rejected: only the hits opened from the search get matches.
- **A separate action to start the matches.** The load and the matches belong together, and `LoadDocument` already bundles the jobs to cancel and the filter to locate in. Kept as a field of the load.

## Consequences

- `DocumentHighlight` changes shape. The search screen's `v` asks for its field by name and picks that field's entry.
- Opening a hit costs one more job, and on a field highlighted by analysis it re-analyzes the stored text, up to 1 MiB per field.
- A field that is stored but not indexed has matches only with `H` set to any field's terms, as for the hit list.
