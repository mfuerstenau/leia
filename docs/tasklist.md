# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed.

### Runtimes
- [ ] Lucene 7.7.3 for 6.x and 7.x (`leia-lucene7`), on runtime interface 3 with `leia-lucene5` as the template (ADR 0046): points (6.0), index sort (6.2), soft deletes (7.4); test indexes written with 6.6.6 and 7.7.3
- [ ] Lucene 9.12.3 for 8.x (`leia-lucene9`), with its backward-codecs; a Lucene 8.11.4 test index exists already (`leia-core`'s `lucene8`, now expected to give `MISSING_RUNTIME`)
- [ ] Check whether the facades of 5, 7 and 9 can share sources (their APIs are close), e.g. one source set compiled against each version with small per-version adapters
- [ ] A feature matrix per runtime in ADR 0044 or a doc page, kept in sync with each module's feature set

## Bugs

Found by the manual run on `lucene4`/`lucene5` (2026-10-12):

- [ ] A JVM warning breaks the screen (hard rule 4): on opening a 4.x/5.x index, the relocated 5.5 `MMapDirectory` unmaps through `sun.misc.Unsafe::invokeCleaner`, and Java 25 prints "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called …" to `VM.initialErr()`, the JVM's own stderr, which `System.setErr` doesn't redirect. It is not in `leia.log`; the TUI keeps stale cells until a redraw. Options: `setUseUnmap(false)` in `leia-lucene5` (and later `leia-lucene7`), `--sun-misc-unsafe-memory-access=allow` in the scripts (but Java < 23 rejects it before `Launcher` can say what is needed), or redirecting fd 2 natively while the UI runs
- [ ] Norms of indexes before Lucene 7 are decoded as lengths with `SmallFloat.byte4ToInt` (`Documents.normLength`): 4.x/5.x `body` shows `118 → length 14360–15383`. Before 7, the default and BM25 similarities stored `floatToByte315(1/√length)` (118 = 0.375, length 6–7); 2.x/3.x likely the same. The TUI needs the index's version (or the server decodes)
- [ ] An anonymous format class shows its relocated full name: 4.10's norms format is `io.github.mfuerstenau.leia.lucene5.shaded.lucene.codecs.lucene410.Lucene410Codec$3` (`Lucene5Commits.formatName`, same fallback in `leia-core`'s `CommitReader`); use the superclass's simple name (`Lucene49NormsFormat`)
- [ ] Small: the Search screen's Query panel still says "x explains the selected hit" and "s similarity and sort" when the features lack them (both are refused with "not for Lucene 5.x")
- [ ] Small: the segment details show "0 soft-deleted", "blocks no" and "no index sort" for versions without soft deletes (7.4), index sorting (6.2) or blocks
- [ ] Small: without a reader, a commit's details say "Not the opened commit: its documents are not shown on the other screens", though no commit is opened
- [ ] Small: the level 1 check of a 4.x index ends with Lucene's "Run checkindex without -fast for full verification"; in leia that is level 2 or 3

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter, cross-screen navigation (`docs/luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6). Done already: deleted documents (ADR 0006, the filter's `includeDeleted`), binary values as text or hex (ADR 0006), cancellation of long operations (ADR 0005), KNN vectors (ADR 0040), point values (ADR 0041), highlighting of hits (ADR 0042). Left:
  - [ ] Per-segment document view: a document's segment and local id, stepping within one segment, and a jump from a segment on the commits screen to its documents
  - [ ] KNN: HNSW graph inspection (a node's levels and neighbours through `HnswGraphProvider`), and a recall check of approximate against exact (left out of ADR 0040)

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
