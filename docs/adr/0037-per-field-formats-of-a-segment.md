# ADR 0037 — The per-field formats of a segment

- **Status:** Accepted
- **Date:** 2026-10-11
- **Amends:** ADR 0009 §4 (codec formats by name, per-field formats left out)

## Context

The commits screen shows the formats of each segment's codec (ADR 0009 §4). Lucene's codecs wrap the postings, doc values and KNN vectors formats in per-field formats, so these read `PerField40`, `PerFieldDV40` and `PerFieldVectors90`, which say nothing about how a field is written. ADR 0009 left the real formats out as "a separate step".

The per-field formats write the name of each field's format as a `FieldInfo` attribute (`PerFieldPostingsFormat.PER_FIELD_FORMAT_KEY` and its siblings) into the segment's field infos, the `.fnm` file. Reading the field infos gives these names as plain strings, without looking a format up through SPI. So a segment whose formats come from a jar that isn't loaded (ADR 0018), or an index opened without a reader (ADR 0021), still shows which formats it needs.

## Decision

1. **Eager, in `getCommit`.** A segment carries everything about it (ADR 0009 §3), so `Segment` gains `fieldFormats`, a list of `FieldFormats {field, postings?, docValues?, knnVectors?}`, and `fieldInfosError?`. There is no new method and no job: the cost is one small `.fnm` per segment.
2. **Read as `SegmentReader` reads them.** With a field infos update (`fieldInfosGen` set), the update's `.fnm` in the index directory, with the generation in base 36 as the suffix. Without one, the segment's own `.fnm`, through the compound reader for a compound segment. Through the segment's codec's `FieldInfosFormat`, with `IOContext.READONCE`. Nothing is written (hard rule 1).
3. **A format per kind that the field has:** postings when its `IndexOptions` aren't `NONE`, doc values when its `DocValuesType` isn't `NONE`, KNN vectors when it has vectors. The value is the per-field attribute. A codec that doesn't use the per-field wrappers writes none, and then the codec's own format name stands in (as ADR 0009 §4 names it).
4. **Every field, sorted by name.** A field with none of the three (stored only, points only) has all three absent.
5. **Field infos that can't be read don't fail the commit**, as ADR 0009 §5 has it for sizes: the segment's `fieldFormats` is empty, and `fieldInfosError` says why, in the words opening an index would use ("Corrupt index, missing file: …"). The server logs a warning.

### The TUI

6. **A "Field formats" section** in a segment's details, after "Codec formats": for postings, doc values and KNN vectors, a line per format with its kind in bold and the format in cyan, the kinds padded to one width. Below it, the fields that use the format, joined with ", ", indented by 2 and wrapped under that indent to the panel's inner width. "none" if no field has any of the three, and the error in red if the field infos can't be read.

   *Amended 2026-10-11:* at first each format was one row of a table (kind, format, fields), with the kind on its first row only. A long field list wrapped back to column 0, under the kind, so it was unclear where one format ended and the next began. A hanging indent under the field column doesn't fit: the details panel is about 46 columns wide at 120, and `Lucene99HnswVectorsFormat` alone takes 25.

## Alternatives considered

- **A method of its own per segment** (`getSegmentFields`). The TUI would fetch on each move through the segments, for a read as small as the `.si` ones. Rejected, as ADR 0009 rejected Luke's call per detail.
- **The formats from the opened reader's `FieldInfos`.** Cheaper, but only for the opened commit, and not for an index opened without a reader or for an older commit. Rejected.
- **A line per field in the TUI.** Exact, but an index of a hundred fields would push the files far down, while the formats are mostly the same for all. Rejected in favour of grouping by format.

## Consequences

- `Segment` in core and in the protocol has two more components.
- Each `getCommit` reads every segment's `.fnm` as well as its `.si`.
- A format that a jar has to provide shows by name before the jar is loaded.
