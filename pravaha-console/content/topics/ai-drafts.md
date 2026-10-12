---
title: Drafts — a continuous query or an alert from words
slug: ai-drafts
category: ai
order: 30
icon: magic
summary: "Describe a continuous query on the Workbench, or an alert on Alerts: the engine judges the model's draft as registering it would, dry-runs it — plan, reads, lane, what it would share — and nothing exists until you register or create it and confirm."
audience: Developers and analysts
keywords: [draft, describe, "Describe a continuous query", "Describe an alert", dry run, plan, lane, sharesWith, fingerprint, register, confirm, "CREATE CONTINUOUS QUERY", "CREATE ALERT", alert draft, PRV-1088, PRV-7002, PRV-8042]
guide: "ai#5-draft-a-continuous-query"
related: [ai-features, create-continuous-query, alerts, sharing, lanes]
---

A draft is ADR-058's drafting done by the engine: the model writes the parts — a name, the `SELECT`, its
keys — and the engine judges the whole statement **as registering it would**, with every refusal
registration gives and the policy's answer for what it reads and writes. A refusal is repaired once, then
refused (`PRV-1088`). **Nothing is registered or created by a draft.**

## Describe a continuous query

On the **Workbench**, **Describe a continuous query** opens a box: say what the view should answer —
"spend per user per minute". The answer shows:

- the `CREATE CONTINUOUS QUERY` **statement**, and the model's one-sentence **explanation** of it;
- the **dry run**: the engine's plan, the streams and views it **reads**, the **lane** it would run on
  (its own, a shared one, or the lane of an identical computation already running), **shares with** — the
  registered queries whose fingerprint it has, so registering it would reuse their computation — and the
  **fingerprint**;
- **Open in Workbench**, to change it first; and **Register**.

**Register asks first.** A confirmation names the query and what it reads; only **Register it** sends the
draft — the one the console holds, exactly as the engine judged it, through the Workbench's ordinary
registration as you. The browser sends the draft's id, never its SQL.

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = 'u1'
```

Once registered, a draft is a query like any other: read its view (above), follow it, replace it.

## Describe an alert

On **Alerts**, **Describe an alert** — "tell me when a user spends over 500 in a minute" — drafts a
`CREATE ALERT` on a view you may read, judged by the very checks `CREATE ALERT` runs: the view, the
condition's columns and literals, the channels, the options, your masks. An alert compares a view's own
columns, so when no view computes what the words need, the answer is `PRV-1088` with the continuous query
that would have to exist first. **Create** asks to confirm, then runs the statement as you — what the
Workbench would do with it.

```text
CREATE ALERT big_spender ON minute_spend
  WHERE spend > 500
  NOTIFY ops_log
  WITH (severity = 'critical')
```

## Who may

A draft needs the `draft` feature and permission to register a query (`PRV-7002` before any model is
asked otherwise); an alert draft the `alert` feature and a view you may read. Without either, the button
is not drawn ([who may](/help/topics/ai-features#who-may)).
