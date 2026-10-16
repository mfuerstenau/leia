# ADR 0045 — The Lucene 3.6 runtime finished: commits, check, top terms, several indexes, analyzers

- **Status:** Accepted
- **Date:** 2026-10-12
- **Amends:** ADR 0044 §3 (routing of walks and no-reader opens), §4 (the features of 3.6), §5 and "Left for later"; ADR 0021 §6 (no-reader opens of old indexes); ADR 0022 (the check routes per directory; `CHECK_LEVELS`)

## Context

ADR 0044 made Lucene 3.6.2 a relocated runtime for indexes of Lucene 2.x and 3.x, but left these out: the commits, `CheckIndex`, top terms, a 3.x test index, several old indexes under one path, and analyzers other than 3.6's `StandardAnalyzer` for search. All of them gave `NOT_SUPPORTED`, because `CurrentIndex.opened()` refuses for a runtime, and an old index refused to open without a reader.

The 3.6 runtime is the template for those of 5, 7 and 9, so the shape of the runtime interface matters more than the 3.6 details.

## Decision

1. **Runtime interface version 2.** `LuceneRuntime.API_VERSION` is 2, bumped once for all of this, since `open` and `Search.parse` change shape. JDK types and records only, as before.
   - `RuntimeIndex open(List<Path> dirs, DirectoryKind)` opens each directory and combines those that open as one index. A directory that fails is skipped and listed in `RuntimeIndex.skipped()` (`Skipped(path, message)`); if none opens, the first error is thrown, naming its directory.
   - `RuntimeIndex.stats()` gives `Stats(numDocs, maxDoc, indexes, fields)`, with `IndexStats(path, docBase, segmentsFile, generation, version, numDocs, maxDoc, segmentVersions, userData)` per directory, read from the opened commit.
   - `Optional<Commits> commits()` and `Optional<Check> check()` on `LuceneRuntime`, not on the index. Both are path-based, need no reader, and keep no state, so they serve an open with a reader, one without, and a directory that was skipped.
   - `Commits`: `commits(dir, kind)`, newest first, with the unreadable `segments_N`; `commit(dir, kind, generation)`, empty if there is no such file. A commit doesn't say whether it is current; the server marks the one whose generation was opened.
   - `Check`: `check(dir, kind, line, progress)`, whose result mirrors the protocol's `IndexCheck` and `SegmentCheck` without path, skipped and codec. `progress` may throw to cancel; the check then throws what it threw.
   - `Terms.topTerms(field, topN, progress)` gives `FieldTerms(uniqueTerms, topTerms)`.
   - `Search.analyzers()` names the analyzers the runtime builds, and `parse` takes an `AnalyzerSpec`: `Named(name, stopwords?, maxTokenLength?)`, `PerField(fallback, fields)` or `LimitTokenCount(analyzer, max)`. One the runtime can't build is an `UnsupportedAnalyzerException`, whose message says what it has.
