# AGENTS.md — leia

leia is a **read-only Lucene index inspector**, an alternative to Luke (the Swing tool shipped with Lucene).
It is one Java application: a TamboUI terminal UI that calls a backend in the same JVM, which does all the Lucene work (ADR 0034).

- Work queue and milestones: `docs/tasklist.md`
- Architecture decisions: `docs/adr/`
- What Luke does, as a reference: `docs/luke-features.md`. Only features *not* tagged `[write]` are in scope; what is left to build is the backlog in `docs/tasklist.md`.

## Architecture

```
leia / leia.cmd  ──finds the JVM, runs──▶  <java> -jar leia.jar
   leia-tui (TamboUI, UI thread)
      │ LeiaBackend.call(RpcMethod<P, R>, P) → CompletableFuture<R>
      ▼
   leia-server (one backend thread, Dagger-built handlers)
      │ LuceneRuntime / RuntimeIndex (leia-runtime), found through ServiceLoader
      ▼
   a runtime per Lucene, each a folder in runtimes/ beside leia.jar, which holds no Lucene:
   its adapter jar and the official Lucene jars (ADR 0073); lucene10 (through leia-core) in the
   jar chain, lucene3/5/7/9 each in a class loader of its own
   logs → leia.log (ADR 0034 §7), never the terminal
```

