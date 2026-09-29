---
title: The assistant
slug: assistant
category: reference
order: 60
icon: robot
summary: "Plain English to and from continuous SQL through any model you configure — draft a query, explain a query or a refusal — with the engine as the judge and you as the one who registers: on the command line and in the console."
badge: ADR-058
audience: Developers and administrators
keywords: [assistant, assist, llm, model, ai, ask, draft, describe, repair, golden set, eval, evaluation, explain-sql, why, explain a refusal, provider, anthropic, openai, openai-compatible, ollama, vllm, fallback, profile, chain, budget, api_key_env, assist.json, ModelRouter, AssistAdmin, entry point, plugin]
guide: python-sdk
related: [admin-ai-models, cli-reference, sql-refusals, reading-a-plan, errors-sql, sdk-reference]
---

The assistant drafts a continuous query from a description in plain English, explains a continuous
query, and explains a refusal — what a code such as PRV-2050 means for *your* statement and what to
change. It asks a language model you configure:
any provider, several at once, with fallback between them. **The engine stays the judge.** The model
is given the engine's own plan or the engine's own refusal, never its memory of what Pravaha accepts,
and any SQL it proposes is validated by the engine before you are told it works. The engine itself
gains no model dependency, no outbound call and no new permission; the assistant lives in the Python
SDK and the command line.

Built (phases 1 to 3 of ADR-058): `pravaha ask` to draft a query, `pravaha explain-sql`,
`pravaha why`, `pravaha assist` to see, check and switch models, and `pravaha assist eval` to measure
one — and in this console, **Describe it** on the workbench, **Explain** on a query's page and beside
every refusal code, and [Admin · AI models](/help/topics/admin-ai-models) to configure and switch
models while the console runs.

## In the console

**Describe it** (the workbench, above the editor). Say what the view should answer; the console
drafts it as you, with your own engine session, exactly as `pravaha ask` does, and shows:

- the `CREATE CONTINUOUS QUERY` statement, the model's explanation and its assumptions;
- the engine's verdict — *accepted* (validated and planned), or *refused* with the engine's code and
  sentence, and an **Explain** beside the code — and every repair turn;
- the engine's own plan, and the guarantee of the sink it writes to, if any;
- the model's **questions**, when it could not decide — answer them in place and **Draft again**;
  the engine is asked nothing until the description is clear enough;
- a running query that appears to compute the same thing, with a link to read it instead;
- **Copy to editor**, which opens the SQL as a new draft tab in the workbench;
- **Register**, disabled until the engine accepts the draft, and even then only once you tick that
  you have read the SQL and the plan. It registers through the ordinary call, as you — the draft
  the console holds, exactly as the engine judged it; the browser sends only its id.

