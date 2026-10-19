# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Lucene 9.12 beyond classic search

Goal: an index created by Lucene 8.x (read with `leia-lucene9`, ADR 0049) gets the features that are greyed out for it today. 9.12 has the APIs that leia-core uses for them on Lucene 10, so most are ports. What needs no runtime change goes to every runtime at once. The others need runtime interface 7 (one ADR for the interface, amending ADR 0049 §4). They land in 9.12 first, and in 5.5 and 7.7 where the copy is the same (ADR 0050). Each item adds its feature in `RuntimeResults.features`, a `runtime()` branch in its handlers, and its row in `docs/runtimes.md` (checked by `RuntimeFeaturesTest`). The TUI already gates on the features.

- [ ] Rebuild a document (`RECONSTRUCT`, ADR 0029, ADR 0041): a port of leia-core's `Reconstruct` and its `count` for the progress. It reads the term vector when it covers the postings, else every term of the field in the document's segment, plus 9.12's `PointTree` for points. A runtime record per field.
- [ ] Reopen (`REOPEN`): `RuntimeIndex.reopen()` through 9.12's `DirectoryReader.openIfChanged`. It returns the new index or empty when nothing changed. `CurrentIndex` forgets the filter on a new commit (ADR 0031 §5). Nothing is written (hard rule 1).
- [ ] Auto stop words (`AUTO_STOP_WORDS`, ADR 0039) on every runtime, with no runtime change: the server pages through `Terms.terms` for the terms above the threshold. Check where the TUI applies the words to the search analyzer: on a runtime they go into `AnalyzerSpec.Named`'s `stopwords`. This is also ADR 0045's "auto stop words on 3.6".
- [ ] Highlight (`HIGHLIGHT`, ADR 0042, ADR 0043): 9.12's `lucene-highlighter` in the runtime jar (check the size against `runtimes/`'s 8.8 MB), a port of leia-core's `Highlights` over 9.12's `uhighlight`, for `startHighlight` and the search's `highlight`.
- [ ] Search beyond classic (`SEARCH`): similarity (BM25, classic, boolean), sort by score, doc id and doc values, the standard parser (point fields), More-Like-This (ADR 0013, ADR 0028), and doc-values queries (ADR 0033, ADR 0036). `SEARCH` splits into a feature per part (ADR 0058 §4): each part gets its own feature, as `TERM_SEARCH` did, and the TUI gates it on `SEARCH ||` that part.
- [ ] Check on more threads on 9.12 (`CheckIndex.setThreadCount`, now fixed at 1, ADR 0049 §5). It goes with the backlog's "check thread count".
- [ ] Run the new features by hand on `lucene8` and `lucene8to9`, as `docs/demo-index.md` does for Lucene 10.

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format). Trie-encoded terms of 4.x to 7.x (7 bits a byte, so valid UTF-8) are written raw today, and one can hold a newline, which breaks the line format
- [ ] Distribution: an option of the start scripts (`leia` and `leia.cmd`) that downloads the runtime jars (`leia-lucene3.jar`, `leia-lucene5.jar`, `leia-lucene7.jar`, `leia-lucene9.jar`) from Maven Central into `runtimes/` beside `leia.jar` (ADR 0044). `--help` advertises it. Needs: publishing the runtime jars to Maven Central, the option's name, checksum checks, and what happens when `runtimes/` isn't writable
- [ ] Misc: log view with level filter
- [ ] Misc: themes
- [ ] Misc: about
- [ ] Improvements over Luke (§6). Done already: deleted documents (ADR 0006, the filter's `includeDeleted`), binary values as text or hex (ADR 0006), cancellation of long operations (ADR 0005), KNN vectors (ADR 0040), point values (ADR 0041), highlighting of hits (ADR 0042), the per-segment document view (ADR 0051). Left:
  - [ ] KNN: HNSW graph inspection (a node's levels and neighbours through `HnswGraphProvider`, on 9.12 too, which has it), and a recall check of approximate against exact (left out of ADR 0040)

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
