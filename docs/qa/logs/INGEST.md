# INGEST — execution log

Executed 2026-09-12 against the pre-built artifacts
(`pravaha-server-0.1.0-SNAPSHOT-app.jar`, `pravaha-cli-0.1.0-SNAPSHOT-cli.jar`), Java 21.0.12,
branch `develop` at `2c287dd`. HTTP 18100, Flight 19100. No production code was changed.

Two drivers were used, both against the shipped jars:

* **`probe.sh`** — a QA client built only from the published SDK (`PravahaFlightClient`), so it is
  the CLI's own surface with one JVM start instead of one per command. Every operation it performs
  (`register`, `drop`, `pause`, `resume`, `queries`, `SELECT * FROM <view>`) is exactly what
  `bin/pravaha` does; it exists so that a 30 ms visibility measurement is not buried under a 2 s JVM
  start. Spot-checked against `bin/pravaha` and agrees (see INGEST-001/002).
* **`h.sh`** — an in-process harness on the shipped server classes, extracted from the app jar
  (`BOOT-INF/classes` + `BOOT-INF/lib/*`), driving `QueryRegistry` + `PluginSourceFeeds` directly.
  It exists for one reason: **`SourceFeed.describe()` is not exposed by any shipped surface**
  (INGEST-007), so the diagnostic the design relies on can only be read from inside the JVM.

---

### INGEST-001 — PASS

```
$ probe.sh register v_txn "SELECT id, usr, amount FROM txn" 0  sleep 1000  list
register v_txn -> RUNNING fp=8dcec656ef1c
  v_txn	RUNNING	8dcec656ef1c	rowsIn=20

$ bin/pravaha queries --url grpc://localhost:19100
NAME	STATE	FINGERPRINT	ROWS IN
v_txn	RUNNING	8dcec656ef1c	20
```

**Verdict:** The release's headline claim holds at the *arrival* boundary: a binding in a
configuration file causes rows to reach the engine. Exactly 20, matching `wc -l data/txn.csv`.
Not vacuous — 20 is the file's length, and a feed that opened and read nothing would report 0
(INGEST-014/015/016 all show 0 from the same counter).

### INGEST-002 — FAIL

```
$ bin/pravaha query --sql "SELECT * FROM v_txn" --url grpc://localhost:19100
id	usr	amount
1	u1	10
...
11	u1	110
11 rows

$ probe.sh count v_txn  sleep 15000  count v_txn  sleep 20000  count v_txn  rows v_txn
viewrows v_txn = 11
viewrows v_txn = 11
viewrows v_txn = 11
rowsIn v_txn = 20
```

**Verdict:** **FAIL — blocker.** 20 rows reached the engine; 11 are readable, and the other 9 never
become readable — 35 seconds of waiting changes nothing. The state the P0 was supposed to have fixed
("five thousand rows in and zero rows out") is still present, only partial instead of total, which
is worse: it looks like it works.

Root cause, caught in the harness with the stack trace intact:

```
$ h.sh txn "id:INT64,usr:STRING,amount:INT64" data/txn.csv "id:INT64,usr:STRING,amount:INT64" \
       "SELECT id, usr, amount FROM txn" 3000
registered, state=RUNNING
Exception in thread "pravaha-feed-v" java.util.ConcurrentModificationException
	at java.base/java.util.LinkedHashMap.forEach(LinkedHashMap.java:988)
	at com.ash.messaging.pravaha.serving.ServedView.commit(ServedView.java:192)
	at com.ash.messaging.pravaha.serving.ViewSink.commit(ViewSink.java:74)
	at com.ash.messaging.pravaha.registry.RegisteredQuery.commit(RegisteredQuery.java:299)
	at com.ash.messaging.pravaha.server.ingest.PumpingFeed.publishPeriodically(PumpingFeed.java:153)
	at com.ash.messaging.pravaha.server.ingest.PumpingFeed.run(PumpingFeed.java:126)
FINAL rowsIn=20 viewRows=6 state=RUNNING
describe(): reading txn (1 partition)
feed thread alive: NONE
```

This is **DEFECT-1**, and it is the single most important finding in this area. `ServedView` holds
plain `LinkedHashMap`/`HashMap` fields (`visible`, `pending`, `writtenAt`, `pendingTime`) and was
written to be touched by one thread. `PumpingFeed` introduced a second: its 20 ms publish timer
calls `query::commit` → `ServedView.commit`, which iterates `pending` **on the feed thread** while
the lane thread is concurrently `put`ting into it from `applyValues`. `LinkedHashMap.forEach`
rechecks `modCount` at the end even when the map is empty, so *any* row applied during a publish
tick throws.

Three things make it a blocker rather than a bug:

1. The throw is from `publishPeriodically()`, which in `PumpingFeed.run` sits **outside** the
   `try/catch` that was written to record feed failures. So `failure` is never set, `describe()`
   still cheerfully says `reading txn (1 partition)`, and the query stays `RUNNING`.
2. The feed thread dies. Ingestion stops permanently and silently.
3. The stack trace goes to the default uncaught-exception handler — raw stderr, not the logging
   framework — so it carries no timestamp, no logger name, no level, and a log collector scraping
   the application log will not classify it as an error.

Probability rises with input size. Twenty rows: 1 death in 5 runs. Five hundred thousand rows:
3 deaths in 3 runs, every one after the first 1024-row batch.

```
$ for i in 1 2 3 4 5; do h.sh txn ... data/txn.csv ... 1500; done      # 20-row file
FINAL rowsIn=20 viewRows=20 state=RUNNING
FINAL rowsIn=20 viewRows=20 state=RUNNING
FINAL rowsIn=20 viewRows=20 state=RUNNING
FINAL rowsIn=20 viewRows=20 state=RUNNING
ConcurrentModificationException ... FINAL rowsIn=20 viewRows=2 state=RUNNING

$ for i in 1 2 3; do h.sh big ... data/big.csv ... 8000; done          # 500 000-row file
ConcurrentModificationException ... FINAL rowsIn=1024 viewRows=1024 state=RUNNING
ConcurrentModificationException ... FINAL rowsIn=1024 viewRows=593  state=RUNNING
ConcurrentModificationException ... FINAL rowsIn=1024 viewRows=1024 state=RUNNING
```

### INGEST-003 — PASS

```
$ probe.sh count v_tail count v_tail  latency v_lat "SELECT id, usr, amount FROM txn" 0 v_lat 20
  t=+28.7ms rows=20
register took 29.2ms; 20 rows visible 30.3ms after register returned
```

**Verdict:** Visibility latency is ~30 ms — one 20 ms publish interval plus a Flight round trip,
exactly what `PUBLISH_INTERVAL_NANOS` predicts. Two warm-up queries were issued first so the
measurement is not a first-call artefact. Non-vacuous: the loop polls until the count *reaches* 20
and reports the first sample where it does, so a view that was already full would have printed
`t=+0.0ms`, and one that never filled prints `NEVER` (which is precisely what INGEST-004 printed).

This run is also the clearest evidence that DEFECT-1 is a race and not a deterministic ceiling: the
same 20-row file that stalled at 11 rows in INGEST-002 reached all 20 here.

### INGEST-004 — FAIL

```
$ probe.sh latency v_tail "SELECT id, usr, amount FROM tail100" 0 v_tail 100  sleep 3000  count v_tail  rows v_tail
  t=+400.1ms rows=8
register took 923.7ms; 100 rows visible NEVER within 10s
viewrows v_tail = 8
rowsIn v_tail = 100
```

**Verdict:** **FAIL — blocker (same cause as INGEST-002).** The whole 100-line file was read —
`rowsIn=100` — and 8 rows are visible. Not 96 (a batch boundary), not 99 (an off-by-one); 8, which
is wherever the lane happened to be when the publish tick collided with it. The failure mode
`publishPeriodically`'s comment was written to prevent — "a fully-read file stays invisible" — is
back, by a different route: the timer is correct and the thread running it is dead.

### INGEST-005 — PASS

```
$ h.sh txn ... "SELECT id, usr, amount FROM txn" 3000
describe(): reading txn (1 partition)
```

**Verdict:** The description names the stream and gets the singular/plural right. Non-vacuous: the
same call returns different text for an unbound stream (INGEST-006) and appends the failure reason
when one exists (INGEST-016).

### INGEST-006 — PASS

```
$ probe.sh register v_unbound "SELECT id, usr, amount FROM unbound" 0  sleep 1500  list
register v_unbound -> RUNNING fp=9c8689d4e523
  v_unbound	RUNNING	9c8689d4e523	rowsIn=0

$ h.sh unbound ... NONE ... "SELECT id, usr, amount FROM unbound" 1000
FINAL rowsIn=0 viewRows=0 state=RUNNING
describe(): no source is bound to this query's streams
feed thread alive: NONE
```

**Verdict:** The documented contract holds exactly. A stream declared under `pravaha.streams` with
no `pravaha.sources` entry registers, runs, returns `SourceFeed.NONE`, starts no thread, and says
what it is. Non-vacuous: `unbound` is a real declared stream in the same config as nineteen bound
ones, and the same server registered `txn` at 20 rows in the same run.

### INGEST-007 — FAIL

```
$ probe.sh list                      # the Flight LIST action, which is what `pravaha queries` prints
  v_ragged	RUNNING	d9683c2a2421	rowsIn=0      # feed died on a malformed line
  v_unb	    RUNNING	9c8689d4e523	rowsIn=0      # nothing bound at all

$ curl -s http://localhost:18100/api/v1/status
{"instanceId":"qa-ingest","version":"0.1.0-SNAPSHOT","engineState":"RUNNING",
 "uptimeSeconds":45,"registeredQueries":20,"plugins":[]}

$ curl -s http://localhost:18100/status | grep -ci 'no source is bound\|reading '
0

$ grep -rn 'describe()' --include=*.java . | grep -v /test/ | grep -i feed
pravaha-registry/.../SourceFeed.java:75:    String describe();
pravaha-server/.../PumpingFeed.java:173:    public String describe() {
$ grep -rn '\.feed()' --include=*.java .
pravaha-server/src/test/java/.../PluginSourceFeedsTest.java:72,91,93
```

**Verdict:** **FAIL.** `RegisteredQuery.feed()` is called from exactly one place in the repository:
a unit test. `describe()` reaches no Flight action, no REST endpoint, no log line and no CLI column.
The two rows above are indistinguishable to an operator — `RUNNING`, `rowsIn=0` — and they are the
two situations `describe()` was written to tell apart. The `RegisteredQueryInfo` record the SDK
returns is `(name, state, sql, fingerprint, rowsIn)`; there is nowhere for the answer to go.

Two smaller defects found in the same sweep:

* `/api/v1/status` reports `"registeredQueries": 20` when **two** queries are registered and
  **twenty** streams are declared. `StatusController.status()` passes `catalog.size()` — the stream
  catalog — into the `registeredQueries` field of `NodeStatus`. The HTML page at `/status` labels
  the same number "Streams", correctly; the JSON field name, which is the contract clients code
  against, is wrong. (**DEFECT-6**)
* `"plugins": []`, and `/status` renders "No plugins registered", on a node with the filesystem
  plugin loaded and nineteen bindings configured. `engine.plugins()` is a different registry that
  source bindings never populate, so the one page designed to be readable "on a machine that may
  have nothing else working" cannot tell an operator which source plugins exist. (**DEFECT-7**)

### INGEST-008 — PASS

```
$ probe.sh register v_p "SELECT id, usr, amount FROM big WHERE id > 999999998" 0 \
           pause v_p  samples v_p 5 3000
register v_p -> RUNNING fp=a37e1bba5782
pause v_p -> ok
  sample 0 t=0ms     rowsIn=33792
  sample 1 t=3000ms  rowsIn=33792
  sample 2 t=6000ms  rowsIn=33792
  sample 3 t=9000ms  rowsIn=33792
  sample 4 t=12000ms rowsIn=33792
```

