# ADR 0130 — A colour of their own for the sort markers

- **Status:** Accepted
- **Date:** 2026-11-09
- **Amends:** ADR 0096 (a theme file may leave out a role that falls back to another), ADR 0121 (one more slot, not required)

## Context

The `▲`/`▼` after the title of the sorted column (the overview's field table, a document's fields) and the `↑`/`↓` in the "sorted by …" title of the search hits had the style of the text around them: bold in the header, plain in a title. On a wide table the sorted column was hard to find at a glance.

## Decision

1. **A slot `sortMarker`** in `Palette`, which styles every sort marker and nothing else. `Panels.sortedTitle` builds a sorted column's title with its marker, and `Panels.sortMarked` styles the arrows of a text, such as the search's sort.
2. **It is optional.** A theme file without it takes its `accent`; `ThemeParser.OPTIONAL` names the role to fall back to. Unlike the slots of ADR 0121, a user's theme from before keeps loading. Setting it twice is still an error.
3. **Bold violet**, a hue that the tables don't use otherwise (only the viewer's JSON numbers or literals). The themes that follow a published palette use only its colours:

   | Theme | `sortMarker` |
   |---|---|
   | `dark` | `magenta bold` |
   | `light` | `90 bold` |
   | `nord` | `#B48EAD bold` (nord15) |
   | `solarized-dark`, `solarized-light` | `#6C71C4 bold` (violet) |
   | `mono` | `bold`, no colour |

## Consequences

- The sorted column and the sort's direction stand out in every theme but `mono`.
- A future slot can be optional in the same way, through `ThemeParser.OPTIONAL`, when a theme from before should keep loading.
