# Task list

The work queue for leia. Check items off (`[x]`) as they land. Finished items move to `docs/tasklist-done.md`.

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again.

The lane is done: on new code the gate passes, and the accepted rules are whitelisted in the repository (ADR 0088).

## Optional only as a return type

Goal: every module follows the rule in `AGENTS.md`, "Code conventions". `Optional*` is not used as a method parameter or field type, record components included. Optional inputs are overloads or JSpecify's `@Nullable`, with the exception of `leia-protocol`'s records (hard rule 3). `leia-client`, `leia-app` and `leia-tui` are done. `leia-client` set the pattern:
- records keep their components, now `@Nullable`, and their accessors become nullable;
- `OptionalInt` and `OptionalLong` become `Integer` and `Long`;
- `Optional` stays as a return type and for locals;
- JSpecify is a `compileOnlyApi` dependency.

Each module is one task, its callers included. The counts are from a regex scan of 2026-11-09 (parameters, fields and components, main + tests) and are approximate. Watch the AssertJ pitfalls: `isEmpty()` and `contains(x)` still compile on a `String` or `List` but mean something else; use `isNull()` and `isEqualTo(x)`.

- [ ] **leia-server** (~100 in 33 files, half of them in tests). Handlers, `CurrentIndex`, `ServerRuntimes`, `JarChain`, `LeiaBackend.start(Optional<…>, …)`. `RuntimeResults` maps from the runtime records and to the protocol records, which keep their `Optional`s. leia-tui's `Optionals` (`optionalInt`, `optionalLong`, `orNull`) is a candidate to share, though leia-server can't see leia-tui.
- [ ] **leia-mcp** (~180 in 27 files). Decide first: the tool records are wire records, and `Schemas` derives *required* from `Optional*` (as hard rule 3 does for the protocol). Either exempt them in `AGENTS.md` as the protocol is, or teach `Schemas` and Jackson to read `@Nullable`. The latter needs JSpecify at runtime, since a compile-only annotation is invisible to reflection, and so a NOTICE line and a `tools.json` review. The rest of `McpServer`, `Jobs` and the tools follows the rule either way.
- [ ] **leia-runtime** (~95 in 15 files). Decide first: its records cross the runtimes' class loaders as the protocol records cross the API. Either exempt them in `AGENTS.md`, or change them and every runtime that builds them (`leia-lucene3/5/7/9/10`, `RuntimeResults`), and bump `LuceneRuntime.API_VERSION`. JSpecify would be `compileOnlyApi` there, and `compileOnly` in the runtime modules, so that `adapterJarCheck` stays clean.
- [ ] **leia-core** (~130 in 42 files). The Lucene 10 library's own records and methods; `leia-lucene10` maps them.
- [ ] **leia-lucene3, leia-lucene5, leia-lucene7, leia-lucene9, leia-lucene10** (~7–23 each). The adapters' own code and test-index writers, apart from what implements `leia-runtime`'s records (see above). One task per runtime, or one for all, after `leia-runtime`.
- [ ] **leia-protocol**: check that nothing outside its records takes or holds an `Optional`, such as a helper method or a `Methods` entry.

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

None open.

## MCP mode

Goal: the tools cost an agent few tokens, then they answer why a query matches or not. Measured on the demo index (compact JSON, the text item): a default `search` of 10 hits is 45.6 KB (about 13k tokens), with `fields: [id, title]` 2.5 KB; `get_document` 6.2 KB, `index_info` 5.7 KB, `reconstruct_fields` of every field 7.3 KB; `tools/list` 5 KB of descriptions and input schemas. The first five checks and changes are done (ADR 0103); the output size comes first, in this order; each change updates `tools.json`.

Each tool is over a method the backend has; it checks the runtime's `features` and answers `NOT_SUPPORTED` without them. Short descriptions keep the cost of `tools/list` down. The tools are done, and so are the costs that needed the protocol to change (ADR 0116).

Not planned: highlighting, the point tree and the KNN graph, the term export, filters, `addJars` and the runtimes, resources and prompts.

## Download mirrors for runtime jars

The lane is done (ADR 0124).

## Open prompt

None open.

## Documentation

None open.

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
