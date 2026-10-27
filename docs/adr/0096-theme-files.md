# ADR 0096 — Theme files

- **Status:** Accepted, amended by ADR 0097 (the logo's roles)
- **Date:** 2026-10-22
- **Amends:** ADR 0095 §2, §5 and its alternatives "user-defined themes" and "the theme's name in the footer"

## Context

ADR 0095 made the four themes Java constants in `Palette`, an enum `Theme`, and left user-defined themes out. Two things followed from that. A user can't tune a theme to their terminal without building leia. And `T` doesn't say which theme it switched to: the footer has no room for the name on 80 columns, so only the colours tell.

## Decision

1. **A theme is a file.** `<id>.theme`, UTF-8, one `role = value` per line. Blank lines and lines starting with `#` are skipped. A `#` elsewhere is no comment, so that `#RRGGBB` needs no quoting. The roles are `Palette`'s: `base`, `error`, `warning`, `success`, `accent`, `jsonKey`, `jsonString`, `jsonNumber`, `jsonLiteral`, `match`, `muted`, `border`, `borderFocused`, and `logo = coloured | plain`.
2. **A style** is `none` (the terminal's own), or `[colour] [on colour] [modifier…]`. A colour is a name, a 256-colour index (`0`–`255`), or `#RRGGBB`. Names are TamboUI's, in kebab case and without regard to case: `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `gray`, `dark-gray`, `light-red` … `light-cyan`, `bright-white`. The modifiers are `bold`, `dim`, `italic`, `underlined` and `reversed`.
3. **Every role, once.** A file that misses a role, sets one twice, or has an unknown role, colour or modifier is refused. `ThemeParser` gives the file, the line and the reason. There's no inheriting from another theme: a user starts from a copy of a bundled file.
4. **The bundled themes are resources.** `dark`, `light`, `mono` and `nord` are `.theme` files beside `…tui.theme.Themes` in `leia.jar`, with the colours of ADR 0095 §2. A broken bundled file is a bug of the build: it throws, and `ThemesTest` parses each one and checks it against the palettes of ADR 0095.
5. **The user's themes** are the `*.theme` files of `$XDG_CONFIG_HOME/leia/themes` (`~/.config/leia/themes`), or `%APPDATA%\leia\themes` on Windows. That is the config folder, beside the default folder of jars and the theme file, not `~/.local/share`: they are settings that a user writes by hand. An id is lower-case letters, digits and `-`, starting with a letter or digit. `Themes.withUserDir` adds them after the bundled themes, in the order of their names. A theme with the id of a bundled one replaces it in its place, and the log says so. A broken file, a bad name or an unreadable folder goes to the log as a WARNING and is left out. A broken theme never stops leia.
6. **Choosing** (amends ADR 0095 §5). `--theme` takes any name. `Cli` can't check it, since the user's themes are known only once their folder is read. `Main` reads the folder after the log is open, so that a broken file goes to the log, and before the backend starts. A name that isn't among the themes exits 2 and lists them. `NO_COLOR` chooses the theme named `mono`, which may be the user's. A theme file naming a theme that's gone, such as a deleted user theme, is no theme, and leia starts with `dark`. `T` cycles all themes, bundled first.
7. **`T` names the theme.** The footer shows `Theme: <id>` in place of the legend (`Notice.ThemeChanged`) until the next key, as an export's outcome does. The legend itself still says `T theme`.

## Alternatives considered

- **`java.util.Properties`.** It parses the same lines, but it can't tell which line is wrong, it lets a key be set twice, and it treats `#` and `!` as comments wherever they start a line, which would also hold for `!` in a later value syntax. A dozen lines of parser give the line numbers.
- **TOML or JSON.** These are more than the format needs. TOML would bring a library into `leia.jar`. Jackson is there, but a JSON file needs quotes around every colour.
- **`extends = dark`.** It gives shorter files, but there is more to explain, to test, and to go wrong (cycles, a base that's gone). Left out. A copy of a bundled file is 15 lines.
- **Refuse a user theme with a bundled name.** The bundled names would always mean the same colours, but tuning `nord` would then need a new name. Rejected: the log says what replaced what.
- **`~/.local/share/leia/themes`.** XDG's data folder is for what a program writes, and the user writes themes. Rejected.
