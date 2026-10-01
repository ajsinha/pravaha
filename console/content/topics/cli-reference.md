---
title: CLI reference
slug: cli-reference
category: reference
order: 50
icon: terminal
summary: "Every pravaha command and flag — query, register, subscribe, lifecycle, blue/green, dead letters, the debugger, status, lanes, audit, identity and the assistant — with output, --json, exit codes and its refusals; and pravaha-engine for SQL with no server."
badge: REFERENCE
audience: Developers
keywords: [cli, pravaha, pravaha-engine, command line, query, register, queries, subscribe, pause, resume, drop, replace, cutover, rollback, dlq, debug, status, health, streams, views, describe, plan, lanes, rebalance, audit, tenants, permissions, login, logout, whoami, user, key, session, version, ask, explain-sql, why, assist, "--url", "--http", "--token", "--insecure-token", "--json", "--yes", "--sql-file", "--params", "--filter", "--snapshot", "--answer", "--reconnect", PRAVAHA_URL, PRAVAHA_HTTP, PRAVAHA_TOKEN, NO_COLOR, exit code, alerts, alert, snooze, ack]
guide: quickstart
related: [clients, http-api, subscriptions, lanes, authentication]
---

`pravaha` is the command line for a **running engine**. It is written in Python on the Python SDK
and does nothing the SDK does not: reads, registrations, subscriptions, dead letters and the
debugger go over **Arrow Flight** (`--url`, port 19090) through the SDK's `Client`, and everything a
node answers over its **HTTP API** (`--http`, port 18080) — status, the catalogue, one query in
detail, lanes, audit, identity — through the SDK's `EngineApi`. Planning or running SQL with **no
server at all** is a different tool, the Java [`pravaha-engine`](#pravaha-engine-sql-with-no-server).

Every command name and flag of the earlier Java `pravaha` still works, with three differences:
`drop` (and the other destructive commands) now asks for `--yes`; the offline `validate`, `explain`
and `run` moved to `pravaha-engine`; and a table is aligned rather than tab-separated (`query --tsv`
keeps the old form).

## Install

```bash
pip install "pravaha[flight]"      # the CLI and the Flight transport (pyarrow)
pravaha --help
```

`pip install pravaha` without the extra installs the CLI too: every HTTP command works, and a Flight
command says `pip install "pravaha[flight]"` and exits `2`. From a checkout, `bin/pravaha` runs an
installed `pravaha` if there is one, and otherwise the CLI from `sdk/python` (with that directory's
`.venv` when it exists, else `python3`). `python -m pravaha.cli` is the same program.

## At a glance

| Command | Talks to | Does |
|---|---|---|
| `query` | Flight | Ask a question; also runs `CREATE`/`DROP`/`PAUSE`/`RESUME CONTINUOUS QUERY` and `SHOW CONTINUOUS QUERIES` |
| `register` | Flight | Register a continuous query with its key, sink and retention |
| `queries` | Flight | List the continuous queries you may see |
| `subscribe` | Flight | Print a view's committed changes as they happen |
| `pause`, `resume`, `drop` | Flight | Lifecycle (`drop` needs `--yes`) |
| `replace`, `replacements`, `cutover`, `rollback`, `abandon`, `finish`, `throttle`, `pause-backfill`, `resume-backfill` | Flight | Blue/green replacement |
| `dlq list`, `show`, `replay`, `count` | Flight (`count`: HTTP) | Dead letters |
| `debug fork`, `checkpoints`, `step`, `state`, `inspect`, `view`, `fixture`, `sessions`, `end` | Flight | The time-travel debugger |
| `status`, `health`, `version`, `metrics`, `plugins`, `sinks` | HTTP | The node |
| `streams`, `views`, `describe`, `plan` | HTTP | The catalogue, one query in full, a running plan |
| `validate`, `explain` | HTTP | Plan SQL against the node, which knows its own streams |
| `lanes`, `lanes rebalance` | HTTP | Where every query runs; an administrator's rebalance |
| `audit`, `tenants`, `permissions` | HTTP | Authorization decisions, quotas, what you may do |
| `catalog ls`, `search`, `namespaces`, `show`, `create-namespace`, `comment`, `tag`, `move`, `owner` | HTTP | The Pravaha Catalog: namespaces, owners, descriptions, tags (`owner` needs `--yes`) |
| `grant`, `revoke`, `grants`, `access why` | HTTP | Grants, and why a user may or may not (`revoke` needs `--yes`) |
| `policy ls`, `show`, `create-filter`, `create-mask`, `bind`, `unbind`, `drop` | HTTP | Row filters and column masks (`unbind` and `drop` need `--yes`) |
| `alerts ls`, `channels`, `show`, `pause`, `resume`, `snooze`, `ack` | HTTP | Alerts: what is firing, and quieting or acknowledging one |
| `alert create`, `alert drop` | Flight | `CREATE ALERT` / `DROP ALERT`, as `query --sql` would send them (`drop` needs `--yes`) |
| `login`, `logout`, `whoami`, `password`, `user`, `key`, `session` | HTTP | Identity |

`pravaha --help` lists every command; `pravaha <command> --help` prints one command's flags and exits
`0` without contacting anything. With no command, `pravaha` prints its usage and exits `2`.

## Configuration

Every command takes these, **before or after** the command name (`pravaha --json queries` and
`pravaha queries --json` are the same). For each, a flag beats an environment variable, which beats
the default.

| Flag | Environment | Default | |
|---|---|---|---|
| `--url` | `PRAVAHA_URL` | `grpc://localhost:19090` | The node's Flight endpoint. `grpc://` is plaintext; `grpc+tls://` (or no scheme) is TLS |
| `--http` | `PRAVAHA_HTTP`, then `PRAVAHA_ENGINE_HTTP` | `http://localhost:18080` | The node's HTTP API |
| `--token` | `PRAVAHA_TOKEN` | the saved token, if any | A bearer token: a session token from `login`, an API key, or a static token |
| `--insecure-token` | `PRAVAHA_INSECURE_TOKEN=true` | off | Allow the token over plaintext `grpc://` or `http://` — for loopback, or TLS ended by a local sidecar |
| `--timeout` | `PRAVAHA_TIMEOUT` | `30` | Seconds per request |
| `--json` | | off | Machine output (below) |
| `--no-color` | `NO_COLOR` | colour on a terminal | Colour is also off whenever stdout is not a terminal |

The token is looked for in order: `--token`, `PRAVAHA_TOKEN`, then the file `pravaha login --save`
wrote — `~/.config/pravaha/token` (or `$XDG_CONFIG_HOME/pravaha/token`, or `$PRAVAHA_CONFIG_DIR/token`),
mode `0600`. `pravaha whoami` says which one it used.

**A token is never sent over plaintext unless you say so.** Without `--insecure-token` a command
with a token and a `grpc://` or `http://` address is refused before anything is sent (PRV-1031,
exit `2`). The right fix is almost always `grpc+tls://` and `https://`.

### TLS

TLS is chosen by the scheme — `grpc+tls://` for Flight, `https://` for HTTP — and these say whom to
trust and what to present. They mirror the SDK's `TlsOptions`; a bad combination is refused with
PRV-1032.

| Flag | Environment | |
|---|---|---|
| `--tls-ca` | `PRAVAHA_TLS_CA` | A PEM CA certificate to trust (a private CA, a self-signed node) |
| `--tls-cert`, `--tls-key` | `PRAVAHA_TLS_CERT`, `PRAVAHA_TLS_KEY` | A client certificate and its key, for mutual TLS; both or neither |
| `--tls-trust-store`, `--tls-trust-store-password`, `--tls-trust-store-type` | `PRAVAHA_TLS_TRUST_STORE_PASSWORD` | A JKS or PKCS12 trust store instead of PEM (Flight only) |
| `--tls-key-store`, `--tls-key-store-password`, `--tls-key-store-type` | `PRAVAHA_TLS_KEY_STORE_PASSWORD` | A JKS or PKCS12 key store (Flight only) |
| `--tls-override-hostname` | | Check the certificate against this name rather than the URL's host |
| `--tls-no-verify` | | Verify nothing. For a test only; refused together with any certificate |

```bash
export PRAVAHA_URL=grpc+tls://engine.internal:19090
export PRAVAHA_HTTP=https://engine.internal:18080
export PRAVAHA_TLS_CA=/etc/pravaha/ca.pem
pravaha login --user ann --save
pravaha queries
```

## Output

By default a person reads it: aligned tables with upper-case headers, `-` for nothing, lists
comma-joined. **Stdout carries only the answer; notes, banners, hints and warnings go to stderr**, so
`pravaha queries > q.txt` holds only the table.

With `--json`, stdout is one JSON document — the SDK's answer, in the API's field names (a Flight
result's fields in `snake_case`, as the Python SDK names them; bytes as base64) — and nothing else.
`subscribe --json` writes **JSON lines**, one per change and one per commit. A failure in `--json`
mode is one JSON object on stderr:

```json
{"error": {"code": "PRV-8002", "message": "no continuous query is registered under 'x'", "exit": 1}}
```

(an HTTP failure adds `"status"`, the HTTP status).

## Exit codes

| Exit | Means |
|---|---|
| `0` | Done. Also: a destructive command without `--yes` that only printed what it would do, and `subscribe` stopped with Ctrl-C or `--limit` |
| `1` | **The engine refused.** Its code and message are on stderr, then a line saying where the code is explained. Also: `health` not `UP`/`DEGRADED`, `validate` of invalid SQL, a `dlq replay` in which a record failed again |
| `2` | **Usage.** An unknown command, a missing or malformed flag, a setting refused before anything was sent (PRV-1031, PRV-1032, PRV-1053), an offline command asked of this tool, a Flight command without pyarrow |
| `3` | **Unreachable.** Nothing answered at `--url` or `--http` (PRV-1040); the message names the address it tried |
| `130` | Interrupted |

A refusal prints the engine's own words:

```text
PRV-8002  no continuous query is registered under 'spend_by_hour'
  look PRV-8002 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
```

With `PRAVAHA_DOCS_BASE_URL` set, the second line is that base followed by the code.
`PRAVAHA_CLI_TRACE=1` adds the stack trace.

## Reading and registering

### `query`

```text
pravaha query (--sql <query> | --sql-file <path>) [--params a,b] [--tsv]
```

| Flag | | |
|---|---|---|
| `--sql` / `--sql-file` | one required | The statement, or a file holding it |
| `--params` | none | Values for the `?` placeholders, comma-separated and in order: an integer if it reads as one, then a decimal, otherwise text |
| `--tsv` | off | Tab-separated with a header row — the Java CLI's format |

```bash
pravaha query --sql "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?" --params u1
```

```text
user_id  window_end           spend
u1       1789808400000000000  4200
u1       1789812000000000000  1350
```

and `2 rows` on stderr. `NULL` prints as `NULL`; a timestamp prints as nanoseconds since the epoch,
UTC, as Arrow carries it. `--json` prints a list of objects. The read it ran:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?
```

`query` also runs the management statements:

```bash
pravaha query --sql "SHOW CONTINUOUS QUERIES"
```

```sql
SHOW CONTINUOUS QUERIES;
```

Refusals: PRV-4023 (not a registered view), PRV-2002 (the SQL does not plan), PRV-1053 (the SQL holds
a lone surrogate; refused before sending, exit `2`). A wrong number of `--params` is refused by the
SDK before anything is sent, exit `2`.

### `register`

```text
pravaha register --name <view> (--sql <query> | --sql-file <path>)
                 [--keys 0,1] [--sink <name>] [--retain PT24H]
```

| Flag | Default | |
|---|---|---|
| `--name` | required | The view's name — what reads will say after `FROM` |
| `--keys` | `0` | The view's key as **output-column ordinals**, comma-separated |
| `--sink` | none | A sink bound under `pravaha.sinks.<name>` on the node |
| `--retain` | the node's | ISO-8601 (`PT24H`, `P7D`) or `forever` |

`register` takes no parameters: `--param` and `--params` are a usage error (exit `2`), because the
register action cannot carry bound values and a dropped value would register a different query.

```bash
cat > spend.sql <<'SQL'
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
SQL
pravaha register --name spend_by_hour --sql-file spend.sql --keys 0,2 --retain P7D
```

```text
registered spend_by_hour  state=RUNNING  fingerprint=3f9c2a61d0b4  retain=P7D
```

and, on stderr, `a query with the same fingerprint is the same computation, shared`. The file's SQL:

```sql
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

The same registration by name rather than ordinal, which cannot drift when the `SELECT` list is
reordered (run it with `pravaha query --sql-file`):

```sql
CREATE CONTINUOUS QUERY spend_by_hour
    KEYED BY (user_id, window_end)
    RETAIN FOR P7D
AS
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

Refusals: PRV-2041 (the query revises its answer and the sink only appends), PRV-8010 (the output or
`--keys` do not match the sink's schema or key), PRV-2050 (a `GROUP BY` with no bound on its keys).

### `queries`

```text
pravaha queries [--verbose]
```

```text
NAME           STATE    FINGERPRINT   ROWS IN  SINK
spend_by_hour  RUNNING  3f9c2a61d0b4  1284551  spend_table
big_txn        PAUSED   9a01bc77e2f3  -        -
```

A `-` under `ROWS IN` means the count was withheld: your access to that view is row-filtered, and
its total is not yours to see (a note on stderr says so). `--verbose` adds `FEED`: `RUNNING`,
`PAUSED`, `STOPPED`, or `NONE` when nothing is bound. With nothing registered, stderr says
`no continuous queries are registered` and stdout is empty.

A query whose source failed mid-read is still `RUNNING`, so its state cell reads
`RUNNING (source stopped)` and a line on stderr says why without being asked
(`w10: source stopped with PRV-5040 reading ev#0 at …`). A sink that refused a batch is
**detached** — the query and its view carry on, nothing more is written — so its cell reads
`spend_table (detached)` and stderr carries `… detached with PRV-8009: …`. Neither is retried: fix the
cause, then drop and register again.

### `pause`, `resume`, `drop`

```text
pravaha pause|resume --name <view>
pravaha drop --name <view> [--yes]
```

`pause` and `resume` state the end you want, so pausing a paused query succeeds and changes nothing.
A failed or dropped query is refused with PRV-8003; an unknown name with PRV-8002.

**`drop` without `--yes` drops nothing.** It says what would happen — whether this is the
computation's last name (the computation and its view go too) or which other names share it (it
keeps running) — and exits `0`:

```text
would drop spend_by_hour  state=RUNNING  fingerprint=3f9c2a61d0b4
  it is the computation's last name, so the computation and its view go too
```

```bash
pravaha drop --name spend_by_hour --yes
```

```text
dropped spend_by_hour
```

The SQL forms:

```sql
PAUSE CONTINUOUS QUERY spend_by_hour;
RESUME CONTINUOUS QUERY spend_by_hour;
DROP CONTINUOUS QUERY spend_by_hour;
```

### `subscribe`

```text
pravaha subscribe --view <name> [--filter col=val[,col=val]]... [--snapshot] [--answer] [--limit N]
                  [--reconnect [--reconnect-timeout S]] [--buffer-rows N --overflow CONFLATE|DROP_OLDEST|FAIL]
```

| Flag | Default | |
|---|---|---|
| `--view` | required | The view to follow |
| `--filter` | none | Equality filters applied at the tap on the server; repeatable. A column the view lacks is refused with PRV-8002; a malformed pair is exit `2` |
| `--snapshot` | off | Print the view's rows first, then every commit after them — none missed and none counted twice. Without it the stream starts at the next commit |
| `--answer` | off | Print how the view's **answer** moves — rows a reader stops seeing at `-1`, rows a reader starts seeing at `+1` — instead of the changelog. For a keyed view that upserts, only these weights sum to the view |
| `--limit` | `0` (none) | Stop after this many rows, at the end of that commit |
| `--reconnect` | off | When the node restarts, open the stream again (backoff 0.25–10 s) instead of ending |
| `--reconnect-timeout` | `300` | Seconds without a stream before giving up; `0` never gives up |
| `--buffer-rows`, `--overflow` | the node's (10000, `CONFLATE`) | What the server's buffer does when you fall behind. `FAIL` ends the stream rather than lose a change |

```bash
pravaha subscribe --view big_txn --filter merchant=TRAVELCO --snapshot
```

```text
WEIGHT	txn_id	user_id	merchant	amount
+1	9001	u3	TRAVELCO	5200
-- snapshot at frontier 1789808400000000000, 1 row
+1	9004	u7	TRAVELCO	4800
-- commit, 1 row
```

Each change leads with its weight, always signed — `+1` a row arriving, `-1` a row withdrawn — so a
retraction never looks like the insert it withdraws. Lines are tab-separated, because a stream cannot
be aligned before it has ended. `subscribing to big_txn …` goes to stderr. If commits were dropped
because you fell behind, stderr says how many. After a reconnect, stderr says `-- reconnected`, and
with `--snapshot` the rows that follow are a fresh snapshot.

With `--json`, one object per line:

```json
{"type": "change", "weight": 1, "row": {"txn_id": 9004, "user_id": "u7", "merchant": "TRAVELCO", "amount": 4800}}
{"type": "commit", "rows": 1, "frontier": null, "droppedBefore": 0, "reconnected": false}
```

Ctrl-C ends it with exit `0`. Refusals: PRV-4023 (no such view), PRV-6105 (fell too far behind a
snapshot subscription; `--reconnect` recovers from it), PRV-6102 (a node too old for `--snapshot`),
PRV-4019 (the view was replaced at a cutover; subscribe again). See
[Subscriptions](/help/topics/subscriptions).

## Blue/green replacement

```text
pravaha replace --name <view> (--sql <query> | --sql-file <path>) [--keys 0,1]
                [--backfill history|none] [--rate-limit N] [--cutover manual|auto]
                [--rollback-retention PT1H] [--wait]
pravaha replacements [--name <view>]
pravaha cutover|rollback|pause-backfill|resume-backfill --name <view>
pravaha throttle --name <view> --rate N
pravaha abandon|finish --name <view> [--yes]
```

`replace` starts a new version beside the running one, backfilled from the source and spliced onto
the live stream; the name keeps answering the old version until `cutover`. `--wait` waits until the
backfill has caught up. `replacements` lists them: `NAME STATE HISTORY ROWS/S LIVE ROLLBACK`.
`rollback` puts the replaced version back while it is retained. `abandon` (end one that has not cut
over) and `finish` (release the replaced version; no rollback after) cannot be undone, so without
`--yes` they print the replacement and what would happen, and change nothing.

```bash
pravaha replace --name spend_by_hour --sql-file spend_v2.sql --keys 0,2 --wait
pravaha cutover --name spend_by_hour
pravaha finish  --name spend_by_hour --yes
```

Refusals: PRV-4014 (not caught up, or the two versions cannot meet at one position), PRV-4017 (no
replacement of that name, or a second one), PRV-4018 (an option this engine does not build, or a
source that cannot be replayed). See [Backfill and cutover](/help/topics/backfill-cutover).

## Dead letters

```text
pravaha dlq list   --name <view> [--offset N] [--limit N]
pravaha dlq show   --name <view> --id <id>
pravaha dlq replay --name <view> --id <id>[,<id>...]
pravaha dlq count  --name <view>
```

`list` prints the records a query's feed could not decode, newest first
(`ID WHEN CODE STREAM OFFSET BYTES STATE REASON`), then the totals and, when retention has evicted
some, how many are gone. A node with no `pravaha.dlq.directory` says so — that is not an empty queue.
`show` prints one whole, the record as it arrived (or why it is withheld from a row-filtered caller).
`replay` feeds chosen ones back through the query as **new rows at its current frontier, not a
rewind**; a record that fails again goes back on the queue under a new id (stderr names it) and the
command exits `1`. `count` is the depth without the records, over HTTP. Refusals: PRV-4091 (no such
dead letter). See [Dead letters](/help/topics/dead-letters).

## The debugger

```text
pravaha debug fork        --name <view> [--checkpoint <id>]    open a session; prints its id
pravaha debug checkpoints --name <view>                        which checkpoints to fork from
pravaha debug step        --session <id> [--step row|rows:N|commit|watermark:<nanos>|until:<col>:<op>:<value>]
pravaha debug state       --session <id>                       what state the fork holds
pravaha debug inspect     --session <id> --operator <id> [--key k] [--offset N] [--limit N]
pravaha debug view        --session <id>                       the fork's own answer
pravaha debug fixture     --session <id> --name <what it reproduces> [--out <path>]
pravaha debug sessions                                         every session you may see
pravaha debug end         --session <id>                       release the fork
```

A fork runs from a checkpoint with **every sink disabled** and nothing able to read its view; the live
query is untouched. `fork` prints the session id alone on stdout, so `S=$(pravaha debug fork --name x)`
works. `fixture` writes the session as a JUnit test (to `--out`, a `.java` file or a directory, or to
stdout). Refusals: PRV-8011 (no checkpoint), PRV-8012 (a source cannot be rewound), PRV-8014 (too many
sessions), PRV-8015 (a step or page it cannot make sense of). See
[The time-travel debugger](/help/topics/time-travel-debugger).

## The node

| Command | | |
|---|---|---|
| `pravaha status` | `GET /api/v1/status` | Instance, version, engine state, uptime, queries, streams, stopped feeds, the Flight address, and each plugin's health |
| `pravaha health` | `GET /actuator/health` | `UP`, `DEGRADED`, `OUT_OF_SERVICE` or `DOWN`, and the components. Exits `1` unless `UP` or `DEGRADED` (a feed stopped, every view still served) — usable as a probe |
| `pravaha version [--client]` | `GET /api/v1/status` | `pravaha <cli version>`, then `server <version>`; `--client` asks no node, and an unreachable node is a note, not a failure |
| `pravaha metrics [--grep TEXT]` | `GET /actuator/prometheus` | The Prometheus exposition, raw; `--grep` keeps the lines containing `TEXT` |
| `pravaha plugins` | `GET /api/v1/plugins` | Every plugin the node can load: kinds, compatible, loaded, health, the bindings you may see |
| `pravaha sinks` | `GET /api/v1/sinks` | The sinks the node binds: plugin, emit modes, retractions, guarantee, writers. Never a binding's options |

```bash
pravaha status
```

```text
instance       node-1
version        1.0.1
engine         RUNNING
uptime         5321 s
queries        4
streams        2
stopped feeds  0
flight         grpc://node-1:19090
```

## The catalogue and one query

```text
pravaha streams [list]
pravaha streams describe <stream>
pravaha streams declare <stream> --schema name:TYPE,... [--event-time <column>] [--out-of-orderness PT10S]
pravaha views [list]
pravaha views describe <view>
pravaha describe <query>          (or --name <query>)
pravaha plan <query>              (or --name <query>)
pravaha validate (--sql <query> | --sql-file <path>)
pravaha explain  (--sql <query> | --sql-file <path>) [--level physical|logical|codegen] [--graph]
```

`streams` lists the streams you may read with their event-time column, lateness, source and fields;
`declare` is an administrative act the node refuses to a principal who may not change what it serves.
`views` lists every view you may see — each is a registered query's — with its key, retention, sink
and fingerprint; `views describe` adds the schema. `describe` is one registered query in full:
state, fingerprint, **lane** (`dedicated`, `own`, or `shared #N`), key, retention, sink and whether it
is attached, rows in, when it was registered, the names sharing it, what it reads, its feed and every
stopped source with its code. `plan` is the plan it is running, one operator per row with rows in and
out, state bytes and its share of the time, then the edges and the bottleneck.

`validate` and `explain` plan against **the node**, which knows every stream — so a join or a lookup
join validates here, where the offline tool reports the other side as not found. An invalid query
prints each diagnostic (`PRV-2050  … (line 1, column 8)`) and exits `1`.

```bash
pravaha validate --sql "SELECT merchant, window_start, window_end, SUM(amount) AS spend
  FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
  GROUP BY merchant, window_start, window_end"
```

```text
valid  2154 us
  output: [merchant VARCHAR, window_start TIMESTAMP, window_end TIMESTAMP, spend BIGINT]
```

```sql
SELECT merchant, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end
```

A refusal, exit `1`:

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) FROM txn GROUP BY user_id
```

## Lanes

```text
pravaha lanes [list]
pravaha lanes rebalance [--yes]
pravaha lanes rebalance status
```

`lanes` prints the node's lane settings and every query's lane:

```text
mode auto, a lane each until 64, at most 16 per shared lane; 70 hosted, 64 on lanes of their own (2 dedicated)
NAME           STATE    LANE       SHARED LANE
spend_by_hour  RUNNING  dedicated  -
big_txn        RUNNING  shared     3
```

`lanes rebalance` **without `--yes` is a plan**: the queries it would move off shared lanes onto lanes
of their own while there is room, and nothing moves. With `--yes` it starts moving them, one at a
time; `lanes rebalance status` shows how the last or running one went. A rebalance is an
administrator's act and never automatic. See [Lane sharing](/help/topics/lanes#sharing-lanes).

## Governance

```text
pravaha audit [--since <instant>] [--until <instant>] [--principal P] [--view V]
              [--action A] [--decision allow|deny] [--limit N] [--cursor C]
