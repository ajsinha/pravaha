# User guide

> **What a continuous query is, how streams, queries and views relate, and which SQL runs:**
> [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md).

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Everything you can do with Pravaha, task by task, in Java, Python and the shell.

Read [`CONCEPTS.md`](CONCEPTS.md) first if you have not — most surprises are one of those eight ideas
working correctly. [`QUICKSTART.md`](QUICKSTART.md) is the ten-minute version of this page.

---

## 1. Connect

Three clients, one protocol (Arrow Flight SQL, ADR-030). Anything one can do, the others can.

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090")) { … }
```
```python
with connect("grpc://localhost:9090") as client: ...
```
```bash
pravaha queries --url grpc://localhost:9090
```

> `grpc://` is **plaintext** and spelled out. Omitting the scheme means TLS, which is the right
> default for a client and the reason plaintext has to be asked for by name.

With a credential:

```java
ClientOptions.builder("grpc+tls://pravaha:9090").token(System.getenv("PRAVAHA_TOKEN")).build()
```
```python
ClientOptions.create("grpc+tls://pravaha:9090", token=os.environ["PRAVAHA_TOKEN"])
```
```bash
pravaha queries --url grpc+tls://pravaha:9090 --token "$PRAVAHA_TOKEN"
```

Both SDKs **refuse to send a token over a plaintext connection** unless told to
(`allowInsecureToken(true)` / `allow_insecure_token=True`), which exists for loopback tests and
sidecar-terminated TLS and is named so nobody enables it by accident.

The CLI spells the same thing `--insecure-token`. Until P-3 it passed that flag for you on every
invocation, so the refusal above was switched off for every CLI user without a word — if you have a
script that sends a token to a `grpc://` URL, it will now be refused until you add the flag, and the
right fix is usually `grpc+tls://` rather than the flag.

## 2. Register a continuous query

In SQL, through anything that sends SQL — `client.query(...)` in either SDK, `pravaha query --sql`,
the console's workbench, a Flight SQL JDBC or ADBC driver, or an embedded engine's `query(sql)`:

```sql
CREATE CONTINUOUS QUERY card_velocity
    KEYED BY (card_id)
    RETAIN FOR PT8H
AS SELECT window_end, card_id, COUNT(*) AS swipes
   FROM TABLE(TUMBLE(TABLE card_swipe, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
   GROUP BY window_start, window_end, card_id;
```

The answer is one row: the name, its state, the fingerprint and the sink. The key is named by
column — as the `SELECT` list names it — and the engine turns it into ordinals by planning the
query, so reordering the `SELECT` list cannot silently change what the key is. `WRITING TO <sink>`
adds a sink, `RETAIN FOREVER` or `RETAIN FOR INTERVAL '8' HOUR` a retention;
[Streams, queries and SQL §10.1](CONTINUOUS_QUERIES.md) has the whole grammar. The PostgreSQL gateway
is read-only and refuses these statements (`PRV-6211`).

Or through the registration call, with the key as output-column ordinals:

```java
RegisteredQueryInfo registered = client.register(
        "card_velocity",                       // the view name your SQL will read
        Files.readString(Path.of("velocity.sql")),
        List.of(1));                           // key column ordinals
```
```python
client.register("card_velocity", open("velocity.sql").read(), [1])
```
```bash
pravaha register --name card_velocity --sql-file velocity.sql --keys 1
```

**What you get back matters.** The `fingerprint` identifies the *computation*. Register the same
question twice — even worded differently — and you get one computation with two names and one copy of
the state. `pravaha queries` shows the fingerprint so you can see it holding.

**The view needs a key.** A view with no key is a log, and a point read against it has nothing to look
up. The ordinals are into the query's *output* columns.

### Writing the answer to a sink as well

A registration can also name a sink the server binds under `pravaha.sinks.<name>`
([Operations](OPERATIONS.md) has the binding). The view is maintained exactly as without one, and
every commit of it is written to the sink too, retractions included:

```java
client.register("spend_by_user", Files.readString(Path.of("spend.sql")), List.of(0, 1), "spend_sink");
```
```python
client.register("spend_by_user", open("spend.sql").read(), [0, 1], sink="spend_sink")
```
```bash
pravaha register --name spend_by_user --sql-file spend.sql --keys 0,1 --sink spend_sink
```

Refused at registration, before the sink opens: a query that revises its answer against a sink that
can only append (`PRV-2041`), and a query whose output columns, or whose `--keys`, differ from the
sink's configured schema or key (`PRV-8010`). Delivery is exactly once to a transactional sink,
effectively once to an idempotent upsert sink and at least once to a plain append — the node logs
which at registration — and a sink that refuses a batch is detached with `PRV-8009` while the view
carries on.
[Streams, queries and SQL §4](CONTINUOUS_QUERIES.md) has the whole contract.

### Retention

A view keeps everything by default (TY-21), unless the node sets a default of its own. Choose how much
event time it keeps when registering — an ISO-8601 duration, or `forever`:

```java
client.register("card_velocity", sql, List.of(1), null, "PT8H");   // no sink, eight hours
```
```python
client.register("card_velocity", sql, [1], retention="PT8H")
```
```bash
pravaha register --name card_velocity --sql-file velocity.sql --keys 1 --retain PT8H
```

