# ADR 0102 — The indexes MCP mode may open

- **Status:** Accepted
- **Date:** 2026-10-25
- **Amends:** ADR 0100 (§7, the index; §8, the CLI); AGENTS.md, the CLI contract

## Context

`open_index` (ADR 0100 §7) opens any path that the user's account can read. The path comes from the agent, and the agent may have it from anywhere: the user, its own guess, or the text of a document that leia showed it, which is the user's data and not the user's instructions. So an agent can open any Lucene index on the machine, such as another project's, or the shards in an Elasticsearch data folder. The other indexes are as readable to leia as the one the user meant.

The refusals say more than they need to. `No such file or directory`, `Not a directory`, `Permission denied` and `No Lucene index in or below` tell whether any path exists and what it is. `open_index` of `/` walks up to 10,000 directories before it gives up (ADR 0021), and `skipped` names the indexes it found but could not open.

leia reads only, it reads only Lucene indexes, and stdio has no network listener, so the harm is bounded: an agent sees index content it shouldn't, and what it sees can travel on through the agent's other tools. The user should be able to bound which indexes those are.

## Decision

1. **`--mcp-root <PATH>`**, repeatable, only with `--mcp` (exit 2 otherwise).
   - With roots, `open_index` opens a path only if its real path is a root or below one. The real path is from `toRealPath()`, so a symbolic link, `..` and the `~` of ADR 0100 §7 are all followed before the check.
   - leia makes each root a real path at startup. A root that isn't a directory exits 1, as a `--jar` that doesn't exist does.
   - The backend opens the real path that was checked, not the path the agent gave. A link that changes after the check doesn't lead elsewhere.
   - Every refusal is the same: `open_index opens only paths below <roots> (INVALID_PARAMS)`. That holds for a path that doesn't exist, one that can't be read, one through a link out of a root, and one outside. So the refusal says nothing about what is outside the roots. It names the roots, which the user chose and the agent needs.
   - The check is in `leia-mcp`'s `Session.allowed`. The backend never sees a refused path, so no protocol `ErrorCode` is needed.
   - Without roots, `open_index` opens any path, as before.
2. **`--mcp-pin`**, only with `--mcp` and `INDEX_PATH` (exit 2 otherwise), and not with `--mcp-root`, which it makes pointless (exit 2).
   - `tools/list` leaves out `open_index` (`Tools.PINNED`), and a call of it is an unknown tool (`-32602`). The agent reads the index the user named, and only that one.
   - The instructions of `initialize` say that the index is fixed and don't mention `open_index`. The other tools' descriptions stay as they are, and `tools.json` keeps pinning the default list.
3. **`INDEX_PATH` is trusted.** The user named it on the command line, so it opens wherever it is, also outside the roots.

## Consequences

- An MCP configuration can bound what the agent reads to one index, or to the folders the user names.
- With roots, a path the agent gets wrong inside a root is refused like one outside, with no `No such file or directory`. The roots in the message show it where to look.
- The paths in results, such as `index_info`'s `path`, are real paths when roots are given.

## Not in this ADR

- **Files inside an index directory.** A link there, such as a `segments_N` pointing out of the folder, is read by Lucene as it is. The directory is the user's, and a file that isn't Lucene's fails to parse.
- **What the account can read anyway.** leia runs as the user. Isolation from the rest of the machine is the job of the operating system: another user, a container, or a sandbox with the index folders mounted read-only. The README says so.
- **Hiding fields or documents.** What an index holds goes to the agent whole.
- **The MCP client's roots** (`roots/list`). Not every client offers them, and they name the folders of the workspace, not of the indexes. They can come later as a default for `--mcp-root`.

## Alternatives considered

- **Check the path as given, without `toRealPath()`.** A link inside a root would lead out of it. Rejected.
- **The ordinary messages inside a root, the fixed one outside.** Whether a path is inside can only be known from its real path, and a path that doesn't exist has none. Telling the two apart would hint at what exists, for little gain. Rejected.
- **Hold `INDEX_PATH` to the roots too.** The user named both, so a mismatch is the user's choice, and refusing it would only make the configuration harder. Rejected.
- **A protocol method to restrict the backend.** The TUI needs no such restriction, since the user types its paths. The check belongs to the front end that takes paths from an agent.
