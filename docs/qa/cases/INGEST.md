# INGEST — source bindings, plugin feeds, and rows reaching a registered query

The surface under test is the newest code in the release: `pravaha-server/.../server/ingest/`
(`PluginSourceFeeds`, `PumpingFeed`, `SourceBinding`, `SourceBindingProperties`, `IngestErrors`),
the registry contract it implements (`SourceFeed`, `SourceFeedFactory`), and the path from
`pravaha.streams.<name>.schema` + `pravaha.sources.<name>` through `ServiceLoader` to a row visible
in a served view.

**Ports:** HTTP 18100, Flight 19100. **Scratch:** `$QA = .../scratchpad/qa-ingest`.

**Standing setup, used by most cases.** One server started from `$QA/conf/main.yaml`, which declares
twenty streams under `pravaha.streams` and binds nineteen of them under `pravaha.sources` — one
stream per hostile input (empty file, header-only, ragged, wide, bad type, blank lines, null in a
NOT NULL column, a 500k-row file, a directory, a missing path, a plugin name that does not exist, a
plugin that may not be on the classpath, and two deliberate catalog/plugin schema disagreements).
`unbound` is declared and deliberately left with no binding.

Everything is driven through the shipped surfaces — `bin/pravaha-server`, `bin/pravaha` over Flight
SQL on 19100, and the HTTP API on 18100 — except where a case says otherwise and says why.

**A note on vacuity.** Almost every case here can pass by accident, because "no rows" is the failure
mode of the entire area. The standard proof used throughout: the same query shape is run against
`txn`, a known-good 20-row file, in the same server run, and must return exactly 20 rows. A case
that reports zero rows for a hostile input is only meaningful when the control returned 20 in the
same process.

---

## INGEST-001 — A configured binding actually feeds a registered query
**Intent:** The headline claim of the release: the P0 was that a registered query received nothing.
Prove a row configured in a file reaches the engine at all.
**Setup:** Server up on `main.yaml`. `txn` is bound to a 20-line CSV.
**Steps:** `pravaha register --name v_txn --sql "SELECT id, usr, amount FROM txn" --keys 0`; wait;
`pravaha queries`.
**Expected:** `v_txn` is `RUNNING` and its ROWS IN is exactly 20 — not 0, and not more than 20.

## INGEST-002 — Rows that reached the engine are readable from the served view
**Intent:** The brief's first hostile point: rows reaching the engine is not the same as rows being
readable. `rowsIn` counts what the feed handed over; a view shows its *committed* frontier.
**Setup:** INGEST-001 done.
**Steps:** `pravaha query --sql "SELECT * FROM v_txn"`.
**Expected:** 20 rows printed, contents matching the CSV. A non-zero ROWS IN with an empty view is a
FAIL and is precisely the bug `afterDelivery` was added to fix.

## INGEST-003 — Visibility latency of a newly fed row is bounded by the publish timer
**Intent:** `PumpingFeed.PUBLISH_INTERVAL_NANOS` is 20ms. Measure what an operator actually waits
for, and confirm it is tens of milliseconds rather than "never" or "seconds".
**Setup:** Fresh server run so the file is read from BEGINNING at registration time.
**Steps:** Register a query over `txn`, then poll `SELECT * FROM v_txn` in a tight loop from
registration, recording the elapsed time at which the row count first reaches 20.
**Expected:** The full 20 rows are visible well under one second. Record the observed figure.

## INGEST-004 — The last rows of a finite file become visible
**Intent:** The specific historical bug named in `publishPeriodically`'s comment: committing only on
the edge into idle leaves a fully-read file invisible. The tail is where that shows.
**Setup:** `tail100` is bound to a 100-line file whose last row is `100,u2,100`.
**Steps:** Register `SELECT id, usr, amount FROM tail100`; after the source has run dry (poll until
ROWS IN stops changing, then wait a further 2s), `SELECT * FROM v_tail` and look for id=100.
**Expected:** 100 rows, the last of them id=100. Not 99, not 96 (a batch boundary), not 0.

## INGEST-005 — `describe()` says what a bound feed is reading
**Intent:** `SourceFeed.describe()` is documented as the operator's tool for telling "nothing bound"
from "bound and quiet". Check the string is right for a bound feed.
**Setup:** Server up with `txn` bound.
**Steps:** Reach `RegisteredQuery.feed().describe()` for a query over `txn`.
**Expected:** Text naming the stream and its partition count, e.g. `reading txn (1 partition)`.

