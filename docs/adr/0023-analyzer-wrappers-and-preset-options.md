# ADR 0023 — Analyzer wrappers and preset options

- **Status:** Accepted, amended by ADR 0038 (collation and Japanese completion presets) and ADR 0039 (analyzers per field and auto stop words)
- **Date:** 2026-10-07

## Context

ADR 0017 gave presets stop words and stem exclusions. It left the rest of the constructor arguments out, and four of them come up often enough to matter:

- **Wrapping analyzers.** `LimitTokenCountAnalyzer(analyzer, maxTokenCount, consumeAllTokens)` stands in for the old `maxFieldLength`. `ShingleAnalyzerWrapper(analyzer, minShingleSize, maxShingleSize, tokenSeparator, outputUnigrams, outputUnigramsIfNoShingles, fillerToken)` indexes word n-grams. Both take another analyzer, so neither is a preset. A custom analyzer can use the `limitTokenCount` and `shingle` filters, but a preset can't be rebuilt that way without knowing its chain.
- **`maxTokenLength`.** `StandardAnalyzer`, `ClassicAnalyzer` and `UAX29URLEmailAnalyzer` have a `setMaxTokenLength(int)` setter. `WhitespaceAnalyzer` takes the length in an `(int)` constructor, and it is the only analyzer in Lucene 10.5.2 whose single `int` argument means that. Longer tokens are split (standard, UAX29) or dropped (classic), so the length decides which terms an index has.
- **Kuromoji and Nori.** `JapaneseAnalyzer(UserDictionary, Mode, CharArraySet stopwords, Set<String> stoptags)` and `KoreanAnalyzer(UserDictionary, DecompoundMode, Set<POS.Tag> stopTags, boolean outputUnknownUnigrams)` are the only constructors besides the no-argument ones. Their types come from the modules, which are extension jars (ADR 0016), so `leia-core` can't name them at compile time. Both `UserDictionary.open(Reader)` read a user dictionary, and return `null` for one without entries. `JapaneseAnalyzer` has no `(CharArraySet)` constructor, so ADR 0017 lists it without stop words.
- **`DutchAnalyzer`'s stem override dictionary,** the third argument of `(CharArraySet, CharArraySet, CharArrayMap<String>)`. No other analyzer has that constructor. `StemmerOverrideFilterFactory` reads such a dictionary as lines of `word<TAB>stem`, skipping blank lines and lines starting with `#`.

A Lucene analyzer wrapper doesn't close the analyzer it wraps.

## Decision

1. **`Analyzer` gains `shingles?` and `limitTokenCount?`.** They apply to any of its alternatives (standard, preset, custom), which stay exactly one. The server wraps with Lucene's own wrappers, in a fixed order: `LimitTokenCountAnalyzer(ShingleAnalyzerWrapper(analyzer))`. Every field of both is required, because only the wire knows them:
   - `Shingles {minShingleSize, maxShingleSize, tokenSeparator, outputUnigrams, outputUnigramsIfNoShingles, fillerToken}`. Lucene's defaults are 2, 2, `" "`, `true`, `false` and `"_"`. Both sizes must be at least 2, and the minimum at most the maximum.
   - `LimitTokenCount {maxTokenCount, consumeAllTokens}`. `maxTokenCount` must be at least 1.
2. **`analyze` shows each wrapper as one more stage** after the analyzer's own. The stage is named by Lucene's class, `ShingleAnalyzerWrapper` or `LimitTokenCountAnalyzer`, and runs the analyzer with the wrappers up to it. A custom analyzer is still shown step by step before them.
3. **`preset` gains `maxTokenLength?`,** from 1 to 1,048,576 (`StandardTokenizer.MAX_TOKEN_LENGTH_LIMIT`, which `CharTokenizer` enforces too). It goes to a public `setMaxTokenLength(int)` after construction, which is a convention. For `WhitespaceAnalyzer`, named by class, it goes to the `(int)` constructor instead.
4. **`preset` gains `stemOverride?`,** a `StemDictionary` of `entries` (word → stem) or `file`, exactly one, like a `WordSet`. The file is UTF-8, `word<TAB>stem` per line, and blank lines and lines starting with `#` are skipped; a line without a tab, or with an empty word or stem, is `INVALID_ANALYZER`. The dictionary is case-sensitive, like Lucene's. Empty `entries` means no overrides. It goes to a `(CharArraySet, CharArraySet, CharArrayMap)` constructor, which is a convention. Stop words it isn't given are the default ones (as in ADR 0017), and stem exclusions it isn't given are none.
5. **`preset` gains `japanese?` and `korean?`,** each only for its own class, which is named by class name:
   - `japanese {userDictionary?, mode?, stopTags?}` for `org.apache.lucene.analysis.ja.JapaneseAnalyzer`. `mode` is `NORMAL`, `SEARCH` or `EXTENDED`. `stopTags` is a `WordSet` of part-of-speech strings, such as `助詞-格助詞-一般`. They aren't checked.
   - `korean {userDictionary?, decompoundMode?, stopTags?, outputUnknownUnigrams?}` for `org.apache.lucene.analysis.ko.KoreanAnalyzer`. `decompoundMode` is `NONE`, `DISCARD` or `MIXED`. `stopTags` is a `WordSet` of `POS.Tag` names, such as `JX` or `NNP`; a name that isn't one is `INVALID_ANALYZER`.

   An absent field is the analyzer's default: no user dictionary, `SEARCH` or `DISCARD`, the default stop tags, and `false`. `userDictionary` is a UTF-8 file in the module's own format, read with `UserDictionary.open`. The server finds the classes through the extension jars' class loader, by reflection. `JapaneseAnalyzer`'s stop words become `OPTIONAL`: with stop words or `japanese`, the server calls the four-argument constructor.
