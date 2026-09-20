# STRM — streaming results: the continuous delivery path

*Area `STRM`, IDs `STRM-001`–`STRM-120`, budget 120.*

A continuous query is judged by what it **streams out**, not only by what a view holds. Round 1
tested `pravaha query` heavily and the subscription path barely. This file is the correction.

Everything here is authored against the code as it stands on `develop`:

| Thing | Where |
|---|---|
| `subscribe`, overload with `SubscriptionFilter`, refusal when terminal | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/RegisteredQuery.java` |
| Bounded buffer, `onCommit`, `admit`, `replaceByKey`, counters | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/Subscription.java` |
| `CONFLATE` / `DROP_OLDEST` / `FAIL`, default `(10_000, CONFLATE)` | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/SubscriptionOptions.java` |
| Tap filter, equality only, refusal on unknown column | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/SubscriptionFilter.java` |
| `pending`, `listeners`, `commit`, `onCommit`, `StagedRow.commit` | `pravaha-serving/src/main/java/com/ash/messaging/pravaha/serving/ViewSink.java` |
| `ViewChange(values, weight)` | `pravaha-serving/src/main/java/com/ash/messaging/pravaha/serving/ViewChange.java` |
| Overlay/visible, `applyValues`, `commit`, `evict` | `pravaha-serving/src/main/java/com/ash/messaging/pravaha/serving/ServedView.java` |
| Subscription ticket, `getStream`, `streamSubscription`, `writeBatch`, handover queue | `pravaha-flight/src/main/java/com/ash/messaging/pravaha/flight/PravahaFlightSqlProducer.java` |
| `subscribeTicket`, `MAGIC`, `isOurs` | `pravaha-api/src/main/java/com/ash/messaging/pravaha/api/wire/ControlWire.java` |
| Client-side `Subscription`, `ChangeBatch`, `Row` | `sdk/pravaha-sdk-java-flight/src/main/java/com/ash/messaging/pravaha/sdk/flight/` |
| `subscribe` sub-command | `pravaha-cli/src/main/java/com/ash/messaging/pravaha/cli/ServerCommand.java:139` |
| Existing (thin) coverage | `pravaha-registry/src/test/java/com/ash/messaging/pravaha/registry/SubscriptionTest.java` |

## Harnesses

Every case names one of these rather than restating it.

**`H-E` — embedded, pass-through.** Exactly `SubscriptionTest.setUp`:

```
StreamSchema TXN = user_id STRING, amount INT64
Principal DANA = ("dana", "acme", roles={analyst})
ViewCatalog views = new ViewCatalog();
QueryRegistry registry = new QueryRegistry(views, TXN);
RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
RegisteredQuery query = registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
```

`feed(user, amount)` writes one binary row with `weight(1)`, `eventTimestampNanos(0)`,
`sequence(0)`, hands it to `query.accept(...)`, then `query.awaitApplied(Duration.ofSeconds(10))`.
`feedW(user, amount, w, seq)` is the same with an explicit `weight(w)` and `sequence(seq)`.
Nothing is published until `query.commit()`.

**`H-EA` — embedded, keyed aggregate.** `H-E` but

```
RegisteredQuery agg = registry.register(
    "agg",
    "SELECT window_start, window_end, user_id, SUM(amount) AS total FROM "
        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
        + "GROUP BY window_start, window_end, user_id",
    List.of(2),
    DANA);
```

so that a second row for a key produces a real retract+insert pair rather than an upsert.

> **Corrected 2026-09-19 (STRM-18).** This harness used to read
> `SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id`, which **cannot be
> registered**: `PhysicalPlanBuilder` admits a keyed `GROUP BY` only over a window
> (`PRV-2050`), because an unwindowed one holds one accumulator per key for ever. Eight cases
> name `H-EA` and were run against a windowed substitute. The windowed form above is what they
> were actually run against, so it is what the file says; `txn` needs an `event_time TIMESTAMP`
> column and an `event-time` declaration for it, which `H-S` below now has.
>
> `STRM-013` stays BLOCKED: its assertion, "five updates in one commit deliver nine changes", is
> about the unwindowed shape and is unreachable on any shape the build admits.

**`H-S` — server over Flight.** `pravaha server` with

```yaml
pravaha:
  streams:
    txn:
      schema: "user_id:STRING,amount:INT64,product_type:STRING,event_time:TIMESTAMP"
      event-time: event_time
  sources:
    txn:
      plugin: filesystem
      options:
        directory: /tmp/strm-feed
        format: csv
        schema: "user_id:STRING,amount:INT64,product_type:STRING,event_time:TIMESTAMP"
        event.time: event_time
  security:
    authentication: none
    policy: permissive
    allow-anonymous: true
```