## INGEST-006 — A stream with no binding still registers, and the feed says nothing is attached
**Intent:** The documented contract in `PluginSourceFeeds`: a stream with no binding is not an
error, because an embedder pushes rows through `accept`. Registration must succeed and the feed must
be `SourceFeed.NONE`.
**Setup:** `unbound` is declared under `pravaha.streams` with no `pravaha.sources` entry.
**Steps:** `pravaha register --name v_unbound --sql "SELECT id, usr, amount FROM unbound"`; then
`pravaha queries`.
**Expected:** Registration succeeds, state RUNNING, ROWS IN 0, and the feed describes itself as
`no source is bound to this query's streams`.

## INGEST-007 — `describe()` is reachable by an operator through a shipped surface
**Intent:** INGEST-006 is only worth anything if a human can see the answer. The whole justification
for `describe()` is diagnosing a silent query. Check whether any shipped surface exposes it.
**Setup:** Server up, `v_unbound` and `v_txn` both registered.
**Steps:** `pravaha queries`; `GET /api/v1/status`; `GET /status`; the startup and steady-state
logs; every Flight action the producer answers.
**Expected:** At least one surface shows the feed description (or the fact that nothing is bound)
for a named query. If none does, the diagnostic exists only for unit tests and the case FAILS.

## INGEST-008 — Pause stops rows *arriving*, not merely stops them being accepted
**Intent:** Named in the brief and in `SourceFeed`'s own javadoc: `accept` drops rows while paused,
but a feed writes straight into the lane inbox. A pause that does not reach the feed is a pause in
name only.
**Setup:** A stream slow enough to observe mid-flight: `big` (500k rows).
**Steps:** Register over `big`; pause almost immediately; sample ROWS IN twice, ~3 seconds apart.
**Expected:** The two samples are equal (allowing for at most one in-flight batch of 1024 between
the pause call and the first sample). Steadily rising ROWS IN after a pause is a FAIL.

## INGEST-009 — Resume continues from where it stopped, with no gap and no repeat
**Intent:** A pause that loses its place is worse than no pause.
**Setup:** INGEST-008 left a paused query part-way through `big`.
**Steps:** Resume; wait for the source to run dry; read `SELECT COUNT(*)`-equivalent by counting
rows from the view, and ROWS IN.
**Expected:** ROWS IN is exactly 500000 — a gap gives fewer, a replay gives more.

## INGEST-010 — Two registrations of the same SQL share one feed and one set of rows
**Intent:** Sharing is by fingerprint. `SourceFeedFactory` is documented as called once per
computation; feeding per name would double every row.
**Setup:** Fresh server so `txn` is read from the start.
**Steps:** Register `v_a` and `v_b` with byte-identical SQL over `txn`. Compare fingerprints, ROWS
IN for each name, and the row count of each view.
**Expected:** Same fingerprint; ROWS IN 20 for both (one counter behind two names, not 40); 20 rows
in each view, not 40.

## INGEST-011 — A shared registration does not open a second file handle
**Intent:** The other half of INGEST-010: even if counting is right, opening a second reader would
mean a second read of the file and a second open handle.
**Setup:** INGEST-010 done, both names live.
**Steps:** Count open descriptors on the CSV in `/proc/<pid>/fd`, and count `pravaha-feed-*` threads.
**Expected:** One feed thread for the shared computation, not two.

## INGEST-012 — A self-join naming one stream twice does not double-feed it
**Intent:** `PluginSourceFeeds.open` calls `.distinct()` specifically for this. Prove the guard
works, or that the query is refused for an unrelated reason and say so.
**Setup:** `txn` bound.
**Steps:** Register `SELECT a.id, a.usr, b.amount FROM txn a JOIN txn b ON a.id = b.id`.
**Expected:** Either the query registers and ROWS IN is 20 (one feed), or the planner refuses the
self-join with a clear error. 40 rows fed is a FAIL.

## INGEST-013 — Dropping one of two shared names leaves the other fed
**Intent:** The refcount in `QueryRegistry.drop` is what makes implicit sharing safe. Dropping the
first name must not close the feed the second name depends on.
**Setup:** INGEST-010's `v_a` and `v_b`.
**Steps:** `pravaha drop --name v_a`; then read `v_b` and its ROWS IN.
**Expected:** `v_b` still RUNNING, still 20 rows, view still answers.

