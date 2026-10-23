# Runtimes: features and differences

leia reads every index through a runtime (ADR 0071), a folder in `runtimes/` beside `leia.jar`, which holds no Lucene itself (ADR 0044, ADR 0072): indexes of Lucene 9 and 10, and those whose version it can't detect, with Lucene 10.5.2 in the jar chain; older ones with an older Lucene in a class loader of its own. Each folder has the runtime's adapter jar and the official Lucene jars it lists, which `D` on leia's start screen downloads, or `leia --download-runtimes --runtime <folder>` unattended (ADR 0073, ADR 0080). Without its folder, an index opens only without a reader, and the about screen says what is missing:

| Runtime | Folder | Reads indexes of |
|---|---|---|
| 3.6.2 | `runtimes/lucene3` | 2.1 – 3.6 |
| 5.5.5 | `runtimes/lucene5` | 4.x, 5.x |
| 7.7.3 | `runtimes/lucene7` | 6.x, 7.x |
| 9.12.3 | `runtimes/lucene9` | 8.x, also once 9.x wrote to them |
| 10.5.2 | `runtimes/lucene10` | 9.x, 10.x, and an unknown version |

The first index of a path decides which Lucene opens it, by the major in its newest `segments_N` (`SegmentsHeader`, ADR 0044 §3, ADR 0045 §3). `openIndex` reports that Lucene as `luceneRuntime`, and what works on the index as `features`. A runtime has the features of the groups it implements (`RuntimeResults.features`), and some only if a field of the index has the data; 10.5.2 has every group.

## Features

