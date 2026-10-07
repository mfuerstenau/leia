# leia

A **read-only Lucene index inspector** for the terminal, as an alternative to
[Luke](https://github.com/apache/lucene/tree/main/lucene/luke), the Swing tool that ships with Lucene.

leia never opens an `IndexWriter`, never takes the write lock and never changes a file in the index
directory, so it is safe to point at a live index.

It opens indexes written by Lucene 10 and Lucene 9. Indexes from Lucene 8 and older are rejected,
because Lucene 10 can't read them.

## What it shows

- **Overview:** path, directory implementation, commit point (`segments_N`, generation, version),
  segment count, live, deleted and max documents, the Lucene versions that created the index, wrote
  its commit and wrote its oldest segment, and the commit user data.
- **Fields:** every field with its index options, norms, payloads, term vectors, doc values type,
  point and vector dimensions, and summed postings statistics (doc count, Σ doc freq, Σ term freq),
  plus on request each field's term count and top terms.
- **Documents:** one document at a time, deleted ones included: norms with the field length they
  encode, stored values, doc values and term vectors, and a viewer for whole values as text, hex, pretty-printed JSON or delimited data.
- **Terms:** the terms of a field with their frequencies, and the documents that contain a term,
  with the positions, offsets and payloads of each occurrence.
- **Commits:** every commit in the index directory with its segments, and for each segment its
  documents, deletions, versions, codec and codec formats, diagnostics, attributes and files with
  their sizes.
- **Search:** a query in Lucene's classic syntax or a single term, the hits with their scores and
  stored fields, exact or approximate hit counts, and an explanation of how a hit scores.

Analysis, more search options and the rest of Luke's read-only features are still to come (see
[`docs/tasklist.md`](docs/tasklist.md)).

## Requirements

- **Java 25 or newer** to run `leia-server.jar`, which does all the Lucene work. Any JRE will do.
- Linux or Windows on x86_64 for the prebuilt TUI. There is no macOS build; on macOS, build the
  TUI from source (see below). leia doesn't bundle a Java runtime.

## Usage

```
leia [OPTIONS] [INDEX_PATH]
```

| Option | Meaning |
|---|---|
| `INDEX_PATH` | the index directory to open on start; without one, leia starts with no index open |
| `-j, --java <PATH>` | the JVM, given as a `java` executable or a JDK/JRE directory; default: `$JAVA_HOME/bin/java`, then `java` on `PATH` |
| `-V, --version` | print the versions of leia, leia-server and the Java in use |

`leia-server.jar` has to be next to the `leia` executable. leia resolves symlinks, so a link to
`leia` in `~/bin` works.

```sh
leia --java /opt/jdk-25 /var/lib/solr/products/data/index
```

### Keys

| Key | Action |
|---|---|
| `o` | open an index: type a path, `Tab` completes directories, `~` is your home directory, `Enter` opens and `Esc` cancels |
| `c` | close the open index |
| `1` … `5` | switch between the overview, the documents, the terms, the commits and the search |
| `Esc` | stop the running term scans, or on the search screen the running search; with none running, quit |
| `q`, `Ctrl+C` | quit |

#### Overview

| Key | Action |
|---|---|
| `Enter` | count the terms of the selected field and find its top N terms by document frequency (N is 50 at first) |
| `a` | do so for every field with postings, one after another |
| `+` `-` | show more or fewer top terms: 10, 20, 50, 100, 200, 500 or 1000 |
| `s` | sort the field table by number, name or term count |
| `S` | flip the sort direction |
| `Tab` | switch between the field table and the top terms |
| `Enter` on a top term | show the term and its documents on the terms screen |
| `/` on a top term | search for the documents that contain it |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the field table or the top terms |

Counting terms reads every term of the field, which can take a while on a large index. The scan
runs in the server while leia shows its progress, and `Esc` stops it. Once every field has been
counted, the field table's title shows the total and the `%` column each field's share of it.
Like Lucene's own statistics, the counts include deleted documents that no merge has removed yet.

Sorted by term count, the fields without a count come last in either direction. With a larger N,
the top-terms title shows `⏎ top N` for a field that was scanned for fewer, and `Enter` (or `a`)
scans it again; a smaller N needs no new scan.

#### Documents