## INGEST-014 — A plugin name that is not on the classpath fails the registration with PRV-5090
**Intent:** `IngestErrors.NO_SUCH_PLUGIN`. The message is supposed to list what *is* available,
which is the whole difference between a fixable and an unfixable error.
**Setup:** `noplug` is bound to `plugin: kafka`.
**Steps:** `pravaha register --name v_noplug --sql "SELECT id, usr, amount FROM noplug"`.
**Expected:** Registration fails with PRV-5090, a message naming `kafka` and listing the plugins
that *are* present. The name must not be left registered afterwards.

## INGEST-015 — A path that does not exist fails the registration with PRV-5091
**Intent:** `IngestErrors.BINDING_FAILED` — the plugin opened and refused. Distinct code from 5090
because the fixes are different.
**Setup:** `nopath` points at a file that is not there.
**Steps:** Register over `nopath`.
**Expected:** PRV-5091 naming the absolute path. Registration refused, name not left behind.

## INGEST-016 — A path that is a directory fails cleanly rather than hanging or corrupting
**Intent:** `FilesystemSourcePlugin.open()` only checks `Files.isReadable`, which is true of a
directory. What happens next is the interesting part.
**Setup:** `dirpath` points at an empty directory.
**Steps:** Register over `dirpath`; observe the error, the registration state, and whether the
server is still healthy afterwards.
**Expected:** A clear error at registration or a feed that fails and says why. Not a hang, not a
zero-row query that looks healthy, not an unhandled `IsADirectoryException` taking out a thread
silently.

## INGEST-017 — An empty file registers, feeds nothing, and reports no failure
**Intent:** The boundary case that passes vacuously by definition — so it is tested against a
control in the same process.
**Setup:** `empty` bound to a zero-byte file.
**Steps:** Register over `empty`; check state and ROWS IN; in the same server run confirm `v_txn`
reads 20.
**Expected:** RUNNING, ROWS IN 0, no failure recorded, view empty. The `txn` control proving the
machinery was working at the time.

## INGEST-018 — A file with only a header yields no rows
**Intent:** One line in, zero rows out. With `skip.header: true` the single line must be consumed as
a header, not decoded as a row (it would fail type decoding if it were).
**Setup:** `hdronly` bound to a one-line file `id,usr,amount` with `skip.header: true`.
**Steps:** Register over `hdronly`; check ROWS IN and whether the feed reports a failure.
**Expected:** ROWS IN 0, no feed failure. A decode error here means the header was not skipped.

## INGEST-019 — `skip.header: true` with data skips exactly one line
**Intent:** Off-by-one in the header skip would either eat a data row or feed the header as data.
**Setup:** `hdr` bound to a header plus 10 data rows, `skip.header: true`.
**Steps:** Register over `hdr`; read the view.
**Expected:** Exactly 10 rows, ids 1..10, and no row whose `usr` is the literal `usr`.

## INGEST-020 — A ragged row (too few fields) — does one bad line kill the whole feed?
**Intent:** `FilesystemPartitionReader.poll` says in a comment that "one malformed line must not
cost the batch; the engine's DLQ handles the record" and then rethrows. `PumpingFeed.run` catches
`PravahaException` and `return`s, ending the feed thread. If that reading is right, one bad line
stops ingestion for the whole query, permanently, and the good rows after it are never read.
**Setup:** `ragged` bound to `1,a,10 / 2,b / 3,c,30 / 4,d,40` — one short line, two good lines
after it.
**Steps:** Register over `ragged`; wait; read ROWS IN and the view.
**Expected (correct behaviour):** the malformed line is rejected to a DLQ or dropped with a warning,
and rows 1, 3, 4 are all fed — 3 rows. **If only row 1 arrives and the feed then stops for good,
this is a FAIL**, and a severe one for any real file.

## INGEST-021 — A row with too many fields
**Intent:** Same mechanism, other direction. `DelimitedCodec.decode` refuses on field count either
way.
**Setup:** `wide` bound to `1,a,10 / 2,b,20,EXTRA / 3,c,30 / 4,d,40`.
**Steps:** Register over `wide`; read ROWS IN and the view.
**Expected:** As INGEST-020: rows 1, 3, 4 fed if bad lines are survivable; only row 1 if they are
fatal.

## INGEST-022 — A value that is not the declared type
**Intent:** The commonest real-world malformation — a letter in a numeric column at 3 a.m.
**Setup:** `badtype` bound to `1,a,10 / 2,b,notanumber / 3,c,30 / 4,d,40`.
**Steps:** Register over `badtype`; read ROWS IN and the view.
**Expected:** As INGEST-020. Also check that the error message names the line number and the column,
which `DelimitedCodec.setField` promises.

