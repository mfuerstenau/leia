# ADR 0068 — Runtime interface 18: extension jars

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0016, ADR 0018 and ADR 0024 (where the jars are loaded); ADR 0065 §6 (the interface now has a part for `addJars` and `getJars`)

## Context

This is step 2c of the Distribution lane (ADR 0065 §4). leia-core's `ExtensionJars` did two jobs:

- It built the chain of class loaders of the jars of `--jar`, of `runtimes/` and of `addJars`, each load a child of the one before.
- It reloaded Lucene 10's global SPI lists from that chain (char filters, tokenizers, token filters, codecs, postings, doc values and KNN vectors formats) and reported what a load added.

At step 3 leia-server stops compiling against leia-core, and at step 4 Lucene 10 moves into `runtimes/leia-lucene10.jar`. Only code inside that jar can then call `Codec.reloadCodecs` and the like. The two jobs have to come apart.

## Decision

1. **A new group, `Extensions`, through `LuceneRuntime.extensions()`.**
   - `check(loader)` builds private copies of the runtime's lists from the loader, changing nothing. It throws `Extensions.InvalidJarException` for a jar that fails, such as one without its dependency.
   - `reload(loader, jars)` reloads the lists from the loader and returns `Extensions.Added`: the names of the lists and the presets that weren't there before, each sorted, ignoring case. `jars` are all the jars the loader sees, for the preset scan.
2. **Only Lucene 10 has it.** 3.6, 5.5, 7.7 and 9.12 return empty: their SPI interfaces are relocated, and so are the names of their service files, so a jar compiled against `org.apache.lucene` can't add to them. `Lucene10Extensions` delegates to core's `ExtensionJars` and maps `JarComponents` and `ExtensionJarException`.
3. **leia-core keeps only the Lucene side.** `ExtensionJars` has `check(loader)`, `reload(loader, jars)`, `classLoader()` and `jars()`. `PresetAnalyzers` and `Analyzers` still read the last two. Paths, directories, the `URLClassLoader` and `AddedJars` are gone from core.
4. **leia-server builds the chain: `jars.JarChain`.** It is static, as global to the JVM as the lists it feeds, and has what core had: a directory stands for its jars sorted by name, the path messages, a jar loaded before is reported as `alreadyLoaded`, a loader whose jars fail is closed, and a jar loaded before that is gone is named ("restart leia"). A load:
   1. makes a `URLClassLoader` over the one before;
   2. finds the runtimes in it with `Runtimes.load`, a runtime of these very jars included, so `runtimes/leia-lucene10.jar` will reload its own lists at startup;
   3. calls `check` on each `Extensions`, and loads nothing if one fails;
   4. calls `reload` on each, merges their `Added`, and then commits the jars and the loader.
5. **Until step 3, core stands for Lucene 10.** With `Routing.DEFAULT`, a runtime that reads Lucene 10's indexes is skipped, and the server's `CoreExtensions` (the same mapping as `Lucene10Extensions`) reloads core's lists. With `Routing.lucene10Runtime()`, only the runtimes' groups are used. This is the rule the analysis handlers follow (ADR 0067 §9); without it, a test with `Lucene10Runtime` on its classpath would reload core's lists twice. Step 3 removes `CoreExtensions` and the rule.
6. **Callers.** `LeiaBackend.start`, `Runtimes`, `AddJarsHandler` (which now takes the `Routing`) and `GetJarsHandler` use `JarChain`. The protocol, the TUI, the error codes and the messages don't change.
7. **Runtime interface 18.** `LuceneRuntime.API_VERSION` is 18.

## Alternatives considered

- **Keep the chain in core until step 3.** Step 3 is already the biggest step. Moving the chain now leaves it only the deletion of `CoreExtensions`.
- **Let the runtime build the loader.** Each runtime would then keep a chain of its own, and the runtimes that `runtimes/` brings could not be found through it. One chain in the server, which the runtimes only read from, keeps the one order in which jars were given.
- **An `Extensions` group on the relocated runtimes too.** Nothing could reach it: a user's jar names `org.apache.lucene` service files.

## Consequences

- `Lucene10ParityTest` doesn't compare `addJars` and `getJars`. Loading is global to the JVM, so the second backend's `addJars` would find the jars already loaded. `JarsBackendTest` calls them with the routing to the runtime instead. It checks the names they report, the order of a directory's jars and `alreadyLoaded`, and that `getAnalysisComponents`, routed to `Lucene10Analysis`, lists the new filter.
- Core's `ExtensionJarsTest`, `KuromojiNoriTest` and `IcuCollationTest` load through a test helper, `TestJars`, as the server does. The path rules are tested in the server's `JarChainTest`.
- A jar compiled against Lucene 10 reaches Lucene 10 only. On an index read by a relocated runtime, its codecs and analysis components are never used, as before.
