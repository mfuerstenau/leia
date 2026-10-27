# leia

leia is a **read-only Lucene index inspector** for the terminal, an alternative to
[Luke](https://github.com/apache/lucene/tree/main/lucene/luke), the Swing tool that ships with
Lucene. It shows an index's fields, documents, terms, postings, points, doc values, vectors with
their HNSW graphs, and commits, searches it, explains scores, analyzes text and checks the index.
It opens indexes written by Lucene 2.1 to 10, each with the Lucene that reads it.

leia is **read-only** towards the index: it never opens an `IndexWriter`, never takes the write
lock and never changes, adds or deletes a file in the index directory. It is safe to point at a
live index.

## Requirements

- **Java 25 or newer.** Any JDK or JRE will do; leia doesn't bundle one. Started with an older
  Java, leia says which version it needs.
- **Linux or Windows on x86_64.** `leia.jar` is plain Java; macOS may work through the `leia`
  script, but isn't tested or supported.

The scripts take the Java of `-j, --java <PATH>` (a `java` executable or a JDK/JRE directory),
else `$JAVA_HOME/bin/java`, else `java` on `PATH`.

## Start

Unzip `leia-<version>.zip` anywhere. It unpacks to the folder `leia-<version>/` with `leia.jar`,
the start scripts `leia` (Linux) and `leia.cmd` (Windows), this README, `LICENSE` and `NOTICE`.

```sh
leia-<version>/leia /path/to/index          # Linux
leia-<version>\leia.cmd C:\path\to\index    # Windows
leia-<version>/leia --java /opt/jdk-25 /path/to/index
```

Without an index path, leia starts with no index open; `o` opens one. The scripts run the
`leia.jar` beside them. `leia` resolves symlinks, so a link to it in `~/bin` works; on Windows, put
the folder on `PATH`.

## Runtimes

leia reads every index through a **runtime**: a folder in `runtimes/` beside `leia.jar` with the
official Lucene jars of one Lucene version and leia's small adapter jar for it. The zip comes
without `runtimes/`, so on the first start it is empty. Install the runtimes once:

- **Interactively:** start leia and press `D` on its start screen. The window lists the runtimes
  that are missing or incomplete, with the jars each would fetch, their URLs, SHA-256s and sizes.
  Space checks a runtime, `a` all, `n` none; Enter downloads the checked ones, Esc cancels. leia
  loads them without a restart (only Lucene 10's needs one when extension jars are loaded; the
  window says so).
- **Unattended:**

  ```sh
  leia --download-runtimes --runtime lucene10 --runtime lucene9 --runtime lucene7 \
       --runtime lucene5 --runtime lucene3
  ```

  It lists the jars, then downloads them without asking. Running it again keeps the jars that
  match, replaces those that don't and removes those that don't belong, such as an older Lucene's
  after an update of leia. The exit status is 1 if a runtime failed.

**What is downloaded.** Only the official Lucene jars, from Maven Central
(`https://repo1.maven.org/maven2/`; `LEIA_OPTS=-Dleia.download.url=<URL>` names a mirror), each
checked against the SHA-256 and size that leia's build recorded. A jar that doesn't match is
refused. leia's own adapter jars come out of `leia.jar`, not from the network. All five runtimes
are about 32 MB, downloaded once. Behind a proxy, start leia with
`LEIA_OPTS=-Djava.net.useSystemProxies=true`.

Without a runtime, leia starts all the same: an index that needs it opens only without a reader
(its commits and the check), and the about screen says what is missing.

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
| Overview, documents, stored values and term vectors | ✓ | ✓ | ✓ | ✓ | ✓ |
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
| `INDEX_PATH` | the index directory to open on start, or a directory with indexes below it, which open as one |
| `-j, --java <PATH>` | the JVM: a `java` executable or a JDK/JRE directory; default `$JAVA_HOME/bin/java`, then `java` on `PATH` |
| `--jar <PATH>` | a jar with analysis components, codecs or formats of its own, or a directory of such jars; repeatable |
| `--server <PATH>` | an Elasticsearch 8 or 9 or OpenSearch 2 or 3 installation, or its `lib` folder, whose Lucene reads its indexes (see [Elasticsearch and OpenSearch](#elasticsearch-and-opensearch)); repeatable, once per Lucene major |
| `--no-default-jars` | don't load the jars of the default folder (see [Extension jars](#extension-jars)) |
| `--directory <fs\|mmap\|nio>` | the Lucene `Directory` to read through: `fs` (default) lets Lucene choose, `mmap` is `MMapDirectory`, `nio` is `NIOFSDirectory`; `Ctrl+D` in the open prompt changes it |
| `--no-reader` | open `INDEX_PATH` without a reader, for an index a reader can't open: only its commits and the check work; `Ctrl+N` in the open prompt does the same |
| `--theme <NAME>` | the colours: `dark` (default) is the terminal's own, `light` reads on a light background, `mono` has none, `nord` is the Nord palette on its own background and needs a terminal with 24-bit colour; or a theme of your own (see [Themes of your own](#themes-of-your-own)). Without it: `mono` if the environment variable `NO_COLOR` is set and not empty, else the theme chosen last with `T`, else `dark` |
| `--download-runtimes` | install the runtimes of `--runtime` without asking, then exit; takes no other option |
| `--runtime <FOLDER>` | with `--download-runtimes`, a runtime to install: `lucene10`, `lucene9`, `lucene7`, `lucene5` or `lucene3`; repeatable |
| `-V, --version` | print the version and build of leia, and the Java in use |
| `-h, --help` | print the help |

The environment variable `LEIA_OPTS` holds options for the JVM, split at spaces, such as
`LEIA_OPTS=-Xmx4g`. A wrong command line exits with 2; a Java or `leia.jar` that the script can't
find exits with 1; otherwise the exit status is Java's.

## Extension jars

leia reads the codecs and formats of `lucene-core`, `lucene-backward-codecs`, `lucene-codecs`,
`lucene-suggest` and `lucene-sandbox`, and the analysis components of `lucene-analysis-common`.
An index with a codec or format from elsewhere, or an analyzer of Lucene's unbundled analysis
modules (ICU, Kuromoji, Nori, Smartcn, Stempel, Morfologik, OpenNLP, Phonetic) or of your own,
needs a jar that has it, with its dependencies; its factories, codecs and formats are found
through `META-INF/services`, as Lucene's are. They must be built for Lucene 10.

- `--jar <PATH>` loads a jar, or the jars directly in a directory, at startup.
- The **default folder** is loaded at every start, after the jars of `--jar`:
  `~/.config/leia/jars` (`$XDG_CONFIG_HOME/leia/jars` if that is set), or `%APPDATA%\leia\jars` on
  Windows. leia doesn't create it. `--no-default-jars` leaves it out.
- `X`, on any screen, lists the loaded jars and adds more while leia runs. Jars can't be unloaded,
  and a loaded jar must stay where it is.

If a jar can't be loaded at startup, for instance because a dependency is missing, leia says why
and doesn't start. A name that is already taken is ignored, so `--jar` wins over the default folder.

## Elasticsearch and OpenSearch

Their shards use codecs and formats of their own, built for the Lucene the server ships, so no
extension jar helps. `--server` reads them with the server's own Lucene: give it the installation,
or its `lib` folder. leia loads the jars directly in `lib` with its `lucene9` runtime for
Elasticsearch 8 and OpenSearch 2 (Lucene 9), or its `lucene10` runtime for Elasticsearch 9 and
OpenSearch 3 (Lucene 10), which must be installed. The server then reads the indexes of its
Lucene's major and the one before.

```sh
leia --server /usr/share/elasticsearch /var/lib/elasticsearch/indices/<uuid>/0/index
```

A shard's index is `indices/<uuid>/<shard>/index` (`nodes/0/indices/…` for OpenSearch) in the
server's data folder. Stop the node or copy the shard first. A screen that the server's Lucene
can't do, for an older minor than leia's runtime is built for, says so, and below Lucene 9.5
(Elasticsearch 8.7) there are no KNN vectors or highlighting. OpenSearch's k-NN plugin (`plugins/opensearch-knn`) is loaded too, so its `knn_vector` shards read; other plugins' formats aren't.

## Keys

| Key | Action |
|---|---|
| `o` | open an index: `Tab` completes directories, `↑` `↓` step through the last opened paths, `Ctrl+D` switches the `Directory`, `Ctrl+N` the reader |
| `c` | close the open index |
| `R` | reopen the index, if another process committed to it since |
| `X` | list the loaded jars and add more |
| `D` | on the start screen: download the runtimes |
| `T` | on the start screen: the next theme, `dark`, `light`, `mono`, `nord`, then your own; the footer names it, and leia remembers it |
| `L` | show the log; `-`/`+` change its level, `y` copies it |
| `1` … `7` | overview, documents, terms, commits, search, analysis, check |
| `Space` `a` `l` `t` `r` | on the check screen: mark an index or a segment, mark all, change the level, change the threads, list the segments again; `⏎` checks what is marked, or everything if nothing is |
| `Backspace` | go back to where the last jump came from |
| `Esc` | stop what is running; with nothing running, quit |
| `q`, `Ctrl+C` | quit |

The footer of each screen shows its own keys.

## Files

- **Log:** `~/.local/state/leia/leia.log` (`$XDG_STATE_HOME/leia/leia.log` if that is set), or
  `%LOCALAPPDATA%\leia\leia.log` on Windows, written anew on each start. `L` shows it inside
  leia, and `-` there lowers the level at once: at FINE, every call and its result is logged.
  To start at FINE, run leia with `LEIA_OPTS=-Dleia.log.level=FINE`.
- **History:** the last 10 opened index paths, in `history` beside the log.
- **Theme:** the theme chosen last with `T`, in `~/.config/leia/theme` (`$XDG_CONFIG_HOME/leia/theme`
  if that is set), or `%APPDATA%\leia\theme` on Windows, beside the default folder of jars.

## Themes of your own

A theme is a file `<name>.theme` in `~/.config/leia/themes` (`$XDG_CONFIG_HOME/leia/themes` if
that is set), or `%APPDATA%\leia\themes` on Windows. The name is lower-case letters, digits and
`-`. leia reads the folder on start. `--theme <name>` chooses a theme, and `T` cycles through your
themes after the four of leia's own. A file named like one of those, such as `nord.theme`,
replaces it. This is `nord`:

```
# nord: the Nord palette (nordtheme.com) on its own background; needs a truecolor terminal
base          = #D8DEE9 on #2E3440
error         = #BF616A
warning       = #EBCB8B
success       = #A3BE8C
accent        = #88C0D0
jsonKey       = #8FBCBB
jsonString    = #A3BE8C
jsonNumber    = #B48EAD
jsonLiteral   = #81A1C1
match         = #2E3440 on #EBCB8B
muted         = #4C566A
border        = #4C566A
borderFocused = #88C0D0
logo          = coloured
```

A file sets every role once; a line starting with `#` is a comment. A style is `none`, the
terminal's own, or a colour, `on` a background colour, and modifiers, each part optional. A colour
is a name (`black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `gray`,
`dark-gray`, `light-red`, `light-green`, `light-yellow`, `light-blue`, `light-magenta`,
`light-cyan`, `bright-white`), a number from 0 to 255 of the 256-colour palette, or `#RRGGBB`,
which needs a terminal with 24-bit colour. The modifiers are `bold`, `dim`, `italic`, `underlined`
and `reversed`. `logo` is `coloured` or `plain`. A file leia can't read is left out, and the log
says which line is wrong and why.

## License and disclaimer

leia is licensed under the Apache License, Version 2.0: see `LICENSE`. `NOTICE` lists the Apache
Lucene code it adapts and the third-party software in `leia.jar`. The Lucene jars that leia
downloads into `runtimes/` carry their own licenses.

leia stands for **L**uke **E**volved **I**ndex **A**nalysis. leia is in no way affiliated with,
endorsed by or connected to Disney, Lucasfilm or the Star Wars franchise.
