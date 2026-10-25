# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Review of the k-NN plugin (ADR 0092, `01e7bc8`)

- [ ] `OpenSearchKnnSetup.setUp` catches no `RuntimeException`: a failed cast or unboxing in `clusterService()` (`BUILT_IN_CLUSTER_SETTINGS` not a `Set` on another OpenSearch) escapes `ServerRuntimes.load` as no `ServerException`, leaves the `fresh` loaders open and stops leia, against "best effort". Catch it and log; check `ElasticsearchSetup` for the same
- [ ] The thread pool isn't "without executors": OpenSearch's `ThreadPool` builds its built-in executors and starts its cached-time thread and scheduler; only custom executors are left out. Fix the Javadoc of `OpenSearchKnnSetup` and ADR 0092 §3
- [ ] `pluginJars`' Javadoc says none for a `lib/` that stands alone, but it looks in the parent of any `lib/` (`--server ~/somejars` looks at `~/plugins/opensearch-knn`): fix the Javadoc, or look only beside a `lib/` that is an installation's
- [ ] `extended.plugins` is followed one level deep, OpenSearch follows it transitively: follow it on, or say so in ADR 0092
- [ ] Tests: a setup that fails still opens the k-NN index (only close and check fail); OpenSearch 3's case, the Lucene 10 adapter with the plugin, is tried only by hand (a fake server 10 with the plugin, if worth the build)
- [ ] Nits: inline `libJars` into `jarsIn`; rename `Server9BackendTest.readsTheIndexesOfLucene8And9WithTheServersLucene`, which covers the plugin too; make `ServerRuntimes.CODEC_PLUGINS` private

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again (see that day's entry in `docs/SESSION-SUMMARIES.md`).

The lane is done: on new code the gate passes, and the accepted rules are whitelisted in the repository (ADR 0088).

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: `lucene-suggest` in `runtimes/lucene7` and `runtimes/lucene5`, for the completion fields of Elasticsearch 5 and 6 shards (as `runtimes/lucene9` has it for Elasticsearch 7); untested, no such shards tried
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format). Trie-encoded terms of 4.x to 7.x (7 bits a byte, so valid UTF-8) are written raw today, and one can hold a newline, which breaks the line format
- [ ] Misc: themes
- [ ] Misc: about

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