**Verdict:** Pause genuinely stops rows arriving. Frozen at 33 792 of 500 000 for twelve seconds.
Emphatically not vacuous: the identical query left unpaused reaches 500 000 in under one second
(INGEST-029's control run), so had pause only stopped rows being *accepted* while the feed kept
pumping, the counter — which is `feed.rowsFed()` — would have run to 500 000 inside sample 0.

**Method note.** These large-source cases use a predicate that matches nothing
(`WHERE id > 999999998`). That is deliberate: it makes the lane consume every input row while
writing no output row, which keeps `ServedView.pending` untouched and so sidesteps DEFECT-1. Without
that trick the feed dies after one batch and every pause/backpressure/lifecycle case below would
pass vacuously against a corpse. It is stated here because it is the reason these cases could be run
at all.

### INGEST-009 — PASS

```
$ probe.sh resume v_p  waitstable v_p 1500 30000  rows v_p
resume v_p -> ok
stable v_p rowsIn=500000 after 1765ms
rowsIn v_p = 500000
```

**Verdict:** Exactly 500 000 — the file's length. A gap would give fewer, a replay from the start
would give more than 500 000 (33 792 already counted plus a full re-read). Resume picks up precisely
where the pause left off.

### INGEST-010 — PASS

```
$ probe.sh register v_a "SELECT id, usr, amount FROM txn" 0 \
           register v_b "SELECT id, usr, amount FROM txn" 0  sleep 1500  list
register v_a -> RUNNING fp=8dcec656ef1c
register v_b -> RUNNING fp=8dcec656ef1c
  v_lat	RUNNING	8dcec656ef1c	rowsIn=20
  v_a	RUNNING	8dcec656ef1c	rowsIn=20
  v_b	RUNNING	8dcec656ef1c	rowsIn=20
```

**Verdict:** Three names, one fingerprint, one counter reading 20. Not 40, not 60. The registry's
early return on a matching fingerprint means `PluginSourceFeeds.open` is never reached a second
time, which is the mechanism the design claims. Non-vacuous: `v_diff`, a genuinely different plan
over the same stream, got its own fingerprint and its own 20 in the same run (INGEST-038).

**But** the second and third names are not usable:

```
$ bin/pravaha query --sql "SELECT * FROM v_a" --url grpc://localhost:19100
PRV-1041  PRV-2002  Object 'v_a' not found. Known streams: [v_tail, v_lat]
```

`QueryRegistry.register` calls `views.register(query.view())` only on the path that creates a new
computation; the shared path calls `existing.addName(name)` and returns. So the client is told
`registered v_a  state=RUNNING`, and `SELECT * FROM v_a` reports that no such object exists. The
answer is only reachable under whichever name happened to register first — which the second caller
has no way to learn, and which may belong to another tenant. The CLI even prints "a query with the
same fingerprint is the same computation, shared" as if this were the intended outcome.
(**DEFECT-5**)

### INGEST-011 — PASS

```
$ jcmd <pid> Thread.print | grep '^"pravaha'
"pravaha-metrics" ...
"pravaha-query-0" #64 ...
"pravaha-query-0" #69 ...
"pravaha-feed-v_lat" #70 ...
$ ls -l /proc/<pid>/fd | grep -c txn.csv
1
```

**Verdict:** One feed thread and one open descriptor on the CSV for the three shared names
(`v_lat`, `v_a`, `v_b`). The other lane thread belongs to `v_tail`, a different fingerprint. Second
file handle: none. Non-vacuous — the same measurement returned 2 threads and 2 descriptors once
`v_diff` (a different fingerprint over the same file) was registered.

### INGEST-012 — PASS

```
$ probe.sh register v_sj "SELECT a.id, a.usr, b.amount FROM txn a JOIN txn b ON a.id = b.id" 0
register v_sj -> ERROR PRV-1041  stream 'txn' appears on both sides of this plan;
                 self-joins are not supported yet
```

**Verdict:** No double-feed is possible because the planner refuses the query before ingestion is
reached. The `.distinct()` guard in `PluginSourceFeeds.open` is correct and currently unreachable
through SQL — worth knowing, because it means the guard is protected by nothing but that planner
refusal, and will be exercised for real the day self-joins land.

### INGEST-013 — PASS

```
$ probe.sh drop v_a  sleep 800  list  count v_lat
drop v_a -> ok in 4.1ms
  v_lat	RUNNING	8dcec656ef1c	rowsIn=20
  v_b	RUNNING	8dcec656ef1c	rowsIn=20
  v_diff	RUNNING	77206052c4aa	rowsIn=20
viewrows v_lat = 20
$ jcmd <pid> Thread.print | grep '^"pravaha-feed'
"pravaha-feed-v_lat" ...
"pravaha-feed-v_diff" ...
```

**Verdict:** The refcount works. Dropping one of three shared names leaves the computation, its feed
thread and its view intact and still answering with all 20 rows.

### INGEST-014 — PASS

```
$ probe.sh register v_noplug "SELECT id, usr, amount FROM noplug" 0
register v_noplug -> ERROR PRV-1041  PRV-5090  no source plugin named 'kafka' is on the
  classpath, so stream 'noplug' cannot be fed. Available: [filesystem]
$ probe.sh list     # v_noplug absent
```

**Verdict:** Right code, names the plugin asked for, lists what is actually there, and leaves no
half-registered name behind. This is the message an operator can act on, and it is also the
empirical half of INGEST-034.

### INGEST-015 — PASS

```
$ probe.sh register v_nopath "SELECT id, usr, amount FROM nopath" 0
register v_nopath -> ERROR PRV-1041  PRV-5091  the 'filesystem' plugin could not be opened for
  stream 'nopath': com.ash.messaging.pravaha.api.ConfigurationException: PRV-5040  plugin
  'nopath' cannot read /.../qa-ingest/data/does-not-exist.csv
```

**Verdict:** PRV-5091 as designed, distinct from 5090, with the absolute path in the text. The name
is not registered. The nested `ConfigurationException:` prefix and doubled error codes make it
wordier than it needs to be, but nothing actionable is missing.

### INGEST-016 — FAIL

```
$ probe.sh register v_dirpath "SELECT id, usr, amount FROM dirpath" 0  sleep 1500  list
register v_dirpath -> RUNNING fp=3af572e078ba
  v_dirpath	RUNNING	3af572e078ba	rowsIn=0

$ h.sh dirpath ... data/adir ... "SELECT id, usr, amount FROM dirpath" 2000
FINAL rowsIn=0 viewRows=0 state=RUNNING
describe(): reading dirpath (1 partition) -- stopped: PRV-5040  read failed at line 0
feed thread alive: NONE

$ grep -cE 'Exception|ERROR' logs/main.log
0
```

**Verdict:** **FAIL.** `FilesystemSourcePlugin.open()` checks only `Files.isReadable`, which is true
of a directory, so registration succeeds. The first `readLine` then fails, `PumpingFeed` records the
failure and the thread exits. The query sits at `RUNNING`, `rowsIn=0`, forever. Nothing is logged —
zero exception or error lines in the whole server log for this. The one place the reason exists is
`describe()`, which INGEST-007 shows no operator can read; and even that reason —
`PRV-5040 read failed at line 0` — mentions neither the path nor the fact that it is a directory.
Two cheap fixes: `Files.isRegularFile` in `open()`, and `IOException`'s own message in the 5040 text.

### INGEST-017 — PASS

```
$ probe.sh register v_empty "SELECT id, usr, amount FROM \"empty\"" 0  sleep 1200  rows v_empty
register v_empty -> RUNNING fp=acb31c7ee205
rowsIn v_empty = 0
$ probe.sh list        # same server, same moment
  v_ctl	RUNNING	8dcec656ef1c	rowsIn=20
```

**Verdict:** A zero-byte file registers, feeds nothing and reports no failure — `describe()` in the
harness stayed `reading emptyf (1 partition)` with the feed thread still alive, so this is a source
that ran dry rather than one that broke. **Proof it is not vacuous:** `v_ctl`, a query over the
20-row `txn` file, was registered in the same server process and read 20 at the same moment, so the
machinery was demonstrably working while `empty` reported 0.

Incidental: a stream named `empty` is unreachable in SQL without double quotes
(`PRV-2001 Encountered "empty" at line 1, column 29`). Nothing rejects or warns about a reserved
word as a stream name at declaration time, which turns a config typo into a parser error much later.

### INGEST-018 — PASS

```
$ probe.sh list
  v_hdronly	RUNNING	7e7515e458d1	rowsIn=0
$ h.sh hdronly ... data/hdronly.csv ... skip.header=true
describe(): reading hdronly (1 partition)        # no failure appended
feed thread alive: pravaha-feed-v=true
```

**Verdict:** A one-line header-only file yields zero rows and no failure. This is a strong test
rather than a vacuous one: had the header not been skipped, `id,usr,amount` would have been decoded
against `id:INT64,...` and produced exactly the `'id' is not a number` failure seen in INGEST-022.
Silence here proves the skip happened.

### INGEST-019 — PASS

```
$ probe.sh register v_hdr "SELECT id, usr, amount FROM hdr" 0  sleep 2500  list  dump v_hdr 12
  v_hdr	RUNNING	ecefb983d967	rowsIn=10
  1	u1	1
  2	u2	2
  ...
  10	u10	10
  (10 rows total)
```

**Verdict:** Exactly one line skipped. 10 rows from an 11-line file, ids 1..10 contiguous, no row
whose `usr` is the literal string `usr`. Eating a data row would give 9; feeding the header would
give 11 or a decode failure.

### INGEST-020 — FAIL

```
$ probe.sh register v_ragged "SELECT id, usr, amount FROM ragged" 0  sleep 2500  list  dump v_ragged 5
  v_ragged	RUNNING	d9683c2a2421	rowsIn=0
  (0 rows total)
  v_ctl	RUNNING	8dcec656ef1c	rowsIn=20          # control, same server, same moment

$ h.sh ragged ... data/ragged.csv ... 2000
FINAL rowsIn=0 viewRows=0 state=RUNNING
describe(): reading ragged (1 partition) -- stopped: PRV-5092  the source feed for 'v' stopped:
  java.lang.UnsupportedOperationException: a plugin aborted a row mid-write, which the ingest
  path cannot yet undo: the claimed inbox cell would stay unpublished and stall this lane.
  Report this -- it needs a cancel path on RowInbox, not a workaround here.
feed thread alive: NONE
```

The file is `1,a,10` / `2,b` / `3,c,30` / `4,d,40` — one short line, two good lines after it.

**Verdict:** **FAIL — severe.** Three separate faults, and the case was written to catch the first:

1. **One malformed line ends ingestion for the query, permanently.** `FilesystemPartitionReader.poll`
   carries the comment "One malformed line must not cost the batch. The engine's DLQ handles the
   record; the reader's job is to keep going" and then executes `throw e`. `ConfigurationException`
   extends `PravahaException`, `PumpingFeed.run` catches it, records `failure` and returns. There is
   no DLQ on this path. Rows 3 and 4 are never read.
2. **The rows before the bad line are lost too.** `rowsIn=0`, not 1. Row 1 was decoded and published
   into the lane's inbox, but the exception propagates out of `reader.poll(...)` before
   `IngestPump.pumpOnce` can add to `rowsPumped`, and the feed dies before any publish tick, so the
   row is applied and never committed. A single bad line on line 2 of a million-line file discards
   everything.
3. **The diagnostic is destroyed.** The real error — `line 2 has 2 fields but schema 'ragged' has 3`
   — never reaches anyone. The reader's `catch` block calls `writer.abort()` first, `DelegatingRowWriter.abort()`
   throws `UnsupportedOperationException`, and *that* is what propagates. What survives is an
   internal note telling the operator to "Report this", about a `RowInbox` cancel path, for what is
   in fact a routine bad CSV line. (**DEFECT-3**)

Not vacuous: `v_ctl` read 20 rows from the same server at the same moment, and `v_blanks`
(INGEST-023) read 3 from a file of the same size.

### INGEST-021 — FAIL

```
  v_wide	RUNNING	218bf54a2409	rowsIn=0
describe(): reading wide (1 partition) -- stopped: PRV-5092 ... UnsupportedOperationException:
  a plugin aborted a row mid-write ...
feed thread alive: NONE
```

File: `1,a,10` / `2,b,20,EXTRA` / `3,c,30` / `4,d,40`.

**Verdict:** **FAIL — identical to INGEST-020 in both directions of the field-count check.** Zero
rows out of four; rows 3 and 4 never read.

### INGEST-022 — FAIL

```
  v_badtype	RUNNING	b7037bddba06	rowsIn=0
describe(): reading badtype (1 partition) -- stopped: PRV-5092 ... UnsupportedOperationException:
  a plugin aborted a row mid-write ...
```

File: `1,a,10` / `2,b,notanumber` / `3,c,30` / `4,d,40`.

**Verdict:** **FAIL, and this is the one that will be hit in production.** A letter in a numeric
column is the commonest real malformation there is. `DelimitedCodec.setField` builds a genuinely
good message — line number, column name, declared type, and the offending text — and none of it
survives `abort()`. The operator gets "Report this", zero rows, and a query reporting `RUNNING`.

### INGEST-023 — PASS

```
  v_blanks	RUNNING	2e2f944a6dde	rowsIn=3
$ probe.sh dump v_blanks 5
  1	a	10
  2	b	20
  3	c	30
  (3 rows total)
$ h.sh blanks ...
describe(): reading blanks (1 partition)
feed thread alive: pravaha-feed-v=true
```

File: 3 data rows with 3 blank lines interleaved.

**Verdict:** Blank lines are skipped, produce no row and no failure, and the feed survives. Given
INGEST-020/021/022, this is the one that matters most for ordinary files: a trailing newline does
not kill a feed. Non-vacuous — 3 rows is the number of data lines, and had blanks been decoded the
feed would have died with a field-count failure like `ragged`.

### INGEST-024 — FAIL

```
  v_nullcol	RUNNING	c03de7a236e8	rowsIn=0
describe(): reading nullcol (1 partition) -- stopped: PRV-5092 ... UnsupportedOperationException ...
feed thread alive: NONE
```

File: `1,a,10` / `2,,20` / `3,c,30`, schema `usr:STRING` (not nullable).

**Verdict:** The *refusal* is right — an empty field is not silently written into a NOT NULL column,
which is what this case was chiefly guarding against. But it is enforced by killing the feed:
0 rows of 3, row 1 lost, row 3 never read, and the honest message
(`line 2 has null in NOT NULL column 'usr'`) replaced by the `abort()` `UnsupportedOperationException`.
**FAIL** on the same three counts as INGEST-020.

### INGEST-025 — PASS

```
  v_nullok	RUNNING	8fdd7ee8d700	rowsIn=3
$ probe.sh dump v_nullok 5
  1	a	10
  2	null	20
  3	c	30
  (3 rows total)
```

**Verdict:** Same file as INGEST-024, schema `usr:STRING?`. Three rows, row 2's `usr` is NULL. This
is the positive control that makes INGEST-024 meaningful: the refusal there is about nullability,
not about empty fields in general, and the `null.literal` default of the empty string works.

### INGEST-026 — FAIL

```
$ probe.sh register v_mm "SELECT id, usr, amount FROM mismatch" 0  sleep 2500  list  dump v_mm 10
register v_mm -> RUNNING fp=fd7cf4110e4b
  v_mm	RUNNING	fd7cf4110e4b	rowsIn=0
  (0 rows total)

$ h.sh mismatch "id:INT64,usr:STRING,amount:INT64" data/mismatch.csv \
       "id:INT64,usr:STRING,amount:INT64,extra:STRING" ...
describe(): reading mismatch (1 partition) -- stopped: PRV-5092 ... UnsupportedOperationException:
  a plugin aborted a row mid-write ...
```

Catalog stream: 3 columns. Plugin `schema` option: 4 columns. File: 4 fields per line.

**Verdict:** **FAIL.** The good news first, because it is the one that could have been catastrophic:
there is **no memory corruption**. `IngestPump` builds its `RowLayout` from the catalog schema while
`DelimitedCodec` decodes with the plugin's, and the write of ordinal 3 into a three-column layout is
caught by the row writer's bounds check. Zero rows reached the view; nothing garbage was served.

The failure is everything around it. **Nothing validates that the two schemas agree** — not
`SourceBindingProperties`, not `PluginSourceFeeds.open`, not registration. A configuration mistake
that is fully detectable at startup is instead detected on the first row, at which point it presents
as a dead feed, a `RUNNING` query, `rowsIn=0`, and an `UnsupportedOperationException` telling the
operator to file a bug. `PluginSourceFeeds` already reads both `pravaha.streams` and
`pravaha.sources`; comparing them at bind time is the obvious place. (**DEFECT-4**)

### INGEST-027 — FAIL

```
$ probe.sh register v_tm "SELECT id, usr, amount FROM typemix" 0  sleep 2500  list  dump v_tm 10
  v_tm	RUNNING	7dc7c1c8ce90	rowsIn=0
  (0 rows total)
```

Three variants, all with matching column counts and disagreeing types:

| catalog | plugin option | result |
|---|---|---|
| `id:INT64,usr:STRING,amount:INT64` | `id:STRING,usr:STRING,amount:STRING` | 0 rows, feed dead, `UnsupportedOperationException` |
| `id:INT64,usr:STRING,amount:INT64` | `id:INT32,usr:STRING,amount:INT64` | 0 rows, feed dead, `UnsupportedOperationException` |
| `id:INT64,usr:STRING,amount:INT64` | `id:INT64,usr:STRING` (2 cols, 2-field file) | 0 rows, feed dead, `UnsupportedOperationException` |

**Verdict:** **FAIL, but the severe outcome this case was written to hunt did not occur.** I could
not produce a type-confused read: every disagreement I constructed, including the narrowing
`INT32`-into-`INT64` case that had the best chance of passing the writer silently, was refused at
the row writer. **No wrong value was ever served.** That is the important negative result.

What fails is the same as INGEST-026 — no validation, a dead feed, a healthy-looking query, and a
diagnostic replaced by an internal one. The two schemas are written by hand in two adjacent blocks
of the same YAML file, so this is a configuration mistake an operator will make.

### INGEST-028 — PASS

```
$ probe.sh register v_gone "SELECT id, usr, amount FROM gone WHERE id > 999999999" 0  pause v_gone
$ rm -f data/gone.csv
$ ls data/gone.csv
ls: cannot access 'data/gone.csv': No such file or directory
$ probe.sh rows v_gone  resume v_gone  waitstable v_gone 2000 40000  rows v_gone
rowsIn v_gone = 38912
resume v_gone -> ok
stable v_gone rowsIn=200000 after 2145ms
rowsIn v_gone = 200000
```

**Verdict:** The file was unlinked with the feed paused at 38 912 of 200 000 rows; on resume the
reader read the remaining 161 088 rows from its surviving descriptor and finished at exactly
200 000. POSIX behaviour, and the right one for a reader mid-file — no error, no partial read, no
truncation. Worth documenting in the operator notes, because it also means **an operator cannot
reclaim disk by deleting a file a query is reading**; the space is held until the query is dropped.
Non-vacuous: the deletion is proven by `ls`, and the pause is proven by the 38 912 mid-file sample.

### INGEST-029 — FAIL

```
$ probe.sh register v_bigv "SELECT id, usr, amount FROM big WHERE id < 999999999" 0 \
           waitstable v_bigv 2000 40000  count v_bigv
register v_bigv -> RUNNING fp=f0fe88d852d9
stable v_bigv rowsIn=5123 after 2035ms
viewrows v_bigv = 5123
$ grep -c ConcurrentModification logs/main.log
1
```

**Verdict:** **FAIL — blocker, and the most damaging presentation of DEFECT-1.** 5 123 rows of
500 000 were ingested; the feed then died of the `ConcurrentModificationException` and the query has
reported `RUNNING` ever since. A user reading this view sees 1% of their data and nothing anywhere
says so.

The control that isolates the cause is the *same file, same server, same 500 000 rows*, with a
predicate that writes nothing to the view:

```
$ probe.sh register v_big "SELECT id, usr, amount FROM big WHERE id > 999999999" 0  samples v_big 12 1000
  sample 0 t=0ms     rowsIn=8192
  sample 1 t=1000ms  rowsIn=500000
  ... (stable through t=11000ms)
```

All 500 000 rows in under a second. The ingest path is fast and complete; it is the act of making
rows *visible* that kills it. That is exactly the distinction the brief asked to be probed.

### INGEST-030 — PASS (with a caveat)

```
$ probe.sh register v_big "SELECT id, usr, amount FROM big WHERE id > 999999999" 0  samples v_big 12 1000
  sample 1 t=1000ms rowsIn=500000     # ... stable to t=11000ms
$ jcmd <pid> GC.heap_info
 garbage-first heap   total 573440K, used 306090K
```

**Verdict:** 500 000 rows in under one second with no OutOfMemory, no runaway heap, no dropped rows
and no `PRV-…BACKPRESSURED` escaping to the feed. Nothing fell over.

**Caveat, and it is a real limit on this verdict: I could not build a consumer slow enough to make
the source outrun it.** The lane kept up with the file at every point, so I never observed
`IngestPump` engaging the high watermark. Two things blocked a stronger test. A stateful query is
the natural way to slow a lane, and an unwindowed aggregate is refused by design
(`PRV-2050 GROUP BY usr has no bound on its key space`) while a windowed one needs an event-time
column these streams do not have. Any query that *does* write to the view dies of DEFECT-1 within a
batch. And `IngestPump` exposes `pauseCount()`, `resumeCount()` and `pausedNanos()` — the design's
named capacity-planning signal, `backpressure.ratio` — through no surface at all, so even when
backpressure does engage there is nothing to observe it with. **Backpressure under genuine overload
is untested, and currently unobservable in a running server.**

### INGEST-031 — PASS

```
$ probe.sh register v_dropload "SELECT id, usr, amount FROM big WHERE id > 999999997" 0 \
           rows v_dropload  drop v_dropload  sleep 500  list
register v_dropload -> RUNNING fp=9ceb3442d0f6
rowsIn v_dropload = 52224
drop v_dropload -> ok in 9.3ms
  (v_dropload absent from the listing)
$ jcmd <pid> Thread.print | grep pravaha-feed-v_dropload      # nothing
$ grep -cE 'Exception|ERROR' logs/main.log
1                                                             # the pre-existing CME, not the drop
```

**Verdict:** Dropped with the feed demonstrably mid-file — 52 224 of 500 000 rows read — and the
call returned in 9.3 ms, three orders of magnitude inside the 5 s join budget in `PumpingFeed.close`.
Feed thread gone, lane thread gone, descriptor released, nothing thrown. Non-vacuous: the 52 224
sample is the proof the feed was actively pumping and not already finished.

### INGEST-032 — PASS

```
BASELINE  feed threads: 0   lane threads: 0   fds on txn.csv: 0
register r1 -> RUNNING fp=0fab69cd580c ; rowsIn r1 = 20 ; drop r1 -> ok in 8.6ms
register r2 -> RUNNING fp=247dd2ef8b29 ; rowsIn r2 = 20 ; drop r2 -> ok in 3.6ms
... (r3..r10, each fingerprint distinct, each rowsIn = 20, each drop 2.6-3.9ms)
AFTER     feed threads: 0   lane threads: 0   fds on txn.csv: 0
```

**Verdict:** Ten register/drop cycles leak nothing — no thread, no descriptor. Each iteration used a
distinct predicate so each got its own fingerprint, its own feed and its own reader rather than
sharing the previous one. **Proof it is not vacuously passing:** every iteration reports
`rowsIn = 20`, so each cycle really opened, read a whole file and closed; a loop that had quietly
stopped feeding would also have leaked nothing.

### INGEST-033 — PASS

```
$ probe.sh register t1 ... FROM txn ...   register t2 ... FROM tail100 ...
           register t3 ... FROM hdr ...   register t4 ... FROM unbound ...
$ jcmd <pid> Thread.print | grep '^"pravaha-feed'
"pravaha-feed-t1" #87 [4189432] daemon ... waiting on condition
"pravaha-feed-t2" #89 [4189434] daemon ... runnable
"pravaha-feed-t3" #91 [4189438] daemon ... waiting on condition
$ probe.sh drop t1 drop t2 drop t3 drop t4
drop t1 -> ok in 183.9ms ; drop t2 -> 3.7ms ; drop t3 -> 3.3ms ; drop t4 -> 2.7ms
$ jcmd <pid> Thread.print | grep -cE '^"pravaha-feed|^"pravaha-query'
0
```

**Verdict:** Feed threads are named `pravaha-feed-<queryName>` as designed, one per bound
computation, and all are gone after the drops along with their lane threads. `t4` over the unbound
stream correctly has no feed thread at all — three threads for four queries, which is the positive
discrimination that makes the count meaningful.

One observation for the record: a feed thread that has died of DEFECT-1 is also absent from this
listing, so **thread count cannot be used to detect a dead feed** — a dead feed and a clean drop look
identical here. The descriptor does stay open until the query is dropped.

### INGEST-034 — FAIL (documentation)

```
$ unzip -l pravaha-server-0.1.0-SNAPSHOT-app.jar | grep 'BOOT-INF/lib/pravaha-plugin'
    18748  BOOT-INF/lib/pravaha-plugin-filesystem-0.1.0-SNAPSHOT.jar
$ ls plugins
pravaha-cluster-zookeeper  pravaha-plugin-aerospike  pravaha-plugin-delta
pravaha-plugin-feedfile    pravaha-plugin-filesystem pravaha-plugin-jdbc
$ grep -n 'plugin-' pravaha-server/pom.xml
97:      <artifactId>pravaha-plugin-filesystem</artifactId>
$ probe.sh register v_feedfile "SELECT id, usr, amount FROM feedfile" 0
register v_feedfile -> ERROR PRV-1041  PRV-5090  no source plugin named 'feedfile' is on the
  classpath, so stream 'feedfile' cannot be fed. Available: [filesystem]
$ unzip -p ...app.jar META-INF/MANIFEST.MF | grep Main-Class
Main-Class: org.springframework.boot.loader.launch.JarLauncher
```

**Verdict:** **FAIL — documentation, honestly reported as the brief asks.** The shipped server can
read exactly one kind of source: a delimited file. `feedfile`, `jdbc`, `delta` and `aerospike` build
in this repository and are on nobody's server classpath. Two pieces of shipped text say otherwise:

* `application.yaml`, the file an operator configures from: *"The plugin name is the one the plugin
  reports for itself, found on the classpath by ServiceLoader: filesystem, feedfile, jdbc and delta
  ship in this repository."* Literally true about the repository, and read as a menu.
* `PluginSourceFeeds`' class javadoc: *"adding Delta or JDBC to a deployment is dropping a jar in,
  not rebuilding the server."*

The second is not achievable with this artefact. The jar launches through `JarLauncher`, not
`PropertiesLauncher`, so `loader.path` is not honoured; there is no plugins directory, no
`-cp` extension point (it is `java -jar`), and no documented procedure anywhere in `docs/`.
The only way to add a source plugin today is to add a dependency to `pravaha-server/pom.xml` and
rebuild. The runtime error message is excellent — `Available: [filesystem]` tells the truth
immediately — which is the only reason this is a documentation defect and not a support incident.
(**DEFECT-8**)

### INGEST-035 — PASS

```
# conf/orphan.yaml declares only pravaha.streams.txn, but binds both txn and orphan
$ grep -E 'streams declared|sources bound' logs/orphan.log
streams declared in configuration: [txn]
sources bound: [txn <- filesystem[schema, path], orphan <- filesystem[schema, path]]
$ probe.sh register v_orphan "SELECT id, usr, amount FROM orphan" 0 \
           register v_ctl "SELECT id, usr, amount FROM txn WHERE id > 9999" 0  sleep 1000  list
register v_orphan -> ERROR PRV-1041  PRV-2002  Object 'orphan' not found. Known streams: [txn]
register v_ctl -> RUNNING fp=71e18b5ac679
  v_ctl	RUNNING	71e18b5ac679	rowsIn=20
```

**Verdict:** The node starts, the binding is honoured as data, and the registration is refused with
a message that names the stream and lists what is known — the behaviour
`SourceBindingProperties`' javadoc describes as the fix for a baffling error. Non-vacuous: `txn`,
declared *and* bound in the same config, registered and read its 20 rows.

Improvement worth logging: nothing warns at startup that `orphan` is bound to a source and declared
nowhere. Both lists are logged adjacently so a careful operator can diff them by eye, but a one-line
warning naming the difference is nearly free and would turn a registration-time surprise into a
startup-time one.

### INGEST-036 — PASS

```
$ start.sh main          # config contains plugin 'kafka', a missing path and a directory path
UP after 26s
$ grep -E 'streams declared|sources bound' logs/main.log
streams declared in configuration: [txn, tail100, empty, hdronly, hdr, ragged, wide, badtype,
  blanks, nullcol, gone, big, mismatch, typemix, dirpath, nopath, noplug, feedfile, unbound, nullok]
sources bound: [noplug <- kafka[path, schema], nopath <- filesystem[path, schema], ...]
```

**Verdict:** Nineteen bindings including three that cannot possibly work, and the node starts
cleanly and serves the other sixteen. Bindings are resolved lazily at registration, which is the
right trade: the opposite policy makes one typo an outage.

The trade-off is worth recording rather than just approving. Nothing at all is validated at startup
— not the plugin name, not the path, not the catalog-vs-plugin schema agreement of INGEST-026/027.
A `noplug`-style typo is discovered by the first client who registers against it, possibly weeks
later. A non-fatal startup *probe* — resolve each plugin name through `ServiceLoader` and log a
warning for the ones that do not exist — would keep the lazy semantics and remove most of the delay.

### INGEST-037 — FAIL

```
$ probe.sh register v_ragged ... register v_unb ...  sleep 2000  list  count v_ragged  count v_unb
  v_ragged	RUNNING	d9683c2a2421	rowsIn=0
  v_unb	    RUNNING	9c8689d4e523	rowsIn=0
viewrows v_ragged = 0
viewrows v_unb = 0
$ grep -cE 'Exception|ERROR|WARN.*feed' logs/main.log
0
```

**Verdict:** **FAIL.** The counting half works — `rowsIn` freezes, sampled twice — but the reporting
half does not exist. In this one listing, `v_ragged` (feed killed by a malformed line on line 2) and
`v_unb` (nothing bound at all) are character-for-character identical, and a third case, a bound
source that is merely quiet, would print the same again. The server log contains **zero** lines about
any of the four feeds that died during this session.

`PumpingFeed` does the right thing internally: it records `failure` and appends it to `describe()`.
That string is unreachable (INGEST-007). "A query that looks healthy, reports RUNNING, and has
silently stopped ingesting" is the outcome this case named as the worst one, and it is the outcome.
Note also that the DEFECT-1 death does not even record `failure`, because the throw comes from
outside `run()`'s try block — so for the most likely failure of all, `describe()` would still say
`reading txn (1 partition)` even if somebody could read it.

### INGEST-038 — PASS

```
$ probe.sh register v_diff "SELECT id, amount FROM txn" 0  sleep 2000  list  count v_diff
register v_diff -> RUNNING fp=77206052c4aa      # vs 8dcec656ef1c for SELECT id, usr, amount
  v_diff	RUNNING	77206052c4aa	rowsIn=20
viewrows v_diff = 20
$ jcmd <pid> Thread.print | grep '^"pravaha-feed'
"pravaha-feed-v_lat" ... ; "pravaha-feed-v_diff" ...
$ ls -l /proc/<pid>/fd | grep -c txn.csv
2
```

**Verdict:** Different plan, different fingerprint, own feed thread, own descriptor, own complete
read of the file — 20 rows each, and `v_diff`'s view is readable with all 20. Two queries over one
stream do not share a reader and neither sees a partial file. This is the direct complement to
INGEST-010/011 and the pair together show the fingerprint boundary is drawn in the right place.

### INGEST-039 — PASS (behaviour established; a disclosure defect raised)

```
$ probe.sh register s_a "SELECT id, usr, amount FROM big WHERE id > 999999999" 0 \
           register s_b "SELECT id, usr, amount FROM big WHERE id > 999999999" 0 \
           pause s_a  rows s_a  rows s_b  sleep 3000  rows s_a  rows s_b
register s_a -> RUNNING fp=a757882b7e54
register s_b -> RUNNING fp=a757882b7e54
pause s_a -> ok
rowsIn s_a = 18432 ; rowsIn s_b = 18432
rowsIn s_a = 18432 ; rowsIn s_b = 18432
```

**Verdict:** The truth, which is what this case asked for: pausing `s_a` froze `s_b` as well, at
18 432 of 500 000, and both stayed frozen. That is correct and unavoidable — they are one
computation behind one feed — and the pause is real rather than cosmetic, which is the mechanism
this case verifies.

The defect is that nothing says so. `pause s_a` returns `ok` with no indication that another name is
attached; `s_b`'s owner sees a `RUNNING` query whose `rowsIn` has stopped, with no way to discover
that somebody else paused it, and `pause`/`drop` are the two operations where implicit sharing
becomes visible to a tenant who never asked for it. The refcount in `drop` handles this properly;
`pause` has no equivalent. At minimum the pause response should name the other holders.
(**DEFECT-9**)

### INGEST-040 — PASS (with a documented caveat)

```
# conf/journal.yaml: pravaha.registry.journal set, no checkpoint directory
$ probe.sh register v_j "SELECT id, usr, amount FROM txn" 0  sleep 1500  list  count v_j
register v_j -> RUNNING fp=8dcec656ef1c
  v_j	RUNNING	8dcec656ef1c	rowsIn=20
viewrows v_j = 20

$ stop.sh journal && start.sh journal
$ grep recovered logs/journal.log
registry recovered 1 of 1 queries from /.../state/journal.log
$ probe.sh sleep 1500  list  count v_j
  v_j	RUNNING	8dcec656ef1c	rowsIn=20
viewrows v_j = 20
```

**Verdict:** The recovered query is re-fed. It is not left registered-but-starved, which was the
failure this case was chiefly hunting, and the view is complete at 20 rows rather than doubled at
40. Non-vacuous: `recovered 1 of 1` proves the query came from the journal and was not re-registered
by me.

Caveat for the operator notes: with no checkpoint directory configured, **no source offset is
restored, so the feed re-reads the file from line 1 on every restart.** It is invisible here because
a keyed view upserts by `id`, so a full replay is idempotent. It would not be invisible for a
`COUNT(*)`, and the node's own startup warning (*"a restart recovers their definitions from the
journal and none of their accumulated state"*) describes the state but not the replay.

---

## Summary

| Verdict | Count |
|---|---|
| PASS | 27 |
| FAIL | 13 |
| BLOCKED | 0 |
| NOT RUN | 0 |
| **Total** | **40** |

FAIL: INGEST-002, 004, 007, 016, 020, 021, 022, 024, 026, 027, 029, 034, 037.

### Defects, in priority order

| # | Severity | Cases | Defect |
|---|---|---|---|
| 1 | **Blocker** | 002, 004, 029, 037 | `ServedView` is not thread-safe and `PumpingFeed`'s 20 ms publish timer calls `commit` on the feed thread while the lane thread applies rows. `ConcurrentModificationException` kills the feed permanently. Query stays `RUNNING`; the view holds a silent, arbitrary fraction of the data — 11 of 20, 8 of 100, 5 123 of 500 000. Near-certain on any file of consequence. |
| 2 | **Blocker** | 020, 021, 022, 024, 026, 027 | One malformed line ends ingestion for the whole query, and takes the rows read before it with it (`rowsIn=0`, not 1). `FilesystemPartitionReader.poll` documents a DLQ that does not exist on this path and rethrows instead. |
| 3 | High | 020, 021, 022, 024, 026, 027 | `DelegatingRowWriter.abort()` throws `UnsupportedOperationException`, which replaces every decode diagnostic with an internal note telling the operator to "Report this". `line 2, column 'amount' (INT64): 'notanumber' is not a number` is computed and then thrown away. |
| 4 | High | 026, 027 | Nothing validates `pravaha.streams.<n>.schema` against `pravaha.sources.<n>.options.schema`. They are written by hand in adjacent YAML blocks and a disagreement is caught only by the row writer, at the first row, as a dead feed. (No corruption occurred in any variant I could build — see INGEST-027.) |
| 5 | High | 010 | A second registration of identical SQL returns `RUNNING` under a name that no view answers to: `SELECT * FROM v_a` → `Object 'v_a' not found`. `views.register` is skipped on the shared path. |
| 6 | High | 007, 016, 037 | `SourceFeed.describe()` reaches no shipped surface — Flight, REST, CLI, logs, all silent; its only caller in the repository is a unit test. A dead feed, an unbound stream and a quiet source are indistinguishable to an operator. No log line is emitted when a feed dies. |
| 7 | Medium | 034 | `application.yaml` and `PluginSourceFeeds`' javadoc advertise `feedfile`, `jdbc` and `delta` as classpath-discoverable and claim a jar can be dropped in. Only `filesystem` ships, and `JarLauncher` provides no drop-in mechanism. |
| 8 | Medium | 016 | A directory as `path` passes `open()` (`Files.isReadable` is true of a directory) and fails on the first read as `PRV-5040 read failed at line 0`, naming neither the path nor the cause. |
| 9 | Low | 007 | `/api/v1/status` reports the stream count in the `registeredQueries` field (20 streams reported as 20 queries when 2 were registered). `plugins` is always `[]` on a node with a loaded source plugin. |
| 10 | Low | 039 | Pausing one name of a shared computation silently pauses every other name, with no indication to either the caller or the other holders. |
| 11 | Low | 035, 036 | No startup validation or warning for a binding whose stream is not declared, a plugin name that does not resolve, or a path that does not exist. |
| 12 | Low | 017 | A stream may be declared with a SQL reserved word as its name (`empty`); nothing warns, and the failure surfaces much later as a parser error. |

### What I could not cover, and why

**Backpressure under genuine overload (INGEST-030) is the significant gap.** I never got the source
to outrun the consumer, so `IngestPump`'s high-watermark pause was never observed engaging. Three
things stood in the way: an unwindowed aggregate — the natural way to make a lane slow — is refused
by design; a windowed one needs an event-time column these streams do not have; and any query that
writes to its view dies of DEFECT-1 within one batch. `pauseCount()`, `resumeCount()` and
`pausedNanos()` — the design's named `backpressure.ratio` inputs — are exposed through no surface,
so even a successful overload could not be measured from outside the JVM. Backpressure should be
treated as untested.

**DEFECT-1 shaped the rest of the suite.** Every large-source case (008, 009, 028, 029's control,
030, 031, 039) had to use a predicate matching no rows, so that the lane consumed input without
writing to the view. Those cases test the ingest path honestly, but none of them exercises ingest
and serving together at scale — which is the combination that is broken, and which cannot be tested
until DEFECT-1 is fixed. The visibility results at scale in INGEST-029 should be re-run afterwards;
so should 002, 004 and 037.

**`describe()` was read from inside the JVM, not through a shipped surface**, using a harness built
on the classes extracted from the shipped app jar. That is the same code the server runs, but it is
not the same path a user takes, and INGEST-005/006/016/020-022/024/026/027 depend on it for their
diagnostic evidence. Their `rowsIn` and view-content evidence is from the product surface and stands
on its own; only the *reason* comes from the harness. If DEFECT-6 is fixed, all of them can be
re-run entirely through `pravaha queries`.

**Not covered at all:** plugins other than `filesystem` (not on the classpath — INGEST-034);
multi-partition sources (`FilesystemSourcePlugin.partitions` always returns exactly one, so the
`partitionCounts` plural branch of `describe()`, per-partition backpressure and the lane-0 comment
in `PluginSourceFeeds.open` are all unexercised); feeding a join, since each side needs its own
bound stream and a self-join is refused (INGEST-012), leaving `IngestPump`'s `input` index — the
whole reason a join is feedable — untested; checkpoint/offset restore on restart (no checkpoint
directory was configured, so INGEST-040 only covers the journal); `PumpingFeed.close`'s 5 s
bounded-join path, since no reader I could construct blocked long enough to reach it; and
concurrent registration of the same query from two clients at once, which is where the
fingerprint-sharing path in `QueryRegistry.register` would be raced — it is `synchronized`, so I
judged it lower value than the defects above and did not spend the time.

---

# Re-QA 2026-09-12 (verification pass)

Executed against the rebuilt artifacts, HTTP 18100 / Flight 19100, Java 21.0.12. No production code
was changed.

**What I tested, and a warning about it.** The app jar under test was packaged at 21:37. The working
tree has moved past it: `git status` at 22:16 showed uncommitted edits to `StreamSchema`,
`ViewCatalog`, `QueryRegistry`, `RegisteredQuery`, `QueryExecution` and a new
`EngineHealthIndicator.java`. Two of my verdicts below (INGEST-053, DEFECT-16) turn on a method,
`StreamSchema.renamedTo`, that **exists in the tree and not in the shipped jar** — so on the artifact
I was told to use, half of the DEFECT-5 fix is absent. Every verdict here is about the artifact.

New drivers, both built only from shipped classes:

* **`probe2.sh`** — the SDK client with concurrency added: N reader threads, each on its own Flight
  connection, scanning a view in a loop while a feed ingests, with per-scan latency and a rowsIn
  time series. It exists because the one combination the original pass could never run was *ingest
  and serving together at scale*.
* **`h2.sh`** — in-process on the shipped server classes, in package
  `com.ash.messaging.pravaha.server.ingest` so it can reach three things no shipped surface exposes:
  `SourceFeed.describe()`, `IngestPump`'s backpressure counters, and `Retention` (the SDK's
  `register` has no such parameter, so every Flight registration silently takes the 24 h default).

