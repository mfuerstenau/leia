# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed.

### Runtimes
- [ ] Lucene 9.12.3 for 8.x (`leia-lucene9`), with its backward-codecs; a Lucene 8.11.4 test index exists already (`leia-core`'s `lucene8`, now expected to give `MISSING_RUNTIME`)
- [ ] Check whether the facades of 5, 7 and 9 can share sources (their APIs are close), e.g. one source set compiled against each version with small per-version adapters
- [ ] A feature matrix per runtime in ADR 0044 or a doc page, kept in sync with each module's feature set

## Bugs

None open. The last ones, from the manual run on `lucene4`/`lucene5`, are in `docs/tasklist-done.md`.

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
