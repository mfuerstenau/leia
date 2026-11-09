# ADR 0127 — `@Nullable` instead of `Optional` outside the protocol

- **Status:** Accepted
- **Date:** 2026-11-09
- **Amends:** ADR 0100 §2 (a tool's component is required exactly when it isn't `@Nullable`); ADR 0044 and the runtime ADRs that list `leia-runtime`'s records (their optional components are `@Nullable`, and `LuceneRuntime.API_VERSION` is 27); AGENTS.md, "Tech stack" (where JSpecify is)

## Context

AGENTS.md's code conventions allow `Optional*` only as a return type, with one exception: the records of `leia-protocol`, whose optional fields are `Optional*` by hard rule 3. `leia-client`, `leia-app` and `leia-tui` follow the rule with JSpecify's `@Nullable`, compile-only. Two modules had records whose `Optional*` components carried meaning:

- **leia-mcp's tool records** are wire records too. `Schemas` generated each tool's input and output schema from them and took *required* to mean "not `Optional*`", and `missing()` checked the arguments by the same rule before binding them.
- **leia-runtime's records** cross the runtimes' class loaders, as the protocol records cross the API between UI and backend. Every runtime (`leia-lucene3/5/7/9/10`) builds them, and `leia-server`'s `RuntimeResults` maps them to the protocol records.

Both could have been exempted as the protocol is. That would have left two more kinds of record with a rule of their own.

## Decision

1. **The only exception stays the protocol's records.** leia-mcp's tool records and leia-runtime's records have `@Nullable` components. `OptionalInt`, `OptionalLong` and `OptionalDouble` become `Integer`, `Long` and `Double`. A method that returns an `Optional` may keep it, and so may a local.
2. **`Schemas` reads `@Nullable` at runtime.** A component is required exactly when its annotated type has `org.jspecify.annotations.Nullable`, which has RUNTIME retention. Type-use syntax places it: `Queries.@Nullable Term` for a nested type, and `byte @Nullable []` for an array. An `Optional` component now has no schema (`No schema for …`), so a record that still has one fails `ToolsListTest`. The wire contract is unchanged: `tools.json`, the golden `tools/list`, is byte-identical. Jackson needs nothing: an absent property binds as `null`, and `NON_ABSENT` already leaves `null` out of a result.
3. **JSpecify is on leia-mcp's runtime classpath** (`implementation`), because `Schemas` reads it there. It was in `leia.jar` already, a runtime dependency of Dagger, and `NOTICE` already lists it. Elsewhere it stays compile-only: `compileOnlyApi` in `leia-client` and `leia-runtime`, `compileOnly` in the runtimes, so that no adapter jar and no `runtime.txt` changes (`adapterJarCheck`).
4. **leia-runtime's interface takes `@Nullable` too**: the records' components and the parameters of its groups, such as `Terms.terms(…, byte @Nullable [] from, …)` and the filter of `Knn`. `LuceneRuntime.API_VERSION` goes from 26 to 27, so that an adapter built against the old records is refused as outdated (ADR 0098).
5. **`runtime.support.Optionals`**, JDK-only, converts at the boundaries where a record still has or needs an `Optional*`: `orNull(OptionalInt|OptionalLong|OptionalDouble)` and `optionalInt`, `optionalLong`, `optionalDouble` of a nullable number. `orNull` taking an `Optional` is the accepted boundary adapter, as in leia-tui. leia-mcp has a package-private copy of leia-tui's helper.
6. **No nullness checker in the build.** NullAway or the Checker Framework would check the annotations, but they need an error-prone or a checker compiler setup for every module, and the runtimes compile against five Lucenes. The tests and the review hold the line for now.

## Alternatives considered

- **Exempt the tool records and the runtime records, as the protocol is.** Rejected: three kinds of record with their own rule instead of one, and the convention would be harder to state.
- **A leia annotation of its own instead of JSpecify's for `Schemas`.** Rejected: the code would carry two nullness annotations, and JSpecify was in `leia.jar` anyway.
- **Derive *required* from a `@Required` annotation.** Rejected: most components are required, so it would be the more common annotation, and a forgotten one would silently make an argument optional.

## Consequences

- Code reads `x != null` instead of `x.isPresent()`, and `Given.isTrue(flag)` for a flag that is false by default. A method on a now-nullable `String` or `List`, such as `isEmpty()`, still compiles but means something else, as do AssertJ's `isEmpty()` and `contains(x)`; the conversion checked for them.
- A compact constructor no longer checks a `@Nullable` component with `requireNonNull`; the required ones keep their checks.
- An adapter jar of API 26 in a user's `runtimes/` is rewritten at startup from `leia.jar` (ADR 0098), or refused as outdated.
- leia-server follows the rule too: `LeiaBackend.start` and `startWith` take a `@Nullable Path` for the runtimes and the cache folder, and `RuntimeFolders.ListedJar.mavenPath` is a `@Nullable String`, which leia-client's install and leia-tui's runtime window read. Its accessors that returned an `Optional` still do, and type arguments such as `List<Optional<FoundText>>` stay, as `Highlight.Highlighted` keeps them.
- leia-core follows the rule too: its records have `@Nullable` components, with `Integer` and `Long` for `OptionalInt` and `OptionalLong`, and `ReadOnlyIndex` takes `@Nullable` sorts, field sets, filters and bounds, so leia-lucene10 hands core's values to the runtime records as they are. Its accessors that returned an `Optional`, such as `DocFilter.timedOutValues`, still do. JSpecify is `compileOnlyApi` there, and `runtimes/lucene10` gains no jar.
- The adapters follow the rule too: `LuceneNPresets.Preset`, `rawRange`, `BinaryDocValuesQuery`, 9's `ExactKnnQuery` and `Lucene3Analyzers.named` take and hold `@Nullable` values, and their constructor and method lookups still return `Optional`. Nothing in an adapter's interface changed, so `API_VERSION` stays.
