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
  encode, stored values, doc values and term vectors, the terms a field was indexed with (rebuilt
  from the postings), and a viewer for whole values as text, hex, pretty-printed JSON or delimited data.
- **Terms:** the terms of a field with their frequencies, and the documents that contain a term,
  with the positions, offsets and payloads of each occurrence.
- **Commits:** every commit in the index directory with its segments, and for each segment its
  documents, deletions, versions, codec and codec formats, diagnostics, attributes and files with
  their sizes.
- **Search:** a query in Lucene's classic or standard syntax, a single term, or the documents like
  a given one or like a text (More-Like-This, with the terms it chose), with the parsers' options and ranges on point fields, scored with BM25, classic TF-IDF or boolean similarity and
  sorted by score, document id or up to two doc values fields (with the value of a multi-valued
  field and the place of missing values); the hits with their scores, sort values and
  chosen stored fields, exact or approximate hit counts, and an explanation of how a hit scores.
  A regular expression over the stored values finds what was never indexed or was analyzed
  away, with the matches highlighted. A query can also narrow the documents screen to the
  documents it matches, to browse them in document id order.
- **Analysis:** the standard analyzer, one of Lucene's analyzers by class name (such as
  `EnglishAnalyzer`), or a custom one built from Lucene's char filters, tokenizers and token filters
  with their parameters and a config directory for resource files. More analyzers and components
  come from jars, such as Lucene's ICU, Japanese or Korean modules, or your own. The screen
  shows what the analyzer makes of a text step by step, with every token attribute. It needs no
  open index, and the search parses queries with the analyzer you build.
- **Check:** Lucene's `CheckIndex` on every index of the path, at one of its three levels, with
  its report as it is written and a summary of what is broken. It writes nothing, and works
  without a reader too.
- **Export terms:** the terms of a field with their document frequencies, written to a file.

