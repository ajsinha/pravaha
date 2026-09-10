# Sequencing run QC — a biology case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** Aerospike · **Time to first result:** about fifteen minutes · **Setup:** [`../SETUP.md`](../SETUP.md)

## The problem

A sequencing run takes hours and costs real money. Somewhere in hour two, one sample's coverage
collapses — a bad library prep, a clogged flow cell lane. If you find out when the run finishes and
the pipeline reports, you have paid for the whole run and you rerun the sample tomorrow. If you find
out in hour two, you stop, fix and restart.

This is a streaming problem wearing a laboratory coat. Reads arrive continuously; the QC metrics are
aggregates over a window; the answer is only useful while the run is still going.

It is here for a second reason: to show the same engine, the same SQL shapes and the same three
commands on a domain that has nothing to do with money. The `GROUP BY` does not care.

## What you will build

```
   Aerospike                       Pravaha                      lab dashboard
  ┌────────────┐   filter        ┌──────────────────┐   SQL    ┌──────────────┐
  │ read_metric│ ──────────────► │ continuous query │ ───────► │ "is sample 7 │
  │ set        │   pushed down   │ keeps coverage_  │          │  under depth?"│
  └────────────┘                 │ qc current       │          └──────────────┘
  ┌────────────┐   lookup join   │                  │
  │ manifest   │ ──────────────► └──────────────────┘
  └────────────┘
```

## The data model

### `read_metric` — the stream

One record per aligned read, or per batch of reads if your aligner emits summaries. This is the
high-volume table; a run produces millions of rows.

| Bin | Type | Meaning |
|---|---|---|
| `read_id` | integer | Unique within the run |
| `run_id` | string | The flow cell run |
| `sample_id` | string | Which sample. A grouping key |
| `target_id` | string | The panel region this read covers (an exon, an amplicon) |
| `mapping_quality` | integer | MAPQ, 0–60. Phred-scaled confidence the alignment is in the right place |
| `depth` | integer | Reads covering this position |
| `gc_percent` | integer | GC content, 0–100. Whole percent, not a fraction |
| `called_at` | integer | Event time, epoch nanoseconds |

> **`gc_percent` is an integer percentage, and `depth` is a count.** Neither is a float, and that is
> a deliberate schema choice rather than an engine limitation. A GC fraction of 0.41 stored as a
> double invites a mean that differs in the last digit between two runs of the same analysis, which
> in a regulated laboratory is a finding.

> **MAPQ 30 means a 1-in-1000 chance the read is misplaced.** That is the conventional threshold and
> the one the query filters on. Change it in one place — the `WHERE` clause.

### `sample_manifest` — the reference data

| Bin | Type | Meaning |
|---|---|---|
| `sample_id` | string | Primary key |
| `subject_id` | string | The patient or organism |
| `panel` | string | `CARDIO_V3`, `ONCO_V7` — the capture panel |
| `tissue` | string | `BLOOD`, `TUMOUR`, `SALIVA` |

Machine-readable and build-checked: [`schema/streams.properties`](schema/streams.properties).

## Step 1 — start Aerospike

```bash
docker run -d --name pravaha-aerospike --network host aerospike/aerospike-server:latest
docker exec pravaha-aerospike asinfo -v status   # expect: ok
```

## Step 2 — load the manifest

```bash
docker exec -it pravaha-aerospike aql
```
```sql
INSERT INTO test.sample_manifest (PK, sample_id, subject_id, panel, tissue)
  VALUES ('s-01', 's-01', 'subj-1', 'CARDIO_V3', 'BLOOD');
INSERT INTO test.sample_manifest (PK, sample_id, subject_id, panel, tissue)
  VALUES ('s-02', 's-02', 'subj-2', 'CARDIO_V3', 'BLOOD');
INSERT INTO test.sample_manifest (PK, sample_id, subject_id, panel, tissue)
  VALUES ('s-03', 's-03', 'subj-3', 'ONCO_V7',   'TUMOUR');
```

## Step 3 — the continuous query

[`sql/01-continuous-coverage-qc.sql`](sql/01-continuous-coverage-qc.sql):

```sql
SELECT STREAM
  TUMBLE_END(r.called_at, INTERVAL '30' SECOND) AS window_end,
  r.run_id,
  r.sample_id,
  m.panel,
  COUNT(*)                    AS read_count,
  AVG(r.depth)                AS mean_depth,
  MIN(r.mapping_quality)      AS min_mapq,
  COUNT(DISTINCT r.target_id) AS targets_touched
FROM read_metric AS r
LEFT JOIN sample_manifest FOR SYSTEM_TIME AS OF r.called_at AS m
       ON r.sample_id = m.sample_id
WHERE r.mapping_quality >= 30
GROUP BY TUMBLE(r.called_at, INTERVAL '30' SECOND), r.run_id, r.sample_id, m.panel
```

Domain notes:

- **Thirty-second windows**, because the point is catching a failure inside the run. A run-long
  aggregate is what the existing pipeline already gives you, too late.
- **`WHERE r.mapping_quality >= 30`** is pushed into Aerospike. On a run producing millions of reads,
  poorly-mapped ones never cross the network — which is most of the reason this fits on a laptop.
- **`COUNT(DISTINCT r.target_id)`** is the important one. Mean depth can look healthy while every
  read piles onto three exons and the rest of the panel has nothing. Depth without breadth is a
  sample you will be re-running.
