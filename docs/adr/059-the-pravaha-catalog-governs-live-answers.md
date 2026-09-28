# ADR-059: the Pravaha Catalog governs live answers, not only stored data

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; phase 1 built — see *Phase 1, as built* below. Phases 2 to 4 (policies, lineage and labels, contracts, sharing, history, search beyond names/descriptions/tags) are not built |
| Date | 2026-09-28 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-025 (security predicates in the fingerprint), ADR-031 (authorization at the Pravaha layer — **superseded in part**: grants move into the engine), ADR-050 (tenancy), ADR-052 (the engine is the identity authority), ADR-056 (queries on queries), ADR-057 (alerts), ADR-058 (plain English) |

## Context

Pravaha has the pieces of governance and not the whole. It authenticates people and keys itself
(ADR-052), isolates tenants (ADR-050), audits every decision with a hash-chained trail, injects row
filters soundly into plans (ADR-031) and fingerprints a computation together with its security
predicates (ADR-025). But **grants live outside it**: `SecurityPolicy` is a Java interface a
deployment implements, the server's shipped policies are configuration, SECURITY.md says "there is
no endpoint to change a grant", row filters can only be supplied programmatically, column masking is
not built, objects have no owner, description or tags, and nothing records what feeds what.

The owner's goal is a governed catalogue of streams, continuous queries and their answers — Unity
Catalog's role, for streaming — and to do better than Unity where streaming allows it. Unity governs
data **at rest**: a table has a location, and a grant decides who may scan it. A Pravaha view is an
answer **still being computed**: it has a freshness, a delivery guarantee, subscribers reading it
this second, and upstreams that are themselves live. Governance that ignores those is governance of
the wrong thing.

## Decision

**The engine keeps a catalogue — every governed object, its owner, its metadata, its grants and
policies, and the live graph between them — journalled like registrations, enforced on every read,
subscribe and registration, and answerable over SQL, REST, the CLI and the console.** It lives in the
`pravaha-catalog` module (today an empty placeholder) and is consulted by a new built-in
`SecurityPolicy`, `CatalogPolicy`, so every existing enforcement point keeps working unchanged.

### 1. Objects and names

Three-part names: **`tenant.namespace.object`**. A tenant is ADR-050's; a namespace groups objects
for ownership and grants (`sales`, `risk`, `ops`); unqualified names resolve against the session's
default namespace, so every statement written today keeps working in `<tenant>.default`.

| Kind | What it is | Created by |
|---|---|---|
| `NAMESPACE` | A folder for ownership and inherited grants | `CREATE NAMESPACE` |
| `STREAM` | A declared input, with its schema contract (§6) | stream declaration |
| `SOURCE` / `SINK` binding | A configured connection (`pravaha.sources.*`, `pravaha.sinks.*`) | configuration, registered into the catalogue at start |
| `QUERY` / `VIEW` | A registered continuous query and the answer it keeps (one object, two facets) | `CREATE CONTINUOUS QUERY` |
| `ALERT` | ADR-057's alert | `CREATE ALERT` |
| `LOOKUP` | A dimension a query asks (jdbc-lookup, aerospike-lookup) | configuration |
| `POLICY` | A row filter or a column mask (§4) | `CREATE ROW FILTER`, `CREATE MASK` |

Every object carries: **owner** (a user or a role; the creator by default), **description**,
**tags** (`key` or `key=value`, e.g. `pii`, `domain=payments`, `tier=gold`), `created_at/by`,
`updated_at/by`, and a **version** that increments on every change to its definition.

### 2. Privileges, and how they combine