**Explain this query** (a query's page) explains the registered query in plain English, grounded in
the engine's plan for it. **Explain** beside a refusal code — on the workbench, in its diagnostics,
on a query's page, on a refused draft — says what that code means for *this* statement and what to
change; a rewrite the model proposes is shown with the engine's verdict on it.

Every answer says which model gave it, after which others were unavailable, and what it cost. The
budgets apply: a request over one is refused before anything is sent, and the page says so. When no
model is configured, or none is enabled, each of these shows one notice instead — with a link to
Admin · AI models for an administrator, and "ask an administrator" for anyone else. Each works with
scripting off too: the form posts, and the answer comes back as a page.

## Configure a model

Keys live in environment variables, never in the file:

```bash
export ANTHROPIC_API_KEY=your-key          # or OPENAI_API_KEY; a local Ollama needs none
```

Then `~/.config/pravaha/assist.json` (or the path in `PRAVAHA_ASSIST_CONFIG`):

```json
{
  "default_profile": "explain",
  "providers": [
    {"id": "anthropic", "type": "anthropic", "api_key_env": "ANTHROPIC_API_KEY"},
    {"id": "local", "type": "ollama", "endpoint": "http://localhost:11434"}
  ],
  "models": [
    {"id": "claude", "provider": "anthropic", "model": "claude-opus-5"},
    {"id": "llama", "provider": "local", "model": "llama3.1:70b"}
  ],
  "profiles": {"explain": ["llama", "claude"], "draft": ["claude", "llama"]},
  "budgets": {"per_user_daily_tokens": 200000, "per_request_max_tokens": 8000}
}
```

A **profile** is an ordered chain: the first model answers, and the next is tried only when one is
unavailable or rate-limited. The file is JSON because programs write it too (`pravaha assist use`,
and this console's Admin · AI models) and it must round-trip exactly with the standard library alone; a `.yaml` or
`.toml` file is refused with directions.

**A key written in the file is refused** — a field named `api_key`, `token`, `secret` or `password`,
or a value shaped like a key — on load, on save and on every change. Name the variable with
`api_key_env`, or a file only you can read with `api_key_file`.

```bash
pravaha assist models      # what is configured; whether each key variable is set (never its value)
pravaha assist check       # ping each model; no tokens where the provider's API allows it
```

## Explain a query, explain a refusal

```bash
pravaha explain-sql --query hourly_spend --show-plan
pravaha why PRV-2050
pravaha why PRV-2050 --sql "SELECT customer, COUNT(*) FROM orders GROUP BY customer"
```

`explain-sql` gives the model the SQL and the plan the engine would run for it, and tells it the plan
wins where the two disagree. `why` with no statement needs no engine at all: it is grounded in the
error table and the sections of the long-form guide about that code, shipped with the SDK as a
*dialect card* that a test keeps current with the guide. With `--sql`, the model is given the engine's
own diagnostics too, and a rewrite it proposes is printed with the engine's verdict on it:

```text
PRV-2050  The query's state would grow without bound.

why  It groups by customer with no window, so every customer ever seen is kept.
fix  Group by a window of event time, or give the view a retention.

rewrite  (the engine accepts it)
  SELECT customer, COUNT(*) AS n FROM orders GROUP BY customer, TUMBLE(ts, INTERVAL '1' MINUTE)
```

Which model answered, how long it took and what it cost are on stderr. `--json` prints the whole
result: the answer, the engine's plan or verdict, the prompt version, and the model that answered
after which others were tried.

## Draft a query from a description

```bash
pravaha ask "stock lines at or below their reorder point, and tell me when they recover"
pravaha ask "orders per customer per minute" --name orders_per_minute --register
```

What happens, in order:

1. **The context comes from the engine**, under your own credentials: the streams you may read (with
   their columns, event-time column and lateness), the views you may read, the sinks you may write
   to and the guarantee each gives, the guide's rules on windows, joins and refusals, and two to
   four worked examples from the case studies most like your description. A stream you cannot read
   is never named to the model, and no row is ever read. Everything is held to a size budget; what
   is least relevant is left out first, and named.
2. **The model drafts** a fixed shape: a name, one `SELECT`, the key, the options (retention, index,
   sink, lane), an explanation, its assumptions, its questions and its confidence. **If it has
   questions, you get the questions** — the engine is not asked anything until the description is
   clear enough.
3. **The engine judges it**: validate, then explain. The assistant also checks what the engine only
   checks at registration — the key and index name real output columns, the sink is one you may see,
   the retention is a duration — and says it made those checks, not the engine.
4. **A refusal is repaired**, at most three times: the model is given the PRV code, the engine's
   sentence and the guide's section about that code. **It may not loosen your question** — a repair
   that reads different streams than the first draft is refused as "a different question" and never
   sent to the engine. Still refused after three turns, the draft is shown as refused, in the
   engine's words, and the command exits 1.
5. **You see** the `CREATE CONTINUOUS QUERY` statement, the model's explanation, the engine's own
   plan, the sink's guarantee, the assumptions and questions, every turn, and which model answered at
   what cost. If a running query you can see has the same plan, key and retention, you are told and
   offered to read that instead.

```text
the engine accepts it after 1 repair turn

  CREATE CONTINUOUS QUERY low_stock
    KEYED BY (sku, warehouse)
  AS
  SELECT sku, warehouse, on_hand, reorder_point, updated_at FROM stock WHERE on_hand <= reorder_point

Turns
  1. draft   refused by the engine: PRV-2002  Column 'reorder_level' not found
  2. repair  accepted by the engine
```

**Registration is yours.** `--register` registers only a draft the engine accepted, and only once you
confirm — it asks at a terminal; `--yes` confirms in a script; otherwise nothing is registered. It is
the ordinary registration call under your own credentials (so it needs `--url`), authorized exactly
like a statement you typed. The model has no way to register anything.

**The same as a running query, by fingerprint.** The engine answers, for an accepted draft, the
fingerprint registering it would get for you — plan, your row filters, key, retention and tenant — and
a running query with that fingerprint is the same computation. Against an engine too old to answer one,
the assistant compares the engine's plan text, key and retention instead, and says `match: plan`:
registering then tells you for sure.

## Measure a model

```bash
pravaha assist eval --model claude
pravaha assist eval --model llama --case retail-inventory-mysql,negative --json
```

A golden set ships with the SDK, generated from the case studies: one case per continuous query (its
description and reference SQL), plus three that must be refused or must raise a question — an
aggregate kept forever with no window, a join between two streams with no time bound, and a stream
that does not exist. Each is drafted exactly as `ask` would (without its own worked example), then
scored **by meaning, not text**: the engine accepted it, it reads the same streams as the reference,
and the engine's plan and the key equal the reference's. A negative case passes only if refused or
asked about — a model that quietly answers a different question is caught. The report gives each
case's outcome, repair turns, tokens and time, and a summary; exit 1 if a scored case failed.

It needs an engine with the case studies' streams (start a node with a study's configuration); cases
whose streams are missing are skipped. `--run` also registers the draft and the reference under a
prefix, compares the engine's fingerprints — or their answers on the node's sample data — and drops
both: use it on a test node, never a production engine.

## Providers

| Type | Speaks | Key |
|---|---|---|
| `anthropic` | the Messages API | required |
| `openai` | Chat Completions | required |
| `openai-compatible` | Chat Completions at your `endpoint` — vLLM, LM Studio, llama.cpp's server, a gateway | only if the server wants one |
| `ollama` | Ollama's own chat API, on your hardware | none |
| `fake` | scripted answers, for tests | none |

All are standard-library HTTP in the SDK: nothing extra to install. Another provider is a Python
package declaring an entry point in the SDK's provider group (docs/ASSIST.md shows a complete one); `pravaha assist providers`
lists what is installed. A package cannot take a built-in's name.

Every provider failure is one of four: *unavailable* (the next model is tried), *rate limited* (the
next model is tried; the provider's retry-after is reported), *refused* on content policy, or an
answer that did not fit the shape asked for after one repair turn. The last two **stop the chain**
and name the model — the assistant does not shop a refusal around until some model agrees.

## Switching models while things run

The configuration is an immutable snapshot. A change — through `pravaha assist use explain claude,llama --yes`,
`pravaha assist disable llama --yes`, or [Admin · AI models](/help/topics/admin-ai-models) in this console — is
validated completely first (unknown provider, duplicate id, a chain naming a disabled model, a key
variable not set), then stored atomically, then swapped in: requests already running finish on the
old configuration, new ones use the new. A process following the file picks the change up within a
second, and keeps out one it cannot use. Every change comes back as an audit record: who, what,
before and after. Disabling a model a chain still names is refused — change the chain first.

## Budgets and exit codes

`per_request_max_tokens` refuses a request whose estimated size would exceed it, before sending;
`per_user_daily_tokens` counts what each person spent today in a small local ledger (mode `0600`).
Both refusals are `BudgetExceeded`.

| Exit | Means |
|---|---|
| `0` | Answered — for `ask`, a draft the engine accepts, or the model's questions |
| `1` | A model failed (the normalised error on stderr), a budget refused the request, the engine refused the SQL (for `ask`, still refused after the repair turns), or an evaluation case failed |
| `2` | The assistant's configuration is wrong — nothing was sent to any model |
| `3` | The engine could not be reached |

## Security

- **No rows are ever sent.** What leaves your machine is the SQL, the engine's plan or refusal, what
  the engine reports about a registered query, excerpts of the guide, and — for `ask` — the names,
  columns and types of what you may read. A deployment that may not
  send even that off-site configures only `ollama` or `openai-compatible` on its own hardware.
- **Your credentials, your permissions.** The assistant explains, validates and lists the catalogue
  with your own token. The only thing that registers is you — `ask --register`, after you confirm, as
  any registration of yours; the model has no way to register, drop or read a view, whatever it says.
- **Keys by environment variable or secret file only**; never in the configuration, an error
  message, `assist models` or an audit record.
- The full reference — every field, the provider table, runtime reconfiguration from Python, and a
  complete provider plugin — is `docs/ASSIST.md` in the repository.
