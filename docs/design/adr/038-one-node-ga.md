# ADR-038: GA is one node, and waves 10 and 11 are descoped to it

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | **Superseded by [ADR-039](039-ga-includes-the-known-gaps-and-clustering.md)** — GA now requires the seven known gaps and then clustering. Kept because its reasoning for a smaller GA is still sound if the scope is ever cut again |
| Date | 2026-09-16 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-034 (distribution deferred), ADR-035 (wave 8 is survival), ADR-036 (one node, thousands of queries) |

## Decision

**GA is a single node that a competent operator can run, diagnose and trust.** Waves 10 and 11 are
cut to what that sentence requires. Everything else in E8 and E9 moves to the roadmap, explicitly,
so that a later reader can tell a decision from an omission.

| Wave | Planned | GA scope |
|---|---|---|
| 10 (E8) | Control plane, self-tuning, **time-travel debugger** | **Nothing.** A debugger diagnoses a system people already depend on; nobody depends on this one yet |
| 11 (E9) | Breadth, **Nexmark**, all SLOs, GA | **GA only.** Nexmark is a competitive claim, not a deployment requirement |

## What GA actually requires, and why it is not a feature list

The gating work is **the 23 findings dispositioned GA-REQUIRED**, and that is not a coincidence —
it is the definition the triage already uses: *not a breach, but the product is not usable or not
diagnosable without it.* That is the bar for handing a node to somebody else.

They are unglamorous and they are the right list: error codes unreachable through the path every new
user hits, `PRV-2020`'s twenty-four messages pointing at no document, an SDK that turns every server
error into a stringy exception, and an audit sink nothing can read (`CFG-23`).

**Two things outside the register also gate it**, and both are one-node properties:

- **Gate P4's missing evidence** — no chaos test, and no kill during a checkpoint write. The
  process-kill harness now exists (`NodeCrashRestartTest`), so this is an extension rather than a
  build.
- **Gate P7's standby** — promotion has never been exercised against a real `SIGKILL`ed primary.

## What moves to the roadmap, named so it stays visible

Membership, partition assignment, rebalance, elastic rescale, multi-tenancy, Ratis, any multi-node
execution (ADR-034). The time-travel debugger and the control-plane UI. Self-tuning. Nexmark and the
published SLOs. `LaneMultiplexer` wiring (W9-8, W9-10), demoted by W9-11's measurement rather than
abandoned. ADR-037's B2 disk spill. `SX-5`'s remaining channels. (`SRC-3`'s shared reader was on this list and
has since been built for at-least-once sources; the exactly-once and ordered ones still read once per
query.)

## Why this is honest rather than convenient

The temptation in a decision like this is to redefine GA as whatever is finished. Three things keep
it from being that:

1. **The blocker list is not being waived.** It went 19 → 2 by being fixed, and the two that remain
   (`SX-5`, `SX-1`) are recorded as narrowed-not-closed and still counted.
2. **The gate packs already say what is unproven** rather than quietly passing. P4 is partly proven,
   M6 is not passed at all because its demo has never been performed, and those verdicts stand.
3. **The deferred list above is specific.** "Distribution later" is a plan; naming rebalance,
   handoff and Ratis is a commitment that somebody can hold this to.

What a GA node will not do is run as a cluster, tune itself, or come with a debugger. What it will
do is answer correctly, refuse clearly when it cannot, survive its own restart, and say what it is
doing while it does it. That is a product; the rest is a roadmap.
