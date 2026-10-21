# ADR 0067 — Runtime interface 17: analysis

- **Status:** Accepted
- **Amended by:** ADR 0075 (§7: 7.7 and 5.5 copy 9.12)
- **Date:** 2026-10-14
- **Amends:** ADR 0045 §8 (the analyzer of a runtime's search); ADR 0049 §4 (the runtime interface); ADR 0065 §3 (a named analyzer becomes a preset by class name) and §6 (the 2b rows of the parity table are done, and the row of two presets with the same simple name moves from 2d into 2b)

## Context

This is step 2b of the Distribution lane (ADR 0065 §4). Step 3 will send every index to a runtime, and leia-server will stop compiling against leia-core. Before that, the runtime interface needs everything analysis does today:

- `Lucene10ParityTest` listed three search differences: custom analyzers, shingles, and preset options beyond stop words and the longest token. `AnalyzerSpec.Named` had stop words and the longest token only, and named a preset by its simple name.
- `getAnalysisComponents` and `analyze` were core-only.

The user settled two things:

- **9.12 gets these too, for search.** Custom analyzers, shingles and the preset options work on indexes created by Lucene 8.x. 7.7, 5.5 and 3.6 keep refusing them.
- **Preset identity moves from 2d into 2b.** The spec names a preset by its full class name, as core's does, so two presets with the same simple name are told apart.

The analysis tab doesn't depend on an index, so it stays on Lucene 10. Only `leia-lucene10` has the new group.

## Decision

1. **`Search.AnalyzerSpec` mirrors core's `AnalyzerSpec`.** It stays nested in `Search`, as `Highlight.Options` uses it. The sealed variants are core's: `Standard`, `Preset`, `Custom`, `Wrapped`, `PerField` and `AutoStopWords`. Its nested records and enums are core's too: `WordSet`, `StemDictionary`, `Japanese`, `Korean`, `Collation`, `JapaneseCompletion`, `Component`, `Shingles` and `LimitTokenCount(maxTokenCount, consumeAllTokens)`. All of them are JDK types. `Named` and the `LimitTokenCount` variant are gone.
   - `Preset.className` is Lucene's unrelocated class name, as the protocol gives it. Lucene 10 looks the preset up by that name. A relocated runtime matches its simple name (`Preset.simpleName()`) against a table of its own, since the relocation would rewrite a class name in a string.
   - Word-set files, stem dictionary files, user dictionaries and the config directory are `Path`s. The runtime reads them.
2. **`Search.InvalidAnalyzerException`,** next to `UnsupportedAnalyzerException`. It covers an analyzer that is wrong on any runtime: a bad component or parameter, a file that can't be read, or a size or option out of range. Its messages are core's. `UnsupportedAnalyzerException` stays for what a runtime's Lucene lacks. `Search.parse`, `StandardSearch.parse`, `MoreLikeThisSearch.like` and `Highlight.highlight` declare both.
3. **`Search.analyzers()` is gone.** Only the server used it, to refuse a preset before the runtime saw it. Each runtime's "has no analyzer X; it has …" names what it has.
4. **A new group, `Analysis`, through `LuceneRuntime.analysis()`.** Like `commits()` and `check()`, it doesn't depend on an index:
   - `components()` returns the records of core's `AnalysisComponents`: `Components`, `AvailableAnalyzer`, `Available`, and the enums `WordSetSupport` and `PresetOption`.
   - `analyze(spec, field, text, maxTokens)` returns the records of core's `TextAnalysis`. The runtime builds the analyzer and closes it.

   3.6, 5.5, 7.7 and 9.12 return empty. Their components differ from Lucene 10's, and the tab isn't about an index.
5. **leia-lucene10 delegates to core.** `Lucene10Analyzers.coreSpec` maps the spec one to one, and core's `InvalidAnalyzerException` becomes the runtime's. Its preset table and its names are gone. `Lucene10Analysis` maps `Analyzers.components()` and `Analyzers.analyze`.
6. **9.12 ports core** (ADR 0050 §1):
   - **`Lucene9Presets`** is the class-literal table, with `CollationKeyAnalyzer` added. It finds by reflection what each preset takes, as core's `PresetAnalyzers.preset` does: the word-set and stem-override constructors, `setMaxTokenLength`, `WhitespaceAnalyzer(int)`, `getDefaultStopSet` and the JDK `Collator` constructor. It then checks and creates in core's order, with core's messages, and makes the JDK collator as core's `Collations` does.
     - `StopAnalyzer` has no default stop words on 9.x. It now needs them, as on Lucene 10, instead of getting 7.7's.
     - Japanese, Korean and Japanese-completion options are `UnsupportedAnalyzerException`, since 9.12 has no Kuromoji, Nori or ICU, and the `--jar` modules are Lucene 10's.
   - **`Lucene9CustomAnalyzers`** copies core's `custom`, `builder` and `add` against the relocated `CustomAnalyzer` and its SPI lists, which the convention's `mergeServiceFiles` relocates. Resources are read from the config directory, then through the runtime's own class loader, not `ExtensionJars`. A component name 9.12 doesn't have is `UnsupportedAnalyzerException` ("Lucene 9.12.3 has no token filter 'x'"), since the TUI offers Lucene 10's components.
   - **`Lucene9Analyzers`** is the dispatch, with core's checks and messages for the shingle sizes and the token limit, then `ShingleAnalyzerWrapper` and `LimitTokenCountAnalyzer(…, consumeAllTokens)`. A field's analyzer that is invalid says "field 'x': …", as in core.
7. **3.6 keeps its reach.** *Amended (ADR 0075):* 7.7 and 5.5 copy 9.12, as §6 says.
   - `Standard` and a `Preset` of their table take stop words (words or a file) and the longest token.
   - `Wrapped` with only a token limit works. 3.6 ignores `consumeAllTokens`, since it changes no token.
   - Anything else is `UnsupportedAnalyzerException`, worded as the server worded it before: "shingles", "custom analyzers", "options of X other than stop words and the longest token".
8. **`runtime.support.WordFiles`** reads the word files for 3.6, 5.5, 7.7 and 9.12, with core's messages ("stop words: no file …"). A word list is read as Lucene 10's `WordlistLoader.getWordSet` reads it: each line trimmed, blank lines skipped. A `#` line is a word there, so the old server-side reader, which skipped such lines, differed from Lucene 10. A stem dictionary is read as core reads it: `word<TAB>stem`, with `#` lines skipped.
9. **The server.** *Amended (ADR 0071):* the analysis methods always go to the first runtime with an `Analysis`, and are `NOT_SUPPORTED` without one.
   - `RuntimeAnalyzers.spec(Analyzer)` no longer takes a runtime. It is the twin of `AnalyzerSpecs.spec`, with the same `INVALID_PARAMS` checks and messages. A path that isn't one is `INVALID_ANALYZER` with core's message.
   - `RuntimeAnalyzers.failed` maps `InvalidAnalyzerException` to `INVALID_ANALYZER "Invalid analyzer: …"` and `UnsupportedAnalyzerException` to `NOT_SUPPORTED "Not supported with Lucene X: …"`. It serves `RuntimeResults`, `RuntimeQueries` and `RuntimeHighlights`. The highlight's wrapper through core's `TextHighlighter` (`AnalyzerFailed`) carries either.
   - With `Routing.lucene10Runtime()`, `AnalyzeHandler` and `GetAnalysisComponentsHandler` use the first loaded runtime that has `analysis()` (`Runtimes.analysis()`, mapped by `RuntimeAnalysis`). Otherwise they use core, as before. The text-length check and the "Cannot analyze the text" mapping hold on both paths.
   - `AnalyzerSpecs` stays until step 3.
10. **Runtime interface 17.** `LuceneRuntime.API_VERSION` is 17.

## Alternatives considered

- **Keep `Named` and add options to it.** That keeps the simple-name identity that 2d was to fix, and a second shape beside core's. Mirroring core's spec makes `leia-lucene10` a mapping and the server's two specs twins.
- **One analyzer exception with a kind.** Two checked types let each runtime say which it means, and the server maps each to its code at every call site through one helper.
- **Scan 9.12's jars for presets, as core does.** The relocated runtime has a fixed set of jars, and a class literal survives the relocation where a scanned name has to be mapped back. The table is short and checked by the tests.
- **The analysis tab on 9.12 as well.** It doesn't depend on an index, and Lucene 10 is always there to run it. It isn't needed for step 3.

## Consequences

- `Lucene10ParityTest` no longer expects the three 2b differences. It now compares:
  - searches with a stop-word file, Dutch stem overrides, a `CollationKeyAnalyzer`, a custom analyzer with a char filter and a config directory, and shingles with a token limit;
  - three searches that must fail with `INVALID_ANALYZER` on both backends;
  - `getAnalysisComponents` and `analyze` for each kind of analyzer.

  Its `openIndex` difference names step 2d only.
- On `lucene8` and `lucene8to9`, the search screen's analyzer editor works with custom analyzers, shingles and the preset options. The analysis tab still runs on Lucene 10.
- On a runtime, the analyzer of a highlight is built in the highlight's job, as before. One that is wrong fails that job with `INVALID_ANALYZER`, where leia-core fails `startSearch` or `startHighlight` at once. The parity test highlights with the standard analyzer only.
- A fix to analyzer building belongs in core, its 9.12 copy and, for the spec, in `AnalyzerSpecs` and `RuntimeAnalyzers`.
- 7.7 and 5.5 stay without custom analyzers, shingles and preset options. *Amended (ADR 0075):* they have them now.