## INGEST-023 — Blank lines are skipped, not fed as rows
**Intent:** `poll` skips `line.isEmpty()`. A trailing newline is present in nearly every real file
and must not produce a row or a decode failure.
**Setup:** `blanks` bound to 3 data rows with 3 blank lines interleaved.
**Steps:** Register over `blanks`; read ROWS IN and the view.
**Expected:** Exactly 3 rows, no feed failure.

## INGEST-024 — An empty field in a NOT NULL column is refused
**Intent:** The null literal defaults to the empty string, and a non-nullable column must reject it
rather than write a garbage value.
**Setup:** `nullcol` (schema `usr:STRING`, not nullable) bound to a file whose row 2 is `2,,20`.
**Steps:** Register over `nullcol`; read ROWS IN, the view, and any feed failure message.
**Expected:** The bad row does not become a row with an empty `usr` silently. Behaviour on the
*rest* of the file is the INGEST-020 question again.

## INGEST-025 — A nullable column accepts the null literal
**Intent:** The positive control for INGEST-024: prove the refusal in 024 is about nullability and
not about empty fields in general.
**Setup:** `nullok` bound to the same file with schema `usr:STRING?`.
**Steps:** Register over `nullok`; read ROWS IN and the view.
**Expected:** 3 rows, row 2's `usr` NULL.

## INGEST-026 — Catalog schema and plugin schema disagree on column count
**Intent:** The nastiest configuration mistake available here, and nothing validates against it. The
pump builds its `RowLayout` from the *catalog* schema (`QueryExecution.pumpInto` →
`pipelines.inputSchema`), while `DelimitedCodec` decodes using the *plugin's* `schema` option. Four
columns written into a three-column layout is an out-of-bounds write on the hot path.
**Setup:** `mismatch`: catalog `id:INT64,usr:STRING,amount:INT64`; plugin option
`id:INT64,usr:STRING,amount:INT64,extra:STRING`; file has 4 fields per line.
**Steps:** Register over `mismatch`; observe the error, the view contents, and whether the server
survives.
**Expected:** Refused at registration with a message naming the disagreement. A silent success, a
corrupted row, or a JVM-level failure is a FAIL — and the absence of *any* validation that the two
schemas agree is itself a finding whatever the runtime does.

## INGEST-027 — Catalog schema and plugin schema disagree on types
**Intent:** The quieter half of INGEST-026: same column count, different types. A STRING written
into an INT64 slot is a type-confused read, not an exception.
**Setup:** `typemix`: catalog `id:INT64,usr:STRING,amount:INT64`; plugin option
`id:STRING,usr:STRING,amount:STRING`; file is `1,a,10 / 2,b,20 / 3,c,30`.
**Steps:** Register over `typemix`; read the view and compare against the file.
**Expected:** Refused, or values that match the file. Values that do not match the file — garbage
integers read from string bytes — is a FAIL of the highest severity, because nothing anywhere
reports it.

## INGEST-028 — A file deleted while it is being read
**Intent:** Named in the brief. On Linux an open handle survives `unlink`, so the honest question is
whether the feed keeps going to the end of the deleted file and whether anything reports the file is
gone.
**Setup:** `gone` bound to a 200k-row file.
**Steps:** Register over `gone`; delete the file within the first second; sample ROWS IN until it
stops; compare to 200000.
**Expected:** Either all 200000 rows are read from the surviving handle (POSIX behaviour, acceptable
and worth documenting), or the feed stops and `describe()` records why. A partial read with no error
anywhere is a FAIL.

## INGEST-029 — A huge file: every row arrives and every row becomes visible
**Intent:** The scale case. Also the strongest test of the visibility timer, because it runs for
many publish intervals.
**Setup:** `big`, 500000 rows, in a fresh server run (not the one INGEST-008/009 paused).
**Steps:** Register over `big`; poll ROWS IN until stable; then count rows readable from the view.
**Expected:** ROWS IN 500000 and the view answers with 500000 rows. Record the wall time and the
rate.
**Note on vacuity:** the view row count must be read, not inferred from ROWS IN — those are the two
numbers the P0 showed disagreeing.

