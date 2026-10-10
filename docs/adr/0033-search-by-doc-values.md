# ADR 0033 — Search by doc values

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

Every search so far finds documents through their terms or points (ADR 0010 to ADR 0013, ADR 0028), or by reading their stored values (ADR 0027). Values that exist only as doc values can't be found: the demo index's `price`, `quantity`, `scores`, `weight` and `checksum`, or an application's sort and facet fields, which often have no terms or points at all. The documents screen shows them, but nothing finds the documents with a given value.

Lucene has queries for four of the five doc values types: `newSlowExactQuery`, `newSlowSetQuery` and `newSlowRangeQuery` on `NumericDocValuesField`, `SortedNumericDocValuesField`, `SortedDocValuesField` and `SortedSetDocValuesField`. They read the values of every document that the doc values skipper doesn't rule out. `BINARY` doc values have none, so finding them means reading every document's value.

Several questions are new:

- **A query or a scan job.** A scan of binary values can take long, so by rule 7 it has to be cancellable. The regex over stored values (ADR 0027) became a job of its own, because a match is in a value and has no score or sort.
- **What a number is.** Numeric doc values are opaque longs (ADR 0006, ADR 0012). A double may be stored as its raw bits (`DoubleDocValuesField`) or as a sortable long (`DoubleField`), and the index doesn't record which. Raw bits order negative numbers backwards as signed longs, so a range of raw bits is not a range of numbers.
- **Values on the wire.** Numbers and byte strings, in one query.

## Decision

1. **A fifth `SearchQuery` alternative, `docValues`.** It is a query, not a job of its own:
   - `ExitableDirectoryReader` wraps every doc values getter of the reader a search runs on, so a scan through doc values checks the search's `QueryTimeout` and stops on `cancelJob` (ADR 0010 §8). No thread is interrupted.
   - So the search job covers the scan, and paging, the sort, explain, the stored fields per hit and the filter (ADR 0031) come with it.
   - There are no new methods and no new error codes.
2. **`DocValuesQuery {field, number?, values?, range?}`:**
   - `values` (one value or more) or `range`: exactly one of the two.
   - `range` is `DocValuesRange {lower?, upper?, includeLower, includeUpper}`, where an absent bound is open.
   - **Values are `BytesValue`s.** For a field of bytes, they are text, or hex with `binary`. For a number field, `binary` is false and the text is the number in decimal, which the server parses: `Long.parseLong`, `Double.parseDouble` or `Float.parseFloat`. `Infinity` is a number, NaN is refused.
   - `number` is required for `NUMERIC` and `SORTED_NUMERIC` fields, and refused for the others.
   - A document with several values (`SORTED_NUMERIC`, `SORTED_SET`) matches if any value matches, as in Lucene.
3. **`number` is one of five encodings:** `LONG`, `DOUBLE` and `FLOAT` (raw bits, as `DoubleDocValuesField` and `FloatDocValuesField` write them), and `SORTABLE_DOUBLE` and `SORTABLE_FLOAT` (as `DoubleField` and `FloatField` write them). These are the same five the documents screen's `x` decodes.
   - **How the encoding applies:** a value is encoded before it is compared, so `0` and `-0` are different values.
   - **Exclusive bounds** step to the next number first: by one for a long; for floating point, with `DoublePoint`/`FloatPoint.nextUp` and `nextDown`, which keep `-0.0` below `0.0`. A step past the end matches nothing.
   - **Raw-bit ranges are split at zero.** The non-negative part runs from the bits of `0.0` up. The negative part runs from the bits of `-0.0` up as the numbers get smaller. Together they make a `BooleanQuery` of up to two ranges. An open bound reaches to the end of the longs, and so takes in NaN.
4. **The server checks the query when the search starts.** Each of these fails at once with `INVALID_PARAMS`:
   - a field without doc values;
   - a missing or a needless `number`;
   - both or neither of `values` and `range`, or no values;
   - a number that doesn't parse, or NaN;
   - a binary number;
   - bad hex.

   An unknown field is `FIELD_NOT_FOUND`.
5. **`BINARY` is a query of leia's own, `BinaryDocValuesQuery`.** It is a constant-score query whose two-phase iterator reads each document's value and compares the bytes: unsigned, byte by byte, as `BytesRef.compareTo` does. `toString` writes the bytes as Lucene writes a `BytesRef`: `checksum:[80 ff]` or `checksum:{[80] TO *]`.
6. **Core stays free of the wire.** It takes the numbers as text with a `DocValuesNumber`, the bytes as `BytesRef`s, and the match as `DocValuesMatch` (values or a range). The server maps the DTOs onto these.

### The TUI

7. **The search screen gets a doc values mode** (`m`: classic, standard, term, **doc values**, More-Like-This for a document or a text, regex).
   - **The line:** one value, several values separated by spaces, or a range in Lucene's syntax: `42`, `-1 0 1`, `[10 TO 20]`, `{0 TO *]`, `"new york" boston`. Quotes take `\"` and `\\`, and an unquoted `*` is an open bound. The TUI reads the line itself. A line it can't read shows why in place of the hits and searches nothing.
   - **Keys:**
     - `f`/`F` step through the fields with doc values;
     - `n` steps the encoding of a number field, starting at long;
     - `h` reads a field of bytes as hex;
     - `p` and `a` do nothing;
     - the similarity, sort, stored fields, count, explain, `b`/`B` and Enter work as in the other modes.
   - **The title** names the field, its doc values type and the encoding: `Doc values · field price (NUMERIC) · double bits`.
8. **`s` on the documents screen** searches for the selected field's doc values on the search screen. One value is an exact match, several are a set. Numbers go in the encoding `x` shows them in. Byte strings are text, or all hex if one of them is not UTF-8.

## Alternatives considered

- **A scan job of its own for `BINARY`**, as for the regex (ADR 0027). Its hits could be kept, so paging wouldn't scan again. But it needs two more methods and a filter source of its own, and it has no sort and no explain. A search already runs as a cancellable job. Rejected; every page of a `BINARY` search scans again, as a wildcard query does.
- **ADR 0012's sort types** (`INT`, `LONG`, `FLOAT`, `DOUBLE`, with raw bits in `NUMERIC` and sortable bits in `SORTED_NUMERIC`). There is less to choose, but they can't say that a `NUMERIC` field holds sortable bits, and they differ from what the documents screen shows. Rejected.
- **Numbers as JSON numbers.** A long beyond 2^53 doesn't survive every JSON reader, and JSON has no infinities. The text is what the user typed anyway. Rejected.
- **The doc values syntax inside the query parsers**, such as `dv:price:[1 TO 2]`. It would mean a parser of leia's own, mixed with Lucene's syntax. Rejected.
- **A prefix match for bytes.** Lucene has no slow prefix query on doc values; it could be a range up to the prefix's successor. Deferred.

## Consequences

- `SearchQuery` gains `docValues`, with `DocValuesQuery`, `DocValuesRange` and `DocValuesNumber` on both sides, and fixtures for `startSearch` and `startFilter`.
- Core has `DocValuesQueries`, `DocValuesMatch`, `DocValuesNumber` and `BinaryDocValuesQuery`. The last is leia's own Lucene query, so a Lucene upgrade that changes the `Weight` or `Scorer` API needs a look at it.
- The parsed query is Lucene's `toString`, which writes the encoded longs and the bytes in hex: `price:[0 TO 4621819117588971520] price:[-9223372036854775808 TO -1]` for a raw-bit range up to 10, and `category:[[6b 69 …] TO [6b 69 …]]` for an exact match on a sorted field. The query line shows what was typed.
- The search screen's `m` cycle is one step longer.