| Privilege | On | Means |
|---|---|---|
| `USE` | namespace | may resolve names in it (necessary, not sufficient) |
| `SELECT` | view, stream | may read the answer by point read or scan |
| `SUBSCRIBE` | view | may receive its changes live (separate from `SELECT`: a dashboard may read without holding a stream open, and a feed consumer may subscribe to a view it may not scan) |
| `BUILD_ON` | view, stream | may register a query that reads it (ADR-056's queries on queries); the new query's owner needs this on every input |
| `CREATE` | namespace | may register queries, alerts and policies in it |
| `WRITE` | sink | may name it in `WRITING TO` / `WITH (sink=...)` (today's `mayWriteTo`) |
| `MODIFY` | query, alert | pause, resume, replace (blue/green), throttle a backfill |
| `MANAGE` | any | change its grants, policies, tags; rebalance its lane; drop it |
| `OWN` | any | all of the above; transferable, exactly one owner |

Rules, deliberately simple:

- **Grants are to roles, users or groups of roles**, and are **allow-only**; there is no deny grant to
  reason about. Access is the union of what a principal's roles grant — except that **policies
  narrow** (§4): a row filter or mask always applies, whatever grant let you in.
- **Inheritance downward**: a grant on a namespace applies to every object in it, now and later
  (`GRANT SELECT ON NAMESPACE sales TO ROLE analyst`).
- **A view does not launder its inputs.** Reading a view needs `SELECT` on the view, not on its
  sources — that is the point of a view — but *registering* a query needs `BUILD_ON` on every input,
  and a row filter or mask on an input is **carried into** every query built on it (the plan
  injection ADR-031 already does), so no one can see through a view what they could not see in its
  source. The fingerprint already includes security predicates (ADR-025), so two people with
  different filters never share one computation by accident.
- **Tenants are hard walls** (ADR-050). The one crossing is an explicit **share** (§7).

### 3. Statements and endpoints

```sql
CREATE NAMESPACE sales COMMENT 'Order-to-cash';
GRANT USE, CREATE ON NAMESPACE sales TO ROLE sales_eng;
GRANT SELECT, SUBSCRIBE ON VIEW sales.hourly_revenue TO ROLE analyst;
REVOKE SUBSCRIBE ON VIEW sales.hourly_revenue FROM ROLE analyst;
ALTER VIEW sales.hourly_revenue SET TAGS ('domain' = 'finance', 'certified');
COMMENT ON VIEW sales.hourly_revenue IS 'Revenue per region per closed hour, corrected for late lines';
ALTER VIEW sales.hourly_revenue OWNER TO ROLE finance_data;
SHOW GRANTS ON VIEW sales.hourly_revenue;
SHOW GRANTS TO ROLE analyst;
SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW sales.hourly_revenue;   -- which grant, which filter, which mask
```

The same over REST (`/api/v1/catalog/...`), the `pravaha` CLI (`pravaha grant`, `pravaha catalog show`,
`pravaha access why ana sales.hourly_revenue`) and the console. Every change is a journal record and an
audit event; `SHOW` answers from the journal, so it is exact.

### 4. Policies: row filters and column masks, as objects

```sql
CREATE ROW FILTER sales.region_scope ON COLUMN region
  AS region = session_attribute('region')          -- the principal's claim, from ADR-052
  EXCEPT ROLE finance_admin;
CREATE MASK sales.card_last4 ON COLUMN card_number
  AS 'XXXX-XXXX-XXXX-' || RIGHT(card_number, 4)
  EXCEPT ROLE payments_ops;
ALTER STREAM sales.orders SET POLICY sales.region_scope;
ALTER TAG 'pii' SET POLICY sales.card_last4;          -- tag-based: every column tagged pii
```

- A row filter is a boolean expression over the object's columns and the principal's attributes; it
  is planned and **soundness-checked** by the rules ADR-031 already built (no function that could
  leak, no always-true filter — `SECURITY: an always-true row filter is refused` is in the tree).
- A mask is an expression that replaces a column's value in everything read, subscribed or built on;
  a masked column cannot be used as a join key, group key or index by someone it is masked for (they
  would learn the value's equality classes), which the planner refuses with a named code.
- **Tag-bound policies** apply to every column or object carrying the tag, now and later — the way
  governance keeps up with a growing catalogue without a grant per column.
- ADR-031's "row filters are programmatic-only" ends: the catalogue is where they are configured.

### 5. Live lineage, to the column, with the running state

ADR-056 makes the engine know which view feeds which query. The catalogue records, from each plan:

- **object lineage**: stream → query → view → downstream query → sink / alert;
- **column lineage**: for each output column, the input columns and the operators between (the plan
  already has this: projection, aggregate and join nodes name their inputs);
- **live state on the graph**: each node's state, rows in, lag, freshness (§6), lane, and **who is
  subscribed right now** (count per principal, never data).

What that buys, beyond a static lineage diagram:

- **Impact before change**: a replacement (ADR-046), a drop, a schema change on a stream or a grant
  revocation first shows "4 downstream queries, 2 alerts, 1 sink, 17 live subscribers are affected";
  a drop with live dependants is refused by name unless forced (ADR-056 decides the refusal).
- **"Where does this number come from?"** — from any column of any view, back to the streams and the
  source positions it was computed from (ties to the row-level explain idea and the debugger).
- **Propagating classification**: a column derived from a `pii` column is `pii` unless an owner
  declassifies it with a recorded reason — tags flow along column lineage, so a mask follows the data.

### 6. Guarantees and contracts as catalogue facts

Every view carries labels the **engine computes**, not ones a person types:

- **delivery**: exactly-once or at-least-once end to end, derived from its sources' and sinks'
  declared guarantees (the rules in OPERATIONS);
- **freshness**: the current and p99 lag between an input's event time and its reflection in the view;
  a **freshness objective** can be declared (`ALTER VIEW ... SET FRESHNESS OBJECTIVE '30s'`) and a
  breach is an ADR-057 alert;
- **retention** and **completeness** (a window closes when the watermark says so; late-data policy);
- **certified**: a tag only holders of a `certify` right may set, shown in search and the console.

A **stream contract** is its declared schema plus evolution rules (`add nullable column: allowed`,
`drop / retype: refused`). A source that delivers a violating record is refused by name at the edge
(dead-letter or stop, per the binding), and a contract change shows its lineage impact first (§5).

### 7. Sharing across tenants

A tenant is a wall (ADR-050). The one door: `CREATE SHARE finance_to_audit; ALTER SHARE ... ADD VIEW
sales.hourly_revenue; GRANT SHARE ... TO TENANT audit` — the recipient sees a read-only, live view in
its own catalogue (`SELECT`, `SUBSCRIBE`; never `BUILD_ON` unless granted), with the provider's row
filters and masks applied as the share's policy, revocable at once, audited on both sides. Unity's
Delta Sharing shares tables at rest; this shares a live answer.

### 8. Enforcement, including mid-stream

- `CatalogPolicy implements SecurityPolicy`: every existing check (`mayRead`, `mayRegisterQuery`,
  `mayAdminister`, `mayWriteTo`, audit read) asks the catalogue; ADR-031's enforcement points,
  plan injection and fingerprinting are reused, not rebuilt. A deployment may still plug in its own
  `SecurityPolicy` (embedded engines), which then bypasses the catalogue — stated at startup.
- **Revocation is immediate, including for open streams**: a revoked `SUBSCRIBE` ends the principal's
  subscriptions with `PRV-7002` at the next commit (the STRM-12 machinery already ends a stream when
  an entitlement goes); a changed row filter or mask ends and requires re-subscription rather than
  changing what an open stream means half-way.
- **Decisions are cached** per principal and object version and invalidated by the journal, so a read
  costs a map lookup, not a policy evaluation.

### 9. Time travel of access

Because grants, policies and object versions are journal records, the catalogue can answer **"who
could read `sales.hourly_revenue` on 14 September at 10:00, and through which grant?"** and **"what
did Ana's grants let her see last quarter?"** — joined with the hash-chained audit of what was actually
read. Auditors ask exactly these questions; most catalogues can answer only "who can read it now".

### 10. Discovery

`pravaha catalog search "revenue"`, the console's Catalog page, and `/api/v1/catalog/search` search
names, descriptions, tags, columns and owners, **showing only what the searcher may `USE`** — a search
never confirms that an object you may not see exists. Results carry the computed labels (§6), owner,
certification and freshness, so a reader chooses between two revenue views on evidence. The plain-
English assistant (ADR-058) builds its context from the same entitlement-filtered search.

## Where this goes beyond Unity Catalog

| | Unity Catalog (data at rest) | Pravaha Catalog (live answers) |
|---|---|---|
| Governed object | table, volume, model | stream, continuous query/view, alert, share — objects still computing |
| Lineage | recorded from jobs that ran | derived from the running plans, to the column, with each node's lag, state and live subscribers |
| Revocation | affects the next query | ends open subscriptions at the next commit |
| Guarantees | not a catalogue concern | delivery, freshness, completeness computed by the engine and shown on every object; freshness objectives alert |
| Classification | tags on columns | tags that propagate along column lineage, so masks follow derived data |
| History of access | audit of what was read | plus a replayable history of who *could* read what, when, and why |
| Sharing | tables at rest (Delta Sharing) | a live, filtered, revocable answer to another tenant |
| Impact of change | lineage to look at | a change is shown its live blast radius before it runs, and refused where it would break dependants |

Honesty about the other direction: Unity governs many engines, files, ML models and a lakehouse's
storage across a cloud estate; Pravaha's catalogue governs one engine on one node (multi-node is on
hold). Pravaha's sinks (Iceberg, Delta) write tables another catalogue may govern; the catalogue records
the sink and its lineage, and an export of that lineage in OpenLineage form is the bridge.

## Alternatives considered

| Alternative | What goes wrong |
|---|---|
| Keep grants in deployment configuration (ADR-031 as is) | No owner, no delegation, no audit of grant changes, no history, a restart to change access, and nowhere for lineage or tags to live |
| Delegate to an external catalogue (Unity, Polaris, OpenMetadata) as the authority | None governs a live view, a subscription or a freshness objective; every read would call out to it; the engine would enforce someone else's model. Export lineage to them instead |
| Deny grants alongside allows | Every access question becomes an ordering puzzle; narrowing is what row filters and masks are for |
| Column-level grants instead of masks | A grant hides a column entirely and breaks `SELECT *`; a mask keeps the shape and hides the value, and is policy rather than a grant per column |
| Enforce in the console | The console is one client of several (Flight, pgwire, SDKs, CLI); ADR-024 and ADR-052 put authority in the engine |

## Consequences

- One authority for who may do what, owned and delegated inside the product, changeable without a
  restart, with every change journalled and audited.
- ADR-031 is superseded where it says grants live outside the engine and row filters are
  programmatic-only; its enforcement machinery is kept and becomes the catalogue's.
- New work in the parser (the statements above), the journal, the planner (masks, mask-safety
  refusals), the console (catalogue, grants and lineage screens) and the CLI.
- A migration: a deployment's existing policy is imported once into catalogue grants, and the
  `pravaha.security` policy settings then refuse to start alongside a populated catalogue unless the
  deployment chooses which is authoritative — two authorities are the failure this ADR removes.

## Phases

1. **Objects and grants**: namespaces, owners, descriptions, tags; privileges and inheritance;
   `CatalogPolicy`; GRANT/REVOKE/SHOW; REST, CLI, console grants screen; migration from policy config.
2. **Policies**: row filters and masks as objects, tag-bound policies, mask-safety refusals, mid-stream
   revocation.
3. **Lineage and labels**: object and column lineage from plans, the live graph, impact before change,
   computed delivery/freshness labels, freshness objectives (with ADR-057), classification propagation.
4. **Contracts, sharing, history, search**: stream contracts, cross-tenant shares, access time travel,
   entitlement-filtered search, OpenLineage export.

## Phase 1, as built

**Where.** `pravaha-catalog`: `Catalog` (objects, grants, the import marker; journalled by
`CatalogJournal` — length-prefixed `ControlWire` records, owner-only, fsync'd per change, a torn tail
dropped, compacted atomically at open once 256 records are stale), `CatalogAccess` (decisions, cached
per principal/privilege/object and dropped whenever the catalogue's generation moves), `CatalogService`
(every rule, once, for SQL, REST, CLI and console; every change an `AuditEvent`),
`CatalogStatements`/`CatalogStatementExecutor` (the §3 statements, plus `ALTER VIEW … SET NAMESPACE`,
`CREATE NAMESPACE IF NOT EXISTS`, `ON CATALOG`, `ON TENANT` and `SHOW NAMESPACES`) and `CatalogPolicy`.
`pravaha-server`: `NodeCatalog`, `pravaha.catalog.*`, `/api/v1/catalog/...`. Codes `PRV-7030`–`7037`.

**Decisions phase 1 took that the design left open.**

- **Off by default** (`pravaha.catalog.enabled: false`). Turning it on changes who decides, every
  existing deployment, test and embedded engine was written against `pravaha.security.policy`, and an
  anonymous development node would otherwise be refused everything on upgrade. With it on,
  `pravaha.catalog.authority: import` (the default) makes switching safe: the configured policy is
  imported once as grants meaning what it meant (`permissive` → every privilege on `*` to `ROLE
  public`; `authenticated` → `USE`, `SELECT`, `SUBSCRIBE`, `BUILD_ON`, `CREATE`, `WRITE`, `MODIFY` on
  `*` to `ROLE authenticated`), recorded, and a later start whose policy setting differs — or a
  catalogue holding grants it never imported a policy for — refuses with `PRV-7034`.
  `authority: catalog` is the deployment choosing the catalogue alone.
- **Names.** Engine names stay single identifiers, unique on the node (ADR-050), and the catalogue
  records where each lives: a registration lands in `<tenant>.default` and moves with `ALTER VIEW …
  SET NAMESPACE`, taking its grants. The hierarchy is `*` → tenant → namespace → object. What the node
  is configured with (streams, sources, sinks, lookups) belongs to no tenant and is catalogued under
  the pseudo-tenant `node` (`node.streams.orders`), recorded when first named; a grant there reaches
  every tenant, as ADR-050 left reads and sinks unscoped.
- **USE.** Every privilege on an object also needs `USE` on its namespace, except that a principal
  may always use their own tenant's `default` namespace and the node's — so every name that resolved
  before the catalogue still does. `CREATE NAMESPACE` needs `CREATE` on the tenant.
- **Implicit roles** `public` (every caller) and `authenticated` (every verified caller) exist so a
  policy can be imported exactly. The **`admin` role** holds every right, and owns what the catalogue
  creates on its own account.
- **BUILD_ON and chains.** A registration asks `mayBuildOn` of each input its plan names; a source
  reached only through an upstream view is asked `mayBuildThrough`, which the catalogue allows (the
  view's owner held `BUILD_ON` on it). Reading a view is `SELECT` on the view alone: `mayReadThrough`
  replaces the SX-11 source check under the catalogue, and keeps it (defaulting to `mayRead`) under
  any other policy.
- **Administering** (`mayAdminister`: pause, resume, replace, drop) is `MODIFY` or `MANAGE`; the SPI
  has one question for all four, so phase 1 does not separate drop from pause.
- **Audit read** is the `admin` role, a `pravaha.security.audit-readers` role, or `MANAGE` on `*`.
- **Mid-stream revocation** of `SUBSCRIBE` came for free: Flight re-asks the policy every two seconds
  on an open subscription, and now asks `maySubscribe`.
- `SHOW` and the REST reads answer from the live, journalled state; a name the caller may not see is
  answered exactly as a missing one (`PRV-7031`).

**Not in phase 1**: row filters and masks as objects, tag-bound policies (phase 2); lineage and
computed labels (phase 3); contracts, shares, access history, search over columns, OpenLineage
(phase 4); the PostgreSQL gateway runs no catalogue statement (it is read-only and refuses them with
its read-only code); an embedded engine keeps its own `SecurityPolicy` and so no catalogue.
