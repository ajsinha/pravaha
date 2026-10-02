---
title: Dead letters
slug: dead-letters
category: operating
order: 100
icon: envelope-exclamation
summary: "pravaha.dlq.directory: where a record a source cannot decode is kept, one JSON line each, so one bad field does not stop a feed. What goes there, how much is kept, and reading and replaying them from the console, the CLI or the API."
audience: Operators
keywords: [dlq, dead letter queue, undecodable, malformed record, decode failure, division by zero, overflow, PRV-3027, replay, retention, evicted, max-bytes, PRV-4090, PRV-4091, PRV-4092, PRV-5040, PRV-5105, kafka, tombstone, jq, base64, pravaha-engine run --dlq, pravaha dlq list]
guide: operations#files-that-hold-data
related: [source-filesystem, source-kafka, observability, checkpoints-recovery, configuration]
---

A dead-letter queue keeps two rules that pull against each other: **never drop a record silently**,
and **never let one record stop the pipeline**. Without somewhere durable to put a bad record, "keep
going" is just "drop it" — so a node with no queue configured keeps the first rule and fails loudly.
Configure a directory and it keeps both: an undecodable record is written there with its bytes and
its reason, and the source reads on.

## The setting

```yaml
pravaha:
  dlq:
    directory: /opt/pravaha/data/dlq
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.dlq.directory` | empty | Where undecodable records go, **one file per query**, named `<query>.dlq`. Empty: no queue, and a decode failure stops that query's source |
| `pravaha.dlq.max-bytes` | `268435456` (256 MiB) | The largest one query's file may grow. Past it the **oldest entries are evicted** and the loss is recorded. `0` for no byte bound |
| `pravaha.dlq.max-entries` | `0` (off) | The most entries one query's file may hold |
| `pravaha.dlq.max-age` | `0` (off) | How long an entry is kept, as a duration (`30d`, `PT72H`) |

The node logs it at startup, with the bound:

```text
dead-lettering undecodable records to /opt/pravaha/data/dlq (pravaha.dlq.directory), keeping 268435456 bytes
```