| Key | Action |
|---|---|
| `←` `→` / `p` `n` | previous or next document id |
| `i` | go to a document id |
| `[` `]` | previous or next document of the term opened on the terms screen |
| `Enter` | load the term vector of the selected field |
| `v` | view the selected field's values (see below) |
| `x` | show numeric doc values as long, double bits, sortable double, float bits or sortable float |
| `e` | hide or show the fields in which the document has nothing |
| `Tab` | switch between the field table and the details of the selected field |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the fields or scroll the details |

The documents screen shows one document at a time, every field of the index in a row: its flags,
its norm and its first stored value (or else its doc values). The details panel shows all of the
selected field's stored values, doc values and, on `Enter`, its term vector with positions,
offsets and payloads. Deleted documents can be read too, until a merge removes them; they are
marked `deleted`. Byte strings that aren't UTF-8 are shown as `0x` and their hex.

The norm is shown with the field length it encodes, such as `88 → length 1048–1175`: all of
Lucene's similarities store the number of terms in one byte (`SmallFloat.intToByte4`), exactly up to
39 and as a range above. A norm that another similarity wrote may mean something else.

Stored values longer than 64 KiB come cut: the details panel shows their beginning, and the
viewer fetches them whole.

Flags: `I` indexed, `N` has a norm, `P` payloads, `S` stored, `V` term vector, `D` doc values,
`T` points, `K` KNN vectors. `N`, `S`, `V` and `D` say what this document has; the others describe
the field. Whether the document has terms in a field is not shown: finding out means reading the
field's terms.

#### Viewer

`v` shows the selected field's stored values and doc values one at a time over the whole screen.

| Key | Action |
|---|---|
| `←` `→` / `p` `n` | previous or next value |
| `t` | show the text |
| `h` | switch between the text and a hex dump |
| `J` | switch between the text and pretty-printed JSON |
| `d` | split the value at a delimiter, one item per line (asks for the delimiters) |
| `y` | copy the value as shown (the text, the bytes in hex, the pretty-printed JSON or the items) to the clipboard |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn` / `Space`, `Home` `End` / `g` `G` | scroll |
| `Esc` / `q` / `v` | close the viewer |

Bytes that aren't UTF-8 open as hex, and JSON objects and arrays open pretty-printed; numbers show
the big-endian bytes of their Java type. The JSON mode keeps the value as written: keys in their
order, numbers and strings with their text. A value that isn't JSON is shown as text, with the
parse error below it.

The delimited mode asks for two delimiters: the one between items, and optionally one between an
item's key and its value. Without a key/value delimiter, items are numbered; with one, keys are
aligned. `\t`, `\n`, `\r`, `\0`, `\\`, `\xHH` (up to `7f`) and `\u{H…}` stand for characters, so
`\u{1f}` splits at the unit separator. The delimiters are kept for the next value you view.

Copying uses the OSC 52 escape sequence, which works over SSH. The terminal has to allow it: in
tmux, set `set -g set-clipboard on`. Terminals may drop very large copies without a word; tmux
takes about 1 MiB.

#### Terms

| Key | Action |
|---|---|
| `←` `→` / `h` `l`, `Tab` | switch between the fields, their terms and the postings of the selected term |
| `/` | seek: type a text, and the terms start from the first one that is not smaller; `Enter` or `Esc` ends it, and an empty text goes back to the first term |
| `Enter` | go to the next column; on a posting, show its document on the documents screen |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the focused column |

The terms screen lists the fields that have postings, the terms of the selected field in term
order with their document frequency and total term frequency, and the documents that contain the
selected term with the term's frequency in each. Terms and postings are fetched 100 at a time,
the next ones once the selection reaches the last row. Lucene can't step back through terms, so
to see the terms before the first one shown, seek again. Like the statistics, the frequencies
include deleted documents, and the postings mark them `deleted`.

A document opened from a posting shows the term in the header, and the details panel of the
term's field shows where the term occurs: its positions, offsets and payloads, as far as they
were indexed. `[` and `]` then step through the term's documents.

#### Commits

| Key | Action |
|---|---|
| `Tab`, `Shift+Tab` | switch between the commits, the segments of the selected commit and the details |
| `Enter` | on a commit, go to its segments |
| `r` | read the commits from the directory again |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the focused list, or scroll the details |

The commits screen lists the commits of the index directory, newest first; `●` marks the one leia
opened. A directory keeps older commits only if the writer's deletion policy kept them, and it
can hold commits written after leia opened the index: `r` reads them again. Below are the
segments of the selected commit in document id order, and the details of the commit (its user
data and all its files) or of the selected segment (document range, deletions, versions,
generations, diagnostics, attributes, codec formats and its files). A file that can't be read
shows as missing. The documents and terms screens always show the opened commit.

#### Search

| Key | Action |
|---|---|
| `/` | edit the query; `Enter` searches, `Esc` leaves the line, and `←` `→`, `Ctrl+A` `E` `U` `K` `W` edit |
| `m` | switch between classic query syntax and a single term |
| `f` `F` | step through the fields with postings: the default field of a classic query, or the field of a term |
| `a` | combine clauses without an operator with OR or AND |
| `t` | count every hit, or stop counting after 1000 |
| `x` | explain how the selected hit scores |
| `Enter` | show the selected hit's document on the documents screen |
| `Tab` | switch between the hits and the details |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the hits, or scroll the details |

A classic query is parsed with Lucene's classic `QueryParser` and the `StandardAnalyzer`, for
example `title:fox AND body:qu*`, `"quick fox"~2` or `fox~`. A term query searches the field for
the line exactly as typed, `:` and spaces included, without analysis. A changed setting searches
again. The hits come 100 at a time, the next ones once the selection reaches the last row, up to
the first 10,000. Each hit shows its stored fields, with long values cut. The details panel shows
the parsed and the rewritten query, or after `x` the explanation of the selected hit as Lucene
computes it; parts that don't match are dimmed. Searches and explanations run in the server, so
`Esc` stops a slow one, such as a fuzzy query on a large index. A numeric range in classic syntax
doesn't match point fields yet.

If an index can't be opened, the prompt shows why (not found, not an index, unsupported format,
corrupt, not readable) and keeps the path, so you can fix it. The index that was open before
stays open.

## Building

| Part | Needs |
|---|---|
| server (`leia-core`, `leia-protocol`, `leia-server`) | a Java 17+ runtime to start the Gradle wrapper; Gradle downloads the Java 25 toolchain itself |
| TUI (`tui/`) | a stable Rust toolchain via rustup (`tui/rust-toolchain.toml`) |
| Windows cross-build | [`cargo-zigbuild`](https://github.com/rust-cross/cargo-zigbuild) and [zig](https://ziglang.org) |

```sh
./gradlew build                      # compile, test, check formatting
./gradlew :leia-server:shadowJar     # → leia-server/build/libs/leia-server.jar

