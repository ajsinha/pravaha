# Call-detail-record fraud — a telecom case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** none — a CSV file the node follows · **Time to first result:** about ten minutes ·
**Shows:** hopping (sliding) windows, `COUNT(DISTINCT)`, an `IN` filter, top-N with `ROW_NUMBER`

## The problem

Every call a mobile network completes produces a call detail record (CDR): who called whom, where
to, for how long, from which cell. Two frauds hide in them, and both cost money by the minute:

- **SIM-box bypass.** A rack of SIM cards relays international calls onto the network as if they
  were local, so the operator is paid a local rate for an international termination. The tell is
  one number making a call every few seconds, each to a different number, all short.
- **International revenue-share fraud (IRSF).** A hijacked line calls premium-rate ranges in small
  island states — the fraudster shares the termination revenue — in long calls, often at night.

A nightly batch over the day's CDRs finds both, twelve hours and several thousand pounds late. The
fraud team wants the profile of each caller over the last five minutes, kept current, and every
premium call as it is recorded.

## What you will build

```
  data/call_records.csv           Pravaha                            fraud desk
 ┌────────────────────────┐     ┌─────────────────────────────┐    ┌──────────────────────┐
 │ one line per completed │ ──► │ caller_velocity  (sliding)  │ ─► │ "is this a SIM box?" │
 │ call, appended by the  │     │ premium_calls    (a filter) │ ─► │ "block this line"    │
 │ mediation layer        │     │ top_callers      (top-3)    │ ─► │ leader board         │
 └────────────────────────┘     └─────────────────────────────┘    └──────────────────────┘
```

Three continuous queries over one stream, and nothing installed but the node.

## The data model

One stream, `call_record`:

| Column | Type | Meaning |
|---|---|---|
| `cdr_id` | STRING | Unique per record |
| `caller` | STRING | The calling number (MSISDN) |
| `callee` | STRING | The number called |
| `callee_country` | STRING | ISO country of the called number |
| `duration_s` | INT64 | Billed seconds |
| `cell_id` | STRING | The cell the call was made from |
| `start_time` | TIMESTAMP | When the call started — **the stream's event time** |

Checked by the build from [`schema/streams.properties`](schema/streams.properties); the node reads
[`conf/application.yaml`](conf/application.yaml), which declares `event-time: start_time` and ten
seconds of out-of-orderness.

## Step 1 — generate five minutes of an evening

From this directory:

```bash
python3 data/generate_cdrs.py > data/call_records.csv
```

21:00 to 21:05 UTC. Six ordinary subscribers make one to three calls each. `447700900666` is the SIM
box: a call every ten seconds from 21:01:00 to 21:02:50, twelve different numbers, 25–40 seconds
each. `447700900123` calls the Solomon Islands (`SB`) twice for 21 and 15 minutes, and Tuvalu (`TV`)
once for 45 seconds. The last record, at 21:10:30, is an ordinary call that moves event time past
every window those five minutes open. The output is identical on every run.

## Step 2 — start the node

From this directory:

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

```text
stream call_record: event-time=start_time, out-of-orderness=PT10S, allowed-lateness=PT0S
```

## Step 3 — the three continuous queries

### A sliding profile of every caller

[`sql/01-continuous-caller-velocity.sql`](sql/01-continuous-caller-velocity.sql):

```sql
SELECT STREAM
  HOP_END(start_time, INTERVAL '1' MINUTE, INTERVAL '5' MINUTE) AS window_end,
  caller,
  COUNT(*)               AS calls,
  COUNT(DISTINCT callee) AS distinct_callees,
  SUM(duration_s)        AS talk_seconds
FROM call_record
GROUP BY HOP(start_time, INTERVAL '1' MINUTE, INTERVAL '5' MINUTE), caller
```

`HOP(…, INTERVAL '1' MINUTE, INTERVAL '5' MINUTE)` is a five-minute window that starts every minute,
so each call belongs to five windows and "the last five minutes" is answered once a minute. A
tumbling five-minute window would split a burst that straddles a boundary into two halves that each
look innocent. Keyed by `(window_end, caller)` — `[0, 1]`.

`COUNT(DISTINCT callee)` is the SIM-box signal: a person calls the same few numbers; a SIM box
calls a different one every time. Distinct counting is bounded here because each window ends.

### Every long call to a premium destination

[`sql/02-continuous-premium-calls.sql`](sql/02-continuous-premium-calls.sql):

```sql
SELECT cdr_id, caller, callee, callee_country, duration_s, start_time
FROM call_record
WHERE callee_country IN ('SB', 'TV', 'NU')
  AND duration_s >= 60
```

