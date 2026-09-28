# The assistant: plain English to and from continuous SQL, with the engine as the judge

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../LICENSE`](../LICENSE).

The assistant explains a continuous query in plain English, and explains a refusal — what
`PRV-2050` means for *this* statement and what to change. It asks a language model you configure,
any provider, several at once, and it never trusts what the model says on its own: the model is
given the engine's own plan and the engine's own words, and any SQL the model proposes goes back to
the engine before you are told it works. The design is [ADR-058](adr/058-plain-english-to-continuous-sql.md).

**What is built (phase 1):** the `pravaha.assist` package in the Python SDK — the provider protocol,
five built-in providers, the router with fallback chains, budgets and runtime reconfiguration, the
configuration store and its administration facade, and two tasks: `pravaha explain-sql` and
`pravaha why`. **Not yet:** drafting a query from a description (`pravaha ask`, phase 2), the
console's panel and admin screen (phase 3), and the `azure-openai`, `bedrock` and `vertex`
providers (phase 4).

- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Providers](#providers)
- [How a model is chosen](#how-a-model-is-chosen)
- [Changing the configuration while it runs](#changing-the-configuration-while-it-runs)
- [The two tasks](#the-two-tasks)
- [Commands](#commands)
- [From Python](#from-python)
- [Writing a provider plugin](#writing-a-provider-plugin)
- [Security](#security)
- [Developing](#developing)

---

## Quick start

1. Put a key in an environment variable — never in a file the assistant reads:

   ```bash
   export ANTHROPIC_API_KEY=...        # or OPENAI_API_KEY, or nothing for a local Ollama
   ```

2. Write `~/.config/pravaha/assist.json` (mode `0600` is what the tools write; any mode works):

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

3. Check it, then ask:

   ```bash
   pravaha assist models                  # what is configured, which key variables are set
   pravaha assist check                   # ping each model; no tokens where the API allows
   pravaha why PRV-2050                   # no engine needed
   pravaha why PRV-2050 --sql "SELECT customer, COUNT(*) FROM orders GROUP BY customer"
   pravaha explain-sql --query hourly_spend
   ```

`pravaha why` with no statement works offline: it needs a model and nothing else. With `--sql` and
`explain-sql` it also talks to the engine (`--http`, as every HTTP command does), under your own
token.

## Configuration

**Where.** `$PRAVAHA_ASSIST_CONFIG`, else `assist.json` in the command line's configuration
directory (`$PRAVAHA_CONFIG_DIR`, else `$XDG_CONFIG_HOME/pravaha`, else `~/.config/pravaha`). No file
means no models: every task exits `2` saying so.

**Why JSON.** The file is written by programs — `pravaha assist use|enable|disable` and, in phase 3,
the console's admin screen — as well as by people, so it must round-trip exactly. JSON is the one
format the standard library both reads and writes on every Python the SDK supports (3.9+). YAML
would need PyYAML, which the SDK does not depend on (its base install has no dependencies at all);
TOML's reader is 3.11+ and the standard library has no TOML writer. An `assist.yaml`, `.yml` or
`.toml` is refused with directions rather than half-read. (ADR-058 sketched the file in YAML; the
fields are the same.)

| Field | What it is |
|---|---|
| `providers` | A list. Each has an `id` (named by models), a `type` (a built-in, or a plugin's name — [Providers](#providers)), and optionally `endpoint`, `api_key_env` **or** `api_key_file`, `timeout_s` (default 60) and `options` (passed to the provider). Several may share a type: two gateways, two accounts |
| `models` | A list. Each has an `id` (named by profiles and `--model`), a `provider` (a provider's `id`), `model` (the provider's own model name), and optionally `enabled` (default `true`), `timeout_s` and `options` — merged over the provider's |
| `profiles` | Profile → an ordered list of model ids: the fallback chain. The tasks ask for `explain` |
| `default_profile` | The chain used when a task's profile is not configured |
| `budgets` | `per_request_max_tokens` and `per_user_daily_tokens`; either may be left out |
| `debug` | `true` keeps each provider's raw answer on the response (`PRAVAHA_ASSIST_DEBUG=1` does the same) |
| `version`, `changed_at`, `changed_by` | Written by the store on every save; leave them alone |

Model `options` a built-in understands: `structured_output` (`json_schema`, `tool_call` or `none`,
overriding what the provider claims), `max_context_tokens`, `max_output_tokens`. The `fake` provider
also takes `replies` and `ping` ([Developing](#developing)).

**No secret in configuration.** A key is named by `api_key_env` (an environment variable) or
`api_key_file` (a file that must not be readable by group or others), and read at the moment a
provider is built. A field called `api_key`, `key`, `token`, `secret`, `password`, `authorization`…
anywhere in the document, or a value shaped like a key (`sk-…`, `Bearer …`), is refused — on load,
on save and on every administrative change — whoever wrote it. It is the rule
`NoSecretsInConfigurationTest` holds the engine to.

**Validation** happens in two steps, and a configuration is applied only when both pass:

1. what the document can say about itself — shapes, unknown fields (a typo is an error, not a
   silently ignored setting), unique ids, provider types that exist, chains that name existing,
   **enabled** models, a default profile that is a profile, positive budgets;
2. what only a running process knows — that each enabled model's key variable is set here, its
   secret file readable.

Disabling a model that a chain still names is refused: change the chain first, so what answers is
always a decision someone made.

## Providers

| Type | API | Key | Endpoint (default) | Schema asked for | `check` pings |
|---|---|---|---|---|---|
| `anthropic` | Messages, `POST /v1/messages` | required | `https://api.anthropic.com` | natively, `output_config.format` (`json_schema`) | `GET /v1/models/{model}` |
| `openai` | Chat Completions, `POST /chat/completions` | required | `https://api.openai.com/v1` | natively, `response_format` (`json_schema`) | `GET /models/{model}` |
| `openai-compatible` | Chat Completions | optional | **required** — the server's base URL, e.g. `http://localhost:8000/v1` | in the prompt, unless the model option `structured_output: json_schema` | `GET /models` |
| `ollama` | Ollama's own `POST /api/chat` | none (optional behind a proxy) | `http://localhost:11434` | natively, `format` | `POST /api/show` |
| `fake` | nothing — scripted answers | none | — | as configured | nothing |

