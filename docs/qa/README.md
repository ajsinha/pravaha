# QA — test cases, execution log, and outcomes

This directory is the QA record for the release that closed the three P0 blockers on 2026-09-12.
It is written by QA, kept in the repository, and meant to be read by whoever takes the release on.

| | |
|---|---|
| `cases/` | Test cases, one file per surface. Written before execution, so a case that was never run is visible as one that was never run. |
| `logs/` | Execution logs, one per surface. Every case gets a verdict and evidence. |
| `FINDINGS.md` | Defects found, their severity, and what was done about each. |
| `SUMMARY.md` | The single page to read if you read nothing else. |

## How to read a verdict

| | |
|---|---|
| **PASS** | Executed, behaved as the case says it should. |
| **FAIL** | Executed, did not. Every FAIL has an entry in `FINDINGS.md`. |
| **BLOCKED** | Could not be executed, and why. A blocked case is not a passing one. |
| **NOT RUN** | Written, deliberately not executed. The reason is recorded. |

A case with no evidence is treated as NOT RUN regardless of what it claims.
