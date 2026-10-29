# ADR 0104 — MCP search analyzers by name

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§6, the Tools table: `search`)

## Context

`search` parsed a classic query with `StandardAnalyzer` always. On a field indexed with another analyzer that finds nothing or the wrong documents: `id:doc-0042` on the demo index's keyword `id` became `doc` and `0042`. Lucene doesn't record a field's analyzer, so the agent has to name one. The backend already builds any analyzer a `ClassicQuery` carries, on every runtime, so only `leia-mcp` changes.

## Decision

1. **Two optional arguments of `search`, for `query` only.** `analyzer` is the analyzer of the query's fields, `fieldAnalyzers` a list of `{field, analyzer}` that gives fields one of their own (`Analyzer.fields`, as `PerFieldAnalyzerWrapper`; a list because `Schemas` has no maps). Without them the analyzer is `StandardAnalyzer`, as before. With `term` they fail with `INVALID_PARAMS`: a term isn't analyzed.
2. **Names, not settings.** An analyzer is a simple name (`EnglishAnalyzer`) or a fully qualified class name, with its default settings. Stop words, collation locale and strength, shingles and custom analyzers are left out to keep `tools/list` short, so a field such as `title_key` (German collation at primary strength) still can't be searched from MCP.
3. **Simple names resolve through `getAnalysisComponents`,** ignoring case, against `AvailableAnalyzer.name`, which lists Lucene 10's analyzers and those of the `--jar` jars. One match gives its class name. No match fails with `INVALID_PARAMS` and lists the simple names (about 50, sent on error only, so one failed call shows the choices). Several matches fail and list their class names. The list is fetched at most once per call, and only if a simple name needs it. A name with a `.` is passed on as it is; `StandardAnalyzer` is the standard analyzer.
4. **Without a Lucene 10 runtime** `getAnalysisComponents` is `NOT_SUPPORTED`; the simple name is then passed on as it is, which is all the older runtimes take (`AnalyzerSpec.Preset.simpleName()`). An analyzer a runtime lacks is `NOT_SUPPORTED` from the search, with the names it has.

## Consequences

- `tools/list` grows by two arguments; `tools.json` pins them.
- A wrong simple name costs a call, with the choices in the error.
