# The command line

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`LICENSE`](../LICENSE).

Pravaha has two command-line tools, and which one you want depends on whether there is a node.

| | **`pravaha`** | **`pravaha-engine`** |
|---|---|---|
| For | a **running engine**: ask, register, subscribe, operate, administer | SQL with **no server**: plan it, explain it, run it over a file |
| Written in | Python, on the Python SDK (`sdk/python/pravaha/cli`) | Java (the `pravaha-cli` module) |
| Talks to | the node's Arrow Flight port (`--url`, 19090) and HTTP API (`--http`, 18080) | nothing |
| Knows | every stream, view, sink and query the node has | one stream: the `--schema` you pass |
| Commands | everything below | `validate`, `explain`, `run`, `version` |
| Install | `pip install "pravaha[flight]"`, or `bin/pravaha` from a checkout | `bin/pravaha-engine` after `./mvnw -DskipTests install` |

The full per-command reference — every flag, sample output, and the refusal codes each command
meets — is the console's help page **CLI reference**, whose source is
[`console/content/topics/cli-reference.md`](../console/content/topics/cli-reference.md). This page is
the repository's guide to the same program: how it is configured, how it behaves, how it is built, and
the command map.

## Why a Python CLI

The command line for a running engine was Java. It is now the Python SDK as a command, for three
reasons: an operator's shell usually has Python and seldom a JVM; the Python SDK already carried every
Flight call and a typed client for the published HTTP API, so the CLI is a thin layer of argument
parsing and printing over code that is tested anyway; and a command line is the SDK's first consumer,
so an awkward corner of the client shows up at a prompt before a customer finds it.

It has **no protocol code of its own**. Every Flight command calls `pravaha.client.Client`; every HTTP
command calls `pravaha.api.EngineApi`, which is also what `Client`'s HTTP methods delegate to. When the
CLI needed a call the SDK did not have — the identity endpoints, lanes and their rebalance, health, and
PUT/PATCH/DELETE on `RestClient` — the call was added to the SDK, public, typed, documented and tested,
and the CLI calls that.

Every command name and flag of the Java `pravaha` still works, so the documentation written for it
stays valid, with three deliberate differences:

- `drop`, `abandon`, `finish`, `lanes rebalance`, `key revoke` and `user disable` **do nothing without
  `--yes`**: they print what would happen and exit `0`.
- `validate`, `explain` and `run` with `--schema` are `pravaha-engine`'s; asked of `pravaha` they are
  usage errors that say so. `pravaha validate` and `pravaha explain` without `--schema` plan against
  the node, which knows its own streams — so a join validates there.
- A table is aligned rather than tab-separated; `query --tsv` keeps the old form, and `subscribe`
  still writes tab-separated lines because a stream cannot be aligned before it ends.

## Install

```bash
pip install "pravaha[flight]"    # the CLI and the Flight transport
pravaha --help
pravaha version
```

Without the `flight` extra the CLI still installs and every HTTP command works; a Flight command says
`pip install "pravaha[flight]"` and exits `2`. From a checkout, `bin/pravaha` runs an installed
`pravaha` when there is one (other than itself), and otherwise `python -m pravaha.cli` from
`sdk/python`, with that directory's `.venv` when it exists. `PRAVAHA_CLI_FROM_SOURCE=1` skips the
installed one; `PRAVAHA_PYTHON` chooses the interpreter.

## Configuration

Options that say how to reach the engine are accepted **before or after** the command. For each, a
flag beats an environment variable, which beats the default.

