# ADR 0080 — The runtime download inside leia

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amends:** ADR 0079 §2–§3 and §7–§9 (a window inside leia replaces the standalone dialogue; `--yes` is gone; no terminal check); ADR 0077 §2–§3 (`--download-runtimes` asks nothing); ADR 0073 (the folders load at startup and on `loadRuntimes`); AGENTS.md, the CLI contract

## Context

`leia --download-runtimes` opened a TamboUI dialogue of its own before leia started (ADR 0079). A user who started leia without runtimes had to quit, run the dialogue and start leia again, and a script needed `--yes`. The download belongs where the missing runtimes show: on leia's start screen. The command line only needs the unattended install.

## Decision

1. **`D` on the start screen** (no index open, not the analysis tab, which has a `D` of its own) opens the window "Download runtimes", drawn over the screen inside leia's frame like the jars popup: centred, at most 110 columns wide and the screen's height less two rows, with `Clear` and a bordered block. Its header, list, details, gauge, jar lines and key legend stay inside the border. Without `runtimes/` beside `leia.jar`, `D` says so in the footer instead. The about screen's lines, its key legend and the hint of `MISSING_RUNTIME` point to `D`.
2. **Only missing and incomplete runtimes are offered.** The app plans the runtimes whose state in `getRuntimes` is MISSING or INCOMPLETE. A loaded, refused or RESTART runtime is never planned, so the install never writes a jar that is open: the loader of an incomplete folder is never made (`State.Incomplete` returns before), and a missing folder has none. Windows' file locks can't stop it.
3. **Phases.** Planning (a spinner while `RuntimeInstall.plan` hashes the jars that are there, on a thread of its own), choose (as in ADR 0079 §2; with nothing to download, it says so), installing (ADR 0079 §4–§6), loading and done. Esc before the install closes the window; Esc while it installs cancels it cooperatively, and the window shows "Cancelling…" until the install has ended; Esc or Enter after it closes the window. Ctrl+C quits leia at any time; `Main` then cancels a running install and waits for it, so no `.part` file stays.
4. **The download is not a backend job.** It runs on the TUI's daemon thread `leia-runtime-install`, with the cooperative cancel of ADR 0079 §6, and reports back to the UI thread. This is an exception to rule 7 of AGENTS.md: no index is involved, the backend thread stays free, and a job would make the backend wait on the network for nothing it reads. Only the loading afterwards goes through the backend.
5. **`loadRuntimes`.** Once a runtime is installed, the app calls the new method `loadRuntimes` (`LoadRuntimesParams()`, `LoadRuntimesResult(loaded, runtimes)`), which runs `RuntimeFolders.reload()`: `load` again on the `runtimes/` it was given at startup, which loads only the folders that aren't loaded yet. An old runtime gets its class loader of its own, as at startup. The backend thread's context class loader is set to the jar chain's again, as `addJars` does. `Runtimes` rebuilds its list when the folders' runtimes change, not only when the chain does. It is not a job: it only reads jars, as `addJars` does. The result says which folders this call loaded and every runtime's state, and the about screen shows the states. The window says to open the index again (`o`) to read it with the new runtime; since `D` works only with no index open, no open index keeps an old one.
6. **Lucene 10 loads in place only while the jar chain is empty.** Its folder becomes the chain's first link, since the jars of `--jar` link against its Lucene (ADR 0065). Once jars are loaded, they link against no Lucene of it, so a complete Lucene 10 folder gets the new state `RESTART` (`RuntimeState.RESTART`, "complete, but leia has to restart to load it"), and the window and the about screen say "restart leia to load it". At startup the chain is always empty when the folders load, so nothing changes there. The old runtimes always load in place.
7. **`--download-runtimes` is for unattended installs.** It needs `--runtime <folder>` (repeatable) and downloads those without asking: naming the runtimes is the consent. It prints the listing of ADR 0077 §2, a line per jar and the outcome. Without `--runtime` it exits 2 and points to `D`. `--yes` is gone, an unknown option now (exit 2). There is no terminal check and no dialogue, and the log goes to stderr at WARNING, as before.

## Alternatives considered

- **Rebuild the jar chain** with Lucene 10 first and the loaded jars after it. Rejected: the runtimes' SPI lists are global to the JVM and hold the classes of the old chain, which can't be unloaded, and an open index or analyzer would keep them. A restart is simple and certain.
- **A backend job for the download.** Rejected: see §4.
- **Offer every runtime, and refresh loaded ones.** Rejected: a loaded runtime's jars are open, which Windows wouldn't let it replace, and there is nothing to gain.
- **`D` on every screen.** Rejected: the analysis tab uses `D`, and downloading under an open index is pointless, since it keeps its runtime.
- **Keep `--yes`.** Rejected: with `--runtime` required, it said nothing more.

## Consequences

- `RuntimeDialog` is now `RuntimeWindow`, a popup whose `handleKey` returns `Nothing`, `Install` or `Close`; the `Session` runs the plan and the install on threads of its own and replies to the app. `Main` lost the dialogue's runner and its buffering of the log.
- `runtimeLoadTest` in leia-server runs `RuntimeLoadBackendTest` and `RuntimeRestartBackendTest`, each in a JVM of its own, since the folders load once per JVM.
