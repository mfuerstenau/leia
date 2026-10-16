# ADR 0046 — The Lucene 5.5 runtime and runtime interface 3

- **Status:** Accepted
- **Date:** 2026-10-12
- **Amends:** ADR 0044 §4 (the features per runtime); ADR 0045 §1 (the runtime interface), §5 (the check's level), §6 (`CHECK_LEVELS` per runtime, `POSITIONS` on 3.6); ADR 0022 (the levels on a runtime)

## Context

Lucene 10 reads indexes of 9 and 10 only. Since ADR 0044, a 4.x or 5.x index failed with `MISSING_RUNTIME` naming `leia-lucene5.jar`, and the routing of ADR 0044/0045 was already generic: `SegmentsHeader` finds the major, `Runtimes.forMajor` picks a runtime whose `indexMajors()` has it, and `IndexDirectories.route` sends a directory to a loaded runtime.

Runtime interface 2 is shaped for 3.x. A 4.x or 5.x index has what it can't carry: terms that are bytes (the trie-encoded terms of `IntField` and `LongField` are not text), doc values, offsets in the postings, postings statistics per field, payloads, codecs with their formats, segment ids, field infos and doc values updates, and from 5.3 the commit's own Lucene version. And 5.5's `CheckIndex` has modes that map to leia's levels.

## Decision

1. **A runtime `leia-lucene5`** with lucene-core, backward-codecs (the codecs of 4.0 to 5.3), queryparser and analyzers-common 5.5.5, relocated as `leia-lucene3` is (ADR 0044), built by `leia.lucene-runtime-conventions`. `indexMajors()` is `{4, 5}`. It is `runtimes/leia-lucene5.jar` beside `leia.jar`.
2. **The build convention.**
   - It also relocates `org.tartarus`: analyzers-common bundles the Snowball stemmers, which Lucene 10's analysis-common has too.
   - Shadow drops a path it has seen before the services merge sees it, so lucene-core's codecs were lost behind backward-codecs'. `META-INF/services/**` includes duplicates now, and the merge keeps them all.
   - The relocated codecs are found through 5.5's `NamedSPILoader`, which also scans the loader of the SPI class when it isn't a parent of the context loader: a loaded jar finds its own codecs.
   - `luceneRuntime.testIndex(name, mainClass, vararg lucene)` takes several Lucene jars, since the writers need analyzers-common.
3. **Runtime interface 3.** `LuceneRuntime.API_VERSION` is 3, bumped once for all of this.
   - Terms are `byte[]`: `Terms.terms(field, Optional<byte[]> from, limit)`, `TermEntry.term`, `TermsPage.next`, `postings(field, byte[] term, …)` and `VectorTerm.term`. 3.6 encodes its strings as UTF-8. The server encodes them as text if they are UTF-8, as hex otherwise (ADR 0006), as it does for Lucene 10.
   - `Terms.Posting(docId, freq, deleted)`. 5.5's postings include deleted documents (they have no `liveDocs` argument since 5.3), and are marked from the live docs; 3.6 skips them and gives `false`.
   - `Terms.positions(docId, field, byte[] term)` gives `Optional<List<Documents.Position>>`, empty if the document doesn't contain the term, an empty list if positions aren't indexed. `Position` gains `Optional<byte[]> payload`.
   - `RuntimeIndex.Field` gains `DocValuesType docValuesType` and `Optional<TermStats> terms`, with `TermStats(docCount, sumDocFreq, sumTotalTermFreq)`; `IndexOptions` gains `DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS`.
   - `IndexStats` gains `Optional<String> luceneVersion`, the commit's writer, and `commitVersion(fallback)`, which falls back to the newest segment. `RuntimeIndex.VERSION_ORDER` orders versions by their numbers, so `4.10.4` is after `4.9.0`.
   - `Documents.DocumentField` gains `Optional<DocValues> docValues`, with `DocValues(type, numbers, values)` as the protocol's.
   - `Commits.Segment` gains `id`, `codec`, `formats` (`Commits.Formats`, the protocol's `CodecFormats` with `points` and `knnVectors` optional), `fieldFormats`, `fieldInfosError`, `fieldInfosGen` and `docValuesGen`. A constructor without them is for segments before codecs.
   - `Check.hasLevels()`, and `check(dir, kind, OptionalInt level, line, progress)`. `SegmentCheck` gains `Optional<String> codec`.
4. **Lucene 5.5 implements all of it** (`Lucene5Index`, `Lucene5Commits`, `Lucene5Check`, `Lucene5Analyzers`):
   - One `DirectoryReader` per directory through a directory with `NoLockFactory`, several in a `MultiReader`. The fields are `MultiFields.getMergedFieldInfos`; their statistics come from `MultiFields.getTerms`, a `sumTotalTermFreq` of -1 (no frequencies) becoming `sumDocFreq`.
   - Doc values are read per leaf with 5.x's random-access API, `getDocsWithField` deciding whether a document has one. A `totalTermFreq` of -1 becomes `docFreq`, as in Lucene 10.
   - The commits are read as leia-core reads those of Lucene 10 (ADR 0009, ADR 0037): per segment its id, codec, formats, attributes, generations, and the formats of each field from its field infos (the update, the compound file or the plain file). The commit's version is `getCommitLuceneVersion()` from 5.3, otherwise the newest segment's.
   - The check is 5.5's `CheckIndex(dir, lock)` with a lock that locks nothing, through a read-only `FilterDirectory` whose inputs, clones and slices ask `progress` on positioning and bulk reads, ported from leia-core's `CheckDirectory`. As in `Lucene3Check`, the first exception from `progress` is kept and rethrown, since 5.5 also catches every `Throwable` of a segment.
   - The search analyzers are the six of 3.6, `UnicodeWhitespaceAnalyzer`, `UAX29URLEmailAnalyzer` and the 33 language analyzers of analyzers-common, a table of class literals (which the relocation rewrites) built through their no-arg and `(CharArraySet)` constructors. Stop words apply where an analyzer has that constructor, the longest token for standard, classic and `UAX29URLEmailAnalyzer`.
5. **The levels on 5.5** (amends ADR 0022, ADR 0045 §5). 1 is `setChecksumsOnly(true)`, 2 the default check, 3 adds `setCrossCheckTermVectors(true)`. The server passes the level to a runtime with levels, and says "which has no levels" only for one without.
6. **Features** (amends ADR 0044 §4, ADR 0045 §6).
   - `POSITIONS` comes with the `Terms` group, so 3.6 has it too: positions and payloads from `TermPositions`, without offsets, and none for deleted documents, which 3.6 skips.
   - `DOC_VALUES` comes with the `Documents` group if a field has doc values.
   - `CHECK_LEVELS` comes with a check that `hasLevels()`. Without a reader, it is left out when the first index goes to a runtime without levels. `MISSING_RUNTIME` without a reader stays for majors below 4: without `leia-lucene5.jar`, Lucene 10 still lists the commits of 4.x to 8.x as unreadable.
7. **The protocol.** `CodecFormats.points` and `knnVectors` are `Optional`: there were no points before 6 and no KNN vectors before 9. Lucene 10 always fills them; the commits screen shows "–".
8. **Test indexes** `lucene4` (4.10.4) and `lucene5` (5.5.5), written by `Lucene4IndexWriter` and `Lucene5IndexWriter`: two commits with user data, a plain and a compound segment, a deletion, `IntField`/`LongField`, a docs-only field without norms, a non-UTF-8 stored binary, a field with offsets and payloads (`DelimitedPayloadTokenFilter`) and term vectors with payloads, doc values of all five types; 5.5 adds a numeric doc values update, which gives `fieldInfosGen` and `docValuesGen`. The writers delete their `write.lock`, so the tests' check that reading writes none means something.

## Consequences

- A runtime jar built for interface 2, such as an older `leia-lucene3.jar`, is refused and has to be built again.
- The trie-encoded terms of 4.x/5.x numeric fields use 7 bits per byte, so they are valid UTF-8 and come out as text of control characters, not as hex.
- Caveats:
  - A 4.x index that still has 3.x segments can't be read by either runtime: 5.5 refuses the 3.x segments, and 3.6 the 4.x `segments_N`.
  - Segments before 4.8 have no checksums, so level 1 checks nothing in their files; the output says so for each, and 5.5 itself warns about segments before 5.0, which have no ids.
  - On Java 25, 5.5 calls `sun.misc.Unsafe` (`RamUsageEstimator`, MMap's unmapping); the JVM's warnings go to stderr, which is the log while the UI runs.
- The runtimes of 7 and 9 start from `leia-lucene5`: the same interface, and codecs through relocated services.