pravaha tenants
pravaha permissions
```

`audit` is one page of the node's authorization decisions, newest first
(`SEQUENCE AT PRINCIPAL ACTION TARGET DECISION REASON`); stderr says how much the window holds and,
when there is more, the `--cursor` for the next page. Reading it is a permission of its own — a
principal the policy does not allow gets a 403 refusal (exit `1`), and the attempt is recorded.
`tenants` prints the admission quotas in force and each tenant's use and refusals (your own tenant
only, unless you may read the audit trail). `permissions` is what the policy lets **you** do: whether
you may register and read the audit trail, and for each view and stream whether you read it `full`
or `filtered` and may administer it. See [Audit](/help/topics/audit).

## The Pravaha Catalog and grants

```text
pravaha catalog ls [--namespace T.NS] [--kind VIEW|STREAM|SINK|NAMESPACE|SOURCE|LOOKUP]
pravaha catalog search <text>
pravaha catalog namespaces
pravaha catalog show <object>
pravaha catalog create-namespace <name> [--comment TEXT] [--if-not-exists]
pravaha catalog comment <object> <text>
pravaha catalog tag <object> key[=value] ... [--unset k1,k2]
pravaha catalog move <view> --namespace <ns>
pravaha catalog owner <object> --role R | --user U --yes
pravaha grant  <privileges> <object> --role R | --user U
pravaha revoke <privileges> <object> --role R | --user U --yes
pravaha grants --on <object> | --role R | --user U
pravaha access why <user> <object>
```

These need a node with the catalogue on (`pravaha.catalog.enabled`); a node without it answers
`PRV-7030` (exit `1`). An object is named as you would write it — `revenue`, `sales.revenue`, or in
full, `acme.sales.revenue`. `<privileges>` is a comma list — `SELECT,SUBSCRIBE` — or `ALL` for every
one that applies. `ls` and `search` show only what the engine lets you `USE`; a search never shows an
object you may not see. `show` prints the owner, description, tags, the grants you may see and what
you may do. `access why ana sales.revenue` prints, privilege by privilege, whether she may and
through which grant, role, namespace or ownership — for yourself, or for anyone on an object you
manage (`PRV-7033` otherwise). `revoke` and `catalog owner` take something away from somebody, so
they print what they would do and change nothing without `--yes`. See
[Catalog and grants](/help/topics/catalog-and-grants).

## Row filters and masks

```text
pravaha policy ls [--on <object>]
pravaha policy show <policy>
pravaha policy create-filter <policy> --as <predicate> [--except-role R]... [--comment TEXT]
pravaha policy create-mask   <policy> --column <c> --as <expression> [--except-role R]... [--comment TEXT]
pravaha policy bind   <policy> --on <object> | --tag key[=value]
pravaha policy unbind <policy> --on <object> | --tag key[=value] --yes
pravaha policy drop   <policy> --yes
```

A policy is defined, then bound: nothing is narrowed until `bind`. `--as` is the expression as SQL
would write it — `"region = session_attribute('region')"`, `"'XXXX-' || RIGHT(card, 4)"` — and the
engine refuses a subquery, a non-deterministic or unlisted function (`PRV-7038`). Binding to an
object needs `MANAGE` on it; to a tag, `MANAGE` on the tenant. `ls --on payments` lists what reaches
a view, directly or by one of its tags. `unbind` widens what an object shows and `drop` removes a
policy (refused with `PRV-7040` while it is still bound), so both change nothing without `--yes`.
See [Row filters and masks](/help/topics/row-filters-and-masks).

## Alerts

```text
pravaha alerts [ls]
pravaha alerts channels
pravaha alerts show <alert>
pravaha alerts pause <alert>
pravaha alerts resume <alert>
pravaha alerts snooze <alert> <duration>
pravaha alerts ack <alert> [--key 'sku=sku-100, warehouse=LDN']
pravaha alert create <alert> --on <view> --notify <channel>[,<channel>] [--where '<column> <op> <literal> AND ...']
                     [--severity info|warning|critical] [--fire-after D] [--clear-after D] [--dedupe D]
                     [--resend-every D] [--include c1,c2] [--print-sql]
