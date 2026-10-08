# ADR 0020 — Directory choice and recent paths

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

leia opens every index with `FSDirectory.open`, which picks the `Directory` for the platform: `MMapDirectory` on a 64-bit JVM, which is the only one leia runs on. Luke lets the user choose the implementation when opening, mainly to compare how the index reads through `MMapDirectory` and `NIOFSDirectory`, or to avoid memory mapping where it fails (address space limits, some network or container file systems). Luke also accepts any `Directory` class with a `(Path)` constructor.

Luke keeps the last 10 opened paths in `~/.luke.d/history` and offers them in its open dialog. In leia the user types the path every time; Tab completes directories, but there is no way back to an index opened yesterday.

The other open options (`MultiReader`, no-reader mode, Elasticsearch/OpenSearch indexes) are not part of this ADR.

## Decision

1. **`openIndex` takes an optional `directoryImpl`:** `FS`, `MMAP` or `NIO`. `FS` is `FSDirectory.open`, Lucene's choice for the platform, and is what an absent `directoryImpl` means, so existing requests are unchanged. `MMAP` is `MMapDirectory`, `NIO` is `NIOFSDirectory`. Each uses `NoLockFactory`, as before (rule 1). The class that is actually used is already in `getIndexStats`' `directoryImpl`, which the overview shows; `openIndex`' result doesn't change.
2. **A fixed set, no class names.** Third-party `Directory` classes from a `--jar` are not offered: leia can't check that they never write, and the three Lucene ones cover the reasons to choose.
3. **`reopenIndex` keeps the directory,** as it already shares it with the new reader (ADR 0019).
4. **The TUI's default comes from `--directory fs|mmap|nio`** (default `fs`). The open prompt shows the choice in its title, and Ctrl+D cycles it for the opens from that prompt; it is kept for the next prompt in the session. The index of the command line is opened with the default.
5. **Recent paths, TUI only.** Each successful open puts the index's absolute path (from the server's result) first in a list of at most 10, without duplicates. In the open prompt, Up and Down step through the list like a shell's history, skipping the path that is already in the input; Down past the newest entry brings back what was typed.
6. **The list is a file, one path per line, newest first:** `$XDG_STATE_HOME/leia/history`, or `~/.local/state/leia/history` without `XDG_STATE_HOME`, on Linux; `%LOCALAPPDATA%\leia\history` on Windows. It is written whole (a temporary file renamed over it) after each open. A missing or unreadable file is an empty list; a failed write is ignored, since the history is a convenience and the TUI has nowhere to report it. Two leia sessions at once: the last one to open an index wins.

## Alternatives considered

- **A CLI flag only.** Simplest, but comparing two implementations on the same index would need two sessions. Rejected.
- **The prompt only.** A user who always needs NIO would press Ctrl+D on every open. Rejected.
- **Any `Directory` class with a `(Path)` constructor, as in Luke.** Possible with ADR 0018's jars, but read-only can't be guaranteed for code leia doesn't know. Left out until someone needs it.
- **Report the chosen implementation in `openIndex`' result.** `getIndexStats` already has it, and the TUI always fetches it after opening. Rejected as duplication.
- **A list of recent paths under the prompt.** Shows more at once, but needs a selection mode next to the editing keys. Rejected for Up/Down, which every shell user knows.
- **`~/.leia/history` on every platform, like Luke's `~/.luke.d`.** One place to look, but another dotfile in the home directory. Rejected for the platform's state directory, which needs no new crate.
- **Remember the directory choice per path.** Rarely matters; the choice is per session. Left out.

## Consequences

- `leia-core`: `DirectoryImpl` and `ReadOnlyIndex.open(Path, DirectoryImpl)`; `open(Path)` opens with `FS`.
- `leia-protocol` and the TUI: `OpenIndexParams.directoryImpl` and the enum `DirectoryImpl` on both sides; the fixture `openIndex.request-directory.json`.
- `leia-server`: `OpenIndexHandler` maps the enum.
- TUI: `--directory`, Ctrl+D and Up/Down in the open prompt, and the history file.
- Rule 7 matters more now that `NIOFSDirectory` can be chosen: interrupting a thread that reads through it closes its file channels. Cancellation is already cooperative (ADR 0005).
