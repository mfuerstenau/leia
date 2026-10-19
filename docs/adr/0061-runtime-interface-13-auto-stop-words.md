# ADR 0061 — Runtime interface 13: auto stop words

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0039 §4 (auto stop words on an index that a runtime reads); ADR 0045 ("left for later": auto stop words on 3.6); ADR 0049 §4 (the runtime interface); ADR 0044 §4 (the features of an index)

## Context

`startAutoStopWords` (ADR 0039) worked only on indexes that Lucene 10 reads. On an index that a runtime reads (3.6, 5.5, 7.7, 9.12), three things refused it:

- `StartAutoStopWordsHandler` asked `CurrentIndex.readable()`, which refuses with `NOT_SUPPORTED`.
- `RuntimeResults.features` never reported `AUTO_STOP_WORDS`, so the TUI greyed out `w`.
- `RuntimeAnalyzers.spec` refused an analyzer that carries `autoStopWords`, so a search couldn't use the words even if they had been found.

Finding the words needs nothing but the terms of a field with their `docFreq`, which every runtime's `Terms.terms` gives page by page (export terms reads them so, ADR 0022). Applying them needs a `StopFilter` after the analyzer's chain, which each runtime's Lucene has.

## Decision

1. **`AnalyzerSpec.AutoStopWords(analyzer, words)` in `Search`.** The tokens of each field in `words` lose those words, case-sensitive, after the analyzer's whole chain, as Lucene's `QueryAutoStopWordAnalyzer` and leia-core's `AutoStopWordsAnalyzer` do. Each runtime builds it with a package-private wrapper copied from core (ADR 0050):
   - 5.5, 7.7 and 9.12: `Lucene{5,7,9}AutoStopWordsAnalyzer extends AnalyzerWrapper` with `PER_FIELD_REUSE_STRATEGY`, which closes the analyzer it wraps. They differ in the packages of `CharArraySet` and `StopFilter` (5.5's are in `analysis.util` and `analysis.core`) and in the source of the components (`getTokenizer` before 8.0, `getSource` from it).
   - 3.6 has no `AnalyzerWrapper`: `Lucene3AutoStopWordsAnalyzer` is a `final` subclass of `Analyzer` whose `tokenStream` puts a `StopFilter` of `Version.LUCENE_36` over the delegate's stream for a field with words. It is final because 3.6 asserts that an analyzer's `tokenStream` and `reusableTokenStream` are final or its class is.
   - `RuntimeAnalyzers.spec` wraps the whole spec, per-field analyzers included, as `AnalyzerSpecs` does on Lucene 10, and refuses two sets of words for one field with `INVALID_PARAMS`.
2. **The scan pages through `Terms.terms`; no runtime method of its own.** `AutoStopWords.find(fields → TermSource, maxDocFreq, maxWords, progress)` in leia-core is the loop of ADR 0039 §4, moved out of `ReadOnlyIndex`: the fields in the order of their names, a term with `docFreq > maxDocFreq` as UTF-8, `TooManyWordsException` above `maxWords`, a progress report every 1024 terms and at the end. `ReadOnlyIndex.autoStopWords` passes it `TermExport.terms` per field; `RuntimeResults.startAutoStopWords` passes `RuntimeResults.pages` over `Terms.terms`. `docFreq` counts deleted documents on every runtime, as in Lucene. On 3.6 a term page counts `totalTermFreq` from the postings, so the scan reads every posting, as export terms does; it is a job that can be cancelled, so that is accepted. `Threshold.maxDocFreq(numDocs)` resolves the threshold for both.
3. **The server.** `StartAutoStopWordsHandler` checks the threshold, then sends an index that a runtime reads to `RuntimeResults.startAutoStopWords`: a field that is missing or not indexed fails with `FIELD_NOT_FOUND`; without fields every indexed field is scanned. The job, "auto stop words of … with Lucene x", reads `numDocs` for the threshold inside the job, not on the backend thread. Both paths map the outcome with `StartAutoStopWordsHandler.found`: too many words is `INVALID_PARAMS`, a read failure `INDEX_NOT_READABLE`.
4. **`AUTO_STOP_WORDS` with the terms group,** which every runtime has, the 2.4 index included. The TUI needs no change: `w` and the search already follow the feature and send `AnalysisSettings` as they are.
5. **Runtime interface 13.** `LuceneRuntime.API_VERSION` is 13, so a runtime jar built for interface 12 is refused (ADR 0044).

## Alternatives considered

- **Fold the words into `Named`'s stop words.** No interface change, but that filter runs before stemming (`run` wouldn't drop `running`), only analyzers that take stop words have it, and it replaces the analyzer's default words. Rejected: the result would differ from Lucene 10's.
- **A scan method on the runtime** (`Terms.autoStopWords`). It would save 3.6 the postings, but each runtime would copy the loop, the threshold and the limit, and the result would need records of its own. Rejected for one loop over pages that every runtime already serves.

## Consequences

- `w` works on every index with a reader, and a search there drops the words found.
- `docs/runtimes.md` has ✓ in every column of the `AUTO_STOP_WORDS` row; its differences note that 3.6 reads every posting for the scan.
- A fix to the scan belongs in `AutoStopWords.find`; a fix to the filter in core's `AutoStopWordsAnalyzer` and the four runtime copies.
