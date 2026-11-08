# ADR 0124 — Download mirrors for the runtimes' Lucene jars

- **Status:** Accepted
- **Date:** 2026-11-07
- **Amended by:** ADR 0125 (the jars that a local Maven or Gradle cache has are copied, not downloaded)
- **Amends:** ADR 0073 §7 (one repository becomes a list of mirrors); ADR 0077 §2 (the listing names the mirrors); ADR 0080 §1 and §7 (`m` in the window of `D`; `--download-runtimes` asks, on a terminal, after a failed download); AGENTS.md, the CLI contract

## Context

Every Lucene jar of `runtimes/` came from one repository, `https://repo1.maven.org/maven2/`, which `-Dleia.download.url` could replace. A user behind a firewall that blocks it, or far from it, had no other way. A jar that failed to download failed its runtime, with no second try. Since every jar is checked against the SHA-256 and size that leia's build recorded (ADR 0073), any repository that serves Central's jars at their Maven paths will do, and needs no trust.

## Decision

1. **leia's own list.** `mirrors.txt`, a resource beside `RuntimeInstall`, lists the mirrors of Maven Central, a URL and a name per line. On 2026-11-07, each candidate was checked by fetching `lucene-core` 9.12.3 and 3.6.2 and comparing their SHA-256 with Central's. All matched, and seven were taken:
   - Maven Central (`repo1.maven.org`) and its Apache name (`repo.maven.apache.org`), one CDN under two host names, since a firewall may let only one through;
   - Google Cloud's copies in the US, the EU and Asia (`maven-central*.storage-download.googleapis.com/maven2/`; the task list's `storage.googleapis.com` was wrong);
   - Alibaba Cloud (`maven.aliyun.com/repository/central/`, without `/maven2/`);
   - Huawei Cloud (`repo.huaweicloud.com/repository/maven/`).

   Tencent's `maven-public` and JBoss's `central` are left out. They are group repositories that mix other repositories into Central's, and JBoss redirects.
2. **The user's own, and the order.** `Mirrors.resolve` builds the list that the download tries, each URL once:
   1. `-Dleia.download.url`, which now goes first instead of replacing Central;
   2. `--download-url <URL>` (repeatable, only with `--download-runtimes`);
   3. the mirror chosen last in the window of `D`, the file `mirror` in the config folder;
   4. the file `mirrors` in the first config candidate that has one, the user's before the system's, so that an administrator can name the company's mirror (its mirrors run no code, and their jars are checked like any other);
   5. leia's own list.

   A line of `mirrors` is a URL and an optional name; `#` starts a comment. A URL of leia's list that the file names without a name keeps leia's name.
3. **What a URL must be.** A mirror is an absolute `https` URL with a host, and without credentials, which the listing would print, a query or a fragment. `http` passes only with `-Dleia.download.insecure=true`: the hash check makes it safe, but a proxy may rewrite it. A refused `--download-url` exits 2. Under `--download-runtimes`, so do a refused `-Dleia.download.url` and a refused line of `mirrors`, before any folder is looked at. In the TUI, they are logged, left out, and named in the window's header.
4. **A failed download asks.** A jar's download fails from a mirror on a connection error, on no answer within 60 s, on no bytes for 60 s, on an HTTP status other than 200, or on a size or SHA-256 that doesn't match. The install then asks its `MirrorChooser`, on the thread that installs, what to do:
   - `Retry(mirror)` downloads the jar again from that mirror, which is then the one the install goes on with for every later jar of the run;
   - `Skip` fails the runtime, and the install goes on with the next one;
   - `Abort` fails the runtime ("the download was stopped") and cancels the runtimes not begun yet.

   A cancel while it is asked counts as a cancel. The 60 s stall is caught by a watchdog that closes the response's body, since Java's `HttpClient` has no read timeout.
5. **In the window of `D`,** a failed download asks at once, with no silent fallback:
   - The window enters a phase of its own, RETRY. It shows the jar, the mirror and the reason, the jars' progress with every failed try (`✗ <mirror>: <reason>`), and the mirrors, those that failed marked with their reasons, and the cursor on the first that hasn't failed.
   - ⏎ retries from the mirror at the cursor; `u` types a URL of the user's own; Esc stops the download (`Abort`).
   - `Session` hands the choice to the install thread through a future, which the thread polls every 100 ms, so that Ctrl+C still ends the install with no `.part` file left.
   - Before the download, `m` opens the same list to choose the first mirror. The choice is saved to `mirror` in the config folder, as is a mirror retried from, and is tried first next time.
6. **`--download-runtimes`** still asks nothing up front. When a download fails on a terminal (`System.console().isTerminal()`), it prints the mirrors numbered, those that failed marked, and asks `Mirror [1-n], or a to abort:`. Only a number of the list retries; `a` and the end of input abort; anything else asks again. Without a terminal, as in a script, it tries the next mirror it hasn't tried for the jar, and fails the runtime once each has failed (`Skip`), naming each mirror and why. The exit status is 1 if a runtime failed.
7. **The listing** (ADR 0077 §2) names the first mirror in its opening sentence. When there are more, it lists them in order, each with where it came from (`--download-url`, `mirrors file`, `chosen last`). The line per jar names the mirror it came from (`downloaded, 2.9 MiB, from Google Cloud, EU`), as does the window's.

## Alternatives considered

- **Fall back silently in the window, and ask only when every mirror failed.** Rejected by the user: a failure should be seen at once, and the user often knows which mirror will work.
- **Ask in scripts too, on stdin.** Rejected: an unattended install has no one to answer, and end of input would stop it on the first failure.
- **Update the list without a release,** from a URL. Rejected for now: a `mirrors` file in the config folder covers a mirror leia doesn't know, and a system one covers a company.
- **Allow credentials in a mirror's URL.** Rejected: the listing and the log print the URL. A mirror that needs them can sit behind a proxy (`-Djava.net.useSystemProxies=true`).

## Consequences

- `RuntimeInstall` takes a list of `Mirror`s instead of one base URI. `RuntimeInstall.CENTRAL` and `RuntimeInstall.base()` are gone; `Mirrors.CENTRAL` and `Mirrors.resolve` replace them.
- A mirror that lacks some jars works: the jars it lacks fail on it, and the prompt or the fallback takes another.
- The tests run the download against several local repositories: one down, one serving a wrong jar, one stalling (with a 1 s timeout), and all failing.
