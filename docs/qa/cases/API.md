# API — the three surfaces a client actually touches

*Area `API`, IDs `API-001`–`API-180`, budget 180.*

Three surfaces, three sections: the CLI (`API-001`–`API-075`), the REST/HTTP surface
(`API-076`–`API-125`), and Flight / Flight SQL (`API-126`–`API-180`).

Everything here is authored against the code as it stands on `develop`. Nothing below was executed.

| Thing | Where |
|---|---|
| Dispatch, exit codes, usage text | `pravaha-cli/src/main/java/com/ash/messaging/pravaha/cli/PravahaCli.java` |
| `--key value`, `--key=value`, bare-flag-is-`true`, `require` | `pravaha-cli/.../cli/Args.java` |
| `validate`, `explain`, `run` | `pravaha-cli/.../cli/ValidateCommand.java`, `ExplainCommand.java`, `RunCommand.java` |
| `run`'s pipeline, `checkHealth` after close | `pravaha-cli/.../cli/QueryRunner.java` |
| `query`, `register`, `queries`, `drop`/`pause`/`resume`, `subscribe`, `connect` | `pravaha-cli/.../cli/ServerCommand.java` |
| Colour suppression | `pravaha-cli/.../cli/Ansi.java` |
| Launcher, JVM `--add-opens` | `bin/pravaha` |
| REST controllers | `pravaha-server/.../server/api/StatusController.java`, `StreamController.java`, `QueryController.java` |
| Wire types, `ApiError` | `pravaha-server/.../server/api/ApiDtos.java` |
| Status derived from error category | `pravaha-server/.../server/api/ApiExceptionHandler.java` |
| HTTP authentication, `OPEN_PREFIXES` | `pravaha-server/.../server/security/BearerTokenFilter.java` |
| Filter registration (`setEnabled(verifier != null)`) | `pravaha-server/.../server/PravahaServerApplication.java` |
| Actuator exposure, springdoc paths, security defaults | `pravaha-server/src/main/resources/application.yaml` |
| Locked API surface | `api/openapi.lock.json` |
| Stream catalog, `require` message | `pravaha-server/.../server/catalog/StreamCatalog.java` |
| Category → HTTP status, category ranges | `pravaha-api/.../api/ErrorCode.java` |
| Flight SQL producer: verbs, tickets, prepared statements, subscriptions | `pravaha-flight/.../flight/PravahaFlightSqlProducer.java` |
| Bearer auth on Flight | `pravaha-flight/.../flight/PrincipalMiddleware.java` |
| Handle framing, 1 MiB parameter ceiling | `pravaha-flight/.../flight/StatementHandle.java` |
| PRV → `CallStatus` mapping | `pravaha-flight/.../flight/FlightErrors.java` |
| Arrow schema mapping (every field `FieldType.nullable`) | `pravaha-flight/.../flight/ArrowSchemas.java` |
| Control framing, `MAGIC`, `isOurs`, `subscribeTicket` | `pravaha-api/.../api/wire/ControlWire.java` |
| Server construction, TLS, `requireOnePolicy` | `pravaha-flight/.../flight/PravahaFlightServer.java` |
| Disclosure sites | `QueryRegistry.require` (PRV-8002), `SqlPlanner.plan` (PRV-2002), `ViewQuery.execute` (PRV-4023) |

## Harnesses

Every case names one of these rather than restating it.

**`H-CLI` — the CLI, no server.** Built with `./mvnw -pl pravaha-cli -am install -DskipTests`;
`export PATH="$PWD/bin:$PATH"` from the repository root; `NO_COLOR=1` and output redirected, so
`Ansi.enabled()` is false and every expectation below is plain text. **Nothing is listening on
19090** — confirmed with `ss -ltn 'sport = :19090'` returning no row before the section runs.
Constants used throughout:

```
SCHEMA4    = 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
OUTSCHEMA  = 'user_id:STRING,amount:INT64'
IN         = examples/01-filter-and-project/transactions.csv
FILTERSQL  = "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100"
NUMERICSQL = "SELECT txn_id, amount FROM txn WHERE amount > 100"
```

`NUMERICSQL` exists for one case, API-037, and finding **API-F2** is why. `FILTERSQL` projects
`user_id`, a `STRING`, and the code generator refuses a STRING projection
(`PRV-3101  cannot generate a projection of STRING yet`), so `explain --level codegen` over it
exercises the *fallback* — which is API-038's case — and can never demonstrate the happy path
API-037 is written to pin. A second constant is the whole fix: the two cases then differ by the
one thing they are about.

`IN` holds exactly six rows:

```
1,alice,500,COMPLETED
2,bob,50,COMPLETED
3,carol,900,PENDING
4,dave,150,COMPLETED
5,erin,75,COMPLETED
6,frank,1200,COMPLETED
```

`FILTERSQL` over them, computed by hand: row 1 `COMPLETED` and `500 > 100` → kept; row 2
`COMPLETED` but `50 > 100` false → dropped; row 3 `900 > 100` true but `PENDING <> COMPLETED` →
dropped; row 4 `COMPLETED` and `150 > 100` → kept; row 5 `75 > 100` false → dropped; row 6
`COMPLETED` and `1200 > 100` → kept. **6 read, 3 written**, in file order: `alice,500` /
`dave,150` / `frank,1200`.

Exit codes are `PravahaCli.EXIT_OK = 0`, `EXIT_FAILED = 1`, `EXIT_USAGE = 2`.

**`H-SRV` — an open server.** `pravaha-server --spring.profiles.active=dev` (so
`pravaha.security.allow-anonymous: true`, `authentication: none`, `policy: permissive`, and
`PravahaServerApplication.pravahaAuthentication` registers the filter **disabled** because
`security.verifier()` returns null). HTTP on 18080, Flight on 19090. `application.yaml` amended with:

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
```

so `GET /api/v1/streams` answers with exactly one stream of four fields.

**`H-SRVA` — the same server, authenticating.** As `H-SRV` but

```yaml
pravaha:
  security:
    authentication: token
    policy: authenticated
    allow-anonymous: false
    tokens:
      "ann-token-0123456789": { id: ann, tenant: acme, roles: [reader] }
      "bob-token-0123456789": { id: bob, tenant: globex, roles: [reader] }
