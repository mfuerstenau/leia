# ADR 0042 — Highlighting the hits in stored text

- **Status:** Accepted
- **Date:** 2026-10-12
- **Amends:** ADR 0010 §5 (a hit can carry highlighted passages), ADR 0027 §10 (the viewer's marked matches also come from a query)

## Context

A hit shows its stored fields, cut at 256 bytes (ADR 0010 §6). The matches are rarely in those first bytes, so the hit list doesn't show why a document matched. Luke ships `lucene-highlighter` and never uses it (`docs/luke-features.md` §6).

Lucene's `UnifiedHighlighter` finds the query's matches in a stored text value and picks the best passages, usually sentences. It takes the offsets of the matches from one of three places, chosen per field and per query:

- **The postings**, if the field was indexed with offsets. This is the cheapest.
- **The term vector**, if the document has one with offsets.
- **Analysis**: the value is analyzed again, which costs the most. For a wildcard, fuzzy or other multi-term query, the highlighter also falls back to analysis on a field with postings offsets but no term vector.

Which source a field gets is itself worth seeing in an inspector. It shows whether a field has offsets, and whether a query highlights cheaply.

Several questions are new:

- **Where it runs.** A page of hits is up to 1000 documents.
- **The shape on the wire.** The highlighter writes HTML by default. The TUI needs positions, and ADR 0027 already has them: UTF-8 byte offsets into the whole value (`MatchSpan`, `Snippet`).
- **Multi-valued fields.** The highlighter joins a field's text values into one string, with one separator character between them. Its positions are into that string, and it skips values that aren't text.
- **Term vectors without offsets.** The highlighter can't tell them from term vectors with offsets until it reads one. It then throws `IllegalArgumentException`.
- **Analyzing.** Re-analysis needs an analyzer, and the index doesn't record which one was used.

## Decision

1. **Passages are an option of `startSearch`**, `highlight: HighlightOptions?`. One job then searches and highlights, on one searcher. A page of hits is highlighted in one pass over their stored fields.
   - `HighlightOptions {maxPassages, requireFieldMatch, analyzer}`:
     - `maxPassages` is 1 to 10 per field.
     - `requireFieldMatch` highlights a field only with the query's terms in that field, as Lucene does by default. Without it, a field is highlighted with the terms of every field, which also marks text that is stored but not indexed.
     - `analyzer` is used where the offsets come from analysis. The TUI sends the analysis screen's analyzer, the one its queries are parsed with.
   - **The fields highlighted** are those of `fields` (all of them when absent) with a `STRING` value in a hit. Numbers and binary values are not highlighted.
   - **Doc values and KNN queries** have no terms, so the option is ignored for them. It is not an error.
2. **A hit gains `highlights: [HitHighlight]`**, in the order of its fields, and only for fields with a match.
   - A `HitHighlight` is `{field, offsetSource, passages}`. `offsetSource` is `POSTINGS`, `TERM_VECTORS`, `POSTINGS_WITH_TERM_VECTORS`, `ANALYSIS` or `NONE`.
   - A `HighlightPassage` is `{valueIndex, snippet, spans, score}`:
     - `valueIndex` counts every stored value of the field, as `getStoredValue` does. The highlighter's n-th text value is mapped back to that index.
     - `snippet` and `spans` are ADR 0027's `Snippet` and `MatchSpan`, in UTF-8 bytes into the whole value. Overlapping matches are merged.
     - A passage longer than 300 characters is cut from 60 characters before its first match. Whitespace at its ends is trimmed.
   - A field is read up to its first 100,000 characters for passages.
3. **Every match in one value: a job, `startHighlight` / `getHighlight`.** *Amended by ADR 0043: `field` is optional, and the outcome has one entry per field.*
   - `startHighlight {query, docId, field, options}` returns `JobStarted`.
   - The outcome is `DocumentHighlight {offsetSource, values, truncated}`, with one `ValueHighlight {valueIndex, matchCount, spans}` per value with a match. It keeps the first 100 spans (`StartStoredScanParams.MAX_SPANS`) and counts the rest.
   - The highlighter reads each value whole, with `WholeBreakIterator`, up to 1 MiB characters of the field. `truncated` says that the field is longer.
   - It is a job by rule 7: re-analyzing a megabyte takes time, and `cancelJob` stops it through the searcher's `QueryTimeout`, as for search (ADR 0010 §8).
   - Errors are those of search, plus `DOC_NOT_FOUND` and `FIELD_NOT_FOUND`. A doc values or KNN query gives `{NONE, [], false}`.
4. **Core: `Highlights`, a subclass of `UnifiedHighlighter`** that:
   - **builds each field's highlighter itself**, which records the offset source chosen for it;
   - **formats nothing**: a `PassageFormatter` copies the passages and their matches, and core turns them into values and UTF-8 offsets;
   - **falls back to analysis on term vectors without offsets**: it catches the `IllegalArgumentException` ("indexed without offsets"), highlights that document by analysis, and reports `ANALYSIS`;
   - **highlights the query as built, not rewritten**: More-Like-This is built first, and the highlighter handles multi-term queries itself;
   - **turns weight matches off without `requireFieldMatch`.** Weight matches run the whole query against one field's text, so `+title:lamp +body:qu*` would match neither field alone, and nothing would be marked. The query's terms are highlighted instead, with phrases still strict;
   - uses Lucene's defaults otherwise: sentence passages, weight matches, phrases highlighted strictly, and `maxNoHighlightPassages` 0, so a field without a match has no passages.
5. **No new error codes.** `maxPassages` out of range is `INVALID_PARAMS`.

### The TUI

6. **Highlighting is on by default.** `H` cycles "terms of the field" → "any field's terms" → off, and searches again. The query line's title names the setting unless it is the default. The option is sent in every mode but regex, doc values and KNN, with three passages.
7. **The hit list shows the first passage of each highlighted field**, as `field: …text…  field: …text…` with the matches marked (`TextFormat.MATCH`). A hit without passages shows its stored fields as before. The regex scan's snippet code is shared: `StoredScan.snippetSpans(snippet, spans, valueLength)`.
8. **The details panel lists the selected hit's passages.** Each field shows its offset source ("offsets from the postings", "from analysis", …), then each passage with its `[valueIndex]`.
9. **`v` views the value of the hit's first passage with every match marked.**
   - It starts `startHighlight` for that field, and the title says "finding the matches…".
   - Once the matches are in, the documents screen's viewer opens on that value, exactly as the regex scan's `v` opens it (ADR 0027 §10). `m`/`M` step through the matches. A value that `getDocument` cut is fetched whole.
   - A highlight has an id like an explanation: a reply for a replaced one is dropped and its job cancelled. `Esc` stops it.

## Alternatives considered

- **A separate `getHighlights` call for a page of hits.** The search would stay as it is, but each page would read the stored fields twice, and the TUI would track one more job per page. Rejected.
- **The highlighter's HTML with `<b>` tags, parsed in the TUI.** The tags can't be told from the text, and positions would be lost. Rejected.
- **Whole values on hits instead of passages.** A value can be megabytes long; passages keep a page small. The viewer gets whole values with `startHighlight`.
- **`FastVectorHighlighter` or the original `Highlighter`.** The first needs term vectors with offsets, and the second re-analyzes always. The unified highlighter picks the cheapest source per field. Rejected.
- **Highlighting by the documents screen**, marking matches in every value of a document opened from a hit. It needs the query on the documents screen and one more job per document. Left for later; `v` covers one value. *Done in ADR 0043.*
- **Failing on term vectors without offsets.** That is Lucene's behaviour, but such a field would then be impossible to highlight. Rejected for the fallback to analysis.

## Consequences

- `leia-core` depends on `lucene-highlighter`, which brings `lucene-memory` into the fat jar.
- Two more job methods, `startHighlight` and `getHighlight`. `StartSearchParams` and `Hit` gain a field each.
- A page of 100 hits highlighted by analysis costs up to 100 re-analyses per field, each of at most 100,000 characters. With postings offsets it costs almost nothing.
- Stored values that contain U+0000 confuse the mapping to values, because the highlighter uses that character as its separator. This is rare and not handled.
- A value is mapped through the text values the hit's search read. A field whose stored values mix text with numbers or binary values still gets the right `valueIndex`.
