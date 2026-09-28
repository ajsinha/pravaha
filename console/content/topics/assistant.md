---
title: The assistant
slug: assistant
category: reference
order: 60
icon: robot
summary: "Plain English to and from continuous SQL through any model you configure — explain a query, explain a refusal — with the engine as the judge: configuration, providers, fallback, budgets, switching models at runtime, and security."
badge: ADR-058
audience: Developers and administrators
keywords: [assistant, assist, llm, model, ai, explain-sql, why, explain a refusal, provider, anthropic, openai, openai-compatible, ollama, vllm, fallback, profile, chain, budget, api_key_env, assist.json, ModelRouter, AssistAdmin, entry point, plugin]
guide: python-sdk
related: [cli-reference, sql-refusals, reading-a-plan, errors-sql, sdk-reference]
---

The assistant explains a continuous query in plain English and explains a refusal — what a code such
as PRV-2050 means for *your* statement and what to change. It asks a language model you configure:
any provider, several at once, with fallback between them. **The engine stays the judge.** The model
is given the engine's own plan or the engine's own refusal, never its memory of what Pravaha accepts,
and any SQL it proposes is validated by the engine before you are told it works. The engine itself
gains no model dependency, no outbound call and no new permission; the assistant lives in the Python
SDK and the command line.

Built so far (phase 1 of ADR-058): `pravaha explain-sql`, `pravaha why`, and `pravaha assist` to see,
check and switch models. Drafting a query from a description, and this console's own panel and model
administration screen, come later.

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
  "profiles": {"explain": ["llama", "claude"]},
  "budgets": {"per_user_daily_tokens": 200000, "per_request_max_tokens": 8000}
}
```

A **profile** is an ordered chain: the first model answers, and the next is tried only when one is
unavailable or rate-limited. The file is JSON because programs write it too (`pravaha assist use`,
and later this console) and it must round-trip exactly with the standard library alone; a `.yaml` or
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
`pravaha assist disable llama --yes`, or the SDK's admin facade the console's screen will call — is
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
| `0` | Answered |
| `1` | A model failed (the normalised error on stderr), a budget refused the request, or the engine refused the SQL |
| `2` | The assistant's configuration is wrong — nothing was sent to any model |
| `3` | The engine could not be reached |

## Security

- **No rows are ever sent.** What leaves your machine is the SQL, the engine's plan or refusal, what
  the engine reports about a registered query, and excerpts of the guide. A deployment that may not
  send even that off-site configures only `ollama` or `openai-compatible` on its own hardware.
- **Your credentials, your permissions.** The assistant only explains and validates, with your own
  token; it has no way to register, drop or read a view, whatever a model says.
- **Keys by environment variable or secret file only**; never in the configuration, an error
  message, `assist models` or an audit record.
- The full reference — every field, the provider table, runtime reconfiguration from Python, and a
  complete provider plugin — is `docs/ASSIST.md` in the repository.