## INGEST-030 — Backpressure: a source far faster than the query
**Intent:** The brief asks whether it degrades or falls over. The file source is effectively
unbounded rate; the lane inbox is finite; `IngestPump.updateBackpressure` should pause the reader at
the high watermark.
**Setup:** `big`, with an aggregating query so the lane does real per-row work.
**Steps:** Register `SELECT usr, COUNT(*) FROM big GROUP BY usr` (or the nearest the planner
accepts); watch ROWS IN progress and the server's memory/threads; confirm completion.
**Expected:** Steady progress to 500000, no OutOfMemory, no unbounded heap growth, no dropped rows,
no thrown `PRV-…BACKPRESSURED` escaping to the feed.

## INGEST-031 — Dropping a query while it is actively being fed
**Intent:** `PumpingFeed.close` sets `closed`, unparks, joins for 5s, then closes pumps and
resources. A drop under load is where an ordering mistake shows.
**Setup:** A query over `big` mid-read.
**Steps:** `pravaha drop --name v_drop` while ROWS IN is still rising; time the call; then check the
server log for exceptions and check that `pravaha queries` no longer lists it.
**Expected:** Drop returns promptly (well under the 5s join budget), no exception in the log, the
feed thread gone, the file descriptor released.

## INGEST-032 — Register / drop / register rapidly
**Intent:** A tired operator re-registering, and the classic way a thread or handle leak becomes
visible.
**Setup:** Server up.
**Steps:** 10 iterations of register-over-`txn` then drop, under different names; measure
`pravaha-feed-*` thread count and open descriptors on the CSV before and after.
**Expected:** Both counts return to their starting values. Each iteration must also feed 20 rows —
a loop that silently stopped feeding would otherwise pass this case.

## INGEST-033 — No leaked `pravaha-feed-*` threads after a clean drop
**Intent:** The explicit ask in the brief. Measured from `/proc/<pid>/task/*/comm` rather than
inferred.
**Setup:** Baseline thread list captured with nothing registered.
**Steps:** Register several queries over different streams, confirm the feed threads exist by name,
drop them all, re-measure.
**Expected:** Feed threads appear named `pravaha-feed-<queryName>` while live, and none remain after
the drops.

## INGEST-034 — Which source plugins are actually on the server's classpath
**Intent:** `application.yaml` tells an operator that "filesystem, feedfile, jdbc and delta ship in
this repository" and are found by `ServiceLoader`. The brief asks for an honest answer about the
*server's* classpath, which is a different question from what the repository builds.
**Setup:** The shipped `pravaha-server-…-app.jar`.
**Steps:** Enumerate `BOOT-INF/lib` in the jar; and empirically, bind `feedfile` and register over
it, reading the `Available:` list in the PRV-5090 message.
**Expected:** The documentation and the shipped jar agree. If only `filesystem` is present, this is
a documentation defect and must be reported as one.

## INGEST-035 — A source bound to a stream that was never declared
**Intent:** The two blocks are independent, and `SourceBindingProperties`' own javadoc says a
binding without a declaration was historically baffling. Check the current behaviour.
**Setup:** A config with `pravaha.sources.orphan` and no `pravaha.streams.orphan`.
**Steps:** Start a server on that config; register `SELECT * FROM orphan`.
**Expected:** The server starts (a binding is not a declaration), and the registration is refused
with an error that mentions the stream is unknown. Ideally a startup warning names the orphan; check
whether one exists.

## INGEST-036 — Broken bindings do not stop the server from starting
**Intent:** Bindings are resolved lazily at registration. A node with one bad binding out of twenty
must still serve the other nineteen — the opposite policy would make one typo an outage.
**Setup:** `main.yaml`, which contains a nonexistent plugin, a missing path and a directory path.
**Steps:** Start the server; check it reaches `Started PravahaServerApplication`; check the bound
list is logged.
**Expected:** Clean start, all bindings logged, no validation of any of them at startup. Note
whether a startup-time check would have been better — it is a trade-off worth recording either way.

## INGEST-037 — A feed that has failed says so, and stops counting
**Intent:** `PumpingFeed` records `failure` and returns; `describe()` is supposed to carry the
reason. Check both halves: the count stops, and the reason is retrievable.
**Setup:** Whichever of 020/021/022/024 produced a mid-read failure.
**Steps:** Sample ROWS IN twice 3s apart; retrieve the query state and any diagnostic.
**Expected:** ROWS IN frozen, state still RUNNING (by design), and *some* surface saying why. A
query that looks healthy, reports RUNNING, and has silently stopped ingesting is the worst outcome
here.

