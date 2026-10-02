# ADV-SURFACE — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Executed 2026-10-01 (US Eastern; container clocks read 2026-10-02 UTC) against `develop` at
**`e3ad67dc`** (v2.0.0 + the 2.0.1-SNAPSHOT bump). Cases: [`../cases/ADV-SURFACE.md`](../cases/ADV-SURFACE.md),
written and committed (`44711af2`) before any was run.

What was tested:

- **Source build** — a fresh `git clone` of this worktree into `~/.cache/pravaha-qi/pravaha`, built by
  GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER step 3 (`./mvnw -q install -DskipTests`, with
  `-o -Dmaven.repo.local=<scratch>` so no other checkout's `~/.m2` was touched). Artefacts are
  `2.0.1-SNAPSHOT`; nothing between the `Release 2.0.0` commit and `e3ad67dc` changes code.
- **Shipped images** — `pravaha/pravaha-server:2.0.0`, `pravaha/pravaha-console:2.0.0`, and
  `pravaha/pravaha-server:1.0.0` / `pravaha/pravaha-console:1.0.0` for the upgrade cases.
- **The "QI node"** — `bin/pravaha-server` from the clone, home mode, `dev,users`, catalogue on,
  pgwire on 127.0.0.1:26432, HTTP 28480, Flight 29490, `-Xmx1g`; console on 27470.
- Clients: psql 18.6, psycopg 3.3.6, pgjdbc 42.7.11 on Java 25, pyarrow 25.0.1, the Python CLI/SDK from
  the clone, the Java SDK jars from `tools/build-sdk.sh`. **No .NET on this machine: Npgsql not run.**

Evidence: command transcripts under [`adv-surface-evidence/`](adv-surface-evidence/) (tokens masked);
each case below quotes the lines that decide it. Scripts that produced them were scratch files under
`~/.cache/pravaha-qi/s/`; the reusable checks are kept as
[`tests/qa/adv_surface/test_adv_surface.py`](../../../../tests/qa/adv_surface/test_adv_surface.py).

---

## Summary

| Area | Cases | PASS | FAIL | BLOCKED | NOT RUN |
|---|---|---|---|---|---|
| PGW — PostgreSQL gateway | 32 | 25 | 5 | 1 | 1 |
| HTTP — `/api/v1`, actuator | 21 | 15 | 6 | 0 | 0 |
| FLT — Arrow Flight | 8 | 5 | 2 | 1 | 0 |
| CLI — `pravaha`, SDKs, `pravaha-engine` | 22 | 20 | 2 | 0 | 0 |
| CON — console | 21 | 18 | 3 | 0 | 0 |
| OPS — images, compose, tarball, Helm, upgrade | 26 | 22 | 4 | 0 | 0 |
| CONN — connectors under failure | 11 | 7 | 3 | 1 | 0 |
| DOC — guides as promises | 13 | 8 | 5 | 0 | 0 |
| **Total** | **154** | **120** | **30** | **3** | **1** |

A PASS with a **NOTE** is a case that behaved as documented where the behaviour still deserves a
second look; the notes are collected in the register below with disposition NOTE.

### Defects, by severity

| ID | Sev. | Case(s) | One line |
|---|---|---|---|
| PGPREAUTH-1 | HIGH | QI-012 | 60 unauthenticated pgwire sockets declaring a 16 MiB PasswordMessage kill the shipped engine (OOM, `ExitOnOutOfMemoryError`) |
| HTTPBODY-1 | HIGH | QI-045 | 30 concurrent anonymous 19 MB `POST /api/v1/auth/login` bodies kill the shipped engine the same way |
| PGREVOKE-1 | HIGH | QI-004 | An open pgwire connection keeps reading after its key is revoked, its session ended or its user disabled |
| CDCSLOT-1 | HIGH | QI-172, QI-173 | A PostgreSQL CDC slot dropped while running is not detected: query and feed RUNNING, health UP, every later change silently missing |
| PGINTPARAM-1 | MEDIUM | QI-031 | An `int4` binary parameter against a `BIGINT` column is refused `08P01` (pgjdbc `setInt`, psycopg `%b`) |
| DECLSTREAM-1 | MEDIUM | QI-059, QI-076 | A stream declared over `POST /api/v1/streams` / `pravaha streams declare` validates but cannot be registered over (`PRV-2002 not found`) |
| LOCKENUM-1 | MEDIUM | QI-054, QI-123 | Account lockout is an enumeration oracle: an existing user answers `423 PRV-7011`, an unknown one `401 PRV-7010` |
| BIGREAD-1 | MEDIUM | QI-027 | Reads over a ~1 M-row view fail: `SELECT *` is PRV-3001 (arena) not PRV-4024; `COUNT(*)` is a raw `Index -1 out of bounds for length 64` (`XX000`) |
| SEEDWINDOW-1 | MEDIUM | QI-151 | The compose `seed` profile's `spend_per_minute` stays empty (guide promises 10 rows): two of three Kafka partitions idle, watermark never advances |
| ENVRERUN-1 | MEDIUM | QI-155 | Re-running `tools/docker-env.sh` resets the ports and image tag the `.env` says are "yours to change" |
| PGCOPY-1 | LOW | QI-021 | `COPY` is `PRV-2001 42000`, the pgwire topic promises `PRV-6201 0A000` |
| TOMCATHTML-1 | LOW | QI-041, QI-046, QI-060 | Encoded `/`, NUL, oversized headers get Tomcat's HTML error page, not an ApiError |
| FLIGHTTICKET-1 | LOW | QI-071, QI-077 | Unparseable tickets and path-descriptor `do_put` are gRPC `INTERNAL` with no PRV code |
| CONSOLEHDR-1 | LOW | QI-124 | The console sends no `Content-Security-Policy`, `X-Frame-Options`/`frame-ancestors` or `nosniff`: frameable |
| LOGOUTREPLAY-1 | LOW | QI-117 | A copied console cookie after sign-out renders the signed-in chrome with PRV-7001 instead of sending the user to sign in |
| PLUGINLATE-1 | LOW | QI-143 | A source naming a plugin that does not exist starts the node UP; PRV-5090 only at registration, and its text says the jar "carries filesystem alone" while listing nine |
| HELMNAME-1 | LOW | QI-160 | `fullnameOverride` near 63 chars renders a 72-char `-headless` Service name; `image.repository=""` renders `":tag"` |
| CDCPRIVCODE-1 | LOW | QI-170 | A capture role without `REPLICATION` is PRV-5118 with advice about idle transactions, not PRV-5112 |
| TOPICGONE-1 | LOW | QI-177 | A deleted Kafka topic leaves the query RUNNING / health UP until the topic is recreated |
| SNAPDOC-1 | LOW | QI-088, QI-099 | `pravaha subscribe --limit N` counts commits, not "rows"; `--limit 0` and `-5` run forever; `--snapshot` help says "the view's rows" but prints the changelog Z-set |
| CONSOLEREADY-1 | LOW | QI-147, QI-198 | The console's `/health/ready` is 503 "not-ready" whenever the engine requires a credential — in the compose stack too, where the docs show 200 "ready" |
| STALEDOC-1 | LOW | QI-190, QI-193, QI-195, QI-197, QI-200, QI-201 | Guides out of date: jar names, "not built" table, a QUICKSTART command that refuses to start, CLI.md missing commands |

Not covered, and why: Npgsql (no .NET here; QI-031's binary-parameter defect is the one Npgsql would
meet first); pgwire **TLS** (no case exercised a TLS-configured gateway or Flight); GUIDE_WITH_DOCKER
**Route A** (`docker build .` needs network for the Maven stage) and **§8** `tools/docker-test.sh`
(the full containerised suites — skipped as heavy while another QA agent was running); the `stores`
profile (Aerospike, Cassandra); Kafka Avro/Protobuf drift (JSON only); the admin lockout as a
denial of service against the bootstrap `admin` itself (inferred from LOCKENUM-1, not executed, to keep
the node usable); QI-023 (cancel during a long read — reads complete in milliseconds, nothing to cancel).

---

## Register text (proposed)

### PGPREAUTH-1 (HIGH) — the PostgreSQL gateway allocates an unauthenticated client's declared message size

> **Status:** OPEN — `PgFrontend.readMessage` does `new byte[length - 4]` for any declared length up to 16 MiB before reading a byte of it, and `PgWireConnection.authenticate` reads the `PasswordMessage` through it, so a client that has sent only a startup packet makes the node hold 16 MiB per socket for the 10 s handshake deadline, with no connection limit (a cached thread pool). Against `pravaha/pravaha-server:2.0.0` in a 1 GiB container (image defaults `-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError`, `dev,users`, `pravaha.pgwire.enabled: true`) **60 such sockets ended the JVM: `Terminating due to java.lang.OutOfMemoryError: Java heap space`, container exit 3**. On a `-Xmx1g` node without `ExitOnOutOfMemoryError` 100 sockets filled the heap to 1 043 804 K and produced 42 `OutOfMemoryError`s in `pravaha-pgwire-session` threads; the engine survived by luck. TLS does not help (authentication follows the handshake). Repro: QI-012, `test_qi012_*` in `tests/qa/adv_surface` (gated `PRAVAHA_QI_DESTRUCTIVE=1`). Fix direction: cap pre-authentication messages at a few KiB (a password/token is small), read before allocating, and bound concurrent unauthenticated connections.
> **Disposition:** GA-REQUIRED — an unauthenticated network peer can stop the node; the gateway is documented as fit to expose with TLS.

### HTTPBODY-1 (HIGH) — anonymous request bodies are buffered whole before authentication

> **Status:** OPEN — `POST /api/v1/auth/login` (open by nature) parses any JSON body up to Jackson's 20 M-character string limit. 30 concurrent anonymous requests carrying a 19 MB `username` killed `pravaha/pravaha-server:2.0.0` in a 1 GiB container (`OutOfMemoryError`, exit 3); on the `-Xmx1g` QI node the same burst produced 38 `OutOfMemoryError`s, 17 answers `500`, and an OOM in the engine's own `pravaha-clock` thread. A single 50 MiB body is refused in 21 ms (Jackson's cap), so the gap is the size below it and the concurrency. Repro: QI-045, `test_qi045_*` (destructive-gated). Fix direction: a request-size limit far below 20 MB on every endpoint (and especially the unauthenticated ones), enforced before the body is read.
> **Disposition:** GA-REQUIRED — the HTTP port is the one every deployment exposes.

### PGREVOKE-1 (HIGH) — revoking a credential does not stop a pgwire connection it opened

> **Status:** OPEN — the gateway checks the password once, at sign-in, and never again; the connection has no idle timeout (`setSoTimeout(0)` after the handshake). After (a) `DELETE /api/v1/keys/{id}`, (b) `POST /api/v1/auth/logout` of the session token, or (c) `PATCH /api/v1/users/bob {"status":"disabled"}`, the already-open connection kept answering `SELECT … FROM by_user` with rows, while the same credential got `401` on HTTP and `28P01` on a new connection. SECURITY.md promises "revocation is immediate" for keys and that disabling a user ends their sessions; a BI tool's pooled connection (Power BI, Grafana) can stay open indefinitely. Repro: QI-004, `test_qi004_*` (`PRAVAHA_QI_REPRODUCE=1` makes it a strict xfail). Fix direction: re-verify the credential per statement (as Flight does per call), or at least on a short interval, and close the connection with `28P01`/`57P01` when it no longer verifies.
> **Disposition:** GA-REQUIRED — incident response (disable the user, revoke the key) does not take effect on this door.

### CDCSLOT-1 (HIGH) — a dropped PostgreSQL replication slot goes unnoticed while the node runs

> **Status:** OPEN — in the compose `cdc` profile, with `customers_live` registered and streaming, terminating the walsender and dropping the slot (`pg_terminate_backend` + `pg_drop_replication_slot('pravaha_customers')`, looped until the drop wins) left the plugin's connection idle with no walsender and no slot. For the following four minutes `pravaha describe customers_live` said `state RUNNING`, `feed RUNNING`, `/actuator/health` said `UP`, nothing was logged above INFO, and rows inserted after the drop (`6 pied piper`, `7 aviato`) never reached the view. The source-postgres-cdc help topic promises PRV-5117 "while running: the slot dropped or invalidated" and "detected and refused, never skipped over". (On the next restart the gap *was* detected — PRV-5115 — and the registration was refused and disappeared from `pravaha queries`, with health still UP; see RECOVERYHEALTH-1.) Repro: QI-172 (transcript in this log), `adv-surface-evidence/cdc1.txt`, `cdc2.txt`.
> **Disposition:** GA-REQUIRED — silent loss of every change after the event, which the connector's documentation says cannot happen.

### PGINTPARAM-1 (MEDIUM) — a 4-byte integer parameter against a BIGINT column is a protocol violation

> **Status:** OPEN — the gateway types a parameter by the column it is compared with and demands 8 bytes, ignoring the type OID the client declared in `Parse`. pgjdbc 42.7.11 `PreparedStatement.setInt(1, 600)` on `… WHERE amount > ?` (amount `BIGINT`) gets `PSQLException 08P01 PRV-6202 a binary parameter declared 4 bytes; this server expected 8`, and so does psycopg with `%b` and a small int. PostgreSQL coerces `int4` to `int8`; the pgwire topic says binary parameters "are decoded for the fixed-width types". Npgsql (Power BI) sends `int` parameters as binary `int4`. Repro: QI-031 (pgjdbc transcript in the log entry), `test_qi031_*`.
> **Disposition:** GA-REQUIRED — the commonest JDBC call on a numeric filter fails.

### DECLSTREAM-1 (MEDIUM) — a stream declared over HTTP cannot be registered over

> **Status:** OPEN — on a fresh `pravaha/pravaha-server:2.0.0` (`dev`, catalogue on or off): `pravaha streams declare s2 --schema "k:INT64,v:INT64"` → `declared s2`; `pravaha streams` lists it; `pravaha validate --sql "SELECT k FROM s2"` → `valid`; `pravaha register --name s2v --sql "SELECT k, v FROM s2" --keys 0` → `PRV-2002 Object 's2' not found. This server has 0 stream(s) declared`. The HTTP catalogue and the Flight planner disagree about what exists. Documented as a working path (streams help topic, `Client.declare_stream`, CLI.md). Repro: QI-059, `test_qi059_*`.
> **Disposition:** GA-REQUIRED — a documented way to create a stream produces one nothing can use.

### LOCKENUM-1 (MEDIUM) — account lockout reveals which user names exist

> **Status:** OPEN — sign-in failures are uniform (401 `PRV-7010`, equal timing within noise: 241/232, 305/244, 225/217 ms medians), but after five failures an existing account answers `423 PRV-7011 this account is locked until <instant>` while a name that does not exist keeps answering `401 PRV-7010` — through the engine and through the console's `/login`. Anyone can therefore confirm a user name with six requests, and lock any known account (the bootstrap `admin` included) for 30 minutes. SECURITY.md: messages "say only that the credential was not accepted". Repro: QI-054, QI-123, `test_qi054_*`.
> **Disposition:** GA-REQUIRED — the enumeration half; the lock-out-anyone half is the usual trade-off and may be a NOTE once the oracle is closed (answer locked and unknown alike, record the lock server-side).

### BIGREAD-1 (MEDIUM) — reads over a view near the row ceiling fail with internal errors

> **Status:** OPEN — with `allrows` holding 1 009 786 rows (stopped at its 1 M-key ceiling, PRV-4022, as designed): `SELECT * FROM allrows` over pgwire and Flight → `PRV-3001 the projection's arena is full; raise pravaha.lane.arena.slab-bytes …` (`42000`), where the pgwire topic promises `PRV-4024` / `54000` for more than 1 000 000 result rows; `SELECT COUNT(*) FROM allrows` (and any aggregate over it) → `ERROR: Index -1 out of bounds for length 64`, SQLSTATE `XX000`, `PRV-1041` from the CLI — an unhandled `IndexOutOfBoundsException` text, nothing logged. The same reads over 1 008-row views work. Repro: QI-027 (append 850 000 rows to a followed CSV under an unkeyed view, then read it).
> **Disposition:** GA-REQUIRED — a count over a large view is a dashboard's first query; the error is not a PRV code at all.

### SEEDWINDOW-1 (MEDIUM) — the compose seed's windowed view never fills

> **Status:** OPEN — `--profile seed up` on a fresh stack: `orders_live` answers as in GUIDE_WITH_DOCKER §7, but `spend_per_minute` printed `0 rows` in the seed log and still had 0 rows minutes later and after a container recreate (`pravaha_query_watermark_lag_seconds{query="spend_per_minute"} NaN`). All twelve seed orders land in partition 0 of the 3-partition topic; partitions 1 and 2 go idle (`watermark_partitions_idle 2`) yet the watermark does not advance until another record arrives — one extra order (event time 10:07) produced all twelve windows at once. The guide, the seed SQL's own comment ("10:00 to 10:04 close") and RUNNING_IN_DOCKER show 10 rows. Repro: QI-151, `adv-surface-evidence/stack1.txt`, `stack3.txt`.
> **Disposition:** GA-REQUIRED — the first thing the Docker walkthrough shows is an empty view; whether the fix is the watermark (idle exclusion should advance it) or the seed (spread the orders), the promise is wrong today.

### ENVRERUN-1 (MEDIUM) — `tools/docker-env.sh` resets the settings it says are yours

> **Status:** OPEN — the script's header says "SAFE TO RE-RUN … Nothing that exists is overwritten" and the `.env` it writes says "the ports and the image tag below are yours to change". After editing `PRAVAHA_TAG` and every `PRAVAHA_*_PORT`, a re-run put them all back to the defaults (`PRAVAHA_TAG=local`, `18080`, `19090`, `17070`, …) — except `PRAVAHA_PGWIRE_PORT`. Cause: `cat > "$env_file" <<EOF` truncates the file before the heredoc's `$(port …)` substitutions read it, and only `pgwire_port` is computed beforehand. The next `compose up` then binds the default ports (a clash, or another stack's ports). Repro: QI-155.
> **Disposition:** GA-REQUIRED — small fix, and the failure is silent until something else breaks.

### PGCOPY-1 (LOW) — `COPY` is refused with the wrong code

> **Status:** OPEN — `COPY by_user TO STDOUT` → `42000 PRV-2001 Non-query expression encountered in illegal context`; `DECLARE`/`FETCH` → `42000 PRV-2001 Incorrect syntax`. The pgwire topic's refusal table promises `PRV-6201` / `0A000` for both. Also: `SELECT STREAM …` is `PRV-4023` (no such view), not the documented `PRV-6211` for continuous-query statements. Repro: QI-021, `test_qi021_*`.
> **Disposition:** POST-GA

### TOMCATHTML-1 (LOW) — some errors are Tomcat HTML pages, not ApiErrors

> **Status:** OPEN — `GET /api/v1/queries/..%2f..%2fetc%2fpasswd`, `/api/v1/streams/a%00b`, `/api/v1/queries/..%5c..%5cx`, a 10 000-character `Authorization` header, a 64 KiB header, and 500 headers are all answered `400` with Tomcat's `<!doctype html>… HTTP Status 400 – Bad Request` page. application.yaml: "every non-2xx response is an ApiError". Nothing leaks (no version, no stack). Repro: QI-046, `test_qi046_*`.
> **Disposition:** POST-GA

### FLIGHTTICKET-1 (LOW) — malformed Flight requests are INTERNAL without a code

> **Status:** OPEN — `do_get(Ticket(b"\x00\xff garbage"))`, `Ticket(b"NOPE:x")`, `Ticket(b"LIST")`, `get_flight_info(for_path("by_user"))`, and `do_put` with a path descriptor → `FlightInternalError: There was an error servicing your request` (gRPC 13), no PRV code; an empty or 1 MiB ticket is `INVALID_ARGUMENT`. COMPATIBILITY.md: an unknown verb is "refused by name". Repro: QI-071, QI-077, `test_qi071_*`.
> **Disposition:** POST-GA

### CONSOLEHDR-1 (LOW) — the console can be framed

> **Status:** OPEN — no response from the console carries `Content-Security-Policy`, `X-Frame-Options` or `frame-ancestors`, `X-Content-Type-Options`, `Referrer-Policy`. Its forms are CSRF-protected and its cookie is `SameSite=Lax`, but a signed-in operator can be shown the console inside another site's frame (clickjacking the pause/drop/revoke buttons). Repro: QI-124.
> **Disposition:** POST-GA

### LOGOUTREPLAY-1 (LOW) — a signed-out cookie still renders as signed in

> **Status:** OPEN — the console's session is a signed (not server-side) cookie holding the engine session token (readable: base64 JSON with `token`, `user`, `roles`, `csrf`). After `POST /logout` a copy of the cookie still opens `/queries` with the signed-in chrome ("admin", Sign out) and `PRV-1041 PRV-7001 this server requires a credential` instead of the documented "a session that ends sends them to sign in again". No data is exposed — the engine refuses the ended token. Repro: QI-117.
> **Disposition:** POST-GA

### PLUGINLATE-1 (LOW) — an unknown plugin is not refused at startup, and the refusal contradicts itself

> **Status:** OPEN — a source with `plugin: redis` starts the node (`sources bound: [t <- redis[key]]`, health UP); `PRV-5090 no source plugin named 'redis' …` arrives only at `register`. CONNECTORS.md and GUIDE_WITHOUT_DOCKER §11 say "refused at startup with PRV-5090". The message then says "the server jar carries filesystem alone, and feedfile, jdbc, …" right after `Available: [aerospike, cassandra, delta, feedfile, filesystem, jdbc, kafka, mysql-cdc, postgres-cdc]`: the 2.0 server jar bundles ten plugin modules, and CONNECTORS.md, QUICKSTART's YAML comment and the guide still say filesystem only. Repro: QI-143.
> **Disposition:** POST-GA

### HELMNAME-1 (LOW) — the chart renders names Kubernetes refuses

> **Status:** OPEN — `helm template qi deploy/helm/pravaha --set fullnameOverride=<78 a's>` truncates the base to 63 characters and appends `-headless` (72) and `-test-probes`: an invalid Service name, rendered without complaint; `--set image.repository=""` renders `image: ":2.0.1-SNAPSHOT"`. The chart's own `fail` guards (replicas, TLS secret, standby, spill, PDB) are good. Repro: QI-160.
> **Disposition:** POST-GA

### CDCPRIVCODE-1 (LOW) — a role without REPLICATION gets the wrong code and the wrong advice

> **Status:** OPEN — `ALTER ROLE pravaha_cdc NOREPLICATION`, then register over `customers`: `PRV-5118 … cannot start the initial snapshot … FATAL: permission denied to start WAL sender … Only roles with the REPLICATION attribute may …` followed by advice about transactions left idle and `max_replication_slots`. The PostgreSQL detail is right; the code (PRV-5112 is the prerequisite code, PGCDCPRIV-1) and the remedy are not. Repro: QI-170.
> **Disposition:** POST-GA

### TOPICGONE-1 (LOW) — a deleted Kafka topic is not a stopped feed

> **Status:** OPEN — after `kafka-topics --delete --topic orders` the queries over it stayed `RUNNING`, `feed RUNNING`, health `UP` for as long as the topic was absent, with only the Kafka client's `unknown topic or partition` WARNs. Once the topic was recreated the source correctly stopped with `PRV-5106 offset 214 of orders-0 is no longer in the log` and health went `DEGRADED`. Repro: QI-177.
> **Disposition:** POST-GA

### SNAPDOC-1 (LOW) — `pravaha subscribe` flags do not do what their help says

> **Status:** OPEN — `--limit N` is "stop after N rows" in `--help` but counts commits (`--limit 2` printed a 1 009-row snapshot); `--limit 0` and `--limit -5` are accepted and never end. `--snapshot` is "print the view's rows first", but on a keyed view it prints the changelog Z-set — `alice 500` and `alice 250` both at `+1`, `u7 7` at `+200` — while a read shows one row per key (KEYEDWT-1 documents this for the SDK and the `--answer` flag exists, but CLI.md lists neither `--answer` nor the caveat). Repro: QI-088, QI-099.
> **Disposition:** POST-GA

### CONSOLEREADY-1 (LOW) — the console is never "ready" in front of an authenticating engine

> **Status:** OPEN — `/health/ready` answers `503 {"status":"not-ready","engine":{"reachable":false,…"PRV-7001 this server requires a credential"}}` whenever the engine wants a credential, because the console holds none of its own (by design). That includes the compose stack, where RUNNING_IN_DOCKER and GUIDE_WITH_DOCKER §6 show `200 {"status":"ready","engine":{"reachable":true,…"queries":2}}`. The image's healthcheck uses `/health/live`, so compose is unaffected; an orchestrator probing `/health/ready` never routes to the console. Repro: QI-147.
> **Disposition:** POST-GA

### STALEDOC-1 (LOW) — guides out of step with 2.0

> **Status:** OPEN — (a) GUIDE_WITHOUT_DOCKER §3 lists `pravaha-server-0.2.1-SNAPSHOT-app.jar` (it is `2.0.1-SNAPSHOT`); GUIDE_WITH_DOCKER §3 shows `pravaha-engine 1.0.1-SNAPSHOT` / `pravaha-server-1.0.1-SNAPSHOT-app.jar`. (b) QUICKSTART "What is not built" says the Kafka plugin and the Spring Boot starter are not built and clustering is deferred; `plugins/pravaha-plugin-kafka` and `pravaha-spring-boot-starter` build and ship, and COMPATIBILITY.md documents the starter's Boot 3.4+ requirement. (c) QUICKSTART §4's `pravaha-server --spring.config.additional-location=file:./application.yaml &` (no profile) refuses to start with PRV-7004; the YAML shown has no security block. (d) QUICKSTART §7 `make install` fails on Ubuntu without `python3.X-venv` (the workaround is only in GUIDE_WITHOUT_DOCKER). (e) CLI.md's command map lacks `explain-sql` and `subscribe --answer`. (f) QUICKSTART's Java and Python snippets register then query at once; the Java one printed nothing (the view had not filled yet). Repro: QI-190, QI-193, QI-195, QI-197, QI-200, QI-201.
> **Disposition:** POST-GA

### PGVALIDATE-1 (NOTE) — `SELECT 1` is not answered by the gateway

> **Status:** NOTE — `SELECT 1`, `SELECT now()`, `SELECT 'a'::text`, `SHOW search_path` and `SET search_path` are refused (PRV-2020 LogicalValues, PRV-2002, PRV-2001, PRV-6204). Empty queries, `;`, `-- ping` and pgjdbc `isValid()` work, so most pools are fine, but `SELECT 1` is the default validation query of HikariCP's `connectionTestQuery`, DBeaver's "Test connection", SQLAlchemy's pre-ping on some dialects and Grafana's health check. `COUNT(*)` columns are named `EXPR$0`. Also pgjdbc `getSchemas()` and `getPrimaryKeys()` are PRV-6205 (DBeaver's navigator calls the first). Behaviour matches "refuses anything else by name". Cases QI-029, QI-031.
> **Disposition:** NOTE

### PERMISSIVEUSERS-1 (NOTE) — `dev,users`, the documented console profile, makes every user an administrator of every view

> **Status:** NOTE — with `dev,users` and the catalogue on, `permissive` is imported whole: user `bob` (role `guest`) paused admin's `by_user`, read the audit endpoint and saw every tenant's counts. SECURITY.md documents exactly this; the guides present `dev,users` as the way to run the console without saying so. Cases QI-042, QI-050.
> **Disposition:** NOTE

### RECOVERYHEALTH-1 (NOTE) — a registration refused at recovery leaves the node UP and the query gone

> **Status:** NOTE — PRV-5115 (Postgres slot overtaken) and PRV-5155 (MySQL binlog purged) were detected correctly at restart, but each refused registration simply vanished from `pravaha queries`, with one WARN line and health `UP`. OPERATIONS.md documents "the refused list is the one to read"; a metric or a DEGRADED health for refused recoveries would make it visible. Cases QI-172, QI-174.
> **Disposition:** NOTE

### COOKIETOKEN-1 (NOTE) — the console cookie carries a usable engine bearer token

> **Status:** NOTE — the signed cookie's payload is readable base64 JSON including the engine session token (`prv_s_…`), which works directly against Flight, HTTP and pgwire. HttpOnly and SameSite=Lax limit theft to the same channels a session id has; encrypting the cookie or keeping the token server-side would keep a leaked cookie from becoming a credential for the other doors. Case QI-114.
> **Disposition:** NOTE

Smaller observations, recorded in the cases rather than registered: `PATCH /api/v1/users/{u}` ignores unknown fields with 200 (`{"enabled": false}` does nothing); `POST /api/v1/queries/validate` accepts `{"sql": 5}` and `"str"` as SQL; anonymous `/api/v1/openapi.json` and `/actuator/info` (version); a 16 MiB simple query takes 21 s to refuse; doubled codes in `pravaha-engine` messages (`PRV-1028  stream 't', column 'a': PRV-1028 …`); `ConfigurationException:` class names inside PRV-5091 messages; Java-SDK clients print JEP 498 `sun.misc.Unsafe` warnings unless they add the launcher's two options; the compose Prometheus never scrapes the console, so the Assistant dashboard and the `pravaha-console` rule group are always empty there; Kafka dead letters show `CODE -`; every `pravaha register` prints "a query with the same fingerprint is the same computation, shared" even when nothing is shared; sink failure text (PRV-8009) carries the failed row's values to every reader of the query listing.

---

## Area PGW — the PostgreSQL gateway

### QI-001 — PASS (NOTE)
```
$ PGPASSWORD=$ADMIN psql "host=127.0.0.1 port=26432 dbname=pravaha user=admin" -c '\d' -c '\d by_user' -c 'SELECT * FROM by_user'
 public | by_user | table | pravaha          (List of relations)
 user_id | text   |  | not null |           (Table "public.by_user")
 amount  | bigint |  | not null |
 alice   |    250
\dt, \dv, \conninfo: answered.  \dn, \l, \du, \df: ERROR: PRV-6205 this looks like a pg_catalog query this gateway does not recognise … DETAIL: PGWIRE_UNSUPPORTED_CATALOG_QUERY (connection survives)
```
Matches the topic ("refuses anything else by name"). Evidence `pg1.txt`.

### QI-002 — PASS
Account password → `FATAL: PRV-7001 the credential was rejected` (exit 2); session token and API key connect.

### QI-003 — PASS
Key: connected before revoke; `DELETE /api/v1/keys/{id}` 204; new connection `FATAL: PRV-7001`. Ended session: new connection `PRV-7001`.

### QI-004 — FAIL → PGREVOKE-1
```
read before logout (5,)       logout 204       token over HTTP after logout 401
read on OPEN connection after logout: [('alice', 250), ('carol', 900)]
key: read on OPEN connection after revoke: (5,)
bob: read on OPEN connection after disable: (5,)     (bob's token over HTTP after disable: 401)
```
Minimal repro: open psycopg connection with a key; revoke the key over HTTP; `SELECT` on the same connection still returns rows.

### QI-005 — PASS
`NOTICE: connected as 'ann', not 'dana': the credential determines the principal …`

### QI-006 — PASS
`sslmode=require` → `server does not support SSL, but SSL was required` (exit 2); `prefer` connects.

### QI-007 — PASS
`gssencmode=require` → client error; `prefer` connects; node unaffected.

### QI-008 — PASS
Protocol 2.0 and 4.0 → `E[0A000] PRV-6203 this server speaks PostgreSQL protocol 3.0 …`, closed; node 200. (`pgraw.txt`)

### QI-009 — PASS
Declared 10 001, 2³¹−1 and 7 bytes → `E[08P01] PRV-6202 a startup packet declared … the protocol allows 8 to 10000`; nothing allocated (node heap unchanged).

### QI-010 — PASS
Five SSLRequests → `PRV-6202 the client sent more than 4 encryption requests without ever sending a startup packet`.

### QI-011 — PASS
50 silent sockets: threads 89 → 138; all 50 closed by the server at the 10 s deadline; threads back to 92 after the pool's idle expiry.

### QI-012 — FAIL → PGPREAUTH-1
```
QI node (-Xmx1g):  before used 780 556K → t+1.5s used 1 043 804K of 1 048 576K; 42 × "OutOfMemoryError: Java heap space" in pravaha-pgwire-session
shipped image, 1 GiB container, 60 sockets: health URLError from t+1.5s
  status=exited exit=3 oomkilled=false
  Terminating due to java.lang.OutOfMemoryError: Java heap space
```
Minimal repro: `docker run --memory 1g -e SPRING_PROFILES_ACTIVE=dev,users` the 2.0.0 image with `pravaha.pgwire.enabled: true`; open 60 TCP connections; on each send a v3 startup packet, read the `R` (cleartext password) reply, send `p` followed by the 4-byte length 16 777 216 and nothing else; keep them open.

### QI-013 — PASS (NOTE)
A 16 MiB simple query on an authenticated connection: answered `T C Z` after 21.0 s, node healthy.

### QI-014 — PASS
Lengths 3, 0, −1 → `E[08P01] PRV-6202 a 'Q' message declared … this server accepts 4 to 16777216`.

### QI-015 — PASS
`z` → `E[0A000] PRV-6201 message type 'z' is not supported`, then `Z(I)`; session continues.

### QI-016 — PASS
Execute of an unbound unnamed portal → `E[34000] PRV-6208 the unnamed portal does not exist`, `Z(I)`; next query works.

### QI-017 — PASS
Two values bound to one placeholder → `E[42000] PRV-2061 this statement has 1 placeholder and 2 values were bound`, `Z(I)`; usable.

### QI-018 — PASS (NOTE)
Re-`Parse` of named statement `s1` without `Close` is accepted silently (PostgreSQL: `42P05`). No documented promise.

### QI-019 — PASS
psycopg default mode: bad statement `42000 PRV-2002`; next `25P02 PRV-6212 current transaction is aborted …`; status INERROR; after `rollback()` the read returns rows.

### QI-020 — PASS
`BEGIN` → `BEGIN`; failure; `COMMIT` answers tag `ROLLBACK`; status IDLE — as PostgreSQL does.

### QI-021 — FAIL → PGCOPY-1
```
INSERT/UPDATE/DELETE → 42000 PRV-2020 … is not built        (as documented)
CREATE ALERT …       → 25006 PRV-6211 the PostgreSQL gateway is read-only   (as documented)
CREATE TABLE / DROP TABLE → 42000 PRV-2001 Incorrect syntax
COPY by_user TO STDOUT → 42000 PRV-2001 Non-query expression encountered in illegal context   (documented: PRV-6201, 0A000)
DECLARE c CURSOR …  → 42000 PRV-2001                                                          (documented: PRV-6201, 0A000)
SELECT STREAM user_id FROM txn → 42P01 PRV-4023                                               (topic: continuous-query statements PRV-6211)
```

### QI-022 — PASS
CancelRequest with a random key: connection closed, no reply.

### QI-023 — NOT RUN
No read lasts long enough to cancel (reads answer from the maintained view in milliseconds; the 16 MiB parse in QI-013 is the only long one and is not cancellable mid-parse by design). QI-022 shows the cancel itself is a harmless no-op.

### QI-024 — PASS
View `uni` with columns `名前`, `montant_€` and a value `名前😀`: psql, psycopg and pgjdbc (`getColumns` → `[名前:text, montant_€:int8]`) round-trip byte-exact.

### QI-025 — PASS (NOTE)
`client_encoding=LATIN1` / `SQL_ASCII` at startup: the server reports `UTF8` and the driver follows it (`info.encoding utf-8`); values intact. Accepted-and-ignored is visible to the client, so nothing is silently mangled.

### QI-026 — BLOCKED
A 1 MiB value cannot enter the engine: the CSV row was dead-lettered at ingest, `PRV-5040 field 'user_id' needs 1048656 bytes and this row was given 512` (visible in `pravaha dlq list`). The ingest row bound is the engine's (second QA agent's area); the gateway could not be tested with a value that large.

### QI-027 — FAIL → BIGREAD-1
```
pravaha queries: allrows  RUNNING (source stopped)  … PRV-4022 view 'allrows' holds 1009786 keys, past its ceiling of 1000000
psql: SELECT * FROM allrows      → ERROR: 42000: PRV-3001 the projection's arena is full; raise pravaha.lane.arena.slab-bytes …
psql: SELECT COUNT(*) FROM allrows → ERROR: XX000: Index -1 out of bounds for length 64
pravaha query --sql "SELECT COUNT(*) AS n FROM allrows" → PRV-1041 Index -1 out of bounds for length 64
same COUNT over by_user (1008 rows) → 1008
```

### QI-028 — PASS
300 concurrent psycopg connections, each two reads 5 s apart: 300 OK, 0 errors, threads 100 → 409 peak, 6.5 s; health UP. (No connection cap exists — relevant to PGPREAUTH-1.)

### QI-029 — PASS (NOTE → PGVALIDATE-1)
Answered: `''`, `;`, `-- ping`, `version()`, `current_schema()`, `SHOW server_version`, `SHOW TRANSACTION ISOLATION LEVEL`, `SET application_name`, `pg_backend_pid()`, `count(*)`. Refused by name: `SELECT 1` (PRV-2020), `pg_settings` (PRV-4023), bare `information_schema.tables`/`columns` and `pg_type` (PRV-6205), `SET statement_timeout`/`search_path` (PRV-6204), `now()`, `::text`, `SET TIME ZONE` (PRV-2001/2002). Connection survives each.

### QI-030 — PASS
User `carl` (tenant `acme`) reading `by_user` (tenant `public`): `PRV-4023 no views are registered`; nothing of `public` visible. (`bob` in `public` reads it: `permissive`, see PERMISSIVEUSERS-1.)

### QI-031 — FAIL → PGINTPARAM-1
pgjdbc 42.7.11, Java 25:
```
isValid: true            getTables: [by_user, uni]      getColumns uni: [名前:text, montant_€:int8]
prepared, autocommit off, setString: 900
prepared setInt vs BIGINT: PSQLException 08P01 ERROR: PRV-6202 a binary parameter declared 4 bytes; this server expected 8
connection still usable after 08P01: true
prepared setLong x7 (crosses the server-prepare threshold): 4
getSchemas / getPrimaryKeys: PSQLException 0A000 PRV-6205
SELECT 1: 42000 PRV-2020
```
psycopg `%b` with int 100: `08P01 PRV-6202 a binary parameter declared 2 bytes; this server expected 8`.

### QI-032 — PASS
`WHERE user_id = %s` with `x' OR '1'='1` → `[]`; with `alice` → one row.

## Area HTTP

### QI-040 — PASS
Every path in `api/openapi.lock.json`, anonymous: `401 PRV-7001` on all but `POST /api/v1/auth/reset/redeem` (`400 PRV-7017 this reset token is unknown …`, open by design). (`http1.txt`)

### QI-041 — FAIL (part of TOMCATHTML-1)
Forged `prv_s_…`, empty bearer, forged `prv_dev_…`, a token with two characters changed, Basic `admin:pravaha-dev-admin` → `401 PRV-7001 the credential was rejected` (no reason given — correct). A 10 000-character bearer → Tomcat HTML `400`.

### QI-042 — PASS
As `ann` (reader): users/sessions/keys report/roles/password-reset/keys?all → `403 PRV-7002 administering users and keys needs the 'admin' role`; lanes rebalance → `403`; `POST /api/v1/keys` with `roles:[admin]` → `403 PRV-7015 a key can hold only roles its holder has`. `GET /api/v1/audit` and `/tenants` → 200: the node's policy is `permissive`, under which SECURITY.md says every caller may read the trail. (PERMISSIVEUSERS-1)

### QI-043 — PASS (NOTE)
`{`, `[]`, `null`, empty, `\xff\xfe` → `400 PRV-1052` ApiError, no class names. `"str"`, `{"sql": 5}` and a duplicate key → `200 valid:false PRV-2001` (coerced to SQL text).

### QI-044 — PASS
`text/plain`, `application/xml`, `multipart/form-data`, form-urlencoded → `415 PRV-1052` ApiError.

### QI-045 — FAIL → HTTPBODY-1
```
single 50 MiB body, admin, /queries/validate → 400 in 0.02 s  (Jackson: String value length (20051112) exceeds the maximum allowed (20000000))
single 50 MiB body, anonymous, /auth/login  → 400                (body parsed before the endpoint)
QI node: 30 × 19 MB anonymous login concurrently → results {500: 17, 401: 11, BrokenPipe: 2}; +38 OutOfMemoryError (incl. thread pravaha-clock)
shipped image, 1 GiB: same 30 → status=exited exit=3; "Terminating due to java.lang.OutOfMemoryError: Java heap space"
```

### QI-046 — FAIL → TOMCATHTML-1
`%2e%2e`, `..;/x`, `<script>`, a 5 000-char name → ApiError 404 (`PRV-8002 no registered query named '<script>' that you may see`, `PRV-1052`); `..%2f`, `%00`, `..%5c` → Tomcat HTML 400. No file served anywhere.

### QI-047 — PASS
`DELETE /status`, `PUT /queries`, `PATCH /streams` → `405` ApiError with `Allow`; `TRACE` → 405 empty; `OPTIONS` → 200 `Allow: GET,HEAD,OPTIONS`.

### QI-048 — PASS
Preflight from `https://evil.example` → 401, no `Access-Control-*` headers; simple GET → none.

### QI-049 — PASS (NOTE)
`/api/v1/nope`, `/nope` → 404 ApiError; unknown query plan → `404 PRV-8002`; dead letter of unknown query → `404`; `/error` requested directly → `500 PRV-1052` (cosmetic).

### QI-050 — PASS
A user in `acme` sees no `public` view by name through `/api/v1/queries/{n}`, `/views/{n}` or reads (`PRV-4023`, `PRV-8002`); `/api/v1/tenants` shows `public`'s counts to that user — documented for `permissive` (PERMISSIVEUSERS-1).

### QI-051 — PASS (NOTE)
Live `/api/v1/openapi.json` vs the lock: no path or method difference. Anonymous callers get the 78 KB document (`/api/docs` redirects, Swagger UI itself 401).

### QI-052 — PASS (NOTE)
`GET /status` is in the lock and answers with a credential; anonymous 401.

### QI-053 — PASS (NOTE)
Anonymous: `/actuator/health{,/liveness,/readiness}` 200 status only; `/actuator/info` 200 with the version; `/actuator`, `metrics`, `prometheus`, `env`, `heapdump`, `threaddump`, `loggers` → 401.

### QI-054 — FAIL → LOCKENUM-1
```
lock1: failures 1-5 → 401 PRV-7010 that user name and password do not match
       failure 6     → 423 PRV-7011 this account is locked until 2026-10-02T01:25:03Z after too many failed sign-ins
       right password → 423 PRV-7011
nosuchuser_zz after many → 401 PRV-7010
```

### QI-055 — PASS
Medians before any lock (4 tries each): tim0 241 ms / ghost0 232; tim1 305 / ghost1 244; tim2 225 / ghost2 217; identical status and body.

### QI-056 — PASS
`short` → `PRV-7012 at least 12 characters`; `alllowercaseletters` → `PRV-7012 at least 3 of …`; `Abcdefghijk1` → 200; `Abcdefghij1` (11) refused; `pravaha-dev-admin` refused.

### QI-057 — PASS
An ended session's token → `401` on `/api/v1/auth/me` (QI-004 transcript).

### QI-058 — PASS
`via` → `PRV-7020 'via' is a claim the engine sets …`; 257 chars, `\n`, empty → `PRV-7020 … 1 to 256 characters with no control characters`; 33 attributes → `PRV-7020 at most 32`; name `<b>` → `PRV-7020 an attribute name is 1 to 64 …`; `region=EU` → 200.

### QI-059 — FAIL → DECLSTREAM-1 (NOTE on names)
Stream names `../x`, `a b`, `<script>`, 300 × `x`, `SELECT`, `名前`, `a;DROP`, `a"b` → `201` (empty → `400 PRV-1051`); nothing written to disk (streams are catalogue entries) and the console escapes them (QI-122). Then:
```
$ pravaha streams declare s2 --schema "k:INT64,v:INT64"   → declared s2
$ pravaha validate --sql "SELECT k FROM s2"              → valid  output: [k INT64 NOT NULL]
$ pravaha register --name s2v --sql "SELECT k, v FROM s2" --keys 0
PRV-2002  Object 's2' not found. This server has 0 stream(s) declared …     (fresh 2.0.0 container, catalogue on and off)
```

### QI-060 — FAIL (TOMCATHTML-1)
64 KiB header and 500 headers → Tomcat HTML `400`; node healthy afterwards.

## Area FLT

### QI-070 — PASS
`do_action("nope")` → `INVALID_ARGUMENT Unrecognized request: nope`; `pravaha.nope` → `NOT_FOUND PRV-6102 this is not a Pravaha request`.

### QI-071 — FAIL → FLIGHTTICKET-1
`\x00\xff garbage`, `NOPE:x`, `LIST`, `pravaha.list` tickets → `FlightInternalError: There was an error servicing your request` (grpc 13); empty and 1 MiB tickets → `INVALID_ARGUMENT`; `get_flight_info(for_path)` → INTERNAL; `list_flights` → UNIMPLEMENTED.

### QI-072 — PASS
Anonymous `list_actions`, `list_flights`, `get_flight_info`, `do_get`, `get_schema` and all eight actions → `UNAUTHENTICATED PRV-7001`. `do_put`/`do_exchange` fail at the first exchange with the same.

### QI-073 — PASS
A subscriber that read one batch and stopped, while 200 000 rows were appended: heap oscillated 228–389 MB (no growth trend), a second subscriber received all 200 000, `pravaha_query_subscribers{query="allrows"} 2`.

### QI-074 — PASS
`kill -9` of the stalled client: subscriber gauge 2 → 0 within 5 s; health 200.

### QI-075 — PASS
`pravaha subscribe --view by_user --reconnect` across a node restart: `-- reconnected; commits made while disconnected were not delivered`, then `+1 frank 222` written after the restart.

### QI-076 — BLOCKED
The 300 views were to be registered over a stream declared through `POST /api/v1/streams`; DECLSTREAM-1 refused the first registration. Not repeated over `txn` (each view would replay its 1.2 M rows on a 1 GiB heap).

### QI-077 — FAIL (FLIGHTTICKET-1)
`do_put` with a path descriptor to a view, an undeclared stream and `txn` → INTERNAL without a code.

## Area CLI

### QI-080 — PASS
Unknown command 2; missing `--sql` 2; `--url grpc://127.0.0.1:1` 3 (`PRV-1040 … talking to grpc://127.0.0.1:1`); `--http …:1` 3; engine refusal 1 (`PRV-4023` + where to look it up); token over plaintext 2 (`PRV-1031`, Flight and HTTP); `health` against nothing 3. (`cli1.txt`)

### QI-081 — PASS
`--json queries` / `status` are single JSON documents; failures leave stdout empty and put `{"error": {"code": "PRV-2002", …, "exit": 1}}` on stderr (usage errors `"code": null`, exit 2).

### QI-082 — PASS
`queries --url …` and `--url … queries` identical.

### QI-083 — PASS
`drop`, `key revoke`, `user disable`, `lanes rebalance`, `revoke`, `policy drop`, `alert drop` without `--yes`: "would …" on stdout, "nothing was changed; run … --yes" on stderr, exit 0, state unchanged.

### QI-084 — PASS
`login --save`: directory 0700, token 0600 under `$PRAVAHA_CONFIG_DIR`.

### QI-085 — PASS
Without `PRAVAHA_CONFIG_DIR`, `XDG_CONFIG_HOME=<empty dir>` → no saved token → 401; with it → the saved session.

### QI-086 — PASS
`pravaha validate … --schema` and `pravaha run …` → exit 2 naming `pravaha-engine`.

### QI-087 — PASS
`health` → `UP` exit 0; `DEGRADED` exit 0 seen in the stack (CONN); nothing answering → 3.

### QI-088 — FAIL (SNAPDOC-1)
`--sql-file /nonexistent.sql` → 2 `cannot read …`; `--keys a` → 2; `--timeout -1` → 2 `PRV-1031 request_timeout_seconds must be positive`; `--timeout abc` → 2; `--params a,b` for one placeholder → 2; `--params it's` bound literally (0 rows); missing params → 1 `PRV-2060`; `--overflow NOPE` → 2; `--url http://…` accepted for Flight. `subscribe --limit 0` and `--limit -5` never end (killed after 10 s).

### QI-089 — PASS
`pravaha version` → `pravaha 2.0.1` / `server 2.0.1-SNAPSHOT (http://127.0.0.1:28480)`; `--client` → the first line only.

### QI-090 — PASS
Bare wheel in a fresh venv: `pravaha status` works; `pravaha queries` → exit 2 `this command speaks Arrow Flight and needs pyarrow … pip install "pravaha[flight]"` (the sentence is printed twice — cosmetic).

### QI-091 — PASS
`tools/sdk-standalone-check.sh` step 4: `import pravaha: ok`, `pravaha.connect -> ImportError: … pip install "pravaha[flight]"`.

### QI-092 — PASS
`tools/build-sdk.sh -- -o -Dmaven.repo.local=<scratch>` → exit 0; `target/sdk-dist/java/` (api, sdk-java, sdk-java-flight, `-all`, sources, javadoc, poms) and `python/pravaha-2.0.1-py3-none-any.whl`, `.tar.gz`.

### QI-093 — PASS
`tools/sdk-standalone-check.sh --docker pravaha/pravaha-server:2.0.0` → exit 0, "all four clients worked from the SDK artefacts alone" (Maven client, `-all` jar with `javac`/`java`, wheel with and without `[flight]`); container removed.

### QI-094 — PASS
Step 2 of the standalone check and the QUICKSTART Java snippet (QI-196) compile and run against the `-all` jar alone with `--add-opens=java.base/java.nio=ALL-UNNAMED` (JEP 498 warnings printed — NOTE).

### QI-095 — PASS
`javac` 21 against the `-all` jar: `class file has wrong version 69.0, should be 65.0`; a class compiled for 21 run on java 21 with the jar: `UnsupportedClassVersionError … (class file version 69.0)`. Clear enough; it is the JVM's message, not Pravaha's.

### QI-096 — PASS (NOTE)
Missing `--in` → 2 `--in is required`; `a:NOTATYPE` → 1 `PRV-1028 … PRV-1028 unknown type` (code doubled); unwritable `--out` → 1 `PRV-5040 cannot open …`; `--sql ""` → 2 `--sql is required. Supplied: [sql, …]` (contradictory wording); join → 1 `PRV-2002 … PRV-2002 Object 'u' not found. This command plans against one stream`; `--level nonsense` → 2; missing input → 1 `PRV-5040 plugin 'txn' cannot read …`; `nosuch` → 2 with usage.

### QI-097 — PASS
`pravaha-engine --help` exit 0, lists validate/explain/run/version.

### QI-098 — PASS
```
pravaha-engine: Java 21 found (/usr/lib/jvm/java-21-openjdk-amd64/bin/java); Pravaha 2.x requires Java 25 or later.
  Point JAVA_HOME at a JDK or JRE 25 … (Pravaha 1.x is the line that runs on Java 21; docs/operations/COMPATIBILITY.md).   exit=1
```
Same for `pravaha-server`, and with java 21 found on `PATH` only.

### QI-099 — FAIL (SNAPDOC-1)
`--json subscribe --snapshot --limit 2`: change objects then `{"type": "snapshot", "rows": 1009, "frontier": …}` — `--limit` did not stop at 2 rows. Minimal keyed repro: view `snaprep` (`alice` 500 then 250; `u7` 7 × 200): read → `alice 250`, `u7 7`; snapshot → `alice 500 +1`, `alice 250 +1`, `u7 7 +200`.

### QI-100 — PASS
Catalogue off: `catalog ls`, `grant`, `policy ls` → exit 1 `PRV-7030 this node's catalogue is off (pravaha.catalog.enabled is false) …`.

### QI-101 — PASS
`PRAVAHA_DOCS_BASE_URL=https://docs.example` → `  https://docs.example/PRV-4023` after the message.

## Area CON

### QI-110 — PASS
Anonymous: `/`, `/about`, `/about/competitive`, `/help*`, `/tutorials`, `/login`, `/health`, `/health/live`, `/api/v1/health` → 200; every engine, account and admin page → `303 /login?next=<path>`; `/_components`, `/metrics` → 404. (`/health/ready` → 503, CONSOLEREADY-1.) (`con1.txt`)

### QI-111 — PASS
`/api/v1/queries` → `401 {"error":"sign in to the console first"}`; others 404.

### QI-112 — PASS
As `ann`: `/admin` → redirect to `/admin/access`; `/admin/users`, `/keys`, `/sessions` → 403; `/admin/audit`, `/tenants` → 200 (engine allows under `permissive`); no user names shown; `POST /admin/users` (with ann's CSRF token, role admin) → refused by the engine, `evil2` not created.

### QI-113 — PASS
`POST /queries/uni/pause` with no token, with ann's token, with a bad token from `Origin: https://evil.example` → 403 each; `uni` still RUNNING. `/preferences/role` without token → 403.

### QI-114 — PASS (NOTE → COOKIETOKEN-1)
`Set-Cookie: pravaha_console=…; path=/; Max-Age=43200; httponly; samesite=lax`; payload keys `token, user, expires, must_change, roles, tenant, default_admin_password, csrf`.

### QI-115 — PASS
`next=` `https://evil.example`, `//evil.example`, `/\evil.example`, `javascript:alert(1)`, `%2F%2Fevil.example`, `/%2F%2F…`, `https:evil.example`, `\\evil.example` → `303 /home`; `/\t/evil.example` → `/%09/evil.example` (same-origin path).

### QI-116 — PASS
Cookie value changes at sign-in.

### QI-117 — FAIL → LOGOUTREPLAY-1
`POST /logout` → `303 /`; the copied cookie on `/queries` → 200, chrome shows admin and Sign out, body `PRV-1041 PRV-7001 this server requires a credential`, no rows; on `/admin/users` → 303.

### QI-118 — PASS
`GET /logout` → 405.

### QI-119 — PASS
`/queries`, `/queries/by_user`, `/views*`, `/overview`, `/catalog*`: rows `<img src=x onerror=alert(1)>` and `<script>alert(2)</script>` appear only escaped.

### QI-120 — PASS
Same values in the live tail (`/views/by_user/live`) and the workbench result: escaped.

### QI-121 — PASS
Workbench error for `SELECT '<script>…' FROM nope`, `/help/search?q=<script>…`, `/queries/<script>…`, `/help/codes/<script>…`: escaped or 404/405.

### QI-122 — PASS
Catalogue comment `<img src=x onerror=alert(77)>`, tag `owner=<svg/onload=alert(78)>`, stream `<svg onload=alert(79)>`: rendered `&lt;img src=x onerror=alert(77)&gt;`, `&lt;svg onload=alert(79)&gt;` in text and `href`.

### QI-123 — FAIL (LOCKENUM-1)
Six wrong passwords through `/login`: `tim2` (exists) → 423; `ghost9` → 401.

### QI-124 — FAIL → CONSOLEHDR-1
`/overview` response: `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Strict-Transport-Security`, `Permissions-Policy` all absent.

### QI-125 — PASS
`/metrics` anonymous → 404 (metrics not enabled in this console config).

### QI-126 — PASS
22 help examples (cli-reference, catalogue, debug, dlq, lanes, alerts, audit, permissions, views topics), with the fixture names `by_user`/`allrows`/`ann`: `catalog namespaces|search|show|create-namespace|move|ls --kind VIEW`, `grants --on`, `access why ann by_user`, `describe`, `explain --level logical --graph`, `lanes list`, `lanes rebalance status`, `alerts channels`, `audit --since`, `dlq list|show`, `debug checkpoints|fork|state|step --step row|view|end|sessions`, `permissions`, `views describe` — every one behaved as its page shows. (`help15.txt`)

### QI-127 — PASS
Sign-out → `303 /`.

### QI-128 — PASS
`carl` (acme): `/queries/by_user`, `/views/by_user` → 404, no rows; `/queries`, `/overview` don't mention it.

### QI-129 — PASS
Anonymous `POST /preferences/role` → 303 to sign in; signed in without token → 403.

### QI-130 — PASS
Encoded traversal under `/help`, `/help/topics`, `/help/decisions`, `/about/papers`, `/tutorials`, `/help/case-studies`, `/static` → 404; nothing outside `content/` served.

## Area OPS

### QI-140 — PASS
2.0.0 image, `--read-only`, `--user 1000:1000`, four bind mounts: ready in 6 s; `docker diff` empty; every created file mine (`.pravaha-owner`, `checkpoints/`, the snappy `.so` and Tomcat dirs in `tmp/`).

### QI-141 — PASS
`docker exec … bin/pravaha-health /actuator/health/liveness` → exit 0.

### QI-142 — PASS
`data/` mode 0500 → exit 1, `PRV-8006 pravaha.registry.journal is /opt/pravaha/data/registry.journal and this process cannot write into /opt/pravaha/data …`.

### QI-143 — FAIL → PLUGINLATE-1
Unparseable YAML → exit 1 with SnakeYAML's position (`expected ',' or ']'` line 3 col 21). `plugin: kafka` without the plugin directory → starts (Kafka is bundled). `plugin: redis` → node starts, `sources bound: [t <- redis[key]]`, health UP; `pravaha register` → `PRV-5090 no source plugin named 'redis' … Available: [aerospike, …, postgres-cdc] … the server jar carries filesystem alone …`.

### QI-144 — PASS
Empty `application.yaml`, no profile → exit 1 `PRV-7004 this node is configured to accept unauthenticated callers …`.

### QI-145 — PASS
`administer: legacy-read` → exit 1 `PRV-7004 pravaha.security.administer is 'legacy-read', and legacy-read was removed in 2.0; grant MODIFY/MANAGE or use the admin role …`.

### QI-146 — PASS
`openjdk version "25.0.4.1" 2026-08-18 LTS`; `Starting PravahaServerApplication v2.0.0 using Java 25.0.4.1`; status `"version":"2.0.0"`.

### QI-147 — PASS
`tools/docker-env.sh --home <scratch>`, `.env` edited to project `pravaha-qa`, tag `2.0.0`, ports 2849x/2643x/2747x: `--profile seed up -d --wait` → kafka, pravaha-server, pravaha-console healthy; home and secrets owned by me (`initial-admin-password` 0600, `prometheus.token` 0644 in 0700 `secrets/`). (`stack1.txt`; console readiness → CONSOLEREADY-1.)

### QI-148 — PASS
`rm --stop --force pravaha-server && up -d --wait`: `registry recovered 2 of 2`, ROWS IN 0, `orders_live` answers acme 5/1502, globex 4/834, initech 3/309.

### QI-149 — PASS
`docker diff` server: nothing; console: only the `conf/console.yaml` mount point. Both `ReadonlyRootfs=true`, user `1000:1000`.

### QI-150 — PASS
Console port moved onto a bound port → `failed to bind host port`; the other services and the existing listener unaffected.

### QI-151 — FAIL → SEEDWINDOW-1
```
seed: the view spend_per_minute
window_start  customer  orders  spend
0 rows
partition 0 end offset: orders:0:12   partition 1: orders:1:0   partition 2: orders:2:0
pravaha_query_watermark_lag_seconds{query="spend_per_minute"} NaN        (minutes later, and after a recreate)
one more order at 10:07 → 12 rows, 10:00 … 10:05
```

### QI-152 — PASS (NOTE)
Prometheus target `pravaha up`; 3 groups, 15 rules, all `ok`; Grafana: four dashboards in folder Pravaha; `jvm_info` 25.0.4.1+1-LTS. Never scraped: `pravaha_console_*` (no console job — Assistant dashboard and `pravaha-console` rules always empty), alert and catalogue counters (created on first use; catalogue off in the stack).

### QI-153 — PASS
`promtool check rules` → `SUCCESS: 15 rules found`; `check config` valid. Every `pravaha_*` series in the rules exists on the engine except the lazily-created and console ones above.

### QI-154 — PASS
RUNNING_IN_DOCKER `cdc` walkthrough verbatim: `customers_live` → `2 globex silver / 1 acme platinum / 4 umbrella silver`; `payments_live` → `1 acme 500 / 2 globex 300`.

### QI-155 — FAIL → ENVRERUN-1
```
before re-run: COMPOSE_PROJECT_NAME=pravaha-qa  PRAVAHA_TAG=2.0.0  PRAVAHA_HTTP_PORT=28490 … PRAVAHA_GRAFANA_PORT=29498
$ tools/docker-env.sh --home <same>      → "kept … application.yaml (exists; never overwritten)"
after:  COMPOSE_PROJECT_NAME=pravaha-stack  PRAVAHA_TAG=local  PRAVAHA_HTTP_PORT=18080  PRAVAHA_FLIGHT_PORT=19090
        PRAVAHA_PGWIRE_PORT=26435 (kept)  PRAVAHA_CONSOLE_PORT=17070  … PRAVAHA_GRAFANA_PORT=23030
```
Credentials and `secrets/` were kept. (The project name sits in the block the file says to regenerate; the ports and tag do not.)

### QI-156 — PASS
`deploy/release/dist.sh` → `target/dist/pravaha-2.0.1-SNAPSHOT.tar.gz` (185 M); unpacked into `dist test dir/` (a space in the path): `bin/pravaha-server --spring.profiles.active=dev` from `/tmp` → ready in 5 s, every path under the unpacked home.

### QI-157 — PASS
Same refusal text as QI-098 from the tarball's launcher.

### QI-158 — PASS
Files newer than a marker: only under the home (`data/.pravaha-owner`, `data/checkpoints/.pravaha-owner`, `tmp/snappy…so`, `logs/pravaha-server.log`); nothing in `$HOME`; `/tmp/hsperfdata_*` entries belong to other JVMs (the launcher sets `-XX:+PerfDisableSharedMem`).

### QI-159 — PASS
`deploy/helm/test.sh` → `19 checks PASSED`.

### QI-160 — FAIL → HELMNAME-1
`replicaCount=0|3` → `fail` with the ADR-045 reason; `tls.enabled` without a Secret → `fail`; `probes=null` → template nil-pointer error; release name > 53 → helm refuses; `fullnameOverride` 78 chars → `…a-headless` (72 chars) rendered; `image.repository=""` → `":2.0.1-SNAPSHOT"`; `persistence.size=-1Gi`, `podSecurityContext.runAsUser=0`, `config…administer=legacy-read` render (the node refuses the last at start).

### QI-161 — PASS
1.0.0 image wrote: users carl (tenant acme) and dana, dana's API key, `v_default` (admin), `acme.default.v_acme` (carl), alert `a_big` on `ops-log`, namespace `public.ns1`, grant `SELECT … TO ROLE reader`. 2.0.0 on the same home: `registry recovered 2 of 2`, `alerts: recovered 1`; users, tenant, grant, namespace (comment "made by 1.0.0"), alert (`FIRING 1`), carl's `v_acme` read as carl, dana's key works; a row appended after the upgrade arrives. (`up-v1.txt`, `up-v2.txt`)

### QI-162 — PASS
The upgraded home plus `administer: legacy-read` → `PRV-7004 … legacy-read was removed in 2.0 …`.

### QI-163 — PASS
1.0.0 on the state 2.0.0 wrote: `recovered 2 of 2`, `alerts: recovered 1` (not promised; works).

### QI-164 — PASS
The 1.0.0 CLI (`pravaha/pravaha-console:1.0.0`) against a 2.0 node: `version` and `queries` work.

### QI-165 — PASS
Console container in the stack: `ReadonlyRootfs=true`, user 1000:1000, healthy, `docker diff` = the conf mount point only.

## Area CONN

### QI-170 — FAIL → CDCPRIVCODE-1
`ALTER ROLE pravaha_cdc NOREPLICATION` → `PRV-5118 plugin 'customers' cannot start the initial snapshot … FATAL: permission denied to start WAL sender … The snapshot is pinned to the log by a temporary replication slot … a session idle in a transaction holds it up …`.

### QI-171 — PASS
`wal_level=replica` → `PRV-5091 … PRV-5112 plugin 'customers': wal_level is 'replica', and logical decoding needs 'logical'. Run ALTER SYSTEM SET wal_level = logical; and then RESTART PostgreSQL …` (NOTE: `com.ash.messaging.pravaha.api.ConfigurationException:` appears inside the message).

### QI-172 — FAIL → CDCSLOT-1
```
NOTICE: dropped=t after 0 tries          INSERT (6,'pied piper') ; later INSERT (7,'aviato')
pg_replication_slots: (none)   pg_stat_activity: pravaha-cdc pravaha_customers | idle | client backend
pravaha query … customers_live → 4 rows (1,2,4,5) — 6 and 7 missing
describe: state RUNNING  feed RUNNING ;  /actuator/health {"status":"UP"} ;  no WARN/ERROR in 10 minutes of log
after a restart: WARN registration not recovered -- customers_live: PRV-5115 … slot 'pravaha_customers' has already confirmed …
```
Minimal repro: compose `cdc` profile, register `customers_live`, then in PostgreSQL loop `pg_terminate_backend(active_pid)` + `pg_drop_replication_slot('pravaha_customers')` until the drop wins; insert a row; the view never receives it and nothing reports a problem.

### QI-173 — BLOCKED
`ALTER PUBLICATION … DROP TABLE customers` was applied after CDCSLOT-1 had already stalled the source, so its own effect could not be told apart (row 7 missing, RUNNING). Needs a fresh stack.

### QI-174 — PASS (NOTE → RECOVERYHEALTH-1)
First attempt purged only binlogs the checkpoint did not need: rows 4 and 5 arrived after restart, exactly once. Second, `PURGE BINARY LOGS TO` the active file: `registration not recovered -- payments_live: PRV-5155 plugin 'payments' cannot resume from gtid=…:1-15 …: mysql:3306 has purged transactions …:16-17 that come after it. The changes in between are gone … Raise binlog_expire_logs_seconds …`.

### QI-175 — PASS
`binlog_format=STATEMENT` → `PRV-5152 … binlog_format is STATEMENT, and change capture needs every changed row in the log: SET PERSIST binlog_format = 'ROW'; …`.

### QI-176 — PASS
200 orders, broker restarted, 1 more (landed), 200 more: view count 13 → 414 = 13 + 200 + 1 + 200. Feed RUNNING.

### QI-177 — FAIL → TOPICGONE-1
Topic deleted: queries RUNNING, feed RUNNING, health UP; log only `Received unknown topic or partition error in fetch` WARNs. Recreated with 3 partitions + 30 orders: `orders#0 STOPPED: PRV-5106 offset 214 of orders-0 is no longer in the log … so the source stops`; health `DEGRADED a source feed has stopped`.

### QI-178 — PASS
Partitions 3 → 6 and 60 round-robin orders: 20 present after 20 s, all 60 eventually (`COUNT … order_id >= 2000` = 60); dead letters from partitions 3–5 prove they were read. (`watermark_partitions` metric still 3 at the 20 s mark.)

### QI-179 — PASS (NOTE)
`order_id` string, `amount` string, `amount` missing, non-JSON → four dead letters with exact reasons (`column 'amount' is INT64 and its value is a string that does not parse as one`, `… is missing or null, and is declared NOT NULL`, `the value is not valid JSON …`); the next good record arrives; feed RUNNING. The DLQ `CODE` column is `-` for Kafka decode failures.

### QI-180 — PASS
`pay_out` table column dropped while `pay_sink` runs: `pay_sink: sink 'pay_out' detached with PRV-8009 … PRV-5076 … column "amount" of relation "pay_out" does not exist … re-registering the query against the sink starts it again from the view's contents`; `pravaha queries` shows `pay_out (detached)`; the view keeps the new row. (NOTE: the message carries the row's values.)

## Area DOC

### QI-190 — PASS (STALEDOC-1)
`./mvnw -q install -DskipTests` (offline, scratch repository) → EXIT 0 in a fresh clone. Produced `pravaha-server-2.0.1-SNAPSHOT-app.jar` and `pravaha-cli-2.0.1-SNAPSHOT-cli.jar`; the guide names `0.2.1-SNAPSHOT`. (Deviation: `-o -Dmaven.repo.local=…` added to keep `~/.m2` untouched while other checkouts build.)

### QI-191 — PASS (NOTE)
§5 `make -C sdk/python install` after `uv venv --seed .venv` (ensurepip missing, as the guide's troubleshooting says) and `make test` → exit 0 (not "quiet": progress dots). §7–§10 verbatim (home under scratch, `PRAVAHA_CONFIG_DIR` set): the log lines, `by_user` `alice 250` → `alice 250, carol 900`, `--params carol` → 900, SDK snippet and psycopg snippet print the two rows, `psql` prints them too; restart `registry recovered 1 of 1` and `alice 250 / carol 900 / bob 75`. ROWS IN after the restart was **5**, not the guide's 1, because the walkthrough's pace stopped the node before its first 5 s checkpoint; given 20 s up it was 1. (`guide.txt`)

### QI-192 — PASS
QUICKSTART §2: `ok 6 in, 3 out`, `alice,500 / dave,150 / frank,1200`; the bad line `PRV-5040 line 2, column 'amount' (INT64): 'not-a-number' is not a number`; with `--dlq`, `ok 2 in, 2 out / 1 rejected -> rejects.jsonl`. §3: `PRV-2050 GROUP BY user_id has no bound on its key space …`.

### QI-193 — FAIL (STALEDOC-1)
§4 `pravaha-server --spring.profiles.active=dev &` + `pravaha queries` → `no continuous queries are registered`. The next command, `pravaha-server --spring.config.additional-location=file:./application.yaml &`, refuses to start: `PRV-7004 this node is configured to accept unauthenticated callers …`. With `dev` kept, the rest works.

### QI-194 — PASS
`velocity.sql` registered `--keys 1`; `--params u1` → `u1 750`; `subscribe --view user_volume --snapshot --limit 1` and `--filter user_id=u1` behave as described; `drop --name user_volume --yes` → `dropped user_volume`.

### QI-195 — PASS (STALEDOC-1)
`make install` fails exactly as GUIDE_WITHOUT_DOCKER's troubleshooting row says (`ensurepip is not available`); with `uv venv --seed .venv` first it installs. `run_pravaha_web.py --server.port=27471 --engine.url=grpc://localhost:29490` → Uvicorn on 27471 against that engine.

### QI-196 — PASS (NOTE)
Python snippet → `750`. The Java snippet compiles verbatim against the `-all` jar and runs, printing nothing: it queries the view it has just registered before the view has filled.

### QI-197 — FAIL (STALEDOC-1)
"What is not built": "Kafka and Redis plugins | Not built" — `pravaha-plugin-kafka` is in the server jar and the compose seed uses it; "Spring Boot starter | not built" — `pravaha-spring-boot-starter-2.0.1-SNAPSHOT.jar` builds (38 classes, major 69); clustering "Deferred (ADR-034)" while ADR-039 puts it on the GA road.

### QI-198 — FAIL (SEEDWINDOW-1, CONSOLEREADY-1, STALEDOC-1)
GUIDE_WITH_DOCKER run with the project renamed: §4 matches (ownership, modes); §5/§6 healthy, but `curl /health/ready` on the console is 503 not 200; §7 `spend_per_minute` 0 rows not 10; §7a–§7d (container CLI, host CLI, psql — host psql rather than a container) answer as shown; §9 recovery and `docker diff` match; §10 matches (15 rules, four dashboards); §11 `down -v` removed everything named `pravaha-qa-*`. §3 Route B's `build.sh` and Route A's `docker build .` and §8 `docker-test.sh` were **not run** (the 2.0.0 images were already built; §8 is the full suite). Version strings in §3 say 1.0.1-SNAPSHOT.

### QI-199 — PASS
Class files: `pravaha-api` 62 × major 69, `pravaha-sdk-java` 11 × 69, `pravaha-sdk-java-flight` 28 × 69 (`-all` 101 × 69), every Pravaha class in the server jar 1 362 × 69. CLI exit codes (QI-080), container layout (QI-140), OpenAPI lock (QI-051), on-disk state from 1.0.0 (QI-161) hold.

### QI-200 — FAIL (STALEDOC-1)
COMPATIBILITY.md: "The Spring Boot starter | Java 25, and Spring Boot 3.4 or later"; QUICKSTART: "Spring Boot starter | ADR-020 planned it; not built".

### QI-201 — FAIL (STALEDOC-1)
`pravaha --help` has `explain-sql` (not in CLI.md's map, only in COMPATIBILITY's experimental list) and `subscribe --answer` (not in CLI.md's `subscribe` row); every command CLI.md lists exists.

### QI-202 — PASS
Every PRV code seen in this run (≈ 50: 1028 … 9002) is in TROUBLESHOOTING.md; the only `PRV-` literal in main Java source missing from it is `PRV-0400`, which appears only in comments explaining its removal (PRV0400-1).

---

## Automated checks kept

[`tests/qa/adv_surface/test_adv_surface.py`](../../../../tests/qa/adv_surface/test_adv_surface.py) — 22 tests,
all skipped unless pointed at a node (`PRAVAHA_QI_HTTP`, `PRAVAHA_QI_PGWIRE`, `PRAVAHA_QI_FLIGHT`). Against
the QI node: **11 passed, 11 skipped**. The nine that reproduce open defects (QI-004, 021, 031, 046 ×3,
054, 059, 071) are skipped with their IDs; with `PRAVAHA_QI_REPRODUCE=1` they run as strict xfails and
all nine failed as expected (`9 xfailed`). The two denial-of-service reproductions (QI-012, QI-045) are
additionally gated on `PRAVAHA_QI_DESTRUCTIVE=1` and were exercised by the scratch scripts this log
quotes, not through pytest. The module is not collected by any existing build or CI job.
