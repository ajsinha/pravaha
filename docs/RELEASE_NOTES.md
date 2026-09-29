# Pravaha — release notes

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../LICENSE`](../LICENSE).

> **What this page is.** One entry per cut, written from what the tree proves rather than from what
> was planned. Every number here comes from a command named beside it, so a reader can run the
> command and get the number. Where a target was not reached, the number that was measured is here
> instead of the target.

---

## Unreleased

- **Waiting for a dropped query's rows no longer waits out the timeout (LIFE-067).** A row a
  producer handed over as the query was dropped -- after its lane had drained and stopped -- was
  never applied, and `awaitApplied` (the embedded engine's and the tests' way to wait for a push to
  land) waited for the inbox to empty until its timeout ran out. A stopped or failed lane now
  answers at once, and so do the lane group and the query's execution. This is the likely cause of
  the one-off gate failure, where a feeder pushing into a dropped query outlived the test's
  five-second wait inside a ten-second one; the mechanism is reproduced deterministically at the
  lane (`LaneTest`), and `LifeDropTest` now repeats the race forty times and prints the feeder's
  stack if it ever hangs.
- **A Cassandra token-range reader stopped inside a wide partition reads the rest of it (CASS-1).**
  The reader resumed its pass -- after a restart, or after a page failed -- with `token(pk) > <last
  token>`, so a stop part way through a partition's clustering rows skipped the rest of that
  partition until the next full pass. It resumes with `>=`, re-reading that partition from its first
  row (at `+1`, as every pass re-reads every row; `deletes: detect` restarts its pass and was not
  affected). `TokenRangeScanReaderTest`.
- **The PostgreSQL gateway's password with engine accounts on, verified and said (PGWIREPASS-1).**
  With the engine's own accounts on (ADR-052) the gateway accepts an API key or a session token as
  the password -- verified through the same transport verifier as Flight -- and refuses the account's
  own password, a revoked key and a session that must change its password first, each with SQLSTATE
  `28P01`. Nothing needed fixing; `PgWireSignInTest` now signs in with a real PostgreSQL driver both
  ways, and the pgwire, clients and power-bi topics say which credential the password is.
- **`/validate` judges a whole `CREATE CONTINUOUS QUERY` statement (VALIDATEREG-1).** It planned only
  a `SELECT`, so a key or index naming a column the view would not have (`PRV-2071`, `PRV-2074`), a
  sink the caller may not see or whose shape, key or changelog does not fit, a taken name or an
  unknown `WITH` option passed it and was refused only on register. Given the statement, it runs
  registration's own reading and preparation without registering -- nothing is started, no sink is
  opened, no name is taken -- and answers every refusal as a diagnostic, with the view's columns as
  `outputFields`. The Python SDK's `validate`, `pravaha validate` and the assistant's drafting use it;
  the assistant falls back to its own checks against an older engine. A plain `SELECT` is validated
  as before. `ValidateRegistrationTest`, `test_assist_drafting`.
- **Which access path a view's reads took is visible (IDXVIS-1).** The view counted reads by the
  whole key, by a `RANGE` run, by an `INDEX (column)` probe and by scan, and nothing a user could
  reach read the counts. `GET /api/v1/queries/{name}` now carries `accessPaths` (`point`, `range`,
  `index`, `scan`, and each index's entries), the console's query page shows them under "Reads of
  its view", and `pravaha_query_view_reads_total{query,path}` counts them. Chosen over a field on
  every read response, which would have changed three wire formats for a diagnostic.
  `AccessPathsVisibleTest`, `PravahaMetricsTest`.
- **Dropping one name of a shared computation drops the index only it declared (IDXSHR-1).** The
  view kept an equality index a dropped name had declared until the next restart -- memory, never
  a wrong answer. Each name's `INDEX (column)` is now counted against the names still answered by
  the computation; a replacement carries the indexes its own name declared.
  `SecondaryIndexRegistryTest`.
- **A view's dependants include its alerts (ALERTDEPS-1).** `dependants` in `GET
  /api/v1/queries/{name}`, `QueryRegistry.dependantsOf` and the console's query page listed only the
  queries over a view, although a drop or replace of it was already refused naming `ALERT x`. The
  alerts now follow the queries, as `ALERT <name>`, each only where the caller may see that alert;
  the console links them to the alert's page.
- **An alert may not be called `channels` (ALERTPATH-1).** `/api/v1/alerts/channels` is the channel
  list, so such an alert could not be reached, paused, snoozed or acknowledged by its own path.
  `CREATE ALERT channels ...` is refused `PRV-8042`, naming the path. The channel list stays where it
  is, so no caller changes.
- **Answer-following subscriptions over the wire (SUBANSWERWIRE-1).** A subscription that follows
  the view's answer — per commit, the rows a reader stopped seeing at `-1` and started seeing at
  `+1`, so its weights sum to the view even for a keyed view that upserts — was reachable only
  embedded (`SubscriptionOptions.followingTheAnswer()`). The Flight ticket now carries it, as two
  new verbs (`subscribe.answer`, `subscribe.answer.snapshot`) an older server refuses rather than
  misreads: `client.subscribe(view, changes="answer")` in Python, `subscribeToAnswer` and
  `subscribeToAnswerFromSnapshot` in the Java SDK, `pravaha subscribe --answer`.
  `JavaSdkAnswerSubscriptionTest`, SDK and CLI tests.
- **A `DECIMAL` group key and `COUNT(DISTINCT decimal)` work without a window too (DECKEYGROUP-1).**
  A read of a view grouping by a decimal column or counting its distinct values, and a continuous
  query over a view grouping by one, were refused `PRV-3020` (the continuous query at registration,
  `PRV-2075`), where windowed aggregates had handled both since WINDECKEY-1. The grouped and
  unwindowed aggregates now carry the whole unscaled value as their key and distinct value, through
  checkpoints (a new distinct-value tag; a checkpoint without one is unchanged).
  `DecimalGroupKeyReadTest`, `DecimalGroupKeyChainTest`.
- **A window past its lateness is never fired again, and a fired window holds nothing it cannot
  need (EMIT-1).** What a fired window published is kept, per group, only while the window can
  still be corrected, and let go on every watermark advance once its lateness passes — it used to
  go only when an advance happened to release a slice, so a hopping window closed with no lateness
  could be fired again as a "correction" by a row on time for the next window it overlaps. With no
  allowed lateness, the default, nothing is kept per group at all, not even while the window
  fires. A late row within lateness now corrects a window that fired empty too. `LateDataTest`.
- **A correction is published at the next commit (EMIT-2).** A late row within allowed lateness is
  applied at once; its retraction and corrected row used to wait for the next watermark advance,
  which on a quiet stream moves only with later rows. They are now published with the query's
  next commit. `CONTINUOUS_QUERIES.md` §6 says so, and what lateness costs in heap.
- **An upsert sink holds the row the view shows (SINKKEYROWS-1).** A keyed view keeps every
  distinct row of a key and shows the newest; retracting that row shows the one behind it again. A
  keyed sink in upsert mode (`jdbc-sink`, `kafka-sink`, `delta-sink`, `iceberg-sink`,
  `aerospike-sink`) was handed the changelog, whose only change for that commit was the retraction,
  so it deleted the key's record while the view still showed a row. Such a sink is now handed how
  the answer changed at each commit — the row that left the key, then the row that entered it — as
  an answer-following subscription is (KEYEDWT-1), so its last word on every key is the view's. A
  sink in changelog mode still receives the changelog verbatim, a row retention ages out is still
  never deleted from a sink, and the exactly-once protocol is unchanged (the answer is delivered
  inside the same commit, before a checkpoint's cut). Reproduced against `jdbc-sink` (H2) and
  `kafka-sink` (Kafka's `MockProducer`): `UpsertSinkKeyRowsTest`, `JdbcSinkRegistrationTest`,
  `KafkaUpsertKeyRowsTest`.
- **A total past 2^63 stops by name instead of wrapping (SUMWRAP-1).** Every `SUM`, `COUNT` and
  `AVG` accumulator — unwindowed, grouped, windowed (per slice and when a window's slices are
  combined), a pushed-down partial, and the read path — adds with checked arithmetic, and a
  retraction is checked the same way. A `BIGINT` total, or a `DECIMAL` total's unscaled value, that
  leaves the 64-bit range is refused with the new **`PRV-3025` RUNTIME_AGGREGATE_OVERFLOW**, naming
  the aggregate (`SUM(amount)`): a continuous query moves to `FAILED` and its view refuses reads, as
  for every runtime refusal, and a read is refused. It used to publish `-9223372036854775808`.
- **A windowed `GROUP BY` on a `DECIMAL` column, and `COUNT(DISTINCT)` of one, keep values apart
  (WINDECKEY-1).** The key was read as the high half of the 128-bit value — zero for every value of
  eighteen digits or fewer — so the `GROUP BY` failed with an uncoded "is DECIMAL, not INT64" and
  `COUNT(DISTINCT price)` counted `1.50` and `2.75` as one. Both now use the whole unscaled value,
  through the off-heap state and checkpoints (a new key tag; a checkpoint without decimal keys is
  unchanged). The unwindowed and grouped aggregates followed in DECKEYGROUP-1.
- **A restore that fails half-way leaves nothing behind (RESTOREPART-1).** A checkpoint is restored
  lane by lane, operator by operator, then the view; when a later part was refused, the earlier parts
  stayed restored while the query started from the beginning of its sources — counting every row
  before the checkpoint twice in the parts that came back. The restore is now all or nothing: each
  part's state is taken first and put back on a refusal, a refusal on a lane no longer kills the lane,
  a join's restore replaces its rows rather than adding to them, and the registry's own half (the
  sinks a checkpoint recorded) undoes the execution's if it fails. Starting from the sources is still
  what a refused checkpoint means — the documented "reprocessing, never a double count" — and it is
  no longer silent: the node logs a `WARN` naming the query, the checkpoint and the cause, and the
  query counts it as a checkpoint failure (`pravaha_query_checkpoint_failures_total`, the console's
  "Checkpoints are failing"). If the undo itself cannot complete, the registration is refused, and a
  recovery lists it among the refused, rather than running from state that is neither.
- **Summing a keyed view's subscription weights, corrected (KEYEDWT-1).** CONCEPTS §4 told a
  consumer keeping its own copy to sum a subscription's weights; for a view that keeps the latest row
  per key, an upsert arrives as `+1` with no `-1` for the row it replaced, so the copy counted the key
  twice. The advice now says so and gives the exact way: subscribe to a continuous query registered
  over the view, which is fed the view's answer changing (rows leaving at `-1`, entering at `+1`) and
  whose weights sum to the view. In the embedded engine, `SubscriptionOptions.followingTheAnswer()`
  hands a subscription the answer's changes directly (and a snapshot of the rows the view shows). The
  plain subscription is unchanged: its weights passing through verbatim is a documented contract.

- **About and the competitive landscape follow MAYA's.** `/about` takes MAYA's About section for
  section: the hero (mark, name, tagline, creed, version), what it is, the problem (asking again
  against a maintained answer, with problem-and-fix pairs), twelve cards of what makes it different
  (exact cuts, exact seams, lossless cutover, governed live answers, alerts that clear, queries on
  queries, refusal, any model with the engine as judge, …), how it works, what is built by area, this
  release beside the honest limits, the measured numbers with their sources, a competitive summary
  (the table, where Pravaha shines and where it is behind, and the way to the full comparison), the
  principles, the research paper, deck and Medium post, the technology and the author.
  `docs/COMPETITIVE_LANDSCAPE.md` is rewritten in MAYA's form: a landscape naming the families and
  well-known examples of each, a table of 28 capabilities against six categories — a new
  **Governance catalogues** column among them — and a note per row with *the problem elsewhere* and a
  list of *how Pravaha does it*. New rows: the governed catalogue of live answers, alerts that fire and
  clear, BI tools over the PostgreSQL protocol with security applied, plain English with the engine as
  judge, lanes, native CDC, observability, queries on queries (Partial), Delta and Iceberg sinks
  (Partial), and the honest ones — governing many engines, MFA and SSO, a managed service. The paper
  and the deck are served at `/about/papers/` from a fixed list when the installation carries them.

- **The console follows MAYA's design language.** Tokens and themes (`static/css/tokens.css`,
  `theme.css`), the navigation bar, the menu, the theme menu, the banners, the flashes and the footer
  are MAYA's files with only names, routes and content changed. **Themes**: MAYA's four — Crimson,
  Dark, Blue, Green — picked from a theme menu with swatches (`pravaha.theme` in `localStorage`, as
  `data-theme` and `data-bs-theme`); the terminal theme is gone and a stored `terminal` falls back to
  the system's light or dark. **Navigation**: a fixed top bar with the mark and three brand lines
  (Pravaha, *Continuous SQL where your data already lives*, *Ask once. Answer always.*), and one menu
  defined once as data and drawn as mega-menu panels — Catalog, Workbench, Operate (alerts is an
  item here, not a new tab), Admin (administrators only) and Help; the search (the command palette),
  alerts, the theme menu and the user menu on the right; collapsed behind one button on a phone.
  Every screen is in the menu or listed with the reason it is not (`core/navigation.EXCLUDED`), and a
  test holds it. **Signed out**, every page has MAYA's public bar (Help, About, theme, Sign in) and
  never the app's menu; signing out lands on the landing page with that bar. The sign-in and reset
  pages stand on the gradient with no bar. **Banners** under the bar: the bootstrap `admin` still on
  its published password, the engine not answering, and where this is (engine, `app.environment`,
  version). **Footer**: *Ask once. Answer always.* Pravaha 0.2.1 · Help · About · © 2026 Ashutosh
  Sinha. All rights reserved. Density moved into the user menu (*Compact rows*, still `d`). The
  landing page takes MAYA's layout and keeps its three figures. Contrast departures from MAYA's
  values (light slate, the blue accent as text, the dark accent, `bad`) are noted in `tokens.css`
  and held by `test_contrast.py`; every page passes axe in all four themes and both densities, and
  the visual baselines were retaken for the four themes.

- **A row filter that restricts nothing is refused, not only one the planner folds to `TRUE`
  (TAUTOFILTER-1).** `region = region`, `1 = 1 OR region = 'x'`, `x IS NULL OR x IS NOT NULL`,
  `NOT (a <> a)`, `a >= a` and `lower(r) = lower(r)` used to be enforced as though they restricted
  something. The compiled predicate is now decided as a formula (constants folded, an expression
  compared with itself given its value, `NOT NULL` columns read as never null): a filter true for
  every row, or one dropping only rows with a NULL in a compared column without saying `IS NOT NULL`,
  is refused with `PRV-7003` where it is applied, and with `PRV-7038` when a session-free catalogue
  policy is bound; a session-free policy false for every row is refused at binding too. A filter
  bound to a reader that keeps no row is enforced. Sound, not complete: a property test holds it to
  never refusing a filter that restricts by value. **Behaviour change**: a policy that was accepted
  and restricts nothing now refuses its readers, naming the filter; exempt them with `EXCEPT ROLE`
  or write the comparison that was meant.
- **Users in the identity store carry attributes, presented as claims (STORECLAIMS-1).**
  `PUT /api/v1/users/{u}/attributes`, `pravaha user attrs <u> key=value ... [--unset key]` and an
  Attributes column in Admin · Users set them; every session and API key of the user's (a key exactly
  its holder's) carries them as claims, so a policy reading `session_attribute('region')` applies to
  store users instead of refusing them with `PRV-7039`. Journalled with the user, audited as
  `user.attributes_changed` (names only). A registration by a store user is now restored at restart
  as that user — recovery used to ask only the static token table, so it was refused.
- **Writes document the status they answer (CAT201-1).** The catalogue's `POST`s (namespaces,
  grants, policies, bindings) answer 201 and every endpoint answering 204 (sign-out, password change
  and reset, key revocation, session end, revoking a grant, dropping a policy) now says so in the
  OpenAPI document and `api/openapi.lock.json`, which said 200. The contract test derives each
  handler's status and holds the document to it for every operation, and calls the catalogue's
  writes for real. A generated client that treated the documented status as the only success now
  sees the one the engine sends.

- **The Pravaha Catalog, phase 2: row filters and column masks as catalogue objects (ADR-059 §4).**
  `CREATE ROW FILTER p AS <predicate> [EXCEPT ROLE r, ...]` and `CREATE MASK p ON COLUMN c AS
  <expression> [EXCEPT ROLE ...]` define a policy (kind `POLICY`: owner, description, tags, version,
  journalled); `ALTER STREAM|VIEW o SET|UNSET POLICY p` binds it to one object and `ALTER TAG
  'k[=v]' SET|UNSET POLICY p` to every object of the tenant carrying the tag, now and later; `DROP ROW
  FILTER|MASK` (refused while bound) and `SHOW POLICIES [ON o]`. Expressions read the object's
  columns and `session_attribute('claim')`, `current_user()`, `is_member('role')`; subqueries,
  non-deterministic and unlisted functions are refused. Filters AND together; a reader holding an
  `EXCEPT ROLE` is exempt. Enforced on Flight reads and point reads, pgwire (text and binary),
  subscriptions (snapshot and every commit), registrations (the input's filter and masks go into the
  plan, into the fingerprint, and on into every query built on the view) and alerts (as their owner).
  A masked column used as a filter operand, group, join or sort key, aggregate argument, view key or
  tap filter is refused (`PRV-7006`); a changed policy ends affected subscriptions (`PRV-7007`). `SHOW
  EFFECTIVE ACCESS` lists the filters and masks that apply and why. `/api/v1/catalog/policies`,
  `pravaha policy ls|show|create-filter|create-mask|bind|unbind|drop`, policies on the console's object
  page and an Admin → Policies editor. Static tokens gain `claims`. New codes `PRV-7006`, `PRV-7007`,
  `PRV-7038` to `PRV-7040`. `SecurityPolicy` gains `narrowing`, defaulting to none.
- **`SUM`, `MIN` and `MAX` of a `DECIMAL` column answer exactly (DECSUM-1).** Power BI's
  `select sum("_"."avg_ticket") from "public"."rr" "_"` failed "field 0 ('a0') is DECIMAL, not INT64"
  with no code: the aggregates read the 16-byte decimal slot as a `long` and wrote their answer as
  one. They now accumulate the unscaled value and write it back at the column's scale — on a read, in
  a continuous query and in a window — and `SUM` of a `DECIMAL(p, s)` is a `DECIMAL(38, s)`. A value
  whose unscaled form has more than 18 digits is refused `PRV-3020`, naming the column, rather than
  cut. **`AVG` of a decimal is refused `PRV-2021`**: a quotient at the column's scale would round. The
  float refusal (`PRV-2020`) is unchanged; it is a documented rule, not this defect.
- **A second, different query over a `postgres-cdc` or `mysql-cdc` binding is refused at
  registration, `PRV-8028` (new, `REGISTRY_SOURCE_HELD`, HTTP 409), naming the query holding it
  (CDCREPL-2).** A binding names one replication slot (or replica `server.id`), which has one consumer;
  the second registration used to be accepted and fail `PRV-5117` about fifteen seconds later. Bind the
  table again with a slot of its own for a second query. The engine does not create a slot per query:
  every slot retains WAL, and one the engine lost track of would fill the database's disk.
- **Queries owned by identity-store users survive a restart (RECOVERYOWNER-1).** Recovery resolved
  owners through the static token table only, so on a node with `pravaha.identity.enabled` every query
  a signed-in user had registered was refused `PRV-8007` at the next start. Owners are resolved through
  the identity store first, then the token table, each with today's tenant and roles. A disabled user's
  queries keep running, with a warning naming the owner at each start.
- **`/api/v1/queries/explain` answers the fingerprint a registration would get (EXPLAINFP-1).** Given
  `keys` (and optionally `retention`, `sink`, `name`) in the body, it answers `fingerprint` — computed
  by the registration's own preparation for the caller: plan, row filters, keys, retention, tenant — or
  `fingerprintRefusal` with the code registration would give. The Python SDK's `EngineApi.explain`
  takes them; the assistant's reuse offer (`match: "fingerprint"`) and `pravaha assist eval` compare
  the engine's fingerprints, falling back to plan text against an older engine.
- **The assistant in the console, phase 3 (ADR-058): Admin · AI models, "Describe it", and
  Explain.** An administrator configures several providers and models at once in **Admin · AI
  models** and **switches between them while the console runs**: providers (configured, and every
  type the console can build, with its capabilities), models (the key shown only by the name of the
  variable or file that holds it, whether it is set in the console's process, enabled or disabled,
  and a **Test** button giving the latency or the normalised error), each profile's chain in fallback
  order (move up, down, out, add, set by typing), the default profile, budgets, usage per model and
  per person, and the recent changes with who made each and what it was before and after. Every
  change is a CSRF-protected form through the SDK's `AssistAdmin`, validated completely, saved
  against the version the page was drawn from — a concurrent edit is refused as a conflict, naming
  who changed it and when — and applied to the console's one `ModelRouter` before the answer, so the
  **very next request uses it** with no restart; a change `pravaha assist use` stores is followed
  within a second. The screen is for a person the engine gives the `admin` role, asked of the engine
  on every request. On the workbench, **Describe it** drafts a query as the signed-in person — the
  statement, explanation, assumptions, the engine's verdict and plan, every repair turn, the model's
  questions answered in place and drafted again, the reuse offer, *Copy to editor* — and **Register**
  is disabled until the engine accepts the draft and then needs the person's confirmation; the draft
  is held by the console, so the browser sends its id, never SQL. **Explain this query** on a query's
  page and **Explain** beside every refusal code (the workbench's refusal and diagnostics, a query's
  sink, feed and failure codes, a refused draft). Budgets apply; with no model configured or none
  enabled, each surface shows one empty state (an administrator is linked to Admin · AI models).
  Every change and every request is appended to the console's assist log (`assist.log`): who, the
  model, tokens, a hash of what was asked, the verdict, whether it was registered. Every surface works
  with scripting off. New settings `assist.config`, `assist.usage`, `assist.log`,
  `assist.watch_seconds`. 23 new console tests with the SDK's `fake` provider; the help topics *The
  assistant* and *Admin · AI models*; [`ASSIST.md`](ASSIST.md) § In the console.
- **The assistant, phase 2 (ADR-058): `pravaha ask` drafts a continuous query from a description,
  the engine judges it, and only you register it.** The context is built from the engine under
  your own credentials — the streams and views you may read (a stream you may not read is never
  named), the sinks you may write to with their guarantees, the guide's rules from the dialect card,
  and two to four worked examples from the case studies chosen by similarity — ordered
  deterministically, held to a size budget that follows the per-request token budget, with what was
  left out named. No row is read. The model answers a fixed schema (`draft_query@v1`: name, SQL,
  key, options, explanation, assumptions, questions, confidence); questions come back without
  asking the engine; otherwise the engine validates and explains the draft, and a refusal gets up to
  three repair turns with the PRV code, the engine's sentence and the guide's section — none of
  which may change what the query reads ("a different question" is refused by the assistant and
  never sent). A draft still refused is shown in the engine's words, exit `1`. The result carries
  the `CREATE CONTINUOUS QUERY` statement, the engine's plan, the sink's guarantee, every turn, the
  model and the tokens, and offers reuse when a running query has the same plan, key and retention.
  `--register` registers an accepted draft only after you confirm (or `--yes`), through the
  ordinary client call. **Approximated, and said so:** the HTTP API gives no fingerprint for
  unregistered SQL, so "the same computation" compares the engine's plan text, key and retention.
  **`pravaha assist eval`** scores a model on a golden set generated from the case studies — 27
  reference cases (accepted, the same streams, the same plan and key) and three that must be refused
  or asked about (an unbounded `GROUP BY`, a stream–stream join with no time bound, a stream that
  does not exist) — with repair turns, tokens and latency per case, a table or `--json`; `--run`
  registers draft and reference under a prefix on a test node, compares fingerprints and answers,
  and drops both. Examples and golden set are generated by `sdk/python/tools/build_examples.py`,
  with a staleness test. 35 new tests against a stand-in engine on `127.0.0.1` and scripted models.
  [`ASSIST.md`](ASSIST.md); the console's *The assistant* help page.
- **Alerts (ADR-057): told when a row enters a view, and when it leaves.** `CREATE ALERT name ON
  view [WHERE column op literal AND ...] NOTIFY channel [, ...] [WITH (severity, fire_after,
  clear_after, dedupe, resend_every, include, snooze)]`, `ALTER`, `DROP`, `PAUSE`, `RESUME`, `SNOOZE
  … FOR`, `ACK ALERT` and `SHOW ALERTS`, wherever `CREATE CONTINUOUS QUERY` runs. An alert follows its
  view's answer (ADR-056): a key fires when its row enters — an insert, or an update across the
  threshold — and clears when it leaves — a delete, or the update back — so a clear is a retraction the
  alert was handed, not a guess. What is true and what is said are kept apart: `fire_after` and
  `clear_after` decide the first; `dedupe`, pause, snooze and reminders (until `ACK`) only hold the
  second back, and the receivers are told the difference when they allow — a flap folds into its end
  state and a clear is never lost. **Exactly-once state, at-least-once delivery**: every decision is
  journalled (`alerts.journal`) and forced before anything is sent, so a restart neither re-fires a
  firing key nor forgets a clear it owed, and every attempt carries the same `Idempotency-Key`.
  Notifier channels are plugins (`NotifierPlugin`) bound under `pravaha.notifiers.<name>`: `webhook`
  (JSON, HMAC-SHA256-signed over `<timestamp>.<body>`, retries with backoff and timeouts, `format:
  slack`; the secret only by `secret-env` / `secret-file`) and `log`; a channel the node cannot open
  refuses the start. An alert is a catalogue object (`ALERT`: `SELECT`, `MODIFY`, `MANAGE`), a `NOTIFY`
  needs `WRITE` on the channel (new kind `NOTIFIER`), and a view an alert follows cannot be dropped
  (`PRV-8024`). `/api/v1/alerts` (list, detail with per-key state and recent notifications, channels,
  pause/resume/snooze/ack), `pravaha alerts ls|show|channels|pause|resume|snooze|ack`, `pravaha alert
  create|drop`, and the console's Alerts screens (from the command palette, under Operations). The
  retail case study's low-stock alert runs end to end on a real node with a signed webhook and a
  restart (`RetailLowStockAlertEndToEndTest`). New codes `PRV-8040` to `PRV-8047`. Not built: `email`,
  `teams` and `pagerduty` channels (designed in ADR-057), freshness-objective alerts.
- **Power BI reads views through the PostgreSQL gateway, in Import and DirectQuery.** Power BI's
  PostgreSQL connector runs Npgsql 4.0.17, and until now it could not open a connection: Npgsql's
  type-loading query was refused (PRV-6205), and so, after it, would have been every query — Npgsql
  asks for binary results, which the gateway refused (PRV-6209). The gateway now answers Npgsql's
  three type-loading queries, `GetSchema("Tables"/"Columns")` and Power BI's navigator queries
  (`INFORMATION_SCHEMA` tables, columns, character sets, keys) from the view catalogue, filtered by
  what the principal may read; sends PostgreSQL's binary format for every type it sends (a binary
  `timestamptz` carries microseconds, so sub-microsecond digits are truncated); accepts `DISCARD ALL`;
  reads `public.<view>` as `<view>`; and takes a trailing top-level `LIMIT n` — Power BI's
  `LIMIT 1000001` on every DirectQuery statement — off before planning and applies it to the answer.
  `ORDER BY`, joins, float aggregates and date functions are still refused by the planner, each by
  name. `NpgsqlClientTest` drives the gateway with the real Npgsql 4.0.17 (skipped without a dotnet
  SDK); `PowerBiGatewayTest` replays the same texts everywhere. Power BI Desktop itself was not run.
  New help topic: [Power BI](../console/content/topics/power-bi.md), including a Microsoft Fabric
  real-time path through `kafka-sink` that is **not verified against Azure**.
- **The assistant, phase 1 (ADR-058): `pravaha explain-sql` and `pravaha why`, through any model,
  with the engine as the judge.** `pravaha.assist` in the Python SDK, standard library only: a
  provider protocol with `anthropic` (Messages API), `openai` and `openai-compatible` (Chat
  Completions — vLLM, LM Studio, llama.cpp, gateways), `ollama` and `fake`, each normalising its
  failures into four errors, and third-party providers by entry point. A router with fallback chains
  (a refusal does not fall through), per-request and per-user daily token budgets, and runtime
  reconfiguration — validated immutable snapshots swapped atomically, a JSON file store other
  processes watch, and an `AssistAdmin` facade whose every change is an audit record — so an
  administrator can switch models while the console runs. `explain-sql` grounds the model in the
  engine's plan; `why` in the engine's diagnostics and a dialect card generated from
  [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md), and a rewrite it proposes is validated by the
  engine before it is shown. `pravaha assist models|providers|check|use|enable|disable`. Keys by
  environment variable or secret file only; no rows are ever sent. 135 tests, none touching a
  network beyond `127.0.0.1` (`cd sdk/python && .venv/bin/python -m pytest -q tests/test_assist_*.py`).
  [`ASSIST.md`](ASSIST.md); the console's *The assistant* help page.
- **The Pravaha Catalog, phase 1: grants live in the engine (ADR-059).** `pravaha.catalog.enabled`
  (off by default) makes the engine keep every governed object — namespaces (`tenant.namespace.object`;
  an unqualified name is in `<tenant>.default`), views, streams, sinks — with an owner, description,
  tags and version, and the grants on it: `USE`, `SELECT`, `SUBSCRIBE`, `BUILD_ON`, `CREATE`, `WRITE`,
  `MODIFY`, `MANAGE`, `OWN`; allow-only, to roles and users, inherited down; owners and the `admin`
  role hold everything; tenants are walls. Journalled beside the registry journal and replayed at
  start. A built-in `CatalogPolicy` answers every existing check, so nothing about where checks happen
  changed: subscribing asks `SUBSCRIBE` (and a revocation ends an open Flight subscription), a
  registration asks `BUILD_ON` on each input it names and makes the registrant the owner.
  `GRANT`, `REVOKE`, `CREATE NAMESPACE`, `COMMENT ON`, `ALTER … SET|UNSET TAGS | OWNER TO | SET
  NAMESPACE`, `SHOW GRANTS ON|TO`, `SHOW EFFECTIVE ACCESS FOR USER … ON …` and `SHOW NAMESPACES` run
  wherever `CREATE CONTINUOUS QUERY` does; `/api/v1/catalog/{objects,namespaces,grants,access}`;
  `pravaha catalog ls|search|show|…`, `pravaha grant|revoke|grants`, `pravaha access why`; the
  console's Catalog → Objects and grants tab, object pages and Admin → Grants. `authority: import`
  imports the configured policy once and refuses to start (`PRV-7034`) if it later disagrees. New
  codes `PRV-7030` to `PRV-7037`. `SecurityPolicy` gains `maySubscribe`, `mayBuildOn`,
  `mayBuildThrough`, `mayReadThrough`, a named `mayRegisterQuery` and `registered`/`dropped`, each
  defaulting to what it meant before.
- **A view keeps every row of a key, and shows the one that most recently gained weight (VIEWW-1).**
  A key inserted as `A` and then as `B`, with `A` then retracted, went on showing `A` — the row just
  withdrawn. `ServedView` now keeps each distinct row of a key with its own weight (only for a key
  holding more than one; a one-row key costs nothing more), a retraction takes weight from the row it
  names, a checkpoint and a subscription's snapshot carry each row with its weight, and a restore
  rebuilds them. `ViewZSetPropertyTest` checks latest and consistent reads, a checkpoint mid-schedule
  and the snapshot against a Z-set model over 2,000 schedules; it fails 5 of 5 on the old code.
  [`CONCEPTS.md`](CONCEPTS.md) §4 states the rule.
- **Replacing a query over `postgres-cdc` or `mysql-cdc` is refused, naming the slot or replica id
  (CDCREPL-1).** Against a real PostgreSQL, the replacement's backfill waited 15 s for the running
  version's slot and failed with `PRV-5117` ("replication slot … is active for PID"); a slot's
  "beginning" is its confirmed position, so there was no history to replay either. Now `PRV-4018`
  before anything opens, and a debug fork `PRV-8012`. A plugin says so through a new SPI default,
  `StreamSourcePlugin.secondReaderRefusal()`. `PostgresCdcReplacementTest`, `MySqlCdcSecondReaderTest`.
- **ADR-054's exact seam is tested against a real Kafka broker (SEAMKAFKA-1).** Transactional
  producers with aborted transactions put markers and gaps beside every position; a query joining
  behind the shared reader while records arrive, one restored ahead of it across a 20,000-record gap,
  one restored behind and one from nothing each count every committed record once and none aborted,
  in topic order (`KafkaExactSharingBrokerTest`). Nothing needed fixing; a reader that handed over the
  record on its bound loses exactly that record for the query waiting there, and the test catches it.
- **A dead shared lane is no longer placed on (LANEFATE-1).** A failing pipeline takes down exactly
  the queries on its shared lane — tested now, with the other shared lane and a lane of its own
  answering throughout — but a registration after the failure could land on the dead lane, report
  `RUNNING`, and fail on its first row with the other query's error (`PRV-3010`). `SharedLanes`
  skips a failed lane until the node restarts. A stalled query backpressures its own lane only, and
  loses nothing (`SharedLaneFateTest`).
- **Queries on queries (ADR-056).** A continuous query whose `FROM` names another registered query now
  *follows* that query's answer — its snapshot, then every change to it — instead of scanning its view
  once. Layer answers: `cleaned` → `by_region` → `big_regions`. The downstream is fed the answer's
  changes (a row leaving, a row entering), not the upstream's changelog, so an upsert upstream is an
  update downstream rather than a second row. Filters, projections and unwindowed `COUNT`/`SUM`/`AVG` —
  with a `GROUP BY` too, now continuous and checkpointed — run over a view; windows, joins, top-N,
  `MIN`/`MAX` and `COUNT(DISTINCT)` are refused `PRV-2075`. Exactly once across the chain: the
  downstream carries what it has consumed of the upstream's answer in its checkpoint and is fed the
  difference on restore, whichever of the two checkpointed later. `DROP` of a query others read is
  `PRV-8024`, naming them (no cascade); a replacement that would read its own answer is `PRV-8025`;
  replacing a member of a chain, `RETAIN FOR` over a view and a restore without the consumed answer are
  `PRV-8026`; a chain deeper than eight is `PRV-8027`. Reads are authorised against the upstream and
  every stream behind it, and only the caller's own tenant's views can be read. `GET
  /api/v1/queries/{name}` reports `readsFrom` and `dependants`, and the console's query page links them.
- **A research paper, and an article version of it.** `docs/research/continuous-queries-as-maintained-answers.pdf`
  (LaTeX source beside it) and `-article.md`: *Continuous Queries as Maintained Answers — Exact Cuts, Exact
  Seams and Lossless Cutover in a Single-Node Streaming SQL Engine*. It states the Z-set model and the
  operator laws, and proves, under assumptions it lists, that a snapshot subscription gets every later
  commit once, that a checkpoint is one cut (and a two-phase sink exactly once), that an ordered source's
  shared reader hands each query each record once (ADR-054), and that a replacement cuts over losslessly
  (ADR-046). Every claim names the test that carries it or says it is argued; the paper reports that
  `pravaha-algebra` is tested but imported by no running module, and quotes only measurements already in
  the repository, with their conditions. Licensed CC BY-NC-ND 4.0 (`docs/research/LICENSE`); the software
  stays proprietary. Built with `latexmk -pdf`.
- **The Java CLI is now `pravaha-engine`, and keeps only what needs the engine in-process:
  `validate`, `explain`, `run` and `version`.** `bin/pravaha` is renamed `bin/pravaha-engine`
  (`PRAVAHA_CLI_JAR` becomes `PRAVAHA_ENGINE_JAR`; the container image installs
  `lib/pravaha-engine.jar`). Every command that talked to a running engine is removed from it —
  `query`, `register`, `queries`, `drop`, `pause`, `resume`, `replace`, `cutover`, `rollback`,
  `abandon`, `finish`, `throttle`, `pause-backfill`, `resume-backfill`, `replacements`,
  `subscribe`, `dlq`, `login`, `password`, `user`, `key`, `session`, `lanes` and `debug` — and
  lives in the Python CLI, `pravaha`, with the same names and flags. Typing one of them into
  `pravaha-engine` prints where it went and exits 2. The jar no longer bundles the Java SDK.
  `deploy/docker/smoke.sh` lists queries with `GET /api/v1/queries` and registers and reads over
  Flight with the Python CLI on the host.
- **`pravaha` is a Python CLI on the Python SDK.** Everything that talks to a running engine is
  now `sdk/python/pravaha/cli` — the `pravaha` console script of `pip install "pravaha[flight]"`,
  `python -m pravaha.cli`, or `bin/pravaha` from a checkout — with no protocol code of its own:
  Flight commands call `Client`, HTTP ones the new `pravaha.api.EngineApi` (stdlib only, no
  pyarrow), which `Client`'s HTTP methods now delegate to and which adds lanes, rebalance, health
  and the identity endpoints; `RestClient` gains `put`/`patch`/`delete`. Every Java CLI command
  and flag still works; new are `status`, `health`, `version` (CLI and node), `metrics`, `plugins`,
  `sinks`, `streams`, `views`, `describe` (with the lane), `plan`, `validate`/`explain` against the
  node, `audit`, `tenants`, `permissions`, `whoami`, `logout`, `dlq count` and
  `subscribe --reconnect`. `--json` on every command; `--url`/`--http`/`--token` or
  `PRAVAHA_URL`/`PRAVAHA_HTTP`/`PRAVAHA_TOKEN`, then `login --save`'s `0600` token file; exit `0`
  ok, `1` engine refusal, `2` usage, `3` unreachable. `drop`, `abandon`, `finish`,
  `lanes rebalance`, `key revoke` and `user disable` only say what they would do without `--yes`.
  The offline `validate --schema`, `explain --schema` and `run` are the Java `pravaha-engine`.
  [`docs/CLI.md`](CLI.md); `test_cli.py`, `test_cli_flight.py`, `test_api.py`.
- **SDK subscriptions survive a server restart.** Python: `subscribe(..., reconnect=True,
  reconnect_timeout=300)`; Java: `subscribe(view, filters, Reconnect, onBatch)` and
  `subscribeFromSnapshot(view, filters, Reconnect, onBatch)` returning a `ReconnectingSubscription`.
  A stream ended by a restart, a broken connection or `PRV-6105` is reopened with backoff (250 ms to
  10 s) until the limit; a refusal that will not change is raised at once. With a snapshot
  subscription the first batch after reopening is a fresh snapshot, so a copy loses nothing
  (`batch.reconnected` in Python, `Reconnect.onReconnected` in Java). `JavaSdkReconnectTest`
  restarts a real server under a subscriber; `test_reconnect.py`.
- **Lane sharing is on by default, as `auto`.** `pravaha.lane.multiplex.enabled` was a boolean,
  `false` by default; it is now `auto` (the default), `true` or `false`, with
  `pravaha.lane.multiplex.auto-from` (64). Under `auto` a node's first 64 queries each own a lane
  -- a failing query takes down only itself -- and every registration after them is placed on a
  shared lane, saving about 1 MiB of inbox and arena per idle query. Running queries are never
  moved. `true` and `false` keep their meaning. The node's `lanes:` status line says which mode is in
  force. An embedded `QueryRegistry` still shares nothing unless asked
  (`multiplexingLanes(lanes, ceiling, shareFrom)`).
- **`WITH (lane = 'dedicated')`: one query on a lane of its own, whatever the node's mode.** A
  registration option (`'dedicated'` or `'shared'`, the default; anything else is PRV-8017),
  journalled with the registration as a new `L` record so a restart keeps it -- a build that predates
  it refuses the journal by name. On `CREATE OR REPLACE`, `lane` moves a running query between a
  shared lane and its own at a lossless cutover, with the SQL unchanged if that is all that changes; a
  replacement that does not say keeps the running version's lane. A dedicated registration that
  would join a computation already on a shared lane is refused with PRV-8017. `GET /api/v1/queries`
  and `/{name}` gain `lane` (`dedicated` | `shared` | `own`) and `sharedLane`; the new
  `GET /api/v1/lanes` summarises placement (mode, `autoFrom`, per-lane counts, own-lane, dedicated
  and hosted computations). Placements are not rebalanced when queries are dropped — until an
  administrator asks: **Admin → Lanes** in the console (every query's lane, the mode, each shared
  lane's fill, and a preview-then-run rebalance for the `admin` role), `pravaha lanes` and
  `pravaha lanes rebalance [--yes]`, and `GET|POST /api/v1/lanes/rebalance`. It moves shared queries
  onto lanes of their own while there is room under `auto-from`, oldest first and one at a time, each
  by a blue/green replacement with `lane = 'own'` (new: a lane of its own without pinning it).
- **`iceberg-sink`, an Apache Iceberg sink** (`plugins/pravaha-plugin-iceberg`), on iceberg-core and
  iceberg-parquet 1.2.1, not Spark. A table on the local filesystem; `mode: upsert` (the default)
  keeps it equal to the view by key through equality deletes (format version 2), and
  `mode: changelog` appends every change with `_op` and `_weight`. One snapshot per checkpoint,
  exactly once: files are staged unreferenced at `prepare`, and the snapshot summary carries the
  transaction id and label, so a commit repeated after a restore is skipped. New codes `PRV-5140`
  (binding), `PRV-5141` (table not the binding's) and `PRV-5142` (write). Not built: object stores,
  catalog services, partitioned tables. `IcebergSinkPluginTest`, 10 tests.
- **`mysql-cdc`, change data capture from MySQL** (REMAINING C3). One table per binding, read from the
  row-based binary log with the plugin registered as a replica, on ADR-041's model and without
  Debezium: an insert at +1, a delete as the whole old row at −1, an update as both, whole
  transactions, `EXACTLY_ONCE` from a binlog file and offset. `binlog_format` other than `ROW`,
  `binlog_row_image` other than `FULL`, and a user without `REPLICATION SLAVE` and `REPLICATION
  CLIENT` are refused by name (`PRV-5152`); a purged resume file is `PRV-5155`. Changes only:
  `snapshot.mode: initial` is refused. New codes `PRV-5150` to `PRV-5157`. Tested against a real
  MySQL 8 (`MySqlCdcIT`, `./mvnw -Pit -pl plugins/pravaha-plugin-mysql-cdc -am verify`).
- **Thirteen case studies, every one run by the build.** Three new ones: `retail-inventory-mysql`
  (`mysql-cdc`, low-stock alerts that clear themselves), `lakehouse-orders-iceberg` (`iceberg-sink`
  in upsert mode, a late row corrected in the table) and `payments-shared-kafka` (three queries on
  one shared Kafka reader, ADR-054; `INDEX (merchant)`, ADR-055). `CaseStudyRunTest` runs each
  study's continuous queries in an embedded engine over its `data/sample/` and checks every read
  against answers worked out by hand. It found four studies registering the wrong view key
  (biology, finance, trading, trade processing) and fixed them.
- **A windowed aggregate that is sent event time before its first row no longer walks from the
  epoch.** With a filter ahead of it — `cancel_rate`'s `WHERE event_type = 'CANCEL'` behind a
  `NEW` — the first watermark fired every empty window since 1970: `PRV-3022` for a ten-second
  slide, and hundreds of thousands of empty windows for an hourly one. It now has nothing to fire.
- **`PravahaEngine.retract(stream, rows...)`** pushes rows at weight −1, as a change-data-capture
  source delivers a delete or the old half of an update.
- **Observability, built out.** *Metrics* for the newest features, all with bounded labels (never a
  user, key, row or statement): alerts (`pravaha_alert_keys_firing`, `_transitions_total` by alert
  and kind, `_notifications_total` by channel and outcome, `_notification_retries_total`,
  `_delivery_seconds`, `_notifications_owed`, `_journal_write_failures_total`), the catalogue
  (`pravaha_catalog_access_decisions_total` by privilege and outcome, `_decision_cache_lookups_total`,
  `_changes_total` by kind, `_subscriptions_ended_total` by reason) and Flight
  (`pravaha_flight_calls_seconds` by operation). The console serves the assistant's at `/metrics`
  (`metrics.enabled`, off; `metrics.token`): requests, tokens, failures, fallbacks and a latency
  histogram per model and profile, and today's ledger tokens. *Dashboards*: four Grafana dashboards
  under `deploy/observability/grafana/`. *Rules*: `deploy/observability/prometheus/pravaha-rules.yaml`
  -- the help topic's ten plus delivery failing, notifications owed growing, the alert journal failing,
  catalogue denials spiking and the assistant failing on every model -- and a Helm `PrometheusRule`
  (`prometheusRule.enabled`, off). *Logs*: `pravaha.logging.format: json` (Spring Boot's structured
  logging) with `correlationId`, `query`, `traceId` and `spanId` from the logging context; every HTTP
  response answers `X-Correlation-Id`; the console has `logging.format: json`. *Traces*:
  `pravaha.tracing.enabled` (off) -- Micrometer Tracing over OpenTelemetry, OTLP/HTTP to
  `pravaha.tracing.endpoint` or the standard `OTEL_EXPORTER_OTLP_*` variables -- with spans per REST
  request, Flight call, registration, replacement, checkpoint and alert notification; a caller's
  `traceparent` is continued, and the Python SDK sends one (`pravaha.tracecontext`). New server
  dependencies, from Spring Boot's BOM: `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`
  over the JDK's HTTP client (`opentelemetry-exporter-sender-jdk`; OkHttp and Kotlin excluded). New
  help topic *Observability*.


Register: **465 findings — 402 fixed, 49 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.2.0 — QA, 2026-09-27

**What this build is for.** People sign in to the console as themselves: the engine keeps users,
passwords, API keys and sessions, and the console holds no password or token of its own. **An upgrade
from 0.1.x needs its server configuration to gain an identity block** -- install.sh says so and how
when it keeps an older file. Also: one reader shared by every query on a Kafka topic, an index on a
column outside a view's key, compressed and Avro/Protobuf Kafka, and a glibc image in which Parquet's
Snappy codec loads.

- **`kafka-sink` writes Avro and Protobuf values** (`format: avro` with `schema.file`, and
  optionally `schema.id` for the Confluent prefix; `format: protobuf` with `schema.descriptor` and
  `schema.message`). Upsert mode only, the key stays JSON and a retraction stays a tombstone.
  Columns map by name, and a column the schema cannot hold exactly is refused at registration
  (`PRV-5108`); `mode: changelog` with either is refused (`PRV-5100`). No schema is registered, and
  no Avro or Confluent library is added: the encoder is written from the specification beside the
  source's reader, which reads every row back unchanged.
- **`jdbc-sink` can commit through PostgreSQL's own two-phase commit** (`commit.mode: prepared`). Each
  checkpoint's changes go straight into the table inside a transaction that `PREPARE TRANSACTION` holds
  and `COMMIT PREPARED` publishes: one write per change instead of two. It is opt-in, because the
  database must allow prepared transactions and the touched rows stay locked for a checkpoint
  interval. It is refused by name elsewhere.
- **The snapshot-and-change-feed splice is a documented boundary.** `SplicedReader` stays
  unwired: its newest-row-per-key rule would double-retract on a weighted changelog such as
  `postgres-cdc`'s, whose own `snapshot.mode: initial` is already exact. A replacement still splices
  at an offset (ADR-046), and `backfill.adaptive` is still refused (`PRV-4018`).
- **An equality index over a column outside a view's key**
  ([ADR-055](adr/055-an-equality-index-over-a-column-outside-the-key.md)). `CREATE CONTINUOUS QUERY
  ... INDEX (region)`, or `WITH (index = 'region')`, keeps value-to-keys in the view's own commit, so
  `WHERE region = 'eu'` and `WHERE region IN ('eu', 'us')` probe instead of scanning, over Flight
  SQL, REST and pgwire alike. The index is journalled with the registration (a new `X` record, which
  an older build refuses by name), rebuilt over a restored checkpoint, and carried to a replacement by
  column name. New code `PRV-2074` refuses an index over `FLOAT`, `DECIMAL`, `BYTES` or the view's
  whole key.
- **The server image runs on glibc, and Parquet's Snappy codec loads in it** (PORT-1,
  [ADR-053](adr/053-native-code-only-where-java-cannot.md)). Up to 0.1.3 the image was Alpine, where
  snappy-java cannot load, so the `feedfile` and `delta` plugins could not read a Snappy-compressed
  Parquet file inside the container. The build now refuses native libraries except Parquet's two
  codecs; TLS runs on the JDK's engine. The node says at startup if a codec cannot load.
- **One reader for many queries, even over an exactly-once source**
  ([ADR-054](adr/054-an-ordered-source-is-shared-at-an-exact-seam.md)). A source declaring ordered
  positions and bounded reads is shared at an exact seam: a query joining, resuming or restoring
  behind the reader catches up to exactly where it stands, and one restored ahead waits for it. The
  Kafka source and the filesystem source (files read once through) implement it, so a thousand queries over one topic read it once, and partitions the topic gains are joined by every query sharing its reader.
- **Users, passwords, API keys and sessions kept by the engine**
  ([ADR-052](adr/052-the-engine-is-the-identity-authority.md), stages 1 to 3), with a REST API and
  `pravaha login|user|key|session|password`. The console signs each person in against the engine and
  acts as them; it keeps no password or engine token of its own. QA installs generate `admin`'s first
  password; locally, run the engine with `--spring.profiles.active=dev,users`. New codes PRV-7010 to
  PRV-7021.

Register: **417 findings — 377 fixed, 26 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.3 — QA, 2026-09-27

**What this build is for.** 0.1.2 with one fix QA would otherwise meet in its first week: a
replacement over a source that had just dead-lettered a record failed. Nothing else changes -- the
ports, the configuration files and the images' layout are 0.1.2's, so a 0.1.2 host upgrades by
installing this bundle over it.

- **Replacing a query whose latest record was dead-lettered works** (REPL-2). The backfill counts a
  record its source rejected as read, and `PartitionReader#poll`'s `maxRecords` now bounds records
  consumed, rejected ones included, so a backfill stops on the running version's exact position
  whether or not the record there could be decoded. The filesystem and Kafka readers are brought into
  line; PostgreSQL CDC already was. 0.1.2 fails such a replacement with `PRV-4013`.

