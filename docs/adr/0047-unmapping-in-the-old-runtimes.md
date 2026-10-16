# ADR 0047 — Unmapping in the old runtimes on Java 25

- **Status:** Accepted
- **Date:** 2026-10-12
- **Amends:** ADR 0034 §8 (the scripts' command line)

## Context

The manual run on `lucene4`/`lucene5` broke the screen. Opening a 4.x or 5.x index maps its files through the relocated 5.5 `MMapDirectory` (`FSDirectory.open` gives one on 64-bit), and closing an input unmaps it through `sun.misc.Unsafe::invokeCleaner`. Java 25 then warns once: "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called …".

The warning goes to `VM.initialErr()`, the JVM's own stderr, which `System.setErr` doesn't redirect. So it is not in `leia.log`, and it lands on the TUI's screen (hard rule 4).

Whether it warns is decided in `sun.misc.Unsafe.beforeMemoryAccess()`, from `VM.getSavedProperty("sun.misc.unsafe.memory.access")`. This is a saved property, read at JVM start, so a later `System.setProperty` has no effect.

The options were:
- `--sun-misc-unsafe-memory-access=allow` in the scripts. Javas before 23 reject it and don't start, so `Launcher` could no longer say that Java 25 is needed (ADR 0034).
- Redirecting file descriptor 2 natively while the UI runs. This is platform code for one warning.
- Never unmapping (`setUseUnmap(false)`). Then mapped files are released only at GC, which is a cost when leia is started through the scripts, where unmapping could be allowed.

## Decision

1. **The scripts pass `-Dsun.misc.unsafe.memory.access=allow`** first, before `LEIA_OPTS`, which can override it. Java 25 reads this `-D` like the `--` flag. Javas before 23 accept it without complaint, so `Launcher` still reports the version.
2. **The old runtimes unmap only when the property is `allow`.** `leia-lucene5`'s `Directories.open` calls `setUseUnmap(Directories.unmap(…))` on every `MMapDirectory`, including the one `FS` gives. `unmap` is true only for `allow`, and only where `MMapDirectory.UNMAP_SUPPORTED`. Without the property, as with `java -jar leia.jar`, nothing is unmapped and nothing warns. The check goes through `Directories.open` and gets the same.
3. **Lucene 3.6 needs nothing.** Its `UNMAP_SUPPORTED` needs `sun.misc.Cleaner`, gone since Java 9, so it never unmaps. A test keeps this true.
4. **Every later relocated runtime that brings its own `MMapDirectory` does the same**, `leia-lucene7` first. Lucene 9 and 10 map through `MemorySegment` and don't touch `Unsafe`.

## Consequences

- The screen stays clean, through the scripts and through `java -jar leia.jar`.
- Through the scripts, mapped files are unmapped on close, as Lucene intends. Without them, they are held until GC. The index is read-only, so this costs only address space and file handles.
- When a later Java removes `Unsafe`'s memory access, `allow` stops working. 5.5's `UNMAP_SUPPORTED` will then be false, and the runtime will not unmap.
