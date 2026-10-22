# ADR 0078 — The runtime catalog

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amends:** ADR 0073 (a new runtime is a catalog entry, not a `RuntimeFolders.KNOWN` entry); AGENTS.md, the `leia-server` row

## Context

The runtimes leia knows were a literal list in `RuntimeFolders.KNOWN` (leia-server): each one's folder in `runtimes/` and the majors of the indexes it reads. The download dialogue (ADR 0079) also needs a name to show, such as `Lucene 9`. Nothing checked that the list matched the runtime modules of the build, or the majors that each runtime's `LuceneRuntime.indexMajors()` reports.

## Decision

1. **The catalog** is the resource `META-INF/leia/runtimes.json` of leia-server, which goes into `leia.jar` with the server's jar:
   ```json
   { "runtimes": [
     { "folder": "lucene10", "name": "Lucene 10", "indexMajors": [9, 10] },
     { "folder": "lucene9", "name": "Lucene 9", "indexMajors": [8] }, … ] }
   ```
   The entries go newest first. The first one is `LATEST`, the runtime of the jar chain (ADR 0071). The adapter jar's name is still derived from the folder, as `leia-<folder>.jar`.
2. **The schema.** `folder` matches `[a-z0-9]+` and is unique. `name` is not blank. `indexMajors` is a non-empty list of positive integers, and no major is read by two runtimes. Unknown keys and duplicate keys are refused, so a typo fails. `RuntimeFolders.Known` gains `name`.
3. **The reader** is `RuntimeCatalog` in leia-server: a small recursive-descent JSON reader of its own (objects, arrays, strings with escapes, integers, booleans, null; no fractions) and the schema check, with the JDK only. `RuntimeFolders.KNOWN` is what it reads. A catalog that is missing or broken is a bug of the build: the class fails to initialise, with a message that names the resource. The tests catch it first.
4. **The build check** `runtimeCatalogCheck` (a task class `RuntimeCatalogCheck` in build-logic, part of leia-server's `check`) parses the catalog with Groovy's `JsonSlurper`. It checks that its folders are exactly those of `RUNTIME_MODULES` and that `lucene10` comes first.
5. **The majors test.** leia-server's `jarsTest`, which loads all five folders, checks that each loaded runtime's `indexMajors()` equals its catalog entry's majors.

## Alternatives considered

- **Jackson in the server.** Rejected: Jackson is only in leia-tui, and the server's dependencies stay small; the catalog needs a fraction of JSON.
- **Read the catalog in the TUI and hand the server the records.** Rejected: there would be two places that know the runtimes, and every server test would have to pass them in.
- **Generate the list from the build.** Rejected: the catalog holds what the build can't know, such as the name and the majors, and one checked file is simpler than generated code.

## Consequences

- A new runtime is one module with the conventions, one entry in build-logic's `RUNTIME_MODULES` and one entry in the catalog. `runtimeCatalogCheck` fails when the two disagree, and `jarsTest` when the majors are wrong.
- `RuntimeInstall` and the dialogue get the runtimes and their names from `RuntimeFolders.KNOWN`, as before.
