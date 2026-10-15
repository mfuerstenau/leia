# ADR 0044 — Old indexes on a relocated Lucene, and features per index

- **Status:** Accepted on 2026-10-12, after the spike ("What the spike showed"); decisions 1, 3 and 5 changed and 7 added: the old runtimes are optional jars, not part of `leia.jar`
- **Date:** 2026-10-12
- **Amends:** ADR 0021 (`openIndex` reports the features of the opened index), ADR 0024 (a `runtimes/` folder beside `leia.jar` is loaded too), ADR 0034 §9 (the release folder gets `runtimes/`), AGENTS.md hard rule 5 (a second Lucene on the classpath)

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

1. **A second Lucene is relocated, and is a jar of its own.**
   - The module `leia-lucene3` depends on lucene-core 3.6.2. Its Shadow jar renames `org.apache.lucene` to `io.github.mfuerstenau.leia.lucene3.shaded.lucene`, its own classes included. That jar is the runtime, `leia-lucene3.jar`, and it is never part of `leia.jar`. `leia.jar` reads Lucene 9 and 10 only, and doesn't grow: the 1.6 MB of 3.6 are in the runtime jar.
   - A runtime loads like any other jar, through the `ExtensionJars` chain of ADR 0016 and 0024. That chain is parent-first, so the runtime sees leia's own classes, and relocation keeps its Lucene apart from Lucene 10.
   - leia finds a runtime with `ServiceLoader` over `ExtensionJars.classLoader()`, through a runtime interface that `leia.jar` defines, of JDK types and records only. The interface has a version; a runtime built for another version is refused as `INVALID_JAR`.
   - 3.x has no `META-INF/services`, so renaming the classes is all there is to it. The Lucenes from 4 on use SPI for codecs; their services files would be relocated with them (Shadow's `mergeServiceFiles`). A codec jar from `--jar` (ADR 0018) only ever extends Lucene 10.
   - A child-first classloader with nested jars was rejected. It needs jar extraction and an API of JDK types only; relocation needs the API too, and with it a runtime shares the loader chain that already exists, so child-first gains nothing.
2. **The module's API has no Lucene types.**
   - Its facade implements the runtime interface of decision 1.
   - The module's code is compiled against the unrelocated names, so a Lucene type in its API wouldn't match at runtime. `Lucene3Index` returns records of its own (`Stats`, `IndexDocument`, `TermsPage`, `PostingsPage`, `SearchResults`, …).
   - It is read-only like leia-core: `IndexReader.open(dir, readOnly=true)` through a `Directory` with `NoLockFactory`, never an `IndexWriter`. `--directory` maps to `FSDirectory.open`, `MMapDirectory` and `NIOFSDirectory` of 3.6.
3. **Routing by the `segments_N` header.**
   - Before Lucene 10 is tried, a detector in `leia.jar` reads the first int of the newest `segments_N`, without any Lucene. A value from -2 to -11 is a format from before 4.0. Every Lucene from 4.0 on writes `CodecUtil`'s magic there. The detector is in `leia.jar`, not in a runtime (the spike's `Lucene3Index.handles`), so leia can tell an old index even when its runtime isn't loaded.
   - An old index that no loaded runtime reads gives `MISSING_RUNTIME` (-32020), next after `NOT_SUPPORTED` in `ErrorCode`. Its message names the runtime jar and the index's Lucene version, so the TUI can point at `X`, as with `MISSING_CODEC` (ADR 0018).
   - Only a single index directory with a reader is routed. `DIRECTORY_ONLY` mode on such an index gives `UNSUPPORTED_INDEX_FORMAT`.
4. **`openIndex` reports the features of the opened index.**
   - `OpenIndexResult` gains `features: Set<Feature>` and `luceneRuntime`.
   - `Feature` lists the screens and methods: `INDEX_STATS`, `TOP_TERMS`, `DOCUMENTS`, `STORED_VALUES`, `TERM_VECTORS`, `POSITIONS`, `RECONSTRUCT`, `FILTER`, `TERMS`, `POSTINGS`, `POINTS`, `DOC_VALUES`, `KNN_VECTORS`, `COMMITS`, `SEARCH`, `CLASSIC_SEARCH`, `EXPLAIN`, `HIGHLIGHT`, `STORED_SCAN`, `CHECK_INDEX`, `EXPORT_TERMS` and `REOPEN`.
   - Lucene 10 with a reader has them all, and without one only `COMMITS` and `CHECK_INDEX`.
   - Lucene 3.6 has `INDEX_STATS`, `DOCUMENTS`, `STORED_VALUES`, `TERM_VECTORS`, `TERMS`, `POSTINGS` and `CLASSIC_SEARCH`.
