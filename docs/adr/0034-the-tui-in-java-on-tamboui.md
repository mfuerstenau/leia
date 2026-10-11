# ADR 0034 — The TUI in Java on TamboUI

- **Status:** Accepted on 2026-10-09, after the spike ("Spike results"); decision 1 changed by it: Panama, with JLine as the fallback
- **Date:** 2026-10-09
- **Supersedes, once accepted:** ADR 0001, ADR 0003
- **Amends, once accepted:** ADR 0004 (the folders and the launch), ADR 0005 decision 2 (the transport)

## Context

leia is two processes in two languages: the Rust/ratatui TUI spawns `leia-server.jar` and talks JSON-RPC to it over a pipe (ADR 0001). That has a price:

- **Every DTO is written twice**, as a Java record and a serde struct, and golden fixtures with round-trip tests on both sides keep the two in step (ADR 0003). A new method touches six places (`AGENTS.md`, hard rule 4).
- **Two toolchains.** The Windows build needs `cargo-zigbuild` and zig. The tests against the real server run as `cargo test -- --ignored`, with indexes, jars and a Java 25 runtime that Gradle has to build first.
- **Two languages for one UI's logic.** Anything the UI parses or formats (doc values lines, Lucene ranges, byte strings) exists in Rust next to the Java that understands it.