Register: **399 findings — 370 fixed, 15 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.2 — QA, 2026-09-27

**What this build is for.** The QA host's second build, and the one to test on: 0.1.1's console image
shipped without the documentation its help pages include (IMG-1), and 0.1.1's query sharing could
hand one query another's answer (FP-1). Both are fixed here. **The default ports change** — 18080,
19090 and 17070 — so a QA host moving from 0.1.1 installs this bundle fresh or moves its published
ports. `install.sh` keeps a host's two configuration files, and 0.1.1's name the old ports
explicitly: it detects that and prints the one `sed` that moves them.

- **Default ports moved** (the owner's decision): the engine's HTTP port is **18080** (was 8080),
  Flight SQL **19090** (was 9090), the console **17070** (was 8090). Both SDKs' default port, the CLI's
  default URL, the images, the Helm chart and the QA install follow. **A 0.1.1 deployment that
  relied on the defaults must move its clients and published ports**; one that set them explicitly
  keeps working. The documented help-link base now points at a console path that exists (HELPURL-1).
- **`docs/PYTHON_API_GUIDE.md`**: an integrator's guide and reference for every call the Python SDK
  makes and every REST endpoint, each sample run against a 0.1.1 node. It ships in the QA bundle.
- **Python SDK:** a refusal over Flight carries the engine's code as `QueryError.engine_code`, as
  `ApiError` always has (PYSDK-1); an engine that is down is `ConnectError`, retryable, not a
  refusal (PYSDK-2); `pravaha.__version__` is the installed wheel's (PYSDK-3).
- **The QA host's demonstration stream** is the seven-column `txn` with an event-time column and a
  `large_payments` sink, so the guide's samples, windows included, run on a fresh install.
- **Replacement:** a replacement whose backfill stops is `FAILED`, with the source's code, and its
  candidate is released; it used to go on reporting `BACKFILLING` with no failure (REPL-1).
- **Console image:** it carries the documentation its help pages include; the 0.1.1 image did not,
  so its tutorials, guides and code browser were empty (IMG-1). The build now checks every include.
  The Python guide has a help card (`/help/python-api-guide`).
- **Query sharing (FP-1, fixed):** two queries differing only in a join's time bound, INNER against
  LEFT, which column a projected name came from, or an aggregate's function shared one computation,
  and the second read the first one's answer. The fingerprint now hashes each operator's full
  identity, and EXPLAIN shows a join's window, `LeftJoin`, renamed columns' sources and aggregate
  arguments. **0.1.1 has this defect**; nothing on disk changes on upgrade.
- **A tutorial joining two Aerospike sets and a CSV file** (`docs/tutorials/aerospike-fulfilment.md`,
  and a console card), with a script that pushes live orders; the QA install gains `/opt/pravaha/feeds/`
  for files you drop for file sources, and the bundle carries the tutorials' scripts.
- **Console help:** `docs/COMPETITIVE_LANDSCAPE.md` scores Pravaha against five categories of
  product, with a card per row and where it loses, and is its own page (`/about/competitive`). The
  About page gains problem-and-fix pairs, "What makes it different", this list ("In this release",
  read from this file) and a condensed landscape; the help gains an FAQ, and guides for these
  notes, the roadmap (`REMAINING.md`) and `DEPLOYMENT.md`.
- **Fixed from the tutorials' runs:** a parameterised query from the Java SDK or CLI works under a
  token (SDKJ-1); a second name on a shared computation is answered with that name (NAME-1); a debug
  fixture of a windowed query carries its event time and runs (FIX-1).
- **Found, open:** a query cannot be replaced while the record at its position was dead-lettered
  (REPL-2, POST-GA); since REPL-1 it fails as `FAILED` `PRV-4013` rather than silently.

Register: **399 findings — 369 fixed, 16 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.1 — QA, 2026-09-26

**What this build is for.** The same as 0.1.0 — quality assurance on one node — now handed to a QA
team as files: two container images, a compose file and two configuration files, installed on one
Linux machine with Docker. 68 commits since `v0.1.0`; the tag is the only thing published.

### How it is delivered

| | |
|---|---|
| **Two images** | `pravaha/pravaha-server` (615 MB, every connector inside) and, new, `pravaha/pravaha-console` (481 MB). Built by `deploy/docker/build.sh` and `deploy/docker/console/build.sh`; both run as uid 10001 |
| **One root** | Every path is under `/opt/pravaha`: `conf/` and `console/conf/` for the two configuration files, `data/` for state, `logs/` for the engine's log and the audit trail. `/var/lib/pravaha` and `/etc/pravaha` are gone — **a 0.1.0 deployment that mounted them must move its mounts** ([`DEPLOYMENT.md`](DEPLOYMENT.md), "One root") |
| **Two files** | The engine and the console are each configured by their own YAML file, edited in place and read on restart |
| **A bundle** | `deploy/qa/bundle.sh` writes `pravaha-qa-0.1.1.tar.gz`: both images as `docker save` archives, `install.sh`, the compose file, the jars, wheels and chart, and `SHA256SUMS`. [`deploy/qa/README.md`](../deploy/qa/README.md) is the page for the QA team |

### What changed in behaviour

A QA reader who tried 0.1.0 will meet these:

- **Every connector is in the server jar.** Kafka, Delta, JDBC, PostgreSQL CDC, Aerospike and
  Cassandra bind with nothing to install. Two lookups, `jdbc-lookup` and `aerospike-lookup`, had never
  been declared to the plugin loader, so a lookup join on a real node was refused `PRV-5090`; they now
  load. 14 plugins, checked by `ShippedConnectorsTest` and by the smoke run inside the image.
- **A filter on a `TINYINT`, `SMALLINT` or `REAL` column gave wrong answers** in 0.1.0 (NARROW-1): the
  comparison read the column's neighbour too. Fixed, and the reason 0.1.0 should not be used for
  answers over narrow columns.
- **Filters and projections run generated code** by default (`pravaha.codegen.enabled`); a query's
  description says which path each chain is on.
- **Tenants.** A tenant is charged for its queries and its state against quotas
  (`pravaha.tenancy.*`, ADR-050), and **identical SQL from two tenants is now two computations** —
  a tenant shares a computation only with itself. Refusals `PRV-8020`–`PRV-8023`.
- **SQL that 0.1.0 refused and now runs:** a stream joined with itself; `ROW_NUMBER() ... rn <= N`
  as a maintained top-N; exact `DECIMAL` arithmetic; `DATE_FORMAT`, `REGEXP_EXTRACT`, `SPLIT_INDEX`; a
  comma join with its condition in `WHERE`. Nexmark: **12 of 23** queries run (5 at 0.1.0).
- **Delta** reads deletion vectors (a deleted row arrives as a retraction) and `delta-sink` writes
  partitioned tables. **Kafka** resolves Avro against a reader schema and fetches Protobuf
  descriptors from the schema registry.
- **Firing a large window streams its groups** instead of building the window on the heap (SPILL-3),
  and an Aerospike `lut-scan` reads a page at a time (SRC-7) — the two defects 0.1.0 told QA to watch.
- An empty paging parameter on the REST debug read is refused rather than read as the default, and a
  lone surrogate over Flight is refused `PRV-1053`, as over HTTP.
- The console has **blue** and **green** themes and a new landing page.

### The numbers

| Measurement | Result | Command |
|---|---|---|
| Java tests | **4,175 run, 0 failures, 189 skipped**, 37 reactor projects | `tools/verify-clean.sh` |
| With the Docker integration tests | **4,185 run, 0 failures**, 18 skipped | `sg docker -c "./mvnw -o verify"` |
| Console tests | **1,732 passed, 0 failed** (380 without a browser, 1,352 in Chrome: visual, accessibility, journeys, states, performance) | `cd console && python -m pytest` |
| The images | `smoke.sh` **PASSED** (register, read, follow, restart, `--read-only`); `qa-smoke.sh` **PASSED** (the console against a real node, all 14 plugins); the QA compose stack installed, signed in to, queried and restarted | `deploy/docker/smoke.sh`, `tools/qa-smoke.sh` |

The performance figures of 0.1.0 stand and have not been re-measured; PERF-1 below says why the
default command's figures should be distrusted until they are.

### Open defects

At this cut: **377 findings — 354 fixed, 9 open, 0 GA-BLOCKER, 0 GA-REQUIRED**, 7 of the open ones
triaged POST-GA and 2 recorded as notes rather than defects ([`qa/FINDINGS.md`](qa/FINDINGS.md)).
Two worth knowing before starting:

- **EMIT-1** — a fired window still holds heap per group for as long as its lateness lasts, and a
  late row can fire it again. Size lateness with the group count in mind.
- **PERF-1** — every performance figure taken with the default command ran under the coverage agent.
  Treat 0.1.0's throughput table as an ordering, not as measurements.

---

## 0.1.0 — QA, 2026-09-20

**What this build is for.** Quality assurance on a single node. It is the first cut offered to
anyone but its author, and the point of it is to be tried, not to be deployed: run a node, register
continuous queries, watch answers change, break it, and tell the author what broke.

**Nothing has been published from this tree.** No Maven repository, no container registry, no
Python index, no signing key. A QA reader builds it, or is handed the artefacts the release script
produced. The licence is proprietary ([`../LICENSE`](../LICENSE)) and every file in the tree says so.

### What it does

A SQL query registered once keeps answering. Rows arrive from a source, the answer is maintained
incrementally as a Z-set — a change carries a weight, `+1` for an insert and `-1` for a retraction —
and the current answer is a view that can be read, subscribed to, or written to a sink.

| | |
|---|---|
| **Ask it** | Flight SQL, a REST API (`/api/v1`), the PostgreSQL wire protocol, `pravaha` on the command line, a Java SDK, a Python SDK, and the console in a browser |
| **Sources** | Filesystem (bounded or followed), feedfile directories (CSV, Parquet), Delta Lake, JDBC polling, Aerospike scans, Cassandra `token()`-range scans, PostgreSQL change data capture, Kafka topics (JSON, Avro and Protobuf — with no Avro or Confluent library) |
| **Sinks** | `filesystem`, `aerospike-sink`, `jdbc-sink`, `kafka-sink`, `delta-sink`. Five, and each states its delivery guarantee at registration rather than in a document |
| **Survives a restart** | Checkpoints, a registry journal, and sinks that stage a checkpoint and commit it once the checkpoint is durable |
| **Explains itself** | Every refusal is a `PRV-nnnn` code with a sentence saying what to do; `EXPLAIN` shows the plan; a query's plan carries per-operator rows in, rows out and a measured bottleneck; a debug session forks a query from a checkpoint and steps it row by row |

### The numbers, and the commands that produce them

| Measurement | Result | Command |
|---|---|---|
| Java tests | **4,043 run, 0 failures, 183 skipped**, 37 reactor projects | `tools/verify-clean.sh` (offline, wipes the project from `~/.m2` first) |
| Console tests | **844 run, 842 passed** at the last full run; the two failures were a test-isolation defect and a load flake, both since fixed | `cd console && python -m pytest` |
| Python SDK tests | **132 collected, 131 passed, 1 skipped** without the `tls-keystore` extra | `cd sdk/python && python -m pytest` |
| Skips | 184, and every one of them names its reason: Docker, Cassandra, Aerospike or `psql` absent on the machine | in the surefire output |

The skips matter for QA: a machine without Docker does not run the Kafka broker, PostgreSQL CDC or
Aerospike integration tests, and they are skipped **by name** rather than passing quietly.

### Performance, measured here and nowhere else

There is no reference hardware, so the gates were measured on the development machine — an AMD
Ryzen AI 9 HX 370, 12 physical cores, frequency-scaled, with other work running — and every number
in [`gates/measured-2026-09-20/`](gates/measured-2026-09-20) carries the machine's load average
beside it.

| Gate | Target | Measured | Verdict |
|---|---|---|---|
| P2, Profile A throughput | ≥ 1.2 M rows/s per lane | ~30 M warm, ~11 M cold; the worst pass, at load 77, was 1.04 M | **reached** |
| P2, scaling 1 → 8 lanes | ≥ 90 % of linear | **28–42 %** | **not reached** |
| P3, Profile B throughput | ≥ 350 k rows/s per lane | 2.5–2.8 M, worst pass 1.1 M | **reached**, and measured for the first time |
| ADR-038's Nexmark comparison | head-to-head against Flink | **not run** — no Flink, no quiet machine, no reference generator. Of Nexmark's 23 queries, **5 run** on this engine today | **not reached** |

A QA reader should not quote the throughput figures as product numbers. They were taken on a laptop
part under load, several are too noisy to state as a figure, and the harness says so where they are.

### Known, and deliberately not in this build

- **Multi-node execution.** Designed ([ADR-045](adr/045-cluster-mode-assigns-queries-not-rows.md))
  and on hold by the owner's decision. A node refuses `PARTITIONED` with `PRV-9002` rather than
  serving every partition while claiming to own some.
- **Continuous integration.** Four workflows exist; **none has ever run**. Nothing is built, tested,
  published or signed by a machine other than this one.
- **Tenancy and admission quotas.**
- **The manual WCAG 2.2 AA audit.** The automated half — axe on every page, both themes, both
  densities — is green; a person still has to do the rest.
- **The console's design-system surface**, by decision.
- Smaller refusals, each named and reasoned where it is raised: no secondary index over a non-key
  column, no `INSERT INTO <sink> SELECT`, no Iceberg or Hudi sink, no partitioned Delta tables,
  `COUNT(DISTINCT)` cannot spill.

### Open defects

The register is [`qa/FINDINGS.md`](qa/FINDINGS.md), and it is the honest list: every defect found,
what happened to it, and what is still true of the build.

At this cut: **362 findings — 332 fixed, 17 open, 0 GA-BLOCKER, 0 GA-REQUIRED**, 15 of the open ones
triaged POST-GA and 2 recorded as notes rather than defects. The header's counts are enforced by
`FindingsRegisterTest`, so this page and the register cannot drift apart silently.

Three worth a QA reader's attention before they start:

- **SPILL-3** — firing a very large window builds it on the heap and can exhaust it, whether or not
  the spill tier is on. Bounded by the operator's own sizing; `OPERATIONS.md` gives the arithmetic.
- **PF-12** — a benchmark harness reported 131 % of linear scaling on a loaded machine and *passed*.
  Fixed, and recorded because it is the first defect here that produced a pass rather than a
  failure. If a number looks too good on a busy machine, distrust it.
- **SRC-7** — an Aerospike `lut-scan` buffers a whole scan on the heap, and `maxRecords` bounds
  only what it hands on. Size the heap for the scan, or use `deletes: detect` with a narrower
  range.

### Running it

[`QUICKSTART.md`](QUICKSTART.md) is the five-minute path, and its `application.yaml` declares an
event-time column — which it did not until today, so a first-time reader's windowed query silently
never emitted. [`DEPLOYMENT.md`](DEPLOYMENT.md) covers the container image and the Helm chart, both
of which run one node by design. [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) carries the code index.
