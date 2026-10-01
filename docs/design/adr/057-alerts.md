# ADR-057: an alert follows a view's answer, and says when a row enters it and when it leaves

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `AlertService`, `Alert`, `AlertJournal`, `Notifiers` and the `webhook` and `log` notifiers in `pravaha-registry` (`registry.alert`), `AlertStatements` in `pravaha-sql`, the `NotifierPlugin` SPI in `pravaha-api`, `NodeAlerts` and `/api/v1/alerts` in `pravaha-server`, `pravaha alerts` / `pravaha alert` in the Python SDK, the console's Alerts screens. Not built: the `email`, `slack` (beyond a webhook preset), `teams` and `pagerduty` plugins, freshness-objective alerts (ADR-059 §6), alerts across nodes |
| Date | 2026-09-28 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-008 (a checkpoint is one cut), ADR-043 (how a query names its sink), ADR-050 (tenancy), ADR-052 (secrets are never configuration), ADR-056 (queries on queries — an alert is a follower of an answer), ADR-059 (the catalogue: `ALERT` is a kind), KEYEDWT-1 |

## Context

The retail case study's buyers want to know **the moment a line falls to its reorder point** — and,
just as much, the moment it stops being a problem. The engine already computes the answer: `low_stock`
is a continuous query whose rows are the lines at or under their reorder point, kept exactly by
retractions, so a delivery that tops a line up takes it out of the view. What the product has not had
is the last step: *telling somebody*. Today a person subscribes to the view and writes their own
alerting — deduplication, snoozing, retries, restarts — and most of them get clearing wrong, because a
consumer that listens only for rows arriving "pages the buyer about toasters for ever".

ADR-056 built the mechanism an alert needs: a follower of a view's **answer** (`ServedView.followAnswer`,
`AnswerListener`) — the committed rows, then each commit's rows leaving and entering — rather than of its
changelog, which for a keyed upsert view does not say that a row left (KEYEDWT-1). ADR-059 reserved
`ALERT` as a catalogue kind with `SELECT`, `MODIFY` and `MANAGE`. This ADR decides what an alert is.

## Decision

**An alert is a registered follower of one view's answer, with a condition over its rows. A key
*fires* when its row enters the condition and *clears* when it leaves; the alert's state is kept
exactly once, journalled before anything is sent, and its notifications are delivered at least once to
notifier channels — plugins configured like sinks — under an idempotency key that is the same on every
attempt.**

### 1. What fires and what clears: entering and leaving the answer

An alert names a view (`ON low_stock`) and optionally narrows it (`WHERE warehouse = 'LDN'`). Its
**condition** is "the view's row for this key exists and satisfies the `WHERE`". It follows the view's
answer (ADR-056 §1) and, per commit, sees each touched key's row before and after:

| Before | After | Means |
|---|---|---|
| out | in | the key **entered** — an insert, or an update across the threshold |
| in | out | the key **left** — a delete, or an update back across it |
| in | in, changed | still in; the row it carries is updated, nothing is said |

Retractions are what make **clearing honest**. The delivery that tops sku-200 up arrives from
`mysql-cdc` as the old row at −1 and the new at +1; the filter passes neither half into `low_stock`,
the answer loses the row, and the alert is told the key left — it does not have to infer it from
silence. Following the **answer** rather than the changelog matters for a keyed view whose query
upserts: its changelog carries only the +1 of a row that replaced another (KEYEDWT-1), so an alert on
the changelog would never see the old row leave; the answer says so in the same commit.