pravaha alert drop <alert> [--if-exists] --yes
```

`alerts ls` lists the alerts you may see with how many keys are firing; `show` prints each key's
state, what the channels were last told and what they are owed, and the recent notifications with
their outcome. `pause`, `resume`, `snooze` and `ack` need `MODIFY` on the alert; a refusal exits `1`
with its code (`PRV-7002`, or `PRV-8040` for an alert you may not see). `alert create` and `alert drop`
write the statement and send it over Flight — the same as `pravaha query --sql 'CREATE ALERT ...'`.
A duration is `30s`, `10m`, `2h`, `1d` or `PT2H`. See [Alerts](/help/topics/alerts).

## Identity

The engine keeps its own users, passwords, API keys and sessions; these commands ask it. A password
you leave out is asked for without echo on a terminal, or read from stdin with `--password-stdin`
(`--new-stdin` for `password`), so it need not sit in shell history.

```text
pravaha login --user <name> [--password <p> | --password-stdin] [--save]
pravaha logout
pravaha whoami
pravaha password [--current <p>] [--new <p>]
pravaha password --reset-token <token> [--new <p>]
pravaha user [list]
pravaha user create <name> --roles a,b [--password <p>] [--tenant T] [--email E] [--display-name D] [--service]
pravaha user disable <name> [--yes] | enable <name> | roles <name> --roles a,b | reset <name>
pravaha user attrs <name> [KEY=VALUE ...] [--unset KEY ...]
pravaha key [list] [--all] | create <name> [--roles a,b] [--days N] [--for <user>] | rotate <keyId> | revoke <keyId> [--yes] | report
pravaha session [list] [--all] | end <id>
```

`login` prints the session token on stdout (for `--token` or `PRAVAHA_TOKEN`). With `--save` it
prints nothing secret: the token goes to the config file, mode `0600`, and every later command sends
it when no `--token` or `PRAVAHA_TOKEN` is set. `logout` ends that session on the engine and deletes
the file. An account that must change its password is told so on stderr.

```bash
pravaha --http https://engine.internal:18080 login --user ann --save
pravaha --http https://engine.internal:18080 whoami
```

```text
user        ann
principal   ann
tenant      acme
roles       admin,reader
via         session
token from  file
```

`user attrs` shows a user's attributes, or sets and removes some: `pravaha user attrs ann
region=EU desk=rates --unset team`. Attributes are facts about a person that every session and API
key of theirs carries as claims, which is what a policy's `session_attribute('region')` reads (see
[Row filters and masks](/help/topics/row-filters-and-masks)). The engine replaces the whole set, so
the command reads it, changes it and sends it back; a value may contain `=` (only the first one
splits), and `via`, `session`, `key` and `mustChangePassword` are the engine's own claims and are
refused (`PRV-7020`).

`user reset` prints a single-use reset token, once; its holder sets a password with
`pravaha password --reset-token <token> --new <password>`. `key create` and `key rotate` print the key
**once** on stdout (its id and expiry on stderr); a rotated key's predecessor works until the overlap
ends. `user disable` and `key revoke` print what would happen unless `--yes`. `user`, `key --all`,
`key report` and `session --all` need the admin role. See [Authentication](/help/topics/authentication).

## The assistant

Four commands ask a language model you configure — any provider, several at once — with the engine
as the judge: the model is given the engine's own catalogue, plan or refusal, and SQL it proposes is
validated by the engine before it is shown as working. The configuration is
`~/.config/pravaha/assist.json`; see [The assistant](/help/topics/assistant).

```text
pravaha ask "<description>" [--name N] [--repairs 0-3] [--register [--yes]] [--show-context]
            [--profile P] [--model ID]
