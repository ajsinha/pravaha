---
title: Reading the numbers on a plan
slug: reading-a-plan
category: operating
order: 72
icon: diagram-3
summary: "What the numbers drawn on each operator of a plan mean, how exact each one is, which of them names a bottleneck, and why a node with pravaha.metrics.operators off shows none of them rather than showing zeros."
badge: BACKPRESSURE
audience: Operators
keywords: [plan, operator, bottleneck, backpressure, blocked fraction, inbox, self time, sampled, rows in, rows out, state bytes, watermark, pravaha.metrics.operators, lane, shared lane]
guide: operations#diagnosing-backpressure
related: [metrics-alerts, sizing-lanes, lane-sharing, backfill-cutover, event-time-watermarks]
---

A query that is "slow" is one of three things, and they want three different answers:

1. **Its source has stopped.** Nothing is arriving. The query page says so, with the code.
2. **It is keeping up and the data is late.** `watermark_lag_seconds` is large and nothing is
   waiting. See [event time and watermarks](/help/topics/event-time-watermarks).
3. **It cannot be fed as fast as rows arrive.** Something downstream of the source is the limit.
   That is this page.

## Is it backpressured at all

On the operations dashboard, and on the query's own plan, the number to read first is the **blocked
fraction**: the share of wall clock a writer into this query's lanes spent with nowhere to put a
row.

| Blocked fraction | Read it as |
|---|---|
| Near zero | Headroom. Whatever is slow, it is not this |
| Over about 0.2 | Worth looking at |
| Near 1 | The lane is the limit, and everything upstream of it is waiting |

Beside it, **inbox depth against inbox cells** says how full the queue is *right now*. A lane at
0.9 blocked with a nearly empty inbox is bursty; one with a full inbox is saturated. Depth is an
instantaneous gauge, and whoever scrapes it is the one sampling it — a burst between two scrapes is
invisible, and the dashboard does not pretend otherwise.

## Whose fault is it

The blocked fraction is the **lane's** view, and it counts every writer into that lane. On a lane
the query owns, that is the query. On a shared lane (`pravaha.lane.multiplex.*`) it is not, and the
difference is the diagnosis:

| The query's own waiting | Blocked fraction | What it means |
|---|---|---|
| High | High | This query's writer is waiting and so is the lane. It is the one to look at |
| Low | High | The lane is full of somebody else's rows. Look at the lane's own row and at the other queries on it |
| High | Low | The source is being held off by the inbox's high/low watermark hysteresis rather than by a full inbox: it was paused at the high mark and is waiting to fall back to the low one |

The dashboard's **Shared lanes** table is the other half of that: each lane's blocked fraction, its
inbox depth, and how many queries are on it. With lane sharing off there are no lanes in it, and
the table says that rather than drawing an empty one.

The query's own waiting is published as episodes, not rows: a writer opens an episode the first
time it finds no room and closes it the first time it finds room again. **A source held off for an
hour is one episode**, which is why the count is small and the seconds are not.

## Which operator

With `pravaha.metrics.operators` on, the plan carries a block per node, keyed by the ids the graph's
own nodes use. The console draws them on the graph and marks the one the engine calls the
bottleneck.

| Per operator | How exact |
|---|---|
| Rows in, rows out | Counts, exact to the last batch boundary. Against each other they are the operator's selectivity |
| State bytes | Exact where the operator keeps state off the heap; absent where it keeps none, or keeps it on the heap and there is no byte count that is not a guess |
| Watermark | The **query's**, repeated on every node. An advance reaches every operator in one call on the lane thread, so they cannot differ; it is per node only so it can be drawn beside the operator you are looking at |
| Self time | **Sampled**: one row in every 1,024 that enters the pipeline is timed at every operator on its path. `sampledRows` is published beside it, so a share read off four samples can be recognised as one |

**The bottleneck is measured, not inferred.** It is the node most of the sampled time went into — not
the node that drops the most rows. A filter that throws away 99 % of its input is doing its job
cheaply, and an engine that guessed from selectivity would accuse it every time.

Two things sampling cannot see, and the console does not claim otherwise: a workload beating in step
with the 1-in-1,024 period, and an operator that only runs on a watermark tick, which is never on a
sampled row's path at all.

## When the numbers are not there

`operatorMetrics` is absent for three different reasons, and the plan says which in words rather
than letting a reader infer it from a blank:

| What the plan says | Why |
|---|---|
| Not published for a plan that is not running | This is SQL nobody registered. There is nothing running to measure. Register it and read its plan |
| `pravaha.metrics.operators` is off on this node | The counters were never built into this query's stages. Set it and **re-register the query**: the switch is read when a query is compiled, not when it is read |
| Measured | The numbers are there |

None of the three is drawn as zeros. A counter reading zero and a counter that was never built are
different facts, and only one of them means the operator did no work.

## What it costs to measure

Per-operator counting is **off by default** because it is not free: 7.9 %, 8.2 % and 8.6 % over three
runs against a three-operator plan with no lane and no view commit beside it — the largest share the
wrappers will ever take — on the reference machine. `pravaha_metrics_operators_enabled` is 1 on a
node that has it on, so a dashboard that finds no per-operator numbers can say which of the two it
is looking at.

Turning it on for a diagnosis and off afterwards is the expected way to use it.
