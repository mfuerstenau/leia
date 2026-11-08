# ADR 0126 — A headless client module and an app module

- **Status:** Accepted
- **Date:** 2026-11-08
- **Amends:** ADR 0030 (the export is put together in `leia-client`, not in the TUI); ADR 0034 §8 (the entry point and `leia.jar` are built by `leia-app`); ADR 0080 §4 (the install runs on a thread of `leia-client`'s `InstallJob`, still not a backend job); AGENTS.md, the module table and the dependency direction

## Context

`leia-tui` held more than the screens. It also held the runtime download (`RuntimeInstall`, mirrors, local caches), the folders of the platform (ADR 0122), the log, the history of paths, the document export (calls, a reconstruct job, the JSON, the checked write), the decoding of raw doc values and points, and `Main`, which starts both the TUI and MCP mode. MCP mode had its own copies of some of these: `StoredNumbers` and the token line of `reconstruct_fields`. Another UI would have had to depend on TamboUI to reuse any of them, or copy them.

## Decision

1. **`leia-client`** is a library without TamboUI and without Lucene. It holds what any frontend of leia needs that isn't drawing or keys:
   - `…client.platform`: `Platform`, `DefaultJars`, `RuntimesDirs`;
   - `…client.log`: `LeiaLog`, `LogBuffer`;
   - `…client.history`: `History`;
   - `…client.runtimes`: the install, the mirrors, the local caches, `InstallJob`, `RuntimeService`, and the unattended install of `--download-runtimes` (`RuntimeDownload`, with `ConsoleChooser` for the mirror question on a terminal), which prints to streams and owns no terminal;
   - `…client.export`: `DocumentExport`, `DocumentExporter`;
   - `…client.values`: the decoding of doc values' numbers and of points, Rust's `{:?}` for floats, and the tokens of a rebuilt field in position order.

   It depends on `leia-server` for `LeiaBackend` and the runtime catalog, on `leia-protocol` and on Jackson. A test checks that neither TamboUI nor Lucene is on its classpath.
2. **Long-running client work is a handle that a UI polls.** `InstallJob` and `ExportJob` each have an immutable `snapshot()`, `cancel()` and, for the install, `choose(…)` for the mirror question of ADR 0124. A UI needs no thread of its own and no callback onto its UI thread. It reads the snapshot on its tick, as it polls a backend job (ADR 0005). The install still doesn't run on the backend thread (ADR 0080 §4).
3. **`leia-app`** is the composition root: `Launcher`, `Main` and `Cli`, the scripts, the user README, and the builds of `leia.jar`, `leiaFolder` and the zip. It hands the TUI over to `leia-tui` (`Tui.run`) and `--mcp` over to `leia-mcp`. Another UI is one more module that `leia-app` dispatches to.
4. **`leia-tui` is the screens only.** It depends on `leia-client` for everything above. `ScreensOnlyTest` keeps downloads, hashing, JSON writing, the platform's folders and the install's threads out of it.
5. **MCP uses `leia-client`'s values.** `get_document` and `reconstruct_fields` answer exactly as before.

## Alternatives considered

- **Everything behind `LeiaBackend.call`.** Rejected: the install must run before the backend loads a runtime jar (ADR 0098, Windows' file locks), and a backend job contradicts ADR 0080 §4.
- **Several small modules** (`leia-platform`, `leia-install`, `leia-values`). Rejected for now: more build and public API surface, and the install needs the runtime catalog of `leia-server` anyway.
- **Keep the code in `leia-tui`, separated by package.** Rejected: another UI would still pull in TamboUI.
- **The document export as a backend job.** Not now: Jackson would enter `leia-server`. `leia-client` checks the target as the server checks a terms export.

## Consequences

- The module graph is `leia-app → leia-tui, leia-mcp, leia-client`, `leia-tui → leia-client`, `leia-mcp → leia-client`, and `leia-client → leia-server, leia-protocol`.
- `leia.jar` is built by `:leia-app:shadowJar`, and the development folder is `leia-app/build/leia/`.
- The TUI's install window and export render a snapshot. The 100 ms wait for the mirror answer on the install thread is gone.
