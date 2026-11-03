# ADR 0097 — Logo colours from the theme

- **Status:** Superseded by ADR 0099 (an icon of characters and colours, named by the theme, drawn from new PNGs)
- **Date:** 2026-10-22
- **Amends:** ADR 0095 §1 and §2 (the logo row), ADR 0096 §1 (the role `logo`)

## Context

The start screen's logo is `…tui.widgets/leia.icon`: rows of ANSI escapes, each cell a character after its own 24-bit foreground colour, about 480 colours in all. A theme can only say `logo = coloured | plain` (ADR 0095, ADR 0096). So `nord` draws a brown gazelle on Nord's blue-grey, and a user theme can't touch the logo.

There is a new drawing of the gazelle in two colourings, `gazelle-default.go` and `gazelle-aurora.go` (each `fmt.Print` lines, 30 rows of 38 cells). What they hold:

- **Only foreground colours.** Every cell is `ESC[38;2;R;G;Bm` and one character; no cell sets a background (`ESC[48;…`), and each row ends with `ESC[0m`. The background is never painted: it is the terminal's.
- **Five colours and a canvas.** Each file has some 500 distinct RGB values, but they are five flat colours of the source drawing, darkened toward black where a cell only partly covers the shape, plus the black canvas around it. In `gazelle-aurora` the five are exactly Nord's Aurora colours (`#BF616A`, `#B48EAD`, `#A3BE8C`, `#EBCB8B`, `#D08770`).
- **The character is the brightness.** The converter picks each cell's character by how bright the cell is.
- **The two files don't share their characters.** They share the outline, but each was converted from its own colouring, so the same part of the gazelle has other characters in each. About 90 of the 1,140 cells are blank in one and not in the other.

## Decision

1. **The logo is characters and slots.** A resource `leia.logo` replaces `leia.icon`: rows of characters, a blank line, then as many rows of as many digits `0`–`5`, the slot of each cell. A space is slot 0 and nothing else is. `Logo` refuses a file whose blocks differ in size or break that rule; it is a bug of the build, as a broken bundled theme is (ADR 0096 §4). It still cuts the columns that are blank in every row and keeps `MIN_HEIGHT`.
2. **One set of characters, those of `gazelle-default`.** Both colourings draw them. Mixing the files would give the gazelle two outlines, and `gazelle-aurora`'s characters only suit its own colours. The slots come from `gazelle-default` too: each cell goes to the colour it is closest to, allowing for the darkening. Where both files have a cell, the slots agree with the Aurora colour of `gazelle-aurora` in three cells of four; the rest are edges.
3. **Six roles, `logo0` to `logo5`,** replace `logo` in `Palette` and in the theme files. Each is a style (ADR 0096 §2), and every theme file sets all six. A cell of slot 1 to 5 is drawn as its character in its slot's style. The cells of slot 0 are spaces: only `logo0`'s background shows there, and `none` lets `base` show. `logo0` is the transparency.
4. **The bundled themes:**

   | role | dark, light (`gazelle-default`) | mono | nord (`gazelle-aurora`) |
   |---|---|---|---|
   | `logo0` | none | none | none |
   | `logo1` | `#1A0D0D` | none | `#BF616A` red |
   | `logo2` | `#563F38` | none | `#B48EAD` purple |
   | `logo3` | `#9E6038` | none | `#A3BE8C` green |
   | `logo4` | `#C7905D` | none | `#EBCB8B` yellow |
   | `logo5` | `#E4CDA5` | none | `#D08770` orange |

   The default colours are each slot's most common colour in `gazelle-default` where a cell covers the shape fully. The Aurora colours are Nord's, which the brightest cells of `gazelle-aurora` match. `logo0` is `none` everywhere: the source's black is the converter's canvas, not part of the drawing, and `none` lets `nord` paint its own background behind the gazelle. `mono` is the old `logo = plain`.
5. **A theme file with `logo` is refused,** as any unknown role is (ADR 0096 §3), and the message names `logo0` to `logo5`. Theme files are a day old; no alias for `coloured` and `plain`.
6. **`gazelle-default.go` and `gazelle-aurora.go` are no part of leia.** `leia.logo` holds what was taken from them; once `Logo` reads it, they go, and so does `leia.icon`.

## Consequences

- Five colours instead of 480: the edges are no longer darker than the inside. The characters still draw the shading, since they carry the brightness.
- The outline `#1A0D0D` was made for a black canvas: on `dark` it all but vanishes, as it does in the source; on `light` it is a dark line, and `#E4CDA5` is faint on a white terminal. A user who minds copies `light.theme` and changes the slots.
- A user theme can colour the logo, or leave it the terminal's colour with `none`.
- Another drawing is another `leia.logo`, with no change to the code, as long as it keeps to five slots.

## Alternatives considered

- **Each colouring with its own characters.** `nord` would draw `gazelle-aurora` as it is, but the logo would be two resources, a theme would have to name which one it draws, and a user theme could only choose between the two. Rejected: one drawing, coloured by the theme.
- **Keep the 480 colours and tint them.** Scaling each cell toward a theme colour keeps the soft edges, but a theme would then say how to tint, not which colour, and the shading rests on a black canvas that `light` and `nord` don't have. Rejected.
- **The characters of `gazelle-aurora`.** Its outline is clearer, but they were picked for its bright colours and are too heavy in the darker colours of `dark` and `light`. Rejected.
- **Names such as `logoOutline` and `logoHorns`.** They read better in a theme file, but a slot paints whatever the drawing puts in it, and `logo0`–`logo5` match the digits of `leia.logo`.
- **`logo = plain` as a shorthand for six `none`.** Two ways to say one thing. Rejected; `mono.theme` sets the six.
