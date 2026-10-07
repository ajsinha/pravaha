# The deck's facts, and where each comes from

Every number and named claim on a slide, beside the file that holds it. Each slide's speaker notes
begin `Source:` and say the same in more detail; this page is the index a reviewer checks first.
When a figure changes in its source, change it here and on the slide in the same commit.

| Figure on the slides | Slide(s) | Source |
|---|---|---|
| "Ask once. Answer always."; "Continuous SQL where your data already lives" | 1, 56 | `brand/README.md` |
| Java 21 or later, tested on 21 and 25 | 1, 2, 16, 50 | `README.md` header; `docs/operations/COMPATIBILITY.md`; ADR-062 |
| 582 findings, 563 fixed, 0 open; 10 by design, 9 superseded; 0 GA-blocker / GA-required / post-GA | 2, 16, 49 | `docs/project/qa/FINDINGS.md` header (enforced by `FindingsRegisterTest`) |
| Ten principles | 7 | `docs/guides/CONCEPTS.md` §1–§10 |
| Seven families of product, their examples and the closing pattern | 8, 6 | `docs/publications/COMPETITIVE_LANDSCAPE.md` "The landscape" |
| Case-study quotations (batch, polling) | 5 | `examples/case-studies/{banking-card-velocity,finance-counterparty-exposure,lakehouse-orders-iceberg,retail-inventory-mysql}/README.md` |
| The windowed query and `AerospikeContinuousQueryIT`, PRV-2042 | 12 | `README.md` "What it is" |
| The −1/+1 correction of press-02 at 08:03 | 13 | `examples/case-studies/manufacturing-sensor-anomalies/README.md` step 5 |
| 26 modules | 16 | `README.md` "Building" (module list, checked against `pom.xml` by `DocumentationFreshnessTest`) |
| 10 connector plugins: 9 sources, 6 sinks, 2 lookups | 16, 29 | `README.md` "Building", "Sources", "Sinks"; `docs/design/ARCHITECTURE.md` §2 plugin table |
| 291 error codes; per area 35 / 21 / 14 / 32 / 82 / 29 / 30 / 41 / 7 | 16, 42 | `docs/guides/TROUBLESHOOTING.md` "Every code" (counted by its Range column); `ErrorCodeUniquenessTest` |
| 5,112 tests, 0 failures, 0 errors, 122 skipped; 960; 211 container tests; 110 + 21 adversarial; 432 SDK; 1,957 console; four standalone clients | 16, 51 | `docs/development/TESTING.md` "Where the numbers come from", "The tiers at a glance" |
| 4,832 tests on JDK 25 (2026-10-01) | 47 | `docs/development/TESTING.md` "Where the numbers come from" |
| 12 of Nexmark's 23 queries; which are refused and why | 16, 46 | `docs/project/gates/measured-2026-09-20/README.md` "Coverage re-measured on 2026-09-26" |
| Thirteen console screens and their captions | 19–25 | `docs/design/architecture/console-screens.md`; images in `docs/assets/screenshots/` (made by `pravaha-console/tools/docs_screenshots.py` over the fake engine) |
| Console on a phone (360 / 390 px), listens on 0.0.0.0, "About this page" | 19, 40 | `docs/project/RELEASE_NOTES.md` "Unreleased" and "2.4.0" |
| `pravaha doctor`, `top --once`, `drop --dry-run`, `validate` refusal output | 26–28, 42 | `pravaha-console/content/topics/cli-reference.md` captured blocks (`cli_captures.py`); trimmed or wrapped, said so in the notes |
| Exit codes 0 / 1 / 2 / 3 / 130 | 28 | `cli-reference.md` "Exit codes" |
| `plugin new`: ten source TCK tests pass | 28 | `cli-reference.md` "A connector project" |
| 19 MB `-all` jar; `SdkIndependenceTest`; four standalone clients | 30 | `docs/project/RELEASE_NOTES.md` "1.0.0"; `docs/development/TESTING.md` |
| Reconnect 250 ms to 10 s, five minutes; 60 s deadline, PRV-1045 | 31 | `docs/guides/USER_GUIDE.md` "Surviving a restart"; `RELEASE_NOTES.md` "2.2.0" |
| Sign-in flow, cookie flags, CSP, lockout 5 / 15 min / 30 min / 50 | 34, 38 | `docs/operations/SECURITY.md` "The console acts as the person signed in", "Users, passwords, API keys and sessions" |
| API key format, 90 / 365 days, 7-day rotation, re-verify every 2 s / every statement, PRV-6218 | 35, 38 | `docs/operations/SECURITY.md` "Users, passwords, API keys and sessions" |
| PRV-7001, 7002, 7003, 7006 | 35, 36, 39 | `docs/guides/TROUBLESHOOTING.md` |
| Audit: allows too, `audit.dropped`, `audit.lost`, DEGRADED | 37 | `docs/operations/SECURITY.md` "Audit" |
| Argon2id, SHA-256, session 30 min / 12 h / 3, reset 60 min, PRV-7019 | 38 | `docs/operations/SECURITY.md` "Users, passwords, API keys and sessions" |
| PRV-6221, PRV-6104, PRV-6206, 30-day WARN | 40 | `docs/operations/SECURITY.md` "Transport" |
| Profile A on JDK 21 vs 25: 630,864 / 586,418 / 618,952 batches/s, etc.; machine and load | 43 | `docs/project/gates/measured-2026-10-04-jdk21/README.md` |
| P2 / P3 / scaling / W5 verdicts and figures | 44 | `docs/project/gates/measured-2026-10-04-jdk25/README.md`; `measured-2026-09-20/README.md`; ADR-042 |
| +24 threads for 200 queries; ~1 MiB per idle query; 1,000 queries on 8 lanes; 64 | 45 | `README.md` "Many queries on one node", "Lanes" |
| 322 cases; 66 / 244 / 12; 46 defects (10 / 17 / 19) and 4 notes; 420, 40 × 150, 19, 9,800 | 47 | `docs/project/qa/SUMMARY.md`; `docs/project/qa/logs/ADV-ENGINE.md` |
| The ten high-severity defects | 48 | `docs/project/qa/SUMMARY.md` HIGH list; `FINDINGS.md` status lines |
| ADV-JDK21: seven findings, fixed in 2.4.0 and 2.4.1 | 49 | `docs/project/RELEASE_NOTES.md` "2.4.0", "2.4.1" |
| 42 jars, about 128,600 classes; CI 21/25; temurin:21-jre; ZGC caveat | 50 | `docs/project/RELEASE_NOTES.md` "2.3.0", "2.4.0" |
| What 2.x keeps stable | 52 | `docs/operations/COMPATIBILITY.md` |
| What is left | 54 | `docs/development/REMAINING.md` "What is genuinely left (2026-10-02)"; `README.md` "Roadmap" |
| Thirteen case studies | 55 | `examples/case-studies/README.md` |

**Not used, on purpose.** The reference deck this one's storytelling follows has an "industry voices"
slide of attributed quotations. The repository cites no quotation from a person about this category,
so slide 8 is built instead from the competitive landscape's own description of the field, by
category and not by vendor. No customer, quotation or statistic appears that is not in a file above.

---

Copyright © 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
See [LICENSE](../../LICENSE).