5. **The backend refuses what an index can't do with `NOT_SUPPORTED` (-32019).**
   - `CurrentIndex` holds either a Lucene 10 index or a `Lucene3Index`. With the latter, `opened()` and `readable()` throw `NOT_SUPPORTED`, so every handler without a branch of its own refuses.
   - Handlers reach a runtime through the runtime interface of decision 1. The spike's `legacy()` branches in the handlers and its `Lucene3Results` are interim, until that interface exists.
   - Search is a job as always (hard rule 7). It takes a classic query only, best score first.
6. **The TUI switches off what the index lacks.**
   - `Opened` carries the features.
   - A screen the index lacks gets a dimmed tab, and its key gives a notice: "Commits: not for Lucene 2.x".
   - `R` and the overview's top-terms keys go the same way.
   - The tab line says "Lucene 2.x, read with 3.6.2".
   - Keys inside a screen that the index lacks, such as `x` explain, still reach the backend, and the screen shows its `NOT_SUPPORTED` message.
7. **Where the runtimes come from.**
   - The release folder has a `runtimes/` folder beside `leia.jar`, with one jar per runtime. `leiaFolder` builds the same for a development build.
   - leia finds that folder from the location of its own jar and loads it at startup, before the jars of `--jar` and the default folder. `--no-default-jars` doesn't affect it.
   - `--jar`, the default folder and `X` can load a runtime too.

### Until the Packaging task

leia-server still takes `leia-lucene3`'s shadow jar (`shadowRuntimeElements`), so 3.6 is in `leia.jar`. Decisions 3 and 7 land with the routing and packaging tasks of `docs/tasklist.md`.

The runtime seam (decision 1) has landed:
- The interface is the module `leia-runtime`, part of `leia.jar`: the service `LuceneRuntime`, with `API_VERSION` 1, opens a `RuntimeIndex`, which hands out the groups `Documents`, `Terms` and `Search`. A runtime that lacks a group returns it empty, and `openIndex` leaves its features out.
- `leia-lucene3` implements it as `Lucene3Runtime` and takes `leia-runtime` compileOnly, so the interface isn't in its jar.
- leia-server depends on `leia-lucene3` at runtime only, and finds the runtime with `ServiceLoader` (`Runtimes`). The Lucene 10 handlers are unchanged; each handler that a runtime can serve has one `currentIndex.runtime()` branch into `RuntimeResults`.
- A runtime built for another `API_VERSION` is refused with `INVALID_JAR`: at the start, nothing starts; on `addJars`, the jar stays loaded and its runtime is never used.
- The detector is still interim: `SegmentsHeader.isPreCodec` tells an index before 4.0, which counts as major 3. Without a runtime for it, `openIndex` fails with `UNSUPPORTED_INDEX_FORMAT` until the routing task brings `MISSING_RUNTIME`.

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

- Each further runtime is a module like `leia-lucene3`: a relocated Lucene and a facade that implements the runtime interface. Each needs a routing rule (the `segments_N` header names the version from 4.0 on) and a feature set. The APIs of 5, 7 and 9 differ less from each other than from 3, so their facades may share sources.
- Packaging:
  - the release folder grows with each runtime; `leia.jar` doesn't, and someone who only reads Lucene 9 and 10 can leave `runtimes/` out;
  - every runtime module is built against the runtime interface's version, and a change to the interface means building them all again;
  - `build-dist.sh` and `leiaFolder` copy `runtimes/`.
- The protocol's records stay those of Lucene 10. An old runtime fills what it lacks with neutral values: no doc values, no points, no vectors.
- Left for later:
  - commits and `CheckIndex` on 3.6;
  - several old index directories under one path;
  - a 3.x test index;
  - the term-statistics scan (`TOP_TERMS`);
  - hiding the keys of the features a screen lacks;
  - folding the no-reader checks into `features`.
