# ADR 0002 — Java build conventions

- **Status:** Accepted
- **Date:** 2026-10-05

## Context

The Java side has three modules (`leia-core`, `leia-protocol`, `leia-server`). They share a toolchain, compiler flags, a test setup and formatting. `leia-server` must ship as one runnable `leia-server.jar` that can open Lucene 9 and 10 indexes. Lucene discovers codecs, postings formats and similar components through `ServiceLoader`, and both `lucene-core` and `lucene-backward-codecs` ship `META-INF/services` files with the same names. The server also has to report its version and build at runtime (`version` RPC, `leia --version`).

## Decision

1. **Conventions in `build-logic/`.** An included build holds precompiled Kotlin script plugins:
   - `leia.java-conventions`: Java 25 Adoptium toolchain, `-Xlint:all,-processing -Werror`, JUnit Platform with JUnit + AssertJ, and Spotless with Palantir Java Format.
   - `leia.java-library-conventions`: the above plus `java-library`.
   - `leia.lucene-runtime-conventions` (added with ADR 0044): the above plus Shadow, for a module `leia-luceneN` that relocates an old Lucene. It relocates `org.apache.lucene` into the module's package, merges services files, gives the unrelocated jar the classifier `plain`, and registers test indexes through the extension `luceneRuntime`. Its task `relocationCheck`, part of `check`, fails when the shadow jar holds a class or a services file outside the module's package. Shadow is a dependency of `build-logic` for it, not a plugin in the catalog, since the root class loader has it already.

   All versions live in `gradle/libs.versions.toml`, which `build-logic` imports as well. The root `build.gradle.kts` applies `leia.java-conventions` with `apply false`. That loads Spotless once in the root class loader, so projects with different plugin sets share its build service.
2. **Libraries.**
   - Jackson 3 (`tools.jackson.*`): the current major line; a fresh project has nothing to migrate.
   - JUnit 6 and AssertJ 3.
   - Dagger is used as an annotation processor in `leia-server` only.
3. **Fat jar.** The Shadow plugin (`com.gradleup.shadow`) builds `leia-server.jar`:
   - `mergeServiceFiles()` with `duplicatesStrategy = INCLUDE`. Shadow's default `EXCLUDE` drops duplicate service files before they are merged, and that loses codecs. Only the duplicate LICENSE and NOTICE files are excluded.
   - `Multi-Release: true` in the manifest, because Lucene and Jackson ship multi-release classes.
4. **Version and build.**
   - `version` (and `group`) come from the root `gradle.properties`.
   - `leia-server` generates `build-info.properties` with `version`, `commit` (`git rev-parse --short HEAD`) and `commitTime` (the commit's timestamp, not the wall clock, so the output is reproducible). `BuildInfo` reads it at runtime.
5. **Gradle features.** The configuration cache, the build cache and parallel execution are on (`gradle.properties`).

## Alternatives considered

- **`subprojects {}` in the root build.** Less ceremony, but Gradle discourages cross-project configuration, and it doesn't work with isolated projects. Rejected.
- **Jackson 2.** Many examples use it (including `../json-rpc-sample`), but it is the legacy line now. Rejected.
- **Build timestamp as the build ID.** Changes on every build, which defeats up-to-date checks and caching. Rejected in favour of the commit time.

## Consequences

- A new Java module only needs `id("leia.java-conventions")` or `id("leia.java-library-conventions")`.
- `-Werror` makes compiler warnings fail the build, as `clippy -D warnings` does on the Rust side.
- A build with uncommitted changes reports the last commit's hash. A dirty marker can be added later if needed.
- Where the TUI's version comes from (a shared `VERSION` file vs. Gradle) is still an open question in `docs/tasklist.md`. For now, Gradle is the source for the server.
