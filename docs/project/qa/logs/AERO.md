# AERO — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/AERO.md`](../cases/AERO.md). 60 written, 60 executed.

## Environment

    Aerospike Community Edition 8.1.2.4-4, container aerospike/aerospike-server:latest,
      --network host --ulimit nofile=16000:16000, namespace "test", started for this round
      and removed at the end.
    Docker 29.8.0. Java 21 (/usr/lib/jvm/java-21-openjdk-amd64).
    Server ports used: HTTP 18600 and 18610, Flight 19600 and 19610.
    Harnesses compiled into the scratch directory against the built module classes; no production
      code, and no repository file outside these two, was modified.

Port note: 18601/18602 and 19601/19602 inside this area's reserved range were already held by two
long-running JVMs belonging to another session. They were left alone and free ports in the range
were used instead.

---

## Group A — is any of this reachable?

### AERO-001 — FAIL
```
$ for j in plugins/*/target/*.jar; do printf "%-56s " $(basename $j);
    unzip -l "$j" | grep -c "META-INF/services/com.ash"; done
pravaha-cluster-zookeeper-0.1.0-SNAPSHOT.jar             1
pravaha-plugin-aerospike-0.1.0-SNAPSHOT.jar              0
pravaha-plugin-delta-0.1.0-SNAPSHOT.jar                  1
pravaha-plugin-feedfile-0.1.0-SNAPSHOT.jar               1
pravaha-plugin-filesystem-0.1.0-SNAPSHOT.jar             1
pravaha-plugin-jdbc-0.1.0-SNAPSHOT.jar                   1

$ java -cp <api:common:aerospike-classes:filesystem-classes:deps> Discover
  loaded: filesystem  (com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin)
  total: 1

$ javap -cp <same classpath> com.ash...aerospike.AerospikeSourcePlugin | head -3
public final class ...AerospikeSourcePlugin implements ...StreamSourcePlugin {
  public ...AerospikeSourcePlugin();
```
**Verdict:** `plugins/pravaha-plugin-aerospike/src/main/resources/META-INF/services/` does not
exist. Every other source plugin in the repository has the file; Aerospike is the only one without.
The class is on the classpath, implements `StreamSourcePlugin`, and has a public no-arg
constructor — `ServiceLoader` still does not return it, because nothing declares it.

`PluginSourceFeeds.discover` (the only production path from configuration to a source plugin) is
`ServiceLoader.load(StreamSourcePlugin.class)`. So the flagship connector cannot be selected by
configuration on any deployment, now or after any amount of classpath work. **BLOCKER.** The fix is
one three-line resource file.

Not vacuous: the same run loaded `filesystem` from the same classpath by the same call, so the
loader and the classpath were both working.

### AERO-002 — FAIL
```
$ cat scratch/server/application.yaml   (extract)
  sources:
    txn:
      plugin: aerospike
      options: {hosts: "127.0.0.1:3000", namespace: test, set: qa_orders,
                schema: "...", user: aerouser, password: "SUPERSECRET-AERO-PW"}

$ bin/pravaha-server --spring.config.additional-location=file:.../application.yaml   (HTTP 18600, Flight 19600)
INFO  c.a.m.pravaha.server.PravahaNode : sources bound: [txn <- aerospike[hosts, user, password, schema, namespace, set]]
INFO  c.a.m.p.server.PravahaServerApplication : Started PravahaServerApplication in 4.781 seconds

$ bin/pravaha register --name aero_volume --sql-file q1.sql --keys 1 --url grpc://127.0.0.1:19600
PRV-1041  PRV-5090  no source plugin named 'aerospike' is on the classpath, so stream 'txn' cannot
be fed. Available: [filesystem]

$ bin/pravaha queries --url grpc://127.0.0.1:19600
no continuous queries are registered
```
**Verdict:** the message itself is exemplary — it names the plugin, the stream and what is actually
available. Two things are still wrong. First, the capability is absent (AERO-001). Second, the node
**starts up logging `sources bound: [txn <- aerospike[...]]`**, which reads as success, and the
contradiction only appears at the first registration. A binding naming a plugin that is not on the
classpath should fail at startup, where one operator sees it once, rather than at every
registration. HIGH, downgraded from BLOCKER only because AERO-001 is the same defect's root.

Credential check while here: the startup line prints the option **keys** and not the values
(`SourceBinding.toString` prints `options.keySet()`). `grep SUPERSECRET server1.log` found nothing.
That part is correct.

### AERO-003 — FAIL
```
$ unzip -l pravaha-server/target/pravaha-server-0.1.0-SNAPSHOT-app.jar | grep BOOT-INF/lib/pravaha-plugin
    19514  BOOT-INF/lib/pravaha-plugin-filesystem-0.1.0-SNAPSHOT.jar
```
**Verdict:** the shipped server contains exactly one plugin. `pravaha-server/pom.xml` depends only
on `pravaha-plugin-filesystem`. There is no documented mechanism to add another — the jar is a
Spring Boot fat jar launched with `-jar`, so a `-cp` addition does not work. Already recorded as
finding I-7 from round 1; Aerospike makes it worse because it is the one the ADRs call the flagship.
MEDIUM (duplicate of I-7 in cause, new in consequence).

