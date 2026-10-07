# ADR 0014 — Analysis on the wire

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

Luke's Analysis tab (`luke-features.md` §4.4) builds an analyzer and shows what it makes of a text:

- **The analyzer** is `StandardAnalyzer` by default, or a `CustomAnalyzer` that the user puts together from Lucene's factories, found by their SPI names: char filters in order, one tokenizer, then token filters in order. Each component has string parameters, and an optional config directory holds resource files such as stop words or synonyms. External jars can add factories.
- **Analysis** lists the tokens with every attribute (`AttributeImpl.reflectWith`). For a custom analyzer, a step-by-step view shows the text after each char filter and the tokens after the tokenizer and each token filter.
- **One global "current analyzer"** set on that tab is also used by query parsing and More-Like-This.

leia's search parses queries and analyzes MLT text with `StandardAnalyzer` (ADR 0010, ADR 0013), which leaves out every index built with another analyzer.

What `CustomAnalyzer` does (read in Lucene 10.5.2, `lucene-analysis-common`):

- `withTokenizer`, `addCharFilter` and `addTokenFilter` look the factory up by name, ignoring case. They throw `IllegalArgumentException` for an unknown name, an unknown or bad parameter, and `IOException` when a `ResourceLoaderAware` factory can't read or parse its resource file.
- `builder(Path)` loads resources from the directory and then from the classpath. It throws `IllegalArgumentException` if the path is not a directory. `builder()` loads from the classpath only.
- `getCharFilterFactories()`, `getTokenizerFactory()` and `getTokenFilterFactories()` give back the factories. These are thread-safe and can create any number of streams.

Several things need deciding:

- Where the current analyzer lives.
- What a token looks like on the wire.
- How the step-by-step view is computed.
- Whether analysis is a job (rule 7).
- How search gets the analyzer.

## Decision

1. **The analyzer is data in every request,** never server state. The `Analyzer` DTO has two alternatives, and exactly one is present (`INVALID_PARAMS` otherwise):
   - `standard {}`: `StandardAnalyzer`;
   - `custom {charFilters, tokenizer, tokenFilters, configDir?}`: a `CustomAnalyzer`. Each component is `{name, params}`, where `name` is the factory's SPI name and `params` is a map of strings, as the factories take them.

   The server builds the analyzer for the request and closes it when it is done. The current analyzer is a TUI concept (rule 6): the TUI holds it, the analysis screen sets it, and the search screen sends it.
2. **`getAnalysisComponents {}`** lists the char filters, tokenizers and token filters that the server can build, as `{name, className}`, sorted by name, ignoring case. The names are what `custom` takes, and the class names are where the parameters are documented.
3. **`analyze {analyzer, text}`** gives `{charFilters, stages}`:
   - `charFilters`: one `{name, text}` per char filter of a custom analyzer, with the text after it and every char filter before it;
   - `stages`: one `{name, tokens, truncated}` for the tokenizer and one for each token filter of a custom analyzer. A standard analyzer has no char filter steps and one stage, `StandardAnalyzer`.

   Neither method needs an open index.
4. **Each stage runs the chain again from the start,** from the factories that the analyzer was built with: the char filters, then the tokenizer, then the token filters up to that stage. That costs one pass per stage over a short text. Unlike Luke, which feeds the tokens it captured from one stage into the next filter, every stage is exactly what the analyzer would make with that chain: each stream is reset, ended and closed as usual. Offsets are offsets into the original text, because the tokenizer corrects them through the char filters.
5. **A token has typed fields and a list of everything else:**
   - `term`;
   - `position`: Lucene's absolute position, which starts at -1 and adds each increment;
   - `positionIncrement` and `positionLength`;
   - `startOffset` and `endOffset`;
   - `type`;
   - `attributes`: every other value that `reflectWith` reports, as `{attribute, key, value}`. `attribute` is the attribute interface's simple name, such as `KeywordAttribute`, and `value` is Lucene's `toString`, such as `[66 6f 78]` for bytes. Values that are `null` are left out.

   The typed fields come from attributes that the server adds to the stream, so they are always there.
6. **`analyze` is a plain method, not a job.**
   - The text is at most `MAX_TEXT_LENGTH` (10,000) UTF-16 code units, as Java counts them (`INVALID_PARAMS` otherwise).
   - A stage stops after `MAX_TOKENS` (10,000) tokens and says so with `truncated`, so an n-gram filter or a broken component can't make the response huge or endless.
   - With these limits, analysis takes milliseconds. Building a custom analyzer reads its resource files, and the user chooses how big they are.
7. **`INVALID_ANALYZER` (-32013)** for an analyzer that can't be built: an unknown component, an unknown or bad parameter, a config directory that is not a directory, or a resource file that can't be read or parsed. The message is Lucene's.
8. **The config directory is only read.** Resource paths are resolved against it, and then against the classpath, as `CustomAnalyzer` does. Without one, they resolve against the classpath only, which has Lucene's bundled word lists.
9. **Search takes the analyzer:** `classic`, `standard` and `moreLikeThis` each gain a required `analyzer`. `term` doesn't analyze, so it has none.
   - The handler builds the analyzer (`INVALID_ANALYZER`).
   - The parsers use it at once. MLT uses it in the job, for stored strings of fields without a term vector.
   - The job closes it when it ends.
10. **Only `lucene-analysis-common`** is added, next to the analysis in `lucene-core`. Luke also bundles ICU, Kuromoji, Nori, SmartCN, Stempel, Morfologik, OpenNLP and phonetic. These are left out for now. ICU alone would add more than 10 MB to the jar.

## Alternatives considered

- **A current analyzer on the server,** as in Luke. Requests would depend on what an earlier request set, fixtures couldn't show what a search parses with, and a server restart would lose it. Rejected (decision 1).
- **Luke's step by step,** which feeds the tokens it captured from one stage to the next filter through a list-backed stream. It skips `end()`, so the final offset and any trailing position increment are lost. A filter also sees only the attributes that were captured, not a live stream. Rejected (decision 4).
- **Analysis as a job** (`startAnalyze` / `getAnalyze`). That means four methods and polling for something that takes milliseconds on a bounded text. Rejected (decision 6).
- **Only the generic attribute list,** as Luke has it. The TUI would have to dig the term, position and offsets out of strings. Rejected.
- **Only typed fields.** Keyword marks, flags, payloads and custom attributes would be lost. Rejected.
- **An analyzer by class name,** such as `EnglishAnalyzer` (Luke's `createAnalyzerFromClassName`). It needs a way to find the analyzer classes, so the user doesn't have to type their names. Deferred: it can come as a third alternative.
- **External jars** with factories of their own. They need a class loader and reloading Lucene's SPI. Deferred to an ADR of its own.
- **Parameters as a list of `{name, value}`.** The factories take a map, and a name can't repeat. Rejected.

## Consequences

- New methods `getAnalysisComponents` and `analyze`, with fixtures. There is a new error code, `INVALID_ANALYZER` (-32013), with an `analyze.error-invalid-analyzer.json` fixture.
- `ClassicQuery`, `StandardQuery` and `MoreLikeThisQuery` gain `analyzer`, and their fixtures change. The TUI and the server ship together (ADR 0004).
- `leia-core` depends on `lucene-analysis-common`. Its factories register through `META-INF/services`, which the fat jar already merges.
- The TUI gets an analysis screen (`6`). It has the current analyzer, a builder popup for custom analyzers, a text line, the stages, and the tokens with their attributes. The search screen shows and sends the current analyzer.
