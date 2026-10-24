# ADR 0090 — Elasticsearch and OpenSearch through a server runtime

- **Status:** Accepted
- **Date:** 2026-10-18
- **Amends:** ADR 0018 (its deferred alternative, Elasticsearch and OpenSearch `lib/` folders), ADR 0073 (a runtime's class loader can also be a server's)

## Context

The shards of Elasticsearch and OpenSearch name codecs and formats of their own: `ES812Postings`, `ES87TSDB`, the bloom filter on `_id` (`ES87BloomFilter`), Elasticsearch's vector formats (`ES813Int8FlatVectorFormat`, `ES816BinaryQuantizedVectorsFormat`, …), zstd stored fields, and OpenSearch's k-NN codecs. leia reports such a shard as `MISSING_CODEC`, and `--jar` can't help:

- the jar chain sits on Lucene 10.5.2 and is parent-first, so a server's own `lucene-*.jar` is ignored (ADR 0018);
- Elasticsearch 8 and OpenSearch 2 build their codecs against Lucene 9, whose internals differ from 10's;
- the old runtimes don't see `--jar` at all (ADR 0073).

Since ADR 0073 every old runtime has a class loader of its own over the official Lucene jars. A server's `lib/` is such a set of jars, with its codecs beside its Lucene, so the same construction reads its shards.

## Decision

1. **`--server <PATH>` (repeatable)**, the installation's home or its `lib/`. `Main` checks that the path is a directory and that it, or its `lib/`, has a `lucene-core-*.jar`; else it exits 1, as for `--jar`. It can't be combined with `--download-runtimes` (exit 2), and `--version` doesn't load it. There is no way to add one in the TUI. It reaches the backend as `LeiaBackend.start(runtimesDir, jars, servers)`; the two-argument `start` passes none.
2. **`ServerRuntimes` loads them at startup**, after `RuntimeFolders.load` and before `JarChain.load`, once per JVM, like the jar chain. For each:
   - the Lucene major comes from the name of `lucene-core-<version>.jar`, else its manifest's `Implementation-Version` or `Specification-Version` (`LuceneJarVersion`). 9 takes the adapter of `runtimes/lucene9`, 10 that of `runtimes/lucene10`; any other major is refused, naming the servers leia reads (Elasticsearch 7 and OpenSearch 1 ship Lucene 8);
   - of the folder only leia's own jars are needed: the adapter and the jars of its `runtime.txt` without a Maven path (leia-core for 10). The folder may be incomplete; without the adapter, the message says to install the runtime with `D` or `--download-runtimes`;
   - the loader is `URLClassLoader("leia-server-<folder>", [adapter jars…, lib/*.jar sorted by name], ApiOnlyLoader)`, the parent of the old runtimes. Only the jars directly in `lib/`: neither `modules/` nor `plugins/`;
   - `Runtimes.load` must find exactly one runtime on that Lucene;
   - the product and version, such as `Elasticsearch 8.15.3`, come from `lib/elasticsearch-<v>.jar` or `lib/opensearch-<v>.jar`, as a label only.

   A server that can't be loaded, or a second one for the same major, stops the startup with exit 1 and the reason (`INVALID_JAR`): it was asked for, as a jar of `--jar` is (ADR 0018 §3). None of the servers is loaded then.
3. **`ServerRuntime` wraps the adapter.** Its `indexMajors()` are the server's major and the one before, {M−1, M}, whatever the adapter says (`Lucene9Runtime` says {8}), so that routing stays out of the adapters. It has no `analysis()` and no `extensions()`: the analysis tab and the jars of `--jar` stay with leia's own runtimes. `Runtimes` lists the servers' runtimes **first**, so `forMajor` and `latest` prefer them; `known()`, which `getRuntimes` reports per folder, counts only leia's own.
4. **An index the server can't open goes to leia's own runtime.** Preferring the server means that its older Lucene now reads every index of its majors, also one of a newer minor, such as leia-core's Lucene 9 test index, written by 9.12 with `Lucene912` postings that 9.10 lacks. So if a server's runtime fails to open an index (an `RpcException`, a `LinkageError` or a `ServiceConfigurationError`), `OpenIndexHandler` tries leia's own runtime for the index's major (`IndexDirectories.leiasRuntime`); if that fails too, the server's error is reported. Naming a server never keeps an index from opening that opened without it. The log says which Lucene read it.
5. **Elasticsearch's bootstrap is done for it** (`ElasticsearchSetup`), reflectively and best effort, when its loader is made:
   - `LogConfigurator.configureESLogging()`, which sets Elasticsearch's logger factory. Without it, every class of Elasticsearch that logs fails to initialize, such as `ES813Int8FlatVectorFormat`, and since Lucene instantiates every format of its list at once, one failure breaks the whole list: then no index with vectors opens, not even with Lucene's own formats. Elasticsearch 8.0 has no such method and needs nothing;
   - `es.nativelibs.path` set to `lib/platform/<os>-<arch>` while `LoaderHelper` initializes, which reads it once (`org.elasticsearch.nativeaccess.lib` up to 9.4, `org.elasticsearch.foreign` from 9.5), then cleared, unless the user set it. Elasticsearch takes its native libraries, zstd for the stored fields of logsdb and its vector scorers, from there, else from `es.path.home` (8.x) or the working directory (9.0);
   - **the context class loader**: Elasticsearch 9.5 finds its native providers with `ServiceLoader.load(Class)`, through the thread's context class loader. `ServerRuntime` sets it to the server's loader for its own calls (`open`, commits, check), and `CurrentIndex` while an index of a server is open, else to the jar chain's, which `addJars` and `loadRuntimes` then leave in place (`ServerRuntimes.chainAsContextLoader`); the jobs' threads inherit it.

   Nothing for OpenSearch, which logs through Log4j's API and has no native libraries in `lib/`.
