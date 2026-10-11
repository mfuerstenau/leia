# TamboUI spike: the Windows checklist

The Windows half of the spike of ADR 0034. The Linux half is done (ADR 0034, "Spike results"). This one needs a person at a Windows keyboard: AltGr and the console's key input can't be faked from WSL.

## Setup

1. A **Java 25** on Windows, for example a Temurin 25 JRE zip unpacked anywhere. `java -version` must say 25.
2. Build the jar in WSL and copy it to a Windows folder:

   ```sh
   ./gradlew :leia-tui:shadowJar
   mkdir -p /mnt/c/Users/<you>/leia-spike
   cp leia-tui/build/libs/leia.jar /mnt/c/Users/<you>/leia-spike/
   ```

3. Open **Windows Terminal** and `cd` into that folder. The spike writes `leia-spike-keys.log` (every key) and `leia-spike.log` (stray output) into the current folder.

## Runs

Do all four runs, and after each one save a copy of `leia-spike-keys.log` before the next run overwrites it:

| # | Shell in Windows Terminal | Backend | Command |
|---|---|---|---|
| 1 | PowerShell | JLine 3 | `java -jar leia.jar` |
| 2 | PowerShell | Panama | `$env:TAMBOUI_BACKEND="panama"; java -jar leia.jar` (then `Remove-Item Env:TAMBOUI_BACKEND`) |
| 3 | cmd | JLine 3 | `java -jar leia.jar` |
| 4 | cmd | Panama | `set TAMBOUI_BACKEND=panama` then `java -jar leia.jar` |

Ctrl+T switches tabs everywhere, and Ctrl+C quits. Outside the Keys tab, `1`–`4` and `q` work too.

### Tab 1: Keys

The status line at the bottom names the backend. For JLine it also names the terminal class: `DumbTerminal` there means JLine found no real console, which counts as a fail.

Press each key **once**, slowly. Each must show up **exactly once** in the log, with the right name. A key logged twice (press and release) is a fail.

- Ctrl+D, Ctrl+N, Ctrl+U, Ctrl+K, Ctrl+W, Ctrl+A, Ctrl+E, Ctrl+Y, Alt+B
- Esc, Tab, Shift+Tab, Enter, Backspace, Delete
- ↑ ↓ ← →, PgUp, PgDn, Home, End
- F1, F5, F12. These are not bound in leia today, so a fail here is only recorded.

Then **type** into the input at the bottom:

- AltGr on a German layout: `@` (AltGr+Q), `\` (AltGr+ß), `{ [ ] }` (AltGr+7 8 9 0), `~` (AltGr++), `|` (AltGr+<), `€` (AltGr+E)
- `äöüß`, and a few CJK characters or emoji pasted with Ctrl+V or a right-click

Every character must appear in the input as typed. In the log, AltGr characters may show either `-` or `ctrl+alt` as modifiers; both are fine.

### Tab 2: Table

- Hold ↓, then PgDn, End, and PgUp. Scrolling must keep up, and rows with CJK titles must stay aligned with the columns.
- Note the `render … ms (max …)` in the status line. The max includes the first visit, which builds the 10,000 rows.

### Tab 3: Wide text

Every `end` and the right border must line up. ZWJ emoji and flags depend on the font, so note how they look rather than failing on them.

### Window size

Resize the window a few times, and maximise and restore it. The status line's size and `resizes` must follow, and the layout must redraw cleanly.

### Tab 4: Clipboard & threads

- `y`, then paste into Notepad: it must give `grüße 漢字 🔍`.
- `b`: "running…", then "call N done on leia-backend" after about 0.3 s, while the UI stays responsive.
- `p` a few times: nothing may appear on the screen. The lines go to `leia-spike.log`.

### Quit

Ctrl+C. The terminal must come back clean: the normal screen, a visible cursor, and echo on.

## Startup time

In PowerShell, for each backend:

```powershell
1..5 | % { java "-Dleia.spike.exitAfterFirstFrame=true" -jar leia.jar }
```

Each run prints `terminal ready … ms, first frame … ms`, counted from the start of the launcher.

## Send back

- The four `leia-spike-keys.log` files. The first line names the backend, the terminal and the Java version.
- Anything that failed above, with the run number.
- The startup lines.