**Keying.** Every DEFECT-1 case below keys the view on ordinal 0, `id`, which is unique in every file
used, so `view size == rowsIn` or rows were lost. This is the trap the remediation's own commit
message records falling into: a view keyed on a 500-value column made 200 000 lost rows look like a
correct 500.

---

## Verdicts on the thirteen original FAILs

| Case | Verdict |
|---|---|
| INGEST-002 | **VERIFIED-FIXED** |
| INGEST-004 | **VERIFIED-FIXED** |
| INGEST-007 | **STILL-FAILING** |
| INGEST-016 | **STILL-FAILING** |
| INGEST-020 | **STILL-FAILING** |
| INGEST-021 | **STILL-FAILING** |
| INGEST-022 | **STILL-FAILING** |
| INGEST-024 | **STILL-FAILING** |
| INGEST-026 | **STILL-FAILING** |
| INGEST-027 | **STILL-FAILING** |
| INGEST-029 | **PARTIALLY-FIXED** — the loss is gone; two new failures took its place |
| INGEST-034 | **STILL-FAILING** |
| INGEST-037 | **STILL-FAILING** |

---

### INGEST-002 — VERIFIED-FIXED

```
$ probe2.sh register t1 "SELECT id, usr, amount FROM txn WHERE id > 0" 0  sleep 1200  rows t1  count t1
register t1 -> RUNNING fp=f2eb2bbc309d  rowsIn t1 = 20  viewrows t1 = 20
                                        rowsIn t2 = 20  viewrows t2 = 19     (WHERE id > 1)
                                        rowsIn t3 = 20  viewrows t3 = 18     (WHERE id > 2)
                                        rowsIn t4 = 20  viewrows t4 = 17
                                        rowsIn t5 = 20  viewrows t5 = 16
```

