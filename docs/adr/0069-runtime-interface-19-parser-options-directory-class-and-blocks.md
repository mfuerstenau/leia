# ADR 0069 — Runtime interface 19: parser options, directory class and blocks

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0065 §6 (the 2d rows are done); ADR 0066 §1 (`StandardSearch.Options` has dates); ADR 0026 (dates on the runtimes); ADR 0049 §4 (the runtime interface)

## Context

This is step 2d of the Distribution lane (ADR 0065 §4), the last part of step 2. Before step 3 routes every index to a runtime, the interface has to carry what only leia-core has. Otherwise those features are lost at the switch. `Lucene10ParityTest` still expected four differences, and two more compared equal only because the test indexes hid them:

- `getIndexStats`: `RuntimeIndex.Stats` didn't name the `Directory` class, so the server reported the kind (`FS`) instead of, say, `MMapDirectory`.
- The classic parser: `Search.ParserOptions` had no `fuzzyMaxEdits`, `splitOnWhitespace`, date resolution, locale or time zone (ADR 0011, ADR 0026). `StandardSearch.Options` had no dates either (ADR 0066).
- `hasBlocks`: `Commits.Segment` lacked it, so the server always reported false.

On the way, a bug turned up: 7.7 and 9.12 refused every query with `autoGeneratePhraseQueries`. Their `QueryParser.setAutoGeneratePhraseQueries(true)` throws "disallowed when getSplitOnWhitespace() == false", and the runtimes never set split, which defaults to false since 7.0.

## Decision

1. **Runtime interface 19.** `LuceneRuntime.API_VERSION` is 19.
   - `Search.DateResolution` (YEAR to MILLISECOND), as Lucene's `DateTools.Resolution`.
   - `Search.Dates(resolution, fields, locale, timeZone)`, with `Dates.NONE`. `Locale` and `ZoneId` are JDK types, as the interface requires. `resolution(field)` gives a field's own resolution, or else the default.
   - `Search.ParserOptions` gains `fuzzyMaxEdits`, `splitOnWhitespace` and `dates`. The 7-argument constructor stays, with 2 edits, split as `autoGeneratePhraseQueries`, and `Dates.NONE`, so the tests that don't care don't change (the `Preset` precedent).
   - `StandardSearch.Options` gains `dates`, with the 6-argument constructor kept.
   - `RuntimeIndex.Stats` gains `directoryClass`: the simple class name of the first directory's `Directory`, as core's `IndexStats.directoryImpl`. Every runtime can report it, so there is no overload.
   - `Commits.Segment` gains `hasBlocks` as its last component. The 20-argument constructor stays as an overload with false; the older overloads chain through it.
2. **Every runtime honours the options its Lucene has.**

   | Runtime | Fuzzy edits | Split | Classic dates | Standard dates | `hasBlocks` |
   |---|---|---|---|---|---|
   | 10 | passed to core's `ClassicOptions` | core | core's `ParserOptions` | core | core's value |
   | 9.12 | `setFuzzyMinSim` | `setSplitOnWhitespace`, before auto phrases | `Lucene9DateRangeQueryParser` | `Lucene9StandardParser` with a copy of core's processor of term ranges | `getHasBlocks()` |
   | 7.7 | `setFuzzyMinSim` | `setSplitOnWhitespace`, before auto phrases | `Lucene7DateRangeQueryParser` | – | false |
   | 5.5 | `setFuzzyMinSim` (edits since 4.0) | ignored: always splits | `Lucene5DateRangeQueryParser`, which lowercases the bounds first, as 5.5's `getRangeQuery` does | – | false |
   | 3.6 | ignored: a minimum similarity of 0.5 | ignored: always splits | `Lucene3DateRangeQueryParser` | – | false |

   The date parsers are copies of core's `DateRangeQueryParser`, each checked against its own Lucene's `getRangeQuery` (ADR 0050 §1). What else that method does stays: the lowercasing of `lowercaseExpandedTerms` on 3.6 and 5.5. 3.6's parser has no time zone, so its subclass holds the zone itself, the JVM's default without one. 3.6 also turns a date without a resolution into a term of the deprecated `DateField`, which indexes before Lucene 1.9 used; that stays too, since it is what 3.6 does with those indexes.
3. **`support.DateRanges`.** The date format and the end of an inclusive day need no Lucene and are the same in every runtime, so they go to `leia-runtime`'s `support` package (ADR 0050 §2, §4), a copy of core's `DateRanges`. Only the subclasses stay copies.
4. **The server checks on both paths in the same words.** `search.ParserChecks`, JDK and protocol types only as `KnnChecks` is, takes from `SearchQueries`:
   - `locale(tag)` and `timeZone(id)`, with their messages;
   - the "listed twice" check of the field date resolutions, given the path's own field check;
   - the `fuzzyMaxEdits` range (0 to 2) and "`autoGeneratePhraseQueries` needs `splitOnWhitespace`", in `ClassicOptions`' words.

   `SearchQueries` maps the result to `DateTools` and `ClassicOptions` as before. On a runtime, `RuntimeResults.parse` and `RuntimeQueries.standard` check through it too; before, they didn't check at all. A refused option is `INVALID_PARAMS`, an unknown field of a date resolution `FIELD_NOT_FOUND`, on both paths.
5. **The server passes them on.** `RuntimeResults.parse` passes the fuzzy edits, split and dates, `RuntimeQueries.standard` the dates, `indexStats` reports `stats.directoryClass()`, and the segments of `getCommit` carry `hasBlocks`.
6. **No change to `features()`.** Only leia-core reports `SEARCH`; whether it is still needed is a question for step 3. The TUI already gates each part on `SEARCH ||` that part.

## Alternatives considered

- **Leave the options out where a Lucene can't do them,** with a feature for each. A runtime would then refuse a query that it can run with a small difference. Ignoring them, as `consumeAllTokens` is ignored on 3.6, and saying so in `docs/runtimes.md` keeps the search screen the same everywhere.
- **Copy `DateRanges` into each runtime** as the parsers are copied. It has no Lucene types, so `support` holds it once (ADR 0050 §4).
- **Check the options in each runtime.** The messages would drift, and the core path checks in the server already.

## Consequences

- `Lucene10ParityTest` expects only `openIndex` to differ, until step 3. It compares the fuzzy edits, split with shingles (where it changes the query), auto-generated phrases, and the classic and standard parsers' dates with a field's own resolution, on `demo`'s `published`.
- Each runtime's index test checks the dates of a range (a field's resolution and the default one, the locale's short dates in a zone, the end of an inclusive day), the fuzzy edits, the directory class, and on 7.7, 9.12 and 10 auto-generated phrases; 9.12 and 10 read `hasBlocks` from a segment written with a block. `Lucene8BackendTest` checks the options and their refusals through the server on 9.12.
- 7.7 and 9.12 run queries with auto-generated phrases again.
- A runtime built against interface 18 is refused, as for every bump.
