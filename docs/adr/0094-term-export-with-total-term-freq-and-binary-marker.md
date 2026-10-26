# ADR 0094 — Term export with `totalTermFreq` and a marker for text and hex terms

- **Status:** Accepted
- **Date:** 2026-10-22
- **Amends:** ADR 0022 (§8, §13, and the alternative that rejected a marker and `totalTermFreq`)

## Context

ADR 0022 kept the term export in Luke's format, `term<delimiter>docFreq`, and left out `totalTermFreq` and a mark for terms written as hex. Two things bring them back. A reader of the file can't tell a hex term from a text term that looks like hex, and the total term frequency is what the terms screen shows next to the document frequency. And there is a bug: the trie-encoded numeric terms of Lucene 4.x to 7.x use 7 bits a byte, so they are valid UTF-8 and were written raw, with C0 control bytes among them, a newline too, which breaks the line format.

The runtimes don't change: `Terms.TermEntry` already carries `totalTermFreq`, which every term page fills (Lucene 3 counts it from the postings, the others read it from the term dictionary, `docs/runtimes.md`).

## Decision

1. **`startExportTerms` takes `format?`:** `LUKE` (the default, Luke's layout) or `FULL`. The runtime interface and `API_VERSION` stay.
2. **`FULL` line:** `marker<d>docFreq<d>totalTermFreq<d>term\n`, where `marker` is `t` (the term is text, UTF-8) or `x` (the term is lowercase hex) and `<d>` the delimiter. The term comes last, so a reader splits at the first three delimiters, even if the term holds one. `LUKE` is `term<d>docFreq\n` as before.
3. **Text or hex, in both formats.** A term is written as text only if it is valid UTF-8 and holds no C0 control (U+0000 to U+001F) or DEL. Otherwise it is written as hex, unmarked in `LUKE` and with `x` in `FULL`. In UTF-8 the bytes below 0x80 are ASCII only, so a byte scan finds every control. No term can break a line now. A text term with a tab is hex too, which changes the `LUKE` output for it.
4. **The prompt:** on the terms screen, Ctrl+F toggles the format (`Luke`, then `full`), shown in the bottom title next to the delimiter. Ctrl+F isn't used by the line input.

## Alternatives considered

- **Replace Luke's format.** Files that others read with Luke's convention would change. Rejected: `LUKE` stays the default.
- **The marker as the last column.** It would sit after a term that may hold the delimiter, so a reader couldn't find it. Rejected.
- **Keep the old rule (valid UTF-8 only) for `LUKE`.** It keeps the bug of the broken lines in the format most files use. Rejected.
- **A header line.** It breaks `sort` and concatenation of files, and says nothing per term. Rejected.
- **Decode trie terms to numbers.** The terms screen doesn't either; out of scope.