The condition is **deliberately not a language** (as ADR-048's debug predicate is not): comparisons of
the view's own columns with literals — `= != <> < <= > >=`, `IS [NOT] NULL` — joined by `AND`. `OR`,
arithmetic, functions and one column against another are refused (`PRV-2072`, `PRV-2070`): each is a
question, and a question belongs in a continuous query over the view (ADR-056) that the alert then
watches, where the planner reasons about it and anyone can read its answer. A column the view does not
have, or a number compared with text, is refused at `CREATE` (`PRV-8042`) rather than being a condition
that silently never holds.

### 2. The statements

```text
CREATE ALERT [IF NOT EXISTS] low_stock_alert ON inventory.low_stock
  [WHERE warehouse = 'LDN' AND on_hand < 5]
  NOTIFY buyers [, ops_log]
  [WITH (severity = 'warning', fire_after = '1m', clear_after = '5m', dedupe = '10m',
         resend_every = '1h', include = (sku, on_hand), snooze = '30m')]
ALTER  ALERT low_stock_alert SET (severity = 'critical', dedupe = '15m')
ALTER  ALERT low_stock_alert NOTIFY buyers, ops_log
DROP   ALERT [IF EXISTS] low_stock_alert
PAUSE  ALERT low_stock_alert
RESUME ALERT low_stock_alert            -- ends a pause or a snooze
SNOOZE ALERT low_stock_alert FOR '2h'
ACK    ALERT low_stock_alert            -- every firing key; REST and the CLI take one key
SHOW   ALERTS
```

They are read before Calcite by `AlertStatements`, carried as `ContinuousStatement.Alert`, and run
wherever `CREATE CONTINUOUS QUERY` runs — Flight SQL, JDBC, `pravaha query --sql`, the workbench —
answering rows. `ALTER ALERT … SET TAGS | UNSET TAGS | OWNER TO` stay the catalogue's (ADR-059 §3):
`SET (` is an alert option list, `SET TAGS` is governance. To change the condition or the view, drop
and create: the state of every key is about the old condition, and carrying it across would be
guessing. An alert and a query cannot share a name (`PRV-8041`), because every surface names both the
same way. *(Amended by [ADR-060](060-view-names-are-unique-per-tenant.md): an alert's name, like a
view's, is unique within its tenant and resolved in the caller's tenant.)*

Options, each refused by name if unknown rather than ignored (an ignored `dedupe` is a pager that goes
off every second): `severity` (`info`, `warning` — the default — `critical`), `fire_after` (alias
`for`), `clear_after`, `dedupe`, `resend_every`, `include` (the columns a notification carries; all by
default) and, on `CREATE` only, `snooze`. Durations are `30s`, `10m`, `2h`, `1d`, `250ms` or ISO-8601.

### 3. State per key, and two states kept apart

For each key it has seen enter the condition an alert keeps: firing or not, the **episode** (1 for the
first firing, 2 for the next), how many times it has fired, since when, when it cleared, what the
channels were **last told** and when, reminders sent, and who acknowledged it.

What is **true** (firing, after `fire_after` and `clear_after`) and what was **said** are kept apart,
and every flap control acts on the second only:

- **`fire_after` / `clear_after`** — a key must stay in (out) this long before it fires (clears). A
  line that dips for thirty seconds under a one-minute `fire_after` never fires; one topped up and
  sold down again inside `clear_after` stays firing and nothing is said. These change what is true.
- **`dedupe`** — the least time between two notifications about one key. A transition inside the
  window is not lost: it is **deferred**, and at the end of the window the channels are told the key's
  state *then*, only if it differs from what they were last told. Fired, cleared and fired again inside
  ten minutes is one `FIRED`; fired and cleared is `FIRED` then, `CLEARED` ten minutes later. The
  receiver's picture converges on the truth, with at most one notification per key per window.
- **`PAUSE`** — keeps following and deciding, says nothing. **`RESUME`** tells the channels the
  difference: a key that fired and cleared while paused is never announced (it was over before anybody
  would have been told), one that cleared is announced as cleared, one that fired as fired.
- **`SNOOZE … FOR`** — a pause that ends by itself; the same convergence at its end.
- **`resend_every`** — a firing key not yet acknowledged is re-announced as a `REMINDER`. **`ACK`**
  stops the reminders until the key fires again (a new episode).

A pending key (in, waiting out `fire_after`) is not durable: after a restart its timer starts again.
Up to 1,000 cleared keys are remembered per alert for the page; the oldest is forgotten first.

### 4. Guarantees, plainly: exactly-once state, at-least-once delivery

- **State is exactly once.** Every decision — `F` fired, `C` cleared, `N` told, `K` acknowledged — is
  appended to `alerts.journal` (beside the registry journal; `pravaha.alerts.journal`) and **forced
  to the device before anything is sent**, in the registry and catalogue journals' discipline:
  length-prefixed `ControlWire` records, owner-only, a torn tail dropped. The journal *is* the
  checkpoint of every alert: a compaction rewrites it with one snapshot record per remembered key
  (at open once 256 records are stale, and while running once it has grown well past what is live).
- **After a restart** the alert replays its journal, follows its view again, and compares the view's
  restored answer with the keys it holds (ADR-056 §2's "difference between two answers", applied to
  keys). A key that was firing and is still in the answer is **not fired again**. A clear that was
  decided and not yet delivered is **delivered** — it is owed, because what was last said is in the
  journal. A firing key missing from the restored answer clears — after `pravaha.alerts.recovery-grace`
  (30s) as well as `clear_after`, so a view rebuilt by replay rather than restored from a checkpoint
  has time to catch up before anybody is told its lines are fine.
- **Delivery is at least once.** A notification is owed until every channel of the alert accepts it:
  the webhook retries with backoff inside one attempt, and the service sends it again every
  `pravaha.alerts.redeliver-after` (60s) until it is accepted, recording each failure on the alert
  (`PRV-8045`, its delivery error and a `FAILED` line in its history). A restart between a send and
  its record sends it again. So a receiver can see a notification twice, and every attempt carries an
  **idempotency key** — SHA-256 over the alert's identity (a new UUID per alert, so a re-created alert
  never collides), the key, the episode, the kind and the reminder number — **the same across retries
  and restarts**. A receiver that must act once de-duplicates on it. We say exactly-once *state*,
  at-least-once *delivery*, and do not claim more: exactly-once delivery to an HTTP endpoint needs the
  endpoint's cooperation, which the idempotency key is.

