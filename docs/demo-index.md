# Demo index

A Lucene 10 index for trying leia by hand: a small product catalogue with a field for nearly
everything leia shows. It is generated, not checked in:

```sh
./gradlew :leia-core:demoIndex :leia-tui:leiaFolder   # → leia-core/build/test-indexes/demo
leia-tui/build/leia/leia leia-core/build/test-indexes/demo
```

The content is random with a fixed seed, so every run writes the same index. The writer is
`leia-core/src/demoIndex/java/…/DemoIndexWriter.java`. `DemoIndexTest` in leia-server checks the
numbers below through the backend, so a change to the writer that moves one fails the build; the
other tests use their own indexes and the Lucene 9 and 8 indexes from `LegacyIndexWriter`.

## Index

- 500 documents in 3 segments (documents 0–199, 200–399, 400–499), with no merges. The first two
  are compound files, the third (`_2`) is not.
- 4 commits, all kept (`NoDeletionPolicy`): `segments_1` to `segments_3` after each segment, and
  `segments_4`, the latest, with the deletions and the user data.
- On the commits screen, the details of segment `_2` (or the compound `_0`) list the field formats:
  postings `Lucene104` for the indexed fields, doc values `Lucene90` for `category`, `checksum`
  and the other doc values fields, and KNN vectors `Lucene99HnswVectorsFormat` for `embedding` and
  `fingerprint` (ADR 0037).
- 13 deleted documents: every multiple of 37 (37, 74, …, 481).
- Commit user data: `writer`, `source`, `docs`.

## Fields

| Field | What it is for |
|---|---|
| `id` | `doc-0000` … `doc-0499`: a keyword with docs only and no norms; stored |
| `title` | text with positions and norms; stored |
| `title_key` | the title as one German collation key at primary strength (`CollationKeyAnalyzer`), docs only: a range on it needs that analyzer for the field |
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
| `updated` | keyword, stored: the hour of `timestamp` in UTC as `DateTools` writes it (`2026013114`), for a date resolution per field |
| `scores` | sorted numeric doc values, 1–4 values from -100 to 100 |
| `weight` | sorted numeric doc values as sortable double bits (`x` decodes them) |
| `location` | lat/lon point + lat/lon doc values |
| `checksum` | binary doc values, 8 random bytes |
| `embedding` | float KNN vector, 8 dimensions, cosine; in every document but 0. Its first component is shifted by 2, so it is never all zeros |
| `fingerprint` | byte KNN vector, 16 dimensions, euclidean; in every document but 0 |
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
- **Rebuilding indexed terms (`r`):** `title` from the postings with positions; `body` from its term
  vector at once, and 42's 5000 words in position order (`東京` as `東 京`); `labels` with freqs and
  `keywords` with docs only, neither of them stored; `binary_key` as hex; `deleted_only` in the
  deleted document 74.
- **Rebuilding points (`r`, ADR 0041):** on document 1, `quantity` shows `1 point, as int (t)` and
  `302`, its stored value; `price` shows a long until `t` makes it a double, `313.97`; `location`
  shows `52.52, 13.405` as lat/lon (`t` cycles to int, float and hex). The deleted document 74 has
  its points too: `quantity` 697.
- **A BKD tree (terms screen, ADR 0041):** the demo writer keeps BKD leaves at 16 points, so
  `price pt 1×8` has 3 segments of 199, 200 and 100 points (document 0 has no price), each a tree of
  several levels. `t` shows them as doubles: `_0` covers `1.07 … 499.23`; `Enter` down the first
  cells reaches a leaf of 16 points whose documents include 3, 29 and the deleted 37 (in red);
  `Enter` on one opens it on the documents screen with `price` selected. `location pt 2×4` shows
  lat/lon ranges.
- **Seeking a field (`/`):** `ts` selects `tsv`, `sep` selects `unit_separated` (it contains
  `sep`), and `bl` selects `blob` on document 42; on document 0 with `e`, `bl` finds no match.
- **Exporting a document (`E`):** 42 gives a file of about 1.7 MB (format version 3): `blob` and
  `_source` whole, not cut; `embedding` and `fingerprint` with their `vector`; `body` with `termVector` and `indexed` from the term vector; `labels` and `keywords` with
  `indexed` from the postings only; `price`, `quantity`, `rating`, `timestamp` and `location` with
  `points` in hex, and no `indexed`. `jq '.fields[] | {name, flags, src: .indexed.source}'` lists
  them. A path in `leia-core/build/test-indexes/demo/` is refused. Document 0 gives only `id`.

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
- `+published:[1/1/26 TO 1/31/26] +updated:[1/10/26 TO 1/20/26]`, with `p` setting the date
  resolution to DAY, `updated` in the per-field group to HOUR, the locale to `en-US` and the time
  zone to `UTC`: 12 hits, the query becomes `+published:[20260101 TO 20260131]
  +updated:[2026011000 TO 2026012023]`, the same documents as
  `timestamp:[1768003200000 TO 1768953599999]`. With `updated` at the default DAY, its bounds
  become `[20260110 TO 20260120]`, and every hour of January 20 sorts after the upper bound: 10
  hits.
- `title:lamp` with `s` sorting by the score, worst first: the longest titles come first.
- `title:lamp` with `s` set to classic TF-IDF: doc 51 scores 2.4358, and `x` shows the
  `ClassicSimilarity` explanation with the same value. Boolean scores every hit 1.
- `body:the`: 471 hits. `Enter` on the first, then `]` a hundred times on the documents screen:
  the second page is fetched on the way to hit 101 (doc 418).
