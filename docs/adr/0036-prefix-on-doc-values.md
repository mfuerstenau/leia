# ADR 0036 — A prefix on doc values of bytes

- **Status:** Accepted
- **Date:** 2026-10-11
- **Amends:** ADR 0033 (the deferred prefix match for bytes)

## Context

ADR 0033 finds documents by their doc values: one value, a set, or a range. It deferred a prefix match for bytes. A prefix is the natural way to find a family of keyword values, such as categories, paths or IDs with a common start, or binary values with a known leading byte. Lucene has no slow prefix query on doc values. `PrefixQuery` reads terms, and a field that only has doc values has none.

Every byte string that starts with a prefix `p` sorts at or after `p` and before the least byte string above all of them. ADR 0033 already has ranges on all three doc values types of bytes: `newSlowRangeQuery` on `SORTED` and `SORTED_SET` (an ord lookup, then a compare of ords), and `BinaryDocValuesQuery` on `BINARY`.

## Decision

1. **`DocValuesQuery` gains `prefix`, an optional `BytesValue`** (text, or hex with `binary`). Exactly one of `values`, `range` and `prefix` is present. There are no new methods and no new error codes.
2. **A prefix is a range up to its successor.** `DocValuesQueries.prefix(field, p)` is `[p TO succ(p)}`. `succ(p)` drops `p`'s trailing `0xff` bytes and adds one to the last byte left. If no byte is left (the empty prefix, or only `0xff`s), the range is open above. So `SORTED` and `SORTED_SET` go through Lucene's range queries, and `BINARY` goes through the range of `BinaryDocValuesQuery`. Neither changes.
3. **The empty prefix matches any value.** It is the range `[* TO *]`, and the parsed query says so.
4. **Bytes only.** A prefix with a `number`, or on a `NUMERIC` or `SORTED_NUMERIC` field, is `INVALID_PARAMS`, as is bad hex. A prefix together with `values` or `range` is `INVALID_PARAMS` too. For numbers, a range says what a prefix of decimal text would mean.
5. **The parsed query is the range**, as Lucene writes it: `category:[[6f] TO [70]}` for `o*`.

### The TUI

6. **An unquoted value ending in `*` is a prefix**, as in Lucene's syntax: `kit*`, or `80*` in hex with `h`. One trailing `*` is taken off, so `a**` is the prefix `a*`.
   - A prefix stands alone on the line. `kit* garden` shows "A prefix (ending in *) stands alone on the line."
   - A lone `*` is the empty prefix. Before, it was the value `*`.
   - A value that ends in `*` is written in quotes: `"kit*"`. `s` on the documents screen quotes such values, so they stay values.
   - Inside a range nothing changes: only a lone unquoted `*` is an open bound, and `[a* TO b]` has the bound `a*`.
   - On a number field, a prefix shows "A prefix is for doc values of bytes; numbers take a range." in place of the hits.

## Alternatives considered

- **Several prefixes, mixed with values, on one line** (`kit* garden off*`), OR-ed together: `prefixes` as a list and a `BooleanQuery` of ranges and a set. More to type in one line, but more on the wire and in core, for a case that a few searches cover. Rejected for now.
- **A prefix mode of `BinaryDocValuesQuery`** with `startsWith` and a `toString` of `checksum:[80]*`. It reads better, but it is a second path for `BINARY` only, while `SORTED` and `SORTED_SET` would still be ranges. Rejected; the range compares the same bytes.
- **A query of leia's own over the ords of `SORTED_SET`** that tests each term for the prefix. The range of ords is exactly the prefix's terms, so it would do the same, only slower. Rejected.

## Consequences

- `DocValuesQuery` has a fifth component. Every constructor call names it.
- A value that ends in `*` has to be quoted on the line.
- The parsed query shows a range, not the prefix that was typed. The query line still shows what was typed.