then `pravaha register --name q --sql "SELECT user_id, amount, product_type FROM txn" --keys 0`
against `grpc://localhost:9090`, and `pravaha subscribe --view q` as the subscriber. Rows reach
the node by being written to the source's directory. Note `PumpingFeed.PUBLISH_INTERVAL_NANOS =
20_000_000L` — the 20 ms commit cadence every latency case is measured against.

> **Corrected 2026-09-19 (STRM-18).** Four things in this block were false against the build, and
> each of them changes what can be run at all.
>
> * **The schema key is `schema:`, in `name:TYPE` form**, not `fields: "user_id STRING, …"`. A
>   node configured the old way refuses to start.
> * **There is no `DoPut` path for stream rows.** `acceptPutPreparedStatementQuery` is the only
>   put the producer implements and it carries prepared-statement parameters. A node is fed
>   through `pravaha.sources.*`; every case that says "DoPut N rows" means "write N rows where
>   the bound source reads them".
> * **`event-time: ""` is not a way to say "no event time" that anything can act on.** A stream
>   with no declared event time has no watermark, so no window over it can ever close -- a
>   windowed query registers, reports `RUNNING`, ingests every row and emits nothing (`TIME-6`).
>   The stream declares a real column, because `H-EA` windows.
> * **`pravaha.security.policy` has no `tenant` value.** It is `permissive` or `authenticated`,
>   and no configured policy can produce an `AccessDecision` carrying a row filter — so section
>   G's cases cannot be run against a configured node at all. They were run against an in-process
>   `PravahaFlightServer` with a policy written in the test, which is what `STRM-100` is BLOCKED
>   on.

**`H-S2` — server, two names, one computation.** `H-S` plus a second registration with
byte-identical SQL under the name `q2`, which `QueryRegistry.register` resolves to the *same*
`RegisteredQuery` (`byFingerprint.get(fingerprint)` → `addName` → `views.registerAs`).

---

### A. Change kinds delivered (STRM-001–016)

The Z-set vocabulary is the product's whole delivery contract: `+1` adds, `-1` withdraws, an update
is a pair, a net-zero removes. Each of those is a separate case because each is a separate code
path through `StagedRow.commit` → `ViewSink.pending` → `Subscription.onCommit`.

## STRM-001 — a plain insert is delivered once, with weight +1
**Intent:** the base case, and the one every other case is measured against.
**Falsifier:** the subscriber sees zero changes, two changes, or a change whose `weight()` is not
exactly `1L`.
**Setup:** `H-E`. One subscriber, `SubscriptionOptions.DEFAULT` (10 000 rows, `CONFLATE`), no filter,
collecting into `List<ViewChange> seen`.
**Steps:** 1. `subscribe`. 2. `feed("u1", 300)`. 3. `query.commit()`.
**Expected:** `seen.size() == 1`. `seen.get(0).weight() == 1L`. `seen.get(0).isRetraction() == false`.
`seen.get(0).values()` is `["u1", 300L]` — element 0 `"u1"`, element 1 `Long.valueOf(300)`.
`subscription.delivered() == 1`, `conflated() == 0`, `dropped() == 0`.
**Vacuity:** remove the `listeners.add` in `ViewSink.onCommit` and `seen` is empty, so the assertion
is not satisfiable by an inert harness. The `values()` assertion additionally rules out a batch of
one change that happens to be somebody else's row.

## STRM-002 — three inserts in one commit arrive as one batch of three
**Intent:** a batch boundary is a commit boundary (`ViewSink` javadoc: "Gathered rather than
delivered per row"). Round 1 never checked the batch *shape*, only that something arrived.
**Falsifier:** three batches of one, or one batch of one.
**Setup:** `H-E`, subscriber records `batch.size()` per callback into `List<Integer> sizes`.
**Steps:** 1. `subscribe`. 2. `feed("u1",1)`, `feed("u2",2)`, `feed("u3",3)`. 3. `query.commit()`.
**Expected:** `sizes` is exactly `[3]` — one callback, three changes. Not `[1,1,1]`.
`subscription.delivered() == 3`.
**Vacuity:** if `StagedRow.commit` delivered per row instead of staging into `pending`, `sizes`
would be `[1,1,1]` and the case fails. Feeding three distinct keys (not one repeated) means
conflation cannot collapse them either.

## STRM-003 — nothing is delivered before the commit
**Intent:** between commits the view holds a partly applied batch; a subscriber woken per row could
act on a half-assembled total.
**Falsifier:** `seen` is non-empty after step 2 and before step 3.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `subscribe`. 2. `feed("u1",300)`, `feed("u2",50)`, then assert. 3. `query.commit()`,
then assert again.
**Expected:** after step 2, `seen.size() == 0` and `subscription.delivered() == 0`. After step 3,
`seen.size() == 2` and `delivered() == 2`.
**Vacuity:** `awaitApplied(10s)` inside `feed` guarantees the rows really were applied to the lane
before the first assertion, so "empty" means "staged and withheld", not "not yet arrived". Without
that wait the case would pass on a race and prove nothing.

## STRM-004 — an update on an aggregate is delivered as a retraction *and* an insert, both
**Intent:** the single most important semantic in the area. `CONCEPTS.md` §4 says a consumer
maintaining its own aggregate must apply the weights. That is only possible if both halves arrive.
**Falsifier:** the subscriber sees one change instead of two, or two changes both with weight `+1`,
or the `-1` carries the *new* value rather than the superseded one.
**Setup:** `H-EA` (`SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id`), one default
subscriber.
**Steps:** 1. `subscribe`. 2. `feed("u1", 300)`; `agg.commit()`. 3. `feed("u1", 50)`;
`agg.commit()`.
**Expected:** batch 1 is `[ +1 ["u1", 300L] ]`. Batch 2 is exactly two changes, in this order:
`-1 ["u1", 300L]` then `+1 ["u1", 350L]`, because `300 + 50 = 350` and the superseded total was
`300`. A consumer applying weights holds `300 - 300 + 350 = 350`, which equals the view's own
`total` for `u1`. **Both halves, not one replacing the other, is the correct answer** — the stream
is a changelog, and a consumer that only sees the `+1` cannot maintain a `SUM` over the stream
(it would hold `300 + 350 = 650`, an error of `+300`).
**Vacuity:** the arithmetic distinguishes all three wrong behaviours: one-change delivery gives a
batch of size 1; both-positive gives `650`; a `-1` carrying `350` gives `300 - 350 + 350 = 300`.
Only the correct stream yields `350`.

## STRM-005 — the retraction precedes the insert within the batch
**Intent:** order inside the pair. `ViewChangeListener` javadoc promises "in the order they were
applied". A consumer that applies `+1` before `-1` on a keyed upsert view ends with the key removed.
**Falsifier:** in batch 2 of STRM-004, `seen.get(0).weight() == 1`.
**Setup:** `H-EA`, one default subscriber.
**Steps:** as STRM-004.
**Expected:** `batch2.get(0).weight() == -1L` and `batch2.get(0).values()[1] == 300L`;
`batch2.get(1).weight() == 1L` and `batch2.get(1).values()[1] == 350L`.
**Vacuity:** `ViewSink.pending` is an `ArrayList` appended under `synchronized`, so order is
structural, not incidental; if the sink were a `Set` or a map keyed by row, this assertion fails.
A consumer replaying `[+1 350, -1 300]` against an upsert view would remove `u1` entirely — a
falsification with a visible downstream consequence, not just an ordering nit.

## STRM-006 — a standalone retraction is delivered with weight −1
**Intent:** `-1` reaching a consumer as arithmetic rather than as a message type to special-case.
**Falsifier:** the change arrives with `weight() == 1`, or `isRetraction()` returns false, or it is
not delivered at all because the sink decided a removal is not a change.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `feed("u1", 300)`; `commit()`. 2. `subscribe`. 3. `feedW("u1", 300, -1, 1)`;
`commit()`.
**Expected:** exactly one change in step 3's batch: `weight() == -1L`, `isRetraction() == true`,
`values()` `["u1", 300L]`. The view's `visible` map no longer contains key `u1`
(`ServedView.applyValues` puts `null` in `pending`, and `commit` does `visible.remove(key)`).
**Vacuity:** subscribing *after* the insert means the only thing that can appear in the batch is the
retraction, so a delivery count of 1 cannot be satisfied by the insert leaking through.

## STRM-007 — a weight of exactly 0 removes nothing and is delivered as a positive change
**Intent:** the brief calls for "a net-zero weight that removes a key". `ServedView.applyValues`
branches on `weight < 0`, so `weight == 0` takes the **upsert** arm, and `ViewChange.isRetraction()`
(`weight < 0`) reports `false`. Under Z-set semantics a net weight of zero means the row is not
there. This case pins the disagreement.
**Falsifier:** — this case is the falsifier. Pass = the key is removed and the change is flagged as
a retraction. Fail = the key is upserted and the change reports `isRetraction() == false`.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `feed("u1", 300)`; `commit()`. 2. `subscribe`. 3. `feedW("u1", 300, 0, 1)`;
`commit()`. 4. Read the view for key `u1`.
**Expected (documented intent):** `u1` absent from the view; one change with `isRetraction() == true`.
**Expected (code as written):** `u1` present with `amount = 300`; one change with `weight() == 0L`
and `isRetraction() == false`. Record which occurred; the second is a defect against
`docs/CONCEPTS.md` §4 and the "net-zero weight removes a key" invariant in the authoring brief.
**Vacuity:** the view read in step 4 is the independent check — the assertion cannot be satisfied by
the delivery path alone.

## STRM-008 — net zero reached as +1 then −1 in one commit removes the key and delivers both changes
**Intent:** the *other* reading of net-zero: two changes whose weights sum to zero, in one batch.
**Falsifier:** the subscriber sees one change, or the key survives the commit.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `subscribe`. 2. `feed("u1", 300)` then `feedW("u1", 300, -1, 1)`. 3. `commit()`.
**Expected:** one batch, two changes, in order `+1 ["u1",300L]` then `-1 ["u1",300L]`; weights sum
`1 + (-1) = 0`. The view does **not** contain `u1`: `applyValues` last wrote `pending.put(key,
null)`, and `ServedView.commit` turns that into `visible.remove(key)`.
**Vacuity:** if the sink collapsed the pair (a "net" optimisation) the batch would be empty or of
size 1. Feeding both inside one commit is what makes the collapse possible and therefore what makes
the case non-vacuous.

## STRM-009 — net zero reached as −1 then +1 in one commit leaves the key present
**Intent:** the mirror of STRM-008. Same weights, opposite order, opposite view outcome — which is
why per-key order inside a batch is load-bearing and not cosmetic.
**Falsifier:** the key is absent after the commit, or the delivered order does not match the fed
order.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `feed("u1", 300)`; `commit()`. 2. `subscribe`. 3. `feedW("u1",300,-1,1)` then
`feed("u1", 300)`. 4. `commit()`.
**Expected:** batch of 2, order `-1 ["u1",300L]` then `+1 ["u1",300L]`; view **contains** `u1` with
`amount = 300`, because the last `applyValues` took the upsert arm.
**Vacuity:** compared against STRM-008 the inputs are identical as a multiset and the outcomes
differ; only an implementation that preserves order can satisfy both cases, so neither is passable
by accident.

## STRM-010 — explicit weights other than ±1 pass through unchanged
**Intent:** `RowWriter.weight(long)` accepts any long and `ViewChange` stores it verbatim. A
consumer maintaining a count needs `+3` to mean three.
**Falsifier:** the delivered weight is normalised to `1`/`-1`.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `subscribe`. 2. `feedW("u1", 10, 3, 1)`, `feedW("u2", 20, -2, 2)`,
`feedW("u3", 30, 7, 3)`. 3. `commit()`.
**Expected:** batch of 3 with weights exactly `[3L, -2L, 7L]`. Sum `3 + (-2) + 7 = 8`.
`isRetraction()` is `[false, true, false]`. In the view, `u1` and `u3` are present (upsert arm),
`u2` is absent (`-2 < 0` → removal arm).
**Vacuity:** normalising weights would give `[1,-1,1]`, sum `1`, which differs from `8`.

## STRM-011 — `RowKind` maps to weight on all four kinds
**Intent:** `StagedRow.rowKind` sets `weight = (DELETE || UPDATE_BEFORE) ? -1 : 1`. Four kinds, four
cases collapsed into one enumeration because they share one line of code — but all four are asserted.
**Falsifier:** any of the four maps to the wrong sign.
**Setup:** `H-E`, one default subscriber. Rows written through `ViewSink.begin()` directly with
`rowKind(...)` instead of `weight(...)`.
**Steps:** 1. `subscribe`. 2. Write four rows: `INSERT ["a",1]`, `UPDATE_AFTER ["b",2]`,
`UPDATE_BEFORE ["c",3]`, `DELETE ["d",4]`. 3. `commit()`.
**Expected:** weights in order `[+1, +1, -1, -1]`. Sum `1 + 1 - 1 - 1 = 0`. View contains `a` and
`b`, not `c` or `d`.
**Vacuity:** four distinct keys, so a view assertion of "two keys present" cannot be met by
conflation or by the wrong two.

## STRM-012 — a `rowKind` set after `weight` wins, and vice versa
**Intent:** both setters write the same field, so the last call wins. An SDK that sets both in the
wrong order silently inverts a retraction. Nothing documents the precedence.
**Falsifier:** the delivered weight is not the value set last.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `subscribe`. 2. Row A: `.weight(-1).rowKind(INSERT)`. Row B:
`.rowKind(DELETE).weight(5)`. 3. `commit()`.
**Expected:** A delivered with `weight() == 1L` (`rowKind` last); B with `weight() == 5L` (`weight`
last). If either differs, the precedence is order-independent and one of the two writers is being
ignored — a defect, and an undocumented one either way.
**Vacuity:** `5` is a value no `rowKind` can produce, so B's assertion cannot be met by the
`rowKind` path.

## STRM-013 — a key updated five times inside one commit delivers five changes under the default buffer
**Intent:** the brief's "key updated many times rapidly". With `DEFAULT.bufferRows == 10_000` and a
batch of 5, `admit` never reaches the overflow branch, so nothing is conflated.
**Falsifier:** fewer than 5 changes delivered, or `conflated() > 0`.
**Setup:** `H-EA`, one default subscriber.
**Steps:** 1. `subscribe`. 2. `feed("u1",10)`, `feed("u1",20)`, `feed("u1",30)`, `feed("u1",40)`,
`feed("u1",50)`. 3. `agg.commit()`.
**Expected:** one batch of **9** changes: `+1 [u1,10]`, then four retract/insert pairs —
`-1 [u1,10] +1 [u1,30]` (`10+20=30`), `-1 [u1,30] +1 [u1,60]` (`30+30=60`),
`-1 [u1,60] +1 [u1,100]` (`60+40=100`), `-1 [u1,100] +1 [u1,150]` (`100+50=150`).
`1 + 8 = 9`. Weighted sum of `total`: `10 - 10 + 30 - 30 + 60 - 60 + 100 - 100 + 150 = 150`, which
equals `10+20+30+40+50 = 150`. `conflated() == 0`, `dropped() == 0`.
**Vacuity:** the weighted sum `150` is reachable only if every intermediate pair is present; drop
any single pair and it no longer balances. This is exactly the assertion round 1's row-count test
lacked when 200 000 rows collapsed into 500 keys.

## STRM-014 — a key updated five times across five commits delivers five batches
**Intent:** the same five updates spread over commits. Conflation is per-commit-batch only (see
section F), so this must also be lossless.
**Falsifier:** fewer than 5 callbacks, or any batch missing its retraction half.
**Setup:** `H-EA`, one default subscriber recording `(batchIndex, size)`.
**Steps:** 1. `subscribe`. 2. For each of `10,20,30,40,50`: `feed("u1", v)` then `agg.commit()`.
**Expected:** sizes `[1, 2, 2, 2, 2]`, total `1 + 2 + 2 + 2 + 2 = 9` changes — the same 9 as
STRM-013, and the same weighted total `150`. `conflated() == 0`.
**Vacuity:** paired with STRM-013 this shows the batching boundary does not change the *content* of
the stream, only its grouping. A implementation that conflated across commits would give fewer than
9 and fail here while passing STRM-013.

## STRM-015 — an update that changes the key column is a retract of the old key and an insert of the new
**Intent:** the key is `user_id`, ordinal 0. Changing it is not an update, it is two rows, and a
consumer keyed on `user_id` must remove the old entry.
**Falsifier:** one change delivered, or the `-1` carries the new key.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `feed("u1", 300)`; `commit()`. 2. `subscribe`. 3. `feedW("u1",300,-1,1)` and
`feed("u2", 300)`. 4. `commit()`.
**Expected:** batch of 2: `-1 ["u1",300L]` then `+1 ["u2",300L]`. View contains `u2` only; `u1`
absent. A consumer applying both holds one key, not two.
**Vacuity:** if only the `+1` were delivered the consumer would hold `{u1:300, u2:300}` — two keys
where the view has one. The count `1` vs `2` is the discriminator.

## STRM-016 — a null in a non-key column survives the stream; a null key is delivered as a key
**Intent:** `ViewChange` stores `Object[]` and `ServedView.Key` wraps the key values, `null`
included. A subscriber must be able to tell a null from a missing column.
**Falsifier:** a null `amount` arrives as `0L`; or a null `user_id` throws inside `keyOf`/`Key` and
kills the commit for every other subscriber.
**Setup:** `H-E`, one default subscriber.
**Steps:** 1. `subscribe`. 2. Row A `["u1", null]` via `setString(0,"u1").setNull(1)`. Row B
`[null, 42L]` via `setNull(0).setLong(1,42)`. 3. `commit()`.
**Expected:** batch of 2. A: `values()[0].equals("u1")`, `values()[1] == null` — not `0L`.
B: `values()[0] == null`, `values()[1].equals(42L)`, and the commit completes without throwing.
View holds two keys: `"u1"` and `null`.
**Vacuity:** asserting `values()[1] == null` rather than "falsy" separates a real null from a
default-initialised `long`. Row B in the same batch as Row A means a throw on the null key would
also lose Row A, making the failure loud rather than silent.

### B. Wire fidelity — does the carrier deliver what the sink produced? (STRM-017–028)

Section A tests the stream at the `ViewChangeListener` tap. This section asks whether the *same*
stream survives the Flight carrier. It should not need its own section, and the fact that it does is
the finding: `PravahaFlightSqlProducer.writeBatch` writes `change.values()` into a
`VectorSchemaRoot` built from `ArrowSchemas.toArrow(query.outputSchema())`, and that schema has one
Arrow field per view column and **no weight field**.

## STRM-017 — the weight reaches a Flight subscriber
**Intent:** the load-bearing case of the whole area. `docs/CONCEPTS.md` §4 tells a subscriber to
apply the `-1`/`+1` weights; `docs/TROUBLESHOOTING.md` "Are you ignoring weights?" assumes they are
there. `writeBatch` never writes one and `toArrow` never declares one.
**Falsifier:** — this case *is* the falsifier for the documentation. The feature is broken if the
Arrow schema the client receives has no column carrying the weight, or if it has one that is always
`+1`.
**Setup:** `H-S` with the aggregate registration
`pravaha register --name agg --sql "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id" --keys 0`.
**Steps:** 1. `pravaha subscribe --view agg` (or the Java SDK, so the Arrow schema is inspectable).
2. Push `{"user_id":"u1","amount":300}`. 3. Push `{"user_id":"u1","amount":50}`.
4. Inspect `stream.getRoot().getSchema().getFields()` and every delivered row.
**Expected (documented):** the second commit delivers two rows distinguishable as `-1 (u1, 300)` and
`+1 (u1, 350)`, `300 + 50 = 350`.
**Expected (code as written):** the Arrow schema is exactly `[user_id: Utf8, total: Int64]`. Two
rows arrive, `(u1, 300)` and `(u1, 350)`, **indistinguishable in sign**. A client maintaining its
own total computes `300 + 350 = 650` where the view holds `350` — an error of `+300`, growing with
every subsequent update. `ChangeBatch`/`Row` in the Java SDK expose no weight accessor at all.
Record the Arrow field list verbatim.
**Vacuity:** `650 ≠ 350` is arithmetic on the client's own state, not on the server's, so it cannot
be satisfied by the server being right. The field-list assertion is structural and independent of
any data.

## STRM-018 — a Flight subscriber cannot tell a deletion from an insertion
**Intent:** the direct consequence of STRM-017 on the simplest possible query, where no aggregate is
involved and the row bytes of the retraction are identical to the row bytes of the insert.
**Falsifier:** the client can, by any means available to it, distinguish the two rows.
**Setup:** `H-S`, pass-through registration `q`.
**Steps:** 1. subscribe. 2. Push `{"user_id":"u1","amount":300,"product_type":"SWAP"}`; wait for
delivery. 3. Push the same row with `weight = -1` (via the embedded path, or a source emitting a
`DELETE` row kind). 4. Compare the two delivered Arrow rows field by field.
**Expected:** both rows are `("u1", 300, "SWAP")` and compare equal on every column the client can
see. A dashboard applying "upsert by key" holds `u1` after step 3, while the view does **not** hold
it — the client's state and the view's state have diverged by one key, permanently.
**Vacuity:** the divergence is asserted against `pravaha query --view q`, an independent read path,
so it cannot pass by both sides being wrong in the same way. Round 1's read-path coverage is what
makes that comparison trustworthy.

## STRM-019 — the CLI's rendered output for a retraction
**Intent:** `ServerCommand.subscribe` prints `render(row)` per row and a `-- commit, N rows` marker.
An operator watching the terminal must be able to see a withdrawal.
**Falsifier:** a retraction and an insertion render to byte-identical lines.
**Setup:** `H-S`. `pravaha subscribe --view q --limit 2`, output captured.
**Steps:** as STRM-018 steps 2–3.
**Expected:** two data lines plus two `-- commit, 1 row` markers. Assert whether the two data lines
differ. Given `Row` carries no weight, they will not. Then check whether the CLI *help* or the
`subscribed to …` banner warns of it — `PravahaCli.java:130` documents only
`--view/--filter/--limit/--url`.
**Vacuity:** the marker lines prove the subscription really delivered two separate commits, so
"identical lines" means the carrier lost information rather than the test seeing one line twice.

## STRM-020 — one Arrow batch per commit, and the batch boundary is the commit boundary
**Intent:** `writeBatch`'s own contract ("so a batch boundary is a commit boundary"), and the
`USER_GUIDE.md` promise "A batch is a commit. Never a partial window."
**Falsifier:** a commit of 3 rows arrives as 3 Arrow batches, or two commits coalesce into one.
**Setup:** `H-S`, Java SDK subscriber counting `stream.next()` iterations and `root.getRowCount()`.
**Steps:** 1. subscribe. 2. DoPut 3 rows fast enough to land in one 20 ms publish tick.
3. Wait 100 ms. 4. DoPut 1 more row. 5. Wait 100 ms.
**Expected:** two `next()` iterations with `getRowCount()` `3` then `1`; `3 + 1 = 4` rows total,
`subscription.batches() == 2`, `subscription.rows() == 4`.
**Vacuity:** the 100 ms gaps exceed `PUBLISH_INTERVAL_NANOS = 20 ms` by 5×, so the split into two
commits is forced rather than hoped for; without the gap the case would be a coin flip and would
prove nothing either way.

## STRM-021 — a commit larger than one Arrow batch
**Intent:** `writeBatch` sets `root.setRowCount(index)` for *the whole commit* with no cap, while
the request/response path caps at `BATCH_ROWS = 4096`. A commit of 50 000 changes is written as one
Arrow batch of 50 000 rows.
**Falsifier:** the server splits the commit (contradicting "a batch boundary is a commit boundary"),
or the allocator refuses and the subscription dies.
**Setup:** `H-S`, one subscriber, default `BufferAllocator` limit as configured by the server.
**Steps:** 1. subscribe. 2. DoPut 50 000 distinct-key rows inside one 20 ms tick (feed from a file
so the pump batches them). 3. Observe.
**Expected:** record which of three happens: (a) one batch of 50 000; (b) several batches,
contradicting the commit-boundary claim; (c) an allocator failure that ends the subscription.
Whichever occurs, the memory held by the server for this single write is `50 000 × row width`, and
`SUBSCRIPTION_HANDOVER_BATCHES = 64` means up to `64 × 50 000 = 3 200 000` changes may be queued
behind it — a bound stated in *batches*, which says nothing about rows.
**Vacuity:** 50 000 distinct keys, not 50 000 updates to one key, so nothing upstream can collapse
them; the view's key count is asserted at `50 000` afterwards through `pravaha query`.

## STRM-022 — every declared type survives the subscription wire
**Intent:** `ArrowSchemas.write(root, index, change.values(), schema)` is the only place a
`ViewChange`'s `Object[]` becomes Arrow. The read path exercises `ArrowSchemas` heavily; the
subscription path calls it with values that came from `ViewSink.StagedRow`, which stores
`BigDecimal` as `long[]{high, low}` and `bytes` as a cloned `byte[]`. Different producer, same
consumer.
**Falsifier:** any column arrives with a different value than the read path reports for the same row.
**Setup:** `H-S` with a stream declaring one column of each of the 16 documented types
(`docs/SQL_SUPPORT.md`), keyed on ordinal 0.
**Steps:** 1. subscribe. 2. Push one row with a known value per column. 3. `pravaha query --view`
the same row. 4. Compare column by column.
**Expected:** the subscribed row equals the queried row on all 16 columns. Specifically check
`DECIMAL` (the `long[]{high, low}` staging), `BYTES` (the defensive clone in
`StagedRow.setBytes` and the second clone in the `ViewChange` constructor), and `TIMESTAMP`
nanosecond precision (ADR-012).
**Vacuity:** the comparison is against an independent path that round 1 validated, so an identical
wrong answer on both sides is the only way to pass falsely, and the literal values are known
constants checked against the source data too.

## STRM-023 — `ViewChange` defensive copying does not alias the view's row
**Intent:** `StagedRow.commit` calls `view.applyValues(values, …)` — which clones internally — and
then `new ViewChange(values, weight)` — which also clones — before reassigning `values` to a fresh
array. Three copies of every row per commit, per sink. Correctness first, cost second.
**Falsifier:** mutating the array returned by `change.values()` changes what a later read of the
view returns, or changes what a second subscriber sees.
**Setup:** `H-E`, two subscribers A and B. A mutates `change.values()[1] = 999L` in its callback.
**Steps:** 1. subscribe A then B. 2. `feed("u1", 300)`. 3. `commit()`. 4. Read the view.
**Expected:** B sees `300L`, the view holds `300L`, and only A's local copy holds `999L`.
`ViewChange.values()` returns `values.clone()` on every call, so even A's second call returns `300L`.
**Vacuity:** `999` is a value no code path can produce, so seeing it anywhere but A's own variable
proves aliasing.

## STRM-024 — a subscription ticket is recognised by magic, not by parse failure
**Intent:** `getStream` dispatches on `ControlWire.isOurs(ticket.getBytes())`, which checks a 4-byte
`MAGIC = 0x50525648` and `length >= 5`. A Flight SQL ticket that happens to start with those bytes
would be misrouted; a truncated Pravaha ticket must be refused, not guessed at.
**Falsifier:** a well-formed Flight SQL statement ticket is routed to `streamSubscription`, or a
4-byte ticket throws something other than a Pravaha error.
**Setup:** `H-S`, raw Flight client.
**Steps:** Call `getStream` with: (a) a valid `TicketStatementQuery`; (b) `ControlWire.subscribeTicket("q", [])`;
(c) the first 4 bytes of (b); (d) the first 6 bytes of (b); (e) 8 bytes of `0x00`;
(f) a ticket whose first 4 bytes are `0x50 0x52 0x56 0x48` followed by 40 bytes of random.
**Expected:** (a) query rows. (b) a subscription. (c) `isOurs` false (`length >= 5` fails) → falls
through to `super.getStream` → a Flight SQL parse error, not `PRV-BAD_HANDLE`. (d) `isOurs` true →
`ControlWire.decode` refuses (`remaining() < 9`). (e) `isOurs` false. (f) `isOurs` true → decode
refuses, or `fields.size() < 2` → `PRV-BAD_HANDLE` "this is not a subscription ticket". Every
refusal names a Pravaha error code; none is a bare `NullPointerException` or
`ArrayIndexOutOfBoundsException`.
**Vacuity:** case (c) is the one that distinguishes a real magic check from a `startsWith` — it is
a Pravaha ticket by prefix and must still be rejected by the length rule.

## STRM-025 — a subscription ticket naming a query that does not exist
**Intent:** `streamSubscription` calls `required.require(viewName)` before any authorization.
**Falsifier:** the call hangs, or returns an empty successful stream, or leaks whether the name
exists to a principal not entitled to know.
**Setup:** `H-S`.
**Steps:** `pravaha subscribe --view no_such_view`.
**Expected:** immediate failure with the registry's `NO_SUCH_QUERY` code and a message naming the
view. Exit code non-zero from the CLI (`fail(e)`). Compare with STRM-091: `require` runs *before*
`policy.mayRead`, so an unauthorized principal learns that `payroll` exists by getting a different
error than for `payrol`. Record both messages.
**Vacuity:** the two spellings differ by one character; identical responses prove no disclosure,
different responses prove one. Neither outcome is assumable.

## STRM-026 — a subscription against a server started without a registry
**Intent:** `requireRegistry()` exists for the embedded/serving-only deployment.
**Falsifier:** an NPE, or a stream that opens and never delivers.
**Setup:** a `PravahaFlightSqlProducer` built with the 3-arg constructor and no `withRegistry`.
**Steps:** `getStream` with a valid subscribe ticket.
**Expected:** `PRV` `UNSUPPORTED_REQUEST` with the message "this server serves views but does not
host a registry, so it cannot register, drop or subscribe to queries. Start it with a QueryRegistry
if it should". The same refusal for `pravaha.register`, `pravaha.drop`, `pravaha.pause`,
`pravaha.resume` — all five verbs, checked.
**Vacuity:** all five verbs go through the same `requireRegistry()`; asserting all five is what
catches a later refactor that adds a sixth.

## STRM-027 — debug `System.out.println` on the subscription path
**Intent:** `PravahaFlightSqlProducer` prints `"SRVDBG starting subscription on …"` on every
subscribe and `"SRVDBG … writing N"` on **every batch written**. That is one unstructured stdout
write per commit per subscriber, on the thread that owns the Arrow writes.
**Falsifier:** — this case is the finding. It is broken if `SRVDBG` appears in server stdout at all.
**Setup:** `H-S`, server stdout captured to a file.
**Steps:** 1. subscribe. 2. Push 10 000 rows over 10 s. 3. `grep -c SRVDBG` the captured stdout.
**Expected:** zero lines. **As written:** `1` startup line plus one line per commit — at the 20 ms
cadence that is `50 lines/s per subscriber`, so 10 subscribers for an hour is
`10 × 50 × 3600 = 1 800 000` lines. `System.out` is synchronised, so this also serialises the write
loops of every concurrent subscriber on one lock. Cross-reference the latency measurement in
STRM-104 taken with stdout redirected to a file on a slow disk.
**Vacuity:** the count is exact and the line prefix is unique to this file; nothing else in the
codebase emits it.

## STRM-028 — ADR-026 claims "encode once per query, write N times"; the code encodes per subscriber
**Intent:** ADR-026's central performance argument — "a thousand subscribers at 20 Hz is 20 000
serialisations a second instead of 20" — and its stated consequence that subscriber scale is a
capacity-planning line item rather than an incident.
**Falsifier:** — the ADR is falsified if serialisation work scales with subscriber count. In
`streamSubscription` each subscription allocates its **own** `VectorSchemaRoot` and calls its own
`writeBatch`, so N subscribers on one query perform N encodings of the same batch.
**Setup:** `H-S`, one query, subscriber counts of 1, 10, 100, 250.
**Steps:** For each count: attach N subscribers, push at a steady 1 000 rows/s for 60 s, measure
server CPU and the count of Arrow encodings (JFR allocation profile on `VectorSchemaRoot`, or a
counter added in a scratch build — not on `develop`).
**Expected (ADR-026):** encodings per second ≈ 50 (one per commit) regardless of N; CPU roughly
flat in N.
**Expected (code as written):** encodings per second ≈ `50 × N` — `50`, `500`, `5 000`, `12 500` —
and CPU rising roughly linearly. Also record that ADR-026 names three carriers (gRPC, WebSocket,
SSE) and that only the gRPC/Flight one exists, and that its "conflating, drop-oldest tap ring" and
`RELIABLE` disconnect mode do not appear in the code at all.
**Vacuity:** the 1-subscriber run is the control; the claim is about the *ratio* between runs, so an
absolute measurement error cancels.

### C. Correctness of the stream — once, in order, without gaps (STRM-029–046)

## STRM-029 — every change delivered exactly once, 10 000 changes, no duplicates
**Intent:** the fundamental delivery claim. Checked by identity, not by count, because a count is
what round 1 asserted when 200 000 rows collapsed into 500.
**Falsifier:** any sequence number appears twice, or fewer than 10 000 distinct sequence numbers
arrive while `dropped() == 0`.
**Setup:** `H-E`. Column `amount` carries a strictly increasing sequence `1..10 000` and `user_id` is
`"u" + n` so every row is a distinct key. Subscriber records `values()[1]` into a `long[]` histogram
and a `LongAdder` total.
**Steps:** 1. subscribe (`DEFAULT`). 2. `for n in 1..10000: feed("u"+n, n)`.
3. `query.commit()` once at the end. 4. Assert.
**Expected:** `delivered() == 10_000`, `dropped() == 0`, `conflated() == 0`. Every bucket
`1..10 000` has count exactly 1. Sum of received `amount` = `10000 × 10001 / 2 = 50 005 000`. The
view holds 10 000 keys.
**Vacuity:** the histogram catches duplication (a bucket of 2) and loss (a bucket of 0) separately,
and they cannot cancel as they would in a sum-only check. Note the single trailing commit means the
batch is 10 000 changes, which is inside `DEFAULT.bufferRows == 10_000` by exactly zero margin —
see STRM-078.

## STRM-030 — 10 001 changes in one commit under the default buffer
**Intent:** the off-by-one against `DEFAULT.bufferRows == 10_000`. `admit` adds while
`buffer.size() < bufferRows`, so change 10 001 is the first to overflow.
**Falsifier:** all 10 001 arrive with `conflated() == 0` and `dropped() == 0` (meaning the bound is
not enforced), or fewer arrive with both counters still zero (meaning loss is silent).
**Setup:** as STRM-029 with `n` running `1..10 001`.
**Steps:** as STRM-029.
**Expected:** `delivered() == 10_000`. Exactly one change is accounted for: `conflated() +
dropped() == 1`. With all keys distinct, `replaceByKey` finds no match, so the `CONFLATE` arm falls
through to `buffer.removeFirst()` and `dropped() == 1`, `conflated() == 0`. The **oldest** change
(`amount = 1`) is the one missing; bucket 1 has count 0 and buckets `2..10 001` have count 1.
Sum received = `50 005 000 + 10 001 - 1 = 50 015 001`.
**Vacuity:** naming *which* change is lost — the oldest, not an arbitrary one — is what makes this
an assertion about `removeFirst` rather than about loss in general.

## STRM-031 — order is preserved per key across 1 000 updates to one key
**Intent:** `USER_GUIDE` and `ViewChangeListener` both promise application order. A consumer
replaying out of order ends at the wrong value.
**Falsifier:** the received `total` sequence is not monotonically consistent with the fed sequence.
**Setup:** `H-EA`, one default subscriber, buffer raised to `SubscriptionOptions.of(100_000,
CONFLATE)` so conflation cannot interfere.
**Steps:** 1. subscribe. 2. `for n in 1..1000: feed("u1", 1); agg.commit()`.
**Expected:** 1 000 batches. Batch 1 is `[+1 (u1,1)]`. Batch `k` for `k ≥ 2` is
`[-1 (u1, k-1), +1 (u1, k)]`. Final `total` for `u1` is `1000`, and the weighted sum
`1 - 1 + 2 - 2 + … - 999 + 1000 = 1000` equals `1000 × 1 = 1000`. Any transposition breaks the
telescoping sum.
**Vacuity:** the telescoping is the check: it only equals 1 000 if each `-k` is preceded by its
`+k`. A shuffled stream with the same multiset of weights gives the same naive sum but a different
*replayed* state, which is asserted separately by replaying into a `HashMap` and comparing to the
view.

## STRM-032 — order across keys matches application order within a batch
**Intent:** `ViewSink.pending` is a list appended in `StagedRow.commit` order. A consumer with
cross-key invariants (a debit then its credit) depends on it.
**Falsifier:** the batch order differs from the feed order for distinct keys.
**Setup:** `H-E`, one subscriber with buffer 100 000.
**Steps:** 1. subscribe. 2. `feed("z",1)`, `feed("a",2)`, `feed("m",3)`, `feed("b",4)`.
3. `commit()`.
**Expected:** batch order is exactly `["z","a","m","b"]` — feed order, **not** key order
(`["a","b","m","z"]`) and not insertion order into any map.
**Vacuity:** the keys are chosen so that feed order and sorted order differ in every position; a
sink that grouped by key would produce the sorted list and fail.

## STRM-033 — no gaps across a commit boundary under sustained feed
**Intent:** `ViewSink.commit` reads `pending` under `synchronized`, copies and clears. Rows applied
by the lane thread between the copy and the clear would be lost. This is the classic changelog gap.
**Falsifier:** any sequence number in `1..200 000` missing from the union of all batches while
`dropped() == 0` and `conflated() == 0`.
**Setup:** `H-S` so the 20 ms `PumpingFeed` timer really does commit concurrently with the lane
thread applying rows — the interleaving `H-E` cannot produce. Subscriber with buffer 1 000 000,
`FAIL` overflow so any loss is loud. Distinct key per row.
**Steps:** 1. subscribe. 2. Push 200 000 rows at ~5 000 rows/s for 40 s, spanning
`40 / 0.02 = 2 000` commit ticks. 3. Union the received sequences.
**Expected:** 200 000 distinct sequences, none missing, none repeated. Sum
`200000 × 200001 / 2 = 20 000 100 000`. The subscription is still open and has not failed.
**Vacuity:** the concurrency is forced by the server's own 20 ms timer against a 5 000 rows/s feed —
about 100 rows applied per tick, so the copy-and-clear window is crossed 2 000 times. `FAIL` mode
means a gap cannot be excused as intentional loss. Round 1's `INGEST` log already records
`ServedView` being touched by two threads (feed timer and lane thread) and killing the feed with a
`ConcurrentModificationException`; if that reproduces here, record it as the cause and re-run once
it is fixed rather than reporting a gap.

## STRM-034 — a change applied between the `pending` copy and the listener call is not lost
**Intent:** the narrow race inside `ViewSink.commit`: `batch` is copied and `pending` cleared inside
the monitor, but `listener.onCommit` is called *outside* it. A row applied during the callback lands
in the now-empty `pending` and must be delivered by the **next** commit, not dropped.
**Falsifier:** such a row never arrives.
**Setup:** `H-E`, a subscriber whose callback blocks on a `CountDownLatch` for 200 ms while a second
thread feeds one more row.
**Steps:** 1. subscribe. 2. `feed("u1",1)`. 3. On another thread, `commit()`. 4. Inside the callback,
`feed("u2",2)` and `awaitApplied`. 5. Release the latch. 6. `commit()` again.
**Expected:** batch 1 = `[(u1,1)]`; batch 2 = `[(u2,2)]`. Total delivered `1 + 1 = 2`. `u2` is not
lost and not duplicated into batch 1.
**Vacuity:** the 200 ms block guarantees the second feed genuinely occurs during the callback
window; without it the interleaving would not be produced and the case would pass trivially.

## STRM-035 — a subscriber attaching mid-stream receives no snapshot, only subsequent changes
**Intent:** `docs/TROUBLESHOOTING.md` states it plainly: "A subscription starts from *now*, not from
the beginning of time. A change committed before the subscriber attached was published to nobody."
`RegisteredQuery.subscribe` passes `sink.onCommit(...)` and nothing else — there is no snapshot
path. This case pins the documented behaviour so a later "helpful" snapshot is caught.
**Falsifier:** the first batch contains rows committed before `subscribe` returned.
**Setup:** `H-E`, no subscriber initially.
**Steps:** 1. `feed("u1",1)`, `feed("u2",2)`, `feed("u3",3)`; `commit()`. 2. `subscribe`.
3. `feed("u4",4)`; `commit()`.
**Expected:** exactly one batch, of size 1, containing `("u4", 4L)`. `delivered() == 1`, **not** 4.
The view holds 4 keys, so the subscriber's replayed state (1 key) differs from the view's (4) by
`4 - 1 = 3` — which is the documented and intended gap, and the reason a client must read the view
once before subscribing if it wants a complete picture.
**Vacuity:** the view read is the independent check that the three earlier rows really were
committed, so "only one delivered" means withheld, not absent.

## STRM-036 — the read-then-subscribe recipe has a hole, and nothing documents how to close it
**Intent:** given STRM-035, the only way to build complete state is read the view, then subscribe.
Changes committed between the read and the `subscribe` call are in neither. No API returns a
frontier with the read that the subscription could be started from, and no doc mentions the gap.
**Falsifier:** — the case is the finding. It is closed if some observable (a committed frontier on
the read result, an `asOf` on the ticket) lets a client prove no change was missed.
**Setup:** `H-S`, steady 1 000 rows/s feed, all distinct keys.
**Steps:** 1. `pravaha query --view q` and record the key set K. 2. Immediately subscribe and record
the delivered key set S. 3. Stop the feed. 4. `pravaha query --view q` for the final key set F.
**Expected:** `K ∪ S` should equal `F`. Measure `|F| − |K ∪ S|` over 20 repetitions at 1 000 rows/s:
each gap is roughly one round trip plus one commit tick, so ≈ `1000 × 0.05 = 50` keys per attempt.
Report the distribution and whether any API surface would let a client detect the loss. Also check
whether `ChangeBatch`, the Flight stream metadata, or `ControlWire` carry the committed frontier —
`ViewChangeListener.onCommit` receives one and `Subscription.onCommit` discards it.
**Vacuity:** the third read F is taken after the feed stops and the view has quiesced, so it is a
definite ground truth rather than another moving target.

## STRM-037 — the committed frontier is delivered to the in-process listener and discarded by `Subscription`
**Intent:** `ViewChangeListener.onCommit(List<ViewChange>, long frontier)` promises "every change in
this batch belongs at or before it". `Subscription.onCommit(changes, frontier)` ignores the
parameter entirely, and nothing downstream can recover it.
**Falsifier:** a `Subscription` consumer has any way to obtain the frontier of the batch it was
handed.
**Setup:** `H-E`. Two attachments: a raw `sink.onCommit(listener)` recording frontiers, and a
`query.subscribe(...)` recording batches.
**Steps:** 1. Attach both. 2. `feed("u1",1)` with `sequence(5000)`; `commit()`. 3. `feed("u2",2)`
with `sequence(9000)`; `commit()`.
**Expected:** the raw listener sees frontiers `5000` then `9000` (`ViewSink.commit` is called with
`sink.appliedFrontier()`, the running max of `max(eventTime, sequence)`). The `Subscription`
consumer receives the same two batches with no frontier available on `ViewChange`, `Subscription`,
or the callback signature. Record it as an API gap, and link it to STRM-036: the frontier exists at
exactly the point where it would close that hole.
**Vacuity:** `5000` and `9000` are values nothing else produces, so the raw listener's observation
is unambiguous.

## STRM-038 — a commit with no changes delivers no callback
**Intent:** `ViewSink.commit` returns early when `pending.isEmpty()`. A subscriber must not be woken
with an empty batch, and `Subscription.onCommit` also returns early when its buffer is empty after
filtering.
**Falsifier:** a callback fires with `batch.isEmpty()`.
**Setup:** `H-E`, one subscriber counting callbacks including empty ones.
**Steps:** 1. subscribe. 2. `commit()` three times with no rows fed. 3. `feed("u1",1)`; `commit()`.
4. `commit()` twice more.
**Expected:** exactly **1** callback, of size 1. Not 6, not 4. The three leading and two trailing
no-op commits produce nothing.
**Vacuity:** the six commits are real calls that reach `ViewSink.commit` and `ServedView.commit`
(`commits` counter rises by 6); only the *delivery* is suppressed, so the case distinguishes "no
commit happened" from "a commit happened and was correctly silent".

## STRM-039 — a filtered subscriber gets no callback for a commit containing none of its rows
**Intent:** `Subscription.onCommit` filters before buffering, then returns if `buffer.isEmpty()`.
A subscriber on `product_type = 'SWAP'` should be silent through a commit of 500 `BOND` rows.
**Falsifier:** an empty callback, or a callback containing a `BOND` row.
**Setup:** `H-S`-shaped schema in `H-E` (add `product_type STRING` at ordinal 2), one subscriber with
`SubscriptionFilter.matching(schema, "product_type", "SWAP")`.
**Steps:** 1. subscribe. 2. 500 `BOND` rows; `commit()`. 3. 1 `SWAP` row; `commit()`. 4. 500 more
`BOND` rows; `commit()`.
**Expected:** exactly 1 callback, of size 1, `values()[2].equals("SWAP")`. `delivered() == 1`.
`dropped() == 0` and `conflated() == 0` even though `500 > bufferRows` would have overflowed a small
buffer — with `SubscriptionOptions.of(2, DROP_OLDEST)` the counters are still zero, which is the
"filtered before buffering" claim (`SubscriptionTest.filteredOutRowsDoNotConsumeTheSubscribersBuffer`,
extended here to 500 rows on both sides of the wanted one).
**Vacuity:** placing the wanted row *between* two 500-row floods means a filter applied after
buffering would have evicted it in either direction.

## STRM-040 — multi-column tap filter, all columns must match
**Intent:** `SubscriptionFilter.matching(schema, Map)` builds parallel ordinal/value arrays and
`accepts` requires every one to match. Only equality, deliberately (ADR-032).
**Falsifier:** a row matching one of two filter columns is delivered.
**Setup:** `H-E` + `product_type` at ordinal 2. Filter `{user_id: "u1", product_type: "SWAP"}`.
**Steps:** 1. subscribe. 2. Feed four rows: `(u1,10,SWAP)`, `(u1,20,BOND)`, `(u2,30,SWAP)`,
`(u2,40,BOND)`. 3. `commit()`.
**Expected:** exactly 1 delivered: `(u1,10,SWAP)`. The other three fail on `product_type`,
`user_id`, and both respectively. `4 − 1 = 3` filtered out.
**Vacuity:** the 2×2 grid means any single-column implementation delivers 2 instead of 1, and an
OR implementation delivers 3.

## STRM-041 — filter value type mismatch is a silent non-match, not a refusal
**Intent:** `SubscriptionFilter` refuses an unknown *column* but compares values with
`Objects.equals`, so a `String "300"` never equals a `Long 300`. Over Flight this is guaranteed:
`streamSubscription` builds `Map<String, Object> equals` from `ControlWire` **strings**, so a filter
on any non-string column can never match anything.
**Falsifier:** — the case is the finding. It is sound if a type-mismatched filter is refused at
subscribe time the way an unknown column is.
**Setup:** `H-S`. `pravaha subscribe --view q --filter amount=300`.
**Steps:** 1. Subscribe with that filter. 2. Push `{"user_id":"u1","amount":300}` ten times.
3. Wait 2 s.
**Expected (sound):** the subscribe is refused, naming the type, exactly as an unknown column is
refused ("a filter that was quietly ignored would leave you receiving everything while believing you
asked for a slice" — the same argument applies verbatim to a filter that matches nothing).
**Expected (code as written):** the subscription opens successfully, banner prints
`subscribed to q {amount=300}`, and **zero** rows are ever delivered. `10 − 0 = 10` rows silently
withheld, with no error and no counter. Compare with `--filter user_id=u1` on the same data, which
delivers all 10 — same syntax, same CLI, opposite outcome, no way to tell them apart.
**Vacuity:** the `user_id` control run proves the harness, the server, and the feed all work, so
"zero rows" isolates the type comparison.

## STRM-042 — filter column name matching is case-insensitive; filter *value* matching is not
**Intent:** `ordinalOf` uses `equalsIgnoreCase` on the column name, `accepts` uses `Objects.equals`
on the value. Two different rules in one class, neither documented.
**Falsifier:** `--filter USER_ID=u1` is refused, or `--filter user_id=U1` delivers rows for `u1`.
**Setup:** `H-S`, rows with `user_id = "u1"`.
**Steps:** Four subscriptions, one at a time: `user_id=u1`, `USER_ID=u1`, `User_Id=u1`,
`user_id=U1`. Push 5 rows to each.
**Expected:** the first three deliver 5 rows each (`equalsIgnoreCase`). The fourth delivers 0 and
does not error. Record the asymmetry.
**Vacuity:** four variants of one filter over identical data; the only variable is casing, so the
`5,5,5,0` pattern is attributable to nothing else.

## STRM-043 — a filter naming a column the view does not have is refused, at both entry points
**Intent:** the refusal is asserted in `SubscriptionTest` at the `SubscriptionFilter` level only.
The Flight path builds the filter from wire strings and must refuse there too, before the stream
starts.
**Falsifier:** the subscription opens and delivers everything.
**Setup:** `H-S`.
**Steps:** 1. `pravaha subscribe --view q --filter prodcut_type=SWAP` (typo). 2. Push 5 rows.
**Expected:** the subscribe call fails before `listener.start(root)`; the client sees a Pravaha error
whose message contains "has no column" and lists the view's real columns
`[user_id, amount, product_type]`. Zero rows delivered, non-zero CLI exit code. The `PRV-8002` code
`docs/CONCEPTS.md` §6 associates with a refused subscription filter is the one reported — if the
code is `NO_SUCH_QUERY` instead (which is what `SubscriptionFilter` actually throws), record the
mismatch: `NO_SUCH_QUERY` for a bad *column* is a code meaning two things, which `ERRC` owns.
**Vacuity:** 5 rows are pushed so that "opened and delivered everything" is a distinguishable
outcome from "refused".

## STRM-044 — an empty filter map is the same as no filter
**Intent:** `SubscriptionFilter.matching` returns `NONE` for null/empty, and `streamSubscription`
short-circuits on `equals.isEmpty()`. Two paths to the same object.
**Falsifier:** `--filter ""` behaves differently from omitting `--filter`.
**Setup:** `H-S`.
**Steps:** Subscribe three ways: no `--filter`; `--filter ""`; and via the SDK with `Map.of()`.
Push 10 rows to each.
**Expected:** 10 rows on all three. Note that `ServerCommand.subscribe` splits `""` on `,` giving
`[""]`, whose `indexOf('=')` is `-1`, which raises `Args.UsageException` "--filter takes
column=value pairs, got ''" — so the CLI refuses rather than treating it as empty. Record which of
the three it is; a refusal is acceptable, silent divergence is not.
**Vacuity:** identical data and row count across three call shapes; only the filter argument varies.

## STRM-045 — a change delivered to a filtered subscriber matches the filter on the *delivered* row
**Intent:** on an aggregate, the retraction and the insert may differ in the filtered column if the
filter names a non-key column. The `-1` must be judged on its own values, not on the `+1`'s.
**Falsifier:** a retraction is delivered whose own `product_type` does not match the filter, or one
is withheld whose own does.
**Setup:** `H-EA` variant: `SELECT user_id, product_type, SUM(amount) AS total FROM txn GROUP BY
user_id, product_type`, keys `[0,1]`. Filter `product_type = "SWAP"`.
**Steps:** 1. subscribe. 2. `(u1, SWAP, 100)`; commit. 3. `(u1, BOND, 200)`; commit.
4. `(u1, SWAP, 50)`; commit.
**Expected:** commit 2 → `[+1 (u1,SWAP,100)]`. Commit 3 → **nothing** (the `BOND` group is a
different key). Commit 4 → `[-1 (u1,SWAP,100), +1 (u1,SWAP,150)]`, `100 + 50 = 150`. Total delivered
`1 + 0 + 2 = 3`. A consumer applying weights holds `100 − 100 + 150 = 150` for `(u1,SWAP)` and
nothing for `(u1,BOND)`.
**Vacuity:** the `BOND` commit is the discriminator — a filter evaluated on the batch rather than on
each change would either leak it or suppress commit 4 along with it.

## STRM-046 — a continuous stream replayed equals a batch recomputation
**Intent:** the `INCR` contract stated on the delivery path: applying every delivered change, in
order, to an empty map must reproduce the view exactly. This is the end-to-end statement that
sections A–C add up to.
**Falsifier:** the replayed map differs from `pravaha query --view` on any key or value.
**Setup:** `H-EA` with 500 distinct users and 50 000 rows drawn so each user gets 100 rows with
amounts `1..100` (`SUM` per user = `100 × 101 / 2 = 5050`; total across users
`500 × 5050 = 2 525 000`). One subscriber, buffer 1 000 000, `FAIL` overflow.
**Steps:** 1. subscribe. 2. Feed all 50 000 rows, committing every 1 000. 3. Replay the received
stream into `Map<String,Long>`: `weight > 0` → put, `weight < 0` → remove. 4. Compare against the
view.
**Expected:** 500 keys in both; every key's `total` is `5050`; sum `2 525 000`. Changes delivered:
`500` inserts + `2 × (50 000 − 500) = 99 000` update halves = `99 500`. `dropped() == 0`,
`conflated() == 0`, and the subscription is still open.
**Vacuity:** 500 keys × 100 rows is precisely the collapse round 1's row-count test failed to
notice; here the per-key value `5050` is asserted for all 500 keys, so a stream that delivered the
right *number* of changes with the wrong content fails. `FAIL` overflow means any loss ends the
subscription rather than passing quietly.

### D. Subscriber behaviour (STRM-047–060)

## STRM-047 — a slow in-process subscriber *does* block the engine
**Intent:** `SubscriptionOptions`' javadoc says "Blocking is not on the list: a subscriber that
blocks the engine applies backpressure to the *query*". `CONCEPTS.md` §8 and `USER_GUIDE.md` repeat
it. But `Subscription.onCommit` calls `consumer.accept(batch)` **synchronously**, and `ViewSink.commit`
calls every listener synchronously, and `RegisteredQuery.advanceWatermark`/`commit` calls
`sink.commit` on the caller's thread. The bounded buffer bounds what waits *within one commit*; it
does not decouple the consumer's thread from the engine's.
**Falsifier:** — the documentation is falsified if the engine's commit call takes as long as the
consumer does.
**Setup:** `H-E`, one subscriber whose callback does `Thread.sleep(2000)`.
**Steps:** 1. subscribe. 2. `feed("u1",1)`. 3. Time `query.commit()` on the main thread.
**Expected (documented):** `commit()` returns in well under 2 000 ms.
**Expected (code as written):** `commit()` takes ≥ 2 000 ms. Extend: 3 subscribers each sleeping
2 000 ms → `commit()` takes ≥ `3 × 2000 = 6000` ms, because `ViewSink.commit` iterates listeners
serially. In `H-S` this same call is `PumpingFeed`'s 20 ms publish tick, so one slow in-process
listener stalls ingestion for every query that feed drives.
**Vacuity:** 2 000 ms against a 20 ms cadence is a 100× separation; no scheduling noise explains it.
The 1-vs-3-subscriber comparison distinguishes "blocked" from "blocked once".

## STRM-048 — the Flight carrier *does* decouple the socket from the engine
**Intent:** the mitigation that exists: `streamSubscription`'s consumer only does
`handover.offer(changes)` and every Arrow write happens on the call's own thread. So the blocking in
STRM-047 is an in-process-API hazard, not a network one. Worth establishing so the two are not
confused.
**Falsifier:** a Flight subscriber on a throttled link slows the query's commit rate.
**Setup:** `H-S`, one subscriber over a link throttled to 10 KB/s (`tc netem` or a proxy). Steady
2 000 rows/s feed.
**Steps:** 1. Establish the unthrottled baseline commit rate for 60 s. 2. Apply the throttle.
3. Subscribe. 4. Feed for 120 s, sampling the commit rate and `rowsIn()` every 5 s.
**Expected:** the server's commit rate stays at ~50/s (measured via `pravaha query --view q` key
count growing at 2 000/s) while the subscriber falls behind. `rowsIn()` on the query is unaffected
by the throttle. Contrast directly with STRM-047's number.
**Vacuity:** the unthrottled control run at the same feed rate gives the baseline; the claim is that
the two match, so it cannot be satisfied by the feed simply being slow.

## STRM-049 — a subscriber that stops reading entirely: batches are dropped, and the client is never told
**Intent:** `handover` is a `LinkedBlockingQueue` of `SUBSCRIPTION_HANDOVER_BATCHES = 64`, filled
with `offer` (never `put`). Once full, whole **batches** are discarded and `droppedBatches` is
incremented. The count goes to the `AuditSink` when the subscription ends — and nowhere the
subscriber can see it. `OPERATIONS.md` says `dropped()`/`conflated()` on `Subscription` tell a
consumer it is falling behind; the client-side `Subscription` in
`sdk/pravaha-sdk-java-flight` exposes only `batches()` and `rows()`.
**Falsifier:** — broken if the client has no way to learn it lost data. Sound if the stream carries
a dropped count, or ends with an error.
**Setup:** `H-S`, Java SDK subscriber whose `onBatch` sleeps 5 s per batch. Feed 1 000 rows/s.
**Steps:** 1. subscribe. 2. Feed for 60 s. 3. Close, and inspect everything the client can observe.
**Expected:** at 50 commits/s the queue of 64 fills in `64 / 50 ≈ 1.3` s; over 60 s roughly
`60 × 50 − 64 − (60 / 5) ≈ 3000 − 64 − 12 ≈ 2 900` batches are dropped. The client's `rows()` is far
below the `60 000` fed. Assert whether any client-visible signal reports the loss: it will not.
Server-side, one `subscribe.dropped` audit event is recorded — but only with `audit: memory|log`
configured, and only when the subscription ends.
**Vacuity:** `rows()` vs the 60 000 pushed is the loss measurement and comes from the client's own
counters; the audit event is checked independently in the server's audit sink.

## STRM-050 — whole batches are dropped, so loss is never a partial commit
**Intent:** if data must be lost, losing whole commits is better than losing rows inside one — a
consumer that received half a window's changes holds a total that never existed.
**Falsifier:** a delivered Arrow batch has a row count different from the commit that produced it.
**Setup:** `H-S`, subscriber sleeping 2 s per batch, feed producing commits of exactly 100 rows
(100 distinct keys per 20 ms tick).
**Steps:** 1. subscribe. 2. Feed for 30 s. 3. Assert on every received batch.
**Expected:** every `root.getRowCount()` is exactly `100`. Number of batches received ≈
`30 / 2 = 15`; rows received ≈ `15 × 100 = 1500` out of `30 × 50 × 100 = 150 000` fed. Loss is
`150 000 − 1500 = 148 500` rows in `1485` whole batches — large, but never a fractional commit.
**Vacuity:** a constant 100 rows per commit makes any partial batch immediately visible as a row
count other than 100.

## STRM-051 — a subscriber that disconnects mid-stream is detached within one poll interval
**Intent:** `streamSubscription`'s loop checks `listener.isCancelled()` and the `finished` latch
after a `handover.poll(200, MILLISECONDS)`. Exiting the loop closes the `Subscription` via
try-with-resources, which runs `detach.close()` → `listeners.remove(listener)`.
**Falsifier:** `query.subscriberCount()` does not return to its pre-subscribe value, or the sink
keeps assembling batches.
**Setup:** `H-S`, one subscriber, steady feed.
**Steps:** 1. Record `subscriberCount()` (via the console/registry) = 0. 2. Subscribe; assert 1.
3. `kill -9` the subscriber process. 4. Poll `subscriberCount()` every 100 ms for 30 s.
**Expected:** returns to 0. Record the time taken. The loop's `poll` timeout is 200 ms, so detection
should be ≤ 200 ms *after gRPC reports the cancellation* — but a `kill -9` with no FIN may rely on
TCP keepalive, so the real number may be minutes. Report it. Repeat with a graceful
`subscription.close()`, which cancels explicitly (`TROUBLESHOOTING.md`: "the server learns nobody is
listening from a *cancellation*, not from a dropped transport"), and expect ≤ 400 ms.
**Vacuity:** the two variants (kill vs close) over the same setup isolate the transport-detection
question from the detach logic. `subscriberCount()` is read from the server, not inferred.

## STRM-052 — a reconnecting subscriber costs nothing and misses the gap
**Intent:** ADR-025's headline consequence: "a dashboard that reconnects loses its warm state" is
the thing avoided. The computation, its state, and its `rowsIn` must be untouched — and, per
STRM-035, the reconnecting client misses everything committed while it was away.
**Falsifier:** the query's state resets, `rowsIn()` goes backwards, the fingerprint changes, or
reconnection takes measurably longer than the first connection.
**Setup:** `H-S`, steady 1 000 rows/s feed, distinct keys.
**Steps:** 1. Subscribe; record `rowsIn`, fingerprint, and 5 s of delivered keys. 2. Close.
3. Wait 10 s. 4. Re-subscribe; record the same. 5. `pravaha query --view q`.
**Expected:** same fingerprint; `rowsIn` strictly greater and never reset; the query's `state` is
`RUNNING` throughout; reconnect latency within noise of the first connect. The `10 × 1000 = 10 000`
rows committed during the gap appear in the view but in **neither** delivery window — the union of
the two delivered key sets is short of the view's key count by ≈ 10 000.
**Vacuity:** the 10 s gap at a known rate makes the expected miss a computed number rather than "some".

## STRM-053 — ten subscribers on one query all receive the same changes
**Intent:** `sink.listeners` is a `CopyOnWriteArrayList` iterated per commit. ADR-025's "ten
analysts opening the same dashboard are one computation with ten taps".
**Falsifier:** any subscriber receives a different set of changes, or `registry.size()` exceeds 1.
**Setup:** `H-E`, 10 subscribers each collecting into its own list.
**Steps:** 1. Subscribe ×10. 2. Feed 1 000 distinct-key rows. 3. `commit()`.
**Expected:** all ten lists are equal as ordered lists, each of size 1 000, each summing to
`1000 × 1001 / 2 = 500 500` on `amount`. `registry.size() == 1`.
`query.subscriberCount() == 10`. Total change objects handed out: `10 × 1000 = 10 000`, but only
`1 000` `ViewChange` instances exist — `List.copyOf(pending)` produces one batch shared by
reference across listeners.
**Vacuity:** ordered equality across ten independent collectors rules out per-listener reordering
and per-listener loss, which a size-only check would not.

## STRM-054 — ten subscribers with ten different tap filters, one computation
**Intent:** the `SubscriptionFilter` argument in full: "Ten desks watching ten product types are one
read of the source and one copy of the state."
**Falsifier:** `registry.size() > 1`, or a subscriber receives a row outside its filter.
**Setup:** `H-E` + `product_type` at ordinal 2. Ten subscribers filtering `product_type` =
`P0`…`P9`.
**Steps:** 1. Subscribe ×10. 2. Feed 10 000 rows, `product_type = "P" + (n % 10)`, distinct keys.
3. `commit()`.
**Expected:** each subscriber receives exactly `10 000 / 10 = 1 000` changes, all with its own
`product_type`. `1000 × 10 = 10 000` total, matching the feed with nothing lost and nothing
duplicated. `registry.size() == 1`; one `ServedView`; `subscriberCount() == 10`.
**Vacuity:** the partition is exact by construction (`n % 10`), so any leak across filters shows as
a count other than 1 000 for two subscribers at once.

## STRM-055 — one subscriber leaving does not disturb the others
**Intent:** `CopyOnWriteArrayList` removal during another listener's callback.
**Falsifier:** a remaining subscriber misses a change, or the commit throws.
**Setup:** `H-E`, subscribers A, B, C. A's callback calls `subscriptionA.close()` on its first batch.
**Steps:** 1. Subscribe A, B, C. 2. `feed("u1",1)`; `commit()`. 3. `feed("u2",2)`; `commit()`.
**Expected:** batch 1 delivered to all three. Batch 2 delivered to B and C only. A: `delivered() == 1`.
B and C: `delivered() == 2` each. No `ConcurrentModificationException` — COW snapshots the
iteration. `subscriberCount()` falls `3 → 2`.
**Vacuity:** closing *during* iteration is the interleaving that breaks a plain `ArrayList`; closing
between commits would not exercise it.

## STRM-056 — a subscriber that throws is detached, and the others are unaffected
**Intent:** `Subscription.onCommit` catches `RuntimeException` from the consumer, records a
`QUERY_FAILED` failure and closes. `SubscriptionTest` checks the detach; it does not check that
other subscribers survive.
**Falsifier:** a second subscriber stops receiving, or the query's state leaves `RUNNING`.
**Setup:** `H-E`, subscriber A throwing `IllegalStateException("consumer is broken")` on every call,
subscriber B collecting.
**Steps:** 1. Subscribe A then B. 2. `feed("u1",1)`; `commit()`. 3. `feed("u2",2)`; `commit()`.
4. `feed("u3",3)`; `commit()`.
**Expected:** A called exactly **1** time, `isClosed() == true`, `failure()` present with code
`PRV-8003`-family `QUERY_FAILED` and a message containing "threw and has been detached".
B receives all `3`. `query.state() == RUNNING`. Then repeat with the order reversed (A subscribed
*after* B) — the listener list order changes which one is called first, and both orders must give
B = 3.
**Vacuity:** A throwing on *every* call means "called once" can only be produced by detachment, not
by luck. The order-reversal run rules out an implementation that only survives when the thrower is
last.

## STRM-057 — a subscriber that fails with `FAIL` overflow takes the whole query down
**Intent:** `Subscription.admit`'s `FAIL` arm calls `close()` and then **throws** — from inside
`onCommit`, outside the try that guards `consumer.accept`. That escapes to `ViewSink.commit`'s
listener loop, aborts it, and propagates to `RegisteredQuery.advanceWatermark`, whose
`catch (PravahaException e) { fail(e); throw e; }` sets the query to `FAILED`.
**Falsifier:** — broken if one subscriber's declared overflow policy can terminate a computation
other subscribers depend on. `SubscriptionOptions`' own javadoc frames `FAIL` as failing *the
subscription*.
**Setup:** `H-E`, subscriber A with `SubscriptionOptions.of(1, FAIL)`, subscribers B and C with
`DEFAULT`, all attached in that order.
**Steps:** 1. Subscribe A, B, C. 2. `feed("u1",1)`, `feed("u2",2)`. 3. `query.advanceWatermark(1000)`.
**Expected (documented):** A closes with a failure; B and C receive the batch of 2;
`query.state() == RUNNING`.
**Expected (code as written):** A closes and throws; B and C — later in the listener list — receive
**nothing**; `advanceWatermark` rethrows; `query.state() == FAILED` and
`query.failure()` is A's message. Repeat with A subscribed **last** to confirm B and C do receive
the batch in that ordering — the outcome depending on subscription order is itself the defect.
**Vacuity:** the two orderings over identical data give different results for B and C; that
difference cannot come from anything but the exception escaping mid-iteration.

## STRM-058 — a subscriber on a shared computation registered under two names
**Intent:** `H-S2`: `q` and `q2` are one `RegisteredQuery`, one `ViewSink`, one listener list.
A subscriber on `q2` must see everything, and `subscriberCount()` is a property of the computation,
not of the name.
**Falsifier:** the `q2` subscriber receives nothing, or receives only rows pushed "through" `q2`.
**Setup:** `H-S2`. Subscriber X on `q`, subscriber Y on `q2`.
**Steps:** 1. Subscribe X and Y. 2. Push 100 distinct-key rows. 3. Compare.
**Expected:** X and Y receive identical sets of 100 changes. `subscriberCount()` reported for both
`q` and `q2` is **2**, not 1 each — the count is per computation. `registry.size() == 1`.
Sum on `amount` for both: `100 × 101 / 2 = 5050`.
**Vacuity:** rows are pushed once, to one stream; two subscribers each seeing all 100 is only
possible if both taps are on the same sink.

## STRM-059 — zero subscribers: the sink must not accumulate a change log
**Intent:** `ViewSink.commit`'s comment claims a sink with no listeners "must not accumulate a change
log nobody will ever read, which is a leak that only appears in the deployments that never
subscribe — that is, most of them." `SubscriptionTest.aSinkWithNoSubscribersDoesNotAccumulateAChangeLog`
asserts only `rowsIn() == 50_000` and nothing whatsoever about `pending`. The claim is untested.
**Falsifier:** `pending.size()` is non-zero at any point, or heap held by `ViewChange` grows with
rows fed.
**Setup:** `H-E`, **no** subscription at any point. JFR or a heap histogram on
`com.ash.messaging.pravaha.serving.ViewChange`.
**Steps:** 1. Feed 2 000 000 rows over 100 keys, committing every 10 000. 2. Heap histogram.
3. Force GC, re-histogram. 4. Reflectively read `pending.size()`.
**Expected:** `ViewChange` instance count is **0** — `StagedRow.commit` guards the `pending.add`
with `if (!listeners.isEmpty())`, so none is ever constructed. `pending.size() == 0`. Live heap flat
across the run.
**Vacuity:** 2 000 000 rows is 20× the buffer default and 40× the largest batch anywhere else in
this file; a change log that accumulated would be unmissable in the histogram. 100 keys means the
*view* stays small, so any heap growth is attributable to the change log rather than to the view.

## STRM-060 — a subscriber attaching between `applyValues` and `commit` sees a partial batch
**Intent:** the guard in `StagedRow.commit` is `if (!listeners.isEmpty())`, evaluated per row. A
subscriber that attaches after row 1 but before row 2 of the same commit is delivered row 2 only —
a batch that is not a commit, which is precisely what `USER_GUIDE.md` promises never happens.
**Falsifier:** — broken if the first batch a subscriber receives contains a strict subset of a
commit's rows.
**Setup:** `H-E`. Row 1 fed; then subscribe; then rows 2–3 fed; then one `commit()`.
**Steps:** 1. `feed("u1",1)`. 2. `subscribe`. 3. `feed("u2",2)`, `feed("u3",3)`. 4. `commit()`.
**Expected (promised):** either all 3 (the whole commit) or 0 — never a fragment.
**Expected (code as written):** a batch of **2** — `("u2",2)` and `("u3",3)` — while the view's
commit covered 3 rows. On a windowed query (section J) this is a subscriber receiving part of a
window's close and computing a total that never existed. Record the batch size.
**Vacuity:** the three rows go into one `ServedView.commit` (asserted by the view's `commits`
counter rising by exactly 1 and holding 3 keys), so a delivered batch of 2 is unambiguously a
fragment of one commit.

### E. Lifecycle interaction (STRM-061–076)

## STRM-061 — subscribing to a paused query is allowed and delivers nothing
**Intent:** `RegisteredQuery.subscribe` refuses only when `state.isTerminal()`; `PAUSED` is not
terminal (`QueryState`). So the subscribe succeeds and is silent, because `accept` returns false for
a non-`RUNNING` query and `commit()`/`advanceWatermark` are no-ops outside `RUNNING`.
**Falsifier:** the subscribe is refused (contradicting the code), or it delivers changes while paused.
**Setup:** `H-E`.
**Steps:** 1. `registry.pause("q")`. 2. `subscribe`. 3. `feed("u1",1)` ×10. 4. `query.commit()`.
5. Assert. 6. `registry.resume("q")`. 7. `feed("u2",2)`; `commit()`.
**Expected:** step 2 succeeds and `subscriberCount() == 1`. Step 3's `accept` returns `false` for all
10 rows, so `rowsIn()` does not move. Step 4 delivers nothing (`delivered() == 0`). Step 7 delivers
exactly 1. The 10 rows fed while paused are **gone** — `RegisteredQuery.accept`'s javadoc: "A paused
query drops rows rather than buffering them." So the subscriber's replayed state is short by 10 keys
against a source that sent 11.
**Vacuity:** `accept` returning `false` is the independent signal that the rows were refused, not
merely delayed; the case distinguishes "dropped" from "buffered and later delivered".

## STRM-062 — pausing while a subscriber is attached does not detach it
**Intent:** a pause is meant to stop work, not to tear down consumers. Nothing in `pause()` touches
`sink.listeners`.
**Falsifier:** `subscriberCount()` drops, or the client's stream ends.
**Setup:** `H-S`, one Flight subscriber, steady feed.
**Steps:** 1. Subscribe; confirm rows arriving. 2. `pravaha pause --name q`. 3. Wait 10 s.
4. `pravaha resume --name q`. 5. Wait 10 s.
**Expected:** `subscriberCount()` stays 1 throughout; the client's stream is never `completed` and
never errored; `batches()` stops rising in step 3 and resumes in step 5. Rows pushed during the 10 s
pause at 1 000 rows/s — `10 × 1000 = 10 000` — are dropped and appear in neither the view nor the
stream, which the view's key count confirms.
**Vacuity:** the pause window is 500× the 20 ms commit tick, so "stopped rising" cannot be a
scheduling artefact. The resume run proves the subscriber survived rather than silently died.

## STRM-063 — the `streamSubscription` loop survives a 10-minute pause
**Intent:** the loop's exit conditions are cancelled / latch / `subscription.isClosed()` /
`query.state().isTerminal()`. `PAUSED` is none of them, so the loop should spin on a 200 ms poll
returning null. At `10 × 60 / 0.2 = 3 000` empty polls, confirm it neither exits nor burns CPU.
**Falsifier:** the stream ends, or the server thread shows sustained CPU while idle.
**Setup:** `H-S`, one subscriber.
**Steps:** 1. Subscribe. 2. Pause. 3. Measure the subscription thread's CPU over 10 minutes.
4. Resume; push 10 rows.
**Expected:** stream still open; CPU for the thread ≈ 0 (blocked in `poll`); after resume, all 10
rows delivered. Also confirm no `SRVDBG writing` lines were emitted during the pause (STRM-027).
**Vacuity:** 3 000 poll cycles is enough that a per-poll leak (a thread, an allocation, a log line)
would be visible; 10 rows after resume proves the loop was still functional rather than merely alive.

## STRM-064 — resuming re-arms delivery without replaying the pause
**Intent:** the complement of STRM-061/062, stated as an assertion on content rather than on liveness.
**Falsifier:** the first post-resume batch contains rows fed during the pause.
**Setup:** `H-E`, one subscriber.
**Steps:** 1. Subscribe. 2. `feed("a",1)`; `commit()`. 3. `pause`. 4. `feed("b",2)`, `feed("c",3)`;
`commit()`. 5. `resume`. 6. `feed("d",4)`; `commit()`.
**Expected:** two batches: `[(a,1)]` and `[(d,4)]`. `delivered() == 2`. `b` and `c` appear nowhere —
not in the stream and not in the view (`pravaha query` shows keys `{a, d}`, size 2).
**Vacuity:** the view read confirms the loss is at ingestion, not at delivery; a subscriber-only
assertion could not tell the two apart.

## STRM-065 — dropping a query with a live subscriber ends the stream, but not as an error
**Intent:** `QueryRegistry.drop` → `removeName` returns true → `query.close()` → `state = DROPPED`.
The in-process `Subscription` is **never** closed and its consumer is never told; the Flight loop
notices via `query.state().isTerminal()` within one 200 ms poll and calls `listener.completed()`.
A clean end-of-stream is indistinguishable from a client-initiated close.
**Falsifier:** the client cannot distinguish "the query you were watching was destroyed" from "your
subscription ended normally".
**Setup:** `H-S`, one Flight subscriber plus one in-process `Subscription` on the same query.
**Steps:** 1. Subscribe both. 2. `pravaha drop --name q`. 3. Observe both for 30 s.
**Expected:** Flight client: `stream.next()` returns false, `run()` exits normally, **no** error, no
status, no reason. Time to notice ≤ 200 ms + one RPC. In-process: `subscription.isClosed() == false`
and `failure()` empty — it simply never receives another change, for ever, and remains in
`sink.listeners`. Record both. A drop is an administrative destruction of the thing the client
asked to watch; it should arrive as `CANCELLED`/`NOT_FOUND` with a reason.
**Vacuity:** the in-process observation is the discriminator: an object that still reports itself
open and attached, on a computation that has been closed, is a state no correct implementation has.

## STRM-066 — subscribing to an already-dropped query is refused
**Intent:** `subscribe` checks `state.isTerminal()` first. `SubscriptionTest` covers this; it is
restated here because section E's other cases assume it.
**Falsifier:** the subscribe succeeds.
**Setup:** `H-E`.
**Steps:** 1. `registry.drop("q")`. 2. `query.subscribe(changes -> {})`.
**Expected:** `PravahaException` with `ILLEGAL_TRANSITION`, message containing `PRV-8003` and
"cannot subscribe to 'q': it is DROPPED". Over Flight the equivalent is `require(viewName)` failing
with `NO_SUCH_QUERY` (the name was removed from `byName` first) — a **different** code for the same
user-visible situation. Record both codes; `ERRC` owns whether that is acceptable.
**Vacuity:** the two transports are asked the identical question after the identical action, so
differing codes are attributable to the code paths and nothing else.

## STRM-067 — dropping one name of a shared computation while a subscriber watches the other
**Intent:** `H-S2`. `drop("q")` removes the name and the view from the catalogue but
`removeName` returns false, so the computation keeps running for `q2`. A subscriber on `q2` must be
unaffected.
**Falsifier:** the `q2` subscriber's stream ends, or it stops receiving changes.
**Setup:** `H-S2`. Subscriber Y on `q2` only.
**Steps:** 1. Subscribe Y. 2. Push 50 rows; confirm 50 delivered. 3. `pravaha drop --name q`.
4. Push 50 more rows.
**Expected:** Y's stream stays open and receives the second 50; `50 + 50 = 100` total.
`query.state()` remains `RUNNING`. `pravaha query --view q` now fails (the view was removed from the
catalogue); `pravaha query --view q2` still answers with 100 keys.
**Vacuity:** the `q` read failing is the proof that the drop really took effect, so Y's continued
delivery is a real survival rather than a drop that did nothing.

## STRM-068 — dropping the name a subscriber is watching, on a shared computation
**Intent:** the mirror of STRM-067, and the hazard. Subscriber X subscribed via the ticket naming
`q`. `drop("q")` removes `q` from `byName` and from the catalogue, but `X`'s loop holds the
`RegisteredQuery` object directly and checks only `query.state().isTerminal()` — which is still
`RUNNING`, because `q2` holds the computation open. So X keeps receiving changes on a name that no
longer exists and a view that has been removed.
**Falsifier:** — broken if a subscriber keeps being served under a dropped name.
**Setup:** `H-S2`. Subscriber X on `q` only.
**Steps:** 1. Subscribe X. 2. Push 50 rows; confirm 50. 3. `pravaha drop --name q`. 4. Push 50 more.
5. `pravaha query --view q`.
**Expected (sound):** X's stream ends when `q` is dropped, with a reason.
**Expected (code as written):** X receives the second 50 — `50 + 50 = 100` — while step 5 reports
that `q` does not exist. A client is being streamed rows from a view it can no longer read, under a
name the server says is gone. Also check the authorization consequence: `policy.mayRead(principal,
"q")` was evaluated once at subscribe time, and there is now no `q` for a policy to have an opinion
about.
**Vacuity:** the contradiction is between two server responses to the same name at the same moment
(streaming vs "no such view"), so neither side can be explained away as a stale client.

## STRM-069 — a query that fails while subscribed
**Intent:** `RegisteredQuery.accept` and `advanceWatermark` call `fail(e)` on a `PravahaException`,
setting `FAILED`. `FAILED.isTerminal()` is true, so the Flight loop exits and `listener.completed()`
is called — again with no error, and again losing the reason, which is sitting in `query.failure()`.
**Falsifier:** the client receives no indication that the query failed as opposed to ended.
**Setup:** `H-E` with `maxKeys` set low (see STRM-070) or a deliberately failing expression, one
Flight subscriber and one in-process subscriber.
**Steps:** 1. Subscribe both. 2. Drive the query to `FAILED`. 3. Observe.
**Expected:** `query.state() == FAILED`, `query.failure()` present. Flight client: clean
`completed()`, no error, `run()` returns normally. In-process: never notified, `isClosed() == false`.
Then `registry.resume("q")` must refuse — "A failed query is not restarted in place: whatever failed
is still in the state it failed in" — with `ILLEGAL_TRANSITION`.
**Vacuity:** `query.failure()` being present server-side while the client sees a normal completion
is the whole assertion; both halves are observed in the same run.

## STRM-070 — a view hitting `maxKeys` with a subscriber attached leaks the change log
**Intent:** `ViewSink.commit` calls `view.commit(frontier)` **before** it touches `pending`.
`ServedView.commit` throws `VIEW_TOO_LARGE` after applying and evicting. The throw propagates out of
`ViewSink.commit` with `pending` never copied and never cleared — so every subsequent staged change
piles onto a list that will never be drained, for as long as anything keeps calling `commit`.
**Falsifier:** — broken if `pending` grows without bound after the first `VIEW_TOO_LARGE`.
**Setup:** `H-E` with the view constructed at `maxKeys = 1000`, one subscriber attached (so
`StagedRow.commit` stages into `pending`), retention `forever()`.
**Steps:** 1. Subscribe. 2. Feed 1 000 distinct keys; `commit()` — succeeds. 3. Feed 1 more key;
`commit()` — expect `PRV-4022` `VIEW_TOO_LARGE`. 4. Feed 100 000 more rows, calling `commit()` after
each 1 000 and swallowing the exception. 5. Reflectively read `pending.size()`; take a heap
histogram of `ViewChange`.
**Expected (sound):** `pending` is cleared or bounded regardless of the view's outcome.
**Expected (code as written):** `pending.size()` ≈ `100 001` and rising, `ViewChange` live count
matching, heap growing monotonically. Contrast with the **zero-subscriber** run of the same steps,
where `StagedRow.commit` never stages and `pending` stays 0 — so the leak appears only in the
deployments that *do* subscribe, the exact inverse of the leak the `listeners.isEmpty()` branch was
written to prevent.
**Vacuity:** the zero-subscriber control run is the discriminator; both runs feed identical data and
hit the identical view ceiling, so the difference is attributable to the subscriber alone.

## STRM-071 — retention eviction is invisible to subscribers
**Intent:** `ServedView.evict()` removes keys from `visible` and increments `evicted`, and emits
**nothing**. A subscriber replaying the change stream therefore diverges from the view by exactly
the number of evicted keys, permanently and silently. `docs/OPERATIONS.md` describes retention as a
cache policy for the view; nothing says the stream does not reflect it.
**Falsifier:** — broken if `pravaha query --view` and a stream replay disagree with no signal to the
subscriber.
**Setup:** `H-S` with a registration carrying a short retention (60 s of event time) and an
`event-time` column so `Retention.horizonFor` has something to work with.
**Steps:** 1. Subscribe. 2. Push 10 000 rows with event times spread over 300 s, distinct keys, so
about `10 000 × (300 − 60) / 300 = 8 000` fall outside the window as time advances. 3. Replay the
stream into a map. 4. `pravaha query --view q`; read `evicted()`.
**Expected:** the replayed map holds 10 000 keys; the view holds ≈ 2 000; `evicted()` ≈ 8 000, and
`10 000 − 8 000 = 2 000` reconciles the two. The subscriber received **no** change for any of the
8 000 removals. Assert the reconciliation exactly, using `evicted()` as the bridge.
**Vacuity:** `evicted()` is an independent server counter; the case is a three-way reconciliation
(stream replay, view contents, eviction count) that no two-way check could close.

## STRM-072 — a server restart with a live subscriber
**Intent:** the registry journal replays registrations on start; nothing replays subscriptions.
The client must find out, and the state it holds must be reconcilable.
**Falsifier:** the client hangs, silently receives nothing, or reconnects into a stream that omits
what it missed without saying so.
**Setup:** `H-S` with the journal enabled, steady 1 000 rows/s feed, one SDK subscriber.
**Steps:** 1. Subscribe; record delivered keys. 2. `SIGTERM` the server; restart it. 3. Observe the
client. 4. Re-subscribe; record again. 5. `pravaha query --view q`.
**Expected:** the client's `run()` exits with a transport error (`UNAVAILABLE`), not a clean
`completed()` — a restart is not an orderly end of stream. `q` is `RUNNING` again after replay with
the same fingerprint. The keys committed between the last delivered batch and the restart, plus
those during the downtime, are in the view and in neither delivery window; quantify the gap as
`|view| − |before ∪ after|`. Repeat with a hard `SIGKILL` and record the difference.
**Vacuity:** the two shutdown modes over the same feed rate separate "the client was told" from "the
socket happened to close".

## STRM-073 — subscribing to a query registered, dropped, and re-registered under the same name
**Intent:** after `drop`, `byFingerprint.remove` and `query.close()` run, and a new `register`
creates a **new** `RegisteredQuery` with a new `ServedView` and a new `ViewSink`. A stale ticket
naming the old query must not attach to the new computation's sink, and must not attach to the dead
one either.
**Falsifier:** an old subscription resumes delivering after the re-registration.
**Setup:** `H-S`.
**Steps:** 1. Register `q`; subscribe; push 10 rows; confirm 10. 2. `drop --name q`. 3. Confirm the
stream ended. 4. Register `q` again with identical SQL. 5. Push 10 more rows. 6. Observe the old
client, then subscribe fresh and push 10 more.
**Expected:** the old client stays ended and receives nothing from step 5 — total still 10. The new
subscriber receives the step-6 rows only. `registry.size() == 1`. The new view starts empty:
`pravaha query --view q` after step 5 shows `10` keys, not `20`, because dropping released the state
and the checkpoints (`deleteCheckpointsOf`).
**Vacuity:** the `10` vs `20` key count is the independent confirmation that state really was
released, which is what makes "the old subscriber saw nothing" meaningful rather than accidental.

## STRM-074 — a subscriber across a checkpoint
**Intent:** checkpoints run on their own scheduler while the sink commits. A checkpoint must not
duplicate, reorder, or suppress a delivered change.
**Falsifier:** any duplicate or gap in the delivered sequence across a checkpoint boundary.
**Setup:** `H-S` with checkpointing enabled at a 1 s interval, subscriber with buffer 1 000 000 and
`FAIL` overflow, distinct-key feed at 2 000 rows/s for 120 s (≈ 120 checkpoints).
**Steps:** 1. Subscribe. 2. Feed. 3. Union and histogram the delivered sequences.
**Expected:** `120 × 2000 = 240 000` sequences, each exactly once. Sum
`240000 × 240001 / 2 = 28 800 120 000`. `checkpointFailures() == 0`. Subscription still open.
**Vacuity:** `FAIL` overflow makes any loss terminate the subscription rather than pass; the
histogram separates duplication from loss; 120 boundaries make a once-per-checkpoint defect certain
to appear rather than possible.

## STRM-075 — a subscriber across a blue-green query update
**Intent:** ADR-016 describes replacing a running query's plan. If that path exists and swaps the
`ServedView`/`ViewSink`, every attached listener is attached to the *old* sink and goes silent.
**Falsifier:** a subscriber silently stops receiving after an update it was not told about.
**Setup:** `H-S`. Determine first whether a blue-green update is reachable from any public surface
(`ControlWire` has `REGISTER`, `DROP`, `PAUSE`, `RESUME` and nothing else; `QueryRegistry` may have
an internal path). If it is not reachable, record that ADR-016 describes an unimplemented capability
and stop — that finding is the case's result.
**Steps:** 1. Subscribe. 2. Trigger the update. 3. Push 50 rows. 4. Observe.
**Expected:** either the stream ends with a reason and the client re-subscribes to the new plan, or
it is transparently re-attached and receives all 50. Silently receiving 0 while the stream stays
open is the failure.
**Vacuity:** 50 rows pushed after the swap makes "attached to a dead sink" observable as zero
delivery against a live query.

## STRM-076 — `subscriberCount()` is accurate through a full lifecycle
**Intent:** `OPERATIONS.md` lists `subscriberCount()` as the operator's signal that a query nobody
is watching is a clue. It is `sink.listenerCount()`, so it counts *listeners*, which for a shared
computation spans names and for a broken subscriber depends on detach running.
**Falsifier:** the count does not return to 0 after every subscriber is gone.
**Setup:** `H-S2`, four subscribers: two on `q`, two on `q2`.
**Steps:** Assert the count after each of: 0 subscribers; +1; +2; +3; +4; close one gracefully;
kill one process; one whose consumer throws (in-process); `pause`; `resume`; `drop --name q`;
`drop --name q2`.
**Expected:** `0,1,2,3,4` then `3`; then `2` once the killed client's cancellation lands (record the
delay); then `1` immediately after the throwing consumer is detached; `1` through pause and resume;
`1` after `drop q` (the computation survives for `q2`); and after `drop q2` the computation is
closed — assert whether the count reads `0` or whether the listener was never removed
(`RegisteredQuery.close` does not touch `sink.listeners`).
**Vacuity:** twelve observations of one counter over one computation, each with a known expected
delta; a counter that only ever went up would pass a subset and fail the sequence.

### F. Backpressure toward the subscriber (STRM-077–090)

The mechanism as written: `Subscription.onCommit` admits each change into `buffer` (bounded by
`options.bufferRows()`, overflowing per `options.overflow()`), and then — **in the same call** —
copies the whole buffer, clears it, and hands it to the consumer. The buffer therefore never holds
anything between commits. That is the thing this section is built to establish or refute.

## STRM-077 — the bounded buffer bounds a single commit, not a slow subscriber
**Intent:** `SubscriptionOptions` says the buffer "decides how far behind a subscriber may fall".
`Subscription.onCommit` drains it unconditionally before returning, so a subscriber cannot fall
behind *in the buffer* at all — it can only be slow inside `consumer.accept`, during which the
engine waits (STRM-047). The overflow policy fires only when one commit exceeds `bufferRows`.
**Falsifier:** — the documented model holds if a subscriber that is slow *across* commits triggers
`conflated()`/`dropped()`. It is falsified if those counters stay at 0 no matter how far behind the
consumer is, while overflow fires on a single large commit with a fast consumer.
**Setup:** `H-E`. Run A: `SubscriptionOptions.of(10, CONFLATE)`, consumer sleeping 500 ms per batch,
fed 1 row per commit across 200 commits on 5 rotating keys. Run B: same options, instant consumer,
one commit of 100 rows on 5 rotating keys.
**Steps:** run both; read `delivered()`, `conflated()`, `dropped()` after each.
**Expected (documented):** Run A, the slow one, shows `conflated() > 0`.
**Expected (code as written):** Run A: `delivered() == 200`, `conflated() == 0`, `dropped() == 0`,
and wall-clock `≥ 200 × 0.5 = 100` s — the slowness became engine latency, not conflation. Run B:
`delivered() == 10`, `conflated() == 90` (5 keys, so `replaceByKey` matches after the buffer fills),
`dropped() == 0` — the fast subscriber is the one that conflates. The mechanism is inverted relative
to its documentation.
**Vacuity:** the two runs differ only in *where* the slowness is; both counters are read from the
same object in the same way, so the inversion cannot be a measurement artefact.

## STRM-078 — `bufferRows` boundary: exactly at, one below, one above
**Intent:** `admit` uses `buffer.size() < options.bufferRows()`. Three values, three outcomes.
**Falsifier:** off-by-one in either direction.
**Setup:** `H-E`, `SubscriptionOptions.of(100, DROP_OLDEST)`, distinct keys, one commit per run.
**Steps:** Three runs feeding 99, 100 and 101 rows respectively, then one `commit()`.
**Expected:** 99 → `delivered() == 99`, `dropped() == 0`. 100 → `delivered() == 100`,
`dropped() == 0`. 101 → `delivered() == 100`, `dropped() == 1`, and the missing row is the **first**
fed (`removeFirst`), so received `amount` values are `2..101` summing to
`101 × 102 / 2 − 1 = 5150`.
**Vacuity:** the three runs bracket the boundary; naming which row is lost pins `removeFirst`
specifically.

## STRM-079 — `bufferRows = 1`, the minimum the record allows
**Intent:** `SubscriptionOptions`' compact constructor refuses `bufferRows < 1`. At exactly 1 every
change after the first in a commit overflows.
**Falsifier:** a value of 1 is refused, or behaves as unbounded.
**Setup:** `H-E`, `of(1, DROP_OLDEST)`, 5 distinct keys, one commit.
**Steps:** 1. Subscribe. 2. `feed("k1",1)` … `feed("k5",5)`. 3. `commit()`. 4. Separately, construct
`SubscriptionOptions.of(0, DROP_OLDEST)` and `of(-1, DROP_OLDEST)`.
**Expected:** `delivered() == 1`, `dropped() == 4`, and the single delivered change is the **last**
fed (`amount == 5`). Also assert `of(0, …)` and `of(-1, …)` throw `IllegalArgumentException` with
"bufferRows must be at least 1, got 0" / "got -1".
**Vacuity:** "the last, not the first" is the assertion that distinguishes `removeFirst` + `add`
from `add` + truncate.

## STRM-080 — `CONFLATE` replaces by key, keeping the newest value per key
**Intent:** `replaceByKey` walks the buffer and swaps the first change with a matching key.
**Falsifier:** the delivered change for a key is not the newest one fed.
**Setup:** `H-E`, `of(2, CONFLATE)`, keys `k1`, `k2` only.
**Steps:** 1. Subscribe. 2. `feed("k1",10)`, `feed("k2",20)`, `feed("k1",30)`, `feed("k2",40)`,
`feed("k1",50)`. 3. `commit()`.
**Expected:** buffer fills with `[k1:10, k2:20]`; `k1:30` replaces `k1:10` (conflated 1); `k2:40`
replaces `k2:20` (2); `k1:50` replaces `k1:30` (3). Delivered batch is `[k1:50, k2:40]`, size 2,
`conflated() == 3`, `dropped() == 0`, sum `50 + 40 = 90`. Buffer **order** is preserved by
`replaceByKey` (it rewrites in place), so `k1` stays first.
**Vacuity:** the interleaved feed order means a naive "keep the last 2" would give `[k2:40, k1:50]`,
a different order and a different first element; both are asserted.

## STRM-081 — `CONFLATE` on a key never seen falls back to dropping the oldest
**Intent:** `admit`'s `CONFLATE` arm: when `replaceByKey` returns false it does `removeFirst()` +
`add()` and increments **`dropped`**, not `conflated`. Two different outcomes from one policy, and
the counter correctly distinguishes them.
**Falsifier:** a non-matching key increments `conflated()`.
**Setup:** `H-E`, `of(2, CONFLATE)`, all keys distinct.
**Steps:** 1. Subscribe. 2. `feed("a",1)`, `feed("b",2)`, `feed("c",3)`, `feed("d",4)`. 3. `commit()`.
**Expected:** delivered `[c:3, d:4]`, `dropped() == 2` (`a` and `b`), `conflated() == 0`.
Contrast with STRM-080's `conflated() == 3, dropped() == 0` on the same buffer size and the same
number of rows — the key cardinality is the only difference.
**Vacuity:** the pair of cases holds everything constant except distinct-vs-repeated keys, so the
counter split is attributable to `replaceByKey` alone.

## STRM-082 — `CONFLATE` is wrong for a weight-maintaining consumer, and nothing prevents it
**Intent:** `SubscriptionOptions.CONFLATE`'s own javadoc: "Wrong for anything maintaining its own
aggregate from the weights, because conflating drops the intermediate weights that aggregate is
built from." It is also the **default**, and the only option the Flight path ever uses
(`streamSubscription` hard-codes `SubscriptionOptions.DEFAULT`).
**Falsifier:** — the hazard is confirmed if a conflated stream replays to a different total than the
view, with no error and no counter the client can see.
**Setup:** `H-EA`, `of(2, CONFLATE)`, one key `u1`.
**Steps:** 1. Subscribe. 2. `feed("u1",10)`, `feed("u1",20)`, `feed("u1",30)`. 3. `commit()`.
**Expected:** the un-conflated stream would be `+1 (u1,10), -1 (u1,10), +1 (u1,30), -1 (u1,30),
+1 (u1,60)` — weighted total `10 − 10 + 30 − 30 + 60 = 60 = 10+20+30`. With `bufferRows = 2` and
conflation by key, the buffer holds at most 2 changes for `u1` and the retractions are replaced by
inserts and vice versa: record the exact delivered sequence and its weighted sum. It will not be
`60`. `conflated() > 0` server-side; the Flight client sees no counter at all (STRM-049), and no
weights either (STRM-017).
**Vacuity:** `60` is computed from the input independently of the implementation, so any other
replayed total is a definite divergence.

## STRM-083 — `DROP_OLDEST` counts every loss
**Intent:** the guarantee `OPERATIONS.md` states: "Never silent — this is why it is counted."
**Falsifier:** `delivered() + dropped()` is less than the number of changes that reached
`Subscription.onCommit`.
**Setup:** `H-E`, `of(2, DROP_OLDEST)`, 10 000 distinct keys, one commit.
**Steps:** 1. Subscribe. 2. `for n in 1..10000: feed("k"+n, n)`. 3. `commit()`. 4. Read all three
counters.
**Expected:** `delivered() == 2`, `dropped() == 9 998`, and `2 + 9998 = 10 000` — every change
accounted for. The two delivered are the last two fed (`amount` `9999` and `10000`).
**Vacuity:** the arithmetic identity `delivered + dropped = fed` is the accounting claim itself; it
fails on any silent loss, which is exactly what a count-only-what-you-noticed implementation gives.

## STRM-084 — `FAIL` closes the subscription and reports why
**Intent:** the contract for a ledger or audit feed.
**Falsifier:** the subscription continues with a gap, or closes without a `failure()`.
**Setup:** `H-E`, `of(1, FAIL)`, one subscriber only (so STRM-057's blast radius is not in play).
**Steps:** 1. Subscribe. 2. `feed("u1",1)`, `feed("u2",2)`. 3. `query.commit()`.
**Expected:** `commit()` throws `PravahaException`; `subscription.isClosed() == true`;
`failure()` present, message contains "fell more than 1 changes behind and asked to be failed rather
than lose any. Reconnect and re-read the view to catch up". Note the message says "1 changes" — a
message-quality defect `ERRC` owns. `delivered() == 0` — the consumer is never called at all, so the
subscriber that "cannot afford to miss anything" is told before it is given a partial batch.
**Vacuity:** `delivered() == 0` distinguishes "failed before delivering" from "delivered one then
failed", which are materially different guarantees for a ledger.

## STRM-085 — `FAIL` is unreachable from any client, because the carrier hard-codes `DEFAULT`
**Intent:** `ClientOptions` has `subscriberBufferRows` (default 10 000) and `conflateOnOverflow()`;
`ControlWire.subscribeTicket(view, filterPairs)` carries neither; `streamSubscription` passes
`SubscriptionOptions.DEFAULT`. So a remote subscriber's declared overflow policy is silently ignored
and is always `(10_000, CONFLATE)`.
**Falsifier:** — sound if a client can select `FAIL` or `DROP_OLDEST` and observe the difference.
**Setup:** `H-S`, SDK client built with `subscriberBufferRows(1)` and, if the builder allows,
conflation off.
**Steps:** 1. Subscribe with those options. 2. Drive a single commit of 5 000 rows. 3. Observe.
**Expected (sound):** the subscription fails, or drops, per the client's choice.
**Expected (code as written):** all 5 000 delivered (5 000 < 10 000), the client's settings having
had no effect whatsoever. Confirm by decoding the ticket bytes: `ControlWire.subscribeTicket`
encodes `["subscribe", view, col, val, …]` and nothing else. Record that
`ClientOptions.subscriberBufferRows` is a setting with no reachable effect — `CFG`/`SDKX` territory,
raised here because `OPERATIONS.md` presents the overflow policy as "per the subscriber's choice".
**Vacuity:** the ticket bytes are a direct structural observation; the 5 000-row run is the
behavioural confirmation.

## STRM-086 — the handover queue bound is stated in batches and is therefore not a bound on memory
**Intent:** `SUBSCRIPTION_HANDOVER_BATCHES = 64`, justified as "Small on purpose… A deep queue here
would silently override that choice". 64 *batches* of unbounded size is not small.
**Falsifier:** server heap attributable to one stalled subscriber exceeds any stated bound.
**Setup:** `H-S`, one subscriber that connects and then never reads (SDK subscriber that never calls
`run()`, or a raw gRPC client with a zero-sized flow-control window). Commits of 50 000 rows each
(STRM-021's shape).
**Steps:** 1. Subscribe, do not read. 2. Drive 200 commits of 50 000 rows. 3. Heap histogram on
`ViewChange`, and RSS.
**Expected:** the handover holds up to `64 × 50 000 = 3 200 000` `ViewChange` objects plus their
`Object[]` payloads — at 3 columns and ~100 bytes per change, ≈ 320 MB **per stalled subscriber**.
Commits 65 onward are dropped whole. Measure the peak and compare against 64 × a typical small
commit (64 × 100 = 6 400 changes, ≈ 0.6 MB), the case the constant was sized for. Then repeat with
10 stalled subscribers: `10 × 320 MB = 3.2 GB`.
**Vacuity:** the two commit sizes over identical subscriber behaviour isolate "bounded in batches"
from "bounded in rows"; a row-bounded queue would show the same peak in both.

## STRM-087 — a stalled subscriber cannot starve ingestion over Flight
**Intent:** round 1 observed a *reader* starving ingestion on the read path. The subscription path
should not, because the handover uses `offer`. This is the case that proves or disproves it.
**Falsifier:** ingest throughput with N stalled subscribers is materially below the N = 0 baseline.
**Setup:** `H-S`, steady maximum-rate feed, measured as `rowsIn()` per second over 60 s.
**Steps:** Measure with 0, 1, 5, 20, 50 stalled subscribers (connected, never reading).
**Expected:** throughput within 10 % of baseline at every N. Any decline must be attributable and
named — candidates in order of likelihood: the per-batch `System.out.println` in `writeBatch`
(STRM-027) serialising on `System.out`'s monitor; per-subscriber Arrow encoding (STRM-028); the
`ViewSink.commit` listener loop growing with N; heap pressure from STRM-086.
**Vacuity:** the N = 0 baseline is taken in the same process on the same feed, and the named
candidates give the run a falsifiable cause rather than a number.

## STRM-088 — a slow **in-process** subscriber starves ingestion, with a measured figure
**Intent:** STRM-047 established the mechanism; this quantifies it against the 20 ms cadence, since
`PumpingFeed`'s publish timer is the caller of `commit` in a real node.
**Falsifier:** ingest rate is unaffected by in-process consumer latency.
**Setup:** an embedded node (`H-E` driven by a `PumpingFeed`-equivalent at 20 ms) with one
in-process subscriber whose callback sleeps `d` ms.
**Steps:** For `d` ∈ {0, 1, 5, 10, 20, 50, 200}, feed at maximum rate for 60 s; record `rowsIn()/s`.
**Expected:** at `d = 0` the baseline. At `d = 20` the publish tick is fully consumed by the
consumer, so the effective commit rate halves to ≈ 25/s. At `d = 200` it falls to ≈ 5/s, a 10×
reduction, and rows arriving faster than that are refused by `accept` returning false (inbox full).
Assert the monotonic decline and report the value of `d` at which `rowsIn()/s` first drops 10 %
below baseline.
**Vacuity:** seven points on one curve; a flat curve falsifies the mechanism outright and a
monotonic one cannot be produced by noise.

## STRM-089 — no blocking anywhere: `offer` not `put`, `poll` not `take`
**Intent:** a structural audit of the claim "The handover must not block, so it does not."
**Falsifier:** any blocking call on the delivery path.
**Setup:** source inspection plus a runtime thread dump under load.
**Steps:** 1. Grep the subscription path for `put(`, `take(`, `await(` without a timeout, and
`synchronized` blocks that span a consumer call. 2. Under a 50-stalled-subscriber load (STRM-087),
take 20 thread dumps 1 s apart and classify every engine thread's state.
**Expected:** `handover.offer(...)` and `handover.poll(200, MILLISECONDS)` are the only queue
operations. The one blocking construct on the path is `synchronized (buffer)` in
`Subscription.onCommit`, held across the whole filter-and-admit loop but **not** across
`consumer.accept` — confirm that in the source and that no dump shows an engine thread blocked on a
`Subscription.buffer` monitor. Note separately that `synchronized (pending)` in `ViewSink` is held
across `List.copyOf(pending)`, which is O(batch), on the commit thread.
**Vacuity:** the thread dumps are taken under the load most likely to produce blocking; a
source-only audit would not distinguish "no blocking call" from "no blocking observed".

## STRM-090 — the counters are monotonic and consistent under concurrency
**Intent:** `delivered`, `conflated`, `dropped` are `AtomicLong`s updated from the commit thread;
`delivered` is incremented **after** `consumer.accept` returns, so a consumer that throws leaves the
batch uncounted.
**Falsifier:** any counter decreases, or `delivered()` includes a batch the consumer never accepted.
**Setup:** `H-E`, one subscriber whose consumer throws on its 5th call, with `delivered()` sampled
from another thread throughout.
**Steps:** 1. Subscribe. 2. 10 commits of 3 distinct-key rows each. 3. Sample `delivered()` after
each commit.
**Expected:** `delivered()` reads `3, 6, 9, 12, 12, 12, …` — the 5th batch's 3 changes are **not**
counted because the throw precedes `delivered.addAndGet`, and nothing is counted afterwards because
the subscription closed. Final `delivered() == 12`, `4 × 3 = 12`. `dropped() == 0` and
`conflated() == 0` — the 3 changes the consumer rejected are counted *nowhere*, so
`delivered + dropped + conflated = 12 ≠ 15` fed. Record the discrepancy: a detached subscriber's
last batch is unaccounted for, which contradicts "What is dropped is always counted".
**Vacuity:** `15 − 12 = 3` is exact and reproducible; the case is an accounting identity, not a
timing observation.

### G. Security on the stream (STRM-091–102)

`docs/../application.yaml` (line 78) and `SecurityProperties`' javadoc both say "row filters honoured
on subscribe". `streamSubscription` does not honour them — it **refuses** any principal whose
`AccessDecision` carries a row filter. Refusing is the safe behaviour and a previous leak is on the
record ("a principal whose entitlement is `region = 'EU'` would have received every region"). The
documentation is what is wrong, and three cases below pin it.

## STRM-091 — an unauthorised principal is refused before the stream starts
**Intent:** `policy.mayRead(principal, viewName)` is checked, audited, and enforced before
`listener.start(root)`.
**Falsifier:** any row reaches a principal `mayRead` denies.
**Setup:** `H-S` with `pravaha.security.authentication: token`, `policy: tenant`,
`allow-anonymous: false`. View `q` owned by tenant `acme`. Principal `bob@other` with a valid token.
**Steps:** 1. `pravaha subscribe --view q --token <bob>`. 2. Push 100 rows. 3. Wait 5 s.
**Expected:** the call fails immediately with `SecurityErrors.FORBIDDEN`, message
`"bob may not subscribe to 'q': <reason>"`. Zero Arrow batches — `listener.start` is never reached.
An `AuditEvent` of type `subscribe` with `allowed = false` is recorded. CLI exit code non-zero.
**Vacuity:** 100 rows are pushed and the subscription is held open for 5 s, so "zero rows" is a real
refusal rather than an empty feed.

## STRM-092 — an unauthenticated subscriber against an authenticating node
**Intent:** `principalOf` returns `Principal.ANONYMOUS` only when the middleware is absent
(embedded). A node with `authentication: token` refuses before the producer is reached.
**Falsifier:** an anonymous caller opens a subscription on a token-authenticating node.
**Setup:** `H-S` with `authentication: token`, `allow-anonymous: false`.
**Steps:** Subscribe with: (a) no token; (b) a malformed token; (c) an expired/unknown token;
(d) a valid token.
**Expected:** (a)(b)(c) refused at the transport with `UNAUTHENTICATED`, no Pravaha error code
needed and no view name echoed back — a refusal must not confirm that `q` exists. (d) succeeds.
Assert that (a)–(c) produce **no** `AuditEvent` naming the view, or if they do, that the event does
not disclose it to the caller.
**Vacuity:** the (d) control proves the node is serving; (a)–(c) differ only in the credential.

## STRM-093 — a principal whose access is conditional on a row filter is refused, not over-served
**Intent:** the leak that was fixed. `decision.rowFilter().isPresent()` → `FORBIDDEN` with a long
explanatory message.
**Falsifier:** — the leak recurs if such a principal receives any row outside its filter.
**Setup:** `H-S`, policy granting `carol` read on `q` conditional on `region = 'EU'`. Stream carries
a `region` column with values `EU` and `US`.
**Steps:** 1. `pravaha subscribe --view q --token <carol>`. 2. Push 50 `EU` and 50 `US` rows.
**Expected:** refused. Message contains "because their access to it is conditional on the row filter
'region = 'EU''" and "a subscription cannot enforce a filter -- it delivers every change the view
commits", and directs the caller to read the view instead. Zero rows delivered — specifically zero
`US` rows, which is the leak, **and** zero `EU` rows, which is the (deliberate) over-refusal.
**Vacuity:** 50 rows of each region means a leak is 50 observable rows, not a subtle one.

## STRM-094 — the same principal *can* read the view it may not subscribe to
**Intent:** the refusal's own suggested remedy must work. `ViewQuery` ANDs the predicate into the
plan.
**Falsifier:** the suggested workaround also fails, making the refusal message wrong.
**Setup:** as STRM-093.
**Steps:** `pravaha query --view q --token <carol>` after the 100 rows of STRM-093.
**Expected:** 50 rows, all `region = 'EU'`, zero `US`. `50 + 50 = 100` pushed, 50 visible to carol.
So the message is actionable — the entitlement is enforceable on one path and not the other, which
is the real state of the system and worth recording as such.
**Vacuity:** the row counts on the two paths for one principal over one dataset are the whole
comparison.

## STRM-095 — the documentation says row filters are honoured on subscribe; they are not
**Intent:** `pravaha-server/src/main/resources/application.yaml:78` and `SecurityProperties`'
javadoc both list "row filters honoured on subscribe" among the mechanisms that were "already built
and tested". `CONCEPTS.md` §6 lists "Subscription filters → Refuse the filter (`PRV-8002`)" as a
*subscriber's* filter rule, not a security one, so nothing documents the refusal.
**Falsifier:** — the docs are correct if a conditionally-entitled principal can subscribe and
receive only its rows.
**Setup:** the STRM-093 setup, plus a checkout of `develop` for the documentation audit.
**Steps:** 1. Run STRM-093. 2. `grep -rn "honoured on subscribe" docs/ pravaha-server/`.
3. Search every doc for any statement that a conditional entitlement makes `subscribe` impossible.
**Expected:** STRM-093 refuses; the two doc sites claim the opposite; no doc anywhere tells an
operator that granting a row filter removes a principal's ability to subscribe at all. File as
documentation rot, and note the operational consequence: switching `policy: permissive` →
`policy: tenant` can silently break every dashboard belonging to a conditionally-entitled user, with
no migration note.
**Vacuity:** the grep result is the evidence; the case is a direct code-versus-doc comparison.

## STRM-096 — an entitlement revoked while a stream is open
**Intent:** `policy.mayRead` is evaluated **once**, at subscribe time. Nothing re-checks it. A
long-lived dashboard keeps receiving rows after its access is withdrawn.
**Falsifier:** — broken if revocation has no effect on an open stream.
**Setup:** `H-S` with a policy whose grants are reloadable (or a `SecurityPolicy` test double whose
decision can be flipped at runtime).
**Steps:** 1. `dana` subscribes; push 50 rows; confirm 50. 2. Revoke `dana`'s read on `q`. 3. Push
50 more rows. 4. Wait 60 s. 5. Also attempt `pravaha query --view q --token <dana>`.
**Expected (sound):** the stream ends with `FORBIDDEN` within some bounded interval.
**Expected (code as written):** the second 50 are delivered — `50 + 50 = 100` — indefinitely, while
step 5 is refused. Record the divergence and how long the stream survives (until the client
disconnects, the query is dropped, or the server restarts). Note that a revoked principal reading
`pravaha query` is refused, so the two paths disagree about the same principal at the same instant.
**Vacuity:** step 5 is the independent confirmation that revocation took effect server-side, so
continued delivery is not a stale-policy artefact.

## STRM-097 — a principal's token expiring while a stream is open
**Intent:** the narrower version of STRM-096, and the more likely one: tokens expire on a clock, not
on an administrative action.
**Falsifier:** an expired credential continues to receive data with no re-authentication.
**Setup:** `H-S` with `authentication: token` and a token with a 60 s lifetime.
**Steps:** 1. Subscribe. 2. Feed steadily for 300 s. 3. Observe.
**Expected:** record whether the stream ends at ≈ 60 s (re-authentication enforced) or continues for
the full 300 s (authenticated once, at connect). Given `principalOf` reads the middleware's
principal once per call and `getStream` is one long call, expect the latter. Quantify: `300 − 60 =
240` s of delivery on an expired credential, `240 × commit rate` batches.
**Vacuity:** the token lifetime is known and short relative to the run, so the boundary is a
specific predicted time rather than "eventually".

## STRM-098 — subscribe is audited, including the filter
**Intent:** `audit.record(AuditEvent.of(principal, "subscribe", viewName, decision,
filterText(fields)))` runs for allowed **and** denied decisions, before the refusal.
**Falsifier:** a denied subscribe is not audited, or the audited filter text does not match what was
asked for.
**Setup:** `H-S` with `audit: memory`.
**Steps:** Subscribe four ways: allowed no filter; allowed with `product_type=SWAP`; allowed with
two filter pairs; denied. Then read the audit sink.
**Expected:** four events, all typed `subscribe`, with `allowed` true/true/true/false and filter
text `"no filter"`, `"product_type=SWAP"`, `"product_type=SWAP=user_id=u1"` (note `filterText`
joins **all** remaining fields with `=`, producing that mangled form for two pairs — a defect worth
recording), and the denied one carrying its filter too.
**Vacuity:** the two-pair case is the one that exposes the join defect; a single-pair-only test
would pass on a broken implementation.

## STRM-099 — `pravaha.drop` / `pause` / `resume` are authorized; `subscribe` uses a different verb
**Intent:** `requireAdministrable` uses `policy.mayAdminister`; `streamSubscription` uses
`policy.mayRead`. A principal who may read but not administer must be able to subscribe and unable
to drop. The javadoc records that these three verbs once authorized nothing at all.
**Falsifier:** a read-only principal can drop, or an administer-only principal cannot subscribe when
it may also read.
**Setup:** `H-S`, principals: `reader` (read only), `admin` (read + administer), `neither`.
**Steps:** Each principal attempts: subscribe, drop, pause, resume. 12 attempts.
**Expected:** `reader`: subscribe ✓, drop ✗, pause ✗, resume ✗. `admin`: all ✓. `neither`: all ✗.
Every refusal carries `SecurityErrors.FORBIDDEN` and an audit event. 12 outcomes, all asserted.
**Vacuity:** the 3×4 grid is written out; a single-principal test would not separate the two policy
methods.

## STRM-100 — a subscriber cannot see rows the query's own security predicate excluded
**Intent:** ADR-025: the fingerprint includes "the normalised plan **including the security
predicates applied to it**", so two principals with different entitlements get *different*
computations and different views. A subscriber taps a view that was already built for its principal.
**Falsifier:** two principals with different predicates share one fingerprint, and therefore one
sink, and therefore one stream.
**Setup:** `H-S`, principals `p_eu` and `p_us` whose policies inject `region = 'EU'` and
`region = 'US'`. Both register byte-identical SQL under different names.
**Steps:** 1. Both register. 2. Compare fingerprints via `pravaha queries`. 3. Both subscribe (note:
if either carries a *row filter* decision, subscribe is refused per STRM-093 — in that case assert
the refusal and record that the entitlement model and the subscription model do not meet).
4. Push 50 `EU` and 50 `US` rows.
**Expected:** two distinct fingerprints, `registry.size() == 2`, two views, two sinks. If subscribe
is reachable at all, `p_eu` receives 50 and `p_us` receives 50, with zero crossover. If it is not
reachable, the finding is that the *only* entitlement mechanism the subscription path supports is
"unconditional or nothing".
**Vacuity:** identical SQL text with different predicates is precisely the case ADR-025 says
text-hash sharing would leak; the fingerprint comparison is a direct structural check.

## STRM-101 — the tap filter is not a security boundary and must not be mistaken for one
**Intent:** `SubscriptionFilter` is the *subscriber's* choice, applied in `Subscription.onCommit`
after the sink has already assembled the batch. It restricts what a subscriber receives; it does not
restrict what it may ask for. A client can subscribe with no filter at all.
**Falsifier:** any documentation or API presents the tap filter as an access control.
**Setup:** `H-S`, `policy: permissive`.
**Steps:** 1. Subscribe with `--filter product_type=SWAP`, confirm only `SWAP`. 2. Subscribe again
with no filter, confirm everything. 3. Audit the docs for any wording that conflates the two.
**Expected:** step 2 succeeds and delivers all rows, which is correct and is the point: the filter
is a convenience. `CONCEPTS.md` §6's table places "Subscription filters" alongside "Security row
filters" in one matrix — check whether a reader could take that as an authorization claim, and say
so if they could.
**Vacuity:** step 2 is the demonstration; a filter that could not be omitted would be a boundary,
and it can be.

## STRM-102 — error messages on the subscription path do not disclose what the caller may not know
**Intent:** `SECX` owns disclosure generally; this is the subscription-path slice.
`streamSubscription` calls `require(viewName)` **before** `policy.mayRead`, so a nonexistent view and
a forbidden view produce different errors. `SubscriptionFilter`'s refusal lists the view's **entire
column list** in the message.
**Falsifier:** an unauthorised caller learns a view's existence, or its schema, from an error.
**Setup:** `H-S`, `policy: tenant`, principal `bob@other`, view `payroll` owned by `acme`.
**Steps:** 1. `bob` subscribes to `payroll`. 2. `bob` subscribes to `payrol` (typo). 3. `bob`
subscribes to `payroll --filter nosuchcol=x`. 4. Compare the three messages verbatim.
**Expected:** (1) `FORBIDDEN` "bob may not subscribe to 'payroll'". (2) `NO_SUCH_QUERY`. These
differ, so `bob` learns `payroll` exists. (3) reaches `SubscriptionFilter.matching` only *after*
`mayRead` passed — so for `bob` it cannot; confirm that, and then run (3) as an *authorised*
principal to capture the column-list disclosure and judge whether listing every column of a view the
caller may read is acceptable (it is; the case exists to prove the ordering, not to object).
**Vacuity:** the one-character spelling difference in (2) isolates existence disclosure from every
other variable.

### H. Latency — row arrival to delivery (STRM-103–108)

The cadence to measure against is `PumpingFeed.PUBLISH_INTERVAL_NANOS = 20_000_000L` — 20 ms. Round
1's `INGEST` log measured ~30 ms visibility on the *read* path (one publish interval plus a Flight
round trip). The subscription path should be no worse and arguably better, since the server pushes.

## STRM-103 — end-to-end latency at idle, single subscriber
**Intent:** the number a product claim rests on. Measured from the row's own arrival timestamp to
the instant the subscriber's callback sees it.
**Falsifier:** p50 above 40 ms (two commit ticks), or p99 above 100 ms (five ticks), at one row per
second on an idle node.
**Setup:** `H-S`, one SDK subscriber on the same host (loopback), clock read with
`System.nanoTime()` on both sides of one process where possible; otherwise timestamp the row at
DoPut and compare against callback time in the same JVM.
**Steps:** 1. Subscribe. 2. Push 1 row/s for 600 s (600 samples). 3. Report the full distribution.
**Expected:** p50 ≈ 10 ms (uniform arrival within a 20 ms window averages half of it) + one RPC,
call it 10–25 ms. p99 < 100 ms. Report min, p50, p90, p99, max, and the count above 100 ms. Record
whether `SRVDBG` stdout (STRM-027) was going to a terminal, a file, or `/dev/null` — it changes the
answer and must be stated.
**Vacuity:** 600 samples at 1/s on an idle node means queueing is not a factor, so the measurement
is of the cadence and the transport, not of load. The distribution's *shape* (a floor at ~0 and a
ceiling at ~20 ms + RPC) is itself a check that the 20 ms tick is what is being measured.

## STRM-104 — latency does not grow with view size
**Intent:** `ViewSink.commit` → `ServedView.commit` → `evict()`, which is an O(view) sweep on every
commit unless retention is `forever()`. Round 1's `INGEST` finding 15 recorded ingest decaying to a
fifth as the view grew, with `evicted = 0` on all 1 400+ commits measured. Every subscriber's
delivery sits behind that sweep.
**Falsifier:** — the claim "latency is bounded" is falsified if p50 rises with the number of keys in
the view.
**Setup:** `H-S` with the default retention (24 h, which is **not** `forever()`, so the sweep runs),
one subscriber, a distinct-key feed.
**Steps:** Measure p50/p99 delivery latency for 100 probe rows at view sizes of
`10^3`, `10^4`, `10^5`, `10^6` keys, growing the view between measurements.
**Expected (bounded):** p50 flat across all four, within noise of STRM-103.
**Expected (if the sweep dominates):** p50 rising roughly linearly — if `10^3` keys cost `x` ms then
`10^6` costs `1000x`, and at any `x > 0.02 ms` the 20 ms tick is missed entirely. Report the four
numbers and the ratio `p50(10^6) / p50(10^3)`. Repeat the whole matrix with `Retention.forever()`
registered explicitly; if the ratio collapses to ~1, `evict()` is confirmed as the cause.
**Vacuity:** the `forever()` re-run is the controlled variable — same view sizes, same feed, sweep
disabled — so a difference is attributable to `evict()` and to nothing else.

## STRM-105 — latency under subscriber fan-out
**Intent:** `ViewSink.commit` iterates listeners **serially** on the commit thread, and each Flight
subscriber does its own Arrow encoding and its own `System.out.println`. The last subscriber in the
list waits for all the others.
**Falsifier:** p99 for the *last-attached* subscriber grows with N.
**Setup:** `H-S`, 1 probe subscriber attached **last**, plus N−1 others. N ∈ {1, 10, 50, 200}.
**Steps:** For each N, push 100 probe rows at 1/s and measure the probe subscriber's latency.
**Expected:** report p50/p99 per N and the ratio to N = 1. With per-subscriber encoding (STRM-028)
and a synchronised stdout write per batch (STRM-027), expect visible growth; quantify it. At N = 200
and 50 commits/s, stdout alone is `200 × 50 = 10 000` synchronised writes per second.
**Vacuity:** the probe is always the last listener attached, so its position in the iteration is
fixed across runs and N is the only variable.

## STRM-106 — latency under ingest load
**Intent:** the cadence holds only if a commit completes inside its tick. At high ingest, commits
get larger and delivery latency should rise with batch size, not with time since arrival.
**Falsifier:** latency becomes unbounded (growing without limit) rather than settling at a higher
plateau.
**Setup:** `H-S`, one subscriber, feed rates of 100, 1 000, 10 000, 50 000 rows/s for 120 s each.
**Steps:** Timestamp every row; measure delivery latency distribution per rate.
**Expected:** per-commit batch sizes of `rate / 50` = `2`, `20`, `200`, `1 000` rows. p50 latency
should stay ≈ 10–25 ms at all four if the engine keeps up. If it grows monotonically over the 120 s
at any rate, the engine is not keeping up at that rate and the number to report is the rate at which
latency first fails to plateau. Cross-check `rowsIn()` against rows pushed — if `accept` is refusing
(returning false), report that instead of a latency figure, because the two mean different things.
**Vacuity:** the `rowsIn()` cross-check prevents the classic false pass where latency looks fine
because most rows were never admitted.

## STRM-107 — delivery latency for a subscriber that is the only thing keeping the query busy
**Intent:** the pathological read of STRM-047 in latency terms: a subscriber whose own callback
costs `d` ms adds `d` to the commit and therefore to *its own* next delivery and to every other
subscriber's.
**Falsifier:** a subscriber's callback duration does not appear in another subscriber's latency.
**Setup:** `H-E` driven at 20 ms, subscriber A with `d = 100` ms, probe subscriber B attached after
A, measured.
**Steps:** Push 1 row/s for 300 s; measure B's latency with A present and with A absent.
**Expected:** with A absent, B's p50 ≈ the STRM-103 baseline. With A present, B's p50 ≈ baseline
`+ 100` ms, because `ViewSink.commit` runs A before B. Reversing the attach order moves the penalty
to A. Report both orders.
**Vacuity:** the attach-order reversal is a controlled swap that changes which subscriber pays,
which no explanation other than serial iteration produces.

## STRM-108 — the 200 ms poll does not add latency to a delivered batch, only to loop exit
**Intent:** `handover.poll(200, MILLISECONDS)` returns as soon as an element is offered; the 200 ms
only bounds how long the loop waits before re-checking its exit conditions. Worth proving so the
constant is not blamed for latency it does not cause.
**Falsifier:** delivery latency shows a mode near 200 ms, or exit takes longer than ~200 ms after
cancellation.
**Setup:** `H-S`, one subscriber, 1 row/s.
**Steps:** 1. Measure 300 delivery latencies (STRM-103 method). 2. Separately, cancel the stream and
measure the time until `subscriberCount()` reaches 0, 50 times.
**Expected:** latency histogram has **no** mode at 200 ms. Exit time p99 ≤ 250 ms, mean ≈ 100 ms
(uniform within the poll window). Report both.
**Vacuity:** the two measurements come from the same constant but should behave oppositely; a
200 ms mode appearing in the first would mean `poll` is not returning early, which the second would
not detect.

### I. Scale (STRM-109–116)

## STRM-109 — one change
**Intent:** the degenerate end of the scale axis, stated so the axis is complete and so the
per-subscription fixed cost is measurable against nothing.
**Falsifier:** a single change is not delivered, or delivering it costs more than one batch.
**Setup:** `H-S`, one subscriber.
**Steps:** 1. Subscribe. 2. Push exactly 1 row. 3. Wait 1 s. 4. Close.
**Expected:** `batches() == 1`, `rows() == 1`. Exactly one Arrow batch of one row. Server allocator
returns to its pre-subscribe level after close (no leaked `VectorSchemaRoot`).
**Vacuity:** the allocator check is what makes a one-row case worth running; delivery of one row is
otherwise covered by STRM-001.

## STRM-110 — 10^3 changes
**Intent:** the smallest volume at which per-commit batching, rather than per-row delivery, is what
is being measured — and the control run for STRM-111 and STRM-112.
**Setup:** `H-S`, one subscriber.
**Steps:** 1. Subscribe. 2. Push 1 000 distinct-key rows as fast as the client can. 3. Wait 2 s.
**Expected:** `rows() == 1000`; sum of `amount` = `1000 × 1001 / 2 = 500 500`; view holds 1 000 keys;
`dropped()` server-side 0; no dropped handover batches in the audit sink.
**Falsifier:** any of those four numbers differs.
**Vacuity:** the sum and the count together catch loss and duplication independently.

## STRM-111 — 10^5 changes
**Intent:** two orders of magnitude up, at a rate the engine should absorb comfortably, so that any
loss is a defect rather than an overload.
**Setup:** `H-S`, one subscriber.
**Steps:** 1. Subscribe. 2. Push 100 000 distinct-key rows over 60 s (≈ 1 667 rows/s, ≈ 33 rows per
20 ms commit). 3. Wait 2 s.
**Expected:** `rows() == 100 000`; sum `100000 × 100001 / 2 = 5 000 050 000`; 100 000 keys in the
view; `conflated() == 0` (33 ≪ 10 000 per batch); zero handover drops. Record peak server RSS and
subscriber-side RSS.
**Falsifier:** loss, duplication, or a handover drop at a rate the engine is comfortably sustaining.
**Vacuity:** the per-commit batch size of 33 is far inside every bound in the system, so a drop here
indicates a defect rather than an overload.

## STRM-112 — 10^6 changes
**Intent:** the top of the scale axis, and the run in which view growth and delivery latency can be
watched against each other inside a single measurement.
**Setup:** `H-S`, one subscriber, retention `forever()` and `maxKeys` raised above 10^6.
**Steps:** 1. Subscribe. 2. Push 1 000 000 distinct-key rows over 300 s (≈ 3 333 rows/s, ≈ 67 rows
per commit). 3. Wait 5 s.
**Expected:** `rows() == 1 000 000`; sum `1000000 × 1000001 / 2 = 500 000 500 000`; 1 000 000 keys.
Report: peak RSS, GC pause distribution, delivery latency p50/p99 at the start and at the end of the
run (cross-reference STRM-104 — this run grows the view by three orders of magnitude while a
subscriber watches), and `evicted()` (must be 0 under `forever()`).
**Falsifier:** loss, duplication, `OutOfMemoryError`, or end-of-run p50 latency more than 2× the
start-of-run p50.
**Vacuity:** the start-vs-end latency comparison inside one run controls for everything except view
size, which is the variable STRM-104 isolates across runs.

## STRM-113 — 10^6 changes with `maxKeys` deliberately too low
**Intent:** the scale case that collides with STRM-070's leak. A million-row feed into a view capped
at 10^5 keys, with a subscriber attached.
**Falsifier:** the server dies without reporting `VIEW_TOO_LARGE`, or `pending` grows unbounded.
**Setup:** as STRM-112 with `maxKeys = 100_000`.
**Steps:** 1. Subscribe. 2. Push 1 000 000 distinct-key rows over 300 s. 3. Sample heap and
`pending.size()` every 10 s. 4. Repeat the whole run with no subscriber attached.
**Expected:** commits succeed until the view reaches 100 000 keys, then every commit throws
`PRV-4022`. With a subscriber attached, `ViewSink.pending` is never cleared after the first throw —
so roughly `1 000 000 − 100 000 = 900 000` `ViewChange` objects accumulate. Measure the heap and the
time to `OutOfMemoryError`, and compare against the same run with **no** subscriber, which should
hold flat. Report both.
**Vacuity:** the no-subscriber control run is identical in every other respect; a heap difference
between the two is attributable to `pending` alone.

## STRM-114 — a subscription open for 24 hours
**Intent:** the brief's "a subscription open for a long time". Leaks that do not show in a 5-minute
run: Arrow buffer retention, `ViewChange` accumulation, handover queue growth, the `SRVDBG` stdout
volume, thread count.
**Falsifier:** any monotonic growth over 24 h in server RSS, client RSS, thread count, or file
descriptors, with the feed rate held constant.
**Setup:** `H-S`, one subscriber, steady 100 rows/s (`8 640 000` rows over 24 h), `maxKeys` and
retention set so the view stays at a constant 10 000 keys (rows cycle over 10 000 keys, so the view
size is flat and any growth is not the view).
**Steps:** Sample every 5 minutes (288 samples): server RSS, client RSS, thread count, fd count,
Arrow allocator outstanding bytes, `batches()`, `rows()`, delivery p50, `SRVDBG` line count in the
log file.
**Expected:** `rows() == 8 640 000` (allow for the known truth that conflation is off at this rate).
RSS flat within 10 % after the first hour. `SRVDBG` line count ≈ `24 × 3600 × 50 = 4 320 000` lines —
at ~50 bytes each that is ≈ 216 MB of log, which is itself a finding (STRM-027). Thread count flat.
**Vacuity:** the rotating key set holds the view size constant, so the usual explanation for growth
is removed by construction.

## STRM-115 — 1 000 concurrent subscriptions on one query
**Intent:** ADR-025's scale claim ("ten analysts … the subscriber count grows and the *query* count
does not") and ADR-026's thousand-subscriber arithmetic.
**Falsifier:** `registry.size() > 1`, any subscriber missing changes another received, or the server
failing to sustain the feed.
**Setup:** `H-S`, one query, 1 000 SDK subscribers across 10 client processes, 500 rows/s feed with
distinct keys for 300 s (`150 000` rows).
**Steps:** 1. Attach all 1 000 subscribers and confirm `subscriberCount() == 1000`. 2. Feed for
300 s. 3. Close all subscribers. 4. Collect each one's row count and `amount` sum.
**Expected:** `registry.size() == 1`; one `ServedView`; `subscriberCount() == 1000`. Every subscriber
reports `rows() == 150 000` and the same sum `150000 × 150001 / 2 = 11 250 075 000`. Server-side:
`1000 × 50 = 50 000` Arrow encodings/s (STRM-028), `50 000` stdout lines/s (STRM-027), and 1 000
`VectorSchemaRoot`s live. Report whether the node sustains it, and if not, which of those three is
the binding constraint.
**Vacuity:** requiring *identical* sums from all 1 000 subscribers means a partial fan-out fails
even if the aggregate row count looks right.

## STRM-116 — 100 concurrent subscriptions across 100 distinct queries
**Intent:** the other axis: subscriber cost is per query here, so this is 100 sinks, 100 views, 100
listener lists — and the `PumpingFeed`/commit threading is what scales or does not.
**Falsifier:** any query's subscriber receives another query's changes, or ingest throughput per
query falls below `1/100` of the single-query baseline by more than 2×.
**Setup:** `H-S`, 100 registrations over distinct SQL (so distinct fingerprints), one subscriber
each, 100 rows/s per query (`10 000` rows/s aggregate) for 120 s. Each query's output carries a
constant column holding its own name, so a misrouted change is identifiable.
**Steps:** 1. Register all 100 and attach one subscriber each. 2. Feed for 120 s. 3. Per subscriber,
assert the row count and that every row's query-name column matches. 4. Re-run STRM-106 at
10 000 rows/s on a single query for the comparison.
**Expected:** each subscriber receives exactly its own query's `120 × 100 = 12 000` rows, zero
crossover. `registry.size() == 100`. Compare aggregate throughput against a single query fed at
10 000 rows/s (STRM-106) and report the ratio.
**Vacuity:** the zero-crossover assertion is checked by giving each query a distinct constant column
value, so a misrouted change is identifiable rather than merely a count discrepancy.

### J. Windowed queries (STRM-117–120)

## STRM-117 — what a subscriber sees when a tumbling window closes
**Intent:** the brief's question: one change per group, or a batch? `RegisteredQuery.advanceWatermark`
calls `execution.advanceWatermark(nanos)` and then `sink.commit(...)` in the same call, so
everything the window close emitted is staged and published as **one** commit — one batch, one
Arrow batch, one callback.
**Falsifier:** a window's groups arrive split across callbacks, or a batch spans two windows.
**Setup:** `H-E`, `SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id,
TUMBLE(event_time, INTERVAL '10' SECOND)` with `event-time` declared, keys on the group columns.
Data: 3 users × 4 rows each in window `[0s, 10s)`, amounts `100, 101, 102, 103` per user.
**Steps:** 1. Subscribe. 2. Feed all 12 rows with event times inside the window. 3.
`advanceWatermark` past `10s`.
**Expected:** **one** callback. Its size is 3 — one `+1` change per group — with `total` = `100 +
101 + 102 + 103 = 406` for each user. `3 × 406 = 1218` summed across the batch.
`delivered() == 3`.
**Vacuity:** 12 input rows collapsing to 3 changes is exactly the collapse round 1's row-count
assertion missed; asserting `406` per group and a batch size of 3 catches both a failure to
aggregate (12 changes) and a failure to emit (0).

## STRM-118 — two windows closing on one watermark advance
**Intent:** if the watermark jumps past two window ends, does the subscriber get one batch or two?
One `advanceWatermark` call means one `sink.commit`, so it should be one batch mixing both windows —
which contradicts "a batch is a commit, never a partial window" only in spirit, but matters to a
consumer that keys on the window.
**Falsifier:** the subscriber cannot tell which window a change belongs to.
**Setup:** as STRM-117, plus the window-start column in the output so provenance is visible.
**Steps:** 1. Subscribe. 2. Feed 3 users × 2 rows in `[0,10)` with amounts `100, 102`, and 3 users ×
2 rows in `[10,20)` with amounts `200, 203`. 3. One `advanceWatermark` past `20s`.
**Expected:** one callback of **6** changes: 3 with window start `0` and `total = 100 + 102 = 202`,
3 with window start `10` and `total = 200 + 203 = 403`. Batch sum
`3 × 202 + 3 × 403 = 606 + 1209 = 1815`. If the output schema does not carry the window bounds, that
is the finding: the consumer cannot attribute the changes and the mixed batch is unusable.
**Vacuity:** `202` and `403` are distinct and hand-computed, so a batch that merged the windows
(`100+102+200+203 = 605` per group) or emitted only one is immediately distinguishable.

## STRM-119 — late data reopening a closed window reaches the subscriber as a retract/insert pair
**Intent:** the mechanism `CONCEPTS.md` §4 and `TROUBLESHOOTING.md` both cite as the reason to apply
weights — "it drifts from the view the first time late data corrects a window". This is that case,
on the stream.
**Falsifier:** the correction arrives as a bare new value with no retraction, or not at all.
**Setup:** as STRM-117, with `pravaha.streams.txn.out-of-orderness` set to 5 s so a row 3 s late is
still accepted.
**Steps:** 1. Subscribe. 2. Feed `u1` with `100` and `102` in `[0,10)`. 3. `advanceWatermark` to
`11s`; observe. 4. Feed `u1` with `50` at event time `9s` (2 s late, inside the allowed lateness).
5. `advanceWatermark` to `12s`; observe.
**Expected:** batch 1: `[+1 (u1, win=0, total=202)]`, `100 + 102 = 202`. Batch 2:
`[-1 (u1, win=0, total=202), +1 (u1, win=0, total=252)]`, `202 + 50 = 252`. A consumer applying
weights holds `202 − 202 + 252 = 252`; one ignoring them holds `202 + 252 = 454`, an error of
`+202`. Over Flight, where the weight is absent (STRM-017), `454` is the only answer the client can
compute — which is the concrete cost of that defect on the one case the documentation names.
**Vacuity:** `252` vs `454` is arithmetic the harness performs independently, and the correct answer
is checked against `pravaha query --view` for the same window.

## STRM-120 — a subscriber attaching between a window's close and the next commit
**Intent:** STRM-060's fragment hazard at its worst: a window close stages N changes into `pending`
one row at a time through `StagedRow.commit`, each guarded by `if (!listeners.isEmpty())`. A
subscriber attaching partway through receives part of a window.
**Falsifier:** — broken if the first batch a subscriber receives is a strict subset of one window's
groups.
**Setup:** as STRM-117 with 1 000 groups, so the close stages 1 000 changes and the attach window is
wide enough to hit reliably. A second thread calls `subscribe` while the close is in progress.
**Steps:** 1. Feed 1 000 groups × 4 rows each in `[0,10)`. 2. Start `advanceWatermark` past `10s` on
thread A. 3. From thread B, `subscribe` after a short delay calibrated to land mid-close. 4. Assert
on the first batch received.
**Expected (promised):** the first batch is either all 1 000 groups or nothing.
**Expected (code as written):** a batch of `k` where `0 < k < 1000`. Report `k` and its variability
across 50 repetitions. A consumer summing the batch gets `k × 406` where the window's true total is
`1000 × 406 = 406 000` — a total that never existed, delivered as a completed commit, which is
precisely the failure `ViewSink`'s "a subscriber must see whole batches" comment exists to prevent.
**Vacuity:** 1 000 groups makes a partial batch a large and unambiguous number rather than a
one-row race; 50 repetitions turn a possible interleaving into a measured rate.

---

## Coverage note

120 cases, the budget as given, and the budget is right for this area — arguably light. Three things
worth saying to whoever executes them.

**Several cases are written as findings rather than as tests.** STRM-017 (the weight never reaches a
Flight subscriber), STRM-057 (`FAIL` overflow terminates the whole query), STRM-070 (the change log
leaks after `VIEW_TOO_LARGE`), STRM-077 (the bounded buffer bounds a commit, not a slow subscriber),
STRM-047 (a slow in-process subscriber blocks the engine), and STRM-068 (a dropped name keeps being
streamed) were identified by reading the code and are expected to fail. They are written with the
falsifier pointing at the documentation rather than at the code, because in each case the code is
self-consistent and the claim made about it is not. If any of them passes, the reading was wrong and
that is worth just as much.

**STRM-017 is the one to run first.** `PravahaFlightSqlProducer.writeBatch` writes only
`change.values()` into a `VectorSchemaRoot` built from a schema with no weight field, and the Java
SDK's `ChangeBatch`/`Row` expose no weight accessor. If that holds, then every remote subscriber —
CLI, Java SDK, Python SDK, console — receives a stream in which a retraction is byte-identical to an
insertion, while `CONCEPTS.md` §4, `TROUBLESHOOTING.md` and `USER_GUIDE.md` all instruct consumers
to apply the weights. That would make the continuous-delivery path, which the index calls "the
product", unable to express the one thing `docs/adr/013-zsets-and-dbsp.md` exists for. Nine cases
here depend on the answer (017–019, 046, 049, 082, 119, and the replay half of 036 and 071).

**What is deliberately not here.** Windowing semantics belong to `WIN`, Z-set arithmetic per
operator to `INCR`, the Python SDK and console to `SDKX`, error-code quality to `ERRC`, and
policy/auth breadth to `SECX`; section G covers only the subscription-path slice of each. Three
things I could not size from the source and left as investigations rather than assertions:
blue-green updates (STRM-075 — ADR-016 describes them and `ControlWire` has no verb for them),
whether any `SecurityPolicy` in the tree supports runtime revocation (STRM-096), and whether the
WebSocket and SSE carriers ADR-026 names exist anywhere (STRM-028 — they do not appear to).
