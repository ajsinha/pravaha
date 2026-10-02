# ADV-SURFACE — adversarial QA of Pravaha 2.0.0's external surfaces

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Surface under test: what a client, an operator or a packager touches — the PostgreSQL wire gateway,
the HTTP `/api/v1` surface, Arrow Flight, the `pravaha` and `pravaha-engine` command lines, the Python
and Java SDKs, the console, the Docker images and compose stack, the distribution tarball, the Helm
chart, upgrade from 1.0.0, connectors under failure, and the guides read as promises. Engine
correctness, durability and in-engine security semantics are a second QA agent's (not this file's).

Written **before** execution, on 2026-10-01, against `develop` at `e3ad67dc` (v2.0.0 plus the
2.0.1-SNAPSHOT bump). The log is [`../logs/ADV-SURFACE.md`](../logs/ADV-SURFACE.md).

Case IDs are `QI-nnn`. Every case names the document that promises the expected result; where no
document promises anything the expectation is "what a reasonable operator would expect" and a miss is
filed as a NOTE, not a FAIL.

Environment assumed by every case unless it says otherwise:

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    export TMPDIR=$HOME/.cache/pravaha-tmp MAVEN_OPTS=-Djava.io.tmpdir=$HOME/.cache/pravaha-tmp
    export PRAVAHA_CONFIG_DIR=$HOME/.cache/pravaha-qi/cli-config   # never the owner's ~/.config/pravaha
    QI=$HOME/.cache/pravaha-qi                                     # scratch, outside the repository
    # artefacts: a fresh clone built per GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER step 3, at $QI/pravaha

Reserved ports: node HTTP **28480**, Flight **29490**, pgwire **26432**, console **27470**; a second
node 28481/29491/26433; compose project `pravaha-qa` on 2848x/2949x/2643x/2747x. None of the owner's
ports (5432, 9092, 2181, 8080, 5050, 5672, 15672, 8161, 61616, 3000-3002, 55416, 55417) is used.

The "QI node" below is `bin/pravaha-server` in home mode (`PRAVAHA_HOME=$QI/home`) with profiles
`dev,users`, pgwire enabled on 127.0.0.1:26432, the catalogue enabled, a followed `txn` CSV stream,
heap `-Xmx1g`, and views `by_user` (keyed, default tenant) registered as `admin`. Users `ann`
(role `reader`) and `bob` (no roles) are created through the API at setup.

---

## Area PGW — the PostgreSQL wire gateway, hostile clients

### QI-001 — psql 18 connects, `\d`, `\d by_user`, a read
**Intent:** The tested-client list names `psql`; psql 18 is newer than any version it was tested with
and sends newer catalogue queries.
**Steps:** `PGPASSWORD=$TOKEN psql "host=127.0.0.1 port=26432 dbname=pravaha user=admin"` then `\d`,
`\d by_user`, `SELECT * FROM by_user`, `\dt`, `\dn`, `\l`, `\conninfo`.
**Expected:** Connects (server `9.4.26 (Pravaha)`), `\d` lists `by_user`, `\d by_user` shows its
columns, the read returns rows. Meta-commands the shim does not answer fail with an error, not a
dropped connection. *Promise:* pgwire help topic, "At a glance" and "Finding the views".

### QI-002 — the account password is refused, a session token and an API key are accepted
**Steps:** psql with `PGPASSWORD=<admin's password>`; then a `prv_s_` session token; then a `prv_k_` key.
**Expected:** password → `28P01`; token and key → connected. *Promise:* pgwire topic, PGWIREPASS-1.

### QI-003 — a revoked key and an ended session are refused at the next connection
**Steps:** create key, connect OK, revoke (`DELETE /api/v1/keys/{id}`), reconnect; same with a session
ended by `POST /api/v1/auth/logout`.
**Expected:** `28P01` both times. *Promise:* pgwire topic ("nor is a revoked or expired key").

### QI-004 — an open connection after its session ends
**Intent:** A connection authenticated with a session token stays open after logout; does it keep
reading?
**Steps:** psql connected with a session token; `POST /auth/logout` that token from elsewhere; run a
`SELECT` on the still-open connection.
**Expected:** Undocumented. Either refused (preferred) or allowed. Allowed is a NOTE (credential checked
only at sign-in) unless a doc says otherwise.

### QI-005 — user name differs from credential's principal
**Steps:** psql `user=dana` with admin's token.
**Expected:** `NOTICE: connected as 'admin', not 'dana' …`. *Promise:* pgwire topic.

### QI-006 — `sslmode=require` against a gateway with no TLS
**Steps:** `psql "sslmode=require …"`; then `sslmode=prefer`.
**Expected:** require → client error "server does not support SSL"; prefer → plaintext connect.
*Promise:* pgwire topic ("answers every `SSLRequest` with 'no'").

### QI-007 — `gssencmode=require`
**Expected:** declined; psql fails cleanly with a GSS message, server survives.

