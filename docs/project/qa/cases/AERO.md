# AERO — Aerospike plugin and the state tier

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Area under test: `plugins/pravaha-plugin-aerospike/` (source, sink, lookup, strategy, expression
pushdown, schema mapping, host parsing, client construction), `pravaha-state/`
(`FileCheckpointStore`, `CheckpointStore`, `L0StateMap`, `RowStore`, `StateErrors`) and
`pravaha-it/.../AerospikeContinuousQueryIT`.

The design calls Aerospike the flagship store and ADR-029 caps it at `lut-scan` / at-least-once.
These cases test what is built against what is claimed, in that order.

Written before execution. Ports reserved for this area: HTTP 18600-18619, Flight 19600-19619.
A real Aerospike Community server is run in Docker with `--network host`, which needs the host's
port 3000 — the only port outside the reserved range this area uses, and unavoidable because an
Aerospike client routes by the cluster's partition map (see `AerospikeContainer`).

---

## Group A — is any of this reachable?

## AERO-001 — the Aerospike source plugin is discoverable by `ServiceLoader`
**Intent:** `PluginSourceFeeds.discover` is the *only* production path from configuration to a
source plugin, and it uses `ServiceLoader.load(StreamSourcePlugin.class)`. A plugin with no
`META-INF/services` entry cannot be bound by any amount of correct configuration, however complete
the code behind it is.
**Setup:** the built `pravaha-plugin-aerospike` jar.
**Steps:** list the jar's `META-INF/services` entries; compare with the four other source plugins;
run `ServiceLoader.load(StreamSourcePlugin.class)` with the Aerospike jar explicitly on the
classpath and print every plugin name found.
**Expected:** `aerospike` appears in the loaded set. Anything else means the plugin is
programmatic-only regardless of configuration.

## AERO-002 — a server configured with `plugin: aerospike` reports the truth
**Intent:** the failure mode a user following a case study actually hits. If the plugin cannot be
loaded, the message must say so in a way that names what *is* available.
**Setup:** `bin/pravaha-server` on HTTP 18600 / Flight 19600, with
`pravaha.sources.txn.plugin=aerospike` and a declared `txn` stream.
**Steps:** start the server, register a query over `txn`, inspect the log and the registration
result.
**Expected:** either the stream is fed, or a `PRV-5002`-class error naming `aerospike` as absent and
listing the plugins that are present. A query that registers `RUNNING` and silently receives nothing
is a FAIL.

## AERO-003 — the Aerospike jar ships somewhere a server can use it
**Intent:** ADR-010 and the docs describe dropping a plugin jar in. Check whether the shipped server
artefact contains the Aerospike plugin, or has any documented mechanism to add it.
**Setup:** `pravaha-server/target/pravaha-server-0.1.0-SNAPSHOT-app.jar`.
**Steps:** list `BOOT-INF/lib` for plugin jars; grep the docs for an "add a plugin jar" procedure.
**Expected:** the plugin is present, or a documented, tested procedure exists to add it.

## AERO-004 — the sink and lookup plugins are reachable by configuration
**Intent:** `AerospikeSinkPlugin` and `AerospikeLookupPlugin` are two of the four main classes. A
sink or a lookup that can only be constructed in Java is not a product surface.
**Steps:** grep the whole tree for any `ServiceLoader.load(StreamSinkPlugin)` /
`ServiceLoader.load(LookupSourcePlugin)`, and for any configuration property that names a sink or
lookup plugin.
**Expected:** some production path exists from configuration to these plugins.

---

## Group B — strategies, guarantees and what is claimed

## AERO-005 — the three unimplemented strategies are refused with an explanation
**Intent:** ADR-029 makes the refusal the whole justification for keeping the enum. The refusal must
name the licence requirement and the alternative, not just fail.
**Steps:** configure a source with `strategy=xdr-kafka`, `xdr-http`, `write-intercept` in turn.
**Expected:** `ConfigurationException` with `PRV-5083`, naming Enterprise XDR and `lut-scan`.

