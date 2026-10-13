# ADR 0011 — Query parser options and the standard query parser

- **Status:** Accepted, amended by ADR 0026 (date resolution, locale and time zone for date ranges)
- **Date:** 2026-10-06

## Context

ADR 0010 runs Lucene's classic `QueryParser` with its defaults. Only the default field and the default operator can be chosen. Luke's query parser settings cover more (`docs/luke-features.md` §4.3, `QueryParserConfig`):

- leading wildcards;
- phrase slop;
- fuzzy minimum similarity and prefix length;
- position increments;
- for the classic parser only: split on whitespace, auto-generated phrase queries and auto-generated multi-term synonym phrase queries;
- date resolution, locale and time zone, for date ranges;
- the standard (flexible) query parser, with a numeric type per point field.

Point fields are the main gap. Without a points-aware parser, `price:[10 TO 20]` becomes a term range over text, which matches nothing on a `DoublePoint` field (ADR 0010, Consequences). The standard query parser builds a `PointRangeQuery` from a range on a field it has a `PointsConfig` for. That config needs the Java type of the field's values. The index does not record it: a 4-byte point is an `int` or a `float`, and an 8-byte point is a `long` or a `double`. The bytes are sortable encodings in both cases, so the values don't reveal the type either.

Several other things need deciding:

- **Fuzzy similarity.** Lucene still calls the setting `fuzzyMinSim`, a float. A value of 1 or more is a maximum edit distance, capped at 2. A value below 1 is a similarity, a deprecated notion that Lucene converts to an edit distance from the term's length. The standard parser has the setter, but its syntax parser writes 2 into every `term~` without a distance (`StandardSyntaxParser.FuzzyOp`), so the setting never takes effect there.
- **Date ranges.** These only matter for fields written with `DateTools`. Their settings depend on a locale and a time zone, which are the server JVM's defaults unless given.
- **Synonym phrases.** Auto-generated multi-term synonym phrase queries only have an effect with an analyzer that produces multi-term synonyms. `StandardAnalyzer` doesn't produce any.

## Decision

1. **A shared `ParserOptions` record.** It holds the options that both parsers have:
   - `allowLeadingWildcard` (boolean);
   - `phraseSlop` (0 or more);
   - `fuzzyPrefixLength` (0 or more);
   - `enablePositionIncrements` (boolean).

   Every option is required, and the TUI starts from Lucene's defaults: `false`, `0`, `0`, `true`. A value out of range is `INVALID_PARAMS`.
2. **The fuzzy setting is a maximum edit distance, and classic-only.** `fuzzyMaxEdits` (0 to 2, default 2) is passed to `setFuzzyMinSim` as is. The deprecated similarity below 1 is left out: it means different edit distances for terms of different lengths, and `term~0.5` in an expression still writes it per term. The standard parser ignores the setting (see Context), so it is not one of its options. An option that does nothing would only mislead.
3. **Classic gets its options.** `classic` becomes `{expression, defaultField, defaultOperator, options, fuzzyMaxEdits, splitOnWhitespace, autoGeneratePhraseQueries}`. The last three are classic-only and start from Lucene's defaults (`2`, `false`, `false`). Lucene allows `autoGeneratePhraseQueries` only with `splitOnWhitespace`; asking for it without is `INVALID_PARAMS`. This changes ADR 0010's `classic` in place: the TUI and the server ship together (ADR 0004), so no old client needs the old shape.
4. **The standard query parser is a third alternative**, `standard {expression, defaultField, defaultOperator, options, points}`. It uses `StandardQueryParser` with `StandardAnalyzer`. `points` lists `{field, type}` pairs, where `type` is `INT`, `LONG`, `FLOAT` or `DOUBLE`. For each pair, the parser gets a `PointsConfig` with that type and a `Locale.ROOT` number format: an integer format for `INT` and `LONG`, a decimal format for `FLOAT` and `DOUBLE`, as in Luke. A range or a single value on such a field becomes a `PointRangeQuery`. A field that is not listed is parsed as text.
5. **The server checks the point types.** A listed field must exist (`FIELD_NOT_FOUND`), and must have one-dimensional points with 4 bytes for `INT`/`FLOAT` or 8 bytes for `LONG`/`DOUBLE` (`INVALID_PARAMS`). A field listed twice is also `INVALID_PARAMS`. This catches a `LONG` mapped onto a 4-byte field. It cannot catch an `INT` mapped onto a `float` field: that query runs, and matches the wrong documents.
6. **The TUI guesses the types, and the user corrects them.** Every one-dimensional 4- or 8-byte point field starts as `INT` or `LONG`. The parser options popup shows each mapping and cycles it: `INT` → `FLOAT` → not mapped for 4 bytes, and `LONG` → `DOUBLE` → not mapped for 8 bytes. The guess is wrong for float and double fields, but a visible guess is easier to fix than an empty list that nobody fills.
7. **Parse errors stay `INVALID_QUERY`.** The standard parser's own message for a syntax error leaves out what it found ("Syntax Error, cannot parse a AND: "). leia builds the message from the exception's token instead, in the classic parser's shape: `Cannot parse 'a AND': Encountered "<EOF>" at column 5.` Other errors keep the first line of their message. That includes a lexical error and a number that a point field's format can't read.
8. **Left out:**
   - date resolution, locale and time zone wait until there is an index with `DateTools` fields to try them on; until then the server JVM's defaults apply, as before;
   - auto-generated multi-term synonym phrase queries wait for the Analysis ADR, since they do nothing with `StandardAnalyzer`;
   - the multi-term rewrite method and the determinize work limit, which Luke doesn't offer either.

## Alternatives considered

- **Optional options, with absence meaning Lucene's default.** That would keep ADR 0010's `classic` shape valid. But the TUI always sends every option, and a fixture with all of them documents the defaults. Rejected: no client needs the old shape.
- **One query alternative with a `parser` enum.** The classic-only booleans and the standard-only `points` would then be fields that some parsers ignore. Rejected for the same reason as ADR 0010's `mode` enum.
- **`fuzzyMinSim` as a float, as in Lucene and Luke.** It mirrors the API, but values below 1 are deprecated. Rejected for `fuzzyMaxEdits`.
- **No guess, only types the user sets.** It can't produce a wrong type, but every point field matches nothing until the user sets its type. Rejected (decision 6).
- **Detecting the type from a stored field or numeric doc values of the same name.** A naming convention is not a rule, and doc values encode floats and doubles as sortable integers too. Rejected.
- **Points on the classic parser,** by overriding `QueryParser.getRangeQuery`. This is what the standard parser is for, and Luke's split is the same. Rejected.

## Consequences

- `startSearch` and `startExplain` take a third query alternative. Their params are otherwise unchanged.
- The fixture harnesses accept named variants, `<method>.request-<case>.json` and `<method>.response-<case>.json`, so one method can have a request fixture for each query alternative.
- The TUI's `m` cycles classic → standard → term. `p` opens the parser options popup in the two parser modes, and applying it searches again.
- A range on a point field matches in standard mode once the field's type is right. Multi-dimensional points, such as `LatLonPoint`, are still out of reach of both parsers.