cd tui
cargo build --release                # → tui/target/release/leia
```

To run a development build, point leia at the jar:

```sh
LEIA_SERVER_JAR=$PWD/leia-server/build/libs/leia-server.jar cargo run --manifest-path tui/Cargo.toml -- <index>
```

For an index to try things on, `./gradlew :leia-core:demoIndex` writes a demo index with a field for
nearly everything leia shows to `leia-core/build/test-indexes/demo`; see
[docs/demo-index.md](docs/demo-index.md).

### Release folders

```sh
./build-dist.sh
```

This builds the jar and the TUI for Linux and Windows (x86_64) into
`dist/leia-<version>-linux-x86_64/` and `dist/leia-<version>-windows-x86_64/`. Each folder holds the
executable and `leia-server.jar`, and can be copied anywhere.

## How it works

```
leia (Rust TUI)  ──spawns──▶  java -jar leia-server.jar
   child stdin   ── JSON-RPC 2.0 request,  one per line ──▶
   child stdout  ◀── JSON-RPC 2.0 response, one per line ──
   child stderr  ── server logs, kept out of the terminal
```

The Java server (Lucene 10, plus the backward codecs for Lucene 9 indexes) does all the index work,
and the Rust TUI ([ratatui](https://ratatui.rs)) only displays it. Every method has typed DTOs on
both sides, which are checked against the shared golden files in
[`protocol/fixtures/`](protocol/fixtures/).

To talk to the server by hand:

```sh
jq -c . protocol/fixtures/version.request.json | java -jar leia-server/build/libs/leia-server.jar
```

More detail for contributors: [`AGENTS.md`](AGENTS.md) (layout, rules, commands) and the
architecture decisions in [`docs/adr/`](docs/adr/).
