# ADR 0066 — Runtime interface 16: doc-values, standard and More-Like-This queries

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0049 §4 (the runtime interface); ADR 0058 §4 (the parts of search); ADR 0065 §6 (the 2a rows of the parity table are done, and step 2d covers the standard parser's dates too)

## Context

Step 2 of the Distribution lane (ADR 0065) adds to the runtime interface what only leia-core has, so that step 3 can send every index to a runtime without losing features. `Lucene10ParityTest` showed that a runtime refuses three query kinds with `NOT_SUPPORTED`: `standard`, `moreLikeThis` and `docValues`. They are also the three open parts of "Search beyond classic on 9.12" (ADR 0064 §Context), so indexes created by Lucene 8.x should get them in the same step.

The user settled two things: one step, so one ADR, one `API_VERSION` bump and three groups in one commit; and 9.12 and 10 only, with 7.7 and 5.5 left on the tasklist.

Lucene 9.12.3 has what core uses: `newSlowExactQuery`, `newSlowSetQuery` and `newSlowRangeQuery` of the four doc-values fields (checked with `javap`), the `StandardQueryParser` with `PointsConfig`, and `MoreLikeThis` in lucene-queries, which lucene-queryparser brings along. So the 9.12 part is a port, as for ADR 0063 and ADR 0064.

## Decision

1. **Three optional groups in `leia-runtime`,** as `Highlight` and `Scoring` are, each with JDK types of its own. Their names keep clear of Lucene's `MoreLikeThis` and core's `DocValuesQueries`.
   - `DocValuesSearch`: `numbers(field, NumberEncoding, Match<String>)`, `bytes(field, Match<byte[]>)` and `prefix(field, byte[])`, each a `Search.ParsedQuery`; the enum `NumberEncoding` (core's five `DocValuesNumber`s) and the sealed `Match<T>` with `Values` (refusing no values with "no values to match") and `Range`. A wrong field type, a value that isn't a number of the encoding, or NaN is an `IllegalArgumentException` with core's message.
   - `StandardSearch`: `parse(expression, Options, Map<String, PointType>, AnalyzerSpec)`. `Options` has the classic parser's options without `autoGeneratePhraseQueries`, which the standard parser doesn't take. There are no dates, locale or time zone, as for the classic parser: range bounds stay text. *Amended (ADR 0069):* `Options` has `Search.Dates`, as the classic parser's options have.
   - `MoreLikeThisSearch`: `like(Source, Options, AnalyzerSpec)`, with the sealed `Source` (`Doc(docId)`, `Text(text)`), `Options`, whose compact constructor runs core's `MoreLikeThisOptions` checks with the same messages (its `MAX_QUERY_TERMS` is Lucene's default maximum clause count, 1024), and `LikeTerm(field, term, tf, docFreq, idf, score)`.
2. **A More-Like-This query holds a spec, not an analyzer.** Wherever it is used (search, matches, explain, `Scoring` and `Highlight`) the runtime builds the analyzer from the spec, builds the `MoreLikeThis` query from the reader it uses, and closes the analyzer afterwards; core builds it in the search too (ADR 0013). `like` builds the analyzer once, so that one the runtime can't build fails at once. Its `toString` is core's: `like(doc 0, fields [body], …)`.
3. **The terms it chose come back.** `Search.SearchResults` gains `Optional<List<LikeTerm>> likeTerms`, worked out before the rewrite as core's `Searcher` does. A five-argument constructor leaves it empty, so 3.6, 5.5 and 7.7 stay as they are (the trick of `Hit`, ADR 0064 §1).
4. **9.12 copies core** (ADR 0050 §1): `Lucene9DocValuesQueries` (core's `DocValuesQueries`, `DocValuesNumber` and `BinaryDocValuesQuery`, whose weight gives its scorer itself on 9.12), `Lucene9StandardParser` (core's `Queries.standard` with its number formats and syntax error, but Lucene's own processor of term ranges, which without a date resolution leaves the bounds text exactly as core's does), `Lucene9MoreLikeQuery` and `Lucene9LikeTerms`. Its progress is asked between fields while the like terms are counted. 3.6, 5.5 and 7.7 return empty groups.
5. **leia-lucene10 delegates to core** (ADR 0065 §3): `DocValuesQueries`, `Queries.standard` with the five-argument `ParserOptions`, and `Queries.moreLikeThis`/`moreLikeText` through a `Lucene10Like` query that builds the analyzer for each use. Core's `Searcher.built` is private, and the highlighter needs the built query, so core gains a public `Queries.built(IndexReader, Query)`, which `Searcher.built` calls.
6. **The server checks first, with Lucene 10's errors.** `server.runtime.RuntimeQueries` does what `SearchQueries` does against `RuntimeIndex.fields()`, with the same codes and messages: the default and point fields exist and fit (`pointDimensionCount` 1, `pointNumBytes` of the type), no point field twice; for More-Like-This exactly one of document and text, the document id, the text's length, indexed fields and the options; for doc values the field, exactly one of values, range and prefix, no prefix with a number, no binary number, hex decoded. `RuntimeResults.parsedQuery` serves `startSearch`, `startFilter`, `startExplain` and `startHighlight`, so the three kinds reach all four, and refuses a kind whose group is missing with `NOT_SUPPORTED`.
7. **Three features.** `DOC_VALUES_SEARCH`, `STANDARD_SEARCH` and `MORE_LIKE_THIS`, after `SCORING`, where the runtime has the group and searches. Lucene 10 has them through `Feature.all()`. The TUI gates each on `SEARCH ||` the part (ADR 0058 §4): the standard, like and like-text modes, the doc-values mode (with `DOC_VALUES` too), and on the documents screen `s` (search value, with `DOC_VALUES`) and `m` (similar).
8. **Runtime interface 16.** `LuceneRuntime.API_VERSION` is 16.

## Alternatives considered

- **One step per part,** as ADR 0064 did. Rejected by the user: three bumps of the interface for one lane step.
- **Hold an analyzer in the More-Like-This query.** The runtime's analyzers aren't leia's to close, and a query built once could be searched many times. A spec is data, built and closed per use.
- **Dates in the standard parser now.** They need the date options in the interface for the classic parser too; they belong to step 2d, which now covers both parsers.

## Consequences

- On `lucene8` and `lucene8to9`, `m` on the search screen cycles through standard, doc values, like and like text; the documents screen's `s` and `m` work; filters, explanations and highlights take the new kinds.
- `Lucene10ParityTest` no longer expects differences for the three kinds, and compares them in filters, explanations and highlights too. Its `openIndex` difference stays until step 3: `SEARCH` lacks the parts of steps 2b and 2d, and `DOC_VALUES` follows the runtimes' rule.
- A fix to one of these queries belongs in core, its 9.12 copy and `RuntimeQueries`.
- 7.7 and 5.5 stay without them; the tasklist has the item.