## INGEST-038 — Two *different* queries over the same stream each get their own complete feed
**Intent:** The complement to sharing: different fingerprints must not share, and each must read the
whole file from the beginning.
**Setup:** `txn` bound.
**Steps:** Register `SELECT id, usr, amount FROM txn` and `SELECT id, amount FROM txn` — different
plans, different fingerprints.
**Expected:** Two feeds, two file handles, 20 rows each. One of them seeing a partial file because
the two shared a reader would be a serious FAIL.

## INGEST-039 — Pausing one name of a shared computation
**Intent:** Sharing makes pause ambiguous: `v_a` and `v_b` are one computation, so pausing `v_a`
pauses `v_b`'s data too. Establish what actually happens and whether anything warns.
**Setup:** `v_a` and `v_b` sharing a fingerprint over `big`.
**Steps:** `pravaha pause --name v_a`; sample ROWS IN for both names.
**Expected:** Document the truth. Both frozen is defensible and should be *said*; a pause that
silently starves another tenant's view with no indication is a usability defect worth recording.

## INGEST-040 — Restart with a journal: is the query re-fed, and from where?
**Intent:** No checkpoint directory means no source offset is restored. A journalled query coming
back and re-reading the file from line 1 means duplicate rows after every restart.
**Setup:** A config with `pravaha.registry.journal` set and no checkpoint directory.
**Steps:** Register over `txn`; confirm 20 rows; stop the server by PID; restart on the same config;
read ROWS IN and the view for the recovered query.
**Expected:** Document the truth precisely. 20 rows again from a fresh read is defensible for a
file source; 40 rows in the view, or a recovered query that is never fed at all, is a defect.

---

# Re-QA cases — verification pass

Written before execution, as the original set was. These cover only the re-verification: the
thirteen FAILs, the code paths their fixes touched, and the two things the original pass could not
test because DEFECT-1 killed every attempt (ingest and serving together at scale, and backpressure).

**New data.** `$QA/data/huge.csv` (2 000 000 rows, `id` unique), `$QA/data/gone.csv` recreated at
200 000, `$QA/data/ev.csv` (60 000 rows over exactly 60 s carrying an event-time column, 5 users,
`amount=1` on every row so every window sum is hand-computable), `$QA/data/evbig.csv` (500 000 rows
over 500 s, 50 000 distinct keys, for a deliberately state-heavy consumer).
**New config.** `$QA/conf/main2.yaml` — `main.yaml` plus `huge`, `ev`, `evbig`, with
`pravaha.streams.ev.event-time` / `.out-of-orderness` and `pravaha.watermark.{idle-after,tick}`.

**Keying rule for every DEFECT-1 case.** Every view below is keyed on ordinal 0, `id`, which is
UNIQUE in every file used. This is deliberate: the remediation's own commit message records that the
first reproduction attempt failed because the view was keyed on a 500-value column, so 200 000 rows
collapsed to a correct-looking 500. A view keyed on a unique column has size == rowsIn or it has
lost rows, and there is nowhere for loss to hide.

## INGEST-041 — DEFECT-1 at scale, ingest and serving together, unique key
**Intent:** The headline fix. The combination the original pass could never run: a query that
actually writes to its view, over a large file, with the view keyed on a unique column so that any
lost row is visible as a count.
**Setup:** `main2.yaml`; stream `big` (500 000 rows, `id` unique).
**Steps:** Register `SELECT id, usr, amount FROM big` keyed on 0. Wait for `rowsIn` to stop moving.
Read `rowsIn` and `SELECT * FROM <view>`.
**Expected:** `rowsIn = 500000` **and** view size `= 500000`. Anything less in either is
STILL-FAILING. A `ConcurrentModificationException` anywhere in the server log is a fail regardless
of the counts.

## INGEST-042 — Many concurrent readers while ingesting
**Intent:** The commit says the reader is "the third thread and the one a deployment actually has".
Eight readers scanning continuously while the feed commits is the shape that most exercises the new
monitor, and the shape most likely to expose a lock the fix did not cover.
**Setup:** As INGEST-041.
**Steps:** Register the query and immediately start 8 threads, each on its own Flight connection,
scanning the view in a loop until `rowsIn` settles. Count scans, reader errors, and the maximum
single-scan latency.
**Expected:** `rowsIn = view size = 500000`; zero reader errors; no exception in the log. A reader
seeing a *partial* commit is not directly observable, but a scan returning a count above the final
total, or an error, would prove it.

