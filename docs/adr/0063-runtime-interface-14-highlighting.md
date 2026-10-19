# ADR 0063 — Runtime interface 14: highlighting

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0042 (highlighting on an index that a runtime reads); ADR 0049 §4 (the runtime interface); ADR 0044 §4 (the features of an index)

## Context

Highlighting (ADR 0042, ADR 0043) worked only on indexes that Lucene 10 reads. On an index created by 8.x and read with `leia-lucene9`:

- `RuntimeResults.features` didn't report `HIGHLIGHT`, so the TUI turned `H` off and greyed out `v`.
- `RuntimeResults.startSearch` dropped `params.highlight()` without a word.
- `StartHighlightHandler` asked `CurrentIndex.readable()`, which refuses with `NOT_SUPPORTED`.

Lucene 9.12.3's `uhighlight` has the API that leia-core's `Highlights` uses on Lucene 10: `UnifiedHighlighter.builder`, `withFieldMatcher`, `withWeightMatches`, `getHighlightComponents`, `getOptimizedOffsetSource`, `getOffsetStrategy`, `getPassageSortComparator`, the 8-argument `FieldHighlighter` constructor, `MULTIVAL_SEP_CHAR = '\0'`, `WholeBreakIterator`, and the message "was indexed without offsets, cannot highlight". So the Lucene-facing part copies over almost line for line.

`Highlights` mixed two things: the Lucene-facing part (the `UnifiedHighlighter` subclass, `CheckedFieldHighlighter`, `CopyingFormatter`, the builder), and an orchestration that uses only the JDK (choosing the fields, mapping text values back to their indexes, finding a passage's value by its separators, cutting passages to 300 characters, UTF-8 offsets).

## Decision

1. **Split `Highlights` at a `TextHighlighter`, as ADR 0061 split the auto stop words scan.** leia-core's new public interface `TextHighlighter` finds the raw passages: `highlight(fields, docIds, maxPassages per field, Mode)` gives the offset source per field and, per field and document, an optional `FoundText` (source, joined content, passages with UTF-16 match offsets). `Mode.PASSAGES` breaks at sentences in the first `MAX_HIT_CHARS` characters, `Mode.VALUES` takes whole values in the first `MAX_DOCUMENT_CHARS`. `Highlights.passages(TextHighlighter, maxPassages, docIds, stored)` and `Highlights.document(TextHighlighter, docId, stored, maxSpans)` are public and Lucene-free. `Highlights.lucene(searcher, query, spec)` is Lucene 10's `TextHighlighter`, and `Searcher` uses it as before.
2. **A `Highlight` group in `leia-runtime`.** `RuntimeIndex.highlight()` gives an optional `Highlight` with `highlight(query, Options, fields, docIds, maxPassages, progress)`. `Options` holds `requireFieldMatch`, the runtime's `AnalyzerSpec`, `wholeValues` and `maxLength`. The records `Highlighted`, `FieldText` and `Passage` and the enum `OffsetSource` mirror `TextHighlighter`'s in JDK types. The query is highlighted as parsed, not rewritten. A deleted document is highlighted too. The progress is called before each field of each document, and throws to stop.
3. **9.12 only.** `Lucene9Highlight` copies core's Lucene part (ADR 0050 §1) over a plain `IndexSearcher` without a query cache, with the analyzer built by `Lucene9Analyzers` and closed afterwards. `leia-lucene9` takes `lucene-highlighter` 9.12.3, which brings `lucene-memory`; the relocation covers both, and `relocationCheck` confirms it. 3.6, 5.5 and 7.7 return an empty group:
   - 3.6 and 5.5 have no `UnifiedHighlighter`.
   - 7.7 has one, but without the builder and with another `FieldHighlighter` constructor, so it isn't a copy. It is left for later.
4. **The server reuses core's orchestration.** `server.runtime.RuntimeHighlights` adapts the runtime's group to a `TextHighlighter`:
   - `startHighlight` checks the document, the field (`FIELD_NOT_FOUND`) and the query at once, as a search would. A KNN query gives no matches, as on Lucene 10. Otherwise a job reads the document's stored values through `Documents.storedValues`, deleted ones included, and runs `Highlights.document`.
   - `startSearch` checks `maxPassages` and builds the analyzer spec at once, then highlights the hits in the search's job with `Highlights.passages`, over the stored values the search returned. The job's count stays at `maxDoc` meanwhile. On a runtime without the group, the option stays ignored; the TUI never sends it there.
   - `StartHighlightHandler` sends an index that a runtime reads to `RuntimeHighlights`, as `StartSearchHandler` sends it to `RuntimeResults`. Both map the protocol records with `Highlighting`, which is public now.
5. **`HIGHLIGHT` where the runtime highlights and searches.** The TUI needs no change: `H`, `v` and the documents screen's matches already follow the feature.
6. **Runtime interface 14.** `LuceneRuntime.API_VERSION` is 14, so a runtime jar built for interface 13 is refused (ADR 0044).

## Alternatives considered

- **Port all of `Highlights` into the runtime,** returning the protocol's shapes. The passage cutting, the UTF-8 offsets and the value mapping would then live twice, and drift. Rejected for the seam of ADR 0061.
- **Highlight in the server with Lucene 10 over the runtime's stored values,** by analysis only. It would need no runtime change, but would lose the postings and term vectors of the index and analyze every field again. Rejected: the offset source is part of what leia shows.

## Consequences

- On `lucene8` and `lucene8to9` the hit list shows passages, `v` shows every match in a value, and a hit opened on the documents screen marks its matches, as on Lucene 10.
- An analyzer the runtime can't build, such as a custom one, fails a highlighted search on 9.12 with `NOT_SUPPORTED`, even when no field needs analysis: the highlighter takes its analyzer up front. `H` turns highlighting off.
- A highlight on 9.12 stops before the next field of a document; Lucene 10 stops through `ExitableDirectoryReader` inside one.
- `runtimes/leia-lucene9.jar` grows from 8.86 MB to 9.2 MB.
- A fix to the passages belongs in `Highlights`; a fix to the Lucene part in `Highlights.lucene` and `Lucene9Highlight`.
- `docs/runtimes.md` has ✓ for 9.12.3 in the `HIGHLIGHT` row, and its differences show the analyzers and the cancellation.