### QI-008 — protocol 2 / unknown protocol in the startup packet
**Steps:** raw socket: startup with protocol code `0x00020000`; then `0x00040000`.
**Expected:** ErrorResponse naming protocol 3.0 support (`0A000`), connection closed, server alive.

### QI-009 — oversized startup packet (length 10 001, and 2^31-1)
**Expected:** Refused with a protocol-violation error, no allocation proportional to the claim; server alive.
*Promise:* `PgFrontend.MAX_STARTUP_BYTES` (code), no doc.

### QI-010 — five SSLRequests and no startup
**Expected:** refused after 4 with "more than 4 encryption requests". *Promise:* code comment.

### QI-011 — a client that connects and says nothing
**Expected:** closed after the 10 s handshake deadline; no thread or socket leak over 50 such sockets
(thread count back to baseline after 15 s).

### QI-012 — pre-authentication memory: password message declaring 16 MiB, never sent
**Intent:** The frontend allocates `new byte[length-4]` before reading. An unauthenticated client can
declare a 16 MiB `PasswordMessage` and trickle nothing. N connections → N × 16 MiB of heap before the
10 s deadline.
**Setup:** QI node, `-Xmx1g`.
**Steps:** open 100 sockets in parallel; on each send a valid startup, wait for `AuthenticationCleartextPassword`,
send `p` + length 16 777 216 and then nothing; keep them open; meanwhile poll `/actuator/health`
and run a Flight read.
**Expected (reasonable):** the node keeps serving; it must not reach `OutOfMemoryError`. An OOM, or
the engine failing other work, is a pre-auth denial of service → FAIL (no doc promises a limit; the
gateway is documented as safe to expose with TLS).

### QI-013 — post-authentication 16 MiB message (authenticated client)
**Steps:** authenticated socket sends `Q` declaring 16 MiB of SQL then sends it.
**Expected:** parsed and refused as SQL error (or accepted), connection survives or is closed with an
ErrorResponse; node alive.

### QI-014 — message length below 4 / negative
**Expected:** protocol violation (`08P01`), connection closed, node alive.

### QI-015 — unknown message type after authentication (`z`)
**Expected:** ErrorResponse `08P01`, closed or continues; no stack trace in the client message.

### QI-016 — extended protocol out of order: Execute an unnamed portal that was never bound
**Steps:** raw: `E` (portal "") then `S`.
**Expected:** ErrorResponse (`34000` invalid cursor name or equivalent), then `ReadyForQuery`; connection usable.

### QI-017 — Bind with a parameter count that does not match Parse
**Expected:** `08P01`/`22023`-class error, then RFQ after Sync; usable afterwards.

### QI-018 — Parse a statement name twice (named statement redefined without Close)
**Expected:** PostgreSQL says `42P05 prepared statement "s1" already exists`; an equivalent refusal is PASS.

### QI-019 — psycopg 3 default mode: failed statement inside a transaction then a read
**Steps:** `conn = psycopg.connect(...)` (autocommit off), `cur.execute("SELECT nope FROM by_user")`
→ error; `cur.execute("SELECT 1")`; then `conn.rollback()` and read again.
**Expected:** second statement `25P02` (in failed transaction), after rollback the read works.
*Promise:* GUIDE_WITHOUT_DOCKER §12 troubleshooting, pgwire topic "Transactions".

### QI-020 — `COMMIT` inside a failed block reports `ROLLBACK`
**Expected:** PostgreSQL answers `ROLLBACK` as the command tag for COMMIT in a failed block. Doc says
"PostgreSQL's tags and transaction status".

### QI-021 — writes are refused with the documented code
**Steps:** `INSERT INTO by_user VALUES ('x',1)`, `CREATE TABLE t(a int)`, `DELETE FROM by_user`,
`SELECT STREAM … ` (a continuous-query statement), `CREATE ALERT …`.
**Expected:** refused; continuous-query statements `PRV-6211` with SQLSTATE `25006`. *Promise:* pgwire topic.

### QI-022 — a cancel request with a random key
**Expected:** silently closed, no reply; server alive. *Promise:* code (no cancel support).

### QI-023 — psql `\timing` + Ctrl-C (cancel) during a long result
**Expected:** cancellation not supported; documented? The cancel is a no-op and the query completes;
the session remains usable. NOTE if undocumented.

### QI-024 — unicode identifiers and values
**Setup:** register a view with a unicode alias (`SELECT user_id AS "名前", amount AS "montant_€" FROM txn`),
a value with emoji in the CSV.
**Steps:** read it over psql and psycopg; `\d` it.
**Expected:** names and values round-trip byte-exact in UTF-8; `client_encoding` reported `UTF8`.

### QI-025 — `client_encoding=LATIN1` requested
**Expected:** either honoured or refused clearly; never mojibake silently. NOTE if accepted and ignored.

### QI-026 — a wide result: 2 000 columns? / long text values (1 MiB)
**Steps:** a view with a 1 MiB string value; read via psql.
**Expected:** returned intact.

