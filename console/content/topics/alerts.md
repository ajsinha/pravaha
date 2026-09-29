---
title: Alerts — told when a row enters a view, and when it leaves
slug: alerts
category: operating
order: 72
icon: bell
summary: "CREATE ALERT (ADR-057): a key fires when its row enters a view and clears when it leaves; flap controls, snooze and pause change what is said, never what is true; exactly-once state, at-least-once delivery."
badge: ALERTS
audience: Analysts, operators
keywords: [alert, alerts, "CREATE ALERT", "SHOW ALERTS", "PAUSE ALERT", "SNOOZE ALERT", "ACK ALERT", notify, notifier, webhook, hmac, signature, idempotency, fired, cleared, reminder, snooze, dedupe, flapping, fire_after, clear_after, resend_every, severity, "pravaha alerts", "pravaha alert create", ADR-057, PRV-8040, PRV-8042, PRV-8043, PRV-8046, PRV-8047]
guide: continuous-queries
related: [views-and-keys, create-continuous-query, catalog-and-grants, observability, errors-registry, cli-reference]
---

An **alert** watches a continuous query's view. A key **fires** when its row enters the view's
answer — inserted, or updated across the view's `WHERE` — and **clears** when it leaves — deleted,
or updated back. Clearing is honest because the view is fed retractions: the update that tops a
line up arrives as the old row at `-1`, and the alert is told the line has left rather than guessing
that it might have.

## Creating one

```text
CREATE ALERT low_stock_alert ON low_stock
  WHERE warehouse = 'LDN'
  NOTIFY buyers
  WITH (severity = 'warning', dedupe = '10m', include = (on_hand, reorder_point));
```

- **The alert's name** shares the namespace of queries (PRV-8041) and may not be `channels`, which
  is the path of the channel list (`/api/v1/alerts/channels`): PRV-8042 (ALERTPATH-1).
- **`ON`** names a registered query's view (under the catalogue, `namespace.view` too). The view's
  page, and `dependants` in `GET /api/v1/queries/{name}`, list the alert as `ALERT <name>`
  (ALERTDEPS-1).
- **`WHERE`** narrows it: comparisons of the view's own columns with literals (`=`, `!=`, `<`, `<=`,
  `>`, `>=`, `IS [NOT] NULL`) joined by `AND`. Anything richer — `OR`, arithmetic, two columns — is
  a question: register it as a query over the view and alert on that.
- **`NOTIFY`** names one or more channels the node binds under `pravaha.notifiers.<name>`.
- **`WITH`**: `severity` (`info`, `warning`, `critical`), `fire_after` (in the condition this long
  before firing), `clear_after` (out of it this long before clearing), `dedupe` (the least time
  between two notifications about one key), `resend_every` (remind until acknowledged), `include`
  (the columns a notification carries), `snooze` (start snoozed).

The other statements: `ALTER ALERT a SET (...)`, `ALTER ALERT a NOTIFY c, d`, `DROP ALERT a`,
`PAUSE ALERT a`, `RESUME ALERT a`, `SNOOZE ALERT a FOR '2h'`, `ACK ALERT a`, `SHOW ALERTS`. They run
wherever `CREATE CONTINUOUS QUERY` runs — the workbench, `pravaha query --sql`, Flight SQL, JDBC.

## What is true, and what is said

Each key has two states, kept apart: **what is true** — firing or not, after `fire_after` and
`clear_after` — and **what the channels were last told**. Pausing, snoozing and `dedupe` hold the
second back and never change the first; whenever they allow, the channels are told the difference
once. A key that fired and cleared while an alert was paused is never announced; one that cleared
while it was snoozed is announced as cleared when the snooze ends; a flap inside a `dedupe` window is
folded into the state at the end of it.

## Delivery, plainly

- **State is exactly once.** Every decision — fired, cleared, told, acknowledged — is journalled
  (`alerts.journal`, beside the registry journal) and forced to disk before anything is sent. After a
  restart a key that was firing is still firing and is **not fired again**, and a clear that was
  decided and not yet delivered **is delivered**.
- **Delivery is at least once.** A channel that does not accept a notification is sent it again —
  by the webhook with backoff, then by the node every `redeliver-after` — and a restart between a
  send and its record sends it again. Every attempt carries the same **`Idempotency-Key`**; a
  receiver that must act once de-duplicates on it.

## Channels

```yaml
pravaha:
  notifiers:
    buyers:
      plugin: webhook
      options:
        url: https://hooks.example.com/pravaha
        secret-env: PRAVAHA_BUYERS_HOOK_SECRET   # or secret-file: /run/secrets/buyers-hook
    ops-log:
      plugin: log
```

The **webhook** POSTs the notification as JSON, signed: `X-Pravaha-Signature: sha256=<hex>` is
HMAC-SHA256 of `<X-Pravaha-Timestamp>.<body>` under the secret. It retries a timeout, a 5xx, 408 and
429 with backoff, and never a 400. A secret is never configuration: `secret`, `token` and `password`
options are refused (`PRV-8046`). `format: slack` sends Slack's `{"text": ...}`. The **log** channel
writes one line per notification under the logger `pravaha.alerts`.

## Who may do what

Under the catalogue an alert is an `ALERT` object its creator owns: `SELECT` to see it and its keys,
`MODIFY` to pause, resume, snooze and acknowledge, `MANAGE` to alter or drop it. Creating one needs
`CREATE` in your namespace, `SELECT` on the view and `WRITE` on each channel
(`GRANT WRITE ON NOTIFIER buyers TO ROLE buyers`). Without the catalogue the view's rights stand in.
A view an alert follows cannot be dropped (`PRV-8024`) until the alert is.

## In the console

**Alerts** lists what you may see, with a chip per alert — how many keys are firing, or *clear*.
One alert's page shows every key it holds (`FIRING`, `PENDING`, `CLEARING`, `CLEARED`), what the
channels were last told and what they are owed, its recent notifications with their outcome, and the
pause, resume, snooze and acknowledge buttons — each asking the engine as you.

## From the command line

```text
pravaha alerts ls
pravaha alerts show low_stock_alert
pravaha alerts snooze low_stock_alert 2h
pravaha alerts ack low_stock_alert --key "sku=sku-300, warehouse=LDN"
pravaha alert create low_stock_alert --on low_stock --notify buyers --dedupe 10m
pravaha alert drop low_stock_alert --yes
```
