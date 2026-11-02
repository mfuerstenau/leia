# Task list

The work queue for leia. Check items off (`[x]`) as they land. Finished items move to `docs/tasklist-done.md`.

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again.

The lane is done: on new code the gate passes, and the accepted rules are whitelisted in the repository (ADR 0088).

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

## Open prompt

None open.

## Documentation

- [ ] Describe leia's folders on Linux and Windows, config, data, state and cache with their user and system candidates, and which one wins (read from the first that has it, write to the first writable user one), in both READMEs: the main `README.md` and the user README `leia-tui/src/dist/README.md` (ADR 0122).

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