Each is written against the HTTP API with the standard library (`urllib`), as `EngineApi` is: no
provider SDK is installed or used.

**Chat Completions, not Responses, for OpenAI.** `POST /chat/completions` is the one wire shape that
OpenAI and every server imitating it speak — vLLM, LM Studio, llama.cpp's server, LiteLLM, most
gateways — so one implementation serves both `openai` and `openai-compatible`, and a fix to one is a
fix to both. The Responses API is OpenAI's alone, and nothing these tasks ask for (one turn, text or
one JSON object) needs what it adds. `openai` sends `max_completion_tokens` (its newer models
require it); `openai-compatible` sends `max_tokens` (what the servers accept).

**A schema is always checked.** When a task needs structured output, a provider that can constrain
its answer natively is asked to; one that cannot is told in the system prompt to answer with one
JSON object satisfying the schema. Either way the answer is validated here against the JSON Schema
(a small standard-library validator for the subset the prompts use — it refuses a schema with a
keyword it does not enforce rather than half-checking it). An answer that fails gets **exactly one**
repair turn, with the validation error; a second failure is `ModelOutputError`, never a guess.

**Every failure is one of four.** Each provider normalises its own errors:

| Error | When | The router |
|---|---|---|
| `ModelUnavailable` | nothing answered, a timeout, `5xx`, Anthropic's `529 overloaded`, an unknown model (`404`), a refused key (`401`/`403`), an exhausted quota | tries the next model |
| `ModelRateLimited` | `429`, with the provider's `retry-after` (seconds, an HTTP date, or `retry-after-ms`) | tries the next model |
| `ModelRefused` | the model declined on content policy (`stop_reason: refusal`, a `refusal` message, a content filter) | stops, and says which model |
| `ModelOutputError` | the answer did not satisfy the schema after the repair turn | stops, and says which model |

A key never appears in an error: keys travel in headers, and messages quote only the URL and the
provider's own error text.

## How a model is chosen

A task asks for a profile (`explain`; `--profile` changes it, `--model ID` asks one model only).
Its chain is tried in order. **Only `ModelUnavailable` and `ModelRateLimited` fall through** to the
next model; `ModelRefused` and `ModelOutputError` stop the chain and are reported with the model that
said them — asking a second model until one agrees is not something the assistant does quietly. A
model whose key variable has disappeared since the configuration was applied is passed over the same
way. Every answer says which configured model gave it and which were tried first.

