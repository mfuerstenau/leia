# Demo index

A Lucene 10 index for trying leia by hand: a small product catalogue with a field for nearly
everything leia shows. It is generated, not checked in:

```sh
./gradlew :leia-core:demoIndex        # → leia-core/build/test-indexes/demo
LEIA_SERVER_JAR=$PWD/leia-server/build/libs/leia-server.jar \
  cargo run --manifest-path tui/Cargo.toml -- leia-core/build/test-indexes/demo
```

The content is random with a fixed seed, so every run writes the same index. The writer is
`leia-core/src/demoIndex/java/…/DemoIndexWriter.java`. The tests don't use this index; they use
their own indexes and the Lucene 9 and 8 indexes from `LegacyIndexWriter`.

## Index

- 500 documents in 3 segments (documents 0–199, 200–399, 400–499), with no merges. The first two
  are compound files, the third (`_2`) is not.
- 4 commits, all kept (`NoDeletionPolicy`): `segments_1` to `segments_3` after each segment, and
  `segments_4`, the latest, with the deletions and the user data.
- 13 deleted documents: every multiple of 37 (37, 74, …, 481).
- Commit user data: `writer`, `source`, `docs`.

## Fields

| Field | What it is for |
|---|---|
| `id` | `doc-0000` … `doc-0499`: a keyword with docs only and no norms; stored |
| `title` | text with positions and norms; stored |
| `body` | text with positions and offsets, and term vectors with positions and offsets; stored. A rare `sku1234` term per sentence gives a long tail of terms (more than one 100-term page) |
| `keywords` | docs only, with norms |
| `labels` | docs and freqs, with norms |
| `tagged` | positions with payloads, and term vectors with payloads; stored. Words have a text payload (`DET`, `NN`, `VB`), numbers a 4-byte int that shows as hex |
| `category` | keyword + sorted doc values (6 values) |
| `tags` | multi-valued keyword + sorted set doc values; missing in about 1 in 5 documents |
| `binary_key` | binary terms that aren't UTF-8 (`0xff…80`): hex on the terms screen; stored binary |
| `price` | double point + stored double + double doc values (raw double bits: `x` decodes them) |
| `quantity` | int point + stored int + numeric doc values, some negative |
| `rating` | float point + stored float + float doc values (float bits) |
| `timestamp` | long point + stored long + sorted numeric doc values (epoch millis in 2026) |
| `published` | keyword, stored: the day of `timestamp` in UTC as `DateTools` writes it (`20260131`), for date ranges |
| `scores` | sorted numeric doc values, 1–4 values from -100 to 100 |
| `weight` | sorted numeric doc values as sortable double bits (`x` decodes them) |
| `location` | lat/lon point + lat/lon doc values |
| `checksum` | binary doc values, 8 random bytes |
| `embedding` | float KNN vector, 8 dimensions, cosine |
| `fingerprint` | byte KNN vector, 16 dimensions, euclidean |
| `_source` | the document as JSON, stored as UTF-8 bytes like in Elasticsearch: opens as JSON in the viewer |
| `json` | 9 values: an object (big numbers, `2.50`, escapes, duplicate keys, empty `{}`/`[]`, nesting), an array, a string, a number, 20 levels deep, 200 levels deep, then three that aren't JSON |
| `csv` | comma-separated, with one empty item |
| `tsv` | tab-separated: delimiter `\t` |
| `kv` | `key=value;…`: delimiters `;` and `=`, with an item without `=`, a value with `=`, and a long key |
| `log` | pipe-separated log line: delimiter `\|` |
| `unit_separated` | items split by U+001F, keys by U+001E: delimiters `\u{1f}` and `\u{1e}` |
| `lines` | 3–22 lines: delimiter `\n` numbers them |
| `mixed` | one value of every stored type (string, int, long, float, double, binary, JSON): step with ←/→ |
| `note` | sparse: only in documents 3, 13, 23, … |
| `empty_string` | a stored empty string, in documents 5, 30, 55, … |
| `odd_text` | only in document 7: tab, bell, NUL, CRLF, emoji, wide characters, a combining accent |
| `blob` | only in document 42: 100,000 random bytes, over the 64 KiB cut and not UTF-8 |
| `deleted_only` | only in document 74, which is deleted: in the schema, but no live document has it |

## Documents worth a look