**Verdict:** Fixed. Five registrations, five distinct fingerprints, 20 rows in each time and a view
holding exactly what the predicate selects. The original failure was 11 of 20 with the other 9 never
arriving. Not vacuous: the five predicates make the expected view size 20, 19, 18, 17, 16, and all
five match — a view filled by luck would not walk down in step with the predicate.

### INGEST-004 — VERIFIED-FIXED

```
$ probe2.sh register tl "SELECT id, usr, amount FROM tail100" 0  sleep 1500  rows tl  count tl
rowsIn tl = 100 ; viewrows tl = 100
```

**Verdict:** 100 of 100, against 8 of 100 before.

### INGEST-007, INGEST-037 — STILL-FAILING (DEFECT-6 unchanged)

```
$ probe2.sh register v_ragged ... v_wide ... v_badtype ... v_nullcol ... v_dirpath ... v_mm ...
            v_tm ... v_unb ... v_ctl ...  sleep 3000  list
  v_ragged   RUNNING  d9683c2a2421  rowsIn=0      # feed killed by a malformed line
  v_wide     RUNNING  218bf54a2409  rowsIn=0
  v_badtype  RUNNING  b7037bddba06  rowsIn=0
  v_nullcol  RUNNING  c03de7a236e8  rowsIn=0
  v_unb      RUNNING  9c8689d4e523  rowsIn=0      # nothing bound at all
  v_dirpath  RUNNING  3af572e078ba  rowsIn=0
  v_mm       RUNNING  fd7cf4110e4b  rowsIn=0
  v_tm       RUNNING  7dc7c1c8ce90  rowsIn=0
  v_ctl      RUNNING  8dcec656ef1c  rowsIn=20     # control, same server, same moment

$ curl -s http://localhost:18100/api/v1/status
{"instanceId":"qa-ingest","version":"0.1.0-SNAPSHOT","engineState":"RUNNING",
 "uptimeSeconds":210,"registeredQueries":24,"plugins":[]}
$ curl -s http://localhost:18100/actuator/health
{"status":"UP","groups":["liveness","readiness"]}
$ bin/pravaha queries
NAME       STATE    FINGERPRINT   ROWS IN
v_ragged   RUNNING  d9683c2a2421  0
v_unb      RUNNING  9c8689d4e523  0
v_ctl      RUNNING  8dcec656ef1c  20

$ grep -icE 'ragged|badtype|dirpath|feed.*stopped|PRV-5092|abort' logs/main2.log
2                      # both are the startup "streams declared"/"sources bound" lines
$ for p in /api/v1/queries /api/v1/health; do curl -o/dev/null -w '%{http_code}\n' ...; done
404
404
```

