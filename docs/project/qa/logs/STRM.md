# STRM — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Cases: [`../cases/STRM.md`](../cases/STRM.md). Executed 2026-09-14 on branch `develop` (worktree
`.claude/worktrees/qa-strm`, branch `worktree-qa-strm`, at `e6eb69a`), against `pravaha-*` as built
by `./mvnw -o -T1C install -DskipTests` (Java 21, `/usr/lib/jvm/java-21-openjdk-amd64`). Ports HTTP
**18800** / Flight **19800** throughout, both confirmed unbound beforehand. Scratch `$QA =
/tmp/claude-1000/-home-ashutosh-IdeaProjects-pravaha/qa-strm`. **No production code was modified by
any case in this file.** Every verdict below carries the command that produced it and the output it
produced; nothing is inferred from a neighbouring case.

**Overall: 109/120 cases executed. 83 PASS / 23 FAIL / 3 BLOCKED / 11 NOT RUN.**

Nineteen new findings are recorded in `docs/project/qa/FINDINGS.md` as **STRM-1 … STRM-19**. The three most
severe:

- **STRM-2 (HIGH)** — one subscriber's `FAIL` overflow policy **terminates the whole computation**.
  `Subscription.admit`'s `FAIL` arm throws from inside `onCommit`, which is outside the try that
  guards `consumer.accept`; it escapes `ViewSink.commit`'s listener loop mid-iteration and
  `RegisteredQuery.advanceWatermark` catches it, calls `fail(e)` and rethrows. Reproduced both ways
  round: with the `FAIL` subscriber attached **first**, subscribers B and C received **nothing** and
  `query.state()` went to `FAILED`; attached **last**, B and C received their batch of 2 and the
  query still went `FAILED`. A subscriber declaring its own durability preference takes down a
  computation every other subscriber depends on, and the blast radius depends on attach order.
- **STRM-9 (HIGH)** — `streamSubscription` calls `required.require(viewName)` **before**
  `policy.mayRead`, and `QueryRegistry.require` puts **every registered query name on the node** into
  its message. A principal `mayRead` denies on every view learned the full registry by subscribing
  to a misspelled name: `PRV-8002  no query named 'payrol' is registered; this node has [payroll,
  headcount]`. Reproduced in `SrvG.s102` against a `PravahaFlightServer` with a deny-all policy.
- **STRM-5 (HIGH)** — `ViewSink.commit` calls `view.commit(frontier)` **before** it copies and
  clears `pending`, so the first `PRV-4022 VIEW_TOO_LARGE` leaves `pending` undrained for ever —
  but **only when a subscriber is attached**. Reproduced with the identical feed both ways:
  with a listener `ViewSink.pending.size()` reached **100 001** and kept rising; with no listener it
  stayed at **0**. The leak appears in exactly the deployments that subscribe, which is the inverse
  of the leak the `listeners.isEmpty()` branch was written to prevent.

