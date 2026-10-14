# ADR 0038 — Collation and Japanese completion presets

- **Status:** Accepted
- **Date:** 2026-10-11
- **Amends:** ADR 0023 (two of the analyzers it deferred)

## Context

ADR 0023 deferred four analyzers. Two of them are presets with a constructor that the preset scan doesn't take:

- **The collation analyzers.**
  - `org.apache.lucene.collation.CollationKeyAnalyzer(java.text.Collator)` is in analysis-common, which leia bundles.
  - `org.apache.lucene.analysis.icu.ICUCollationKeyAnalyzer(com.ibm.icu.text.Collator)` is in the ICU module, an extension jar (ADR 0016).

  Neither has another constructor. Each indexes one token per value, the whole text, whose bytes (`TermToBytesRefAttribute`) are the collator's collation key and not the text. A range query on such a field only works with the same collator. So to search the field, leia needs the collator the application used.
- **Collators.**
  - The JDK's `Collator.getInstance(Locale)` takes a locale, `setStrength` takes `PRIMARY`, `SECONDARY`, `TERTIARY` or `IDENTICAL`, and `setDecomposition` takes `NO_DECOMPOSITION`, `CANONICAL_DECOMPOSITION` or `FULL_DECOMPOSITION`.
  - ICU's `Collator.getInstance(ULocale)` also has `QUATERNARY`, but no full decomposition. `ULocale.forLanguageTag` reads the `-u-` keywords of a BCP 47 tag, such as `de-u-co-phonebk` (phone book order) or `de-u-ks-level1` (primary strength).
- **`JapaneseCompletionAnalyzer`** is listed as a preset through its no-argument constructor, which means no user dictionary and `Mode.INDEX`. Its other constructor is `(UserDictionary, JapaneseCompletionFilter.Mode)`, with `INDEX` (romanize for the index) and `QUERY` (romanize half-typed input as well, for a query). An application that suggests with it uses `QUERY` at query time, so the mode changes what leia should analyze.

## Decision

1. **A collation analyzer is a preset.** The scan also takes a class whose only usable constructor is a public `(java.text.Collator)` or `(com.ibm.icu.text.Collator)` one. ICU's type is found by name through the analyzer class's own loader, as for Kuromoji and Nori (ADR 0023 §5). Such a preset takes no word sets, and always gets a collator.
2. **`preset` gains `collation?`:** `Collation {locale, strength?, decomposition?}`.
   - `locale` is a BCP 47 language tag, and `""` is the root locale. A tag that isn't well-formed is `INVALID_ANALYZER`. ICU reads its `-u-` keywords; the JDK reads the locale only.
   - `strength` is `PRIMARY`, `SECONDARY`, `TERTIARY`, `QUATERNARY` or `IDENTICAL`.
   - `decomposition` is `NO`, `CANONICAL` or `FULL`.
   - `QUATERNARY` with the JDK's collator, or `FULL` with ICU's, is `INVALID_ANALYZER`.
   - An absent field is the collator's default.
   - An absent `collation` is the root locale with the collator's defaults. It is deterministic, unlike the JVM's default locale, and the option never becomes required.
   - The server builds the collator that the constructor takes. For ICU, it reads the constants from the collator class's static fields.
3. **`preset` gains `japaneseCompletion?`:** `JapaneseCompletionOptions {userDictionary?, mode?}`, only for `org.apache.lucene.analysis.ja.JapaneseCompletionAnalyzer`. `mode` is `INDEX` or `QUERY`. An absent field is what the no-argument constructor uses: no user dictionary, and `INDEX`. The user dictionary is read as for `JapaneseAnalyzer` (ADR 0023 §5).
4. **`PresetOption` gains `COLLATION` and `JAPANESE_COMPLETION`.** As before, a preset given an option it doesn't take is `INVALID_ANALYZER`.
5. **Nothing new in `analyze`.** The collation key already shows as the token's `bytes` attribute, beside the text as its term.

## Alternatives considered

- **A `collation` alternative of `Analyzer`,** beside standard, preset and custom. Both analyzers would be named by leia instead of found, and a jar's own collation analyzer would be left out. Rejected; the scan finds them like the other presets.
- **One option per collator,** a JDK one and an ICU one. Their settings are the same apart from one strength and one decomposition, which the server checks. Rejected.
- **ICU rules or a `RuleBasedCollator` from rules.** Rare, and the rules are a language of their own. Left out.
- **The JVM's default locale when none is given.** Its keys would depend on the machine. Rejected for the root locale.
- **Collation keys as a token filter in a custom analyzer.** `CollationKeyFilterFactory` was removed from Lucene; the analyzers set an attribute factory, which a custom chain can't. Not possible.

## Consequences

- Protocol: `PresetOption` gains `COLLATION` and `JAPANESE_COMPLETION`, and `PresetAnalyzer` gains `collation` and `japaneseCompletion`, with the new `Collation`, `CollationStrength`, `CollationDecomposition`, `JapaneseCompletionOptions` and `JapaneseCompletionMode`.
- Core: `AnalyzerSpec.Preset` gains the two options. The new `Collations` finds the constructor and builds the collator. `KuromojiNori` builds the completion analyzer. `PresetAnalyzers` lists and checks both options.
- `:leia-core:testAnalysisJars` adds the ICU module (about 14 MB in the build directory), for the jar tests.
- TUI: the `b` popup gets rows for a collation preset (locale, strength, decomposition) and for the completion analyzer (user dictionary, mode). The search screen shows them with the analyzer.
- An application's collated field can now be searched, but only with the collation analyzer for every field. ADR 0039 gives each field an analyzer of its own.