```

`verifier()` is non-null, so `BearerTokenFilter` is registered on `/*` at `HIGHEST_PRECEDENCE`, and
the policy is `AuthenticatedOnlyPolicy` — which allows **every** authenticated caller to read and
register, and denies only anonymous ones. There is no denied-but-authenticated principal reachable
from YAML; those cases use `H-FLA`.

**`H-FL` — embedded Flight server, open.** Exactly `FlightSqlEndToEndTest.startServer`:

```java
StreamSchema SCHEMA = user_volume(user_id STRING, tier STRING?, total INT64)
ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
view.applyValues({"u1","gold",300L}, 1, 10);
view.applyValues({"u2","silver",50L}, 1, 10);
view.applyValues({"u3",null,7L},     1, 10);
view.commit(10);
server = new PravahaFlightServer(new ViewCatalog().register(view), allocator).start("localhost", 0);
client = new FlightSqlClient(FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port())).build());
```

No verifier, so every call is `Principal.ANONYMOUS`; `SecurityPolicy.PERMISSIVE`; **no registry**,
so every `pravaha.*` verb hits `requireRegistry()`.

**`H-FLR` — `H-FL` plus a registry.** `QueryRegistry registry = new QueryRegistry(views,
SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)` where `TXN = txn(txn_id INT64, user_id STRING,
amount INT64, status STRING)`, and the server built as
`new PravahaFlightServer(views, allocator).authorizedBy(SecurityPolicy.PERMISSIVE, audit).hosting(registry).start("localhost", 0)`.
Three registrations exist before each case unless it says otherwise:

```
q_alpha : SELECT user_id, amount FROM txn WHERE amount > 100   keys [0]
q_beta  : SELECT user_id, status FROM txn                      keys [0]
q_gamma : SELECT user_id, amount FROM txn WHERE amount > 500   keys [0]
```

**`H-FLA` — embedded Flight server, authenticating and authorizing.** `H-FLR` but with an
explicitly-implemented policy (not a lambda, so `mayRegisterQuery` below is the one that runs) and
three credentials:

```java
StaticTokenVerifier verifier =
    StaticTokenVerifier.of("analyst-token-1", new Principal("dana","acme",Set.of("analyst"),Map.of()))
        .and("intern-token-1",  new Principal("sam", "acme", Set.of("intern"),  Map.of()))
        .and("auditor-token-1", new Principal("ravi","acme", Set.of("auditor"), Map.of()));

SecurityPolicy POLICY = new SecurityPolicy() {
    public AccessDecision mayRead(Principal p, String view) {
        if (p.hasRole("analyst")) return AccessDecision.allow();
        if (p.hasRole("auditor")) return AccessDecision.allowWithRowFilter("tier = 'gold'");
        return AccessDecision.deny("only analysts read " + view);
    }
    public AccessDecision mayRegisterQuery(Principal p) {
        return p.isAnonymous() ? AccessDecision.deny("anonymous may not register") : AccessDecision.allow();
    }
};
```

The same `POLICY` **instance** goes to both `QueryRegistry` and `authorizedBy`, because
`requireOnePolicy()` compares by identity. So: **dana** is allowed outright, **ravi** is allowed
subject to a row filter (which `mayAdminister` inherits, so `allowed()` is true for him too), and
**sam** is denied everything. Tokens travel as `authorization: Bearer <token>` via
`HeaderCallOption`.

---

## CLI (API-001–API-075)

### A. Dispatch, usage and version (API-001–008)

## API-001 — no arguments prints usage on stdout and exits 2
**Intent:** `run(args)` with `args.length == 0` prints the usage block and returns `EXIT_USAGE`. A
CLI that exits 0 when told nothing makes `pravaha` in a script a silent no-op.
**Falsifier:** exit code 0, or usage on stderr, or no output at all.
**Setup:** `H-CLI`.
**Steps:** `pravaha >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`. `out.txt` begins `pravaha 0.1.0-SNAPSHOT  Ask once. Answer always.` and
contains the lines `Usage:  pravaha <command> [options]` and all nine command entries
(`validate`, `query`, `register`, `queries`, `subscribe`, `pause | resume | drop`, `explain`,
`run`, `version`). `err.txt` is empty (0 bytes).

## API-002 — `--help` exits 0 with the same usage
**Intent:** `isHelp("--help")` is true, so usage prints and the exit code is `EXIT_OK`. Asking for
help is not an error.
**Falsifier:** exit 2, or usage on stderr.
**Setup:** `H-CLI`.
**Steps:** `pravaha --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` byte-identical to API-001's `out.txt`; `err.txt` empty.

## API-003 — `help` as a subcommand exits 0
**Intent:** the bare word `help` is the third accepted spelling in `isHelp`.
**Falsifier:** `help` treated as an unknown command (exit 2 with `unknown command: help`).
**Setup:** `H-CLI`.
**Steps:** `pravaha help >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` identical to API-001's; `err.txt` empty.

## API-004 — `-h` exits 0
**Intent:** the short form. Documented nowhere, accepted by `isHelp`, and worth pinning so it is not
dropped by accident.
**Falsifier:** exit 2.
**Setup:** `H-CLI`.
**Steps:** `pravaha -h >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` identical to API-001's; `err.txt` empty.

## API-005 — an unknown command names itself on stderr, prints usage on stdout, exits 2
**Intent:** the `default ->` arm writes `unknown command: X` through `Ansi.bad` to **err** and the
usage block to **out**. Split streams so a pipeline can keep the two apart.
**Falsifier:** the message on stdout, or the usage on stderr, or exit 1.
**Setup:** `H-CLI`.
**Steps:** `pravaha frobnicate >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is exactly `unknown command: frobnicate` plus a newline (27 + 1
bytes with `NO_COLOR=1`); `out.txt` is the usage block.

## API-006 — a command name differing only in case is unknown
**Intent:** dispatch is a `switch` on the exact string; `VALIDATE` is not `validate`. Pinned because
a case-insensitive shell user will try it.
**Falsifier:** `VALIDATE` running the validate command.
**Setup:** `H-CLI`.
**Steps:** `pravaha VALIDATE --sql "SELECT 1" --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `unknown command: VALIDATE`; `out.txt` is the usage block.

## API-007 — `version` prints one line and exits 0
**Intent:** `version` is answered without touching the network, the filesystem or the engine.
**Falsifier:** a stack trace, a second line, or a non-zero exit.
**Setup:** `H-CLI`.
**Steps:** `pravaha version >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` is exactly one line, `pravaha 0.1.0-SNAPSHOT` when the jar carries
no `Implementation-Version`, otherwise `pravaha <that value>`; `err.txt` empty. The same string
appears in the usage header (API-001), and the two must agree.

## API-008 — `version` ignores every flag given to it
**Intent:** the `version` arm never constructs `Args`, so nothing can be required, rejected or
misread. A trailing flag from shell history must not turn a version query into a usage error.
**Falsifier:** exit 2, or any attempt to reach `--url`.
**Setup:** `H-CLI`.
**Steps:** `pravaha version --url grpc://nowhere:1 --token x --sql "SELECT 1" >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` identical to API-007's; `err.txt` empty; the command returns in
well under a second because no connection is attempted.

### B. `--help` on each of the nine commands (API-009–017)

`isHelp` is consulted only for `args[0]`. For every other command `--help` is parsed by `Args` as a
bare flag and stored as `help=true`, after which the command's own `require` calls run. These nine
cases enumerate what that produces, command by command. **This is the documented-but-broken
behaviour the brief names**, and each case pins the current answer so the fix is visible as a diff.

## API-009 — `pravaha validate --help` demands `--sql` instead of helping
**Intent:** pin that per-command help does not exist and fails as a usage error.
**Falsifier:** help text for `validate` appearing on stdout, or exit 0.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--sql is required. Supplied: [help]`; `out.txt` empty.

## API-010 — `pravaha explain --help` demands `--sql`
**Intent:** same path through `ExplainCommand.run`, which calls `require("sql")` first.
**Falsifier:** help text, or exit 0, or a complaint about `--level`.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--sql is required. Supplied: [help]`; `out.txt` empty.

## API-011 — `pravaha run --help` demands `--sql`
**Intent:** the case the brief names explicitly. `RunCommand.run` evaluates
`args.require("sql")` as the first argument to `QueryRunner.run`.
**Falsifier:** help text for `run`, or exit 0, or a complaint naming `--in`/`--out` (which would
mean the arguments are evaluated in a different order than written).
**Setup:** `H-CLI`.
**Steps:** `pravaha run --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--sql is required. Supplied: [help]`; `out.txt` empty. No file
is created anywhere.

## API-012 — `pravaha query --help` demands `--sql` without opening a connection
**Intent:** `ServerCommand.query` calls `sqlFrom(args)` before `connect(args)`, so a help attempt
fails locally rather than dialling `grpc://localhost:19090`.
**Falsifier:** exit 1 with a connection message (which would mean the connection is attempted
first), or a hang.
**Setup:** `H-CLI` (nothing on 19090).
**Steps:** `time pravaha query --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--sql is required. Supplied: [help]`; `out.txt` empty; elapsed
under 2 s with no TCP connection attempted to 19090.

## API-013 — `pravaha register --help` demands `--name`
**Intent:** `register` requires `--name` before it looks for SQL, so the first complaint differs
from the other commands'. Pinning it catches a reordering.
**Falsifier:** `--sql is required` instead, or help text.
**Setup:** `H-CLI`.
**Steps:** `pravaha register --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--name is required. Supplied: [help]`; `out.txt` empty.

## API-014 — `pravaha queries --help` tries to reach a server and fails
**Intent:** `queries` requires nothing, so `--help` is swallowed as an option and the command
proceeds straight to `connect(args)`. Asking for help contacts the network. This is the worst of
the nine and the one that most justifies the section.
**Falsifier:** help text on stdout, or exit 2.
**Setup:** `H-CLI`, nothing listening on 19090.
**Steps:** `pravaha queries --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `err.txt` names `localhost:19090` and carries the SDK's
`PRV-…  cannot connect to localhost:19090: …` or a Flight `UNAVAILABLE` description reaching it
through `fail(e)`; `out.txt` empty. The exit code is 1 (a failure), never 2.

## API-015 — `pravaha subscribe --help` demands `--view`
**Intent:** `subscribe` requires `--view` before connecting.
**Falsifier:** help text, or a connection attempt.
**Setup:** `H-CLI`.
**Steps:** `pravaha subscribe --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--view is required. Supplied: [help]`; `out.txt` empty.

## API-016 — `pravaha drop --help` demands `--name`
**Intent:** `lifecycle` requires `--name` for all three of `drop`, `pause` and `resume`.
**Falsifier:** help text, or a drop actually happening.
**Setup:** `H-CLI`.
**Steps:** `pravaha drop --help >out.txt 2>err.txt; echo $?`; repeat for `pause` and `resume`.
**Expected:** all three exit `2` with `err.txt` = `--name is required. Supplied: [help]` and empty
`out.txt`.

## API-017 — `pravaha version --help` prints the version, not help
**Intent:** completes the enumeration: the one command where `--help` neither helps nor fails, it
is simply ignored (API-008's mechanism).
**Falsifier:** anything other than the version line.
**Setup:** `H-CLI`.
**Steps:** `pravaha version --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` is `pravaha 0.1.0-SNAPSHOT`; `err.txt` empty. **Nine commands,
four distinct behaviours** (usage-error on a required flag ×6, network attempt ×1, version ×1,
and `pravaha --help` itself ×1 from API-002) — no command prints its own help.

### C. The option grammar (API-018–026)

`Args.parse` has four rules and every one of them is observable. These cases enumerate them.

## API-018 — `--key value` and `--key=value` mean the same thing
**Intent:** both forms reach `options.put`, and nothing downstream can tell them apart.
**Falsifier:** one form validating and the other reporting a missing option, or two different plans.
**Setup:** `H-CLI`.
**Steps:**
1. `pravaha validate --sql "SELECT user_id FROM txn" --schema SCHEMA4 >a.txt 2>&1; echo $?`
2. `pravaha validate --sql="SELECT user_id FROM txn" --schema=SCHEMA4 >b.txt 2>&1; echo $?`
**Expected:** both exit `0`; both files' second line is
`  output: [user_id STRING NOT NULL]`; the only permitted difference between `a.txt` and `b.txt` is
the microsecond figure on the first line (`valid  <n> us`).

## API-019 — a value containing `=` survives the `=` form
**Intent:** `body.indexOf('=')` splits on the **first** `=` only, so `--sql=SELECT ... WHERE a='b'`
keeps everything after the first `=` as the value.
**Falsifier:** SQL truncated at the second `=`, producing a parse error.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate "--sql=SELECT user_id FROM txn WHERE status = 'COMPLETED'" --schema=SCHEMA4; echo $?`
**Expected:** exit `0`; `valid` on stdout with `output: [user_id STRING NOT NULL]`.

## API-020 — a bare flag becomes the string `true`
**Intent:** the third `Args.parse` branch. `--level` with nothing after it is `level=true`, and
`ExplainCommand` compares that against its four level names.
**Falsifier:** `--level` with no value being treated as absent (and so defaulting to `physical`).
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql "SELECT user_id FROM txn" --schema SCHEMA4 --level >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is
`--level must be logical, physical, codegen or all; got 'true'` — the word `true` in the message is
the proof that the bare-flag rule fired.

## API-021 — a `--sql` value beginning with `--` is silently replaced by `true`
**Intent:** the documented-and-planned defect the brief names. `arguments.get(i + 1).startsWith("--")`
is how `Args` decides a flag has no value, so `--sql --drop-me` stores `sql=true` and the engine is
asked to plan the SQL text `true`. The user's query is discarded without a word.
**Falsifier:** either a refusal naming `--sql`, or the intended SQL actually being planned. Today
neither happens.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --sql --nonsense --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`. `err.txt` carries `PRV-2001` (`SQL_PARSE_FAILED`) from Calcite failing on the
one-word statement `true`, plus the help URL line `  https://docs.pravaha.io/errors/PRV-2001`.
Nowhere in either stream does the string `--nonsense` appear: the argument the user typed is gone.
Record this case's output verbatim — it is the before-picture for whatever fix lands.

## API-022 — the same value survives when written with `=`
**Intent:** the escape hatch for API-021, and the proof that the fault is in the lookahead and not
in the value itself.
**Falsifier:** `--sql=--nonsense` also collapsing to `true` (which would mean the `=` branch is
broken too).
**Setup:** `H-CLI`.
**Steps:** `pravaha validate "--sql=--nonsense" --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `err.txt` carries `PRV-2001` **and quotes `--nonsense`** in Calcite's parse
message, because that string reached the planner. The contrast with API-021 is the finding.

## API-023 — a repeated option keeps the last occurrence
**Intent:** `options.put` overwrites, so `--sql A --sql B` runs `B`. Silent, and worth knowing.
**Falsifier:** a refusal for the duplicate, or the first value winning.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --sql "SELECT nope FROM txn" --sql "SELECT user_id FROM txn" --schema SCHEMA4; echo $?`
**Expected:** exit `0` with `output: [user_id STRING NOT NULL]`. Had the first value won, the exit
would be 1 with `PRV-2002` naming `nope`.

## API-024 — an unknown option is accepted and ignored
**Intent:** `Args` has no notion of a known option set. `--lanes`, `--verbose`, `--tenant` and every
typo are stored and never read. A user who mistypes `--scheme` for `--schema` gets
"`--schema` is required", but a user who adds `--lanes 8` gets a silent single-lane run.
**Falsifier:** an unknown option being refused (which would be the better behaviour, and would
falsify this pin).
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --sql "SELECT user_id FROM txn" --schema SCHEMA4 --frobnicate 7 --verbose; echo $?`
**Expected:** exit `0`, output identical to API-018's case 1. Neither `frobnicate` nor `verbose`
appears in either stream.

## API-025 — `--lanes` on `run` is accepted and has no effect
**Intent:** the specific instance of API-024 that the source documents as a feature.
`QueryRunner.run` has a seven-argument overload taking `lanes`, and `QueryRunner`'s own Javadoc says
"`--lanes` is there for anyone who wants the parallelism" — but `RunCommand` calls the six-argument
overload, which hard-codes `1`. The flag is documentation for a wire that is not connected.
**Falsifier:** any observable difference between the two runs.
**Setup:** `H-CLI`.
**Steps:**
1. `pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN --out /tmp/a.csv --out-schema OUTSCHEMA`
2. `pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN --out /tmp/b.csv --out-schema OUTSCHEMA --lanes 8`
**Expected:** both exit `0` and print `ok  6 in, 3 out`; `/tmp/a.csv` and `/tmp/b.csv` are
byte-identical and both are exactly `alice,500\ndave,150\nfrank,1200\n` — the file order the
single-lane path guarantees. Eight lanes would be free to interleave; the identical ordering over
ten repetitions is the evidence the flag did nothing.

## API-026 — positional arguments are collected and never used
**Intent:** `Args.positional()` exists and no command calls it, so `pravaha validate mystery.sql …`
silently ignores the filename. Someone will type it.
**Falsifier:** the positional being read as SQL or as a path.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate mystery.sql --sql "SELECT user_id FROM txn" --schema SCHEMA4; echo $?`
(with no file named `mystery.sql` on disk).
**Expected:** exit `0`, output identical to API-018's case 1, no filesystem access to `mystery.sql`
(confirm with `strace -f -e openat` or by the absence of any error).

### D. `validate` (API-027–033)

## API-027 — the happy path validates and reports its own latency
**Intent:** the command the console's editor calls on every keystroke burst: parse, validate, plan,
print the output schema and the elapsed microseconds, run nothing.
**Falsifier:** any row being read, any file being written, or an output schema that is not the
projection's.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --sql FILTERSQL --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `err.txt` empty; `out.txt` two lines —
`valid  <n> us` where `<n>` is a positive integer, and
`  output: [user_id STRING NOT NULL, amount INT64 NOT NULL]`. The two output fields are exactly the
two in the `SELECT` list, in that order; `status` and `txn_id` do not appear even though the filter
names `status`.

## API-028 — `--sql` missing names itself and lists what was supplied
**Intent:** `Args.require` reports the option **and** the keys it did get, which is the difference
between a diagnosis and a shrug.
**Falsifier:** a message that names neither, or exit 1.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is exactly `--sql is required. Supplied: [schema]`.

## API-029 — `--schema` missing is reported after `--sql` is accepted
**Intent:** the second required option, and the `Supplied:` list showing the one that arrived.
**Falsifier:** `--sql is required` (wrong option), or a default schema being invented.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --sql "SELECT user_id FROM txn" >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is exactly `--schema is required. Supplied: [sql]`.

## API-030 — both missing says `nothing`
**Intent:** the empty branch of `require`'s message (`options.isEmpty() ? "nothing" : keySet()`).
**Falsifier:** `Supplied: []`, which is what an unguarded `keySet()` would print.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is exactly `--sql is required. Supplied: nothing`.

## API-031 — an empty `--sql` value counts as missing
**Intent:** `require` rejects blank as well as null, so `--sql ""` is a usage error rather than a
parse error. Pinned because shell expansion of an unset variable produces exactly this.
**Falsifier:** exit 1 with `PRV-2001` (an empty statement reaching Calcite).
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --sql "" --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--sql is required. Supplied: [sql, schema]` — note the key is
present in the list while its value is rejected, which is the whole point of the message.

## API-032 — `--stream` renames the table the SQL must use
**Intent:** `args.get("stream", "txn")` names the schema, so the same SQL validates under one name
and fails under another. Six-line coverage of a flag that is otherwise invisible.
**Falsifier:** `--stream` being ignored (both queries validating), or the default not being `txn`.
**Setup:** `H-CLI`.
**Steps:**
1. `pravaha validate --sql "SELECT user_id FROM payments" --schema SCHEMA4 --stream payments; echo $?`
2. `pravaha validate --sql "SELECT user_id FROM txn" --schema SCHEMA4 --stream payments; echo $?`
**Expected:** (1) exit `0`, `output: [user_id STRING NOT NULL]`. (2) exit `1`, `PRV-2002` whose
message ends `. Known streams: [payments]` — the disclosure list confirms the rename took effect.

## API-033 — a malformed schema spec exits 1, not 2
**Intent:** a command-line mistake that arrives as a `PravahaException` from
`FilesystemSourcePlugin.parseSchema` rather than as a `UsageException`, so it is classified as a
failure (1) and not as a usage error (2). The CLI's own contract — "scripts distinguish those" — is
half-true, and this pins where the line actually falls.
**Falsifier:** exit 2 (which would mean the classification was fixed), or exit 0.
**Setup:** `H-CLI`.
**Steps:** `pravaha validate --sql "SELECT user_id FROM txn" --schema "user_id STRING,amount:INT64" >out.txt 2>err.txt; echo $?`
(note the missing colon in the first entry).
**Expected:** exit `1`; `err.txt` line 1 is
`PRV-5040  schema entry 'user_id STRING' is not 'name:TYPE'. Example: id:INT64,name:STRING` and line
2 is `  https://docs.pravaha.io/errors/PRV-5040`. Also record that the code is in the **PLUGIN**
range (5000–5999) for what is purely a caller's typo — the same misclassification shows up over
HTTP in API-121.

### E. `explain` (API-034–040)

## API-034 — the default level is `physical`
**Intent:** `args.get("level", "physical")`. A caller who names no level gets the plan the engine
will execute, not Calcite's.
**Falsifier:** logical output (a `Logical…` header) when no level is given.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql FILTERSQL --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `err.txt` empty; `out.txt` line 1 is `Physical plan`, and the body contains
`Scan(txn)` at the leaf with a filter below a projection.

## API-035 — `--level logical` prints Calcite's optimised tree
**Intent:** the level that answers "what did the optimiser decide".
**Falsifier:** a physical plan under a `Logical plan` header.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql FILTERSQL --schema SCHEMA4 --level logical; echo $?`
**Expected:** exit `0`; line 1 `Logical plan`; the body contains `Logical` relational operators
(e.g. `LogicalProject`, `LogicalFilter`) and **not** `Scan(txn)`.

## API-036 — `--level all` prints both, logical first
**Intent:** the composite level, and the blank line between the two sections.
**Falsifier:** one section only, or physical printed before logical.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql FILTERSQL --schema SCHEMA4 --level all >out.txt; echo $?`
**Expected:** exit `0`; `out.txt` contains `Logical plan` at line 1, then exactly one empty line
before `Physical plan`; the text after `Physical plan` is byte-identical to API-034's body and the
text between the headers is byte-identical to API-035's body.

## API-037 — `--level codegen` prints numbered generated Java
**Intent:** the level that exists because a generated plan has no source file to open. The line
numbers are part of the contract (`String.format("%4d  %s%n", …)`).
**Falsifier:** unnumbered source, or numbering starting at 0.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql NUMERICSQL --schema SCHEMA4 --level codegen >out.txt; echo $?`
**Expected:** exit `0`; line 1 is `Generated source`; line 2 begins with `   1  ` (three spaces, `1`,
two spaces) and the numbers increase by one per line with no gaps; the body contains a Java class
declaration named `ExplainStage`.

## API-038 — a query with no generated form says so and still exits 0
**Intent:** `ExplainCommand.codegen` catches `PravahaException` and returns a diagnostic string. An
uncovered plan is not a failure — the interpreted path runs it.
**Falsifier:** exit 1, or a stack trace, or an empty body.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql "SELECT user_id FROM txn" --schema SCHEMA4 --level codegen >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `err.txt` empty; `out.txt` is `Generated source` followed by a line starting
`This query has no generated form: ` and then
`It will run on the interpreted path, which is correct and slower.` (This pins the current
behaviour of a bare projection; if the generator does cover it, the falsifier is instead that
numbered source appears — record which, since `PravahaCliTest.explainCodegenSaysSoWhenAQueryWillRunInterpreted`
asserts the diagnostic form.)

## API-039 — an unknown level is a usage error
**Intent:** the `default ->` arm throws `Args.UsageException`, which `PravahaCli` maps to exit 2.
The message enumerates the four valid values rather than saying "invalid".
**Falsifier:** exit 1, or a message that does not list the alternatives.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql "SELECT user_id FROM txn" --schema SCHEMA4 --level weird >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is exactly
`--level must be logical, physical, codegen or all; got 'weird'`; `out.txt` empty.

## API-040 — level names are case-sensitive
**Intent:** the switch compares exact strings, so `PHYSICAL` is not `physical`. Enumerated because
API-039 covers only a nonsense value.
**Falsifier:** `PHYSICAL` producing a plan.
**Setup:** `H-CLI`.
**Steps:** `pravaha explain --sql "SELECT user_id FROM txn" --schema SCHEMA4 --level PHYSICAL >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is
`--level must be logical, physical, codegen or all; got 'PHYSICAL'`.

### F. `run` — files in, files out (API-041–053)

## API-041 — the happy path reads six rows and writes three
**Intent:** the whole engine in one command: parse, plan, lane execution, sink. The counts and the
file contents are the product's smallest end-to-end claim.
**Falsifier:** any count other than `6 in, 3 out`, any row order other than file order, or a fourth
row in the output.
**Setup:** `H-CLI`, `/tmp/big.csv` absent.
**Steps:**
```
pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN \
            --out /tmp/big.csv --out-schema OUTSCHEMA >out.txt 2>err.txt; echo $?
cat /tmp/big.csv
```
**Expected:** exit `0`; `err.txt` empty. `out.txt` line 1 is `ok  6 in, 3 out` and line 2 is
`  plan <p> us, execute <e> us` with both integers ≥ 0. `/tmp/big.csv` is exactly:
```
alice,500
dave,150
frank,1200
```
Arithmetic, per the harness: kept = rows 1, 4, 6 (`500 > 100`, `150 > 100`, `1200 > 100`, all
`COMPLETED`); dropped = row 2 (`50 > 100` false), row 3 (`PENDING`), row 5 (`75 > 100` false).
`6 - 3 = 3` dropped.
**Vacuity:** the three kept rows straddle the two predicates — removing the `amount` comparison
would yield 5 rows (1,2,4,5,6 are `COMPLETED`), removing the `status` comparison would yield 4
(1,3,4,6). No single predicate being dropped produces 3, so the count alone distinguishes all three
states.

## API-042 — `--sql` missing
**Intent:** first of the five required options of `run`, each enumerated so a reordering or a
silent default is visible.
**Falsifier:** a different option named, or exit 1.
**Setup:** `H-CLI`.
**Steps:** `pravaha run --schema SCHEMA4 --in IN --out /tmp/o.csv --out-schema OUTSCHEMA 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--sql is required. Supplied: [schema, in, out, out-schema]`
(the `Supplied:` list is a `LinkedHashMap` key set, so it is in the order the options were typed).
`/tmp/o.csv` is not created.

## API-043 — `--schema` missing
**Intent:** as API-042, second position.
**Falsifier:** a schema being inferred from the file.
**Setup:** `H-CLI`.
**Steps:** `pravaha run --sql FILTERSQL --in IN --out /tmp/o.csv --out-schema OUTSCHEMA 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--schema is required. Supplied: [sql, in, out, out-schema]`.

## API-044 — `--in` missing
**Intent:** as API-042, third position.
**Falsifier:** stdin being read instead (there is no stdin path in `RunCommand`).
**Setup:** `H-CLI`.
**Steps:** `echo "1,alice,500,COMPLETED" | pravaha run --sql FILTERSQL --schema SCHEMA4 --out /tmp/o.csv --out-schema OUTSCHEMA 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--in is required. Supplied: [sql, schema, out, out-schema]`.
The piped row is ignored — pinning that **the CLI has no stdin input path at all**.

## API-045 — `--out-schema` missing
**Intent:** as API-042, fourth position. This is also the option the QUICKSTART omits (API-053).
**Falsifier:** the output schema defaulting to the plan's output schema, which would make the flag
optional and API-053 pass.
**Setup:** `H-CLI`.
**Steps:** `pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN --out /tmp/o.csv 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--out-schema is required. Supplied: [sql, schema, in, out]`.

## API-046 — `--out` missing
**Intent:** the fifth and last required option.
**Falsifier:** results printed to stdout instead (there is no such fallback).
**Setup:** `H-CLI`.
**Steps:** `pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN --out-schema OUTSCHEMA >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--out is required. Supplied: [sql, schema, in, out-schema]`;
`out.txt` empty — no rows leak to stdout.

## API-047 — `--in` naming a file that does not exist
**Intent:** the source plugin opens the path during `source.open()`; the failure must name the path
and must not be a bare `NoSuchFileException` with no context.
**Falsifier:** exit 0, a zero-row success, or a stack trace as the entire message.
**Setup:** `H-CLI`, `/tmp/absent.csv` guaranteed absent.
**Steps:** `pravaha run --sql FILTERSQL --schema SCHEMA4 --in /tmp/absent.csv --out /tmp/o.csv --out-schema OUTSCHEMA >out.txt 2>err.txt; echo $?`
**Expected:** exit `1` (a `PravahaException` with a `PRV-5xxx` plugin code, or the
`RuntimeException` arm printing `<ExceptionClass>: <message>`); `err.txt` contains the string
`/tmp/absent.csv`; `out.txt` contains no `ok` line. Record the exact code — a missing input file is
an operator's most common mistake and it should have one.

## API-048 — `--in` naming a directory
**Intent:** the same path with a different `IOException` underneath. Enumerated separately because
"not a file" and "no such file" fail in different places.
**Falsifier:** the directory being read as an empty file and the run reporting `ok  0 in, 0 out`.
**Setup:** `H-CLI`; `mkdir -p /tmp/adir`.
**Steps:** `pravaha run --sql FILTERSQL --schema SCHEMA4 --in /tmp/adir --out /tmp/o.csv --out-schema OUTSCHEMA >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `err.txt` names `/tmp/adir`; `out.txt` has no `ok` line. An `ok  0 in, 0 out`
here would be the worst outcome — a silent empty answer — and is the falsifier.

## API-049 — `--out` under a directory that does not exist
**Intent:** the sink is configured and opened **before** the pipeline runs, so an unwritable
destination should be discovered before six rows of work, not after.
**Falsifier:** exit 0; or the work being done and the failure arriving only at `sink.write`.
**Setup:** `H-CLI`; `/tmp/nodir` guaranteed absent.
**Steps:** `pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN --out /tmp/nodir/out.csv --out-schema OUTSCHEMA >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `err.txt` names `/tmp/nodir/out.csv`; `out.txt` has no `ok` line. Record
whether the failure is reported at open or at write — if the timings on a successful run show
`execute` work happening first, the sink is validated too late.

## API-050 — `--out` into a directory the user cannot write
**Intent:** permission, as distinct from absence.
**Falsifier:** exit 0, or a message that does not mention permission or the path.
**Setup:** `H-CLI`; `mkdir -p /tmp/ro && chmod 500 /tmp/ro` (and the test not running as root —
assert `id -u` is non-zero first, or the case is vacuous).
**Steps:** `pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN --out /tmp/ro/out.csv --out-schema OUTSCHEMA >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `err.txt` names `/tmp/ro/out.csv` and carries a permission-denied cause;
no file appears in `/tmp/ro`.
**Vacuity:** the `id -u != 0` precondition is what stops this passing trivially — as root the write
succeeds and the case must be reported as not run rather than as passed.

## API-051 — `--out` naming an existing directory
**Intent:** the third destination failure: the path exists and is the wrong kind of thing.
**Falsifier:** a file being created inside the directory, or exit 0.
**Setup:** `H-CLI`; `mkdir -p /tmp/outdir`.
**Steps:** `pravaha run --sql FILTERSQL --schema SCHEMA4 --in IN --out /tmp/outdir --out-schema OUTSCHEMA >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `err.txt` names `/tmp/outdir`; `ls /tmp/outdir` is still empty.

## API-052 — a query that throws mid-run exits non-zero and prints no `ok`
**Intent:** the regression `QueryRunner` documents in a comment: the command once printed
`ok  3 in, 1 out` and exited 0 for a query that had thrown, because the lane's failure surfaced only
after `execution.close()`. The second `checkHealth()` after close is the fix, and this is the case
that holds it.
**Falsifier:** exit 0, **or** an `ok` line on stdout, **or** an output file containing the rows that
happened to get through before the throw. Any of the three is the old bug.
**Setup:** `H-CLI`. Query: `SELECT user_id, txn_id / (amount - 500) AS r FROM txn`, out-schema
`'user_id:STRING,r:INT64'`.
**Steps:**
```
pravaha run --sql "SELECT user_id, txn_id / (amount - 500) AS r FROM txn" \
            --schema SCHEMA4 --in IN --out /tmp/div.csv --out-schema 'user_id:STRING,r:INT64' \
            >out.txt 2>err.txt; echo $?
```
**Expected:** exit `1`. Row 1 is `1,alice,500,COMPLETED`, so the divisor is `500 - 500 = 0` and
integer division throws. `err.txt` contains `ArithmeticException` or the engine's wrapping of it
and names `/ by zero`; `out.txt` contains **no** line beginning `ok`; `/tmp/div.csv` contains no
data rows.
**Vacuity:** the arithmetic is the load-bearing part — rows 2–6 have divisors
`50-500 = -450`, `900-500 = 400`, `150-500 = -350`, `75-500 = -425`, `1200-500 = 700`, all non-zero.
Exactly one of six rows throws, so a run that swallowed the failure would report `6 in, 5 out` and
look entirely healthy. Assert the absence of that line, not merely the presence of an error.

## API-053 — the QUICKSTART invocation of `run` does not work as printed
**Intent:** `docs/QUICKSTART.md` §2 tells a first-time user to run `pravaha run` with `--sql`,
`--schema`, `--stream`, `--in` and `--out` — and no `--out-schema`, which `RunCommand` requires.
The schema it gives is also two columns for a four-column file. The documentation and the code
disagree, and this pins the disagreement rather than describing it.
**Falsifier:** the command succeeding and printing the three rows the QUICKSTART shows.
**Setup:** `H-CLI`, `cd examples/01-filter-and-project`.
**Steps:**
```
pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
            --schema "user_id:STRING,amount:INT64" \
            --stream txn --in transactions.csv --out out.csv >out.txt 2>err.txt; echo $?
```
**Expected:** exit `2`; `err.txt` is `--out-schema is required. Supplied: [sql, schema, stream, in, out]`;
`out.csv` is never created, so the QUICKSTART's next line (`cat out.csv`) fails too. The working
form is the one in `examples/01-filter-and-project/README.md` (API-041). Two defects in one
command: the missing option, and — once it is supplied — a two-field schema against a four-field
file, which would decode `alice` as an `INT64`.

### G. The commands that talk to a server (API-054–066)

## API-054 — `register` reports name, state, fingerprint and the sharing note
**Intent:** the output contract of `register`, including the deliberate line about fingerprint
sharing that tells an operator whether they got a new computation or joined an existing one.
**Falsifier:** a missing fingerprint, a state other than the registry's, or the sharing line absent.
**Setup:** `H-SRV`.
**Steps:**
```
pravaha register --name by_user --sql "SELECT user_id, amount FROM txn WHERE amount > 100" --keys 0 >out.txt 2>err.txt; echo $?
```
**Expected:** exit `0`; `err.txt` empty; `out.txt` line 1 is
`registered by_user  state=RUNNING  fingerprint=<8-to-16 hex chars>` and line 2 is exactly
`a query with the same fingerprint is the same computation, shared`.

## API-055 — two names on one fingerprint report the same fingerprint
**Intent:** the note in API-054 is only useful if it is true. Byte-identical SQL registered twice
under two names must yield one fingerprint.
**Falsifier:** two different fingerprints for identical SQL, or the second registration being
refused as a duplicate.
**Setup:** `H-SRV` with `by_user` already registered (API-054).
**Steps:** `pravaha register --name by_user_2 --sql "SELECT user_id, amount FROM txn WHERE amount > 100" --keys 0`
then `pravaha queries`.
**Expected:** exit `0`; the printed fingerprint equals API-054's character for character;
`pravaha queries` lists **two** rows (`by_user` and `by_user_2`) whose `FINGERPRINT` columns are
equal and whose `ROWS IN` columns are equal at every instant, because there is one computation.
**Vacuity:** compare `ROWS IN` after feeding rows — two independent computations would each count
their own input and could not be relied on to agree; equal fingerprints plus equal counts is the
observation that distinguishes sharing from coincidence.

## API-056 — `--sql-file` reads the query from disk, and an unreadable path is a usage error
**Intent:** `sqlFrom` prefers `--sql-file` when present and converts an `IOException` into a
`UsageException` — so a missing file exits 2, not 1.
**Falsifier:** exit 1 for the missing file, or `--sql` winning when both are given.
**Setup:** `H-SRV`; `printf 'SELECT user_id, amount FROM txn WHERE amount > 100' > /tmp/q.sql`.
**Steps:**
1. `pravaha register --name from_file --sql-file /tmp/q.sql --keys 0; echo $?`
2. `pravaha register --name from_file2 --sql-file /tmp/absent.sql --keys 0 2>err.txt; echo $?`
3. `pravaha register --name from_file3 --sql-file /tmp/q.sql --sql "SELECT 1" --keys 0; echo $?`
**Expected:** (1) exit `0`, fingerprint equal to API-054's (same SQL text after `.strip()`).
(2) exit `2`, `err.txt` is `cannot read /tmp/absent.sql: /tmp/absent.sql` plus the
`NoSuchFileException` detail — the path appears and no stack trace does. (3) exit `0` and the
fingerprint again equals API-054's, proving `--sql-file` wins over `--sql` when both are present.

## API-057 — `--keys` defaults to `0` and a non-numeric ordinal fails as an engine error
**Intent:** `args.get("keys", "0")` split on commas and passed through `Integer.parseInt`, with no
validation of range. Three inputs, three outcomes.
**Falsifier:** `--keys` absent producing an unkeyed registration, or a `NumberFormatException`
escaping as exit 2 rather than 1.
**Setup:** `H-SRV`.
**Steps:**
1. `pravaha register --name k_default --sql "SELECT user_id, amount FROM txn" ; echo $?`
2. `pravaha register --name k_bad --sql "SELECT user_id, amount FROM txn" --keys a; echo $?`
3. `pravaha register --name k_range --sql "SELECT user_id, amount FROM txn" --keys 9; echo $?`
**Expected:** (1) exit `0` — key column `[0]`. (2) exit `1` with
`NumberFormatException: For input string: "a"` on stderr, from the `RuntimeException` arm of
`PravahaCli.run`; it is a command-line mistake reported as a runtime failure, which is worth
recording against the CLI's "scripts distinguish those" claim. (3) exit `1` with the engine's own
refusal naming the ordinal `9` against a two-column output — record the PRV code.

## API-058 — `queries` on a server with nothing registered says so
**Intent:** the empty branch prints a sentence rather than an empty table, and still exits 0.
**Falsifier:** an empty stdout, a header with no rows, or a non-zero exit.
**Setup:** `H-SRV`, freshly started, nothing registered.
**Steps:** `pravaha queries >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` is exactly `no continuous queries are registered`; `err.txt` empty.

## API-059 — `queries` prints a tab-separated table with a header
**Intent:** the listing format, which a script will parse with `cut -f`.
**Falsifier:** spaces instead of tabs, a missing header, or columns in a different order.
**Setup:** `H-SRV` with `by_user` and `by_user_2` registered (API-054, API-055).
**Steps:** `pravaha queries >out.txt 2>err.txt; echo $?` then `cut -f1 out.txt`.
**Expected:** exit `0`; line 1 is `NAME<TAB>STATE<TAB>FINGERPRINT<TAB>ROWS IN`; two further lines,
each with exactly three tab characters; `cut -f1` yields `NAME`, `by_user`, `by_user_2`. Row order
is the registry's `names()` order, i.e. registration order.

## API-060 — `query` prints a header, the rows, and a trailing count
**Intent:** the read path's output contract: bold header of column names, one tab-separated line per
row, then `N rows` (singular `1 row` at one).
**Falsifier:** a missing header line, a count that disagrees with the rows printed, or `1 rows`.
**Setup:** `H-SRV` with `by_user` registered and three rows fed to `txn`:
`(10,u1,300,COMPLETED)`, `(11,u2,50,COMPLETED)`, `(12,u3,700,COMPLETED)`. By the registered
predicate `amount > 100`, `u1` and `u3` are in the view and `u2` is not: `3 - 1 = 2` rows.
**Steps:** `pravaha query --sql "SELECT user_id, amount FROM by_user" >out.txt 2>err.txt; echo $?`
**Expected:** exit `0`; `out.txt` line 1 `user_id<TAB>amount`; lines 2–3 `u1<TAB>300` and
`u3<TAB>700` (order as scanned); line 4 exactly `2 rows`. With a single-row result the last line
reads `1 row`.
**Vacuity:** the count line is computed from the rows actually iterated, so it cannot agree with the
body unless both come from the same result; feeding a fourth row that fails the predicate must leave
the output unchanged, which is what proves the view — not the input — is being read.

## API-061 — `--params` coerces long, then double, then string
**Intent:** `ServerCommand.coerce` tries `Long`, then `Double`, then gives up and keeps the text.
Three values in one call enumerate all three branches, and the third is the one that silently
changes a type.
**Falsifier:** `007` arriving as the string `007` (it becomes the long `7`), or `1e3` arriving as a
string, or `u1` arriving as a number.
**Setup:** `H-SRV` with a registered parameterised view; query
`SELECT user_id, amount FROM by_user WHERE user_id = ? AND amount > ?`.
**Steps:** `pravaha query --sql "SELECT user_id, amount FROM by_user WHERE user_id = ? AND amount > ?" --params "u1,200"; echo $?`
then repeat with `--params "u1,2e2"` and with `--params "u1,007"`.
**Expected:** all three exit `0` and return the single row `u1<TAB>300` (`300 > 200` and
`300 > 200.0` and `300 > 7`), and each prints `1 row`. Then the negative: `--params "u1, 200"` (a
space after the comma) also works, because `coerce` is given `value.strip()`. Record whether a
parameter intended as the string `"007"` can be sent at all — it cannot, and that is the finding.

## API-062 — `query` against a view that does not exist exits 1 with `PRV-4023` and names nothing else
**Intent:** two things at once: the exit code for an engine refusal, and that the refusal for a name
this server does not serve is the **serving** layer's, whatever else is registered.
**Falsifier:** exit 0 (the defect the brief names — a query that threw once printed `ok` and exited
0); a message that does not carry the PRV code; `PRV-2002`, which is the SQL planner answering a
question about a view; or a message that lists the views this server serves.
**Setup:** `H-SRV` with `by_user` and `by_user_2` registered.
**Steps:** `pravaha query --sql "SELECT * FROM nope" >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `out.txt` empty — in particular it contains no `0 rows` line, because the
failure happens before iteration; `err.txt` carries `PRV-4023` and the name `nope`, and **does not**
contain `by_user`.

**Corrected twice, and both corrections are findings.** The case originally expected the message to
list every view this server serves; SX-5 removed that list, because it is the node's whole inventory
handed to whoever mistypes a name. And it originally disagreed with API-152 about the code — API-152
recorded that `PRV-4023` fired only over an empty catalogue and the planner's `PRV-2002` otherwise,
which is what executing it showed and what finding **API-F6** recorded. L-3 then fixed the engine,
not the case: the code no longer depends on whether some unrelated view happens to exist, and
API-062's original `PRV-4023` is what a caller now gets.

## API-063 — `drop`, `pause` and `resume` print `dropped`, `pauseped` and `resumeped`
**Intent:** `lifecycle` prints `Ansi.good(action + "ped ") + name`. `"drop" + "ped"` is correct by
luck; the other two are not words. A user-visible defect on the only three verbs that change a
running system.
**Falsifier:** `paused` and `resumed` appearing (which would mean it was fixed — update the case).
**Setup:** `H-SRV` with `by_user` registered and RUNNING.
**Steps:**
```
pravaha pause  --name by_user >p.txt; echo $?
pravaha resume --name by_user >r.txt; echo $?
pravaha drop   --name by_user >d.txt; echo $?
pravaha queries
```
**Expected:** all three exit `0`. `p.txt` is `pauseped by_user`; `r.txt` is `resumeped by_user`;
`d.txt` is `dropped by_user`. The final `queries` shows `by_user` gone (and, on a server that also
has `by_user_2`, that name still present, since a computation is released only when its **last**
name is dropped).

## API-064 — a lifecycle verb on an unknown name exits 1 and lists every query
**Intent:** the `PRV-8002` path, and the second disclosure site: `QueryRegistry.require` appends
`this node has ` + every registered name.
**Falsifier:** exit 0 for a no-op drop, or a message without the PRV code.
**Setup:** `H-SRV` with `by_user` and `by_user_2` registered.
**Steps:** `pravaha drop --name ghost >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `out.txt` empty (no `dropped ghost` line); `err.txt` contains
`PRV-8002  no query named 'ghost' is registered; this node has [by_user, by_user_2]`. Both existing
names are disclosed to a caller who named neither.

## API-065 — `subscribe --limit N` stops after N rows and marks commit boundaries
**Intent:** the streaming path's CLI contract: one `-- commit, N rows` line per commit, and `--limit`
closing the subscription through the watcher thread.
**Falsifier:** more than `N` data rows printed, no commit markers, a non-zero exit, or the command
hanging after the limit is reached.
**Setup:** `H-SRV`, `by_user` registered, subscriber started before any row is fed.
**Steps:**
1. `pravaha subscribe --view by_user --limit 2 >out.txt 2>err.txt &`
2. Feed `(10,u1,300,COMPLETED)`, wait for the commit, then `(12,u3,700,COMPLETED)`.
3. `wait`; `echo $?`
**Expected:** exit `0`. `out.txt` line 1 is
`subscribed to by_user; changes print as they are committed. Ctrl-C to stop.`; then `u1<TAB>300`,
then `-- commit, 1 row` (singular); then `u3<TAB>700`, then `-- commit, 1 row`. Exactly
`1 + 1 = 2` data rows, matching `--limit 2`, and the process exits on its own.
**Vacuity:** the subscriber is started **before** any row is fed, so it cannot be satisfied by
pre-existing view contents; and feeding a third row after the limit is reached must produce no
further output — a subscription that had not actually closed would print it.

## API-066 — `--filter` without an `=` is a usage error
**Intent:** `subscribe` parses `col=val` pairs itself and throws `UsageException` for a pair with no
`=`, before connecting.
**Falsifier:** exit 1, a connection attempt, or the malformed pair being silently dropped.
**Setup:** `H-CLI` (no server needed — the refusal precedes `connect`).
**Steps:** `pravaha subscribe --view by_user --filter "user_id" >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--filter takes column=value pairs, got 'user_id'`;
`out.txt` empty; no TCP connection to 19090. Then the mixed case
`--filter "user_id=u1,status"` must fail the same way on the second pair, having accepted the first.

### H. Exit codes, streams, transport and hostile input (API-067–075)

## API-067 — with no server running, every server command exits 1
**Intent:** six commands, one expectation. `PravahaFlightClient.connect` builds a lazy gRPC channel,
so the failure appears on the first call and reaches `fail(e)`, which exits 1. None of these may
exit 0 and none may exit 2.
**Falsifier:** any of the six exiting 0 (a silent success against nothing), or 2 (which would tell a
script the command line was wrong when the server was simply down), or hanging indefinitely.
**Setup:** `H-CLI`, nothing listening on 19090, `--url grpc://localhost:19090` (the default).
**Steps:** run each and record `$?`:
```
pravaha queries
pravaha query     --sql "SELECT 1"
pravaha register  --name x --sql "SELECT user_id FROM txn" --keys 0
pravaha drop      --name x
pravaha pause     --name x
pravaha resume    --name x
pravaha subscribe --view x
```
**Expected:** all seven invocations exit `1`. Each writes a message to **stderr** naming
`localhost:19090` (either the SDK's `cannot connect to localhost:19090: …` or a Flight
`UNAVAILABLE` description) and writes nothing to stdout. Each returns within the client's connect
timeout rather than blocking forever — record the actual wall time for each, since `subscribe`
blocks in `subscription.run()` and is the one that could hang.

## API-068 — a `--url` with no scheme defaults to TLS and fails against a plaintext server
**Intent:** `Endpoint.parse` starts with `tls = true` and only a recognised scheme turns it off, so
`--url localhost:19090` is **not** the same as the default `grpc://localhost:19090`. A user who
copies a host:port out of a log gets a TLS handshake against a plaintext socket.
**Falsifier:** `localhost:19090` behaving identically to `grpc://localhost:19090`.
**Setup:** `H-SRV` (Flight is plaintext — `pravaha.flight.tls.certificate` is empty).
**Steps:**
1. `pravaha queries --url grpc://localhost:19090; echo $?`
2. `pravaha queries --url localhost:19090 >out.txt 2>err.txt; echo $?`
**Expected:** (1) exit `0` with the listing. (2) exit `1`; `err.txt` shows a transport/handshake
failure, not a Pravaha PRV code. The contrast is the finding: the same address, two outcomes, and
nothing in the message says "this client tried TLS".

## API-069 — an unknown `--url` scheme is refused by the client
**Intent:** the `default ->` arm of `Endpoint.parse`'s scheme switch.
**Falsifier:** the scheme being ignored and a connection attempted anyway.
**Setup:** `H-CLI`.
**Steps:** `pravaha queries --url "ftp://localhost:19090" >out.txt 2>err.txt; echo $?`; then
`pravaha queries --url "" 2>err2.txt; echo $?`; then `pravaha queries --url "grpc://" 2>err3.txt; echo $?`
**Expected:** all three exit `1`. `err.txt` says `'ftp' is not a known scheme` and quotes the
connection string; `err2.txt` says it is empty — **except** that `--url ""` is stored as a blank
value and `args.get("url", default)` uses `getOrDefault`, which returns the blank rather than the
default, so the blank reaches `Endpoint.parse`; `err3.txt` says it names no host. Record which of
the three produces a message that names `--url` — a client-layer message that never mentions the
flag the user typed is the usability defect here.

## API-070 — `--token` sends a bearer credential in clear text without a word
**Intent:** `ServerCommand.connect` calls `options.token(token).allowInsecureToken(true)`
unconditionally — and `allowInsecureToken()` is read by **nothing** in the SDK. The guard exists as
a field and a builder method and enforces nothing, so a token typed on a `grpc://` URL travels
unencrypted and silently.
**Falsifier:** a warning on stderr, or a refusal, when a token is paired with a plaintext URL.
**Setup:** `H-SRVA` (authenticating) but with `pravaha.flight.tls` still empty, so Flight is
plaintext.
**Steps:** `pravaha queries --url grpc://localhost:19090 --token "ann-token-0123456789" >out.txt 2>err.txt; echo $?`
while capturing loopback traffic (`tcpdump -i lo -A port 19090`).
**Expected:** exit `0` with the listing; `err.txt` **empty** — no warning of any kind; and the
capture contains the literal bytes `Bearer ann-token-0123456789` in a gRPC header frame. That
`err.txt` is empty is the assertion; the capture is the proof it should not have been.

## API-071 — `PRAVAHA_CLI_TRACE` adds a stack trace to stderr and nothing to stdout
**Intent:** the only debugging switch the CLI has, and the guarantee that it changes stderr only.
**Falsifier:** the trace appearing on stdout, or the exit code changing when the variable is set.
**Setup:** `H-CLI`, nothing on 19090.
**Steps:**
1. `pravaha queries >a.out 2>a.err; echo $?`
2. `PRAVAHA_CLI_TRACE=1 pravaha queries >b.out 2>b.err; echo $?`
**Expected:** both exit `1`; `a.out` and `b.out` are both empty and identical; `b.err` begins with
the same first line as `a.err` and then contains at least one line matching `^\s+at ` (a stack
frame), while `a.err` contains none. Note that the variable is consulted only in
`ServerCommand.fail`, so it does nothing for `validate`, `explain` or `run` — verify that
`PRAVAHA_CLI_TRACE=1 pravaha validate --sql --nonsense --schema SCHEMA4` (API-021) still prints no
trace, which is the gap.

## API-072 — data goes to stdout and diagnosis goes to stderr, for every command
**Intent:** one case enumerating the split across the whole CLI, because a pipeline
(`pravaha query … | cut -f2`) is broken by a single diagnostic line on the wrong stream.
**Falsifier:** any of the rows below landing on the wrong stream.
**Setup:** `H-SRV` with `by_user` registered and the three rows of API-060 fed.
**Steps:** run each command with `>out 2>err` and classify every byte:

| Command | must be on **stdout** | must be on **stderr** |
|---|---|---|
| `pravaha` (no args) | the usage block | nothing |
| `pravaha frobnicate` | the usage block | `unknown command: frobnicate` |
| `pravaha validate` (ok) | `valid …`, `  output: …` | nothing |
| `pravaha validate` (bad SQL) | nothing | `PRV-…` + help URL |
| `pravaha explain` (ok) | header + plan | nothing |
| `pravaha run` (ok) | `ok  6 in, 3 out`, timings | nothing |
| `pravaha query` (ok) | header, rows, `N rows` | nothing |
| `pravaha query` (refused) | nothing | `PRV-4023 …` |
| `pravaha queries` | header + rows, or the empty sentence | nothing |
| `pravaha register` | `registered …` + sharing note | nothing |
| `pravaha subscribe` | the `subscribed to …` banner, rows, `-- commit, …` | nothing |
| `pravaha drop/pause/resume` | `…ped <name>` | nothing |

**Expected:** exactly as tabulated. Two things to flag if observed: the `subscribed to …` banner and
the `-- commit, N rows` markers are **annotations on stdout**, so `pravaha subscribe --view v | …`
feeds a consumer three kinds of line; and the `run`/`register` second lines are likewise
commentary on stdout. Record them — they are defensible, but a consumer must be told.

## API-073 — unicode survives arguments, data and output
**Intent:** the CLI passes argument strings to the planner and rows through the arena unchanged. A
non-ASCII identifier, a non-ASCII literal and non-ASCII data in one case.
**Falsifier:** mojibake in the output file, a row dropped, or a parse failure on a valid identifier.
**Setup:** `H-CLI`, `LANG=C.UTF-8`. Input `/tmp/uni.csv`:
```
1,Ashutosh,500,COMPLETED
2,元気,900,COMPLETED
3,Ünïcödé,150,COMPLETED
4,🚀rocket,50,COMPLETED
```
**Steps:**
```
pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
  --schema SCHEMA4 --in /tmp/uni.csv --out /tmp/uni-out.csv --out-schema OUTSCHEMA; echo $?
xxd /tmp/uni-out.csv | head
```
**Expected:** exit `0`; `ok  4 in, 3 out` (`500 > 100`, `900 > 100`, `150 > 100` kept; `50 > 100`
dropped → `4 - 1 = 3`). `/tmp/uni-out.csv` is exactly
`Ashutosh,500\n元気,900\nÜnïcödé,150\n`, and `xxd` shows `元` as the three bytes `e5 85 83` — not
`3f` (`?`) and not a pair of Latin-1 bytes. Then repeat with the rocket row raised to `150` and
confirm a four-byte astral-plane codepoint (`f0 9f 9a 80`) round-trips.
**Vacuity:** the row that is dropped is ASCII-named and the rows that survive are not, so a run that
silently dropped every non-ASCII row would report `4 in, 0 out` rather than `4 in, 3 out`.

## API-074 — a very large `--sql` is accepted or refused, but never truncated
**Intent:** argument size limits are the operating system's (`ARG_MAX`), not the CLI's, and the
failure mode at the boundary must not be a silently shortened query.
**Falsifier:** a query longer than some threshold being planned as a prefix of itself — i.e. exit 0
with an output schema that does not match the full statement.
**Setup:** `H-CLI`. Build `SELECT user_id FROM txn WHERE amount IN (1, 2, …, N)` for
`N = 1_000`, `10_000` and `100_000`; the last is roughly `100_000 × 7 = 700_000` characters, near
typical `ARG_MAX` of 2 MiB for the whole environment.
**Steps:** for each `N`: `pravaha validate --sql "$BIG" --schema SCHEMA4 >out.txt 2>err.txt; echo $?`
Then repeat the largest through `--sql-file` on `pravaha register`, which has no argument limit.
**Expected:** for each `N`, exactly one of: exit `0` with `output: [user_id STRING NOT NULL]`; or
exit `1` with a PRV code from the planner; or the shell itself failing with
`Argument list too long` before `pravaha` runs (exit `126`/`127`, and no Pravaha output at all). A
fourth outcome — exit 0 after the SQL was truncated — is the falsifier. Record where the planner's
own limit bites, and confirm the `--sql-file` route gets further than the argument route.

## API-075 — colour is suppressed whenever a machine is reading
**Intent:** `Ansi.detect()` returns false when `NO_COLOR` is set, when `TERM` is unset or `dumb`, or
when `System.console()` is null (which is exactly the redirected case). Escape codes in a CI
transcript hide the string somebody is grepping for.
**Falsifier:** a `0x1B` byte in any redirected output under any of the three conditions.
**Setup:** `H-CLI`.
**Steps:** run `pravaha validate --sql "SELECT nope FROM txn" --schema SCHEMA4 2>err.txt` under each
of: (a) `NO_COLOR=1` with a tty; (b) `TERM=dumb`; (c) `TERM` unset; (d) output redirected to a file
with `TERM=xterm-256color` and `NO_COLOR` unset; (e) under `script -qc` so a pty exists **and**
output is a terminal.
**Expected:** in (a)–(d), `grep -c $'\x1b' err.txt` is `0` and the first line is exactly
`PRV-2002 …`. In (e) the same line is wrapped in `\x1b[31m … \x1b[0m` — the colour path is
reachable, which is what stops (a)–(d) from passing vacuously against a build where colour was
removed altogether.

---

## REST (API-076–API-125)

Six documented paths, plus `/actuator/*` and the OpenAPI document. `api/openapi.lock.json` is the
contract of record and lists exactly: `GET /api/v1/status`, `GET /status`, `GET|POST /api/v1/streams`,
`GET /api/v1/streams/{name}`, `POST /api/v1/queries/validate`, `POST /api/v1/queries/explain`.
Every case below states the status, the body shape, and — in section D — whether it answers without
a credential.

### A. Path and method (API-076–086)

## API-076 — `GET /api/v1/status` returns the node's identity and uptime as JSON
**Intent:** the machine-readable half of the status pair; the console's first call.
**Falsifier:** a missing field, `uptimeSeconds` as a string, or a plugin list that is not an array.
**Setup:** `H-SRV`, up for at least 3 s.
**Steps:** `curl -si localhost:18080/api/v1/status`
**Expected:** `200`; `Content-Type: application/json`; a body parsing to an object with exactly the
six `NodeStatus` fields — `instanceId` (`"pravaha-node-01"` from `pravaha.node.id`), `version`,
`engineState` (`"RUNNING"`), `uptimeSeconds` (a JSON number >= 3), `registeredQueries`, `plugins`
(an array). Note the field name: `registeredQueries` is filled from `catalog.size()`, the count of
**streams**, so with the one declared stream it reads `1` while zero queries are registered. Pin
that — the name and the value disagree.

## API-077 — `GET /status` returns a self-contained HTML page
**Intent:** the page that must render when the console process is down and nothing else works. No
template engine, no static asset, no external font, no script.
**Falsifier:** any `<script`, `<link`, `src=`, or `http`-scheme URL in the body; or a
`Content-Type` that is not `text/html`.
**Setup:** `H-SRV`.
**Steps:** `curl -si localhost:18080/status -o page.html` then
`grep -Eic '<script|<link|src=|https?://' page.html`
**Expected:** `200`; `Content-Type: text/html;charset=UTF-8`; grep count `0`; the body contains
`<title>Pravaha node pravaha-node-01</title>`, the four rows `State`, `Version`, `Uptime`,
`Streams`, and the state rendered with `class="ok"` because `stateClass("RUNNING")` is `ok`. The
`Streams` row's value equals `registeredQueries` from API-076 — the same field, so the two
representations cannot disagree.

## API-078 — `GET /api/v1/streams` lists the declared streams with their fields
**Intent:** the read half of the catalog, and the field rendering (`sqlName()`, not an enum name).
**Falsifier:** an internal enum constant in `type`, a `fieldCount` that disagrees with `fields`
length, or ordinals out of order.
**Setup:** `H-SRV` (one declared stream, `txn`, four fields).
**Steps:** `curl -s localhost:18080/api/v1/streams | jq .`
**Expected:** `200`; an array of length `1`; `[0].name == "txn"`, `[0].version == 1`,
`[0].fieldCount == 4`, `[0].fields | length == 4`; the fields in ordinal order `0,1,2,3` with
`type` values `"INT64 NOT NULL"`, `"STRING NOT NULL"`, `"INT64 NOT NULL"`, `"STRING NOT NULL"` and
`nullable` `false` for all four. Record the `nullable` values — Flight reports the same schema with
every field nullable (API-150), and the two surfaces disagree.

## API-079 — `GET /api/v1/streams/{name}` returns one stream
**Intent:** the single-resource read.
**Falsifier:** an array instead of an object, or a different rendering of the same schema.
**Setup:** `H-SRV`.
**Steps:** `curl -s localhost:18080/api/v1/streams/txn | jq .`
**Expected:** `200`; an object byte-equal to element `[0]` of API-078's array.

## API-080 — `POST /api/v1/streams` answers 201 while the locked contract says 200
**Intent:** `StreamController.register` returns `ResponseEntity.status(HttpStatus.CREATED)`, but
`api/openapi.lock.json` records `"responses": ["200"]` for this operation because springdoc
documents the declared return type rather than the entity's status. A generated client that treats
anything but 200 as a failure breaks on the one write the API has.
**Falsifier:** the response being 200 (then the lock is right and this case retires), or the lock
listing 201.
**Setup:** `H-SRV`.
**Steps:**
```
curl -si localhost:18080/api/v1/streams -H 'Content-Type: application/json' \
     -d '{"name":"orders","schema":"order_id:INT64,sku:STRING,qty:INT32"}'
jq '.paths."/api/v1/streams".post.responses' api/openapi.lock.json
```
**Expected:** HTTP `201 Created`, body a `StreamSummary` with `name == "orders"`,
`fieldCount == 3`, `version == 1`; and the lock file showing `[ "200" ]`. Both observations in one
case, because the finding is the difference between them.

## API-081 — `POST /api/v1/queries/validate` answers 200 for a valid query
**Intent:** the editor endpoint. A successful validation carries the output fields and its own
latency.
**Falsifier:** a status other than 200, an empty `outputFields`, or `elapsedMicros` absent.
**Setup:** `H-SRV`.
**Steps:**
```
curl -s localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT user_id, amount FROM txn WHERE amount > 100"}' | jq .
```
**Expected:** `200`; `valid == true`; `diagnostics == []`; `outputFields` of length `2` —
`{name:"user_id", type:"STRING NOT NULL", nullable:false, ordinal:0}` and
`{name:"amount", type:"INT64 NOT NULL", nullable:false, ordinal:1}`; `elapsedMicros` a number.

## API-082 — an invalid query is also 200, with `valid: false`
**Intent:** the deliberate design choice: a syntax error mid-keystroke is a normal editor state, not
an HTTP failure. And the diagnostic carries the PRV code and a help URL.
**Falsifier:** a 400, or a `diagnostics` array without `code`/`helpUrl`.
**Setup:** `H-SRV`.
**Steps:**
```
curl -si localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT nope FROM txn"}'
curl -s  localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"}' | jq -r .diagnostics[0].code
```
**Expected:** the first is `200` with `valid == false`, `diagnostics[0].code == "PRV-2002"`,
`diagnostics[0].severity == "error"`,
`diagnostics[0].helpUrl == "https://docs.pravaha.io/errors/PRV-2002"`, and `outputFields == []`.
The second prints `PRV-2050` (the unbounded-state refusal), also inside a 200. Note that
`diagnostics[0].message` for the first ends `. Known streams: [txn]` — the disclosure of API-116.

## API-083 — `explain` honours `level`, and an unknown level is 400
**Intent:** four values on one query parameter, enumerated: `physical` (the default), `logical`,
`codegen`, and anything else.
**Falsifier:** `codegen` returning a plan instead of Java; an unknown level defaulting silently to
physical; or a level error arriving as a 500.
**Setup:** `H-SRV`. Body `{"sql":"SELECT user_id, amount FROM txn WHERE amount > 100"}` throughout.
**Steps:** POST to `/api/v1/queries/explain` with no `level`, then `?level=physical`,
`?level=logical`, `?level=codegen`, `?level=PHYSICAL`, `?level=`.
**Expected:**
- no `level` and `?level=physical`: `200`, `level == "physical"`, `plan` contains `Scan(txn)`,
  `outputFields` of length 2 (as API-081).
- `?level=logical`: `200`, `level == "logical"`, `plan` contains `Logical`, and
  **`outputFields == []`** — the logical arm returns `List.of()`. Pin it: the same request shape
  returns fields for one level and not another.
- `?level=codegen`: `200`, `level == "codegen"`, `plan` is Java source (contains `class` and
  `ExplainStage`) or begins `-- no generated form:`, and `outputFields` has length 2.
- `?level=PHYSICAL` and `?level=`: `400` with an `ApiError` whose `code` is `PRV-0400` and whose
  message is `level must be 'logical' or 'physical', got 'PHYSICAL'` — note the message omits
  `codegen`, which the endpoint does accept. That is a second finding in the same case.

## API-084 — the wrong method on each documented path is 405
**Intent:** enumerate the method matrix rather than gesture at it. Six paths x the methods they do
not implement.
**Falsifier:** any of these answering 200, or a 404 where the path exists but the method does not
(which tells a client the endpoint is gone rather than misused).
**Setup:** `H-SRV`.
**Steps:** for each row, `curl -si -X <M> localhost:18080<path>`:

| Path | implemented | expected 405 for |
|---|---|---|
| `/api/v1/status` | GET | POST, PUT, PATCH, DELETE |
| `/status` | GET | POST, PUT, PATCH, DELETE |
| `/api/v1/streams` | GET, POST | PUT, PATCH, DELETE |
| `/api/v1/streams/txn` | GET | POST, PUT, PATCH, DELETE |
| `/api/v1/queries/validate` | POST | GET, PUT, PATCH, DELETE |
| `/api/v1/queries/explain` | POST | GET, PUT, PATCH, DELETE |

**Expected:** every cell in the last column returns `405 Method Not Allowed` with an `Allow` header
naming exactly the implemented methods for that path. Record the **body**: these are Spring's own
errors, not `ApiError` (see API-124), so a strict client parsing `code`/`helpUrl` finds neither.
That is `4 + 4 + 3 + 4 + 4 + 4 = 23` requests.

## API-085 — HEAD and OPTIONS on every path
**Intent:** the two methods a proxy, a browser preflight or a health checker sends unprompted.
**Falsifier:** HEAD returning a body, or OPTIONS returning 500.
**Setup:** `H-SRV`.
**Steps:** `curl -sI localhost:18080<path>` and `curl -si -X OPTIONS localhost:18080<path>` for the
six paths.
**Expected:** HEAD mirrors GET's status and headers with a zero-length body on the four GET paths,
and `405` on the two POST-only paths. OPTIONS returns `200` with an `Allow` header listing the
implemented methods for that path. No path returns 500.

## API-086 — near-miss paths are 404, and the 404 body is Spring's, not `ApiError`
**Intent:** trailing slashes, wrong case and unknown segments — the four ways a client mistypes a
path — and the shape of the answer.
**Falsifier:** `/api/v1/Streams` serving the stream list (a case-insensitive route), or a 500.
**Setup:** `H-SRV`.
**Steps:** `curl -si` each of `/api/v1/streams/`, `/api/v1/Streams`, `/API/v1/streams`,
`/api/v2/streams`, `/api/v1/queries`, `/api/v1/queries/validate/`, `/apiv1/status`.
**Expected:** `/api/v1/streams/` returns `200` with the list (Spring matches the trailing slash by
default in this configuration) **or** `404`; record which, because the lock file names the
slash-free form only. Every other path returns `404`. Each 404 body is Spring's default error
object (`timestamp`, `status`, `error`, `path`) — with `spring.mvc.problemdetails.enabled: false`
there is no RFC 7807 body and no `ApiError`, which is the gap API-124 measures.

### B. Request bodies (API-087–098)

## API-087 — malformed JSON is 400 and must not be 500
**Intent:** the parser failing before any controller runs.
**Falsifier:** a 500, or a 200 with `valid:false` (which would mean the body was silently read as
something).
**Setup:** `H-SRV`.
**Steps:**
```
curl -si localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' -d '{"sql": '
curl -si localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' -d 'not json'
curl -si localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' -d '[1,2,3]'
```
**Expected:** all three `400`. The bodies are Spring's `HttpMessageNotReadableException` rendering,
**not** `ApiError` — no `code`, no `helpUrl`. Record the exact body of the first: a client that
parses `.code` gets `null` and will report "unknown error".

## API-088 — a body missing `sql` reaches the planner as null
**Intent:** `ValidateRequest` is a record with no validation, so `{}` binds `sql = null` and
`SqlPlanner.plan(null)` is called. Either a `PravahaException` (-> a 400 `ApiError`) or an
unhandled `NullPointerException` (-> a 500 with Spring's body, and a stack trace in the log).
**Falsifier:** a 200 with `valid: true`.
**Setup:** `H-SRV`.
**Steps:** `curl -si localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' -d '{}'`
and the same with `{"sql":null}`, and against `/api/v1/queries/explain`.
**Expected:** record the status for each of the four. The required outcome is a `400` carrying an
`ApiError`; a `500` is a defect and the case's finding, because a caller omitting a field is a
caller's mistake and the handler's own Javadoc says every non-2xx is an `ApiError`. Whichever is
observed, the body must be valid JSON (API-125).

## API-089 — wrong types in the body
**Intent:** `{"sql": 42}`, `{"sql": []}`, `{"sql": {}}`, `{"sql": true}` — Jackson coerces some of
these and refuses others, and the difference is invisible from the outside unless it is pinned.
**Falsifier:** `{"sql": 42}` being planned as the string `42` and returning 200 `valid:false` with a
parse error, when the client sent a number and deserves to be told so.
**Setup:** `H-SRV`.
**Steps:** POST each of the four to `/api/v1/queries/validate`.
**Expected:** Jackson's default coerces a scalar to `String`, so `{"sql": 42}` and `{"sql": true}`
are expected to reach the planner as `"42"` and `"true"` and return `200` with `valid == false` and
`diagnostics[0].code == "PRV-2001"`; `{"sql": []}` and `{"sql": {}}` are expected to fail
deserialization with `400`. Record all four: the finding is that a type error on the wire and a
syntax error in the SQL are reported identically for two of them.

## API-090 — unknown fields in the body are ignored
**Intent:** Spring Boot's Jackson default is `FAIL_ON_UNKNOWN_PROPERTIES = false`, so a client
sending a field the server does not know gets no warning — which is the right choice for forward
compatibility and worth recording so nobody relies on the opposite.
**Falsifier:** a 400 for an extra field.
**Setup:** `H-SRV`.
**Steps:**
```
curl -s localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT user_id FROM txn","tenant":"acme","level":"codegen","limit":99}' | jq .
```
**Expected:** `200`, `valid == true`, `outputFields` of length 1. The `level` field in the **body**
is ignored entirely — `explain` reads `level` from the **query string** — so a client that puts it
in the body silently gets `physical`. Confirm that against `/api/v1/queries/explain` with
`{"sql":"...","level":"logical"}` and no query parameter: the response must say
`"level":"physical"`.

## API-091 — an empty body
**Intent:** the zero-length case, distinct from `{}`.
**Falsifier:** a 200.
**Setup:** `H-SRV`.
**Steps:** `curl -si -X POST localhost:18080/api/v1/queries/validate -H 'Content-Type: application/json' --data-binary ''`
and the same for `/api/v1/queries/explain` and `/api/v1/streams`.
**Expected:** `400` for all three (`Required request body is missing`), with Spring's error body.
No 500, no stack trace in the response.

## API-092 — a very large body
**Intent:** there is no configured `server.max-http-request-size` for the JSON surface, so the limit
is Tomcat's and then the planner's. The failure at the boundary must be a status, not an OOM.
**Falsifier:** the process exhausting heap, the connection being dropped without a status, or a
truncated SQL being planned.
**Setup:** `H-SRV` with `-Xmx512m` so exhaustion would be visible.
**Steps:** POST `/api/v1/queries/validate` with `sql` built as
`SELECT user_id FROM txn WHERE amount IN (1, ..., N)` at `N = 10_000` (~70 KB),
`N = 1_000_000` (~7 MB), and a 64 MB body.
**Expected:** `N = 10_000` -> `200` (`valid` true or a planner refusal, either is acceptable);
`N = 1_000_000` -> a status within 60 s, either `200` with `valid:false` or `400`/`413`, and the
node still answering `GET /api/v1/status` afterwards; 64 MB -> a `413` or a connection refusal, and
again a live node afterwards.
**Vacuity:** the post-condition — `GET /api/v1/status` returning 200 after each — is the part that
cannot pass with the feature removed; a node that died would fail it.

## API-093 — the wrong `Content-Type`
**Intent:** `@RequestBody` on a JSON-only controller; `text/plain`, `application/xml`,
`application/x-www-form-urlencoded` and a missing header are four different client mistakes.
**Falsifier:** any of them being parsed as JSON and returning 200.
**Setup:** `H-SRV`.
**Steps:** POST the valid body of API-081 to `/api/v1/queries/validate` four times: with
`Content-Type: text/plain`, `application/xml`, `application/x-www-form-urlencoded`, and with the
header removed entirely (`-H 'Content-Type:'`).
**Expected:** `415 Unsupported Media Type` for all four, with an `Accept-Post` or `Accept` header
naming `application/json`. Record whether the body is `ApiError` (it is not — API-124).

## API-094 — `Accept` headers the client sends and the ones it does not
**Intent:** JSON endpoints under `Accept: text/html`, the HTML endpoint under
`Accept: application/json`, and both with no `Accept` at all.
**Falsifier:** `/status` returning JSON, or `/api/v1/status` returning the HTML page, to a caller
whose `Accept` asked for the other.
**Setup:** `H-SRV`.
**Steps:** for `/api/v1/status` and `/status`, send `Accept: application/json`,
`Accept: text/html`, `Accept: */*`, and no `Accept` header.
**Expected:** `/api/v1/status` returns `200 application/json` for `application/json`, `*/*` and no
header, and `406 Not Acceptable` for `Accept: text/html`. `/status` (declared
`produces = TEXT_HTML_VALUE`) returns `200 text/html` for `text/html`, `*/*` and no header, and
`406` for `Accept: application/json`. Two paths, four headers, eight observations — the two 406s
are the ones a badly-configured console will hit.

## API-095 — unicode in the request body round-trips
**Intent:** a non-ASCII identifier and literal through JSON, the planner, and back into the
diagnostic message.
**Falsifier:** mojibake in `diagnostics[0].message`, or a 400 for a well-formed UTF-8 body.
**Setup:** `H-SRV`.
**Steps:** POST to `/api/v1/queries/validate` with `Content-Type: application/json; charset=utf-8`
and the body `{"sql":"SELECT \"顧客\" FROM txn"}`, reading `diagnostics[0].message`.
**Expected:** `200` with `valid == false` and a message quoting `顧客` in correct UTF-8 (bytes
`e9 a1 a7 e5 ae a2`), not `???`. Then the same identifier as a **real** column: register a stream
`uni` with schema `顧客:STRING,amount:INT64` and validate `SELECT 顧客 FROM uni` — expect `200`,
`valid == true`, `outputFields[0].name == "顧客"`, and the same name back from
`GET /api/v1/streams/uni`.

## API-096 — `POST /api/v1/streams` with fields missing
**Intent:** `RegisterStreamRequest` is an unvalidated record: `{"name":"x"}` passes `schema = null`
into `parseSchema`, and `{"schema":"a:INT64"}` passes `name = null` into `StreamSchema.builder`.
**Falsifier:** a stream being registered under the name `null`, or a 200.
**Setup:** `H-SRV`.
**Steps:** POST `{}`, `{"name":"x"}`, `{"schema":"a:INT64"}`, `{"name":"","schema":"a:INT64"}`,
`{"name":"x","schema":""}`.
**Expected:** none of the five results in a stream appearing in `GET /api/v1/streams` — verify after
each. Record each status: the `null` schema is expected to raise an NPE inside `spec.split(",")`
(-> `500`, a defect), the empty schema produces `PRV-5040` from `parseSchema` (-> `500`, because
5040 is in the **PLUGIN** range; see API-122), and the empty name may register a stream named `""`.
Any stream actually created here is a finding in its own right.

## API-097 — a malformed schema spec is a caller's mistake reported as a server error
**Intent:** the HTTP twin of API-033. `parseSchema` throws `PravahaException(PRV-5040)`;
`ErrorCode.Category` puts 5040 in `PLUGIN`; `ApiExceptionHandler.statusFor` maps `PLUGIN` to
`INTERNAL_SERVER_ERROR`. So a typo in a request body returns **500**.
**Falsifier:** a 400 (which would mean the classification was fixed).
**Setup:** `H-SRV`.
**Steps:**
```
curl -si localhost:18080/api/v1/streams -H 'Content-Type: application/json' \
  -d '{"name":"bad","schema":"order_id INT64,sku:STRING"}'
