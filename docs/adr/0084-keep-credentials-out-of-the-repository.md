# ADR 0084 — Keep credentials out of the repository

- **Status:** Accepted
- **Date:** 2026-10-17

## Context

The repository holds no credentials: a scan of its whole history with gitleaks (160 commits) finds none. Nothing kept it that way, though: no git hook, no CI, no remote, no secret scanner. `.gitignore` ignored `.env` and `.env.*`, but no key or keystore files. A release to Maven Central would bring credentials (a Sonatype token, a signing key and its passphrase), and the tracked `gradle.properties` is where Gradle looks for them first. A secret once committed stays in the history, and once pushed must be revoked.

## Decision

1. **Credentials live outside the tree.** In `~/.gradle/gradle.properties` or the environment (`ORG_GRADLE_PROJECT_<name>` for a Gradle property), never in the project's `gradle.properties` or any other tracked file. `AGENTS.md` says so as a hard rule.
2. **Key and keystore files are ignored.** A "Secrets" block in `.gitignore`: `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `secring.*`, `*.asc`, `credentials*.json`. None of them is tracked today.
3. **gitleaks runs as a pre-commit hook.** `.githooks/pre-commit`, a POSIX sh script, runs `gitleaks git --pre-commit --staged --redact --verbose` and blocks the commit on a finding, showing the file, line and rule with the secret redacted. Without gitleaks (on `PATH` or in `$GOPATH/bin`) it says how to install it and fails, so a missing scanner isn't mistaken for a clean commit. `.gitleaks.toml` uses gitleaks' default rules, minus `build/`, `dist/`, test indexes and `.jdks/`. Each clone enables the hook once with `git config core.hooksPath .githooks`.
4. **A server-side backstop when the repository goes to GitHub:** Secret scanning and Push protection, which catch what a skipped (`--no-verify`) or uninstalled hook let through.

## Alternatives considered

- **The `pre-commit` framework.** Rejected: it needs Python and a tool of its own for one hook; a sh script under `.githooks/` needs only git and gitleaks.
- **A Gradle task that scans.** Rejected: it runs on build, not on commit, so a secret would already be in a commit by then.
- **Only a server-side scanner.** Rejected as the sole guard: it catches the secret after it left the machine, when it must be revoked anyway; it stays as the backstop.
- **Skipping the hook when gitleaks is missing.** Rejected: the commit would go through unchecked without a word.

## Consequences

- A commit with a secret that gitleaks' rules know is blocked before it exists; a generic `password = …` line of low entropy isn't caught, so rule 1 still matters.
- Committing needs gitleaks installed once the hook is enabled; `--no-verify` skips it on purpose.