`RuntimeFeaturesTest` checks this table against what `openIndex` reports for each runtime's newest test index: its rows against `Feature`, and each column against the features of that runtime. A cell starting with ✓ is a feature the runtime has. The condensed table of the user README in the distribution (`leia-tui/src/dist/README.md`, ADR 0083) is checked against this one by the same test.

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
| `POINTS` | – | – | ✓² | ✓² | ✓² |
| `DOC_VALUES` | – | ✓¹ | ✓¹ | ✓¹ | ✓¹ |
| `KNN_VECTORS` | – | – | – | ✓³ | ✓³ |
| `COMMITS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `CLASSIC_SEARCH` | ✓ | ✓ | ✓ | ✓⁴ | ✓ |
| `TERM_SEARCH` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `SCORING` | – | ✓ | ✓ | ✓ | ✓ |
| `DOC_VALUES_SEARCH` | – | ✓⁶ | ✓⁶ | ✓⁶ | ✓ |
| `STANDARD_SEARCH` | – | ✓ | ✓ | ✓ | ✓ |
| `MORE_LIKE_THIS` | – | ✓ | ✓ | ✓ | ✓ |
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
3. Only if a field has KNN vectors. On 9.12.3, no 8.x segment has them: only the 9.x segments of an index created by 8.x (ADR 0049 §6).
4. With `KNN_VECTORS`, `CLASSIC_SEARCH` takes a KNN query too, approximate or exact, with a filter (ADR 0049 §6).
5. A query filter takes no deleted documents: Lucene 3.6 doesn't read their postings, so `startFilter` refuses a query with `includeDeleted` (ADR 0056 §4). A pattern filter takes them.
6. Reported without regard to the fields; the TUI offers doc values queries with `DOC_VALUES` too, which needs a field with doc values (ADR 0066).

A runtime reports the parts of search it has, such as `CLASSIC_SEARCH`, `TERM_SEARCH`, `SCORING`, `DOC_VALUES_SEARCH`, `STANDARD_SEARCH` and `MORE_LIKE_THIS`, and the TUI offers a part with that feature (ADR 0058, ADR 0064, ADR 0066, ADR 0071). `SCORING` is the similarity and sort of the queries a runtime has.

Every runtime reports the open reader's segments in `getIndexStats`, from runtime interface 6 on (ADR 0051), so the documents screen shows and pins segments on every index with a reader.

**Without a reader** (ADR 0021), an index has `COMMITS` and `CHECK_INDEX`, and `CHECK_LEVELS` too if its first index goes to a runtime with levels, which 3.6.2 for 2.x and 3.x isn't (ADR 0045 §4, ADR 0046 §6). An index whose runtime isn't loaded opens too; its commits and check then say which runtime jar is missing (ADR 0071).

## Differences

Where the runtimes give the same feature in different ways. The facades of 5.5.5, 7.7.3 and 9.12.3 are copies of each other (ADR 0050), so a fix to one belongs in the others; this table is where their drift shows. Not checked by a test.

| Area | 3.6.2 | 5.5.5 | 7.7.3 | 9.12.3 | 10.5.2 |
|---|---|---|---|---|---|
| Terms | strings, as UTF-8 | bytes | bytes | bytes | bytes |
| Postings of deleted documents | skipped, though `docFreq` counts them | marked | marked | marked | marked |
| Query filter with deleted documents (ADR 0056) | refused | ✓, through leaves without deletions | as 5.5.5 | as 5.5.5 | ✓, through readers without deletions |
| Filter cancellation | per segment and every 1024 matches | per segment and every 1024 matches | as 5.5.5 | through the searcher's `QueryTimeout` | through `ExitableDirectoryReader` |
| Term query (ADR 0058) | the bytes decoded as UTF-8 | the bytes | the bytes | the bytes | the bytes |
| Explain similarity (ADR 0057) | the runtime's default, classic TF-IDF | the one chosen (ADR 0076); the TUI's default is BM25, not 5.5's TF-IDF | the one chosen (ADR 0076) | the one chosen (ADR 0064) | the one chosen |
| Similarity and sort (ADR 0064, ADR 0076) | the runtime's default, best score first | the ones chosen, with a port of 6.4's `BooleanSimilarity`; the TUI's default is BM25, not 5.5's TF-IDF | the ones chosen | the ones chosen | the ones chosen |
| Explain cancellation | before and after | before and after | before and after | through the searcher's `QueryTimeout` | through `ExitableDirectoryReader` |
| Explanation of a deleted document | no match, by the facade | no match, by Lucene | no match, by Lucene | no match, by Lucene | no match, by Lucene |
| Explained value against the score | the same | BM25 may differ in the last bits | BM25 may differ in the last bits | the same | the same |
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
| Check levels | none | 1 checksums only, 2 default, 3 `crossCheckTermVectors` | as 5.5.5 | as 5.5.5, but 3 is `doSlowChecks`; segments in parallel, as Lucene 10 | Lucene's own `setLevel` |
| `-fast` hint in the check | – | rewritten to leia's levels | not given | not given | – |
| Search analyzers | 6 of lucene-core | 42 of analyzers-common (+ `CollationKeyAnalyzer`) | 43 (+ Bengali, `CollationKeyAnalyzer`) | 48 (+ Estonian, Nepali, Serbian, Tamil, Telugu, `CollationKeyAnalyzer`) | every preset of `getAnalysisComponents`, the extension jars' included |
| Custom analyzers, shingles (ADR 0067, ADR 0075) | – | ✓, with 5.5's own components; shingles only within a phrase | ✓, with 7.7's own components | ✓, with 9.12's own components; one only Lucene 10 has is `NOT_SUPPORTED` | ✓ |
| Preset options (ADR 0067, ADR 0075) | stop words, from a file too, and the longest token | as 9.12.3; `StopAnalyzer` has default stop words | as 9.12.3; `StopAnalyzer` has default stop words | all but Kuromoji's, Nori's and ICU's, checked as on Lucene 10 | all |
| Token limit | `consumeAllTokens` ignored (it changes no token) | ✓ | ✓ | ✓ | ✓ |
| Analysis tab (ADR 0067) | – | – | – | – | ✓, `Analysis` of leia-lucene10; without a runtime that has one, the tab says so (ADR 0071) |
| Extension jars (ADR 0068) | – (a class loader of its own, ADR 0073) | – | – | – | ✓, `Extensions` of leia-lucene10 (ADR 0071) |
| Failure reasons (ADR 0070) | corrupt and unsupported format; no codecs to miss | corrupt, unsupported format, missing codec | as 5.5.5 | as 5.5.5 | leia-core's, unreadable included |
| Hit count without an exact count (ADR 0070) | every hit counted | every hit counted | every hit counted | stops after 1000 | stops after 1000 |
| Unmapping | never (no `sun.misc.Cleaner`) | only with `-Dsun.misc.unsafe.memory.access=allow` | as 5.5.5 | memory segments | memory segments |
| KNN search cancellation | – | – | – | through the searcher's `QueryTimeout` | through the searcher's `QueryTimeout` |
| Highlight (ADR 0063) | – | – (no `UnifiedHighlighter`) | – (its `UnifiedHighlighter` differs; not yet) | ✓, with its search analyzers | ✓, with every analyzer of `startSearch` |
| Highlight cancellation (ADR 0063) | – | – | – | before each field of each document | through `ExitableDirectoryReader` |
| Standard parser (ADR 0066, ADR 0069, ADR 0074) | – | with dates, through a copy of leia-core's processor of term ranges; no points, and Lucene's own lower-casing of expanded terms rather than the analyzer's | as 9.12.3 | with dates, through a copy of leia-core's processor of term ranges | leia-core's, with dates |
| Classic dates (ADR 0026, ADR 0069) | ✓, in a zone the parser holds itself (3.6 has none); without a resolution, a date becomes a `DateField` term, as 3.6 does | ✓ | ✓ | ✓ | ✓ |
| Fuzzy terms (ADR 0069) | a minimum similarity of 0.5; the edits are ignored | the edits | the edits | the edits | the edits |
| Split on whitespace (ADR 0069) | always | always | as chosen | as chosen | as chosen |
| Segment blocks (ADR 0069) | – | – | – | recorded from 9.9 | recorded |
| Directory class in the stats (ADR 0069) | ✓; `fs` gives `NIOFSDirectory`, as 3.6 can't unmap | ✓ | ✓ | ✓ | ✓ |
| More-Like-This (ADR 0066, ADR 0074) | – | as 7.7.3, but one count of each term over all the fields, its clause in the first field with the most documents | as 9.12.3, but with the live documents for the idf and each token counted once | built from the reader before the rewrite, its terms worked out as leia-core does | leia-core's |
| More-Like-This cancellation | – | between fields while the terms are counted, then per segment | as 5.5.5 | between fields while the terms are counted, then through the searcher's `QueryTimeout` | through `ExitableDirectoryReader` |
| Doc values queries (ADR 0066, ADR 0074) | – | the sandbox's range, terms and numbers queries, and leia-core's for `BINARY`, copied | Lucene's slow queries and the sandbox's for sets, and leia-core's for `BINARY`, copied | Lucene's slow queries, and leia-core's for `BINARY`, copied | leia-core's |