## INGEST-043 — Several distinct queries over one stream, all serving, at scale
**Intent:** INGEST-038 established that different fingerprints get their own feed and their own
reader. Under the fix each of those feeds now commits into its own view on its own thread. Four
concurrent feeds over one file is the multi-writer case.
**Setup:** As INGEST-041.
**Steps:** Register four queries over `big` with four distinct plans, all writing to their views,
all keyed on `id`. Wait for all to settle.
**Expected:** Each reports `rowsIn = 500000` and each view holds exactly the number of rows its
predicate selects. No feed dies.

## INGEST-044 — A query dropped while another reads the same stream at scale
**Intent:** `drop` now calls `views.remove(name)`, which is new code on a path that runs while other
feeds are committing. A drop mid-ingest must not disturb the survivors, and the dropped name must
stop answering.
**Setup:** Two distinct queries over `big`, both serving.
**Steps:** With both mid-file, drop one. Immediately query the dropped name and the survivor.
**Expected:** The dropped name no longer answers (`Object not found`). The survivor finishes at
500 000 in and 500 000 in its view.

## INGEST-045 — Pause and resume under load, with a view being written
**Intent:** INGEST-008/009 could only test pause against a query that wrote nothing. Repeat them
against a query that commits, which is the combination the fix changed.
**Setup:** A serving query over `big`.
**Steps:** Pause mid-file; sample `rowsIn` and the view size four times; resume; wait for settle.
**Expected:** Both frozen while paused, both equal, and both reach exactly 500 000 after resume —
not more (a replay) and not less (a gap).

## INGEST-046 — A very large file: 2 000 000 rows, ingest and serving together
**Intent:** DEFECT-1's probability rose with input size. Four times the largest file the original
pass used, with the view actually being written, is the strongest available disproof.
**Setup:** Stream `huge`.
**Steps:** Register a serving query keyed on `id`; wait for settle; compare.
**Expected:** `rowsIn = view size = 2000000`.

## INGEST-047 — Did the lock collapse throughput?
**Intent:** The named regression risk. A monitor taken on every `apply` and every `commit`, with
readers contending for it, could stall ingestion instead of losing rows — a different failure with
the same symptom (a view that never fills).
**Setup:** Streams `big` and `huge`.
**Steps:** Measure rows/s for (a) the original pass's no-output control — `WHERE id > 999999999`,
which never touches the overlay — and (b) the same file with a serving query, with and without 8
concurrent readers.
**Expected:** A number, recorded. Serving is expected to cost something; the question is whether it
costs an order of magnitude. Below ~10 000 rows/s for a local CSV, or readers making ingestion more
than ~2x slower, is a regression worth filing.

## INGEST-048 — Does a reader block for long behind the lock?
**Intent:** The other half of the same risk, from the reader's side. `scan()` copies the whole
visible map under the monitor; at 500 000 rows that copy is not free, and `commit` waits behind it.
**Setup:** A filled 500 000-row view.
**Steps:** 20 sequential scans; report mean and max latency. Repeat while a second feed is ingesting.
**Expected:** A number, recorded. A scan that takes seconds, or a max far above the mean, says the
monitor is the bottleneck.

## INGEST-049 — Is a dead feed now visible? (DEFECT-6)
**Intent:** The original pass's second-worst finding: a dead feed and a healthy one are
character-for-character identical to an operator. `PumpingFeed` now records the failure for every
throwable; the question is whether anything an operator can read says so.
**Setup:** `ragged` (a malformed line on line 2).
**Steps:** Register over it. Then look everywhere a shipped surface could say: `pravaha queries`,
the Flight LIST action, `/api/v1/status`, `/status`, and the server log.
**Expected:** Somewhere an operator can reach, a query whose feed has died is distinguishable from
one that is merely quiet. If not, say exactly what an operator would see instead.

## INGEST-050 — DEFECT-2/3 confirmation: a malformed line still ends ingestion
**Intent:** Not fixed, per the brief. Confirm precisely, so the record is accurate.
**Setup:** `ragged`, `wide`, `badtype`, `nullcol`.
**Steps:** Register over each; read `rowsIn`, the view, and the recorded failure.
**Expected:** Confirm or refute: ingestion ends at the bad line; rows before it are lost
(`rowsIn=0`); the diagnostic is replaced by `abort()`'s `UnsupportedOperationException`.

## INGEST-051 — DEFECT-4 confirmation: no schema validation
**Setup:** `mismatch` (4-column plugin schema, 3-column catalog) and `typemix`.
**Steps:** Register over each.
**Expected:** Confirm registration succeeds and the feed dies at the first row, with nothing
validated at bind time. Re-check specifically that the new `event.time` schema rebuild in
`FilesystemSourcePlugin.open` has not changed what the plugin decodes with.

