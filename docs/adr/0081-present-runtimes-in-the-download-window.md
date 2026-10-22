# ADR 0081 — Present runtimes in the download window

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amends:** ADR 0080 §2 and its rejected alternative "Offer every runtime, and refresh loaded ones" (present runtimes are listed, not offered); ADR 0079 §2 and its rejected alternative "Offer complete runtimes too" (they are shown, still not chosen); AGENTS.md, the CLI contract

## Context

The window that `D` opens (ADR 0080) listed only the runtimes that are MISSING or INCOMPLETE. A runtime that is there already, loaded, RESTART or REFUSED, didn't show, so the user couldn't read which Lucene jars its folder holds, where they come from and what their SHA-256s are. The install must still never write such a folder: a loaded runtime's jars are open, and Windows locks them.

## Decision

1. **Present runtimes are listed, checked and greyed out.** After the offered runtimes, in `RuntimeFolders.KNOWN` order, the list shows each runtime whose state in `getRuntimes` is LOADED, RESTART or REFUSED as `[x] Lucene 9 · indexes of 8 · loaded` (`loads on restart`, `refused`), dimmed. Space on such a row checks nothing and says that there is nothing to download for it; `a` checks only the offered runtimes, and the "Checked: …" total counts only them.
2. **Their details.** A state line (loaded with its Lucene version and that the download leaves its folder alone; complete, restart leia to load it; refused, with why), then each Lucene jar the adapter lists with its size, URL and SHA-256, a warning on a jar that isn't there or doesn't match the list (the loader checks only that a jar is there), and leia's own jars from `leia.jar`.
3. **Planned apart, read only.** `Action.PlanRuntimes(runtimes, present)`: the session plans `present` with `RuntimeInstall.plan`, which only reads and hashes the jars (reading an open jar is fine on Windows too), and hands the plans to the window beside the install's `Plan`. `RuntimeWindow.Install` carries that `Plan` only, so `RuntimeInstall.install` never sees a present runtime. If either plan fails, the planning fails as a whole.
4. **With nothing to download,** the window still says "Every runtime is loaded or complete; there is nothing to download.", and lists the present runtimes with the legend `↑↓ move   [/] details   Esc close`.

## Alternatives considered

- **Put present runtimes into the install's `Plan`, with a flag.** Rejected: `install` refreshes every planned runtime that needs no download, so one forgotten check would write the open jars of a loaded runtime. Keeping them out of the type that reaches `install` makes that impossible.
- **Offer present runtimes for a refresh.** Rejected, as in ADR 0080: there is nothing to gain, and a loaded runtime's jars can't be replaced on Windows.

## Consequences

- `RuntimeWindow` takes the runtimes' states (`List<RuntimeFolder>`) and `planned(install, plan, present)`; `App.runtimesPlanned` passes `present` on.
- Planning reads the present runtimes' Lucene jars too, which takes a little longer: the hashing of up to five folders.
