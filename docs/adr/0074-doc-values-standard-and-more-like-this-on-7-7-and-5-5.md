# ADR 0074 — Doc-values, standard and More-Like-This queries on 7.7 and 5.5

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0066 §4 (3.6 alone returns empty groups) and its last consequence (7.7 and 5.5 have the three kinds)

## Context

ADR 0066 added the optional groups `DocValuesSearch`, `StandardSearch` and `MoreLikeThisSearch` to the runtime interface and built them on 9.12, as copies of leia-core, and on 10. 7.7 and 5.5 returned empty groups, so indexes of Lucene 4.x to 7.x had no doc-values, standard or More-Like-This search, and the tasklist kept the item.

Both runtimes' folders already list lucene-queries and lucene-sandbox, which lucene-queryparser brings along, so they have `MoreLikeThis`, the flexible `StandardQueryParser` and the sandbox's doc-values queries. What differs from 9.12 was checked with `javap` and the sources of 7.7.3 and 5.5.5:

| 9.12 uses | 7.7.3 | 5.5.5 |
|---|---|---|
| `newSlowExactQuery`, `newSlowRangeQuery` of the doc-values fields | ✓ | – : the sandbox's `DocValuesRangeQuery` (an exact match is the range from the value to itself) |
| `newSlowSetQuery` | – : the sandbox's `DocValuesTermsQuery` and `DocValuesNumbersQuery` | as 7.7 |
| `DoublePoint`/`FloatPoint.nextUp`/`nextDown` | ✓ | – : a private copy |
| `Weight` of the `BINARY` query | `createWeight(searcher, needsScores, boost)`, no `QueryVisitor`, `BinaryDocValues` an iterator | `createWeight(searcher, needsScores)`, `BinaryDocValues.get(doc)` by document, `equals` with the boost |
| Standard parser | `PointsConfig`; the processor of term ranges normalizes with the analyzer | no points; expanded terms are lower-cased by an earlier processor, and the processor of term ranges leaves the bounds as they are |
| `MoreLikeThis` idf | over `numDocs()` | over `numDocs()` |
| `MoreLikeThis` term frequency | 1 per token | 1 per token |
| `MoreLikeThis` counts | per field | one map over all the fields; a clause's field is the first with the highest `docFreq` |
| `MoreLikeThis.like(Map)` | analyzes every value with every field, whatever its key | as 7.7 |

## Decision

1. **7.7 and 5.5 copy 9.12** (ADR 0050 §1): `Lucene<N>DocValuesQueries`, `Lucene<N>StandardParser`, `Lucene<N>DateRangeQueryNodeProcessor`, `Lucene<N>MoreLikeQuery` and `Lucene<N>LikeTerms`, adapted only where the table says, with the same messages. Their indexes implement the three groups, build a More-Like-This query before the rewrite in search, matches and explain, and return the like terms with the search's results. The like terms are counted with `() -> progress.accept(0)` as the check, since neither has a `QueryTimeout`; the search itself stops per segment, as before.
2. **Doc-values queries.** 7.7 keeps Lucene's slow exact and range queries and takes the sandbox's for sets. 5.5 takes the sandbox's for all three; they read a `NUMERIC` field as a `SORTED_NUMERIC` one and a `SORTED` field as a `SORTED_SET` one, so the type no longer picks the query. An empty range on 5.5 is a `MatchNoDocsQuery` without a reason, which 5.5's can't take.
3. **The standard parser on 5.5 has no points.** A non-empty points map is an `IllegalArgumentException` ("Lucene 5.5 has no points: …"), which the index reports as an invalid query; the server refuses it first anyway, since no field of a 4.x or 5.x index has `pointDimensionCount` 1. The bounds of a term range that isn't a date stay as the earlier lower-casing left them, as in 5.5's own processor.
4. **5.5's More-Like-This terms follow 5.5.** `Lucene5LikeTerms` counts each term once over all `options.fields()`; a clause's `tf` comes from that count, its `docFreq` from the clause's term, which is in the field `MoreLikeThis` chose, and its idf is `ClassicSimilarity.idf(docFreq, numDocs())`. leia shows what 5.5 searched for, rather than what 9.12 would have.
5. **A text is passed to `like(Map)` once.** 7.7 and 5.5 analyze every value of the map with every field, whatever its key, so 9.12's map of one entry per field would count each term once per field squared. One entry, under the first field, makes each field analyze the text once, which on 7.7 gives 9.12's counts.

## Alternatives considered

- **Count 5.5's terms per field, as 9.12 does.** The terms shown would differ from the clauses 5.5 built and searched for; the like terms exist to explain the query.
- **Pass 9.12's map, one entry per field.** It would reproduce a quirk of the old `MoreLikeThis` that 9.x fixed, and make a text with several fields count its terms several times over.
- **Write leia's own doc-values set queries for 5.5 and 7.7.** The sandbox has them, in the folders already, and leia's own query stays for `BINARY`, which no Lucene has.

## Consequences

- Indexes of Lucene 4.x to 7.x report `DOC_VALUES_SEARCH`, `STANDARD_SEARCH` and `MORE_LIKE_THIS`; on the search screen, `m` cycles through standard, doc values, like and like text, and the documents screen's `s` and `m` work. Highlighting stays 9.12's and 10's.
- `RuntimeSearches.searchesTheQueriesOf912` runs the three kinds through `startSearch`, `startFilter` and `startExplain` on 5.5 and 7.7; `refusesTheQueriesOf912` is left for 3.6.
- A fix to one of these queries belongs in core, its copies on 9.12, 7.7 and 5.5, and `RuntimeQueries`; `docs/runtimes.md` lists where the copies differ.