**Verdict:** **STILL-FAILING, and this is now the most consequential of the unfixed items**, because
the failures it hides have multiplied (see DEFECT-13). `PumpingFeed`'s fix is real and internal:
`publishPeriodically` is inside the try and the catch takes `Throwable`, so the feed now *records*
why it died. Nothing reads the recording. `describe()` still reaches no Flight action, no REST
endpoint, no CLI column and no log line. Seven dead feeds and one unbound stream printed eight
identical lines, and `/actuator/health` said `UP` while they did.

**What an operator would actually see:** `ROWS IN` stuck at 0 in `pravaha queries`, `engineState:
RUNNING` and `status: UP` from HTTP, and a server log containing no error, warning or exception
about any of it. To tell a dead feed from a quiet source they would have to know the row count of
the source file and compare it by hand, per query, repeatedly — and that only works for a source
whose length is knowable, which a stream is not.

### INGEST-016, INGEST-020, INGEST-021, INGEST-022, INGEST-024, INGEST-026, INGEST-027 — STILL-FAILING

Confirmed one at a time through `h2.sh`, which is the only way to read the reason:

```
--- ragged (line 2 has 2 fields) ---
FINAL rowsIn=0 viewRows=0 state=RUNNING
describe(): reading ragged (1 partition) -- stopped: PRV-5092  the source feed for 'v' stopped:
  java.lang.UnsupportedOperationException: a plugin aborted a row mid-write ... Report this ...
feed thread alive: NONE
--- wide (line 2 has 4 fields) ---      identical
--- badtype ('notanumber' in an INT64) ---   identical
--- nullcol (empty field in NOT NULL) ---    identical
--- mismatch (3-col catalog vs 4-col plugin) ---  identical
--- typemix (INT64 catalog vs STRING plugin) ---  identical
--- dirpath (a directory as path) ---
describe(): reading dirpath (1 partition) -- stopped: PRV-5040  read failed at line 0
feed thread alive: NONE
```

