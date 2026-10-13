# Luke — Feature & Capability Analysis

## 1. What Luke is
- "Luke – Lucene Toolbox": desktop GUI for **browsing, searching, analyzing, checking and maintaining** Lucene indexes.
- Java Swing app; only CLI arg is `--sanity-check`. UI = 6 tabs (Overview, Documents, Search, Analysis, Commits, Logs) + File/Tools/Settings/Help menus + status bar.
- Bundled Lucene modules: core, codecs, **backward-codecs** (reads older index formats), analysis-common, queries, queryparser, misc, highlighter, suggest, and analysis icu, kuromoji, morfologik, nori, opennlp, phonetic, smartcn, stempel → all their tokenizers/filters/char filters are selectable in the analyzer builder.
- Features tagged `[write]` modify the index and are out of scope for a read-only app; they are listed for completeness.

## 2. How Luke organizes features (inspiration only)
- UI-independent "model" per area (overview, documents, search, analysis, commits, tools) in `lucene/luke/src/java/org/apache/lucene/luke/models/` — a good map of the operations behind each screen.
- One global "open index" state; every tab refreshes when an index is opened/closed/reopened.
- One global "current analyzer" (set in the Analysis tab) used by query parsing, More-Like-This and Add Document.
- Tabs link to each other (see §5).

## 3. Opening an index (Open dialog + `IndexUtils.openIndex`)
| Option | Behavior |
|---|---|
| Index path | Walks the path **recursively**; every sub-directory that opens as an index is collected. 1 → `DirectoryReader`; >1 → `MultiReader` ("multi-reader mode"). |
| Directory impl | `FSDirectory` (default), `MMapDirectory`, `NIOFSDirectory`, or any class with a `(Path)` ctor. |
| Read-only | Disables all write operations. |
| No IndexReader | Opens only the `Directory` (for corrupt indexes): only Commits + Check Index work. |
| IndexWriter config | Use compound file format; deletion policy *keep only last commit* vs *keep all commits* — used by every write op. |
- Also: Reopen current index, Close index, Exit. Last **10 paths** kept in `~/.luke.d/history`; opener options + color theme persisted in `~/.luke.d/luke.ini`.
- Status bar icons: read-only, multi-reader, no-reader.

**Capability matrix**
| Feature | Normal | Read-only | Multi-reader | No reader |
|---|---|---|---|---|
| Overview / Documents / Search | ✔ | ✔ | ✔ (some Optional fields empty) | ✘ |
| Commits | ✔ | ✔ | ✘ (tab enabled but empty) | ✔ |
| Analysis, Logs | ✔ | ✔ | ✔ | ✔ |
| `[write]` Add doc / delete by query / optimize | ✔ | ✘ | ✘ | ✘ |
| Check index (+ `[write]` try repair) | ✔ | ✔ | ✘ | ✔ |
| Export terms | ✔ | ✔ | ✔ | ✘ |

## 4. Features by area

### 4.1 Overview tab (`models/overview`)
- Index summary: path, #fields, #docs, #terms (sum over all fields), has deletions / #deleted, optimized? (segment count == 1), index version, **index format** (decoded from `segments_N` header: "Lucene 7.4+", "8.6+", unsupported), directory impl class, current commit point (`segments_N`, generation, segment count), commit user data.
- Field table: field name, term count, % of total — sortable (name/count asc/desc).
- **Top terms** for a selected field: N (default 50, max 1000) highest-docFreq terms via `HighFreqTerms` (rank, docFreq, term); cached per field.
- Context actions on a top term: *Browse docs by this term* → Documents; *Search docs by this term* → Search (TermQuery).

