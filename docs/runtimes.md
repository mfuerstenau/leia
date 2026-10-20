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
| `RECONSTRUCT` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `FILTER` | ✓⁵ | ✓ | ✓ | ✓ | ✓ |
| `TERMS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `POSTINGS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `POINTS` | – | – | ✓² | ✓² | ✓ |
| `DOC_VALUES` | – | ✓¹ | ✓¹ | ✓¹ | ✓ |
| `KNN_VECTORS` | – | – | – | ✓³ | ✓ |
| `COMMITS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `SEARCH` | – | – | – | – | ✓ |
| `CLASSIC_SEARCH` | ✓ | ✓ | ✓ | ✓⁴ | ✓ |
| `TERM_SEARCH` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `SCORING` | – | – | – | ✓ | ✓ |
| `DOC_VALUES_SEARCH` | – | – | – | ✓⁶ | ✓ |
| `STANDARD_SEARCH` | – | – | – | ✓ | ✓ |
| `MORE_LIKE_THIS` | – | – | – | ✓ | ✓ |
| `EXPLAIN` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `HIGHLIGHT` | – | – | – | ✓ | ✓ |
| `STORED_SCAN` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `CHECK_INDEX` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `CHECK_LEVELS` | – | ✓ | ✓ | ✓ | ✓ |
| `EXPORT_TERMS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `REOPEN` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `AUTO_STOP_WORDS` | ✓ | ✓ | ✓ | ✓ | ✓ |

1. Only if a field has doc values (ADR 0046 §6).
2. Only if a field has points (ADR 0048 §3).
3. Only if a field has KNN vectors, which no 8.x segment has: only the 9.x segments of an index created by 8.x (ADR 0049 §6).
4. With `KNN_VECTORS`, `CLASSIC_SEARCH` takes a KNN query too, approximate or exact, with a filter (ADR 0049 §6).
5. A query filter takes no deleted documents: Lucene 3.6 doesn't read their postings, so `startFilter` refuses a query with `includeDeleted` (ADR 0056 §4). A pattern filter takes them.
6. Reported without regard to the fields; the TUI offers doc values queries with `DOC_VALUES` too, which needs a field with doc values (ADR 0066).

`SEARCH` is every part of search; a runtime reports the parts it has, such as `CLASSIC_SEARCH`, `TERM_SEARCH`, `SCORING`, `DOC_VALUES_SEARCH`, `STANDARD_SEARCH` and `MORE_LIKE_THIS`, and the TUI offers a part with `SEARCH` or with that part (ADR 0058, ADR 0064, ADR 0066). `SCORING` is the similarity and sort of the queries a runtime has.

Every runtime reports the open reader's segments in `getIndexStats`, from runtime interface 6 on (ADR 0051), so the documents screen shows and pins segments on every index with a reader.

**Without a reader** (ADR 0021), an index has `COMMITS` and `CHECK_INDEX`, and `CHECK_LEVELS` too unless its first index goes to a runtime without levels, which is 3.6.2 for 2.x and 3.x (ADR 0045 §4, ADR 0046 §6). Indexes of 4.x to 8.x go to Lucene 10 then, which lists their commits as unreadable.

## Differences

Where the runtimes give the same feature in different ways. The facades of 5.5.5, 7.7.3 and 9.12.3 are copies of each other (ADR 0050), so a fix to one belongs in the others; this table is where their drift shows. Not checked by a test.