- **`AVG(r.depth)` truncates**, because `depth` is an integer and SQL's `AVG` over integers is integer
  division. A mean depth of 47.9 reads as 47. If that matters, take `SUM(r.depth)` and
  `COUNT(*)` and divide where you have floating point.

## Step 4 — stream reads in

```sql
INSERT INTO test.read_metric (PK, read_id, run_id, sample_id, target_id, mapping_quality, depth, gc_percent, called_at)
  VALUES ('r-1', 1, 'run-A', 's-01', 'EX1', 60, 52, 41, 1767225600000000000);
INSERT INTO test.read_metric (PK, read_id, run_id, sample_id, target_id, mapping_quality, depth, gc_percent, called_at)
  VALUES ('r-2', 2, 'run-A', 's-01', 'EX2', 60, 48, 43, 1767225602000000000);
INSERT INTO test.read_metric (PK, read_id, run_id, sample_id, target_id, mapping_quality, depth, gc_percent, called_at)
  VALUES ('r-3', 3, 'run-A', 's-02', 'EX1', 60,  9, 39, 1767225603000000000);
INSERT INTO test.read_metric (PK, read_id, run_id, sample_id, target_id, mapping_quality, depth, gc_percent, called_at)
  VALUES ('r-4', 4, 'run-A', 's-02', 'EX1', 12, 40, 40, 1767225604000000000);
INSERT INTO test.read_metric (PK, read_id, run_id, sample_id, target_id, mapping_quality, depth, gc_percent, called_at)
  VALUES ('r-5', 5, 'run-A', 's-03', 'EX9', 60, 61, 55, 1767225605000000000);
```

`s-02` is the sample in trouble: depth 9, and its other read is MAPQ 12 and filtered out entirely, so
it shows one target touched where the others show two. `r-4` never reaches the engine.

For a realistic run:

```bash
python3 data/generate_reads.py --run run-A --samples s-01,s-02,s-03 --minutes 5 --degrade s-02 | \
  docker exec -i pravaha-aerospike aql
```

`--degrade s-02` makes that sample's coverage fall away after the first minute, which is the failure
you are trying to catch.

> **Windows close on data, not on the clock.** Insert a read with `called_at` past the end of the
> thirty-second window and the window publishes. The generator handles this; by hand, bump the
> timestamp.

## Step 5 — ask questions

### One sample

[`sql/02-read-one-sample.sql`](sql/02-read-one-sample.sql):

```sql
SELECT sample_id, panel, read_count, mean_depth, min_mapq, targets_touched
FROM coverage_qc
WHERE sample_id = ?
```

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    for row in client.query(open("sql/02-read-one-sample.sql").read(), ["s-02"]):
        print(row["sample_id"], "depth", row["mean_depth"], "targets", row["targets_touched"])
```

### Everything under a depth threshold — the alert

[`sql/04-read-undercovered.sql`](sql/04-read-undercovered.sql):

```sql
SELECT run_id, sample_id, mean_depth, targets_touched
FROM coverage_qc
WHERE mean_depth < ?
```

```python
undercovered = list(client.query(open("sql/04-read-undercovered.sql").read(), [30]))
if undercovered:
    print("stop the run:", [r["sample_id"] for r in undercovered])
```

Java:

```java
try (QueryResult result = client.query(sql, 30)) {
    for (Row row : result) {
        System.out.println("undercovered: " + row.getString("sample_id"));
    }
}
```

### Per panel

[`sql/03-read-panel-summary.sql`](sql/03-read-panel-summary.sql):

```sql
SELECT panel, COUNT(*) AS samples, SUM(read_count) AS read_count, MIN(min_mapq) AS worst_mapq
FROM coverage_qc
GROUP BY panel
```

## Step 6 — a manifest correction mid-run

Sample swaps happen, and the correction must not rewrite the run:

```sql
UPDATE test.sample_manifest SET panel = 'ONCO_V7' WHERE PK = 's-02';
```

Windows already closed keep `CARDIO_V3`. For a clinical audit trail, a QC record that silently
changed panel when somebody fixed a spreadsheet is not a record.

## Making this yours

| To change | Do this |
|---|---|
| QC cadence | `INTERVAL '30' SECOND` → whatever your run length makes sensible |
| MAPQ threshold | The `WHERE`. One place, and it is pushed to the store |
| Per-target rather than per-sample | Add `r.target_id` to the `GROUP BY`. State grows with targets × samples |
| True mean depth | `SUM(r.depth)` and `COUNT(*)`, divide in the client |
| Reads from files, not Aerospike | The filesystem plugin. The SQL does not move |

## Limits you will meet

Full list: [`docs/SQL_SUPPORT.md`](../../../docs/SQL_SUPPORT.md).

- **`AVG` over an integer truncates.** Described above, and the most likely thing to surprise you.
- **No `CASE`**, so no "percentage of bases over Q30" in one query. Register a second query filtered
  to the threshold and divide client-side.
- **No `ORDER BY` / `LIMIT`** — rank the worst samples in your application.
- **No `STDDEV` or percentiles.** `MIN`, `MAX`, `COUNT`, `SUM`, `AVG` and `COUNT(DISTINCT)` are what
  exists. Coverage uniformity needs the raw distribution, which is a batch question over the run.
- **No floating-point aggregates on `DECIMAL`.** Keep integers integers.
