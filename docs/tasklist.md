# Task list

The work queue for leia. Check items off (`[x]`) as they land. Finished items move to `docs/tasklist-done.md`.

## User folders — next

Where leia keeps its files. The item changes accepted ADRs, so it needs a new ADR. The theme files are done (ADR 0096).

- [ ] Runtimes in the user folder: today `runtimes/` sits beside `leia.jar` (ADR 0044, ADR 0072, ADR 0073), not in `~/.local`. Move it to `$XDG_DATA_HOME/leia/runtimes` (`~/.local/share/leia/runtimes`, `%LOCALAPPDATA%\leia\runtimes` on Windows), so a read-only or shared install of `leia.jar` works and a new version of leia keeps the downloaded runtimes. To settle: whether `runtimes/` beside the jar still counts as a fallback or override, `-Dleia.runtimes` under Gradle, `--download-runtimes` and `D` writing there, how a Lucene 10 runtime whose version doesn't match the adapter in `leia.jar` is treated, and the docs (AGENTS.md CLI contract, README, `build-dist.sh`).

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again.

The lane is done: on new code the gate passes, and the accepted rules are whitelisted in the repository (ADR 0088).

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Misc: about

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
