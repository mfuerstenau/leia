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
| `KNN_GRAPH` | – | – | – | ✓⁷ | ✓⁷ |
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
| `CHECK_THREADS` | – | – | – | ✓ | ✓ |
| `EXPORT_TERMS` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `REOPEN` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `AUTO_STOP_WORDS` | ✓ | ✓ | ✓ | ✓ | ✓ |

1. Only if a field has doc values (ADR 0046 §6).
2. Only if a field has points (ADR 0048 §3).
3. Only if a field has KNN vectors. On 9.12.3, no 8.x segment has them: only the 9.x segments of an index created by 8.x (ADR 0049 §6).
4. With `KNN_VECTORS`, `CLASSIC_SEARCH` takes a KNN query too, approximate or exact, with a filter (ADR 0049 §6).
5. A query filter takes no deleted documents: Lucene 3.6 doesn't read their postings, so `startFilter` refuses a query with `includeDeleted` (ADR 0056 §4). A pattern filter takes them.
6. Reported without regard to the fields; the TUI offers doc values queries with `DOC_VALUES` too, which needs a field with doc values (ADR 0066).
7. With `KNN_VECTORS` (ADR 0085). A segment shows no graph if its format has none leia can read (a flat format, or those of 9.0 to 9.4, whose readers aren't an `HnswGraphProvider`), or if it was too small for Lucene 10 to build one.

A runtime reports the parts of search it has, such as `CLASSIC_SEARCH`, `TERM_SEARCH`, `SCORING`, `DOC_VALUES_SEARCH`, `STANDARD_SEARCH` and `MORE_LIKE_THIS`, and the TUI offers a part with that feature (ADR 0058, ADR 0064, ADR 0066, ADR 0071). `SCORING` is the similarity and sort of the queries a runtime has.

Every runtime reports the open reader's segments in `getIndexStats`, from runtime interface 6 on (ADR 0051), so the documents screen shows and pins segments on every index with a reader.

**Without a reader** (ADR 0021), an index has `COMMITS` and `CHECK_INDEX`, and `CHECK_LEVELS` and `CHECK_THREADS` too if its first index goes to a runtime with levels, which 3.6.2 for 2.x and 3.x isn't, or with threads, which only 9.12.3 and 10.5.2 are (ADR 0045 §4, ADR 0046 §6, ADR 0093). An index whose runtime isn't loaded opens too; its commits and check then say which runtime jar is missing (ADR 0071).

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
| Fields that conflict between indexes (ADR 0021 §5) | – (merges them; no schema) | skipped on a change of a doc values type or point shape already set; a field that gains them merges | as 5.5.5 | skipped on any change of a field's schema, and on another parent field | as 9.12.3 |
| Check levels | none | 1 checksums only, 2 default, 3 `crossCheckTermVectors` | as 5.5.5 | as 5.5.5, but 3 is `doSlowChecks`; segments in parallel, as Lucene 10 | Lucene's own `setLevel` |
| Check threads and selected segments (ADR 0093) | selected segments (`checkIndex(onlySegments)`); one thread | as 3.6.2 | as 3.6.2 | selected segments, on as many threads as asked for (default all processors, at most one per segment) | as 9.12.3 |
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
| KNN recall check (ADR 0086) | – | – | – | through 9.12's searches, each stopped through the searcher's `QueryTimeout` | through Lucene 10's |
| HNSW graph (ADR 0085) | – | – | – | 9.5's and 9.9's formats; no M, and an ordinal's document through `RandomAccessVectorValues` | every `HnswGraphProvider`, with M |
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

## Servers

`--server` reads the shards of an Elasticsearch or OpenSearch installation with its own Lucene, codecs and formats (ADR 0090): leia's adapter for its Lucene's major, `runtimes/lucene9` or `runtimes/lucene10`, over the jars of its `lib/`. It reads the indexes of that major and the one before, ahead of the runtimes above; an index it fails to open goes to the runtime above. The adapters are built for 9.12.3 and 10.5.2, so a server's older minor may lack what a feature needs: the Lucene 9 adapter leaves out KNN vectors, points and highlighting on a Lucene without their APIs (ADR 0091), and any other such feature is `NOT_SUPPORTED`. Tried with single-node servers on Java 25, through the calls of the screens:

| Server | Lucene | With `--server` |
|---|---|---|
| Elasticsearch 6.8.23 (5.x alike) | 7.7.3 (6.x) | not tried with a server (Lucene 7 is refused). Without `--server`, `runtimes/lucene7` reads their shards, those with a `completion` field too, whose `Completion50` postings are in its `lucene-suggest`: a 6.8.23 shard with a `text` and a `completion` field (3 documents, a compound segment) opened, listed the completion terms, showed `_source` and passed check index; also tested with the `lucene6completion` index (6.6, a `SuggestField`). No 5.x shard tried. The `completion090` shards of Elasticsearch 1.x and 2.x stay unreadable: that format is in Elasticsearch's own jar |
| Elasticsearch 7.17.28 | 8.11.3 | refused (Lucene 8). Without it, leia's Lucene 9.12.3 reads its shards, those with a `completion` field too, whose `Completion84` postings are in the `lucene-suggest` of `runtimes/lucene9` |
| Elasticsearch 8.0.1 | 9.0.0 | everything but KNN vectors, points and highlighting, which are left out, and sort, `NOT_SUPPORTED` (`Pruning`, Lucene 9.10); a search is cancelled only between segments (ADR 0091) |
| Elasticsearch 8.6.2 | 9.4.2 | as 8.0.1, but with points; the time series shards with `ES85BloomFilter` on `_id` too |
| Elasticsearch 8.9.2 | 9.7.0 | everything: text, completion, time series |
| Elasticsearch 8.12.2 | 9.9.2 | everything (`int8_hnsw` included) but the HNSW graph, `NOT_SUPPORTED` (`HnswGraphProvider`, Lucene 9.11) |
| Elasticsearch 8.19.23 | 9.12.2 | everything: `ES812Postings`, `ES87TSDB`, `ES87BloomFilter`, `int8_hnsw` and `bbq_hnsw`, logsdb with zstd |
| Elasticsearch 9.0.8 | 10.1.0 | everything but the HNSW graph, `NOT_SUPPORTED` (`HnswGraph.maxConn()`) |
| Elasticsearch 9.5.5 | 10.5.1 | everything |
| OpenSearch 2.11.1 | 9.7.0 | everything but the `knn_vector` index (the k-NN plugin's `KNN950Codec`); not tried with the plugin |
| OpenSearch 2.19.6 | 9.12.3 | everything, the `knn_vector` indexes too, with the plugin (`KNN9120Codec`, ADR 0092): `lucene` engine all; `faiss` and `nmslib` open, but their KNN search is `INTERNAL_ERROR` |
| OpenSearch 3.9.0 | 10.5.1 | everything, the `knn_vector` indexes of the `lucene` and `faiss` engines too, with the plugin (`KNN1040Codec`) |

OpenSearch's k-NN plugin, `plugins/opensearch-knn`, is loaded with the plugins and modules it extends, and what they extend (`extended.plugins`: Guava in OpenSearch 3's `transport-grpc`), and given a cluster service of its own, so that closing and checking a k-NN index work. The native engines' libraries aren't loaded (ADR 0092).

So the Lucene 9 adapter reads with any 9.x (tried from 9.0.0): below 9.5 without KNN vectors and highlighting, on 9.0 without points either, below 9.10 without sort, below 9.11 without the HNSW graph. The Lucene 10 one takes any 10.x (tried from 10.1.0); older minors lose the HNSW graph.