TamboUI (https://tamboui.dev) is a terminal UI library for Java modelled on ratatui and Textual: immediate-mode widgets and layouts like ratatui's, a toolkit DSL, TCSS styling, and Pilot for driving an app in tests. Its backends are JLine 3, Panama (Java 22+) and Aesh. What is known so far:

- The latest release is `dev.tamboui:tamboui-toolkit:0.5.0` (2026-09-14). The docs are at 0.6.0-SNAPSHOT and call the library experimental, with APIs that may change.
- JLine 3 is documented as the reliable default on all platforms. Windows support per backend isn't stated.

The server already has the seam an in-process UI needs: each method is a `MethodHandler<P, R>` keyed by its `RpcMethod<P, R>`, bound into a set in `HandlerModule`. `Dispatcher` only adds the JSON around it.

What has to carry over:

- read-only (hard rule 1), and Lucene 9 and 10 indexes (rule 5);
- long work as jobs, cancelled cooperatively and never by `Thread.interrupt`, which breaks `NIOFSDirectory` (rule 7, ADR 0005);
- `CurrentIndex` touched by one thread only, the request loop (ADR 0005, Consequences);
- the extension jars and the context class loader (ADR 0016, ADR 0018, ADR 0024);
- the CLI contract in `AGENTS.md`.

## Decision

1. **The TUI moves to Java, on TamboUI 0.5.0 with the JLine 3 backend.** The version is pinned, and an upgrade is a change of its own. Panama stays a later option, if the spike gives a reason, such as startup time. *Changed by the spike: the Panama backend, with JLine 3 as the fallback. Both are in the jar, and `tamboui.backend` defaults to `panama,jline3`, which TamboUI tries in order. An explicit `-Dtamboui.backend` or `TAMBOUI_BACKEND` still wins. Panama is about 70 ms faster to the first frame on Windows and passed every check on both platforms.*
2. **One JVM, no transport.** The UI calls the handlers in-process, through `call(RpcMethod<P, R>, P)` over the Dagger-built `Set<MethodHandler<?, ?>>`. The `leia-protocol` records are its model. There is no JSON, no envelope, no request ids and no fixtures.
   - An expected failure is still an `RpcException` with its `ErrorCode`, and the UI shows it as the Rust TUI shows an error response now.
   - Any other `RuntimeException` becomes an `INTERNAL_ERROR` and is logged, as `Dispatcher.invoke` does.
3. **One backend thread.** Every call runs in order on a single executor thread, which takes the place of the request loop:
   - `CurrentIndex` keeps its one thread.
   - The UI thread never waits for Lucene: `call` returns a future, and the UI draws the result when it completes.
   - That thread's context class loader is set as `LeiaServer.main` sets it now, and `addJars` sets it again there.
4. **Jobs stay as in ADR 0005.** `start<Op>`, `get<Op>` and `cancelJob` are unchanged. The UI polls `get<Op>` on a timer, as the Rust TUI polls every 100 ms. Cancellation stays cooperative, through `ScanProgress` and `QueryTimeout`. This replaces ADR 0005's decision 2 (the stdin loop and the synchronous client), not its job model.
5. **Modules:**
   - **`leia-tui`, new**, an application: the UI, the CLI and the main class. It depends on `leia-server` and `leia-protocol`, and builds the fat jar `leia.jar`.
   - **`leia-server` becomes a library**, with `api` on `leia-protocol` and `implementation` on `leia-core`, and a public class for `call`. So the UI can't compile against core or Lucene, and the build enforces hard rule 6.
   - **Nothing is renamed.** `leia-server`, `RpcMethod`, `RpcException` and `Methods` keep their names, although they are now an in-process API.
6. **The protocol records are the API between the UI and the backend.** A field is optional exactly when it is `Optional*`, as before. The wire conventions of ADR 0003 have nothing left to serve once the Rust crate is gone, so these go with the old path:
   - `protocol/fixtures/` and the tests that replay them;
   - `Request`, `Response`, `JsonRpcVersion`, `ProtocolJson`, `ProtocolAnnotationIntrospector` and the Jackson annotations in `leia-protocol`;
   - `protocol/error-codes.json`, and Jackson itself if nothing else needs it.

   One rule replaces hard rules 2–4: **the UI reaches an index only through `call` and the protocol records.**
7. **Logs go to a file**, since the terminal belongs to the UI:
   - `$XDG_STATE_HOME/leia/leia.log`, or `~/.local/state/leia/leia.log` without `XDG_STATE_HOME`, on Linux; `%LOCALAPPDATA%\leia\leia.log` on Windows. That is the folder of ADR 0020's history.
   - The file is overwritten on each start. The level comes from `-Dleia.log.level`, as now (`ServerLogging`). If the file can't be written, the logs are dropped.
   - Until the UI takes the terminal, messages go to stderr: a bad argument, jars that fail to load, `--version`. An error exits with a non-zero code.
   - While the UI runs, `System.out` and `System.err` go into the log, so a stray print can't break the screen. The terminal takes its streams before that.
8. **The launch.**
   - Two scripts, `leia` (sh) and `leia.cmd`, find the JVM as `tui/src/java.rs` does now: `-j/--java` (a `java` executable, or a JDK/JRE directory), then `$JAVA_HOME/bin/java`, then `java` on `PATH`. They run `leia.jar` from the script's own folder, symlinks resolved, and pass the other arguments on. *Settled with the scripts:*
     - *The environment variable `LEIA_OPTS` holds options for the JVM, such as `-Dleia.log.level=FINE`. It is split at spaces, without globbing, and goes before `-jar`.*
     - *The values of `--jar` and `--directory`, and everything after `--`, are passed on even if they look like `-j`.*
     - *A wrong command line exits with 2, as in `leia.jar`. A JVM or `leia.jar` the script can't find exits with 1. Otherwise the script passes on Java's exit status.*
     - *`leia.cmd` doesn't resolve symlinks. It runs the `leia.jar` in its own folder (`%~dp0`). On Windows, people put the folder on `PATH`.*
     - *The scripts are in `leia-tui/src/scripts/`, and `./gradlew :leia-tui:leiaFolder` puts them next to `leia.jar` in `leia-tui/build/leia/`.*
   - The Java side parses the rest of the CLI contract, unchanged: `--jar`, `--no-default-jars`, `--directory`, `--no-reader`, `INDEX_PATH` and `--version`.
   - **The Java version is checked by an entry class compiled for an old `--release`.** It compares `Runtime.version()` with 25 and says what is needed, instead of an `UnsupportedClassVersionError`, before it loads the rest. The spike checks that this works.
9. **Distribution, amending ADR 0004.** Still Linux and Windows on x86_64, unpacked folders and no bundled Java. Each folder holds `leia.jar`, `leia` and `leia.cmd`, and the jar is the same in both. `build-dist.sh` needs no cargo, cargo-zigbuild or zig. macOS would work through the sh script, but stays untested and unsupported.
10. **The way there.**
    - The Rust TUI and the JSON-RPC path keep working, under their rules, until the port reaches parity: `docs/demo-index.md` and what the real-server tests check now. Then they are removed in one step.
    - No new features until then (the freeze in `docs/tasklist.md`).
    - A spike decides go or no-go, and so this ADR's status. It checks:
      - the keys leia binds (Ctrl+D/N, AltGr characters, F-keys);
      - wide characters, resizing, and a table of 10,000 rows;
      - the OSC 52 clipboard;
      - startup time;
      - Windows Terminal;
      - the version-check entry class.
    - *Settled with the app shell:*
      - *`App` (`leia-tui`, package `…tui.app`) keeps the shape of `app.rs`: a key returns an `Action`, and the outcome comes back through `App`'s methods. `Session` makes the calls on `LeiaBackend` and dispatches each outcome to the UI thread as a `Reply` event, so `App` is only touched there.*
      - *The Rust TUI blocked on its calls, so no key came in between. While an open, reopen or close runs, `App` is busy and takes no key but Ctrl+C.*
      - *A screen that isn't ported yet shows a placeholder under its tab. The overview's lists the basics of the open index.*
      - *The Rust tests are ported as unit tests that render through `Frame.forTesting`. Pilot tests run whole flows against a real `LeiaBackend`. `Pilot.press` only dispatches the key and sleeps 50 ms, so they wait on a barrier event instead.*
      - *The about screen shows leia's own lines and Java's. The backend's line is gone, since they are one build.*

## Alternatives considered

- **Keep Rust and ratatui.** It works. But the DTOs stay written twice, with the fixture machinery, two toolchains and the Windows cross-build. Rejected.
- **TamboUI over the JSON-RPC child process.** Nothing in the backend changes, but it keeps two JVMs and all the serialization when both sides are Java. Rejected.
- **Another Java library: Lanterna, or JLine alone.** Lanterna's widget model is far from ratatui's, so the port would be a rewrite. JLine alone means writing the widgets. TamboUI's model maps onto the current screens one by one. Rejected.
- **The Panama backend.** No extra dependency, but less proven, and its Windows support isn't documented. Deferred until the spike has numbers. *Chosen after the spike, with JLine as the fallback (decision 1).*
- **The UI and `leia-server` in one module.** One module less, but nothing would stop the UI from importing core or Lucene. Rejected.
- **A jlink-bundled runtime, now that everything is Java.** ADR 0004's reasons still hold. Rejected.
- **The scripts check the version from `java -version`.** Its output differs between vendors, and the check would be written twice, in sh and in cmd. Rejected for the entry class.

## Consequences

- **One language and one build.** Gradle only. The tests against the real server become ordinary JUnit and Pilot tests. A new method is a record pair, a handler and its UI, with no mirror and no fixture.
- **The backend thread could later run calls side by side**, for example two screens loading at once. Not used for now.
- **Risks:**
  - TamboUI is experimental and young, so an upgrade may break the UI; the pin contains this.
  - How it behaves in Windows terminals was unknown until the spike. It passed in Windows Terminal, with PowerShell and cmd. Older consoles (conhost) were not tried.
- **On acceptance:**
  - ADR 0001 and ADR 0003 get "Superseded by ADR 0034".
  - ADR 0004 gets "Amended by ADR 0034" for the folders and the launch.
  - ADR 0005 gets a note on its decision 2.
  - `AGENTS.md` changes with the removal of the old path: the architecture, hard rules 2–4, the tech stack, the commands and the CLI contract.
- **Lost:** talking to the server by piping a fixture into it. A JUnit test, or the log at `FINE`, takes its place.

## Spike results

The spike is the `leia-tui` module: the build, the fat jar `leia.jar`, the version-check entry class, and a four-tab app in the package `…tui.spike` that the port of the app shell deletes. The Windows half is `docs/spike-tamboui-windows.md`.

### Linux (2026-10-09, WSL2, tmux and `script`, Temurin 25.0.4)

Both backends pass every check:

- **Keys:** the keys leia binds arrive once each, with the right modifiers:
  - Ctrl+D/N/U/K/W/A/E/Y and Alt+B;
  - Esc, Tab, Shift+Tab (a `TAB` with shift, since TamboUI has no `BACK_TAB`), Enter, Backspace and Delete;
  - the arrows, PgUp/PgDn and Home/End;
  - F1/F5/F12, and `ü @ \ { € 漢 字 🔍` as typed characters.

  Both backends read the same VT byte stream through TamboUI's `EventParser`, so there are no press/release pairs as with crossterm on Windows.
  - Esc waits 50 ms for a following byte. Esc and a control key within those 50 ms become one `UNKNOWN` event; a printable key becomes Alt+key. This is the usual VT ambiguity and is not blocking.
  - Ctrl+H and Backspace are the same byte, as are Ctrl+I and Tab, and Ctrl+M and Enter. leia binds none of them.
- **Wide characters:** CJK, Hangul, simple emoji and combining accents keep the columns aligned, in a paragraph and in table cells. ZWJ emoji and flags count as one cluster of width 2. That is right for Windows Terminal, but tmux draws them as separate glyphs. ratatui's `unicode-width` counts them the same way, so nothing changes.
- **Resize:** follows, and redraws.
- **10,000-row table:** 0.5–2.5 ms per frame at any position, End included. `Table` walks the rows before the offset on every frame, as ratatui does, and that is cheap at this size.
- **OSC 52:** `Backend.writeRaw` gets `grüße 漢字 🔍` into tmux's clipboard (with `set-clipboard on`), on both backends.
- **Threads (§3, §4):**
  - A call on a single backend thread comes back through `TuiRunner.dispatch` with an event of its own, and the UI redraws for it. `runLater` alone doesn't redraw.
  - A 100 ms `tickRate` gives the job poll.
- **Logs (§7):** with `System.out`/`System.err` redirected once the terminal is up, stray prints land in the log and never on the screen. Both backends keep their own streams.
- **Version check (§8):**
  - The entry class compiles with `--release 8` on JDK 25. It needs `-Xlint:-options` against the "obsolete" warning.
  - It reads `java.specification.version`, because `Runtime.version()` is Java 9+.
  - Java 21 gets "leia needs Java 25 or newer; this is Java 21.0.12.1 (…)" and exit code 1.
- **Startup**, as the median of 10 runs at 120×40, until the first frame is drawn and flushed:

  | | from the launcher | process, end to end |
  |---|---|---|
  | JLine 3 | 208 ms (terminal ready at 110 ms) | 250 ms |
  | Panama | 216 ms (terminal ready at 125 ms) | 285 ms |
  | today: `leia-server.jar` up to its `version` response | – | 285 ms |

  The in-process backend will add the server's Dagger graph and its classes to this. Those load at startup today too, in the child process.
- **Pilot:** `TuiTestRunner`/`TuiPilot` from the `tamboui-tui` test fixtures (plus `TestBackend` from the `tamboui-core` ones) drive the app under JUnit 6. They press keys, including Ctrl+Alt, wait, and read `TestBackend.rawOutput()` for the OSC 52 bytes.
- **Packaging:** Shadow needs `DuplicatesStrategy.INCLUDE` to merge the two backends' `BackendProvider` services, as `leia-server` needs it for the codecs. Without it, only the first backend is found.
- **Pitfall in measuring:** `ProcessHandle.info().startInstant()` is off by hundreds of milliseconds on WSL, so the spike times from the launcher. Under `script`, the pty must have a size (`stty cols … rows …`), or Panama fails with "Failed to get terminal size". JLine then saw one Ctrl+Space (NUL) at startup, under `script` only.

### Windows (2026-10-09, Windows 11, Windows Terminal, Temurin 25.0.3)

The checklist `docs/spike-tamboui-windows.md` was run four times: PowerShell and cmd, each with both backends. JLine picked its native console terminal (`windows-vtp`, `NativeWinSysTerminal`), not a dumb one. Every run passed:

- **Keys:** each key of the list arrives once, with the right modifiers; there are no press/release pairs. F1/F5/F12 arrive too (not pressed in the PowerShell/JLine run).
  - AltGr on a German layout gives plain characters with no modifiers: `@ \ { [ ] } ~ | €`. So do `äöüß`, and pasted CJK, Hangul and emoji.
  - An emoji with a skin tone comes as two characters (`🙏`, `🏻`).
- **Wide text, resize and maximise, OSC 52 into Notepad, `p` with nothing on the screen, a clean terminal after Ctrl+C:** all right, checked by eye.
- **10,000-row table:** 2.4–10 ms per frame after scrolling to the end, as measured at the end of each run. The max, 159–175 ms, is the first visit, which builds the rows.
- **Startup,** from the launcher, 5 runs each in PowerShell:

  | | terminal ready | first frame |
  |---|---|---|
  | JLine 3 | 116–135 ms | 218–242 ms |
  | Panama | 73–78 ms | 159–165 ms |

### Outcome

**Go.** Nothing blocks on either platform, so the status is Accepted. The backend is Panama, with JLine as the fallback (decision 1): Panama is faster on Windows, needs no extra library, and was as correct as JLine everywhere.

The fallback covers only a backend that fails to create its terminal. A terminal that reports no size (a pty with 0×0, as under `script` without `stty`) makes Panama fail later, on the first size query, with "Failed to get terminal size". That doesn't happen in real terminals and is left as it is.
