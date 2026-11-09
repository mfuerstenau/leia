# Task list

The work queue for leia. Check items off (`[x]`) as they land. Finished items move to `docs/tasklist-done.md`.

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again.

The lane is done: on new code the gate passes, and the accepted rules are whitelisted in the repository (ADR 0088).

## Optional only as a return type

Goal: every module follows the rule in `AGENTS.md`, "Code conventions". `Optional*` is not used as a method parameter or field type, record components included. Optional inputs are overloads or JSpecify's `@Nullable`, with the exception of `leia-protocol`'s records (hard rule 3). `leia-client`, `leia-app`, `leia-tui`, `leia-mcp`, `leia-runtime`, `leia-server`, `leia-protocol`, `leia-core` and the runtime adapters `leia-lucene3`, `leia-lucene5`, `leia-lucene7`, `leia-lucene9` and `leia-lucene10` are done (ADR 0127). `runtime.support.Optionals` (`orNull`, `optionalInt`, `optionalLong`, `optionalDouble`) converts at the boundaries. `leia-client` set the pattern:
- records keep their components, now `@Nullable`, and their accessors become nullable;
- `OptionalInt` and `OptionalLong` become `Integer` and `Long`;
- `Optional` stays as a return type and for locals;
- JSpecify is a `compileOnlyApi` dependency.

Each module is one task, its callers included. The counts are from a regex scan of 2026-11-09 (parameters, fields and components, main + tests) and are approximate. Watch the AssertJ pitfalls: `isEmpty()` and `contains(x)` still compile on a `String` or `List` but mean something else; use `isNull()` and `isEqualTo(x)`.

The lane is done (ADR 0127).

## Null checking with Error Prone and NullAway

Goal: `@Nullable` (ADR 0127) is checked by the build, not only documentation. NullAway runs in the build (Error Prone, from `leia.java-conventions`) and checks the `@NullMarked` packages: every module but `leia-protocol` and the old runtimes `leia-lucene3/5/7/9`.

- [x] Mark the packages `@NullMarked` (JSpecify, a `package-info.java` each), module by module: `leia-client`, `leia-runtime`, `leia-core`, `leia-server`, `leia-mcp`, `leia-tui`, `leia-app` and `leia-lucene10`. `leia-protocol` and `leia-lucene3/5/7/9` wait for the next item.
- [x] Fix what NullAway finds, and make JSpecify `compileOnly` in `leia-server`, `leia-tui` and `leia-app`.
- [ ] Decide for `leia-protocol`'s `Optional*` fields (hard rule 3) and for the old runtimes' classes (Lucene 3 to 9 in class loaders of their own) whether to mark them. Write an ADR for the decision, and amend the tech stack in `AGENTS.md`.

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