### 4.2 Documents tab (`models/documents`)
- **Browse by doc id**: spinner 0..maxDoc-1; deleted docs show empty.
- **Document view** — one row per (field, value), including fields with no stored value:
  - Field | Flags | Norm | Value. Flags string `IdfpoNPSB#txxVDtxxxxTx/xKxxxx/xxx`:
    `I`+`d/f/p/o` index options · `N` norms · `P` payloads · `S` stored · `B` binary · `#i32/i64/f32/f64…` numeric stored type · `V` term vectors · `D`+`number/binary/sorted/srtnum/srtset` doc values · `T<bytes>/<dims>` points · `K<dim>/<cos|dot|euc|mip>` KNN vectors.
  - Per-field actions: **term vector** (term, freq, positions, offsets), **doc values** (type + values; numeric decodable as long/float/double; bytes as UTF-8 or hex), **stored value** viewer, copy value; copy all/selected values.
- **Term browsing** for a field: First term, Next term, **seek** to arbitrary term (ceil); shows docFreq.
- **Postings browsing** for the current term: First doc / Next doc (with "n-th of docFreq"), displays that document plus **positions table** (position, start-end offsets, payload).
- *More like this* on current doc → Search tab MLT.
- `[write]` *Add document* dialog (only writable, DirectoryReader): table of fields with type choice `TextField, StringField, Int/Long/Float/DoublePoint, SortedDocValuesField, SortedSetDocValuesField, NumericDocValuesField, SortedNumericDocValuesField, StoredField, Field` (expert `Field` gets a full FieldType editor: stored, tokenized, omit norms, index options, term vectors+positions/offsets/payloads, DV type, point dims/bytes), stored flag, value (comma-separated for multi-dim points); uses the Analysis tab's current analyzer; commits and reopens the index; per-type help text.

### 4.3 Search tab (`models/search`)
- Query expression (default `*:*`), **Parse** (shows parsed `Query.toString()`, optional **rewrite**), **Search**, paging (fixed page size 10; prev/next), total hits (approximate ≥1000 unless *exact hits count*).
- *Term query* mode: input `field:term` → raw `TermQuery` (bypasses analysis).
- Results table: doc id, score, `field=v1,v2;` of loaded stored fields. Row actions: **Explain** (tree of `Explanation`), **Show all fields** → Documents.
- `[write]` **Delete Docs** by the current query (confirm dialog; commit + reopen).
- Settings sub-tabs:
  - *Query Parser*: classic `QueryParser` vs `StandardQueryParser`; default field (searchable fields), default operator OR/AND, phrase slop, enable position increments, allow leading wildcard, split on whitespace, auto-generate phrase queries, auto-generate multi-term synonym phrase queries, fuzzy min sim + prefix length, date resolution, locale, time zone, **point range field types** (int/long/float/double per point field; standard parser only).
  - *Analyzer*: shows current analyzer & chain; link to Analysis tab.
  - *Similarity*: BM25 (k1=1.2, b=0.75) or Classic TF-IDF; discount overlaps.
  - *Sort*: primary + secondary sort (field with doc values, type guessed from DV type: INT/LONG/FLOAT/DOUBLE, STRING/STRING_VAL, sorted-set, DOC; asc/desc). Sorting forces exact hit count.
  - *Field Values*: choose stored fields to load (or all).
  - *More Like This*: fields, max/min doc freq, min term freq, analyzer; runs MLT for a doc id.

### 4.4 Analysis tab (`models/analysis`)
- Global "current analyzer" (default `StandardAnalyzer`) used by query parsing, MLT and Add Document.
- **Custom analyzer builder** (`CustomAnalyzer`): pick char filters (ordered list), one tokenizer, token filters (ordered list) from SPI names; edit per-component **parameters**; optional **config dir** (for resource files like stopwords/synonyms); **load external jars** to add custom factories at runtime; show the analysis chain.
- Model also supports instantiating any Analyzer by class name (`createAnalyzerFromClassName`), not prominently exposed in the current UI.
- **Test analyzer** on input text:
  - Simple: token list; double-click → all token attributes (class → key/value via `reflectWith`: term, offsets, position increment/length, type, flags, payload, keyword…).
  - **Step by step** (custom analyzers only): text after each char filter, then a token grid per tokenizer/filter stage.

