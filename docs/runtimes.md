# Runtimes: features and differences

leia reads indexes of Lucene 9 and 10 with Lucene 10.5.2 itself, and older ones with a relocated Lucene, a runtime jar in `runtimes/` beside `leia.jar` (ADR 0044):

| Runtime | Jar | Reads indexes of |
|---|---|---|
| 3.6.2 | `leia-lucene3.jar` | 2.1 – 3.6 |
| 5.5.5 | `leia-lucene5.jar` | 4.x, 5.x |
| 7.7.3 | `leia-lucene7.jar` | 6.x, 7.x |
| 9.12.3 | `leia-lucene9.jar` | 8.x, also once 9.x wrote to them |
| 10.5.2 | `leia.jar` | 9.x, 10.x |

The first index of a path decides which Lucene opens it, by the major in its newest `segments_N` (`SegmentsHeader`, ADR 0044 §3, ADR 0045 §3). `openIndex` reports that Lucene as `luceneRuntime`, and what works on the index as `features`. Lucene 10 has every feature; a runtime has those of the groups it implements (`RuntimeResults.features`), and some only if a field of the index has the data.

## Features

`RuntimeFeaturesTest` checks this table against what `openIndex` reports for each runtime's newest test index: its rows against `Feature`, and each column against the features of that runtime. A cell starting with ✓ is a feature the runtime has.

| Feature | 3.6.2 | 5.5.5 | 7.7.3 | 9.12.3 | 10.5.2 |
|---|---|---|---|---|---|
| `INDEX_STATS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `TOP_TERMS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DOCUMENTS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `STORED_VALUES` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `TERM_VECTORS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `POSITIONS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `RECONSTRUCT` | – | – | – | – | ✓ |
| `FILTER` | – | – | – | – | ✓ |
| `TERMS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `POSTINGS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `POINTS` | – | – | ✓² | ✓² | ✓ |
| `DOC_VALUES` | – | ✓¹ | ✓¹ | ✓¹ | ✓ |
| `KNN_VECTORS` | – | – | – | ✓³ | ✓ |
| `COMMITS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `SEARCH` | – | – | – | – | ✓ |
| `CLASSIC_SEARCH` | ✓ | ✓ | ✓ | ✓⁴ | ✓ |
| `EXPLAIN` | – | – | – | – | ✓ |
| `HIGHLIGHT` | – | – | – | – | ✓ |
| `STORED_SCAN` | – | – | – | – | ✓ |
| `CHECK_INDEX` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `CHECK_LEVELS` | – | ✓ | ✓ | ✓ | ✓ |
| `EXPORT_TERMS` | – | – | – | – | ✓ |
| `REOPEN` | – | – | – | – | ✓ |
| `AUTO_STOP_WORDS` | – | – | – | – | ✓ |

1. Only if a field has doc values (ADR 0046 §6).
2. Only if a field has points (ADR 0048 §3).
3. Only if a field has KNN vectors, which no 8.x segment has: only the 9.x segments of an index created by 8.x (ADR 0049 §6).
4. With `KNN_VECTORS`, `CLASSIC_SEARCH` takes a KNN query too, approximate or exact, with a filter (ADR 0049 §6).

Every runtime reports the open reader's segments in `getIndexStats`, from runtime interface 6 on (ADR 0051), so the documents screen shows and pins segments on every index with a reader.

**Without a reader** (ADR 0021), an index has `COMMITS` and `CHECK_INDEX`, and `CHECK_LEVELS` too unless its first index goes to a runtime without levels, which is 3.6.2 for 2.x and 3.x (ADR 0045 §4, ADR 0046 §6). Indexes of 4.x to 8.x go to Lucene 10 then, which lists their commits as unreadable.

## Differences

Where the runtimes give the same feature in different ways. The facades of 5.5.5, 7.7.3 and 9.12.3 are copies of each other (ADR 0050), so a fix to one belongs in the others; this table is where their drift shows. Not checked by a test.

| Area | 3.6.2 | 5.5.5 | 7.7.3 | 9.12.3 | 10.5.2 |
|---|---|---|---|---|---|
| Terms | strings, as UTF-8 | bytes | bytes | bytes | bytes |
| Postings of deleted documents | skipped, though `docFreq` counts them | marked | marked | marked | marked |
| `totalTermFreq` | counted from the live postings | recorded | recorded | recorded | recorded |
| Term statistics per field | absent | ✓ | ✓ | ✓ | ✓ |
| Offsets in positions | – | ✓ | ✓ | ✓ | ✓ |
| Commit's Lucene version | the newest segment's | recorded from 5.3, else the newest segment's | recorded | recorded | recorded |
| Created major | – (the oldest segment's) | – (the oldest segment's) | recorded from 7.0; 6 for a commit of 6.x | recorded | recorded |
| Segment id, codec, formats | – | codec and formats; the id from 5.0 | ✓ | ✓ | ✓ |
| Soft deletes, index sort, min version | – | – | soft deletes from 7.4, index sort from 6.2, min version from 7.0 | ✓ | ✓ |
| Formats: points, KNN vectors | – | – | points | both; KNN vectors from 9.0 segments | both |
| Point cell sizes | – | – | computed from the tree's shape (ADR 0048 §5) | from the `PointTree` | from the `PointTree` |
| Norms, decoded by the created major (ADR 0047, ADR 0048 §3) | `floatToByte315(1/√length)` | `floatToByte315(1/√length)` | 6: `floatToByte315(1/√length)`, 7: the length | the length | the length |
| Check levels | none | 1 checksums only, 2 default, 3 `crossCheckTermVectors` | as 5.5.5 | as 5.5.5, but 3 is `doSlowChecks`; one thread | Lucene's own `setLevel` |
| `-fast` hint in the check | – | rewritten to leia's levels | not given | not given | – |
| Search analyzers | 6 of lucene-core | 41 of analyzers-common | 42 (+ Bengali) | 47 (+ Estonian, Nepali, Serbian, Tamil, Telugu) | every analyzer of `startSearch`, custom ones included |
| Unmapping | never (no `sun.misc.Cleaner`) | only with `-Dsun.misc.unsafe.memory.access=allow` | as 5.5.5 | memory segments | memory segments |
| KNN search cancellation | – | – | – | through the searcher's `QueryTimeout` | through the searcher's `QueryTimeout` |
