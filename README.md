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
- **Documents:** one document at a time, deleted ones included: norms, stored values, doc values
  and term vectors.
- **Terms:** the terms of a field with their frequencies, and the documents that contain a term,
  with the positions, offsets and payloads of each occurrence.

Search, analysis, commits and the rest of Luke's read-only features are still to come (see
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
| `1` `2` `3` | switch between the overview, the documents and the terms |
| `Esc` | stop the running term scans; with none running, quit |
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
| `x` | show numeric doc values as long, double bits, sortable double, float bits or sortable float |
| `e` | hide or show the fields in which the document has nothing |
| `Tab` | switch between the field table and the details of the selected field |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the fields or scroll the details |

The documents screen shows one document at a time, every field of the index in a row: its flags,
its norm and its first stored value (or else its doc values). The details panel shows all of the
selected field's stored values, doc values and, on `Enter`, its term vector with positions,
offsets and payloads. Deleted documents can be read too, until a merge removes them; they are
marked `deleted`. Byte strings that aren't UTF-8 are shown as `0x` and their hex.

Flags: `I` indexed, `N` has a norm, `P` payloads, `S` stored, `V` term vector, `D` doc values,
`T` points, `K` KNN vectors. `N`, `S`, `V` and `D` say what this document has; the others describe
the field. Whether the document has terms in a field is not shown: finding out means reading the
field's terms.

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