A filter, so no window and no state: each qualifying record is in the view as soon as it is read.
The list of destinations is in the SQL because it changes rarely; when it changes, replace the query
([Tutorial 3](../../../docs/tutorials/03-changing-a-running-query.md)) and readers keep reading.

### The three busiest callers in each five minutes

[`sql/03-continuous-top-callers.sql`](sql/03-continuous-top-callers.sql):

```sql
SELECT window_start, caller, calls, rn
FROM (
  SELECT window_start, caller, calls,
         ROW_NUMBER() OVER (PARTITION BY window_start ORDER BY calls DESC) AS rn
  FROM (
    SELECT TUMBLE_START(start_time, INTERVAL '5' MINUTE) AS window_start, caller, COUNT(*) AS calls
    FROM call_record
    GROUP BY TUMBLE(start_time, INTERVAL '5' MINUTE), caller
  )
)
WHERE rn <= 3
```

`ROW_NUMBER() … WHERE rn <= 3` is the one ordering a continuous query supports: a maintained top-N.
`ORDER BY` and `LIMIT` over a stream are refused (`PRV-2020`) because they describe an order over
rows that have not all arrived; a top-N per partition is incremental — a row entering the top three
is emitted with its rank, the row it displaces is withdrawn. Partitioning by the window's start is
what bounds it: each window's partition is finished when the window closes.

### Register them

```bash
python3 python/run.py --url grpc://localhost:19090
```

[`python/run.py`](python/run.py) registers all three — `client.register(name, sql, keys)` — waits
for the windows to close, and asks the questions below. Java:
[`java/CdrFraudExample.java`](java/CdrFraudExample.java). From a shell:
`pravaha register --name caller_velocity --sql-file sql/01-continuous-caller-velocity.sql --keys 0,1`,
and the same for the other two.

```text
registered caller_velocity RUNNING 4a2655cf6ed1
registered premium_calls RUNNING 66607824042b
registered top_callers RUNNING a9e100aab74d
```

## Step 4 — ask questions

Everything below is what `python/run.py` printed on a real node.

### One caller's profile

[`sql/04-read-one-caller.sql`](sql/04-read-one-caller.sql):

```sql
SELECT window_end, calls, distinct_callees, talk_seconds
FROM caller_velocity
WHERE caller = ?
```

Bound to the SIM box, `447700900666`:

```text
-- caller_velocity for 447700900666
   {'window_end': '2026-05-14T21:02:00+00:00', 'calls': 6, 'distinct_callees': 6, 'talk_seconds': 185}
   {'window_end': '2026-05-14T21:03:00+00:00', 'calls': 12, 'distinct_callees': 12, 'talk_seconds': 390}
   {'window_end': '2026-05-14T21:04:00+00:00', 'calls': 12, 'distinct_callees': 12, 'talk_seconds': 390}
   {'window_end': '2026-05-14T21:05:00+00:00', 'calls': 12, 'distinct_callees': 12, 'talk_seconds': 390}
   {'window_end': '2026-05-14T21:06:00+00:00', 'calls': 12, 'distinct_callees': 12, 'talk_seconds': 390}
   {'window_end': '2026-05-14T21:07:00+00:00', 'calls': 6, 'distinct_callees': 6, 'talk_seconds': 205}
```

This is what sliding looks like: the burst from 21:01:00 to 21:02:50 enters the window ending
21:02 half-way, fills the four windows ending 21:03 to 21:06, and is leaving by 21:07.

### SIM-box suspects

[`sql/05-read-simbox-suspects.sql`](sql/05-read-simbox-suspects.sql):

```sql
SELECT caller, COUNT(*) AS windows_flagged, MAX(calls) AS most_calls, MAX(distinct_callees) AS most_distinct
FROM caller_velocity
WHERE calls >= ? AND distinct_callees >= ?
GROUP BY caller
```

Bound to `[8, 8]`:

```text
-- SIM-box suspects: 8+ calls to 8+ numbers in five minutes
   {'caller': '447700900666', 'windows_flagged': 4, 'most_calls': 12, 'most_distinct': 12}
```

`447700900001`, the busiest honest subscriber, made three calls to three numbers and is nowhere
near.

### Premium destinations

```text
-- premium_calls
   {'cdr_id': 'cdr-019', 'caller': '447700900123', 'callee_country': 'SB', 'duration_s': 1260}
   {'cdr_id': 'cdr-023', 'caller': '447700900123', 'callee_country': 'SB', 'duration_s': 915}
```