- Browsing through a filter (`b`): `title:lamp AND body:qu*` with the third hit selected, then `b`:
  the documents screen shows doc 93, `1 of 10`; `→` goes to 137 and 156, `←` from 93 says `no
  previous match`, and `i` `499` lands on the last match, 489. `*:*` with `b` has 487 matches,
  with `B` 500, and `i` `37` then shows the deleted doc 37 as `38 of 500`. With `title:lamp` as
  the filter (19 matches), `Enter` on hits of `body:quick` says `not a match`, and `→` from doc
  440 goes on to the next lamp, 466.
- The filter on the terms screen: with `title:lamp` as the filter, the postings of `body`/`quick`
  (249) mark the lamps `•`; `b` lists only those, 10 of them from doc 93 to 489.
- Doc values (`m` three times, `f` to the field, `t` for an exact count): `quantity` as long,
  `[-5 TO 5]`: 9 hits, the same as `quantity:[-5 TO 5]` on the int points in standard syntax.
  `price` with `n` as double bits, `{* TO 10]`: 12 hits, the same as the double points, with the
  range split at zero in the parsed query. `category` `kitchen`: 80
  hits; `tags` `eco gift`: 101, `[a TO c}`: 92. On the documents screen, `s` on `checksum` (binary,
  8 random bytes) searches for it in hex: 1 hit, the document itself; `s` on `price` after `x`
  searches for its double.
- A doc values prefix (ADR 0036): `category` `o*`: 158 hits (office and outdoor), the same as
  `category:o*` in classic syntax, with `category:[[6f] TO [70]}` as the parsed query; `tags` `l*`:
  179, `*`: 385 (every document with a tag); `checksum` with `h` and `80*`: 1, and `*`: 486. `price` `1*` says a
  prefix is for doc values of bytes, and `o* toys` that a prefix stands alone.
- KNN (ADR 0040): the overview's Vector column says `8d f32 cos` for `embedding` and `16d byte euc`
  for `fingerprint`. On document 42, the details of `embedding` list its 8 components and its
  length, 2.63876; `N` searches its 10 nearest: doc 42 first with score 1.0, then 411, 98, 491.
  `e` scores every document exactly instead: 486 hits (every live document but 0), with the same
  first 10. `w` with `category:kitchen`: 80 hits exactly; with `e` again and `k` 3, the graph
  finds 42, 98 and 278, the exact top 3, and `b` browses them on the documents screen (`1 of 3`).
  `[1, 2]` on the line fails: `embedding` has 8 dimensions. With `f` on `fingerprint`, `[0.5]`
  can't be read, since a vector of bytes takes whole numbers.
- A regex as the filter, on a copy of the demo index without `segments_4`, which has no deletions
  yet: `qu\w+` in regex mode, then `b`: 499 matches (all but document 0), starting on `1 of 499`.
  Put `segments_4` back and press `R`: the tab line says `Reopened: segments_4; rebuilding the
  filter`, and the title then says `1 of 486`, on the same document.

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
- The preset `CollationKeyAnalyzer` (`b`, `Space` to preset, `Space` on the class) with locale `de`
  and strength `primary`: `Äpfel` and `apfel` both stay their own term, but the token details
  show the same `bytes`, the collation key that an index of this analyzer holds. With `tertiary`
  they differ. `ICUCollationKeyAnalyzer` comes with `--jar` and the `icu` module
  (`./gradlew :leia-server:analysisModules`), and also reads tags like `de-u-ks-level1`.
- A field's own analyzer: `f` until the field line says `title_key`, then `b`, the preset
  `CollationKeyAnalyzer`, locale `de`, strength `primary`. On the search screen, the classic
  `title_key:[a TO m]` then finds 206 documents; with the standard analyzer for every field it
  compares the text's bytes with the keys and finds others. `D` on the analysis screen drops the
  field's analyzer again.
- Auto stop words (`w`): 40 % of the documents with `body` chosen finds the 16 words of `body`
  in more than 194 of the 487 live documents (deleted ones count, as in Lucene), from `a` and
  `and` to `the`, `to` and `with`; `v` on `body` lists them. With the field `body` on the
  analysis screen, a text then gets one more stage, `AutoStopWords`, without them; the search
  screen's info line counts them.
- A config directory with `syn.txt` (`wifi, wireless network`) and `synonymGraph` with
  `synonyms=syn.txt`: `fast wifi router` gives `wireless` and `network` at positions 1 and 2, and
  `wifi` in italics, stacked on `wireless` and spanning `1–2`; `router` moves to position 3.
- More-Like-This for a text (`m` six times), `A vintage leather wallet with a wooden table`:
  with Lucene's defaults only `a` occurs twice, so the query is `a` in four fields, as the term
  table shows. With `p`, a min term freq of 1 and the stop words `a with`: `vintage`, `wooden`,
  `wallet`, `table` and `leather`, in `title` first, where they are rarest. A min word length of 7
  leaves only `vintage` and `leather`.
- Regex over stored values (`m` seven times), `sku\d{4}`: 980 values in 486 documents, in `body`,
  `tagged` and `note`, each with its matches highlighted. `"event":` finds one value, the 108 KiB
  `_source` of doc 42, with 3000 matches: `v` opens it with the first 100 highlighted, and `m`/`M`
  jump between them. `DELETED` with `i` (ignore case) and `d` (deleted documents too): doc 74's
  `deleted_only`, marked `✗`. `(.*.){30}x` with `l` limited to `odd_text` takes over a second on
  doc 7, which is skipped and listed as timed out.
