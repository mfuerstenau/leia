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
  point and vector dimensions, and summed postings statistics (doc count, Σ doc freq, Σ term freq).

Documents, search, analysis, commits and the rest of Luke's read-only features are still to come
(see [`docs/tasklist.md`](docs/tasklist.md)).

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
| `Enter` | count the terms of the selected field and find its top 50 terms by document frequency |
| `a` | do so for every field with postings, one after another |
| `Tab` | switch between the field table and the top terms |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the field table or the top terms |
| `Esc` | stop the running term scans; with none running, quit |
| `q`, `Ctrl+C` | quit |

Counting terms reads every term of the field, which can take a while on a large index. The scan
runs in the server while leia shows its progress, and `Esc` stops it. Once every field has been
counted, the field table's title shows the total. Like Lucene's own statistics, the counts include
deleted documents that no merge has removed yet.

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
