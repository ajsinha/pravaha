---
title: Dead letters
slug: dead-letters
category: operating
order: 100
icon: envelope-exclamation
summary: "pravaha.dlq.directory: where records a source cannot decode are written, one JSON line each, so one bad field does not stop a feed. What goes there, which sources use it, reading it during an incident, and PRV-4090."
audience: Operators
keywords: [dlq, dead letter queue, undecodable, malformed record, decode failure, PRV-4090, PRV-5040, PRV-5105, kafka, tombstone, jq, base64, pravaha run --dlq]
guide: operations#files-that-hold-data
related: [source-filesystem, source-kafka, metrics-alerts, checkpoints-recovery, configuration]
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
    directory: /var/lib/pravaha/dlq
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.dlq.directory` | empty | Where undecodable records go, **one file per query**, named `<query>.dlq`. Empty: no queue, and a decode failure stops that query's source |

The node logs it at startup:

```text
dead-lettering undecodable records to /var/lib/pravaha/dlq (pravaha.dlq.directory)
```

## Without it, and with it

A CSV bound to `txn` has 20,000 lines, and line 812 carries `amount` as `12.50` — a decimal in
an `INT64` column.

**No queue configured.** The decode failure ends the poll and stops the source for that query, and
with it every other row in the file. The query goes on reporting `RUNNING`; its view simply stops
growing. The feed records why it stopped (`PRV-5092` or the source's own code), but no API, CLI
command or console screen shows that record yet (FEED-1): look for the source's error in the
node's log. This used to be the only
behaviour a server had: one malformed field could take a whole file to zero rows with nothing in the
query's state to show it (TIME-4).

**With `pravaha.dlq.directory` set.** Line 812 is written to `/var/lib/pravaha/dlq/<query>.dlq`, the
other 19,999 rows are ingested, and the query carries on.

## What a dead letter looks like

One JSON object per line, flushed per entry — a crash is when the interesting records arrive, and a
queue that loses its last few in a buffer loses exactly those:

```text
{"timestamp":83218734112093,"query":"big_card_txn","correlationId":"6f1c2b0e-5d7a-4e8f-9a41-0c3b2d9e7f15","offset":"line 812","reason":"column 'amount' (INT64): cannot read '12.50' as a number","raw":"ODEyLHUtMTA0MixhY21lLDEyLjUwLEVVUiwsMjAyNi0wOS0xOVQwOToxMjowMFo="}
```

| Field | Holds |
|---|---|
| `timestamp` | When it was rejected: a monotonic nanosecond reading, for ordering entries — not a wall-clock time |
| `query` | The registered query whose feed rejected it |
| `correlationId` | A fresh id per rejection, to quote in a ticket or a log search |
| `offset` | Where in the source: for a file, `line N`; for a Kafka record, `topic/partition@offset` (`orders/3@1041`) |
| `reason` | Why it could not be decoded, as the source's decoder said it |
| `raw` | The **original bytes**, Base64 — exactly what arrived, for replay or forensics. For a Kafka record, its value (a tombstone's key, having no value) |

(The reason text above is illustrative; it is whatever the decoder reported.)

## Reading it during an incident

Meant for `grep` and `jq`. How many, per query:

```bash
wc -l /var/lib/pravaha/dlq/*.dlq
```

The reasons, most common first:

```bash
jq -r .reason /var/lib/pravaha/dlq/big_card_txn.dlq | sort | uniq -c | sort -rn | head
```

The original line of each rejected record:

```bash
jq -r .raw /var/lib/pravaha/dlq/big_card_txn.dlq | while read -r b; do echo "$b" | base64 -d; echo; done
```

```text
812,u-1042,acme,12.50,EUR,,2026-09-19T09:12:00Z
```

To put corrected records back, fix them and append them to the source (for a followed file, to the
file itself); the dead-letter file is a record, not an input.

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
PRV-4090  pravaha.dlq.directory is /var/lib/pravaha/dlq and this node cannot write there: ... Fix the path or unset the key -- starting without the queue would give you the behaviour you configured it to avoid.
```

Deliberately fatal: an operator who configured a queue asked for bad records to be kept. Fix the
path and its permissions, or unset the key to go back to failing loudly.

## The same thing from the CLI

`pravaha run`, which runs a query over a file with no server, takes the queue as a flag and prints
the reject count beside the row counts:

```bash
pravaha run --sql "SELECT txn_id, user_id, amount FROM txn WHERE amount > 100" \
  --schema "txn_id:INT64,user_id:STRING,amount:INT64" \
  --out-schema "txn_id:INT64,user_id:STRING,amount:INT64" \
  --stream txn --in txn.csv --out big.csv --dlq rejected.dlq
```

```text
ok  19999 in, 4211 out
  1 rejected -> rejected.dlq
  plan 5210 us, execute 81234 us
```

(Sample counts and timings.) If the queue itself could not be written, that count goes to stderr --
a run that reports `ok` while having discarded input is what the queue exists to prevent.

## Pitfalls

!!! danger "The dead-letter files are data"
    Each holds the raw bytes of every rejected record — account numbers and all. It is created
    owner-only (`rw-------`), like the journal and the checkpoints; nothing is encrypted at rest.

!!! warning "Pitfall: a quiet queue is not a healthy feed"
    Nothing publishes a dead-letter count to Prometheus today. Watch the directory's size, or alert on
    a query's `pravaha_query_rows_in` falling behind what its source should deliver.

!!! note "Older wording"
    The long-form Operations guide still says a server "has no `pravaha.dlq.*` key yet". It has:
    `pravaha.dlq.directory` is read by the node, as above.

## Where next

- [The filesystem source](/help/topics/source-filesystem)
- [The Kafka source](/help/topics/source-kafka) — its dead letters are named `topic/partition@offset`
- [Metrics and alerts](/help/topics/metrics-alerts)
- [Configuring a node](/help/topics/configuration)
