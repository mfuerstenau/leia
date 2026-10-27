# ADR 0072 — leia.jar without Lucene

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amended by:** ADR 0073 (`runtimes/` holds a folder per runtime with the official Lucene jars; `RuntimeJar` becomes `RuntimeFolder` with a state; `:leia-tui:run` takes `-Dleia.runtimes`); ADR 0098 (`runtimes/` is beside `leia.jar` only if it is there, else in the user's data folder)
- **Amends:** ADR 0034 (what `leia.jar` holds); ADR 0044 (`runtimes/` holds every runtime); ADR 0065 (step 4 lands); ADR 0071 §2 (leia-lucene10 is no runtime dependency of the server); AGENTS.md hard rule 5 and the CLI contract

## Context

This is step 4 of the Distribution lane (ADR 0065 §4). Since ADR 0071, the server reads every index through a runtime and compiles without leia-core and Lucene. Lucene 10 still reached `leia.jar` because leia-server had `runtimeOnly(project(":leia-lucene10"))`, which brought leia-core and about 12 MB of Lucene with it. `leia-lucene10.jar`, the runtime's shadow jar, was built and checked (`excludedPackagesCheck`) but not packaged. Steps 5 to 7 publish the runtime jars, download them with `--download-runtimes` and ship the release folders without `runtimes/`; all of them need `leia.jar` to work without any Lucene in it.

## Decision

1. **`leia-lucene10.jar` goes into `runtimes/`** with the other four. leia-tui's `runtimeJars` configuration gains it, so `leiaFolder` and `build-dist.sh` copy it without change. leia-server drops its `runtimeOnly` dependency on leia-lucene10, and keeps it for its tests only. `leia.jar` shrinks from about 17 MB to about 6.5 MB.
2. **The class-loader order stays as it is.** `Main` puts `runtimes/` first in the one `JarChain.load`, before the jars of `--jar` and the default folder. Those jars are compiled against `org.apache.lucene`, unrelocated (ADR 0065 §2), and their loader is a child of the one that holds `leia-lucene10.jar`, so they see its Lucene 10. Jars that X adds later are children of the last loader, so they see it too.
3. **`getRuntimes`** (`GetRuntimesParams`, `GetRuntimesResult`, `RuntimeJar` in `protocol.jars`) lists every runtime jar leia knows, newest first: its file name, the majors of the indexes it reads, and the Lucene version of the loaded runtime that reads them, or none if no loaded runtime does. The list lives in `Runtimes.KNOWN_JARS`, from which `jarFor` and `LATEST_JAR` are now derived, so a new runtime is one entry there and one line in leia-tui's `runtimeJars`.
4. **The about screen** shows the loaded runtimes (`Runtimes: Lucene 10.5.2 (9–10), 9.12.3 (8), …`) and, highlighted, the missing jars and the folder to put them into, or "(not run from leia.jar)" without one. With none loaded, it adds that indexes open only without a reader and that the analysis tab is off. `Main` fetches the list at start, beside `version`; the session fetches it again after `addJars` succeeds. Step 7 will point the hint to `leia --download-runtimes`.
5. **`./gradlew :leia-tui:run`** has no `runtimes/` folder (`RuntimeJars.none()`), so the task puts the runtime jars on its classpath. They are shadow jars, the old Lucenes relocated, and nothing else on that classpath brings Lucene 10, so nothing clashes.
6. **leia-server's `jarsTest` runs without Lucene on its classpath.** It subtracts a configuration that resolves `project(":leia-lucene10")` transitively, which covers the plain jar, leia-core and every `org.apache.lucene` artifact, beside the four runtime jars it subtracted before, and runs only `JarsBackendTest`, since the other test classes name Lucene types. `JarsBackendTest` starts with no jars and checks that `lucene9` and `demo` fail with `MISSING_RUNTIME` naming `leia-lucene10.jar`, open without a reader, that `getRuntimes` lists all five as missing and that the analysis is `NOT_SUPPORTED`. Then it loads `leia-lucene10.jar` first, as leia loads `runtimes/`: the indexes open with Lucene 10, and the codec, format and analysis jars find their Lucene classes in it.
7. **Checks.** leia-tui's `noLuceneInJarCheck`, an `ExcludedPackagesCheck` on `leia.jar` and part of `check`, fails on any class in `org/apache/lucene/`, `io/github/mfuerstenau/leia/core/` or `io/github/mfuerstenau/leia/lucene10/`. leia-server's `noLuceneCheck` walks its runtime classpath as well as its compile classpath, and also refuses leia-lucene10.
8. **`--help`** says that the runtimes are a jar for each Lucene from 3.6 to 10 in `runtimes/`, always loaded first, and that without them an index opens only without a reader.

## Alternatives considered

- **Fail at startup without `runtimes/`.** leia can still open an index without a reader, list commits it can't read and check indexes through a runtime that X adds later; and step 6 will need a `leia.jar` that starts so that it can download the runtimes. Starting and saying what is missing serves both.
- **Say it only when an index fails to open.** `MISSING_RUNTIME` does that already, but the analysis tab, which needs no index, would be off without a word.
- **Keep the test classpath with Lucene 10 for `jarsTest`.** `Lucene10Runtime` would then be found through the classpath, and the test would not show that `leia.jar` works with the runtime only from a loaded jar, nor that the extension jars link against its Lucene.

## Consequences

- Without `runtimes/`, leia starts, lists the missing jars on the about screen, and opens indexes only without a reader; X adding `runtimes/leia-lucene10.jar` makes Lucene 9 and 10 indexes open.
- The Pilot tests of leia-tui take leia-lucene10's plain jar as `testRuntimeOnly`, since leia-server no longer brings it.
- `:leia-server:analysisModules` still leaves out `lucene-core` and `lucene-analysis-common`, which now come from `runtimes/leia-lucene10.jar`.
- A runtime jar that isn't in `KNOWN_JARS` still loads and reads indexes, but the about screen doesn't list it.
