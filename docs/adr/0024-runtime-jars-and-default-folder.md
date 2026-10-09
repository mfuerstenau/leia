# ADR 0024 — Jars at runtime and a default folder

- **Status:** Accepted
- **Date:** 2026-10-07
- **Amends:** ADR 0016 (jars can be added while leia runs), ADR 0018

## Context

ADR 0016 and ADR 0018 load jars with analysis components, codecs and formats once, at startup, from `--jar`. Changing them means restarting leia, and two things suffer from that:

- **An index that needs a codec,** which fails to open with `MISSING_CODEC`. The user has to quit, find the jar, and start leia again with `--jar`.
- **Jars that are always wanted,** such as a team's own analyzers or Lucene's Kuromoji module. They have to be given on every start.

ADR 0016 deferred both: "Add jars at runtime … the loader can grow a child later" and "A default folder for the user's own jars may come later".

`ExtensionJars.load` already builds each `URLClassLoader` as a child of the one before, and the presets are scanned again when the loader changes. What it lacks for runtime use:

- **It isn't atomic.** It reloads seven of Lucene's global lists one after the other: three factory lists and four codec lists. Each reload is atomic on its own (the new map replaces the old one), but if the fourth one fails, the first three already have the jar's names. At startup this doesn't matter, because the server exits. At runtime, the server would keep running with half of a jar.
- **Nothing tells the user what a jar brought.** A jar whose names are all taken adds nothing, and a codec jar doesn't show up anywhere.

Lucene's `AnalysisSPILoader` and `NamedSPILoader` are public. A new instance for a class loader runs the same `ServiceLoader` lookups as the global reload, so it fails the same way, but it changes no global state.

## Decision

1. **Two new methods.** `addJars {paths}` loads more jars, and `getJars {}` lists the loaded ones, in the order they were loaded. A path is a jar or a directory of jars, as for `--jar`. A relative path is resolved against the server's working directory, which is the TUI's. Neither method needs an open index.
2. **`addJars` changes nothing if it fails.** Before reloading the global lists, the server builds a private `AnalysisSPILoader` or `NamedSPILoader` for each of the seven kinds from the new loader. If one of them fails, the new loader is closed and the method fails with the new `INVALID_JAR` (-32017). A path that is neither a jar nor a directory with jars is also `INVALID_JAR`, and an empty `paths` is `INVALID_PARAMS`.
3. **A jar is loaded once.** A jar that is already loaded (the same absolute, normalized path) is listed in the result's `alreadyLoaded` and isn't loaded again. If every jar was already loaded, no loader is made.
4. **The result says what the jars added:** the new jars, and per kind the names that weren't there before:
   - presets (class names);
   - char filters, tokenizers and token filters (SPI names);
   - codecs, postings formats, doc values formats and KNN vectors formats.
   
   A name that a jar has but that was already taken isn't listed, so a jar that brought nothing new has empty lists.
5. **Each addition is another child loader.** Jars can't be taken out again. A class that leia or an earlier jar already has comes from there (the loader asks its parent first), as for `--jar`.
   - Every later lookup reads the service files of all loaded jars again, by path.
   - A loaded jar that has been deleted or replaced therefore makes every later addition fail. If the dry run fails and a loaded jar is gone, the error says so: "the jar … was loaded before and is gone; restart leia to load more jars". Lucene's own message, "Error accessing configuration file", names no jar.
6. **Jobs see the jars.** After an addition, the server makes the new loader the request thread's context class loader. Job threads are started from that thread and inherit it. A job that already runs keeps the loader it started with.
7. **`addJars` is no job.** It reads the given jars and nothing of the index. The preset scan, the slowest part, reads only the entries of the new jars and of the jars of the registered factories, as it already does on the first `getAnalysisComponents`.
8. **The default folder.** It is `$XDG_CONFIG_HOME/leia/jars`, or `~/.config/leia/jars` without `XDG_CONFIG_HOME`, on Linux, and `%APPDATA%\leia\jars` on Windows.
   - If it exists and has at least one `*.jar` directly in it, the TUI passes it to the server as one more `--jar`, after the ones from the command line. On a clash of names, `--jar` wins.
   - leia never creates the folder.
   - `--no-default-jars` leaves it out.
   - If the server fails to start with the folder, the TUI's error names the folder and `--no-default-jars`.
   - The server doesn't know about the folder.
9. **The TUI's jars popup.** `X` opens it on any screen where no input has the keys.
   - It shows the default folder and the loaded jars, from `getJars`. The jars that came from the default folder are marked.
   - It has a path input. Tab completes directories and `.jar` files.
   - Enter calls `addJars` and shows what was added, or the error.
   - After an addition, the analysis components are fetched again the next time the analysis screen needs them.
   - The `MISSING_CODEC` hint becomes "add a jar that has it with X, or start leia with --jar".
   - If the open prompt is closed after its open failed, the next `o` starts from that path, not from the open index. Esc, `X` and `o` then retry the open.
   
   `J`, the key the plan first named, is taken by the analyzer builder and the document viewer.

## Alternatives considered

- **Reload the lists and accept a half-loaded jar on failure.** This is Luke's way: its `addExternalJars` reloads the three factory lists in turn. Rejected: the server keeps running, so a failed jar shouldn't leave names behind.
- **Return only the jars** and let the TUI fetch `getAnalysisComponents` again. A codec jar would then show nothing at all. Rejected.
- **A recent-jars list** like ADR 0020's recent paths. The default folder covers jars that are always wanted. Left out.
- **Remove jars.** Lucene's lists only grow, and classes loaded from a jar can't be unloaded while anything holds them. Rejected, as in ADR 0016.
- **A `jars/` folder next to the executable.** It is portable with the release folder, but it is lost when a new release replaces the old folder. Rejected for the user's config directory.
- **Let the server read the default folder.** The server would have to know the platform's config directories, and `--version` and the tests would need a way to turn it off. The TUI already resolves the state directory (ADR 0020). Rejected.
- **A key in the open prompt to add a jar,** for an open that failed with `MISSING_CODEC`. Not chosen. Esc, `X`, then `o` does the same, since `o` brings back the path that failed.
- **Copy each jar to a private folder before loading it,** so that deleting or replacing the original can't break later additions. This costs a copy of every jar, and the listed paths would no longer be the user's. Rejected for the clear error above.

## Consequences

- `leia-core`:
  - `ExtensionJars.add` returns the new `AddedJars` (the jars, the already loaded ones and the added names in `JarComponents`), and `ExtensionJars.jars()` becomes public;
  - `load` and `add` share the dry run;
  - `load` also leaves out jars that are already loaded.
- `leia-protocol` and the TUI:
  - the methods `addJars` and `getJars`, with the DTOs `AddJarsParams`, `AddJarsResult`, `JarComponents`, `GetJarsParams` and `GetJarsResult`;
  - `INVALID_JAR` in both `ErrorCode` enums and `protocol/error-codes.json`;
  - fixtures for both methods and `addJars.error-invalid-jar.json`.
- `leia-server`: `AddJarsHandler` and `GetJarsHandler`.
- TUI:
  - `--no-default-jars` and the default folder;
  - the jars popup on `X`;
  - the `MISSING_CODEC` hint;
  - the analysis components are fetched again after an addition.
- The core tests that make jars (one with a broken codec entry, one with a test factory and a test analyzer) write them below `build/tmp/`, not to a JUnit temporary directory. A jar must outlive the test that loads it, because the next test's lookups read it again.
- `AGENTS.md` (TUI CLI contract) and the README describe `--no-default-jars`, the default folder and `X`.
