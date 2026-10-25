# ADR 0092 — OpenSearch's k-NN plugin with a server

- **Status:** Accepted
- **Date:** 2026-10-18
- **Amends:** ADR 0090 (§2, the jars of a server; "Observed, not changed"; the rejected alternative "Load `modules/` and `plugins/` too")

## Context

An OpenSearch shard with a `knn_vector` field names the codec of the k-NN plugin: `KNN950Codec` on OpenSearch 2.11, `KNN9120Codec` on 2.19, `KNN1040Codec` on 3.9. The plugin is a folder of `plugins/`, not a jar of `lib/`, so `--server` (ADR 0090) left these shards `MISSING_CODEC`.

Trying the plugin's jars in the server's loader, with the copied `plugins/opensearch-knn/` of OpenSearch 2.19.6 and 3.9.0 (podman, indexes with a `knn_vector` of the `lucene`, `faiss` and, on 2.x, `nmslib` engine), showed two things the jars alone don't do:

1. **The plugin needs a node.** Its `KNNSettings` read every value from the node's `ClusterService`, which the plugin is given as it starts (`KNNSettings.state().setClusterService`). Without one, opening, documents, terms and a search of the `lucene` engine work, but `closeIndex` fails with a `NullPointerException` (`KNN80DocValuesProducer.close` asks the native memory cache, which asks the settings) and so does the check of the index: every `close` and every `check` of a k-NN shard, with the index left half closed.
2. **OpenSearch 3's plugin extends a module.** Its `plugin-descriptor.properties` says `extended.plugins=lang-painless,transport-grpc`, and the classes of `opensearch-knn` use Guava, which only `modules/transport-grpc/` has. Without it, the `lucene` engine's index doesn't open (`NoClassDefFoundError: com/google/common/collect/ImmutableSet`) and the `faiss` engine's `KNNEngine` doesn't initialize. OpenSearch 2.x's plugin extends only `lang-painless`, and needs none of it.

## Decision

1. **The jars of `plugins/opensearch-knn/` are loaded** into a server's class loader, after those of `lib/`, whose classes win. The plugin is a fixed list, `ServerRuntimes.CODEC_PLUGINS`: it is the plugin that holds the codec of a mapping type. Nothing else of `plugins/` or `modules/` is loaded for its own sake; each plugin has a class loader of its own in the server and a setup that leia would have to do (ADR 0090). The folder is found beside `lib/`, so the server's home and its `lib/` give the same result. A server without it loads as before. No flag, no change to the protocol.
2. **What the plugin extends is loaded too.** The names in `extended.plugins` of its descriptor are looked up in `modules/`, then `plugins/`, and their jars follow the plugin's. One that is not there (`lang-painless` of an installation that stripped it) is skipped. That follows OpenSearch's own model of an extended plugin, and takes no name of a library: today it brings Guava and the gRPC jars of `transport-grpc`, and the ASM and ANTLR jars of `lang-painless`, about 20 MB of jars, only opened when a class is used.
3. **`OpenSearchKnnSetup` gives the plugin a cluster service**, reflectively and best effort, when the loader is made and the plugin is there (as `ElasticsearchSetup` does for Elasticsearch). It makes a `ClusterService` of its own, over the settings that OpenSearch registers for a cluster (`ClusterSettings.BUILT_IN_CLUSTER_SETTINGS`), the plugin's own settings that are of the node's scope (`ClusterSettings` refuses an index setting, and the plugin lists `index.knn`) and a `ThreadPool` without executors named `leia`, whose threads are daemons. Every setting has its default value. No node starts, and nothing is read from the installation's `opensearch.yml`. What fails is logged, and the calls that need it fail as they would without it.
4. **The native libraries are not loaded.** The plugin's `lib/` holds `libopensearchknn_*.so`; the reading of the index never loads them: the shards of the `faiss` and `nmslib` engines open, their documents and terms read, and they check clean.
5. **A native engine's KNN search** is `INTERNAL_ERROR` on OpenSearch 2.x (the plugin's reader throws `UnsupportedOperationException: Search functionality using codec is not supported with Native Engine Reader`); on 3.9 the search of a `faiss` field works. leia doesn't map a `RuntimeException` of a server's classes (ADR 0090 §6), and the message says what is the matter.
6. **The label doesn't change.** The about screen's line and the tab line name the server, not its plugin; the log says that the plugin was loaded.

### Tried with real servers

The same harness as ADR 0090, with `--server` on the installation (`lib/`, `plugins/opensearch-knn/`, and the two modules), over every shard of the copied data:

| Server | `lucene` engine | `faiss` | `nmslib` |
|---|---|---|---|
| OpenSearch 2.19.6 (Lucene 9.12.3) | everything: documents, terms, KNN search, KNN graph, commits, check, close | opens, documents, terms, commits, check; KNN search `INTERNAL_ERROR` (see 5) | as `faiss` |
| OpenSearch 3.9.0 (Lucene 10.5.1) | everything | everything, KNN search too | – (gone from 3.x) |

The text shards of both read as before. Without the setup (3), every k-NN shard fails its close and its check; without the extended jars (2), OpenSearch 3.9's k-NN shards don't open. Not tried: 2.11 (its `KNN950Codec` should be in the same plugin, as 2.19's jar lists every codec back to `KNN80Codec`).

## Alternatives considered

- **Only the jars of the plugin, no setup.** Opens, but `closeIndex` and the check of every k-NN shard fail, on 2.19 also with the index half closed. Rejected.
- **Load all of `plugins/` and `modules/`.** Rejected as in ADR 0090: a class loader and a setup per plugin; and the plugins with a `Codec` or `PostingsFormat` of their own would join Lucene's lists, which fail as a whole when one of them fails to initialize.
- **A flag naming the plugins** (`--server-plugin`). The user chose loading by name without a flag: the one plugin with a codec of a mapping type is the case to cover.
- **Hard-code Guava from `transport-grpc`.** Breaks with the next version that moves it. Following `extended.plugins` is what the server does.
- **A mock `ClusterService` with `Unsafe.allocateInstance`**, setting only its `clusterSettings` field. Needs no thread pool, but depends on the class's private layout and skips its constructor's checks. Rejected for the constructor `ClusterService(Settings, ClusterSettings, ThreadPool)`, which has been the same from 2.11 to 3.9.
- **Map the `UnsupportedOperationException` of a native engine's search to `NOT_SUPPORTED`.** It would cover `faiss` and `nmslib` on 2.x, but means treating every `RuntimeException` of a server's classes as a missing feature. Left for when it matters.

## Consequences

- leia-server: `ServerRuntimes.CODEC_PLUGINS` and `pluginJars`, `OpenSearchKnnSetup` in `…server.runtime`. The tests of real loading are in `Server9BackendTest` (`serverTest`), the lookup of the jars in `ServerRuntimesTest`.
- leia-test-servers: the fake OpenSearch `fake-server-9` gains `plugins/opensearch-knn/` (a codec `LeiaKnn` over Lucene 9.10's with a vectors format whose reader asks `KNNSettings` as it closes and is checked, and needs a class of `modules/leia-ext/`, which the plugin's descriptor extends, besides `lang-painless`, which isn't there), stand-ins in its `lib/` of the OpenSearch classes the setup builds (`Settings`, `Setting`, `ClusterSettings`, `ThreadPool`, `ClusterService`), which refuse what the real ones refuse, and a third index `knn` written with the codec.
- `docs/runtimes.md` § Servers keeps the versions tried; ADR 0090 has amendment notes.