```
**Expected:** `500`, body an `ApiError` with `code == "PRV-5040"`,
`message == "schema entry 'order_id INT64' is not 'name:TYPE'. Example: id:INT64,name:STRING"`,
`helpUrl == "https://docs.pravaha.io/errors/PRV-5040"`, `path == "/api/v1/streams"`. A monitoring
rule that pages on 5xx pages for somebody's typo, and a client that retries 5xx retries forever.
`GET /api/v1/streams` afterwards must still show only `txn` (and `orders` if API-080 ran).

## API-098 — control characters and broken encodings in the body
**Intent:** hostile-ish input on the one endpoint an editor calls on every keystroke burst.
**Falsifier:** a 500 with a stack trace, or a response body that is not valid JSON.
**Setup:** `H-SRV`.
**Steps:** four bodies against `/api/v1/queries/validate`, in order:
(a) a SQL string containing a JSON-escaped NUL (the six characters backslash-u-0-0-0-0) between
`SELECT` and `FROM txn`;
(b) the same position holding a **raw** unescaped NUL byte, built with `printf` and sent with
`curl --data-binary @-`;
(c) a SQL string containing two escaped newlines, an escaped tab, and the line comment
`-- trailing comment` after `SELECT user_id FROM txn`;
(d) a lone unpaired surrogate as the whole SQL value (the six characters backslash-u-d-8-0-0).
**Expected:** (a) `200` with `valid == false` and `diagnostics[0].code == "PRV-2001"` — the escaped
NUL survives JSON decoding and is a SQL parse error. (b) `400`, a JSON syntax error, because a raw
NUL is not legal inside a JSON string. (c) `200` with `valid == true` and one output field, because
the newlines, the tab and the SQL line comment are all legal. (d) `400` from Jackson. No case
returns 500, and all four response bodies parse under `jq -e .`.

### C. Missing names, duplicates and concurrency (API-099–104)

## API-099 — an unknown stream name is 400, not 404, and names every stream that does exist
**Intent:** two findings in one request. `StreamCatalog.require` throws `PRV-2003`, which is in the
`PLANNING` category, which `statusFor` maps to `BAD_REQUEST` — so a resource that does not exist is
reported as a malformed request. And the message appends `Registered: ` plus the whole key set.
**Falsifier:** a 404 (the REST convention, and what a client's `if (404) create()` expects), or a
message that does not enumerate.
**Setup:** `H-SRV` with `txn` and `orders` registered.
**Steps:** `curl -si localhost:18080/api/v1/streams/nope`
**Expected:** `400 Bad Request`; body an `ApiError` with `code == "PRV-2003"`,
`message == "no stream named 'nope'. Registered: [txn, orders]"`,
`helpUrl == "https://docs.pravaha.io/errors/PRV-2003"`, `path == "/api/v1/streams/nope"`, and a
`timestamp`. `ApiIntegrationTest.anUnknownStreamIsA400WithTheErrorCodeAndAHelpUrl` asserts the 400,
so the status is deliberate — record it against the REST convention rather than as a surprise, and
record the disclosure as its own finding: an unauthenticated caller on `H-SRV` learns the complete
stream inventory by guessing one wrong name.

## API-100 — awkward names in the path
**Intent:** the `{name}` segment is passed straight to `catalog.require`, so every encoding question
is the servlet container's.
**Falsifier:** a 500 for any of these, or a traversal reaching outside the catalog.
**Setup:** `H-SRV`.
**Steps:** `curl -si` each of:
`/api/v1/streams/%E9%A1%A7%E5%AE%A2` (the UTF-8 for a registered unicode stream),
`/api/v1/streams/txn%20`, `/api/v1/streams/..%2F..%2Fetc%2Fpasswd`,
`/api/v1/streams/a%2Fb`, `/api/v1/streams/` + a 4096-character name, `/api/v1/streams/TXN`.
**Expected:** the unicode name returns `200` with that stream (given API-095 registered it);
`txn%20` (trailing space) returns `400 PRV-2003` naming `'txn '`; the traversal forms return `400`
from Spring's path handling or `400 PRV-2003` — never a 200, never a file, never a 500; the
4096-character name returns `400 PRV-2003` or `414`; `TXN` returns `400 PRV-2003` because the
catalog is case-sensitive. Every non-2xx body must still be JSON.

## API-101 — registering the same stream name twice is refused
**Intent:** `StreamCatalog.register` refuses when the name exists **at the same version**, and
schema versions are immutable. `parseSchema` always builds version 1, so through this API every
duplicate is a refusal.
**Falsifier:** the second POST replacing the first (which would silently change the meaning of a
query planned against the old schema), or a 201.
**Setup:** `H-SRV`, `orders` registered by API-080.
**Steps:**
```
curl -si localhost:18080/api/v1/streams -H 'Content-Type: application/json' \
  -d '{"name":"orders","schema":"order_id:INT64,sku:STRING,qty:INT32"}'
