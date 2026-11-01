# ADR 0120 — One keymap across the screens

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR 0052 (§1, the jump keys, and the footer's `⌫ back`), ADR 0083 (the README's key table)

## Context

Every screen built its footer as one string, glued to the app's keys in `App.footerKeys`. It was ungrouped and unstyled and listed every key at once: the documents legend had about 25 entries. The letters had drifted apart from screen to screen:

- Sort was `s`/`S` on the overview and `z`/`Z` on the documents screen, whose `s` and `S` were taken.
- `s` meant sort on the overview, search on the terms and documents screens, and settings on the search screen.
- Export was `e` on the terms screen and `E` on the documents screen.
- `r` meant read again, list again, analyze again, rebuild and recall.
- The jumps to other screens were letters of their own on each screen: `a s m N T C` on the documents screen, `s` on the terms screen, `/` on the overview.

## Decision

1. **Shared keys, one meaning each.**

   | Key | Meaning everywhere |
   |---|---|
   | `↑↓` `jk` `PgUp/PgDn` `Home/End` `G` | move; `g g` goes to the top (the menu's `g`), and `Home` still does |
   | `Tab` / `Shift+Tab` | next / previous panel; on a screen with two panels both switch |
   | `←→` | the screen's step: document, column, value |
   | `/` | the screen's input: the seek (terms, documents) or the line (search, analysis) |
   | `⏎` | open or drill into the selection |
   | `s` / `S` | sort: the next column / the other direction. On the search screen `s` opens "Similarity and sort", and `S` reverses the first sort level, without a sort the score, worst first, and searches again |
   | `g` | the go-to menu (§2) |
   | `b` / `B` | the documents as a filter: browse, unfilter, filter only |
   | `v` view · `y` copy · `E` export · `r` again · `n` number format · `t` point format · `h` hex · `f/F` field | as named |
   | `?` keys · `⌫` back · `Esc` stop or close · `q o c R 1-7 L X` | the app's; listed in the `?` window, not in the footer, whose tab line shows the numbers |

   The keys that changed: on the documents screen sort is `s`/`S`, the pin `P`, the number format `n` and the rebuild `I` (indexed terms), and the hidden `p`/`n` aliases of `←→` are gone, also in the viewer. Export on the terms screen is `E`. Recall on the search screen is `K`. The analyzer on the analysis screen is `e`, which its panel title says. Kept on purpose: the overview's `a` and `f`, the check screen's keys, the search screen's `a`, `x` and `m`, the forms, the prompts and the log and runtime windows.

2. **The `g` menu holds every jump.** `g` opens a menu above the footer with the jumps the selection allows, each with the same letter on every screen: `t` terms, `s` search, `a` analysis, `c` commit, `d` document, `m` similar, `n` nearest, and `g` for the top.

   | From | Jumps |
   |---|---|
   | Overview, a top term | `t` its documents on the terms screen (as `⏎`), `s` a search for it |
   | Documents | `t` the field's terms, points or graph node, `s` its doc values, `a` its stored text, `c` the document's segment, `m` More Like This, `n` the nearest to its vector |
   | Viewer | `a` the shown value |
   | Terms | `s` the term, `d` the document of a posting or a graph node |
   | Commits | `d` the selected segment's documents |
   | Search | `d` the selected hit's document (as `⏎`) |

   A screen has `jumps()`, the `GoMenu.Target`s with what they do for the selection (`terms of title`), and `jump(Jump)`, the request its key had before. A letter for a jump the index lacks says `Notice.NotSupported` as the key did; any other key closes the menu. A jump goes through the screen's action, so the back stack (ADR 0052) still works. The viewer asks for the menu with an event, so its delimiter form still takes a typed `g`.

3. **The footer is grouped and short.** A screen's `keys()` is a `Keys`: groups in a fixed order, `Move · Find · Show · Sort · Do · Go to · App`, so that the same function sits in the same group everywhere, each entry in the footer or only in the key list. `KeyLegend` shows the footer entries with the keys in bold, which reads under every theme (ADR 0095), and a muted `│` between groups, breaking between entries only. The footer is what `Esc` stops while something runs, the screen's keys, `g go to` if the selection allows a jump, `⌫ back` while the back stack isn't empty, and `? keys`. Prompts and forms keep their plain legends.

4. **`?` lists the keys.** The `?` window has two tabs: Keys, the default, with every key of the shown screen by group, the jumps of its selection, the moves and the app's keys; and About, as before. `Tab` switches them, `y` copies the link on About only, and `L` opens the log over it.

## Consequences

- The documents footer takes two lines at 60 columns instead of three; the start screen's footer still fits 80 columns.
- A jump takes two keys instead of one, and the menu shows what it will do with the selection.
- A new key is a `Keys` entry in its group; whether the footer shows it is a choice of its own.
- The change is TUI-only: no protocol method, handler or runtime changes.