### AERO-004 — FAIL
```
$ grep -rn "ServiceLoader.load" --include=*.java . | grep -v /target/
pravaha-cluster/.../CoordinatorFactory.java:58:   ServiceLoader.load(CoordinatorProvider.class)
pravaha-server/.../ingest/PluginSourceFeeds.java:164:  ServiceLoader.load(StreamSourcePlugin.class)

$ grep -rn "InterpretedPipeline.compile" --include=*.java . | grep -v /target/ | grep src/main
pravaha-serving/.../ViewQuery.java:206   (2-arg form, no lookups)

$ sed -n '530p' pravaha-registry/.../QueryRegistry.java
QueryExecution execution = QueryExecution.start(plan, 1, laneConfig, access, () -> (RowOutput) sink::begin);
```
**Verdict:** there is no discovery mechanism for sinks or lookups at all, and no configuration
property that names one. Worse, `QueryRegistry` calls the five-argument
`QueryExecution.start`, which passes `Map.of()` for lookups — so **a registered query cannot have a
lookup join bound on a server under any configuration**. It fails loudly
(`InterpretedPipeline` line 583: "which is not among the dimension tables this execution was given
([])"), which is the right failure, but it means the README's flagship query — the one
`AerospikeContinuousQueryIT` demonstrates — cannot be registered on a Pravaha server at all.
`AerospikeSinkPlugin` and `AerospikeLookupPlugin`, 493 lines between them, are reachable only from
Java that constructs them directly. HIGH.

---

## Group B — strategies, guarantees and what is claimed

### AERO-005 — PASS
```
strategy=xdr-kafka       -> ConfigurationException [PRV-5083]: strategy 'xdr-kafka' is designed but not
  implemented in this build. It needs Aerospike Enterprise XDR, which cannot be exercised against
  Community Edition, and shipping an untested change-feed path would be worse than not shipping one.
  Use 'lut-scan', which works on Community Edition and declares its weaker guarantees.
strategy=xdr-http        -> ConfigurationException [PRV-5083]: (same shape)
strategy=write-intercept -> ConfigurationException [PRV-5083]: (same shape)
```
**Verdict:** exactly what ADR-029 promises. The refusal names the licence requirement and the
alternative. This is the best-behaved refusal encountered anywhere in this area.

### AERO-006 — PASS
```
strategy='lutscan'    -> ConfigurationException [PRV-5083]: unknown strategy 'lutscan'. Supported:
    lut-scan (Community Edition). Designed but not implemented: xdr-kafka, xdr-http, write-intercept.
strategy=''           -> ConfigurationException [PRV-5083]: unknown strategy ''
strategy=' LUT-SCAN ' -> OK
strategy='lut-scan'   -> OK
```
**Verdict:** strip and case-insensitive match work as documented; nothing threw an NPE or an index
exception. Not vacuous: `lutscan` (the near-miss) was rejected, so the match is not a substring one.

### AERO-007 — PASS
```
replayableOffsets=true orderedWithinPartition=false emitsDeletes=false emitsBeforeImage=false
guarantee=AT_LEAST_ONCE pushdown=[FILTER] typicalLatency=PT1S
name=aerospike version=1.0.0 strategy=LUT_SCAN
```
**Verdict:** every field is what ADR-029 says it must be. The declaration is honest. Whether anybody
reads it is AERO-009 and AERO-044.

### AERO-008 — FAIL
```
emitModes=[RETRACT, UPSERT] transactional=false idempotentUpsert=true maxBatchRows=512
SinkCapabilities.guarantee() = EXACTLY_ONCE
```
**Verdict:** `AerospikeSinkPlugin`'s own Javadoc says "claiming one would make the engine promise
exactly-once output it cannot deliver". `SinkCapabilities.guarantee()` then derives `EXACTLY_ONCE`
from `idempotentUpsert=true`, against README line 155 ("Treat the shipped guarantee as
at-least-once") and ADR-008 ("Until aligned barriers exist, do not claim exactly-once"). The fault
is in `SinkCapabilities.guarantee()` (pravaha-api), which conflates *effectively*-once with
*exactly*-once — its own comment says "Idempotent upsert gives 'effectively once'" and then returns
the stronger enum because no weaker-but-distinct value exists. MEDIUM today because nothing reads
it (AERO-009); it becomes HIGH the moment something does.

### AERO-009 — FAIL
```
$ grep -rn "\.weakest(\|capabilities().guarantee()" --include=*.java . \
    | grep -v /target/ | grep -v /src/test/ | grep -v pravaha-api/src/main | grep -v testkit
(no output)
```
**Verdict:** `DeliveryGuarantee.weakest` has no caller outside `CapabilitiesTest`. Nothing in
production reads `SourceCapabilities.guarantee()` or `SinkCapabilities.guarantee()`. The
registration response carries no guarantee field. §14.4, §1070 and risk mitigation R5 all describe
a weakest-link guarantee computed per query and displayed "loudly, in the API response and the UI".
It does not exist. HIGH — it is the control that ADR-029 leans on to make an at-least-once flagship
source safe to ship.

---

## Group C — configuration validation

### AERO-010 — FAIL
```
hosts='127.0.0.1:3000'   -> OK
hosts='127.0.0.1'        -> ConfigurationException [PRV-5083]: host entry '127.0.0.1' is not 'host:port'
hosts='127.0.0.1:abc'    -> ConfigurationException [PRV-5083]: port 'abc' is not a number
hosts='a:1:2'            -> ConfigurationException [PRV-5083]: host entry 'a:1:2' is not 'host:port'
hosts=''  /  '  '        -> ConfigurationException [PRV-5001]: requires 'hosts', which is not set
hosts='127.0.0.1:3000,'  -> OK        (trailing comma tolerated; harmless)
hosts='[::1]:3000'       -> ConfigurationException [PRV-5083]: not 'host:port'
hosts='127.0.0.1:99999'  -> OK        <-- accepted
hosts='127.0.0.1:-1'     -> OK        <-- accepted
```
**Verdict:** two defects. A port outside 1–65535 is accepted at configuration time and only fails
at `open()` with a connect error that blames the network. And IPv6 cannot be expressed at all:
`AerospikeHosts.parse` splits on `:` and demands exactly two parts, so `[::1]:3000` is rejected as
malformed. For a store that is routinely deployed on IPv6 estates that is a real limitation, and it
is not documented. LOW for the port range, MEDIUM for IPv6.

### AERO-011 — FAIL
```
partitions=abc              -> NumberFormatException: For input string: "abc"
partitions=2147483648       -> NumberFormatException: For input string: "2147483648"
records.per.second=abc      -> NumberFormatException: For input string: "abc"
scan.socket.timeout.ms=abc  -> NumberFormatException: For input string: "abc"
ttl.seconds=abc             -> NumberFormatException: For input string: "abc"
cache.seconds=abc           -> NumberFormatException: For input string: "abc"
concurrency=abc             -> NumberFormatException: For input string: "abc"

partitions=0     -> ConfigurationException [PRV-5083]: partitions must be between 1 and Aerospike's 4096, got 0
partitions=4097  -> ConfigurationException [PRV-5083]: ...got 4097
partitions=-1    -> ConfigurationException [PRV-5083]: ...got -1
records.per.second=-1        -> ConfigurationException [PRV-5083]: must not be negative, got -1
scan.socket.timeout.ms=0/-5  -> ConfigurationException [PRV-5083]: scan timeouts must be positive; zero
   means wait for ever, and a scan that never returns takes its lane with it and looks exactly like a
   hung engine
ttl.seconds=-1   -> ConfigurationException [PRV-5083]: must not be negative, got -1
cache.seconds=-1 -> ConfigurationException [PRV-5083]: must not be negative, got -1
concurrency=0    -> ConfigurationException [PRV-5083]: must be at least 1, got 0
```
**Verdict:** the *range* checks are all present and all have good messages. The *parse* is
unguarded on every one of the seven numeric settings: `Integer.parseInt(context.get(...))` with no
try/catch, so a typo in a YAML file surfaces as `java.lang.NumberFormatException: For input string:
"abc"` naming neither the plugin instance, the stream, nor the setting. `PluginContext.requireInt`
exists and does exactly this correctly, and is not used. On the server path this arrives wrapped as
`BINDING_FAILED`, which names the stream but still not the key. MEDIUM — a tired-operator defect,
and the fix is mechanical.

### AERO-012 — PASS (one LOW observation)
```
schema='abcdefghijklmno:INT64'   (15 bytes) -> OK
schema='abcdefghijklmnop:INT64'  (16 bytes) -> ConfigurationException [PRV-5083]: bin name ... is longer
    than Aerospike's 15-byte limit. The server refuses it on the first write, which is a long way from
    here; refusing it now is cheaper.
schema='éééééééé:INT64'  (8 chars, 16 bytes) -> refused, naming the byte limit
schema='x:DECIMAL' -> ConfigurationException [PRV-5082]: ... Store it as an integer of minor units and
    declare INT64, or as a string and parse it in the query.
schema='x:UUID'    -> ConfigurationException [PRV-5083]: unknown type 'UUID' ... Supported: BOOLEAN,
    INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, STRING, BYTES, TIMESTAMP.
schema='x'                 -> refused, naming the entry
schema='a:INT64,,b:STRING' -> refused, naming the empty entry
schema='a:int64' / 'a:INT64?' / 'a: INT64 ' / 'a:TIMESTAMP' / 'a:BYTES' -> OK
schema='a:INT64,a:STRING'  -> OK   <-- duplicate bin name accepted
```
**Verdict:** the 15-byte check counts UTF-8 bytes rather than characters, which is the correct and
easily-missed reading. PASS. One observation: a schema declaring the same bin twice is accepted, and
`AerospikeExpressions.ordinalOf` then resolves to the first ordinal while `copyInto` writes both.
Harmless today; LOW.

### AERO-013 — PASS
```
sink   key.bin=nope    -> ConfigurationException [PRV-5083]: key.bin 'nope' is not in the declared
   schema, which has [order_id, status, amount]. Without a key there is nothing to make the write
   idempotent, and the sink would append duplicates on every replay.
lookup key.bin=nope    -> ConfigurationException [PRV-5083]: key.bin 'nope' is not in the declared schema...
sink   key.bin missing -> ConfigurationException [PRV-5001]: requires 'key.bin', which is not set
```

### AERO-014 — PASS
```
configure(...) then createReader(...) with no open()
  -> PravahaException [PRV-5080]: source 'orders' was not opened before use
```

---

## Group D — connection and infrastructure failure

All four ran against the live Community server (or against a deliberately dead address).

### AERO-015 — PASS
```
hosts=no-such-host.invalid:3000, open()
  after 63ms: PravahaException [PRV-5080]: plugin 'orders' cannot reach Aerospike at
  no-such-host.invalid:3000: Error -8: Failed to connect to [1] host(s): ... Invalid host ...
  Check the host list, that the cluster is up, and that this process can reach the service port --
  the client also needs the fabric and heartbeat addresses the cluster advertises, ...
```
**Verdict:** correct code, names the address, returns in 63 ms, and the trailing advice is the
single most useful sentence in the plugin for the failure people actually hit.

### AERO-016 — PASS
```
hosts=127.0.0.1:18619, open()
  after 1ms: PravahaException [PRV-5080]: ... cannot reach Aerospike at 127.0.0.1:18619:
  Error -8: Failed to connect to [1] host(s): 127.0.0.1 18619 Error -8: Connection failed
```

### AERO-017 — PASS
```
namespace=nosuchns on a live server, then poll
  after 21ms: PravahaException [PRV-5081]: scan of nosuchns.qa_orders partitions [0, 4096) failed:
  Error 20,1: Namespace not found in partition map: nosuchns
```
**Verdict:** the commonest operator typo produces an error naming the namespace within 21 ms. Not
vacuous: the same harness read one real record from the correct namespace immediately before.

### AERO-018 — PASS
```
namespace=test, set=nosuchset -> scan returned 0 rows, no error
```
**Verdict:** correct. Aerospike has no set catalog to consult; an unknown set is genuinely empty.
Recorded so that a future reader can tell this apart from AERO-017. It does mean a typo in `set`
is a silent empty stream, which is worth a line in the troubleshooting page.

### AERO-019 — PASS
```
user=admin, password=hunter2SECRET against Community Edition
  PravahaException [PRV-5080]: ... 127.0.0.1 3000 Error 51: Login failed ...
  password present in message? false
```
**Verdict:** the server refuses, so credentials against a CE node fail closed rather than being
silently ignored. The password does not appear in the exception message, and the server's own
startup log printed option keys only. Not vacuous: the same configuration minus the credentials
connected and read rows in the preceding test.

### AERO-020 — FAIL
```
$ grep -rn -i "tls|ssl|TlsPolicy|authMode" plugins/pravaha-plugin-aerospike/src/main/java/
(only ttlSeconds matches -- no TLS reference of any kind)

$ javap com.aerospike.client.policy.ClientPolicy | grep -i "tls|authMode"
  public com.aerospike.client.policy.AuthMode authMode;
  public com.aerospike.client.policy.TlsPolicy tlsPolicy;
```
**Verdict:** the plugin builds a bare `ClientPolicy`, sets `user`/`password`/`timeout`/
`failIfNotConnected` and nothing else. There is no way to configure TLS, and no way to set
`authMode` (so Aerospike Enterprise with external/LDAP auth is also unreachable). Every byte between
Pravaha and Aerospike — including the password on the login exchange — is in clear text, with no
option to change that. No documentation admits the gap; `docs/SECURITY.md` discusses Aerospike twice
and never mentions transport. For a product aimed at trade and card data this is HIGH.

---

## Group E — offsets and the at-least-once claim

### AERO-021 — PASS (one LOW observation)
```
offset 'lsn=42'  -> PravahaException [PRV-5084]: offset 'lsn=42' was not written by this plugin, which
   writes 'lut=<nanos>'. Resuming from another plugin's offset would read from an arbitrary point.
offset 'lut=abc' -> PravahaException [PRV-5084]: offset 'lut=abc' does not hold a number
offset 'lut='    -> PravahaException [PRV-5084]: does not hold a number
offset 'LUT=1'   -> PravahaException [PRV-5084]: was not written by this plugin  (case-sensitive, correct)
offset 'lut=99999999999999999999' -> PravahaException [PRV-5084]: does not hold a number
offset 'lut=-5'  -> accepted            <-- LOW
```
**Verdict:** the refusals are right and the reasoning in the message is right. A negative watermark
is accepted; it is harmless (it matches everything, so it over-reads) but it is not a token this
plugin can have written, and the class's own rule is that such a token is refused.

### AERO-022 — PASS
```
null                 -> 1 rows
SourceOffset.BEGINNING -> 1 rows
new SourceOffset("")   -> 1 rows
```

### AERO-023 / AERO-025 — PASS
```
3 records written, then scanned
  first scan: 4 rows, offset=lut=1789266994528000000
sleep 1100ms; 2 more records written
  resumed scan: 2 rows [[5, B], [4, B]]
```
**Verdict:** resumption works: the resumed scan returned exactly the two new records, not zero and
not all five. `replayableOffsets=true` is earned.

The "4 rows" on a 3-record set is the at-least-once boundary in action and is worth recording: the
drain loop scans a second time once the buffer empties, the new watermark is the first scan's start
time in whole milliseconds, and one record's last-update time landed in that same millisecond, so
`Exp.ge` matched it again. That is the documented, chosen direction — duplicate rather than lose —
and this is the first evidence in the repository that it actually happens rather than being a
theoretical concern. AERO-025's expectation ("re-read, not lost") is met.

### AERO-024 — PASS
```
before any poll : lut=0
after first scan : lut=1789266996059000000
after second scan: lut=1789266997168000000   monotonic=true
```
**Verdict:** an empty set still produces a well-formed, advancing token. Note for the record: the
watermark is `System.currentTimeMillis()` on the **client**, compared server-side against the
record's own last-update time. A client clock ahead of the cluster's would skip records, silently.
Nothing checks for that; it is not a defect observed here, but it is an unstated assumption.

---

## Group F — expression pushdown equivalence

Method: for each predicate, drain the set with `ReadRequest.NOTHING` (baseline), apply the engine's
own semantics in Java to the rows the plugin produced, drain again with the filter pushed, and
report any row the engine would have kept that the store did not send. `Pushdown`'s documented
guarantee is that the pushed set is a **superset** of the engine's answer.

### AERO-026 — PASS
```
status = 'DONE'
  rows in set              : 6
  engine filter would keep : 3 [[4, DONE, 40], [2, DONE, 20], [6, DONE, 60]]
  pushdown returned        : 3 [[2, DONE, 20], [6, DONE, 60], [4, DONE, 40]]
  ROWS LOST BY PUSHDOWN    : none
```
Not vacuous: 3 of 6 rows were excluded, so the filter did something.

### AERO-027 — PASS
```
amount GT 30 : keep 3, pushdown 3, lost none
amount GE 30 : keep 4, pushdown 4, lost none
amount LT 30 : keep 2, pushdown 2, lost none
amount LE 30 : keep 3, pushdown 3, lost none
amount NE 30 : keep 5, pushdown 5, lost none
amount EQ 30 : keep 1, pushdown 1, lost none
```

### AERO-028 — FAIL
```
records: 1 -> active = 1 (integer), 2 -> active = 0 (integer), 3 -> active = true (boolean)
schema : order_id:INT64,active:BOOLEAN?

active = true
  rows in set              : 3 [[3, true], [1, true], [2, false]]
  engine filter would keep : 2 [[3, true], [1, true]]
  pushdown returned        : 1 [[3, true]]
  ROWS LOST BY PUSHDOWN    : [1]   <-- SILENT DATA LOSS
```
**Verdict:** `AerospikeSchemas.copyInto` deliberately reads an integer bin as a boolean, with the
comment "Aerospike stored booleans as 0/1 integers before server 5.6, and plenty of live data still
looks like that". `AerospikeExpressions.translate` builds `Exp.boolBin(bin)` for the same column,
which does not match an integer bin, so the server never sends the record. The engine's own filter
never sees it and cannot recover it. One class supports legacy boolean storage and the other
silently excludes it. **HIGH — silent wrong answers over exactly the legacy data the code says it
is catering for.**

### AERO-029 — FAIL
```
records: 1 -> status = 7 (integer), 2 -> status = "7" (string)
schema : order_id:INT64,status:STRING?

status = '7'
  rows in set              : 2 [[1, 7], [2, 7]]
  engine filter would keep : 2
  pushdown returned        : 1 [[2, 7]]
  ROWS LOST BY PUSHDOWN    : [1]   <-- SILENT DATA LOSS
```
**Verdict:** `copyInto`'s `default -> writer.setString(ordinal, String.valueOf(value))` coerces any
bin into a declared STRING column, so the engine sees both records as `"7"` and keeps both.
`translate` builds `Exp.stringBin`, which does not match an integer bin. `AerospikeSchemas`'s class
comment says the declaration exists precisely because "a set can hold records with different bins
and different types in the same bin" and that a disagreement should be "a decode error naming the
bin rather than a silent change of meaning". With pushdown on it is neither — it is a silent
omission. **HIGH.**

### AERO-030 — FAIL
```
records: 1 -> code = 300, 2 -> code = 44        schema: order_id:INT64,code:INT8?

code = 44   (the engine narrows 300 to (byte)300 == 44)
  rows in set              : 2 [[2, 44], [1, 44]]
  engine filter would keep : 2
  pushdown returned        : 1 [[2, 44]]
  ROWS LOST BY PUSHDOWN    : [1]   <-- SILENT DATA LOSS
```
**Verdict:** `copyInto` casts to `(byte)`/`(short)` for INT8/INT16 with no range check; `translate`
compares the full 64-bit value. The same record therefore has two different values depending on
whether the predicate ran in the store or in the engine. The narrowing cast is itself questionable —
silently turning 300 into 44 is the sort of thing this codebase refuses to do for DECIMAL — but the
pushdown disagreement is the defect. MEDIUM (narrower blast radius than 028/029: it needs an
INT8/INT16 declaration over out-of-range data).

### AERO-031 — PASS
```
status IS NULL     : keep 1, pushdown 1, lost none
status IS NOT NULL : keep 1, pushdown 1, lost none
```
**Verdict:** the "Aerospike does not store absent bins, so IS NULL is bin-does-not-exist" mapping is
right, verified both ways against a real server.

### AERO-032 — PASS
```
status <> 'NEW'   (records: 1 -> 'NEW', 2 -> no status bin)
  engine filter would keep : 0     (SQL: NULL <> 'NEW' is UNKNOWN)
  pushdown returned        : 0
  ROWS LOST BY PUSHDOWN    : none
```
**Verdict:** Aerospike's own evaluation of `ne` against a missing bin agrees with SQL three-valued
logic here. Worth recording because it is the case most likely to disagree and does not.

### AERO-033 — PASS
```
filter on 'no_such_bin' -> 3 rows returned (all of them; nothing pushed, no exception)
```

### AERO-034 — PASS
```
string LT 'ZZZ'                      -> 3 rows (not pushed; collation reasoning honoured)
order_id EQ with a String literal    -> 3 rows (not pushed; type guard honoured)
```
**Verdict:** both fall through to null and the engine keeps the filter, as the class comment
promises. This is the correct half of the same mechanism that fails in 028–030: when `translate`
recognises that it cannot be exact it does the right thing; the three failures are cases where it
believes it *is* exact and is not.

---

## Group G — the sink

### AERO-035 — PASS
```
after 1 write : rec1=(gen:1),(exp:0),(bins:(status:NEW),(amount:100))
after 3 writes: rec1=(gen:3),(exp:0),(bins:(status:NEW),(amount:100))
after retract : rec1=null
written=4 deleted=1
```

### AERO-036 — FAIL
```
integer key:
  sink.write(order_id=11, status=NEW, amount=100)
  raw record as stored : (gen:1),(exp:0),(bins:(status:NEW),(amount:100))
  read back through the source (schema order_id:INT64?,status:STRING,amount:INT64):
    [[null, NEW, 100], [null, DONE, 200]]

string key:
  sink.write(user_id=u1, tier=gold, score=10)
  stored : (gen:1),(exp:0),(bins:(tier:gold),(score:10))
  read back through the source: [[null, gold, 10]]
```
**Verdict:** `AerospikeSinkPlugin.upsert` skips the key ordinal on purpose ("the primary key is the
record's identity, not a bin"), and Aerospike does not store the primary key's *value* in a record
unless `WritePolicy.sendKey` is set. `AerospikeSourcePlugin` reads bins only. So a set written by
this sink and read by this source loses the key column entirely — every row comes back with a null
where its identity should be. `AerospikeLookupPlugin` avoids this by filling the key bin from the
key it asked with (`fillKeyBin`), which shows the problem was understood on one path and not the
other. HIGH: it breaks the obvious composition of two plugins in the same connector, silently, with
`null` rather than an error. The one-line fix is `policy.sendKey = true`, or writing the key as a
bin as well.

### AERO-037 — PASS
Same evidence as AERO-035: three identical writes leave generation 3 and identical bin values, so
the end state after a replay is the state after one write. The "effectively-once output" claim holds
at the sink, for rows that the sink can write at all (see AERO-039).

### AERO-038 — PASS
```
ttl.seconds=2
  immediately: present
  after 7s   : absent
```

### AERO-039 — FAIL
```
sink.write with a null column
  -> PravahaException [PRV-5081]: write to test.qa_orders failed:
     Error 4,1,0,30000,1000,0,BB942BABC75F7B8 127.0.0.1 3000: Parameter error

diagnosis with the raw client, same server:
  REPLACE + Bin.asNull    -> Error 4 ... Parameter error
  UPDATE  + Bin.asNull    -> accepted
  REPLACE, no null bin    -> accepted
```
**Verdict:** `upsert` sets `RecordExistsAction.REPLACE` and writes absent columns as `Bin.asNull`.
Aerospike rejects that combination: `REPLACE` already means "these are all the bins", so a
delete-this-bin instruction inside it is a parameter error. **The sink cannot write any row that has
a null in any column.** The class comment claims the opposite ("Aerospike deletes a bin written as
null, which is exactly right"), and the plugin's own IT never writes a null, so this was never
exercised.

This is not a corner case: the README's flagship query is a LEFT lookup join that produces exactly
one such row — user `u3` with a null `tier` — and `AerospikeContinuousQueryIT` asserts on it. Route
that query's output into the Aerospike sink and it fails on that row, mid-batch, having already
written the rows before it. **HIGH.**

---

## Group H — the lookup

### AERO-040 — PASS
```
hit  : n=1 row=[[7, DONE, 700]]        (key column filled from the key asked with)
miss : n=0
null : n=0
wrong-typed key ("not-a-number" against an INT64 key column) : n=0, no exception
```

### AERO-041 — PASS
```
arity 2 -> IllegalArgumentException: an Aerospike lookup takes one key value -- the record's primary
   key -- and was given 2
arity 0 -> IllegalArgumentException: ... and was given 0
```
**Verdict:** correct and well worded. Consistency observation only: this is the one failure in the
plugin that is not a `PravahaException` with an error code, so it will not appear in the error-code
table an operator is told to look things up in.

### AERO-042 — PASS
```
keyColumns=[order_id] cacheFor=PT1M maxConcurrency=16 typicalLatency=PT0.0005S

lookup(7) -> hit; record deleted; lookup(7) -> n=0
```
**Verdict:** the plugin itself does not cache — the second lookup went to the server and correctly
found nothing. That is right: `cacheFor()` is advice to the engine, and
`LookupJoin` line 131 reads `source.cacheFor().toNanos()` and honours it. Declared and consumed;
nothing is promised that nobody keeps. Falsifiable by construction: had the plugin cached, the
second lookup would have returned the deleted row.

---

## Group I — deletes

### AERO-043 — PASS
```
first scan: 3 rows [[3], [1], [2]]
record 2 deleted
scan resuming from the offset      : 0 rows []
a full rescan from the beginning   : 2 rows [[3], [1]]
capabilities.emitsDeletes=false
```
**Verdict:** ADR-029's sharpest edge, demonstrated. The delete produces no event at all; a
maintained view keeps serving row 2 for ever. The full rescan confirms the record really is gone
from the store, so the zero is the absence of a *notification*, not a scan that failed.

### AERO-044 — FAIL
```
$ grep -rn "emitsDeletes()|emitsBeforeImage()|replayableOffsets()" --include=*.java . \
    | grep -v /target/ | grep -v /src/test/ | grep -v pravaha-api/src/main
pravaha-testkit/.../SourcePluginTck.java:192,239,243,244     (a TCK, not the engine)
```
**Verdict:** ADR-029 states: "The engine is told all of this through the capability declaration, so
a query that needs more is refused at registration rather than discovering it in production. That is
the mechanism that makes this decision safe to take: the limitation is not hidden, it is
negotiated." **No such negotiation exists.** Nothing in the engine reads `emitsDeletes`,
`emitsBeforeImage` or `replayableOffsets`; `PluginSourceFeeds` never calls `capabilities()` at all.
An unbounded `COUNT(*)` over an Aerospike-backed stream registers happily and produces a count that
can only rise. HIGH, and it is the load-bearing sentence of an Accepted ADR.

---

## Group J — the state tier

### AERO-045 — PASS
```
stored : Checkpoint[1, 3 partitions, 2 operators, 128 bytes]
loaded : Checkpoint[1, 3 partitions, 2 operators, 128 bytes]
offsets equal        : true
operator state equal : true     (byte-compared, not just size)
latest()             : Checkpoint[1, 3 partitions, 2 operators, 128 bytes]
file size on disk    : 265 bytes
```

### AERO-046 — PASS
```
newer truncated to 133/134 bytes -> latest()=id 1
newer truncated to 130/134 bytes -> latest()=id 1
newer truncated to 122/134 bytes -> latest()=id 1
newer truncated to   0/134 bytes -> latest()=id 1
```
**Verdict:** the trailer does its job at every truncation point tried, including the one-byte case
that the magic and the counts would both survive without it. Not vacuous: `latest()` returned id 2
before the truncation in each run.

### AERO-047 — FAIL
```
two checkpoints stored; the newer one's FORMAT_VERSION byte patched from 1 to 2
  latest() -> IllegalStateException: checkpoint 2 is format version 2 and this engine reads 1.
              Refusing to guess at the difference.
  load(2)  -> IllegalStateException: (same)
```
**Verdict:** `latest()` documents itself as "skip any that will not read: after a crash the newest
file is exactly the one most likely to be damaged, and falling back to the previous one is the whole
reason more than one is kept". It does that for every corruption *except* a version mismatch, where
`load` throws instead of returning empty, and the exception propagates straight out of `latest()`.
One file written by a newer engine — a rolled-back upgrade, a restored backup, a shared NFS
directory — makes the query unrecoverable even though three perfectly good older checkpoints sit
beside it. MEDIUM. The refusal to guess is right; throwing it out of the method whose contract is to
skip unreadable files is not.

### AERO-048 — FAIL
```
availableIds() with 'checkpoint-backup.bin' present -> NumberFormatException: For input string: "backup"
latest()       with the same                        -> NumberFormatException: For input string: "backup"
prune(1)       with the same                        -> NumberFormatException: For input string: "backup"
'checkpoint-2.bin.bak'                              -> OK (suffix filter excludes it)
'checkpoint-.bin'                                   -> NumberFormatException: For input string: ""
a subdirectory named 'checkpoint-x.bin'             -> NumberFormatException: For input string: "x"
```
**Verdict:** `availableIds()` runs `Long.parseLong` on whatever sits between the prefix and the
suffix, with no guard. Any file or directory matching `checkpoint-*.bin` whose middle is not a
number takes out `availableIds`, `latest` and `prune` together — that is recovery, listing and
retention, all three, for the whole query. The realistic triggers are ordinary: a `cp
checkpoint-7.bin checkpoint-backup.bin` before poking at something, a partial `rsync`, a restore
tool that writes a manifest. MEDIUM-HIGH: it converts a tidiness mistake into an unrecoverable
query, and the file the operator created is the one that looks most like a safety measure.

### AERO-049 — FAIL
```
$ grep -n "force|flush|sync|SYNC|DSYNC|FileDescriptor" \
    pravaha-state/.../checkpoint/FileCheckpointStore.java
(no match)
```
**Verdict:** `CheckpointStore.store` is documented as "Returns only once it is durable and
complete", and `FileCheckpointStore`'s class comment rests the design on atomic rename. Rename
orders *visibility*; it does not flush. The `DataOutputStream` is closed (which flushes to the page
cache) and `Files.move` is `ATOMIC_MOVE`, and then nothing calls `force`/`fsync` on either the file
or the containing directory. After a power loss or a kernel panic — as opposed to a process crash,
which the rename does handle — a checkpoint that `store()` said was durable can be absent or
zero-length. MEDIUM: the interface claims more than the implementation delivers, and the claim is
exactly the one a recovery design is built on. Either add `FileChannel.force(true)` before the
rename plus a directory fsync after it, or change the sentence.

### AERO-050 — PASS
```
checkpoint-1.bin : rw-------
directory        : rwx------
```
Confirmed again on a real server's checkpoint directory: `-rw------- ... checkpoint-3.bin`.

### AERO-051 — FAIL (partial)
```
before: [5, 4, 3, 2, 1]
prune(3) removed 2 -> [5, 4, 3]
prune(0)  -> IllegalArgumentException: at least one checkpoint must be kept, asked to keep 0
prune(-1) -> IllegalArgumentException: at least one checkpoint must be kept, asked to keep -1
prune(100) removed 0 -> [5, 4, 3]

with the newest file corrupted:
  latest() = id 2,  prune(1) removed 2 -> [3],  latest() now = empty
```
**Verdict:** the ordinary behaviour is correct. The defect is the last line: `prune` keeps the *N
numerically highest ids*, not the N newest *readable* checkpoints, so an unreadable newest file
counts towards the quota and the last usable fallback is deleted. `latest()` went from returning a
good checkpoint to returning nothing, as a direct result of a retention call. With the shipped
default `keep: 3` this needs the three highest ids to all be unreadable, which is why this is
MEDIUM and not HIGH — but `PeriodicCheckpointer` calls `store.prune(keep)` on every interval, so it
is a scheduled operation that can destroy the only recoverable state. `prune` should consult the
same "will it load" test `latest` does.

### AERO-052 — PASS
```
$ (real server, windowed aggregate over 300 rows, checkpoint interval 2s)
$ ls -la scratch/ckpt2/aero_volume-4dd823b8/
-rw------- 1 ashutosh ashutosh 736 checkpoint-3.bin
-rw------- 1 ashutosh ashutosh 736 checkpoint-4.bin
-rw------- 1 ashutosh ashutosh 736 checkpoint-5.bin

$ java qastate.Inspect scratch/ckpt2/aero_volume-4dd823b8
ids: [12, 11, 10]
  id=12 file=736B timestampNanos=282339847749800 (as an instant: 1970-01-04T06:25:39.847749800Z)
     offsets={partition-0=300}
     operatorState[lane-0] = 662 bytes

synthetic sizes for comparison:
  1 offset, 0 operators : 67 bytes
  1 offset, 1 empty op  : 79 bytes
  1 offset, 4KB of state: 4175 bytes
```
**Verdict:** the "60-byte checkpoints with empty operator state" report does **not** hold for a
stateful query. A real windowed aggregate holding five groups produced 662 bytes of operator state
in a 736-byte file, and `InterpretedPipeline.snapshotState` walks every `WindowedAggregate` and
`SymmetricHashJoin`. The 60–79 byte shape is what a *stateless* query writes, and that is correct by
design: `QueryExecution.checkpoint` skips pipelines where `isStateful()` is false, so there is
nothing to store. The state tier does round-trip, and `CheckpointRecoveryTest` and `JoinRecoveryTest`
both pass (2 + 2 tests green). What is missing is the caller — see AERO-053.

One thing that is right and worth recording: `PeriodicCheckpointer.start()` reads
`store.availableIds()` and continues the id sequence from the highest found, so a restart does not
collide with or prune away the previous run's files (ids 10–12 became 23–25 across a restart).

### AERO-053 — FAIL
```
$ grep -rn "latest()" --include=*.java . | grep -v /target/ | grep -v /src/test/
pravaha-state/.../CheckpointStore.java:35        (the declaration)
pravaha-state/.../FileCheckpointStore.java:109   (the implementation)
$ grep -rn "\.restore(" --include=*.java . | grep -v /target/ | grep -v /src/test/
(nothing calls QueryExecution.restore outside CheckpointRecoveryTest and JoinRecoveryTest)

End-to-end proof, real server:
  1. windowed query registered, fed 300 rows, view holds 5 groups:
     $ bin/pravaha query --sql "SELECT * FROM aero_volume" --url grpc://127.0.0.1:19610
     window_end            user_id  txn_count  total_volume
     1767225620000000000   u3       16         24880
     ... 5 rows
  2. 3 checkpoints on disk, 662 bytes of operator state each, offsets={partition-0=300}
  3. server killed; the source CSV emptied so nothing can be re-read
  4. server restarted with the same configuration:
     INFO PravahaNode : registry recovered 1 of 1 queries from .../journal2.log
     (no line mentioning a checkpoint being read)
  5. $ bin/pravaha queries --url grpc://127.0.0.1:19610
     NAME         STATE    FINGERPRINT   ROWS IN
     aero_volume  RUNNING  632c13a1030f  0
     $ bin/pravaha query --sql "SELECT * FROM aero_volume" --url grpc://127.0.0.1:19610
     0 rows
```
**Verdict:** checkpoints are **write-only in production**. The engine takes them every interval,
prunes them, permissions them carefully, and never reads one. A restart recovers the *question* from
the journal and none of the *answer*, which is the exact failure `QueryRegistry.checkpointingTo`'s
own Javadoc says it was written to fix ("their aggregates, join state and open windows came back
empty"). Half of the fix landed.

Compounding it, `PluginSourceFeeds` line 111 opens every reader at `SourceOffset.BEGINNING`, so the
stored offsets are unused too. Recovery today is "re-read the source from the start", which for a
file happens to produce the right answer and for an Aerospike `lut-scan` produces the *current*
state of the set with every historical change collapsed. **BLOCKER for the state tier.** Falsifiable
by construction: the source was emptied precisely so that a correct restore and a re-read could not
be confused.

### AERO-054 — FAIL
```
$ grep -n "snapshot|serial|writeTo|readFrom|restore" pravaha-state/.../L0StateMap.java \
                                                     pravaha-state/.../RowStore.java
(no match in either)

$ grep -rn "L0StateMap" --include=*.java . | grep -v /target/ | grep -v "^pravaha-state/"
pravaha-it/.../OrphanedClassTest.java:38,82,97   (listed as a known orphan)
```
**Verdict:** two separate facts. `RowStore` **is** in the product — `SymmetricHashJoin` and
`JoinSide` hold join state in it, and `SymmetricHashJoin.writeTo/readFrom` serialise that state out
of it, which is why `JoinRecoveryTest` passes. `L0StateMap` is **not**: it is confirmed orphaned by
the repository's own `OrphanedClassTest`, which lists it and describes it as "the off-heap state map
the architecture is written around". There is no L1 tier and no spill path anywhere. So ADR-006's
tiered state store is one tier, used by one operator, plus an unused reference implementation of the
tier the architecture is written around. Documentation defect rather than a runtime one; MEDIUM,
because the architecture document describes a structure the engine does not have.

### AERO-055 — FAIL
```
$ sed -n '713p' pravaha-runtime/.../QueryExecution.java
return new ...Checkpoint(id, System.nanoTime(), offsets, state);

from a real server's checkpoint:
  timestampNanos=282339847749800  (as an instant: 1970-01-04T06:25:39.847749800Z)
  a wall clock would read         : 2026-09-13T02:38:59.663Z
```
**Verdict:** `Checkpoint.timestampNanos` is documented as "when it was taken, for humans rather than
for logic". `System.nanoTime()` has an arbitrary origin fixed at JVM start, so every checkpoint this
engine has ever written says 1970, and the value is not even comparable across a restart. LOW —
nothing computes on it — but it is the field an operator asked "when was this taken?" would read,
and it is wrong in a way that looks like a plausible number.

---

## Group K — the integration test that carries the product claim

### AERO-056 — PASS
```
$ ./mvnw -o -pl pravaha-it verify -Dsurefire.skip=true -Dit.test=AerospikeContinuousQueryIT \
    -Dspotless.check.skip=true -Denforcer.skip=true -Djacoco.skip=true
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS   Total time: 47.273 s
```
**Verdict:** it runs, against a real Aerospike Community server it starts itself, and it passes. Not
a skip: `Tests run: 1, Skipped: 0`, and the run took 47 s, which is a container start.

### AERO-057 — FAIL
**Verdict on what the test proves, from reading it (lines 200–300 of
`pravaha-it/src/test/java/com/ash/messaging/pravaha/it/AerospikeContinuousQueryIT.java`):**

What it genuinely demonstrates, and these are not small things: the README's SQL parses and plans;
a tumbling window, a temporal lookup join and a `LEFT` miss all produce the right numbers; the
`WHERE` clause is translated to an Aerospike expression and the 9999-value `PENDING` row never
arrives; the totals would be visibly wrong if the filter silently failed, which is a well-chosen
dataset.

What it does not demonstrate, despite its name:

1. **It is not continuous.** The body is `do { moved = reader.poll(...) } while (moved > 0);` — a
   single drain to exhaustion, then one hard-coded `pipeline.advanceWatermark(12 * SECOND)`, then
   assertions. Every row is written by `@BeforeEach` before the query starts. Nothing is written
   while the query is running; there is no second scan; no result is observed changing. A test named
   `ContinuousQuery` that runs one batch and stops is testing a batch query with streaming SQL
   syntax.
2. **The watermark is hand-advanced**, not derived from the data, so the part of the engine that
   decides *when* a window is complete is not exercised.
3. **It drives the engine through Java APIs that no server path uses.** `InterpretedPipeline.compile`
   with a lookups map, `Pushdown.requestFor`, the three-argument `createReader` — none of these has
   a production caller on the server path (AERO-004, AERO-060). So the test proves the *components*
   compose when wired by hand; it does not prove the *product* does, and README line 109 ("runs
   against a real Aerospike server") is read by anybody as the second claim.
4. **No failure path at all** — no restart, no partial scan, no duplicate, no connection loss.

Severity MEDIUM as a test defect and HIGH as a documentation one: this test is what the README
offers as evidence for the product claim, and it is evidence for a narrower claim. The honest fix is
either to make it continuous (write during the run, scan twice, assert the result changed) or to
rename it, plus soften the README sentence.

### AERO-058 — PASS
```
$ ./mvnw -o -pl plugins/pravaha-plugin-aerospike verify -Dsurefire.skip=true -Dit.test=AerospikePluginIT
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.74 s
[INFO] BUILD SUCCESS
```
**Verdict:** all eight pass against a real Community server. Worth saying plainly: this suite is
green *while* AERO-028, 029, 030, 036 and 039 are all true of the same code. It tests pushdown only
with bins whose stored type matches the declaration, and it never writes a null column or reads back
what the sink wrote. The gap is in the cases chosen, not in the mechanics.

### AERO-059 — FAIL
```
$ grep -rn "pravaha.sources|sources:|plugin: aerospike|application.yaml" examples/case-studies/
(no output)
$ grep -rn -i "pravaha-server|bin/pravaha-server|start the server" examples/case-studies/*/README.md \
    examples/case-studies/SETUP.md
(no output)
```
**Verdict:** four case studies are built on Aerospike. `SETUP.md` explains starting the container,
the `--network host` trap, `aql`, and the Python client. `trade-processing/README.md` loads records
with `aql` and then registers a query with `pravaha register --url grpc://localhost:9090`. **Nowhere
in any of them is there a step that starts a Pravaha server, and nowhere is there a step that binds
the `trade` stream to the Aerospike set.** A reader following the page exactly reaches "Step 3 —
load some trades", registers a query against a server they were never told to start, and — had they
started one and bound the source themselves — would hit `no source plugin named 'aerospike' is on
the classpath`. Combined with `docs/QUICKSTART.md` line 325 ("Filesystem, JDBC and Aerospike work
now") against `application.yaml`'s own comment ("filesystem, feedfile, jdbc and delta ship in this
repository"), the documentation is internally inconsistent about the flagship connector. HIGH as a
documentation defect.

### AERO-060 — FAIL
```
$ grep -rn "Pushdown.requestFor" --include=*.java . | grep -v /target/
pravaha-cli/.../QueryRunner.java:123      (the CLI's one-shot `run` over a file)
pravaha-it/.../PushdownEquivalenceTest.java  (test)
pravaha-it/.../AerospikeContinuousQueryIT.java:259  (test)
pravaha-runtime/.../plan/Pushdown.java:57 (the declaration)

$ sed -n '111p' pravaha-server/.../ingest/PluginSourceFeeds.java
PartitionReader reader = plugin.createReader(partition, SourceOffset.BEGINNING);
```
**Verdict:** the server's ingest path calls the **two-argument** `createReader`, whose default
implementation discards the request entirely (`StreamSourcePlugin` line 61–63), and never calls
`Pushdown.requestFor` or `capabilities()` at all. So **no registered continuous query on a Pravaha
server pushes any filter into any store.** Every row in the set crosses the network and is filtered
in the JVM. README line 140 — "Filters are pushed *into* the store — working today against Aerospike
and any JDBC source, so filtered rows never cross the network" — is true of `pravaha run` over a
file and of two tests, and false of the product surface the sentence describes. This is also §2's
"the thing that changes the cost curve of the whole system". HIGH.

---

## Summary

| Verdict | Count |
|---|---|
| PASS | 35 |
| FAIL | 25 |
| BLOCKED | 0 |
| NOT RUN | 0 |
| **Total** | **60** |

### Failures in severity order

| # | Sev | Case | Finding |
|---|---|---|---|
| 1 | BLOCKER | AERO-001 | No `META-INF/services` entry: the Aerospike source plugin cannot be discovered by any configuration, on any deployment |
| 2 | BLOCKER | AERO-053 | Checkpoints are write-only. Nothing calls `latest()` or `restore()` in production; a restart recovers the query and none of its state, proven end to end |
| 3 | HIGH | AERO-028 | Pushdown drops rows: a BOOLEAN bin stored as a legacy 0/1 integer is read by `copyInto` and excluded by `Exp.boolBin` |
| 4 | HIGH | AERO-029 | Pushdown drops rows: a bin whose stored type disagrees with the declaration is coerced by `copyInto` and excluded by `Exp.stringBin` |
| 5 | HIGH | AERO-060 | Filter pushdown is never wired on the server path; no registered query pushes anything into any store |
| 6 | HIGH | AERO-039 | The sink cannot write a row with a null column: `REPLACE` + `Bin.asNull` is a server parameter error. The README's own flagship query produces such a row |
| 7 | HIGH | AERO-036 | Sink → source round trip loses the key: the key is written as the record identity only, and the source reads bins, so every row returns with a null key |
| 8 | HIGH | AERO-044 | Nothing reads `emitsDeletes`/`replayableOffsets`. ADR-029's "refused at registration rather than discovered in production" mechanism does not exist |
| 9 | HIGH | AERO-009 | No weakest-link guarantee computation and no guarantee in any response, against §14.4 and risk R5 |
| 10 | HIGH | AERO-004 | Sinks and lookups have no discovery path at all; `QueryRegistry` passes `Map.of()` lookups, so a lookup-join query cannot be registered on a server |
| 11 | HIGH | AERO-020 | No TLS and no `authMode`: every byte to Aerospike, including the login exchange, is clear text with no option to change it, and no document says so |
| 12 | HIGH | AERO-002 | A binding naming an absent plugin starts the node logging `sources bound:` and fails only at the first registration |
| 13 | HIGH | AERO-059 | Four Aerospike case studies never start a server and never bind a source; QUICKSTART says Aerospike "works now" |
| 14 | MED-HIGH | AERO-048 | `checkpoint-backup.bin` (or a stray directory) makes `availableIds`/`latest`/`prune` all throw `NumberFormatException` |
| 15 | MED | AERO-030 | Pushdown disagrees with the engine for INT8/INT16 declarations over out-of-range data |
| 16 | MED | AERO-051 | `prune` counts unreadable files towards `keep` and can delete the last recoverable checkpoint |
| 17 | MED | AERO-047 | A single forward-version checkpoint makes `latest()` throw rather than fall back |
| 18 | MED | AERO-049 | `store()` never fsyncs, while the interface says it returns only when durable |
| 19 | MED | AERO-011 | Seven numeric settings parse with a bare `Integer.parseInt`; a typo is a raw `NumberFormatException` naming neither plugin nor key |
| 20 | MED | AERO-008 | `SinkCapabilities.guarantee()` returns `EXACTLY_ONCE` for an idempotent non-transactional sink, against ADR-008 and README line 155 |
| 21 | MED | AERO-057 | `AerospikeContinuousQueryIT` is a one-shot batch drain with a hand-advanced watermark; it is the README's evidence for a claim it does not test |
| 22 | MED | AERO-054 | `L0StateMap` — the tier ADR-006 is written around — is orphaned; there is no L1 |
| 23 | MED | AERO-003 | Only `filesystem` ships in the server jar and there is no way to add another |
| 24 | MED | AERO-010 | IPv6 host entries cannot be expressed; ports outside 1–65535 are accepted |
| 25 | LOW | AERO-055 | `Checkpoint.timestampNanos` is `System.nanoTime()`; every checkpoint on disk reads as 1970-01-04 |

### Observations that are not defects of this area

- A plugin jar on the classpath whose own dependencies are missing throws `ServiceConfigurationError`
  out of `ServiceLoader`'s iterator, which aborts discovery of *every* plugin behind it — reproduced
  with `pravaha-plugin-delta` and no Delta Kernel jar. That is a `PluginSourceFeeds.discover`
  robustness issue and belongs to the ingest area.
- `OrphanedClassTest.KNOWN` still lists `FileCheckpointStore` and `PeriodicCheckpointer`, which
  `QueryRegistry.startCheckpointing` now constructs. Stale, harmless.
- `LutScanReader.scan`'s Javadoc says "the watermark advances to the newest record *this scan saw*";
  the code advances it to the scan's start time. The code is the safer of the two and the block
  comment four lines below describes it correctly, so the method-level sentence is simply wrong.
- The scan watermark is the **client's** `System.currentTimeMillis()` compared server-side against
  record last-update times. A client clock ahead of the cluster's silently skips records. Nothing
  detects or documents it.

### What could not be covered, and why

- **Aerospike Enterprise, and therefore XDR.** `xdr-kafka`, `xdr-http` and `write-intercept` are
  refused by configuration and have no implementation to test (ADR-029 says they never will). The
  refusal is tested; the strategies are not testable here and never will be without a licence. This
  is the correct state of affairs, not a gap.
- **Exactly-once, end to end.** It is not implemented — ADR-008 says aligned barriers are not built,
  `DeduplicatingSink` is not wired, and AERO-053 shows checkpoints are never read. There is nothing
  to test beyond establishing that, which these cases do.
- **TLS against a TLS-enabled Aerospike.** Community Edition supports TLS, but the plugin has no
  configuration surface for it, so there is nothing to point at a TLS listener. **NOT TESTABLE HERE
  until `AerospikeSourcePlugin.configure` gains `tls.*` settings.** For a human to test it once it
  exists: generate a CA and a server certificate, add `service { tls-port 4333; tls-name ... }` to
  `aerospike.conf`, mount it into the container, and configure `tlsPolicy` with a truststore
  containing the CA.
- **Authentication against a security-enabled server.** Aerospike user management is Enterprise.
  AERO-019 establishes only that CE refuses a login attempt. **NOT TESTABLE HERE.** A human with an
  Enterprise licence should verify that a wrong password fails closed, that the password never
  reaches a log or an exception, and that `authMode` can be set for LDAP estates — the last of which
  will fail, because the field is never assigned.
- **A multi-node Aerospike cluster.** Everything here ran against a single node on host networking.
  Partition-parallel scanning (`partitions > 1`), rebalancing during a scan, and a node leaving
  mid-scan are exactly the cases `LutScanReader`'s partition-range split exists for, and one node
  cannot exercise any of them. **NOT TESTABLE HERE.** A human needs three nodes on a real network;
  the case to run is: start a scan with `partitions=4`, kill a node mid-scan, and confirm the
  resumed offsets neither lose nor silently duplicate beyond the boundary window.
- **Scale.** Every scan in this round was over fewer than ten records. Nothing here says anything
  about `records.per.second` throttling, scan timeouts under load, or whether the buffered
  `ArrayDeque` in `LutScanReader` is bounded — it is not, and a scan returning ten million records
  will hold all of them on heap before the first row is emitted. That is a design observation, not a
  measurement, and the measurement needs a populated cluster.
- **The console and the HTTP surface** were not touched; other areas own them.