### QI-027 — `MAX_RESULT_ROWS` over pgwire
**Steps:** a view with more rows than `ViewQuery.MAX_RESULT_ROWS`; `SELECT *`.
**Expected:** an error naming the bound, or a truncation that is stated — never silent truncation.
*Promise:* design §17 via pgwire package-info.

### QI-028 — 300 concurrent authenticated connections
**Steps:** psycopg, 300 threads each connect + read + hold 5 s.
**Expected:** all succeed or excess refused with `53300 too_many_connections`; node healthy; threads
released afterwards.

### QI-029 — BI catalogue queries: pgjdbc `getTables`/`getColumns`, information_schema, `pg_type`
**Steps:** psycopg queries that DBeaver/pgjdbc send: `SELECT * FROM pg_catalog.pg_type`,
`SELECT * FROM information_schema.tables`, `information_schema.columns WHERE table_name='by_user'`,
`SELECT current_schema(), version()`, `SHOW server_version`, `SHOW TRANSACTION ISOLATION LEVEL`,
`SET application_name='x'`, `SELECT pg_backend_pid()`, `SELECT * FROM pg_settings`.
**Expected:** the documented ones answer; undocumented ones fail with an error, not a disconnect.

### QI-030 — catalogue queries filtered by tenant / grant
**Steps:** as `bob` (no read on `by_user`): `\d`, `information_schema.tables`, `pg_class`.
**Expected:** `by_user` absent. *Promise:* pgwire topic ("filtered by what you may read").

### QI-031 — pgjdbc (Java 25) prepared statement with a bound parameter, autocommit off
**Expected:** works. *Promise:* pgwire topic tested-client list.

### QI-032 — SQL injection through a bound parameter
**Steps:** psycopg `cur.execute("SELECT * FROM by_user WHERE user_id = %s", ["x' OR '1'='1"])`.
**Expected:** zero rows (the value is bound, not pasted).

## Area HTTP — `/api/v1` and the actuator

### QI-040 — every `/api/v1` path anonymously on a `users` node
**Steps:** curl each OpenAPI path (GET or a POST with `{}`) without a credential.
**Expected:** `401` with an ApiError `PRV-7001` on every path except those documented open
(`/api/v1/auth/login`, `/auth/reset/redeem`, `/api/v1/status`?). *Promise:* SECURITY.md "The three seams" table.

### QI-041 — a forged / malformed bearer token
**Steps:** `Authorization: Bearer prv_s_AAAA`, `Bearer ` (empty), `Basic YWRtaW46eA==`, `Bearer` + 10 KB.
**Expected:** `401 PRV-7001`, message says only that the credential was not accepted. *Promise:* SECURITY.md "Telling failures apart".

### QI-042 — a plain user on admin endpoints
**Steps:** as `ann`: `GET /api/v1/users`, `POST /api/v1/users`, `GET /api/v1/audit`, `GET /api/v1/sessions?all`,
`POST /api/v1/lanes/rebalance`, `GET /api/v1/keys/report`.
**Expected:** `403 PRV-7002` on each.

### QI-043 — malformed JSON bodies
**Steps:** `POST /api/v1/queries/validate` with `{`, `[]`, `"str"`, `{"sql": 5}`, `null`, a 0-byte body.
**Expected:** `400` ApiError with a `PRV-` code, no Jackson class names or stack traces in the body.

