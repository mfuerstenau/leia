# leia

leia is a **read-only Lucene index inspector** for the terminal, an alternative to
[Luke](https://github.com/apache/lucene/tree/main/lucene/luke), the Swing tool that ships with
Lucene.

- **Shows** an index's fields, documents, terms, postings, points, doc values, vectors with their
  HNSW graphs, and commits; searches it, explains scores, analyzes text and checks the index.
- **Read-only:** never opens an `IndexWriter`, never takes the write lock, never changes, adds or
  deletes a file in the index directory. Safe to point at a live index.
- **Lucene 2.1 to 10:** each index is read by the Lucene that can read it (see [Runtimes](#runtimes)).

## Requirements

- **Java 25 or newer**, any JDK or JRE; leia bundles none. On an older Java, leia says what it needs.
- **Linux or Windows on x86_64.** macOS may work through the `leia` script, but isn't supported.

## Start

1. Unzip `leia-<version>.zip` anywhere. It holds `leia.jar`, the scripts `leia` (Linux) and
   `leia.cmd` (Windows), this README, `LICENSE` and `NOTICE`.
2. Run it:

   ```sh
   leia-<version>/leia /path/to/index          # Linux
   leia-<version>\leia.cmd C:\path\to\index    # Windows
   leia-<version>/leia --java /opt/jdk-25 /path/to/index
   ```

3. On the first start, install the runtimes (see [Runtimes](#runtimes)).

- Without an index path, leia starts with no index open; `o` opens one.
- The scripts take the Java of `-j, --java` (a `java` executable or a JDK/JRE directory), else
  `$JAVA_HOME/bin/java`, else `java` on `PATH`.
- `leia` resolves symlinks, so a link to it in `~/bin` works; on Windows, put the folder on `PATH`.

## Runtimes

leia reads every index through a **runtime**: a folder in `runtimes/` with the official jars of one
Lucene and leia's small adapter jar for it. The zip has none, so install them once:

1. **Interactively:** press `D` on the start screen. The window lists the missing and incomplete
   runtimes with the jars each would fetch (URLs, SHA-256s, sizes). `Space` checks one, `a` all,
   `n` none; `Enter` downloads, `Esc` cancels. leia loads them without a restart, except Lucene
   10's when extension jars are loaded (the window says so).
2. **Unattended:**

   ```sh
   leia --download-runtimes --runtime lucene10 --runtime lucene9 --runtime lucene7 \
        --runtime lucene5 --runtime lucene3
   ```

   Running it again keeps matching jars, replaces the others and removes those that don't belong.
   The exit status is 1 if a runtime failed.

- **Where:** `runtimes/` in your data folder, `~/.local/share/leia/runtimes` or
  `%LOCALAPPDATA%\leia\runtimes`, so a new leia finds what an older one downloaded. A `runtimes/`
  beside `leia.jar` (a portable install) comes first, system-wide folders last; downloads go to the
  first of yours that can be written (see [Folders](#folders)). The about screen (`?`) says where
  each runtime came from.
- **What is downloaded:** only the official Lucene jars, from Maven Central
  (`LEIA_OPTS=-Dleia.download.url=<URL>` names a mirror), each checked against the SHA-256 and
  size leia's build recorded. leia's adapter jars come out of `leia.jar`. About 32 MB in all.
- **Behind a proxy:** `LEIA_OPTS=-Djava.net.useSystemProxies=true`.
- **After an update of leia:** at each start, leia rewrites its adapter jars in `runtimes/`. A
  runtime that needs a new Lucene shows as incomplete, and `D` downloads it. If leia can't write a
  jar (a read-only folder, or another running leia holds it on Windows), the runtime shows as
  *outdated*: fix that and start again.
- **Without a runtime**, leia starts all the same: an index that needs it opens only without a
  reader (its commits and the check), and the about screen says what is missing.

## What each runtime can do

| Runtime | Folder | Reads indexes written by Lucene |
|---|---|---|
| 3.6.2 | `runtimes/lucene3` | 2.1 – 3.6 |
| 5.5.5 | `runtimes/lucene5` | 4.x, 5.x |
| 7.7.3 | `runtimes/lucene7` | 6.x, 7.x |
| 9.12.3 | `runtimes/lucene9` | 8.x, also once 9.x wrote to them |
| 10.5.2 | `runtimes/lucene10` | 9.x, 10.x, and an unknown version |

The newest commit of an index decides which runtime opens it. What leia offers on it depends on
that runtime; a screen or option the runtime lacks is greyed out:

| Can | 3.6.2 | 5.5.5 | 7.7.3 | 9.12.3 | 10.5.2 |
|---|---|---|---|---|---|
| Overview, documents, stored values and fields, term vectors | ✓ | ✓ | ✓ | ✓ | ✓ |
| Terms, postings and positions, export terms | ✓ | ✓ | ✓ | ✓ | ✓ |
| Commits and check index | ✓ | ✓ | ✓ | ✓ | ✓ |
| Check levels | – | ✓ | ✓ | ✓ | ✓ |
| Check threads | – | – | – | ✓ | ✓ |
| Points | – | – | ✓² | ✓² | ✓² |
| Doc values | – | ✓² | ✓² | ✓² | ✓² |
| KNN vectors, KNN search and HNSW graphs | – | – | – | ✓²³ | ✓² |
| Classic and term queries, explain, regex over stored values, auto stop words | ✓ | ✓ | ✓ | ✓ | ✓ |
| Filter documents by a query | ✓¹ | ✓ | ✓ | ✓ | ✓ |
| Standard query parser | – | ✓ | ✓ | ✓ | ✓ |
| More-Like-This | – | ✓ | ✓ | ✓ | ✓ |
| Doc values queries | – | ✓ | ✓ | ✓ | ✓ |
| Similarity and sort | – | ✓ | ✓ | ✓ | ✓ |
| Highlighting | – | – | – | ✓ | ✓ |

1. A query filter can't include deleted documents; a pattern filter can.
2. Only if a field of the index has points, doc values or vectors.
3. Only in the segments that Lucene 9.x wrote: Lucene 8.x segments have no vectors.

The analysis tab and extension jars work with the Lucene 10 runtime, whatever index is open.
The details, and where the runtimes differ, are in
[`docs/runtimes.md`](https://github.com/mfuerstenau/leia/blob/main/docs/runtimes.md).

## Options

```
leia [OPTIONS] [INDEX_PATH]
```

| Option | Meaning |
|---|---|
| `INDEX_PATH` | the index to open, or a directory with indexes below it, which open as one |
| `-j, --java <PATH>` | the JVM: a `java` executable or a JDK/JRE directory; default `$JAVA_HOME/bin/java`, then `java` on `PATH` |
| `--jar <PATH>` | a jar, or a directory of jars, with analysis components, codecs or formats; repeatable (see [Extension jars](#extension-jars)) |
| `--server <PATH>` | an Elasticsearch 8/9 or OpenSearch 2/3 installation whose Lucene reads its indexes; repeatable (see [Elasticsearch and OpenSearch](#elasticsearch-and-opensearch)) |
| `--no-default-jars` | don't load the jars of the default folder |
| `--directory <fs\|mmap\|nio>` | the Lucene `Directory`: `fs` (default) lets Lucene choose; `Ctrl+D` in the open prompt changes it |
| `--no-reader` | open without a reader, for an index a reader can't open: only commits and the check work; `Ctrl+N` in the open prompt |
| `--theme <NAME>` | the colour theme: `dark` (default), `light`, `mono`, `nord`, `solarized-dark`, `solarized-light` (the last three need 24-bit colour), or your own (see [Themes of your own](#themes-of-your-own)); without it, `mono` if `NO_COLOR` is set, else the last choice of `T` |
| `--mcp` | serve `INDEX_PATH` to an MCP client instead of starting the UI (see [MCP mode](#mcp-mode)) |
| `--mcp-root <PATH>` | with `--mcp`, a folder below which alone the agent opens indexes; repeatable |
| `--mcp-pin` | with `--mcp` and `INDEX_PATH`, serve only that index |
| `--download-runtimes` | install the runtimes of `--runtime` without asking, then exit |
| `--runtime <FOLDER>` | with `--download-runtimes`: `lucene10`, `lucene9`, `lucene7`, `lucene5` or `lucene3`; repeatable |
| `-V, --version` | print the version and build of leia, and the Java in use |
| `-h, --help` | print the help |

- `LEIA_OPTS` holds JVM options, split at spaces, such as `LEIA_OPTS=-Xmx4g`.
- Exit status: 2 for a wrong command line, 1 if the script can't find Java or `leia.jar`, else Java's.

## Extension jars

- leia reads the codecs and formats of `lucene-core`, `-backward-codecs`, `-codecs`, `-suggest`
  and `-sandbox`, and the analysis of `lucene-analysis-common`.
- Anything else needs a jar with its dependencies: another codec or format, Lucene's unbundled
  analysis modules (ICU, Kuromoji, Nori, Smartcn, Stempel, Morfologik, OpenNLP, Phonetic), or your
  own. They are found through `META-INF/services` and must be built for Lucene 10.
- `--jar <PATH>` loads a jar, or the jars directly in a directory, at startup.
- The default folder `~/.config/leia/jars` (`%APPDATA%\leia\jars`) is loaded after them;
  `--no-default-jars` skips it. A name already taken is ignored, so `--jar` wins.
- `X`, on any screen, lists the loaded jars and adds more. Jars can't be unloaded and must stay
  where they are.
- A jar that can't be loaded at startup stops leia, with the reason.

## Elasticsearch and OpenSearch

Their shards use codecs and formats of their own, so no extension jar helps. `--server` reads them
with the server's own Lucene, from the jars directly in its `lib/`:

```sh
leia --server /usr/share/elasticsearch /var/lib/elasticsearch/indices/<uuid>/0/index
```

- Elasticsearch 8 and OpenSearch 2 (Lucene 9) need the `lucene9` runtime; Elasticsearch 9 and
  OpenSearch 3 (Lucene 10) the `lucene10` one.
- The server then reads the indexes of its Lucene's major and the one before.
- A shard is `indices/<uuid>/<shard>/index` (`nodes/0/indices/…` for OpenSearch) in the server's
  data folder. Stop the node or copy the shard first.
- On an older minor than leia's runtime is built for, a screen the server can't do says so; below
  Lucene 9.5 (Elasticsearch 8.7) there are no KNN vectors or highlighting.
- OpenSearch's k-NN plugin is loaded too, so its `knn_vector` shards read; other plugins' formats aren't.

## MCP mode

`leia --mcp [INDEX_PATH]` serves an index to an MCP client, such as a coding agent, on stdin and
stdout instead of starting the UI. It reads only, like the UI, and opens every index the UI opens.
For Claude Code:

```sh
claude mcp add leia -- /path/to/leia --mcp /path/to/index
```

- `--scope user` adds it in every project, `--scope project` writes the project's `.mcp.json`;
  `claude mcp remove leia` removes it.
- For one session: `claude --mcp-config leia-mcp.json` (add `--strict-mcp-config` to leave out the
  other servers), with:

  ```json
  {"mcpServers": {"leia": {"command": "/path/to/leia", "args": ["--mcp", "/path/to/index"]}}}
  ```

- Other clients take the same command and arguments.

| Tool | What it does |
|---|---|
| `open_index` | opens an index in place of the open one |
| `index_info` | the runtime, document counts, commit user data, the fields and how each is indexed |
| `check_index` | `CheckIndex`, read-only: which segments are broken and why |
| `search` | a page of hits for a query, an exact term, doc values or a vector |
| `explain` | how a document scores for a query, or why it doesn't match |
| `scan_stored` | stored values a regex matches, also in fields not indexed, such as `_source` |
| `stored_fields` | the fields the documents store, with how many store each |
| `analyze` | the tokens an analyzer makes of a text; needs no open index |
| `terms` | a field's terms a page at a time, from a prefix, or the most frequent |
| `postings` | the documents that hold an exact term, with freqs and, on request, positions |
| `get_document` | a document's stored values (cut when long), doc values, norms and vectors |
| `get_field_value` | one stored value whole, in slices |
| `reconstruct_fields` | the terms and points a document was indexed with, also in fields not stored |

- `--jar`, `--server`, `--no-default-jars` and `--directory` work as for the UI.
- An `INDEX_PATH` that can't be opened: the reason on stderr, exit 1.
- Limits: 100 hits a page, 2,048 characters a value unless asked for more, 5 minutes a search or rebuild.

### Which indexes the agent can open

Without more options, any index your account can read:

```sh
# only indexes below these folders; any other path is refused alike, also through a symbolic link
claude mcp add leia -- /path/to/leia --mcp --mcp-root ~/indexes --mcp-root /srv/search
# only this one index: the agent gets no open_index
claude mcp add leia -- /path/to/leia --mcp --mcp-pin /path/to/index
```

- `INDEX_PATH` opens wherever it is, since you named it.
- Everything in an opened index goes to the agent, and on to its other tools. Mind which network
  tools share the session with private data.
- A document's text is data, not instructions, but can be written to look like them.
- Name only jars and servers you trust; `--no-default-jars` keeps the default folder out.
- leia runs as you. To fence it in, use another user, or a container with the indexes mounted read-only.

## Keys

| Key | Action |
|---|---|
| `o` | open an index: `Tab` completes (see below), `↑` `↓` the last opened paths, `Ctrl+D` the `Directory`, `Ctrl+N` the reader |
| `c` | close the open index |
| `R` | reopen the index, if another process committed to it since |
| `X` | list the loaded jars and add more; `Tab` completes directories and jars |
| `D` | on the start screen: download the runtimes |
| `T` | on the start screen: the next theme, remembered |
| `L` | show the log; `-`/`+` change its level, `y` copies it |
| `?` | **every key of the shown screen**, by group; `Tab` switches to About: version, the Java and runtimes, the open index, the licence |
| `1` … `7` | overview, documents, terms, commits, search, analysis, check |
| `Backspace` | go back to where the last jump came from |
| `Esc` | stop what is running; with nothing running, quit |
| `q`, `Ctrl+C` | quit |

`Tab` in a path input lists several matches below it: `↑` `↓` `PgUp` `PgDn` choose, `Enter` or
`Tab` take one, typing narrows, `Esc` closes. The footer of each screen shows its common keys and
ends in `? keys`. Every screen gives these keys one meaning:

| Key | Meaning |
|---|---|
| `↑` `↓` `j` `k` `PgUp` `PgDn` `Home` `End` `G` | move; `g g` goes to the top |
| `Tab` `Shift+Tab` | next and previous panel |
| `←` `→` | the screen's step: the document, the column, the value |
| `/` | the screen's input: seek a field or a term, or type the query or the text |
| `Enter` | open or drill into the selection |
| `s` `S` | sort: the next column, the other direction. On the search screen, `s` sets the similarity and the sort, and `S` reverses the hits' order |
| `g` | go to another screen with the selection: `t` terms, `s` search, `a` analysis, `c` commit, `d` document, `m` similar documents, `n` nearest vectors, `g` the top. The menu lists the jumps the selection allows |
| `b` `B` | browse the documents through a filter, or clear it |
| `v` `y` `E` `r` | view a value, copy, export, again (read, list or analyze again) |
| `n` `t` `h` `f` `F` | the number format, the point format, hex, the field |

## Folders

None has to exist; leia creates nothing until it writes.

| | What's in it | Linux and other Unix: yours | Linux: system-wide | Windows: yours | Windows: system-wide |
|---|---|---|---|---|---|
| **config** | `theme`, `themes/`, `jars/` | `$XDG_CONFIG_HOME/leia`, then `~/.config/leia` | each entry of `$XDG_CONFIG_DIRS` plus `/leia` (default `/etc/xdg/leia`) | `%APPDATA%\leia` | `%PROGRAMDATA%\leia` |
| **data** | `runtimes/` | `$XDG_DATA_HOME/leia`, then `~/.local/share/leia` | each entry of `$XDG_DATA_DIRS` plus `/leia` (default `/usr/local/share/leia`, `/usr/share/leia`) | `%LOCALAPPDATA%\leia` | `%PROGRAMDATA%\leia` |
| **state** | `leia.log`, `history` | `$XDG_STATE_HOME/leia`, then `~/.local/state/leia` | none | `%LOCALAPPDATA%\leia` | none |
| **cache** | `stored-fields/` | `$XDG_CACHE_HOME/leia`, then `~/.cache/leia` | none | `%LOCALAPPDATA%\leia\cache` | none |

- **Read** from the first candidate that has the item, yours before the system-wide ones.
- **Write** to the first of yours that can be written or created; never the temporary folder.
- An `XDG_*` variable that is unset, empty or relative is skipped. On Windows, an unset variable
  falls back to `AppData\Roaming` (config) or `AppData\Local` in your home folder.
- `leia.log`: written anew on each start; `L` shows it. `LEIA_OPTS=-Dleia.log.level=FINE` logs
  every call and its result from the start.
- `history`: the last 10 opened index paths.
- `stored-fields/`: which fields the segments you scanned with `f` store (names and document
  numbers, never values); at most 512 MB, a file dropped after 30 days unused.
  `-Dleia.cache.dir=<path>` moves it, an empty path turns it off.
- `theme`: the last choice of `T`; read from system-wide folders too, so an administrator can set a default.
- `themes/` merge: leia's, then the system-wide, then yours.
- `jars/` comes from your folders only, never a system-wide one.
- `runtimes/`: one beside `leia.jar` comes first. System-wide jars are checked by SHA-256 and
  size and never rewritten.
- `-Dleia.runtimes=<dir>` replaces its list, with no fallback.

## Themes of your own

A theme is `<name>.theme` (lower-case letters, digits, `-`) in `themes/` of a config folder, such
as `~/.config/leia/themes` or `%APPDATA%\leia\themes` (see [Folders](#folders)). leia reads them on
start; `T` cycles through them after its own six, and a file named like one of those replaces it.

1. Make the folder: `mkdir -p ~/.config/leia/themes` (on Windows, `%APPDATA%\leia\themes`).
2. Save this as `harbor.theme` in it. It uses 256-colour numbers, so it needs no 24-bit colour:

```
# harbor: blues and greens on the terminal's own background; needs 256 colours
base          = none
error         = 203
warning       = 214
success       = 114
accent        = 39
jsonKey       = 74
jsonString    = 114
jsonNumber    = 179
jsonLiteral   = 140
match         = black on 214
muted         = 244
border        = 240
borderFocused = 39
heading       = 39 bold
label         = 74
selection     = black on 39
selectionUnfocused = 39 bold
secondary     = 244 italic
logo          = gazelle
```

3. Run `leia --theme harbor`, or press `T` on the start screen until the footer says `harbor`.

| Slot | What it styles |
|---|---|
| `base` | the whole screen, painted first; `none` keeps the terminal's own colours |
| `error`, `warning`, `success` | what failed, what needs attention, what went well |
| `accent` | what stands out without a meaning of its own: segment names, field names, formats |
| `jsonKey`, `jsonString`, `jsonNumber`, `jsonLiteral` | the parts of the viewer's JSON |
| `match` | a highlighted match in a text |
| `muted` | what is there but out of the way |
| `border`, `borderFocused` | a panel's border, and the focused panel's |
| `heading` | a section title, a table's header row |
| `label` | a key or a label, in front of its value |
| `selection`, `selectionUnfocused` | the selected row in the focused table, and in one without focus |
| `secondary` | what comes second of its kind, such as a stacked token |
| `logo` | the start screen's logo, an icon (not a style) |

- Every slot is set once; `#` starts a comment. A theme without the five roles `heading`, `label`,
  `selection`, `selectionUnfocused` and `secondary` is left out and named on the start screen.
- A style is `none` (the terminal's own), or a colour, `on` a background colour, and modifiers,
  each part optional.
- A colour is a name (`black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`,
  `gray`, `dark-gray`, the `light-` ones, `bright-white`), 0–255, or `#RRGGBB` (24-bit colour).
- Modifiers: `bold`, `dim`, `italic`, `underlined`, `reversed`.
- leia's logos: `gazelle` (`dark`, `light`), `gazelle-aurora` (`nord`), `gazelle-solarized-dark`,
  `gazelle-solarized-light` and `gazelle-mono`.
- A file leia can't read is left out; the log says which line and why.

### Icons of your own

- An icon is two files in the themes folder: `<name>.chars`, a row of characters per line, and
  `<name>.colours`, a row of cells per line, one per character, separated by spaces.
- A cell is `foreground/background`, a `foreground` alone, or `-`. Colours are written as in a
  theme; `-` leaves what is beneath.
- A first line `chars = <other>` colours the characters of `<other>.chars`, yours or leia's. So
  `gazelle-sepia.colours` alone recolours leia's gazelle (30 rows of 38 cells):

  ```
  chars = gazelle
  - - - - - - - - - - - - - - - - - - - #704214 - - - - - - - - - - - - - - - #704214 - -
  …
  ```

- `logo = gazelle-sepia` in a theme then draws it.
- A row of characters may be shorter than its cells; the rest are spaces.
- An icon leia can't read leaves out the theme that names it; the log says why.

## License and disclaimer

leia is licensed under the Apache License, Version 2.0: see `LICENSE`. `NOTICE` lists the Apache
Lucene code it adapts and the third-party software in `leia.jar`. The Lucene jars that leia
downloads into `runtimes/` carry their own licenses.

leia stands for **L**uke **E**volved **I**ndex **A**nalysis. leia is in no way affiliated with,
endorsed by or connected to Disney, Lucasfilm or the Star Wars franchise.