The byte bound is **on by default**, and deliberately: a bound that defaults to off is not a bound,
and the failure it exists to stop is one renamed column in a busy feed filling the disk the node's
checkpoints are on. See [how much is kept](#how-much-is-kept).

## Without it, and with it

A CSV bound to `txn` has 20,000 lines, and line 812 carries `amount` as `12.50` — a decimal in
an `INT64` column.

**No queue configured.** The decode failure ends the poll and stops the source for that query, and
with it every other row in the file. The query goes on reporting `RUNNING`; its view simply stops
growing. Where to see why:

- the query's page here, under **Source**: the code (`PRV-5040` for a line a file cannot decode,
  the source's own code otherwise, `PRV-5092` when the feed stopped for a reason that was not the
  source's), linked to its help, the stream and partition, and when;
- **Operations**, where a stopped source is a critical finding and the verdict names the query;
- `pravaha queries`, which shows the query as `RUNNING (source stopped)` and a line with the code;
- `GET /api/v1/queries/{name}`, whose `feed` has the same, and `pravaha_query_feed_stopped`,
  which is 1 while it lasts;
- the node's log, which has an `ERROR` line naming the query, the stream and partition, and the code.

A stopped source is not retried. This used to be the only
behaviour a server had: one malformed field could take a whole file to zero rows with nothing in the
query's state to show it (TIME-4).

**With `pravaha.dlq.directory` set.** Line 812 is written to `/opt/pravaha/data/dlq/<query>.dlq`, the
other 19,999 rows are ingested, and the query carries on.

## A row that decodes and then fails

A row can decode and still have no answer: `a / b` with `b` zero, an `INT` result past `INT`'s range,
`CAST(d AS BIGINT)` of `NaN`. **With a queue configured**, such a row goes to the same file, coded
[PRV-3027](/help/codes/PRV-3027), with the reason (`division by zero …`, `INT overflow: …`) and the
row's columns as a JSON object in place of the source's bytes — `{"id":"2","a":"10","b":"0"}` — and the
query keeps running. That holds for a pushed row as for a source's, so every query gets its queue.
**Without one**, it stops the query, as before (`FAILED`, `PRV-3010` with the cause).

Only a failure **before the row reaches state** goes there: in a `WHERE`, a projection or a computed
column under any aggregate, window, join or top-N. There, leaving the row out is exact — the view is
what it would have been had the row never arrived. A failure *above* state — `HAVING SUM(a) / SUM(b)
> 1`, a projection of an aggregate, a `SUM` past 64 bits (`PRV-3025`) — stops the query either way,
because the state has already taken the row and dropping it could not undo that.

Such an entry is **not replayed**: it decoded, and the same values would fail the same way. A replay
is refused [PRV-4092](/help/codes/PRV-4092); correct the record at the source or change the query.
Until DLQPROJ-1 the guide said these rows went to the queue while they stopped the query with the
queue configured and empty.

## What a dead letter looks like

One JSON object per line, flushed per entry — a crash is when the interesting records arrive, and a
queue that loses its last few in a buffer loses exactly those:

```text
{"timestamp":83218734112093,"query":"big_card_txn","correlationId":"6f1c2b0e-5d7a-4e8f-9a41-0c3b2d9e7f15","offset":"line 812","reason":"column 'amount' (INT64): cannot read '12.50' as a number","raw":"ODEyLHUtMTA0MixhY21lLDEyLjUwLEVVUiwsMjAyNi0wOS0xOVQwOToxMjowMFo="}
```

| Field | Holds |
|---|---|
| `timestamp` | When it was rejected: a monotonic nanosecond reading, for ordering entries — not a wall-clock time |
| `wall` | The same moment on the wall clock, in milliseconds. `timestamp` orders entries and cannot be shown to a person: it is the JVM's uptime, and rendering it as a date gives 1970 |
| `query` | The registered query whose feed rejected it |
| `correlationId` | A fresh id per rejection. It is what a ticket quotes, what a log search finds — and what the API, the CLI and the console **address this entry by** |
| `stream` | Which of the query's streams it arrived on. A query reading two gave no way to tell them apart |
| `offset` | Where in the source: for a file, `line N`; for a Kafka record, `topic/partition@offset` (`orders/3@1041`) |
| `code` | The `PRV-` code of the decode failure, so a thousand rejections can be grouped by what went wrong and each one links to its page |
| `reason` | Why it could not be decoded, as the source's decoder said it |
| `schema` | The stream's schema as it was at the moment of rejection. It is what makes "the schema has changed since" a refusal a replay can check rather than a caveat |
| `raw` | The **original bytes**, Base64 — exactly what arrived, for replay or forensics. For a Kafka record, its value (a tombstone's key, having no value) |

(The reason text above is illustrative; it is whatever the decoder reported. Every field but
`timestamp`, `query`, `offset`, `reason` and `raw` was added later, and an entry written before
them simply has none: it still lists, shows and — where the refusals allow — replays.)

Two sibling files sit beside `<query>.dlq` and are **not** part of it: `<query>.dlq.replays`, which
records what has been replayed, and `<query>.dlq.evicted`, which records what retention took. The
`.dlq` file stays exactly one JSON object per rejected record, because every `jq` recipe below
depends on that being true.

## Reading it during an incident

Meant for `grep` and `jq`. How many, per query:

```bash
wc -l /opt/pravaha/data/dlq/*.dlq
```

The reasons, most common first:

```bash
jq -r .reason /opt/pravaha/data/dlq/big_card_txn.dlq | sort | uniq -c | sort -rn | head
```

The original line of each rejected record:

```bash
jq -r .raw /opt/pravaha/data/dlq/big_card_txn.dlq | while read -r b; do echo "$b" | base64 -d; echo; done
```

```text
812,u-1042,acme,12.50,EUR,,2026-09-19T09:12:00Z
```

## Reading them without a shell on the node

A containerised operator has a browser and an API token, not a shell on the pod. The same entries
are on three surfaces, all deciding by the view's own authorization rules.

**The console.** A query's page has **Dead letters**: the queue newest first, each entry's code
linked to its page, the record legible rather than Base64, and a checkbox per entry to replay it.

**The CLI.**

```bash
pravaha dlq list --name big_card_txn
pravaha dlq show --name big_card_txn --id 6f1c2b0e-5d7a-4e8f-9a41-0c3b2d9e7f15
```

```text
ID                                    WHEN                  CODE      STREAM  OFFSET    BYTES  STATE  REASON
6f1c2b0e-5d7a-4e8f-9a41-0c3b2d9e7f15  2026-09-19T09:12:00Z  PRV-5040  txn     line 812  51     NEW    column 'amount' (INT64): cannot read '12.50' as a number

1 dead letter (51 bytes); retention 268435456 bytes
```

**The API.**

| Call | Answers |
|---|---|
| `GET /api/v1/queries/{name}/dead-letters?offset=&limit=` | A page, newest first, with the queue's totals |
| `GET /api/v1/queries/{name}/dead-letters/count` | How deep it is, and what retention took — the call a dashboard polls |
| `GET /api/v1/queries/{name}/dead-letters/{id}` | One entry whole |
| `POST /api/v1/queries/{name}/dead-letters/replay` | `{"ids": ["..."]}` |

Both SDKs have the same four (`dead_letters`, `dead_letter`, `replay_dead_letters` in Python;
`deadLetters`, `deadLetter`, `replayDeadLetters` in Java), and the Flight actions behind them are
`pravaha.dlq.list`, `pravaha.dlq.show` and `pravaha.dlq.replay`.

!!! warning "Who may see a record, and who may put one back"
    A dead letter's bytes are **a row of the source**, and a record that failed to decode has no row
    for a row filter to be applied to — so the choice is all or nothing. A caller whose read of the
    view is row-filtered is shown the count, the id, the offset, the code, the size and when, and
    **not** the record or the decoder's sentence (which quotes the value it choked on). The refusal
    is said in the answer rather than left as an empty field.

    Replaying is a separate right. It puts a row into a view other people read, so it is authorized
    like `DROP`, `PAUSE` and `RESUME` — the view's owner, a principal granted it, or an admin; a
    reader, filtered or not, does not qualify.

## Putting one back

```bash
pravaha dlq replay --name big_card_txn --id 6f1c2b0e-5d7a-4e8f-9a41-0c3b2d9e7f15
```

The semantics, stated plainly because they are not what "replay" suggests:

- **A new row at the query's current frontier, not a rewind.** The bytes go back through the same
  decoder that refused them and the row is applied to the state the query has *now*. Nothing is
  re-read, no source offset moves, and no earlier answer is recomputed — a query that has already
  emitted a window the record belongs to will not emit it again, and the record lands as late data.
- **A record that fails again returns to the queue.** It is written back as a *new* entry naming the
  id it was a replay of, and the original is marked `FAILED_AGAIN` rather than removed. Nothing
  loops: a client retrying blindly walks forwards through ids instead of round the same one.
- **It is not idempotent.** Replaying the same id twice puts the row in twice, which for a query
  counting things is two.
- **There is no "replay everything".** A queue is usually a mix of causes and most of it is still
  malformed; replaying all of it would mostly rewrite the queue with a copy of itself.

A replay is for a record the source will never send again. Where the source *will* — a followed file
you can correct, a topic you can republish to — correcting it at the source is better, because the
record then arrives in order and the offsets still mean what they say.

Five things make a replay refused with [PRV-4092](/help/codes/PRV-4092): the entry is a row whose
evaluation failed (`PRV-3027`, above), the stream's schema has
changed since the record was rejected, the source promises `EXACTLY_ONCE` and has not read past the
record's offset (so it is going to deliver it again itself), the source cannot decode a record
outside its own read, or the query is gone or reads a different stream now.

## How much is kept

Unbounded, this file is a way for one renamed column to fill the disk the node's checkpoints are on.
The bound is `pravaha.dlq.max-bytes` (256 MiB by default), `pravaha.dlq.max-entries` and
`pravaha.dlq.max-age`, and it is enforced by **evicting the oldest**, not by refusing the newest.

That way round on purpose. Refusing hands the writer a queue that has stopped accepting, and the
writer has exactly two moves left: fail the poll, which stops the source and is the one outcome the
queue exists to prevent, or drop the record, which is the silent loss it exists to prevent. And it
would refuse the *newest* entry — the one somebody is looking at during an incident — to keep ten
thousand copies of last month's schema change.

**The loss is never silent.** Every eviction is recorded three ways:

- a line in `<query>.dlq.evicted`, which outlives the process that caused it, so "how much of this
  queue's history is missing" is answerable a week later;
- a `WARNING` in the node's log naming the file, the bound and how much went;
- an `evicted` count on every surface that shows the queue — the API, `pravaha dlq list`, the
  console's screen, and `pravaha_query_dead_letters_evicted_total`.

A depth without that count cannot be read: a queue steady at two thousand is either one bad
afternoon or a bound throwing two thousand a minute away, and those need opposite responses.

## Watching them

| Meter | Is |
|---|---|
| `pravaha_query_dead_letters` | How many are waiting now — **the one to alert on**. The running total keeps rising for a queue somebody is on top of; the depth does not |
| `pravaha_query_dead_letters_bytes` | How large the file is, against `max-bytes` |
| `pravaha_query_dead_letters_evicted_total` | What retention has thrown away and will not give back |
| `pravaha_query_dead_letters_write_failures_total` | Records the queue itself could not write. Non-zero means the DLQ needs attention **before** the records in it do: those records are gone and nothing else says so |
| `pravaha_query_dead_letters_fraction` | The share of records rejected in the current window |
| `pravaha_query_dead_letters_degraded` | 1 once that share passes the threshold |

The last two are the rate, and the rate is what matters: every real feed produces some rejects, so a
DLQ that alerts on the first one is a DLQ whose alerts get muted in week two. A steady trickle from
one partner is Tuesday; the same feed suddenly rejecting a third of its records is a schema change
nobody announced, and the node reports itself `DEGRADED` for it (design 15.6) rather than stopping —
stopping would discard the records that *are* valid, which is the bigger loss.

**Operations** turns these into findings: a queue that grew since the last look is a warning naming
the query, a query past the rate threshold is critical, and an eviction is its own warning.

## Which sources use it

The queue is offered to every source feed, and it is used by a source whose reader hands an
undecodable record back rather than failing. **Today that is the `filesystem` source**, whose decoder
rejects a line at a time, and **the [`kafka` source](/help/topics/source-kafka)**, which rejects a
record that is not JSON, does not fit the declared schema, or is a tombstone in `format: json`.
Without a queue the Kafka source stops with PRV-5105, its position still before the record. Other
sources fail as they did without a queue — and a source that reads typed values (a database, a table
format) rarely has a record it cannot decode.

## PRV-4090: a queue the node cannot write

If the directory is set and the node cannot create or write the query's file there, the query's feed
is refused with PRV-4090 when it opens — at registration, or when the journal is replayed at startup
-- rather than running without the queue:

```text
PRV-4090  pravaha.dlq.directory is /opt/pravaha/data/dlq and this node cannot write there: ... Fix the path or unset the key -- starting without the queue would give you the behaviour you configured it to avoid.
```

Deliberately fatal: an operator who configured a queue asked for bad records to be kept. Fix the
path and its permissions, or unset the key to go back to failing loudly.

## The same thing from the CLI

`pravaha-engine run`, which runs a query over a file with no server, takes the queue as a flag and prints
the reject count beside the row counts:

```bash
pravaha-engine run --sql "SELECT txn_id, user_id, amount FROM txn WHERE amount > 100" \
  --schema "txn_id:INT64,user_id:STRING,amount:INT64" \
  --out-schema "txn_id:INT64,user_id:STRING,amount:INT64" \
  --stream txn --in txn.csv --out big.csv --dlq rejected.dlq
```

```text
ok  19999 in, 4211 out
  1 rejected -> rejected.dlq
  plan 5210 us, execute 81234 us
```

(Sample counts and timings.) The reject count includes rows that decoded and then failed evaluation
(`PRV-3027`), as on a server; until CLIDLQ-1 such a row ended the run even with `--dlq`. If the queue
itself could not be written, that count goes to stderr --
a run that reports `ok` while having discarded input is what the queue exists to prevent.

## Pitfalls

!!! danger "The dead-letter files are data"
    Each holds the raw bytes of every rejected record — account numbers and all. It is created
    owner-only (`rw-------`), like the journal and the checkpoints; nothing is encrypted at rest.

!!! warning "Pitfall: a quiet queue is not a healthy feed"
    A queue that is not growing is not the same as a feed that is healthy. Alert on
    `pravaha_query_dead_letters` rising, not on it being non-zero, and on
    `pravaha_query_dead_letters_write_failures_total` at all: those records are gone.

!!! warning "Pitfall: replaying is not re-reading"
    A replayed record is a new row now, at the frontier the query has reached. If what you wanted was
    the record *in its original order* — and for a windowed query that is usually what you wanted —
    correct it at the source and let the source deliver it, or re-register the query from an offset
    before it.

## Where next

- [State and serving codes](/help/topics/errors-state) — PRV-4090, PRV-4091, PRV-4092
- [The filesystem source](/help/topics/source-filesystem)
- [The Kafka source](/help/topics/source-kafka) — its dead letters are named `topic/partition@offset`
- [Metrics and alerts](/help/topics/observability)
- [Configuring a node](/help/topics/configuration)
