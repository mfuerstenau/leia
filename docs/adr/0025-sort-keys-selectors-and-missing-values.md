# ADR 0025 — Sort keys, selectors and missing values

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

ADR 0012 sorts the hits by up to two doc values fields, with Lucene's default selector and missing values. It deferred three things:

- **`SCORE` and `DOC` keys.** A field sort with the score as its tie-break, or the documents in id order, needs keys that aren't fields.
- **Selectors.** A multi-valued field sorts by one of its values. `SortedNumericSortField` takes `SortedNumericSelector.Type` (`MIN`, `MAX`), and `SortedSetSortField` takes `SortedSetSelector.Type` (`MIN`, `MAX`, `MIDDLE_MIN`, `MIDDLE_MAX`). A single-valued field has one value, so every selector would pick the same one.
- **Missing values.** Lucene sorts a document without a value as 0 in a numeric sort, and first in an ascending string sort. A numeric sort takes any number of the type as its missing value. A string sort takes `SortField.STRING_FIRST` or `STRING_LAST`.

Several things were found while reading Lucene 10.5.2:

- **The missing value comes before the reverse.** `TermOrdValComparator` compares a missing value as lower (`STRING_FIRST`) or higher (`STRING_LAST`) than every term. The collector then multiplies every comparison by -1 for a reversed sort. So `STRING_LAST` puts the missing documents last in an ascending sort and first in a descending one. A numeric missing value is an ordinary value, and reverses the same way.
- **`setMissingValue` is deprecated in 10.5.** The constructors of `SortField`, `SortedNumericSortField` and `SortedSetSortField` take the missing value instead.
- **A numeric hit without a value shows the missing value.** `FieldDoc.fields` holds the number the comparator sorted by: 0 by default, or the missing value that was set. A string hit without a value holds `null`, which ADR 0012 sends as a sort value with neither field.
- **Score order.** `SortField.Type.SCORE` sorts the best score first, and `reverse` puts the worst first. `DOC` sorts the lowest id first.

## Decision

1. **A sort key is one of three alternatives**, `SortKey {field?, score?, doc?}`. Exactly one is present, otherwise `INVALID_PARAMS`. This is the same pattern as the query (ADR 0010) and the similarity (ADR 0012).
   - `field: FieldSort {field, type, reverse, selector, missing}`: ADR 0012's key, plus the selector and the missing values. Every field is required, and the TUI starts from Lucene's defaults.
   - `score: ScoreSort {reverse}`: `SortField(null, SCORE, reverse)`. The best score comes first, and `reverse` puts the worst first.
   - `doc: DocSort {reverse}`: `SortField(null, DOC, reverse)`. The lowest id comes first.

   This changes ADR 0012's `SortKey` in place. The TUI and the server ship together (ADR 0004).
2. **`selector`: `MIN`, `MAX`, `MIDDLE_MIN` or `MIDDLE_MAX`.** The server checks it against the field's doc values type:
   - `NUMERIC` and `SORTED` take `MIN` only;
   - `SORTED_NUMERIC` takes `MIN` or `MAX`;
   - `SORTED_SET` takes all four.

   Anything else is `INVALID_PARAMS`, such as "the field 'rank' has single-valued NUMERIC doc values, which can't select MAX". An option that does nothing would only mislead (ADR 0011).
3. **`missing`: `DEFAULT`, `FIRST` or `LAST`.**
   - `DEFAULT` is Lucene's: 0 for numbers, first in an ascending string sort.
   - `FIRST` and `LAST` mean the order shown, reversed or not. Core turns them into the lowest or highest value of the type, depending on `reverse`:
     - `MIN_VALUE`/`MAX_VALUE` for `INT` and `LONG`;
     - negative/positive infinity for `FLOAT` and `DOUBLE`;
     - `STRING_FIRST`/`STRING_LAST` for strings.

   Real values equal to those extremes tie with the missing ones, and the document id breaks the tie.
4. **A missing number is no number.** For each hit of the page and each numeric field key, core asks the field's doc values whether the document has a value (`DocValues.getSortedNumeric(...).advanceExact`, which also covers `NUMERIC`). If it has none, the sort value has neither `number` nor `term`, as for a missing string. That is at most 1000 lookups per page. The 0 or `MIN_VALUE` that Lucene sorted the document as is never shown as if it were its value.
5. **Score and doc sort values are numbers.** A `SCORE` key's value is the hit's score (`Float.toString`), and a `DOC` key's is its id. Both go in `number`. `SortValue` keeps its shape.
6. **Nothing is pruned, as before.** Field keys keep ADR 0012's `Pruning.NONE` comparators. Score and doc keys don't depend on a guessed type, so they keep Lucene's own comparators.
7. **The TUI** offers two levels, as before. A level's target cycles through *nothing → score → document id → each sortable field*:
   - A field level shows a type row (numbers only), a value row (multi-valued fields only, with the selectors that fit) and a missing row (Lucene's default, first, last).
   - Every level has an order row: "best first"/"worst first" for the score, ascending/descending otherwise.
   - A new field starts from its guessed type, `MIN` and Lucene's default. The order is kept when the target changes.
   - The hit list's title shows the sort, for example `scores MAX ↓ · doc ↑` or `tags first ↑`. The default selector and the default missing values are left out.

## Alternatives considered

- **`SCORE` and `DOC` as further `SortType`s with an optional `field`.** The `field`, `selector` and `missing` would then mean nothing for two of the types. Rejected, for the reason ADR 0010 gives for queries.
- **An optional selector and missing value, absent meaning Lucene's default.** This keeps the old field keys valid. But the TUI always sends them, and no old client needs the old shape. Rejected, as in ADR 0011 and ADR 0012.
- **`FIRST`/`LAST` as Lucene means them**, relative to the ascending order. Users read "missing last" in the order they see, and Lucene's meaning flips with `reverse`. Rejected.
- **A custom missing number** (`missingNumber`). It would let a missing value sort among the real ones, which only matters to mimic an application's own default. Deferred: it can come as an optional field.
- **Accepting any selector for single-valued fields**, where it has no effect. Rejected (decision 2).
- **Showing the substituted missing number**, as ADR 0012 did with 0. It reads as the document's value. Rejected (decision 4).

## Consequences

- `SortKey` changes shape. `FieldSort`, `ScoreSort`, `DocSort`, `SortSelector` and `SortMissing` are new DTOs. `startSearch.request-sorted.json` and `getSearch.response-sorted.json` change.
- Core's `SortType.sortField` takes a selector and a missing value. `SortType.score` and `SortType.doc` build the other keys. The searcher needs the `Sort` to tell missing numbers apart.
- An index without doc values can now be sorted, by score or by document id.