A retention the server cannot read — `"eight hours"`, a negative duration — is refused, not
defaulted: keeping a day of a view somebody asked to keep for an hour changes what the view means.
The retention is journalled with the registration, so it survives a restart, and it is part of the
fingerprint: the same SQL kept for different lengths of time is two computations. Embedded, the
registry takes a `Retention` directly:

```java
registry.register(name, sql, keys, principal, Retention.ofAge(Duration.ofHours(8)));
registry.retaining(Retention.ofAge(Duration.ofHours(4)));   // this node's default
```

`Retention.forever()` exists and has to be asked for by name — a view is bounded only if its *key
space* is bounded, and nothing can tell in advance whether it is. See
[§7 of Concepts](CONCEPTS.md#7-bounds-what-changes-the-answer-and-what-protects-the-machine).

## 3. Ask a question

```java
try (QueryResult result = client.query("SELECT total FROM card_velocity WHERE card_id = ?", "c-1002")) {
    for (Row row : result) {
        System.out.println(row.getString("card_id") + " " + row.getLong("total"));
    }
}
```
```python
for row in client.query("SELECT total FROM card_velocity WHERE card_id = ?", ["c-1002"]):
    print(row["card_id"], row["total"])
```
```bash
pravaha query --sql "SELECT total FROM card_velocity WHERE card_id = ?" --params c-1002
```

**Bind values; never build the SQL string.** A bound value is never parsed as SQL — by the time it
reaches the server the statement is already planned and there is no parser left to reach — and the
server plans a parameterised statement once however many values you ask about.

Types are the server's: it reports what each placeholder needs when the statement is prepared, so
nothing guesses. Pass an `int` where a `BIGINT` is wanted and it converts; pass a string and you are
told which placeholder is wrong before the call leaves your process.

`?` belongs in a `WHERE` or `HAVING` clause and nowhere else — see
[ADR-032](adr/032-parameters-are-values-not-queries.md) for the full position table.

Whole result to a dataframe, in Python:

```python
frame = client.query("SELECT * FROM card_velocity").to_table()   # pandas or Polars
```

## 4. Subscribe

Querying asks; subscribing is told.

```java
try (Subscription subscription = client.subscribe("trade_feed",
        Map.of("product_type", "SWAP"),
        batch -> batch.forEach(row -> handle(row.getString("trade_json"))))) {
    subscription.run();     // parks this thread until closed
}
```
```python
for batch in client.subscribe("trade_feed", {"product_type": "SWAP"}):
    for row in batch:
        handle(row["trade_json"])
```
```bash
pravaha subscribe --view trade_feed --filter product_type=SWAP
```

Five things to know:

**Every row carries a weight.** `+1` is a row appearing, `-1` is one being withdrawn. A window
corrected by late data arrives as a retraction of the old row followed by an insert of the new one,
so a consumer keeping its own running total must **apply the weight** rather than count rows -- the
retraction is what cancels the value being corrected. A consumer that only wants current values can
overwrite by key and skip the negatives.

```java
batch.forEach(row -> total += row.weight() * row.getLong("amount"));
```
```python
for row in batch:
    total += row.weight * row["amount"]
```

The weight is not one of the view's columns: `row.columns()` lists what the query selected, and a
positional read gets the column it always got.

**A batch is a commit.** Never a partial window. Between commits the view is still taking changes,
so a consumer woken per row could act on a total still being assembled. And a commit ends at the
edge of a batch the engine has finished, never inside one: an update — the old row withdrawn, the new
one inserted — is published whole or not yet, so an answer never vanishes for one commit between the
two (VIEW-1).

**Filters are applied at the tap**, so rows you did not ask for never cross the network — and every
other subscriber is reading the *same computation* with its own filter. Ten desks, ten filters, one
read of the source.

**A filter naming a column the view does not have is refused**, not ignored. A typo quietly dropped
would leave you receiving everything while believing you asked for a slice.
A filter's value is read as its column's type, so `--filter amount=20` or `--filter active=true`
matches a number or a boolean; a value that cannot be one (`amount=twenty`) is refused the same way.
Until HLP-9 every value was compared as text, and a filter on anything but a text column matched
nothing.

**Rows are flyweights.** In Java they point into the Arrow buffer that carried them, and that buffer
is reused for the next commit. Copy anything you keep past the callback.

### Keeping a copy: subscribe from a snapshot

A plain subscription starts at the **next** commit and carries no state, so it is **gapful** for a
client that mirrors a view: subscribe and then read the view, or read and then subscribe, and the
commit in flight at that moment can reach you by neither path — it is not in the rows you read, and
your subscription was not in its audience — with nothing to say so (SUB-1). Subscribe *from a
snapshot* instead:

```java
try (Subscription subscription = client.subscribeFromSnapshot("trade_feed", batch -> {
        if (batch.isSnapshot()) {
            batch.forEach(row -> copy.put(row.getString("trade_id"), row.toArray()));
        } else {
            batch.forEach(row -> apply(copy, row));   // by weight, as above
        }
    })) {
    subscription.run();
}
```
```python
for batch in client.subscribe("trade_feed", snapshot=True):
    if batch.snapshot:
        copy = {row["trade_id"]: row.to_dict() for row in batch}
    else:
        for row in batch:
            apply(copy, row)
```
```bash
pravaha subscribe --view trade_feed --snapshot     # rows, "-- snapshot at frontier F", then commits
```
```java
engine.subscribeFromSnapshot("trade_feed", new RowChangeListener() {   // embedded
    public void onSnapshot(List<RowChange> rows, long frontier) { /* load */ }
    public void onCommit(List<RowChange> changes, long frontier) { /* apply */ }
});
```

The first batch is the view as some commit left it — every row, filtered, each with its multiplicity
as its weight, sent even when there are none, with that commit's frontier — and every batch after it
is a later commit, whole and in order. Applying the snapshot and then each commit by weight gives the
view: nothing between the two is missed and nothing is counted twice.

How the engine keeps that promise, briefly: subscribing takes the lock every batch and every commit
takes. If nothing has been applied since the last commit, the committed rows *are* the view, and the
snapshot is taken and the subscriber registered in that one step, so the next commit includes it. If a
commit is in flight, the subscriber waits for it: that commit takes the snapshot, which now contains
its rows, and registers the subscriber inside its own critical section — and the subscription draws
that commit boundary at once rather than waiting for the feed's timer. Nothing is logged to do it: a
snapshot is a copy of the committed rows, bounded by the view's ceiling.

The snapshot is never conflated or dropped. Over Flight, a snapshot subscriber that falls more than 64
commits behind has its stream ended with `PRV-6105` rather than skipped past a commit; subscribe
again to start from a fresh snapshot. A plain subscription is unchanged, on the wire and in every
SDK, and a server older than the client refuses a snapshot subscription with `PRV-6102`. The Spring
starter's `PravahaTester.awaitView` and the console's live page are built on this.

### When you cannot keep up

Every subscription has a bounded buffer, and blocking is deliberately not on the menu — a subscriber
that blocked would apply backpressure to the *query*, slowing it for everyone keeping up.

| Policy | Right for |
|---|---|
| `CONFLATE` | A dashboard. Wants the latest value per key, does not care how many times it changed |
| `DROP_OLDEST` | Recency over completeness, where keys are not meaningful |
| `FAIL` | A ledger, settlement, an audit feed. Being told beats carrying on with a gap |

`FAIL` **ends your subscription, not the query.** It is your answer to falling behind, not a
statement about the computation everyone else is reading — the subscription records the failure and
closes, and you find out from `failure()` and `isClosed()`. It used to throw from the commit loop and
fail the whole query, so the same three subscribers on the same input got a different answer
depending on the order they attached (STRM-2). Reconnect and re-read the view to catch up.

Whatever is lost is **counted** (`dropped()`, `conflated()`), because a subscriber silently missing
data is the failure the mechanism exists to make visible.

### How a subscription ends

Four endings, and telling them apart is the difference between a client that reconnects and one
that stops with a stale copy. Until STRM-12 they were one signal: an administrative drop, a node
shutting down and your own `close()` all arrived as a clean completion, which means "this stream is
finished", and only the last of the three is.

| Ending | On the wire | Your move |
|---|---|---|
| You called `close()` | the stream ends normally | nothing |
| The name was **dropped** | `PRV-8011`, Flight status `NOT_FOUND` | stop. The name is gone; what you have is complete up to the drop |
| The **node is shutting down** | `PRV-8012`, Flight status `UNAVAILABLE` | reconnect. The query is journalled and comes back `RUNNING`; read the view to catch up |
| The view was **replaced** at a cutover | `PRV-4019` | subscribe again to the same name, which the new version now answers |
| Your entitlement or credential went | `PRV-7002` / `PRV-7001` | re-authenticate, or ask for the grant back |

`PRV-8011` also ends a subscription on a name that was **sharing** a computation with another
registration. Two registrations of the same question are one computation with two names; dropping
one leaves the other running, and a subscriber on the dropped name used to go on being streamed
rows under a name a read of the view refused as nonexistent (STRM-14). A subscriber on the
surviving name is untouched, which is the point of sharing.

In process the same event closes the `Subscription`, puts the reason in `failure()`, and returns
`subscriberCount()` to zero — which after a drop it never did, so the number an operator reads as
"nobody is watching this" was permanently wrong.

## 5. Manage what is running

```sql
SHOW CONTINUOUS QUERIES;                  -- name, state, sql, fingerprint, rows_in, key_columns, sink, retention
PAUSE  CONTINUOUS QUERY card_velocity;
RESUME CONTINUOUS QUERY card_velocity;
DROP   CONTINUOUS QUERY card_velocity;
```

Each is authorized exactly as the calls below are — a principal who may not drop through one may not
drop through the other — and `SHOW` lists only what `pravaha queries` would show the same principal.

```bash
pravaha queries                       # name, state, fingerprint, rows in; a stopped source is marked
pravaha queries --verbose             # ...and each query's feed
pravaha pause  --name card_velocity   # keeps answering, stops advancing
pravaha resume --name card_velocity
pravaha drop   --name card_velocity
```
```java
client.queries(); client.pause(name); client.resume(name); client.drop(name);
```

**Pausing twice is not an error.** `pause` on a paused query and `resume` on a running one both
succeed and change nothing. They say what state you want the query in, not what transition you
believe it is about to make — so a script that pauses before maintenance does not have to know
whether somebody already did. A query that has *failed* or been *dropped* is refused with
`PRV-8003`, because there the end state you asked for is not reachable at all.

**A pause is not a stop.** The view keeps answering at the frontier it reached — far better for a
dashboard than answers that disappear. Rows arriving while paused are **dropped, not buffered**:
buffering would turn a pause into a memory commitment of unknown size, when the point was to stop it
doing work.

**A drop removes a name.** The computation goes when its *last* name goes. If somebody else registered
the same question, dropping yours leaves theirs running — neither of you knows the other exists.

`queries()` in both SDKs also reports each query's key ordinals, its sink binding and its retention.
**A source can stop under a running query.** If a source fails mid-read — a deleted file, a revoked
credential, a line it cannot decode — its feed stops and is not retried; the query stays `RUNNING` and
its view answers at the frontier it reached. `pravaha queries` shows it as `RUNNING (source stopped)`
with the code, `stream#partition` and time on a line beneath; `queries()` in both SDKs carries the same
as `feed`/`feedStop` (Java) and `feed`/`feed_stop` (Python). Fix the cause, then drop and re-register.

The engine's HTTP API describes a query in full — keys by name, retention, sink and whether it is still
attached (`PRV-8009` if it was detached), rows in, the other names sharing its computation that you
may see, the streams it reads, its feed (each source partition's state, and a stopped one's code and
time) — and its running plan as a graph:

```bash
curl -H "Authorization: Bearer $TOKEN" http://engine:8080/api/v1/queries/card_velocity
curl -H "Authorization: Bearer $TOKEN" http://engine:8080/api/v1/queries/card_velocity/plan
curl -H "Authorization: Bearer $TOKEN" http://engine:8080/api/v1/views/card_velocity   # schema, key, retention
curl -H "Authorization: Bearer $TOKEN" http://engine:8080/api/v1/sinks                 # what each sink accepts
```
```python
client = connect(options=ClientOptions.create(
    "grpc+tls://engine:9090", token=token, http_url="https://engine:8080"))
client.describe_query("card_velocity")
client.query_plan("card_velocity"); client.describe_view("card_velocity"); client.sinks()
```

They answer by the listing's rules: a name your policy denies is refused whether or not it exists, and
a query reading a stream you may not read answers exactly as a name that was never registered.

### The records a query could not decode

With `pravaha.dlq.directory` set, a record a source cannot decode is kept rather than stopping the
source. Those records are readable, and one can be put back:

```bash
pravaha dlq list   --name card_velocity              # newest first, with the queue's totals
pravaha dlq show   --name card_velocity --id <id>    # one whole, with its original bytes
pravaha dlq replay --name card_velocity --id <id>    # feed it back through the query
```
```java
client.deadLetters("card_velocity");            // a page, newest first
client.deadLetter("card_velocity", id);         // one whole
client.replayDeadLetter("card_velocity", id);
```
```python
client.dead_letters("card_velocity")
client.dead_letter("card_velocity", letter_id)
client.replay_dead_letter("card_velocity", letter_id)
```

**A replay is a new row at the query's current frontier, not a rewind.** The recorded bytes go back
through the same decoder that refused them and the row is applied to the state the query has now;
nothing is re-read and no earlier answer is recomputed. A record that fails to decode again returns
to the queue as a new entry rather than being retried, and a replay that could not be correct is
refused with `PRV-4092` saying why. Where the source will send the record again, correcting it at
the source is better: it then arrives in order.

The bytes are a row of the source, so they are authorized like one: a caller reading the view through
a row filter is given the count, the code and the offset and not the record, and replaying needs the
same permission as `DROP`. See [Dead letters](../console/content/topics/dead-letters.md) and
[OPERATIONS](OPERATIONS.md#running-with-a-dead-letter-queue).

The [console](../console/) shows all of this in a browser, including which computations are shared.

### Changing a running query: replace, cut over, roll back

Dropping a query and registering it again takes the answer away from everybody reading it and gives
them back an aggregate with no history. Replacing it does not
([ADR-046](adr/046-a-replacement-meets-the-running-version-at-a-position.md),
[CONTINUOUS_QUERIES §8.1](CONTINUOUS_QUERIES.md)):

```sql
CREATE OR REPLACE CONTINUOUS QUERY card_velocity KEYED BY (card_id)
    WITH (backfill = 'history', backfill.rate.limit = 5000)
AS SELECT card_id, COUNT(*) AS attempts, SUM(amount) AS total
   FROM authorisation GROUP BY card_id, HOP(ts, INTERVAL '1' MINUTE, INTERVAL '10' MINUTE);
```

```bash
pravaha replace --name card_velocity --sql-file v2.sql --keys 0 --wait
pravaha replacements                       # how far the backfill has got, and how fast
pravaha cutover  --name card_velocity      # move the name, when the two have consumed the same input
pravaha rollback --name card_velocity      # put the old one back, while it is still retained
pravaha finish   --name card_velocity      # release it, and end the chance to roll back
```
```java
client.replace("card_velocity", sql, List.of(0), "backfill=history;backfill.rate.limit=5000");
client.replacement("card_velocity");   // state, history rows, rate, lag, rollback window
client.cutOver("card_velocity");
client.rollBack("card_velocity");
```
```python
client.replace("card_velocity", sql, [0], backfill="history", rate_limit=5000)
client.replacement("card_velocity")
client.cut_over("card_velocity")
client.roll_back("card_velocity")
```

The new version runs **beside** the old one: it replays the source from the beginning, splices onto
the live stream at the exact position the running version has reached, and catches up. The name goes
on answering the old version until you cut over — and the cutover happens only when the two have
consumed exactly the same input, so a reader sees the old answer up to that point and the new answer
after it, with no gap and nothing counted twice. **Every subscription to the name ends with
`PRV-4019` at the cutover**: subscribe again, and a snapshot subscription starts from a fresh
snapshot of the new version. A sink follows the name and is sent only the difference.

The replaced version keeps running for an hour by default, so a rollback is one swap rather than
another backfill. A replacement requires the administer permission on the name, as a drop does.

## 6. Write SQL Pravaha will run

The complete, test-checked list is [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md). The shape that works:

```sql
SELECT STREAM
  TUMBLE_END(a.event_time, INTERVAL '1' MINUTE) AS window_end,
  a.key_column,
  d.enrichment,
  COUNT(*)        AS n,
  SUM(a.amount)   AS total
FROM source_stream AS a
LEFT JOIN dimension FOR SYSTEM_TIME AS OF a.event_time AS d
       ON a.key_column = d.key_column
WHERE a.status = 'SOMETHING'
GROUP BY TUMBLE(a.event_time, INTERVAL '1' MINUTE), a.key_column, d.enrichment
```

Filter, enrich from a dimension table, window, aggregate. That covers nearly everything people write.

**The four refusals worth knowing before you start:**

- No `DECIMAL` arithmetic — refused rather than approximated in `double`, which would pass every
  test anybody writes and be wrong in a ledger
- No `ORDER BY` / `LIMIT` — sort in your application over a result a `WHERE` already narrowed
- No outer or self joins between *streams* — a lookup join (`LEFT JOIN … FOR SYSTEM_TIME AS OF`) is
  supported and is what the shape above uses
- No unwindowed keyed `GROUP BY` over a stream — see [`PRV-2050`](TROUBLESHOOTING.md)

## 7. Parameters in a continuous query

Different from a parameter in a read, and the difference decides how many computations you run.

```bash
# Ask before you register
pravaha explain --sql "..."                 # the plan
```
```java
List<ParameterPlacement> placements = registry.classify(sql);
```

| Placement | Meaning |
|---|---|
| `TAP` | The view carries the column. **Free** — register once, let each subscriber filter |
| `REGISTRATION` | The query aggregates it away. Each distinct value needs its own computation and state |

The two cases are one word apart in the SQL:

```sql
SELECT user_id, SUM(amount) … GROUP BY user_id, TUMBLE(…)  WHERE user_id = ?   -- tap, free
SELECT tier,    SUM(amount) … GROUP BY tier,    TUMBLE(…)  WHERE user_id = ?   -- forks per user
```

`RegisteredQuery.avoidableForks()` names the parameters that did not need to fork anything — "you are
running N copies of something that could be one".

## 8. Run the console

```bash
cd console && make install && make run
```

Then <http://127.0.0.1:8090>. (Not 8080 — that is the engine's own actuator port, and
following this line to 8080 lands you on the wrong process.) It is a *functional admin* console on purpose — see
[its README](../console/README.md) for what that means and what it does not do.

## 9. Embed the engine in your application

`pravaha-embedded` runs the whole loop in your process: no server, no network, no Spring.
Streams and bindings are declared **before** `start()` — the registry plans every query against the
streams it was built with.

```xml
<dependency>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha-embedded</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;

record Big(String userId, long amount) {}

try (PravahaEngine engine = PravahaEngine.createDefault()) {
    engine.declareStream("txn", "user_id:STRING,amount:INT64");
    engine.start();

    engine.register("big_txn", "SELECT user_id, amount FROM txn WHERE amount > 100", "user_id");
    engine.register("totals", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn", "n");
    // The same registration in SQL: query(sql) runs CREATE / DROP / PAUSE / RESUME CONTINUOUS QUERY
    // and SHOW CONTINUOUS QUERIES too, as the embedded engine's anonymous caller.
    engine.query("CREATE CONTINUOUS QUERY small_txn KEYED BY (user_id) AS "
            + "SELECT user_id, amount FROM txn WHERE amount <= 100");

    // Committed changes, retractions included, on the committing thread: keep it short.
    engine.subscribe("totals", changes -> changes.forEach(c ->
            System.out.println((c.isRetraction() ? "- " : "+ ") + c.values())));

    // Returns once every query reading txn has applied and committed the rows.
    engine.push("txn", new Object[] {"u1", 300L}, new Object[] {"u2", 50L});
    engine.push("txn", java.util.Map.of("user_id", "u3", "amount", 700L));

    List<Big> big = engine.query(Big.class, "SELECT * FROM big_txn");         // records, by column name
    Object u3 = engine.query("SELECT amount FROM big_txn WHERE user_id = ?", "u3").rows().get(0)[0];
}
```

What the calls do:

| Call | |
|---|---|
| `declareStream(name, "col:TYPE,...", eventTimeColumn)` / `declareStream(StreamSchema)` | A stream, with the event-time column that lets windows close |
| `bindSource` / `bindLookup` / `bindSink(name, plugin, options)` | A plugin by the name it reports, with its options — `filesystem` is on the classpath already |
| `register(name, sql, keyColumns...)` / `register(ContinuousQuery)` | A continuous query; `ContinuousQuery.named(..).retaining(..).writingTo(sink)` for retention or a sink |
| `push(stream, rows...)` | Rows in column order (or a `Map` by name). The whole batch is checked first: one bad row delivers nothing (`PRV-8102`) |
| `advanceEventTime(stream, instant)` | Closes windows over pushed rows; a bound source's watermark advances on its own |
| `query(sql, params...)` / `query(Class, sql, params...)` | SQL over the views, as rows or as records |
| `subscribe(query, consumer)` | Every commit of a view, retractions as weight `-1` |
| `pause` / `resume` / `drop` / `queries()` / `registry()` | Lifecycle, and the registry underneath for everything else |
| `registry().replacements()` | Blue/green replacement in process: `replace`, `of`, `cutOver`, `rollBack`, `throttle`, `pause`, `resume`, `finish`. `query("CREATE OR REPLACE CONTINUOUS QUERY ...")` is the same thing in SQL. It needs a bound source to replay from: a query fed by `push` has no history and is refused with `PRV-4018` |

Everything can come from configuration instead, with the server's key names:

```java
Configuration configuration = Configuration.builder()
        .set("pravaha.streams.txn.schema", "user_id:STRING,amount:INT64")
        .set("pravaha.sources.txn.plugin", "filesystem")
        .set("pravaha.sources.txn.options.path", "/var/lib/app/txn.csv")
        .set("pravaha.sources.txn.options.schema", "user_id:STRING,amount:INT64")
        .set("pravaha.queries.big_txn.sql", "SELECT user_id, amount FROM txn WHERE amount > 100")
        .set("pravaha.queries.big_txn.keys", "user_id")
        // Persistence is opt-in: registrations come back from the journal, state from checkpoints.
        .set("pravaha.registry.journal", "/var/lib/app/pravaha/registry.journal")
        .set("pravaha.checkpoint.directory", "/var/lib/app/pravaha/checkpoints")
        .build();
try (PravahaEngine engine = PravahaEngine.create(configuration)) {
    engine.start();   // big_txn is registered, fed from the file, and recovered after a restart
}
```

An embedded engine has no authentication or policy: every call runs as the anonymous principal, on
the assumption that your application has already decided who may call it. Run the server when that
is not true. One known limit: an unwindowed `GROUP BY` per key is refused as unbounded state
(`PRV-2050`, as everywhere). A global aggregate's running total is carried across a restart by its
checkpoint, as every other operator's state is (CKPT-2).

## 10. Embed it in a Spring Boot application

`pravaha-spring-boot-starter` makes the same engine a bean. It is a layer **above** the Spring-free
engine: the build refuses Spring inside `pravaha-embedded` and everything it is made of.

```xml
<dependency>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
pravaha:
  node:
    id: orders-service
  streams:
    orders:
      schema: "order_id:STRING,amount:INT64"
  queries:
    order_stats:
      sql: "SELECT COUNT(*) AS orders, SUM(amount) AS revenue FROM orders"
      keys: [orders]
  # optional: registry.journal, checkpoint.directory, sources.*, sinks.*, lookups.*, watermark.*
```

```java
record OrderStats(long orders, long revenue) {}

@Service
class Dashboard {

    private final PravahaTemplate pravaha;

    Dashboard(PravahaTemplate pravaha) {
        this.pravaha = pravaha;
    }

    /** Every committed change to order_stats, off the engine's thread. */
    @PravahaListener(query = "order_stats")
    void onStats(OrderStats stats, boolean retraction) {
        if (!retraction) {
            System.out.println("revenue is now " + stats.revenue());
        }
    }

    void recordOrder(String id, long amount) {
        pravaha.push("orders", new Object[] {id, amount});
    }

    List<OrderStats> current() {
        return pravaha.query(OrderStats.class, "SELECT * FROM order_stats");
    }
}
```

- **The engine** is started when its bean is created and closed with the context, so a bean may
  register a query from its constructor through `PravahaTemplate`. Supply your own `PravahaEngine`
  bean and the starter steps aside; a `PravahaEngineCustomizer` bean adjusts the auto-configured one
  before it starts. `pravaha.enabled=false` turns it off.
- **A listener** takes `(RowChange)`, `(List<RowChange>)` for a whole commit, or `(SomeRecord row,
  boolean retraction)` / `(Map<String, Object> row, boolean retraction)`. The `boolean` is required:
  an update arrives as the old row withdrawn and the new one added, in that order. Records are
  matched by column name, ignoring case and underscores. `concurrency = N` runs N threads, routed by
  the view's key, so one key's changes stay in order.
- **Failure is loud.** A listener naming a query that does not exist when the context starts, or with
  a signature it cannot be called with, fails the startup. One more than
  `pravaha.listener.max-pending` commits behind (default 10,000) is detached and logged rather than
  handed a stream with a gap.

### When a listener throws

A `PravahaListenerErrorHandler` decides. The default logs at error with the query, the listener and
the change (`@PravahaListener dashboard.onStats on query 'order_stats' threw on [+{orders=2,
revenue=100}]`), and goes on to the next change; the one that failed is not redelivered.
`pravaha.listener.on-error=stop` logs the same line and stops the listener instead: it is detached
from the query and receives nothing more until the context restarts.

```java
@Bean
PravahaListenerErrorHandler pravahaErrors(Alerts alerts) {
    return failure -> {
        alerts.raise(failure.queryName(), failure.exception());   // failure.changes() is what it was handed
        return PravahaListenerErrorHandler.Decision.CONTINUE;      // or STOP
    };
}
```

A handler bean replaces the default for every listener that names none (with several, mark one
`@Primary`); `@PravahaListener(query = "...", errorHandler = "beanName")` gives one listener its own.
Nothing is silent whatever a handler returns: each listener counts its failures and keeps the last
one, and the actuator endpoint below shows both. A handler that throws or returns `null` stops its
listener, and says so at error.

### Testing: `@PravahaTest`

A test slice: the engine and the starter's auto-configuration, and nothing else. Of the application's
scanned components it keeps only those with a `@PravahaListener` method (`includeFilters` adds more).
It clears `pravaha.checkpoint.directory`, `pravaha.registry.journal` and `pravaha.dlq.directory`, so
a test never writes where `application.yaml` points, unless the test sets them itself;
`@PravahaTest(checkpoints = true)` checkpoints into a temporary directory deleted with the context.

```java
@PravahaTest(properties = {
        "pravaha.streams.orders.schema=order_id:STRING,amount:INT64",
        "pravaha.queries.order_stats.sql=SELECT COUNT(*) AS orders, SUM(amount) AS revenue FROM orders",
        "pravaha.queries.order_stats.keys=orders"})
class DashboardTest {

    @Autowired PravahaTester pravaha;
    @Autowired Dashboard dashboard;

    @Test
    void revenueAddsUp() {
        pravaha.push("orders", new Object[] {"o-1", 40L}, new Object[] {"o-2", 60L})
                .awaitListeners("order_stats");                       // every listener has been handed it
        assertThat(dashboard.revenue()).isEqualTo(100);
        assertThat(pravaha.awaitView("order_stats", OrderStats.class, rows -> !rows.isEmpty()))
                .containsExactly(new OrderStats(2, 100));
    }
}
```

Nothing in it sleeps. A push is applied and committed before it returns; `awaitListeners` queues a
marker behind each listener's pending work and returns when every marker has run; `awaitView` checks
the view, then checks again on each commit to it, which is how a test waits for rows a bound source
delivers on its own schedule. It is woken by a snapshot subscription, so a commit in flight as it
starts wakes it too; it used to force a commit of its own to cover that gap (SUB-1). A wait that runs out (30 s, or `withTimeout`) fails naming what it
waited for and the last answer it saw. The slice needs Boot's test stack
(`spring-boot-starter-test`), which the starter does not bring.

### Actuator

With `spring-boot-starter-actuator` on the classpath the starter adds a `pravaha` health contributor
— DOWN when the engine is not running; otherwise UP, with failed queries, detached sinks and stopped
listeners counted in the detail — switched off by `management.health.pravaha.enabled=false`. It also
adds a read-only `pravaha` endpoint, created only once exposed, which over HTTP means

```yaml
management.endpoints.web.exposure.include: health,pravaha
```

`GET /actuator/pravaha` lists every query — state, SQL, lane (`own` or `shared-N`), rows in,
subscribers, watermark and its lag, last checkpoint, sink with its delivery guarantee and failure,
and each listener's delivered, failures and pending commits — and `/actuator/pravaha/{name}` is one.
There is no write or delete operation: pausing or dropping a query stays the application's call.
Secure it as you secure the rest of the management port.

### Boot versions

Built and tested against Boot 3.5 (3.5.16, the server's version). The starter's pom has profiles
`boot-3.2`, `boot-3.3`, `boot-3.4` and `boot-3.5`, each moving the Boot BOM, and `BootVersionTest`
fails a leg that ran some other Boot than the one it names:

```bash
./mvnw -pl pravaha-spring-boot-starter -am test -Pboot-3.2
```

Only the 3.5 leg has been run; the others need their Boot version downloaded, and no CI job runs them
yet.

---

## 11. The time-travel debugger

A query is producing a wrong row and the logs do not say why. Forking it lets you watch the row
that caused it go through, one step at a time (ADR-048, design §16.4).

A **debug session** is a second copy of the query, restored from one of its retained checkpoints,
reading the same sources from the offsets that checkpoint recorded. **Every sink is disabled, the
fork's view is in no catalogue, and its lanes are its own** — the live query, its view and its
subscribers carry on and see nothing. It needs `pravaha.checkpoint.directory` to be set, because a
fork starts from a checkpoint.

```bash
# Which positions this node can still start from.
pravaha debug checkpoints --name user_volume
4471
4470
4469

# Fork. The last line is the session id, so a shell can capture it.
SESSION=$(pravaha debug fork --name user_volume --checkpoint 4471 | tail -1)

# Step: one row, then ten, then until the thing that is wrong happens.
pravaha debug step --session "$SESSION" --step row
pravaha debug step --session "$SESSION" --step rows:10
pravaha debug step --session "$SESSION" --step until:total:<:0
```

Each step reports four things at once, which is what makes a wrong answer explainable rather than
mysterious: **the rows that entered** with their weights and event times, **each operator's rows in
and out**, **the view's changes** with their weights, and **where event time stands**.

```
step 11 (UNTIL)  the view satisfies total < 0
  in   +1 txn#0@8842 [user_42, -160]
  op   n0 Aggregate(group=[user_id], [SUM(total)])  in=1 out=2
  op   n1 Filter(amount <> 0)  in=1 out=1
  op   n2 Scan(txn)  in=1 out=1
  view -1 [user_42, 120]
  view +1 [user_42, -40]
  rows consumed 11, view 3 rows, watermark 1740000000000000000
```

An operator that took a row and produced nothing is the answer to "where did my row go": a filter
that rejected it and an aggregate that produced a zero delta look identical from the view alone.

The `n0`, `n1`, ... are the plan's own node ids — the same ones
`GET /api/v1/queries/{name}/plan` uses — so a step's numbers and the plan graph's are the same
numbers, counted once.

### Reading an operator's state

```bash
pravaha debug state --session "$SESSION"
OPERATOR        KIND       WHAT               ENTRIES
aggregate#0     aggregate  groups             1284

pravaha debug inspect --session "$SESSION" --operator aggregate#0 --key user_42
user_42    rows=3  n=3  total=-40
```

Bounded and paged: `--offset` and `--limit`, and a page above the ceiling is refused
(`PRV-8015`) rather than built. Reading changes nothing — a join's index is walked without
evicting from it, and an aggregate is read without emitting it.

A **windowed** aggregate shows the windows it has fired and is still retaining, not the ones still
filling. That is a real limit rather than an oversight: the only way to read an open window's
answer is to fire it, and a debugger that published a window early would have changed the query it
was asked about. Windows stay readable for as long as the stream's allowed lateness keeps them
correctable.

### Stepping

| Step | What it does |
|---|---|
| `row` | One input row, whichever partition offers it next |
| `rows:N` | Up to N |
| `commit` | Rows until the view actually changes, or until `pravaha.debug.step.max-rows` |
| `watermark:<nanos>` | No rows: event time advances, and any window it closes fires. It does not go backwards |
| `until:<column>:<op>:<value>` | Rows until any row of the view satisfies the comparison |

A predicate is **one column of the view against one value**, with `= != < <= > >=`. That is
deliberate and ADR-048 says why: a second expression language that is nearly SQL's is a source of
wrong answers in the one tool you are using because you already have one. An unknown column is
refused by name with the columns there are.

### The same session twice gives the same answers

Two sessions forked from the same checkpoint and given the same steps produce identical reports —
the same rows, in the same order, the same operator counts, the same view changes. Nothing drives a
fork but you: no feed thread, no watermark clock, no periodic checkpointer.

One cost is worth knowing. The replay reads a query's partitions **round-robin in a fixed order**,
which the live query does not — its pumps are on separate threads. That is what buys
reproducibility, and it means a bug that depends on one particular interleaving of two partitions
may not appear in a fork.

### Exporting the incident as a test

```bash
pravaha debug fixture --session "$SESSION" --name "user 42 goes negative" \
    --out pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures/
wrote .../User42GoesNegativeFixtureTest.java
```

The generated file is a self-contained JUnit test: the schema of every stream the query reads, its
SQL and key columns, the rows the session consumed in order with their weights and event times, the
watermarks it pushed, and the view it produced. It registers the query the ordinary way and replays
the script — it needs nothing from the node it came from, so it runs offline and keeps running when
the state format changes.

**What it asserts is the answer over those rows, from empty.** The session was forked from a
checkpoint, so the fork's own view also held everything before it; the fixture does not, and its
expectation is produced by running the script through an empty copy of the query at export time
rather than copied from the fork. The generated file says so at the top.

### Sessions are resources

A session is a whole second copy of a query. So a node holds at most
`pravaha.debug.sessions.max` (4), releases one nobody has touched for
`pravaha.debug.session.ttl` (15 minutes), and bounds what one may consume with
`pravaha.debug.session.max-rows`. End one when you are finished:

```bash
pravaha debug end --session "$SESSION"
```

Every debug call needs the **administer** permission on the query — the same one dropping it
takes — because a fork exposes the query's SQL, its input rows and its operator state, which is
more than reading its view exposes. The gauge `pravaha_debug_sessions_open` says how many a node is
holding.

Over HTTP the same verbs are `POST /api/v1/queries/{name}/debug`,
`POST /api/v1/debug/sessions/{id}/step`, `GET /api/v1/debug/sessions/{id}/state/{operator}`,
`POST /api/v1/debug/sessions/{id}/fixture` and `DELETE /api/v1/debug/sessions/{id}`; both SDKs have
`debug_fork` / `debugFork` and the rest. The console's debugger screen is not built.

---

## Next

| | |
|---|---|
| [Case studies](../examples/case-studies/) | Five worked systems: trade processing, banking, finance, trading, biology |
| [Operations](OPERATIONS.md) | Running it: memory, disk, admission, what to watch |
| [Security](SECURITY.md) | Authentication, authorization, audit |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code |
| [Architecture](ARCHITECTURE.md) | How it works inside |