pravaha explain-sql (--sql <sql> | --sql-file <path> | --query <name>) [--level physical|logical]
                    [--show-plan] [--profile P] [--model ID]
pravaha why PRV-nnnn [--sql <sql> | --sql-file <path>] [--no-check] [--profile P] [--model ID]
pravaha assist models | providers | check [--model ID,ID]
pravaha assist use <profile> <id>[,<fallback>...] [--default] [--yes]
pravaha assist enable <id> [--yes] | disable <id> [--yes]
pravaha assist eval [--profile P] [--model ID] [--limit N] [--case ID,...] [--run [--prefix P]
                    [--settle S]] [--full]
```

`ask` drafts a continuous query from a description. The model is told only what the engine lists for
you — the streams and views you may read, the sinks you may write to — with the guide's rules and a
few worked examples; the engine validates and explains the draft, and a refusal gets up to three
repair turns (`--repairs`), none of which may change what the query reads. It prints the
`CREATE CONTINUOUS QUERY` statement, the engine's plan, the sink's guarantee, the model's assumptions
and questions, and every turn; if the model has questions, the engine is not asked. Exit `1` when the
engine still refuses after the repairs. **Nothing is registered unless you say so:** `--register`
registers an accepted draft after you confirm at the terminal, or with `--yes`, through the ordinary
registration call under your credentials (so it needs `--url`).

`assist eval` scores a model on the golden set built from the case studies — accepted by the engine,
the same streams and the same plan and key as the reference; the three negative cases must be refused
or asked about — and prints a table with each case's repair turns, tokens and time. Exit `1` if a
scored case failed. `--run` registers each draft and its reference under `--prefix`, compares the
engine's fingerprints and answers, and drops them: for a test node only.

`explain-sql` sends the SQL and the plan `POST /api/v1/queries/explain` gives for it; SQL the engine
refuses is its refusal, exit `1`, and no model is asked. `why` with no statement needs no engine at
all; with `--sql` the model is given the engine's own diagnostics, and the rewrite line says
`the engine accepts it`, `the engine refuses it too` or `not checked`. Which model answered, and its
tokens, is a note on stderr; `--json` prints the whole result, including the engine's plan or verdict.

```bash
pravaha ask "orders per customer per minute" --name orders_per_minute --register --url grpc://localhost:19090
pravaha why PRV-2050 --sql "SELECT customer, COUNT(*) FROM orders GROUP BY customer"
pravaha assist use explain local-llama,claude --yes
```

A model failure exits `1` with the normalised error — `ModelUnavailable`, `ModelRateLimited` (with
its retry-after), `ModelRefused`, `ModelOutputError`, or `BudgetExceeded` — and a wrong assistant
configuration (no model, a key written in the file, a key variable not set) exits `2`. `assist use`,
`enable` and `disable` print the change and make none without `--yes`; `disable` is refused while a
profile's chain names the model.

## Refusal codes you may see

The CLI prints whatever the engine says; these are the ones its commands most often meet. Each links
to its explanation.

| Code | From | Means |
|---|---|---|
| PRV-1031 | the SDK, before sending | A token over plaintext without `--insecure-token`, or an inconsistent setting (exit `2`) |
| PRV-1032 | the SDK, before sending | TLS flags that do not compose (exit `2`) |
| PRV-1040 | the SDK | Nothing answered at the address (exit `3`) |
| PRV-1053 | the SDK, before sending | Text holding a lone surrogate (exit `2`) |
| PRV-2002 | `query`, `register`, `validate` | The SQL does not plan: an unknown stream or column, a type error |
| PRV-2041 | `register`, `replace` | The query revises its answer and the sink only appends |
| PRV-2050 | `register`, `validate` | A `GROUP BY` with no bound on its key space |
| PRV-4014 | `cutover` | Not caught up, or the versions cannot meet at one position |
| PRV-4017 | `replacements --name`, `cutover`… | No replacement of that name, or a second one |
| PRV-4018 | `replace` | An option this engine does not build, or a source that cannot be replayed |
| PRV-4019 | `subscribe` | The view was replaced at a cutover; subscribe again |
| PRV-4023 | `query`, `subscribe` | Not a registered view |
| PRV-4091 | `dlq show`, `dlq replay` | No such dead letter |
| PRV-5040, PRV-5092 | `queries` (stderr) | Why a source stopped |
| PRV-6102, PRV-6105 | `subscribe --snapshot` | A node too old for snapshots; the subscriber fell too far behind |
| PRV-8002 | lifecycle, `describe`, `plan` | No query registered under that name |
| PRV-8003 | `pause`, `resume` | The query failed or was dropped |
| PRV-8009 | `queries` (stderr) | A sink was detached |
| PRV-8010 | `register` | The output or key does not match the sink |
| PRV-8011, PRV-8012, PRV-8014, PRV-8015 | `debug` | No checkpoint; a source cannot rewind; too many sessions; an unreadable step or page |

## pravaha-engine: SQL with no server

`pravaha-engine` is the Java tool (`bin/pravaha-engine`). It embeds the engine and plans or runs SQL
**in its own process against a schema you give it** — no node, no network, no credentials. Use it to
see what the engine accepts and what it does, in seconds; use `pravaha` for everything that concerns a
running node. Its commands and flags are the Java CLI's offline ones, unchanged.

| | `pravaha-engine` | `pravaha` |
|---|---|---|
| Needs a node | no | yes |
| Knows | one stream: the `--schema` you pass | every stream, view and sink the node has |
| Validates a join or lookup join | no (the other side is "not found") | yes (`pravaha validate`) |
| Commands | `validate`, `explain`, `run`, `version` | everything above |
| Written in | Java | Python, on the Python SDK |

### `pravaha-engine validate`

```text
pravaha-engine validate --sql <query> --schema <spec> [--stream <name>] [--event-time <column>]
```

`--schema` is the stream's columns, `name:TYPE,…` with `?` for nullable (`BOOLEAN INT8 INT16 INT32
INT64 FLOAT32 FLOAT64 STRING BYTES TIMESTAMP`); `--stream` is the name the query uses (default `txn`);
`--event-time` marks the event-time column, without which a windowed query is refused as a node
refuses it.

```bash
pravaha-engine validate --stream txn --event-time event_time \
  --schema "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP" \
  --sql "SELECT txn_id, merchant, amount FROM txn WHERE amount > 1000"
