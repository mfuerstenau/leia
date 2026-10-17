# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter
- [ ] Misc: themes
- [ ] Misc: about
- [ ] Improvements over Luke (§6). Done already: deleted documents (ADR 0006, the filter's `includeDeleted`), binary values as text or hex (ADR 0006), cancellation of long operations (ADR 0005), KNN vectors (ADR 0040), point values (ADR 0041), highlighting of hits (ADR 0042), the per-segment document view (ADR 0051). Left:
  - [ ] KNN: HNSW graph inspection (a node's levels and neighbours through `HnswGraphProvider`), and a recall check of approximate against exact (left out of ADR 0040)

## Bugs

From the manual run of cross-screen navigation (ADR 0052), demo index and `lucene3`.

- [ ] `Backspace` shows the search screen in its latest state (ADR 0053 covers terms and documents only): a search hit → document → `m` → `⌫ ⌫` shows the More Like This hits, not the search the hit came from.
- [ ] `Backspace` shows the commits screen in its latest state (ADR 0053 covers terms and documents only): segment → document → `C` on a document of another segment → `⌫ ⌫` shows the other segment, not the one `⏎` came from.
- [ ] The terms screen's `… terms only` note (`s`, `/` or `e` on a point field) outlives leaving the screen: after `⌫` or `2` and back with `3`, the title still says `s searches terms only`, until the next key on the terms screen. Same class as the documents notes fixed in `f3330e7`.
- [ ] At 100 columns the point tree's title is wider than its panel and covers the top-right corner: `┏ price · 3 segments · 499 points in 499 docs ┌ Points`. Since ADR 0041, not from ADR 0052.
- [ ] While the documents `i` prompt is open, the footer still shows the screen's legend with `⌫ back`, though `Backspace` edits the prompt. The terms `/` seek shows `type to seek   ⏎/Esc done` instead.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