**Verdict:** Unchanged in every respect. One malformed line still ends ingestion for the whole query;
the rows read before it are still lost (`rowsIn=0`, not 1); `DelegatingRowWriter.abort()` still
throws `UnsupportedOperationException` and still replaces the real diagnostic with an internal note
telling the operator to "Report this"; nothing still validates the catalog schema against the
plugin's; and `Files.isReadable` still accepts a directory. DEFECT-2, DEFECT-3, DEFECT-4 and
DEFECT-8 all stand exactly as filed.

Worth recording that the new `event.time` handling in `FilesystemSourcePlugin.open` rebuilds the
schema through `StreamSchema.builder`, and I checked specifically that it does not change what the
plugin decodes with: nullability rides on the `PravahaType` rather than on a builder flag, so
`usr:STRING?` survives the rebuild. INGEST-025's positive control still behaves.

### INGEST-034 — STILL-FAILING (DEFECT-7 unchanged)

```
$ probe2.sh register v_feedfile ... register v_noplug ...
register v_feedfile -> ERROR PRV-5090  no source plugin named 'feedfile' is on the classpath,
  so stream 'feedfile' cannot be fed. Available: [filesystem]
register v_noplug   -> ERROR PRV-5090  no source plugin named 'kafka' ... Available: [filesystem]
$ unzip -l ...app.jar | grep -c 'BOOT-INF/lib/pravaha-plugin'
1
```

**Verdict:** One plugin jar ships. The documentation still advertises four.

### INGEST-029 — PARTIALLY-FIXED

```
$ probe2.sh throughput r41 "SELECT id, usr, amount FROM big" 0 500000
THRU r41 rowsIn=500000 viewSize=500000 expected=500000 in 6.39s
$ grep -c ConcurrentModification logs/main2*.log
0   0
```

**Verdict:** The symptom this case was filed for is **gone**: 500 000 rows in, 500 000 rows visible,
keyed on a unique column, no `ConcurrentModificationException` anywhere in two full server sessions.
The original reading was 5 123 of 500 000.

It is *partially* fixed because the failure mode did not disappear — it moved. Two things now
produce the same user-visible outcome, a query reporting `RUNNING` over a view holding a fraction of
the data: DEFECT-13 (the lane thread dies where the feed used to) and DEFECT-14 (readers starve the
feed of the new monitor). Both are below.

---

## The fix, probed adversarially

### INGEST-041 — PASS

```
$ probe2.sh throughput r41 "SELECT id, usr, amount FROM big" 0 500000
THRU r41 rowsIn=500000 viewSize=500000 expected=500000 in 6.39s = 78234 rows/s
```

**Verdict:** The plain case is clean. Non-vacuous: the key is `id`, unique across all 500 000 rows,
so the view cannot collapse; and the same measurement returns a mismatch under INGEST-042.

### INGEST-042 — FAIL (new: DEFECT-14)

```
$ probe2.sh race r42 "SELECT id, usr, amount FROM big" 0 8 500000
RACE r42 readers=8 rowsIn=240379 viewSize=268984 expected=500000 *** MISMATCH ***
  scans=372 readerErrors=0 maxScanLatency=1428.3ms
```

`rowsIn` had not moved for three seconds when the harness gave up; it resumed the moment the readers
stopped, which is why the later view count is *higher* than the last `rowsIn` sample. So this is not
loss. It is a stall, and the measurement below shows its shape:

```
$ probe2.sh stall r47b "SELECT id, usr, amount FROM big" 0 8 0 30000 500000 180000
  t=  258ms rowsIn=  46784  rate= 177212/s  readers=ON
  t=  766ms rowsIn= 311806  rate= 520672/s  readers=ON
  t= 1274ms rowsIn= 313854  rate=   3556/s  readers=ON
  t= 2356ms rowsIn= 314878  rate=      0/s  readers=ON
  t= 3482ms rowsIn= 314878  rate=      0/s  readers=ON
  ... twenty-eight seconds between 0 and 4 000 rows/s ...
  t=29615ms rowsIn= 368455  rate=   2016/s  readers=ON
  t=30123ms rowsIn= 369479  rate=   1973/s  readers=off
  t=31146ms rowsIn= 396888  rate=  43600/s  readers=off
  t=36681ms rowsIn= 500000  rate=  13936/s  readers=off
STALL r47b final rowsIn=500000 viewSize=500000 expected=500000 OK
  scans=427 readerErrors=0 maxScanLatency=965.4ms
```

**Verdict:** **FAIL, new defect, and the direct cost of the fix.** No rows are lost — given enough
time the count is exact — but eight concurrent readers take ingestion from ~520 000 rows/s to
~2 000 rows/s, and hold it there for as long as they keep reading. That is the second failure mode
the brief asked to watch for, and it is present in full.

Cause, from three thread dumps of the running server taken during the stall:

```
$ jcmd <pid> Thread.print          # dumps 1 and 2 of 3
"pravaha-feed-r48" ... java.lang.Thread.State: BLOCKED (on object monitor)
	at com.ash.messaging.pravaha.serving.ServedView.commit(ServedView.java:210)
	- waiting to lock <0x000000044cb6a1b8> (a ...ServedView)
	at com.ash.messaging.pravaha.server.ingest.PumpingFeed.publishPeriodically(PumpingFeed.java:164)

"flight-server-default-executor-0" ... java.lang.Thread.State: RUNNABLE
	at java.util.LinkedHashMap.valuesToArray(LinkedHashMap.java:687)
	at java.util.ArrayList.<init>(ArrayList.java:181)
	at com.ash.messaging.pravaha.serving.ServedView.scan(ServedView.java:319)
	- locked <0x000000044cb6a1b8> (a ...ServedView)
```

`ServedView.scan()` is `synchronized` and its body is `new ArrayList<>(visible.values())` — an O(n)
copy of the committed map, taken under the view's monitor. At 300 000 rows that copy is long enough
that eight readers looping hold the monitor almost continuously, and `commit` on the feed thread
never gets in. Measured directly:

```
$ probe2.sh scanlat s1 10        # a filled 500 000-row view, no other load
  scans=10 mean=346.7ms max=1014.7ms
```

A third of a second of monitor, per scan, per reader.

The degradation is smooth and starts at one reader — end-to-end time for the same 500 000 rows:

| readers | wall clock | effective rate |
|---|---|---|
| 0 | 2.1 s | ~230 000/s |
| 1 | 6.4 s | ~78 000/s |
| 2 | 7.0 s | ~71 000/s |
| 8 | ~37 s | ~13 500/s, with sustained plateaus at ~2 000/s |

Zero reader errors in any run, and no reader ever observed a count above the final total, so the
atomicity the monitor was taken for does hold. This is the price, not a second race.

### INGEST-043 — PASS

```
$ four concurrent registrations over `big`, four distinct plans, all serving, all keyed on id
THRU q1 rowsIn=500000 viewSize=500000 expected=500000 in 7.52s
THRU q2 rowsIn=500000 viewSize=500000 expected=500000 in 6.66s
THRU q3 rowsIn=500000 viewSize=500000 expected=500000 in 7.35s
THRU q4 rowsIn=500000 viewSize=499999 expected=499999 in 7.31s
```

**Verdict:** Four feeds, four lanes, four views, all exact. Non-vacuous: `q4` is
`WHERE amount > 1`, which excludes exactly the one row with `amount = 1`, and its view holds 499 999
— one fewer than the others, which is the right number rather than a round one.

### INGEST-044 — PASS

```
$ probe2.sh register d1 "SELECT id, usr, amount FROM huge" 0  register d2 "SELECT id, amount FROM huge" 0
           sleep 1500  rows d1  rows d2  drop d1  sleep 300  list
rowsIn d1 = 679842 ; rowsIn d2 = 788458        # both demonstrably mid-file
drop d1 -> ok in 31.4ms
  d2  RUNNING  34cd1f6127cb  rowsIn=926481
$ SELECT * FROM d1  ->  PRV-2002  Object 'd1' not found. Known streams: [s1, d2]
$ rowsIn d2 = 1000209                          # still climbing afterwards
```

**Verdict:** A drop with two feeds mid-file over a two-million-row source: the dropped name stops
answering immediately, the survivor is undisturbed and keeps climbing. The new `views.remove(name)`
in `drop` does what it says. Non-vacuous — the 679 842 / 788 458 samples prove both feeds were
actively pumping at the moment of the drop.

### INGEST-045 — PASS

```
$ probe2.sh register p1 "SELECT id, usr, amount FROM big" 0  pause p1
  sample 1: rowsIn p1 = 297423   viewrows p1 = 296468
  sample 2: rowsIn p1 = 297423   viewrows p1 = 296468
  sample 3: rowsIn p1 = 297423   viewrows p1 = 296468
  sample 4: rowsIn p1 = 297423   viewrows p1 = 296468
$ probe2.sh resume p1  waitstable p1 2000 60000  rows p1  count p1
stable p1 rowsIn=500000 after 2708ms ; rowsIn = 500000 ; viewrows = 500000
```

**Verdict:** INGEST-008/009 repeated against a query that actually writes to its view, which is the
combination the fix changed and the original pass had to avoid. Frozen at 297 423 for eight seconds,
then exactly 500 000 — not more, so no replay; not less, so no gap. The 955-row difference between
`rowsIn` and the view while paused is the uncommitted overlay, and it closes on resume.

### INGEST-046 — FAIL (new: DEFECT-13)

