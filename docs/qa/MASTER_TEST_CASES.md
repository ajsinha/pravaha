# Master test case list — index and authoring contract

The detailed cases live in `cases/<AREA>.md`, one file per area. This is the index: what each area
owns, the ID range it uses, the dimensions it must enumerate exhaustively, and the case budget it is
held to. **Nothing is executed until every area's file exists and is reviewed.**

"CQL" throughout means **continuous query** — register, window, watermark, and the **streaming
result** it delivers — not a separate language. There is no separate query dialect in this codebase.

**A continuous query is judged by what it streams out, not only by what a view holds.** Round 1
tested the view-read path heavily (`pravaha query`) and the subscription path barely, which is the
wrong way round: a materialised view answered on demand is the convenience, and the continuous
stream of changes is the product. Area `STRM` exists for that and is budgeted accordingly.

## Authoring contract — every case, without exception

```
## <AREA>-nnn — one-line title
**Intent:** what this actually checks, and why it is worth a case.
**Falsifier:** the observation that would prove the feature broken. A case without one is not a case.
**Setup:** streams, schemas, config, data — exact, reproducible values.
**Steps:** the commands or calls, in order.
**Expected:** the precise result. Numbers computed by hand, shown as arithmetic, not as "the right total".
**Vacuity:** why this cannot pass with the feature removed. Required wherever state or timing is involved.
```

Three rules that come from round 1's failures:

1. **Hand-compute every expected value.** A windowed sum of 202 is checked as `100 + 102`, not as
   "the sum". Round 1 had a row-count assertion that passed while 200,000 rows collapsed into 500.
2. **State the falsifier before running anything.** A pause case passed because the source ran dry.
3. **A combination is a case.** "FLOAT64 in an aggregate" found a silently wrong answer; the other
   fifteen types in that position were never written down, so they were never wrong — they were
   never asked.

## Areas

| Area | ID range | Owns | Budget |
|---|---|---|---|
| `WIN` | 001–210 | Windowing: TUMBLE, HOP, SESSION, CUMULATE. Size, slide vs size, open-window count, key cardinality, row volume, boundary rows, close triggers, empty windows, restart mid-window | 210 |
| `TIME` | 001–120 | Event time and watermarks: declaration, per-plugin support, out-of-orderness, **partition quiet time**, tick vs window size, lateness, ordering, clock health, idle exclusion | 120 |
| `TYPE` | 001–150 | 16 types × 11 positions; literals of every type; narrow integers; overflow; null; boundary values; wire serialisation | 150 |
| `AGG` | 001–110 | 5 aggregate kinds × types × {global, keyed, windowed} × {null, empty, single row, retraction}; COUNT(*) vs COUNT(col) vs COUNT(DISTINCT) across all three operators | 110 |
| `JOIN` | 001–060 | Inner, lookup `FOR SYSTEM_TIME AS OF`, multi-key, no match, all match, empty sides, late side, lane routing, eviction, refusals | 60 |
| `STRM` | 001–120 | **Streaming results** — the continuous delivery path, which is what a continuous query is *for*. `pravaha subscribe`, Flight `getStream` on a subscription ticket, `ViewChangeListener`. Every change delivered as it happens: insert, update (retract+insert), retraction, net-zero. Ordering, duplicates, gaps. Slow subscriber, disconnect mid-stream, reconnect, many subscribers on one query, subscriber during pause / drop / restart. Backpressure toward the subscriber. End-to-end latency from row arrival to delivery. Row filters honoured on the stream, not only on the read | 120 |
| `LIFE` | 001–130 | Continuous query lifecycle: register, pause, resume, drop, re-register, sharing by fingerprint, subscriptions, the 4 read-consistency modes, restart at each point | 130 |
| `INCR` | 001–070 | Z-set correctness: insert, retract, update, net-zero, out-of-order retraction, retraction of an absent key — per operator. A continuous result must equal a batch recomputation | 70 |
| `SQLX` | 001–190 | The 77 documented constructs × happy / boundary / null / refusal; hostile and malformed SQL; reserved words; unicode; very large queries | 190 |
| `CFG` | 001–110 | 37 config keys × valid / invalid / default / interaction; contradictory combinations; startup refusals | 110 |
| `ERRC` | 001–104 | Every error code: reachable, documented, meaning one thing, message actionable | 104 |
| `API` | 001–180 | CLI (9 commands × flags, exit codes, stdin, unicode, failure), REST (6 paths × method, auth, body, content type), Flight (5 verbs + prepared statements, DoPut, tickets, metadata) | 180 |
| `SDKX` | 001–080 | Java SDK, Java Flight SDK, Python SDK, console UI — connect, auth, TLS, query, register, subscribe, error surfacing | 80 |
| `STATE` | 001–110 | Checkpoints (write, prune, restore, collision, permissions), registry journal (append, replay, compaction, corruption), recovery, cluster modes | 110 |
| `PERF` | 001–060 | Idle cost, throughput, reader/writer contention, leaks over cycles, backpressure, arena limits, scale ceilings | 60 |
| `AERO` | 001–045 | Aerospike source/sink/lookup, 4 strategies, pushdown equivalence, exactly-once claims, state tier | 45 |
| `SECX` | 001–095 | Policy × auth × TLS × row filters × every verb on both transports; lineage; ownership; disclosure through error messages | 95 |
| `DOCX` | 001–060 | Every documented instruction executed literally; config keys audited both directions; onboarding walked | 60 |
| | | **Total** | **~1,804** |

Existing files (`INGEST`, `SEC`, `DEPLOY`, `SQL`, `DOC`, `CQ`) are round 1's and stay as the record
of what was true then. The areas above supersede them for coverage purposes.
