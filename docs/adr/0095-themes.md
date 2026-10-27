# ADR 0095 — Themes

- **Status:** Accepted, amended by ADR 0096 (theme files, user themes, `T` names the theme) and ADR 0097 (the logo's roles)
- **Date:** 2026-10-22
- **Revised 2026-10-23** after looking at every screen in all four themes: `nord`'s `muted` and `border` went from `#4C566A` (1.7:1 on `#2E3440`, panel titles all but vanished) to `#7B88A1` (3.5:1), and `mono`'s `match` from `reversed` to `bold underlined`, since the selected row is reversed and hid the match
- **Revised 2026-10-24** after a look in Windows Terminal with a light scheme: `nord` drew the borders and titles of popups and frames in the terminal's foreground, dark on its own background. They now take `base` through `Panels.box` (point 4)

## Context

Luke has four colour themes, kept in `luke.ini` (`docs/luke-features.md` §4). leia hard-coded its colours: about 90 sites of `Style.EMPTY.red()`, `.yellow()`, `.green()`, `.cyan()` and `.magenta()` in about 20 classes, and a few static styles (the viewer's JSON, the highlighted match, the runtime window's failures). On a light terminal yellow, cyan and green are hard to read, and there was no way to turn colour off for `NO_COLOR` or a monochrome terminal.

The themes are a matter of the TUI only: no protocol, server or runtime change.

## Decision

1. **Roles, not colours.** `…tui.theme.Palette` is a record of `Style`s, one per role: `base` (the whole screen), `error`, `warning`, `success`, `accent` (segment names, field names, formats, the spinner), `jsonKey`, `jsonString`, `jsonNumber`, `jsonLiteral`, `match` (a highlighted match), `muted`, `border`, `borderFocused`, and whether the logo has its colours. No code outside `…tui.theme` and the logo names a colour. Bold, dim and reversed (the selection, the focused tab) are no roles: they read well under every theme.
2. **Four themes**, `Theme` `dark`, `light`, `mono` and `nord`:

   | role | dark | light | mono | nord |
   |---|---|---|---|---|
   | base | none | none | none | `#D8DEE9` on `#2E3440` |
   | error | red | red | bold | `#BF616A` |
   | warning | yellow | 256-colour 130 | bold | `#EBCB8B` |
   | success | green | 28 | none | `#A3BE8C` |
   | accent | cyan | 25 | none | `#88C0D0` |
   | JSON key, string, number, literal | cyan, green, yellow, magenta | 25, 28, 130, 90 | none, dim, none, none | `#8FBCBB`, `#A3BE8C`, `#B48EAD`, `#81A1C1` |
   | match | black on yellow | black on 229 | bold underlined | `#2E3440` on `#EBCB8B` |
   | muted | dark grey | 245 | dim | `#7B88A1` |
   | border, focused | none | none | none | `#7B88A1`, `#88C0D0` |
   | logo | coloured | coloured | plain | coloured |

   `dark` is the look leia had before, and the default.
3. **One current palette.** `Palette.current()` is a static field read at render time, and `Palette.use(Theme)` sets it. The TUI renders on one thread, and a theme passed through every screen's render call would change dozens of signatures for nothing. Tests that switch it reset it after.
4. **A painted background.** `App.render` first paints the whole screen with `base`, which is nothing for the terminal themes. A popup clears its area with `Panels.clear`, which paints `base` after TamboUI's `Clear`, since `Clear` alone resets the cells to the terminal's colours and would punch holes in Nord. A bordered block is a `Panels.box`, whose border style is `base` (a panel's is `border`): TamboUI keeps only a cell's background under a border and takes its foreground and the titles' from the border style, so a block without one draws them in the terminal's foreground. `nord` needs a terminal with 24-bit colour; leia doesn't detect it.
5. **Choosing.** At start: `--theme <dark|light|mono|nord>`, else `mono` if `NO_COLOR` is set and not empty (no-color.org), else the theme file, else `dark`. An unknown name exits 2, as does `--theme` with `--download-runtimes`. `T` on the start screen cycles the themes in that order and saves the one chosen. The documents tab keeps its own `T`, and the open prompt takes `T` as text.
6. **Remembering.** The theme file holds the theme's id on one line: `$XDG_CONFIG_HOME/leia/theme` (`~/.config/leia/theme`), or `%APPDATA%\leia\theme` on Windows, beside the default folder of jars (ADR 0024). It is written through a temporary file, as the history is (ADR 0020); a missing or unreadable file, or an unknown id, is no theme, and a failed write is ignored.

## Alternatives considered

- **Pass the palette through the render calls.** Explicit, but it touches the signature of every screen and widget for a value that changes only on the start screen. Rejected.
- **Detect the terminal's background or colour depth.** There is no reliable way across terminals and multiplexers (OSC 11 isn't answered everywhere, `COLORTERM` isn't always set). Rejected: the user chooses.
- **User-defined themes in a file.** More to document and to check, for little gain over four themes. Left out; the roles make it possible later.
- **The theme's name in the start screen's footer.** It makes the legend wrap on 80 columns, which costs the logo a row. The footer says `T theme`; the colours show which one is in use.
