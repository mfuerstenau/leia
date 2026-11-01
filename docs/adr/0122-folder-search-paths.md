# ADR 0122 — Search paths for leia's folders

- **Status:** Accepted
- **Date:** 2026-11-02
- **Amends:** ADR 0020 (the history's folder), ADR 0024 (the default folder of jars, user only), ADR 0034 (§7, the log's folder), ADR 0096 (where themes are read and written), ADR 0098 (the runtimes are looked up one at a time in several folders), ADR 0113 (the cache folder)

## Context

`Platform` chose one location per kind of folder and OS. It never checked that the folder existed, had no system-wide layer, and looked up the runtimes as one whole folder. A read-only `/opt/leia/runtimes` made downloads fail, an administrator couldn't give all users a default theme or a runtime, and moving `XDG_STATE_HOME` lost the history.

## Decision

1. **Candidate lists.** Each kind of folder is an ordered list. Candidates are de-duplicated; a relative or empty variable is ignored (the XDG rule).

   | Kind | User candidates | System candidates |
   |---|---|---|
   | config | `$XDG_CONFIG_HOME/leia`, `~/.config/leia`; Windows `%APPDATA%\leia` | each entry of `$XDG_CONFIG_DIRS` (default `/etc/xdg`) + `/leia`; Windows `%PROGRAMDATA%\leia` |
   | data | `$XDG_DATA_HOME/leia`, `~/.local/share/leia`; Windows `%LOCALAPPDATA%\leia` | each entry of `$XDG_DATA_DIRS` (default `/usr/local/share:/usr/share`) + `/leia`; Windows `%PROGRAMDATA%\leia` |
   | state | `$XDG_STATE_HOME/leia`, `~/.local/state/leia`; Windows as data | none |
   | cache | `$XDG_CACHE_HOME/leia`, `~/.cache/leia`; Windows `%LOCALAPPDATA%\leia\cache` | none |

   On Windows a variable that is unset falls back to `<home>\AppData\Roaming` or `\Local`; one that is set is used alone, since `%APPDATA%` can be redirected to a network share.

2. **Read** from the first candidate where the item exists, each item on its own (`theme`, `themes/`, `history`). **Write** to the first user candidate that is writable or can be created: the probe walks up to the nearest existing ancestor and checks `Files.isWritable`, and creates nothing. `-Dleia.runtimes` and `-Dleia.cache.dir` stay exclusive overrides with no fallback, which keeps tests hermetic.

3. **Config.**
   - `theme` is read from the first config folder that has it, system ones included, so an administrator can set a default; `T` writes the user's.
   - `themes/` is merged, starting from the bundled themes, then the system folders from the lowest precedence to the highest, then the user's: a later file replaces an earlier one by id.
   - `jars/` is **user candidates only**, the first that holds a jar. `%PROGRAMDATA%` lets any local user create folders, so loading jars from there would run code another user planted.

4. **Runtimes, one at a time.** The candidates are `-Dleia.runtimes` alone, or else (with `leia.jar`) `runtimes/` beside the jar if it is a directory, then `runtimes` in each user data folder, then in each system one, each tagged user or system. For each runtime the server goes through them in order and loads the first folder that is complete and usable. A missing, incomplete or refused folder moves on; if none loads, the first state that isn't missing is reported, as the most useful. The load itself happens once, which matters for Lucene 10, whose jar-chain load can't be undone. `getRuntimes` reports `path`, the folder the loaded runtime came from, and the about screen and the runtime window show it. `--server` takes the adapter of the first candidate that has a current one.

5. **System folders are hash-checked.** Every listed jar must match the SHA-256 and size of the adapter's list, and the adapter must equal the one `leia.jar` embeds (a system folder without an embedded adapter to compare is refused). A planted Lucene jar in a shared folder can't load.

6. **Install and refresh.** Downloads go to the first user candidate that is writable or can be created: beside the jar, else the user data folder. A read-only `/opt/leia/runtimes` no longer fails the download; it goes to `~/.local/share/leia/runtimes`. The refresh of leia's own jars at startup covers every user candidate that exists and is writable, never a system one: rewriting a system folder as root would break another leia version. A runtime found in a system folder is loaded, so the runtime window shows it as present and doesn't offer it.

7. **State and cache** fall back to the next user candidate only, never to `java.io.tmpdir`: it is shared and open to symlink attacks, the history would be exposed, and the 512 MB cache could fill a small tmpfs. The history is read from the first state folder that has one, so moving `XDG_STATE_HOME` doesn't lose it. `LeiaLog` still drops the file log if nothing is writable, and an empty cache folder keeps the cache in memory.

## Consequences and pitfalls

- Reading `~/.config` while `XDG_CONFIG_HOME` points elsewhere goes beyond the XDG spec, which uses the default only when the variable is unset. It was asked for, and it means an old `~/.config/leia/theme` comes back when the `$XDG_CONFIG_HOME` one is deleted.
- macOS keeps the Linux branch (ADR 0004). WSL reports Linux and uses the XDG paths in the Linux home.
- `ElasticsearchSetup.platformDir` parses `os.name` on its own; it is about Elasticsearch's folder and out of scope.
- `RuntimeFolder` in the protocol gains `Optional<String> path`; the five-argument constructor stays for callers that have none.