### QI-044 — wrong content type
**Steps:** the same POST with `Content-Type: text/plain`, `application/xml`, `multipart/form-data`.
**Expected:** `415` ApiError (one error shape, per application.yaml's comment "every non-2xx response is an ApiError").

### QI-045 — a 50 MiB JSON body
**Steps:** `POST /api/v1/queries/validate` with `{"sql":"SELECT <50 MiB>"}` authenticated; also
anonymous to `/api/v1/auth/login`.
**Expected:** refused with `413` or a validation error quickly; node heap stable. Anonymous large body
must not be read fully before auth. NOTE if accepted but slow.

### QI-046 — path traversal and odd characters in path names
**Steps:** `GET /api/v1/queries/..%2f..%2fetc%2fpasswd`, `/api/v1/views/%2e%2e`, `/api/v1/streams/a%00b`,
`/api/v1/catalog/objects/..;/x`, `/api/v1/queries/` + 5 000 chars.
**Expected:** `400`/`404` ApiError, never a file, never 500.

### QI-047 — method confusion
**Steps:** `DELETE /api/v1/status`, `PUT /api/v1/queries`, `TRACE /api/v1/status`, `OPTIONS /api/v1/queries`.
**Expected:** `405` ApiError (TRACE disabled), `Allow` header.

### QI-048 — CORS: a hostile `Origin`
**Steps:** `curl -H 'Origin: https://evil.example' -H 'Access-Control-Request-Method: POST' -X OPTIONS /api/v1/queries/validate`
and a GET with `Origin`.
**Expected:** no `Access-Control-Allow-Origin` for an arbitrary origin.

### QI-049 — error leakage: unknown path, a 500
**Steps:** `GET /api/v1/nope`, `GET /nope`, `GET /api/v1/queries/x/plan` for an unknown query,
`GET /error`.
**Expected:** ApiError JSON; no stack trace, no exception class, no Spring whitelabel page.

### QI-050 — other tenants' names do not leak through errors
**Setup:** a view `secret_acme` in tenant `acme` (static token or user in that tenant).
**Steps:** as a default-tenant reader: `GET /api/v1/queries/secret_acme`, `/views/secret_acme`,
`/queries`, `/catalog/objects?q=secret`, a validate of `SELECT * FROM secret_acme`.
**Expected:** each answers as for an unknown name; no `acme` or `secret_acme` in any body.
*Promise:* SECURITY.md "Metadata is data", "Tenants".

### QI-051 — OpenAPI document vs the lock and vs behaviour
**Steps:** `GET /api/v1/openapi.json` (anonymous and authenticated); diff paths against `api/openapi.lock.json`;
`GET /api/docs` (Swagger UI).
**Expected:** document served (or refused consistently); paths equal the lock. Whether the docs are
open to anonymous callers is a NOTE.

### QI-052 — a path in the lock that is not under `/api/v1`: `GET /status`
**Expected:** documented, answers.

### QI-053 — actuator surface anonymously
**Steps:** `/actuator`, `/actuator/health`, `/actuator/health/liveness`, `/readiness`, `/actuator/info`,
`/actuator/metrics`, `/actuator/metrics/jvm.memory.used`, `/actuator/prometheus`, `/actuator/env`,
`/actuator/heapdump`.
**Expected:** probes open (status only, no details), `env`/`heapdump` not exposed, prometheus and
metrics require the prometheus token or a principal (OPERATIONS.md "Watching a running node").

### QI-054 — login brute force lockout over HTTP
**Steps:** 6 bad `POST /api/v1/auth/login` for `ann` within a minute, then the right password.
**Expected:** locked after 5 for 30 min (PRV-70xx), and the response does not tell a stranger whether
the account exists. *Promise:* SECURITY.md table "5 failures in 15 minutes lock the account for 30".

### QI-055 — user enumeration via login
**Steps:** login as a non-existent user vs an existing user with a wrong password; compare status,
body and timing (20 tries each, median).
**Expected:** identical status and message; timing within the same order of magnitude.

### QI-056 — password policy at user creation and change
**Steps:** `POST /api/v1/users` with password `short`, `alllowercaseletters`, `Abcdefghijk1` (12, 3 kinds).
**Expected:** first two refused `PRV-70xx` naming the rule, third accepted.

### QI-057 — expired session token
**Steps:** a node with a session idle timeout shortened (if configurable) or a token ended; use it.
**Expected:** `401 PRV-7001`.

### QI-058 — attributes validation over HTTP
**Steps:** `PUT /api/v1/users/ann/attributes` with `{"via":"x"}`, a 257-char value, a value with `\n`,
33 attributes.
**Expected:** each `PRV-7020`. *Promise:* SECURITY.md STORECLAIMS-1 paragraph.

### QI-059 — `POST /api/v1/streams` with a hostile name
**Steps:** declare streams named `../x`, `a b`, `<script>`, `""`, 300 chars, `SELECT`.
**Expected:** refused with a validation code, or accepted only if a valid SQL identifier; nothing written outside data/.

### QI-060 — HTTP request with a huge header (64 KiB) / many headers
**Expected:** `431`, node alive.

## Area FLT — Arrow Flight

### QI-070 — an unknown action type
**Steps:** pyarrow.flight `do_action(Action("nope", b""))` authenticated.
**Expected:** refused by name (an unknown action), per COMPATIBILITY.md "Clients and servers".

### QI-071 — an unknown ticket verb / garbage ticket
**Steps:** `do_get(Ticket(b"\x00\xff garbage"))`, `do_get(Ticket(b"NOPE:x"))`, a 1 MiB ticket.
**Expected:** `INVALID_ARGUMENT`-class error with a PRV code, never INTERNAL with a stack.

### QI-072 — every action and `list_flights` without a credential on a `users` node
**Steps:** `list_actions`, `list_flights`, `get_flight_info`, `do_get`, `do_action(each listed action)`, `do_put`, `do_exchange` anonymously.
**Expected:** `UNAUTHENTICATED` on each (except possibly `list_actions` — NOTE).

### QI-073 — a subscription to a view whose rows change fast, a client that never reads
**Steps:** subscribe to a busy view, read one batch, then stop reading for 60 s while 50 000 rows are appended.
**Expected:** node heap bounded (the documented `--overflow CONFLATE|DROP_OLDEST|FAIL` policy applies);
other clients unaffected.

### QI-074 — disconnect mid-stream
**Steps:** kill -9 a subscribing Python client; check the node's live-subscriber count returns.
**Expected:** subscription released (metric `pravaha_subscriptions` or console's live feeds back to 0).

### QI-075 — SDK `--reconnect` across a node restart
**Steps:** `pravaha subscribe --view by_user --reconnect --reconnect-timeout 60`; restart node; append a row.
**Expected:** the CLI reconnects and prints the new change. *Promise:* CLI.md command table.

### QI-076 — a very large `list_flights` / `queries` (500 registered views)
**Expected:** answered within seconds; CLI prints all.

### QI-077 — `do_put` to a view (not a stream) / to an undeclared stream
**Expected:** refused by name.

## Area CLI — the `pravaha` command line, the SDKs, `pravaha-engine`

### QI-080 — every documented exit code
**Steps:** unknown command (2), missing flag (2), `--url grpc://127.0.0.1:1` (3), engine refusal (1),
`--token x` over plaintext without insecure (2, PRV-1031), `health` against a down node (3), Ctrl-C (130).
**Expected:** as CLI.md "Output and exit codes".

### QI-081 — `--json` puts exactly one JSON document on stdout and errors as JSON on stderr
**Steps:** `pravaha --json queries`, `pravaha --json query --sql 'bad'`.
**Expected:** stdout parses as JSON; failure → stdout empty, stderr `{"error": {...}}`.

### QI-082 — options before and after the command
**Steps:** `pravaha queries --url …` vs `pravaha --url … queries`.
**Expected:** identical. *Promise:* CLI.md "accepted before or after the command".

### QI-083 — destructive commands without `--yes` change nothing, exit 0
**Steps:** `drop`, `key revoke`, `user disable`, `lanes rebalance`, `revoke`, `policy drop`, `alert drop` without `--yes`.
**Expected:** prints what would happen, exit 0, state unchanged. *Promise:* CLI.md.

### QI-084 — `login --save` file and dir modes, `logout` deletes it
**Expected:** token `0600` in a `0700` directory under `$PRAVAHA_CONFIG_DIR`; logout removes it and
ends the session on the engine (the token then gets 401).

### QI-085 — `PRAVAHA_CONFIG_DIR` precedence over `XDG_CONFIG_HOME`
**Expected:** as CLI.md.

### QI-086 — `validate`/`explain`/`run` with `--schema` asked of `pravaha`
**Expected:** usage error naming `pravaha-engine`, exit 2. *Promise:* CLI.md.

### QI-087 — `pravaha health` exit status
**Expected:** 0 when UP/DEGRADED, 1 otherwise, 3 when nothing answers.

### QI-088 — hostile inputs: `--params` with commas and quotes, `--sql-file /nonexistent`, `--keys a`, `--timeout -1`, `--limit 0`
**Expected:** usage errors exit 2 with a message; no traceback (unless `PRAVAHA_CLI_TRACE=1`).

### QI-089 — `pravaha version` and `--client`
**Expected:** CLI version 2.0.x; server version from `/api/v1/status`.

### QI-090 — CLI without pyarrow (bare wheel)
**Steps:** fresh venv, `pip install <wheel>` (no extras); `pravaha status` and `pravaha queries`.
**Expected:** HTTP command works; Flight command says `pip install "pravaha[flight]"`, exit 2.

### QI-091 — Python SDK import without pyarrow
**Steps:** `python -c "import pravaha; from pravaha import connect; print(pravaha.__version__)"`;
`connect(...)`.
**Expected:** import works; `connect` raises a typed error naming the extra.

### QI-092 — `tools/build-sdk.sh` end to end
**Expected:** builds Java SDK jars, the `-all` jar, wheel and sdist into `target/sdk-dist/`, exit 0.

### QI-093 — `tools/sdk-standalone-check.sh` end to end (against the 2.0.0 image)
**Expected:** four clients read a view, exit 0, container removed.

### QI-094 — the Java SDK `-all` jar with plain `java` 25
**Steps:** a one-file Java program compiled against the `-all` jar, `java -cp all.jar:. Main` reading `by_user`.
**Expected:** works without extra `--add-opens` beyond what the README says.

### QI-095 — the Java SDK on Java 21
**Steps:** the same program with `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`.
**Expected:** refused: `UnsupportedClassVersionError … class file version 69.0` (COMPATIBILITY.md says Java 25).
Whether the message is "clear" is a NOTE.

### QI-096 — `pravaha-engine` offline subcommands with bad input
**Steps:** `run` with `--in` missing, `--schema "x:NOTATYPE"`, `--out` into an unwritable dir, `--sql ''`,
`validate` of a join (one stream known), `explain --level nonsense`, `version`.
**Expected:** each a PRV code and a non-zero exit, no stack trace.

### QI-097 — `pravaha-engine --help`
**Expected:** exits 0 and lists `validate`, `explain`, `run`, `version`. *Promise:* QUICKSTART §1.

### QI-098 — `bin/pravaha-engine` on JDK 21
**Expected:** refuses by name: *"Pravaha 2.x requires Java 25"*. *Promise:* GUIDE_WITHOUT_DOCKER §1.

### QI-099 — CLI `subscribe --limit N --json` output shape
**Expected:** JSON lines, `change` and `commit` objects; exits 0 after N.

### QI-100 — CLI `user attrs` / `catalog` / `grant` / `alerts` against a node without the catalogue
**Expected:** a clear refusal naming `pravaha.catalog.enabled`, exit 1.

### QI-101 — CLI error output for an engine refusal points to the docs
**Steps:** `PRAVAHA_DOCS_BASE_URL=https://docs.example pravaha query --sql 'SELECT * FROM nope'`.
**Expected:** `PRV-nnnn` code, message, then a link. *Promise:* CLI.md exit-code table row 1.

## Area CON — the console

### QI-110 — every page route anonymously
**Steps:** GET every console route without a cookie.
**Expected:** landing, about, help, tutorials, health, login answer 200; every engine-reading or
account/admin page redirects to `/login` (or 401 for API). *Promise:* SECURITY.md "The console acts as the person signed in".

### QI-111 — console `/api/v1/*` anonymously
**Expected:** 401 JSON for engine-backed ones; `/api/v1/health` open.

### QI-112 — plain user on admin pages
**Steps:** sign in as `ann`; GET `/admin`, `/admin/users`, `/admin/keys`, `/admin/audit`; POST `/admin/users`.
**Expected:** refused (403 or engine refusal shown), never the data.

### QI-113 — CSRF: state-changing POST without the token / with another session's token
**Steps:** as admin, `POST /queries/by_user/pause` without `csrf_token`; with a token from ann's session.
**Expected:** 403, state unchanged. *Promise:* SECURITY.md console paragraph.

### QI-114 — CSRF via cross-site form: SameSite=Lax cookie check
**Steps:** inspect `Set-Cookie` on login.
**Expected:** `HttpOnly; SameSite=Lax` (and `Secure` only over https).

### QI-115 — open redirect via `/login?next=`
**Steps:** sign in with `next=https://evil.example`, `next=//evil.example`, `next=/\evil.example`,
`next=javascript:alert(1)`, `next=%2F%2Fevil.example`.
**Expected:** redirect to a local path only.

### QI-116 — session fixation
**Steps:** take an anonymous session cookie, sign in, compare cookie values.
**Expected:** a new session value after sign-in.

### QI-117 — logout invalidates server-side
**Steps:** sign in, copy cookie, `POST /logout`, replay the copied cookie on `/overview`.
**Expected:** redirected to `/login` (engine session ended); sign-out lands on the landing page.

### QI-118 — `GET /logout`
**Expected:** not a state change via GET (405 or a confirmation), so an `<img src=/logout>` cannot sign someone out. NOTE otherwise.

### QI-119 — XSS through a view name
**Steps:** register a view whose name is as hostile as the engine allows (e.g. quoted identifier with `<img src=x onerror=alert(1)>` if allowed); open `/queries`, `/queries/{name}`, `/views`, `/overview`.
**Expected:** escaped in HTML; no script execution (check rendered HTML source).

### QI-120 — XSS through a column value
**Steps:** a CSV row with `<script>alert(1)</script>` and `"><svg onload=alert(1)>` as `user_id`; view the tail on
`/queries/by_user` and `/views/by_user`, and the workbench result.
**Expected:** escaped.

### QI-121 — XSS through an error message
**Steps:** workbench `SELECT '<script>alert(1)</script>' FROM nope`; `/help/search?q=<script>`;
`/queries/<script>alert(1)</script>`.
**Expected:** escaped.

### QI-122 — XSS through an alert name / stream name / catalogue comment
**Steps:** `catalog comment by_user '<img src=x onerror=alert(1)>'`; a stream declared with a hostile
schema column name if accepted; open `/catalog/...`.
**Expected:** escaped.

### QI-123 — login brute force through the console
**Steps:** 6 wrong passwords for `bob` via `POST /login`.
**Expected:** the engine's lockout surfaces as a message; no indication whether the user exists.

### QI-124 — security headers
**Steps:** inspect a page's response headers.
**Expected (reasonable):** `Content-Security-Policy`, `X-Content-Type-Options: nosniff`,
`X-Frame-Options`/`frame-ancestors`, `Referrer-Policy`. NOTE where absent.

### QI-125 — `/metrics` on the console anonymously
**Expected:** requires its token (`metrics.token`) or is documented open.

### QI-126 — 15 help examples run verbatim
**Steps:** pick 15 runnable examples from `console/content/topics/*.md` (CLI and SQL) and run them
against the QI node exactly as printed.
**Expected:** each works, or its failure is explained by a missing fixture the page names.

### QI-127 — sign-out goes to the landing page
**Expected:** `POST /logout` → `303` to `/`.

### QI-128 — other tenant's view through the console
**Steps:** signed in as a default-tenant user, open `/queries/secret_acme`, `/views/secret_acme/live`.
**Expected:** not found, no data, no tenant name.

### QI-129 — `/preferences/role` and `remember_landing` as an anonymous POST
**Expected:** CSRF refused or harmless.

### QI-130 — help page path traversal
**Steps:** `/help/..%2F..%2Fetc%2Fpasswd`, `/help/topics/../../README`, `/help/decisions/..%2f..%2fLICENSE`,
`/about/papers/..%2F..%2Fconfig`, `/catalog/objects/..%2F..`.
**Expected:** 404; no file outside `content/` and `docs/`.

## Area OPS — images, compose, tarball, Helm, upgrade

### QI-140 — server image runs as non-root with a read-only root
**Steps:** `docker run --read-only` the 2.0.0 server image with a bind-mounted `/opt/pravaha` owned by
the invoking uid, `--user $(id -u):$(id -g)`.
**Expected:** starts, healthy; every file it creates is the invoking user's; `docker diff` empty
except mount points. *Promise:* RUNNING_IN_DOCKER "Any uid, and who owns what", "Nothing outside /opt/pravaha".

### QI-141 — server image `bin/pravaha-health`
**Expected:** exit 0 when ready, non-zero when not.

### QI-142 — server image with an unwritable data volume
**Steps:** bind `/opt/pravaha/data` from a directory owned by root (or `chmod 0500`).
**Expected:** refuses at start with a clear message naming the path; not a stack trace loop.

### QI-143 — server image with a configuration that does not parse / names a missing plugin
**Expected:** exits non-zero naming the problem (`PRV-5090` for a plugin).

### QI-144 — server image with no profile and no credentials
**Expected:** refuses to start (anonymous serving without acknowledgement). *Promise:* QUICKSTART §4.

### QI-145 — server image with `legacy-read`
**Expected:** refuses with `PRV-7004 … legacy-read was removed in 2.0`. *Promise:* COMPATIBILITY.md.

### QI-146 — the image's Java is 25 and the version string is 2.0.0
**Expected:** `java -version` 25; `/api/v1/status` version `2.0.0`.

### QI-147 — compose stack prepared by `tools/docker-env.sh --home` comes up healthy
**Steps:** `.env` with project `pravaha-qa` and remapped ports; `up -d --wait`.
**Expected:** three services healthy, files owned by the invoking user.

### QI-148 — compose: recreate the server container, queries and answers come back
**Expected:** registry recovered N of N; view rows identical. *Promise:* GUIDE_WITH_DOCKER §9.

### QI-149 — compose: `docker diff` of both containers
**Expected:** nothing but mount points. *Promise:* GUIDE_WITH_DOCKER §9.

### QI-150 — compose: a port clash
**Steps:** set `PRAVAHA_HTTP_PORT` to a port already bound.
**Expected:** compose reports `address already in use`; nothing else disturbed.

### QI-151 — compose `seed` profile end to end
**Expected:** orders_live and spend_per_minute register and answer as in §7.

### QI-152 — compose `observability` profile: Prometheus target up, rules loaded, Grafana dashboards
**Expected:** target `pravaha up`; 3 groups / 15 rules (§10); four dashboards.

### QI-153 — metrics endpoint correctness
**Steps:** read `/actuator/prometheus` with the prometheus token; check `pravaha_*` meters for the
registered queries; check `ObservabilityContractTest`'s names appear; `promtool check rules` on the shipped rules.
**Expected:** names present; rules reference only metrics that exist.

### QI-154 — compose `cdc` profile: Postgres and MySQL up, tables ready
**Expected:** healthy, CDC users exist.

### QI-155 — `tools/docker-env.sh` re-run keeps credentials; run as root refused
**Expected:** as its header.

### QI-156 — distribution tarball builds (`deploy/release/dist.sh`) and unpacks anywhere
**Expected:** a tarball; unpacked to a path with a space in it, `bin/pravaha-server` in home mode starts.

### QI-157 — tarball's `bin/pravaha-server` on JDK 21
**Expected:** refuses by name, non-zero exit, before the JVM throws `UnsupportedClassVersionError`.

### QI-158 — tarball home mode writes only under `PRAVAHA_HOME`
**Expected:** `find / -newer marker` (scoped to $HOME and /tmp) shows only `$PRAVAHA_HOME` paths.

### QI-159 — Helm: `deploy/helm/test.sh`
**Expected:** passes.

### QI-160 — Helm: odd values
**Steps:** `helm template` with `replicaCount=0`, `replicaCount=3`, `persistence.enabled=false`,
`image.tag=""`, a release name of 60 characters, `pgwire.enabled=true` with no TLS, empty `resources`.
**Expected:** each renders or fails with a `fail` message; nothing silently invalid (e.g. a name over 63 chars).

### QI-161 — upgrade: state written by 1.0.0 read by 2.0.0
**Setup:** run `pravaha/pravaha-server:1.0.0` on a scratch home with `users` + catalogue: register
`v_default` (default tenant), `v_acme` (tenant `acme`), an alert, a catalogue grant, a namespace, a user
and an API key; stop.
**Steps:** start `pravaha/pravaha-server:2.0.0` on the same home.
**Expected:** all queries recovered, `v_acme` still in `acme`, alert listed, grant present, user signs
in with the same password, API key still accepted. *Promise:* COMPATIBILITY.md "State on disk".

### QI-162 — upgrade: a 1.0.0 config with `legacy-read` on 2.0.0
**Expected:** `PRV-7004` refusal naming the fix.

### QI-163 — downgrade: 2.0.0 state read by 1.0.0
**Expected:** not promised; a clean refusal is PASS, silent corruption a NOTE/FAIL.

### QI-164 — 2.0.0 node with a 1.0.0 Python SDK / console against 2.0.0
**Expected:** COMPATIBILITY: "a 1.x Java SDK keeps speaking it"; same major only tested. NOTE only.

### QI-165 — the console image runs as the invoking uid, read-only
**Expected:** healthy; `docker diff` only the conf mount point.

## Area CONN — connectors under failure

### QI-170 — Postgres CDC: user without REPLICATION
**Expected:** `PRV-5112` naming the privilege. *Promise:* CONNECTORS.md (PGCDCPRIV).

### QI-171 — Postgres CDC: `wal_level=replica`
**Expected:** clear code naming `wal_level=logical`.

### QI-172 — Postgres CDC: slot dropped while running
**Expected:** the query fails or the source stops with a clear code; no silent skip of changes.

### QI-173 — Postgres CDC: publication altered to drop the table
**Expected:** a clear code / degraded; no silent data loss.

### QI-174 — MySQL CDC: binlog purged past the saved position
**Expected:** a clear code naming the purged binlog.

### QI-175 — MySQL CDC: `binlog_format=STATEMENT`
**Expected:** clear refusal.

### QI-176 — Kafka: broker restart mid-stream
**Expected:** source resumes; no loss or duplicate in the view.

### QI-177 — Kafka: topic deleted mid-stream
**Expected:** a clear state (DEGRADED, a code); not a silent forever-RUNNING with zero rows.

### QI-178 — Kafka: partitions added mid-stream
**Expected:** new partitions consumed.

### QI-179 — Kafka JSON schema drift (a field changes type)
**Expected:** DLQ or a PRV code naming the field; no silent zeroing.

### QI-180 — JDBC sink: target table column dropped while running
**Expected:** a clear sink failure code; rows not silently discarded.

## Area DOC — the guides as promises

### QI-190 — GUIDE_WITHOUT_DOCKER §3 build from a fresh clone
**Expected:** `./mvnw -q install -DskipTests` succeeds; the jar names printed in the guide exist.

### QI-191 — GUIDE_WITHOUT_DOCKER §7–§10 verbatim (home, console, CLI, psycopg, restart)
**Expected:** each output as printed.

### QI-192 — QUICKSTART §1 `pravaha-engine --help`, §2 run/mixed/DLQ, §3 refusal
**Expected:** outputs as printed.

### QI-193 — QUICKSTART §4 `pravaha-server --spring.profiles.active=dev &` then `pravaha queries`
**Expected:** "no continuous queries are registered".

### QI-194 — QUICKSTART §4 velocity.sql register with `--keys 1`, §5 query, §6 subscribe, §8 drop
**Expected:** as printed.

### QI-195 — QUICKSTART §7 console `make install`, `make run`, override flags
**Expected:** console on 17070 (we use the override form).

### QI-196 — QUICKSTART "From a program": Python snippet against a `dev` node
**Expected:** runs as printed.

### QI-197 — QUICKSTART "What is not built" table vs the tree
**Expected:** accurate (e.g. Kafka plugin, Spring Boot starter, clustering).

### QI-198 — GUIDE_WITH_DOCKER §3 Route B, §4, §5–§7, §9, §11 verbatim (with the project renamed)
**Expected:** outputs as printed; version strings current.

### QI-199 — COMPATIBILITY.md "Stable" claims testable here
**Steps:** Java baseline (class major 69 in every jar incl. pravaha-api), CLI exit codes (QI-080),
container layout (QI-140), OpenAPI lock (QI-051), error codes stable (TROUBLESHOOTING lists every code
`ErrorCode` defines), metrics names.
**Expected:** each claim holds.

### QI-200 — the Spring Boot starter claim (Boot 3.4+) vs "not built" in QUICKSTART
**Expected:** consistent documents.

### QI-201 — CLI.md command map vs `pravaha --help`
**Expected:** every listed command exists; no undocumented command.

### QI-202 — TROUBLESHOOTING: every PRV code the server can emit is documented
**Expected:** no `PRV-` code in `pravaha-*/src/main` missing from TROUBLESHOOTING.md.
