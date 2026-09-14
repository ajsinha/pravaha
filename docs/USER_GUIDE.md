# User guide

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

## 2. Register a continuous query

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

### Retention

A view keeps a day of event time by default. Override it when registering, or change the default for
a node:

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

**A batch is a commit.** Never a partial window. Between commits the view holds a half-applied batch,
so a consumer woken per row could act on a total still being assembled.

**Filters are applied at the tap**, so rows you did not ask for never cross the network — and every
other subscriber is reading the *same computation* with its own filter. Ten desks, ten filters, one
read of the source.

**A filter naming a column the view does not have is refused**, not ignored. A typo quietly dropped
would leave you receiving everything while believing you asked for a slice.

**Rows are flyweights.** In Java they point into the Arrow buffer that carried them, and that buffer
is reused for the next commit. Copy anything you keep past the callback.

### When you cannot keep up

Every subscription has a bounded buffer, and blocking is deliberately not on the menu — a subscriber
that blocked would apply backpressure to the *query*, slowing it for everyone keeping up.

| Policy | Right for |
|---|---|
| `CONFLATE` | A dashboard. Wants the latest value per key, does not care how many times it changed |
| `DROP_OLDEST` | Recency over completeness, where keys are not meaningful |
| `FAIL` | A ledger, settlement, an audit feed. Being told beats carrying on with a gap |

Whatever is lost is **counted** (`dropped()`, `conflated()`), because a subscriber silently missing
data is the failure the mechanism exists to make visible.

## 5. Manage what is running

```bash
pravaha queries                       # name, state, fingerprint, rows in
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

The [console](../console/) shows all of this in a browser, including which computations are shared.

## 6. Write SQL Pravaha will run

The complete, test-checked list is [`SQL_SUPPORT.md`](SQL_SUPPORT.md). The shape that works:

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

---

## Next

| | |
|---|---|
| [Case studies](../examples/case-studies/) | Five worked systems: trade processing, banking, finance, trading, biology |
| [Operations](OPERATIONS.md) | Running it: memory, disk, admission, what to watch |
| [Security](SECURITY.md) | Authentication, authorization, audit |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code |
| [Architecture](ARCHITECTURE.md) | How it works inside |