## AERO-006 — an unknown strategy name is refused and lists the supported one
**Steps:** `strategy=lutscan`, `strategy=""`, `strategy=" LUT-SCAN "`.
**Expected:** the first two refused with `PRV-5083`; the third accepted (documented strip +
case-insensitive match). No `NullPointerException` or `StringIndexOutOfBoundsException` anywhere.

## AERO-007 — the source declares the capabilities ADR-029 says it must
**Intent:** the capability record is the mechanism ADR-029 relies on to make the scan-only decision
safe. If it over-declares, every query over Aerospike inherits a guarantee nobody can honour.
**Steps:** call `capabilities()` on a configured source and print every field.
**Expected:** `replayableOffsets=true`, `orderedWithinPartition=false`, `emitsDeletes=false`,
`emitsBeforeImage=false`, `guarantee=AT_LEAST_ONCE`, pushdown `{FILTER}`.

## AERO-008 — the sink does not claim a guarantee it cannot deliver
**Intent:** `AerospikeSinkPlugin`'s own Javadoc says claiming exactly-once "would make the engine
promise exactly-once output it cannot deliver". `SinkCapabilities.guarantee()` derives the answer
from `idempotentUpsert`. Check what the sink actually reports.
**Steps:** `new AerospikeSinkPlugin().capabilities().guarantee()`.
**Expected:** a value consistent with README line 155 ("treat the shipped guarantee as
at-least-once") and ADR-008 ("do not claim exactly-once"). `EXACTLY_ONCE` is a FAIL.

## AERO-009 — the engine computes a weakest-link guarantee per query
**Intent:** §14.4, §1070 and risk R5 all say the engine downgrades a query's stated guarantee to the
weakest of its source and sink and shows it. That is the control that makes an at-least-once source
safe to ship.
**Steps:** grep for every caller of `DeliveryGuarantee.weakest` and of
`SourceCapabilities.guarantee()` outside tests; register a query over an Aerospike-backed stream and
look for a guarantee in the registration response.
**Expected:** production code computes and surfaces a guarantee.

---

## Group C — configuration validation and misconfiguration

## AERO-010 — host list parsing rejects what it cannot parse
**Steps:** `hosts` = `127.0.0.1:3000` (valid), `127.0.0.1` (no port), `127.0.0.1:abc`,
`""`, `127.0.0.1:3000,` (trailing comma), `[::1]:3000` (IPv6), `127.0.0.1:99999` (out of range).
**Expected:** the valid one parses; each invalid one raises `ConfigurationException` `PRV-5083`
naming the offending entry. An out-of-range port must not be accepted silently.

## AERO-011 — numeric settings are validated, not just parsed
**Intent:** `partitions`, `records.per.second`, `ttl.seconds`, `cache.seconds`, `concurrency` and
the two scan timeouts are all read with `Integer.parseInt` on a user-supplied string.
**Steps:** for each, supply a non-numeric value (`abc`) and an out-of-range value
(`partitions=0`, `partitions=4097`, `records.per.second=-1`, `scan.socket.timeout.ms=0`,
`ttl.seconds=-1`, `cache.seconds=-1`, `concurrency=0`).
**Expected:** every one raises `ConfigurationException` with `PRV-5083` naming the setting. A raw
`NumberFormatException` reaching the operator is a FAIL: it names neither the plugin nor the key.

## AERO-012 — schema declaration rejects what Aerospike cannot store
**Steps:** `schema` = a bin name of 16 bytes; a bin name of exactly 15; `x:DECIMAL`; `x:UUID`;
`x` with no type; `""`; `a:INT64,,b:STRING`.
**Expected:** 15 bytes accepted, 16 refused naming the limit; `DECIMAL` refused with `PRV-5082` and
the documented workaround; unknown type refused with the supported list; malformed entries refused
naming the entry.

## AERO-013 — `key.bin` must be a declared column, on both sink and lookup
**Steps:** configure sink and lookup with `key.bin` naming a bin absent from `schema`.
**Expected:** `ConfigurationException` `PRV-5083` listing the declared columns, for both.

## AERO-014 — using a source before `open()` fails with a diagnosis
**Steps:** `configure(...)` then `createReader(...)` without `open()`.
**Expected:** `PravahaException` `PRV-5080` saying the source was not opened.

---

## Group D — connection and infrastructure failure

## AERO-015 — a host that does not resolve
**Steps:** `hosts=no-such-host.invalid:3000`, then `open()`.
**Expected:** `PravahaException` `PRV-5080` naming the host and port, within a bounded time (the
configured 10 s client timeout), not a hang and not a raw `AerospikeException`.

## AERO-016 — a port with nothing listening
**Steps:** `hosts=127.0.0.1:18619` (reserved to this area, nothing bound), then `open()`.
**Expected:** `PRV-5080` promptly, naming the address.

## AERO-017 — a namespace that does not exist
**Intent:** the commonest operator typo. It is not caught at configuration time, so the question is
whether the first scan explains it.
**Steps:** point a source at namespace `nosuchns` on a live server and poll.
**Expected:** `PRV-5081` naming the namespace and the set. Silently reading zero rows for ever is a
FAIL — it is indistinguishable from a quiet set.

## AERO-018 — a set that does not exist
**Steps:** namespace `test`, set `nosuchset`, poll.
**Expected:** defined behaviour, documented. (Aerospike treats an unknown set as empty, so zero rows
is correct here — recorded to distinguish it from AERO-017.)

## AERO-019 — credentials against a server that has no security
**Intent:** Community Edition has no user management. A `user`/`password` typo must not connect
anyway and must not appear in a log.
**Steps:** configure `user=admin,password=hunter2` against the CE container; `open()`; then grep the
captured output for the password.
**Expected:** either a clear authentication failure or a documented no-op; the password never
appears in any message, exception or log line.

## AERO-020 — TLS is configurable
**Intent:** the design targets a store holding trade and card data. A connector with no TLS option
cannot be deployed to one.
**Steps:** grep the plugin for `TlsPolicy`, `tls`, `ClientPolicy.tlsPolicy`; look for documented
settings.
**Expected:** a TLS configuration path exists, or the docs say plainly that it does not.

---

## Group E — offsets, resumption and the at-least-once claim

## AERO-021 — an offset written by another plugin is refused
**Steps:** create a reader with `SourceOffset("lsn=42")` and with `SourceOffset("lut=abc")`.
**Expected:** `PRV-5084` in both cases, naming the expected form.

## AERO-022 — `SourceOffset.BEGINNING` and null resume from the start
**Steps:** create readers with `null`, `SourceOffset.BEGINNING`, `SourceOffset("")`.
**Expected:** all three read every record in the set; none throws.

## AERO-023 — resuming from a recorded offset reads only what changed
**Intent:** the `replayableOffsets=true` declaration. If this is wrong, the capability record lies
and every recovery over Aerospike loses or repeats data.
**Steps:** write 3 records, scan, take `position()`, write 2 more, create a new reader at the
recorded offset, scan.
**Expected:** the second reader returns the 2 new records (and may repeat boundary records, which is
at-least-once). It must not return zero, and it must not return all 5 minus the new ones.

## AERO-024 — the offset is monotonic and survives a reader that read nothing
**Steps:** scan an empty set; read `position()`; scan again.
**Expected:** a well-formed `lut=<nanos>` token that does not go backwards.

## AERO-025 — a record written during a scan is re-read, not lost
**Intent:** `LutScanReader` takes the scan's *start* time as the new watermark specifically so the
in-flight window is re-read. Prove the safe direction is the one taken.
**Steps:** write a record whose last-update time is within the scan window, then resume from the
recorded offset.
**Expected:** the record appears again (duplicate, acceptable) rather than never (loss, not
acceptable).

---

## Group F — expression pushdown equivalence

The documented guarantee (`Pushdown` class comment): *"Pushdown is an optimisation over how much
arrives, never over what is correct"*, and (`AerospikeExpressions`): *"Untranslatable means absent,
never approximated… Every branch that cannot be exact returns null."* So for any predicate, the rows
Aerospike returns under pushdown must be a **superset** of the rows the engine's own filter keeps.
A row the engine would keep and the store declines to send is silent data loss.

## AERO-026 — equality on a string bin agrees with the engine
**Steps:** load records with `status` in {COMPLETED, PENDING}; read with pushdown
`status = 'COMPLETED'` and without; compare.
**Expected:** pushdown returns exactly the COMPLETED rows; no row the engine would keep is missing.

## AERO-027 — integer comparisons agree with the engine
**Steps:** `amount > 100`, `>=`, `<`, `<=`, `<>` over a mixed set, with and without pushdown.
**Expected:** superset property holds for every operator.

## AERO-028 — a boolean bin stored as a legacy 0/1 integer
**Intent:** `AerospikeSchemas.copyInto` deliberately accepts an integer in a BOOLEAN bin, citing
pre-5.6 data that "plenty of live data still looks like". `AerospikeExpressions` builds
`Exp.boolBin` for the same column.
**Steps:** write `active` as integer 1 and 0; declare `active:BOOLEAN`; read with pushdown
`active = true` and without.
**Expected:** the superset property holds — the pushed filter must not drop the rows the engine's
own filter would keep.

## AERO-029 — a bin whose stored type disagrees with the declaration
**Intent:** Aerospike is schemaless; the class comment says the declaration exists exactly because
another application can write a different type. `copyInto` coerces an integer into a declared STRING
via `String.valueOf`; `translate` builds `Exp.stringBin`.
**Steps:** write `status` as integer `7` in one record and string `"7"` in another; declare
`status:STRING`; read with pushdown `status = '7'` and without.
**Expected:** superset property holds.

## AERO-030 — a narrowing integer declaration
**Intent:** `copyInto` casts to `(byte)`/`(short)` for INT8/INT16; `translate` compares the full
64-bit value.
**Steps:** write `code` = 300; declare `code:INT8`; read with pushdown `code = 44` (300 truncated to
a byte) and without.
**Expected:** superset property holds.

## AERO-031 — `IS NULL` / `IS NOT NULL` pushdown
**Steps:** write records with and without an optional bin; push `IS NULL` and `IS NOT NULL`.
**Expected:** matches the engine's own answer, given that Aerospike does not store absent bins.

## AERO-032 — `<>` against a bin that is absent
**Intent:** SQL three-valued logic: `NULL <> 'X'` is unknown, so the engine drops the row. Aerospike
evaluates a missing bin differently.
**Steps:** records with and without `status`; push `status <> 'PENDING'`.
**Expected:** superset property holds, and the answer matches SQL semantics.

## AERO-033 — a filter naming a column the schema does not declare is not pushed
**Steps:** push a filter on `no_such_bin`.
**Expected:** `translate` returns null and every row arrives; the engine filters. No exception.

## AERO-034 — pushdown that cannot be translated leaves the filter with the engine
**Steps:** push `LT` on a STRING column (documented as untranslatable because of collation), and a
filter on a BYTES column.
**Expected:** null from `translate`, all rows returned, no exception.

---

## Group G — the sink

## AERO-035 — upsert by key, and a retraction deletes
**Steps:** write rows with weight +1, read them back with the client; write the same key with
weight −1; read back.
**Expected:** present, then absent. `recordsWritten` / `recordsDeleted` agree.

## AERO-036 — a sink → source round trip preserves the key
**Intent:** `upsert` deliberately skips the key ordinal ("the primary key is the record's identity,
not a bin"). `AerospikeSourcePlugin` reads bins only. So a set written by this sink and read by this
source is the flagship round trip.
**Steps:** write 3 rows through the sink; read the same set through the source with the same schema.
**Expected:** the key column arrives with the value it was written with.

## AERO-037 — an idempotent replay leaves the same end state
**Intent:** this is the property the "effectively-once output" claim rests on.
**Steps:** write the same batch three times; count records and compare bin values.
**Expected:** identical end state after 1 and after 3 writes.

## AERO-038 — TTL is applied per record
**Steps:** `ttl.seconds=2`; write; read immediately; wait; read again.
**Expected:** present then gone.

## AERO-039 — a null bin is written as an absent bin, not a zero
**Steps:** write a row with a null optional column; read the record back with the client.
**Expected:** the bin is absent, and a subsequent source read produces null rather than 0.

---

## Group H — the lookup

## AERO-040 — a point read by primary key, a miss, and a null key
**Steps:** look up a key that exists, one that does not, and `null`.
**Expected:** 1 row with the key column filled from the key asked for; 0 rows; 0 rows with no round
trip and no exception.

## AERO-041 — a lookup key of the wrong arity
**Steps:** `lookup(new Object[]{"a","b"}, sink)`.
**Expected:** `IllegalArgumentException` naming the arity. (Not a `PravahaException` — recorded as a
consistency observation.)

## AERO-042 — `cache.seconds` is honoured
**Intent:** the plugin accepts and validates `cache.seconds` and reports it through `cacheFor()`. If
nothing implements the cache, a configured 60 s cache is a promise nobody keeps.
**Steps:** configure `cache.seconds=60`; issue the same lookup twice; count server-side reads (via
`asinfo` client-read statistics or by deleting the record between the two lookups).
**Expected:** the second lookup is served without a second round trip, or the property is documented
as advisory to the engine rather than a plugin cache.

---

## Group I — deletes, the sharpest edge of ADR-029

## AERO-043 — a deleted record is invisible and the capability says so
**Steps:** write 3 records, scan, delete one, scan again from the recorded offset.
**Expected:** the delete is not reported (documented), and `capabilities().emitsDeletes()` is false
so the engine can refuse a query that needs them.

## AERO-044 — the engine refuses a query that needs deletes over a source that cannot emit them
**Intent:** ADR-029: "a query that needs more is refused at registration rather than discovering it
in production. That is the mechanism that makes this decision safe to take."
**Steps:** grep for any production code that reads `emitsDeletes()` and refuses a registration;
register an unbounded `COUNT(*)` over an Aerospike-backed stream.
**Expected:** a refusal, or at minimum a recorded, surfaced downgrade.

---

## Group J — the state tier

## AERO-045 — a checkpoint round-trips offsets and operator state
**Steps:** store a checkpoint with 3 offsets and 2 operator states of known bytes; `load`; `latest`.
**Expected:** byte-identical round trip.

## AERO-046 — a truncated checkpoint is skipped, not half-read
**Steps:** store two checkpoints; truncate the newer by 1, by 4, and to 0 bytes; call `latest`.
**Expected:** the older one is returned each time. Never a partial object, never an exception out of
`latest`.

## AERO-047 — a checkpoint from a future format version does not break recovery
**Intent:** `load` throws `IllegalStateException` on a version mismatch while `latest` documents
itself as skipping anything that will not read.
**Steps:** store two checkpoints; patch the version field of the newer to 2; call `latest`.
**Expected:** the older checkpoint is returned. An exception out of `latest` means a single
forward-version file makes the whole query unrecoverable.

## AERO-048 — a stray file in the checkpoint directory does not break recovery
**Intent:** what a tired operator does: `cp checkpoint-7.bin checkpoint-7.bin.bak`, or a partial
`rsync` leaving `checkpoint-backup.bin`.
**Steps:** store two checkpoints, add `checkpoint-backup.bin`, `checkpoint-.bin`, `checkpoint-7.bin.bak`,
a subdirectory named `checkpoint-x.bin`; call `availableIds`, `latest`, `prune`.
**Expected:** the stray files are ignored. An exception out of any of the three is a FAIL — it takes
the query down for a file that is not a checkpoint.

## AERO-049 — `store()` is durable when it returns
**Intent:** the interface says "Returns only once it is durable and complete", and the class comment
rests the whole design on atomic rename. Atomic rename orders the *visibility* of the bytes; it does
not flush them.
**Steps:** read the implementation for `flush`/`force`/`FileDescriptor.sync`/`StandardOpenOption.SYNC`;
strace or otherwise observe the syscalls if practical.
**Expected:** the data is forced to disk before `store` returns, or the claim is corrected.

## AERO-050 — checkpoint files are owner-only
**Intent:** operator state is the aggregated data itself.
**Steps:** store a checkpoint; `stat` the resulting file and the directory.
**Expected:** mode 600 on the file.

## AERO-051 — `prune` keeps the newest N and refuses a nonsense N
**Steps:** store 5 checkpoints; `prune(3)`; `prune(0)`; `prune(-1)`; `prune(100)`.
**Expected:** 2 removed and the 3 highest ids remain; `prune(0)` and `prune(-1)` throw; `prune(100)`
removes nothing.

## AERO-052 — a checkpoint taken from a real running query contains real state
**Intent:** the specific claim to verify — that checkpoints are ~60 bytes with empty operator state.
**Steps:** run a windowed aggregate through `QueryExecution`, take a checkpoint, inspect the
`Checkpoint` object and the file on disk; repeat for a stateless query.
**Expected:** a stateful query's checkpoint carries non-empty operator state; the file size reflects
it.

## AERO-053 — production code restores from a checkpoint
**Intent:** a store that is written and never read is a cost with no benefit, and makes every
"recovery" claim false.
**Steps:** grep for every caller of `CheckpointStore.latest`, `CheckpointStore.load` and
`QueryExecution.restore` outside test sources; restart a server that has been checkpointing and see
whether a registered query resumes with its state.
**Expected:** a production caller exists.

## AERO-054 — the L0/L1 state tiers can be checkpointed
**Intent:** ADR-006 describes a tiered state store. If `L0StateMap` and `RowStore` cannot serialise
themselves, the tier that holds the hot state is outside the checkpoint entirely.
**Steps:** inspect both classes for any snapshot/serialise/restore method; check what
`InterpretedPipeline.snapshotState` actually walks.
**Expected:** the state the engine holds at run time is the state the checkpoint captures.

## AERO-055 — `Checkpoint.timestampNanos` means what it says
**Intent:** documented as "when it was taken, for humans". `QueryExecution.checkpoint` passes
`System.nanoTime()`, which has an arbitrary origin and is not a wall clock.
**Steps:** take two checkpoints, print `timestampNanos`, convert as an epoch time.
**Expected:** a value a human can read as a time.

---

## Group K — the integration test that carries the product claim

## AERO-056 — `AerospikeContinuousQueryIT` runs green against a real server
**Steps:** run it with Docker available and port 3000 free.
**Expected:** passes.

## AERO-057 — `AerospikeContinuousQueryIT` proves what its name claims
**Intent:** the README points at this test as proof that the flagship query "runs against a real
Aerospike server". The word in the class name is *continuous*.
**Steps:** read the test. Determine whether it (a) keeps the query running across more than one
scan, (b) shows a result changing when new data is written, (c) closes a window on a watermark that
advanced from the data rather than from a hard-coded call.
**Expected:** a test named `ContinuousQuery` demonstrates continuous maintenance: a second scan
picking up a write made after the first result was read.

## AERO-058 — the plugin's own IT suite runs green
**Steps:** `./mvnw -o -pl plugins/pravaha-plugin-aerospike verify`.
**Expected:** all 8 integration tests pass against a real Community server.

## AERO-059 — the case studies that name Aerospike are runnable as written
**Intent:** four case studies tell a user to put their data in Aerospike and register a query over
it. Whether the resulting server can read that data is the whole question of Group A.
**Steps:** follow `examples/case-studies/SETUP.md` plus `trade-processing/README.md` as far as the
source binding; identify the step that binds `trade` to the Aerospike set.
**Expected:** the procedure exists and works.

## AERO-060 — filter pushdown happens for a registered continuous query
**Intent:** README line 140 says filters "are pushed *into* the store — working today against
Aerospike and any JDBC source, so filtered rows never cross the network". A registered continuous
query on a server is the product surface that claim is about.
**Steps:** trace the server ingest path from `PluginSourceFeeds.open` to `createReader`; find every
production caller of `Pushdown.requestFor`.
**Expected:** the reader a server creates for a bound stream carries the plan's filters.