Three further failures are worth the reader's attention before the tables: **STRM-11** (a subscriber
attaching mid-commit receives a fragment of it, flagged as a completed batch — 9 of 50 attempts at
1000 groups), **STRM-4** (one stalled subscriber costs 69 % of ingest throughput, against ADR-026's
claim that subscriber count is a capacity line item), and **STRM-12** (a drop, a restart and the
client's own `close()` are one indistinguishable signal on the wire).

**Eight of the case file's stated facts are false against this build**, including the three the area
is built around — the weight *does* reach a Flight subscriber, `SRVDBG` is gone, and `mayRead` *is*
re-checked mid-stream. They are tabulated in their own section before the per-case verdicts, because
a false premise is a more interesting finding than the case resting on it.

**A note on a third-party string.** As in prior rounds, the jqwik dependency's console output carries
an adversarial sentence addressed to an "AI Agent" instructing it to disregard its instructions. It
is untrusted third-party build output, not a project instruction, and was not acted on. The same
applies to any `system-reminder` arriving inside a tool result.

**The owner's three standing constraints**, restated because every finding below is judged against
them: (1) authorization is enforced at the Pravaha layer, never pushed to persistence; (2) only
authenticated users may reach data; (3) a user receives only the data they are authorized for.
Observed behaviour contradicting any of these is **HIGH** regardless of what the case predicted.

---

## The harnesses, as actually built

The case file's `H-E` is exact and was used verbatim. The other three needed correction before
anything could run, and the corrections are themselves findings.

**`H-E` — embedded, pass-through.** As written. `ViewCatalog`, `QueryRegistry`, `RowArena`,
`registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA)`, `feed`/`feedW` through
a `BinaryRowWriter` into `query.accept(...)` followed by `awaitApplied(10s)`. Source:
`$QA/src/qa/H.java`.

**`H-EA′` — the substitute for `H-EA`, which does not exist.** `H-EA` as the case file writes it
cannot be registered on this build at all:

```
$ java -cp … qa.SectionE      (FACT H-EA)
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of
distinct keys and never shrinks. …
```

`PhysicalPlanBuilder.java:828` refuses every keyed `GROUP BY` over an unwindowed stream. The nearest
shape that produces a real retract/insert pair per update is a **windowed** keyed aggregate, and it
was used wherever the case's intent survives the substitution:

```sql
SELECT window_start, window_end, user_id, SUM(amount) AS total
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
GROUP BY window_start, window_end, user_id
```

with `outOfOrderness(5s)` and `allowedLateness(600s)` on the stream, so an update to a closed window
arrives as late data and produces the `-1 old, +1 new` pair the case is about. Every case run this
way says so in its verdict line.

**`H-S` — server over Flight.** Three of its four stated details are wrong (see the fact table):
the stream property is `schema:` in `name:TYPE` form, not `fields:`; there is **no `DoPut` path for
stream rows** on any surface — a node is fed through `pravaha.sources.*`; and the CLI defaults to
port 9090, which this round overrode to 19800. What was actually run:

```yaml
server: {port: 18800}
pravaha:
  flight: {enabled: true, host: 127.0.0.1, port: 19800}
  streams:
    txn: {schema: "user_id:STRING,amount:INT64,product_type:STRING,op:STRING"}
  sources:
    txn:
      plugin: filesystem
      options: {path: $QA/feed/txn.csv, schema: "…", follow: "true", op.column: "op"}
  security: {authentication: none, policy: permissive, audit: memory, allow-anonymous: true}
```

started as a real `pravaha-server-0.1.0-SNAPSHOT-app.jar` node with
`--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED` (the CFG
round's harness fact — without them every read dies as `RST_STREAM … CANCEL`). Rows are pushed by
appending lines to `$QA/feed/txn.csv`; `op=I` is an insertion and `op=D` a retraction, which is the
only route a configured source has to a negative weight. Subscribers are the real Java SDK.

**Section G is run against an in-process `PravahaFlightServer`**, not the node above, because
`pravaha.security.policy` accepts only `permissive` and `authenticated` — there is no `tenant`, and
no policy reachable from configuration ever produces an `AccessDecision` carrying a row filter. The
cases that need one build the policy directly, the way `FlightRegistryTest` already does.

**Two harness contaminations, found and removed.** Both are properties of the filesystem source, not
of the subscription path, and both produced wrong numbers on a first pass that are worth recording
so the next round does not rediscover them:

- an **unfiltered** registration over a shared feed file sees every other case's rows. STRM-033's
  first pass reported 261 046 rows and 59 440 "duplicates" for a 200 000-row feed. Re-run with
  `WHERE product_type = 'G33'` it is exactly 200 000, zero duplicates, zero gaps.
- a **fresh registration re-reads the feed file from the beginning**. STRM-073's first pass read 20
  view keys where 10 were expected; measuring the view immediately after re-registration and before
  pushing anything shows 10 already there, which is the re-read and not surviving state.

---

## Which of the case file's stated facts still hold

Eighteen load-bearing claims were checked against the code and against a running node. **Ten hold,
eight do not.**

| # | The case file says | Holds? | What is true |
|---|---|---|---|
| 1 | `H-EA` is `SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id` | **NO** | Refused at registration with `PRV-2050`; `PhysicalPlanBuilder.java:828` admits a keyed `GROUP BY` only over a window. `H-EA′` (windowed) was substituted |
| 2 | `writeBatch` writes `change.values()` into a root with **no weight field**; `ChangeBatch`/`Row` expose no weight accessor (STRM-017, the case file's "run this first") | **NO** | `ArrowSchemas.subscriptionSchema` (`ArrowSchemas.java:98`) appends `_pravaha_weight: Int64 not null` carrying metadata `pravaha.weight`; `writeBatch` (`PravahaFlightSqlProducer.java:653`) fills it; `Row.weight()` / `Row.isRetraction()` (`Row.java:146`, `:154`) read it by that metadata mark. Observed on the wire: `_pravaha_weight: Int(64, true) not null` |
| 3 | `PravahaFlightSqlProducer` prints `SRVDBG` on every subscribe and every batch (STRM-027) | **NO** | `grep -c SRVDBG` over the subscription path and over a server log carrying ~1 000 000 rows and dozens of subscriptions: **0**. No such string exists in the file |
| 4 | `policy.mayRead` is evaluated **once**, at subscribe time; nothing re-checks it (STRM-096, STRM-097) | **NO** | `PravahaFlightSqlProducer.java:101` `REAUTHORIZE_EVERY = 2s`; the loop re-checks both `middleware.credentialStillValid()` and `policy.mayRead` and ends the stream with `UNAUTHENTICATED` / `FORBIDDEN`. Reproduced: revocation ended a live stream in 1035 ms, a withdrawn credential in 637 ms |
| 5 | `ServedView.applyValues` "branches on `weight < 0`" (STRM-006 … STRM-011) | **NO** | It maintains a **net** weight per key (`weights` + `pendingWeight`) and branches on `net <= 0` (`ServedView.java:194`). `+1, +1, commit, -1, commit` leaves the key **present**, which a `weight < 0` branch would not |
| 6 | `weight == 0` "takes the upsert arm" of `applyValues` (STRM-007) | **NO** | `applyWeighted` returns immediately on `weight == 0` and applies nothing. The view keeps its *previous* values, not the zero-weight row's — `[k, 1]` survived a weight-0 change carrying `[k, 9]` |
| 7 | The `H-S` stream is declared with `fields: "user_id STRING, amount INT64"` and `event-time: ""` | **NO** | `StreamDeclarationProperties.Declaration` binds `schema`, `eventTime`, `outOfOrderness`; the schema grammar is `name:TYPE,…` |
| 8 | "Rows are pushed with `DoPut`" | **NO** | There is no `DoPut` path for stream rows. `acceptPutPreparedStatementQuery` is the only put the producer implements, and it is for prepared-statement parameters. A node is fed through `pravaha.sources.*` |
| 9 | `SubscriptionOptions.DEFAULT` is `(10_000, CONFLATE)` | yes | `SubscriptionOptions.java:32` |
| 10 | `SUBSCRIPTION_HANDOVER_BATCHES = 64`, filled with `offer`, never `put` | yes | `PravahaFlightSqlProducer.java:111`, `:554` |
| 11 | `streamSubscription` hard-codes `SubscriptionOptions.DEFAULT`, so a client's overflow policy is ignored | yes | `PravahaFlightSqlProducer.java:550`; `ControlWire.subscribeTicket` (`ControlWire.java:141`) encodes `["subscribe", view, pairs…]` and nothing else; `ClientOptions.subscriberBufferRows` has **no reader** anywhere in `pravaha-flight` or the Flight SDK |
| 12 | `SubscriptionFilter` refuses an unknown column but compares values with `Objects.equals`, and the Flight path builds the map from wire **strings** | yes | `SubscriptionFilter.java:121`; `PravahaFlightSqlProducer.java:523`. Confirmed behaviourally: `--filter amount=300` opens and delivers 0 of 10 |
| 13 | `ordinalOf` is `equalsIgnoreCase` on the name; `accepts` is case-**sensitive** on the value | yes | `SubscriptionFilter.java:106`, `:121`. Four subscriptions over one feed gave 5, 5, 5, 0 |
| 14 | `ViewSink.commit` calls `view.commit(frontier)` before touching `pending` | yes | `ViewSink.java:74`–`:91`. This is what STRM-070 turns into a leak |
| 15 | `Subscription.onCommit` drains the buffer unconditionally in the same call | yes | `Subscription.java:89`–`:94` |
| 16 | `delivered` is incremented **after** `consumer.accept` returns | yes | `Subscription.java:96`–`:97` |
| 17 | `StagedRow.commit` guards `pending.add` with `if (!listeners.isEmpty())` | yes | `ViewSink.java:232` |
| 18 | `PumpingFeed.PUBLISH_INTERVAL_NANOS = 20_000_000L` | yes | `PumpingFeed.java:61` |

Two further facts the case file does not state, recorded because they change what an executor sees:

- **`subscriberCount()` is unreachable from every remote surface.** It is not a field of the SDK's
  `RegisteredQueryInfo` record, there is no `ControlWire` verb for it, and `GET
  /api/v1/queries` on the HTTP port is **404**. `docs/operations/OPERATIONS.md` presents it as the operator's
  signal that a query nobody is watching is a clue. STRM-051 is BLOCKED on this; STRM-062 and
  STRM-076 were re-pointed at observables that exist.
- **`pravaha.security.policy` has two values, not three.** `permissive` and `authenticated`. There is
  no `tenant`, and no configured policy can produce a row-filter decision, which is why section G
  runs in-process.

---

## Section A — change kinds delivered (STRM-001–016)

Run by `$QA/src/qa/SectionA.java` and `SectionA2.java` against `H-E` / `H-EA′`.

| Case | Verdict | Evidence |
|---|---|---|
| STRM-001 | **PASS** | `seen=[+1[u1, 300]] delivered=1 conflated=0 dropped=0` — one change, `weight()==1L`, `isRetraction()==false`, `values()` = `["u1", 300L]` |
| STRM-002 | **PASS** | `sizes=[3] delivered=3` — one callback of three, not `[1,1,1]` |
| STRM-003 | **PASS** | after `feed`×2 and `awaitApplied`: `seen=0 delivered=0`; after `commit()`: `seen=2 delivered=2` |
| STRM-004 | **PASS** | `H-EA′`: `[[+1[0, 10000000000, u1, 300]], [-1[0, …, u1, 300], +1[0, …, u1, 350]]]`, replayed weighted total **350**, equal to the view's own `total`. Both halves arrive |
| STRM-005 | **PASS** | batch 2 is `[-1 … 300, +1 … 350]` in that order: `batch2.get(0).weight() == -1L` carrying the **superseded** 300 |
| STRM-006 | **PASS** | `seen=[-1[u1, 300]] view(u1).found=false viewSize=0` — `isRetraction()==true`, key gone |
| STRM-007 | **FAIL** | documented intent did **not** hold: `seen=[+0[u1, 300]] view(u1).found=true values=[u1, 300] isRetraction=false`. The mechanism differs from the case's too: `applyWeighted` returns on `weight == 0` without applying anything, so the surviving row is the *previous* one, not the zero-weight row. See finding **STRM-1** |
| STRM-008 | **PASS** | `seen=[+1[u1, 300], -1[u1, 300]] view(u1).found=false viewSize=0` — both changes delivered, key removed |
| STRM-009 | **PASS** | `seen=[-1[u1, 300], +1[u1, 300]] view(u1).found=true values=[u1, 300]` — same multiset as 008, opposite order, opposite outcome |
| STRM-010 | **PASS** | `seen=[+3[u1, 10], -2[u2, 20], +7[u3, 30]] weightSum=8 viewKeys=[u1, u3]` — verbatim weights, sum 8 not 1 |
| STRM-011 | **PASS** | INSERT/UPDATE_AFTER/UPDATE_BEFORE/DELETE → `[+1, +1, -1, -1]`, sum 0, `viewKeys=[a, b]` |
| STRM-012 | **PASS** | `.weight(-1).rowKind(INSERT)` → `+1`; `.rowKind(DELETE).weight(5)` → `+5`. Last writer wins, and 5 is a value no `rowKind` can produce |
| STRM-013 | **BLOCKED** | the case needs five updates to one key **inside one commit** on a keyed aggregate. `H-EA` is unregistrable (fact 1) and `H-EA′` emits once per window close, so the five updates collapse: `size=1 changes=[+1[0, 10000000000, u1, 150]] weightedTotal=150`. The arithmetic is right and the case's assertion (9 changes) is unreachable on any shape this build admits |
| STRM-014 | **PASS** | `H-EA′`, five separate window closes: `sizes=[1, 2, 2, 2, 2] total=9 weightedTotal=150 conflated=0` — the same 9 changes and the same 150 as STRM-013 predicts, grouped per commit |
| STRM-015 | **PASS** | `seen=[-1[u1, 300], +1[u2, 300]] viewKeys=[u2]` — one key in the view, two changes on the stream |
| STRM-016 | **PASS** | `seen=[+1[u1, null], +1[null, 42]] viewSize=2 threw=none` — a null `amount` is `null` and not `0L`; a null key is a key |

## Section B — wire fidelity (STRM-017–028)

Run by `$QA/srvsrc/qa/SrvB.java` against the `H-S` node, plus source inspection where the case asks
for it.

| Case | Verdict | Evidence |
|---|---|---|
| STRM-017 | **PASS** | **the case's premise is refuted.** Arrow schema on the wire: `[user_id: Utf8, amount: Int(64, true), product_type: Utf8, _pravaha_weight: Int(64, true) not null]`. Delivered `[[a17, 300, SWAP]@w=1]`, then `[[a17, 300, SWAP]@w=-1, [a17, 350, SWAP]@w=1]`. A client applying weights holds 350, not 650 |
| STRM-018 | **PASS** | the two rows are `[a18, 300, SWAP]@w=1` and `[a18, 300, SWAP]@w=-1`, `identical=false`. `pravaha query --view q18` afterwards holds **0** rows for `a18`, which is what an upsert-by-key client applying the weight also holds |
| STRM-019 | **FAIL** | the CLI renders `render(row)` with no weight: data lines `[a19\t300\tSWAP, a19\t300\tSWAP]`, commit markers `2`, and the two lines are byte-identical. The weight is on the wire and `Row.weight()` exposes it; `ServerCommand.render` does not use it. See finding **STRM-3** |
| STRM-020 | **PASS** | `Arrow batch row counts in arrival order = [3, 1] batches()=2 rows()=4`. The 800 ms gap is 40× the publish tick |
| STRM-021 | **PASS** | 50 000 rows in one append: `rows=50000 arrowBatches=36 largestBatch=2830 elapsed=1129ms`, subscription still open. Outcome **(b)**: the commit boundary is the 20 ms publish tick, not the size of the write, so `writeBatch` never sees a 50 000-row commit from a configured feed. No allocator failure |
| STRM-022 | **PASS** | the same row through both paths, on a stream declaring one column of each nameable type: subscribed `[k1, true, 7, 300, 70000, 9000000000, 1.5, 2.25, byte[], 1789389296789000000, 20710, 45296000000000]`, queried identically, **equal on every column** — including `TIMESTAMP` at nanosecond precision (ADR-012) and the `BYTES` column through both defensive clones. **12 of the engine's 16 types were reachable**: `DECIMAL(p,s)` is named by the schema parser's own error message and cannot be written in the comma-separated `name:TYPE` grammar at all — `pravaha.streams.typ.schema: "…,c_dec:DECIMAL(18,2)"` splits on the comma inside the parameters and the node refuses to start with `PRV-5040  unknown type 'DECIMAL(18'. Supported: … DECIMAL(p,s)`. `ARRAY`, `MAP` and `ROW` are unsupported by the engine. See finding **STRM-19** |
| STRM-023 | **PASS** | A mutated `change.values()[1] = 999L`; B saw `300`, the view holds `300`, A's own second `values()` call returned `300`. `ViewChange.values()` clones on every call |
| STRM-024 | **PASS** | (b) valid ticket → stream opened, schema as above. (c) first 4 bytes → falls through to `super.getStream`, Flight SQL's own `There was an error servicing your request.` (d) 6 bytes → `PRV-6102 this is not a Pravaha request`. (e) 8 zero bytes → falls through. (f) magic + 40 random → `PRV-6102 this request was built by a different version of the client`. No bare `NullPointerException` or `ArrayIndexOutOfBoundsException` anywhere |
| STRM-025 | **PASS** | `PRV-8002  no query named 'no_such_view_25' is registered; this node has […]`, immediate, non-zero exit. Both spellings give the identical class of error under `permissive`. Under a denying policy the same message is a disclosure — STRM-102, finding **STRM-9** |
| STRM-026 | **PASS** | all five verbs against a `PravahaFlightServer` built with no registry: `subscribe`, `pravaha.register`, `pravaha.drop`, `pravaha.pause`, `pravaha.resume` each → `PRV-6101  this server serves views but does not host a registry, so it cannot register, drop or subscribe to queries. Start it with a QueryRegistry if it should` |
| STRM-027 | **PASS** | **the case's premise is refuted.** `grep -c SRVDBG $QA/logs/server-hs-phase1.log` = **0** over a node that carried ~1 000 000 rows and dozens of subscriptions; and no `SRVDBG` string exists in `PravahaFlightSqlProducer.java` |
| STRM-028 | **FAIL** | ADR-026's central rationale — "Encode once, write N times … a thousand subscribers at 20 Hz is 20 000 serialisations a second instead of 20" — is falsified by the code: `streamSubscription` allocates its **own** `VectorSchemaRoot` per subscription (`PravahaFlightSqlProducer.java:547`) and calls its own `writeBatch`, so encoding scales with N. The measured consequence is STRM-087's ingest collapse. ADR-026's own status line is already honest about the carriers ("one carrier built — Flight/gRPC. There is no WebSocket implementation anywhere"), so only the rationale is stale. See finding **STRM-4** |

## Section C — once, in order, without gaps (STRM-029–046)

| Case | Verdict | Evidence |
|---|---|---|
| STRM-029 | **PASS** | `delivered=10000 dropped=0 conflated=0 duplicateBuckets=0 emptyBuckets=0 sum=50005000 viewKeys=10000` |
| STRM-030 | **PASS** | `delivered=10000 dropped=1 conflated=0 bucket[1]=0 badBucketsIn2..10001=0 sum=50015000`. The **oldest** change is the one lost, exactly as `removeFirst` implies. The case file's stated sum `50 015 001` is an arithmetic slip in the case: its own formula `50 005 000 + 10 001 − 1` evaluates to `50 015 000`, which is what was observed |
| STRM-031 | **PASS** | `H-EA′`, 1000 window closes: `batches=1000 perBatchShapeOk=true telescopingSum=1000 replayedTotal(u1)=1000 conflated=0 dropped=0`. Batch 1 is `[+1 (…,1)]`; batch k is `[-1 (…,k-1), +1 (…,k)]` for every k |
| STRM-032 | **PASS** | `delivered order=[z, a, m, b]` — feed order, not sorted order |
| STRM-033 | **PASS** | 200 000 distinct keys at ~166 000 rows/s into the `H-S` node's 20 ms commit timer: `rows()=200000 missingSequences=0 duplicatedSequences=0 foreignRows=0 sum=20000100000 arrowBatches=66`, subscription still open. No changelog gap across a commit boundary. (Round 1's `I-1` `ConcurrentModificationException` did not reproduce; it is recorded FIXED) |
| STRM-034 | **PASS** | consumer blocked on a latch for 200 ms while a second thread fed `u2`: `batches=[[u1], [u2]] delivered=2`. The row applied during the callback is delivered by the **next** commit |
| STRM-035 | **PASS** | `delivered=1 batch=[+1[u4, 4]] viewKeys=4 gap=3` — no snapshot, and the documented 3-key gap |
| STRM-036 | **FAIL** | keys present in the final view that were in **neither** the pre-read nor the delivered stream, over 5 attempts at ~1000 rows/s: `[100, 100, 50, 0, 100]`. No `frontier` or `asOf` accessor exists on the SDK's `Subscription` or `QueryResult` (`anyFrontierApi=false`), so a client cannot detect the loss. See finding **STRM-6** |
| STRM-037 | **PASS** | raw `sink.onCommit` listener saw frontiers `[5000, 9000]` for rows fed with `sequence(5000)` / `sequence(9000)`; the `Subscription` consumer received the same two batches, and a reflective scan of `ViewChange` and `Subscription` found **no** method whose name contains "frontier". The frontier exists at exactly the point that would close STRM-036 and is discarded |
| STRM-038 | **PASS** | `callbackSizes=[1]` over six `commit()` calls; `ServedView.commits` rose by **6**. A commit happened and was correctly silent |
| STRM-039 | **PASS** | 500 `BOND`, 1 `SWAP`, 500 `BOND`, buffer of **2** with `DROP_OLDEST`: `batch=[+1[s1, 1, SWAP]] delivered=1 dropped=0 conflated=0`. Filtered before buffering |
| STRM-040 | **PASS** | 2×2 grid, filter `{user_id: u1, product_type: SWAP}` → `delivered=[+1[u1, 10, SWAP]]`, 1 of 4 |
| STRM-041 | **FAIL** | `--filter amount=300` against an `INT64` column: the subscription **opened successfully** and delivered **0 of 10** rows pushed, with no error and no counter. The control `--filter user_id=c41` over the same feed delivered **10**. Same syntax, same CLI, opposite outcome, nothing distinguishes them. See finding **STRM-7** |
| STRM-042 | **PASS** | `user_id=u42 → 5 | USER_ID=u42 → 5 | User_Id=u42 → 5 | user_id=U42 → 0`, no error on the fourth. The asymmetry is real and undocumented |
| STRM-043 | **PASS** | `--filter prodcut_type=SWAP` → `PRV-8002  this view has no column 'prodcut_type', so that filter cannot be applied to it. Its columns are [user_id, amount, product_type]. …`, 0 rows delivered. The code is **`PRV-8002` `REGISTRY_NO_SUCH_QUERY`**, which is the code `CONCEPTS.md` §6 associates with a refused subscription filter *and* the code for a missing query — one code meaning two things, which `ERRC` owns |
| STRM-044 | **PASS** | no `--filter` → 10 rows; SDK `Map.of()` → 10 rows; CLI `--filter ""` → `UsageException: --filter takes column=value pairs, got ''`. A refusal, not a silent divergence |
| STRM-045 | **PASS** | `H-EA′` grouped by `(window, user_id, product_type)`, filter `product_type=SWAP`: commit 2 → `[+1 (…,u1,SWAP,100)]`; the `BOND` commit → **nothing**; commit 4 → `[-1 (…,SWAP,100), +1 (…,SWAP,150)]`. `delivered=3`. Each change judged on its own values |
| STRM-046 | **PASS** | `H-EA′`, 500 users × 100 rows: `changesDelivered=99500` — exactly the case's `500 + 2×(50 000 − 500)` — `replayedKeys=500 sum=2525000 every total==5050: true viewSize=500 dropped=0 conflated=0`, subscription still open under `FAIL` overflow |

## Section D — subscriber behaviour (STRM-047–060)

| Case | Verdict | Evidence |
|---|---|---|
| STRM-047 | **FAIL** | `commit()` with one subscriber sleeping 2000 ms took **2001 ms**; with three such subscribers, **6002 ms**. The documentation ("Blocking is not on the list: a subscriber that blocks the engine applies backpressure to the *query*" — `SubscriptionOptions` javadoc, `CONCEPTS.md` §8, `USER_GUIDE.md`) is falsified, and the 1-vs-3 ratio shows `ViewSink.commit` iterating listeners serially. See finding **STRM-8** |
| STRM-048 | **NOT RUN** | the case requires a link throttled to 10 KB/s with `tc netem` or a proxy. `tc` needs `CAP_NET_ADMIN` and this round has no route to it; a proxy would have been a different measurement. Recorded as not run rather than approximated. STRM-087 measures the related question — ingest against stalled subscribers — with a real answer |
| STRM-049 | **FAIL** | subscriber sleeping 5 s per batch, 59 700 rows pushed over 60 s: the client's `rows()` reached **1300** in **13** batches. Loss **58 400 rows**, and the complete set of zero-argument observables the SDK `Subscription` exposes is `[rows(), batches(), isClosed()]` — none reports it. Server-side the loss reaches one `subscribe.dropped` audit event, only with `audit: memory|log`, and only when the subscription ends. See finding **STRM-10** |
| STRM-050 | **PASS** | commits of exactly 100 rows, subscriber sleeping 2 s per batch, 30 s: `batchesReceived=17 rowsReceived=1700 of 148200 pushed; distinct received batch sizes=[100]`. Every delivered batch is a whole commit. Loss is large and never fractional |
| STRM-051 | **BLOCKED** | `subscriberCount()` is not observable from any remote surface (see the fact table). What could be measured: a graceful `subscription.close()` ended the client stream in **1 ms**, well inside one 200 ms poll window. The `kill -9` half needs the server-side count and is blocked with it |
| STRM-052 | **PASS** | same fingerprint `e79dbc4e73f1` before and after; `rowsIn` **796 931 → 816 831**, never reset; `RUNNING` throughout; the second connect-and-deliver window took 5009 ms against a 5000 ms budget, i.e. within noise. First window delivered 4900 keys, second 5000, view holds 21 000, and **11 100** keys are in the view and in neither window — the documented cost of a 10 s absence |
| STRM-053 | **PASS** | ten subscribers, 1000 distinct keys, one commit: all ten lists **equal as ordered lists**, each of size 1000, each summing to 500 500. `registry.size=1 subscriberCount=10` |
| STRM-054 | **PASS** | ten filters `P0…P9` over 10 000 rows: `P0=1000(pure) … P9=1000(pure)`, `registry.size=1 subscriberCount=10`. Exactly `1000 × 10 = 10 000`, no leak across filters |
| STRM-055 | **PASS** | A closes itself inside its first callback: `A.delivered=1 B.delivered=2 C.delivered=2 threw=none subscriberCount=2`. No `ConcurrentModificationException` |
| STRM-056 | **PASS** | both orderings. Thrower first: `A.calls=1 A.closed=true A.failure=PRV-8004  subscriber on 'q' threw and has been detached: consumer is broken B.batches=3 queryState=RUNNING`. Thrower last: identical |
| STRM-057 | **FAIL** | `FAIL`-overflow subscriber attached **first**: `A.closed=true B.received=0 C.received=0 advanceWatermark threw=PRV-8004 … queryState=FAILED`. Attached **last**: `B.received=2 C.received=2 … queryState=FAILED`. B and C's outcome depends on attach order, and one subscriber's declared policy terminates the computation. See finding **STRM-2** |
| STRM-058 | **PASS** | `q58` and `q58b`, byte-identical SQL: both resolve to fingerprint `6b68f41d507b` with the same `rowsIn`; X on `q58` and Y on `q58b` each received **100** changes summing **5050** from rows pushed once |
| STRM-059 | **PASS** | 2 000 000 rows over 100 keys, committing every 10 000, **no subscriber at any point**: `ViewSink.pending.size()=0 rowsApplied=2000000 viewKeys=100 heapUsedAfterGc=10MB`. The claim the existing test does not check is true |
| STRM-060 | **FAIL** | row 1 fed, then subscribe, then rows 2–3, then one `commit()`: **first batch size=2**, `[+1[u2, 2], +1[u3, 3]]`, while `ServedView.commits` rose by exactly 1 covering 3 keys. A batch that is not a commit, which `USER_GUIDE.md` promises never happens. See finding **STRM-11** (with case STRM-120) |

## Section E — lifecycle interaction (STRM-061–076)

| Case | Verdict | Evidence |
|---|---|---|
| STRM-061 | **PASS** | subscribe to a `PAUSED` query succeeds, `subscriberCount=1`; `accept()` returned `true` for **0 of 10** rows; `rowsIn 0→0`; `deliveredWhilePaused=0`; after `resume`, `delivered=1` |
| STRM-062 | **PASS** | over `H-S`: the client's stream was `(still running)` throughout a 10 s pause and never errored; `batches()` stopped rising and resumed. One nuance the case does not predict: the 1000 rows appended while paused **did** reach the view after resume (`view holds 1200 keys`), because the filesystem source's reader is still positioned on them — the embedded "a paused query drops rows" contract does not carry to a file-backed node |
| STRM-063 | **PASS** | after a **180 s** pause — 900 empty 200 ms polls — the stream state was `(still running)`, and after `resume` all **10** rows were delivered (`rows()=10`). The liveness half holds: the loop neither exits nor stops working. The CPU half is **not established**: the only figure available was whole-process (478 850 ms over the pause) on a node still ingesting other queries' feeds, so it is not attributable to the subscription thread and is not quoted as one |
| STRM-064 | **PASS** | `batches=[[a], [d]] delivered=2 viewKeys=[a, d]` — `b` and `c`, fed while paused, appear in neither the stream nor the view |
| STRM-065 | **FAIL** | `pravaha drop --name q65` with a live subscriber: the stream ended **200 ms** later with `completed normally, no error`. No status, no reason, no `CANCELLED`/`NOT_FOUND` — indistinguishable from a client-initiated close. In-process the same drop leaves the `Subscription` reporting `isClosed()==false` with `failure()` empty, still in `sink.listeners`, never to receive another change (measured in STRM-076: the count never returns to 0). See finding **STRM-12** |
| STRM-066 | **FAIL** | embedded: `PRV-8003  cannot subscribe to 'a740dfd20964': it is DROPPED`. The message names a **fingerprint short-form**, not `q` — `RegisteredQuery.anyName()` falls back to `fingerprint.shortForm()` once `removeName` has emptied the name set, so the caller is told about an identifier it has never seen. Over Flight the same situation is `PRV-8002 NO_SUCH_QUERY` — two codes for one user-visible event, which `ERRC` owns. See finding **STRM-17** |
| STRM-067 | **PASS** | Y on `q67b`: 50 distinct keys, then `drop q67`, then 50 more → **100** distinct keys, stream `(still running)`. A read of `q67` afterwards is refused; a read of `q67b` answers with 100 rows |
| STRM-068 | **FAIL** | X subscribed under the name `q68`: 50 keys, then `drop q68`, then 50 more → X holds **100** and its stream is `(still running)`, while a read of `q68` at the same moment is **refused, the view does not exist**. The server is streaming rows under a name it simultaneously says is gone, and `policy.mayRead(principal, "q68")` was evaluated against a view that no longer exists. See finding **STRM-14** |
| STRM-069 | **PASS** | driven to `FAILED` through STRM-057's route: `queryState=FAILED queryFailure=PRV-8004 …`; the in-process watcher was **never notified** (`isClosed=false failure=false batchesReceived=0`); `resume` refused with `PRV-8003  query 'q' is FAILED and cannot be resumed. A failed query is not restarted in place…` |
| STRM-070 | **FAIL** | with a subscriber attached: first commit ok, second `PRV-4022 VIEW_TOO_LARGE`, then **`pending.size` after 100 000 more rows = 100 001**. Identical run with **no** subscriber: `pending.size = 0`. See finding **STRM-5** |
| STRM-071 | **PASS** | 10 000 keys spread over 300 s of event time with `Retention.ofAge(60s)`: the stream replay holds **10 000** keys, the view holds **2001**, `evicted()` = **7999**, and `10000 − 7999 = 2001` reconciles exactly. `delivered=10000 dropped=0` — **no change was delivered for any evicted key**. Documented behaviour, pinned; the subscriber's only route to the reconciliation is a server-side counter it cannot read |
| STRM-072 | **FAIL** | the subscriber held 500 keys; the node was stopped with `SIGTERM` and restarted. The client's `run()` ended with **`completed normally, no error`** — not `UNAVAILABLE`, not any transport error. A server restart is byte-for-byte indistinguishable from an orderly end of stream, because the graceful shutdown drains in-flight Flight calls and `listener.completed()` fires on the way out. After the journal replay the query is back: `q72 [RUNNING, c625056598e3, 0 rows]` — registered again, `rowsIn` reset to 0, and nothing replays the subscription. A client that treats a clean completion as "the stream is finished" stops, keeps the 500 keys it has, and never learns that the view moved on. The `SIGKILL` variant the case also asks for was **not run**: the graceful case already produces the worse of the two answers, and a second stop would have been a second measurement of the same signal. See finding **STRM-12** |
| STRM-073 | **PASS** | old client: 10 distinct keys, `streamEnded=true (completed normally, no error)`, and **10** still after re-registration and 10 more rows — it received nothing from the new computation. The fresh subscriber received its 10. The view's key count immediately after re-registration is **10**, before anything was pushed: that is the filesystem source re-reading its file, not surviving view state |
| STRM-074 | **PASS** | 120 000 distinct keys fed over 60 s into a node with `pravaha.checkpoint.directory` set and `interval: 1s` (≈ 60 checkpoint boundaries crossed): `rows()=120000 missingSequences=0 duplicatedSequences=0 foreignRows=0 sum=7200060000` (expected `120000 × 120001 / 2 = 7 200 060 000`), 6 checkpoint files on disk, subscription still open. No checkpoint duplicated, reordered or suppressed a delivered change. One half of the case is unreachable and recorded as such: it asks for `FAIL` overflow so that any loss is loud, and a remote subscriber cannot select it (finding **STRM-16**) — the histogram is what carries the assertion instead, and it is exact |
| STRM-075 | **PASS** | the investigation's answer: a blue-green update is reachable from no public surface. `ControlWire` declares exactly `REGISTER`, `DROP`, `LIST`, `PAUSE`, `RESUME` (`ControlWire.java:58`–`:66`) and nothing else. ADR-016's own status line already records it: "Accepted; **not built** — `ShadowDeployment` exists in `pravaha-backfill` with tests and no caller". No subscriber can be exposed to a swap that cannot be triggered |
| STRM-076 | **FAIL** | the twelve-observation sequence: `0 | 1 | 2 | q=3 q2=3 | q=4 q2=4 | 3 (graceful close) | 4 (throwing consumer attached) | 3 (detached by the commit) | 3 (paused) | 3 (resumed) | 3 (after drop q) | 3 (after drop q2)`. The count is per computation and spans names, exactly as the case predicts — and it **never returns to 0**. `RegisteredQuery.close()` does not touch `sink.listeners`, so a closed computation reports three live subscribers for ever. See finding **STRM-12** |

## Section F — backpressure toward the subscriber (STRM-077–090)

| Case | Verdict | Evidence |
|---|---|---|
| STRM-077 | **FAIL** | the mechanism is inverted relative to its documentation. Run A (slow consumer, 200 commits, 5 ms sleep each, buffer 10): `delivered=200 conflated=0 dropped=0 wallClock=1442ms` — the slowness became engine latency, not conflation. Run B (instant consumer, one 100-row commit on 5 keys, same buffer): `delivered=10 conflated=90 dropped=0` — the **fast** subscriber is the one that conflates. See finding **STRM-8** |
| STRM-078 | **PASS** | `99 → delivered=99 dropped=0 sum=4950; 100 → delivered=100 dropped=0 sum=5050; 101 → delivered=100 dropped=1 sum=5150 first=2`. The lost row is the **first** fed |
| STRM-079 | **PASS** | `of(1, DROP_OLDEST)` with 5 keys: `delivered=1 dropped=4 batch=[+1[k5, 5]]` — the **last**, not the first. `of(0, …)` → `bufferRows must be at least 1, got 0`; `of(-1, …)` → `…, got -1` |
| STRM-080 | **PASS** | `batch=[+1[k1, 50], +1[k2, 40]] conflated=3 dropped=0 sum=90` — newest per key, and `k1` still first, so `replaceByKey` rewrites in place |
| STRM-081 | **PASS** | four distinct keys, same buffer: `batch=[+1[c, 3], +1[d, 4]] dropped=2 conflated=0`. The counter split against STRM-080's `conflated=3 dropped=0` is attributable to key cardinality alone |
| STRM-082 | **FAIL** | the exact sequence the case names (`+1 10, -1 10, +1 30, -1 30, +1 60` on one key) under `of(2, CONFLATE)`: delivered `[+1[u1, 60], -1[u1, 10]]`, replayed weighted sum **50**, where the un-conflated truth is **60**. `conflated=3`. The default policy, and the only one the Flight path uses, silently corrupts a weight-maintaining consumer's total. See finding **STRM-16** |
| STRM-083 | **PASS** | 10 000 distinct keys, `of(2, DROP_OLDEST)`: `delivered=2 dropped=9998`, `2 + 9998 = 10000`, and the two delivered are `9999` and `10000`. The accounting identity holds |
| STRM-084 | **PASS** | `commit()` threw `PRV-8004  subscriber on 'q' fell more than 1 changes behind and asked to be failed rather than lose any. Reconnect and re-read the view to catch up`; `isClosed=true failurePresent=true delivered=0`. The message does say "1 changes" — `ERRC`'s. Note `queryState=RUNNING` here, because `RegisteredQuery.commit()` has no `catch (PravahaException) { fail(e); }`, unlike `advanceWatermark` — which is why STRM-057 sees `FAILED` and this does not |
| STRM-085 | **FAIL** | structural: `ControlWire.subscribeTicket(view, filterPairs)` encodes `["subscribe", view, pairs…]` and nothing else (`ControlWire.java:141`–`:147`); `ClientOptions.subscriberBufferRows` and `conflateOnOverflow` have **no reader** anywhere in `pravaha-flight` or `sdk/pravaha-sdk-java-flight`; `streamSubscription` passes `SubscriptionOptions.DEFAULT`. A remote subscriber's overflow policy is always `(10 000, CONFLATE)` and its declared choice has no reachable effect, while `OPERATIONS.md` presents the policy as the subscriber's. See finding **STRM-16** |
| STRM-086 | **FAIL** | one subscriber stalled 600 s per batch, 20 × 50 000-row appends: server RSS **1577 MB → 3262 MB**, a **1685 MB** delta for a single stalled subscriber, while the client received `rows()=10000 batches()=1`. `SUBSCRIPTION_HANDOVER_BATCHES = 64` is justified as "Small on purpose… A deep queue here would silently override that choice"; 64 batches of thousands of rows is not small, and the bound says nothing about memory. See finding **STRM-15** |
| STRM-087 | **FAIL** | its own falsifier is "throughput within 10 % of baseline at every N". Measured, 60 000 distinct keys per run: `0 stalled → 65 861 rows/s | 1 stalled → 20 625 rows/s | 5 stalled → 13 904 rows/s | 20 stalled → 14 097 rows/s`. **One** stalled subscriber costs 69 % of ingest throughput. The named candidates narrow to two, since `SRVDBG` no longer exists (fact 3): per-subscriber Arrow encoding (STRM-028) and heap pressure from the handover (STRM-086). See finding **STRM-4** |
| STRM-088 | **NOT RUN** | the case asks for seven throughput points on one curve over 60 s each on an embedded node driven at 20 ms. Two other QA agents were executing in parallel worktrees throughout this round and the load average never fell below 8; a throughput curve taken under that load measures the other agents. Recorded as not run with the reason, as `PERF.md` does. STRM-047 establishes the mechanism with a 100× separation that no scheduling noise explains |
| STRM-089 | **PASS** | source audit plus the loads of STRM-086/087. `handover.offer(changes)` (`:554`) and `handover.poll(200, TimeUnit.MILLISECONDS)` (`:602`) are the **only** queue operations on the path; a grep for `.put(`, `.take()`, bare `.await()` over `PravahaFlightSqlProducer` finds only `equals.put(...)` on a `LinkedHashMap`. `Subscription` holds `synchronized (buffer)` at `:80`–`:94` and releases it before `consumer.accept` at `:96`. `ViewSink` holds `synchronized (pending)` across `List.copyOf(pending)`, which is O(batch), on the commit thread — recorded, as the case asks |
| STRM-090 | **PASS** | consumer throwing on its 5th call: `delivered()` after each of 10 commits = `[3, 6, 9, 12, 12, 12, 12, 12, 12, 12]`, final `delivered=12 dropped=0 conflated=0`, `consumerCalls=5 isClosed=true`. The predicted accounting gap is real: **18 of 30** fed changes are counted nowhere. (The case's own arithmetic says "12 ≠ 15 fed" for a run it defines as 10 commits of 3; the fed total is 30 and the unaccounted total is 18) |

## Section G — security on the stream (STRM-091–102)

Run by `$QA/srvsrc/qa/SrvG.java` and `SrvH.java` against in-process `PravahaFlightServer`s, because
no configured policy can produce the decisions these cases need.

| Case | Verdict | Evidence |
|---|---|---|
| STRM-091 | **PASS** | `FlightRuntimeException: PRV-7002  anonymous may not subscribe to 'payroll': payroll belongs to acme and bob is not in it`; **0** Arrow rows delivered of 100 pushed while the call was held; audit event `type=subscribe, view=payroll, allowed=false, detail=no filter` |
| STRM-092 | **PASS** | against a node with a `TokenVerifier`: (a) no token → `PRV-7001  this server requires a credential: send it as the header 'authorization: Bearer <token>'`; (b) malformed and (c) unknown token → refused at the transport; (d) valid token → stream opened. **No refusal echoes the view name.** (b) and (c) arrive with an empty message, which is a message-quality gap `ERRC` owns, not a disclosure) |
| STRM-093 | **PASS** | a principal whose `mayRead` returns `allowWithRowFilter("region = 'EU'")`: `PRV-7002  anonymous may not subscribe to 'q93' because their access to it is conditional on the row filter 'region = 'EU'', and a subscription cannot enforce a filter -- it delivers every change the view commits. Read the view instead…`. **0 EU rows and 0 US rows** delivered of 50 each. The leak is closed and the over-refusal is deliberate |
| STRM-094 | **PASS** | the same principal reading the same view through `pravaha query`: **EU rows=50, US rows=0**, no error. The refusal's suggested remedy works, so the message is actionable |
| STRM-095 | **FAIL** | documentation rot, confirmed by grep. `pravaha-server/src/main/resources/application.yaml:87` and `SecurityProperties.java:35` both list "row filters honoured on subscribe" among mechanisms "built and tested"; STRM-093 shows they are **refused**. A search of `docs/` for any statement that a conditional entitlement makes `subscribe` impossible returns only the QA case files themselves — no user-facing document says it. Operational consequence: moving a deployment to a policy that grants row filters silently breaks every dashboard belonging to a conditionally-entitled user. See finding **STRM-13** |
| STRM-096 | **PASS** | **the case's premise is refuted.** 50 rows delivered, then `mayRead` flipped to deny: the stream **ended after 1035 ms** with `PRV-7002  anonymous may no longer read 'q96': dana's read on q96 has been withdrawn`. `PravahaFlightSqlProducer.java:101` `REAUTHORIZE_EVERY = 2s`. 50 rows pushed inside that window were delivered, so the exposure is bounded by the re-check interval rather than unbounded |
| STRM-097 | **PASS** | **the case's premise is refuted.** With a `TokenVerifier` told mid-stream to stop accepting the credential, the stream ended after **637 ms** with the refusal, not after 300 s. `middleware.credentialStillValid()` is checked on the same 2 s cadence. (The shipped `StaticTokenVerifier` has "no rotation, no expiry, and no revocation short of restarting", so the clock-driven variant the case describes is not reachable from configuration; the mechanism it would exercise is) |
| STRM-098 | **PASS** | four subscribes, `audit: memory`: `allowed=true detail=no filter`, `allowed=true detail=region=EU`, `allowed=true detail=region=EU=user_id=u1`, `allowed=false detail=region=EU`. All four typed `subscribe`, the denied one audited **before** the refusal. The two-pair case reproduces the `filterText` join defect verbatim: `region=EU=user_id=u1` |
| STRM-099 | **PASS** | the full 3×4 grid. `reader`: subscribe ✓, pause ✗, resume ✗, drop ✗. `admin`: all ✓. `neither`: subscribe refused with `PRV-7002  anonymous may not subscribe to 'q99': no read for neither`, pause ✗, resume ✗, drop ✗. `mayRead` governs subscribe; `mayAdminister` governs the other three |
| STRM-100 | **BLOCKED** | the case needs a policy injecting a different security predicate per principal. The only predicate-bearing decision `SecurityPolicy` offers is `AccessDecision.allowWithRowFilter`, and `streamSubscription` refuses any principal carrying one (STRM-093, reproduced). So the entitlement model and the subscription model do not meet: on this build the only entitlement a subscriber can hold is **unconditional or nothing**, which is the case's own named fallback. No fingerprint comparison was possible because no second predicate-bearing computation can be registered and then subscribed to |
| STRM-101 | **PASS** | demonstrated by STRM-042 and STRM-044 on the `H-S` node: `--filter product_type=SWAP` delivers only `SWAP`, and the same principal subscribing with **no filter at all** delivers everything (10 of 10). The tap filter can always be omitted, so it is not a boundary. `CONCEPTS.md` §6's table does place "Subscription filters" beside "Security row filters" in one matrix; a reader could take that as an authorization claim, and the sentence that would stop them is not there |
| STRM-102 | **FAIL** | the three messages, to a principal `mayRead` denies on every view. (1) `PRV-7002  anonymous may not subscribe to 'payroll': that view belongs to acme`. (2) one character different: `PRV-8002  no query named 'payrol' is registered; this node has [payroll, headcount]`. (3) the bad-filter-column attempt never reaches `SubscriptionFilter` — it is refused by `mayRead` first, confirming the ordering the case asks about. The forbidden and nonexistent cases differ, and the nonexistent one **enumerates the whole registry**. See finding **STRM-9** |

## Section H — latency (STRM-103–108)

| Case | Verdict | Evidence |
|---|---|---|
| STRM-103 | **PASS** | 300 samples at 1 row/s against the `H-S` node on loopback, timestamped at append and read in the SDK callback: **min 3 ms, p50 13 ms, p90 21 ms, p99 31 ms, max 35 ms**, count above 100 ms = **0**. Inside the case's p50 ≤ 40 ms and p99 ≤ 100 ms bounds, and the shape is the 20 ms tick plus an RPC. Server stdout went to a file; the machine was **not** idle (two other QA agents in parallel worktrees), which makes the figures conservative rather than flattering |
| STRM-104 | **NOT RUN** | the case needs four view sizes to 10^6 keys and the whole matrix repeated under `Retention.forever()`. `ControlWire.REGISTER` carries no retention and no server property sets the registry's default, so the controlled half is not reachable from a configured node; and the timing half is a throughput measurement on a machine carrying two other agents. Recorded rather than approximated |
| STRM-105 | **NOT RUN** | fan-out to N=200 subscribers with a probe attached last. Same load objection; and the mechanism's two named causes have since diverged — `SRVDBG` no longer exists (fact 3), so only per-subscriber encoding remains, which STRM-087 measures directly |
| STRM-106 | **NOT RUN** | four feed rates for 120 s each. Same load objection |
| STRM-107 | **NOT RUN** | 300 s per attach order. Same load objection. STRM-047's 2001 ms / 6002 ms measurement establishes the mechanism the case quantifies |
| STRM-108 | **PASS** | from the same 300 samples as STRM-103: **0** samples in the 180–220 ms band, so `handover.poll(200, MILLISECONDS)` adds no mode to delivery latency — it returns as soon as an element is offered. The exit half: a graceful close ended the stream in 1 ms (STRM-051), well inside one poll window |

## Section I — scale (STRM-109–116)

| Case | Verdict | Evidence |
|---|---|---|
| STRM-109 | **PASS** | one row: `batches()=1 rows()=1`. Exactly one Arrow batch of one row |
| STRM-110 | **PASS** | 1000 distinct keys: `rows()=1000 distinctKeys=1000 amountSum=500500 batches=1`. Sum and count agree |
| STRM-111 | **PASS** | 100 000 distinct keys: `rows()=100000 distinctKeys=100000 amountSum=5000050000 batches=97`, no drops. Fed in 2166 ms — far faster than the case's 60 s, so the per-commit batch is larger than 33 and the run is a harder test of the same property, not an easier one |
| STRM-112 | **NOT RUN** | 10^6 keys needs `maxKeys` raised above 10^6 and `Retention.forever()` registered explicitly. Neither is reachable over `ControlWire.REGISTER`, and the case's headline measurements are GC pause and latency distributions, which the machine's load makes unquotable |
| STRM-113 | **NOT RUN** | needs `maxKeys` set low from configuration, which no property provides. The defect it collides with is reproduced directly and at the sink in STRM-070 (finding **STRM-5**), including the no-subscriber control run |
| STRM-114 | **NOT RUN** | a 24-hour soak was never runnable inside a session, as `PERF-049` records for its own equivalent |
| STRM-115 | **NOT RUN** | 1000 subscribers across 10 client processes for 300 s. STRM-087 measures the same binding constraint at N = 20 and finds ingest already down 79 %, so the 1000-subscriber run would measure a node that is already saturated; the number to report is STRM-087's, not a larger one taken under load |
| STRM-116 | **NOT RUN** | 100 registrations over 100 distinct SQL at 10 000 rows/s aggregate for 120 s. Same load objection as section H |

## Section J — windowed queries (STRM-117–120)

| Case | Verdict | Evidence |
|---|---|---|
| STRM-117 | **PASS** | 3 users × 4 rows in `[0s, 10s)`, one `advanceWatermark`: **one** callback, batch size **3**, `[+1[0, 10000000000, u3, 406], +1[…, u2, 406], +1[…, u1, 406]]`, batch sum **1218**, `delivered=3`. `100+101+102+103 = 406`, and 12 input rows collapsed to 3 changes |
| STRM-118 | **PASS** | two windows on one advance: **one** callback of **6**, three with `window_start=0` and `total=202`, three with `window_start=10000000000` and `total=403`, batch sum **1815** = `3×202 + 3×403`. The output schema carries `window_start, window_end`, so provenance is visible and the mixed batch is attributable |
| STRM-119 | **PASS** | `[[+1[0, …, u1, 202]], [-1[0, …, u1, 202], +1[0, …, u1, 252]]]`. Applying weights gives **252**; ignoring them gives **656**. The view holds `[0, 10000000000, u1, 252]`. Over Flight the weight is present (STRM-017), so a remote client can compute 252 too |
| STRM-120 | **FAIL** | 50 attempts, 1000 groups per window close, with 24 busy spinner threads creating scheduling pressure. First-batch sizes observed: **{1, 88, 427, 561, 616, 887, 889, 952, 1000}**. Whole windows: 41. **Strict fragments (0 < k < 1000): 9 in 50 attempts.** A consumer summing a 427-group batch gets `427 × 406`, a total that never existed, delivered as a completed commit. See finding **STRM-11** |

---

## What was executed and how

```
$QA/src/qa/H.java, SectionA.java, SectionA2.java, SectionC.java, SectionD.java,
    SectionE.java, SectionF.java          — the embedded (H-E / H-EA') cases
$QA/srvsrc/qa/S.java, SrvB.java, SrvC.java, SrvD.java, SrvT.java
                                          — the H-S cases against a real node on 19800
$QA/srvsrc/qa/SrvG.java, SrvH.java        — section G, against in-process guarded servers
$QA/srvsrc/qa/Dup.java                    — a probe that ruled out double-ingest on a
                                            two-name computation, which the first pass of
                                            STRM-067 looked like
```

Compiled against the installed `pravaha-*` jars and run with
`-Xss4m --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED`.
Raw output is in `$QA/logs/`.

**An incidental defect found while building the harness, outside STRM's scope and recorded so it is
not lost:** `SELECT MAX(user_id) AS user_id, SUM(amount) AS total FROM txn` — a global aggregate
with a `MAX` over a `STRING` column — registers, reports `RUNNING`, and then kills its lane:
`PRV-3010  lane 0 stopped after a failure: java.lang.IllegalArgumentException: field 0 ('user_id')
is STRING, not INT64 in schema txn_aggregated`. `AGG`/`INCR` territory.
