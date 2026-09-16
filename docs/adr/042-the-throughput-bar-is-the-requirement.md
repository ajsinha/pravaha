# ADR-042: the throughput bar is the requirement, not the aspiration

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — bar restated; measurement not yet taken |
| Date | 2026-09-16 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-030 (scope tiers), ADR-036 (one node, thousands of queries), ADR-039 (GA order), gates P2, P3, P6 |

## Decision

**The throughput requirement is on the order of 1,000 rows per second**, which is the rate the
owner's workload actually needs. The figures the gates carry — 1.2 M rec/s per lane for Profile A
(P2), and a Python client at 1 M rows/s (P6) — were **aspirations taken from the design's ambition,
not requirements anybody had.**

Those gate criteria are therefore **restated against the requirement**, which makes them measurable
on the machine that exists rather than blocked on hardware nobody has bought.

## Why this is not the thing this project forbids

`README.md` says, and should keep saying: *"a gate quietly redefined to match what was built is not
a gate."* This is the opposite of that, and the distinction is the whole point of writing it down.

- **Redefining a gate to match what was built** is fitting the target to the arrow after it lands.
  The number moves because the result came in low, and the record does not say so.
- **Restating a gate against the requirement** is discovering that the target was never the right
  one. The number moves because nobody ever needed the old one, it is stated in public, and **both
  figures are kept** so a later reader can see the bar moved and judge the reason.

This is the second. The 1.2 M and 1 M figures stay in this document and in the gate packs; what
changes is which number a release argument is allowed to rest on.

## What the machine has already measured

This matters, because it stops the new bar reading as a retreat.

| | Measured here | Source |
|---|---|---|
| Lane machinery, single lane | **21 M rows/s** | Gate P2 pack (wave 3) |
| Generated vs interpreted operator | **~10×** | Gate P2 pack (wave 3) |
| Cost per query — threads, off-heap, descriptors | counted, not rated | `NodeScaleTest`, `SourceScaleTest` |

**The loop, inbox, arena and handoff already run at roughly seventeen thousand times the new bar.**
So the requirement is not in question and never was. What has never been measured is Profile A's
*end-to-end* rate under the conditions P2 specifies, and that remains unmeasured — see below.

## What this decision does NOT license

**It does not turn a small measured number into the engine's ceiling.** If a run on this laptop
sustains some thousands of rows per second, the correct statement is "this machine sustained that,
under this load, with these other processes running" — not "the engine does that". The development
machine is a 12-core heterogeneous laptop part and is now routinely running other containers and two
build agents; a number taken from it is a floor, not a capability.

**It does not close P2 or P3.** Those ask for 1.2 M rec/s per lane and ≥ 90 % scaling from one lane
to eight, on 16 physical homogeneous cores. Nothing here makes that measurable, and the packs keep
saying it is not. What changes is that a *release* no longer waits on it, because the product's
requirement is not that number.

**It does not excuse an unmeasured claim.** The rule that no figure from this machine is quoted as if
it were reference hardware stands unchanged. A restated bar still has to be measured before anything
is said to pass.

## What follows

1. P6's throughput criterion is restated: **the client sustains the required rate end to end**, which
   is now measurable here, and must actually be measured rather than assumed.
2. P2 and P3 keep their original criteria and their "needs reference hardware" verdict. They are no
   longer on the GA path; they are a performance claim to be substantiated when hardware exists.
3. The two gate packs and `README.md`'s "what cannot be measured here" section say which of their
   numbers is a requirement and which is an aspiration, so the distinction survives this document.

## What would make this wrong

A workload that needs more. The rate here is the owner's, and it is small because the work is
maintaining answers to registered questions rather than moving bulk data — ADR-030's tier 4 puts
ad-hoc analytics out of scope precisely so this stays true. A deployment whose ingest is orders of
magnitude larger would make the original figures requirements again rather than aspirations, and
this ADR should be revisited rather than quietly stretched.
