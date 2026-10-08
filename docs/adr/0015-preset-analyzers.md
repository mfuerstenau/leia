# ADR 0015 — Preset analyzers

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

ADR 0014 left out analyzers given by class name, such as `EnglishAnalyzer` or `WhitespaceAnalyzer`. Luke's model has `createAnalyzerFromClassName`: `Class.forName`, then the public no-argument constructor. Its Analysis tab used to list these analyzers as "presets", next to the custom builder, but it no longer shows them. Most indexes are built with one of these analyzers, not with `StandardAnalyzer` or a custom chain, so a query that should match them has to be parsed with the right one.

The user shouldn't have to type a class name, so the server has to find the analyzers. Lucene has no registry of `Analyzer` classes as it has for factories (SPI), and the JDK can't list the subclasses of a class. The classes have to be found by reading the jars.

What `lucene-core` and `lucene-analysis-common` 10.5.2 offer: 47 public, concrete `Analyzer` classes with a public no-argument constructor. They include the language analyzers (`EnglishAnalyzer`, `GermanAnalyzer`, …), `WhitespaceAnalyzer`, `SimpleAnalyzer`, `KeywordAnalyzer`, `ClassicAnalyzer`, `UAX29URLEmailAnalyzer` and `CJKAnalyzer`. Others, such as `StopAnalyzer`, `PerFieldAnalyzerWrapper` and `LimitTokenCountAnalyzer`, need constructor arguments, and `CustomAnalyzer` is built by its builder.

## Decision

1. **`preset {className}` is a third alternative of the `Analyzer` DTO,** next to `standard` and `custom`. Exactly one is present (`INVALID_PARAMS` otherwise). `className` is the analyzer's fully qualified class name. The server creates the analyzer with its public no-argument constructor, so it has its default stop words and settings.
2. **`getAnalysisComponents` lists the presets** as `analyzers: [{name, className}]`. `name` is the simple class name, and the list is sorted by it, ignoring case.
3. **The server finds the presets in the jars that analysis comes from.** These are the jar of Lucene's `Analyzer` class and the jars of every char filter, tokenizer and token filter factory registered through SPI. In the fat jar, that is the fat jar itself. Of the classes whose simple name contains `Analyzer`, it keeps every top-level class that is a public, concrete `Analyzer` with a public no-argument constructor. It loads each class without initializing it. A class that can't be loaded, such as one whose dependencies are missing, is skipped. The scan runs once, when the presets are first needed.
4. **Only a listed class can be a preset.** Any other name fails with `INVALID_ANALYZER`, so a request can't make the server load or create an arbitrary class. A constructor that throws also fails with `INVALID_ANALYZER` and its message.
5. **`analyze` shows a preset as one stage,** named by the simple class name, as for `standard` (ADR 0014). An analyzer that isn't a `CustomAnalyzer` doesn't give back its components, so there is no step-by-step view.
6. **`standard` stays.** `StandardAnalyzer` is also a preset, but `standard` is the default that needs no list, and the fixtures and the TUI keep it.

## Alternatives considered

- **Any class name, loaded with `Class.forName`** as Luke does it. Too open, because a request could make the server load any class on the class path, and the user would have to know the names. Rejected (decision 4).
- **A list of analyzers written into leia.** It goes stale with every Lucene release and misses analyzers that a later analysis module or external jar brings. Rejected.
- **The whole class path** instead of the analysis jars. In the fat jar, they are the same. In tests and development, the class path also holds the test libraries. Rejected: the analysis jars are what the presets are about.
- **A library that scans the class path,** such as ClassGraph. It is one more dependency for a scan of a few hundred entries. Rejected.
- **Constructor arguments,** such as stop words or a `Version`. These need a parameter model per class. Deferred: a custom analyzer covers most of them.

## Consequences

- `Analyzer` gains `preset`, and `GetAnalysisComponentsResult` gains `analyzers`, on both sides, with fixtures (`analyze.request-preset.json`).
- The TUI's analyzer popup switches between standard, preset and custom. A preset is picked from a filtered list of the analyzers. The search screen sends and shows the preset as it does a custom analyzer.
- External jars (still deferred) only have to be in the jars that are scanned to add presets of their own.