The 45-second call to Tuvalu is not there: `duration_s >= 60` excluded it.
[`sql/06-read-premium-by-caller.sql`](sql/06-read-premium-by-caller.sql) totals the view per line:

```sql
SELECT caller, callee_country, COUNT(*) AS calls, SUM(duration_s) AS seconds
FROM premium_calls
GROUP BY caller, callee_country
```

```text
-- premium destinations by caller
   {'caller': '447700900123', 'callee_country': 'SB', 'calls': 2, 'seconds': 2175}
```

### The leader board

```text
-- top_callers
   {'window_start': '2026-05-14T21:00:00+00:00', 'caller': '447700900123', 'calls': 3, 'rn': 3}
   {'window_start': '2026-05-14T21:00:00+00:00', 'caller': '447700900666', 'calls': 12, 'rn': 1}
   {'window_start': '2026-05-14T21:00:00+00:00', 'caller': '447700900001', 'calls': 3, 'rn': 2}
```

A view has no order — sort in the reader. `447700900001` and `447700900123` both made three calls;
ties are numbered by the remaining columns, so the ranks are the same on every run.

## Step 5 — follow the leader board

```bash
python3 python/run.py --url grpc://localhost:19090 --watch
```

In another terminal, append 21:10 to 21:15 — the SIM box, moved to another cell and slowed down,
and a subscriber making two calls — with a record at 21:21:00 that closes that window:

```bash
python3 data/generate_cdrs.py --phase next >> data/call_records.csv
```

The subscription prints the snapshot, then the new window's top three as one commit:

```text
-- snapshot
   +1 {'window_start': '2026-05-14T21:00:00+00:00', 'caller': '447700900123', 'calls': 3, 'rn': 3}
   +1 {'window_start': '2026-05-14T21:00:00+00:00', 'caller': '447700900666', 'calls': 12, 'rn': 1}
   +1 {'window_start': '2026-05-14T21:00:00+00:00', 'caller': '447700900001', 'calls': 3, 'rn': 2}
-- commit
   +1 {'window_start': '2026-05-14T21:10:00+00:00', 'caller': '447700900666', 'calls': 5, 'rn': 1}
   +1 {'window_start': '2026-05-14T21:10:00+00:00', 'caller': '447700900002', 'calls': 1, 'rn': 2}
   -1 {'window_start': '2026-05-14T21:10:00+00:00', 'caller': '447700900002', 'calls': 1, 'rn': 2}
   +1 {'window_start': '2026-05-14T21:10:00+00:00', 'caller': '447700900004', 'calls': 2, 'rn': 2}
   +1 {'window_start': '2026-05-14T21:10:00+00:00', 'caller': '447700900002', 'calls': 1, 'rn': 3}
```

Read the commit as a sum, not a list. `447700900002` entered at rank 2, was displaced by
`447700900004`, and re-entered at rank 3 — all inside one commit. Its `+1` and `-1` at rank 2
cancel; what remains is the window's final top three. A consumer that applies weights ends with
exactly the view; one that treats each row as an event would announce a rank that never existed.

## Making this yours

| To change | Do this |
|---|---|
| How far back "recent" is | The second interval of `HOP`: `INTERVAL '15' MINUTE` |
| How often it is recomputed | The first interval: `INTERVAL '30' SECOND` |
| The leader board's depth | `rn <= 3` → `rn <= 10` |
| Premium destinations | The `IN` list; replace the running query rather than dropping it |
| Per-cell views | Add `cell_id` to a `GROUP BY` — a burst from one mast is a different fraud |
| A real feed | Kafka for streamed CDRs, `feedfile` for files dropped by mediation; the SQL does not move |

## Pitfalls

- **Each call is in five sliding windows.** Summing `calls` across `caller_velocity` counts every
  call five times. Count the windows that breach a threshold, as `05` does, or use a tumbling window
  for totals.
- **Windows only close when event time moves.** If the switch goes quiet, the last window waits.
  In production a heartbeat record, or a source with a periodic watermark, keeps answers prompt.
- **Late records are dropped** once their window has closed: `allowed-lateness` is zero here. A
  mediation layer that batches records for minutes needs `out-of-orderness` or `allowed-lateness`
  to match, or those calls are never counted.
- **A top-N over a window needs the window in `PARTITION BY`.** Without it every row of the stream
  is a candidate for ever; the engine holds up to 1,000,000 rows and then stops the query with
  `PRV-4001`.
- **No `CASE`**, so "calls to premium destinations as a share of all calls" is two queries — the
  filter and the profile — joined by the reader.

The full list of what runs and what is refused is
[`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md).
