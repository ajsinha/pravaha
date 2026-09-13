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
