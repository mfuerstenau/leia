# ADR 0098 — Runtimes in the user folder

- **Status:** Accepted
- **Date:** 2026-10-22
- **Amends:** ADR 0044, ADR 0072 and ADR 0073 (where `runtimes/` is); AGENTS.md, the CLI contract

## Context

Since ADR 0044, the runtimes are in `runtimes/` beside `leia.jar` (`Platform.runtimesDir()`), and ADR 0072 and ADR 0073 kept them there. That has two drawbacks. An install that the user can't write to, such as one in `/opt` or `Program Files` or one shared between users, has no place for `D` or `--download-runtimes` to put them. And a new version of leia, unzipped into a new folder, starts without the runtimes it downloaded before.

A folder that outlives leia's versions brings a problem of its own. The adapter jars, and leia-core in `lucene10`, are leia's own code, which `leia.jar` embeds under `leia-runtimes/`. After an upgrade the folder still has the old leia's adapters. These load without complaint unless `LuceneRuntime.API_VERSION` differs, and then the old leia's code runs behind the new one's interface.

## Decision

1. **Where the runtimes are.** `Platform.runtimesDir()` takes the first of:
   1. the folder that `-Dleia.runtimes` names, as under Gradle;
   2. `runtimes` beside `leia.jar`, if it is a directory, as in an install from before this ADR, a portable one, one that an administrator filled, or `:leia-tui:leiaFolder`;
   3. `runtimes` in the data folder: `$XDG_DATA_HOME/leia/runtimes`, or `~/.local/share/leia/runtimes` without it (a relative `XDG_DATA_HOME` is ignored, as the XDG spec says), or `%LOCALAPPDATA%\leia\runtimes` on Windows.

   Without `leia.jar` and without the property there is no folder, as before, since the adapters come from `leia.jar`. `D` and `--download-runtimes` write into whichever folder is in use. Nothing is migrated: an existing `runtimes/` beside the jar stays in use until the user removes it.
2. **leia's own jars are refreshed at startup.** Before the backend starts, and after the log is open, `Main` calls `RuntimeInstall.refreshOwnJars`. For each runtime folder that has an adapter, it rewrites leia's own jars that differ from those of `leia.jar`: first the jars that the new adapter's list names without a Maven path (leia-core), then the adapter. It creates no folder, downloads nothing and removes nothing, and what it writes goes to `leia.log`. If the new list names a Lucene jar that isn't there, as after an upgrade of Lucene, the runtime is `Incomplete`, and `D` downloads the jar. `--version` and `--download-runtimes` don't refresh: the first loads no runtime, and the second installs fully anyway.
3. **An adapter that isn't leia's own is refused as outdated.** `RuntimeFolders.loadFolder` compares the folder's adapter with the one `leia.jar` embeds under `RuntimeFolders.EMBEDDED` (`leia-runtimes/<folder>/<adapter>`). If they differ, the folder is `Refused` with a reason that starts with `outdated:`. The adapter alone is enough, since its list holds the SHA-256 of leia-core. Under Gradle, without the embedded jars, there is nothing to compare and the check is skipped. `ServerRuntimes` applies the same check to the adapter it takes for `--server`. Since §2 writes the adapter last, a refresh that fails halfway leaves the old adapter, and the runtime is refused rather than loaded with a mismatched leia-core.
4. **Why a refresh fails.** The folder may be read-only, or, on Windows, another running leia may hold the jar open. The refused runtime's reason tells the user to close any other leia that uses the folder, make it writable and restart. `D` doesn't refresh a refused runtime: it plans only missing and incomplete ones (ADR 0081).
5. **Two versions of leia sharing one folder** rewrite each other's adapters at each start. That is harmless, since each refreshes before it loads, unless one holds a jar open on Windows (§4). A Lucene jar that one version's `D` removed as stale is downloaded again by the other's `D`.

## Alternatives considered

- **Only the data folder.** A portable or administrator-filled install would then have to copy its runtimes into each user's folder, and `leiaFolder` would need `-Dleia.runtimes`. Keeping the folder beside the jar first costs one `isDirectory` check.
- **Move an existing `runtimes/` beside the jar into the data folder.** It's a write outside the data folder on the first start, which may fail on a read-only install, and it surprises a user who put it there on purpose. Rejected.
- **A folder per leia version** (`runtimes/<version>/`). It avoids the refresh and the outdated state, but each upgrade downloads all of Lucene again and old folders pile up. Rejected: Lucene's jars rarely change between versions of leia, leia's own are small and come from `leia.jar`.
- **Refuse outdated adapters without refreshing them.** The user would then have to press `D` after every upgrade. Rejected: the refresh needs no network, and the jars are leia's own.
- **Compare every own jar, not just the adapter.** The adapter's list already names leia-core's SHA-256, so a matching adapter with a mismatched leia-core can only come from tampering or a broken copy, which other checks don't catch either.