The rest of Luke's read-only features are still to come (see
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
| `INDEX_PATH` | the index directory to open on start, or a directory with indexes below it (see [Several indexes](#several-indexes)); without one, leia starts with no index open |
| `-j, --java <PATH>` | the JVM, given as a `java` executable or a JDK/JRE directory; default: `$JAVA_HOME/bin/java`, then `java` on `PATH` |
| `-V, --version` | print the versions of leia, leia-server and the Java in use |
| `--jar <PATH>` | a jar with analysis components, codecs or formats of its own, or a directory of such jars; can be repeated (see [Jars](#jars)) |
| `--no-default-jars` | don't load the jars of the default folder (see [Jars](#jars)) |
| `--directory <IMPL>` | the Lucene `Directory` to read indexes through: `fs` (default) lets Lucene choose, which is `MMapDirectory` on 64-bit; `mmap` is `MMapDirectory`, `nio` is `NIOFSDirectory`. `Ctrl+D` in the open prompt changes it |
| `--no-reader` | open `INDEX_PATH` without a reader, for an index that a reader can't open (see [Without a reader](#without-a-reader)); `Ctrl+N` in the open prompt does the same |

`leia-server.jar` has to be next to the `leia` executable. leia resolves symlinks, so a link to
`leia` in `~/bin` works.

leia keeps the last 10 opened index paths for the open prompt in `~/.local/state/leia/history`
(`$XDG_STATE_HOME/leia/history` if that is set), or `%LOCALAPPDATA%\leia\history` on Windows.

```sh
leia --java /opt/jdk-25 /var/lib/solr/products/data/index
```

### Several indexes

A path that holds no index itself (no `segments_N` file) opens every index below it as one, like
Luke's multi-reader mode: documents, terms, search and field statistics cover all of them, with
document ids running through the indexes in the order they were found (depth-first, by name).
leia doesn't look below a directory that holds an index, doesn't follow symbolic links, and stops
after 10,000 directories. An index that fails to open, or whose fields are indexed differently
from those of the indexes before it, is left out, and the overview lists it with the reason.
The overview lists each index with its commit, the documents screen says which index a document
is in, the commits screen has an `Indexes` panel to choose whose commits are shown, and `R`
reopens the indexes that changed. Indexes added or removed below the path need `o`.

### Without a reader

An index that a reader can't open (a missing or corrupt file, a codec that isn't loaded) can
still be opened without one: `Ctrl+N` in the open prompt, or `--no-reader`. When an open fails
with a corrupt index or a missing codec, the prompt offers it. Only the commits and check
screens work then: the commits screen lists the commits that can be read and, in red, the
`segments_N` files that can't, with the reason, and the check screen says what `CheckIndex` finds
broken. Overview, documents, terms and search say that they need a reader. The tab line shows
`no reader`.

### Jars

`--jar` adds jars with analysis components, codecs or formats of their own. A directory stands for
the jars directly in it. `X` adds more while leia runs (see below), and the jars in the default
folder are loaded at every start.

**Codecs and formats.** leia opens indexes written with the codecs and formats of `lucene-core`,
`lucene-backward-codecs`, `lucene-codecs` (such as `SimpleText`), `lucene-suggest` (the
`Completion` postings formats of suggesters) and `lucene-sandbox`. An index with a codec, postings
format, doc values format or KNN vectors format from elsewhere needs a jar that has it (registered
through `META-INF/services`, as Lucene's are). Without one, opening the index says which one is
missing. Indexes written by Elasticsearch or OpenSearch need those servers' classes, and their
`lib/` folders come with a Lucene version of their own, so they may not open even with those jars.

**Analysis.** leia knows the analyzers, char filters, tokenizers and token filters of `lucene-core`
and `lucene-analysis-common`. Lucene's other analysis modules aren't bundled, since together they
add about 38 MB. `--jar` adds them, or jars of your own with factories (registered through
`META-INF/services`) or `Analyzer` classes. A module needs its dependencies too:

| Module | Jars (Lucene 10.5.2) |
|---|---|
| ICU | `org.apache.lucene:lucene-analysis-icu`, `com.ibm.icu:icu4j:77.1` |
| Japanese | `org.apache.lucene:lucene-analysis-kuromoji` |
| Korean | `org.apache.lucene:lucene-analysis-nori` |
| Chinese | `org.apache.lucene:lucene-analysis-smartcn` |
| Polish | `org.apache.lucene:lucene-analysis-stempel` |
| Polish and Ukrainian dictionaries | `org.apache.lucene:lucene-analysis-morfologik`, `org.carrot2:morfologik-stemming`, `org.carrot2:morfologik-fsa`, `org.carrot2:morfologik-polish` (2.1.9), `ua.net.nlp:morfologik-ukrainian-search:4.9.1` |
| OpenNLP | `org.apache.lucene:lucene-analysis-opennlp`, `org.apache.opennlp:opennlp-tools:2.5.3`, `org.slf4j:slf4j-api:2.0.16`; the models are files in the config directory |
| Phonetic | `org.apache.lucene:lucene-analysis-phonetic`, `commons-codec:commons-codec:1.17.2` |

If you build leia, `./gradlew :leia-server:analysisModules` puts each module with its dependencies
into a directory of its own:

```sh
leia --jar leia-server/build/analysis-modules/kuromoji \
     --jar leia-server/build/analysis-modules/icu /data/index
```

**Loading.** The jars of `--jar` are loaded when leia starts, then those of the default folder:
`~/.config/leia/jars` (`$XDG_CONFIG_HOME/leia/jars` if that is set), or `%APPDATA%\leia\jars` on
Windows. leia doesn't create the folder; put the jars you always want there.
`--no-default-jars` leaves it out. If a jar can't be loaded, for instance because a dependency is
missing, leia says why and doesn't start. A factory, codec or format whose name is already taken
is ignored, so `--jar` wins over the default folder.

**Adding jars while leia runs.** `X` opens the jars popup on any screen. It shows the default
folder and the loaded jars, and takes the path of a jar or a directory of jars: `Tab` completes
directories and `.jar` files, and `Enter` loads it and says what it added, such as presets, token
filters or codecs. A jar that fails to load leaves nothing behind, and a jar that is already loaded
isn't loaded again. When an index fails to open because it needs a codec, `Esc`, `X` to add the
jar, then `o` brings back the path to open it again. Jars can't be unloaded. A loaded jar must stay
where it is: if it is deleted or replaced, no more jars can be added until leia is restarted.

### Keys

| Key | Action |
|---|---|
| `o` | open an index: type a path, `Tab` completes directories, `~` is your home directory, `↑` `↓` step through the last 10 opened paths, `Ctrl+D` switches the `Directory` (FS, MMap, NIO), `Ctrl+N` switches the reader off for this open, `Enter` opens and `Esc` cancels; after a failed open, the next `o` starts from that path |
| `c` | close the open index |
| `X` | list the loaded jars and add more (see [Jars](#jars)) |
| `R` | reopen the open index, if another process committed to it since: the screens keep their place, and the tab line says `Reopened: segments_N`, `Unchanged: segments_N` (with several indexes, how many changed) or why it failed |
| `1` … `7` | switch between the overview, the documents, the terms, the commits, the search, the analysis and the check; only the analysis works without an open index |
| `Esc` | stop the running term scans, then a running export, or on the search and check screens the running search or check; with none running, quit |
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
| `←` `→` / `p` `n` | previous or next document id; with a filter, previous or next match |
| `i` | go to a document id; with a filter, to the match there or after it, else the last one before |
| `b` | clear the filter (`Esc` stops one that is still being built) |
| `/` | seek a field: type a text, and the first shown field whose name starts with it (ignoring case), or else contains it, is selected; `Enter` or `Esc` ends it |
| `[` `]` | previous or next document of the term opened on the terms screen |
| `Enter` | load the term vector of the selected field |
| `r` | rebuild the terms the selected field was indexed with (`Esc` stops it) |
| `v` | view the selected field's values (see below) |
| `x` | show numeric doc values as long, double bits, sortable double, float bits or sortable float |
| `e` | hide or show the fields in which the document has nothing |
| `m` | search for documents like this one (More-Like-This) on the search screen |
| `E` | export the document as JSON to a file, or copy it (see below) |
| `Tab` | switch between the field table and the details of the selected field |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the fields or scroll the details |

The documents screen shows one document at a time, every field of the index in a row: its flags,
its norm and its first stored value (or else its doc values). The details panel shows all of the
selected field's stored values, doc values and, on `Enter`, its term vector with positions,
offsets and payloads. Deleted documents can be read too, until a merge removes them; they are
marked `deleted`. Byte strings that aren't UTF-8 are shown as `0x` and their hex.

`r` rebuilds the terms the document was indexed with in the selected field, also for fields
without term vectors (ADR 0029). A term vector that holds all that the postings hold is read at
once. Otherwise every term of the field in the document's segment is checked for the document, so
it takes a while on a field with many terms; the details panel shows the progress, and `Esc` or
another document stops it. The result shows the tokens in position order (`a|b` at one position,
`·` where positions were skipped, such as for a removed stop word), then each term with its freq
and positions.

`E` exports the whole document as JSON (ADR 0030): per field, the flags, the norm, the stored
values (whole, never cut), the doc values, the term vector and the rebuilt indexed terms; binary
values are hex. The prompt offers `doc-<id>.json` in the working directory; `Tab` turns the
rebuild of the indexed terms off, which is the slow part on a large index, and `Ctrl+Y` copies
the JSON through OSC 52 instead of writing it (terminals and tmux may cut large copies). As with
the terms export, a file that exists takes a second `Enter`, and leia never writes into an index
directory. While the terms are rebuilt, the tab line shows the progress and `Esc` stops it.

The norm is shown with the field length it encodes, such as `88 → length 1048–1175`: all of
Lucene's similarities store the number of terms in one byte (`SmallFloat.intToByte4`), exactly up to
39 and as a range above. A norm that another similarity wrote may mean something else.

Stored values longer than 64 KiB come cut: the details panel shows their beginning, and the
viewer fetches them whole.

`b` on the search screen narrows the documents screen to the documents that match the query
(ADR 0031), `B` to those including the deleted ones. The server finds every match once, without
scoring, and keeps them as one bit per document. The documents screen then starts on the selected
hit, `←` `→` step to the previous or next match in document id order, and the title says
`filter <query>: 3 of 19`. A document shown some other way, such as from a hit of another search,
says `not a match` if the filter doesn't hold it. `b` on the documents screen clears the filter;
opening, closing or a reopen that finds a new commit forgets it too.

Flags: `I` indexed, `N` has a norm, `P` payloads, `S` stored, `V` term vector, `D` doc values,
`T` points, `K` KNN vectors. `N`, `S`, `V` and `D` say what this document has; the others describe
the field. Whether the document has terms in a field is not shown: finding out means reading the
field's terms, which `r` does.

#### Viewer

`v` shows the selected field's stored values and doc values one at a time over the whole screen.

| Key | Action |
|---|---|
| `←` `→` / `p` `n` | previous or next value |
| `m` `M` | next or previous match, for a value opened from the regex scan |
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
| `e` | export the selected field's terms to a file |
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

`e` writes every term of the selected field, over all indexes, with its document frequency to a
file, one `term<delimiter>docFreq` per line as Luke does. It asks for the file, at first
`terms_<field>_<UTC time>.out` in the current directory; `~` and relative paths work, and `Tab`
switches the delimiter between tab, comma and space. A file that exists is only overwritten after
a second `Enter`, and leia refuses to write into an index directory. While the export runs, the
tab line counts the terms written and `Esc` cancels it, which deletes the file. A term that isn't
valid UTF-8 is written as hex, and a term may contain the delimiter, so split at the last one.

#### Commits

| Key | Action |
|---|---|
| `Tab`, `Shift+Tab` | switch between the indexes (with several), the commits, the segments of the selected commit and the details |
| `Enter` | on a commit, go to its segments |
| `r` | read the commits from the directory again |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the focused list, or scroll the details |

The commits screen lists the commits of the index directory, newest first; `●` marks the one leia
opened, and `✗` a `segments_N` file that can't be read, with the reason. A directory keeps older commits only if the writer's deletion policy kept them, and it
can hold commits written after leia opened the index: `r` reads them again. Below are the
segments of the selected commit in document id order, and the details of the commit (its user
data and all its files) or of the selected segment (document range, deletions, versions,
generations, diagnostics, attributes, codec formats and its files). A file that can't be read
shows as missing. The documents and terms screens always show the opened commit.

#### Search

| Key | Action |
|---|---|
| `/` | edit the query, or for More-Like-This the document id or the text; `Enter` searches, `Esc` leaves the line, and `←` `→`, `Ctrl+A` `E` `U` `K` `W` edit |
| `m` | switch between classic query syntax, standard query syntax, a single term, More-Like-This for a document, More-Like-This for a text and a regex over stored values |
| `p` | set the parser options, and for the standard syntax the type of each point field; for More-Like-This, its fields, limits, word lengths and stop words; `Enter` applies, `Esc` cancels |
| `s` | set the similarity (BM25 with `k1` and `b`, classic TF-IDF or boolean) and the sort; `Enter` applies, `Esc` cancels |
| `l` | choose the stored fields each hit shows; `Space` shows or hides one, `a` all, `n` none |
| `f` `F` | step through the fields with postings: the default field of a query, or the field of a term |
| `a` | combine clauses without an operator with OR or AND |
| `t` | count every hit, or stop counting after 1000 |
| `x` | explain how the selected hit scores |
| `i` | regex: ignore case, or match it |
| `d` | regex: scan deleted documents too, or not |
| `Enter` | show the selected hit's document on the documents screen |
| `b` `B` | browse the documents that match the query on the documents screen, without or with the deleted ones (not for the regex) |
| `v` | regex: view the selected value with its matches highlighted |
| `Tab` | switch between the hits and the details |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the hits, or scroll the details |

A classic query is parsed with Lucene's classic `QueryParser` and the analyzer of the analysis
screen (at first the `StandardAnalyzer`), for example `title:fox AND body:qu*`, `"quick fox"~2` or `fox~`. A standard query is parsed with
Lucene's `StandardQueryParser`, which also turns ranges and values on point fields into point
queries: `price:[10 TO 20]`, `quantity>=5`, `quantity:7`. For that it needs the type of each
point field's values, which the index doesn't record: leia guesses INT for 4 bytes and LONG for 8,
and `p` corrects the guess to FLOAT or DOUBLE, or leaves a field to text. The details panel lists
the types in use. In the same popup are the parser options: leading wildcards, phrase slop, fuzzy
prefix length and position increments for both parsers, and fuzzy max edits, whitespace splitting
and auto-generated phrases for the classic one. For date ranges written with `DateTools`, such as
`published:[1/1/26 TO 1/31/26]`, the popup sets the date resolution, the locale the dates are
written in (`en-US`, `de-DE`) and the time zone they are read in (`UTC`, `Europe/Berlin`). Without
a resolution, the bounds stay text. Without a time zone, they are read in the server JVM's zone,
as Lucene does (ADR 0026). A term query searches the field for
the line exactly as typed, `:` and spaces included, without analysis. A changed setting searches
again.

More-Like-This searches for the documents like the one whose id the line holds, as Lucene's
`MoreLikeThis` does; `m` on the documents screen starts it for the document shown. It takes the
terms of each chosen field from the document's term vector, or else from its stored text,
analyzed with the analyzer of the analysis screen, and keeps the best of them by tf × idf. `p` chooses the
fields (at first those with positions) and the limits, which start from Lucene's defaults: a term
must occur at least twice in the document and in at least 5 documents, and the query keeps the
best 25 terms. With "boost", each term is weighted by its score. The rewritten query lists the
terms, weakest first. The details panel also lists them best first, with the numbers MLT chose
them by: the score (tf × idf), how often the term occurs in the source (tf) and in how many
documents (df), and its field. The source document matches its own terms and is marked `◆`; a
deleted source can be used too, but is no hit. If no term passes the limits, nothing matches, and
the details panel says so.

The next mode, More-Like-This for a text, takes its terms from the text the line holds: each chosen
field analyzes it, and the best terms of all fields make the query, with the same limits (ADR 0028).
In the same popup, a min or max word length and stop words leave words out. They apply to the terms
as analyzed, so with the standard analyzer a stop word is written in lowercase, and the lengths
count UTF-16 units, as Lucene does.

`s` sets what scores the hits and how they are ordered. The similarity also scores the
explanations, so an explanation always adds up to the score shown. "Discount overlaps" is not offered:
Lucene only reads it while indexing. The hits are sorted by score, or by up to two levels, each
by the score (best or worst first), the document id or a field with numeric, sorted or sorted set
doc values, ascending or descending. A sorted hit keeps its score and shows the values it was
sorted by (`–` for a document without a value, even where Lucene sorted it as 0). Numeric doc values
are read as LONG unless you choose INT, FLOAT or DOUBLE, since the index doesn't record the type:
LONG orders Lucene's own number fields right, and raw double or float bits
(`DoubleDocValuesField`) need DOUBLE or FLOAT. A wrong type orders the hits wrongly but never fails.
A multi-valued field sorts by its smallest value unless you choose the largest, or for sorted set
doc values the middle one (the smaller or larger of two). Documents without a value go where
Lucene puts them (as 0 for numbers, first for ascending strings) unless you choose first or last,
which means first or last in the order shown, ascending or descending.

The hits come 100 at a time, the next ones once the selection reaches the last row, up to
the first 10,000. Each hit shows its stored fields, all of them or those chosen with `l`, with long
values cut. A document opened from a hit steps through the hits with `[` and `]` on the documents
screen, fetching the next page as needed. The details panel shows
the parsed and the rewritten query, or after `x` the explanation of the selected hit as Lucene
computes it; parts that don't match are dimmed. Searches and explanations run in the server, so
`Esc` stops a slow one, such as a fuzzy query on a large index. A numeric range in classic syntax
doesn't match point fields: use the standard syntax.

The regex mode reads every document's stored fields, those chosen with `l` or all of them, and
looks for a Java regular expression anywhere in each text value and each binary value that is
UTF-8, such as Elasticsearch's `_source` (ADR 0027). It finds what no query can: values that were
never indexed, or whose terms the analyzer changed. Flags go inline, such as `(?s)` for `.` over
line breaks. Every document is read, so on a large index the scan is slow; the hits come in while
it runs, and `Esc` stops it with the hits found so far. Each hit is one value: its document (`✗`
if deleted), its field and position, the number of matches, and the text around the first match
with the matches highlighted. The first 10,000 values are listed; the counts are of all of them.
Matching one value may take at most a second, against patterns that backtrack without end such as
`(.*a){20}b`; a value that takes longer is skipped, and the details panel lists it. `v` opens the
value in the viewer with its first 100 matches highlighted, `m` and `M` jump between them, and
`[` and `]` on the documents screen step through the documents of the hits.

#### Check

| Key | Action |
|---|---|
| `Enter` | check every index of the path with Lucene's `CheckIndex` |
| `l` | switch the level: 1 checks the checksums of every file, 2 also the integrity of every part of each segment, 3 also runs the slow checks |
| `Esc` | cancel the running check |
| `Tab` | switch between the summary and the output |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | scroll the focused panel; at the end, the output follows new lines |
| `←` `→` | scroll the output sideways |

The check reads the latest commit of each index, the opened ones and then those skipped when the
path was opened, through a directory of its own that refuses to write. The summary says per index
whether it is clean or which segments are broken, why, and how many documents they hold; the
output is `CheckIndex`'s report. It even checks a Lucene 8 index, which leia can't open with a
reader. A check stays until another path is opened; a reopen stops a running one.

#### Analysis

| Key | Action |
|---|---|
| `/` | edit the text; `Enter` analyzes it, `Esc` leaves the line |
| `b` | build the analyzer; `Enter` applies, `Esc` cancels |
| `r` | analyze again, such as after a resource file changed |
| `Tab` | switch between the steps, the tokens and the selected token |
| `↑` `↓` / `j` `k`, `PgUp` `PgDn`, `Home` `End` / `g` `G` | move through the steps or the tokens, or scroll the token |

The analyzer is the `StandardAnalyzer`, a preset (an analyzer by class name, picked from a list) or
a custom one. In the builder (`b`), `Space` (or `→`, and `←` back) switches between them and
changes the selected row: the preset or a component (from a list that you filter by typing; a name
that isn't listed is taken as typed), the config directory or a parameter (`name=value`;
blank removes it). `a` adds a filter after the selected one, `p` a parameter to the selected
component, `d` deletes, and `K` `J` move a filter up or down. A preset that takes them, such as
`EnglishAnalyzer`, has stop words and stem exclusions of its own: words separated by spaces or
commas, `-` for no words (no stop words at all), or `@` and a word list file (UTF-8, one word per
line, `#` for comments); blank or `d` goes back to the analyzer's default stop words, or to no
stem exclusions. `StopAnalyzer` needs stop words. A preset can also have options of its own:
- a token length (`StandardAnalyzer`, `ClassicAnalyzer`, `UAX29URLEmailAnalyzer`, `WhitespaceAnalyzer`);
- stem overrides (`DutchAnalyzer`): `word=stem` pairs, `-` for none, or `@` and a file of
  `word<TAB>stem` lines;
- for Kuromoji's `JapaneseAnalyzer`, a user dictionary file, the mode and the stop tags;
- for Nori's `KoreanAnalyzer`, a user dictionary file, the decompound mode, the stop tags (part of
  speech names such as `JX`) and whether unknown words are split into unigrams.

`Space` cycles a mode, and `d` goes back to the default. Under *Wrappers*, any analyzer can be
wrapped in shingles (Lucene's `ShingleAnalyzerWrapper`: the size as `2-3`, the separator, whether
the tokens are kept, and the filler) and then in a token limit (`LimitTokenCountAnalyzer`). `Space`
turns either on or off, and each is one more step after the analyzer's own. The custom chain starts as the
standard tokenizer and lowercase, which is what the `StandardAnalyzer` does. Resource files, such
as `words` of `stop` or `synonyms` of `synonymGraph`, are read from the config directory first and
then from the classpath and the jars, which have Lucene's own word lists. The server builds the analyzer before
it takes it: if it can't, the builder stays open and says why.

The steps are the text after each char filter and the tokens after the tokenizer and each token
filter, each step running the chain again from the start. A token shows its position (`·` marks a
gap, such as a removed stop word; `1–2` a token that spans two positions, such as a multi-word
synonym), its offsets into the original text and its type. A token stacked on the one before
(position increment 0) is in italics. The selected token shows every attribute, such as keyword
marks, flags and payloads. The text can be up to 10,000 characters long, and a step shows at most
10,000 tokens.

The analyzer is kept while indexes are opened and closed, and the search screen parses classic
and standard queries and analyzes More-Like-This text with it; its details panel shows a custom
analyzer.

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