| Path | Role |
|---|---|
| `leia-core/` | Java library with the Lucene 10 functionality, which `leia-lucene10` wraps as a runtime. No UI or protocol code. |
| `leia-runtime/` | The runtime interface (ADR 0044, ADR 0045, ADR 0046, ADR 0048, ADR 0049, ADR 0051, ADR 0055, ADR 0056, ADR 0057, ADR 0058, ADR 0059, ADR 0060, ADR 0061, ADR 0063, ADR 0064, ADR 0066, ADR 0067, ADR 0068, ADR 0069, ADR 0070): `LuceneRuntime` (with `API_VERSION`), its path-based `Commits` and `Check`, its `Analysis` (the analysis tab), its `Extensions` (the SPI lists that `--jar` reloads), `IndexReadException` (why an index can't be read), and `RuntimeIndex` with the groups `Documents`, `Terms`, `Search`, `Points`, `Knn`, `Rebuild`, `Highlight`, `Scoring`, `DocValuesSearch`, `StandardSearch` and `MoreLikeThisSearch`, with records of its own, and, in `…runtime.support`, the JDK-only helpers the runtimes share (ADR 0050). JDK types only; part of `leia.jar`. |
| `leia-lucene3/` | Lucene 3.6.2 for indexes written by Lucene 2.x and 3.x (ADR 0044), in a class loader of its own (ADR 0073). `Lucene3Runtime` implements `LuceneRuntime` and is found through `ServiceLoader`. Its adapter jar `leia-lucene3.jar` sits with the official `lucene-core-3.6.2.jar` in `runtimes/lucene3/` beside `leia.jar`, not part of it. `org.apache.lucene.index.RawPostings` lives in Lucene's package to reach 3.6's raw postings (ADR 0062). Also writes the Lucene 2.4 and 3.x test indexes. |
| `leia-lucene5/` | Lucene 5.5.5 with backward-codecs, queryparser and analyzers-common, for indexes written by Lucene 4.x and 5.x (ADR 0046). `Lucene5Runtime`, found as `Lucene3Runtime` is, builds 5.5's SPI lists through its own loader (`Spi`, ADR 0073), with doc-values, standard and More-Like-This queries (ADR 0074), custom analyzers, shingles and preset options for search (ADR 0075), and similarity and sort (ADR 0076); its folder is `runtimes/lucene5/`. Also writes the Lucene 4.x and 5.x test indexes. |
| `leia-lucene7/` | Lucene 7.7.3 with backward-codecs, queryparser and analyzers-common, for indexes written by Lucene 6.x and 7.x (ADR 0048). `Lucene7Runtime`, found as `Lucene3Runtime` is, with points, doc-values, standard and More-Like-This queries (ADR 0074), custom analyzers, shingles and preset options for search (ADR 0075), and similarity and sort (ADR 0076); its folder is `runtimes/lucene7/`. Also writes the Lucene 6.x and 7.x test indexes. |
| `leia-lucene9/` | Lucene 9.12.3 with backward-codecs, queryparser, analysis-common and highlighter, for indexes created by Lucene 8.x, also once 9.x wrote to them (ADR 0049). `Lucene9Runtime`, found as `Lucene3Runtime` is, with points, KNN vectors, highlighting (ADR 0063), similarity and sort (ADR 0064), doc-values, standard and More-Like-This queries (ADR 0066), and custom analyzers, shingles and preset options for search (ADR 0067); its folder is `runtimes/lucene9/`. Also writes the test indexes `lucene8` (8.11) and `lucene8to9` (a copy that 9.12 adds vectors to). |
| `leia-lucene10/` | Lucene 10, unrelocated, behind the runtime interface (ADR 0065): `Lucene10Runtime`, `Lucene10Index` and `Lucene10Analysis` (ADR 0067) delegate to leia-core and map its records. It reads the indexes of Lucene 9 and 10 and those of an unknown version (ADR 0071). Its folder `runtimes/lucene10/` beside `leia.jar`, not part of it (ADR 0072), holds its adapter jar, leia-core's jar and the official Lucene 10 jars, which leia loads as the first link of the jar chain (ADR 0073). |
| `leia-protocol/` | Records for every method's params and result, `Methods` (the registry), `RpcMethod` and `ErrorCode`: the API between the UI and the backend. |
| `leia-server/` | Library: a handler per method, with Dagger wiring, mapping the runtimes' results to the records (`RuntimeResults`); every index is read through a runtime (ADR 0071), and `CurrentIndex` holds an `OpenRuntime` or, without a reader, an `OpenDirectories`. It neither compiles nor runs against leia-core or Lucene (`noLuceneCheck`): the JDK-only algorithms the results go through, such as the stored scan and the term export, are its own. `LeiaBackend` runs the calls in order on one thread; `RpcException` carries an expected failure's `ErrorCode`. `JarChain` builds the class loaders of the extension jars, from which the runtimes reload their lists (ADR 0068). `RuntimeFolders` loads the folders of `runtimes/`, an old runtime through a class loader of its own and Lucene 10's into the jar chain, and again on `loadRuntimes` after a download, Lucene 10's only while the chain is empty, else its state is RESTART (ADR 0080); its `KNOWN` lists the runtimes, for `MISSING_RUNTIME` and `getRuntimes` (ADR 0072, ADR 0073), as `RuntimeCatalog` reads them from the catalog `META-INF/leia/runtimes.json`, which `runtimeCatalogCheck` keeps in line with the runtime modules (ADR 0078). |
| `leia-tui/` | The application: the CLI (`Cli`, `Main`), the version-check entry class `Launcher` (compiled for Java 8), the log, and a package per screen (`…tui.overview`, `…tui.documents`, …). Builds the **fat jar** `leia.jar`, without Lucene (`noLuceneInJarCheck`, ADR 0072), but with leia's own jars of the runtimes' folders under `leia-runtimes/`, which `RuntimeInstall` writes beside Lucene's from Maven Central, for the runtimes the user checks in `RuntimeWindow` (`D` on the start screen) or names with `--download-runtimes --runtime` (ADR 0073, ADR 0077, ADR 0079, ADR 0080); the scripts `leia` and `leia.cmd` are in `leia-tui/src/scripts/`. |
| `docs/` | `adr/`, `tasklist.md`, `SESSION-SUMMARIES.md`, `demo-index.md` (the manual-testing index), `luke-features.md` (the Luke analysis), `runtimes.md` (the features and differences per Lucene runtime). |

Dependency direction: `leia-server → leia-protocol` (api), `leia-server → leia-core` (tests only), `leia-server → leia-runtime`, `leia-server → leia-lucene3`, `leia-lucene5`, `leia-lucene7`, `leia-lucene9` (their folders, loaded by the tests as leia loads them, never on the classpath), `leia-server → leia-lucene10` (tests only), `leia-lucene10 → leia-core`, `leia-lucene3`, `leia-lucene5`, `leia-lucene7`, `leia-lucene9`, `leia-lucene10 → leia-runtime` (compileOnly), `leia-tui → leia-server`, `leia-tui → leia-protocol` (`leia-tui` can't compile against core or Lucene, and `leia.jar` holds neither), and `leia-tui` puts the five runtimes' folders into `runtimes/` (`leiaFolder`) and embeds leia's own jars of them. `leia-core` and `leia-protocol` do not depend on each other.

## Hard rules

1. **Read-only.** Never open an `IndexWriter`, never write a lock, and never modify files in an index directory. Luke's `[write]` features are out of scope.
2. **The UI reaches an index only through `LeiaBackend.call` and the protocol records** (ADR 0034 §6), and the server only through the runtime interface of `leia-runtime` (ADR 0071). The build keeps core and Lucene off `leia-tui`'s compile classpath.
3. **Every method has explicit records.** A method is a params and a result record in `leia-protocol`, an entry in `Methods`, and a handler in `leia-server` bound in `HandlerModule` (`Handlers` fails if one is missing). A field is optional exactly when it is `Optional*`; every other field is required. No `Map<String, Object>` or other untyped params or results.
4. **The terminal belongs to the UI.** Logs go to `leia.log` (ADR 0034 §7). While the UI runs, `System.out` and `System.err` go into the log too, so nothing may rely on printing to them.
5. **Lucene 9 and 10 indexes must open,** with Lucene 10 through `leia-lucene10`'s runtime, `runtimes/lucene10/` (ADR 0065, ADR 0071, ADR 0072, ADR 0073). Keep `lucene-backward-codecs` on its runtime classpath and keep a test that opens a Lucene 9 index. Lucene 2.x and 3.x indexes open with the Lucene 3.6 of `leia-lucene3`, 4.x and 5.x with the Lucene 5.5 of `leia-lucene5`, 6.x and 7.x with the Lucene 7.7 of `leia-lucene7`, 8.x with the Lucene 9.12 of `leia-lucene9`, through the runtime interface of `leia-runtime` (ADR 0044, ADR 0046, ADR 0048, ADR 0049), with the features `openIndex` reports. Each Lucene lives only in its runtime's class loader (ADR 0073): `leia.jar` holds none, an adapter jar holds only leia's code (`adapterJarCheck`), and no Lucene but 10 ever reaches the jar chain.
6. **Keep core Lucene-only.** UI concepts belong in the TUI, API concepts in protocol/server, and Lucene behind a runtime (ADR 0071).
7. **Long-running work is a job** (ADR 0005). Anything that can take long (a term scan, check index, …) runs as a backend job behind `start<Op>` / `get<Op>`, with `cancelJob` to stop it, and never blocks the backend thread. Cancellation is cooperative through the server's `ScanProgress` (`…server.job`), which a runtime sees as a `LongConsumer` that throws: never call `Thread.interrupt` on a thread that reads an index (it breaks `NIOFSDirectory`).

## Tech stack

Java 25 via a Gradle toolchain · Gradle 9 (Kotlin DSL, version catalog in `gradle/libs.versions.toml`) · Lucene 10 (+ backward-codecs, queryparser) · Dagger 2.60 (annotation processor, no reflection DI) · TamboUI 0.5.0, pinned (the Panama backend, with JLine 3 as the fallback) · Jackson 3 (`tools.jackson.*`), only in `leia-tui`, for the viewer's JSON and the document export · JUnit 6 + AssertJ, and TamboUI's Pilot for UI flows · Spotless with Palantir Java Format · Shadow for the fat jar. Base package and group: `io.github.mfuerstenau.leia` (`.core`, `.protocol`, `.server`, `.tui`). Shared build config lives in the `build-logic/` convention plugins (`leia.java-conventions`, `leia.java-library-conventions`, `leia.runtime-folder-conventions` for a runtime's adapter jar with its list and `adapterJarCheck`, and `leia.lucene-runtime-conventions` for an old Lucene runtime with its test indexes); see ADR 0002, ADR 0073. Release folders for Linux and Windows on x86_64, without `runtimes/`; no macOS, no bundled Java (ADR 0004, amended by ADR 0034 and ADR 0073).

## Commands

The Gradle wrapper only needs some Java 17+ runtime to start, for example `JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2` (the Temurin 25 that Gradle downloads for the daemon, once it has). The build daemon itself runs on Temurin 25. Gradle downloads that JDK automatically via foojay into `~/.gradle/jdks`, as pinned in `gradle/gradle-daemon-jvm.properties`. To change it, run `./gradlew updateDaemonJvm --jvm-version=<N> --jvm-vendor=adoptium`.

Keep these in sync with the build:

```sh
./gradlew build              # compile, test (also each module's jarsTest), spotlessCheck
./gradlew spotlessApply      # format Java
./gradlew :leia-tui:shadowJar      # fat jar → leia-tui/build/libs/leia.jar
./gradlew :leia-tui:leiaFolder     # leia.jar, the leia and leia.cmd scripts and runtimes/ (from Gradle) → leia-tui/build/leia/
./gradlew :leia-core:demoIndex     # demo index for manual testing → leia-core/build/test-indexes/demo (docs/demo-index.md)
./gradlew :leia-server:analysisModules   # Lucene's unbundled analysis modules → leia-server/build/analysis-modules/<module>/ (ADR 0016)
./build-dist.sh              # leia.jar, leia and leia.cmd, without runtimes/ → dist/leia-<version>-{linux,windows}-x86_64/
leia-tui/build/leia/leia --download-runtimes --runtime lucene10 --runtime lucene9 --runtime lucene7 --runtime lucene5 --runtime lucene3
                             # install runtimes/ unattended, from Maven Central (D on leia's start screen chooses them)

leia-tui/build/leia/leia <index>   # run a development build (needs a Java 25 JAVA_HOME or --java)
```

Run the formatters and linters before calling work done.

To see every call and its result, start leia with `LEIA_OPTS=-Dleia.log.level=FINE` and read the log: `$XDG_STATE_HOME/leia/leia.log` (`~/.local/state/leia/leia.log`), or `%LOCALAPPDATA%\leia\leia.log` on Windows. It is written anew on each start. Tests that go through `LeiaBackend` as the UI does (`RealBackend`, `DemoIndexTest`, `Lucene9BackendTest`, `JarsBackendTest`) are the way to try a method without the UI.

## TUI CLI contract

- `leia [OPTIONS] [INDEX_PATH]`
- `-j, --java <PATH>`: the JVM, given as a `java` executable or a JDK/JRE directory. Default: `$JAVA_HOME/bin/java`, then `java` on `PATH`. The scripts handle it; `leia.jar` never sees it.
- The scripts run the `leia.jar` in their own folder (`leia` resolves symlinks, `leia.cmd` doesn't). `LEIA_OPTS` holds options for the JVM, split at spaces and put before `-jar`. Before them, the scripts pass `-Dsun.misc.unsafe.memory.access=allow`, which keeps Java 25's `Unsafe` warning off the screen and lets the old runtimes unmap (ADR 0047). A wrong command line exits with 2; a JVM or `leia.jar` the script can't find exits with 1; otherwise the script passes on Java's exit status (ADR 0034 §8).
- `leia.jar` checks for Java 25 first, through `Launcher`, and says what is needed.
- `--jar <PATH>` (repeatable): a jar with analysis components, codecs or formats of its own, or a directory of such jars. leia checks that the path exists and loads it at startup (ADR 0016, ADR 0018). `--version` doesn't load them.
- `runtimes/` beside `leia.jar` holds a folder for each Lucene runtime (`lucene3`, `lucene5`, `lucene7`, `lucene9`, `lucene10`), each with its adapter jar `leia-<folder>.jar` and the jars its `META-INF/leia/runtime.txt` lists (ADR 0073). leia finds it from its own jar's location (or `-Dleia.runtimes`, as under Gradle) and loads it at startup, before the jars of `--jar` and the default folder, whatever `--no-default-jars` says (ADR 0044, ADR 0072). A folder that is missing, incomplete or refused isn't fatal: leia starts, indexes it would read open only without a reader, and the about screen says what is wrong (`getRuntimes`). `D` on the start screen downloads the missing and incomplete ones in a window, and loads them in place (`loadRuntimes`), Lucene 10's only while no jars are loaded (ADR 0080).
- `--download-runtimes`: install the runtimes of `--runtime` into `runtimes/` without asking, for an unattended install, then exit: leia's own jars from `leia.jar`, Lucene's from `https://repo1.maven.org/maven2/` (`-Dleia.download.url` overrides it), each checked against the list's SHA-256 and size; a matching jar is kept, unlisted jars are removed. It lists the Lucene jars to download with their URLs, SHA-256s and sizes, then a line per jar. A runtime that fails doesn't stop the others; the exit status is 1 if one failed. Without `--runtime` it exits 2 and points to `D` on the start screen, where a user chooses. It takes no `INDEX_PATH` and no other option but `--runtime` (exit 2), and fails with exit 1 if not run from `leia.jar` or `runtimes/` isn't writable (ADR 0073, ADR 0077, ADR 0079, ADR 0080).
- `--runtime <FOLDER>` (repeatable): with `--download-runtimes`, a runtime to install; alone, or with a folder leia doesn't know, exit 2 (ADR 0079, ADR 0080).
- `--no-default-jars`: don't load the jars of the default folder, `$XDG_CONFIG_HOME/leia/jars` (`~/.config/leia/jars`) or `%APPDATA%\leia\jars` on Windows. Without it, the folder is loaded after the jars of `--jar`, if it has a jar in it (ADR 0024). `X` adds more jars at runtime through `addJars`.
- `--directory <fs|mmap|nio>`: the Lucene `Directory` to open indexes through (ADR 0020). Default: `fs`, which is Lucene's `FSDirectory.open`. The open prompt can change it with Ctrl+D.
- `--no-reader`: open `INDEX_PATH` without a reader, so that only its commits can be read and its indexes checked (ADR 0021, ADR 0022). The open prompt does the same with Ctrl+N.
- `INDEX_PATH` may be a directory with indexes below it, which open as one (ADR 0021).
- `--version` prints the name, version and build of leia, plus the Java version actually in use (through the `version` method).

## Reference material (read-only, sibling directory)

- `../lucene-cli/lucene/luke`: source for *what* to compute (`models/**`, `models/util/IndexUtils.java`). Do **not** copy its architecture (Swing, global state, eager term counting).

## Project workflow

- `docs/tasklist.md` is the work queue. When an item lands, move it to `docs/tasklist-done.md` (the archive).
- Significant decisions become numbered ADRs in `docs/adr/`.
- After a unit of work, append an entry to `docs/SESSION-SUMMARIES.md`. The `/session-summary` skill writes it, and `/catch-up` reads it.
- Don't commit unless asked.
- End each task with a proposed commit message of at most 4 lines, without a `Co-Authored-By` trailer.

### Commit messages

- Keep the subject to about 50 characters. 72 is the hard limit where tools start truncating or wrapping. Git itself doesn't enforce either number.
- Use the imperative mood, as if completing the sentence "If applied, this commit will ___": "Add retry to upload client," not "Added" or "Adds."
- Capitalize the first word and don't end with a period.
- Separate the subject from the body with a blank line. Tools use that blank line to tell the two apart. Wrap body lines at 72 characters, since `git log` (without `--oneline`) shows them as-is.
- Put the explanation in the body, not the subject. The subject says what changed, and the body explains why.
