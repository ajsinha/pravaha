---
title: CLI reference
slug: cli-reference
category: reference
order: 50
icon: terminal
summary: "Every pravaha command and flag — validate, explain and run with no server; query, register, queries, subscribe, pause, resume and drop against one — each with an example and what it prints, plus exit codes and environment."
badge: REFERENCE
audience: Developers
keywords: [cli, pravaha, command line, validate, explain, run, query, register, queries, subscribe, pause, resume, drop, version, "--url", "--token", "--insecure-token", "--sql-file", "--params", "--keys", "--retain", "--sink", "--filter", "--limit", "--dlq", NO_COLOR, exit code]
guide: quickstart
related: [client-snippets, sdk-reference, point-reads, subscriptions, create-continuous-query]
---

`pravaha` is the command-line client, launched by `bin/pravaha` from the CLI jar
(`pravaha-cli/target/pravaha-cli-<version>-cli.jar`). It has two halves. **Offline commands** —
`validate`, `explain`, `run` — plan and execute SQL in the CLI's own process against a schema you give
it, with no server at all: the fastest way to see what the engine accepts and what it does. **Server
commands** — `query`, `register`, `queries`, `subscribe`, `pause`, `resume`, `drop` — speak Arrow Flight
SQL to a running node, exactly as the Java SDK does (the CLI is built on it).

## At a glance

| Command | Needs a server | Does |
|---|---|---|
| `validate` | no | Parse, validate and plan; print the output columns |
| `explain` | no | Print the logical plan, the physical plan, both, or the generated Java |
| `run` | no | Run a query over a delimited file into another |
| `query` | yes | Ask a question; also runs `CREATE`/`DROP`/`PAUSE`/`RESUME CONTINUOUS QUERY` and `SHOW CONTINUOUS QUERIES` |
| `register` | yes | Register a continuous query with its key, sink and retention |
| `queries` | yes | List the continuous queries you may see |
| `subscribe` | yes | Print a view's committed changes as they happen |
| `pause`, `resume`, `drop` | yes | Lifecycle |
| `version`, `help` | no | |

Options are `--name value` or `--name=value`. `pravaha` with no arguments prints usage and exits `2`;
`pravaha help` (or `-h`, `--help`) prints it and exits `0`. `pravaha <command> --help` prints that
one command's flags and exits `0` without contacting a server (P-4).

## Options every server command takes

| Flag | Default | |
|---|---|---|
| `--url` | `grpc://localhost:19090` | The node's Flight endpoint. `grpc://` is plaintext; `grpc+tls://` (or no scheme) is TLS |
| `--token` | none | A bearer token |
| `--insecure-token` | off | Allow `--token` over a plaintext `grpc://` URL. For loopback or a local TLS-terminating sidecar; the right fix is usually `grpc+tls://` |

## Offline commands

### `validate`

```text
pravaha validate --sql <query> --schema <spec> [--stream <name>]
```

| Flag | | |
|---|---|---|
| `--sql` | required | The query |
| `--schema` | required | The stream's columns, `name:TYPE,…` (`?` suffix for nullable) |
| `--stream` | `txn` | The stream's name as the query refers to it |

```bash
pravaha validate --stream txn \
  --schema "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP" \
  --sql "SELECT merchant, window_start, window_end, SUM(amount) AS spend
         FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
         GROUP BY merchant, window_start, window_end"
```

```text
valid  2154 us
  output: [merchant VARCHAR, window_start TIMESTAMP, window_end TIMESTAMP, spend BIGINT]
```

(The timing and the exact type names are illustrative.) The SQL it validated:

```sql
SELECT merchant, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end
```

A refusal prints the engine's message and a help line, and exits `1`:

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) FROM txn GROUP BY user_id
```

```text
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the
number of distinct keys and never shrinks. … Bound it with a window …
  look PRV-2050 up in the console's help under Errors, or in docs/TROUBLESHOOTING.md
```

`validate`, `explain` and `run` plan against **one** stream (the `--schema` you give). A join between two
streams, or a lookup join, is validated against a node instead — the console's workbench, or
`POST /api/v1/queries/validate` ([HTTP API](/help/topics/http-api)).

### `explain`

```text
pravaha explain --sql <query> --schema <spec> [--stream <name>] [--level logical|physical|codegen|all]
```

`--level` defaults to `physical`. `all` prints the logical plan and then the physical one; `codegen`
prints the Java source of the fused stage the engine generates, or says why the plan runs interpreted.

```bash
pravaha explain --stream txn --level all \
  --schema "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP" \
  --sql "SELECT txn_id, merchant, amount FROM txn WHERE amount > 1000"
