# ADR 0075 — Custom analyzers, shingles and preset options on 7.7 and 5.5

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0067 §7 (only 3.6 keeps its reach) and its last consequence (7.7 and 5.5 have them)

## Context

ADR 0067 gave 9.12 what Lucene 10's analyzer spec can do: custom analyzers, shingles and every preset option but Kuromoji's, Nori's and ICU's, as copies of leia-core. 7.7 and 5.5 kept their reach: stop words, the longest token and a token limit; anything else was `NOT_SUPPORTED`.

`javap` on 7.7.3 and 5.5.5 shows every API the 9.12 copies use: `CustomAnalyzer.builder(ResourceLoader)` with `withTokenizer`, `addCharFilter` and `addTokenFilter(String, Map)`; `FilesystemResourceLoader(Path, ResourceLoader)` and `ClasspathResourceLoader(ClassLoader)`; `CollationKeyAnalyzer(Collator)`; the seven-argument `ShingleAnalyzerWrapper`; `DutchAnalyzer(set, set, CharArrayMap)`; and `StandardTokenizer.MAX_TOKEN_LENGTH_LIMIT` of 1048576. What differs:

| 9.12 uses | 7.7.3 | 5.5.5 |
|---|---|---|
| the factories and `FilesystemResourceLoader` in `…analysis` / `…analysis.util`, the resource loaders in `…util` | all in `…analysis.util` | as 7.7 |
| `CharArraySet`, `CharArrayMap`, `StopwordAnalyzerBase` in `…analysis` | ✓ | in `…analysis.util` |
| `ClassicAnalyzer` in `…classic`, `UAX29URLEmailAnalyzer` in `…email` | both in `…analysis.standard` | as 7.7 |
| `WhitespaceAnalyzer(int)` | – | – |
| `StopAnalyzer` without stop words | has English default stop words | as 7.7 |
| the SPI of its own class loader | ✓ | lists built through `Spi.initialize` in `Lucene5Runtime` (ADR 0073 §5) |

## Decision

1. **7.7 and 5.5 copy 9.12** (ADR 0050 §1): `Lucene<N>Presets`, `Lucene<N>CustomAnalyzers` and `Lucene<N>Analyzers`, adapted only where the table says, with the same messages. The preset tables are each runtime's old table plus `CollationKeyAnalyzer`: 43 on 7.7, 42 on 5.5. There is no feature flag for analyzers, and the server already maps both exceptions, so nothing changes in the interface, the protocol, the server or the TUI.
2. **No `WhitespaceAnalyzer(int)`.** The constructor component is gone from the copies, so `WhitespaceAnalyzer` takes no longest token on 7.7 and 5.5.
3. **`StopAnalyzer` keeps its default stop words.** Reflection finds its no-argument constructor, so the stop words are optional, where 9.12 and 10 need them. leia uses what each Lucene has.
4. **Wrong options are now `INVALID_ANALYZER`**, with leia-core's messages ("WhitespaceAnalyzer takes no stop words", "EnglishAnalyzer takes no max token length"), not `NOT_SUPPORTED`. A component or preset the runtime doesn't have is still `NOT_SUPPORTED` ("Lucene 7.7.3 has no token filter 'x'").
5. **5.5's SPI.** The custom builder only looks components up in the lists that `Lucene5Runtime` built through its own loader, and resource files go through an explicit `ClasspathResourceLoader` of the runtime's loader, so no lookup reaches the jar chain's Lucene 10. `Lucene5BackendTest` checks this with the folders loaded as leia loads them; no `Spi.initialize` around the builder is needed.

## Alternatives considered

- **Keep 7.7 and 5.5 at stop words and the longest token.** The APIs are there, and the copies are mechanical; leaving them out kept the search screen's analyzer editor greyed out for indexes of Lucene 4.x to 7.x.
- **Give `StopAnalyzer` no default stop words, as on 9.12 and 10.** It would hide what the Lucene that reads the index does.

## Consequences

- On indexes of Lucene 4.x to 7.x, the search screen's analyzer editor works with custom analyzers, shingles and the preset options, with each runtime's own components.
- 5.5's classic parser always splits on whitespace (ADR 0069), so its shingles only form inside a phrase. `RuntimeSearches.searchesWithTheAnalyzersOf912` shingles a phrase, which gives the same query on 9.12, 7.7 and 5.5, and runs on all three; `searchesWithTheAnalyzersOfAnOldRuntime` is left for 3.6.
- A fix to analyzer building belongs in core and its copies on 9.12, 7.7 and 5.5.
