# Serving: views, reads, Flight SQL and the PostgreSQL gateway

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Part of [the architecture](../ARCHITECTURE.md). A query's answer is a **served view**: committed rows,
indexed, readable by SQL through the same planner the query used, and followed by subscribers. Reading
from a client is [`USER_GUIDE.md` §3–§4](../../guides/USER_GUIDE.md#3-ask-a-question); the wire
contracts are the [client developer guide](../../development/guides/CLIENT_DEVELOPMENT.md).

---

## `pravaha-serving`

**Purpose.** Hold a query's answer and answer questions about it, with consistency declared per read.

| Key type | Role |
|---|---|
| `ServedView` | The answer: rows by key, **committed and pending kept apart** — updates land in a pending overlay and move into the visible map when the frontier commits. Bounded by a key ceiling (`QueryRegistry.DEFAULT_MAX_KEYS`, 1,000,000) enforced on commit |
| `ViewSink` | Where a query writes when its answer is meant to be read: `laneOutput()` for each lane, `commitApplied()` to publish, `onCommit` / `onCommitFromSnapshot` / `onRetainedAnswer` for listeners. Every batch and every commit takes one publish lock |
| `ViewCatalog` | The views a client may query, by name, scoped to a principal's tenant (`scopedTo`, `shownTo`) |
| `ViewQuery` | SQL over a view: authorize, plan with the engine's own planner, narrow, admit, run in an `InterpretedPipeline`, `finish()`. `execute(sql, principal)`, `prepare(...)` and `execute(prepared, parameters, principal)` for bound parameters |
| `ViewAccessPath`, `EqualityIndex`, `KeyRows` | Which rows a read must look at: a whole-key probe, a run of an ordered last key column (`RANGE`), the rows an equality index files under a value (`INDEX`, [ADR-055](../adr/055-an-equality-index-over-a-column-outside-the-key.md)), or every row |
| `ReadAdmission` | Bounds reads three ways — concurrency, queue depth, per-tenant share — and refuses past them (`PRV-4026`–`PRV-4028`, sent as `RESOURCE_EXHAUSTED`) |
| `Retention` | How long a view keeps a row, **in event time** |
| `Consistency` | `Latest` (the overlay included) or `Consistent` (the committed frontier, the default) |
| `RowNarrowing` | A principal's row filter and masks applied to rows leaving the view — for a read, a subscription's snapshot and commits, and what an alert sees ([governance](governance.md#row-filters-and-masks)) |
| `AnswerListener`, `AnswerChanges` | Follow the *answer* (each row a reader sees, once) rather than the changelog — what queries on queries and alerts are built on |
| `ServingErrors` | `PRV-4020`–`PRV-4029`: no such view (`4023`), a result past 1,000,000 rows (`4024`), admission (`4026`–`4028`), a read past its deadline (`4029`) |

**Talks to.** `pravaha-sql` (reads are planned by `SqlPlanner` and `PhysicalPlanBuilder` — this is the one
module besides `pravaha-sql` that uses Calcite's `RelNode`), `pravaha-runtime` (the pipeline a read runs
in), `pravaha-security` (decisions, narrowings, audit), `pravaha-state`.

**Threads.** A read runs on the caller's thread, never on a lane, ending its batches every few thousand
rows so its arena is reclaimed as a lane's is. A commit runs on whichever thread commits — a feed's timer, a
checkpoint's lane task, a caller.

**Invariants.**

- **A read never sees half a batch.** Pending and committed are separate maps; a `Consistent` read is
  the committed map.
- **Same SQL, same meaning.** A read is planned and executed by the operators a continuous query uses,
  so a `WHERE` on a view and in a query cannot disagree.
- **Authorize the text before resolving it.** `ViewQuery.execute` authorizes the name the SQL text gives
  (`SqlPlanner.referencedTable`, parse only) before planning, so a view that does not exist and one you
  may not read are both `PRV-7002` — no existence oracle (SX-5).

**Retention, and what it is for.** A view over a query that does not aggregate gains a row per event
forever; left alone it becomes a second copy of the source. Retention says how long the view is
relevant, in event time — "today", "the last hour" — and turns an unbounded liability into a sized one.
It is a statement of **meaning**, so it is part of the fingerprint; the row ceiling is a statement of
**capacity**, and it fails rather than forgets. Retention is not durability: what survives a restart is
the checkpoint, and the view is in it. The principle is [`CONCEPTS.md` §7](../../guides/CONCEPTS.md#7-bounds-what-changes-the-answer-and-what-protects-the-machine);
the clause is `RETAIN FOR`, in [`CONTINUOUS_QUERIES.md`](../../guides/CONTINUOUS_QUERIES.md).

**Admission.** Continuous queries and request/response share an engine
([ADR-030](../adr/030-flight-sql-as-the-client-protocol.md)), so an unbounded read path lets a client in
a loop starve a query of CPU. `ReadAdmission` bounds the work happening, the work waiting, and any one
tenant's share of both, and a gateway is given one with `admitting(admission, readDeadline)`.
On a node, `pravaha.serving.read.*` (`ReadLimits`) builds one admission that the Flight and PostgreSQL
gateways share, and one read deadline; the embedded engine reads the same keys. The defaults admit every
read with no deadline, as before READADMIT-1 ([OPERATIONS](../../operations/OPERATIONS.md#admission-control)).

### A read, step by step

The sequence diagram is in the overview's [trace one](../ARCHITECTURE.md#3-trace-one-a-kafka-record-becomes-a-change-a-subscriber-sees-and-a-row-a-psql-user-reads).
In code, `ViewQuery.execute(sql, principal)` is, in order:

```java
// pravaha-serving/.../ViewQuery.java, execute(String sql, Principal principal) -- abridged
ViewCatalog scope = catalog.scopedTo(principal);
java.util.Optional<String> named = plannerForParsing(scope).referencedTable(sql);
AccessDecision authorized =
        named.isPresent() ? authorizeRead(principal, ViewNames.resolve(principal, named.get()), sql) : null;

RelNode rel = relFor(scope, sql);
PhysicalOperator plan = physicalOf(rel, BoundParameters.none());
...
if (decision.rowFilter().isPresent()) {
    plan = withRowFilter(plan, view, decision.rowFilter().get());
}
plan = authorizeProvenance(plan, view, principal, "query", sql);
narrowing = narrowed(rel, view, source, engine, principal);
...
try (ReadAdmission.Lease _ = admission.acquire(principal)) {
    return run(plan, view, narrowing);
}
```

A row filter from the policy is inserted **into the plan**, directly above the scan — never concatenated
into SQL text, where `OR TRUE` would remove it — and it is refused (`PRV-7003`) when the view no longer
carries every column the filter names, because a filter cannot separate rows an aggregate has already
mixed ([`SECURITY.md`](../../operations/SECURITY.md#row-filters-and-the-rule-that-bounds-them)).

---

## `pravaha-flight`

**Purpose.** The client gateway: Arrow Flight SQL for request/response, plus Pravaha's own Flight
actions and tickets for what Flight SQL has no words for — registering, managing and subscribing
([ADR-030](../adr/030-flight-sql-as-the-client-protocol.md)). One protocol, whose JDBC, ADBC, Python and
Go clients are maintained upstream.

| Key type | Role |
|---|---|
| `PravahaFlightServer` | Builder and lifecycle: `authenticatedBy(TokenVerifier)`, `encryptedWith(chain, key)`, `authorizedBy(SecurityPolicy, AuditSink)`, `admitting(ReadAdmission, Duration)`, `withDeadLetters(store)`, `observedBy(observation)`, `hosting(QueryRegistry)`, `start(host, port)` |
| `PravahaFlightSqlProducer` | The producer. Flight SQL statements and prepared statements go through `ViewQuery`; a statement `ContinuousStatements` recognises (`CREATE CONTINUOUS QUERY`, `SHOW CONTINUOUS QUERIES`, …) goes to the registry; `doAction` routes every `pravaha.*` action (`ControlWire.REGISTER`, `DROP`, `LIST`, `PAUSE`, `RESUME`, `REPLACE`, `CUTOVER`, `ROLLBACK`, `ABANDON`, `FINISH`, `BACKFILL`, `REPLACEMENT`, `DLQ_*`, `DEBUG_*`) |
| `DebugActions`, `DeadLetterActions` | The debugger's nine verbs and the three dead-letter verbs, split out at the producer's size ceiling |
| `SubscriptionTicket`, `SubscriptionHandover` | A ticket naming the view, whether to start from a snapshot, whether to follow the answer, a tap filter and a buffer preference (`subscribe`, `subscribe.snapshot`, `subscribe.answer`, `subscribe.answer.snapshot`); what stands between the commit thread and one subscriber's call. A slow subscriber falls behind in its buffer and is told `PRV-6105` |
| `PrincipalMiddleware` | The `authorization: Bearer` header on every call, verified into a `Principal` — Flight has no session, so a load balancer need not pin a client |
| `RequestShapeGuard`, `FlightSqlMetadata`, `FlightTransactions`, `ArrowSchemas`, `ArrowParameters`, `StatementHandle` | Refusing shapes the producer cannot read (`PRV-6106`); the catalogue metadata a SQL client asks for; transaction verbs refused in the engine's vocabulary; types on the wire; bound parameters; prepared-statement handles |
| `ObservedFlightProducer`, `FlightObservation` | Every call reported with its operation and query, and the client's `traceparent` continued |
| `FlightErrors` | `PRV-6100`–`PRV-6106`; `ControlWire.BAD_REQUEST` is `PRV-6102` |

**Threads.** Flight calls run on virtual threads; Netty event loops carry the sockets. A subscription's
records are written on its call by its handover; the commit thread only fills the subscription's buffer.

**Example.** `pravaha register --name big_txn --keys 0 --sql "SELECT txn_id, user_id, amount FROM txn
WHERE amount > 1000"` sends `pravaha.register` with the fields `[name, sql, "0", sink, retention]`
framed by `ControlWire.encode`; the producer answers `[name, state, fingerprint-short-form]`.
`pravaha subscribe --view big_txn --snapshot` then opens a `subscribe.snapshot` ticket and receives the
view's rows marked as a snapshot, then one Arrow batch per commit, each row with its weight.

---

## `pravaha-pgwire`

**Purpose.** The PostgreSQL wire protocol, **read half**: the simple and extended query protocols,
`psql`'s catalogue queries, TLS on `SSLRequest`, answered by the same `ViewQuery` and the same
authorization as Flight. Off by default (`pravaha.pgwire.enabled`, port `pravaha.pgwire.port`, 5432).

| Key type | Role |
|---|---|
| `PravahaPgWireServer`, `PgWireConnection`, `PgConnections`, `PgWireLimits` | The listener, one connection from its startup packet to its last message (each on a thread of the gateway's pool), and what the gateway holds at once — connections, unauthenticated ones, per principal, message size, idle time (`pravaha.pgwire.limits.*`, PGPREAUTH-1) |
| `PgFrontend`, `PgBackend` | Messages in and out |
| `PgExtendedSession`, `PgStatement`, `PgPortal`, `PgParameterSyntax` | Parse/Bind/Describe/Execute/Close; `$1` becomes `?` |
| `PgCatalogShim`, `PgInformationSchema`, `PgOidRegistry`, `PgPublicSchema`, `PgShow`, `PgSessionSet`, `PgConstantSelect`, `PgTransactionBlock`, `PgTrailingLimit`, `PgTypes` | Enough of `pg_catalog`, `information_schema`, `SHOW`, `SET`, `SELECT 1`, transaction control and `LIMIT n` for `psql`, JDBC, ADBC, Npgsql and Power BI |
| `PgTls` | TLS from a PEM chain and key; plaintext refused when TLS is required (`PRV-6221`) |
| `PgWireErrors` | `PRV-6200`–`PRV-6221`, each mapped to a SQLSTATE |

**Invariants.** Read-only: anything that is not a read is refused (`PRV-6211`). The credential is
verified again before every statement, so a revoked key ends an open connection (`PRV-6218`, PGREVOKE-1).

**Example.** With the gateway on and `big_txn` registered:

```bash
PGPASSWORD="$PRAVAHA_TOKEN" psql "host=127.0.0.1 port=5433 dbname=pravaha user=ann" \
     -c "SELECT user_id, amount FROM big_txn WHERE txn_id = 7"
```

The token is the password (an API key or a session token when the engine's own accounts are on); the
user name is informational, because the credential decides the principal. The statement reaches
`PgWireConnection`, which verifies the credential, then calls
`ViewQuery.execute(...)` — a whole-key probe on `txn_id` — and answers `RowDescription`, one `DataRow` and
`CommandComplete`. The connection's options and what each driver needs are
[`../../../console/content/topics/pgwire.md`](../../../console/content/topics/pgwire.md).