### 4.5 Commits tab (`models/commits`)
- List commit generations (needs keep-all-commits to see history); per commit: deleted?, segment count, user data.
- Files of the commit (name, size).
- Segments table: name, maxDoc, delCount, delGen, Lucene version, codec, size, compound?
- Segment details: **diagnostics** map, **attributes** map, **codec** breakdown (postings/stored fields/DV/norms/points/term vectors/live docs/field infos/segment info/compound formats).

### 4.6 Logs tab
- In-memory ring buffer of all `java.util.logging` output (Luke + Lucene), level filter (FINEST…SEVERE), copy to clipboard. Error dialogs point here.

### 4.7 Tools menu (`models/tools`)
- `[write]` **Optimize**: `forceMerge(maxSegments)` or *expunge deletes only*; streams IndexWriter infoStream to a log pane.
- **Check index** (read-only): runs `CheckIndex` with streamed output; summary OK / BAD (missing segments, numBadSegments, totLoseDocCount) / tool out of date. `[write]` **Try to repair** (`exorciseIndex`, drops broken segments — destructive, warned).
- **Export terms** (read-only w.r.t. the index) of a field to `terms_<field>_<ts>.out` as `term<delim>docFreq` (delim comma/space/tab).
- `[write]` **Create new index** in an empty dir, optionally populated from the **20 Newsgroups** corpus (sample fields: from, newsgroup(+_sort DV), subject, messageId, date_raw, organization, lines_range point, lines_sort DV, lines_raw, body with term vectors).
- Settings: 4 color themes. Help: About.

## 5. Cross-feature navigation (UX flows worth keeping)
Overview term → Documents (browse postings) or Search (TermQuery) · Documents doc → MLT search · Search hit → Explain / Documents · Search/MLT/AddDoc "Change analyzer" → Analysis · Analysis analyzer → used everywhere.

## 6. Observed limitations & gaps in Luke
- Term counts for **all fields are computed eagerly on open** (full term scan of every field) → slow on big indexes.
- Fixed search page size 10; results only show stored fields; no highlighting (despite the dependency).
- Term-query mode splits on `:` → values containing `:` are truncated.
- No KNN vector inspection/search, no point-value browsing, no per-segment (leaf) view of docs, no deleted-doc inspection.
- Binary stored values shown as raw `BytesRef.toString()`.
- No cancellation of long operations (except export terms).

## 7. Read-only feature checklist
leia has the features of this list except the ones in the backlog of `docs/tasklist.md`.

| Area | Feature |
|---|---|
| Open | path (recursive multi-index), directory impl, no-reader mode, reopen/close, recent-path history |
| Overview | index summary, field/term-count table, top-N terms per field |
| Documents | doc by id with flags/norm/value, term vectors, doc values (+decoding), stored value view/copy, term browse/seek, postings + positions/offsets/payloads |
| Search | parse/rewrite, term query, paging, total hits (exact/approx), explain, query-parser options, similarity (BM25/TF-IDF), sort (2 levels), fields to load, More-Like-This |
| Analysis | custom analyzer builder (char filters/tokenizer/filters + params, config dir, external jars), token attributes, step-by-step |
| Commits | commit list, files, segments, diagnostics/attributes/codec formats |
| Tools | check index, export terms |
| Misc | logs with level filter, cross-tab navigation, themes, about |

## 8. Key source references
- Models: `lucene/luke/src/java/org/apache/lucene/luke/models/{overview,documents,search,analysis,commits,tools}/*.java`, `models/util/IndexUtils.java`
- State (under `lucene/luke/src/java/org/apache/lucene/luke/`): `app/IndexHandler.java`, `app/DirectoryHandler.java`, `app/LukeState.java`, `app/desktop/PreferencesImpl.java`
- UI: `app/desktop/components/*PanelProvider.java`, `components/dialog/**`, `components/fragments/search/*`
- Labels/help texts: `lucene/luke/src/resources/org/apache/lucene/luke/app/desktop/messages/messages.properties`
- Tests: `lucene/luke/src/test/org/apache/lucene/luke/models/**`
