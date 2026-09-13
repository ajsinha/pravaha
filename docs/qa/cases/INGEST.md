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
