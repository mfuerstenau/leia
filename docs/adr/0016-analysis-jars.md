# ADR 0016 — Analysis jars

- **Status:** Accepted, amended by ADR 0018 (`--analysis-jar` is now `--jar`, and the jars also add codecs and formats)
- **Date:** 2026-10-07

## Context

leia analyzes with the analyzers and factories in `lucene-core` and `lucene-analysis-common` (ADR 0014, ADR 0015). Many indexes are built with components from elsewhere:

- **Lucene's other analysis modules,** which Luke bundles. With their dependencies, in Lucene 10.5.2:

  | Module | Adds | Mostly |
  |---|---|---|
  | ICU | 14.4 MB | icu4j |
  | Nori (Korean) | 7.6 MB | its dictionary |
  | Morfologik (Polish, Ukrainian) | 5.9 MB | the dictionaries |
  | Kuromoji (Japanese) | 4.6 MB | its dictionary |
  | SmartCN (Chinese) | 3.5 MB | its dictionary |
  | OpenNLP | 1.4 MB | opennlp-tools; the models are separate files anyway |
  | Stempel (Polish) | 0.5 MB | |
  | Phonetic | 0.4 MB | commons-codec |

  Together they add about 38 MB to the 12 MB `leia-server.jar`.
- **The user's own jars:** factories and, more often, an `Analyzer` subclass written for the index.

Luke's `addExternalJars` puts the chosen jars into a `URLClassLoader` whose parent is Luke's own loader. It then calls `CharFilterFactory.reloadCharFilters`, `TokenizerFactory.reloadTokenizers` and `TokenFilterFactory.reloadTokenFilters` with that loader. These lists are global to the JVM. A reload only adds factories: if a name is already taken, the first factory stays. A reload that fails, such as `NoClassDefFoundError` for a missing dependency (tried with the phonetic module without commons-codec), throws and adds nothing of that kind.

What also depends on the class loader:

- `CustomAnalyzer.builder()` and `builder(Path)` read resource files, such as a module's stop words, through `CustomAnalyzer`'s own class loader. That loader can't see the new jars.
- `PresetAnalyzers` (ADR 0015) loads classes through its own class loader. It scans the jars of `Analyzer` and of the registered factories, and it scans them only once.

## Decision

1. **No analysis module is bundled.** `leia-server.jar` and the release folders (ADR 0004) stay as they are. Every module, like any jar of the user's own, is an analysis jar that the user supplies.
2. **Analysis jars are given at startup.** `leia --analysis-jar <PATH>` can be repeated. `PATH` is a jar or a directory, and a directory stands for the `*.jar` files directly in it. The TUI checks that each path exists. It passes the paths to the server as `--analysis-jar <PATH>` arguments after `-jar leia-server.jar`. `--version` doesn't pass them. Changing the jars means restarting leia. They can't be added at runtime.
3. **The server loads them once, before it serves.** `leia-core`'s `AnalysisJars.load` checks the paths (a path must be a directory with at least one jar, or a file ending in `.jar`). It puts the jars into one `URLClassLoader` whose parent is the server's loader, and reloads the three factory lists from it. The server also makes that loader its main thread's context class loader. Threads created later inherit it, including the job threads. A path that fails the check or a reload that fails stops the server: it logs the reason to stderr and exits with status 1. The TUI shows that as a startup error.
4. **The loader is used wherever analysis loads by name.** Presets are loaded through it. Their scan also covers the analysis jars themselves, so a jar with only an `Analyzer` adds presets. A custom analyzer reads its resource files through it, after its config directory. A factory that fails while it is being built, such as with a `LinkageError`, is an `INVALID_ANALYZER` like any other failure to build.
5. **No protocol change.** The added factories and presets appear in `getAnalysisComponents` with their class names. The TUI knows which jars it passed.
6. **A Gradle task fetches the modules:** `./gradlew :leia-server:analysisModules` writes each module with its dependencies, but without `lucene-core` and `lucene-analysis-common`, to `leia-server/build/analysis-modules/<module>/`. Each folder can be passed as an `--analysis-jar`. The README lists the Maven coordinates for anyone who doesn't build leia.

## Alternatives considered

- **Bundle every module** (about 50 MB), as Luke does. Nothing has to be loaded at runtime for the modules, but every download carries 38 MB that most indexes never use. Rejected.
- **Bundle the small modules** (Stempel, Phonetic, OpenNLP, Morfologik, about 8 MB) and leave out the big ones. This splits the modules into two kinds for no reason the user can see. User jars still need the loader. Rejected.
- **Ship the modules in an `analysis/` folder next to the jar,** loaded automatically. Same download size as bundling. Rejected together with bundling. A default folder for the user's own jars may come later.
- **Put the jars on the JVM class path** (`java -cp leia-server.jar:<jars> …`). Resources and the context class loader would work without any changes. But `-cp` drops the manifest's `Enable-Native-Access`. A failing factory would only fail when it is first used, in a static initializer. And the server couldn't tell its own class path entries from the user's, which the preset scan needs. Rejected.
- **Add jars at runtime,** through a method such as `addAnalysisJars` and a TUI prompt (Luke's way). This is a protocol change, and jars can't be taken out again. Deferred: the loader can grow a child later.
- **Codecs, postings formats and other index SPIs** from the same jars, so that indexes with custom codecs open. This is a different feature with its own reloads. Deferred.

## Consequences

- `leia-core` gains `AnalysisJars` and `AnalysisJarException`. `PresetAnalyzers` scans again when the loader has changed.
- `LeiaServer` reads its arguments. Anything other than `--analysis-jar <PATH>` is a usage error with exit status 2.
- The core tests that load jars run in their own test task and JVM (`analysisJarsTest`), because the factory lists are global and only grow. They use the phonetic and stempel modules, which `:leia-core:testAnalysisJars` copies to `leia-core/build/test-analysis-jars/`. The real-server tests use these too.
- The TUI CLI contract in `AGENTS.md` gains `--analysis-jar`.