6. **An `Error` in a server's classes is `NOT_SUPPORTED`.** The adapters are compiled against Lucene 9.12.3 and 10.5.2, and a server's older minor may lack a class or method, which the JVM reports when it is called, as a `LinkageError`. `ServerRuntimes.notSupported` finds the server by the class loader names in the error's stack trace (`StackTraceElement.getClassLoaderName`), so no handler has to know which runtime it called. `LeiaBackend.run` and the jobs' runner use it for every `Error`, and `OpenIndexHandler` when the fallback fails too. The message says why: "The Lucene 9.9.2 of Elasticsearch 8.12.2 lacks what leia needs for this, as leia's runtime is built for Lucene 9.12.3: java.lang.NoClassDefFoundError: …" for a missing class or method (`IncompatibleClassChangeError`, or a `NoClassDefFoundError` of a class that isn't there), "The classes of Elasticsearch 9.5.5 fail at this, with its Lucene 10.5.1 (more in leia.log): …" for anything else, such as a class of it that fails to initialize or a `ServiceConfigurationError`. A `RuntimeException` stays `INTERNAL_ERROR`, since the adapter's own classes are in the same loader.
7. **Protocol.** `getRuntimes` gains `servers: List<ServerRuntimeInfo>` (home, label, Lucene version, majors), and `openIndex` gains `server: Optional<String>`, the label, or `the server <home>` without one. No new method, no new error code.
8. **TUI.** The about screen has a line per server, `Server: Elasticsearch 8.15.3, Lucene 9.11.1 (8–9), in /usr/share/elasticsearch`. The tab line names the server that reads the open index: `Lucene 9.x, read with 9.11.1 (Elasticsearch 8.15.3)`. The hint of `MISSING_CODEC` adds "for an Elasticsearch or OpenSearch index, start leia with --server and its installation".

### Tried with real servers

Single-node containers (podman) with security off, each with an index of `text`, `keyword`, `long` and `completion` fields, `dense_vector` indexes as `hnsw`, `int8_hnsw` and `bbq_hnsw`, a `time_series` index (`ES87TSDB` doc values, the bloom filter on `_id`) and a `logsdb` index (zstd stored fields, index sort), or for OpenSearch a `knn_vector` index of the Lucene engine. Flushed, stopped, and `data/` and `lib/` copied out. Each shard was opened with `--server`, through the calls of the screens (`openIndex`, `getIndexStats`, `getDocument`, `getTerms`, a KNN search and `getKnnGraph` for a vector field, `getCommits`, `startCheckIndex`), on Java 25:

| Server | Lucene | With `--server` |
|---|---|---|
| Elasticsearch 7.17.28 | 8.11.3 | refused (Lucene 8). Without it, leia's Lucene 9.12.3 reads its shards but one with a `completion` field, whose `Completion84` postings need `lucene-suggest`, which `runtimes/lucene9` lacks |
| Elasticsearch 8.0.1 | 9.0.0 | the adapter can't open on it (it needs `AbstractKnnVectorQuery` and `FloatVectorValues`, Lucene 9.5); these shards, with Lucene's own formats only, open with leia's Lucene 10.5.2 instead |
| Elasticsearch 8.6.2 | 9.4.2 | as 8.0.1; the shards with `ES85BloomFilter` on `_id` don't open, with the server's error |
| Elasticsearch 8.9.2 | 9.7.0 | everything: text, completion, time series |
| Elasticsearch 8.12.2 | 9.9.2 | everything (`int8_hnsw` included) but the HNSW graph, `NOT_SUPPORTED` (`HnswGraphProvider`, Lucene 9.11) |
| Elasticsearch 8.19.23 | 9.12.2 | everything: `ES812Postings`, `ES87TSDB`, `ES87BloomFilter`, `int8_hnsw` and `bbq_hnsw`, logsdb with zstd |
| Elasticsearch 9.0.8 | 10.1.0 | everything but the HNSW graph, `NOT_SUPPORTED` (`HnswGraph.maxConn()`) |
| Elasticsearch 9.5.5 | 10.5.1 | everything |
| OpenSearch 2.11.1 | 9.7.0 | everything but the `knn_vector` index (the k-NN plugin's `KNN950Codec`) |
| OpenSearch 2.19.6 | 9.12.3 | the same (`KNN9120Codec`) |
| OpenSearch 3.9.0 | 10.5.1 | the same (`KNN1040Codec`) |

So the Lucene 9 adapter needs a server on Lucene 9.5 or later (Elasticsearch 8.7, OpenSearch 2.6; tried from 9.7.0), the Lucene 10 one any 10.x (tried from 10.1.0); older minors lose the HNSW graph.

### Observed, not changed

- **`data/indices` as one** opens only if its indexes agree with each other: Lucene refuses to combine a `logsdb` index (whose index sort has a parent field) with others, and a field of one type in one index and of another in the next (`cannot change field "v" from doc values type=NONE to …`) fails the open rather than skipping that index as ADR 0021 says. This isn't the server's, it is how leia combines indexes (ADR 0045); a task.
- **OpenSearch's k-NN indexes** need the k-NN plugin's codec (`KNN950Codec`, `KNN9120Codec`, `KNN1040Codec`), which is in `plugins/`, not `lib/`: still `MISSING_CODEC`.
- A `RuntimeException` thrown in Elasticsearch's classes, such as the `NullPointerException` of zstd stored fields when its native libraries can't be found, is `INTERNAL_ERROR`.

## Alternatives considered

- **Skip the server's `lucene-*.jar` and load the rest into the jar chain** (ADR 0018's deferred idea). Elasticsearch 8 and OpenSearch 2 build against Lucene 9, whose codec API differs from 10's, so their formats wouldn't link, and the old runtimes would still not see them. Rejected.
- **Load `modules/` and `plugins/` too.** They hold the formats of some features (OpenSearch's k-NN, Elasticsearch's x-pack mappers), but each plugin has a class loader of its own in the server, with dependencies that clash, and its own setup. Rejected for now; `lib/` holds the formats of the default mappings.
- **A way in the TUI to add a server, as `X` adds jars.** The server's Lucene must be in place before an index of its major is routed, and a loader can't be unloaded. Rejected; the flag is enough.
- **Leave routing to the adapters' `indexMajors()`.** `Lucene9Runtime` says {8}, so a Lucene 9 server wouldn't read 9.x shards. Rejected for the wrapper.
- **Leia's own runtimes first, the server's only for `MISSING_CODEC`.** Every shard of the server would be opened twice, once to fail, and a shard that happens to name only Lucene's formats would be read by another Lucene than the one that wrote it. Rejected for the server first, with the fallback of 4.
- **Make the Lucene 9 adapter link on Lucene 9.0 to 9.4.** Its KNN classes (`AbstractKnnVectorQuery`, `FloatVectorValues`, from 9.5) run all through `Lucene9Index`, which the JVM then can't verify; moving them out is a rework of the shared adapter for Elasticsearch 8.0 to 8.6. Rejected for now; the fallback of 4 reads their shards that use Lucene's own formats.
- **Map a `LinkageError` by the open index.** The handlers and jobs would need the current index; the stack trace knows the loader already, and also covers a check without a reader. Rejected.
- **No setup of Elasticsearch, only documentation.** Without its logging, no index with vectors opens with Elasticsearch 8.14 and later, whose default `dense_vector` is `int8_hnsw`. Rejected.

## Consequences

- leia-server: `ServerRuntimes`, `ServerRuntime`, `ServerException`, `LuceneJarVersion` and `ElasticsearchSetup` in `…server.runtime`; `RuntimeFolders.ApiOnlyLoader` and `RuntimeFolders.listed` are package-visible. The tests of real loading run in `serverTest`, a JVM per class, as `runtimeLoadTest` does.
- A new test-only module, `leia-test-servers`, builds a fake OpenSearch on Lucene 9.10.0 and a fake Elasticsearch on Lucene 10.2.2 (with stand-ins of `LogConfigurator` and `LoaderHelper` that fail as the real ones do without the setup, and a postings format that needs the context class loader), each with an index that needs its postings format and a plain one. Neither is in `leia.jar` or the zip. Their Lucene versions are in `gradle/libs.versions.toml` and their jars in `gradle/verification-metadata.xml` (ADR 0089).
- `docs/runtimes.md` keeps the versions tried.