2. **Lucene 3.6 implements all of it** (`leia-lucene3`):
   - Several directories open as a 3.6 `MultiReader`; each part keeps its own `docBase`, and the field infos are merged.
   - `Lucene3Commits` lists `segments_N` (not `segments.gen`) and reads each with `SegmentInfos.read(dir, name)` through a directory without locks. The commit's Lucene version is its newest segment's (3.x doesn't record the writer). A segment gives its `delGen` from its deletions file's name, its size (absent if a file can't be stat'ed, ADR 0009 §5), its diagnostics, and as attributes its doc store (`docStoreSegment`, `docStoreOffset`, `docStoreIsCompoundFile`), `hasProx` and `hasVectors`. The files are `SegmentInfo.files()`, which in 3.6 lists only those that exist, plus `segments_N`.
   - `Lucene3Check` runs 3.6's `CheckIndex` through a directory that refuses every write, takes no lock, and whose inputs extend `BufferedIndexInput`: each refill asks `progress`, which keeps 3.6's reads byte by byte fast and still cancellable. Each line of the report asks it too. 3.6 catches every `Throwable` of a segment and goes on, so the first exception from `progress` is kept, thrown again by every later read, and rethrown when `checkIndex` returns. `fixIndex` is never called; nothing interrupts a thread (hard rule 7). A segment's error is the first of its field norm, term index, stored field and term vector errors, or "open reader failed".
   - Top terms are one `TermEnum` pass that keeps the top N by `docFreq`, ties keeping the earlier term as leia-core does, and counts the terms; `totalTermFreq` is then counted from the postings of the N kept.
   - The analyzers are those of lucene-core 3.6 at `Version.LUCENE_36`: `StandardAnalyzer`, `ClassicAnalyzer`, `WhitespaceAnalyzer`, `SimpleAnalyzer`, `StopAnalyzer` and `KeywordAnalyzer`, with stop words where 3.6 takes them (standard, classic, stop) and the longest token for standard and classic; `PerFieldAnalyzerWrapper` and `LimitTokenCountAnalyzer` around them. The plain `standard` keeps 3.6's English stop words, as indexes of that time were written.
   - A 3.x test index, `lucene3`, written at build time by `Lucene3IndexWriter` on lucene-core 3.6.2 (`luceneRuntime.testIndex`): two commits kept by `NoDeletionPolicy`, each with user data; a plain and a compound segment; a deletion; a `NumericField`, a docs-only field without norms, term vectors, and skewed term frequencies.
3. **Routing of a path** (amends ADR 0044 §3). The server finds the index directories of the path itself, with leia-core's `IndexFinder` (now public), and reads the first one's `SegmentsHeader`. **The first index decides which Lucene opens the path:**
   - Below 9 with a reader: its runtime, or `MISSING_RUNTIME`. The directories whose header names a major the runtime reads go to `runtime.open`; the others are skipped with "Written by Lucene X; open it on its own". A directory without a readable header is given to the runtime, which skips it if it fails.
   - Otherwise Lucene 10's `ReadOnlyIndex`, as before, which skips the old indexes below it as it skips any index that fails.
4. **No reader on an old index** (amends ADR 0021 §6, ADR 0044 §3). `DIRECTORY_ONLY` opens a 2.x or 3.x index as any other: `DirectoryOnlyIndex`. If the first index is below 4 and no runtime for it is loaded, the open fails with `MISSING_RUNTIME`.
5. **The commits and the check route per directory.** `IndexDirectories` in `server/index` is the one place that lists the directories of the open path, of either kind: each with its path, `DirectoryKind`, opened generation (if any), place, and the Lucene that reads it (`Route`): the directories a runtime opened go to that runtime; any other one by its header, an old one with a loaded runtime to the runtime, one below 4 without a runtime to `Missing`, the rest to Lucene 10, so 4.x to 8.x stay as they were.
   - `getCommits`/`getCommit` on `Missing` fail with `MISSING_RUNTIME`.
   - `startCheckIndex` checks the opened directories, then the skipped ones (ADR 0022), each with the Lucene that reads it, in one job and one log. A Lucene 10 open with old indexes skipped below it checks those with 3.6, and the other way round. A runtime ignores the level and says so on the output ("Checking with Lucene 3.6.2, which has no levels"); `Missing` is a per-index `error` naming the jar.
6. **Features** (amends ADR 0044 §4, ADR 0022).
   - A new feature, `CHECK_LEVELS`: the check's `level`. Lucene 10 has it, so `Feature.all()` and `Feature.withoutReader()` include it; an open without a reader whose first index is below 4 leaves it out, and so does every runtime.
   - 3.6 with a reader has `INDEX_STATS`, `DOCUMENTS`, `STORED_VALUES`, `TERM_VECTORS`, `TERMS`, `POSTINGS`, `TOP_TERMS`, `CLASSIC_SEARCH`, `COMMITS` and `CHECK_INDEX`. `TOP_TERMS` comes with the `Terms` group, `COMMITS` and `CHECK_INDEX` with the runtime's `commits()` and `check()`.
7. **The protocol.** `commits.Segment`'s `id`, `codec` and `formats` are `Optional`: 3.x has no segment id and no codec. Lucene 10 always fills them.
8. **The search analyzer of a runtime** (amends ADR 0044 §5). The server maps the protocol's `Analyzer` to an `AnalyzerSpec` (`RuntimeAnalyzers`): `standard` to `Named("StandardAnalyzer")`; a `preset` by its class's simple name if the runtime has it, with `stopwords` (words or file) and `maxTokenLength`; `fields` to `PerField`; `limitTokenCount` around it (`consumeAllTokens` changes no token, so it is dropped). A `custom` analyzer, `shingles`, `autoStopWords`, a preset the runtime lacks, any other preset option, or an `UnsupportedAnalyzerException` give `NOT_SUPPORTED`, naming the analyzers the runtime has. The TUI shows that message as the search error.
9. **The TUI.**
   - Without `CHECK_LEVELS`, the check screen leaves `l` and the level out of the legend and the title, starts the check without a level, and answers `l` with the usual notice ("Check level: not for Lucene 2.x").
   - The commits screen shows "–" for a missing codec or id, and leaves out the codec formats of a segment without them; 3.x's doc store is in the segment's attributes.
   - The overview counts a field as having postings by its statistics or, when they are absent as for 3.x, by its index options, so its top terms can be scanned.

## Consequences

- The runtimes of 5, 7 and 9 implement interface 2; `Commits` and `Check` are path-based for them too, so a skipped index of their majors gets its commits and check through them.
- A walk with indexes of several Lucene majors opens only those of the first one's Lucene; the others are listed as skipped and still checked. Opening them together would need several runtimes in one index, which is out of scope.
- Every runtime jar built for interface 1 is refused (`INVALID_JAR`) and has to be built again.
- Left for later: per-field postings statistics for 3.x (`IndexField.terms` stays absent; a full scan at open would be too slow), the reason of a failed open reader in 3.6's check (3.6 prints it only), and auto stop words on 3.6.
