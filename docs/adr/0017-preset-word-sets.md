# ADR 0017 — Stop words and stem exclusions for presets

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

ADR 0015 creates a preset analyzer with its public no-argument constructor, so it always has its default stop words. It deferred constructor arguments. Many indexes are built with a language analyzer and stop words of their own, with no stop words at all, or with words kept from stemming. Rebuilding such an analyzer as a custom chain means knowing its exact chain, such as `EnglishAnalyzer`'s possessive filter, keyword marker and Porter stemmer.

The public constructors of the 61 public, concrete `Analyzer` classes in `lucene-core`, `lucene-analysis-common` and the eight other analysis modules (10.5.2) take about 190 parameters:

- **`CharArraySet`, about 125 of them.** All 46 analyzers that take one have `(CharArraySet stopwords)`. 38 also have `(CharArraySet stopwords, CharArraySet stemExclusionSet)`. These are Lucene's names. `StopAnalyzer` has no constructor without arguments, so ADR 0015 doesn't list it.
- **The rest** is a long tail:
  - `int` (12), such as `WhitespaceAnalyzer(maxTokenLength)` and `ShingleAnalyzerWrapper`'s sizes;
  - `boolean` (5), such as `SmartChineseAnalyzer(useDefaultStopWords)`;
  - stop words as a `Reader` or a `Path`;
  - Kuromoji's and Nori's modes, which come with a `UserDictionary`;
  - an `Analyzer` to wrap (`LimitTokenCountAnalyzer`, `ShingleAnalyzerWrapper`, `PerFieldAnalyzerWrapper`);
  - an `IndexReader` (`QueryAutoStopWordAnalyzer`) and a `Collator` (the collation analyzers).

Lucene isn't compiled with `-parameters`, so reflection gives the parameters' types but not their names.

The default stop words of an analyzer are `getStopwordSet()` of an instance for a `StopwordAnalyzerBase`. `DutchAnalyzer` and `SmartChineseAnalyzer` are not one, but both have the static `getDefaultStopSet()` that most language analyzers have. Between the two, every analyzer with `(stopwords, stemExclusionSet)` is covered.

## Decision

1. **`preset` gains `stopwords?` and `stemExclusion?`,** each a `WordSet`. Without them, a preset is what it was (ADR 0015).
2. **A `WordSet` is `words` or `file`,** and exactly one is present (`INVALID_PARAMS` otherwise):
   - `words: [string]` is the words themselves. An empty list is a set without words, so `stopwords: {words: []}` turns stop words off.
   - `file: string` is a path to a word list file that the server reads. It is UTF-8, with one word per line, and lines starting with `#` are skipped (`WordlistLoader.getWordSet`, as `StopwordAnalyzerBase.loadStopwordSet` reads it). A relative path resolves against the server's working directory, which is the TUI's.

   The set is case-sensitive, like the sets that Lucene loads. The analyzers lowercase before they look a word up.
3. **The constructor goes by its parameter types,** which is Lucene's convention:
   - `(CharArraySet)` takes the stop words;
   - `(CharArraySet, CharArraySet)` takes the stop words and the stem exclusions.

   With neither set, the server calls the no-argument constructor. With stop words only, it calls the one-set constructor. With stem exclusions, it calls the two-set constructor, and without stop words it passes the analyzer's default ones: `getStopwordSet()` of a default instance for a `StopwordAnalyzerBase`, otherwise its static `getDefaultStopSet()`.
4. **`getAnalysisComponents` says what each preset takes:** `stopwords` and `stemExclusion` are each `NONE`, `OPTIONAL` or `REQUIRED`.
   - `stopwords` is `OPTIONAL` with a `(CharArraySet)` constructor and a no-argument one, `REQUIRED` with only the first, and `NONE` otherwise.
   - `stemExclusion` is `OPTIONAL` with a `(CharArraySet, CharArraySet)` constructor whose default stop words can be found (decision 3), and `NONE` otherwise. It is never `REQUIRED`.
5. **A class with a public `(CharArraySet)` constructor is a preset too,** even without a no-argument one. This adds `StopAnalyzer`, whose stop words are `REQUIRED`. The other rules of ADR 0015 stay, and only listed classes are presets.
6. **`INVALID_ANALYZER`** if a preset doesn't take a set it was given, its required stop words are missing, or a file can't be read or decoded.

## Alternatives considered

- **Generic constructors:** list each preset's constructors with typed parameters (word set, `int`, `boolean`, enum constants), and let the request pick one with positional arguments. That covers the long tail, but without parameter names the user sees only `EnglishAnalyzer(CharArraySet, CharArraySet)` and has to know which set is which. Rejected.
- **Generic constructors labelled by convention:** the same, but with `CharArraySet` parameters labelled as stop words and stem exclusions. Most coverage, most code, and the long tail stays unlabelled. Rejected for now.
- **Word sets from classpath resources,** such as Lucene's bundled snowball lists, with a `format`. A custom analyzer already reads those through `stop`'s `words` and `format`. Rejected.
- **`ignoreCase` for a set.** Lucene's own sets are case-sensitive, and the analyzers lowercase first. Left out.
- **Stem exclusions without stop words as an error.** The user would have to type the default stop words to keep them. Rejected (decision 3).
- **Deferred:**
  - wrapping analyzers, with an `Analyzer` argument;
  - user dictionaries and modes for Kuromoji and Nori;
  - `maxTokenLength`, through `WhitespaceAnalyzer(int)` or the setters of `StandardAnalyzer` and friends;
  - `DutchAnalyzer`'s stem override dictionary.

## Consequences

- `PresetAnalyzer` gains `stopwords` and `stemExclusion`, there is a new `WordSet`, and `AvailableAnalyzer` gains `stopwords` and `stemExclusion` of the new enum `WordSetSupport`. All of these are on both sides, with fixtures (`analyze.request-preset.json` changes, plus a new `analyze.request-preset-file.json`).
- The TUI's analyzer popup shows a stop words row and a stem exclusions row for a preset that takes them, and the search screen shows the sets with the preset.
- An analyzer from an analysis jar (ADR 0016) that follows the convention gets word sets too. One that takes `CharArraySet` arguments for something else would get them wrong. Lucene's own analyzers all follow it.