```
$ probe2.sh stall r46b "SELECT id, usr, amount FROM big2" 0 0 0 0 990000 180000     # 990 000 rows, no readers
STALL r46b final rowsIn=932782 viewSize=931136 expected=990000 *** MISMATCH ***

$ probe2.sh list          # and eight seconds later, twice
  r46b  RUNNING  526f9cf8c0f3  rowsIn=932782
  r46c  RUNNING  7667fb0710ed  rowsIn=933155
  r46b  RUNNING  526f9cf8c0f3  rowsIn=932782
  r46c  RUNNING  7667fb0710ed  rowsIn=933155
$ curl -s http://localhost:18100/api/v1/status
{"engineState":"RUNNING", ...}
$ grep -cE 'ERROR|Exception|arena' logs/main2-run1.log
0
$ jcmd <pid> Thread.print | grep -E '^"pravaha-(query|feed)'
"pravaha-feed-r46b" ... cpu=575081.24ms elapsed=959.23s
"pravaha-feed-r46c" ... cpu=451092.74ms elapsed=740.18s
```

**Verdict:** **FAIL, and this is the headline finding of the re-QA.** Ingestion stops dead at
~932 000 rows, permanently, with no reader involved. Both queries report `RUNNING` for ever, the
node reports `engineState: RUNNING`, and the server log contains not one error line. **There is no
`pravaha-query-*` thread in the dump: the lane thread is gone.** The two feed threads are still
alive and have burned 575 s and 451 s of CPU between them since their lanes died.

The reason, from the in-process harness, which can call the method the server never calls:

```
$ h2.sh big2 ... "SELECT id, usr, amount FROM big2" 30000 forever 0
FINAL rowsIn=932744 viewRows=931136 state=RUNNING evicted=0 commits=1436
describe(): reading big2 (1 partition)
  pump rowsPumped=932744 pauseCount=246 resumeCount=245 pausedNanos=28081675519 paused=true
  lane metrics: [LaneMetrics[laneId=0, rowsIn=931208, rowsOut=930696, batches=10049,
                 idleCycles=73042, rejectedOffers=0, inboxFill=1.0, exchangedIn=0]]
  checkHealth() THREW: PRV-3010  lane 0 stopped after a failure:
     PRV-3001  the projection's arena is full; raise arena.slab.size or reduce the batch size
    caused by: PRV-3001 ...
      at ...InterpretedPipeline$Builder.lambda$projector$9(InterpretedPipeline.java:704)
      at ...runtime.lane.Lane.run(Lane.java:433)
feed thread alive: pravaha-feed-v=true
```

`Lane.run` catches `Throwable`, records it in `Lane.failure` and sets `state = FAILED`. The thread
ends. `QueryExecution.checkHealth()` would rethrow it. **Nothing in the server calls
`checkHealth()`** — `grep -rn checkHealth --include=*.java` finds it in `pravaha-cli`'s
`QueryRunner`, in `pravaha-it` tests, and nowhere in `pravaha-server`. So:

* `RegisteredQuery.state()` stays `RUNNING`
* `SourceFeed.describe()` says `reading big2 (1 partition)` — no failure, because the *feed* is fine
* `rowsIn` freezes; the view holds whatever the lane had committed
* the pump pauses at its high watermark (`inboxFill=1.0`) and never resumes, so the feed thread
  spins on a full inbox for the life of the node, burning a core

This is the exact shape of the original DEFECT-1 — a silently dead query reporting healthy — moved
one thread across. The remediation hardened the feed thread against a throw and the lane thread is
now the one that dies.

**It is not a million-row edge case.** A windowed aggregate over 50 000 keys dies at a quarter of
that (INGEST-055).

`arena.slab.size`, which the error names as the remedy, appears in six error messages in
`pravaha-runtime` and **nowhere else in the repository** — not in `application.yaml`, not as a
`@Value`, not in `docs/`. The one instruction the engine gives the operator cannot be carried out.
(**DEFECT-19**)

### INGEST-047 — see INGEST-042. INGEST-048 — FAIL (contributing: DEFECT-15)

```
$ probe2.sh scanlat s1 10                      # 500 000-row view, otherwise idle
  scans=10 mean=346.7ms max=1014.7ms
$ probe2.sh scanlat s1 10                      # with a second feed ingesting big2 concurrently
  scans=10 mean=397.1ms max=1096.2ms
```

**Verdict:** A scan of a 500 000-row view costs a third of a second on average and up to a second,
all of it inside the view's monitor. That is the number behind DEFECT-14 from the reader's side.

A second O(n)-under-the-lock cost was found in the same dumps:

```
"pravaha-feed-r48" ... java.lang.Thread.State: RUNNABLE
	at com.ash.messaging.pravaha.serving.ServedView.evict(ServedView.java:370)
	at com.ash.messaging.pravaha.serving.ServedView.commit(ServedView.java:232)
	- locked <0x000000044cb6a1b8> (a ...ServedView)
```

`commit` calls `evict()` while holding the monitor, on every 20 ms publish tick. `evict()` returns
immediately only when retention is `forever`; **the default is `Retention.DEFAULT`, 24 hours**, and
the SDK's `register` has no retention parameter, so every query registered through Flight gets it.
Under that default `evict()` iterates the entire `visible` map on every tick. Measured with the
harness, same file, same query, retention the only difference:

```
### DEFAULT retention (24h)                    ### forever
  + 1002ms rowsIn=  57391 rate= 57276/s          + 1008ms rowsIn=  23143 rate=  22959/s
  + 5121ms rowsIn= 246121 rate= 28747/s          + 5459ms rowsIn= 580547 rate= 182989/s
  + 9264ms rowsIn= 284009 rate=  9152/s          + 9564ms rowsIn= 932586 rate=       0/s (DEFECT-13)
  +17900ms rowsIn= 344492 rate=  4335/s
  +40546ms rowsIn= 474204 rate=  7211/s
```

Ingestion under the default retention decays to a fifth of its rate as the view grows, and the cost
is paid with the monitor held, so it slows readers too. `evicted=0` in every run: the sweep found
nothing to evict on any tick, all 1 400-plus of them. (**DEFECT-15**)

### INGEST-060 — PASS (no deadlock; starvation instead)

Four thread dumps taken during heavy ingest with eight readers, on both the server and the harness.
Every blocked thread was `BLOCKED (on object monitor)` on `ServedView`, held by a thread that was
`RUNNABLE` and making progress; no cycle, and every run eventually completed with exact counts. The
commit message's reasoning about keeping `awaitFrontier` outside the lock holds up. What the
arrangement produces instead is DEFECT-14.

---

## The other re-tests

### INGEST-049 — FAIL

Covered under INGEST-007/037 above. A dead feed reaches no shipped surface. The recording half of
the `PumpingFeed` fix works and the reading half does not exist. Made materially worse by DEFECT-13,
which is a dead *lane*: for that one, even `describe()` says nothing is wrong, because the feed
genuinely is fine.

### INGEST-053 — PARTIALLY-FIXED, and it introduced DEFECT-16

Half one, the view name. **Not fixed in the shipped artifact:**

```
$ probe2.sh register v_a "SELECT id, usr, amount FROM txn" 0
           register v_b "SELECT id, usr, amount FROM txn" 0
           register v_c "SELECT id, usr, amount FROM txn" 0  sleep 2000  list
  v_a  RUNNING  8dcec656ef1c  rowsIn=20
  v_b  RUNNING  8dcec656ef1c  rowsIn=20
  v_c  RUNNING  8dcec656ef1c  rowsIn=20
  SELECT * FROM v_a ->  (20 rows total)
  SELECT * FROM v_b ->  PRV-2002  Object 'v_b' not found. Known streams: [v_a]
  SELECT * FROM v_c ->  PRV-2002  Object 'v_c' not found. Known streams: [v_a]
```

`QueryRegistry.register` does call `views.registerAs(name, existing.view())` — confirmed in the
shipped bytecode:

```
$ javap -c -p ...pravaha-registry-0.1.0-SNAPSHOT/... QueryRegistry | grep registerAs
 335: invokevirtual  // Method ViewCatalog.registerAs:(Ljava/lang/String;L...ServedView;)L...ViewCatalog;
```

The break is one layer down. `ViewQuery` plans against `catalog.schemas().values()` — the values
only, so the planner keys each table by the *schema's* name, and all three aliases carry the schema
of `v_a`. The tree fixes this by renaming each schema to its catalogue key:

```java
views.forEach((name, view) -> schemas.put(name, view.schema().renamedTo(name)));   // in the tree
```

and the shipped jar does not have it:

```
$ javap -c -p ...pravaha-serving.../ViewCatalog | sed -n '/lambda$schemas$0/,/^$/p'
   3: invokevirtual  // Method ServedView.schema:()L...StreamSchema;
   6: invokeinterface // Method java/util/Map.put
$ javap -p ...appjar/x/pravaha-api-0.1.0-SNAPSHOT/... StreamSchema | grep -i renamed
(nothing)
$ javap -p pravaha-api/target/pravaha-api-0.1.0-SNAPSHOT.jar ... | grep -i renamed
  public com.ash.messaging.pravaha.api.data.StreamSchema renamedTo(java.lang.String);
```

The workspace `pravaha-api` jar is stamped 22:16 and the app jar was packaged at 21:37. So the fix
is written and half-shipped. **Verdict against the artifact: STILL-FAILING.** Against the tree it
is very likely fixed, and should be re-run on the next build.

Half two, the journal. **VERIFIED-FIXED:**

```
$ cat state/journal.log
   VPRVH  R  v_a  SELECT id, usr, amount FROM txn  0  anonymous  86400000
   VPRVH  R  v_b  SELECT id, usr, amoun...                                 # the alias is journalled
$ probe2.sh drop v_a  sleep 800  list
  v_b  RUNNING  8dcec656ef1c  rowsIn=20
  v_c  RUNNING  8dcec656ef1c  rowsIn=20
$ stop.sh journal && start.sh journal
registry recovered 2 of 2 queries from .../state/journal.log
  v_b  RUNNING  8dcec656ef1c  rowsIn=20
  v_c  RUNNING  8dcec656ef1c  rowsIn=20
```

The alias is journalled, the drop is journalled, and `recovered 2 of 2` is now the truth rather than
a claim about a name that had vanished. This half is right.

The adversarial extension found something new, though:

```
$ probe2.sh drop v_a                           # drop the FIRST name of three sharing one computation
drop v_a -> ok in 260.3ms
  SELECT * FROM v_a ->  PRV-4023  'v_a' is not a registered view; this server serves [v_b, v_c]
  SELECT * FROM v_b ->  PRV-2002  Object 'v_b' not found. Known streams: [v_a]
  SELECT * FROM v_c ->  PRV-2002  Object 'v_c' not found. Known streams: [v_a]
```

**The computation is alive, ingesting, reported RUNNING under two names, and unreadable under every
name there is.** Two shipped surfaces contradict each other in consecutive lines: one says the
server serves `v_b` and `v_c`, the other says only `v_a` exists. It clears on a restart, because the
surviving name becomes the primary. This is DEFECT-16, and it is a direct consequence of shipping
`views.remove(name)` in `drop` without the `renamedTo` half — the drop fix made the pre-existing
alias defect strictly worse.

### INGEST-054 — STILL-FAILING. See INGEST-034.

### INGEST-055 — PASS on the mechanism, BLOCKED on the question

The first time backpressure has been exercised at all. A consumer that is slow but survives:

```
$ h2.sh big ... "SELECT id, usr, amount FROM big" 12000 forever 0
FINAL rowsIn=500000 viewRows=500000 state=RUNNING
  pump rowsPumped=500000 pauseCount=45 resumeCount=45 pausedNanos=85589371 paused=false
  lane metrics: [LaneMetrics[laneId=0, rowsIn=500000, rowsOut=500000, batches=17537,
                 rejectedOffers=0, inboxFill=0.0]]
  checkHealth(): healthy
```

