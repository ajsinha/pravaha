---
title: Plain English on Pravaha — turning it on, and who may
slug: ai-features
category: ai
order: 10
icon: stars
summary: "The engine's AI layer behind every plain-English control in the console: off by default, one provider file to turn it on, a gate per feature by role, what a model is ever sent, and the codes it answers with."
badge: ADR-063
audience: Administrators and everyone
keywords: [ai, llm, model, provider, plain english, features, ask, draft, alert, explain, narrative, page, policy, roles, values, labels-only, aggregates, mock, "pravaha.ai.enabled", "Admin AI", test connection, features endpoint, PRV-1080, PRV-1084, PRV-1085, PRV-1086, PRV-1087, PRV-1088, PRV-7002]
guide: ai
related: [ask-your-data, ai-drafts, ai-explain, assistant, audit]
---

The console's plain-English controls — **Ask your data**, **Describe a continuous query**, **Describe an
alert**, the light-bulb **Explain in plain words**, **Why did this change?**, **Summarise changes** and
**Ask about this page** — are all answered by the engine's AI layer
([ADR-063](/help/decisions/063-the-ai-layer)). The design is drishti's. One rule holds everywhere:
**the model writes names and SQL, never answers.** It is told the schema of what *you* may read and your
words; the engine checks what it wrote, runs it **as you**, and the console shows the SQL the model chose
beside what the engine made of it. Nothing is registered, created or saved by any of them.

## Turn it on {#turn-it-on}

The layer is **off** until the node is told otherwise, and while it is off every control is simply not
drawn. One provider file in the node's `conf/ai` folder and two settings:

```yaml
# conf/ai/mock.yaml -- the deterministic provider: regular expressions play the model
type: mock
default-model: m
models:
  - name: m
```

```bash
PRAVAHA_AI_ENABLED=true PRAVAHA_AI_DEFAULT_MODEL=mock/m bin/pravaha-server
```

A real provider is the same file with its `type` and a key named by environment variable or file —
never the key itself; [the AI layer](/help/architecture-ai) lists the types. **The console holds no
key**: its own AI layer has one provider, the engine, and every call goes through it as the signed-in
person (`ai.enabled` in the console's `application.yaml` turns that on). No key ever reaches a browser.

**Admin → AI** shows both layers: on or off, each provider with its health (`OFF`, `ERROR`, `UNKNOWN`,
`UP`, `DOWN`), a provider file that could not be read and why, and when a provider that failed is tried
again. **Test connection** makes one real call to a model — an administrator only, capped at 15 seconds
and rate-limited by the engine — and shows the outcome and how long it took, never the key.

## Who may {#who-may}

Two gates, and both must open. **The engine's capability first:** someone who may read no view is refused
`PRV-7002` before any model is asked, and so is someone who may not register a query asking for a draft.
**Then the AI policy, per feature:** `pravaha.ai.policy.features.<name>.roles` — `ask`, `draft`, `alert`,
`explain`, `narrative`, `page` — with its own per-minute limit, beside the per-person budgets of every call.

```yaml
pravaha:
  ai:
    policy:
      features:
        draft: {roles: [developer, admin]}
        ask: {per-user-per-minute: 10}
```

The console asks the engine which features you may use — `GET /api/v1/ai/features` answers
`{"enabled": true, "values": "labels-only", "features": {"ask": true, "draft": false, ...}}` — and leaves
out every control you may not use. Nothing is called to find out, and nothing is refused on the page.

## What a model is sent

Only the schema of what you may read — views, their keys and columns, masked columns left out — and your
own words, between markers that tell the model they are data. **Never a row or a value** while
`pravaha.ai.values` is `labels-only`, the default. With `aggregates`, your own figures — after your row
filter and masks — may be worded too; every answer the console shows carries a badge saying which.

## When it says no

| Code | What the console shows |
|---|---|
| `PRV-1080` | The layer is off: the controls are not drawn; a control left open says so in one line |
| `PRV-1084` | The policy does not open this feature to your roles |
| `PRV-1085` | A limit: try again in the time it names |
| `PRV-1086` / `PRV-1087` | The model did not answer in time, or failed |
| `PRV-1088` | The model's answer could not be used — the engine refused what it wrote, twice, or the question cannot be answered from what you may read. Nothing ran |
| `PRV-7002` | You may not read the view, or the SQL the model chose names something you may not read (the SQL is shown) |

Each refusal is shown with its code linked to its page, and **Explain in plain words** beside it when the
explain feature is open to you. Every call is audited twice, never with its text: `ai.call` and the
feature's own event (`ai.ask`, `ai.draft`, ...), at **Admin → Audit**.
