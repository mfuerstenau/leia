# ADR 0121 — Style slots in themes

- **Status:** Accepted
- **Date:** 2026-11-02
- **Amends:** ADR 0095 (§1, the roles: bold and reversed are no longer outside them), ADR 0096 (the theme file sets five more roles, and a file that lacks them is refused and now reported)

## Context

Colours already went only through the 13 named slots of `Palette`, and every `.theme` file sets all of them. But 53 places in 20 screen files applied `Style.EMPTY.bold()`, `.reversed()` or `.italic()` themselves, and others chained `.bold()` onto a slot. A theme could not restyle these, and nothing stopped a hard-coded `Color` from being added.

## Decision

1. **Five new slots**, in `Palette`, in `ThemeParser`'s list of roles and in all six bundled themes:

   | Slot | Replaces | Bundled value |
   |---|---|---|
   | `heading` | bold section titles, table header rows, highlighted names (a segment, `#doc`, "leia" in the logo) | `bold` |
   | `label` | bold key or label columns (the key legend, the `g` menu, the info lines, the keys in `?`, the search labels, the viewer's active label) | `bold` |
   | `selection` | `reversed` on the selected row or tab | `reversed` |
   | `selectionUnfocused` | the selected row of a table without focus, which was bold | `bold` |
   | `secondary` | italic tokens at a position increment of 0 on the analysis screen | `italic` |

   The bundled values reproduce the old look exactly. `Panels.highlight(focused)` keeps its name and is `selection` or `selectionUnfocused`. A place that chained `.bold()` onto a slot composes slots instead: `Palette.current().heading().patch(Palette.current().error())`.

   `selectionUnfocused` is a fifth slot beyond the four first thought of, because `Panels.highlight` has an unfocused state that none of those fits.

2. **`Form.dim()` and `Viewer.dim()` are `muted()`.** They returned `muted`; the old name would trip the check below. `ScoringSettings.reversed()` is `withFirstLevelReversed()` for the same reason, and the three comparators use `Collections.reverseOrder`.

3. **A build check.** `SlotsOnlyTest` scans `leia-tui/src/main/java` outside `…tui/theme/` and fails, naming file and line, on `Color.`, `.fg(`, `.bg(`, `.bold()`, `.dim()`, `.italic()`, `.underlined()`, `.reversed()`, `.crossedOut()`, `.blink()` or `Modifier.`. Only the theme package builds a `Style`.

4. **A theme file from before refuses, and leia says so.** The parser still demands every slot (ADR 0096), so a user's theme without the five new ones is refused. `Themes.leftOut()` collects each refused file with the reason. The start screen shows it once, as a warning notice, until the next key; `--theme <id>` naming such a theme exits 2 with the reason instead of "unknown theme". The log has it as before.

## Consequences

- A theme author can restyle headings, labels and the selection. Existing user themes need five more lines (`heading`, `label`, `selection`, `selectionUnfocused`, `secondary`), which the notice names.
- `mono` keeps no colour: its new slots are modifiers only.
- A new screen can't hard-code a style without failing the build.
