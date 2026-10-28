# Task list

The work queue for leia. Check items off (`[x]`) as they land. Finished items move to `docs/tasklist-done.md`.

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again.

The lane is done: on new code the gate passes, and the accepted rules are whitelisted in the repository (ADR 0088).

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Misc: about
- [ ] Themes: `solarized-dark` and `solarized-light`, after Ethan Schoonover's Solarized (https://github.com/altercation/solarized). Two bundled `.theme` files with the palette as `#RRGGBB`, painting their own background as `nord` does (`base`, so a truecolor terminal), and a gazelle recoloured for each (`.colours` beside the shared `gazelle.chars`, ADR 0099). Check the muted and border roles for contrast on `base03` and `base3`, and the match style. Add them to `Themes.BUNDLED` (the order of `T`), `ThemesTest`, the README's list of themes and ADR 0095's table, and look at them in a terminal with Solarized's own scheme and with another.

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
