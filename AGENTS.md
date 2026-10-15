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
   leia-server (one backend thread, Dagger-built handlers) ──▶ leia-core (Lucene)
   logs → leia.log (ADR 0034 §7), never the terminal
```

| Path | Role |
|---|---|
| `leia-core/` | Java library with the Lucene functionality. No UI or protocol code. |
| `leia-lucene3/` | Lucene 3.6.2, relocated into `…lucene3.shaded`, for indexes written by Lucene 2.x and 3.x (ADR 0044). `Lucene3Index` has no Lucene types in its API. To become a jar of its own in `runtimes/` (ADR 0044); until then leia-server takes the shadow jar. Also writes the Lucene 2.4 test index. |
| `leia-protocol/` | Records for every method's params and result, `Methods` (the registry), `RpcMethod` and `ErrorCode`: the API between the UI and the backend. |
| `leia-server/` | Library: a handler per method, with Dagger wiring, mapping core results to the records. `LeiaBackend` runs the calls in order on one thread; `RpcException` carries an expected failure's `ErrorCode`. |
| `leia-tui/` | The application: the CLI (`Cli`, `Main`), the version-check entry class `Launcher` (compiled for Java 8), the log, and a package per screen (`…tui.overview`, `…tui.documents`, …). Builds the **fat jar** `leia.jar`; the scripts `leia` and `leia.cmd` are in `leia-tui/src/scripts/`. |
| `docs/` | `adr/`, `tasklist.md`, `SESSION-SUMMARIES.md`, `demo-index.md` (the manual-testing index), `luke-features.md` (the Luke analysis). |

Dependency direction: `leia-server → leia-protocol` (api), `leia-server → leia-core`, `leia-server → leia-lucene3` (shadow jar), `leia-tui → leia-server`, `leia-tui → leia-protocol` (`leia-tui` can't compile against core or Lucene). `leia-core` and `leia-protocol` do not depend on each other.

## Hard rules

1. **Read-only.** Never open an `IndexWriter`, never write a lock, and never modify files in an index directory. Luke's `[write]` features are out of scope.
2. **The UI reaches an index only through `LeiaBackend.call` and the protocol records** (ADR 0034 §6). The build keeps core and Lucene off `leia-tui`'s compile classpath.
3. **Every method has explicit records.** A method is a params and a result record in `leia-protocol`, an entry in `Methods`, and a handler in `leia-server` bound in `HandlerModule` (`Handlers` fails if one is missing). A field is optional exactly when it is `Optional*`; every other field is required. No `Map<String, Object>` or other untyped params or results.
4. **The terminal belongs to the UI.** Logs go to `leia.log` (ADR 0034 §7). While the UI runs, `System.out` and `System.err` go into the log too, so nothing may rely on printing to them.
5. **Lucene 9 and 10 indexes must open.** Keep `lucene-backward-codecs` on the runtime classpath and keep a test that opens a Lucene 9 index. Lucene 2.x and 3.x indexes open with the relocated Lucene 3.6 of `leia-lucene3` (ADR 0044), with the features `openIndex` reports; an unrelocated second Lucene must never reach the classpath.
6. **Keep core Lucene-only.** UI concepts belong in the TUI, and API concepts belong in protocol/server.
7. **Long-running work is a job** (ADR 0005). Anything that can take long (a term scan, check index, …) runs as a backend job behind `start<Op>` / `get<Op>`, with `cancelJob` to stop it, and never blocks the backend thread. Cancellation is cooperative through `ScanProgress`: never call `Thread.interrupt` on a thread that reads an index (it breaks `NIOFSDirectory`).

## Tech stack

Java 25 via a Gradle toolchain · Gradle 9 (Kotlin DSL, version catalog in `gradle/libs.versions.toml`) · Lucene 10 (+ backward-codecs, queryparser) · Dagger 2.60 (annotation processor, no reflection DI) · TamboUI 0.5.0, pinned (the Panama backend, with JLine 3 as the fallback) · Jackson 3 (`tools.jackson.*`), only in `leia-tui`, for the viewer's JSON and the document export · JUnit 6 + AssertJ, and TamboUI's Pilot for UI flows · Spotless with Palantir Java Format · Shadow for the fat jar. Base package and group: `io.github.mfuerstenau.leia` (`.core`, `.protocol`, `.server`, `.tui`). Shared build config lives in the `build-logic/` convention plugins (`leia.java-conventions`, `leia.java-library-conventions`); see ADR 0002. Release folders for Linux and Windows on x86_64; no macOS, no bundled Java (ADR 0004, amended by ADR 0034).

## Commands

The Gradle wrapper only needs some Java 17+ runtime to start, for example `JAVA_HOME=$PWD/.jdks/jdk-21.0.12.1+1-jre` (a local, git-ignored Temurin JRE). The build daemon itself runs on Temurin 25. Gradle downloads that JDK automatically via foojay into `~/.gradle/jdks`, as pinned in `gradle/gradle-daemon-jvm.properties`. To change it, run `./gradlew updateDaemonJvm --jvm-version=<N> --jvm-vendor=adoptium`.

Keep these in sync with the build:

```sh
./gradlew build              # compile, test (also each module's jarsTest), spotlessCheck
./gradlew spotlessApply      # format Java
./gradlew :leia-tui:shadowJar      # fat jar → leia-tui/build/libs/leia.jar
./gradlew :leia-tui:leiaFolder     # leia.jar with the leia and leia.cmd scripts → leia-tui/build/leia/
./gradlew :leia-core:demoIndex     # demo index for manual testing → leia-core/build/test-indexes/demo (docs/demo-index.md)
./gradlew :leia-server:analysisModules   # Lucene's unbundled analysis modules → leia-server/build/analysis-modules/<module>/ (ADR 0016)
./build-dist.sh              # leia.jar, leia and leia.cmd → dist/leia-<version>-{linux,windows}-x86_64/

leia-tui/build/leia/leia <index>   # run a development build (needs a Java 25 JAVA_HOME or --java)
```

Run the formatters and linters before calling work done.

To see every call and its result, start leia with `LEIA_OPTS=-Dleia.log.level=FINE` and read the log: `$XDG_STATE_HOME/leia/leia.log` (`~/.local/state/leia/leia.log`), or `%LOCALAPPDATA%\leia\leia.log` on Windows. It is written anew on each start. Tests that go through `LeiaBackend` as the UI does (`RealBackend`, `DemoIndexTest`, `Lucene9BackendTest`, `JarsBackendTest`) are the way to try a method without the UI.

## TUI CLI contract

- `leia [OPTIONS] [INDEX_PATH]`
- `-j, --java <PATH>`: the JVM, given as a `java` executable or a JDK/JRE directory. Default: `$JAVA_HOME/bin/java`, then `java` on `PATH`. The scripts handle it; `leia.jar` never sees it.
- The scripts run the `leia.jar` in their own folder (`leia` resolves symlinks, `leia.cmd` doesn't). `LEIA_OPTS` holds options for the JVM, split at spaces and put before `-jar`. A wrong command line exits with 2; a JVM or `leia.jar` the script can't find exits with 1; otherwise the script passes on Java's exit status (ADR 0034 §8).
- `leia.jar` checks for Java 25 first, through `Launcher`, and says what is needed.
- `--jar <PATH>` (repeatable): a jar with analysis components, codecs or formats of its own, or a directory of such jars. leia checks that the path exists and loads it at startup (ADR 0016, ADR 0018). `--version` doesn't load them.
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
