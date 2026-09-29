# The assistant: plain English to and from continuous SQL, with the engine as the judge

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../LICENSE`](../LICENSE).

The assistant drafts a continuous query from a plain-English description, explains a continuous
query in plain English, and explains a refusal — what `PRV-2050` means for *this* statement and
what to change. It asks a language model you configure,
any provider, several at once, and it never trusts what the model says on its own: the model is
given the engine's own plan and the engine's own words, and any SQL the model proposes goes back to
the engine before you are told it works. The design is [ADR-058](adr/058-plain-english-to-continuous-sql.md).

**What is built (phases 1 to 3):** the `pravaha.assist` package in the Python SDK — the provider
protocol, five built-in providers, the router with fallback chains, budgets and runtime
reconfiguration, the configuration store and its administration facade; the explain tasks
`pravaha explain-sql` and `pravaha why`; **drafting a query from a description**, `pravaha ask`,
judged by the engine with up to three repair turns and registered only when a person confirms it;
and the **evaluation harness**, `pravaha assist eval`, over a golden set built from the case
studies; and **in the console** (phase 3), the *Describe it* panel on the workbench, *Explain* on a
query's page and beside every refusal code, and **Admin · AI models**, where an administrator
configures several providers and models and switches between them while the console runs. **Not
yet:** the `azure-openai`, `bedrock` and `vertex` providers (phase 4).

- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Providers](#providers)
- [How a model is chosen](#how-a-model-is-chosen)
- [Changing the configuration while it runs](#changing-the-configuration-while-it-runs)
- [The two tasks](#the-two-tasks)
- [Drafting a query: `pravaha ask`](#drafting-a-query-pravaha-ask)
- [Measuring a model: `pravaha assist eval`](#measuring-a-model-pravaha-assist-eval)
- [In the console](#in-the-console)
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
     "profiles": {"explain": ["llama", "claude"], "draft": ["claude", "llama"]},
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
   pravaha ask "orders per customer per minute"          # drafts; registers nothing
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
| `profiles` | Profile → an ordered list of model ids: the fallback chain. The explain tasks ask for `explain`, `ask` and `assist eval` for `draft` (the strongest model you have); a profile not configured falls back to `default_profile` |
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
a shell can delete it); spend is accounted in the console's assist log ([In the console](#in-the-console)).

## Changing the configuration while it runs

A running process — the console — must be able to switch models without a restart, and
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

## Drafting a query: `pravaha ask`

```bash
pravaha ask "stock lines at or below their reorder point, and tell me when they recover"
pravaha ask "orders per customer per minute" --name orders_per_minute --register   # asks first
pravaha ask "..." --register --yes --url grpc://engine:19090                      # no prompt
pravaha ask "..." --json
```

```text
 describe ──► context ──► draft ──► engine validate/explain ──► repair (≤ 3) ──► present ──► confirm ──► register
