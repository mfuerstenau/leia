# ADR 0128 — `leia-protocol` and the old runtimes are `@NullMarked`

- **Status:** Accepted
- **Date:** 2026-11-09
- **Amends:** ADR 0127 §6 (which modules NullAway checks); AGENTS.md, "Tech stack"

## Context

NullAway checks the packages marked `@NullMarked` (ADR 0127 §6). Every module was marked but two kinds, left for a decision of their own:

- **`leia-protocol`**, whose optional components are `Optional*` by hard rule 3, not `@Nullable`.
- **`leia-lucene3/5/7/9`**, the adapters of the old Lucenes, which run in class loaders of their own over unannotated Lucene jars.

Unmarked, a marked caller could pass `null` for a protocol component without a finding, and an old adapter could return `null` from a method of `leia-runtime`'s marked interface (an override NullAway only checks in marked code).

## Decision

1. **`leia-protocol` is marked, and hard rule 3 stays.** An optional component is an `Optional*` and, being a non-null type, is never `null`; absent is `Optional.empty()`. The convenience constructors that take `@Nullable` values (`Posting`, `GetPostingsParams`, `RuntimeFolder`) were already the way in from nullable code. Nothing deserializes them from JSON (Jackson reads only `leia-client`'s own `ExportedDocument` and the viewer's text), so no `null` can reach a component behind NullAway's back.
2. **`leia-lucene3/5/7/9` are marked as `leia-lucene10` is**, by a `package-info.java` in the adapter's package. JSpecify stays `compileOnly` there, so no adapter jar, `runtime.txt` or `API_VERSION` changes (ADR 0073, ADR 0127 §3).
3. **`RawPostings` and `SegmentFiles`** of `leia-lucene3`, which live in Lucene's package `org.apache.lucene.index` (ADR 0062, ADR 0113), carry `@NullMarked` on the class. A `package-info.java` would put a class outside the allow-list of `adapterJarCheck` and speak for a package that is Lucene's.
4. **The test-index source sets** (`lucene24Index`, `lucene8Index`, …) stay unchecked, as every source set but `main` and `testFixtures` already is.
5. Every module is now checked, so the tech stack in AGENTS.md no longer lists exceptions.

The fixes are the patterns of ADR 0127: a field or local that starts or may be `null` is `@Nullable` (`Lucene9KnnGraph.Vectors.docs`, `Lucene5LikeTerms.frequencies`, the `values` of a doc-values query); an override of a Lucene method that returns `null` says so (`getLiveDocs`, `scorer`, `getCoreCacheHelper`, `highlightFieldForDoc`); `Failures.message(e)` replaces `e.getMessage()` where a message is required; `InvocationTargetException.getTargetException()` replaces `getCause()`; and a preset's optional constructor is read through `Objects.requireNonNull`, since the support checks run first, as in `leia-core`'s `PresetAnalyzers`.

## Alternatives considered

- **Leave them out.** Rejected: two modules would keep their own rule for no reason that holds, and nulls from the adapters into the marked interface would go unseen.
- **Make the protocol's optional components `@Nullable`.** Rejected: it breaks hard rule 3, and an explicit `Optional*` on the wire is what the rule is for.
- **NullAway library models or `@Nullable` stubs for the old Lucenes.** Rejected: five Lucene versions to keep a model for, against code leia doesn't own.

## Consequences

- NullAway checks leia's own code in the adapters and their overrides of `leia-runtime`'s interface. The Lucene jars are unannotated, so NullAway reads their returns as non-null: a Lucene method that returns `null` (`LeafReader.terms`, `getTermVectors`) is not flagged where leia dereferences it. The tests on the test indexes hold that line, as before.
- A new class in an old adapter's package is checked from the start; one in Lucene's package needs its own `@NullMarked`.
