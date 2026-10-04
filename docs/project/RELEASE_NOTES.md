# Pravaha — release notes

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

> **What this page is.** One entry per cut, written from what the tree proves rather than from what
> was planned. Every number here comes from a command named beside it, so a reader can run the
> command and get the number. Where a target was not reached, the number that was measured is here
> instead of the target.

---

## Unreleased

- **Every SDK request has a deadline** (SDKDEADLINE-1). `ClientOptions.requestTimeout` (Java) and
  `request_timeout_seconds` (Python; also `connect(…, timeout=)` and the CLI's `--timeout`), 30 s by
  default, now bound every unary Flight call — a query up to its first batch, and every action — and
  the opening of a subscription, which then runs unbounded. Before, the Java setting had no reader
  and the Python one reached only HTTP, so a node that accepted a call and never answered held the
  caller for ever. A call past the deadline fails `PRV-1045 CLIENT_DEADLINE_EXCEEDED`, retryable,
  naming the call and the deadline. The 60 s stall the 2.1.0 gate saw in `JavaSdkTlsTest` was a cold
  first query (Calcite bootstrap in a fresh JVM, under full-build load) still making progress, not a
  hang.
- **`ClientOptions.connectTimeout` is deprecated** (CONNECTTIMEOUT-1). It never bounded anything:
  Arrow's Flight client builder has no setting it could feed. The connection is made inside the
  first call, so `requestTimeout` is what bounds connecting — a node that is reachable but never
  answers fails that call with `PRV-1045` at the deadline. The builder method and getter are kept
  (`@Deprecated(since = "2.1.1")`, still validated) so 2.x code compiles; Python's
  `connect_timeout_seconds` is documented the same way.

Register: **554 findings — 534 fixed, 1 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

## 2.1.0 — 2026-10-03

**Everything the adversarial QA of 2.0.0 found, fixed — and several answers change.** 2.1.0 carries
the three fix waves (all 50 QA findings, 10 of them HIGH), the documentation sweep, DECIMAL over
Arrow Flight, and static analysis gated in CI. It is a minor release because some fixes make the
engine answer differently or refuse what 2.0.0 accepted; [COMPATIBILITY.md](../operations/COMPATIBILITY.md)
tables every one ("fixes that change an answer").

**Read before upgrading from 2.0.0.**

- **Answers that change:** SUM, AVG, MIN and MAX of a group with no non-null value are NULL, not 0;
  INT/SMALLINT/TINYINT arithmetic, narrowing casts and `Long.MIN_VALUE / -1` are overflows, never
  wrapped values; ±0.0 and every NaN group together; HOP windows start on multiples of the slide.
- **Now refused:** a window finer than `pravaha.lane.max-windows-per-row` (PRV-3026); MIN/MAX over an
  input that can retract (PRV-2076) — a journalled one is refused at recovery and shown FAILED;
  `pravaha.identity.mode` other than `password`; trailing separators in host lists, schemas and
  offsets.
- **State on disk:** new checkpoints carry a CRC32C tail (2.0.0 checkpoints still load); a few 2.0.0
  checkpoints keyed on -0.0 or a non-canonical NaN rebuild from their sources.
- **Operations:** new limits on pgwire and HTTP before sign-in, per-address sign-in throttling (list
  the console in `pravaha.identity.lockout.trusted-proxies`), the console keeps its engine token
  server-side (a console restart signs everyone out; several consoles need sticky sessions), and the
  `users` profile runs the `authenticated` policy.
- **Open:** one POST-GA finding, SDKDEADLINE-1 — the Java SDK's blocking calls take no deadline.

What changed, in detail:


- **Error Prone and javac warnings cleared, and gated (ERRORPRONE-2).** Every module but `pravaha-api`
  and the two Java SDKs now compiles under `-Pep` with no Error Prone warning and no `-Xlint` warning:
  about 800 fixed or, where the code is right, suppressed at the member with its reason. In those
  modules every check Error Prone enables at WARNING is raised to ERROR, so a new one fails `-Pep`, and
  the `fast` workflow's new `errorprone` job runs it on every push. NullAway's 2,826 stay warnings.
  On the way, 57 javadoc comments stranded by later insertions were put back on their members.
- **Parsers no longer accept a trailing separator they refuse anywhere else (SPLITTRAIL-1..4).**
  `String.split` drops trailing empty fields, so `h:3000:`, `id:INT64:` and a trailing comma passed
  in the Aerospike and Cassandra host and schema options, the feedfile and postgres-cdc schemas; the
  Delta and JDBC offset tokens took a trailing `;`; an `X-Forwarded-For` ending in a bare `,` named
  the hop before it as the sign-in source; and pgwire's `SET search_path = public,` was accepted.
  Each is refused (or, for the header, falls back to the peer) like the same slip mid-list.
- **The CLI no longer colours redirected output (ANSICONSOLE-1).** It took a non-null
  `System.console()` to mean a terminal, which on JDK 22 and later it always is, so `pravaha ... >
  file` wrote escape codes into the file. It now asks `Console.isTerminal()`.
- **A feed file gone mid-read names its directory (FEEDGONE-1).** `PRV-5064` said the file was "no
  longer in FeedDirectory@4488aabb"; it now gives the directory's path.

- **Adversarial QA of 2.0.0** (2026-10-01): 322 cases over the engine, data and security
  ([cases](qa/cases/ADV-ENGINE.md), [log](qa/logs/ADV-ENGINE.md)) and the surfaces, operations and
  packaging ([cases](qa/cases/ADV-SURFACE.md), [log](qa/logs/ADV-SURFACE.md)); 244 pass, 66 fail,
  50 findings opened — 46 defects (10 HIGH) and 4 design notes. Waves 1 to 3 fixed every one of them, below.
- **A PostgreSQL CDC slot dropped under a running query is detected (CDCSLOT-1).** The reader treated
  the slot's `42704` at reconnect as one more transient failure and retried for ever, so the query
  stayed `RUNNING`, health `UP`, and every later change was silently missing. Now a permanent refusal
  at reconnect stops the feed with `PRV-5117`, and before each reconnect the reader asks
  `pg_replication_slots` whether the slot still exists, is not `lost`, and has not been confirmed past
  where the reader stopped (recreated under the same name); any of those is `PRV-5117`, logged at
  `ERROR`, with node health `DEGRADED` (FEED-1). `PostgresCdcSlotDroppedTest`.
- **The PostgreSQL gateway bounds what an unauthenticated peer can make it hold (PGPREAUTH-1).** It
  allocated whatever a client declared for its `PasswordMessage` — up to 16 MiB — before reading a
  byte, from a pool with no connection cap: sixty silent sockets ended a 1 GiB node. Now a message
  before sign-in is at most 16 KiB and refused on its declared length (`54000`, `PRV-6217`); any
  message is allocated as its bytes arrive; magic packets must have their exact length; the handshake
  has one 10 s deadline a trickling peer cannot renew; and new settings `pravaha.pgwire.limits.*`
  bound connections (`max-connections` 100, `max-unauthenticated` 32, optional
  `max-connections-per-principal`; past them `FATAL 53300`, `PRV-6216`, before a thread is spent),
  the signed-in message size (`max-message-size` 1MB) and idle time (`idle-timeout`, off; `57P05`,
  `PRV-6219`). Out-of-range limits stop the node (`PRV-6220`). `PgWireLimitsTest`,
  `PravahaNodePgWireLimitsTest`.
- **Revoking a credential ends the PostgreSQL connections it opened (PGREVOKE-1).** The gateway
  checked the password once, at sign-in; a revoked key, a signed-out session or a disabled user kept
  reading on an open connection. It now verifies the credential again before every statement (`Query`,
  `Parse`, `Bind`, `Describe`, `Execute`) and ends the connection `FATAL 28000` (`PRV-6218`); a role
  removed applies from the next statement. Flight subscriptions already re-verified every two seconds.
  `PgWireLimitsTest`, `PgWireSignInTest` (real pgjdbc against identity: key revoked, session signed
  out, user disabled).
- **HTTP request bodies are bounded before anything reads them (HTTPBODY-1).** A body was read whole,
  before authentication, up to Jackson's 20 M-character limit, and thirty 19 MB anonymous sign-ins
  ran a 1 GiB node out of heap. A new filter, first in the chain, refuses a body over
  `pravaha.http.max-anonymous-body` (16KB, open paths) or `pravaha.http.max-request-body` (4MB) with
  `413` `PRV-1054` on its declared length, or as soon as a chunked body passes it; at most
  `pravaha.http.max-concurrent-sign-ins` (8) sign-ins run at once (`429` `PRV-1055`, `Retry-After`);
  Tomcat's `max-connections`, `max-swallow-size` and form-post size are set in `application.yaml`.
  `RequestLimitHttpTest`, `RequestLimitFilterTest`.
- **`SUM`, `AVG`, `MIN` and `MAX` of a group with no non-null value are NULL** (ALLNULLAGG-1), as
  SQL says — in a window, a continuous query, over a view and on a read. They were published 0,
  indistinguishable from a real total of zero. `COUNT(col)` is still 0 and `COUNT(*)` counts rows; a
  retraction that leaves only NULLs makes the answer NULL again, and checkpoints carry it (one
  written before still restores).
- **Narrow-integer arithmetic is never published wrapped** (NARROWINT-1). An `INT`, `SMALLINT` or
  `TINYINT` result outside its type's range — `+`, `-`, `*`, unary minus, `ABS`, a narrowing `CAST`
  of an integer — is an overflow, handled as a `BIGINT` one is (the query stops, naming the
  expression, value and range), and a filter on the expression meets the same overflow as its
  projection. `2e9 * 2` was published `-294967296` while `WHERE i * 2 > 0` kept the row.
  `CAST(i AS BIGINT) * 2` asks for the 64-bit answer.
- **A cast or a quotient with no answer of its type is an overflow** (NARROWCAST-1, DIVMIN-1).
  `CAST(d AS BIGINT)` (or `INT`, `SMALLINT`, `TINYINT`) of `NaN`, `±Infinity` or a value past the
  target's range, a finite `DOUBLE` past `REAL` cast to `REAL`, and `-9223372036854775808 / -1` are
  handled as any other overflow (the query stops naming the expression and value, or the row is
  dead-lettered as DLQPROJ-1 describes). They were published as `0`, `±9223372036854775807` and
  `-9223372036854775808`, and `WHERE CAST(d AS BIGINT) = 0` kept NaN rows. An integer literal outside
  `BIGINT` is refused `PRV-2021` at registration instead of compiled as its low 64 bits. A query that
  ran over such values now stops on them: filter them out (`WHERE d BETWEEN ...`) or keep them `DOUBLE`.
- **A row that fails evaluation goes to the dead-letter queue, as the guide said** (DLQPROJ-1). With
  `pravaha.dlq.directory` set, a row whose evaluation fails before it reaches state — a division by
  zero, an overflow, a cast with no answer, in a `WHERE`, a projection or a computed column — is
  written to the query's queue coded `PRV-3027` (new), its columns as a JSON object, and the query
  keeps running; pushed rows too, so every query now has a `<query>.dlq` once a directory is set.
  Such an entry is not replayable (`PRV-4092`). A failure above an aggregate, window, join or top-N
  still stops the query, and without a queue every one does, as before. **Upgrade:** a query that
  used to stop on such a row now keeps running with the row in its queue — watch the queue's depth.
- **A push one query cannot take is committed by the others, and says so** (PUSHPARTIAL-1). The
  embedded engine applied a push to every query on the stream and committed them one by one, so the
  first query whose lane had died threw `PRV-3010` and left the rest applied and unpublished until some
  later push; a caller retrying the push it was told failed counted the row twice. Each query now takes
  a push independently: the healthy ones commit it, and the push throws `PRV-8105` (new) naming the
  queries that have the rows and the ones that do not — do not retry it. When no query took it, the
  failure is reported unchanged and a retry is right.
- **A second engine in one JVM cannot claim a running engine's state** (SAMEPIDCLAIM-1). The ownership
  marker names a process, so a second embedded engine with the same node id (the default,
  `pravaha-embedded`) on a running engine's directories took it for a re-claim, ran beside it, and on
  close deleted the first engine's marker — after which another node's id was accepted. The claims a
  process holds are now kept in-process too: the second engine is refused `PRV-4003` (`another engine
  in this process holds the state`), and a close deletes only the marker that claim wrote. One engine
  naming a directory twice (a journal in its checkpoint directory) is still one claim.
- **A checkpoint carries a checksum** (CKPTSUM-1). Only the header, counts and trailer were checked, so
  a single flipped bit in a window's state was restored and published, for ever (8–10 of 16 flips in
  QE-080). Every checkpoint now ends with a CRC32C of its contents, checked before anything in it is
  read; one that does not match is skipped with `PRV-4094` (new) and the one before it restored, as a
  truncated one is. **Compatibility:** a 2.0.0 checkpoint has no checksum and is restored as before,
  logged as unverified; the checksum is a tail after an unchanged body, so 2.0.0 still reads a 2.0.1
  checkpoint on a rollback.
- **A checkpoint of another output schema is not restored** (RETYPERESTORE-1). A query re-registered
  over a stream whose selected column changed type (`v BIGINT` to `VARCHAR`) got the old `Long`s
  restored into its new `VARCHAR` column. A checkpoint now records the output schema, and one of
  another schema is not restored: the query rebuilds from its sources, its last checkpoint failure
  saying `PRV-4095` (new) with both schemas. A 2.0.0 checkpoint records none and is restored as before.
- **A GROUP BY on a DOUBLE counts every row once** (NANGROUP-1). A window grouped a `DOUBLE` by its
  bits, so `-0.0` and `0.0` were two groups and two `NaN` payloads two more, which the view then showed
  as one row — five rows in, counts summing to four. Every grouping path (windowed and unwindowed
  aggregates, `COUNT(DISTINCT)`, a read's `GROUP BY`, a view's key) now follows SQL equality: either
  zero is one group, published `0.0`, and every `NaN` is one, published `NaN`. A windowed `GROUP BY`
  on a `REAL` also hashed the next column's bytes with the key, so equal keys could split; it hashes
  the float alone now. **Answers change:** a query grouping on a column that holds both zeros or
  several `NaN` payloads publishes fewer, merged groups. **Upgrade:** a 2.0.0 checkpoint holding a
  `-0.0` or a non-standard `NaN` as a key or distinct value is not restored — restored, it would stay a
  group apart — and that query rebuilds from its sources; any other checkpoint restores as before.
- **HOP windows start on multiples of the slide, as SQL's HOP does** (HOPALIGN-1). A hop whose size is
  not a multiple of its slide aligned its window *ends* to the slide: `HOP(10 s slide, 25 s size)` put a
  row at 12 s in `[-5, 20)` and `[5, 30)` where SQL (Calcite, Flink) says `[-10, 15)`, `[0, 25)` and
  `[10, 35)`, and the firing disagreed with the state's discard about a row's last window. Windows now
  start at `k × slide`. **Answers change** for such hops only — a `TUMBLE`, and a `HOP` whose size is a
  multiple of its slide, have exactly the windows they had. **Upgrade:** a checkpoint of such a hop
  restores (its slices are unchanged), and windows still open fire on the new boundaries; windows the
  view already holds keep their old boundaries until retention removes them.
- **A window too fine for its size is refused at registration, `PRV-3026`** (FINEHOP-1). Each row of
  a `HOP` is published in `size / slide` windows of `size / gcd(size, slide)` slices; where either
  passes the new `pravaha.lane.max-windows-per-row` (100,000 by default; server and embedded), the
  registration is refused naming the size, the slide and both counts. `HOP(INTERVAL '0.001' SECOND,
  INTERVAL '1' DAY)` used to register, and one row of it held its lane and every push to the stream.
- **Damage in the middle of the registry journal refuses the start, `PRV-8005`** (JOURNALMID-1),
  naming the byte offset of the damaged length and of the complete record after it. Any length that
  ran past the end used to be read as a torn final record, so one damaged prefix silently dropped
  every later registration at every start. A genuinely torn tail is still replayed up to, and is now
  cut off before the next append — a registration appended behind it used to be lost at the next start.
- **The surviving name of a shared computation keeps its state across a restart** (SHAREDLOSS-1).
  Two names on one computation checkpoint into the starting name's directory; dropping the starting
  name now journals, with the drop, that the survivors checkpoint there (a new `M` record, applied in
  place). They used to come back from a restart RUNNING and empty.
- **The embedded engine reads `pravaha.lane.*`, and a row wider than an inbox cell is refused for that
  row** (CELLBYTES-1). The cell was 512 bytes whatever was configured, and a 600-character string
  stopped every query on the stream for good with a refusal naming a setting that could not be
  applied. Now an embedded push wider than a query's cell is refused `PRV-8102` before any of it is
  delivered, naming the row, its size and the cell; a row handed to a registered query directly is
  refused `PRV-3002` without failing the query; and a source's writer is bounded to its inbox cell, so
  a wide row is never written into the cells after it.
