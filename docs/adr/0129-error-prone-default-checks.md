# ADR 0129 — Error Prone's default checks fail the build

- **Status:** Accepted
- **Date:** 2026-11-09
- **Amends:** ADR 0127 §6 and ADR 0128 (Error Prone no longer runs NullAway alone); AGENTS.md, "Tech stack"

## Context

Error Prone came into the build for NullAway (ADR 0127, ADR 0128), with `disableAllChecks = true` and NullAway enabled alone. Its own checks were out of scope. Turned on once across the main and testFixtures sources of every module (about 95,000 lines), the default checks found 124 warnings from 21 checks, none in `leia-mcp` or `leia-lucene10`.

- **Real bugs and stale code:** nine Javadoc links still named the `Optional` parameters that ADR 0127 replaced, or methods that had moved (`InvalidLink`); `@throws` on records (`InvalidBlockTag`); an unused method; a `toLowerCase()` in the default locale (`StringCaseLocaleUsage`).
- **Cheap clarity:** missing parentheses (`OperatorPrecedence`), `Boolean.TRUE` for `true`, an unnamed lambda parameter, a `TreeSet` parameter for a `Set`, an implicit long-to-double conversion, `String.split` without a limit, a statement switch that is one `return switch`, and a nested record `Route.Runtime` that shadowed `java.lang.Runtime`.
- **Deliberate patterns:** reference equality on class loaders, sentinels and snapshots; Lucene's `Map<CharSequence, …>` of date resolutions; the TUI's forms and tabs, which are indexed by their enums' declaration order; backend calls whose `whenComplete` handler takes the failure; tag-only Javadoc; arrays in the runtimes' records.

The conventions already pass `-Werror`, so a check at WARNING level fails the build unless it is carved out.

## Decision

1. **Error Prone's default checks run besides NullAway**, from `leia.java-conventions`, on the same source sets: main and testFixtures. Test sources and the test-index source sets stay unchecked.
2. **They fail the build.** Error Prone reports through javac, and `-Werror` stays, as for every `-Xlint` warning. There is no warn-only middle ground, because a warning that nobody has to fix piles up.
3. **A finding is fixed, unless its check fights a pattern.** For a pattern of the whole project, the check is disabled in the conventions by name, with its reason in a comment:
   - `MissingSummary`: the project's Javadoc on records and small members is often only `@param` or `@return` tags.
   - `ArrayRecordComponent`: the runtimes' records carry raw bytes and vectors as arrays, because they are JDK types only (ADR 0050), and nothing compares them by `equals`.
4. **A single deliberate exception is a `@SuppressWarnings("Check")` on the narrowest method or class, with a `//` comment saying why**, in the style of the SonarQube exceptions (ADR 0088). These are now:
   - `ReferenceEquality`: in `MoreLikeQuery.equals`, `PresetAnalyzers.presets`, `Lucene9Commits.formats`, `GetStoredValueHandler.cached`, `Runtimes.chain` and `ServerRuntimes.chainAsContextLoader`.
   - `UndefinedEquals`: the `CharSequence` keys that Lucene's `setDateResolutionMap` takes, in `Queries.standard` and the standard parsers of lucene5/7/9.
   - `EnumOrdinal`: `AutoStopWordsForm`, `LikeForm`, `Screen.title` and `App.renderTabs`.
   - `FutureReturnValueIgnored`: `Session` and `ExportJob.poll`.
   - `InvalidParam`: a `{@code}` that names a method, not a parameter, in three protocol records.
   - `RefactorSwitch`: the two long key switches of `Documents` and `Search`.
   - `SystemConsoleNull`: `Main.downloadRuntimes`, because `System.console()` can still be `null` there.
5. `Route.Runtime` of `IndexDirectories` is renamed to `Route.Loaded`, the counterpart of `Route.Missing`. `Lucene3DateRangeQueryParser` lowercases a range in the parser's locale (`getLocale()`), as later Lucenes do.

## Alternatives considered

- **Warnings that don't fail the build.** Rejected: it needs `-Werror` dropped, or a carve-out for Error Prone alone, and the findings would stay.
- **A hand-picked list of checks.** Rejected: new default checks with an Error Prone upgrade would stay off unseen. The defaults with a short disable list keep that upgrade visible.
- **Checking test sources too.** Deferred: tests pass `null` and odd values on purpose, and ADR 0127 left them unchecked for that reason. They can follow with their own disable list.
- **Disabling `ReferenceEquality`, `EnumOrdinal` and `FutureReturnValueIgnored` globally.** Rejected: each has a few deliberate sites but catches real bugs in new code, such as an `==` on two strings, an ordinal persisted somewhere, or a future whose failure is lost.

## Consequences

- An Error Prone upgrade can bring new default checks that fail the build, and they are dealt with by these rules at the upgrade (ADR 0089).
- A new backend call in the TUI outside `Session` that ignores its future needs either a handler whose result is used or a reasoned suppression.
- No dependency changes: `error_prone_core` was already on the `errorprone` configuration. `verification-metadata.xml`, the adapter jars, `runtime.txt`, `tools.json` and `API_VERSION` are unchanged.
