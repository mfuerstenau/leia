# ADR 0044 — Old indexes on a relocated Lucene, and features per index

- **Status:** Proposed (spike, branch `lucene-2.4-spike`)
- **Date:** 2026-10-12
- **Amends:** ADR 0021 (`openIndex` reports the features of the opened index), AGENTS.md hard rule 5 (a second Lucene on the classpath)

## Context

leia reads indexes with Lucene 10.5.2 and backward-codecs, so Lucene 9 and 10 indexes open. Anything older is refused as `UNSUPPORTED_INDEX_FORMAT`. Each Lucene major reads only its own indexes and those of the major before it. Before 4.0 there were no codecs at all, so no modern Lucene can read a 2.x or 3.x index, whatever jars are added (ADR 0018).

Old indexes still exist, and Luke of their time is hard to run today. The question for the spike was whether one leia could read them too, from 2.4 up to 10, and switch off what an old index can't do.

Lucene 3.6.2 reads every index from 2.1 to 3.6, the formats whose `segments_N` starts with a format number from -2 to -11. The full range needs one Lucene per pair of majors:

| Runtime | Reads indexes of | Notes |
|---|---|---|
| 3.6.2 | 2.1 – 3.6 | no codecs, no doc values; terms are strings |
| 5.5.5 | 4.x, 5.x | doc values from 4.0 |
| 7.7.3 | 6.x, 7.x | points from 6.0, index sort from 6.2, soft deletes from 7.4 |
| 9.12.3 | 8.x | KNN vectors from 9.0 (8.x indexes have none) |
| 10.5.2 | 9.x, 10.x | what leia has now |

Every Lucene version uses the package `org.apache.lucene`, so two of them can't share a classpath under their own names.

## Decision

1. **A second Lucene is relocated, not loaded in a classloader of its own.**
   - The module `leia-lucene3` depends on lucene-core 3.6.2. Its Shadow jar renames `org.apache.lucene` to `io.github.mfuerstenau.leia.lucene3.shaded.lucene`, its own classes included.
   - leia-server takes the shadow jar (`shadowRuntimeElements`), so the unrelocated Lucene 3 never reaches the classpath. leia.jar grows by 1.6 MB.
   - 3.x has no `META-INF/services`, so renaming the classes is all there is to it. The Lucenes from 4 on use SPI for codecs; their services files would be relocated with them (Shadow's `mergeServiceFiles`). A codec jar from `--jar` (ADR 0018) only ever extends Lucene 10.
   - A child-first classloader with nested jars would allow plug-in runtimes, but it needs jar extraction and an API of JDK types only, which relocation needs too. Relocation keeps one jar and one classloader.
2. **The module's API has no Lucene types.**
   - The module's code is compiled against the unrelocated names, so a Lucene type in its API wouldn't match at runtime. `Lucene3Index` returns records of its own (`Stats`, `IndexDocument`, `TermsPage`, `PostingsPage`, `SearchResults`, …).
   - It is read-only like leia-core: `IndexReader.open(dir, readOnly=true)` through a `Directory` with `NoLockFactory`, never an `IndexWriter`. `--directory` maps to `FSDirectory.open`, `MMapDirectory` and `NIOFSDirectory` of 3.6.
3. **Routing by the `segments_N` header.**
   - Before Lucene 10 is tried, `Lucene3Index.handles(path)` reads the first int of the newest `segments_N`, without any Lucene. A value from -2 to -11 is a format from before 4.0. Every Lucene from 4.0 on writes `CodecUtil`'s magic there.
   - Only a single index directory with a reader is routed. `DIRECTORY_ONLY` mode on such an index gives `UNSUPPORTED_INDEX_FORMAT`.
4. **`openIndex` reports the features of the opened index.**
   - `OpenIndexResult` gains `features: Set<Feature>` and `luceneRuntime`.
   - `Feature` lists the screens and methods: `INDEX_STATS`, `TOP_TERMS`, `DOCUMENTS`, `STORED_VALUES`, `TERM_VECTORS`, `POSITIONS`, `RECONSTRUCT`, `FILTER`, `TERMS`, `POSTINGS`, `POINTS`, `DOC_VALUES`, `KNN_VECTORS`, `COMMITS`, `SEARCH`, `CLASSIC_SEARCH`, `EXPLAIN`, `HIGHLIGHT`, `STORED_SCAN`, `CHECK_INDEX`, `EXPORT_TERMS` and `REOPEN`.
   - Lucene 10 with a reader has them all, and without one only `COMMITS` and `CHECK_INDEX`.
   - Lucene 3.6 has `INDEX_STATS`, `DOCUMENTS`, `STORED_VALUES`, `TERM_VECTORS`, `TERMS`, `POSTINGS` and `CLASSIC_SEARCH`.
5. **The backend refuses what an index can't do with `NOT_SUPPORTED` (-32019).**
   - `CurrentIndex` holds either a Lucene 10 index or a `Lucene3Index`. With the latter, `opened()` and `readable()` throw `NOT_SUPPORTED`, so every handler without a branch of its own refuses.
   - The handlers of the supported methods ask `legacy()` first, and `Lucene3Results` maps the records.
   - Search is a job as always (hard rule 7). It takes a classic query only, best score first.
6. **The TUI switches off what the index lacks.**
   - `Opened` carries the features.
   - A screen the index lacks gets a dimmed tab, and its key gives a notice: "Commits: not for Lucene 2.x".
   - `R` and the overview's top-terms keys go the same way.
   - The tab line says "Lucene 2.x, read with 3.6.2".
   - Keys inside a screen that the index lacks, such as `x` explain, still reach the backend, and the screen shows its `NOT_SUPPORTED` message.

## What the spike showed

- Lucene 2.4.1 writes an index on Java 25, and Lucene 3.6.2 reads it on Java 25 with all three directories, `MMapDirectory` included. No flags or workarounds were needed. `MMapDirectory` of 3.6 can't unmap on Java 25 and quietly leaves that to the GC.
- Relocated 3.6.2 and Lucene 10 run side by side in one JVM. A test opens the 2.4 index, then a Lucene 9 index, then the 2.4 index again (`Lucene24BackendTest`).
- Reading the index leaves its files byte for byte as they were and writes no `write.lock` (`Lucene3IndexTest`).
- Differences of Lucene 3 that the protocol shows:
  - Postings skip deleted documents, though `docFreq` counts them.
  - `totalTermFreq` isn't recorded, so it is counted from the postings of live documents.
  - There are no term statistics per field, so `IndexField.terms` is absent.
  - The commit doesn't record the Lucene that wrote it; the newest segment's version stands in.
  - A segment written before 3.0 has no version, and shows as `2.x`.
  - Search parses with 3.6's `StandardAnalyzer` and scores with its default similarity, whatever the params name.

## Consequences

- Each further runtime is a module like `leia-lucene3`: a relocated Lucene, a facade with its own records, and a `Results` class in the server. Each needs a routing rule (the `segments_N` header names the version from 4.0 on) and a feature set. The APIs of 5, 7 and 9 differ less from each other than from 3, so their facades may share sources.
- The protocol's records stay those of Lucene 10. An old runtime fills what it lacks with neutral values: no doc values, no points, no vectors.
- Left for later:
  - commits and `CheckIndex` on 3.6;
  - several old index directories under one path;
  - a 3.x test index;
  - the term-statistics scan (`TOP_TERMS`);
  - hiding the keys of the features a screen lacks;
  - folding the no-reader checks into `features`.