- **Flight SQL `GetTables` lists the views a non-admin may read under the catalogue (GETTABLES-1).**
  The table list asked each stream behind a view for the caller's own `SELECT`, which the catalogue
  does not require to read a view (ADR-059 §2), so every ordinary user's BI catalogue was empty —
  even of views they owned. It now asks what `pravaha.list` asks (`mayReadThrough`), so a view the
  caller owns or was granted is listed, and nothing of another tenant's. `FlightSqlMetadataTest`,
  `AdvSecurityTest` QE-168 enabled.
- **A catalogue row filter withholds the view's row count from the reader it narrows (LISTCOUNT-1).**
  SX-18 withheld `ROWS IN` (`-1`) only for a filter carried by `pravaha.security.policy`; a reader
  narrowed by `CREATE ROW FILTER` was told the whole view's count by `pravaha.list`,
  `SHOW CONTINUOUS QUERIES` and `GET /api/v1/queries`. The listing now consults the catalogue's
  narrowing too (and withholds when it cannot be bound to the caller). `QueryListingNarrowingTest`,
  `AdvSecurityTest` QE-111 enabled.
- **A 4- or 2-byte integer parameter against a `BIGINT` column is widened, not refused
  (PGINTPARAM-1).** The PostgreSQL gateway read every binary parameter at the width of the column it
  was compared with and ignored the type the client declared in `Parse`, so pgjdbc's `setInt`,
  psycopg's `%b` with a small `int` and Npgsql's (Power BI's) `int` parameters were `08P01 PRV-6202`.
  A binary number is now read as its declared type and widened as PostgreSQL widens it (`int2`/`int4`
  to any wider integer or to `DOUBLE`, `float4` to `DOUBLE`); a wider integer is accepted only when its
  value is in range (`PRV-2062` otherwise). `PgTypesTest`, `JdbcClientTest` (real pgjdbc `setInt`,
  `setShort`), ADV-SURFACE `test_qi031` now a passing check.
- **A stream declared over HTTP can be registered over (DECLSTREAM-1).** `POST /api/v1/streams`
  (`pravaha streams declare`, `Client.declare_stream`) recorded the stream in the node's catalogue,
  which listing and validation read, but the registry planned over a copy of the catalogue taken at
  start, so `pravaha register` over it was `PRV-2002 not found` — catalogue on or off. The registry is
  now told of every stream declared after start (a new name takes a fresh stream identity, a new
  version keeps the one it replaces). The declaration, and so a query over it, still lasts until the
  node restarts. `DeclaredStreamTest`, `DeclaredStreamRegistrationTest` (HTTP declare, validate, Flight
  register), ADV-SURFACE `test_qi059` now a passing check.
- **Account lockout no longer tells anyone which user names exist, and cannot be used to lock a user
  out indefinitely (LOCKENUM-1).** After five failures a real account answered `423 PRV-7011 locked
  until …` (quickly, without checking the password) while an unknown name kept answering `401`, so
  six requests enumerated users and anyone could lock any known account — `admin` included — for 30
  minutes, repeatedly. Now an unknown name, a wrong password and a barred sign-in all answer
  `401 PRV-7010`, identically and after the same password-hash work; five failures from one address
  within 15 minutes bar *that address* from the account for 30 minutes, and fifty from any addresses
  lock the account for 30 minutes. The lock is audited and visible to administrators. New setting
  `pravaha.identity.lockout.trusted-proxies` (addresses or CIDR blocks; the compose stack trusts
  `172.16.0.0/12`) whose `X-Forwarded-For` is believed; the console now sends the browser's address
  (the Python SDK's `RestClient` takes `headers=`). Policy in SECURITY.md. `IdentityServiceTest`,
  `SignInThrottleTest`, `SignInSourceTest`, `IdentityHttpTest`, console `test_identity`, SDK
  `test_rest`, ADV-SURFACE `test_qi054` now a passing check.
- **Reads over a view of a million rows answer, or are refused by their own code (BIGREAD-1).** A
  read ran as one unbounded batch, so nothing reclaimed its arenas: `SELECT *` over a ~1 M-row view
  was refused `PRV-3001` (the projection's arena) where the documented answer past 1,000,000 result
  rows is `PRV-4024` / `54000`, and `COUNT(*)` failed with a bare `Index -1 out of bounds for length 64`
  (`XX000`, no code) from the read's own exhausted arena. A read now ends a batch every 4,096 rows
  as a lane does — the operators settle and both arenas are reclaimed — so `COUNT(*)` and other
  aggregates over the whole view answer, `SELECT *` past the ceiling is `PRV-4024`, and a single row
  too wide for an empty arena is a coded `PRV-3001`. `LargeViewReadTest` (90,000 wide rows and
  1,000,010 narrow ones; fails without the fix).
- **Re-running `tools/docker-env.sh` keeps the settings `.env` says are yours (ENVRERUN-1).** The
  script wrote `.env` with `cat > .env <<EOF` whose `$(port …)` substitutions read `.env` back — after
  the redirection had already truncated it — so a re-run reset the image tag and every port but
  pgwire's to the defaults, and the next `compose up` bound them silently. Every value is now read
  first, the file is written to a temporary and renamed into place, and `COMPOSE_PROJECT_NAME`,
  `PRAVAHA_BIND` and lines the script does not write (`COMPOSE_PROFILES`, …) are kept too. New
  `tools/docker-env-test.sh` (run twice with edited values, then a third time byte-for-byte; no
  Docker), wired into the packaging workflow.
- **A windowed query over a burst into one partition of several closes its windows (SEEDWINDOW-1).**
  The Docker `seed` profile writes twelve orders, all into one partition of the three-partition
  `orders` topic; the other two never produce. All three crossed `idle-after` on the same tick, and
  with every partition idle the watermark "stays where it is" — which was nowhere, so
  `spend_per_minute` stayed empty for good (the guide shows ten rows). With every partition idle the
  watermark now catches up to the lowest watermark among the partitions that delivered rows — what
  they themselves said, never past it, never backwards — so the windows the burst has passed close
  `idle-after` after it. The idle-exclusion rule is unchanged otherwise. `WatermarkTrackerTest`;
  proved on the compose stack (`--profile seed`: `spend_per_minute` 10 rows, 10:00 to 10:04).
- **The negation of a floating-point comparison is its IEEE complement (NANNOT-1).** `NOT (d > 5)`
  was compiled as `d <= 5`, which is FALSE for `NaN` as `d > 5` is, so a `NaN` row was in neither a
  predicate nor its negation, and `IS FALSE` / `IS NOT FALSE` over such a comparison dropped and added
  rows the same way. A negated `DOUBLE`/`REAL` comparison is now the total complement restricted to
  rows where both sides are present, interpreted and generated alike; a NULL stays out of both.
  `NegatedFloatingComparisonTest`, QE-014.
- **A file timestamp past 2262 is refused, not wrapped into 1677 (FARTIME-1).** The filesystem codec
  multiplied epoch seconds by 10⁹ unchecked, so `3000-01-01T00:00:00Z` was stored as
  `-4389808147419103232` ns. It now checks the product and refuses the line with `PRV-5040` naming the
  line, the column and the range (1677-09-21 to 2262-04-11 UTC) — dead-lettered with a queue, a stopped
  source without one; a `DATE` past 32 bits of days likewise. The Cassandra event-time read is checked
  the same way (`PRV-5087`). `FilesystemPluginTest`, QE-164.
- **`MIN` and `MAX` over an input that retracts are refused up front (MINRETRACT-1).** The first
  retraction of the extreme stopped the query at run time (`PRV-3020`), windowed or not, after it had
  been accepted over a CDC source or a file with an operation column. Such a query is now refused at
  registration with the new `PRV-2076`, naming the aggregate and the stream; an embedded `retract(...)`
  that would reach one is refused `PRV-8102` before any row is delivered, with every query left running.
  A journalled query of this shape is refused at recovery. `RetractedExtremesTest`,
  `RetractedExtremeTest`, QE-044.
- **An alert comparing a masked column is refused when it is created (MASKALERT-1).** `CREATE ALERT …
  WHERE card = '…'` by an owner for whom `card` is masked answered `ACTIVE`, then the alert showed
  `following=BROKEN` and never fired — closed, but not the plan-time `PRV-7006` SECURITY.md promises.
  `CREATE ALERT` now runs the same check the alert runs when it follows, so the person creating it gets
  `PRV-7006` and nothing is journalled; a mask applied later still marks an existing alert broken with
  the code. `AlertNarrowingTest`, QE-105.
- **A window of a million groups is emitted, not stopped for room (EMITROOM-1).** A firing window
  allocated every result row in the pipeline's 64 MiB arena before the batch ended, so about 836,000
  groups stopped the query with `PRV-3001 no room to emit a window result`, below the view's 1,000,000
  ceiling (`PRV-4022`) that names the limit. Each emitted row is now given back to the arena once
  downstream has copied it — in the windowed and the grouped aggregate — so emission needs one row of
  arena however many groups fire. `EmissionRoomTest`, QE-144.
- **The embedded `register(...)` builds on a view (QOQAPI-1).** It resolved the key columns by
  planning the SQL over the declared streams alone, so `register("down", "SELECT g, SUM(v) AS s FROM up
  GROUP BY g", "g")` was `PRV-2002 Object 'up' not found` while the same SQL as `CREATE CONTINUOUS
  QUERY` worked. It now plans as the registration does — streams, lookups and the registered views.
  `EmbeddedRegisterApiTest`, QE-068.
- **API failures carry a code and a reason (UNCODEDAPI-1).** A keyless `register(...)` is `PRV-2070`
  (the statement's own refusal, saying a global aggregate may be keyed by any of its own columns), a key
  the query does not produce `PRV-2071`, a pushed `Instant` past 2262 `PRV-8102` naming the column, and
  a checkpoint directory a registration cannot create `PRV-4093` — each was an uncoded
  `IllegalArgumentException`, `ArithmeticException` or `UncheckedIOException`. A parse the parser
  abandons without a message (3,000 nested parentheses, a 1.2 MiB `OR` chain) says it nests too deeply
  instead of `PRV-2001  null`. A `BIGINT` overflow in an expression throws its own exception naming the
  expression, so a lane failure still says "long overflow" after the JIT has compiled `Math.*Exact`'s
  throw site and dropped its message. `EmbeddedRegisterApiTest`, `SqlPlannerTest`,
  `NarrowIntegerOverflowTest`, QE-062/063/085/139/152/166.
- **`pravaha-engine run --dlq` dead-letters rows that fail evaluation (CLIDLQ-1).** The one-shot
  runner dead-lettered only records the source could not decode, so a row that divided by zero stopped
  the run with `--dlq` given, while the server and the embedded engine dead-letter it (`PRV-3027`,
  DLQPROJ-1). The runner now attaches the same row-failure path to its queue: the row is written with
  its columns, counted in `N rejected`, and the run finishes; without `--dlq` it still fails the run.
  `PravahaCliTest`.
- **A source naming a plugin that is not there stops the node at startup (PLUGINLATE-1).** A binding
  such as `plugin: redis` started the node `UP` (`sources bound: [t <- redis[key]]`) and was refused
  `PRV-5090` only at the first registration, though CONNECTORS.md said "refused at startup"; and the
  refusal said the server jar "carries filesystem alone" right after listing the nine source plugins it
  carries. A source binding is now looked up when it is bound — at the node's (and the embedded
  engine's) start — and the message lets the list speak: check the name against it, or put the module on
  the classpath. CONNECTORS.md, QUICKSTART's YAML comment, the build guide and the console topics no
  longer say the jar carries `filesystem` alone. `UnknownPluginAtStartupTest`, `PluginSourceFeedsTest`.
- **A registration refused at recovery stays visible until it is dropped (RECOVERYHEALTH-1).** A
  journalled query a restart refused — a CDC slot overtaken (`PRV-5115`), a binlog purged (`PRV-5155`),
  an owner who lost the right (`PRV-8007`) — vanished from `pravaha queries` with one `WARN` line and
  health `UP`. It is now logged at `ERROR`, listed `FAILED` with its code by `pravaha.list` (the code
  and reason in the feed fields, `where` = `recovery`), `SHOW CONTINUOUS QUERIES` and
  `GET /api/v1/queries`, counted by the new gauge `pravaha_registry_recovery_refused`, and turns the
  `engine` health indicator `DEGRADED` (`refusedAtRecovery`, `firstRefusedAtRecovery`). The journal is
  unchanged, so the next start tries it again; `DROP CONTINUOUS QUERY <name>` removes the entry and
  deletes its checkpoints, and registering the name replaces it. `RecoveryRefusalsTest`,
  `RecoveryRefusalSurfacesTest`.
- **A Kafka topic deleted under a running query stops its feed (TOPICGONE-1).** The consumer only
  logs "unknown topic or partition" for a deleted topic, so the query stayed `RUNNING`, its feed
  `RUNNING` and health `UP` for as long as the topic was absent. A quiet reader now asks the brokers for
  its topic and, once they have not known it for the new option `topic.missing.timeout` (30 s, at least
  1 s), stops with the new `PRV-5130`: the feed stops and node health is `DEGRADED`, as for any stopped
  source (FEED-1). A broker that cannot be asked is not counted. `KafkaSourcePluginTest`,
  `KafkaSourceBrokerTest` (Testcontainers: a real topic deleted under a reader).
- **A CDC role without `REPLICATION` gets the prerequisite's code and remedy (CDCPRIVCODE-1).** After
  `ALTER ROLE … NOREPLICATION`, registering over the table was `PRV-5118 … cannot start the initial
  snapshot … FATAL: permission denied to start WAL sender` followed by advice about transactions left
  idle and `max_replication_slots` (and `PRV-5111` when the plugin created the slot). Refused for want
  of privilege (`42501`) when creating the slot, starting the snapshot or starting the stream, it is now
  `PRV-5112` naming `ALTER ROLE <role> REPLICATION;` (`GRANT rds_replication TO <role>;` on Amazon RDS
  or Aurora). `ReplicationPrivilegeRefusalTest` (Testcontainers, PostgreSQL 16).
- **The PostgreSQL gateway refuses `COPY` and cursors with the code it documents (PGCOPY-1).**
  `COPY`, `DECLARE`, `FETCH`, `MOVE`, `CLOSE`, `LISTEN`/`NOTIFY` and `SELECT STREAM` reached the
  planner and came back `42000 PRV-2001` (a syntax error) or, for `SELECT STREAM`, `42P01 PRV-4023`;
  they are now refused by name on both protocols with `PRV-6201` and `0A000`, as the pgwire topic
  promised, and the session goes on. `PgProbeAndRefusalTest`.
- **The PostgreSQL gateway answers connection-validation probes (PGVALIDATE-1).** `SELECT 1` —
  HikariCP's `connectionTestQuery`, DBeaver's "Test connection", Grafana's health check — and `SELECT
  now()`, `SELECT 'a'::text`, `SHOW search_path` and `SET search_path` were refused. A `SELECT` with no
  `FROM` made of literals, literal casts, the clock (`now()`, `current_timestamp`, `current_date`, …),
  `current_user` and the existing `version()`/`current_schema()` family is answered with PostgreSQL's
  column names and types; `SHOW search_path` answers `"$user", public`; `SET search_path` is accepted
  when the path keeps `public` (the only schema) and refused otherwise. Anything else without a
  `FROM` still reaches the planner. Documented under "Connection checks and probes" in the pgwire
  topic. `PgProbeAndRefusalTest`.
- **Malformed Flight requests are refused by name (FLIGHTTICKET-1).** A `DoGet` ticket the server
  did not issue (`\x00\xff…`, `NOPE:x`, `LIST`) and a path descriptor on `GetFlightInfo`,
  `GetSchema` or `DoPut` reached Flight SQL's own parser and failed as gRPC `INTERNAL` with no code.
  They are now `INVALID_ARGUMENT` with new code `PRV-6106` (FLIGHT_UNREADABLE_TICKET) and
  `UNIMPLEMENTED` with `PRV-6101`. `FlightMalformedRequestTest`.
- **A Flight subscription's re-verification compares the principal (FLIGHTPRINCIPAL-1).** Every two
  seconds a subscription checked only that its credential still verified; now it must still verify
  as the same principal, as the PostgreSQL gateway checks (PGREVOKE-1), and any verifier failure ends
  the stream. `SubscriptionRevocationTest`.
- **Requests the HTTP server refuses itself are ApiErrors (TOMCATHTML-1).** An encoded `/` or `\`
  or a NUL in the path, an oversized header and too many headers were refused by Tomcat before any
  servlet ran, with its HTML error page. The host's error-report valve is replaced with one that
  writes the `ApiError` JSON every other failure has: `400` with new code `PRV-1056`
  (API_MALFORMED_REQUEST); any other status the container produces alone is `PRV-1052`.
  `ApiErrorShapeTest` (raw socket).
- **The console cookie no longer carries a usable credential (COOKIETOKEN-1).** It was signed, not
  encrypted, and its readable payload held the engine session token — which works directly against
  Flight, HTTP and pgwire — and, for one round trip, a just-issued API key or reset secret. The cookie
  now holds an opaque id; the secrets stay in the console process (`routes/session_vault.py`) and
  expire with the session. A console restart signs everybody out of the console; several instances
  need sticky sessions. `test_identity.py`.
- **A console cookie copied before sign-out reads as signed out (LOGOUTREPLAY-1).** The copy opened
  `/queries` with the signed-in chrome and `PRV-1041 PRV-7001`: the console looked only at the first
  code of an SDK-wrapped refusal. Every code is read now, so an engine-ended session goes back to sign
  in as documented; and a cookie whose session was signed out is cleared and sent to the landing page,
  where signing out goes. `test_identity.py`.
- **The console sends security headers (CONSOLEHDR-1).** No response carried a CSP, `X-Frame-Options`,
  `nosniff` or a `Referrer-Policy`, so the console could be framed by another site. Every response
  now carries a Content-Security-Policy (scripts from the console or inline with the response's
  nonce; `frame-ancestors 'none'`; `object-src 'none'`; forms post only to the console),
  `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`, a
  `Permissions-Policy`, and HSTS over https. The three inline handlers the policy would refuse became
  listeners. The browser suites now fail on any CSP violation. `test_security_headers.py`.
- **The console is ready in front of an engine that requires a credential (CONSOLEREADY-1).**
  `/health/ready` was always `503`: the console holds no credential, and its probe read the SDK's
  wrapped refusal (`PRV-1041 PRV-7001 …`) as "unreachable". Readiness now asks the engine's
  anonymous `/actuator/health` when `engine.http_url` is set (`UP`/`DEGRADED` ready, `DOWN` not), and
  a wrapped refusal of the credential counts as an answer. `test_readiness.py`.
- **The Helm chart renders only names Kubernetes accepts (HELMNAME-1).** A 78-character
  `fullnameOverride` was cut to 63 and then suffixed (`-headless` at 72, `-test-probes` at 75); an
  empty `image.repository` rendered `":<tag>"`. The base name is now cut to 52 (a StatefulSet's pods
  carry a revision label of its name plus 11), every derived name shortens the base and keeps its
  suffix, and an empty repository fails the render with a message. `deploy/helm/test.sh`: 22 checks.
- **`pravaha subscribe`'s help says what its flags do (SNAPDOC-1).** `--limit N` said "stop after N
  rows" and stopped at the end of the commit that reached N (a 1 009-row snapshot for `--limit 2`) —
  the documented and right behaviour, since a consumer applies whole commits; the help now says so.
  `--limit 0` and negative numbers, which never stopped, are refused (exit `2`). `--snapshot` said
  "the view's rows" and prints the changelog — on a keyed view, every version of a key; the help and
  the start-up note say so and point at `--answer`, and CLI.md documents `--answer` and the caveat.
  `test_cli_flight.py`.
- **The `users` profile runs the `authenticated` policy (PERMISSIVEUSERS-1).** `dev,users`, the
  profile every guide starts the console's engine with, left the default `permissive` policy in
  force: any signed-in user could pause any view, read the audit trail and see every tenant's use.
  `application-users.yaml` now sets `pravaha.security.policy: authenticated` — reads and
  registrations for every signed-in user, administration by ownership, grants or `admin`, the trail
  for `admin`. The guides and SECURITY.md say what `dev` and `users` each grant. **Upgrade note:** a
  home whose catalogue imported `permissive` under this profile refuses to start with `PRV-7034`; set
  `pravaha.security.policy: permissive` to keep it, or `pravaha.catalog.authority: catalog`.
  `UsersProfileTest`.
- **The guides match 2.0 (STALEDOC-1).** GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER and _WITH_DOCKER name
  the `2.0.1-SNAPSHOT` jars; QUICKSTART's "What is not built" no longer lists the Kafka plugin, the
  Spring Boot starter, the time-travel debugger or column masking (all built) and states clustering as
  ADR-039/ADR-045 have it; §4's second server keeps `dev` (it refused with `PRV-7004`) and stops the
  first; §7 says `make install` needs `python3-venv` or `uv venv --seed .venv`; the Java and Python
  snippets read until the new view has filled (the Java one printed nothing); the console's
  "deliberately not there" list, long out of date, says how it is built; CLI.md lists `explain-sql`
  and `subscribe --answer`. QUICKSTART re-run verbatim on JDK 25 (25.0.4.1): §2–§8, §7's console
  against `dev,users`, and both snippets (`750`).
- **`-Pep` runs Error Prone and NullAway (ERRORPRONE-1).** The `ep` and `all` profiles set a property
  nothing read. `-Pep` now runs Error Prone 2.50.0 as a forked javac plugin over the whole reactor,
  main and test code, with NullAway 0.14.1 at WARNING; the default build is unchanged and `-Pall`
  no longer claims to include it (`-Pall,ep`). The ten ERROR-level findings are resolved — nine
  fixed (a boxed-`Boolean` identity comparison in `StateOwnership`, ignored return values in tests
  that now assert them, a double-brace map), one suppressed with its reason (a test pinning an
  overflow); 3,570 warnings remain listed (2,822 NullAway), see TESTING.md. No workflow runs
  `-Pall` or `-Pep`, so no CI job was added.
- **A `DECIMAL` column is read over Arrow Flight, exactly (FLIGHTDECIMAL-1).** `ArrowSchemas`
  refused `DECIMAL` with `PRV-6100`, and every SDK, the CLI and the console read over Flight, so a view
  whose answer carried a decimal could be read only through the PostgreSQL gateway. A `DECIMAL(p, s)`
  column now goes out as Arrow's `decimal128(p, s)` on every Flight path that writes rows — queries and
  point reads, changelog and answer-following subscriptions, snapshots — and in `GetTables`' and a
  statement's schema; `GetXdbcTypeInfo` lists `DECIMAL`. The engine's 128-bit unscaled value is
  Decimal128's, so nothing is rounded; a value that would have to be rounded to fit its column is
  refused with `PRV-6100` naming the column. The Java SDK reads a `BigDecimal` at the column's scale
  (new `Row.getBigDecimal`; `getString` writes plain digits), the Python SDK a `decimal.Decimal`; the
  `pravaha` CLI prints a decimal's digits (`0.0000000000`, not `0E-10`) in its table and TSV and a
  string in `--json`, and the console sends one to the browser the same way, live views included. A
  `?` placeholder compared with a `DECIMAL` is still refused (`PRV-2021`). `FlightDecimalTest`,
  `JavaSdkDecimalTest`, `FlightSqlMetadataTest`, `ErrcFlightTest`, the Python SDK's
  `test_a_decimal_column_*` and `test_against_the_engine_a_decimal_prints_its_digits_in_every_form`,
  the console's `test_a_decimal_column_reaches_the_browser_exactly_and_plain`.
- **`pravaha.identity.mode: sso` or `hybrid` stops the node at start (SSOMODE-1).** Both were
  accepted although single sign-on is not built and no identity provider can be configured, so the
  node signed everyone in with passwords while its configuration said otherwise, and admitted it only
  in a startup warning. `password` is now the one accepted value; anything else is `PRV-7004` at
  start, naming the setting and the value it takes, whether `pravaha.identity.enabled` is on or off.
  **Upgrade note:** a node configured with `sso` or `hybrid` must remove the setting or set it to
  `password`; it signed people in with passwords either way, so nothing else changes. `NodeIdentityTest`.
- **The client modules are clean under `-Pep`, and held there.** `pravaha-api`, `pravaha-sdk-java`
  and `pravaha-sdk-java-flight` had 72, 6 and 61 static-analysis warnings (NullAway, Error Prone's
  WARNING checks, one `-Xlint`); they have none, and each module's `ep` profile now runs NullAway at
  ERROR and fails on any warning, so they stay at none. Their nullness is a contract written with
  JSpecify's `@Nullable` — compile-only in `pravaha-api` and `pravaha-sdk-java`, which stay
  dependency-free, and at compile scope in `pravaha-sdk-java-flight`, where Guava already brought the
  same jar. What a caller can now see in the types: `Row.get`, `getString` and `getBigDecimal`
  return null for a null column; `RegisteredQueryInfo`'s and `ReplacementInfo`'s optional fields,
  `DeadLetterInfo.at`/`replayedAt`, `DebugStatePage.key`, `DebugStepReport.watermarkNanos`,
  `DebugSessionInfo.watermarkNanos` and `ReconnectingSubscription.Reconnect.giveUpAfter` may be
  null, as their javadoc said; `register`'s sink and retention, `replace`'s options,
  `debugFork`'s checkpoint and `ClientOptions.Builder.token` accept null. In `pravaha-api`:
  `ReadRequest.Filter.value` (null for `IS [NOT] NULL`), `PartialAggregate.AggregateCall.column`
  (null for `COUNT(*)`), `Notification`'s `view`, `tenant`, `severity`, `since` and `at`,
  `StreamSourcePlugin.orderedPositions()`, and the `ControlWire` and `HelpUrls` helpers that took
  null already. Behaviour changes: `DeadLetterInfo.equals`/`hashCode` compare the record's bytes by
  content (they compared the array's identity, so two equal dead letters were unequal), and a
  failure the server gave no description reads "the server sent no description" rather than
  "null". See TESTING.md, *Static analysis*.

Register: **553 findings — 533 fixed, 1 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

## 2.0.0 — 2026-10-01

**2.0.0 — breaking: Java 25 required.** Pravaha is built, tested, run and released on JDK 25 only,
and every module, `pravaha-api` and the Java SDKs included, is compiled to Java 25 class files
(ADR-061). With it, `pravaha.security.administer: legacy-read` is removed, as 1.0.0 announced. Those
are 2.0's two breaking changes: the SQL, the wire protocols, the HTTP API, the Python SDK, every
other configuration key and the state on disk are as 1.x left them
([../operations/COMPATIBILITY.md](../operations/COMPATIBILITY.md), "2.0").

- **Who it breaks.**
  - *Applications embedding the engine* (`pravaha-embedded`) or compiling a plugin against
    `pravaha-api`: the host JVM must be 25. On 21 the classes do not load
    (`UnsupportedClassVersionError`, class-file version 69).
  - *Java SDK clients* (`pravaha-sdk-java`, `pravaha-sdk-java-flight`, the `-all` jar): need Java
    25. In 1.x `pravaha-api` and `pravaha-sdk-java` targeted 17 and the Flight client 21. The wire
    is unchanged, so a client that cannot move yet can keep a 1.x SDK against a 2.0 node meanwhile;
    the tested pairing is still the same major.minor.
  - *Spring Boot starter users* (`pravaha-spring-boot-starter`): the application runs on Java 25,
    and on **Spring Boot 3.4 or later**. Boot 3.2 and 3.3 (Spring Framework 6.0, 6.1) cannot read
    Java 25 class files (*Unsupported class file major version 69*); 1.x supported 3.2 to 3.5. The
    `boot-3.2` and `boot-3.3` profiles and CI legs are gone; 3.4 and 3.5 pass 41 tests each on 25.
  - *Anyone running the jar or the distribution on Java 21*: `bin/pravaha-server` and
    `bin/pravaha-engine` stop at once with a message naming Java 25, rather than failing on the
    first class they load. Point `JAVA_HOME` at a JDK or JRE 25.
  - *Deployments still setting `pravaha.security.administer: legacy-read`* (server or embedded):
    the node refuses to start with `PRV-7004 … legacy-read was removed in 2.0; grant MODIFY/MANAGE or use the admin role`.
    Since 1.0.0 that setting let anyone who may read a view unfiltered drop, pause, resume or
    replace it; it was deprecated then and is removed now. Grant those operators `MODIFY` or
    `MANAGE` on the views (or the `admin` role), then remove the setting. `ownership`, the 1.x
    default, is still accepted. A catalogue `authority: import` of `authenticated` no longer has a
    rule under which it imports `MODIFY`.
  - *Image users*: no change in what runs — the image was already on 25 — but the `-jre21` image is
    no longer built, and `deploy/docker/build.sh --java` is refused.
- **Building from source** needs JDK 25: the enforcer requires it (`[25,)`), and the build scripts
  (`tools/worktree-build.sh`, `tools/verify-clean.sh`, `tools/build-sdk.sh`,
  `deploy/release/release.sh`, ...) source `tools/jdk25.sh`, which defaults `JAVA_HOME` to an
  installed JDK 25 and refuses an older one by name.
- **The launchers** always pass `--sun-misc-unsafe-memory-access=allow
  --enable-native-access=ALL-UNNAMED` (JEPs 498 and 472); the 1.x version gate is gone.
- **Docker**: one engine image on `eclipse-temurin:25-jre`, no `JAVA_VERSION` build argument; the
  root `Dockerfile` builds in `maven:3.9-eclipse-temurin-25`; the test runner is JDK 25 only;
  `release.sh` builds and smoke-tests one engine image.
- **Proved on 25**: the whole reactor, 4,832 tests, 0 failures, 12 skipped
  (`tools/worktree-build.sh -o clean install`); every module's main classes are class-file version
  69; the Python SDK suite (426) against a node on 25; `deploy/docker/smoke.sh` on the image; a
  node started by `bin/pravaha-server` on 25 serves a query registered and read with the CLI, and
  on 21 both launchers refuse.
- **CI**: every workflow on JDK 25; the built-with × run-on {21, 25} matrix is gone, and
  `deploy/ci/check-workflows.py` refuses a workflow that sets up any other Java.

Since 1.0.0, also:

- **Fixed (JDKSUBJECT-1):** Parquet feeds, Delta and Iceberg failed on JDK 23 and later with
  "getSubject is not supported". Hadoop client 3.4.0 → 3.4.3, one version for api and runtime;
  commons-logging 1.2 → 1.3.0.
- **The engine image moved to Java 25** (`eclipse-temurin:25-jre`, 822 MB) before the baseline did;
  it passes `deploy/docker/smoke.sh`, and the compose stack's seed, cdc and observability profiles
  run on it.
- **Fixed:** the engine image's `HEALTHCHECK` (and the compose stack's, and `helm test`'s probe pod)
  called `wget`, which the 25 JRE base does not carry: Docker reported a serving node unhealthy and
  compose held the console and seed back. The probe is now `bin/pravaha-health`, which needs only
  bash; `helm test`'s Flight check, which called an `nc` no JRE base carries, uses it too.
  `smoke.sh` now runs the image's own HEALTHCHECK inside the container.
- **Docs** are in six folders under `docs/` (guides, operations, development, design, publications,
  project); `MarkdownLinksTest` checks every relative link in the repository.

Register: **492 findings — 472 fixed, 1 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

## 1.0.0 — 2026-09-30

**The first release with a compatibility promise.** One node, feature-complete: continuous SQL over
streams and change feeds, answers served by key over Arrow Flight SQL, HTTP and the PostgreSQL
protocol, sinks with exactly-once delivery, queries on queries, alerts, a governed catalogue with
grants, row filters and masks, per-tenant names, a console, standalone SDKs for Java and Python,
and container images that keep everything under `/opt/pravaha`. What 1.x keeps stable, what is
experimental and which clients work with which nodes: [../operations/COMPATIBILITY.md](../operations/COMPATIBILITY.md).
Cluster mode (wave 11) is not in 1.0.

**Read before upgrading from 0.2.x.**

- **One-way.** Once a 0.2.x node has restarted on 1.0 with a view outside the default tenant, it
  cannot go back: ADR-060 journals per-tenant names in a record older builds refuse. Back up
  `data/` first.
- **Administration follows ownership.** Reading a view no longer lets you drop or change it; its
  owner, a grantee with MANAGE or an admin may. `pravaha.security.administer=legacy-read` restores
  the old rule; it is deprecated, kept through 1.x and removed in 2.0.
- **A catalogue that imported `authenticated` before 1.0** still grants MODIFY on the catalogue to
  every signed-in caller; the node warns at start until `REVOKE MODIFY ON CATALOG FROM ROLE
  authenticated`.
- **Names outside the default tenant** appear as `tenant.default.name` in metrics, audit and
  listings, and pgwire relation OIDs change once.
- **The assistant is experimental** in 1.0 ([../operations/COMPATIBILITY.md](../operations/COMPATIBILITY.md)).

Everything since 0.2.0:


- **The client SDKs build, ship and run on their own (SDKSTANDALONE-1).** The SDKs are for clients,
  so they are built and released apart from the server, and neither side leans on the other.
  `tools/build-sdk.sh` builds only `pravaha-api`, `pravaha-sdk-java`, `pravaha-sdk-java-flight` and
  the Python wheel and sdist into `target/sdk-dist/`, never the server: `-Dsdk.standalone` drops the
  Flight SDK's test-scope server dependencies, which Maven's `-am` otherwise followed into twelve
  engine modules. `pravaha-sdk-java-flight` gains a `-all` classifier, the client and every runtime
  dependency in one 19 MB jar for a client with no build tool; nothing in it is relocated (the API
  hands out Arrow types), so it must not share a classpath with another gRPC, Netty or Arrow, and
  the thin jar through Maven or Gradle stays the default. `SdkIndependenceTest` fails if an SDK
  module reaches a server module at compile or runtime scope, a server module depends on an SDK,
  the server's executable jar carries SDK classes, or the Python package needs more than the
  standard library to import or imports the console. `tools/sdk-standalone-check.sh` runs four
  clients outside the repository against a throwaway node (a Maven project depending on
  `pravaha-sdk-java-flight` alone, the `-all` jar with plain `java`, the wheel with and without
  `[flight]`). The SDK READMEs state the version policy: SDK and server from the same major.minor;
  a request an older server does not know is refused, never downgraded.
- **A Maven or Gradle client of `pravaha-sdk-java-flight` gets one Netty (SDKNETTYMIX-1).** The
  parent's `netty-bom` pin applied only inside this build; an application depending on the SDK
  resolved Netty by nearest-wins, 4.2.9 `netty-common` and `netty-handler` from Arrow beside 4.1.x
  buffer, transport and codecs, and failed on its first call with an `AbstractMethodError` in
  `netty-buffer` (on Java 25, an `UnsupportedOperationException` from Arrow's allocator). The SDK's
  own tests run in the reactor and never saw it. The SDK now names each Netty artifact it needs, so
  the client resolves 4.1.135, the version the SDK is tested with. Found by the standalone check.
- **The PostgreSQL gateway accepts transactions (PGWIRE-TX-1).** `BEGIN` was refused as
  `PRV-2001 … near the keyword 'BEGIN'`, so psycopg in its default mode, pgjdbc with
  `setAutoCommit(false)`, Npgsql's `BeginTransaction` and most ORMs failed on their first read.
  `BEGIN`/`START TRANSACTION` (any isolation level, `READ ONLY`/`READ WRITE`, `DEFERRABLE`),
  `COMMIT`/`END`, `ROLLBACK`/`ABORT`, `AND CHAIN`, `SAVEPOINT`, `RELEASE`, `ROLLBACK TO` and `SET
  TRANSACTION`/`SET SESSION CHARACTERISTICS` are now accepted as no-ops over the read-only gateway,
  with PostgreSQL's command tags and the `I`/`T`/`E` transaction status on every `ReadyForQuery`, on
  the simple and the extended protocol. After an error inside a block, everything but `ROLLBACK`,
  `COMMIT` (which reports `ROLLBACK`) and `ROLLBACK TO SAVEPOINT` is refused with `25P02` until the
  block ends, as PostgreSQL does. Reads in a block are `READ COMMITTED` — each sees the views as they
  are when it runs; `REPEATABLE READ` and `SERIALIZABLE` are accepted with a `NOTICE` saying so.
  `SHOW transaction_isolation`, `transaction_read_only`, `standard_conforming_strings` and the other
  parameters announced at connect are answered. Writes stay refused, and a refused write fails the
  block. New codes `PRV-6212`–`PRV-6215` (`25P02`, `25P01`, `3B001`, `25001`). The `autocommit=True`
  workaround in the docs is gone. Tested with raw protocol bytes, pgjdbc, Npgsql 4.0.17 and psycopg 3.
- **An upsert sink is handed a replaced key's new row alone, not a delete and an insert
  (SINKKEYROWS-2).** Since SINKKEYROWS-1 a keyed sink in upsert mode follows the view's answer, and
  a commit that replaced a key's row reached it as the old row withdrawn and then the new one: a
  tombstone and a value on a `kafka-sink` topic, where every consumer saw the key deleted and
  compaction keeps the tombstone for its retention, and a `DELETE` before the upsert on a
  `jdbc-sink` table. The withdrawal of a key that re-enters in the same commit is now dropped; the
  insert is the upsert. A key that leaves and does not return is still deleted, and a sink in
  changelog mode still receives the changelog verbatim. Found by running `KafkaSinkRegistrationTest`
  against a real broker (Testcontainers), which had not run since SINKKEYROWS-1;
  `UpsertSinkKeyRowsTest` pins it without Docker.
- **A view is administered by its owner, not by everyone who may read it (LIFE-040, SX-6).** Drop,
  pause, resume, replace, debug and dead-letter replay were allowed to anyone whose read of the view
  carried no row filter, so one reader could destroy the state every other reader depends on. They
  are now allowed to the principal who registered the view (or replaced it last), to a principal the
  policy grants it to — `MODIFY` or `MANAGE` on the view with the catalogue on, or a policy that
  implements `mayAdminister` itself — and to the `admin` role; anyone else is refused `PRV-7002`.
  The owner is the registrant the registry journal has always recorded, so views registered before
  this change come back owned by whoever registered them. It is shown as `owner` in
  `GET /api/v1/queries[/{name}]`, as the last field of the Flight `pravaha.list` row, as `owner` on
  both SDKs' registered-query records, in `pravaha describe` and `pravaha queries --verbose`, and on
  the console's query page. **`pravaha.security.administer: legacy-read`** restores the old rule for
  one release; the default is `ownership`, and anything else is refused at start with `PRV-7004`. A
  catalogue that imports `authenticated` now imports it without `MODIFY`; one that imported it
  earlier keeps the grant, and the node warns at start until `REVOKE MODIFY ON CATALOG FROM ROLE
  authenticated`.
- **View names are unique per tenant, and a name is resolved in the caller's tenant (ADR-060,
  TEN-1).** Two tenants may each register `orders`; each reads, subscribes to, lists, describes,
  drops and builds on its own, and a name only another tenant holds answers every surface — Flight
  SQL and its actions, pgwire and its catalogue, REST, `SHOW`/`DROP … CONTINUOUS QUERY`, subscriptions,
  dead letters, debug sessions, replacements — exactly as a name nobody holds. A registration choosing
  another tenant's name used to be refused with `PRV-8001`, which told the caller it existed; it now
  succeeds. The `PRV-8022` refusal of a cross-tenant replacement does not name the holding tenant.
  **Upgrading:**
  - A node that has only ever had one tenant (`public`) changes nothing: its names, checkpoint
    directories, journal and dead-letter files are as they were.
  - Inside the engine a view of any other tenant is now `tenant.default.name` — its catalogue name.
    Metric `query` labels, audit targets, `GET /api/v1/tenants`, lane rebalancing and the recovery log
    show that name for such views; a `SecurityPolicy` of your own is asked about them by it.
  - Reading another tenant's view by its bare name — possible under `permissive` and `authenticated`
    — stops working: an admin addresses it as `tenant.default.name` (quoted in a SQL `FROM`), and
    anyone else naming another tenant is refused `PRV-7002` whether it exists or not. An admin's
    listings (`pravaha queries`, `pravaha.list`, `GET /api/v1/queries`, `GetTables`) show other
    tenants' views by that name.
  - At the first start, each view another tenant registered before this release is re-keyed: one `N`
    record per view is appended to the registry journal, its checkpoints stay in the directory its
    bare name implied, its dead-letter files are renamed to its new name, and the catalogue record is
    re-keyed with its grants. **A build that predates this refuses a journal holding an `N` record**,
    so this release cannot be rolled back past once another tenant's view has been recovered.
  - A default-tenant view registered under a bare name another tenant's recovered view still holds
    checkpoints in `<name>-1`, journalled as a `C` record; `C` records now carry bound values, which
    an older build ignores.
  - Alert names are unique per tenant as well, resolved in the caller's tenant on every alert verb;
    an admin reaches another tenant's alert as `tenant.default.name`. `PRV-8041` now means your own
    tenant holds the name. Alert journals load unchanged. The `pravaha.alert.*` meters label an alert
    of a non-default tenant `tenant.default.name`, as the query meters do.
  - pgwire's `pg_class` oids are a hash of the view's engine name rather than the next value of a
    node-wide counter, so they no longer say how many relations other callers described; an oid a
    client cached from before this release is not the view's oid now.
- **A window whose state has spilled fires at memory speed again (SPILL-4).** Firing a window and
  discarding dead slices walked every accumulator in the index's hash order, a random read each;
  they now walk the store slab by slab (`RowStore.forEachLive`). Under a 384 MiB cap, 1.18 M
  accumulators: firing 295,384 groups took 0.6 s instead of 87.3 s, 2,517 major faults instead of
  452,758, 154 MB read instead of 25.3 GB.
- **A periodic task that overruns its period says so, and cannot stop its schedule (OBS-1).** The
  shared clock skipped ticks while a checkpoint or watermark tick was still running without a word;
  it now logs once when a firing overruns and again, with the count, when it finishes. A firing that
  could not be handed to a thread left its flag set, so every later tick was skipped for good, and an
  exception out of the timer would have cancelled the schedule; both are caught.
- **Performance harnesses decline under the coverage agent (PERF-1).** One check, `CoverageAgent` in
  `pravaha-common`, is asked by every harness that times: `ProfileAGateIT`, `ProfileBGateIT`,
  `NexmarkCoverageIT`'s timing test, `RestartCompileIT` and `OperatorMetricsOverheadIT` skip naming
  the agent; the JMH benchmarks refuse in their trial setup; `NodeScaleTest`, `SourceScaleTest` and
  `ThousandQueryTest` keep asserting their counts and mark the times they print. Take figures with
  `-Djacoco.skip=true` (`benchmarks/README.md`). Re-taken without it: per-operator metrics cost
  **about 12 %** of a narrow query's throughput (was quoted as 8 %, measured under the agent); eight
  lanes scale to 30–31 % of linear (gate P2 still not reached).
- **A debug fixture comes out formatted, or says it is not (FIX-3).** Exported where the Palantir
  formatter is on the classpath, the fixture is formatted as `spotless:check` requires; a node has no
  formatter, so its fixture carries a comment above the `package` line asking for
  `./mvnw -pl pravaha-it spotless:apply`, which that step replaces with the licence header, and
  `pravaha debug fixture --out` prints the same note.
- **Exporting a session that stepped no rows is refused by name (FIX-2).** A session that stepped only
  event time answers `PRV-8015` ("has stepped no rows") on `debug fixture`: its fixture would replay
  nothing and assert an empty view. It was refused before as `PRV-3022`, windows from 1970.
- **`pravaha.codegen.enabled` is a configuration key (CODEGENPROP-1).** It was read only as a JVM
  system property, so `codegen: enabled: false` in `application.yaml` changed nothing. It is now
  bound like every `pravaha.*` key (YAML, `PRAVAHA_CODEGEN_ENABLED`, `--pravaha.codegen.enabled`),
  default `true`; a `-Dpravaha.codegen.enabled` on the JVM still works and wins over the YAML file,
  in Spring's usual order. A node built without Spring reads only the `-D`.
- **The OpenAPI lock records body fields (OPENAPILOCK-1).** `api/openapi.lock.json` keeps its
  per-operation `paths` entries, adds each operation's `requestBody` and `responseBodies` shapes, and a
  `schemas` section listing every body field as a flattened path (`a.b`, `a[]`, `a{}`) with its type
  and required flag. `OpenApiContractTest` names a removed, renamed or retyped field, or a newly
  required request field, as a break; any other difference (an added optional field) fails until the
  lock is regenerated with `-Dpravaha.openapi.update=true` and the diff reviewed.
- **The server's dependency upper bounds are checked (SERVERSKEW-1).** `pravaha-server` no longer
  skips `requireUpperBoundDeps`; only the four Netty artifacts Arrow asks for above Spring Boot's line
  are excluded, as in the Flight modules. jackson-dataformat-yaml 2.22.1, commons-lang3 3.20.0,
  HdrHistogram 2.2.2 and jspecify 1.0.1 are pinned to the highest version the tree asks for (were
  2.21.4, 3.17.0, 2.1.12 and 1.0.0).
- **`kafka-sink` keys in Avro, Protobuf or text (KSF-1).** `key.format: string | avro | protobuf`
  (default `json`) writes the key columns, in `key.columns` order, as one column's text, an Avro record
  (`key.schema.file`, `key.schema.id`) or a Protobuf message (`key.schema.message`,
  `key.schema.descriptor`, defaulting to `schema.descriptor`), so a registry-aware consumer expecting
  an Avro key can read it. A tombstone keeps the same key bytes.
- **Protobuf values behind the Confluent framing (KSF-2).** With `schema.id` and `schema.registry.url`
  a Protobuf value is the byte `0`, the id and the message's index path in the registered file, then
  the message; the registry's serialized descriptor says where the message is. A Protobuf `schema.id`
  without a registry is refused (PRV-5100); `schema.descriptor` may be left out when the registry has
  the schema.
- **Schema ids are checked at registration (KSF-3).** `kafka-sink` takes `schema.registry.url` (and
  its user, password or token and timeout, as the source does) and fetches `schema.id` and
  `key.schema.id` when the query registers: an Avro id must hold the schema in `schema.file` (or is
  the writer schema when there is no file), a Protobuf id must hold `schema.message` as
  `schema.descriptor` describes it, and the columns must map to it — else **PRV-5108** naming the id.
  An unreachable registry or an unknown id is **PRV-5109**. An Avro id without a registry is still
  written unchecked.
- **An Avro time field's precision is checked at registration (KSF-4).** `schema` now takes
  `TIMESTAMP(p)` and `TIME(p)`. A column goes to a `-millis` field only when declared `(3)` or
  coarser, to `-micros` at `(6)`; an undeclared `TIMESTAMP` (nanoseconds) is refused with PRV-5108
  naming the declaration. Each value is floored to the declared digits, so the old write-time
  refusal (PRV-5102 at the first finer value, which detached the sink) is gone. **Bindings that write
  `TIMESTAMP`/`TIME` columns to Avro time fields must declare the precision.**
- **`iceberg-sink` on the server's Caffeine 3, and Avro 1.11.4 (ICE-1, ICE-4).** The plugin declares
  Caffeine 3.2.4 (Iceberg 1.2.1 asked for 2.9.3; the server already resolved 3) and Avro 1.11.4 (was
  1.11.1, CVE-2024-47561), and no longer ships Commons Compress 1.21 (CVE-2024-25710, -26308): Avro
  reaches it only for a bzip2 codec Iceberg never writes. Iceberg stays 1.2.1: every newer release
  moves Parquet past the 1.12.3 the Delta and feedfile connectors share.
- **`iceberg-sink` upsert memory is bounded (ICE-2).** `upsert.max.keys` (default 1,000,000) caps the
  distinct keys one checkpoint interval holds in memory; the next is refused with the new
  **PRV-5143 ICEBERG_SINK_BUFFER_FULL**.
- **A repeated Iceberg commit is detected after snapshot expiry (ICE-3).** Each labelled commit also
  sets the table property `pravaha.committed-label.<transaction.id>` in the same Iceberg transaction;
  a restart reads it as well as the snapshot history. Before, another engine committing after the sink
  and expiring its snapshot let a repeated commit apply the checkpoint's rows twice.
- **`mysql-cdc`: an idle table's position follows the log (MYC-1).** Heartbeats and binlog rotations
  between transactions move the position, so a purge of files that held nothing for the table no
  longer refuses a restart with PRV-5155.
- **`mysql-cdc` GTID positions (MYC-2).** With `gtid_mode = ON` a new registration's offset carries the
  executed GTID set (`gtid=…;binlog=…`) and a restart resumes by GTID, so a checkpoint survives a
  failover to a replica. A replica that has not executed all of the checkpoint is refused with the new
  **PRV-5158 MYCDC_RESUME_POINT_AHEAD**; purged transactions after it are PRV-5155. File positions
  stay file positions.
- **`mysql-cdc` reads replication privileges through active roles (MYC-3).** The check expands the
  roles active at login (`SHOW GRANTS … USING`), and a granted role that is not active is named with
  `SET DEFAULT ROLE`. (On MySQL 8.0.46 a default role was already accepted; the refusal for an inactive
  one wrongly blamed role expansion.)
- **`mysql-cdc` refuses a column type change that keeps the column count (MYC-4).** Each binlog table
  map is compared with the columns the stream was typed from — type, decimal precision and scale,
  `NOT NULL`, and signedness under `binlog_row_metadata = FULL` — and a mismatch is PRV-5156 naming
  the column, before a value is read with the old conversion (a `SMALLINT` −5 was read as 4294967291
  after `ALTER … INT UNSIGNED`). A DDL statement's leading comments no longer hide it.
- **`mysql-cdc`: a refusal right after connecting is reported as itself.** A PRV-5156 arriving as the
  first event used to surface as PRV-5151 after `start.timeout`.

- **The PostgreSQL gateway answers `AVG` of an integer as a `numeric` (AVGINT-1).** Power BI and
  every PostgreSQL client expect `avg(integer)` to be `numeric`; the engine's `AVG` keeps its
  argument's integer type and truncates, so a report's averages over integer columns were
  truncated. The gateway now plans reads with `AVG` of an integer typed `DECIMAL(38, 16)` -- the
  exact sum over the count, rounded half away from zero at the sixteenth place, as PostgreSQL's
  numeric division does -- sent as `numeric`, and `NULL` over no rows. Decided for the gateway only:
  Flight SQL, the HTTP API, the SDKs and continuous queries keep the integer average, as documented,
  since changing it would change every existing view's column type. `PowerBiGatewayTest`.
- **A `DECIMAL` column compared with a decimal literal runs on the generated path (CG-1).** `ratio >
  0.5` compiled to a comparison of two expressions, which the code generator refuses, so the whole
  filter-and-project chain ran interpreted. A decimal column against a literal it can hold exactly at
  its scale is now a typed comparison of 128-bit unscaled values (`Predicate.CompareDecimal`), which
  the generator emits; a literal with more fractional digits than the column, and decimal arithmetic,
  keep the exact general path. `DecimalGeneratedPathTest` holds the generated and interpreted
  answers equal. The finding's other half -- a restart compiling every distinct chain serially -- is
  not changed: it is a start-up cost still to be measured before it is worth parallelising.
- **`IN` lists reach the source (INLIST-1).** The pushdown extractor sent a source column-against-
  literal comparisons joined by `AND` and nothing OR'd, so `WHERE id IN (7, 8, 9)` was filtered only
  in the engine. An `IN` of up to 64 literals on one column is now the request's one disjunction --
  an equality per value -- which the sources that push filters already honour: `jdbc` as `(id = ? OR
  id = ? ...)`, `cassandra` as a read per partition when the column is part of the partition key,
  `aerospike` as an OR expression. A second `IN` in the same `WHERE`, and `NOT IN`, stay with the
  engine; the engine keeps its own filter either way. `PushdownEquivalenceTest` holds the answers
  equal with the lists pushed.
- **Waiting for a dropped query's rows no longer waits out the timeout (LIFE-067).** A row a
  producer handed over as the query was dropped -- after its lane had drained and stopped -- was
  never applied, and `awaitApplied` (the embedded engine's and the tests' way to wait for a push to
  land) waited for the inbox to empty until its timeout ran out. A stopped or failed lane now
  answers at once, and so do the lane group and the query's execution. This is the likely cause of
  the one-off gate failure, where a feeder pushing into a dropped query outlived the test's
  five-second wait inside a ten-second one; the mechanism is reproduced deterministically at the
  lane (`LaneTest`), and `LifeDropTest` now repeats the race forty times and prints the feeder's
  stack if it ever hangs.
- **A Cassandra token-range reader stopped inside a wide partition reads the rest of it (CASS-1).**
  The reader resumed its pass -- after a restart, or after a page failed -- with `token(pk) > <last
  token>`, so a stop part way through a partition's clustering rows skipped the rest of that
  partition until the next full pass. It resumes with `>=`, re-reading that partition from its first
  row (at `+1`, as every pass re-reads every row; `deletes: detect` restarts its pass and was not
  affected). `TokenRangeScanReaderTest`.
- **The PostgreSQL gateway's password with engine accounts on, verified and said (PGWIREPASS-1).**
  With the engine's own accounts on (ADR-052) the gateway accepts an API key or a session token as
  the password -- verified through the same transport verifier as Flight -- and refuses the account's
  own password, a revoked key and a session that must change its password first, each with SQLSTATE
  `28P01`. Nothing needed fixing; `PgWireSignInTest` now signs in with a real PostgreSQL driver both
  ways, and the pgwire, clients and power-bi topics say which credential the password is.
- **`/validate` judges a whole `CREATE CONTINUOUS QUERY` statement (VALIDATEREG-1).** It planned only
  a `SELECT`, so a key or index naming a column the view would not have (`PRV-2071`, `PRV-2074`), a
  sink the caller may not see or whose shape, key or changelog does not fit, a taken name or an
  unknown `WITH` option passed it and was refused only on register. Given the statement, it runs
  registration's own reading and preparation without registering -- nothing is started, no sink is
  opened, no name is taken -- and answers every refusal as a diagnostic, with the view's columns as
  `outputFields`. The Python SDK's `validate`, `pravaha validate` and the assistant's drafting use it;
  the assistant falls back to its own checks against an older engine. A plain `SELECT` is validated
  as before. `ValidateRegistrationTest`, `test_assist_drafting`.
- **Which access path a view's reads took is visible (IDXVIS-1).** The view counted reads by the
  whole key, by a `RANGE` run, by an `INDEX (column)` probe and by scan, and nothing a user could
  reach read the counts. `GET /api/v1/queries/{name}` now carries `accessPaths` (`point`, `range`,
  `index`, `scan`, and each index's entries), the console's query page shows them under "Reads of
  its view", and `pravaha_query_view_reads_total{query,path}` counts them. Chosen over a field on
  every read response, which would have changed three wire formats for a diagnostic.
  `AccessPathsVisibleTest`, `PravahaMetricsTest`.
- **Dropping one name of a shared computation drops the index only it declared (IDXSHR-1).** The
  view kept an equality index a dropped name had declared until the next restart -- memory, never
  a wrong answer. Each name's `INDEX (column)` is now counted against the names still answered by
  the computation; a replacement carries the indexes its own name declared.
  `SecondaryIndexRegistryTest`.
- **A view's dependants include its alerts (ALERTDEPS-1).** `dependants` in `GET
  /api/v1/queries/{name}`, `QueryRegistry.dependantsOf` and the console's query page listed only the
  queries over a view, although a drop or replace of it was already refused naming `ALERT x`. The
  alerts now follow the queries, as `ALERT <name>`, each only where the caller may see that alert;
  the console links them to the alert's page.
- **An alert may not be called `channels` (ALERTPATH-1).** `/api/v1/alerts/channels` is the channel
  list, so such an alert could not be reached, paused, snoozed or acknowledged by its own path.
  `CREATE ALERT channels ...` is refused `PRV-8042`, naming the path. The channel list stays where it
  is, so no caller changes.
- **Answer-following subscriptions over the wire (SUBANSWERWIRE-1).** A subscription that follows
  the view's answer — per commit, the rows a reader stopped seeing at `-1` and started seeing at
  `+1`, so its weights sum to the view even for a keyed view that upserts — was reachable only
  embedded (`SubscriptionOptions.followingTheAnswer()`). The Flight ticket now carries it, as two
  new verbs (`subscribe.answer`, `subscribe.answer.snapshot`) an older server refuses rather than
  misreads: `client.subscribe(view, changes="answer")` in Python, `subscribeToAnswer` and
  `subscribeToAnswerFromSnapshot` in the Java SDK, `pravaha subscribe --answer`.
  `JavaSdkAnswerSubscriptionTest`, SDK and CLI tests.
- **A `DECIMAL` group key and `COUNT(DISTINCT decimal)` work without a window too (DECKEYGROUP-1).**
  A read of a view grouping by a decimal column or counting its distinct values, and a continuous
  query over a view grouping by one, were refused `PRV-3020` (the continuous query at registration,
  `PRV-2075`), where windowed aggregates had handled both since WINDECKEY-1. The grouped and
  unwindowed aggregates now carry the whole unscaled value as their key and distinct value, through
  checkpoints (a new distinct-value tag; a checkpoint without one is unchanged).
  `DecimalGroupKeyReadTest`, `DecimalGroupKeyChainTest`.
- **A window past its lateness is never fired again, and a fired window holds nothing it cannot
  need (EMIT-1).** What a fired window published is kept, per group, only while the window can
  still be corrected, and let go on every watermark advance once its lateness passes — it used to
  go only when an advance happened to release a slice, so a hopping window closed with no lateness
  could be fired again as a "correction" by a row on time for the next window it overlaps. With no
  allowed lateness, the default, nothing is kept per group at all, not even while the window
  fires. A late row within lateness now corrects a window that fired empty too. `LateDataTest`.
- **A correction is published at the next commit (EMIT-2).** A late row within allowed lateness is
  applied at once; its retraction and corrected row used to wait for the next watermark advance,
  which on a quiet stream moves only with later rows. They are now published with the query's
  next commit. `CONTINUOUS_QUERIES.md` §6 says so, and what lateness costs in heap.
- **An upsert sink holds the row the view shows (SINKKEYROWS-1).** A keyed view keeps every
  distinct row of a key and shows the newest; retracting that row shows the one behind it again. A
  keyed sink in upsert mode (`jdbc-sink`, `kafka-sink`, `delta-sink`, `iceberg-sink`,
  `aerospike-sink`) was handed the changelog, whose only change for that commit was the retraction,
  so it deleted the key's record while the view still showed a row. Such a sink is now handed how
  the answer changed at each commit — the row that left the key, then the row that entered it — as
  an answer-following subscription is (KEYEDWT-1), so its last word on every key is the view's. A
  sink in changelog mode still receives the changelog verbatim, a row retention ages out is still
  never deleted from a sink, and the exactly-once protocol is unchanged (the answer is delivered
  inside the same commit, before a checkpoint's cut). Reproduced against `jdbc-sink` (H2) and
  `kafka-sink` (Kafka's `MockProducer`): `UpsertSinkKeyRowsTest`, `JdbcSinkRegistrationTest`,
  `KafkaUpsertKeyRowsTest`.
- **A total past 2^63 stops by name instead of wrapping (SUMWRAP-1).** Every `SUM`, `COUNT` and
  `AVG` accumulator — unwindowed, grouped, windowed (per slice and when a window's slices are
  combined), a pushed-down partial, and the read path — adds with checked arithmetic, and a
  retraction is checked the same way. A `BIGINT` total, or a `DECIMAL` total's unscaled value, that
  leaves the 64-bit range is refused with the new **`PRV-3025` RUNTIME_AGGREGATE_OVERFLOW**, naming
  the aggregate (`SUM(amount)`): a continuous query moves to `FAILED` and its view refuses reads, as
  for every runtime refusal, and a read is refused. It used to publish `-9223372036854775808`.
- **A windowed `GROUP BY` on a `DECIMAL` column, and `COUNT(DISTINCT)` of one, keep values apart
  (WINDECKEY-1).** The key was read as the high half of the 128-bit value — zero for every value of
  eighteen digits or fewer — so the `GROUP BY` failed with an uncoded "is DECIMAL, not INT64" and
  `COUNT(DISTINCT price)` counted `1.50` and `2.75` as one. Both now use the whole unscaled value,
  through the off-heap state and checkpoints (a new key tag; a checkpoint without decimal keys is
  unchanged). The unwindowed and grouped aggregates followed in DECKEYGROUP-1.
- **A restore that fails half-way leaves nothing behind (RESTOREPART-1).** A checkpoint is restored
  lane by lane, operator by operator, then the view; when a later part was refused, the earlier parts
  stayed restored while the query started from the beginning of its sources — counting every row
  before the checkpoint twice in the parts that came back. The restore is now all or nothing: each
  part's state is taken first and put back on a refusal, a refusal on a lane no longer kills the lane,
  a join's restore replaces its rows rather than adding to them, and the registry's own half (the
  sinks a checkpoint recorded) undoes the execution's if it fails. Starting from the sources is still
  what a refused checkpoint means — the documented "reprocessing, never a double count" — and it is
  no longer silent: the node logs a `WARN` naming the query, the checkpoint and the cause, and the
  query counts it as a checkpoint failure (`pravaha_query_checkpoint_failures_total`, the console's
  "Checkpoints are failing"). If the undo itself cannot complete, the registration is refused, and a
  recovery lists it among the refused, rather than running from state that is neither.
- **Summing a keyed view's subscription weights, corrected (KEYEDWT-1).** CONCEPTS §4 told a
  consumer keeping its own copy to sum a subscription's weights; for a view that keeps the latest row
  per key, an upsert arrives as `+1` with no `-1` for the row it replaced, so the copy counted the key
  twice. The advice now says so and gives the exact way: subscribe to a continuous query registered
  over the view, which is fed the view's answer changing (rows leaving at `-1`, entering at `+1`) and
  whose weights sum to the view. In the embedded engine, `SubscriptionOptions.followingTheAnswer()`
  hands a subscription the answer's changes directly (and a snapshot of the rows the view shows). The
  plain subscription is unchanged: its weights passing through verbatim is a documented contract.

- **About and the competitive landscape follow MAYA's.** `/about` takes MAYA's About section for
  section: the hero (mark, name, tagline, creed, version), what it is, the problem (asking again
  against a maintained answer, with problem-and-fix pairs), twelve cards of what makes it different
  (exact cuts, exact seams, lossless cutover, governed live answers, alerts that clear, queries on
  queries, refusal, any model with the engine as judge, …), how it works, what is built by area, this
  release beside the honest limits, the measured numbers with their sources, a competitive summary
  (the table, where Pravaha shines and where it is behind, and the way to the full comparison), the
  principles, the research paper, deck and Medium post, the technology and the author.
  `docs/publications/COMPETITIVE_LANDSCAPE.md` is rewritten in MAYA's form: a landscape naming the families and
  well-known examples of each, a table of 28 capabilities against six categories — a new
  **Governance catalogues** column among them — and a note per row with *the problem elsewhere* and a
  list of *how Pravaha does it*. New rows: the governed catalogue of live answers, alerts that fire and
  clear, BI tools over the PostgreSQL protocol with security applied, plain English with the engine as
  judge, lanes, native CDC, observability, queries on queries (Partial), Delta and Iceberg sinks
  (Partial), and the honest ones — governing many engines, MFA and SSO, a managed service. The paper
  and the deck are served at `/about/papers/` from a fixed list when the installation carries them.

- **The console follows MAYA's design language.** Tokens and themes (`static/css/tokens.css`,
  `theme.css`), the navigation bar, the menu, the theme menu, the banners, the flashes and the footer
  are MAYA's files with only names, routes and content changed. **Themes**: MAYA's four — Crimson,
  Dark, Blue, Green — picked from a theme menu with swatches (`pravaha.theme` in `localStorage`, as
  `data-theme` and `data-bs-theme`); the terminal theme is gone and a stored `terminal` falls back to
  the system's light or dark. **Navigation**: a fixed top bar with the mark and three brand lines
  (Pravaha, *Continuous SQL where your data already lives*, *Ask once. Answer always.*), and one menu
  defined once as data and drawn as mega-menu panels — Catalog, Workbench, Operate (alerts is an
  item here, not a new tab), Admin (administrators only) and Help; the search (the command palette),
  alerts, the theme menu and the user menu on the right; collapsed behind one button on a phone.
  Every screen is in the menu or listed with the reason it is not (`core/navigation.EXCLUDED`), and a
  test holds it. **Signed out**, every page has MAYA's public bar (Help, About, theme, Sign in) and
  never the app's menu; signing out lands on the landing page with that bar. The sign-in and reset
  pages stand on the gradient with no bar. **Banners** under the bar: the bootstrap `admin` still on
  its published password, the engine not answering, and where this is (engine, `app.environment`,
  version). **Footer**: *Ask once. Answer always.* Pravaha 0.2.1 · Help · About · © 2026 Ashutosh
  Sinha. All rights reserved. Density moved into the user menu (*Compact rows*, still `d`). The
  landing page takes MAYA's layout and keeps its three figures. Contrast departures from MAYA's
  values (light slate, the blue accent as text, the dark accent, `bad`) are noted in `tokens.css`
  and held by `test_contrast.py`; every page passes axe in all four themes and both densities, and
  the visual baselines were retaken for the four themes.

- **A row filter that restricts nothing is refused, not only one the planner folds to `TRUE`
  (TAUTOFILTER-1).** `region = region`, `1 = 1 OR region = 'x'`, `x IS NULL OR x IS NOT NULL`,
  `NOT (a <> a)`, `a >= a` and `lower(r) = lower(r)` used to be enforced as though they restricted
  something. The compiled predicate is now decided as a formula (constants folded, an expression
  compared with itself given its value, `NOT NULL` columns read as never null): a filter true for
  every row, or one dropping only rows with a NULL in a compared column without saying `IS NOT NULL`,
  is refused with `PRV-7003` where it is applied, and with `PRV-7038` when a session-free catalogue
  policy is bound; a session-free policy false for every row is refused at binding too. A filter
  bound to a reader that keeps no row is enforced. Sound, not complete: a property test holds it to
  never refusing a filter that restricts by value. **Behaviour change**: a policy that was accepted
  and restricts nothing now refuses its readers, naming the filter; exempt them with `EXCEPT ROLE`
  or write the comparison that was meant.
- **Users in the identity store carry attributes, presented as claims (STORECLAIMS-1).**
  `PUT /api/v1/users/{u}/attributes`, `pravaha user attrs <u> key=value ... [--unset key]` and an
  Attributes column in Admin · Users set them; every session and API key of the user's (a key exactly
  its holder's) carries them as claims, so a policy reading `session_attribute('region')` applies to
  store users instead of refusing them with `PRV-7039`. Journalled with the user, audited as
  `user.attributes_changed` (names only). A registration by a store user is now restored at restart
  as that user — recovery used to ask only the static token table, so it was refused.
- **Writes document the status they answer (CAT201-1).** The catalogue's `POST`s (namespaces,
  grants, policies, bindings) answer 201 and every endpoint answering 204 (sign-out, password change
  and reset, key revocation, session end, revoking a grant, dropping a policy) now says so in the
  OpenAPI document and `api/openapi.lock.json`, which said 200. The contract test derives each
  handler's status and holds the document to it for every operation, and calls the catalogue's
  writes for real. A generated client that treated the documented status as the only success now
  sees the one the engine sends.

- **The Pravaha Catalog, phase 2: row filters and column masks as catalogue objects (ADR-059 §4).**
  `CREATE ROW FILTER p AS <predicate> [EXCEPT ROLE r, ...]` and `CREATE MASK p ON COLUMN c AS
  <expression> [EXCEPT ROLE ...]` define a policy (kind `POLICY`: owner, description, tags, version,
  journalled); `ALTER STREAM|VIEW o SET|UNSET POLICY p` binds it to one object and `ALTER TAG
  'k[=v]' SET|UNSET POLICY p` to every object of the tenant carrying the tag, now and later; `DROP ROW
  FILTER|MASK` (refused while bound) and `SHOW POLICIES [ON o]`. Expressions read the object's
  columns and `session_attribute('claim')`, `current_user()`, `is_member('role')`; subqueries,
  non-deterministic and unlisted functions are refused. Filters AND together; a reader holding an
  `EXCEPT ROLE` is exempt. Enforced on Flight reads and point reads, pgwire (text and binary),
  subscriptions (snapshot and every commit), registrations (the input's filter and masks go into the
  plan, into the fingerprint, and on into every query built on the view) and alerts (as their owner).
  A masked column used as a filter operand, group, join or sort key, aggregate argument, view key or
  tap filter is refused (`PRV-7006`); a changed policy ends affected subscriptions (`PRV-7007`). `SHOW
  EFFECTIVE ACCESS` lists the filters and masks that apply and why. `/api/v1/catalog/policies`,
  `pravaha policy ls|show|create-filter|create-mask|bind|unbind|drop`, policies on the console's object
  page and an Admin → Policies editor. Static tokens gain `claims`. New codes `PRV-7006`, `PRV-7007`,
  `PRV-7038` to `PRV-7040`. `SecurityPolicy` gains `narrowing`, defaulting to none.
- **`SUM`, `MIN` and `MAX` of a `DECIMAL` column answer exactly (DECSUM-1).** Power BI's
  `select sum("_"."avg_ticket") from "public"."rr" "_"` failed "field 0 ('a0') is DECIMAL, not INT64"
  with no code: the aggregates read the 16-byte decimal slot as a `long` and wrote their answer as
  one. They now accumulate the unscaled value and write it back at the column's scale — on a read, in
  a continuous query and in a window — and `SUM` of a `DECIMAL(p, s)` is a `DECIMAL(38, s)`. A value
  whose unscaled form has more than 18 digits is refused `PRV-3020`, naming the column, rather than
  cut. **`AVG` of a decimal is refused `PRV-2021`**: a quotient at the column's scale would round. The
  float refusal (`PRV-2020`) is unchanged; it is a documented rule, not this defect.
- **A second, different query over a `postgres-cdc` or `mysql-cdc` binding is refused at
  registration, `PRV-8028` (new, `REGISTRY_SOURCE_HELD`, HTTP 409), naming the query holding it
  (CDCREPL-2).** A binding names one replication slot (or replica `server.id`), which has one consumer;
  the second registration used to be accepted and fail `PRV-5117` about fifteen seconds later. Bind the
  table again with a slot of its own for a second query. The engine does not create a slot per query:
  every slot retains WAL, and one the engine lost track of would fill the database's disk.
- **Queries owned by identity-store users survive a restart (RECOVERYOWNER-1).** Recovery resolved
  owners through the static token table only, so on a node with `pravaha.identity.enabled` every query
  a signed-in user had registered was refused `PRV-8007` at the next start. Owners are resolved through
  the identity store first, then the token table, each with today's tenant and roles. A disabled user's
  queries keep running, with a warning naming the owner at each start.
- **`/api/v1/queries/explain` answers the fingerprint a registration would get (EXPLAINFP-1).** Given
  `keys` (and optionally `retention`, `sink`, `name`) in the body, it answers `fingerprint` — computed
  by the registration's own preparation for the caller: plan, row filters, keys, retention, tenant — or
  `fingerprintRefusal` with the code registration would give. The Python SDK's `EngineApi.explain`
  takes them; the assistant's reuse offer (`match: "fingerprint"`) and `pravaha assist eval` compare
  the engine's fingerprints, falling back to plan text against an older engine.
- **The assistant in the console, phase 3 (ADR-058): Admin · AI models, "Describe it", and
  Explain.** An administrator configures several providers and models at once in **Admin · AI
  models** and **switches between them while the console runs**: providers (configured, and every
  type the console can build, with its capabilities), models (the key shown only by the name of the
  variable or file that holds it, whether it is set in the console's process, enabled or disabled,
  and a **Test** button giving the latency or the normalised error), each profile's chain in fallback
  order (move up, down, out, add, set by typing), the default profile, budgets, usage per model and
  per person, and the recent changes with who made each and what it was before and after. Every
  change is a CSRF-protected form through the SDK's `AssistAdmin`, validated completely, saved
  against the version the page was drawn from — a concurrent edit is refused as a conflict, naming
  who changed it and when — and applied to the console's one `ModelRouter` before the answer, so the
  **very next request uses it** with no restart; a change `pravaha assist use` stores is followed
  within a second. The screen is for a person the engine gives the `admin` role, asked of the engine
  on every request. On the workbench, **Describe it** drafts a query as the signed-in person — the
  statement, explanation, assumptions, the engine's verdict and plan, every repair turn, the model's
  questions answered in place and drafted again, the reuse offer, *Copy to editor* — and **Register**
  is disabled until the engine accepts the draft and then needs the person's confirmation; the draft
  is held by the console, so the browser sends its id, never SQL. **Explain this query** on a query's
  page and **Explain** beside every refusal code (the workbench's refusal and diagnostics, a query's
  sink, feed and failure codes, a refused draft). Budgets apply; with no model configured or none
  enabled, each surface shows one empty state (an administrator is linked to Admin · AI models).
  Every change and every request is appended to the console's assist log (`assist.log`): who, the
  model, tokens, a hash of what was asked, the verdict, whether it was registered. Every surface works
  with scripting off. New settings `assist.config`, `assist.usage`, `assist.log`,
  `assist.watch_seconds`. 23 new console tests with the SDK's `fake` provider; the help topics *The
  assistant* and *Admin · AI models*; [`../guides/ASSIST.md`](../guides/ASSIST.md) § In the console.
- **The assistant, phase 2 (ADR-058): `pravaha ask` drafts a continuous query from a description,
  the engine judges it, and only you register it.** The context is built from the engine under
  your own credentials — the streams and views you may read (a stream you may not read is never
  named), the sinks you may write to with their guarantees, the guide's rules from the dialect card,
  and two to four worked examples from the case studies chosen by similarity — ordered
  deterministically, held to a size budget that follows the per-request token budget, with what was
  left out named. No row is read. The model answers a fixed schema (`draft_query@v1`: name, SQL,
  key, options, explanation, assumptions, questions, confidence); questions come back without
  asking the engine; otherwise the engine validates and explains the draft, and a refusal gets up to
  three repair turns with the PRV code, the engine's sentence and the guide's section — none of
  which may change what the query reads ("a different question" is refused by the assistant and
  never sent). A draft still refused is shown in the engine's words, exit `1`. The result carries
  the `CREATE CONTINUOUS QUERY` statement, the engine's plan, the sink's guarantee, every turn, the
  model and the tokens, and offers reuse when a running query has the same plan, key and retention.
  `--register` registers an accepted draft only after you confirm (or `--yes`), through the
  ordinary client call. **Approximated, and said so:** the HTTP API gives no fingerprint for
  unregistered SQL, so "the same computation" compares the engine's plan text, key and retention.
  **`pravaha assist eval`** scores a model on a golden set generated from the case studies — 27
  reference cases (accepted, the same streams, the same plan and key) and three that must be refused
  or asked about (an unbounded `GROUP BY`, a stream–stream join with no time bound, a stream that
  does not exist) — with repair turns, tokens and latency per case, a table or `--json`; `--run`
  registers draft and reference under a prefix on a test node, compares fingerprints and answers,
  and drops both. Examples and golden set are generated by `sdk/python/tools/build_examples.py`,
  with a staleness test. 35 new tests against a stand-in engine on `127.0.0.1` and scripted models.
  [`../guides/ASSIST.md`](../guides/ASSIST.md); the console's *The assistant* help page.
- **Alerts (ADR-057): told when a row enters a view, and when it leaves.** `CREATE ALERT name ON
  view [WHERE column op literal AND ...] NOTIFY channel [, ...] [WITH (severity, fire_after,
  clear_after, dedupe, resend_every, include, snooze)]`, `ALTER`, `DROP`, `PAUSE`, `RESUME`, `SNOOZE
  … FOR`, `ACK ALERT` and `SHOW ALERTS`, wherever `CREATE CONTINUOUS QUERY` runs. An alert follows its
  view's answer (ADR-056): a key fires when its row enters — an insert, or an update across the
  threshold — and clears when it leaves — a delete, or the update back — so a clear is a retraction the
  alert was handed, not a guess. What is true and what is said are kept apart: `fire_after` and
  `clear_after` decide the first; `dedupe`, pause, snooze and reminders (until `ACK`) only hold the
  second back, and the receivers are told the difference when they allow — a flap folds into its end
  state and a clear is never lost. **Exactly-once state, at-least-once delivery**: every decision is
  journalled (`alerts.journal`) and forced before anything is sent, so a restart neither re-fires a
  firing key nor forgets a clear it owed, and every attempt carries the same `Idempotency-Key`.
  Notifier channels are plugins (`NotifierPlugin`) bound under `pravaha.notifiers.<name>`: `webhook`
  (JSON, HMAC-SHA256-signed over `<timestamp>.<body>`, retries with backoff and timeouts, `format:
  slack`; the secret only by `secret-env` / `secret-file`) and `log`; a channel the node cannot open
  refuses the start. An alert is a catalogue object (`ALERT`: `SELECT`, `MODIFY`, `MANAGE`), a `NOTIFY`
  needs `WRITE` on the channel (new kind `NOTIFIER`), and a view an alert follows cannot be dropped
  (`PRV-8024`). `/api/v1/alerts` (list, detail with per-key state and recent notifications, channels,
  pause/resume/snooze/ack), `pravaha alerts ls|show|channels|pause|resume|snooze|ack`, `pravaha alert
  create|drop`, and the console's Alerts screens (from the command palette, under Operations). The
  retail case study's low-stock alert runs end to end on a real node with a signed webhook and a
  restart (`RetailLowStockAlertEndToEndTest`). New codes `PRV-8040` to `PRV-8047`. Not built: `email`,
  `teams` and `pagerduty` channels (designed in ADR-057), freshness-objective alerts.
- **Power BI reads views through the PostgreSQL gateway, in Import and DirectQuery.** Power BI's
  PostgreSQL connector runs Npgsql 4.0.17, and until now it could not open a connection: Npgsql's
  type-loading query was refused (PRV-6205), and so, after it, would have been every query — Npgsql
  asks for binary results, which the gateway refused (PRV-6209). The gateway now answers Npgsql's
  three type-loading queries, `GetSchema("Tables"/"Columns")` and Power BI's navigator queries
  (`INFORMATION_SCHEMA` tables, columns, character sets, keys) from the view catalogue, filtered by
  what the principal may read; sends PostgreSQL's binary format for every type it sends (a binary
  `timestamptz` carries microseconds, so sub-microsecond digits are truncated); accepts `DISCARD ALL`;
  reads `public.<view>` as `<view>`; and takes a trailing top-level `LIMIT n` — Power BI's
  `LIMIT 1000001` on every DirectQuery statement — off before planning and applies it to the answer.
  `ORDER BY`, joins, float aggregates and date functions are still refused by the planner, each by
  name. `NpgsqlClientTest` drives the gateway with the real Npgsql 4.0.17 (skipped without a dotnet
  SDK); `PowerBiGatewayTest` replays the same texts everywhere. Power BI Desktop itself was not run.
  New help topic: [Power BI](../../console/content/topics/power-bi.md), including a Microsoft Fabric
  real-time path through `kafka-sink` that is **not verified against Azure**.
- **The assistant, phase 1 (ADR-058): `pravaha explain-sql` and `pravaha why`, through any model,
  with the engine as the judge.** `pravaha.assist` in the Python SDK, standard library only: a
  provider protocol with `anthropic` (Messages API), `openai` and `openai-compatible` (Chat
  Completions — vLLM, LM Studio, llama.cpp, gateways), `ollama` and `fake`, each normalising its
  failures into four errors, and third-party providers by entry point. A router with fallback chains
  (a refusal does not fall through), per-request and per-user daily token budgets, and runtime
  reconfiguration — validated immutable snapshots swapped atomically, a JSON file store other
  processes watch, and an `AssistAdmin` facade whose every change is an audit record — so an
  administrator can switch models while the console runs. `explain-sql` grounds the model in the
  engine's plan; `why` in the engine's diagnostics and a dialect card generated from
  [`../guides/CONTINUOUS_QUERIES.md`](../guides/CONTINUOUS_QUERIES.md), and a rewrite it proposes is validated by the
  engine before it is shown. `pravaha assist models|providers|check|use|enable|disable`. Keys by
  environment variable or secret file only; no rows are ever sent. 135 tests, none touching a
  network beyond `127.0.0.1` (`cd sdk/python && .venv/bin/python -m pytest -q tests/test_assist_*.py`).
  [`../guides/ASSIST.md`](../guides/ASSIST.md); the console's *The assistant* help page.
- **The Pravaha Catalog, phase 1: grants live in the engine (ADR-059).** `pravaha.catalog.enabled`
  (off by default) makes the engine keep every governed object — namespaces (`tenant.namespace.object`;
  an unqualified name is in `<tenant>.default`), views, streams, sinks — with an owner, description,
  tags and version, and the grants on it: `USE`, `SELECT`, `SUBSCRIBE`, `BUILD_ON`, `CREATE`, `WRITE`,
  `MODIFY`, `MANAGE`, `OWN`; allow-only, to roles and users, inherited down; owners and the `admin`
  role hold everything; tenants are walls. Journalled beside the registry journal and replayed at
  start. A built-in `CatalogPolicy` answers every existing check, so nothing about where checks happen
  changed: subscribing asks `SUBSCRIBE` (and a revocation ends an open Flight subscription), a
  registration asks `BUILD_ON` on each input it names and makes the registrant the owner.
  `GRANT`, `REVOKE`, `CREATE NAMESPACE`, `COMMENT ON`, `ALTER … SET|UNSET TAGS | OWNER TO | SET
  NAMESPACE`, `SHOW GRANTS ON|TO`, `SHOW EFFECTIVE ACCESS FOR USER … ON …` and `SHOW NAMESPACES` run
  wherever `CREATE CONTINUOUS QUERY` does; `/api/v1/catalog/{objects,namespaces,grants,access}`;
  `pravaha catalog ls|search|show|…`, `pravaha grant|revoke|grants`, `pravaha access why`; the
  console's Catalog → Objects and grants tab, object pages and Admin → Grants. `authority: import`
  imports the configured policy once and refuses to start (`PRV-7034`) if it later disagrees. New
  codes `PRV-7030` to `PRV-7037`. `SecurityPolicy` gains `maySubscribe`, `mayBuildOn`,
  `mayBuildThrough`, `mayReadThrough`, a named `mayRegisterQuery` and `registered`/`dropped`, each
  defaulting to what it meant before.
- **A view keeps every row of a key, and shows the one that most recently gained weight (VIEWW-1).**
  A key inserted as `A` and then as `B`, with `A` then retracted, went on showing `A` — the row just
  withdrawn. `ServedView` now keeps each distinct row of a key with its own weight (only for a key
  holding more than one; a one-row key costs nothing more), a retraction takes weight from the row it
  names, a checkpoint and a subscription's snapshot carry each row with its weight, and a restore
  rebuilds them. `ViewZSetPropertyTest` checks latest and consistent reads, a checkpoint mid-schedule
  and the snapshot against a Z-set model over 2,000 schedules; it fails 5 of 5 on the old code.
  [`../guides/CONCEPTS.md`](../guides/CONCEPTS.md) §4 states the rule.
- **Replacing a query over `postgres-cdc` or `mysql-cdc` is refused, naming the slot or replica id
  (CDCREPL-1).** Against a real PostgreSQL, the replacement's backfill waited 15 s for the running
  version's slot and failed with `PRV-5117` ("replication slot … is active for PID"); a slot's
  "beginning" is its confirmed position, so there was no history to replay either. Now `PRV-4018`
  before anything opens, and a debug fork `PRV-8012`. A plugin says so through a new SPI default,
  `StreamSourcePlugin.secondReaderRefusal()`. `PostgresCdcReplacementTest`, `MySqlCdcSecondReaderTest`.
- **ADR-054's exact seam is tested against a real Kafka broker (SEAMKAFKA-1).** Transactional
  producers with aborted transactions put markers and gaps beside every position; a query joining
  behind the shared reader while records arrive, one restored ahead of it across a 20,000-record gap,
  one restored behind and one from nothing each count every committed record once and none aborted,
  in topic order (`KafkaExactSharingBrokerTest`). Nothing needed fixing; a reader that handed over the
  record on its bound loses exactly that record for the query waiting there, and the test catches it.
- **A dead shared lane is no longer placed on (LANEFATE-1).** A failing pipeline takes down exactly
  the queries on its shared lane — tested now, with the other shared lane and a lane of its own
  answering throughout — but a registration after the failure could land on the dead lane, report
  `RUNNING`, and fail on its first row with the other query's error (`PRV-3010`). `SharedLanes`
  skips a failed lane until the node restarts. A stalled query backpressures its own lane only, and
  loses nothing (`SharedLaneFateTest`).
- **Queries on queries (ADR-056).** A continuous query whose `FROM` names another registered query now
  *follows* that query's answer — its snapshot, then every change to it — instead of scanning its view
  once. Layer answers: `cleaned` → `by_region` → `big_regions`. The downstream is fed the answer's
  changes (a row leaving, a row entering), not the upstream's changelog, so an upsert upstream is an
  update downstream rather than a second row. Filters, projections and unwindowed `COUNT`/`SUM`/`AVG` —
  with a `GROUP BY` too, now continuous and checkpointed — run over a view; windows, joins, top-N,
  `MIN`/`MAX` and `COUNT(DISTINCT)` are refused `PRV-2075`. Exactly once across the chain: the
  downstream carries what it has consumed of the upstream's answer in its checkpoint and is fed the
  difference on restore, whichever of the two checkpointed later. `DROP` of a query others read is
  `PRV-8024`, naming them (no cascade); a replacement that would read its own answer is `PRV-8025`;
  replacing a member of a chain, `RETAIN FOR` over a view and a restore without the consumed answer are
  `PRV-8026`; a chain deeper than eight is `PRV-8027`. Reads are authorised against the upstream and
  every stream behind it, and only the caller's own tenant's views can be read. `GET
  /api/v1/queries/{name}` reports `readsFrom` and `dependants`, and the console's query page links them.
- **A research paper, and an article version of it.** `docs/publications/research/continuous-queries-as-maintained-answers.pdf`
  (LaTeX source beside it) and `-article.md`: *Continuous Queries as Maintained Answers — Exact Cuts, Exact
  Seams and Lossless Cutover in a Single-Node Streaming SQL Engine*. It states the Z-set model and the
  operator laws, and proves, under assumptions it lists, that a snapshot subscription gets every later
  commit once, that a checkpoint is one cut (and a two-phase sink exactly once), that an ordered source's
  shared reader hands each query each record once (ADR-054), and that a replacement cuts over losslessly
  (ADR-046). Every claim names the test that carries it or says it is argued; the paper reports that
  `pravaha-algebra` is tested but imported by no running module, and quotes only measurements already in
  the repository, with their conditions. Licensed CC BY-NC-ND 4.0 (`docs/publications/research/LICENSE`); the software
  stays proprietary. Built with `latexmk -pdf`.
- **The Java CLI is now `pravaha-engine`, and keeps only what needs the engine in-process:
  `validate`, `explain`, `run` and `version`.** `bin/pravaha` is renamed `bin/pravaha-engine`
  (`PRAVAHA_CLI_JAR` becomes `PRAVAHA_ENGINE_JAR`; the container image installs
  `lib/pravaha-engine.jar`). Every command that talked to a running engine is removed from it —
  `query`, `register`, `queries`, `drop`, `pause`, `resume`, `replace`, `cutover`, `rollback`,
  `abandon`, `finish`, `throttle`, `pause-backfill`, `resume-backfill`, `replacements`,
  `subscribe`, `dlq`, `login`, `password`, `user`, `key`, `session`, `lanes` and `debug` — and
  lives in the Python CLI, `pravaha`, with the same names and flags. Typing one of them into
  `pravaha-engine` prints where it went and exits 2. The jar no longer bundles the Java SDK.
  `deploy/docker/smoke.sh` lists queries with `GET /api/v1/queries` and registers and reads over
  Flight with the Python CLI on the host.
- **`pravaha` is a Python CLI on the Python SDK.** Everything that talks to a running engine is
  now `sdk/python/pravaha/cli` — the `pravaha` console script of `pip install "pravaha[flight]"`,
  `python -m pravaha.cli`, or `bin/pravaha` from a checkout — with no protocol code of its own:
  Flight commands call `Client`, HTTP ones the new `pravaha.api.EngineApi` (stdlib only, no
  pyarrow), which `Client`'s HTTP methods now delegate to and which adds lanes, rebalance, health
  and the identity endpoints; `RestClient` gains `put`/`patch`/`delete`. Every Java CLI command
  and flag still works; new are `status`, `health`, `version` (CLI and node), `metrics`, `plugins`,
  `sinks`, `streams`, `views`, `describe` (with the lane), `plan`, `validate`/`explain` against the
  node, `audit`, `tenants`, `permissions`, `whoami`, `logout`, `dlq count` and
  `subscribe --reconnect`. `--json` on every command; `--url`/`--http`/`--token` or
  `PRAVAHA_URL`/`PRAVAHA_HTTP`/`PRAVAHA_TOKEN`, then `login --save`'s `0600` token file; exit `0`
  ok, `1` engine refusal, `2` usage, `3` unreachable. `drop`, `abandon`, `finish`,
  `lanes rebalance`, `key revoke` and `user disable` only say what they would do without `--yes`.
  The offline `validate --schema`, `explain --schema` and `run` are the Java `pravaha-engine`.
  [`docs/guides/CLI.md`](../guides/CLI.md); `test_cli.py`, `test_cli_flight.py`, `test_api.py`.
- **SDK subscriptions survive a server restart.** Python: `subscribe(..., reconnect=True,
  reconnect_timeout=300)`; Java: `subscribe(view, filters, Reconnect, onBatch)` and
  `subscribeFromSnapshot(view, filters, Reconnect, onBatch)` returning a `ReconnectingSubscription`.
  A stream ended by a restart, a broken connection or `PRV-6105` is reopened with backoff (250 ms to
  10 s) until the limit; a refusal that will not change is raised at once. With a snapshot
  subscription the first batch after reopening is a fresh snapshot, so a copy loses nothing
  (`batch.reconnected` in Python, `Reconnect.onReconnected` in Java). `JavaSdkReconnectTest`
  restarts a real server under a subscriber; `test_reconnect.py`.
- **Lane sharing is on by default, as `auto`.** `pravaha.lane.multiplex.enabled` was a boolean,
  `false` by default; it is now `auto` (the default), `true` or `false`, with
  `pravaha.lane.multiplex.auto-from` (64). Under `auto` a node's first 64 queries each own a lane
  -- a failing query takes down only itself -- and every registration after them is placed on a
  shared lane, saving about 1 MiB of inbox and arena per idle query. Running queries are never
  moved. `true` and `false` keep their meaning. The node's `lanes:` status line says which mode is in
  force. An embedded `QueryRegistry` still shares nothing unless asked
  (`multiplexingLanes(lanes, ceiling, shareFrom)`).
- **`WITH (lane = 'dedicated')`: one query on a lane of its own, whatever the node's mode.** A
  registration option (`'dedicated'` or `'shared'`, the default; anything else is PRV-8017),
  journalled with the registration as a new `L` record so a restart keeps it -- a build that predates
  it refuses the journal by name. On `CREATE OR REPLACE`, `lane` moves a running query between a
  shared lane and its own at a lossless cutover, with the SQL unchanged if that is all that changes; a
  replacement that does not say keeps the running version's lane. A dedicated registration that
  would join a computation already on a shared lane is refused with PRV-8017. `GET /api/v1/queries`
  and `/{name}` gain `lane` (`dedicated` | `shared` | `own`) and `sharedLane`; the new
  `GET /api/v1/lanes` summarises placement (mode, `autoFrom`, per-lane counts, own-lane, dedicated
  and hosted computations). Placements are not rebalanced when queries are dropped — until an
  administrator asks: **Admin → Lanes** in the console (every query's lane, the mode, each shared
  lane's fill, and a preview-then-run rebalance for the `admin` role), `pravaha lanes` and
  `pravaha lanes rebalance [--yes]`, and `GET|POST /api/v1/lanes/rebalance`. It moves shared queries
  onto lanes of their own while there is room under `auto-from`, oldest first and one at a time, each
  by a blue/green replacement with `lane = 'own'` (new: a lane of its own without pinning it).
- **`iceberg-sink`, an Apache Iceberg sink** (`plugins/pravaha-plugin-iceberg`), on iceberg-core and
  iceberg-parquet 1.2.1, not Spark. A table on the local filesystem; `mode: upsert` (the default)
  keeps it equal to the view by key through equality deletes (format version 2), and
  `mode: changelog` appends every change with `_op` and `_weight`. One snapshot per checkpoint,
  exactly once: files are staged unreferenced at `prepare`, and the snapshot summary carries the
  transaction id and label, so a commit repeated after a restore is skipped. New codes `PRV-5140`
  (binding), `PRV-5141` (table not the binding's) and `PRV-5142` (write). Not built: object stores,
  catalog services, partitioned tables. `IcebergSinkPluginTest`, 10 tests.
- **`mysql-cdc`, change data capture from MySQL** (REMAINING C3). One table per binding, read from the
  row-based binary log with the plugin registered as a replica, on ADR-041's model and without
  Debezium: an insert at +1, a delete as the whole old row at −1, an update as both, whole
  transactions, `EXACTLY_ONCE` from a binlog file and offset. `binlog_format` other than `ROW`,
  `binlog_row_image` other than `FULL`, and a user without `REPLICATION SLAVE` and `REPLICATION
  CLIENT` are refused by name (`PRV-5152`); a purged resume file is `PRV-5155`. Changes only:
  `snapshot.mode: initial` is refused. New codes `PRV-5150` to `PRV-5157`. Tested against a real
  MySQL 8 (`MySqlCdcIT`, `./mvnw -Pit -pl plugins/pravaha-plugin-mysql-cdc -am verify`).
- **Thirteen case studies, every one run by the build.** Three new ones: `retail-inventory-mysql`
  (`mysql-cdc`, low-stock alerts that clear themselves), `lakehouse-orders-iceberg` (`iceberg-sink`
  in upsert mode, a late row corrected in the table) and `payments-shared-kafka` (three queries on
  one shared Kafka reader, ADR-054; `INDEX (merchant)`, ADR-055). `CaseStudyRunTest` runs each
  study's continuous queries in an embedded engine over its `data/sample/` and checks every read
  against answers worked out by hand. It found four studies registering the wrong view key
  (biology, finance, trading, trade processing) and fixed them.
- **A windowed aggregate that is sent event time before its first row no longer walks from the
  epoch.** With a filter ahead of it — `cancel_rate`'s `WHERE event_type = 'CANCEL'` behind a
  `NEW` — the first watermark fired every empty window since 1970: `PRV-3022` for a ten-second
  slide, and hundreds of thousands of empty windows for an hourly one. It now has nothing to fire.
- **`PravahaEngine.retract(stream, rows...)`** pushes rows at weight −1, as a change-data-capture
  source delivers a delete or the old half of an update.
- **Observability, built out.** *Metrics* for the newest features, all with bounded labels (never a
  user, key, row or statement): alerts (`pravaha_alert_keys_firing`, `_transitions_total` by alert
  and kind, `_notifications_total` by channel and outcome, `_notification_retries_total`,
  `_delivery_seconds`, `_notifications_owed`, `_journal_write_failures_total`), the catalogue
  (`pravaha_catalog_access_decisions_total` by privilege and outcome, `_decision_cache_lookups_total`,
  `_changes_total` by kind, `_subscriptions_ended_total` by reason) and Flight
  (`pravaha_flight_calls_seconds` by operation). The console serves the assistant's at `/metrics`
  (`metrics.enabled`, off; `metrics.token`): requests, tokens, failures, fallbacks and a latency
  histogram per model and profile, and today's ledger tokens. *Dashboards*: four Grafana dashboards
  under `deploy/observability/grafana/`. *Rules*: `deploy/observability/prometheus/pravaha-rules.yaml`
  -- the help topic's ten plus delivery failing, notifications owed growing, the alert journal failing,
  catalogue denials spiking and the assistant failing on every model -- and a Helm `PrometheusRule`
  (`prometheusRule.enabled`, off). *Logs*: `pravaha.logging.format: json` (Spring Boot's structured
  logging) with `correlationId`, `query`, `traceId` and `spanId` from the logging context; every HTTP
  response answers `X-Correlation-Id`; the console has `logging.format: json`. *Traces*:
  `pravaha.tracing.enabled` (off) -- Micrometer Tracing over OpenTelemetry, OTLP/HTTP to
  `pravaha.tracing.endpoint` or the standard `OTEL_EXPORTER_OTLP_*` variables -- with spans per REST
  request, Flight call, registration, replacement, checkpoint and alert notification; a caller's
  `traceparent` is continued, and the Python SDK sends one (`pravaha.tracecontext`). New server
  dependencies, from Spring Boot's BOM: `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`
  over the JDK's HTTP client (`opentelemetry-exporter-sender-jdk`; OkHttp and Kotlin excluded). New
  help topic *Observability*.
- **The Flight modules test on the Netty that ships (PKG-4).** The parent imports `netty-bom`
  4.1.135.Final (the version Spring Boot 3.5.16's BOM gives `pravaha-server`) ahead of `arrow-bom`,
  so `pravaha-flight` and `pravaha-sdk-java-flight` resolve the same Netty as the node instead of a
  mix of 4.1.130 and 4.2.9. Those two modules exempt exactly `netty-buffer`, `netty-common`,
  `netty-handler` and `netty-transport` (which Arrow 19 asks for at 4.2.9) from `requireUpperBoundDeps`;
  every other artifact and rule is still enforced. What `pravaha-server` ships is unchanged.
  **An application that depends on `pravaha-sdk-java-flight` now receives Netty 4.1.135 from it,
  not 4.2.9.**
- **Chart colours kept clear of the accent (CON-10).** A data series less than 25 ΔE from its theme's
  accent is moved just past it: light `--pv-series-8` `#e34948` → `#f1353e`, dark `--pv-series-5`
  `#d55181` → `#d44487` and `--pv-series-8` `#e66767` → `#f25d61`, blue `--pv-series-1` `#2a78d6` →
  `#2176e4`. The contrast test now holds accent against every series, and series against each other,
  in every theme.


Register: **482 findings — 464 fixed, 0 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.2.0 — QA, 2026-09-27

**What this build is for.** People sign in to the console as themselves: the engine keeps users,
passwords, API keys and sessions, and the console holds no password or token of its own. **An upgrade
from 0.1.x needs its server configuration to gain an identity block** -- install.sh says so and how
when it keeps an older file. Also: one reader shared by every query on a Kafka topic, an index on a
column outside a view's key, compressed and Avro/Protobuf Kafka, and a glibc image in which Parquet's
Snappy codec loads.

- **`kafka-sink` writes Avro and Protobuf values** (`format: avro` with `schema.file`, and
  optionally `schema.id` for the Confluent prefix; `format: protobuf` with `schema.descriptor` and
  `schema.message`). Upsert mode only, the key stays JSON and a retraction stays a tombstone.
  Columns map by name, and a column the schema cannot hold exactly is refused at registration
  (`PRV-5108`); `mode: changelog` with either is refused (`PRV-5100`). No schema is registered, and
  no Avro or Confluent library is added: the encoder is written from the specification beside the
  source's reader, which reads every row back unchanged.
- **`jdbc-sink` can commit through PostgreSQL's own two-phase commit** (`commit.mode: prepared`). Each
  checkpoint's changes go straight into the table inside a transaction that `PREPARE TRANSACTION` holds
  and `COMMIT PREPARED` publishes: one write per change instead of two. It is opt-in, because the
  database must allow prepared transactions and the touched rows stay locked for a checkpoint
  interval. It is refused by name elsewhere.
- **The snapshot-and-change-feed splice is a documented boundary.** `SplicedReader` stays
  unwired: its newest-row-per-key rule would double-retract on a weighted changelog such as
  `postgres-cdc`'s, whose own `snapshot.mode: initial` is already exact. A replacement still splices
  at an offset (ADR-046), and `backfill.adaptive` is still refused (`PRV-4018`).
- **An equality index over a column outside a view's key**
  ([ADR-055](../design/adr/055-an-equality-index-over-a-column-outside-the-key.md)). `CREATE CONTINUOUS QUERY
  ... INDEX (region)`, or `WITH (index = 'region')`, keeps value-to-keys in the view's own commit, so
  `WHERE region = 'eu'` and `WHERE region IN ('eu', 'us')` probe instead of scanning, over Flight
  SQL, REST and pgwire alike. The index is journalled with the registration (a new `X` record, which
  an older build refuses by name), rebuilt over a restored checkpoint, and carried to a replacement by
  column name. New code `PRV-2074` refuses an index over `FLOAT`, `DECIMAL`, `BYTES` or the view's
  whole key.
- **The server image runs on glibc, and Parquet's Snappy codec loads in it** (PORT-1,
  [ADR-053](../design/adr/053-native-code-only-where-java-cannot.md)). Up to 0.1.3 the image was Alpine, where
  snappy-java cannot load, so the `feedfile` and `delta` plugins could not read a Snappy-compressed
  Parquet file inside the container. The build now refuses native libraries except Parquet's two
  codecs; TLS runs on the JDK's engine. The node says at startup if a codec cannot load.
- **One reader for many queries, even over an exactly-once source**
  ([ADR-054](../design/adr/054-an-ordered-source-is-shared-at-an-exact-seam.md)). A source declaring ordered
  positions and bounded reads is shared at an exact seam: a query joining, resuming or restoring
  behind the reader catches up to exactly where it stands, and one restored ahead waits for it. The
  Kafka source and the filesystem source (files read once through) implement it, so a thousand queries over one topic read it once, and partitions the topic gains are joined by every query sharing its reader.
- **Users, passwords, API keys and sessions kept by the engine**
  ([ADR-052](../design/adr/052-the-engine-is-the-identity-authority.md), stages 1 to 3), with a REST API and
  `pravaha login|user|key|session|password`. The console signs each person in against the engine and
  acts as them; it keeps no password or engine token of its own. QA installs generate `admin`'s first
  password; locally, run the engine with `--spring.profiles.active=dev,users`. New codes PRV-7010 to
  PRV-7021.

Register: **417 findings — 377 fixed, 26 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.3 — QA, 2026-09-27

**What this build is for.** 0.1.2 with one fix QA would otherwise meet in its first week: a
replacement over a source that had just dead-lettered a record failed. Nothing else changes -- the
ports, the configuration files and the images' layout are 0.1.2's, so a 0.1.2 host upgrades by
installing this bundle over it.

- **Replacing a query whose latest record was dead-lettered works** (REPL-2). The backfill counts a
  record its source rejected as read, and `PartitionReader#poll`'s `maxRecords` now bounds records
  consumed, rejected ones included, so a backfill stops on the running version's exact position
  whether or not the record there could be decoded. The filesystem and Kafka readers are brought into
  line; PostgreSQL CDC already was. 0.1.2 fails such a replacement with `PRV-4013`.

Register: **399 findings — 370 fixed, 15 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.2 — QA, 2026-09-27

**What this build is for.** The QA host's second build, and the one to test on: 0.1.1's console image
shipped without the documentation its help pages include (IMG-1), and 0.1.1's query sharing could
hand one query another's answer (FP-1). Both are fixed here. **The default ports change** — 18080,
19090 and 17070 — so a QA host moving from 0.1.1 installs this bundle fresh or moves its published
ports. `install.sh` keeps a host's two configuration files, and 0.1.1's name the old ports
explicitly: it detects that and prints the one `sed` that moves them.

- **Default ports moved** (the owner's decision): the engine's HTTP port is **18080** (was 8080),
  Flight SQL **19090** (was 9090), the console **17070** (was 8090). Both SDKs' default port, the CLI's
  default URL, the images, the Helm chart and the QA install follow. **A 0.1.1 deployment that
  relied on the defaults must move its clients and published ports**; one that set them explicitly
  keeps working. The documented help-link base now points at a console path that exists (HELPURL-1).
- **`docs/guides/PYTHON_API_GUIDE.md`**: an integrator's guide and reference for every call the Python SDK
  makes and every REST endpoint, each sample run against a 0.1.1 node. It ships in the QA bundle.
- **Python SDK:** a refusal over Flight carries the engine's code as `QueryError.engine_code`, as
  `ApiError` always has (PYSDK-1); an engine that is down is `ConnectError`, retryable, not a
  refusal (PYSDK-2); `pravaha.__version__` is the installed wheel's (PYSDK-3).
- **The QA host's demonstration stream** is the seven-column `txn` with an event-time column and a
  `large_payments` sink, so the guide's samples, windows included, run on a fresh install.
- **Replacement:** a replacement whose backfill stops is `FAILED`, with the source's code, and its
  candidate is released; it used to go on reporting `BACKFILLING` with no failure (REPL-1).
- **Console image:** it carries the documentation its help pages include; the 0.1.1 image did not,
  so its tutorials, guides and code browser were empty (IMG-1). The build now checks every include.
  The Python guide has a help card (`/help/python-api-guide`).
- **Query sharing (FP-1, fixed):** two queries differing only in a join's time bound, INNER against
  LEFT, which column a projected name came from, or an aggregate's function shared one computation,
  and the second read the first one's answer. The fingerprint now hashes each operator's full
  identity, and EXPLAIN shows a join's window, `LeftJoin`, renamed columns' sources and aggregate
  arguments. **0.1.1 has this defect**; nothing on disk changes on upgrade.
- **A tutorial joining two Aerospike sets and a CSV file** (`docs/guides/tutorials/aerospike-fulfilment.md`,
  and a console card), with a script that pushes live orders; the QA install gains `/opt/pravaha/feeds/`
  for files you drop for file sources, and the bundle carries the tutorials' scripts.
- **Console help:** `docs/publications/COMPETITIVE_LANDSCAPE.md` scores Pravaha against five categories of
  product, with a card per row and where it loses, and is its own page (`/about/competitive`). The
  About page gains problem-and-fix pairs, "What makes it different", this list ("In this release",
  read from this file) and a condensed landscape; the help gains an FAQ, and guides for these
  notes, the roadmap (`REMAINING.md`) and `DEPLOYMENT.md`.
- **Fixed from the tutorials' runs:** a parameterised query from the Java SDK or CLI works under a
  token (SDKJ-1); a second name on a shared computation is answered with that name (NAME-1); a debug
  fixture of a windowed query carries its event time and runs (FIX-1).
- **Found, open:** a query cannot be replaced while the record at its position was dead-lettered
  (REPL-2, POST-GA); since REPL-1 it fails as `FAILED` `PRV-4013` rather than silently.

Register: **399 findings — 369 fixed, 16 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.1 — QA, 2026-09-26

**What this build is for.** The same as 0.1.0 — quality assurance on one node — now handed to a QA
team as files: two container images, a compose file and two configuration files, installed on one
Linux machine with Docker. 68 commits since `v0.1.0`; the tag is the only thing published.

### How it is delivered

| | |
|---|---|
| **Two images** | `pravaha/pravaha-server` (615 MB, every connector inside) and, new, `pravaha/pravaha-console` (481 MB). Built by `deploy/docker/build.sh` and `deploy/docker/console/build.sh`; both run as uid 10001 |
| **One root** | Every path is under `/opt/pravaha`: `conf/` and `console/conf/` for the two configuration files, `data/` for state, `logs/` for the engine's log and the audit trail. `/var/lib/pravaha` and `/etc/pravaha` are gone — **a 0.1.0 deployment that mounted them must move its mounts** ([`../operations/DEPLOYMENT.md`](../operations/DEPLOYMENT.md), "One root") |
| **Two files** | The engine and the console are each configured by their own YAML file, edited in place and read on restart |
| **A bundle** | `deploy/qa/bundle.sh` writes `pravaha-qa-0.1.1.tar.gz`: both images as `docker save` archives, `install.sh`, the compose file, the jars, wheels and chart, and `SHA256SUMS`. [`deploy/qa/README.md`](../../deploy/qa/README.md) is the page for the QA team |

### What changed in behaviour

A QA reader who tried 0.1.0 will meet these:

- **Every connector is in the server jar.** Kafka, Delta, JDBC, PostgreSQL CDC, Aerospike and
  Cassandra bind with nothing to install. Two lookups, `jdbc-lookup` and `aerospike-lookup`, had never
  been declared to the plugin loader, so a lookup join on a real node was refused `PRV-5090`; they now
  load. 14 plugins, checked by `ShippedConnectorsTest` and by the smoke run inside the image.
- **A filter on a `TINYINT`, `SMALLINT` or `REAL` column gave wrong answers** in 0.1.0 (NARROW-1): the
  comparison read the column's neighbour too. Fixed, and the reason 0.1.0 should not be used for
  answers over narrow columns.
- **Filters and projections run generated code** by default (`pravaha.codegen.enabled`); a query's
  description says which path each chain is on.
- **Tenants.** A tenant is charged for its queries and its state against quotas
  (`pravaha.tenancy.*`, ADR-050), and **identical SQL from two tenants is now two computations** —
  a tenant shares a computation only with itself. Refusals `PRV-8020`–`PRV-8023`.
- **SQL that 0.1.0 refused and now runs:** a stream joined with itself; `ROW_NUMBER() ... rn <= N`
  as a maintained top-N; exact `DECIMAL` arithmetic; `DATE_FORMAT`, `REGEXP_EXTRACT`, `SPLIT_INDEX`; a
  comma join with its condition in `WHERE`. Nexmark: **12 of 23** queries run (5 at 0.1.0).
- **Delta** reads deletion vectors (a deleted row arrives as a retraction) and `delta-sink` writes
  partitioned tables. **Kafka** resolves Avro against a reader schema and fetches Protobuf
  descriptors from the schema registry.
- **Firing a large window streams its groups** instead of building the window on the heap (SPILL-3),
  and an Aerospike `lut-scan` reads a page at a time (SRC-7) — the two defects 0.1.0 told QA to watch.
- An empty paging parameter on the REST debug read is refused rather than read as the default, and a
  lone surrogate over Flight is refused `PRV-1053`, as over HTTP.
- The console has **blue** and **green** themes and a new landing page.

### The numbers

| Measurement | Result | Command |
|---|---|---|
| Java tests | **4,175 run, 0 failures, 189 skipped**, 37 reactor projects | `tools/verify-clean.sh` |
| With the Docker integration tests | **4,185 run, 0 failures**, 18 skipped | `sg docker -c "./mvnw -o verify"` |
| Console tests | **1,732 passed, 0 failed** (380 without a browser, 1,352 in Chrome: visual, accessibility, journeys, states, performance) | `cd console && python -m pytest` |
| The images | `smoke.sh` **PASSED** (register, read, follow, restart, `--read-only`); `qa-smoke.sh` **PASSED** (the console against a real node, all 14 plugins); the QA compose stack installed, signed in to, queried and restarted | `deploy/docker/smoke.sh`, `tools/qa-smoke.sh` |

The performance figures of 0.1.0 stand and have not been re-measured; PERF-1 below says why the
default command's figures should be distrusted until they are.

### Open defects

At this cut: **377 findings — 354 fixed, 9 open, 0 GA-BLOCKER, 0 GA-REQUIRED**, 7 of the open ones
triaged POST-GA and 2 recorded as notes rather than defects ([`qa/FINDINGS.md`](qa/FINDINGS.md)).
Two worth knowing before starting:

- **EMIT-1** — a fired window still holds heap per group for as long as its lateness lasts, and a
  late row can fire it again. Size lateness with the group count in mind.
- **PERF-1** — every performance figure taken with the default command ran under the coverage agent.
  Treat 0.1.0's throughput table as an ordering, not as measurements.

---

## 0.1.0 — QA, 2026-09-20

**What this build is for.** Quality assurance on a single node. It is the first cut offered to
anyone but its author, and the point of it is to be tried, not to be deployed: run a node, register
continuous queries, watch answers change, break it, and tell the author what broke.

**Nothing has been published from this tree.** No Maven repository, no container registry, no
Python index, no signing key. A QA reader builds it, or is handed the artefacts the release script
produced. The licence is proprietary ([`../../LICENSE`](../../LICENSE)) and every file in the tree says so.

### What it does

A SQL query registered once keeps answering. Rows arrive from a source, the answer is maintained
incrementally as a Z-set — a change carries a weight, `+1` for an insert and `-1` for a retraction —
and the current answer is a view that can be read, subscribed to, or written to a sink.

| | |
|---|---|
| **Ask it** | Flight SQL, a REST API (`/api/v1`), the PostgreSQL wire protocol, `pravaha` on the command line, a Java SDK, a Python SDK, and the console in a browser |
| **Sources** | Filesystem (bounded or followed), feedfile directories (CSV, Parquet), Delta Lake, JDBC polling, Aerospike scans, Cassandra `token()`-range scans, PostgreSQL change data capture, Kafka topics (JSON, Avro and Protobuf — with no Avro or Confluent library) |
| **Sinks** | `filesystem`, `aerospike-sink`, `jdbc-sink`, `kafka-sink`, `delta-sink`. Five, and each states its delivery guarantee at registration rather than in a document |
| **Survives a restart** | Checkpoints, a registry journal, and sinks that stage a checkpoint and commit it once the checkpoint is durable |
| **Explains itself** | Every refusal is a `PRV-nnnn` code with a sentence saying what to do; `EXPLAIN` shows the plan; a query's plan carries per-operator rows in, rows out and a measured bottleneck; a debug session forks a query from a checkpoint and steps it row by row |

### The numbers, and the commands that produce them

| Measurement | Result | Command |
|---|---|---|
| Java tests | **4,043 run, 0 failures, 183 skipped**, 37 reactor projects | `tools/verify-clean.sh` (offline, wipes the project from `~/.m2` first) |
| Console tests | **844 run, 842 passed** at the last full run; the two failures were a test-isolation defect and a load flake, both since fixed | `cd console && python -m pytest` |
| Python SDK tests | **132 collected, 131 passed, 1 skipped** without the `tls-keystore` extra | `cd sdk/python && python -m pytest` |
| Skips | 184, and every one of them names its reason: Docker, Cassandra, Aerospike or `psql` absent on the machine | in the surefire output |

The skips matter for QA: a machine without Docker does not run the Kafka broker, PostgreSQL CDC or
Aerospike integration tests, and they are skipped **by name** rather than passing quietly.

### Performance, measured here and nowhere else

There is no reference hardware, so the gates were measured on the development machine — an AMD
Ryzen AI 9 HX 370, 12 physical cores, frequency-scaled, with other work running — and every number
in [`gates/measured-2026-09-20/`](gates/measured-2026-09-20) carries the machine's load average
beside it.

| Gate | Target | Measured | Verdict |
|---|---|---|---|
| P2, Profile A throughput | ≥ 1.2 M rows/s per lane | ~30 M warm, ~11 M cold; the worst pass, at load 77, was 1.04 M | **reached** |
| P2, scaling 1 → 8 lanes | ≥ 90 % of linear | **28–42 %** | **not reached** |
| P3, Profile B throughput | ≥ 350 k rows/s per lane | 2.5–2.8 M, worst pass 1.1 M | **reached**, and measured for the first time |
| ADR-038's Nexmark comparison | head-to-head against Flink | **not run** — no Flink, no quiet machine, no reference generator. Of Nexmark's 23 queries, **5 run** on this engine today | **not reached** |

A QA reader should not quote the throughput figures as product numbers. They were taken on a laptop
part under load, several are too noisy to state as a figure, and the harness says so where they are.

### Known, and deliberately not in this build

- **Multi-node execution.** Designed ([ADR-045](../design/adr/045-cluster-mode-assigns-queries-not-rows.md))
  and on hold by the owner's decision. A node refuses `PARTITIONED` with `PRV-9002` rather than
  serving every partition while claiming to own some.
- **Continuous integration.** Four workflows exist; **none has ever run**. Nothing is built, tested,
  published or signed by a machine other than this one.
- **Tenancy and admission quotas.**
- **The manual WCAG 2.2 AA audit.** The automated half — axe on every page, both themes, both
  densities — is green; a person still has to do the rest.
- **The console's design-system surface**, by decision.
- Smaller refusals, each named and reasoned where it is raised: no secondary index over a non-key
  column, no `INSERT INTO <sink> SELECT`, no Iceberg or Hudi sink, no partitioned Delta tables,
  `COUNT(DISTINCT)` cannot spill.

### Open defects

The register is [`qa/FINDINGS.md`](qa/FINDINGS.md), and it is the honest list: every defect found,
what happened to it, and what is still true of the build.

At this cut: **362 findings — 332 fixed, 17 open, 0 GA-BLOCKER, 0 GA-REQUIRED**, 15 of the open ones
triaged POST-GA and 2 recorded as notes rather than defects. The header's counts are enforced by
`FindingsRegisterTest`, so this page and the register cannot drift apart silently.

Three worth a QA reader's attention before they start:

- **SPILL-3** — firing a very large window builds it on the heap and can exhaust it, whether or not
  the spill tier is on. Bounded by the operator's own sizing; `OPERATIONS.md` gives the arithmetic.
- **PF-12** — a benchmark harness reported 131 % of linear scaling on a loaded machine and *passed*.
  Fixed, and recorded because it is the first defect here that produced a pass rather than a
  failure. If a number looks too good on a busy machine, distrust it.
- **SRC-7** — an Aerospike `lut-scan` buffers a whole scan on the heap, and `maxRecords` bounds
  only what it hands on. Size the heap for the scan, or use `deletes: detect` with a narrower
  range.

### Running it

[`../guides/QUICKSTART.md`](../guides/QUICKSTART.md) is the five-minute path, and its `application.yaml` declares an
event-time column — which it did not until today, so a first-time reader's windowed query silently
never emitted. [`../operations/DEPLOYMENT.md`](../operations/DEPLOYMENT.md) covers the container image and the Helm chart, both
of which run one node by design. [`../guides/TROUBLESHOOTING.md`](../guides/TROUBLESHOOTING.md) carries the code index.
