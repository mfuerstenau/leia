# ADR 0095 — Themes

- **Status:** Accepted, amended by ADR 0096 (theme files, user themes, `T` names the theme) and ADR 0097 (the logo's roles)
- **Date:** 2026-10-22
- **Revised 2026-10-23** after looking at every screen in all four themes: `nord`'s `muted` and `border` went from `#4C566A` (1.7:1 on `#2E3440`, panel titles all but vanished) to `#7B88A1` (3.5:1), and `mono`'s `match` from `reversed` to `bold underlined`, since the selected row is reversed and hid the match
- **Revised 2026-10-24** after a look in Windows Terminal with a light scheme: `nord` drew the borders and titles of popups and frames in the terminal's foreground, dark on its own background. They now take `base` through `Panels.box` (point 4)
- **Revised 2026-10-24** with two themes after Ethan Schoonover's Solarized, `solarized-dark` and `solarized-light`, each on its own background as `nord` is (points 2, 4 and 5). Their `muted` and `border` are Solarized's greys: `base00` `#657B83` on `base03` in the dark one (3.37:1) and `base0` `#839496` on `base3` in the light one (2.93:1). The light theme can't reach the 3.0 that `nord` met: no Solarized grey does on `base3` without becoming body text, so the shared contrast test's bound is 2.9
- **Revised 2026-10-24** after the same look: faint text (SGR 2) was drawn like normal text under One Half Light, so the hints, labels, line numbers and "none" lines that were `dim` weren't out of the way. They now take `muted` (point 1), which every theme sets: dark grey in `dark`, 245 in `light`, `#7B88A1` in `nord`, `dim` in `mono`

## Context

Luke has four colour themes, kept in `luke.ini` (`docs/luke-features.md` §4). leia hard-coded its colours: about 90 sites of `Style.EMPTY.red()`, `.yellow()`, `.green()`, `.cyan()` and `.magenta()` in about 20 classes, and a few static styles (the viewer's JSON, the highlighted match, the runtime window's failures). On a light terminal yellow, cyan and green are hard to read, and there was no way to turn colour off for `NO_COLOR` or a monochrome terminal.

The themes are a matter of the TUI only: no protocol, server or runtime change.

## Decision

1. **Roles, not colours.** `…tui.theme.Palette` is a record of `Style`s, one per role: `base` (the whole screen), `error`, `warning`, `success`, `accent` (segment names, field names, formats, the spinner), `jsonKey`, `jsonString`, `jsonNumber`, `jsonLiteral`, `match` (a highlighted match), `muted`, `border`, `borderFocused`, and whether the logo has its colours. No code outside `…tui.theme` and the logo names a colour. Bold and reversed (the selection, the focused tab) are no roles: they read well under every theme. Dim is none either, but only because `muted` replaces it: no screen asks for the terminal's faint text, which some terminals draw like normal text.
2. **Six themes**, `Theme` `dark`, `light`, `mono`, `nord`, `solarized-dark` and `solarized-light`:

   | role | dark | light | mono | nord | solarized-dark | solarized-light |
   |---|---|---|---|---|---|---|
   | base | none | none | none | `#D8DEE9` on `#2E3440` | `#839496` on `#002B36` | `#657B83` on `#FDF6E3` |
   | error | red | red | bold | `#BF616A` | `#DC322F` | `#DC322F` |
   | warning | yellow | 256-colour 130 | bold | `#EBCB8B` | `#B58900` | `#B58900` |
   | success | green | 28 | none | `#A3BE8C` | `#859900` | `#859900` |
   | accent | cyan | 25 | none | `#88C0D0` | `#268BD2` | `#268BD2` |
   | JSON key, string, number, literal | cyan, green, yellow, magenta | 25, 28, 130, 90 | none, dim, none, none | `#8FBCBB`, `#A3BE8C`, `#B48EAD`, `#81A1C1` | `#268BD2`, `#2AA198`, `#D33682`, `#6C71C4` | the same |
   | match | black on yellow | black on 229 | bold underlined | `#2E3440` on `#EBCB8B` | `#002B36` on `#B58900` (4.68:1) | the same |
   | muted | dark grey | 245 | dim | `#7B88A1` | `#657B83` | `#839496` |
   | border, focused | none | none | none | `#7B88A1`, `#88C0D0` | `#657B83`, `#268BD2` | `#839496`, `#268BD2` |
   | logo | coloured | coloured | plain | coloured | coloured | coloured |

   The Solarized themes' accent is blue in both, since cyan is only 2.93:1 on `base3`; `jsonKey` equals the accent, as it does in `light`.

   `dark` is the look leia had before, and the default.
3. **One current palette.** `Palette.current()` is a static field read at render time, and `Palette.use(Theme)` sets it. The TUI renders on one thread, and a theme passed through every screen's render call would change dozens of signatures for nothing. Tests that switch it reset it after.
4. **A painted background.** `App.render` first paints the whole screen with `base`, which is nothing for the terminal themes. A popup clears its area with `Panels.clear`, which paints `base` after TamboUI's `Clear`, since `Clear` alone resets the cells to the terminal's colours and would punch holes in Nord. A bordered block is a `Panels.box`, whose border style is `base` (a panel's is `border`): TamboUI keeps only a cell's background under a border and takes its foreground and the titles' from the border style, so a block without one draws them in the terminal's foreground. `nord` and the Solarized themes need a terminal with 24-bit colour; leia doesn't detect it.
5. **Choosing.** At start: `--theme <dark|light|mono|nord|solarized-dark|solarized-light>`, else `mono` if `NO_COLOR` is set and not empty (no-color.org), else the theme file, else `dark`. An unknown name exits 2, as does `--theme` with `--download-runtimes`. `T` on the start screen cycles the themes in that order and saves the one chosen. The documents tab keeps its own `T`, and the open prompt takes `T` as text.
6. **Remembering.** The theme file holds the theme's id on one line: `$XDG_CONFIG_HOME/leia/theme` (`~/.config/leia/theme`), or `%APPDATA%\leia\theme` on Windows, beside the default folder of jars (ADR 0024). It is written through a temporary file, as the history is (ADR 0020); a missing or unreadable file, or an unknown id, is no theme, and a failed write is ignored.

## Alternatives considered

- **Pass the palette through the render calls.** Explicit, but it touches the signature of every screen and widget for a value that changes only on the start screen. Rejected.
- **Detect the terminal's background or colour depth.** There is no reliable way across terminals and multiplexers (OSC 11 isn't answered everywhere, `COLORTERM` isn't always set). Rejected: the user chooses.
- **User-defined themes in a file.** More to document and to check, for little gain over four themes. Left out; the roles make it possible later.
- **The theme's name in the start screen's footer.** It makes the legend wrap on 80 columns, which costs the logo a row. The footer says `T theme`; the colours show which one is in use.