## INGEST-052 — DEFECT-8 confirmation: a directory as `path`
**Setup:** `dirpath`.
**Expected:** Confirm `open()` still accepts a directory and the feed dies on first read.

## INGEST-053 — DEFECT-5: the second name answers, and survives a restart
**Intent:** Both halves of the fix. `views.registerAs` makes the second name queryable;
`journalRegistration` on the shared path makes it survive a restart. The second is the half that
cannot be checked without stopping the server.
**Setup:** `journal.yaml` with `pravaha.registry.journal` set.
**Steps:** Register `v_a` and `v_b` with identical SQL. `SELECT * FROM v_a` and `FROM v_b`. Stop the
server by PID; restart on the same config; check the recovery line, then query both names again.
**Expected:** Both names return the same rows before and after the restart, and the recovery line
accounts for both. Adversarial extension: drop one name and confirm the other still answers, and
that the dropped one does not.

## INGEST-054 — DEFECT-7 confirmation: only `filesystem` is on the classpath
**Expected:** Confirm `Available: [filesystem]` and that the app jar still ships one plugin jar.

## INGEST-055 — Backpressure: a source far faster than its consumer
**Intent:** The gap the original pass declared untested. A windowed aggregate over 50 000 distinct
keys is a genuinely slow, state-heavy consumer, and a local CSV is a very fast source. This is the
first time the two can be run together.
**Setup:** `evbig` (500 000 rows, 50 000 keys, event-time declared).
**Steps:** Register a windowed aggregate over it and watch `rowsIn` advance. In parallel, read
`IngestPump.pauseCount()` / `pausedNanos()` from the in-process harness, since no shipped surface
exposes them.
**Expected:** Ingestion completes, every row is accounted for, nothing is dropped, no OOM, and the
pump is observed to pause and resume rather than either overflowing or deadlocking. If the pump
never pauses, say so and say why the consumer was not slow enough.

## INGEST-056 — Windowed ingestion end to end from configuration
**Intent:** The new `pravaha.streams.<n>.event-time` plus the plugin stamping rows. The original
pass could not run a windowed query at all.
**Setup:** `ev`: 60 000 rows, exactly 1 000 per second across 60 s, `amount=1`, 5 users
round-robin, `event-time: event_time`, `out-of-orderness: 1s`.
**Steps:** Register `SELECT window_start, usr, SUM(amount) ... TUMBLE(... INTERVAL '10' SECOND)`.
Wait, then read the view.
**Expected:** Hand-computed. Six 10-second windows; 10 000 rows each; 2 000 per user per window; so
each output row is `SUM(amount) = 2000`, and there are 5 users x however many windows the watermark
closed. With 1 s lateness the first five windows must close from the data alone; the sixth needs the
idle timeout. Any output row whose sum is not 2000 is a fail. Zero rows is a fail.

## INGEST-057 — `event-time` naming a column that does not exist
**Intent:** The new configuration key needs a negative. An operator will typo it.
**Setup:** A config declaring `event-time: no_such_column`.
**Expected:** The node refuses at startup with a message naming the stream, the bad column and the
real columns — not a node that starts and silently stamps nothing.

## INGEST-058 — Regression: sharing, refcount and the dropped view
**Intent:** `registerAs` and `remove` are new on the sharing path, which INGEST-010/011/013 covered.
Re-run them.
**Steps:** Three names on one fingerprint; count feed threads and file descriptors; drop one; check
the other two still answer; drop to zero and check the view stops answering.
**Expected:** One thread and one descriptor for three names; dropping one leaves the rest complete;
the last drop releases everything and the name stops answering.

## INGEST-059 — Regression: journal restart still re-feeds
**Intent:** `journalRegistration` moved inside a try/catch that unwinds. Confirm INGEST-040's result
is unchanged.
**Expected:** `recovered N of N`, the query re-fed, the view complete and not doubled.

## INGEST-060 — Adversarial: can the new monitor deadlock?
**Intent:** The commit says `awaitFrontier` was deliberately left outside the lock to avoid a
deadlock. Probe the reasoning: a consistent read that waits for a frontier, issued while the feed is
committing hard and other readers are scanning.
**Steps:** During a 2 000 000-row ingest, hold 8 readers scanning and issue repeated reads. Watch
for a stall. Take a thread dump if anything hangs and look for a cycle on the `ServedView` monitor.
**Expected:** No thread blocked indefinitely; the ingest completes.
