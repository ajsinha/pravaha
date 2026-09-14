# QA — test cases, execution log, and outcomes

This directory is the QA record for the release that closed the three P0 blockers on 2026-09-12.
It is written by QA, kept in the repository, and meant to be read by whoever takes the release on.

| | |
|---|---|
| `cases/` | Test cases, one file per surface. Written before execution, so a case that was never run is visible as one that was never run. |
| `logs/` | Execution logs, one per surface. Every case gets a verdict and evidence. |
| `FINDINGS.md` | Defects found, their severity, and what was done about each. |
| `SUMMARY.md` | The single page to read if you read nothing else. |

## A trap for anything that greps the repository

Several checks assert something about the source tree: which files call a method, that every class
is referenced, that error codes are unique, that nothing exceeds the line limit. QA executors work
in **git worktrees under `.claude/`**, and those are full copies of this repository.

So a walk of the repository root sees one copy of every source file *per running agent*. A check
counting "the files this call site appears in" reports three where it expects one, and it reads as a
product change. It has now cost four separate debugging sessions.

Exclude nested checkouts **relative to the root you found**, never by matching `/.claude/` as a
substring. Both halves matter and each was got wrong once:

- from a main checkout, the worktrees under `.claude/` are nested and inflate every count;
- from *inside* one of those worktrees, the root's own path contains `/.claude/` — so a substring
  test discards the entire tree and the check passes on nothing at all.

```java
Path nested = repoRoot().resolve(".claude");
… .filter(p -> !p.startsWith(nested))
```

and for a shell `grep`, `--exclude-dir=.claude --exclude-dir=target`.

## How to read a verdict

| | |
|---|---|
| **PASS** | Executed, behaved as the case says it should. |
| **FAIL** | Executed, did not. Every FAIL has an entry in `FINDINGS.md`. |
| **BLOCKED** | Could not be executed, and why. A blocked case is not a passing one. |
| **NOT RUN** | Written, deliberately not executed. The reason is recorded. |

A case with no evidence is treated as NOT RUN regardless of what it claims.
