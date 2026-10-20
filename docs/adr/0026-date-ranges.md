# ADR 0026 — Date ranges: resolution, locale and time zone

- **Status:** Accepted, amended by ADR 0035 (a resolution per field) and ADR 0069 (dates on the runtimes)
- **Date:** 2026-10-07

## Context

ADR 0011 left the date settings of both query parsers out until there was an index with `DateTools` fields to try them on. Applications that index dates with `DateTools` write them as text in GMT, cut to a resolution: `20260131` for a day, `2026013114` for an hour. A range such as `published:[1/1/26 TO 1/31/26]` only matches such terms if the parser turns its bounds into the same form.

Several things were found while reading Lucene 10.5.2 (`QueryParserBase.getRangeQuery` and the standard parser's `TermRangeQueryNodeProcessor`):

- **Without a resolution, nothing is converted.** Both parsers parse the bound as a date, then call `DateTools.dateToString(date, null)`, which throws. They swallow the exception, so the bounds stay text. Today leia never sets a resolution, so date ranges are always text.
- **The bounds are read in the JVM's default zone.** `DateFormat.getDateInstance(SHORT, locale)` is never given a zone. The parser's time zone is only used to move an inclusive upper bound to 23:59:59.999. In a JVM in Europe/Berlin, `1/1/26` is midnight in Berlin, which is `2025-12-31T23:00Z`, so DAY makes it `20251231`. The range takes in a day too many, and the result depends on the machine that runs leia's server, not on what the user typed.
- **The two parsers fail differently.** The classic parser converts each bound on its own. The standard parser converts both or neither: if one isn't a date, both stay text, normalized by the analyzer.
- **The locale chooses the format.** SHORT is `M/d/yy` for `en-US` and `dd.MM.yy` for `de-DE`. Parsing is lenient.

## Decision

1. **`ParserOptions` gains three optional fields**, shared by both parsers:
   - `dateResolution`: `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND` or `MILLISECOND`, as `DateTools.Resolution`. Absent, the bounds stay text, as before.
   - `locale`: a BCP 47 tag such as `en-US`. Absent, the server JVM's default.
   - `timeZone`: what `ZoneId.of` takes: `UTC`, `Europe/Berlin`, `+02:00`. Absent, the server JVM's default.

   They are optional, unlike the other options (ADR 0011), because absence means something no value can say: the JVM's defaults, or no conversion. The fixtures carry them in the classic request and leave them out of the standard one.
2. **One resolution for every field**, as in Luke. Lucene's per-field resolutions can come later as another optional field without breaking this one. *Amended by ADR 0035: `fieldDateResolutions`, a required list, gives single fields their own resolution.*
3. **With a time zone, leia reads the bounds in that zone.** Both the parsing and the end of the day use it. The classic parser is a `QueryParser` subclass that overrides `getRangeQuery`. For the standard parser, a subclass of `TermRangeQueryNodeProcessor` replaces the stock one in the parser's pipeline. Both keep Lucene's code otherwise, including how each parser fails. **Without a time zone**, the parser's zone is the JVM's default, so leia reads the bounds exactly as Lucene does. A test compares both cases with Lucene's own parsers.
4. **The server checks the locale and the zone** and answers `INVALID_PARAMS` for a malformed tag (`en_US`), a blank one, or an unknown zone (`Mars/Olympus`). Lucene would take an unknown zone id as GMT without a word (`TimeZone.getTimeZone(String)`). A well-formed tag that Java has no data for falls back to the root locale's format.
5. **`DateTools` writes GMT**, so a field written from UTC dates needs the zone `UTC` to match day for day. The demo index's `published` field is such a field: the day of `timestamp` in UTC.
6. **TUI:** the parser popup (`p`) has a "Range bounds as dates" group for both parsers:
   - the resolution cycles *none → YEAR … MILLISECOND*;
   - the locale and the time zone are typed in a line of their own, and a blank line, `d` or Backspace clears them back to the server's default;
   - the details panel lists the ones that are set (`dates DAY · locale en-US · zone UTC`);
   - a bad tag or zone shows as the search's error.

## Alternatives considered

- **Keep Lucene's behaviour and document it.** leia would then show what the application's parser does, but only if the application ran in the same zone as leia's server, which nothing ensures. The time zone option would look like it fixes this and wouldn't. Rejected.
- **Always read the bounds in UTC.** This matches `DateTools`, but it would differ from Lucene even when no zone is given. Rejected: absence keeps Lucene's behaviour, and `UTC` is one setting away.
- **Required fields with the TUI sending defaults**, as ADR 0011 does for the other options. No value means "the JVM's default" or "no conversion". Rejected.
- **A resolution per field.** Deferred (decision 2).

## Consequences

- `ParserOptions` gains three fields, and `DateResolution` is a new DTO. `startSearch.request.json` carries them.
- Core gets `DateRangeQueryParser`, `DateRangeQueryNodeProcessor` and `DateRanges`, adapted from Lucene's code. A Lucene upgrade that changes either parser needs a look at them. If the standard parser's pipeline loses its `TermRangeQueryNodeProcessor`, the tests fail.
- The demo index has a `published` field.
- The TUI's parser popup takes typed text, so `OptionsForm::handle_key` takes a `KeyEvent`.