**Budgets** are checked before a request is sent. A request's size is estimated (four characters a
token, plus the most it may answer); over `per_request_max_tokens`, it is refused with
`BudgetExceeded`. `per_user_daily_tokens` is counted from each answer's reported usage — tokens of a
failed repair included — in a small ledger, `assist-usage.json` beside the configuration, mode `0600`,
seven days kept. The ledger is a per-machine convenience cap, not an enforcement point (a person with
a shell can delete it); spend accounting belongs to the console's audit trail (phase 3).

## Changing the configuration while it runs

A running process — the console, in phase 3 — must be able to switch models without a restart, and
an administrator must be able to make that switch safely. The pieces:

- **`AssistConfig`** is an immutable, validated snapshot: providers, models, profiles and chains,
  budgets, which models are enabled, and its `version`, `changed_at` and `changed_by`.
- **`ModelRouter.reconfigure(config)`** validates the new snapshot completely (both steps above) and
  only then swaps it in, under a lock. A request reads the snapshot once when it starts, so requests
  in flight finish on the old configuration and new ones use the new one; none sees half of each.
  On any problem it raises `AssistConfigError` naming every problem, and nothing changes.
- **`AssistConfigStore`** is the protocol for where the configuration lives: `load`, `save` against
  the version it was built from (a concurrent change is `ConfigConflict`, not a silent overwrite),
  and `watch`. **`FileConfigStore`** is the JSON file above, written to a temporary file with mode
  `0600` and renamed over the old one, so a reader sees the old document or the new, never half; its
  watch polls the file's inode, modification time and size.
- **`ModelRouter.follow(store)`** applies every snapshot another process stores. One that does not
  validate *in this process* — its key variable is not set here, or the file was mangled by hand —
  is kept out and recorded in `router.last_rejected`; the router carries on with what it had.
- **`AssistAdmin(store, router, actor=...)`** is what an administration screen calls: `providers()`
  (built-in and discovered, with capabilities), `models()`, `add_provider`/`update_provider`/
  `remove_provider`, `add_model`/`update_model`/`remove_model`/`enable_model`/`disable_model`,
  `set_chain(profile, [ids], default=...)` (the order is the fallback order), `remove_profile`,
  `set_default_profile`, `set_budgets`, and `test_model(id)` (a ping: its latency, or the normalised
  error). Every change is built from the stored snapshot, validated, saved, applied to the router
  and answered as an **`AuditRecord`** — who, what, the element before and after, the version — for
  the caller to log. `dry_run=True` validates and answers the record without storing anything.

On the command line: `pravaha assist use explain claude,llama --yes`, `pravaha assist disable llama
--yes`. Without `--yes` they print the change and make none.

## The two tasks

**Explain a query** — `pravaha explain-sql --sql …|--sql-file …|--query NAME`. The model is given
the SQL and the engine's own plan for it (`POST /api/v1/queries/explain`, physical by default,
`--level logical`), and told the plan wins where the two disagree; for `--query` also the SQL the
engine registered and what it reports about it (keys, retention, sink, lane). SQL the engine refuses
is reported as the engine's refusal, exit `1`, before any model is asked. The answer is a summary, the
steps, and notes; `--show-plan` prints the engine's plan under it.

**Explain a refusal** — `pravaha why PRV-2050 [--sql …]`. The model is given the engine's own words —
with a statement, the diagnostics of `POST /api/v1/queries/validate`; without one, the guide's line for
the code — and the sections of [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) about that code, from
the **dialect card**, followed by the guide's general advice on refusals (§17). It answers what the
code means, why this statement triggered it, what to change, and a rewrite when there is a supported
one. **A rewrite is validated by the engine** and printed with its verdict — `the engine accepts it`,
`the engine refuses it too (PRV-…)`, or `not checked`; it is never presented as working on the model's
say-so. If the engine did not raise that code for the statement, or accepts the statement, you are told.

**The dialect card** (`pravaha/assist/resources/dialect-card.json`) is generated, never edited, by
`sdk/python/tools/build_dialect_card.py` from the guide: every code the guide names, the error table's
line for it, and the sections that the line points at (`§13`), name the code in their heading, or
mention it — most relevant first, long sections cut to the paragraphs about codes. A test fails when
the guide changes and the card was not rebuilt.