```

**1. The context comes from the engine, never from the model's memory.** Under your own token the
assistant asks `GET /api/v1/me/permissions`, `/api/v1/streams`, `/api/v1/queries`,
`/api/v1/views/{name}` and `/api/v1/sinks` — only what the API answers you — and gives the model:

- the **streams you may read** (the engine's listing, intersected with the streams your permissions
  name), each with its columns and types, its event-time column and out-of-orderness. A stream you
  cannot read is never named to the model;
- the **views you may read** — registered queries a continuous query may follow (ADR-056) — with
  their key, retention and, for the twenty most relevant, their columns; a view the engine refuses to
  describe is left out entirely;
- the **sinks you may see**, with the guarantee each gives (`EXACTLY_ONCE`, `EFFECTIVELY_ONCE`,
  `AT_LEAST_ONCE`) and whether it accepts retractions;
- the **dialect**: the guide's error table, then its sections on aggregation, unwindowed `GROUP BY`,
  when a window emits, joins, queries on queries and what to do when refused — from the dialect card;
- **two to four worked examples** from `examples/case-studies/`, chosen by similarity to your
  description (and preferring examples over streams you can read here).

**No row is ever read.** The context is ordered deterministically and held to a **size budget**:
24,000 characters, or less when a `per_request_max_tokens` budget is configured (what it leaves after
the answer, the prompt's own words and a repair turn — 12,000 characters under a budget of 8,000
tokens). What is least relevant to the description is left out first, and what was left out is
named — in the prompt ("left out for size; ask if you need one") and on the result
(`context.omitted`).

**2. The draft is a fixed JSON Schema** (`draft_query@v1`): `name`, `sql` (one `SELECT`), `keys`
(output column names), `options` (`retention`, `index`, `sink`, `lane`), `explanation`,
`assumptions` ("I took `amount` to be in cents"), `questions` and `confidence`. **If the model has
questions, they are printed and the engine is asked nothing**: answer them in a new description.

**3. The engine is the judge.** The `SELECT` goes to `POST /api/v1/queries/validate`, and when it is
valid to `/explain` for the physical plan. Before a draft is called accepted the assistant also makes
the checks the engine only makes at registration and the API cannot be asked — that the key and the
index name columns the engine says the `SELECT` produces (the engine's `PRV-2071` and `PRV-2074`),
that the sink is one you may see, that the retention is a duration the engine reads — and labels them
`by: "assistant"`, never as the engine's words.

**4. Repair: at most three turns, and never a looser question.** A refusal goes back to the model with
the `PRV` code, the engine's sentence and the dialect card's sections about that code. Each repair
turn carries the description and catalogue, the model's last answer and what was wrong with it — not
every earlier turn — so each costs about the same. The prompt tells the model never to loosen the
question, and the assistant checks it: **a repair must read the same streams and views as the first
draft**. One that does not is refused by the assistant ("that is a different question") and never
sent to the engine. A draft still refused after the last turn is presented as refused, in the
engine's own words, exit `1`. Every turn — its SQL, its verdict, whether it kept the question, the
model and its tokens — is on the result.

**5. Present.** The statement (`CREATE CONTINUOUS QUERY … KEYED BY … AS SELECT …`, ready to paste),
the model's explanation, **the engine's own plan**, the sink's guarantee, the assumptions and
questions, the turns, and which model answered at what cost. When a running query you may see has
the same plan, key and retention, you are told and offered to read it instead: the engine would
share one computation (ADR-025).

**6. Registration is yours.** `--register` registers only a draft the engine accepted, and only
after you confirm — at a terminal it asks; `--yes` confirms in a script; with neither and no terminal
it registers nothing, says so, and exits `0`. Registration is the ordinary client call under your own
credentials (`Client.register`, or the draft's `CREATE` statement through `Client.query` when it
declares an index or a lane, which `register` cannot carry), so it is authorized exactly like a
statement you typed; it speaks Flight, so it needs `--url`. The model has no tool: nothing it says is
executed.

**The same computation as a running query, by the engine's fingerprint.** For an accepted draft the
assistant asks `POST /api/v1/queries/explain` with the draft's key (as output ordinals), retention and
sink, and the engine answers the **fingerprint a registration would get** for you (EXPLAINFP-1):
computed by the registration's own code — the plan, your row filters, the key, the retention and your
tenant — and given in the short form `GET /api/v1/queries` lists. A running query with that
fingerprint is the same computation (`match: "fingerprint"`, `sameComputation: true`); one with the
same plan, key and retention but another fingerprint is someone else's computation (their row
filters or tenant differ), and is said to be. The draft carries it as `fingerprint`. **An engine
older than EXPLAINFP-1** answers no fingerprint, and the assistant falls back to comparing the
engine's physical plan text for the draft with its plan for each running query that reads the same
inputs, whitespace-normalised, plus the key and retention — labelled `match: "plan"`: two plans that
print alike are very likely, not certainly, one computation, and registering says for sure. A
fingerprint says what registration would share, not that registration will be accepted: key and
index refusals (`PRV-2071`/`PRV-2074`) still come at registration (VALIDATEREG-1). **Guarantees** are
what `GET /api/v1/sinks` reports for the chosen sink on this node; lane placement is not predicted.

**Exit codes:** `0` accepted, or the model asked questions; `1` refused after the repair turns (or,
with `--register`, questions instead of a draft), a model failure, a budget; `2` configuration; `3`
the engine could not be reached.

## Measuring a model: `pravaha assist eval`

```bash
pravaha assist eval                                   # the default profile's chain, every case
pravaha assist eval --model claude --json > claude.json
pravaha assist eval --model llama --case retail-inventory-mysql,negative --limit 10
pravaha assist eval --run --url grpc://localhost:19090 --prefix assist_eval_   # a test node only
```

**The golden set** (`pravaha/assist/resources/golden-set.json`) is generated by
`sdk/python/tools/build_examples.py`, with the worked examples, from each case study's README and
`sql/*-continuous-*.sql`: one **reference** case per continuous query — the README's heading for it
and the paragraph that explains it as the description, the file as the reference SQL, and
`schema/views.properties` for its key — and three **negative** cases that must be refused or must
raise a question:

| Case | What it asks for | Why it must not be accepted |
|---|---|---|
| `negative/unbounded-group-by` | each card's approved authorisations, forever, no window | an unwindowed `GROUP BY` over a stream is `PRV-2050`; a window answers a different question |
| `negative/join-without-time-bound` | every click matched to its impression however late | a stream–stream join keeps bounded state; a time bound changes what was asked |
| `negative/stream-does-not-exist` | refunds per region per hour, from a `refunds` stream | there is none; answering from `order_line` is a different question |

**Scoring by meaning, not text.** Each case is drafted exactly as `pravaha ask` would, with the case's
own worked example taken out of the prompt (it would be the answer). A **reference** case passes when
the engine accepts the draft, the draft reads the same streams as the reference, and it is the same
computation: by **fingerprint** (the fingerprint `explain` answers for the draft and for the
reference, neither registered, equal — EXPLAINFP-1), else by **plan** (the engine's physical plan for
both equal, and the same key: the fallback for an engine that answers no fingerprint, and the rule
for a draft that differs from the reference only in retention), or with `--run` by **fingerprint** of
both registered or by **answer** (below). A **negative** case passes when the draft is refused
or asks a question; an accepted one is a model that loosened the question, and is reported as one.
A case whose streams this engine does not have is **skipped**, not failed.

Recorded per case: the outcome, how it was shown equal, whether the inputs and key match, repair
turns, tokens, latency and the model that answered; the report table ends with a summary — passed
of scored, references accepted and equal, negatives caught, tokens, repair turns, time and models.
`--json` gives the same (`--full` adds every draft). Exit `0` when every scored case passed, `1`
otherwise, so a provider change is a regression test away. Compare models by running it once per
`--model`.

**The engine it runs against** needs the case studies' streams: start a node with a study's
`conf/application.yaml` (examples/case-studies/SETUP.md), or several studies' streams in one
configuration. Without `--run` nothing is registered — only the catalogue, `validate` and `explain`
are called — so it is safe against any engine you may read, but only cases whose streams it has are
scored.

**`--run`** also registers each accepted draft whose plan differs from its reference, and the
reference, under `--prefix` (`assist_eval_ref_<view>` and `assist_eval_draft_<view>`), compares the
fingerprints the engine answers, and when they differ waits `--settle` seconds and reads both views,
comparing their rows as multisets — the `CaseStudyRunTest` idea, on whatever sample data the node is
fed. Both are dropped afterwards, whatever happened. It needs `--url` (Flight) and the register
permission, and it is **for a test node started with the case studies' data, never a production
engine**: it registers computations there, however briefly. With no data flowing both views stay
empty and the case says so rather than passing.

## In the console

Phase 3 puts the assistant in the console (`console/`), which is the SDK's first consumer here too:
`core/assist.py` holds everything, `routes/assist_routes.py` the screens, and nothing in the engine
changes.

**One router, following the file.** The console process builds one `ModelRouter` from a
`FileConfigStore` — `assist.config` in `console/config/application.yaml` (`PRAVAHA_ASSIST_CONFIG`;
empty is the SDK's own default, so the console and `pravaha assist` share one file) — and calls
`router.follow(store)`, polling every `assist.watch_seconds`. A stored configuration that does not
validate when the console starts is not applied; the console starts with no model and says why.

**Admin · AI models** (`/admin/ai-models`) is `AssistAdmin` behind forms, for a person the engine
gives the `admin` role (asked of the engine, `auth/me`, on every request — ADR-052). Providers
(configured, and every type this process can build, with capabilities), models (key reference by
name, never value; enabled; which profiles name them; **Test** → `AssistAdmin.test_model`), each
profile's chain with its order as the fallback order, the default profile, the budgets, usage, and
recent changes. Each change is a CSRF-protected `POST` carrying the configuration version the page
was drawn from:

1. a version that is no longer the stored one is refused as a `ConfigConflict`, said as such — who
   changed it and when — and nothing is overwritten;
2. `AssistAdmin` builds the change, validates it with the console's own environment, saves it
   against that version, and calls `router.reconfigure` — so **the very next assist request uses
   it**, with no restart (`console/tests/test_assist.py` switches a chain through the form and sees
   the next request answered by the other model; and has a second process's `set_chain` picked up
   by the watch);
3. the `AuditRecord` — who, what, before and after, the version — is appended to the console's
   **assist log** (`assist.log`, JSON Lines, `0600`, beside the configuration by default) and shown
   as *Recent changes*.

The log also has one line per assist request — who, the task, the model and provider, the
configuration version, tokens, a hash of what was asked (never its words), the engine's verdict and
whether it was registered — which is where the usage view's request counts come from. Its token
counts come from the router's ledger (`assist.usage`, the SDK's `assist-usage.json` beside the
configuration by default), charged to the name the engine signed each person in with.

**The tasks run as the person.** The console hands `Assistant` an adapter over its one engine
adapter (the same one every screen uses), so validate, explain, the catalogue's listings and the
registration carry the signed-in person's engine session, and the engine authorises and audits each
as theirs. A draft is **held by the console** between drafting and registering, per person, for an
hour: the browser's Register sends the draft's id and a confirmation, never SQL, so what is
registered is exactly what the engine judged, and `Assistant.register(confirmed=True)` still refuses
a draft the engine did not accept.

| Surface | Where | Calls |
|---|---|---|
| Describe it | the workbench | `Assistant.draft` (profile `draft`); questions answered in place and drafted again; *Copy to editor*; *Register* → `Assistant.register(confirmed=True)` |
| Explain this query | a query's page | `Assistant.explain_query(query_name=...)` (profile `explain`) |
| Explain | beside a refusal code: the workbench (its refusal and its diagnostics), a query's page, a refused draft | `Assistant.explain_refusal(code, sql)` |

Every surface is a server-rendered form that works with scripting off; with scripting,
`web/static/app/assist.js` posts the same form to `/api/v1/assist/{draft,register,explain-query,explain-refusal}`
and puts the fragment the server rendered in place, in a live region. Budgets apply to each; a
refusal of one is shown before anything is sent. With no model configured or none enabled, each
surface shows one empty state — linking an administrator to Admin · AI models, and telling anyone
else to ask one.

## Commands

| Command | What it does |
|---|---|
| `ask "<description>" [--name N] [--repairs 0-3] [--register [--yes]] [--show-context] [--profile P] [--model ID]` | Draft a continuous query, judged by the engine; `--register` registers it once you confirm (needs `--url`). Profile `draft` by default |
| `explain-sql (--sql S \| --sql-file F \| --query NAME) [--level physical\|logical] [--show-plan] [--profile P] [--model ID]` | A query in plain English, grounded in the engine's plan |
| `why PRV-nnnn [--sql S \| --sql-file F] [--no-check] [--profile P] [--model ID]` | What the refusal means and what to change; `--no-check` skips validating a proposed rewrite |
| `assist models` | Every configured model: its provider, key variable (set or not — never its value), enabled, and the chains naming it (`explain#1*`: first in `explain`, the default profile) |
| `assist providers` | Every provider type, built-in and installed by entry point, with what it claims |
| `assist check [--model ID,ID]` | Ping each enabled model as cheaply as its API allows; exit `1` if any failed |
| `assist use PROFILE ID[,FALLBACK...] [--default] [--yes]` | Set a profile's chain |
| `assist enable ID [--yes]`, `assist disable ID [--yes]` | Enable or disable a model |
| `assist eval [--profile P] [--model ID] [--limit N] [--case ID,...] [--run [--prefix P] [--settle S]] [--full]` | Score a model on the golden set; exit `1` if a scored case failed |

All take `--json`. **Exit codes** keep the CLI's contract: `0` done; `1` a model failed — the
normalised error on stderr (`ModelRateLimited: … (retry after 12 s)`; with `--json`, `{"error":
{"kind", "alias", "provider", "model", "retryAfter", …}}`) — or a budget refused the request, or the
engine refused the SQL (for `ask`, still refused after its repair turns), or an evaluation case
failed; `2` the assistant's configuration is wrong (nothing was sent to any model);
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

draft = assistant.draft("orders per customer per minute")    # nothing is registered
print(draft.status, draft.verdict.words())                   # accepted | refused | questions
print(draft.statement(), draft.plan, draft.assumptions, draft.questions, sep="\n")
for turn in draft.turns:
    print(turn.number, turn.kind, turn.verdict.words(), turn.answered_by["modelId"], turn.tokens)
if draft.accepted and input("register? ") == "y":             # your application's confirmation
    from pravaha import connect
    with connect("grpc://engine:19090", token=token) as client:
        print(assistant.register(draft, confirmed=True, client=client))

from pravaha.assist import Evaluator
report = Evaluator(assistant, model="claude").run(limit=5)
print(report.summary())
watch.stop()
```

`Assistant.register` raises `RegistrationRefused` — having sent nothing — without `confirmed=True`
or for a draft the engine did not accept. `ContextBuilder(api).build(description)` gives the
context on its own (what the model would be told, and what was left out).

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
  `explain`, `validate`, `describe` and the catalogue's listings (streams, queries, views, sinks,
  your permissions), with your own token; nothing it does on its own can register, drop, replace or
  read a view. A rewrite or a draft is a proposal, validated by the engine and labelled with its
  verdict. The one call that changes anything is `Assistant.register` / `ask --register`, which
  needs your confirmation and an accepted draft and goes through the ordinary client under your
  credentials, authorized like any statement you typed (`assist eval --run` registers too, under a
  prefix, and drops what it registered — on a test node). The engine has no model dependency, no
  outbound call and no new permission.
- **No data is sent by default.** What leaves the machine is what the task needs: the SQL, the
  engine's plan for it, what the engine says about a registered query (keys, retention, sink), the
  engine's refusal text, excerpts of `CONTINUOUS_QUERIES.md`, and — for `ask` — the names, columns
  and types of the streams, views and sinks **you may read**, and worked examples from the case
  studies. A stream you may not read is never named. **No rows are ever sent** — the assistant never
  reads a view (only `assist eval --run` does, on a test node, and it sends no rows to a model).
  Deployments that may not send even schemas off-site configure only local providers (`ollama`, or
  `openai-compatible` on their own hardware).
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
.venv/bin/python tools/build_examples.py                 # after editing a case study
```

The `fake` provider plays a script, never touching a network: each reply is text, or
`{"text", "usage", "finish_reason"}`, or `{"error": "unavailable" | "rate_limited" | "refused" |
"output", "message", "retry_after"}`; the last reply repeats. Configure it with the model option
`replies`, or `FakeProvider(replies=[...])` from Python; `ping: "fail"` makes `check` fail. No test
calls a real model API.

Drafting and evaluation are tested against `tests/engine_support.py`, a stand-in for the engine's
HTTP API on 127.0.0.1 that answers the catalogue, permissions, `validate` and `explain` by rule, and
a stand-in client for registration (`test_assist_drafting.py`, `test_assist_eval.py`). The worked
examples and the golden set (`resources/examples.json`, `resources/golden-set.json`) are generated
from `examples/case-studies/` and a test fails when a study changes and they were not rebuilt; the
negative cases are written in `tools/build_examples.py`.