| Area | 3.6.2 | 5.5.5 | 7.7.3 | 9.12.3 | 10.5.2 |
|---|---|---|---|---|---|
| Terms | strings, as UTF-8 | bytes | bytes | bytes | bytes |
| Postings of deleted documents | skipped, though `docFreq` counts them | marked | marked | marked | marked |
| Query filter with deleted documents (ADR 0056) | refused | ✓, through leaves without deletions | as 5.5.5 | as 5.5.5 | ✓, through readers without deletions |
| Filter cancellation | per segment and every 1024 matches | per segment and every 1024 matches | as 5.5.5 | through the searcher's `QueryTimeout` | through `ExitableDirectoryReader` |
| Term query (ADR 0058) | the bytes decoded as UTF-8 | the bytes | the bytes | the bytes | the bytes |
| Explain similarity (ADR 0057) | the runtime's default, classic TF-IDF | as 3.6.2 | the runtime's default, BM25 | the one chosen (ADR 0064) | the one chosen |
| Similarity and sort (ADR 0064) | the runtime's default, best score first | as 3.6.2 | as 3.6.2 | the ones chosen | the ones chosen |
| Explain cancellation | before and after | before and after | before and after | through the searcher's `QueryTimeout` | through `ExitableDirectoryReader` |
| Explanation of a deleted document | no match, by the facade | no match, by Lucene | no match, by Lucene | no match, by Lucene | no match, by Lucene |
| Explained value against the score | the same | the same | BM25 may differ in the last bits | the same | the same |
| `totalTermFreq` | counted from the live postings | recorded | recorded | recorded | recorded |
| Export terms | reads every posting, as its term pages count `totalTermFreq` from them | the term dictionary only | the term dictionary only | the term dictionary only | the term dictionary only |
| Auto stop words scan (ADR 0061) | reads every posting, as export terms does | the term dictionary only | the term dictionary only | the term dictionary only | the term dictionary only |
| Term statistics per field | absent | ✓ | ✓ | ✓ | ✓ |
| Offsets in positions | – | ✓ | ✓ | ✓ | ✓ |
| Commit's Lucene version | the newest segment's | recorded from 5.3, else the newest segment's | recorded | recorded | recorded |
| Created major | – (the oldest segment's) | – (the oldest segment's) | recorded from 7.0; 6 for a commit of 6.x | recorded | recorded |
| Segment id, codec, formats | – | codec and formats; the id from 5.0 | ✓ | ✓ | ✓ |
| Soft deletes, index sort, min version | – | – | soft deletes from 7.4, index sort from 6.2, min version from 7.0 | ✓ | ✓ |
| Formats: points, KNN vectors | – | – | points | both; KNN vectors from 9.0 segments | both |
| Rebuild (ADR 0059, ADR 0062) | terms only, no points, no total; fields with payloads from the postings, since its term vectors have none; deleted documents through raw postings | terms only: no points | terms, and points through `PointValues.intersect` | terms, and points through the `PointTree` | as 9.12.3 |
| Point cell sizes | – | – | computed from the tree's shape (ADR 0048 §5) | from the `PointTree` | from the `PointTree` |
| Norms, decoded by the created major (ADR 0047, ADR 0048 §3) | `floatToByte315(1/√length)` | `floatToByte315(1/√length)` | 6: `floatToByte315(1/√length)`, 7: the length | the length | the length |
| Check levels | none | 1 checksums only, 2 default, 3 `crossCheckTermVectors` | as 5.5.5 | as 5.5.5, but 3 is `doSlowChecks`; one thread | Lucene's own `setLevel` |
| `-fast` hint in the check | – | rewritten to leia's levels | not given | not given | – |
| Search analyzers | 6 of lucene-core | 41 of analyzers-common | 42 (+ Bengali) | 48 (+ Estonian, Nepali, Serbian, Tamil, Telugu, `CollationKeyAnalyzer`) | every preset of `getAnalysisComponents`, the extension jars' included |
| Custom analyzers, shingles (ADR 0067) | – | – | – | ✓, with 9.12's own components; one only Lucene 10 has is `NOT_SUPPORTED` | ✓ |
| Preset options (ADR 0067) | stop words, from a file too, and the longest token | as 3.6.2 | as 3.6.2 | all but Kuromoji's, Nori's and ICU's, checked as on Lucene 10 | all |
| Token limit | `consumeAllTokens` ignored (it changes no token) | ✓ | ✓ | ✓ | ✓ |
| Analysis tab (ADR 0067) | – | – | – | – | ✓, `Analysis` of leia-lucene10 when routed to it |
| Extension jars (ADR 0068) | – (relocated service files) | – | – | – | ✓, `Extensions` of leia-lucene10; leia-core's lists until step 3 |
| Unmapping | never (no `sun.misc.Cleaner`) | only with `-Dsun.misc.unsafe.memory.access=allow` | as 5.5.5 | memory segments | memory segments |
| KNN search cancellation | – | – | – | through the searcher's `QueryTimeout` | through the searcher's `QueryTimeout` |
| Highlight (ADR 0063) | – | – (no `UnifiedHighlighter`) | – (its `UnifiedHighlighter` differs; not yet) | ✓, with its search analyzers | ✓, with every analyzer of `startSearch` |
| Highlight cancellation (ADR 0063) | – | – | – | before each field of each document | through `ExitableDirectoryReader` |
| Standard parser (ADR 0066, ADR 0069) | – | – | – | with dates, through a copy of leia-core's processor of term ranges | leia-core's, with dates |
| Classic dates (ADR 0026, ADR 0069) | ✓, in a zone the parser holds itself (3.6 has none); without a resolution, a date becomes a `DateField` term, as 3.6 does | ✓ | ✓ | ✓ | ✓ |
| Fuzzy terms (ADR 0069) | a minimum similarity of 0.5; the edits are ignored | the edits | the edits | the edits | the edits |
| Split on whitespace (ADR 0069) | always | always | as chosen | as chosen | as chosen |
| Segment blocks (ADR 0069) | – | – | – | recorded from 9.9 | recorded |
| Directory class in the stats (ADR 0069) | ✓; `fs` gives `NIOFSDirectory`, as 3.6 can't unmap | ✓ | ✓ | ✓ | ✓ |
| More-Like-This (ADR 0066) | – | – | – | built from the reader before the rewrite, its terms worked out as leia-core does | leia-core's |
| More-Like-This cancellation | – | – | – | between fields while the terms are counted, then through the searcher's `QueryTimeout` | through `ExitableDirectoryReader` |
| Doc values queries (ADR 0066) | – | – | – | Lucene's slow queries, and leia-core's for `BINARY`, copied | leia-core's |