**Prompts are versioned package resources** (`pravaha/assist/resources/prompts/explain_query.v1.txt`
and its `.schema.json`, likewise `explain_refusal`). Changing what the assistant asks is a new
version; every result names the prompt that produced it (`explain_query@v1`).

## Commands

| Command | What it does |
|---|---|
| `explain-sql (--sql S \| --sql-file F \| --query NAME) [--level physical\|logical] [--show-plan] [--profile P] [--model ID]` | A query in plain English, grounded in the engine's plan |
| `why PRV-nnnn [--sql S \| --sql-file F] [--no-check] [--profile P] [--model ID]` | What the refusal means and what to change; `--no-check` skips validating a proposed rewrite |
| `assist models` | Every configured model: its provider, key variable (set or not — never its value), enabled, and the chains naming it (`explain#1*`: first in `explain`, the default profile) |
| `assist providers` | Every provider type, built-in and installed by entry point, with what it claims |
| `assist check [--model ID,ID]` | Ping each enabled model as cheaply as its API allows; exit `1` if any failed |
| `assist use PROFILE ID[,FALLBACK...] [--default] [--yes]` | Set a profile's chain |
| `assist enable ID [--yes]`, `assist disable ID [--yes]` | Enable or disable a model |

All take `--json`. **Exit codes** keep the CLI's contract: `0` done; `1` a model failed — the
normalised error on stderr (`ModelRateLimited: … (retry after 12 s)`; with `--json`, `{"error":
{"kind", "alias", "provider", "model", "retryAfter", …}}`) — or a budget refused the request, or the
engine refused the SQL; `2` the assistant's configuration is wrong (nothing was sent to any model);
`3` the engine could not be reached. Notes, including which model answered and what it cost, go to
stderr; the answer to stdout.

## From Python

```python
from pravaha.api import EngineApi
from pravaha.assist import Assistant, FileConfigStore, ModelRouter

store = FileConfigStore()                        # the same file the CLI uses
router = ModelRouter.from_store(store)
watch = router.follow()                          # pick up changes another process stores
assistant = Assistant(router, EngineApi("https://engine:18080", token=token))

why = assistant.explain_refusal("PRV-2050", "SELECT customer, COUNT(*) FROM orders GROUP BY customer")
print(why.fix, why.rewrite, why.rewrite_verdict["valid"], why.answered_by["modelId"])

explained = assistant.explain_query(query_name="hourly_spend")
print(explained.summary, *explained.steps, sep="\n")

answer = router.ask("One sentence: what is event time?", profile="explain")   # any question
print(answer.text, answer.model_id, answer.usage.total_tokens)
watch.stop()
```

## Writing a provider plugin

A provider is a package, not a change to Pravaha. It is found by entry point, group
`pravaha.assist.providers`; the object named is called with one `ProviderSettings` (`endpoint`,
`api_key` resolved from `api_key_env`/`api_key_file`, `options`, `timeout_s`, `debug`, `id`) and must
answer something with `name`, `capabilities(model)` and `complete(request)` — the `ModelProvider`
protocol. Subclassing `BaseProvider` gets you the structured-output fallback, the repair turn,
latency and debug-only `raw` for free: implement `_send`, one round trip with its errors normalised.

A complete plugin for a hypothetical "Acme" HTTP API — two files:

`pyproject.toml`:

```toml
[project]
name = "acme-pravaha"
version = "1.0.0"
dependencies = ["pravaha>=0.2"]

[project.entry-points."pravaha.assist.providers"]
acme = "acme_pravaha:AcmeProvider"
```

`acme_pravaha.py`:

```python
"""Acme's models for the Pravaha assistant."""

from pravaha.assist import (
    BaseProvider, Capabilities, ChatRequest, ChatResponse, ModelRefused, ModelUnavailable, Usage,
    request_json,   # urllib, with timeouts, 429 and 5xx already normalised
)


def _classify(status, body):
    """Acme's own shapes, read before the shared mapping."""
    if isinstance(body, dict) and body.get("error", {}).get("code") == "policy":
        return ModelRefused("Acme declined on policy")
    if status == 402:
        return ModelUnavailable("Acme account out of credit", retryable=False)
    return None


class AcmeProvider(BaseProvider):
    name = "acme"
    requires_key = True                       # refused at build time without api_key_env/file
    default_capabilities = Capabilities(structured_output="none", max_context_tokens=32000)

    def _send(self, request: ChatRequest, *, native_schema: bool) -> ChatResponse:
        # native_schema is False because we claim structured_output "none": the base class has
        # already put the schema in request.system and will validate and repair the answer.
        answer = request_json(
            "POST", (self.settings.endpoint or "https://api.acme.example") + "/v1/generate",
            body={"model": request.model, "system": request.system,
                  "turns": [{"from": m.role, "text": m.content} for m in request.messages],
                  "limit": request.max_tokens},
            headers={"Authorization": f"Bearer {self.settings.api_key}"},
            timeout_s=request.timeout_s, provider=self.name, model=request.model,
            classify=_classify,
        )
        return ChatResponse(
            text=answer["output"], model=answer.get("model", request.model),
            usage=Usage(answer["tokens_in"], answer["tokens_out"]),
            finish_reason=answer.get("stop", "stop"), raw=answer,
        )

    def ping(self, model: str) -> None:            # optional: without it, check spends 5 tokens
        request_json("GET", (self.settings.endpoint or "https://api.acme.example") + "/v1/models",
                     headers={"Authorization": f"Bearer {self.settings.api_key}"},
                     timeout_s=self.settings.timeout_s, provider=self.name, model=model)
```

Install it beside the SDK (`pip install acme-pravaha`), then configure it like a built-in:

```json
{"providers": [{"id": "acme", "type": "acme", "api_key_env": "ACME_KEY"}],
 "models": [{"id": "rocket", "provider": "acme", "model": "rocket-1"}],
 "profiles": {"explain": ["rocket", "claude"]}}
```

`pravaha assist providers` lists it with its source distribution. A plugin **cannot take a built-in's
name** — `anthropic` from another package is ignored and reported — so installing a package cannot
silently redirect a configured provider. One that fails to import is reported, naming its package,
when it is used. A plugin that raises something other than the four errors is treated as
`ModelUnavailable` so a chain still falls through, but normalise your own: the conformance tests in
`sdk/python/tests/test_assist_providers.py` (a recorded answer per case, served by a local
`http.server`) are the contract to copy.

## Security

- **The engine is the judge and the security boundary, unchanged.** The assistant calls only
  `explain`, `validate` and `describe`, with your own token; nothing it does can register, drop,
  replace or read a view. A rewrite is a proposal, validated by the engine and labelled with its
  verdict. The engine has no model dependency, no outbound call and no new permission.
- **No data is sent by default.** What leaves the machine is what the task needs: the SQL, the
  engine's plan for it, what the engine says about a registered query (keys, retention, sink), the
  engine's refusal text, and excerpts of `CONTINUOUS_QUERIES.md`. **No rows are ever sent** — the
  assistant never reads a view. Deployments that may not send even schemas off-site configure only
  local providers (`ollama`, or `openai-compatible` on their own hardware).
- **Keys by environment or secret file only**, read when a provider is built; a key in the
  configuration is refused on load, on save and on every change, and never appears in an error, in
  `assist models` (which says only whether the variable is set) or in an audit record.
- **Prompt injection is contained by construction.** The model's output is only ever text: an
  explanation shown to you, or SQL the engine parses and a person runs. The prompts tell the model
  the SQL, the plan and the excerpts are data, not instructions, but the containment does not rely on
  it: the model has no tool, and nothing it says is executed.
- **`debug`** keeps each provider's raw answer on the response object. It is off by default; turn
  it on only where the answers may be kept.

## Developing

```bash
cd sdk/python
.venv/bin/python -m pytest -q tests/test_assist_*.py     # no network: fake provider, local HTTP
.venv/bin/python tools/build_dialect_card.py             # after editing docs/CONTINUOUS_QUERIES.md
```

The `fake` provider plays a script, never touching a network: each reply is text, or
`{"text", "usage", "finish_reason"}`, or `{"error": "unavailable" | "rate_limited" | "refused" |
"output", "message", "retry_after"}`; the last reply repeats. Configure it with the model option
`replies`, or `FakeProvider(replies=[...])` from Python; `ping: "fail"` makes `check` fail. No test
calls a real model API.