**Verdict:** Backpressure works and loses nothing. 45 pauses and 45 resumes — balanced, so the
edge-triggered hysteresis is behaving; `rejectedOffers=0`, so no row was ever refused and dropped;
85 ms of pause in total; every one of the 500 000 rows arrives and is visible. Non-vacuous: the pump
demonstrably *did* engage, 45 times, rather than the source simply never outrunning the consumer,
which was the reason INGEST-030 could not conclude.

The question the case was written for is still not answered, for a new reason. The genuinely slow
consumer — a windowed aggregate over 50 000 keys — dies before the source finishes:

```
$ probe2.sh register bp <TUMBLE 600s over evbig, GROUP BY window, usr>  samples bp 8 2000
  sample 0 t=0ms      rowsIn=4757
  sample 1 t=2000ms   rowsIn=263768
  sample 2..7         rowsIn=263768         # frozen, RUNNING, for the rest of the run

$ h2.sh evbig ... <same query> 25000 forever 0 event.time=event_time lateness=PT1S watermarks=on
FINAL rowsIn=263656 viewRows=1 state=RUNNING
  pump rowsPumped=263656 pauseCount=152 resumeCount=151 pausedNanos=24640364125 paused=true
  lane metrics: [... inboxFill=1.0 ...]
  checkHealth() THREW: PRV-3010  lane 0 stopped after a failure:
     PRV-3001  the projection's arena is full; raise arena.slab.size or reduce the batch size
```

**263 656 rows.** That is DEFECT-13 again, at a quarter of the row count, on the kind of query the
engine exists to run. Note `pausedNanos` = 24.6 s of a 25 s run with `paused=true` at the end: the
pump is not throttling a slow consumer, it is parked against a dead one, and it has no way to tell
the difference. Steady-state backpressure against a consumer that is merely slow remains untested.

### INGEST-056 — PASS (with DEFECT-18)

Windowed ingestion works end to end from configuration. This is new and it is the feature the engine
is named for.

```
$ probe2.sh register w1 "SELECT window_start, window_end, usr, SUM(amount) AS total
                         FROM TABLE(TUMBLE(TABLE ev, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
                         GROUP BY window_start, window_end, usr" 0,1,2
register w1 -> RUNNING fp=fc78e66138ac
  w1  RUNNING  fc78e66138ac  rowsIn=60000

$ probe2.sh sqln "SELECT * FROM w1" 40
  1767225600000000000  1767225610000000000  u0  2000
  1767225600000000000  1767225610000000000  u1  2000
  ... 25 rows, five windows x five users, every total exactly 2000 ...
$ probe2.sh sqln "SELECT * FROM w1 WHERE total <> 2000" 5
  (0 rows total)
```

**Verdict:** Hand-checked and exact. `ev.csv` is 60 000 rows, 1 000 per second across 60 seconds,
`amount = 1`, five users round-robin — so a 10-second window holds 10 000 rows, 2 000 per user, and
every output row must read 2 000. All 25 do, and the `total <> 2000` probe returns nothing. The
`pravaha.streams.ev.event-time` declaration reaches the plugin (`sources bound: [ev <-
filesystem[event.time, path, schema]]` in the startup log) and rows are stamped with it. Non-vacuous
by construction: with event time zero, which is what the original pass observed, no window in 2026
would ever close and the view would hold nothing, which is precisely what SQL-039 recorded.

Also confirmed: `COUNT(*)` per window is 5, so no user is double-counted across windows.

**But there are five windows, not six.** The sixth — 1767225650–660, holding 10 000 of the 60 000
rows — never closes. Sampled again after three minutes of a completely idle source with
`pravaha.watermark.idle-after: 5s` configured:

```
$ bin/pravaha query --sql "SELECT window_start, COUNT(*) AS n FROM w1 GROUP BY window_start"
window_start          n
1767225600000000000   5
1767225610000000000   5
1767225620000000000   5
1767225630000000000   5
1767225640000000000   5
5 rows
```

The idle timeout excludes an idle partition from the watermark minimum rather than advancing the
watermark past it, so a source with one partition that has reached end of file leaves its last
window open for ever. One sixth of the data is silently never emitted, with no indication anywhere.
(**DEFECT-18**)

### INGEST-057 — PASS

```
$ start.sh evbad          # pravaha.streams.evbad.event-time: no_such_column
DIED after 9s
Caused by: PRV-2002  stream 'evbad' declares 'no_such_column' as its event time and has no such
  column. Its columns are [id, usr, amount, event_time].
```

**Verdict:** The negative for the new configuration key is exactly right: refused at startup, names
the stream, names the bad column, lists the real ones. Non-vacuous — the same node starts cleanly
when the column exists, which is INGEST-056.

Incidental, and it cost me a restart: the schema grammar spells the type `TIMESTAMP`, not
`TIMESTAMP_LTZ`, even though that is the `TypeName` every error and every internal API uses. The
refusal lists the accepted spellings, so it is self-correcting, but the two names for one type are a
trap for anyone reading the engine's own error messages back into a config file.

### INGEST-058 — PASS on the refcount, FAIL on the outcome

```
$ jcmd <pid> Thread.print | grep -c '^"pravaha-feed'       # three names, one fingerprint
1
$ ls -l /proc/<pid>/fd | grep -c txn.csv
1
```

One feed thread and one descriptor for three shared names: the sharing mechanism itself is
unchanged and correct. The outcome after a drop is DEFECT-16 above.

### INGEST-059 — PASS

`recovered 2 of 2`, both recovered queries re-fed to 20 rows, view complete and not doubled. The new
unwind-on-journal-failure path did not disturb it. The INGEST-040 caveat still stands: with no
checkpoint directory, the file is re-read from line 1 on every restart.

---

## New defects found in this pass

Continuing the original numbering.

| # | Severity | Cases | Defect |
|---|---|---|---|
| 13 | **Blocker** | 046, 055 | **A lane thread that dies leaves a query reporting RUNNING for ever.** `Lane.run` catches `Throwable`, records it and exits; `QueryExecution.checkHealth()` would rethrow it and **nothing in `pravaha-server` calls it**. Observed twice: a plain projection dies at ~932 000 rows, a windowed aggregate over 50 000 keys at ~264 000, both with `PRV-3001 the projection's arena is full`. State stays `RUNNING`, `describe()` reports no failure (the feed really is healthy), `engineState` is `RUNNING`, the log is empty, and the orphaned feed thread spins on a full inbox burning a core — 575 s of CPU in 959 s of wall clock, measured. This is the original DEFECT-1's failure shape, one thread across. |
| 14 | **Blocker** | 042, 047, 048 | **Concurrent readers starve ingestion of the new monitor.** `ServedView.scan()` copies the whole committed map under the view's monitor — 347 ms mean, 1 015 ms max on a 500 000-row view — and `commit` on the feed thread waits behind it. Eight readers take ingestion from ~520 000 rows/s to ~2 000 rows/s and hold it there; one reader costs 3x. Proven by thread dumps: feed `BLOCKED` at `ServedView.commit:210`, monitor held by a Flight executor inside `ServedView.scan:319`. No rows are lost — the fix is correct, and this is its price. |
| 15 | High | 048 | **`evict()` runs a full O(view) sweep on every 20 ms commit, with the monitor held.** It short-circuits only for `Retention.forever()`; the default is 24 hours and the SDK's `register` has no retention parameter, so every Flight registration takes it. Ingest rate decays to a fifth as the view grows, and readers wait behind each sweep. `evicted=0` on all 1 400+ commits measured: the sweep never found anything. |
| 16 | High | 053, 058 | **Dropping the first name of a shared computation makes it unreadable under every name.** `views.remove(name)` shipped; the `StreamSchema.renamedTo` half that makes an alias queryable did not. After `drop v_a`, `v_b` and `v_c` report RUNNING and ingest, one surface says "this server serves [v_b, v_c]" and the next says "Known streams: [v_a]". Clears only on restart. The fix exists in the working tree (`pravaha-api` jar stamped 22:16 vs the app jar's 21:37) and is not in the artifact. |
| 17 | Medium | 045 | **A new `ConcurrentModificationException` in the watermark thread.** `QueryExecution.partitionHighWater` is a plain `LinkedHashMap`, iterated on the `pravaha-watermark` timer while registration puts into it. Caught and logged (`WARN could not advance the watermark`), so the clock survives one occurrence — but the watermark does not advance on that tick, and nothing counts how often it happens. Same class of bug as DEFECT-1, on a path the remediation newly switched on. |
| 18 | Medium | 056 | **A bounded source never closes its last window.** `idle-after` excludes an idle partition from the watermark minimum rather than advancing past it, so an end-of-file source leaves its final window open for ever: 10 000 of 60 000 rows silently never emitted, still absent after three minutes with `idle-after: 5s`. There is no end-of-stream watermark. |
| 19 | Medium | 046, 055 | **`arena.slab.size` is not a configuration key.** Six error messages in `pravaha-runtime` tell the operator to raise it, including the one that kills the lane in DEFECT-13. `grep -rn` finds it in those six strings and nowhere else — no `application.yaml` entry, no `@Value`, no mention in `docs/`. The only remedy the engine offers cannot be applied. |
| 20 | Low | — | **`bin/pravaha` has no Java version guard.** With `JAVA_HOME` unset it takes whatever `java` is on `PATH`; on this machine that is JDK 25, and every invocation dies 10/10 with `ExceptionInInitializerError` → `UnsupportedOperationException at io.netty.buffer.EmptyByteBuf.memoryAddress`. With `JAVA_HOME` pointed at 21 it works 10/10. A two-line version check would turn an Arrow/netty stack trace into "Pravaha requires Java 21". |

DEFECT-9 from the original pass is unchanged: `/api/v1/status` still reports the **stream** count in
the `registeredQueries` field (24 streams declared, reported as 24 queries while 9 were registered),
and `plugins` is still `[]`.

---

## Re-QA summary

| Verdict | Count |
|---|---|
| VERIFIED-FIXED | 2 (002, 004) |
| PARTIALLY-FIXED | 2 (029, 053) |
| STILL-FAILING | 10 (007, 016, 020, 021, 022, 024, 026, 027, 034, 037) |
| New cases PASS | 8 (041, 043, 044, 045, 054-confirm, 057, 059, 060) |
| New cases FAIL | 5 (042, 046, 047, 048, 049) |
| New cases mixed | 2 (055, 056 — pass with a defect) |
| New defects | 8 (13-20) |

**What I could not cover, and why.** Steady-state backpressure against a consumer that is slow
rather than dead is still untested: the only genuinely slow consumer I could build — a windowed
aggregate over 50 000 keys — dies of DEFECT-13 at 264 000 rows. The pause/resume mechanism itself is
now verified (INGEST-055), which is more than the original pass could say. `IngestPump`'s
`pauseCount`/`resumeCount`/`pausedNanos` are still exposed through no surface at all, so everything
above came from a harness reaching into the JVM; the same is true of `Lane.failure()` and
`QueryExecution.checkHealth()`, which is how DEFECT-13's cause was obtained and is exactly why
DEFECT-13 is invisible in production. Multi-partition sources, plugins other than `filesystem`, and
checkpoint/offset restore remain uncovered for the reasons given in the original pass. Finally: the
working tree moved while I tested — `StreamSchema`, `ViewCatalog`, `QueryRegistry`,
`RegisteredQuery`, `QueryExecution` all carry uncommitted edits, and there is a new untracked
`EngineHealthIndicator.java` — so INGEST-053 and DEFECT-16 in particular should be re-run against
the next build before anyone acts on them.
