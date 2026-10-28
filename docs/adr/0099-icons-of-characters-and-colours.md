# ADR 0099 — Icons of characters and colours

- **Status:** Accepted
- **Date:** 2026-10-24
- **Amends:** ADR 0097 §1, §3, §4 and §5 (the logo's file and its roles), ADR 0096 §1 (the role `logo`)

## Context

On a white terminal the `light` logo is poor: its colours were made for a black canvas (ADR 0097, Consequences). There is a new drawing of the gazelle in five colourings, each a PNG of 38 × 60 pixels with an indexed palette:

- `gazelle_default.png`: five colours (`#14070C`, `#523D38`, `#9E6038`, `#C7905D`, `#E5CFA7`) and a fully transparent one, the background.
- `gazelle_aurora.png`: the same pixels, in Nord's Aurora colours (`#BF616A`, `#D08770`, `#EBCB8B`, `#A3BE8C`, `#B48EAD`) and transparent.
- `gazelle_solarized_dark.png` and `gazelle_solarized_light.png`: the same pixels and the same transparency, in Solarized's colours: `#586E75`, `#2AA198`, `#859900`, `#B58900`, `#FDF6E3` for the dark theme, `#002B36`, `#073642`, `#586E75`, `#839496`, `#EEE8D5` for the light one, each in the place of the default's colour at the same rank.
- `gazelle_mono.png`: black and transparent, a drawing of its own; 277 of its pixels are drawn where the others are transparent or the other way round.

Drawn with half blocks, two pixels to a cell, each is 38 cells by 30 rows, the size of the old gazelle.

ADR 0097 can't hold it. A logo there is one grid of characters with a slot per cell, and a theme sets each slot's style. A half block shows two pixels, its foreground and its background, so a cell needs two colours, and which character it is depends on which of its pixels are transparent. A slot is one style for all its cells.

## Decision

1. **An icon is two files.**
   - **`<name>.chars`** holds a row of characters per line, read by code point.
   - **`<name>.colours`** holds a row of cells per line, separated by spaces, with one cell per character.
     - A cell is `fg/bg`, `fg` alone (no background), or `-` (neither). Either side may be `-`.
     - A colour is written as in a theme file (ADR 0096 §2): a name, `0`–`255` or `#RRGGBB`.
     - `-` means *not set*, a style without that colour: what is beneath shows. That is the theme's `base`, so `nord` paints its own background behind the logo.
   - **Shared characters.** If the first line of a `.colours` file is `chars = <other>`, it colours the characters of `<other>.chars`. One drawing can therefore have several colourings without copies of its characters.
   - **Short rows.** A row of characters may be shorter than its cells, as an editor that strips trailing spaces leaves it; the rest are spaces.
   - **Trimming.** The columns that are blank in every row at the right are cut, as before. A blank cell is a space without a background.
   - **Refused.** The parser refuses:
     - files with other numbers of rows;
     - a row of characters longer than its cells;
     - rows of cells that differ in width;
     - an unknown colour;
     - an empty icon.
2. **A theme names its icon,** with `logo = <name>`, a role every theme file sets. `logo0` to `logo5` are refused with a message that names `logo`. `Palette` has `Icon logo` in place of the six styles.
3. **Where icons come from.** A user theme's icons come from the user's themes folder, then from leia's own icons, resources beside the theme files (`Icons`). Each file is looked up on its own, so a single `.colours` file of the user's can colour leia's gazelle with `chars = gazelle`. An icon that is missing or broken makes its theme broken. A user theme is then logged and left out, and a bundled one is a bug of the build (ADR 0096 §4).
4. **The bundled icons:**

   | icon | from | characters | colours | themes |
   |---|---|---|---|---|
   | `gazelle` | `gazelle_default.png` | its own | its palette colours | `dark`, `light` |
   | `gazelle-aurora` | `gazelle_aurora.png` | `chars = gazelle` | its palette colours | `nord` |
   | `gazelle-solarized-dark` | `gazelle_solarized_dark.png` | `chars = gazelle` | its palette colours | `solarized-dark` |
   | `gazelle-solarized-light` | `gazelle_solarized_light.png` | `chars = gazelle` | its palette colours | `solarized-light` |
   | `gazelle-mono` | `gazelle_mono.png` | its own | every cell `-` | `mono` |

   Each was converted once, by pixel, two to a cell, without scaling. The *canvas* is the transparent pixels; they are not set. `gazelle_aurora.png` and the two Solarized PNGs have the transparent pixels of `gazelle_default.png`, so their characters are the same, and their colours files say `chars = gazelle`. Each cell of `gazelle`, `gazelle-aurora` and the Solarized gazelles is:
   - both halves canvas → a space, `-`;
   - only the upper half canvas → `▄` in the lower colour;
   - only the lower half canvas → `▀` in the upper colour, without a background;
   - neither half canvas → `▄` in `lower/upper`.

   A `▄` without a foreground would paint the terminal's own foreground, black on a white terminal, in place of the canvas.

   `mono` is for `NO_COLOR` and monochrome terminals (ADR 0095), so `gazelle-mono` sets no colour: its black is the terminal's own foreground, which a fixed `#000000` would not be on a dark terminal. A cell is a space, `▀`, `▄`, or `█` where both halves are drawn, and every cell `-`.
5. **`leia.logo` and the PNGs are no part of leia.** The icon files hold what was taken from them.

## Consequences

- An icon can be a half-block picture with two colours per cell, or characters in one colour each, as the gazelle is.
- A user theme can bring its own logo, or recolour the gazelle, with no change to the code.
- A logo cell whose upper half is drawn has that half's colour as its background, so under `nord` and the Solarized themes not every cell has the theme's background; the test that every cell is painted allows the icon's colours.
- `Logo` takes its width and height from the icon in use. `MIN_HEIGHT`, the 18 rows the top of the logo is cut down to on a short screen, is still one number for all icons. For the gazelles of this ADR it drops the tips of the horns, where ADR 0097's gazelle had only sparse dots.
- The colours files are larger: `gazelle.colours` is about 9 KB.

## Alternatives considered

- **Keep the slots and add a background per slot.** A half block's two colours are any pair of the palette, so a slot would be needed per pair, and a theme would set styles for pairs, not colours. Rejected.
- **A legend of colours and a grid of keys per cell.** It is more compact, but each cell is then read through a table, and a drawing with many colours needs keys of several characters. Rejected: a cell names its colours.
- **One file with characters and colours mixed, as ANSI escapes are.** Characters and colours couldn't be edited, or shared, one without the other. Rejected.
- **A copy of the gazelle's characters per colouring.** That would be three equal files that could drift apart. Rejected for `chars = <other>`.
