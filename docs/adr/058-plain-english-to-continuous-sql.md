# ADR-058: plain English to a continuous query, through any model, with the engine as the judge

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; phases 1–3 built. Phase 1 — the `pravaha.assist` core in the Python SDK: the provider protocol and the `fake`, `anthropic`, `openai`, `openai-compatible` and `ollama` providers (standard library only, discovered by entry point); `ModelRouter` with fallback chains, per-request and per-user daily budgets and runtime reconfiguration; the JSON configuration store, its watcher and the `AssistAdmin` facade; the explain-a-query and explain-a-refusal tasks with versioned prompts and a dialect card generated from `CONTINUOUS_QUERIES.md`; `pravaha explain-sql`, `pravaha why` and `pravaha assist models\|providers\|check\|use\|enable\|disable`. Phase 2 — drafting (`Assistant.draft`, `pravaha ask`): a context built from the engine under the caller's credentials, the fixed draft schema, the engine as judge with up to three repair turns that may not change what the query reads, registration only on a person's confirmation; and the golden set generated from the case studies with `pravaha assist eval` (§2a records what the API exposes and what is approximated). Phase 3 — the console: Admin · AI models (providers, models, key references by name, Test, enable and disable, each profile's chain in fallback order, the default profile, budgets, usage per model and per person, recent changes), every change a CSRF-protected form through `AssistAdmin` against the version the page showed, applied to the console's one `ModelRouter` so the next request uses it with no restart; the Describe-it panel on the workbench and Explain on a query's page and beside every refusal code, run as the signed-in person; the assist audit events in the console's assist log. Phase 4 is not built. See [`docs/ASSIST.md`](../ASSIST.md) |
| Date | 2026-09-28 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-024 (the console reaches the engine only through its API), ADR-031 (authorization at the Pravaha layer), ADR-050 (tenancy), ADR-052 (the engine is the identity authority), ADR-053 (native code only where Java cannot), ADR-056 (queries on queries), ADR-057 (alerts) |

## Context

A continuous query is a small program with sharp edges. Its author has to know which streams exist
and what their columns are called, which column carries event time, that a `GROUP BY` over an
unbounded key needs a window or a retention (`PRV-2050`), that a stream–stream join needs a time
bound, that `MIN`/`MAX` behave differently under retraction, and which of the ~70 refusals in
`docs/CONTINUOUS_QUERIES.md` applies to what they just wrote. The case studies show the pattern:
the people with the question ("which stock lines are at their reorder point, and tell me when they
recover") are rarely the people who can write that SQL first time.

Language models are good at drafting SQL from a description and a schema, and bad at knowing what a
particular engine accepts. Pravaha already has the half they lack: an engine that validates a
statement, explains its plan, names every refusal with a code and a sentence, and fingerprints a
computation so two statements can be compared by meaning rather than by text.

The owner's constraints: assume access to LLMs, but depend on none — any model from any provider
must plug in, and several may be configured at once.

## Decision

**The engine stays model-free and remains the only judge.** A model drafts; the engine decides.
Nothing a model says is trusted until the engine has validated and explained it, and nothing is
registered without a person confirming it under their own credentials. The engine gains no model
dependency, no outbound network call and no new permission.

**The assistant lives in the Python SDK** as `pravaha.assist`, so the `pravaha` CLI and the console
share one implementation (the owner's rule that tools reuse the Python code). It talks to the engine
through `EngineApi` like everything else, and to models through a provider abstraction.

### 1. The provider abstraction

Three layers, each replaceable:

```text
Assistant  --uses-->  ModelRouter  --picks-->  ModelProvider (anthropic | openai | azure | bedrock
  (task logic)        (profiles, fallback,       | vertex | ollama | openai-compatible | fake | ...)
                       budgets, caching)
```

**`ModelProvider`** — the only interface a provider implements (a `typing.Protocol`):

```python
class ModelProvider(Protocol):
    name: str                                   # "anthropic", "openai", "ollama", ...
    def capabilities(self, model: str) -> Capabilities: ...
    def complete(self, request: ChatRequest) -> ChatResponse: ...
    # optional: def stream(self, request) -> Iterator[ChatChunk]
```

- `ChatRequest`: `model`, `system`, `messages` (role, content), `response_schema` (a JSON Schema the
  answer must satisfy, or `None`), `temperature`, `max_tokens`, `stop`, `timeout_s`, `metadata`
  (request id, tenant, user — for the provider's own logs where it takes them).
- `ChatResponse`: `text`, `parsed` (the object when a schema was asked for and satisfied), `model`
  (the one that actually answered), `usage` (input, output, cached tokens), `finish_reason`,
  `latency_ms`, `raw` (kept only in debug mode).
- `Capabilities`: `structured_output` (`json_schema` | `tool_call` | `none`), `max_context_tokens`,
  `max_output_tokens`, `supports_system`, `supports_streaming`, `supports_prompt_cache`.

A provider that cannot enforce a schema natively still satisfies `response_schema`: the base class
asks for JSON in text, validates it against the schema, and runs **one** repair turn with the
validation error; a second failure is a `ModelOutputError`, never a guess. Providers normalise their
own errors into four types — `ModelUnavailable` (retryable), `ModelRateLimited` (retryable, with the
provider's retry-after), `ModelRefused` (content policy), `ModelOutputError` — so the router can act
on them without knowing the provider.

**Discovery** is by Python entry points, group `pravaha.assist.providers`, so a new provider is a
package, not a change to Pravaha:

```toml
[project.entry-points."pravaha.assist.providers"]
acme = "acme_pravaha:AcmeProvider"
```

Built in: `anthropic`, `openai`, `azure-openai`, `bedrock`, `vertex` (Gemini), `ollama`,
`openai-compatible` (vLLM, LM Studio, llama.cpp server, any gateway speaking that API) and `fake`
(scripted answers, for tests and for the evaluation harness's controls). Each built-in is written
against the provider's HTTP API with the standard library, as `EngineApi` is, and a provider's
official SDK is used only when installed (`pip install "pravaha[anthropic]"`, `"pravaha[bedrock]"`),
so the SDK's base install gains no dependency.

**`ModelRouter`** — configuration, not code, decides which model answers which task:

```yaml
# ~/.config/pravaha/assist.yaml, or the console's application.yaml under assist:
profiles:
  draft:                       # writing SQL: the strongest model available
    chain: [claude-main, gpt-backup]
  explain:                     # SQL or a refusal into English: cheaper is fine
    chain: [local-llama, claude-main]
models:
  claude-main:  {provider: anthropic, model: claude-sonnet-5, api_key_env: ANTHROPIC_API_KEY}
  gpt-backup:   {provider: openai,    model: <model id>,        api_key_env: OPENAI_API_KEY}
  local-llama:  {provider: ollama,    model: llama3.1:70b,      endpoint: http://localhost:11434}
budgets:
  per_user_daily_tokens: 200000
  per_request_max_tokens: 8000
```

- **Fallback**: a chain is tried in order on `ModelUnavailable`/`ModelRateLimited`; a `ModelRefused`
  or `ModelOutputError` does not fall through silently — it is reported, with which model said it.
- **No secret in configuration**: keys are named by environment variable (`api_key_env`) or a secret
  file, never written in YAML — the rule `NoSecretsInConfigurationTest` already enforces for the
  engine, applied to the assistant's configuration too.
- **Budgets and rate limits** per user and per request, counted from `usage`; a request that would
  exceed one is refused before it is sent.
- **Caching**: the catalogue context (below) is large and stable, so it is placed first in the
  prompt and marked cacheable where the provider supports prompt caching; identical requests within
  a short window are answered from a local cache keyed by the catalogue version.

### 1a. Runtime configuration

Added when phase 1 was built, at the owner's requirement that several providers and models be
configured at once and that an administrator can switch between them while the console runs (the
admin screen itself is phase 3; the core it needs is built and tested now).

- **An immutable, validated snapshot.** `AssistConfig` holds the providers, models, profiles and
  their chains, budgets and which models are enabled, with a `version`, `changed_at` and
  `changed_by`. Validation is complete before anything is applied: unknown provider types,
  duplicate ids, unknown fields, a chain naming an unknown or disabled model, a default profile that
  does not exist, and — checked in the process that will use it — a key variable that is not set or
  a secret file others may read. Each is a named problem; nothing is applied.
- **Atomic swap.** `ModelRouter.reconfigure(config)` validates, then replaces the snapshot under a
  lock. A request reads the snapshot once when it starts, so requests in flight finish on the old
  configuration and new ones use the new; none sees half of each.
- **A store, shared between processes.** `AssistConfigStore` (load, save against the version the
  change was built from, watch) with a file implementation: JSON written to a temporary file with
  mode `0600` and renamed into place, and a watch that polls the file. A router that `follow`s the
  store applies what another process stores, and keeps out — and records — what does not validate
  in its own process. The store refuses any document holding a key, on load and on save.
- **An administration facade.** `AssistAdmin` lists providers (built-in and discovered) with their
  capabilities, adds, updates, removes, enables and disables providers and models, sets a profile's
  chain (its order is the fallback order), the default profile and the budgets, and pings a model.
  Each change is validated, persisted, applied through `reconfigure`, and answered as an audit
  record (who, what, before and after, the version) for the caller to log. Disabling a model a chain
  still names is refused, so what answers is always a decision someone made.
- **The format is JSON, not the YAML sketched in §1.** The file is written by programs as well as
  people and must round-trip exactly; JSON is the only format the standard library reads and writes
  on every supported Python. YAML would add PyYAML to an SDK with no dependencies; the standard
  library's TOML is read-only and 3.11+. A `.yaml` or `.toml` file is refused with directions. The
  fields are §1's, with providers named separately from models so that two models can share one
  provider's endpoint and key reference.
- **OpenAI through Chat Completions.** The one wire shape OpenAI and every compatible server speak,
  so `openai` and `openai-compatible` share one implementation; the Responses API adds nothing these
  tasks use.

### 2. The task: from a description to a registered query

```text
 describe ──► context ──► draft ──► engine validate/explain ──► repair (≤ 3) ──► present ──► confirm ──► register
 (person)    (catalogue,  (model)    (the judge: PRV codes,      (model, told     (SQL, plan,   (person,   (engine, as
              dialect,                plan, fingerprint)          the refusal)      guarantees,   explicit)   that person)
              examples)                                                             cost, doubts)
```

1. **Context**, built by the assistant from the engine, never from the model's memory:
   - the streams and views **this person may read** (`GET /api/v1/permissions`, `/streams`,
     `/queries`) — their columns, types, event-time column, lateness, keys, and the sinks they may
     write to; a stream they cannot read is never named to the model;
   - a **dialect card** generated from `docs/CONTINUOUS_QUERIES.md`: what is supported, what is
     refused and why (windows, bounds, join time bounds, `ROW_NUMBER` top-N, retraction rules), the
     `WITH (...)` options, and the query-on-query form of ADR-056;
   - **worked examples** from `examples/case-studies/` (description → SQL), chosen by similarity to
     the request;
   - no row data by default. Sample values can be enabled per deployment for columns an
     administrator marks safe, masked by the same row filters the person reads under.
2. **Draft**: the model answers a fixed JSON Schema — `name`, `sql`, `keys`, `options`
   (retention, index, sink, lane), `explanation`, `assumptions` (e.g. "I took `amount` to be in
   cents"), `questions` (what it could not decide) and `confidence`. If `questions` is not empty the
   assistant asks the person before going further.
3. **Judge**: the engine validates and explains the draft (`POST /api/v1/validate`, `/explain`). A
   refusal comes back as a PRV code, a sentence and a help link.
4. **Repair**: the refusal is given back to the model with the relevant section of the dialect card
   — up to three turns, each shown to the person. A draft still refused after three is presented as
   refused, with the engine's words; the assistant does not loosen the question to make it pass.
5. **Present**: the SQL, the model's explanation, **the engine's own plan explanation** (operators,
   state bound, window, lane placement it would get), the guarantees the chosen sources and sinks
   give, the assumptions and any question the model raised. When the fingerprint matches a query
   already running, the assistant says so and offers to reuse it instead (ADR-025).
6. **Confirm and register**: only a person, pressing Register or running `--register --yes`, under
   their own session or key. The model has no tool that registers, drops, replaces or reads data.

Three smaller tasks reuse the same machinery and are worth building first because they need no
confirmation step: **explain a query** (SQL → English), **explain a refusal** (a PRV code and the
statement → what to change), and **describe a view** (schema and plan → a sentence for the catalogue).

### 2a. What the engine's API exposes for the judge, recorded when phase 2 was built

- **No fingerprint for unregistered SQL.** `POST /api/v1/queries/explain` answers `level`, `plan`,
  `outputFields` and, with `format=graph`, `graph`; `validate` answers `valid`, `diagnostics` and
  `outputFields`. A fingerprint exists only for a registered query (`GET /api/v1/queries`,
  `/views/{name}`, a registration's answer). So "the same computation as a running query" (step 5)
  and "equal to the reference" (§4) compare the engine's physical plan text, whitespace-normalised,
  with the key and retention — the parts of a fingerprint the API shows. The plan text is a summary
  for people (the fingerprint hashes each operator's identity), so equal plans are strong evidence,
  not proof; the result says `match: "plan"`. `assist eval --run` registers both and compares the
  engine's fingerprints, then answers. An `explain` that returned the fingerprint would remove the
  approximation; the engine was not changed for it.
  **Since EXPLAINFP-1 it does:** `explain` given `keys` (and optionally `retention`, `sink`, `name`)
  answers `fingerprint`, the value a registration would get for the caller — computed by the
  registration's own preparation, so plan, row filters, keys, retention and tenant — or
  `fingerprintRefusal` with the code registration would give. The assistant compares that with each
  running query's fingerprint (`match: "fingerprint"`) and the evaluation compares the draft's with the
  reference's, and both fall back to plan text against an engine that answers no fingerprint.
- **Registration-time checks the API cannot be asked.** `validate` plans a `SELECT`; the key, index,
  sink and retention are checked only when a query is registered. The assistant checks them against
  what the engine answered (`outputFields`, `/api/v1/sinks`) and labels those verdicts
  `by: "assistant"`, never as the engine's.
- **Guarantees** are what `GET /api/v1/sinks` reports for the chosen sink (`guarantee`,
  `acceptsRetractions`); lane placement is not predicted.
- **"Never loosen the question"** is in the prompt and enforced: a repair must read the same streams
  and views as the first draft, or it is refused by the assistant and not sent to the engine.

### 3. Safety, security and cost

- **The engine is the security boundary, unchanged.** The assistant acts with the person's
  credentials; whatever it drafts is authorized by `SecurityPolicy` like any statement they typed. A
  model cannot widen what a person can read or register.
- **Prompt injection** is contained by construction: the model's output is only ever a *proposal*
  of text that the engine parses as SQL and a person confirms. Catalogue comments and descriptions
  are placed in the prompt as quoted data, never as instructions.
- **Data minimisation**: schemas and names leave the network; rows do not, unless an administrator
  opts columns in. Deployments that may not send schemas off-site configure only local providers
  (`ollama`, `openai-compatible` on their own hardware); the router refuses a remote provider for a
  tenant marked `assist.local_only`.
- **Audit**: every request is an audit event — who, which model and provider, tokens, the prompt's
  hash (not its text unless debug audit is on), the engine's verdict, and whether it was registered.
  The console's admin audit screen shows them beside the engine's own events.
- **Cost**: budgets per user and request, prompt caching of the catalogue, a cheap model for the
  explain tasks, and a daily usage view per user and per model.

### 4. Evaluation, so the choice of model is measured

A harness, `pravaha assist eval`, runs a golden set against any configured model:

- **Golden set**: each case study contributes descriptions paired with its reference SQL, plus
  descriptions that *should* be refused or should raise a question (an unbounded group, a join with
  no time bound, a stream that does not exist).
- **Scoring by meaning, not text**: a draft scores when the engine accepts it and either its
  **fingerprint equals** the reference's (the same computation, ADR-025) or, when the plan differs,
  running both on the case study's `data/sample/` gives the **same answer** (the `CaseStudyRunTest`
  machinery). Also recorded: repair turns needed, tokens, latency, and questions asked.
- The report compares models and providers on the same set, so "which model do we use" is a
  measured decision per deployment, and a provider change is a regression test away.

### 5. Surfaces

- **CLI**: `pravaha ask "stock lines at or below their reorder point"` prints the draft, the plan and
  the doubts; `--register` asks to confirm (or `--yes`); `pravaha explain-sql`, `pravaha why PRV-2050`.
- **Console**: a "Describe it" panel on the workbench — the conversation on the left, the SQL, the
  engine's plan and the guarantees on the right, Register disabled until the engine accepts; "Explain"
  buttons on query detail and on every refusal.
- **SDK**: `Assistant(engine_api, router).draft("...")` returns a `Draft` for applications that want
  their own UI.

## Alternatives considered

| Alternative | What goes wrong |
|---|---|
| Put the model call in the engine (Java) | The engine gains outbound network calls, secrets and a large dependency surface for a feature that needs none of its internals; ADR-024 already keeps presentation outside it, and a model outage would become an engine incident |
| Trust the model's SQL once it parses | Parsing is not planning: the refusals that matter (unbounded state, missing time bounds, retraction rules) are only found by the planner |
| Let the model register through a tool call | An injected instruction in a catalogue comment could register or drop a query under the person's credentials; confirmation by a person is the boundary |
| One provider, chosen now | Models change monthly and deployments differ (air-gapped, one cloud's marketplace only); the owner asked for any provider |
| A general agent framework (LangChain-style) as the abstraction | Four small interfaces are enough; a framework would bring dependencies and its own abstractions to keep up with |
| Fine-tune a model on Pravaha SQL | Premature: the engine's validation and repair loop plus retrieved examples may be enough, and the evaluation harness will say when they are not |
| Score drafts by comparing SQL text | Two correct queries rarely share text; the fingerprint and the sample-data run compare meaning |

## Consequences

- People who can describe the answer they want can get a correct continuous query without learning
  the dialect's refusals first, and every draft arrives with the engine's plan beside it.
- The engine is unchanged and remains the authority; the assistant can be disabled, removed or
  replaced without touching it.
- Any model plugs in as a package; the evaluation harness makes switching a measured decision.
- New operational surface: model keys, budgets and an assist audit trail to administer.
- Quality depends on the dialect card and examples staying current with the engine — they are
  generated from `CONTINUOUS_QUERIES.md` and the case studies for that reason, and the golden set
  fails when they drift.

## Phases

1. **Built.** `pravaha.assist` core: the provider protocol, `fake`, `anthropic`, `openai`,
   `openai-compatible`, `ollama`; the router with fallback and budgets; runtime configuration (§1a);
   the explain and why-refused tasks; CLI `pravaha explain-sql`, `pravaha why` and `pravaha assist`.
   Deferred from it: streaming (`stream` stays optional in the protocol; no built-in implements it),
   prompt caching and the short-window answer cache (§1), and "describe a view".
2. **Built.** Drafting with context, judge and repair; `pravaha ask`; the golden set and
   `pravaha assist eval` (§2a). Deferred from it: sample values for opted-in columns (phase 4), the
   short-window answer cache, and the assist audit events (phase 3).
3. **Built.** The console's Describe-it panel and Explain buttons; Admin · AI models, switching models
   at runtime; the assist audit events and usage view. The audit events are kept in the console's
   own assist log (JSON Lines beside the configuration) and shown on Admin · AI models; the engine's
   audit API takes no client events, so they are not merged into Admin · Audit trail. A draft is held
   by the console between drafting and registering, so a registration sends an id, never SQL.
   Deferred from it: a short-window answer cache, and "describe a view".
4. The remaining providers (`azure-openai`, `bedrock`, `vertex`), local-only tenants, sample values
   for opted-in columns.