```

```sql
SELECT txn_id, merchant, amount FROM txn WHERE amount > 1000
```

### `pravaha-engine explain`

```text
pravaha-engine explain --sql <query> --schema <spec> [--stream <name>] [--level logical|physical|codegen|all]
```

`--level` defaults to `physical`; `all` prints the logical plan and then the physical one; `codegen`
prints the Java source of the fused stage, or says why the plan runs interpreted.

### `pravaha-engine run`

```text
pravaha-engine run --sql <query> --schema <spec> --in <file> --out <file> --out-schema <spec>
                   [--stream <name>] [--dlq <file>] [--event-time <column>]
```

Runs the query over a delimited file into another. `--out` is **replaced** on each run; `--dlq`
finishes the run anyway and writes each rejected line there as JSON with its bytes base64-encoded —
without it, one undecodable line ends the run and writes nothing.

```bash
cd examples/01-filter-and-project
pravaha-engine run --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
  --schema "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING" \
  --out-schema "user_id:STRING,amount:INT64" --stream txn --in transactions.csv --out out.csv
```

`pravaha-engine` exits `0` on success, `1` when the engine refused or the run failed, `2` on a usage
error. Asked of `pravaha`, `validate --schema`, `explain --schema` and `run` are usage errors that
name `pravaha-engine`.

## Pitfalls

!!! warning "Pitfall: `--keys` are ordinals, and default to 0"
    `register` without `--keys` keys the view by its first output column. That is right for a view
    keyed by one leading column and silently wrong for anything else — two rows that should be distinct
    replace each other. Always say the key; better, register in SQL with `KEYED BY (names)`.

!!! warning "Pitfall: two ports"
    `--url` is Flight, **19090**; `--http` is the HTTP API, **18080**. A command that says it could not
    reach the engine names the address it tried — check which of the two it was.

!!! warning "Pitfall: a token over plaintext"
    A token with `grpc://` or `http://` is refused unless you add `--insecure-token`. Use `grpc+tls://`
    and `https://`; keep `--insecure-token` for loopback.

!!! warning "Pitfall: a script that drops"
    `drop`, `abandon`, `finish`, `lanes rebalance`, `key revoke` and `user disable` exit `0` **without
    doing anything** when `--yes` is missing. A script that means it must say `--yes`.

## Where next

- [Choosing a client](/help/topics/clients) — the CLI beside the SDKs, drivers and the HTTP API.
- [Client snippets](/help/topics/clients#snippets) — the same operations from code.
- [HTTP API](/help/topics/http-api) — the endpoints the HTTP commands call.
- [Quick start](/help/quickstart) — the CLI end to end against a fresh node.
