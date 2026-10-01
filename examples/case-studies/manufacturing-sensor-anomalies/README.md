# Machine sensor anomalies — a manufacturing case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** none — a CSV file the node follows · **Time to first result:** about ten minutes ·
**Shows:** tumbling windows, a filter writing to a sink, allowed lateness and the correction it makes

## The problem

A hydraulic press runs hot for two minutes before its seals fail. Every reading on the way there is
within the sensor's range; what matters is the *trend* inside a minute, and a single reading past
the alarm line. A plant historian stores every reading and a report shows the failure the next
morning. The maintenance team wanted to know while the press could still be stopped.

Two answers are wanted, and they are different shapes:

- **Per machine, per minute:** how many readings, the coolest and hottest, the worst vibration. A
  dashboard reads it; an engineer asks about one machine.
- **Every reading over the alarm line, as it happens:** a stream of alarms, written to a file a
  maintenance system reads, as well as to a view.

Sensors are also *late*. A gateway buffers readings when the plant network drops, and delivers them
seconds after the minute they belong to has been reported. This study shows what the engine does
with them.

## What you will build

```
  data/sensor_readings.csv        Pravaha                          readers
 ┌────────────────────────┐     ┌──────────────────────┐   SQL   ┌──────────────────────┐
 │ one line per reading,  │ ──► │ machine_health       │ ──────► │ dashboard, engineer  │
 │ appended as they come  │     │  (1-minute windows)  │         └──────────────────────┘
 └────────────────────────┘     │ overheat_alerts      │ ──────► data/overheat_alerts.csv
                                │  (a filter + a sink) │
                                └──────────────────────┘
```

Nothing is installed besides the node: the source is the `filesystem` plugin following a CSV file,
and the sink is the same plugin appending to another. Both are inside the server jar.

## The data model

One stream, `sensor_reading`:

| Column | Type | Meaning |
|---|---|---|
| `reading_id` | STRING | Unique per reading |
| `machine_id` | STRING | `press-01`, `press-02`, `lathe-07` |
| `line_id` | STRING | The production line: `L1` (the presses), `L2` (the lathe) |
| `temperature_dc` | INT64 | **Tenths of a degree** Celsius: `723` is 72.3 °C |
| `vibration_um` | INT64 | Peak displacement in micrometres |
| `reading_time` | TIMESTAMP | When the sensor took it — **the stream's event time** |

> **Integers, not floats.** A temperature in tenths is exact, compares exactly against an alarm
> threshold, and sums without drift. Divide by ten where a person reads it.

The build checks the SQL against [`schema/streams.properties`](schema/streams.properties); the
node reads [`conf/application.yaml`](conf/application.yaml). The three settings on the stream are
the ones that decide what this study shows:

- **`event-time: reading_time`** — windows are cut on the sensor's clock, not the node's. Without it
  the windowed query is refused at registration, because no minute could ever close.
- **`out-of-orderness: 5s`** — how far one machine's readings may trail another's. A minute closes
  when a reading five seconds past its end has been read.
- **`allowed-lateness: 30s`** — after a minute is published, its state is kept for thirty more
  seconds of event time, so a late reading can still correct it. The default is zero: final on close.

## Step 1 — generate the shift

From this directory:

```bash
python3 data/generate_readings.py > data/sensor_readings.csv
wc -l data/sensor_readings.csv        # 56: a header, 54 readings, one heartbeat
```

Three machines report every ten seconds from 08:00:00 to 08:02:50. `press-02` climbs through the
second minute and crosses 95.0 °C in the third; `lathe-07` shakes at 08:01:30 and 08:01:40. The last
line is a heartbeat from `press-01` at 08:03:20: it is what tells the engine, in event time, that
all three minutes are over. The generator's output is the same on every run, so the numbers below
are the ones you will see.

## Step 2 — start the node

From this directory, so the relative paths in the configuration resolve here:

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

It states what is in force for the stream — the line to read first if a view is ever unexpectedly
empty:

```text
stream sensor_reading: event-time=reading_time, out-of-orderness=PT5S, allowed-lateness=PT30S
```

`dev` lets a client connect without a token, which is right on a laptop and wrong anywhere else;
[`../SETUP.md`](../SETUP.md) covers the node and the SDK once for every study.

## Step 3 — the two continuous queries

### Machine health, per minute

[`sql/01-continuous-machine-health.sql`](sql/01-continuous-machine-health.sql):

```sql
SELECT STREAM
  TUMBLE_END(reading_time, INTERVAL '1' MINUTE) AS window_end,
  machine_id,
  line_id,
  COUNT(*)            AS readings,
  MIN(temperature_dc) AS min_temperature_dc,
  MAX(temperature_dc) AS max_temperature_dc,
  MAX(vibration_um)   AS max_vibration_um
FROM sensor_reading
GROUP BY TUMBLE(reading_time, INTERVAL '1' MINUTE), machine_id, line_id
```

One row per machine per minute, keyed by `(window_end, machine_id)` — key columns `[0, 1]`. The
window is what makes it safe to run for ever: a minute's state is released once it closes and its
lateness has passed. Group by `machine_id` with no window and the engine refuses it (`PRV-2050`),
because that state would grow with every machine for as long as the node runs.

### Overheat alarms, as they happen

[`sql/02-continuous-overheat-alerts.sql`](sql/02-continuous-overheat-alerts.sql):

```sql
SELECT reading_id, machine_id, line_id, temperature_dc, reading_time
FROM sensor_reading
WHERE temperature_dc >= 950
```

No window, no state: a filter. It is registered with `sink="overheat_alerts"`, the binding under
`pravaha.sinks` in the configuration, so every alarm is appended to `data/overheat_alerts.csv` as
well as kept in the view. A filter's answer only ever grows, which is why an append-only file can
hold it; the engine refuses (`PRV-2041`) to attach an append-only sink to a query that revises its
answer, such as the windowed one above once lateness is allowed.

### Register them

[`python/run.py`](python/run.py) does it, waits for the first minute to close, and asks the
questions in Step 4:

```bash
python3 python/run.py --url grpc://localhost:19090
```

The registration inside it is two calls:

```python
from pravaha import connect

with connect("grpc://localhost:19090") as client:
    client.register("machine_health", open("sql/01-continuous-machine-health.sql").read(), [0, 1])
    client.register("overheat_alerts", open("sql/02-continuous-overheat-alerts.sql").read(), [0],
                    sink="overheat_alerts")
```

It prints:

```text
registered machine_health RUNNING 34e4c1e9152e
registered overheat_alerts RUNNING ecaf710a73e1
```

The Java equivalent is [`java/SensorAnomalyExample.java`](java/SensorAnomalyExample.java). From a
shell: `pravaha register --name machine_health --sql-file sql/01-continuous-machine-health.sql --keys 0,1`
and `pravaha register --name overheat_alerts --sql-file sql/02-continuous-overheat-alerts.sql --keys 0 --sink overheat_alerts`.

## Step 4 — ask questions

Everything below is what `python/run.py` printed on a real node.

### One machine

[`sql/03-read-one-machine.sql`](sql/03-read-one-machine.sql):

```sql
SELECT window_end, readings, min_temperature_dc, max_temperature_dc, max_vibration_um
FROM machine_health
WHERE machine_id = ?
```

Bound to `press-02`:

```text
-- machine_health for press-02
   {'window_end': '2026-03-02T08:01:00+00:00', 'readings': 6, 'min_temperature_dc': 720, 'max_temperature_dc': 724, 'max_vibration_um': 28}
   {'window_end': '2026-03-02T08:02:00+00:00', 'readings': 6, 'min_temperature_dc': 780, 'max_temperature_dc': 905, 'max_vibration_um': 28}
   {'window_end': '2026-03-02T08:03:00+00:00', 'readings': 6, 'min_temperature_dc': 940, 'max_temperature_dc': 980, 'max_vibration_um': 28}
```