```

```text
Logical plan
LogicalProject(txn_id=[$0], merchant=[$2], amount=[$3])
  LogicalFilter(condition=[>($3, 1000)])
    LogicalTableScan(table=[[txn]])

Physical plan
<one line per operator, inputs indented beneath>
```

(Logical plan in Calcite's notation; exact text illustrative.) An unknown level is a usage error:
`--level must be logical, physical, codegen or all; got 'x'`.

```sql
SELECT txn_id, merchant, amount FROM txn WHERE amount > 1000
```

### `run`

```text
pravaha run --sql <query> --schema <spec> --in <file> --out <file> --out-schema <spec>
            [--stream <name>] [--dlq <file>]
```

| Flag | | |
|---|---|---|
| `--in` | required | A delimited file of input rows, in `--schema` order |
| `--out` | required | Where the result rows are written. **Replaced** on each run — a second run leaves one answer in the file, not two (unlike a server's `filesystem` sink, which appends across restarts) |
| `--out-schema` | required | The result's columns, `name:TYPE,…` — must match what the query produces |
| `--dlq` | none | Finish the run anyway and write rejected lines here, one JSON object each with the original bytes base64-encoded. Without it, one undecodable line ends the run and writes nothing |

```bash
cd examples/01-filter-and-project
pravaha run --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
            --schema "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING" \
            --out-schema "user_id:STRING,amount:INT64" \
            --stream txn --in transactions.csv --out out.csv
```

```text
ok  6 in, 3 out
  plan 3120 us, execute 850 us
```

(Counts and timings depend on the file.) With `--dlq`, a rejected line is counted separately:

```text
ok  2 in, 2 out
  1 rejected -> rejects.jsonl
```

## Server commands

### `query`

```text
pravaha query (--sql <query> | --sql-file <path>) [--params a,b] [--url …] [--token …]
```

`--params` is comma-separated and positional; a value that looks like an integer is sent as one, then a
decimal, otherwise text. A `TIMESTAMP` placeholder is bound as epoch nanoseconds.

```bash
pravaha query --sql "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?" --params u1
```

```text
user_id	window_end	spend
u1	2026-09-19T09:00:00Z	4200
u1	2026-09-19T10:00:00Z	1350
2 rows
```

Output is tab-separated with a header; `NULL` prints as `NULL`. The read it ran:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?
```

`query` also runs the management statements:

```bash
pravaha query --sql "SHOW CONTINUOUS QUERIES"
```

```text
name	state	sql	fingerprint	rows_in	key_columns	sink	retention
hourly_spend	RUNNING	SELECT user_id, …	3f9c2a61d0b4	1284551	user_id,window_end	NULL	P7D
1 row
```

(Illustrative row.)

```sql
SHOW CONTINUOUS QUERIES;
```

### `register`

```text
pravaha register --name <view> (--sql <query> | --sql-file <path>)
                 [--keys 0,1] [--sink <name>] [--retain PT24H] [--url …] [--token …]
```

| Flag | Default | |
|---|---|---|
| `--name` | required | The view's name — what reads will say after `FROM` |
| `--keys` | `0` | The view's key as **output-column ordinals**, comma-separated |
| `--sink` | none | A sink bound under `pravaha.sinks.<name>` on the node |
| `--retain` | forever | ISO-8601 (`PT24H`, `P7D`) or `forever` |

`register` takes no parameters: `--param` and `--params` are refused with a usage error (exit 2),
because the server's register action cannot carry bound values and a dropped value would register a
different query. Write the value into the SQL, or register without it and filter at read time.

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
a query with the same fingerprint is the same computation, shared
```

The file's SQL:

```sql
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

The same registration by name rather than ordinal, which cannot drift when the `SELECT` list is
reordered:

```sql
CREATE CONTINUOUS QUERY spend_by_hour
    KEYED BY (user_id, window_end)
    RETAIN FOR P7D
AS
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

A registration with bound values (the `?` becomes part of the plan and of the fingerprint) is the SDKs'
job; the CLI's `register` sends none. Refusals at registration: PRV-2041 (the query revises its answer
and the sink only appends), PRV-8010 (output or `--keys` do not match the sink's schema or key).

### `queries`

```bash
pravaha queries
```

```text
NAME	STATE	FINGERPRINT	ROWS IN	SINK
spend_by_hour	RUNNING	3f9c2a61d0b4	1284551	spend_table
big_txn	PAUSED	9a01bc77e2f3	-	-
a '-' under ROWS IN means the server did not disclose the count: your access to that view is a filtered subset of its rows, and its total is not part of what you may see
```

With nothing registered: `no continuous queries are registered`.

A query whose source failed mid-read is still `RUNNING`, so the state cell says so and a line under
the table says why — without being asked:

```text
NAME	STATE	FINGERPRINT	ROWS IN	SINK
w10	RUNNING (source stopped)	954ae0e3ea2c	120	-
w10: source stopped with PRV-5040 reading ev#0 at 2026-09-19T08:00:00Z: PRV-5040  line 121 ...
a stopped source is not retried: the view keeps answering at the frontier it reached. Fix the cause, then drop the query and register it again, or restart the node. Look each code up in the console's help under Errors, or in docs/TROUBLESHOOTING.md.
```

`SINK` is the binding the query's changes are also written to, or `-` when it writes only to its
view. A sink that refused a batch is **detached** — the query stays `RUNNING` and its view stays
right, and nothing more is written — so the cell says so and a line under the table says why, on
the same terms as a stopped source:

```text
NAME	STATE	FINGERPRINT	ROWS IN	SINK
merchant_spend	RUNNING	7c1a40f9b2de	98211	spend_table (detached)
merchant_spend: sink 'spend_table' detached with PRV-8009: sink 'spend_table' for query 'merchant_spend' failed and has been detached: ...
a detached sink is not retried either, and the query and its view carry on and stay right. Fix the cause, then drop the query and register it again: the sink is sent the view's whole contents first, so nothing written while it was detached is lost
```

`pravaha queries --verbose` adds a `FEED` column: `RUNNING`, `PAUSED`, `STOPPED`, or `NONE` when
nothing is bound to the query's streams.

### `subscribe`

```text
pravaha subscribe --view <name> [--filter col=val,col2=val2] [--limit N] [--url …] [--token …]
```

| Flag | | |
|---|---|---|
| `--view` | required | The view to follow |
| `--filter` | none | Equality filters applied at the tap on the server; a column the view lacks is refused |
| `--limit` | `0` (no limit) | Stop after this many rows |

```bash
pravaha subscribe --view big_txn --filter merchant=TRAVELCO --limit 10
```

```text
subscribed to big_txn {merchant=TRAVELCO}; changes print as they are committed. Ctrl-C to stop.
WEIGHT	txn_id	user_id	merchant	amount
+1	9004	u7	TRAVELCO	4800
-- commit, 1 row
```

Each change leads with its Z-set weight, always signed — `+1` a row arriving, `-1` a row withdrawn —
under a `WEIGHT` header printed with the first change, so a retraction never looks like the insert it
withdraws. Each commit ends with a `-- commit, N rows` line. See
[Subscriptions](/help/topics/subscriptions).

### `pause`, `resume`, `drop`

```text
pravaha pause|resume|drop --name <view> [--url …] [--token …]
```

```bash
pravaha drop --name spend_by_hour
```

```text
dropped spend_by_hour
```

`pause` and `resume` are idempotent: they state the end state you want, so pausing a paused query
succeeds and changes nothing. A failed or dropped query is refused with PRV-8003. A drop removes a
**name**; the computation goes when its last name does. The SQL forms:

```sql
PAUSE CONTINUOUS QUERY spend_by_hour;
RESUME CONTINUOUS QUERY spend_by_hour;
DROP CONTINUOUS QUERY spend_by_hour;
```

### `version`

```bash
pravaha version
```

```text
pravaha 0.1.0-SNAPSHOT
```

## Exit codes and environment

| Exit | Means |
|---|---|
| `0` | Success |
| `1` | The command ran and failed: a refusal (`PRV-…` and the help line on stderr), a connection failure, any other error |
| `2` | A usage error: an unknown command, a missing required flag, a malformed `--filter` |

| Variable | |
|---|---|
| `NO_COLOR` | Set to anything to suppress colour. Colour is also off when output is redirected |
| `PRAVAHA_CLI_TRACE` | Set to print the stack trace of a server-command failure |

## Pitfalls

!!! warning "Pitfall: `--keys` are ordinals, and default to 0"
    `register` without `--keys` keys the view by its first output column. That is right for a view
    keyed by one leading column and silently wrong for anything else — two rows that should be distinct
    replace each other. Always say the key; better, register in SQL with `KEYED BY (names)`.

!!! warning "Pitfall: a token over grpc://"
    `--token` with a `grpc://` URL is refused unless you add `--insecure-token`. Use `grpc+tls://`.

!!! warning "Pitfall: offline commands know one stream"
    `validate`, `explain` and `run` plan against the single `--schema` given, so a join or a lookup join
    reports the other side as not found. Validate those against a node.

## Where next

- [Client snippets](/help/topics/client-snippets) — the same operations from code.
- [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query) — the SQL form of `register`.
- [Quick start](/help/quickstart) — the CLI end to end against a fresh node.