### 5. Notifier channels are plugins, configured as bindings

A channel is a `NotifierPlugin` (`pravaha-api`), the sink SPI's shape: discovered by `ServiceLoader`
under the name it reports, configured before it is opened, one instance per channel shared by every
alert, never throwing for a failed delivery. Channels are bindings, like sinks:

```yaml
pravaha:
  notifiers:
    buyers:
      plugin: webhook
      options:
        url: https://hooks.example.com/pravaha      # or url-env / url-file when the URL is a credential
        secret-env: PRAVAHA_BUYERS_HOOK_SECRET       # or secret-file: /run/secrets/buyers-hook
    ops_log:
      plugin: log
  alerts:
    enabled: true
```

A channel the node cannot open — no such plugin, no URL, a secret it cannot read — **refuses the
start** (`PRV-8046`): a pager found broken at the first page is worse than a node that did not start.

**Built:**

- **`webhook`** — HTTP `POST` of the notification as JSON (`Notification.toJson()`), with
  `Idempotency-Key`, `X-Pravaha-Event`, `X-Pravaha-Attempt`, `X-Pravaha-Timestamp` and
  `X-Pravaha-Signature: sha256=<hex>`, the HMAC-SHA256 of `<timestamp>.<body>` under the channel's
  secret (a receiver recomputes it, compares in constant time, and refuses a stale timestamp — which
  is what stops a captured request being replayed). Retries a timeout, network failure, 5xx, 408 and
  429 with exponential backoff (`retries` 4, `backoff` 500ms to `max-backoff` 30s); any other 4xx is
  the receiver refusing and is not retried in that attempt. `connect-timeout` 5s, `timeout` 10s,
  redirects not followed, the URL never written into a message. `format: slack` sends Slack's
  incoming-webhook body (`{"text": ...}`) — a preset, because it is one line; signing is then usually
  `none`, and the Slack URL, which is itself a credential, is given by `url-env`.
- **`log`** — one line per notification under the logger `pravaha.alerts`; always delivered.

**Secrets are never configuration (ADR-052).** The webhook refuses an option called `secret`, `token`,
`password` or `key`, and a `header.*` that would carry a credential; the secret is read from where
`secret-env` or `secret-file` says. Signing is required unless the binding says `signing: none`.

**Designed, not built:** `email` (SMTP with STARTTLS, the password by `password-env`/`-file`, one
message per notification, the idempotency key as `Message-ID`), `teams` (a Workflows webhook posting an
Adaptive Card — not a `format:` preset, because the card is not one line), `pagerduty` (Events API v2:
`trigger` on `FIRED`, `resolve` on `CLEARED`, the alert and key as the `dedup_key`, so PagerDuty's own
de-duplication does what the idempotency key does elsewhere). Each is a plugin behind the same SPI.

### 6. An alert is a catalogue object; a `NOTIFY` needs `WRITE` on the channel

Under the catalogue (ADR-059) an alert is an object of kind `ALERT`, owned by its creator, in their
tenant's `default` namespace, with tags and a description like any object:

| To | Needs |
|---|---|
| create it | `CREATE` in the namespace, `SELECT` on the view (an alert shows its receivers the view's rows), and `WRITE` on every channel it names |
| see it, its keys and its history | `SELECT` on the alert |
| pause, resume, snooze, acknowledge | `MODIFY` (or `MANAGE`) |
| alter, drop | `MANAGE` |

Channels are catalogued as the node is configured with them, as a new kind `NOTIFIER` under
`node.notifiers` (`GRANT WRITE ON NOTIFIER buyers TO ROLE buyers`), with `WRITE`, `MANAGE` and `OWN`.
An alert on a view its creator may read only through a row filter is refused: it would send whole
rows to its channels. **Without the catalogue** there are no grants on an alert, so its view's rights
stand in: seeing it is `mayRead` on the view, changing it is being its creator or `mayAdminister` on
the view, and a channel is asked as a sink is (`mayWriteTo`). Either way **tenants are walls**
(ADR-050): an alert is never visible outside its tenant, and one the caller may not see is answered
exactly as a missing one (`PRV-8040`).

### 7. Lineage: an alert follows its view, so the view cannot be dropped from under it

An alert is a **dependant** of its view, as a query over it is (ADR-056 §4): `DROP CONTINUOUS QUERY`
of a view an alert follows is refused with `PRV-8024`, naming `ALERT <name>`, and `CREATE OR REPLACE`
of it with `PRV-8026` — a cutover would move the name to another computation behind the alert. No
cascade, for ADR-056's reason. `PAUSE` of the view is allowed; its answer stops moving and the alert
with it. After a restart an alert whose view did not come back **waits** for it (shown as `WAITING`)
rather than being dropped, and follows it when it is registered.

### 8. Where it lives

`registry.alert` in `pravaha-registry`: the registry already owns views, the follower mechanism and
the policy, and a new module would have needed all three. `QueryRegistry` and `PravahaNode` are at the
file-size ceiling, so each gains only a hook — `QueryRegistry.alertingWith(Alerting)` (what follows a
view, and the door for the statements) and `NodeAlerts`, started after the registry recovers. The
service evaluates on one thread (every 250ms) and delivers on a small pool, one notification in flight
per key so a key's `FIRED` is never overtaken by its `CLEARED`.

## Alternatives considered

**Alert on the view's changelog (a subscription).** For a keyed upsert view the changelog says a row
arrived and not that the one it replaced left (KEYEDWT-1), so clearing would be guessed. The answer
says both, in the commit that did it.

**Make an alert a continuous query with a sink.** `CREATE CONTINUOUS QUERY … WRITING TO webhook`
already half-works — and has no per-key state, no clearing distinct from a retraction row, no
dedupe, snooze or acknowledgement, and a sink's at-least-once replay would re-page every row since the
last checkpoint after a restart. An alert's product is not rows; it is a small state machine per key.

**Evaluate a condition expression language.** A second, nearly-SQL language with its own null and
type rules disagrees with SQL in the one tool somebody uses when something is already wrong. Richer
conditions are a query over the view (ADR-056), which is exact, planned and readable.

**Exactly-once delivery.** Not achievable to an HTTP endpoint without its cooperation; claiming it
would be the most dangerous sentence in the documentation. The idempotency key is the cooperation.

**Deduplicate by suppressing, not deferring.** A clear suppressed inside a window leaves the receiver
believing a line is still low when it is not — the silent failure alerts exist to prevent. Deferring
and converging costs one delayed notification and never a wrong picture.

**Keep alert state in the view's checkpoint.** The alert and its view restart independently, and a
view may have no checkpoints at all. The alert's own journal is its checkpoint, and ADR-056's rule —
compare answers, not positions — makes the two meet whatever order they come back in.

## Consequences

- New codes `PRV-8040`–`PRV-8047`: no such alert (404), exists (409), definition invalid, no such
  channel, journal failed, delivery failed (recorded, never thrown to a caller), notifier misconfigured
  (refuses the start), not served (409; an embedded engine, or `pravaha.alerts.enabled: false`).
- `/api/v1/alerts` (list, detail with per-key state and recent notifications, `channels`, and
  `pause`/`resume`/`snooze`/`ack`), `pravaha alerts ls|show|channels|pause|resume|snooze|ack`,
  `pravaha alert create|drop`, and the console's **Alerts** screen and nav entry.
- Audit events: `alert.create`, `alert.alter`, `alert.drop`, `alert.pause`, `alert.resume`,
  `alert.snooze`, `alert.ack`, `alert.show`, `alert.notify` (a channel's `WRITE`), and the engine's own
  `alert.fire` and `alert.clear`, under the principal `pravaha-alerts`.
- The catalogue gains the kind `NOTIFIER` and `Catalog.registerObject`/`dropObject`/`reconcile` for any
  kind a person creates by statement; a query cannot be registered under an alert's catalogue name.
- **Later:** freshness-objective alerts (ADR-059 §6: `ALTER VIEW … SET FRESHNESS OBJECTIVE '30s'` is
  an alert whose condition is the view's lag, fired by the engine rather than by a row), alert groups
  and routing by severity, the four designed plugins, alerts across nodes (ADR-045 is design only).

## Notes

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number and
gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning behind a
decision that was later reversed is usually the most useful thing in the directory.