The trend is the point: a spread of 72.0–72.4 °C, then 78.0–90.5, then 94.0–98.0.

### Which minutes look wrong

[`sql/04-read-anomalous-minutes.sql`](sql/04-read-anomalous-minutes.sql):

```sql
SELECT window_end, machine_id, max_temperature_dc, max_vibration_um
FROM machine_health
WHERE max_temperature_dc >= ? OR max_vibration_um >= ?
```

Bound to `[950, 150]` — 95.0 °C, 150 µm:

```text
-- minutes over 95.0 C or 150 um
   {'window_end': '2026-03-02T08:02:00+00:00', 'machine_id': 'lathe-07', 'max_temperature_dc': 609, 'max_vibration_um': 180}
   {'window_end': '2026-03-02T08:03:00+00:00', 'machine_id': 'press-02', 'max_temperature_dc': 980, 'max_vibration_um': 28}
```

Two different failures, one question. Thresholds are parameters, so tuning them is not a redeploy.

### By line

[`sql/05-read-line-summary.sql`](sql/05-read-line-summary.sql):

```sql
SELECT line_id, COUNT(*) AS machine_minutes, SUM(readings) AS readings, MAX(max_temperature_dc) AS hottest_dc
FROM machine_health
GROUP BY line_id
```

```text
-- by line
   {'line_id': 'L1', 'machine_minutes': 6, 'readings': 36, 'hottest_dc': 980}
   {'line_id': 'L2', 'machine_minutes': 3, 'readings': 18, 'hottest_dc': 609}
```

An unwindowed `GROUP BY` — refused in a continuous query, fine here, because a read of a view scans
the finite set of rows the view holds and stops.

### The alarms

```text
-- overheat_alerts
   {'reading_id': 'press-02-14', 'machine_id': 'press-02', 'line_id': 'L1', 'temperature_dc': 956, 'reading_time': '2026-03-02T08:02:20+00:00'}
   {'reading_id': 'press-02-15', 'machine_id': 'press-02', 'line_id': 'L1', 'temperature_dc': 964, 'reading_time': '2026-03-02T08:02:30+00:00'}
   {'reading_id': 'press-02-16', 'machine_id': 'press-02', 'line_id': 'L1', 'temperature_dc': 972, 'reading_time': '2026-03-02T08:02:40+00:00'}
   {'reading_id': 'press-02-17', 'machine_id': 'press-02', 'line_id': 'L1', 'temperature_dc': 980, 'reading_time': '2026-03-02T08:02:50+00:00'}
```

And the same four in the sink's file, `data/overheat_alerts.csv`, with the time as epoch
nanoseconds:

```text
press-02-14,press-02,L1,956,1772438540000000000
press-02-15,press-02,L1,964,1772438550000000000
press-02-16,press-02,L1,972,1772438560000000000
press-02-17,press-02,L1,980,1772438570000000000
```

Notice what the filter did *not* wait for: the alarms were there as soon as the readings were read,
while the minute they sit in was still open. Only the windowed query waits for time to pass.

## Step 5 — late readings

Follow `press-02` in one terminal:

```bash
python3 python/run.py --url grpc://localhost:19090 --watch
```

and in another, deliver what the gateway had buffered:

```bash
python3 data/generate_readings.py --phase late >> data/sensor_readings.csv
```

That appends three lines: `press-02-late` for 08:02:55 at 99.1 °C, `press-01-stale` for 08:00:45,
and a second heartbeat at 08:03:40. The subscription prints the view as it stood, then one commit:

```text
following machine_health for press-02; Ctrl-C to stop
-- snapshot
   +1 {'window_end': '2026-03-02T08:01:00+00:00', 'machine_id': 'press-02', 'line_id': 'L1', 'readings': 6, 'min_temperature_dc': 720, 'max_temperature_dc': 724, 'max_vibration_um': 28}
   +1 {'window_end': '2026-03-02T08:02:00+00:00', 'machine_id': 'press-02', 'line_id': 'L1', 'readings': 6, 'min_temperature_dc': 780, 'max_temperature_dc': 905, 'max_vibration_um': 28}
   +1 {'window_end': '2026-03-02T08:03:00+00:00', 'machine_id': 'press-02', 'line_id': 'L1', 'readings': 6, 'min_temperature_dc': 940, 'max_temperature_dc': 980, 'max_vibration_um': 28}
-- commit
   -1 {'window_end': '2026-03-02T08:03:00+00:00', 'machine_id': 'press-02', 'line_id': 'L1', 'readings': 6, 'min_temperature_dc': 940, 'max_temperature_dc': 980, 'max_vibration_um': 28}
   +1 {'window_end': '2026-03-02T08:03:00+00:00', 'machine_id': 'press-02', 'line_id': 'L1', 'readings': 7, 'min_temperature_dc': 940, 'max_temperature_dc': 991, 'max_vibration_um': 28}
```

What happened, reading by reading:

- **`press-02-late` corrected the third minute.** That minute ended at 08:03:00 and was published
  when the watermark passed it (08:03:15, from the first heartbeat less five seconds). Allowed
  lateness keeps it until the watermark reaches 08:03:30, so the reading was applied: the old row is
  withdrawn (`-1`) and the corrected one inserted (`+1`) in the same commit. A reader of the view
  never sees both; a subscriber sees exactly what changed.
- **`press-01-stale` was dropped by the window.** The first minute's lateness ran out at 08:01:30,
  long before. `press-01`'s first minute still says 6 readings and a maximum of 72.0 °C.
- **The second heartbeat published the correction.** A correction is emitted when event time next
  moves, not the instant the late reading is read. Without that heartbeat the view would hold the
  corrected count internally and show the old one until the next reading arrived.

The alarm filter, which has no window, passed *both* late readings through — its view and its file
now end:

```text
press-02-late,press-02,L1,991,1772438575000000000
press-01-stale,press-01,L1,999,1772438445000000000
```

Lateness belongs to windows. A stateless filter has nothing to be late for.

## Making this yours

| To change | Do this |
|---|---|
| The window | `INTERVAL '1' MINUTE` → `'5' MINUTE`. Longer windows hold more state and answer later |
| A rolling view | `TUMBLE` → `HOP(reading_time, INTERVAL '1' MINUTE, INTERVAL '5' MINUTE)` — see the [telecom study](../telecom-cdr-fraud/) |
| How late is too late | `allowed-lateness` on the stream. More lateness is more state held, and later finality |
| The alarm line | The literal in `02-continuous-overheat-alerts.sql`, or a second query per severity |
| Where alarms go | Another sink binding — Kafka, JDBC, Delta — with the same `sink=` on registration |
| A real feed | Replace the `filesystem` source with Kafka or a directory of files (`feedfile`); the SQL does not move |

## Pitfalls

- **A correction is published at the next commit.** Within allowed lateness a late reading changes
  the window's state at once, and the corrected row reaches the view with the query's next commit
  (within the feed's publish interval). Before EMIT-2 it waited for the next watermark advance,
  which on a quiet sensor could be a while.
- **Allowed lateness makes the windowed answer revisable**, so the windowed view cannot feed an
  append-only sink (`PRV-2041`). Keep sinks on the filter, or use a sink that accepts retractions.
- **A filter passes late rows.** `press-01-stale` became an alarm for a minute that had closed long
  before. If an alarm must be timely, compare `reading_time` with the time it was received in the
  consumer.
- **No `CASE`, so no "severity" column in one query.** Register one filter per severity.
- **`AVG` over integers truncates.** For an average temperature, sum and count in the view and
  divide in the reader.
- **A windowed query over a stream without `event-time` is refused** at registration with
  `PRV-2002`. That is the engine saving you from a view that would never fill.

The full list of what runs and what is refused is
[`docs/guides/CONTINUOUS_QUERIES.md`](../../../docs/guides/CONTINUOUS_QUERIES.md).