- **0:** only `id`, so every other field is empty (`e` hides them).
- **7:** `odd_text`.
- **42:** a 5000-word `body` (its norm encodes a range), a `_source` of about 108 KiB with a
  3000-entry `history` array (cut, then fetched whole by the viewer), and `blob`.
- **37, 74, …:** deleted; 74 has `deleted_only`.

## Searches worth a try

- `title:lamp AND body:qu*`: 10 hits; `x` on doc 223 shows a BM25 explanation with a prefix
  clause.
- `*:*` with `t` (exact count): 487 hits, the live documents, fetched 100 at a time as you scroll.
- `/` on a top term of `binary_key` in the overview: a term query for a binary term, shown as hex.
- `title:(lamp`: a syntax error; `body:*a*`: a leading wildcard, which the classic parser rejects
  until `p` turns leading wildcards on.
- `m` on document 223 (documents screen): More-Like-This with Lucene's defaults, 485 hits with doc 223
  (`◆`) first. `body` has term vectors, `title` is analyzed from its stored text. With `p`, a min
  term freq of 1, a max doc freq of 50 and "boost", the rewritten query shows the weighted terms.
  Document 0 has nothing to take terms from, and document 37 is deleted: a source, but no hit.
- Standard syntax (`m`), `title:lamp AND price:[10 TO 20]`: no hits while `price` is guessed to be
  LONG (the details panel says so). With `p`, set `price` to DOUBLE: 1 hit, doc 196.
  `quantity>=50` needs no change, since `quantity` is an int point.
- `title:lamp`, then `s`: sort by `price`, read as DOUBLE, descending (doc 51, 495.49, first), then
  by `tags`: hits without tags show `–`. Read as LONG, `price` orders by its raw double bits, which
  for these positive prices is the same order with odd numbers; read as INT, it orders by their
  low 32 bits, which is nonsense, but no error.
- `*:*`, then `s`: sort by `scores`, value MAX, descending, then by document id: the documents
  whose largest score is 100 come first, in id order (doc 64 first). Sort by `tags` with missing
  first: about a hundred documents without tags (`–`) come first, ascending or descending; with
  missing last, they come last either way.
- `published:[1/1/26 TO 1/31/26]`, with `p` setting the date resolution to DAY, the locale to
  `en-US` and the time zone to `UTC`: 49 hits, the bounds become `[20260101 TO 20260131]`, the same
  documents as `timestamp:[1767225600000 TO 1769903999999]` in standard syntax. Without a
  resolution, the bounds stay text: no hits. With `de-DE`, `[01.01.26 TO 31.01.26]` reads the same.
  `[2/1/26 TO 2/28/26]` has 44 hits in UTC; with the time zone cleared, a JVM in Berlin reads
  midnight as the day before in UTC, so the range starts at `20260131`: 46 hits.
- `title:lamp` with `s` sorting by the score, worst first: the longest titles come first.
- `title:lamp` with `s` set to classic TF-IDF: doc 51 scores 2.4358, and `x` shows the
  `ClassicSimilarity` explanation with the same value. Boolean scores every hit 1.
- `body:the`: 471 hits. `Enter` on the first, then `]` a hundred times on the documents screen:
  the second page is fetched on the way to hit 101 (doc 418).

## Analysis worth a try

The analysis screen (`6`) needs no index, but these go with the demo index's text:

- `<b>The</b> Wi-Fi routers are running FASTER` with the standard analyzer: the tags come out
  as terms (`b`), and `Wi-Fi` as `wi` and `fi`. With `htmlStrip` added as a char filter (`b`, `Space`, then `+ char filter`), the tags
  are gone, and `the` keeps the offsets of the original text (3–10).
- Add `porterStem` after `lowercase`: `routers` becomes `router`, `running` becomes `run`. On the
  search screen, `routers running` then parses to `title:router title:run`, and the details panel
  shows the analyzer.
- Add `stop`: `the` and `are` are gone, and the next tokens' positions are marked `·`. With
  `words=nope.txt`, the builder stays open and says that the resource is not found.
- A config directory with `syn.txt` (`wifi, wireless network`) and `synonymGraph` with
  `synonyms=syn.txt`: `fast wifi router` gives `wireless` and `network` at positions 1 and 2, and
  `wifi` in italics, stacked on `wireless` and spanning `1–2`; `router` moves to position 3.
