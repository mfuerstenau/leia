# ADR 0079 — The download dialogue

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amends:** ADR 0077 §2–§4 (a dialogue instead of the `[y/N]` question; `--runtime`); ADR 0073 §7 (a runtime's failure doesn't stop the others; a download can be cancelled); AGENTS.md, the CLI contract

## Context

`leia --download-runtimes` (ADR 0077) listed every Lucene jar of every runtime and asked one `[y/N]` question. A user who reads only Lucene 8 indexes had to download all five runtimes or none. The download printed a line per jar when it was done, with no progress in between, and the first failure stopped it.

## Decision

1. **Plan, then install the chosen ones.** `RuntimeInstall.plan(runtimes)` reads `leia.jar` and `runtimes/` and returns a `RuntimePlan` per runtime: its listed jars, the downloads, and the Lucene jars that are kept. It neither writes nor uses the network. `install(plan, chosen, listener, cancelled)` installs the runtimes that need no download, which only refreshes leia's own jars and removes stale ones (ADR 0077 §5), and then the chosen runtimes. It leaves an unchosen runtime that needs downloads untouched.
2. **The dialogue.** On a terminal, without `--yes`, `RuntimeDialog` (TamboUI) offers the runtimes that need downloads; complete ones aren't offered. Each one is a row, `[ ] Lucene 9 · indexes of 8 · 9 jars, 8.3 MiB`, and **none is checked** at first. Space checks the highlighted runtime, `a` checks all, `n` none. The details pane shows the highlighted runtime's downloads, each with its size, URL and SHA-256, then the Lucene jars already there and matching, then leia's own jars from `leia.jar`. The header says where the jars come from and what they're checked against, and the total of the checked runtimes. Enter installs the checked runtimes. Enter with nothing checked shows a hint instead of exiting.
3. **Declining.** Esc or `q` before the install prints `leia: nothing downloaded` and exits 1, with `runtimes/` untouched: complete runtimes aren't refreshed either, as with a no in ADR 0077 §3.
4. **Progress.** The install runs on a daemon platform thread of the dialogue's own. Its listener (`jarStarted`, `bytes`, `jarDone`, `runtimeFailed`) writes into a synchronised snapshot that each 100 ms tick draws: a row per Lucene jar of the checked runtimes, with a spinner on the active one, `✓` downloaded, `=` kept and `✗` failed, and bytes of size, plus a `LineGauge` of the bytes of all. The end comes back as an event. The Done phase shows a line per runtime (installed, failed with why, or cancelled), and stdout gets the same lines once the terminal is restored. The exit status is 0 only if every runtime it took on is installed.
5. **One failure doesn't stop the others.** A runtime that fails, such as a download that doesn't match, is recorded with its message, and the install goes on with the next runtime.
6. **Cancel.** Esc while the install runs cancels it cooperatively: the download loop reads 64 KiB at a time and checks the flag after each buffer and before each jar. On a cancel, it closes the body and deletes its `.part` file. Jars already moved into place stay, since they match. Runtimes not yet begun count as cancelled. The thread is never interrupted. This isn't a backend job (ADR 0005): there is no backend and no index here, only the dialogue's own thread, with the same cooperative cancel. The complete runtimes are refreshed first, so a cancel only reaches chosen ones. A stalled connection is noticed at its next buffer or at the download's 10-minute timeout.
7. **`--runtime <folder>`**, repeatable, goes only with `--download-runtimes` (exit 2 otherwise). An unknown folder exits 2 and names the known ones. It narrows the plan to the named runtimes. With `--yes` it installs only those. In the dialogue it offers only those that need downloads, checked.
8. **`--yes` keeps the text listing** of ADR 0077 §2, of every runtime that needs downloads (or of those of `--runtime`), and installs them with a line per jar. **Without a terminal** and without `--yes`, it still downloads nothing and exits 1 (ADR 0077 §3).
9. **Logs.** While the dialogue has the terminal, the log, `System.out` and `System.err` go to a buffer in memory, which goes to stderr once the terminal is restored. `leia.log` stays the last session's.

## Alternatives considered

- **A checkbox per jar.** Rejected: a runtime is useless without all of its jars.
- **Everything checked by default.** Rejected: the point is to download what the user chose.
- **Offer complete runtimes too, to refresh them.** Rejected: they are refreshed without asking, as before, and a row that does nothing would confuse.
- **A backend job.** Rejected: there is no backend in `--download-runtimes`, and starting one for this would load runtimes, whose jars Windows then wouldn't let it replace.
- **Exit 1 on Enter with nothing checked.** Rejected: an accidental Enter shouldn't end the dialogue.

## Consequences

- `RuntimeInstall` no longer prints or asks: the printing listener and `printListing` do the text, `RuntimeDownload` chooses between the listing, the dialogue and the refusal, and the `Prompt` is gone.
- The tests drive the dialogue with Pilot against a repository on loopback that can hold a download back, for the cancel.