6. **`AvailableAnalyzer` gains `options`,** a list of `PresetOption`: `MAX_TOKEN_LENGTH`, `STEM_OVERRIDE`, `JAPANESE` and `KOREAN`. These are what the preset takes besides its word sets. A preset given an option it doesn't take is `INVALID_ANALYZER`, and so is a value out of range. A request that isn't well-formed, such as a `StemDictionary` without exactly one alternative, is `INVALID_PARAMS`.
7. **The core closes every layer.** The analyzer built for a request is a `DelegatingAnalyzerWrapper` of leia's own, which knows the base analyzer and each Lucene wrapper, and closes all of them.

## Alternatives considered

- **Wrappers as `Analyzer` alternatives that nest an `Analyzer`.** This matches Lucene in any order and depth, but the DTO becomes recursive, and the TUI would need nested editing. Indexes rarely use both wrappers, and the fixed order covers the common case. Rejected.
- **Shingles and the limit as preset options only.** A custom analyzer has the filters already, but the standard analyzer has nothing. Applying them to every alternative costs nothing more. Rejected.
- **Optional shingle fields with Lucene's defaults on the server.** Either side would have to know the defaults, so this saves the client nothing. Rejected.
- **`maxTokenLength` for any `(int)` constructor.** Only `WhitespaceAnalyzer` means the token length, and a jar's analyzer could mean anything. Rejected; the setter is the convention.
- **`maxTokenLength` on the `standard` alternative.** The preset `StandardAnalyzer` takes it. Left out.
- **Generic constructors** (rejected in ADR 0017) would cover Kuromoji and Nori, but with unnamed parameters of types from another module. Rejected again.
- **Deferred:**
  - `PerFieldAnalyzerWrapper`; *amended by ADR 0039: `Analyzer.fields`*;
  - `QueryAutoStopWordAnalyzer`, which needs an `IndexReader`; *amended by ADR 0039: a job finds the words, and `Analyzer.autoStopWords` carries them*;
  - the collation analyzers, which need a `Collator`; *amended by ADR 0038: presets with a `collation` option*;
  - `JapaneseCompletionAnalyzer`; *amended by ADR 0038: its options, as `japaneseCompletion`*;
  - `SmartChineseAnalyzer(boolean)`, which is already covered: its stop words are `OPTIONAL`.

## Consequences

- Protocol, on both sides, with fixtures:
  - `Analyzer` gains `shingles` and `limitTokenCount`, with the new `Shingles` and `LimitTokenCount`.
  - `PresetAnalyzer` gains `maxTokenLength`, `stemOverride`, `japanese` and `korean`, with the new `StemDictionary`, `JapaneseOptions`, `JapaneseMode`, `KoreanOptions` and `KoreanDecompoundMode`.
  - `AvailableAnalyzer` gains `options`, with the new `PresetOption`.
- Core:
  - `AnalyzerSpec` gains `Wrapped`, and the preset's options;
  - `PresetAnalyzers` finds the options;
  - `KuromojiNori` builds the two analyzers by reflection;
  - `Analyzers.analyze` shows the wrappers as stages.
- `:leia-core:testAnalysisJars` adds Kuromoji and Nori (about 12 MB in the build directory), for the jar tests and the real-server tests.
- TUI: the `b` popup gets:
  - shingle and token limit rows for every kind, each with its fields;
  - for a preset that takes them, rows for the max token length, the stem overrides, and the Japanese or Korean options.

  The search screen shows them with the analyzer.