| Flag | Environment | Default |
|---|---|---|
| `--url` | `PRAVAHA_URL` | `grpc://localhost:19090` |
| `--http` | `PRAVAHA_HTTP`, then `PRAVAHA_ENGINE_HTTP` (the Java CLI's name) | `http://localhost:18080` |
| `--token` | `PRAVAHA_TOKEN` | the token `login --save` wrote |
| `--insecure-token` | `PRAVAHA_INSECURE_TOKEN=true` | off |
| `--timeout` | `PRAVAHA_TIMEOUT` | `30` seconds |
| `--json` | | off |
| `--no-color` | `NO_COLOR` | colour only on a terminal |
| `--tls-ca`, `--tls-cert`, `--tls-key` | `PRAVAHA_TLS_CA`, `PRAVAHA_TLS_CERT`, `PRAVAHA_TLS_KEY` | none |
| `--tls-trust-store[-password\|-type]`, `--tls-key-store[-password\|-type]` | `PRAVAHA_TLS_TRUST_STORE_PASSWORD`, `PRAVAHA_TLS_KEY_STORE_PASSWORD` | none (Flight only) |
| `--tls-override-hostname`, `--tls-no-verify` | | off |

TLS is chosen by the scheme (`grpc+tls://`, `https://`); the TLS flags say whom to trust and what to
present, and are the SDK's `TlsOptions`, refused together when they do not compose (PRV-1032).

**The token** is `--token`, else `PRAVAHA_TOKEN`, else the saved file:
`$PRAVAHA_CONFIG_DIR/token`, else `$XDG_CONFIG_HOME/pravaha/token`, else `~/.config/pravaha/token`.
`login --save` creates it with mode `0600` in a `0700` directory, and `logout` deletes it after ending
the session on the engine. A token is **never sent over plaintext** `grpc://` or `http://` unless
`--insecure-token` says so — the SDK refuses before anything is sent (PRV-1031, exit `2`). That switch
is for loopback and a TLS-terminating sidecar; the fix is otherwise `grpc+tls://` and `https://`.

```bash
export PRAVAHA_URL=grpc+tls://engine.internal:19090
export PRAVAHA_HTTP=https://engine.internal:18080
export PRAVAHA_TLS_CA=/etc/pravaha/ca.pem
pravaha login --user ann --save       # asks for the password without echo
pravaha whoami                        # "token from  file"
pravaha queries
pravaha logout
```

## Output and exit codes

**Stdout carries the answer and nothing else**; notes, banners, hints and warnings go to stderr. With
`--json` stdout is one JSON document (the SDK's answer; Flight results in the Python SDK's
`snake_case` field names, HTTP results in the API's own), `subscribe --json` is JSON lines (a
`change` object per row and a `commit` or `snapshot` object per commit), and a failure is a JSON
`{"error": {...}}` object on stderr.

| Exit | Means |
|---|---|
| `0` | Done — including a destructive command that only said what it would do, and a subscription ended by Ctrl-C or `--limit` |
| `1` | The engine refused: its `PRV-nnnn` code and message on stderr, then where the code is explained (`PRAVAHA_DOCS_BASE_URL` makes that a link). Also `health` not `UP`/`DEGRADED`, invalid SQL under `validate`, a record that failed again under `dlq replay` |
| `2` | Usage: an unknown command, a missing or malformed flag, a setting refused before sending (PRV-1031, PRV-1032, PRV-1053), an offline command, a Flight command without pyarrow |
| `3` | Nothing answered at `--url` or `--http` (PRV-1040); the message names the address |
| `130` | Interrupted |

`PRAVAHA_CLI_TRACE=1` adds a stack trace to any failure.

## Commands

Flight (`--url`):

| Command | SDK call |
|---|---|
| `query (--sql S \| --sql-file F) [--params a,b] [--tsv]` | `Client.query` |
| `register --name V (--sql \| --sql-file) [--keys 0,1] [--sink S] [--retain PT24H]` | `Client.register` |
| `queries [--verbose]` | `Client.queries` |
| `pause \| resume --name V`, `drop --name V [--yes]` | `Client.pause`, `resume`, `drop` |
| `replace --name V (--sql \| --sql-file) [--keys] [--backfill history\|none] [--rate-limit N] [--cutover manual\|auto] [--rollback-retention D] [--wait]` | `Client.replace` |
| `replacements [--name V]` | `Client.replacements`, `replacement` |
| `cutover \| rollback \| pause-backfill \| resume-backfill --name V`, `throttle --name V --rate N`, `abandon \| finish --name V [--yes]` | `Client.cut_over`, `roll_back`, `pause_backfill`, `resume_backfill`, `throttle_backfill`, `abandon_replacement`, `finish_replacement` |
| `subscribe --view V [--filter c=v,...] [--snapshot] [--limit N] [--reconnect [--reconnect-timeout S]] [--buffer-rows N] [--overflow CONFLATE\|DROP_OLDEST\|FAIL]` | `Client.subscribe` |
| `dlq list --name V [--offset N] [--limit N]`, `dlq show --name V --id I`, `dlq replay --name V --id I[,I]` | `Client.dead_letters`, `dead_letter`, `replay_dead_letters` |
| `debug fork \| checkpoints \| step \| state \| inspect \| view \| fixture \| sessions \| end` (flags as the Java CLI) | `Client.debug_*` |
| `alert create A --on V --notify C[,C] [--where COND] [--severity] [--fire-after D] [--clear-after D] [--dedupe D] [--resend-every D] [--include c,c] [--print-sql]`, `alert drop A [--if-exists] [--yes]` | `Client.query` with the `CREATE ALERT` / `DROP ALERT` statement it writes |

HTTP (`--http`):

| Command | Endpoint (via `EngineApi`) |
|---|---|
| `status` | `GET /api/v1/status` |
| `health` | `GET /actuator/health` (exit `1` unless `UP` or `DEGRADED`) |
| `version [--client]` | the CLI's version, and `GET /api/v1/status`'s |
| `metrics [--grep TEXT]` | `GET /actuator/prometheus` |
| `plugins`, `sinks` | `GET /api/v1/plugins`, `/api/v1/sinks` |
| `streams [list]`, `streams describe S`, `streams declare S --schema ... [--event-time C] [--out-of-orderness D]` | `GET /api/v1/streams`, `/streams/{name}`, `POST /api/v1/streams` |
| `views [list]`, `views describe V` | `GET /api/v1/queries` (a view is a registered query's), `/api/v1/views/{name}` |
| `describe Q` | `GET /api/v1/queries/{name}` — including `lane` and `sharedLane` |
| `plan Q` | `GET /api/v1/queries/{name}/plan` |
| `validate (--sql \| --sql-file)`, `explain ... [--level] [--graph]` | `POST /api/v1/queries/validate`, `/explain` |
| `dlq count --name V` | `GET /api/v1/queries/{name}/dead-letters/count` |
| `lanes [list]`, `lanes rebalance [--yes]`, `lanes rebalance status` | `GET /api/v1/lanes` and `/queries`; `POST /api/v1/lanes/rebalance[?dryRun=true]`; `GET /api/v1/lanes/rebalance` |
| `audit [--since] [--until] [--principal] [--view] [--action] [--decision] [--limit] [--cursor]` | `GET /api/v1/audit` |
| `tenants`, `permissions` | `GET /api/v1/tenants`, `/api/v1/me/permissions` |
| `login --user U [--password P \| --password-stdin] [--save]`, `logout`, `whoami` | `POST /api/v1/auth/login`, `/auth/logout`, `GET /auth/me` |
| `password [--current] [--new]`, `password --reset-token T [--new]` | `POST /api/v1/auth/password`, `/auth/reset/redeem` |
| `user [list]`, `user create N --roles a,b [...]`, `user disable N [--yes]`, `enable N`, `roles N --roles`, `reset N` | `/api/v1/users...` |
| `key [list] [--all]`, `key create N [--roles] [--days] [--for U]`, `rotate K`, `revoke K [--yes]`, `report` | `/api/v1/keys...` |
| `session [list] [--all]`, `session end I` | `/api/v1/sessions...` |
| `catalog ls [--namespace] [--kind]`, `catalog search T`, `catalog namespaces`, `catalog show O` | `GET /api/v1/catalog/objects[?q=]`, `/catalog/namespaces`, `/catalog/objects/{name}` |
| `catalog create-namespace N [--comment] [--if-not-exists]` | `POST /api/v1/catalog/namespaces` |
| `catalog comment O TEXT`, `catalog tag O k[=v]... [--unset k]`, `catalog move V --namespace NS`, `catalog owner O --role R \| --user U [--yes]` | `PATCH /api/v1/catalog/objects/{name}` |
| `grant PRIVS O --role R \| --user U`, `revoke PRIVS O --role R \| --user U [--yes]`, `grants --on O \| --role R \| --user U` | `POST`, `DELETE`, `GET /api/v1/catalog/grants` |
| `access why USER O` | `GET /api/v1/catalog/access` |
| `alerts [ls]`, `alerts channels`, `alerts show A` | `GET /api/v1/alerts`, `/alerts/channels`, `/alerts/{name}` |
| `alerts pause A`, `alerts resume A`, `alerts snooze A DURATION`, `alerts ack A [--key 'c=v, c=v']` | `POST /api/v1/alerts/{name}/pause`, `/resume`, `/snooze`, `/ack` |

| `policy ls [--on O]`, `policy show P` | `GET /api/v1/catalog/policies[?object=]`, `/catalog/policies/{name}` |
| `policy create-filter P --as EXPR [--except-role R]... [--comment]`, `policy create-mask P --column C --as EXPR [--except-role R]...` | `POST /api/v1/catalog/policies` |
| `policy bind P --on O \| --tag k[=v]`, `policy unbind P --on O \| --tag k[=v] [--yes]`, `policy drop P [--yes]` | `POST`, `DELETE /api/v1/catalog/policies/{name}/bindings`, `DELETE /catalog/policies/{name}` |

The catalogue commands (ADR-059) need a node with `pravaha.catalog.enabled`; `revoke`, `catalog
owner`, `policy unbind` and `policy drop` change nothing without `--yes`.

The alert commands (ADR-057): `alerts show` prints every key's state (`FIRING`, `PENDING`, `CLEARING`,
`CLEARED`), what the channels were last told and are owed, and the recent notifications; `alert
create` writes the `CREATE ALERT` statement and sends it over Flight exactly as `pravaha query --sql`
would (`--print-sql` prints it instead); `alert drop` changes nothing without `--yes`.

A password left out is asked for without echo on a terminal, or read from stdin with
`--password-stdin` / `--new-stdin`.

The assistant (ADR-058; configured in `~/.config/pravaha/assist.json` — see [`ASSIST.md`](ASSIST.md)):

| Command | What it does |
|---|---|
| `ask "<description>" [--name N] [--repairs 0-3] [--register [--yes]] [--show-context] [--profile P] [--model ID]` | Drafts a continuous query from the description: the context from `GET /api/v1/me/permissions`, `/streams`, `/queries`, `/views/{name}` and `/sinks` (only what you may read), judged by `POST /api/v1/queries/validate` and `/explain`, up to three repair turns. Prints the statement, the engine's plan, assumptions, questions and every turn. `--register` registers an accepted draft once you confirm (at a terminal, or `--yes`) through the Flight `register` call under your credentials, so it needs `--url`. Exit `1` when still refused after the repairs |
| `why PRV-nnnn [--sql \| --sql-file] [--no-check] [--profile P] [--model ID]` | What the refusal means and what to change, grounded in `POST /api/v1/queries/validate` and the guide; a proposed rewrite is validated too. With no statement it needs no engine |
| `assist models`, `assist providers` | The configured models (key variable set or not, the chains naming each) and every provider type, built-in or installed |
| `assist check [--model ID,ID]` | Ping each enabled model as cheaply as its API allows (exit `1` if one failed) |
| `assist use PROFILE ID[,FALLBACK...] [--default] [--yes]`, `assist enable\|disable ID [--yes]` | Change the stored configuration; a running console following the file picks it up |
| `assist eval [--profile P] [--model ID] [--limit N] [--case ID,...] [--run [--prefix P] [--settle S]] [--full]` | Scores a model on the golden set built from the case studies: each draft judged by the engine and compared with the reference by plan; negative cases must be refused or asked about. Cases whose streams the engine lacks are skipped. `--run` also registers draft and reference under `--prefix`, compares fingerprints and answers, and drops both (a test node; needs `--url`). Exit `1` if a scored case failed |

A model that failed exits `1` with the normalised error on stderr (`ModelUnavailable`,
`ModelRateLimited` with its retry-after, `ModelRefused`, `ModelOutputError`, or `BudgetExceeded`);
a wrong assistant configuration — no model, a key written in the file, a key variable not set —
exits `2` and asked no model. Which model answered, and its tokens, is a note on stderr.

## pravaha-engine

`pravaha-engine validate`, `explain` and `run` embed the engine and plan or run SQL in their own
process against the one stream `--schema` describes: no node, no network, no credentials, seconds from
idea to answer. Their flags are the Java CLI's offline flags, unchanged (`--sql`, `--schema`,
`--stream`, `--event-time`, `--level`, `--in`, `--out`, `--out-schema`, `--dlq`). Because they know one
stream, a join or a lookup join reports the other side as not found — validate those with
`pravaha validate` against a node. See the CLI reference help page for each flag and an example.

## Building and testing

The CLI lives in [`sdk/python/pravaha/cli/`](../sdk/python/pravaha/cli/__init__.py): `_app.py` (the
parser, dispatch and exit codes), `_flight.py`, `_http.py` and `_identity.py` (the commands),
`_output.py` (tables and JSON) and `_settings.py` (configuration and the token file). It is the
`pravaha` console script of the SDK's `pyproject.toml`. Its tests are in `sdk/python/tests`:
`test_cli.py` (parsing, output, exit codes, and every HTTP command against a recording `http.server`),
`test_cli_flight.py` (every Flight command against a `pyarrow.flight` server that answers with the
SDK's own wire framing, and the engine's fixture server when `pravaha-flight` is built) and
`test_api.py` (`EngineApi`).

```bash
cd sdk/python
.venv/bin/python -m pytest -q
.venv/bin/ruff check pravaha tests
.venv/bin/mypy pravaha
```