curl -si localhost:18080/api/v1/streams -H 'Content-Type: application/json' \
  -d '{"name":"orders","schema":"order_id:INT64"}'
curl -s  localhost:18080/api/v1/streams/orders | jq .fieldCount
```
**Expected:** both POSTs return `400` with `code == "PRV-2002"` and the message
`stream 'orders' version 1 is already registered. Schema versions are immutable; register a new
version rather than replacing one a query may be planned against.` The final `jq` prints `3` — the
**original** three-field schema, unchanged by the second, narrower attempt. Note the status: a
duplicate is `400`, not the `409 Conflict` a REST client will be looking for.
**Vacuity:** the third request is what makes this non-vacuous — asserting the 400 alone would pass
even if the catalog had been overwritten and then complained.

## API-102 — concurrent identical registrations produce exactly one stream
**Intent:** `StreamCatalog` is `synchronized` on every method, so the race should resolve to one
winner and N-1 refusals. This is the only write the REST API has.
**Falsifier:** two 201s; or a `fieldCount` that is neither of the submitted schemas; or a 500 from
a `ConcurrentModificationException`.
**Setup:** `H-SRV`, `concurrent_stream` not registered.
**Steps:** 20 parallel POSTs of `{"name":"concurrent_stream","schema":"a:INT64,b:STRING"}` with
`xargs -P20 -n1 curl -s -o /dev/null -w '%{http_code}\n'`, then `GET /api/v1/streams`.
**Expected:** exactly one `201` and exactly nineteen `400` responses — `1 + 19 = 20`, no other
status, no timeout. `GET /api/v1/streams` shows `concurrent_stream` exactly once with
`fieldCount == 2`.
**Vacuity:** run the same 20 against a name that already exists and confirm `0` successes and `20`
refusals; a harness that counted "at least one 201" would pass in both worlds.

## API-103 — concurrent identical reads and validations agree byte for byte
**Intent:** the read and validate paths hold no per-request state, so 50 concurrent identical calls
must return 50 identical bodies (modulo `elapsedMicros` and `uptimeSeconds`).
**Falsifier:** any two responses differing in a field other than the two timing fields; any 5xx;
any response with a truncated body.
**Setup:** `H-SRV`.
**Steps:** 50 parallel `POST /api/v1/queries/validate` with the API-081 body; 50 parallel
`GET /api/v1/streams`; 50 parallel `GET /api/v1/status`. Normalise `elapsedMicros` and
`uptimeSeconds` to `0` with `jq` and `sort -u | wc -l` each set.
**Expected:** `1` distinct body for the validate set, `1` for the streams set, `1` for the status
set; `150` responses total, all `200`. `SqlPlanner` is constructed per call, so a shared-planner
regression would show here as a mixed set.

## API-104 — a validation racing a registration sees one state or the other, never half
**Intent:** the catalog is read by `QueryController.plannerFor()` on every validate, so a stream
appearing mid-flight must either be fully visible or not visible at all.
**Falsifier:** a validate that reports `valid == true` against a stream whose fields it then cannot
project; or a `PRV-2002` whose `Known streams:` list contains a name that `GET /api/v1/streams` does
not.
**Setup:** `H-SRV`.
**Steps:** in a loop of 200 iterations: POST `/api/v1/streams` with
`{"name":"race_N","schema":"a:INT64,b:STRING"}` while, in parallel, POSTing
`{"sql":"SELECT a, b FROM race_N"}` to `/api/v1/queries/validate`, and immediately afterwards
`GET /api/v1/streams`.
**Expected:** each validate returns either `valid == true` with `outputFields` of exactly
`[a INT64 NOT NULL, b STRING NOT NULL]`, or `valid == false` with `PRV-2002` and a
`Known streams:` list that does **not** contain `race_N`. Never a third outcome. Every `race_N`
appears exactly once in the final listing: `200` streams created from `200` attempts.

### D. Authentication — which paths answer without a credential (API-105–114)

## API-105 — every documented path refuses an unauthenticated caller when authentication is on
**Intent:** the core of this section. `BearerTokenFilter` is registered on `/*` at
`HIGHEST_PRECEDENCE` and `shouldNotFilter` exempts only five prefixes, so all six documented paths
must refuse. Enumerated one by one because "the API is authenticated" is exactly the claim that is
true for five paths and false for the sixth in every product that has this bug.
**Falsifier:** any row below answering 200 without a credential. The write (`POST /api/v1/streams`)
is the one that matters most: before the filter existed, anyone who could reach the port could
register a stream.
**Setup:** `H-SRVA`. No `Authorization` header on any request.
**Steps:** `curl -s -o /dev/null -w '%{http_code} %{url_effective}\n'` for:
`GET /api/v1/status`, `GET /status`, `GET /api/v1/streams`, `GET /api/v1/streams/txn`,
`POST /api/v1/streams` (valid body), `POST /api/v1/queries/validate` (valid body),
`POST /api/v1/queries/explain` (valid body).
**Expected:** all seven return `401`. Each body is JSON with
`code == "PRV-7001"`, `message == "this server requires a credential; send it as 'Authorization: Bearer <token>'"`,
`helpUrl == "https://docs.pravaha.io/errors/PRV-7001"`, a `timestamp`, `path` equal to the request
URI — and a sixth field, `status: 401`, which `ApiError` does not have (API-123). Confirm with
`GET /api/v1/streams` afterwards, with a valid token, that the unauthenticated POST created nothing.

## API-106 — malformed and wrong credentials
**Intent:** six shapes of a bad `Authorization` header, and the rule that the refusal must not say
*why*. `BearerTokenFilter` strips a case-insensitive `Bearer ` prefix and otherwise passes the whole
header value to the verifier as the token.
**Falsifier:** two different messages for "unknown token" and "expired token" (an oracle); or any of
these being admitted.
**Setup:** `H-SRVA`. Target `GET /api/v1/streams` each time.
**Steps:** send `Authorization:` with each of — (a) `Bearer ann-token-0123456789` (control, valid);
(b) `bearer ann-token-0123456789` (lower case scheme); (c) `BEARER ann-token-0123456789`;
(d) `ann-token-0123456789` (no scheme at all); (e) `Bearer wrong-token`; (f) `Bearer ` (empty
token); (g) `Basic YW5uOng=`; (h) the header sent twice with different values.
**Expected:** (a), (b), (c) and (d) all return `200` — the `regionMatches(true, …)` makes the scheme
case-insensitive, and a bare token with no scheme is accepted, which is worth recording as a
deliberate leniency. (e), (f) and (g) return `401` with **the same** `message` as each other, which
must not distinguish "unknown" from "malformed". (h) returns `401` or `200` deterministically —
record which header wins, since a proxy that appends a header would decide it.

## API-107 — the OpenAPI document and the docs UI answer without a credential, by design
**Intent:** three of the five `OPEN_PREFIXES`. They describe the shape of the API and disclose no
row data, and a client that cannot fetch the schema cannot generate a client.
**Falsifier:** a 401 on any of them (which would break code generation), **or** any of them
containing stream names, query text or configuration values.
**Setup:** `H-SRVA`, no `Authorization` header.
**Steps:** `curl -si localhost:18080/api/v1/openapi.json`, `.../api/docs`, `.../swagger-ui/index.html`;
then `grep -c 'txn\|by_user\|token' openapi.json`.
**Expected:** `200` for all three. The OpenAPI document lists the six locked paths and the DTO
schemas, and the grep count is `0` — no stream name, no query text and no credential appears in it.
If a stream name does appear, an unauthenticated caller can enumerate the deployment's data model,
and that is the finding.

## API-108 — `/actuator/health` and `/actuator/info` answer without a credential; `metrics` and `prometheus` do not
**Intent:** the other two open prefixes, and the boundary immediately beyond them. A liveness probe
must not authenticate — it would fail closed when the identity source is down — but metrics are
operational data.
**Falsifier:** `/actuator/metrics` or `/actuator/prometheus` answering 200 without a credential
(they are exposed by `management.endpoints.web.exposure.include` but are **not** in
`OPEN_PREFIXES`); or `/actuator/health` requiring one.
**Setup:** `H-SRVA`, no `Authorization` header.
**Steps:** `curl -s -o /dev/null -w '%{http_code} %{url_effective}\n'` for
`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`,
`/actuator/metrics`, `/actuator/metrics/jvm.memory.used`, `/actuator/prometheus`, `/actuator`.
**Expected:** `200` for the four health/info paths — including the two probe sub-paths, which match
the `/actuator/health` prefix. `401` for `/actuator/metrics`, its sub-path, `/actuator/prometheus`
and the `/actuator` index. Then repeat all eight **with** a valid token and expect `200` for the
first seven. Record the health body: with `show-details: when-authorized` and no Spring Security in
the stack, an unauthenticated caller is not "authorized", so `/actuator/health` should be
`{"status":"UP"}` and nothing more — component details leaking here would name plugins, databases
and paths.

## API-109 — `OPEN_PREFIXES` is a `startsWith` test, so near-miss paths are also unauthenticated
**Intent:** `shouldNotFilter` does `OPEN_PREFIXES.stream().anyMatch(path::startsWith)` on the raw
`getRequestURI()`. Any path that merely *begins* with one of the five strings bypasses
authentication entirely. Whether that is exploitable depends on what else routes.
**Falsifier:** any of the probe paths below reaching a controller that serves data while
unauthenticated. A 404 is an acceptable answer; a 200 with content is not.
**Setup:** `H-SRVA`, no `Authorization` header.
**Steps:** `curl -si` each of `/actuator/healthz`, `/actuator/health-anything`,
`/actuator/healthXX/../metrics`, `/api/v1/openapi.jsonx`, `/api/v1/openapi.json/../streams`,
`/api/docsomething`, `/swagger-ui-x`, `/api/docs/../../api/v1/streams`.
**Expected:** every one must be `404` (unmatched route) or `401`. **None** may return `200` with a
stream list, a status document or metrics. Record, for each, whether the filter was skipped — the
distinguishing signal is a `404` body in Spring's shape rather than the `PRV-7001` JSON. Any path
that both skips the filter and routes somewhere is an authentication bypass and the most serious
finding this file can produce.

## API-110 — a valid token from any tenant reads everything
**Intent:** the HTTP surface authenticates and **never authorizes**: no controller calls
`BearerTokenFilter.principalOf`, and `SecurityPolicy` is consulted only on the Flight path. So
`AuthenticatedOnlyPolicy` is the whole of REST's authorization model, and it says "any verified
caller".
**Falsifier:** any difference between what `ann` (tenant `acme`) and `bob` (tenant `globex`) can
see or do. There is none — this case pins that.
**Setup:** `H-SRVA`.
**Steps:** with ann's token and then with bob's, `GET /api/v1/streams`, `GET /api/v1/status`,
`POST /api/v1/queries/validate` (a query over `txn`), and `POST /api/v1/streams` registering
`bobs_stream`.
**Expected:** identical `200` responses for the reads under both tokens, byte for byte; and both
registrations succeed. In particular `bob`, from another tenant, registers a stream on this node
and reads `acme`'s schemas. The `path`/`principal` never appears in an audit record for these calls
either, because the REST surface records none.

## API-111 — with `authentication: none` the filter is registered but disabled
**Intent:** `PravahaServerApplication` always constructs the filter and sets
`registration.setEnabled(verifier != null)`, for the stated reason that a `FilterRegistrationBean`
holding no filter fails the servlet container at refresh. So on an open node the filter object
exists and does nothing.
**Falsifier:** an open node refusing a request (the filter leaking through as enabled), or the
container failing to start.
**Setup:** `H-SRV` (`--spring.profiles.active=dev`).
**Steps:** all seven requests of API-105 with no `Authorization` header; then the same seven **with**
`Authorization: Bearer anything-at-all`.
**Expected:** all fourteen return their normal 2xx — the header is neither required nor validated
nor rejected. The node started (which is the regression this arrangement exists to prevent), and
`GET /actuator/health` is `200`.

## API-112 — the node refuses to start open unless somebody said so
**Intent:** `refuseAccidentalOpenServer` is the reason the defaults in `application.yaml` do not
boot. It is part of the API surface because it decides whether the port is there at all.
**Falsifier:** the node starting and serving `/api/v1/streams` with the shipped defaults.
**Setup:** a clean checkout's `application.yaml`, no profile.
**Steps:** `pravaha-server` with no `--spring.profiles.active`; then with
`pravaha.security.policy=authenticated` and `authentication=none`; then with the `dev` profile.
**Expected:** (1) startup fails with a `PravahaException` naming
`pravaha.security.authentication=none, policy=permissive` and listing the three ways out; no port
18080 listener (`curl` gets connection refused). (2) startup fails with the contradiction message —
"a node nobody can use" — because the policy serves only verified callers and nothing can verify
one. (3) starts, and `/api/v1/streams` answers `200` anonymously. Three configurations, three
outcomes, and only the third has an HTTP surface.

## API-113 — credentials are read from the header and nowhere else
**Intent:** `request.getHeader("authorization")` is the only source. A token in a query string or a
cookie must not work — and, just as importantly, must not be logged.
**Falsifier:** `?token=…` or `?access_token=…` being honoured.
**Setup:** `H-SRVA`.
**Steps:** `GET /api/v1/streams?token=ann-token-0123456789`, then with
`?access_token=…`, then with `Cookie: authorization=Bearer ann-token-0123456789`, then with
`X-Authorization: Bearer …`.
**Expected:** all four `401` with `PRV-7001`. Then inspect the access log and the application log:
the query-string forms mean the credential is now in `path` inside the 401 body **and** in any
request log line. Record whether `ApiError.path` echoes the full query string — if it does, a
credential is being written into the error body the client may log again.

## API-114 — a credential is not required to learn the node exists, its version, or its state
**Intent:** closing the section by naming what an unauthenticated caller can still learn on a fully
authenticated node: liveness, readiness, and the OpenAPI document.
**Falsifier:** `/actuator/health` or `/api/v1/openapi.json` disclosing the instance id, the stream
names, the configured tokens, or the plugin inventory.
**Setup:** `H-SRVA`, no credential.
**Steps:** fetch `/actuator/health`, `/actuator/info`, `/api/v1/openapi.json` and diff their content
against the authenticated `GET /api/v1/status` body.
**Expected:** the open endpoints disclose at most `{"status":"UP"}`, an empty or build-info `info`
body, and the API's static shape. None of `pravaha-node-01`, `txn`, a plugin name or a token
substring appears in any of the three. Anything that does is a disclosure finding, and belongs in
`SECX` as well as here.

### E. Disclosure: actuator endpoints and error messages (API-115–120)

## API-115 — `/actuator/env`
**Intent:** checked individually, because `env` is the single worst actuator endpoint to expose: it
renders the whole `Environment`, including `pravaha.security.tokens.*` — the static credentials
themselves — and any secret passed as a system property or an environment variable.
**Falsifier:** a `200` from `/actuator/env` under any configuration reachable from the shipped
files; or a `200` from `/actuator/env/pravaha.security.tokens`.
**Setup:** `H-SRV` (open) and `H-SRVA` (authenticated), tried in turn.
**Steps:** `curl -si localhost:18080/actuator/env` and
`curl -si 'localhost:18080/actuator/env/pravaha.security.tokens.*'`, on both nodes, without a
credential and then with one.
**Expected:** `404` on both nodes in all four combinations, because
`management.endpoints.web.exposure.include: health,info,metrics,prometheus` does not list `env`.
On `H-SRVA` an unauthenticated request may be answered by the filter as `401` before routing —
record which comes first, since a `401` here proves the filter runs and a `404` proves the exposure
list does. If either node returns a `200`, capture the body and check it for a token value; that is
a credential disclosure and stops the wave.

## API-116 — REST error messages enumerate the catalogue
**Intent:** three messages on this surface list everything the node holds, to any caller who reaches
them: `PRV-2003` appends `Registered: [every stream]`, `PRV-2002` from the planner appends
`. Known streams: [every stream]`, and `PRV-4023` (reachable through the Flight read path, quoted
back by the CLI) appends `this server serves [every view]`. None consults the policy.
**Falsifier:** none of them enumerating — which is what a filtered listing would look like, and what
`pravaha.list` already does on the Flight side.
**Setup:** `H-SRVA` with streams `txn`, `orders`, `payroll` declared. Call as `bob` (tenant
`globex`), who has no relationship with any of them.
**Steps:**
```
curl -s -H "$BOB" localhost:18080/api/v1/streams/nope            | jq -r .message
curl -s -H "$BOB" localhost:18080/api/v1/queries/validate \
     -H 'Content-Type: application/json' -d '{"sql":"SELECT x FROM nope"}' | jq -r .diagnostics[0].message
```
**Expected:** the first message ends `Registered: [txn, orders, payroll]`; the second ends
`. Known streams: [txn, orders, payroll]`. Both are served on a node configured to serve data only
to verified callers, and both hand a verified caller from an unrelated tenant the complete
inventory. This is the REST half of the disclosure the Flight side has at API-180: **a filtered
listing plus an enumerating error message is not a filtered listing.**
**Vacuity:** `GET /api/v1/streams` as bob returns all three too (API-110), so on `H-SRVA` the error
message discloses nothing the listing does not. Re-run the case on a node whose policy filters the
listing — the point is that these messages would still enumerate, and the mechanism is the same one
`QueryRegistry.require` uses where the listing *is* filtered.

## API-117 — `/actuator/beans` and `/actuator/configprops`
**Intent:** two endpoints checked individually. `beans` is a map of the application's internals;
`configprops` renders bound `@ConfigurationProperties` — which includes `SecurityProperties`, and
therefore `tokens`, unless Spring's sanitizer masks it.
**Falsifier:** either returning `200` with content.
**Setup:** `H-SRV` and `H-SRVA`.
**Steps:** `curl -si` both paths on both nodes, with and without a credential.
**Expected:** `404` everywhere (neither id is in the exposure list); `401` instead of `404` for the
unauthenticated calls on `H-SRVA` is acceptable and should be recorded. If `configprops` ever
returns 200, assert specifically that `pravaha.security.tokens` values render as `******` and not as
the token text.

## API-118 — `/actuator/heapdump` and `/actuator/threaddump`
**Intent:** individually, because they are different risks. A heap dump contains every row in flight,
every token in memory and every query's bound parameters; a thread dump contains stack frames and,
indirectly, the shape of what is running.
**Falsifier:** either returning `200`; or `heapdump` returning a `Content-Disposition` and a
multi-megabyte body.
**Setup:** `H-SRV` and `H-SRVA`.
**Steps:** `curl -si -o /dev/null -w '%{http_code} %{size_download}\n'` for both paths on both
nodes, with and without a credential.
**Expected:** `404` with a body of a few hundred bytes in all eight combinations (or `401` on the
unauthenticated `H-SRVA` calls). Any response over 1 MB from `/actuator/heapdump` is a stop-the-wave
finding.

## API-119 — `/actuator/loggers`, `/actuator/mappings`, `/actuator/shutdown`
**Intent:** the three that let a caller *change* or *map* the running node rather than read it.
`loggers` accepts a POST that turns on DEBUG logging (which will write query text and parameter
values to disk); `mappings` lists every route; `shutdown` stops the node.
**Falsifier:** any of them returning anything but 404 — and in particular
`POST /actuator/shutdown` returning `200 {"message":"Shutting down…"}`.
**Setup:** `H-SRV` and `H-SRVA`.
**Steps:** `GET /actuator/loggers`, `POST /actuator/loggers/com.ash.messaging.pravaha` with
`{"configuredLevel":"DEBUG"}`, `GET /actuator/mappings`, `POST /actuator/shutdown` — each on both
nodes, unauthenticated and authenticated.
**Expected:** `404` (or `401`) for all of them, and — the assertion that matters — the node is
**still running** afterwards: `GET /actuator/health` returns `200 {"status":"UP"}` at the end of the
case. A `shutdown` that worked would be found by the next case failing to connect, which is not the
same as being found here.

## API-120 — the exposure list is the control, so audit it in both directions
**Intent:** every endpoint above is closed by one line of `application.yaml`. That line is therefore
part of the API's security surface, and a change to it is an API change. Audit it both ways:
everything exposed answers, and everything else does not.
**Falsifier:** an actuator id answering 200 that is not one of the four listed; or one of the four
listed returning 404 (which would mean monitoring has silently stopped).
**Setup:** `H-SRV`.
**Steps:** for every id Spring Boot ships — `auditevents, beans, caches, conditions, configprops,
env, flyway, health, heapdump, httpexchanges, info, integrationgraph, liquibase, logfile, loggers,
mappings, metrics, prometheus, quartz, scheduledtasks, sessions, shutdown, startup, threaddump` —
`curl -s -o /dev/null -w '%{http_code} %{url_effective}\n' localhost:18080/actuator/<id>`.
**Expected:** exactly four `200`s — `health`, `info`, `metrics`, `prometheus` — matching
`management.endpoints.web.exposure.include` character for character. Every other id returns `404`:
`24 - 4 = 20` of them. Also fetch `/actuator` itself (the discovery index) and confirm it lists only
the four, so a caller cannot learn that the others exist.

### F. The error body (API-121–125)

## API-121 — every failure the handler produces is an `ApiError` with the same five fields
**Intent:** `ApiExceptionHandler`'s stated contract — "every non-2xx response is an `ApiError` and
nothing else" — asserted across every reachable producer rather than on one example.
**Falsifier:** any two of these bodies having different field sets; any missing `helpUrl`; any
`timestamp` absent.
**Setup:** `H-SRV`.
**Steps:** provoke each and dump `keys` with `jq -S 'keys'`:
`GET /api/v1/streams/nope` (PRV-2003, 400);
`POST /api/v1/streams` with a duplicate name (PRV-2002, 400);
`POST /api/v1/streams` with a malformed spec (PRV-5040, 500);
`POST /api/v1/queries/explain?level=weird` (PRV-0400, 400, the `IllegalArgumentException` arm).
**Expected:** all four bodies have exactly the key set
`["code","helpUrl","message","path","timestamp"]` — five keys, no more and no fewer. `code` matches
`^PRV-\d{4}$` in all four. `helpUrl` is `https://docs.pravaha.io/errors/<code>` for the three
`PravahaException` cases and — note — the **empty string** for the `IllegalArgumentException` case,
which the handler hard-codes as `""`. That is a contract violation in the field the console renders
as a link; record it.

## API-122 — the status is derived from the code's category, so enumerate the categories
**Intent:** `statusFor` switches on `ErrorCode.Category`, which is decided purely by the numeric
range. That is the design's strength (a new PRV-2xxx is a 400 without anyone remembering) and its
weakness (PRV-5040, a caller's typo, is a 500 because 5xxx means PLUGIN).
**Falsifier:** any row below mapping differently.
**Setup:** `H-SRV`, plus a unit-level check of `ApiExceptionHandler.statusFor` for the codes that
cannot be provoked over HTTP.
**Steps:** for each category, provoke or call `statusFor` directly:

| Category | Range | Example code | Expected status | Reachable over HTTP? |
|---|---|---|---|---|
| CONFIGURATION | 1000–1999 | PRV-1xxx | 400 | not from these controllers |
| PLANNING | 2000–2999 | PRV-2002, PRV-2003 | 400 | yes — API-099, API-101 |
| RUNTIME | 3000–3999 | PRV-3xxx | 500 | not from these controllers |
| STATE | 4000–4999 | PRV-4023 | 500 | not from these controllers |
| PLUGIN | 5000–5999 | PRV-5040 | **500** | yes — API-097 |
| CLUSTER | 6000–6999 | PRV-6102 | 500 | not from these controllers |
| SECURITY | 7000–7999 | PRV-7001 | 403 | no — the filter writes 401 itself |

**Expected:** exactly as tabulated. Two findings to record: PRV-5040 is a **caller's** error
returned as 500 (the whole of API-097), and the `SECURITY` row maps to **403** in `statusFor` while
the only security failure a client actually sees is the filter's hand-written **401** — so the
mapping for `PRV-7001` is dead code, and a `PRV-7002` raised inside a controller would arrive as a
403 that no client is looking for on this surface.

## API-123 — codes outside every category range make the handler throw
**Intent:** `ErrorCode.Category` covers 1000–7999. `RegistryErrors` uses 8001–8007 and
`ClusterErrors` uses 9001–9007, so `category()` throws `IllegalStateException("no category for
PRV-8002")`. If any such exception ever reaches `ApiExceptionHandler`, the handler itself fails and
the client gets Spring's 500 page instead of an `ApiError` — the one shape the design promises never
to send.
**Falsifier:** `statusFor(new ErrorCode(8002, …))` returning a status instead of throwing; or a
`PravahaException` carrying an 8xxx/9xxx code producing a well-formed `ApiError`.
**Setup:** a unit call to `ApiExceptionHandler.statusFor` with `RegistryErrors.NO_SUCH_QUERY`
(8002) and `ClusterErrors.INSUFFICIENT_GUARANTEE` (9002); plus a survey of whether any controller
path can reach an 8xxx or 9xxx code today.
**Expected:** both calls throw `IllegalStateException` with the message `no category for PRV-8002`
and `no category for PRV-9002`. The survey's expected answer is "no path today" — the REST surface
touches the catalog and the planner only, never the registry or the cluster. Record it as a latent
defect with a one-line reproduction, because the first REST endpoint that lists registered queries
will hit it.

## API-124 — the framework's own failures are not `ApiError`
**Intent:** the honest boundary of the "one error shape" promise. `spring.mvc.problemdetails` is
deliberately off, so a 404, a 405, a 415 and a malformed-JSON 400 are all rendered by Spring's
default error handling, which produces `{timestamp, status, error, path}` — different field names,
no `code`, no `helpUrl`.
**Falsifier:** any of them carrying `code` and `helpUrl` (which would mean the promise is now kept
and this case retires).
**Setup:** `H-SRV`.
**Steps:** collect `jq -S 'keys'` for: `GET /api/v1/nothing-here` (404),
`DELETE /api/v1/streams` (405), `POST /api/v1/queries/validate` with `Content-Type: text/plain`
(415), `POST /api/v1/queries/validate` with `-d '{'` (400), and — for contrast —
`GET /api/v1/streams/nope` (400 `ApiError`).
**Expected:** the first four share a key set that is **not** the five of API-121 (expect
`["error","path","status","timestamp"]`), and the fifth has exactly the `ApiError` five. So a strict
client on this surface must handle **two** error shapes, and the count is the finding: `4` framework
failures against `1` engine failure in this sample.

## API-125 — every error body is strictly parseable JSON
**Intent:** the 401 body is built by **string concatenation** in `BearerTokenFilter.refuse`, with
only `"` replaced by `'` in the reason and the path. A message containing a backslash, a newline, or
a non-ASCII character would produce invalid JSON, and the client that most needs to parse a 401 is
the one retrying with a fresh credential.
**Falsifier:** any body below failing `jq -e .`; or the 401's `status` field being absent, since
that is what API-123 records; or a `timestamp` that is not ISO-8601.
**Setup:** `H-SRVA`.
**Steps:**
1. Unauthenticated `GET /api/v1/streams` → parse the 401 with `jq -e .`.
2. Unauthenticated `GET` of a path containing a double quote, a backslash and a newline
   (percent-encoded) → parse the 401, and check that `path` did not break the JSON.
3. Every error body collected in API-121 and API-124 → `jq -e .`.
4. For each, check `timestamp` against `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$`.
**Expected:** every body parses. The `path` in step 2 either round-trips escaped correctly or shows
the concatenation's limits — a backslash in the URI is **not** escaped by
`replace("\"", "'")`, so a path ending in a backslash before the closing quote is the specific
input that breaks the body. Record whether it does: that is a malformed response to an
unauthenticated caller, which is reachable by anyone who can reach the port.

---

## Flight and Flight SQL (API-126–API-180)

Five control verbs (`pravaha.register`, `.drop`, `.list`, `.pause`, `.resume`), the statement path
(`getFlightInfo`, `getStream`, `getSchema`), prepared statements, `acceptPutPreparedStatementQuery`,
and Flight SQL's catalog/metadata commands. Control verbs are Flight **actions** framed by
`ControlWire`; a subscription is a Flight **ticket** recognised by the magic `0x50525648` ("PRVH").

Status mapping, from `FlightErrors.statusFor` — needed in nearly every case below:

| PRV | CallStatus |
|---|---|
| PRV-7001 | `UNAUTHENTICATED` |
| PRV-7002, PRV-7003 | `UNAUTHORIZED` |
| PRV-4026, PRV-4027, PRV-4028 | `RESOURCE_EXHAUSTED` |
| PRV-4021, PRV-4029 | `TIMED_OUT` |
| PRV-4023, PRV-6102 | `NOT_FOUND` |
| everything else | `INVALID_ARGUMENT` |

### A. The five control verbs, authorized / unauthenticated / denied (API-126–140)

## API-126 — `pravaha.register`, authorized
**Intent:** the happy path of the verb that stands up a computation, and the three fields it returns.
**Falsifier:** an empty result, a state that is not the registry's, or a fingerprint that is not the
short form.
**Setup:** `H-FLA`, called as **dana** (allowed).
**Steps:** `client.doAction(new Action("pravaha.register", ControlWire.encode("q_new", "SELECT user_id, amount FROM txn WHERE amount > 100", "0")), danaHeaders)`
and read the single `Result`.
**Expected:** exactly one `Result`, then `onCompleted`. `ControlWire.decode(result.getBody())` is a
three-element list: `["q_new", "RUNNING", <short fingerprint>]`. `registry.names()` afterwards
contains `q_new`, so the four registrations are `q_alpha, q_beta, q_gamma, q_new`.

## API-127 — `pravaha.register`, unauthenticated
**Intent:** `PrincipalMiddleware.Factory.onCallStarted` runs before the producer, so an action with
no credential costs a header parse and never reaches the registry.
**Falsifier:** a registration appearing; or the refusal arriving as `INVALID_ARGUMENT` rather than
`UNAUTHENTICATED`, which tells a client to fix its request instead of its credential.
**Setup:** `H-FLA`, no `authorization` header.
**Steps:** the same `doAction` as API-126 with no `CallOption`.
**Expected:** `FlightRuntimeException` with `CallStatus.UNAUTHENTICATED` and a description starting
`PRV-7001  this server requires a credential: send it as the header 'authorization: Bearer <token>'`.
`registry.names()` is unchanged: still exactly `[q_alpha, q_beta, q_gamma]`.
**Vacuity:** assert the name set before and after; a refusal that still registered would pass an
exception-only assertion.

## API-128 — `pravaha.register`, authenticated but denied
**Intent:** the interesting half. `POLICY.mayRegisterQuery(sam)` **allows** (sam is not anonymous),
so the refusal comes from the *source* check inside `QueryRegistry.register` — `mayRead(sam, "txn")`
— which exists precisely so that a principal who may register cannot register a standing read of
something they may not read.
**Falsifier:** the registration succeeding; or the message naming `mayRegisterQuery` rather than the
source stream, which would mean the source check did not run.
**Setup:** `H-FLA`, called as **sam**.
**Steps:** the same `doAction` as API-126, as sam.
**Expected:** `CallStatus.UNAUTHORIZED` (from `PRV-7002`), description
`sam may not register 'q_new' because it reads 'txn', which they may not read: only analysts read
txn. A registration is a standing read of everything the query names, so it is refused here rather
than at the first row.` `registry.names()` unchanged at three.

## API-129 — `pravaha.drop`, authorized
**Intent:** the verb that destroys accumulated state, on the allowed path.
**Falsifier:** the query surviving, or a result body that does not say `DROPPED`.
**Setup:** `H-FLA`, as **dana**; `q_gamma` registered.
**Steps:** `doAction("pravaha.drop", ControlWire.encode("q_gamma"))`.
**Expected:** one `Result` decoding to `["q_gamma", "DROPPED"]`; `registry.names()` afterwards is
`[q_alpha, q_beta]` — `3 - 1 = 2`; a subsequent `pravaha.list` returns two results.

## API-130 — `pravaha.drop`, unauthenticated
**Intent:** the case the code's own comment is about: "an unauthenticated caller dropped every
continuous query on a node configured to serve only verified callers."
**Falsifier:** the drop succeeding. This is the highest-severity falsifier in the file.
**Setup:** `H-FLA`, no credential; `q_gamma` registered.
**Steps:** `doAction("pravaha.drop", ControlWire.encode("q_gamma"))` with no header.
**Expected:** `UNAUTHENTICATED` with `PRV-7001`; `registry.names()` still `[q_alpha, q_beta,
q_gamma]`; `q_gamma` still `RUNNING` and still answering reads.
**Vacuity:** feed a row to `q_gamma` after the refusal and confirm its `rowsIn` still advances — a
query that had been dropped could not.

## API-131 — `pravaha.drop`, authenticated but denied
**Intent:** the other half of that comment: "an authenticated but denied principal dropped another
principal's payroll query." `requireAdministrable` runs `mayAdminister`, which defaults to
`mayRead`, and records the decision in the audit sink either way.
**Falsifier:** the drop succeeding, or no audit record for the refusal.
**Setup:** `H-FLA`, as **sam**, with an `AuditSink.InMemory`.
**Steps:** `doAction("pravaha.drop", ControlWire.encode("q_gamma"))` as sam; then inspect the audit
sink.
**Expected:** `UNAUTHORIZED` (`PRV-7002`), description
`sam may not drop 'q_gamma': only analysts read q_gamma`. `q_gamma` still registered. The audit sink
holds one event with action `drop`, subject `sam`, object `q_gamma`, decision denied — recorded
**before** the throw, so a refusal is auditable.

## API-132 — `pravaha.pause`, authorized
**Intent:** the state transition and the returned state string.
**Falsifier:** a body that does not say `PAUSED`, or a query still advancing after the call.
**Setup:** `H-FLA`, as **dana**; `q_alpha` RUNNING.
**Steps:** `doAction("pravaha.pause", ControlWire.encode("q_alpha"))`, then feed three rows to
`txn`, then `pravaha.list`.
**Expected:** one result decoding to `["q_alpha", "PAUSED"]`; the listing shows `q_alpha` in state
`PAUSED`; its `rowsIn` is the same before and after the three rows.
**Vacuity:** the row feed is what makes this non-vacuous — a paused query and an idle source look
identical without it. Confirm `q_beta`'s `rowsIn` **did** advance by 3 over the same interval, which
proves rows were flowing.

## API-133 — `pravaha.pause`, unauthenticated
**Intent:** pause takes a query away from everyone reading it just as surely as drop does.
**Falsifier:** the pause succeeding.
**Setup:** `H-FLA`, no credential.
**Steps:** `doAction("pravaha.pause", ControlWire.encode("q_alpha"))`.
**Expected:** `UNAUTHENTICATED`, `PRV-7001`; `q_alpha` still `RUNNING` in a subsequent authenticated
listing, and its `rowsIn` still advancing.

## API-134 — `pravaha.pause`, authenticated but denied
**Intent:** as API-131 for the pause verb.
**Falsifier:** the pause succeeding, or a different error code than drop's for the same denial.
**Setup:** `H-FLA`, as **sam**.
**Steps:** `doAction("pravaha.pause", ControlWire.encode("q_alpha"))`.
**Expected:** `UNAUTHORIZED`, `PRV-7002`, description `sam may not pause 'q_alpha': only analysts
read q_alpha`; state unchanged; one denied audit event with action `pause`.

## API-135 — `pravaha.resume`, authorized
**Intent:** the return leg of API-132, and the `RUNNING` string.
**Falsifier:** the state not returning to RUNNING, or rows that arrived while paused being lost or
double-counted.
**Setup:** `H-FLA`, as **dana**, `q_alpha` PAUSED by API-132 with three rows fed during the pause.
**Steps:** `doAction("pravaha.resume", ControlWire.encode("q_alpha"))`, then read the view.
**Expected:** one result decoding to `["q_alpha", "RUNNING"]`; the listing shows `RUNNING`. Record
`rowsIn` before the pause (`n`), during (`n`), and after resume — whether it becomes `n + 3` or
stays `n` is `LIFE`'s question, but this case records the number so the two areas cannot disagree.

## API-136 — `pravaha.resume`, unauthenticated
**Intent:** completing the matrix; resume is the verb an attacker would use to undo an operator's
pause.
**Falsifier:** the resume succeeding.
**Setup:** `H-FLA`, no credential, `q_alpha` PAUSED.
**Steps:** `doAction("pravaha.resume", ControlWire.encode("q_alpha"))`.
**Expected:** `UNAUTHENTICATED`, `PRV-7001`; `q_alpha` still `PAUSED`.

## API-137 — `pravaha.resume`, authenticated but denied
**Intent:** the last of the twelve administer cells.
**Falsifier:** the resume succeeding.
**Setup:** `H-FLA`, as **sam**, `q_alpha` PAUSED.
**Steps:** `doAction("pravaha.resume", ControlWire.encode("q_alpha"))`.
**Expected:** `UNAUTHORIZED`, `PRV-7002`, `sam may not resume 'q_alpha': only analysts read
q_alpha`; still `PAUSED`.

## API-138 — `pravaha.list`, authorized
**Intent:** the listing's five fields per query, and the order.
**Falsifier:** a missing field, a `rowsIn` that is not a number, or SQL text absent (the console
needs it).
**Setup:** `H-FLA`, as **dana**; three registrations.
**Steps:** `doAction("pravaha.list", ControlWire.encode(""))` and collect every `Result`.
**Expected:** exactly three results, in registration order `q_alpha, q_beta, q_gamma`. Each decodes
to five fields: `[name, state, sql, shortFingerprint, rowsIn]` — note the listing carries the
**full SQL text**, which the repository's own configuration says to treat like data.

## API-139 — `pravaha.list`, unauthenticated
**Intent:** the set of view names is a map of what the deployment does; `PrincipalMiddleware`'s own
Javadoc says so.
**Falsifier:** any result at all reaching an unauthenticated caller.
**Setup:** `H-FLA`, no credential.
**Steps:** `doAction("pravaha.list", ControlWire.encode(""))`.
**Expected:** `UNAUTHENTICATED`, `PRV-7001`, and **zero** `Result` objects delivered before the
error.

## API-140 — `pravaha.list`, authenticated but denied, returns an empty list rather than a refusal
**Intent:** the deliberate asymmetry: `LIST` filters with `policy.mayRead` per name and `continue`s
past the ones the caller may not read, so sam gets an empty successful listing instead of a
`FORBIDDEN`. "A principal sees the queries they could read, and does not learn that the others
exist."
**Falsifier:** sam receiving any result; or sam receiving an error, which would itself confirm that
queries exist.
**Setup:** `H-FLA`, as **sam**, three registrations.
**Steps:** `doAction("pravaha.list", ControlWire.encode(""))` as sam, and also as **ravi** (allowed
with a row filter).
**Expected:** sam gets **zero** results and a clean `onCompleted` — no error. Ravi gets **three**,
because `AccessDecision.allowWithRowFilter(...)` is `allowed()`. So of three principals the counts
are `3, 3, 0` for dana, ravi, sam. This filtering is what API-180 shows is undone by the error
messages.
**Vacuity:** sam's empty listing is indistinguishable from an empty registry, which is the intent —
so assert dana's three in the same run, or the case passes against a node with nothing registered.

### B. Malformed control bodies (API-141–146)

## API-141 — `pravaha.register` with too few fields is refused cleanly
**Intent:** the one arity check in `doAction`: `fields.size() < 3` throws `PRV-6102` with a sentence
naming what is needed.
**Falsifier:** an `IndexOutOfBoundsException` reaching the client as `INTERNAL`.
**Setup:** `H-FLR` (permissive, anonymous, registry present).
**Steps:** `doAction("pravaha.register", ControlWire.encode())` (zero fields), then with one field,
then with two.
**Expected:** all three return `CallStatus.NOT_FOUND` — because `PRV-6102` maps to `NOT_FOUND` —
with the description `register needs a name, some SQL and key columns`. Record the status: a
malformed request arriving as `NOT_FOUND` is odd enough that a client may retry it as a missing
resource.

## API-142 — `pravaha.drop`, `.pause` and `.resume` with an empty body throw from an array index
**Intent:** none of the three checks `fields.size()` before `fields.get(0)`, so an empty
`ControlWire` payload raises `IndexOutOfBoundsException`, which falls to the `RuntimeException` arm
and becomes `CallStatus.INTERNAL`. The class's own contract — "a malformed payload is a refusal with
a code, never an exception with an array index in it" — is kept by `ControlWire.decode` and then
broken by its caller.
**Falsifier:** a `PRV-6102` refusal (which would mean it was fixed).
**Setup:** `H-FLR`.
**Steps:** for each of the three verbs, `doAction(verb, ControlWire.encode())` — a well-formed
envelope with zero fields.
**Expected:** all three fail with `CallStatus.INTERNAL` and a description that is
`Index 0 out of bounds for length 0` or similar — an exception message with an index in it, reaching
a client. Three verbs, one defect, one line of fix.

## API-143 — `pravaha.register` with a non-numeric key ordinal
**Intent:** `Integer.parseInt(ordinal.strip())` is unguarded.
**Falsifier:** a `PRV-6102` refusal naming the bad ordinal.
**Setup:** `H-FLR`.
**Steps:** `doAction("pravaha.register", ControlWire.encode("q_x", "SELECT user_id FROM txn", "a"))`;
then with `"0,"`, `",0"`, `"-1"`, `"99"`, and an empty key string `""`.
**Expected:** `"a"` → `INTERNAL` with `For input string: "a"`. `"0,"` and `",0"` → accepted, because
blank ordinals are skipped by `if (!ordinal.isBlank())`. `""` → an empty key list, which
`QueryRegistry.register` refuses with `IllegalArgumentException("a registration needs at least one
key column: a view with no key is a log …")` → `INTERNAL`. `"-1"` and `"99"` → the engine's own
refusal; record the code. Six inputs, at least three distinct failure shapes, only one of which
carries a PRV code.

## API-144 — a control body that is not `ControlWire` at all
**Intent:** `ControlWire.decode` is the guard that works: magic, version and every length are
bounds-checked, and a bad payload is `PRV-6102` rather than an exception.
**Falsifier:** an exception with an index or an offset in it; or a partial decode being acted on.
**Setup:** `H-FLR`.
**Steps:** `doAction("pravaha.list", body)` for each body: 0 bytes; 4 bytes of `0x00`; 8 random
bytes; a valid protobuf `Any`; a 1 MB block of random bytes; a valid envelope truncated to 12 bytes;
a valid envelope with the field-count int set to `0x7FFFFFFF`; the same with a field length larger
than the remaining buffer.
**Expected:** every one fails with `NOT_FOUND` (`PRV-6102`) and one of the three
`ControlWire.decode` messages — `this is not a Pravaha request` (magic mismatch or under 9 bytes),
`this request was built by a different version of the client; upgrade one of them` (version byte),
or `this Pravaha request is malformed` (count over 1024, or a length past the end). No case produces
`INTERNAL`, no case allocates the 2 GB the bogus count asks for, and the server is still answering
afterwards.

## API-145 — a control envelope from a future client version
**Intent:** `VERSION` is 1 and `decode` refuses anything else with an upgrade instruction. Pinning
it protects the one forward-compatibility promise this hand-rolled framing makes.
**Falsifier:** a version-2 envelope being decoded as version 1.
**Setup:** `H-FLR`.
**Steps:** hand-build an envelope with magic `0x50525648`, version byte `2`, one field `q_alpha`,
and send it as `pravaha.drop`; repeat with version `0` and version `-1` (`0xFF`).
**Expected:** all three `NOT_FOUND` with `this request was built by a different version of the
client; upgrade one of them`. `q_alpha` is not dropped — verify with a listing.

## API-146 — unknown and non-Pravaha actions
**Intent:** the `type.startsWith("pravaha.")` fork. Anything not starting with the prefix goes to
Flight SQL's own `doAction`; anything that does and is not one of the five is `PRV-6101`.
**Falsifier:** an unknown `pravaha.*` action being silently accepted; or a Flight SQL action being
swallowed by the Pravaha branch.
**Setup:** `H-FLR`.
**Steps:** `doAction("pravaha.frobnicate", …)`, `doAction("pravaha.", …)`, `doAction("PRAVAHA.list", …)`,
`doAction("CancelFlightInfo", …)`, `doAction("", …)`.
**Expected:** `pravaha.frobnicate` and `pravaha.` → `INVALID_ARGUMENT` (PRV-6101 is not in the
mapping table) with `this server does not answer the action 'pravaha.frobnicate'`.
`PRAVAHA.list` does **not** match the prefix (case-sensitive) so it goes to Flight SQL and comes
back `UNIMPLEMENTED` or "Unknown action" — a different status for what a user will read as the same
mistake. `CancelFlightInfo` and `""` are Flight SQL's to answer; record what they return.

### C. The statement path: `getFlightInfo`, `getStream`, `getSchema` (API-147–153)

## API-147 — `getFlightInfo` plans without executing and returns a schema and a ticket
**Intent:** the design's central claim for this path: the query is planned twice and executed once,
and the ticket carries the SQL rather than a handle to a materialised result, so the server holds
nothing between the two calls.
**Falsifier:** the rows being produced at `getFlightInfo` time (visible as the call's latency
scaling with view size); a ticket that is not a `TicketStatementQuery`; or an endpoint list with a
length other than 1.
**Setup:** `H-FL`.
**Steps:** `FlightInfo info = client.execute("SELECT user_id, total FROM user_volume")` and inspect
`info.getSchema()`, `info.getEndpoints()`, and the ticket bytes.
**Expected:** one endpoint, whose location is the server's own. The ticket is
`Any.pack(TicketStatementQuery)` whose `statementHandle` is the UTF-8 of the SQL — so
`new String(ticket, UTF_8)` contains `SELECT user_id, total FROM user_volume`. **Record that**: the
ticket is the query text in the clear, which a proxy or a log may retain. The schema has two fields,
`user_id` and `total`.

## API-148 — `getStream` on that ticket returns the rows
**Intent:** the fetch half, and the exact contents.
**Falsifier:** a row count other than 3, a null rendered as an empty string, or the rows arriving in
more batches than API-151 predicts.
**Setup:** `H-FL` (three committed rows: `u1/gold/300`, `u2/silver/50`, `u3/null/7`).
**Steps:** iterate `client.getStream(info.getEndpoints().get(0).getTicket())`.
**Expected:** `3` rows: `u1|300`, `u2|50`, `u3|7`. With `SELECT user_id, tier, total` instead, the
third row's `tier` is **null** (`vector.isNull(2)` true), not the empty string. One record batch of
3 rows, then the final batch of 0 (API-151).

## API-149 — `getSchema` is not implemented, though `getFlightInfo` returns a schema
**Intent:** `PravahaFlightSqlProducer` overrides neither `getSchemaStatement` nor
`getSchemaPreparedStatement`, so `FlightSqlProducer.getSchema` dispatches into
`NoOpFlightSqlProducer` and throws. A client that asks for a schema the cheap way is refused, while
the same schema is available from `getFlightInfo` — and `FlightSqlClient.getExecuteSchema` is the
documented way to ask.
**Falsifier:** `getSchema` returning a schema (which would mean it was implemented — retire this
case), or returning a schema that differs from `getFlightInfo`'s.
**Setup:** `H-FL`.
**Steps:** `client.getExecuteSchema("SELECT user_id, total FROM user_volume")`; then the prepared
equivalent `client.getExecuteSchema(preparedStatement)`.
**Expected:** both throw `FlightRuntimeException` with `CallStatus.UNIMPLEMENTED` and the
description `Not implemented.` — Arrow's own words, carrying no PRV code and no hint that
`getFlightInfo` would have answered. Record it as a gap in the surface, not as a bug in a case.

## API-150 — the Arrow schema marks every field nullable, including NOT NULL ones
**Intent:** `ArrowSchemas.toArrow` builds each field with `FieldType.nullable(arrowTypeOf(field))`
unconditionally, discarding the Pravaha type's nullability. REST reports the same schema with
`nullable: false` (API-078). Two surfaces, one schema, two answers.
**Falsifier:** the Arrow schema carrying `nullable = false` for `user_id`/`total` — which would mean
it was fixed and API-078 agrees.
**Setup:** `H-FL`.
**Steps:** from API-147's `FlightInfo`, read `schema.getFields()` and each field's
`isNullable()`; compare with `GET /api/v1/streams/...` on an equivalent stream.
**Expected:** all three Arrow fields report `isNullable() == true`, including `user_id` (STRING NOT
NULL) and `total` (INT64 NOT NULL). Arrow types: `Utf8`, `Utf8`, `Int(64, signed)`. A client
generating a DDL or a dataframe schema from this gets every column nullable, so a downstream
`NOT NULL` constraint cannot be derived from the wire.

## API-151 — batch boundaries at the 4096-row constant
**Intent:** `BATCH_ROWS = 4096`, and the code emits a final `putNext` unconditionally — so a result
that is an exact multiple of 4096 ends with an empty batch, and a result of zero rows is one empty
batch. Enumerated, because "it streams" is not a contract and the row counts per batch are.
**Falsifier:** a client seeing fewer rows than the view holds; or zero batches for an empty result,
which leaves a client waiting for data that will never come.
**Setup:** `H-FL` variants whose view holds `n` rows for
`n` in `{0, 1, 4095, 4096, 4097, 8192}`, built by `applyValues` over keys `u00001…` and one
`commit`.
**Steps:** for each `n`, run `SELECT user_id, total FROM user_volume` and record
`(batch count, rows per batch, total rows)`.
**Expected:**

| n | batches | rows per batch | total |
|---|---|---|---|
| 0 | 1 | 0 | 0 |
| 1 | 1 | 1 | 1 |
| 4095 | 1 | 4095 | 4095 |
| 4096 | 2 | 4096, 0 | 4096 |
| 4097 | 2 | 4096, 1 | 4097 |
| 8192 | 3 | 4096, 4096, 0 | 8192 |

Arithmetic: `4097 = 4096 + 1`; `8192 = 4096 + 4096 + 0`. The totals must equal `n` exactly in all
six.
**Vacuity:** the totals are checked as well as the batch counts, so a build that emitted one giant
batch would fail the batch-count column while a build that dropped the tail would fail the total —
neither can pass by accident.

## API-152 — an unknown view gives one error whether or not anything else is registered
**Intent:** a client branching on the Flight status must not see a different answer to the same
mistake in two deployments.
**Falsifier:** the two cases producing different codes or different statuses.
**Setup:** (a) `H-FL` with its one view; (b) the same server built on an **empty** `ViewCatalog`.
**Steps:** `client.execute("SELECT * FROM nope")` on each, and iterate the stream.
**Expected:** both `CallStatus.NOT_FOUND`, both carrying `PRV-4023`. (a) names `nope` and does not
name `user_volume`; (b) says `no views are registered, so there is nothing to query. A view is
created by registering a continuous query that serves one.`

**This case's own falsifier came true, and it is the reason to keep it.** As written it recorded
`ViewQuery.relFor` short-circuiting to `PRV-4023` only over an **empty** catalogue and handing the
SQL to the planner otherwise, which answered `PRV-2002 … Known streams: [every view]` — same mistake
by the user, two codes, two statuses, and one of them enumerating the catalogue. SX-5 removed the
enumeration; finding **L-3** removed the divergence, after measuring the same split reached from the
other side: a read racing a `drop` then `re-register` of one name got the planner's `PRV-2002`,
3 times in 71,823 iterations, where a retry loop needed `PRV-4023`.

## API-153 — the statement path under authentication and authorization
**Intent:** the same three principals against the read path, including the one whose access is
conditional — `ViewQuery.execute` ANDs the row filter into the plan, so ravi gets fewer rows rather
than a refusal.
**Falsifier:** ravi seeing a row whose `tier` is not `gold`; sam seeing any row; an unauthenticated
caller reaching the planner at all.
**Setup:** `H-FLA`, with the `user_volume` view of `H-FL` also registered (`u1/gold/300`,
`u2/silver/50`, `u3/null/7`).
**Steps:** `execute("SELECT user_id, tier, total FROM user_volume")` then `getStream`, four times:
no credential, as dana, as ravi, as sam.
**Expected:**
- no credential → `UNAUTHENTICATED` at `getFlightInfo`, before any planning.
- dana → `3` rows: `u1|gold|300`, `u2|silver|50`, `u3|null|7`.
- ravi → `1` row: `u1|gold|300`. The filter `tier = 'gold'` matches row 1 only; `silver` fails and
  `null` fails (`NULL = 'gold'` is not true). `3 - 2 = 1`.
- sam → `UNAUTHORIZED` (`PRV-7002`), `sam may not read 'user_volume': only analysts read
  user_volume`, and **zero** batches delivered.
**Vacuity:** ravi's single row is the load-bearing observation: it distinguishes "filter applied"
from "filter dropped" (3 rows) and from "denied" (0 rows), which no assertion of the form "ravi got
a successful response" can do.

### D. Tickets (API-154–160)

## API-154 — a ticket that is not ours
**Intent:** `ControlWire.isOurs` checks the magic before anything tries to parse the bytes as
protobuf — "guessing is not telling". What happens after the fall-through is Arrow's business, and
a client deserves to know which.
**Falsifier:** a ticket with our magic being handed to Flight SQL, or a foreign ticket being handed
to `streamSubscription`.
**Setup:** `H-FLR`.
**Steps:** `getStream` with each of: 0 bytes; 4 bytes; 8 random bytes; a valid `Any` packing an
unrelated message; a ticket copied from a **different** Pravaha server's `getFlightInfo` (same
build, different process, same SQL); a ticket whose first four bytes are the magic but whose
remainder is garbage.
**Expected:** the last one is recognised as ours and refused with `PRV-6102`
(`this Pravaha request is malformed` or `this is not a subscription ticket`) → `NOT_FOUND`. The
random and empty ones go to `FlightSqlProducer.getStream`, whose `Any.parseFrom` failure is passed
to `listener.error(e)` — record the status the client actually sees (expected `UNKNOWN` or
`INTERNAL`, **not** a PRV code), because that is the one path on this transport where a malformed
request is not answered with a code. The other server's ticket **succeeds**, because the ticket
carries only SQL and this server has the same view — statelessness means a ticket is portable
between nodes, which is by design and worth writing down.

## API-155 — a subscription ticket for a query that does not exist, or was dropped
**Intent:** `streamSubscription` calls `required.require(viewName)` **before** `policy.mayRead`, so
the `PRV-8002` message — which enumerates every registered name — is produced for a caller who has
not yet been authorized for anything.
**Falsifier:** the refusal arriving before the name list is built; or a dropped query's ticket still
streaming.
**Setup:** `H-FLA`, three registrations, called as **sam** (denied everything).
**Steps:**
1. `getStream(ControlWire.subscribeTicket("no_such_view", List.of()))` as sam.
2. Drop `q_gamma` as dana, then `getStream(subscribeTicket("q_gamma", List.of()))` as dana.
**Expected:** (1) `INVALID_ARGUMENT` (PRV-8002 is outside the mapping table) with the description
`no query named 'no_such_view' is registered; this node has [q_alpha, q_beta, q_gamma]` — **sam,
who may read nothing, is told every query name.** (2) the same code for `q_gamma` after the drop,
now listing `[q_alpha, q_beta]`, so repeated probing also reveals the inventory changing over time.

## API-156 — a query dropped while a subscription is streaming ends the stream
**Intent:** the loop condition includes `!query.state().isTerminal()`, so a drop must end the call
rather than leaving a subscriber attached to a computation that no longer exists.
**Falsifier:** the stream continuing after the drop; or the client hanging; or the server thread
staying alive (visible as a leaked thread over repetitions).
**Setup:** `H-FLR`, `q_alpha` RUNNING, a subscriber attached and receiving.
**Steps:** attach a subscriber to `q_alpha`; feed rows until at least two batches have been
received; issue `pravaha.drop` for `q_alpha`; observe the subscriber.
**Expected:** the subscriber's stream completes (or errors) within one poll interval — the loop
polls the handover queue with a 200 ms timeout, so within about `200 ms + the commit cadence`. No
further batch arrives after the drop. Repeat 50 times and confirm the process's thread count returns
to its starting value ±2.
**Vacuity:** rows must be flowing before the drop (assert at least two batches received), otherwise
a subscription that had already ended for want of data would "pass".

## API-157 — the subscription ticket's happy path, with and without a filter
**Intent:** the ticket's framing (`["subscribe", view, col, val, …]`), one batch per commit, and the
filter's effect.
**Falsifier:** a batch spanning two commits; the filter being ignored; or a filter on an unknown
column being accepted.
**Setup:** `H-FLR`, `q_beta` (`SELECT user_id, status FROM txn`, keys `[0]`).
**Steps:**
1. `getStream(subscribeTicket("q_beta", List.of()))`, then commit two changes in one commit and one
   in the next.
2. `getStream(subscribeTicket("q_beta", List.of("status", "COMPLETED")))` with a mixed feed.
3. `getStream(subscribeTicket("q_beta", List.of("nosuchcol", "x")))`.
**Expected:** (1) two record batches, of `2` and `1` rows — "a batch boundary is a commit boundary".
(2) only rows whose `status` is exactly `COMPLETED`; feed 3 `COMPLETED` and 2 `PENDING` and receive
exactly `3`. (3) a refusal from `SubscriptionFilter.matching` naming the unknown column, with a PRV
code, before any batch is delivered.

## API-158 — a subscriber whose access is conditional is refused, not over-served
**Intent:** the `PRV-7003` path. A subscription has no plan to AND a row filter into, and the code
fails closed rather than delivering every row to a principal entitled to some of them.
**Falsifier:** ravi receiving any batch at all. This is the falsifier that matters: a silent
over-serve here is an entitlement breach that looks like success.
**Setup:** `H-FLA`, as **ravi** (`allowWithRowFilter("tier = 'gold'")`).
**Steps:** `getStream(subscribeTicket("q_alpha", List.of()))` as ravi.
**Expected:** `CallStatus.UNAUTHORIZED` (PRV-7003 maps there alongside 7002), with the long
explanation beginning `ravi may not subscribe to 'q_alpha' because their access to it is conditional
on the row filter 'tier = 'gold''` and ending with the two suggested remedies. **Zero** batches
delivered. Then confirm the contrast in one run: dana subscribes to the same view and receives
batches, sam is refused with `PRV-7002`, ravi is refused with `PRV-7003` — three principals, three
outcomes, three codes.

## API-159 — cancellation mid-stream
**Intent:** `listener.setOnCancelHandler(finished::countDown)` and the `!listener.isCancelled()`
checks in both the loop and `writeBatch`. Without them a subscription outlives its subscriber and
the query keeps assembling batches for nobody.
**Falsifier:** the server continuing to write after cancellation; the `Subscription` not being
closed (its `close()` runs in the try-with-resources); or a leaked thread per cancelled call.
**Setup:** `H-FLR`, `q_beta`, a steady feed of 100 rows/s.
**Steps:** attach a subscriber, receive at least 3 batches, call `stream.cancel("client done",
null)`, keep feeding for 5 s, then inspect the server: thread count, the query's subscriber count,
and the audit sink.
**Expected:** the server-side call returns within ~200 ms of the cancel (one poll interval); the
query has zero subscribers afterwards; thread count returns to baseline; and no further
`putNext` is attempted. Repeat the cycle 100 times: the final thread count and the allocator's
outstanding-byte count must match the values recorded before the loop.
**Vacuity:** the continued feed after the cancel is what makes this real — a source that had run dry
would produce the same quiet server whether or not cancellation worked.

## API-160 — a slow subscriber loses batches and the loss is recorded, not hidden
**Intent:** the handover queue is `SUBSCRIPTION_HANDOVER_BATCHES = 64` and the producer calls
`offer`, never `put` — "a full queue means this subscriber is slower than the query, and the answer
is to lose its batches rather than the engine's pace". The count is written to the audit sink on
close.
**Falsifier:** the engine's commit thread blocking on a slow subscriber (visible as the query's
`rowsIn` stalling); or batches being dropped with no audit record.
**Setup:** `H-FLR` with an `AuditSink.InMemory`; a subscriber that sleeps 500 ms per batch; a feed
fast enough to commit more than 64 batches during one sleep.
**Steps:** attach the slow subscriber to `q_beta`; feed 10 000 rows at the fastest rate the source
allows; close the subscriber; read the audit sink.
**Expected:** the query's `rowsIn` reaches `10_000` — the engine was never slowed. The audit sink
holds one event with action `subscribe.dropped` whose detail is `<n> batches dropped for a slow
subscriber` with `n >= 1`. The subscriber's received row count is strictly less than `10_000`, and
the difference is accounted for by the dropped batches.
**Vacuity:** assert `rowsIn == 10_000` **and** `received < 10_000` **and** `n >= 1` together; any
one of the three alone is satisfiable by a source that ran dry or a subscriber that kept up.

### E. Prepared statements (API-161–171)

The server keeps **nothing** between these calls: the handle carries the SQL and, once bound, the
client's own Arrow IPC bytes. Every case here is also a case about what statelessness costs.

## API-161 — `createPreparedStatement` returns both schemas before any value is bound
**Intent:** the dataset schema lets a client lay out a grid while the user is still typing; the
parameter schema tells it which types to send so it never has to guess.
**Falsifier:** either schema absent; a parameter schema whose field count disagrees with the
placeholders in the SQL; or a handle that does not decode.
**Setup:** `H-FL`.
**Steps:** `client.prepare("SELECT user_id, total FROM user_volume WHERE user_id = ? AND total > ?")`.
**Expected:** one `Result`. `datasetSchema` has two fields (`user_id` Utf8, `total` Int(64)).
`parameterSchema` has **two** fields named `param_1` and `param_2`, both `FieldType.nullable` (every
parameter is nullable by construction — "a caller is allowed to bind NULL to any placeholder"), with
types matching the inferred placeholder types. The handle decodes via `StatementHandle.decode` to
version `1`, the original SQL, and no bound parameters.

## API-162 — `createPreparedStatement` on SQL that will not plan
**Intent:** preparation plans, so an invalid statement must fail here rather than at fetch.
**Falsifier:** a handle being issued for SQL that cannot be planned.
**Setup:** `H-FL`.
**Steps:** prepare `SELECT nope FROM user_volume`, then `SELECT * FROM absent_view`, then `not sql
at all`, then `""`.
**Expected:** each fails through `listener.onError` with the engine's own code:
`PRV-2002` → `INVALID_ARGUMENT` for the unknown column (message ending
`. Known streams: [user_volume]`), likewise for the absent view, `PRV-2001` → `INVALID_ARGUMENT`
for the nonsense and the empty string. No handle is returned in any of the four.

## API-163 — `getFlightInfoPreparedStatement` re-plans from the handle
**Intent:** the handle carries SQL, so this call plans again — which is what makes the second call
able to reach a different node. It also means a policy change between `prepare` and `getFlightInfo`
takes effect, since `queries.prepare(handle.sql(), principalOf(context))` re-authorizes.
**Falsifier:** a cached plan being reused (visible as a revoked principal still getting a schema).
**Setup:** `H-FLA`, dana prepares `SELECT user_id, tier, total FROM user_volume`.
**Steps:** (1) dana calls `getFlightInfo` with the handle → expect success. (2) Replace the policy
so dana is denied (rebuild the server, or use a policy whose answer depends on a flag), then call
again with the **same** handle.
**Expected:** (1) a `FlightInfo` whose schema matches API-161's dataset schema. (2)
`UNAUTHORIZED` with `PRV-7002` — the handle does not carry an entitlement, so revocation is
effective immediately. That is the property statelessness buys and it should be asserted, not
assumed.

## API-164 — `getStreamPreparedStatement` with nothing bound
**Intent:** `handle.boundParameters()` empty → `BoundParameters.none()`. A statement with no
placeholders must simply run.
**Falsifier:** a refusal for the unbound case, or an empty result.
**Setup:** `H-FL`; prepare `SELECT user_id, total FROM user_volume` (no placeholders).
**Steps:** `getFlightInfo` with the handle, then `getStream` on its ticket.
**Expected:** `3` rows, identical to API-148's — the prepared path and the plain path must return
the same rows for the same SQL, and this is the assertion that keeps `emit` shared between them.

## API-165 — `acceptPutPreparedStatementQuery` binds values and hands back an updated handle
**Intent:** the DoPut on this transport binds **parameters, not data**. There is no ingest path
here, and the mechanism that keeps the values on the client's side of the wire is the updated
handle.
**Falsifier:** the server retaining the values (visible as the original handle working for the
fetch); or no `PutResult` metadata coming back.
**Setup:** `H-FL`; prepare `SELECT user_id, total FROM user_volume WHERE user_id = ?`.
**Steps:** bind one row with `user_id = 'u1'` via the stock client's
`PreparedStatement.setParameters(root)` + `execute()`; capture the returned handle; then fetch.
**Expected:** exactly one `PutResult` carrying a `DoPutPreparedStatementResult` whose
`preparedStatementHandle` decodes to the **same SQL** and now carries non-empty
`boundParameters`, byte-identical to the Arrow IPC the client wrote. The fetch on the updated handle
returns `1` row, `u1|300`. The fetch on the **original** (unbound) handle returns `3` rows — proof
the server stored nothing.

## API-166 — parameter values are checked against the placeholders
**Intent:** `ArrowParameters.decode` enforces three rules: at least one batch, exactly one row, and
exactly as many columns as placeholders.
**Falsifier:** a binding with the wrong arity being accepted and silently matching nothing.
**Setup:** `H-FL`; prepare `SELECT user_id, total FROM user_volume WHERE user_id = ? AND total > ?`
(two placeholders).
**Steps:** bind, in turn: zero rows; two rows; one row with one column; one row with three columns;
one row with two columns of the right types (control).
**Expected:**
- zero rows → `PRV-6102` `the bound parameters carried no rows` → `NOT_FOUND`.
- two rows → `a binding must carry exactly one row of values and this one has 2. Binding several
  rows means running the statement several times, which is a different call`.
- one column → `this statement has 2 placeholders and 1 were bound`.
- three columns → `this statement has 2 placeholders and 3 were bound`.
- the control → rows returned. With `('u1', 100)`: `300 > 100` so `1` row; with `('u1', 1000)`:
  `300 > 1000` false so `0` rows and one empty batch (API-151's n=0 line).

## API-167 — a Utf8 parameter arrives as a `String`, not as Arrow's `Text`
**Intent:** `normalise` exists because a `Text` is a `CharSequence` whose `equals` against a
`String` is false — "a filter that matches nothing, with no error anywhere". The falsifier is
silence, so the case must assert a **non-empty** result.
**Falsifier:** zero rows for a binding that should match — which is exactly what the un-normalised
code produced.
**Setup:** `H-FL`; prepare `SELECT user_id, total FROM user_volume WHERE user_id = ?`.
**Steps:** bind `'u1'`, `'u2'`, `'u3'`, and `'nope'` in four separate executions.
**Expected:** `1`, `1`, `1` and `0` rows respectively — `u1|300`, `u2|50`, `u3|7`. The three
non-empty results are the assertion; a build without `normalise` returns `0, 0, 0, 0` and would pass
any test that only checked "no error".

## API-168 — `closePreparedStatement` succeeds and the handle keeps working afterwards
**Intent:** the method is an empty `onCompleted` — "there was never anything held" — so a closed
handle is indistinguishable from an open one. A client that closes and then (wrongly) reuses gets
rows rather than an error, and a client that never closes leaks nothing.
**Falsifier:** `closePreparedStatement` returning an error; or a handle being refused after close
(which would mean state was being held after all).
**Setup:** `H-FL`; a bound handle from API-165.
**Steps:** `close` the prepared statement, then `getFlightInfo` and `getStream` with the same handle
bytes; then `close` the same handle a second time; then `close` a handle that was never issued
(random bytes).
**Expected:** every call succeeds. The post-close fetch returns the same `1` row as before the
close. The double close succeeds. The close of a fabricated handle also succeeds — `closePreparedStatement`
never decodes its argument, so it cannot refuse anything. Record that: it means a client cannot
detect a handle mix-up by closing.

## API-169 — a handle from another session, or another server
**Intent:** statelessness means a handle is a bearer token for a **query**, not a session. Any
principal holding the bytes can fetch with them — subject to re-authorization at fetch time, which
is what makes it safe.
**Falsifier:** a handle issued to dana being usable by sam **without** a fresh policy check; or a
handle being rejected purely because a different connection issued it (which would mean hidden
state).
**Setup:** `H-FLA`. Dana prepares `SELECT user_id, tier, total FROM user_volume` and the handle
bytes are copied out.
**Steps:** (1) a second connection, also as dana, fetches with the handle. (2) sam fetches with
dana's handle. (3) ravi fetches with dana's handle. (4) an unauthenticated connection fetches with
it.
**Expected:** (1) `3` rows — handles are portable across connections by design. (2)
`UNAUTHORIZED` (`PRV-7002`) — `getStreamPreparedStatement` calls `queries.prepare(handle.sql(),
principalOf(context))`, so the policy runs on the fetching principal, not the issuing one. (3) `1`
row (`u1|gold|300`), ravi's row filter applied to the re-planned statement. (4)
`UNAUTHENTICATED`. The handle confers no authority; it only names a query.

## API-170 — a malformed, truncated or future-version handle
**Intent:** `StatementHandle.decode` bounds-checks every read and answers `PRV-6102`, which maps to
`NOT_FOUND` precisely so the client's move is "prepare the statement again".
**Falsifier:** an exception with an index in it; or a handle with a negative length being acted on.
**Setup:** `H-FL`.
**Steps:** `getFlightInfoPreparedStatement` with each of: 0 bytes; 1 byte (`0x01`); a valid handle
truncated at 6 bytes; a valid handle with the version byte set to `2`; a valid handle with the SQL
length set to `-1`; the same with the SQL length set past the end; a valid handle with 4 trailing
junk bytes.
**Expected:** version `2` → `NOT_FOUND` with `this handle was issued by a different version of the
server; prepare the statement again`. Every other malformed input → `NOT_FOUND` with
`this prepared-statement handle is malformed`. None produces `INTERNAL`, and none produces a stack
trace in the description. The trailing-junk case is the interesting one: the decoder reads the
declared lengths and ignores the remainder, so it is expected to **succeed** — record it, because a
handle that decodes with unread bytes left over is a place where a future field would be
silently dropped.

## API-171 — a binding larger than the 1 MiB ceiling
**Intent:** `MAX_PARAMETER_BYTES = 1 << 20 = 1_048_576`, enforced in `boundTo`, because the handle
travels on every call that uses it and a large binding is paid for repeatedly.
**Falsifier:** a 2 MiB binding being accepted; or the ceiling being enforced somewhere the message
does not explain.
**Setup:** `H-FL`; prepare `SELECT user_id, total FROM user_volume WHERE user_id = ?`.
**Steps:** bind a single Utf8 value of length 1024 (control); then 1_048_000 bytes (just under);
then 2_097_152 bytes (twice the ceiling).
**Expected:** the control and the just-under binding succeed — the IPC envelope adds a few hundred
bytes, so a payload of `1_048_000` may or may not cross `1_048_576`; record the exact encoded size
from the error message when it does. The 2 MiB binding fails with `PRV-6103`
(`FLIGHT_PARAMETERS_TOO_LARGE`) whose message states the actual byte count and the ceiling and
recommends sending the values as data. `PRV-6103` is not in `statusFor`'s table, so the client sees
`INVALID_ARGUMENT` — correct here, and worth recording next to `PRV-6102`'s `NOT_FOUND`.

### F. Flight SQL's catalog and metadata commands (API-172–177)

`PravahaFlightSqlProducer` extends `BasicFlightSqlProducer`, which **implements `getFlightInfo` for
every metadata command** and leaves every corresponding `getStream*` to `NoOpFlightSqlProducer`,
which throws `UNIMPLEMENTED`. So metadata discovery appears to work and then fails at fetch. These
six cases enumerate that, because a JDBC or ADBC client issues these calls unprompted.

## API-172 — `getSqlInfo`
**Intent:** the first call many Flight SQL clients make at connect time, to learn what the server
supports.
**Falsifier:** `getFlightInfo` failing (which would at least be honest), or `getStream` succeeding
(which would mean it was implemented).
**Setup:** `H-FL`.
**Steps:** `client.getSqlInfo()` → returns a `FlightInfo`; then `client.getStream(ticket)`.
**Expected:** `getFlightInfo` succeeds with `Schemas.GET_SQL_INFO_SCHEMA` and one endpoint;
`getStream` throws `UNIMPLEMENTED` with the description `Not implemented.` Record the sequence: a
client is told a stream exists and then refused it.

## API-173 — `getCatalogs` and `getDbSchemas`
**Intent:** the two calls a JDBC browser makes to populate a tree.
**Falsifier:** either `getStream` returning rows (retire the case), or `getFlightInfo` throwing.
**Setup:** `H-FL`.
**Steps:** `client.getCatalogs()` then `getStream`; `client.getSchemas(null, null)` then `getStream`.
**Expected:** both `getFlightInfo` calls return a `FlightInfo` with the standard schema
(`GET_CATALOGS_SCHEMA`, `GET_SCHEMAS_SCHEMA`); both `getStream` calls throw `UNIMPLEMENTED`
(`Not implemented.`). An empty result set would be a better answer than a refusal and is the obvious
fix; note it.

## API-174 — `getTables` and `getTableTypes`
**Intent:** the calls that would list `user_volume` if this server answered them — which is the one
place where implementing the metadata path would actually surface the view catalogue.
**Falsifier:** `getTables(includeSchema = true)` and `(false)` behaving identically at
`getFlightInfo` (they must return different schemas), or either `getStream` succeeding.
**Setup:** `H-FL`.
**Steps:** `client.getTables(null, null, null, null, false)` and the same with `true`; then
`getStream` on each; then `client.getTableTypes()` and `getStream`.
**Expected:** `includeSchema=false` → `GET_TABLES_SCHEMA_NO_SCHEMA`; `includeSchema=true` →
`GET_TABLES_SCHEMA` (one extra binary `table_schema` column) — the two differ, which proves the
`BasicFlightSqlProducer` branch runs. All three `getStream` calls throw `UNIMPLEMENTED`. So a client
cannot discover `user_volume` through the standard catalogue; the only listing is
`pravaha.list`, which is a Pravaha-specific action.

## API-175 — the key-metadata commands
**Intent:** four commands with no meaning for a streaming view, enumerated so the answer is uniform.
**Falsifier:** any of them answering with rows, or any of them failing at `getFlightInfo` while the
others fail at `getStream` — an inconsistency a client would trip over.
**Setup:** `H-FL`.
**Steps:** `getPrimaryKeys`, `getExportedKeys`, `getImportedKeys`, `getCrossReference`, each
followed by `getStream`.
**Expected:** all four `getFlightInfo` calls succeed with their standard schemas; all four
`getStream` calls throw `UNIMPLEMENTED` with `Not implemented.` Four for four, no exceptions.

## API-176 — `getXdbcTypeInfo`
**Intent:** the type catalogue a driver uses to map SQL types. Pravaha has sixteen types and a
documented Arrow mapping (`ArrowSchemas.arrowTypeOf`), so this is the one metadata command with a
real answer available.
**Falsifier:** `getStream` succeeding but returning a type list that disagrees with
`ArrowSchemas.arrowTypeOf` — a wrong answer being worse than none.
**Setup:** `H-FL`.
**Steps:** `client.getXdbcTypeInfo()` then `getStream`; also the filtered form for one data type.
**Expected:** `getFlightInfo` succeeds with `GET_TYPE_INFO_SCHEMA`; `getStream` throws
`UNIMPLEMENTED`. If it ever returns rows, assert every entry against the sixteen types and
their Arrow forms — `BOOLEAN→Bool`, `INT8/16/32/64→Int(n, signed)`, `FLOAT32→FloatingPoint(SINGLE)`,
`FLOAT64→FloatingPoint(DOUBLE)`, `STRING→Utf8`, `BYTES→Binary`, `DATE→Date(DAY)`,
`TIME`/`TIMESTAMP_LTZ→Timestamp(NANOSECOND, "UTC")`, and the types `arrowTypeOf` refuses with
`PRV-6100`.

## API-177 — what a stock JDBC/ADBC client does on connect, end to end
**Intent:** the composite of API-172–176, from the client the protocol was chosen for. ADR-030's
whole argument is that the clients are somebody else's work; this case checks that argument.
**Falsifier:** the JDBC driver failing to establish a connection at all, or `DatabaseMetaData`
throwing where a Flight SQL server is expected to answer.
**Setup:** `H-FL`, plus the Arrow Flight SQL JDBC driver at the same Arrow version (19.0.0), URL
`jdbc:arrow-flight-sql://localhost:<port>/?useEncryption=false`.
**Steps:** open a connection; call `DatabaseMetaData.getDatabaseProductName()`, `getTables(...)`,
`getTypeInfo()`; then `Statement.executeQuery("SELECT user_id, total FROM user_volume")` and read
the rows; then the same through a `PreparedStatement` with one parameter.
**Expected:** the connection opens, and the two **query** paths return `3` rows and `1` row
respectively — the paths Pravaha implements work through an unmodified third-party driver, which is
the claim being tested. The three `DatabaseMetaData` calls fail or return empty; record exactly
which, because a tool that calls `getTables` before allowing a query (most SQL IDEs do) is unusable
against this server, and that is a product finding rather than a bug in a case.

### G. Concurrency and disclosure (API-178–180)

## API-178 — concurrent calls of every kind against one server
**Intent:** the producer holds no per-call state except the allocator and the registry, and both are
shared. This is the case that would find a `VectorSchemaRoot` reused across threads or a registry
map mutated during iteration.
**Falsifier:** any `INTERNAL` status; any batch containing another caller's rows; any
`ConcurrentModificationException`; any allocator leak at the end.
**Setup:** `H-FLR` with `q_alpha`, `q_beta`, `q_gamma`, a view of 10 000 rows, and a steady feed.
**Steps:** for 60 s, run concurrently: 8 threads issuing `execute` + `getStream` on the 10 000-row
view; 4 threads doing `prepare` + bind + fetch; 2 threads calling `pravaha.list` in a loop; 1
thread cycling `pravaha.pause`/`pravaha.resume` on `q_beta`; 2 long-lived subscribers on `q_alpha`;
1 thread registering and dropping `q_tmp_<n>` repeatedly.
**Expected:** every read returns exactly `10_000` rows (`2 × 4096 + 1808`, i.e. three batches);
every `pravaha.list` returns a well-formed 5-field result per registered name; the pause/resume
cycle never yields a state other than `PAUSED`/`RUNNING`; the subscribers receive only `q_alpha`'s
rows. Afterwards: `allocator.getAllocatedMemory()` returns to its pre-run value, and the registry
holds exactly the three original names.
**Vacuity:** the exact row count per read is the assertion — "no exception was thrown" would pass
against a server that returned short results under contention, which is the failure mode this
arrangement actually produces.

## API-179 — the subscription path prints debug lines to the server's stdout
**Intent:** `streamSubscription` contains two `System.out.println("SRVDBG …")` calls — one per
subscription start, one **per batch written** — carrying the view name, an identity hash and a batch
size. On a busy node that is one stdout write per commit per subscriber, in the path the design
says must not be slowed, and it goes to a container's log stream rather than the logger.
**Falsifier:** no `SRVDBG` line appearing (it was removed — retire the case).
**Setup:** `H-FLR`, server stdout captured to a file.
**Steps:** attach one subscriber to `q_beta`; commit 1 000 batches; count `SRVDBG` lines; then
attach 10 subscribers and repeat.
**Expected:** `1` start line plus `1_000` write lines for the single subscriber, and
`10 + 10_000 = 10_010` lines for ten — the count grows with commits × subscribers. The lines are on
**stdout**, not through SLF4J, so no log level suppresses them. Measure the commit-to-delivery
latency with the prints present and with stdout redirected to `/dev/null`, and record the
difference.

## API-180 — the filtered `LIST` is undone by the error messages
**Intent:** the disclosure the brief names, assembled in one case. `pravaha.list` deliberately
filters by `mayRead` so "a principal sees the queries they could read, and does not learn that the
others exist". But three messages enumerate everything, to anyone who can reach them:
`PRV-8002` from `QueryRegistry.require` appends `this node has [every name]`; `PRV-2002` from
`SqlPlanner.plan` appends `. Known streams: [every view]`; `PRV-4023` from `ViewQuery.execute`
appends `this server serves [every view]`. And on the subscription path `require()` runs **before**
`mayRead`, so the disclosure precedes the authorization that was supposed to prevent it.
**Falsifier:** any of the three messages returning a policy-filtered list. That is the fix, and this
case is written so the fix is what makes it pass.
**Setup:** `H-FLA` with three registrations and a view per registration; called as **sam**, who
`pravaha.list` correctly shows nothing (API-140).
**Steps:** as sam, in one session:
1. `doAction("pravaha.list", …)` — record the names returned.
2. `getStream(subscribeTicket("aaaa", List.of()))` — record the error description.
3. `execute("SELECT * FROM aaaa")` — record the error description.
4. `doAction("pravaha.drop", ControlWire.encode("aaaa"))` — record the error description.
**Expected:**
- step 1 returns **zero** names. `0` of `3`.
- step 2 returns `INVALID_ARGUMENT` with `no query named 'aaaa' is registered; this node has
  [q_alpha, q_beta, q_gamma]` — **all three names**, to the principal step 1 correctly told nothing.
- step 3 returns `INVALID_ARGUMENT` with `PRV-2002 … . Known streams: [<every view>]`.
- step 4 returns `UNAUTHORIZED` with `PRV-7002` and **no** name list — because
  `requireAdministrable` runs before `required.drop`, so the drop verb is the one that does not
  leak. That contrast is the proof that the ordering is the mechanism: the same registry lookup
  discloses or does not depending on whether the authorization check precedes it.
**Vacuity:** step 1's empty listing is asserted in the same run as steps 2 and 3, so the case cannot
pass by the node simply having nothing registered — and dana's listing of three in the same session
confirms the three exist.

---

## Coverage note

Budget 180, written 180, numbered `API-001`–`API-180` with no gaps: CLI `001`–`075` (75), REST
`076`–`125` (50), Flight and Flight SQL `126`–`180` (55), which matches the split the brief asked
for.

Three things the wave that executes this should know.

**The CLI's `--help` is nine cases because it is nine behaviours.** API-009–017 look repetitive and
are not: `isHelp` is consulted for `args[0]` only, so six commands report a missing required option,
`queries` opens a network connection, `version` prints the version, and only the top-level form
helps. One fix changes all nine expectations at once, which is exactly why each is written down.

**Several cases pin behaviour that is wrong.** API-021 (a `--sql` value beginning with `--` becomes
the string `true`), API-063 (`pauseped`, `resumeped`), API-080 (201 against a lock file that says
200), API-097 (a caller's typo returned as HTTP 500), API-099 (a missing resource returned as 400),
API-123 (an error code outside every category makes the error handler throw), API-142 (an array
index reaching a client), API-149 (`getSchema` unimplemented while `getFlightInfo` answers),
API-150 (Arrow says nullable where REST says not null), API-179 (`SRVDBG` on stdout) and API-180
(the disclosure) are written to **record the current answer** so that a fix shows up as a diff in
this file. They are not assertions that the current answer is right.

**Three cases need a second body of work to be conclusive.** API-092 and API-174/177 depend on a
stock third-party client and on Tomcat defaults this repository does not set; API-109 (the
`OPEN_PREFIXES` prefix match) needs a route to exist beyond one of the five prefixes before it can
be more than a probe — today every probe is expected to 404, and the case's value is that it will
stop being a 404 the moment someone adds a path under `/api/docs…` or `/actuator/health…`. If that
never happens the case stays cheap; if it does, it is the one that catches it.
