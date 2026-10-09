# ADR 0018 — Codecs and formats from jars

- **Status:** Accepted, amended by ADR 0024 (the jars can also be added at runtime)
- **Date:** 2026-10-07
- **Amends:** ADR 0016 (the flag is renamed, and the jars also add codecs)

## Context

A Lucene segment names its codec in `segments_N`, and per field the postings format, the doc values format and the KNN vectors format in its field infos. Lucene looks each name up in a global list that it fills through `ServiceLoader`. `DirectoryReader.open` does all four lookups, so an index with a name that the server lacks fails when it is opened. Today that is an `INTERNAL_ERROR` with Lucene's `IllegalArgumentException`: "An SPI class of type org.apache.lucene.codecs.Codec with name 'X' does not exist. You need to add the corresponding JAR file supporting this SPI to your classpath. …".

Where such names come from:

- **Lucene's own modules.** `lucene-codecs` has `SimpleText` and the postings formats `BlockTreeOrds`, `FST50`, `UniformSplit`, `SharedTermsUniformSplit`, `BloomFilter`, among others. `lucene-suggest` has the `Completion*` postings formats, which Elasticsearch, OpenSearch and Solr use for completion suggesters. `lucene-sandbox`, a dependency of the query parser, has `IDVersion` and is in `leia-server.jar` already. `lucene-codecs` (479 KB) and `lucene-suggest` (244 KB, needs `lucene-analysis-common`) have no third-party dependencies. Luke bundles both.
- **The user's own jars,** with codecs and formats written for the index.
- **Elasticsearch and OpenSearch,** whose segments name their own codecs and formats. Their classes need the server's `lib/` folder, which also has its own `lucene-*.jar`, usually a different Lucene version.

Each of the four lists can be reloaded from a class loader (`Codec.reloadCodecs`, `PostingsFormat.reloadPostingsFormats`, `DocValuesFormat.reloadDocValuesFormats`, `KnnVectorsFormat.reloadKnnVectorsFormat`), as the factory lists can (ADR 0016). A reload only adds names. The first one with a name stays.

## Decision

1. **`lucene-codecs` and `lucene-suggest` are bundled.** They add about 0.7 MB to `leia-server.jar`. ADR 0016 rejected bundling the analysis modules for their 38 MB of dictionaries, which doesn't apply here. And without its codec, an index doesn't open at all.
2. **One kind of jar, one flag.** `--analysis-jar` becomes `--jar <PATH>` on both the TUI and the server, without an alias. Everything else from ADR 0016 stays: it can be repeated, it is a jar or a directory of jars, the TUI checks that the path exists, and `--version` starts the server without the jars. A jar can carry analysis components, codecs and formats, or both. One loader means a jar is never loaded twice.
3. **Loading reloads seven lists.** `ExtensionJars.load` (formerly `AnalysisJars`) reloads the three factory lists and the four codec lists from the one `URLClassLoader`. Like a factory reload, a codec reload that fails, such as a codec whose dependency is missing, stops the server with exit status 1 and the reason on stderr.
4. **A missing codec or format is an error of its own.** `IndexOpenException.Reason.MISSING_CODEC` maps to the new `MISSING_CODEC` (-32014). Its message names the kind (codec, postings format, doc values format or KNN vectors format) and the name: "Missing codec: the index needs the postings format 'X', which isn't loaded". Lucene's own message also lists every loaded name, which is too long to show, so it is left out. Lucene's own names (`Lucene` and a version, such as `Lucene106`) are not missing jars: an index that names one leia doesn't know was written by a newer Lucene. That stays `UNSUPPORTED_INDEX_FORMAT`, with a message that says so. Core finds the name in Lucene's `IllegalArgumentException` message, anywhere in the cause chain, and turns it into a `MissingCodecException` (an `IOException`). `getCommits` (ADR 0009) skips such a commit as unreadable, and `getCommit` fails with `MISSING_CODEC`.
5. **The remedy is the TUI's.** Core and server don't know about the command line. For `MISSING_CODEC`, the TUI adds "start leia with --jar and a jar that has it" to the message it shows when opening fails. There is no other protocol change.

## Alternatives considered

- **Keep `--analysis-jar` and also reload the codecs from it.** No CLI change, but the name is wrong for a jar with only codecs. Rejected; leia isn't released yet, so no alias is needed.
- **A separate `--codec-jar`** with a second loader whose parent is the first. Clear names, but a jar with both kinds (an Elasticsearch `lib/`) is given twice and loaded twice, as two sets of classes. Rejected.
- **Don't bundle the two modules,** and add a `codecModules` Gradle task as ADR 0016 did for analysis. Consistent, but it makes the user find a jar for an index that a 0.7 MB jar would have opened. Rejected.
- **Bundle `lucene-suggest` only.** `lucene-codecs` is mostly experimental formats, but `SimpleText` and `BloomFilter` do show up. Rejected for 479 KB.
- **Reuse `UNSUPPORTED_INDEX_FORMAT`** with a better message. The TUI couldn't tell "add a jar" from "use another Lucene". Rejected.
- **Read the names without opening the index,** from `segments_N` and the field infos, instead of parsing the message. The field infos are read by the codec, so this only works when the codec is there. Rejected.
- **Handle Elasticsearch and OpenSearch `lib/` folders,** for example by skipping their `lucene-*.jar`. Unverified without an installation, and a codec built against another Lucene version may still fail to link. Deferred. Until then, the classes of a jar that duplicate leia's own come from leia's (the loader asks its parent first), so a jar of another Lucene version is at best ignored.

## Consequences

- `leia-core`: `AnalysisJars`/`AnalysisJarException` become `ExtensionJars`/`ExtensionJarException`, plus the new `MissingCodecException`. `ReadOnlyIndex` maps the missing names, and `CommitReader` lists them as unreadable. The build gains a test codec jar (`:leia-core:testCodecJar`, a codec and one format of each kind) and two indexes written with it (`:leia-core:testCodecIndexes`): one with the codec, one with only the formats. The tests that load jars run in the `jarsTest` task (formerly `analysisJarsTest`), in a JVM of their own.
- `leia-protocol` and the TUI: `MISSING_CODEC` in both `ErrorCode` enums and `protocol/error-codes.json`, plus the fixture `openIndex.error-missing-codec.json`.
- `leia-server`: `ServerArgs` takes `--jar`. `leia-server.jar` grows from 12.2 MB to 12.9 MB. The real-server tests in `tui/` open both test indexes, without and with the jar.
- `AGENTS.md` (TUI CLI contract) and the README describe `--jar`.
