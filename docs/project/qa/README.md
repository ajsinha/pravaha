# QA — test cases, execution log, and outcomes

This directory is the QA record for the release that closed the three P0 blockers on 2026-09-12.
It is written by QA, kept in the repository, and meant to be read by whoever takes the release on.

| | |
|---|---|
| `cases/` | Test cases, one file per surface. Written before execution, so a case that was never run is visible as one that was never run. |
| `logs/` | Execution logs, one per surface. Every case gets a verdict and evidence. |
| `FINDINGS.md` | Defects found, their severity, and what was done about each. |
| `SUMMARY.md` | The single page to read if you read nothing else. |

## `SQL_SUPPORT.md` is now `CONTINUOUS_QUERIES.md`

Every case and log in this directory cites `docs/SQL_SUPPORT.md`, often by line number. That file was
merged into [`../../guides/CONTINUOUS_QUERIES.md`](../../guides/CONTINUOUS_QUERIES.md) on 2026-09-16, which now carries
the support matrix row for row alongside the streams-to-views narrative (ADR-040's sibling work, see
`1b242f3`).

The citations here are **deliberately not rewritten.** They are dated records of what was read at the
time, against a file that had that name and those line numbers, and editing them would make them say
something their author never saw. Read them against the merged document's Part II.

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

## The other trap, which has cost more than the first

`./mvnw -pl <module> test` resolves that module's dependencies from `~/.m2`, **not from the working
tree**. A change in `pravaha-runtime` is therefore invisible to a test in `pravaha-it` unless
something reinstalled it in between.

The failure is not a build error. It is a test run that reports results for code that is not the code
in front of you — failures that are not real, or passes that are not real, and no indication which.
It has cost four separate debugging sessions, twice in one day: an agent reported "`pravaha-it` is
not green, 3 failures" against a tree that was green, and a findings-register check failed the same
way an hour later. The reflex it produces is to go hunting for a defect in whatever was just changed,
which is the most expensive possible wrong turn.

Use **`tools/verify-clean.sh`** for any substantial test run. It deletes Pravaha's own artefacts from
`~/.m2` first, so there is no stale jar left to resolve and the reactor is the only possible source.
Third-party dependencies are left alone — the build runs offline and re-downloading them is neither
possible nor the problem.

```
tools/verify-clean.sh                     # full verify
tools/verify-clean.sh -pl pravaha-it -am  # any maven arguments
```

If you do run Maven directly, `-am` is the minimum: it builds the dependencies in the same reactor
rather than resolving them.

## How to read a verdict

| | |
|---|---|
| **PASS** | Executed, behaved as the case says it should. |
| **FAIL** | Executed, did not. Every FAIL has an entry in `FINDINGS.md`. |
| **BLOCKED** | Could not be executed, and why. A blocked case is not a passing one. |
| **NOT RUN** | Written, deliberately not executed. The reason is recorded. |

A case with no evidence is treated as NOT RUN regardless of what it claims.
