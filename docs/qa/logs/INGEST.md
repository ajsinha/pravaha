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
