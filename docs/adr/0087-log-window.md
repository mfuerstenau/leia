# ADR 0087 — Log window

- **Status:** Accepted
- **Date:** 2026-10-18
- **Amends:** ADR 0034 §7 (the log can also be read, and its level changed, inside leia)

## Context

leia logs only to `leia.log` (ADR 0034 §7). To see a warning, or every call with `-Dleia.log.level=FINE`, a user had to leave leia, find the file and start again with `LEIA_OPTS`. Luke has a Logs tab: a ring buffer of all `java.util.logging` output with a level filter and copy (`docs/luke-features.md` §4.6). The backlog item "Misc: log view with level filter" of `docs/tasklist.md` asks for the same.

## Decision

1. **A ring of the last 10,000 records in leia-tui, with no protocol method.** `LogBuffer` (`…tui.log`) is a `java.util.logging` handler on the root logger, at `ALL`, added by `LeiaLog.toFile` beside the file's handler. The backend runs in the same JVM and logs to the same root logger, and the window reads no index, so no method, record or handler is needed and hard rule 2 isn't touched. The buffer counts the records published (`serial`) and those it let go (`dropped`). `--version` and `--download-runtimes` log to stderr and get no buffer.

2. **A record is kept as text.** Each is copied into an `Entry {instant, level, logger, message, trace?}`: the message with its parameters filled in (`Formatter.formatMessage`), and the stack trace of its exception as text. Holding the `Throwable` would keep the class loader of a runtime or of jars alive, which leia closes or reloads (ADR 0068, ADR 0073).

3. **One level, the root logger's.** `LeiaLog` sets its handlers to `ALL` and the level from `leia.log.level` on the root logger only, so that one place decides what is logged.

4. **The filter sets the live level.** The window shows the records at or above its filter, which `-` makes more verbose and `+` less, through SEVERE, WARNING, INFO, CONFIG, FINE, FINER and FINEST. Each step sets the root logger to the more verbose of the filter and the level the start set: the window can add detail at once, for `leia.log` too, so FINE needs no restart; but filtering to WARNING on a FINE start keeps FINE in the file. A change is logged at INFO ("Logging at FINE"), while the more verbose of the old and the new level, and INFO at least, holds, so it reaches the file and the window. The title says the level logged at when it differs from the filter. The app keeps the filter when the window closes; it starts at the level logged at.

5. **A window on `L`**, like `X` (jars) and `D` (runtimes), over any screen, the start screen included; a prompt that takes characters takes `L` too. It covers nearly the whole body, since log lines are wide. Each record is a line `HH:mm:ss.SSS LEVEL   [SimpleLoggerName] message`, the default format of the file with the logger's simple name, and a further line of the message or of a trace continues indented. SEVERE is red, WARNING yellow, FINE and below dim. ↑↓ `j`/`k`, PgUp/PgDn and Home/End `g`/`G` scroll, and the window follows the end until scrolled up, as the check screen does; ←→ scroll sideways. `y` copies the shown records in the file's format, with the loggers' full names and the traces, through OSC 52, with the viewer's caveat for a large copy. Esc closes it; it takes the keys even while an index opens. While it is open, the app's tick draws again when records arrived.

6. **Stray prints are left out.** `System.out` and `System.err` still go into the file only (ADR 0034 §7); they aren't records.

## Alternatives considered

- **A protocol method `getLog`.** The UI and the backend share a JVM and a root logger, so the server would only hand the TUI its own records through records and a handler. Rejected.
- **A screen 8.** The log is about leia, not about the index, and is wanted from the start screen too, where no tabs show. Rejected.
- **A filter apart from the level.** The window could only filter what the file already has, and FINE would still need a restart. Rejected.
- **Letting the filter also lower the detail below the start's level.** A user who started with FINE to send the file would lose it by looking at warnings. Rejected.
