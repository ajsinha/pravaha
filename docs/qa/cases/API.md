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
9090** — confirmed with `ss -ltn 'sport = :9090'` returning no row before the section runs.
Constants used throughout:

```
SCHEMA4    = 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
OUTSCHEMA  = 'user_id:STRING,amount:INT64'
IN         = examples/01-filter-and-project/transactions.csv
FILTERSQL  = "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100"
```

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
`security.verifier()` returns null). HTTP on 8080, Flight on 9090. `application.yaml` amended with:

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
fails locally rather than dialling `grpc://localhost:9090`.
**Falsifier:** exit 1 with a connection message (which would mean the connection is attempted
first), or a hang.
**Setup:** `H-CLI` (nothing on 9090).
**Steps:** `time pravaha query --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `2`; `err.txt` is `--sql is required. Supplied: [help]`; `out.txt` empty; elapsed
under 2 s with no TCP connection attempted to 9090.

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
**Setup:** `H-CLI`, nothing listening on 9090.
**Steps:** `pravaha queries --help >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `err.txt` names `localhost:9090` and carries the SDK's
`PRV-…  cannot connect to localhost:9090: …` or a Flight `UNAVAILABLE` description reaching it
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
**Steps:** `pravaha explain --sql FILTERSQL --schema SCHEMA4 --level codegen >out.txt; echo $?`
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

## API-062 — `query` against a view that does not exist exits 1 and lists every view
**Intent:** two things at once: the exit code for an engine refusal, and the disclosure in
`PRV-4023`'s message, which names every view this server serves regardless of who is asking.
**Falsifier:** exit 0 (the defect the brief names — a query that threw once printed `ok` and exited
0), or a message that does not carry the PRV code.
**Setup:** `H-SRV` with `by_user` and `by_user_2` registered.
**Steps:** `pravaha query --sql "SELECT * FROM nope" >out.txt 2>err.txt; echo $?`
**Expected:** exit `1`; `out.txt` empty — in particular it contains no `0 rows` line, because the
failure happens before iteration; `err.txt` carries
`PRV-4023 … 'nope' is not a registered view; this server serves [by_user, by_user_2]`. Record the
bracketed list: on `H-SRVA` the same message is served to any authenticated caller, which is the
REST/Flight disclosure tracked at API-116 and API-180.

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
`out.txt` empty; no TCP connection to 9090. Then the mixed case
`--filter "user_id=u1,status"` must fail the same way on the second pair, having accepted the first.
