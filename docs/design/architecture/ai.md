# The AI layer: one interface, one file per provider

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../../LICENSE`](../../../LICENSE).

The decision is [ADR-063](../adr/063-the-ai-layer.md). **The design is Drishti's** — its `drishti-ai`
module and its console's `core/ai` — ported with the same names, the same file format and the same
rules, so a provider file written for one reads the same in the other. Where Pravaha differs it says
so, in [§10](#10-where-pravaha-differs-from-drishti). Grounding and safety follow Drishti's BI AI
guide: a model's output is a proposal, the engine judges, nothing a model says is executed.

- [1. The pieces](#1-the-pieces)
- [2. Provider files](#2-provider-files)
- [3. The types shipped](#3-the-types-shipped)
- [4. Add a provider in 30 minutes](#4-add-a-provider-in-30-minutes)
- [5. Policy, masking and audit](#5-policy-masking-and-audit)
- [6. REST](#6-rest)
- [7. Python: the SDK, the CLI, the assistant and the console](#7-python-the-sdk-the-cli-the-assistant-and-the-console)
- [8. The second layer: OpenAI-shaped models](#8-the-second-layer-openai-shaped-models)
- [9. Tests](#9-tests)
- [10. Where Pravaha differs from Drishti](#10-where-pravaha-differs-from-drishti)
- [11. The features on the layer](#11-the-features-on-the-layer)

---

## 1. The pieces

```text
feature ──► LlmGateway ──► LlmPolicy.before ──► LlmRegistry ──► LlmProvider ──► LlmModel ──► vendor HTTP
  (CallContext:          (roles, limits,        (provider/model,   (one file)    (chat /        (JDK HttpClient /
   user, roles,           masking)               health, reload)                  embeddings)    urllib)
   feature)          ◄── LlmPolicy.after ◄──── one audit row per call: who, feature, model, sizes, tokens, cost, outcome
```

| Type (`com.ash.messaging.pravaha.ai`) | What it is |
|---|---|
| `LlmProvider`, `ProviderFactory` | One configured provider, built by the factory of its `type`. A factory is found by `ServiceLoader` (`META-INF/services/com.ash.messaging.pravaha.ai.ProviderFactory`) or handed to `ProviderFactories.discover` |
| `LlmModel`, `EmbeddingModel`, `ModelInfo` | A model by its **logical** name; `ModelInfo` carries the vendor id, the kind, the capabilities and the prices |
| `ChatRequest`, `ChatResponse`, `ChatMessage`, `ToolSpec`, `ToolCall`, `Usage` | The call and its answer, independent of any vendor. `Usage` is tokens and cost |
| `Capability` | `chat`, `streaming`, `tool-calls`, `embeddings`, `json-mode`; the gateway refuses tools or JSON mode a model lacks |
| `ProviderConfig`, `ProviderConfigLoader`, `LlmCatalog` | One file, parsed; the folder's loader; the presets and model catalogue (`ai/llm_catalog.json`) |
| `LlmRegistry`, `RetryBoard` | Providers and models by `provider/model`; health; `reload()`; the schedule a provider that could not be built is tried again on |
| `LlmPolicy`, `DefaultLlmPolicy`, `PolicySettings`, `PromptMasker` | The hooks around every call: roles per feature, per-minute / per-day / cost-per-day limits, masking, the audit row |
| **`LlmGateway`** | **The one door.** `gateway.chat(new CallContext(user, roles, "<feature>"), ChatRequest.of(...))` |
| `LlmException`, `AiErrors` | A failure: `CONFIG`, `NOT_FOUND`, `UNSUPPORTED`, `POLICY`, `RATE_LIMITED`, `TIMEOUT`, `FAILED`, each with its `PRV` code. Its message never carries a key, a prompt or a vendor's error body |

`pravaha-ai` depends on `pravaha-api` and `pravaha-common` only (and Jackson, SnakeYAML, SLF4J);
nothing in the engine depends on it, and only the server wires it (`AiConfiguration`). Both halves
are `ArchitectureRulesTest.aiLayerDependsOneWay`. **The engine core stays model-free** — ADR-058's
promise, now a machine-checked rule rather than a habit.

**Off by default.** `pravaha.ai.enabled` (`PRAVAHA_AI_ENABLED`) is `false`: no provider file is read,
no model is called, and every call — the REST endpoints, the gateway, `LLMFactory` — answers
`PRV-1080`.

## 2. Provider files

One YAML file per provider in `pravaha.ai.dir` (`$PRAVAHA_HOME/conf/ai` under a home, `config/ai`
otherwise). The file's name is the provider's name, the first half of `provider/model`:

```yaml
# conf/ai/openai.yaml
type: openai
enabled: true
api-key: ${OPENAI_API_KEY}        # an ${ENV_NAME} placeholder only; a literal key is refused
timeout: 60s
retries: 1                        # extra attempts after a 429 or a 5xx
default-model: fast
models:
  - name: fast                    # the logical name features use
    id: gpt-4o-mini               # what the vendor calls it
    max-tokens: 400
    capabilities: [chat, tool-calls, json-mode]
    cost-per-1k-input: 0.00015
    cost-per-1k-output: 0.0006
```

- **Keys only from the environment.** `api-key` — and every other secret: `aws-secret-access-key`,
  `aws-session-token`, `client-secret`, `proxy.password`, the TLS store passwords, a credential header
  (`header.Authorization`) — must be `${NAME}` or `${NAME:-fallback}`. **A literal is refused when the
  file is read**, and the refusal names the setting, never the value. The key is resolved at load and
  never appears in `ProviderConfig.toString()`, an admin answer or an audit row.
- **A bad file is reported and skipped**; the others load. `GET /api/v1/admin/ai` lists it under
  `fileErrors` with the reason. A `*.yaml.example` is never read: Pravaha ships one per type in
  `config/ai/` (and `conf/ai/` in a distribution), **every one with `enabled: false`**.
- **Hot reload.** The server looks at the folder every `pravaha.ai.reload-interval` (5s); an added,
  changed or removed file is picked up without a restart, and a call in flight finishes on the
  provider it started on.
- **A provider that cannot be built is retried** on a schedule (5s doubling to 5 minutes); a mistake
  only an edit can mend (a literal key, an unknown type, a missing base URL) waits for the file to
  change. Health says `ERROR` with the reason meanwhile.
- **TLS** is the connectors' own vocabulary and code (`PluginTls`, [CONNECTOR_TLS.md](../../guides/CONNECTOR_TLS.md)):
  `tls.ca`, `tls.certificate` + `tls.key`, `tls.truststore`, `tls.keystore`, their passwords as
  `${ENV}`; an unknown `tls.` key is refused rather than ignored. `tls.verify-hostname: false` is
  refused: the JDK's client always checks the name. **Proxy**: `proxy.url` (an https target is
  tunnelled); proxy authentication is not built.
- **Presets fill the rest.** A preset type (`groq`, `mistral`, `gemini`, `bedrock`, ...) knows its
  base URL, the variable its key is read from (`GROQ_API_KEY`) and a model catalogue; a file model
  of the same name wins, and `catalogue: false` keeps only the file's.

## 3. The types shipped

Every type speaks plain HTTP on the JDK client — **no vendor SDK and no Spring** (Drishti used Spring
AI for `openai` and the AWS SDK for `bedrock`; [§10](#10-where-pravaha-differs-from-drishti)).

| Type | API | Notes |
|---|---|---|
| `mock` | none | Deterministic: regex `rules` → `reply` / `tool` + `tool-args` / `error` / `latency-ms`; `reply`, `fail`, `fail-every`, `dimensions`. Usage is characters / 4, so budgets and audit can be exercised |
| `openai` | Chat Completions, `/v1/chat/completions`, `/v1/embeddings` | `base-url` https://api.openai.com |
| `openai_compatible`, `groq`, `together`, `fireworks`, `deepseek`, `xai`, `openrouter`, `perplexity`, `huggingface`, `vllm`, `lmstudio`, `mistral` | Chat Completions, `/chat/completions` | Presets; `max-tokens-param`, `live-models`, `models-path` |
| `azure_openai` | Chat Completions, Azure's addressing | `api-style` v1 or deployments, `deployments.<id>: <name>`, `auth` api_key / bearer / **entra** (client secret or workload identity; managed identity is not built) |
| `anthropic` | Messages, `/v1/messages` | `anthropic-version` |
| `gemini` | `:generateContent`, `:batchEmbedContents` | AI Studio key; `schema-mode`; Vertex is not built |
| `cohere` | v2 `/v2/chat`, `/v2/embed` | |
| `ollama` | `/api/chat`, `/api/embed`, `/api/tags` | No key; `options.*`, `keep-alive`, `num-ctx`, `think` |
| `bedrock` | Converse `/model/{id}/converse`, InvokeModel, `/foundation-models` | **SigV4 signed by hand** (`SigV4`, checked against AWS's own test vector). Keys from the file's `${ENV}` or `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`; profiles, SSO and instance roles are not read |
| `server` | the engine's `POST /api/v1/ai/chat` | **Client side only** (the Python SDK, the CLI, the console): the recommended client provider, so keys stay in the engine |

Errors map the same way for every HTTP type: **429 is `RATE_LIMITED`**, a timeout `TIMEOUT`, any
other status `FAILED` with a hint (401/403 the key, 404 the path or model) and **never the body**,
which can echo the prompt. 429 and 5xx are retried `retries` times.

## 4. Add a provider in 30 minutes

1. **Write the class** (10 minutes). Extend `HttpProvider` (it reads the model list from the file and
   gives you `JsonHttp`: TLS, proxy, retries, the error mapping) and implement `chat`:

   ```java
   public final class AcmeProvider extends HttpProvider {
       AcmeProvider(ProviderConfig c) {
           super(c, c.baseUrl().isEmpty() ? "https://api.acme.example" : c.baseUrl(), Set.of(Capability.JSON_MODE));
       }

       @Override
       protected ChatResponse chat(ModelInfo info, ChatRequest r) {
           ObjectNode body = JsonHttp.JSON.createObjectNode().put("model", info.id()).put("limit", maxTokens(r, info, 1024));
           body.put("system", systemText(r));
           ArrayNode turns = body.putArray("turns");
           turns(r).forEach(m -> turns.addObject().put("from", assistant(m) ? "bot" : "user").put("text", m.content()));
           JsonNode j = http.call("POST", http.base() + "/v1/generate", body,
                   Map.of("Authorization", "Bearer " + config.apiKey()), timeout(r));
           int in = j.path("tokens_in").asInt(), out = j.path("tokens_out").asInt();
           return new ChatResponse(j.path("output").asText(), List.of(), info.id(), "stop", new Usage(in, out, info.cost(in, out)));
       }

       public static final class Factory implements ProviderFactory {
           public String type() { return "acme"; }
           public LlmProvider create(ProviderConfig c) { return new AcmeProvider(c); }
       }
   }
   ```
2. **Register it** (1 minute): list `com.example.AcmeProvider$Factory` in
   `META-INF/services/com.ash.messaging.pravaha.ai.ProviderFactory`; a jar in `$PRAVAHA_HOME/plugins`
   is on the server's class path.
3. **Write the file** (2 minutes): `conf/ai/acme.yaml` with `type: acme`, `base-url`,
   `api-key: ${ACME_KEY}` and the models. Anything else you need goes in the file under your own keys;
   read it with `config.setting("key", default)` (nested maps are flat: `a.b.c`, lists `a.0.b`).
4. **Test it** (15 minutes) against a loopback fake of your API, the way `LlmFakes` does for every
   shipped type (echo, a tool call, 500, 429, a slow answer), and the `mock` provider for everything
   above you. Then `GET /api/v1/admin/ai` shows your provider and `POST /api/v1/admin/ai/test` sends
   one real call.

Python: the same shape (`pravaha.ai.providers.http_base.JsonHttpProvider`, a `complete(info, req)`),
then `pravaha.ai.providers.register("acme", factory)`.

## 5. Policy, masking and audit

`pravaha.ai.policy.*` (all `0` means no limit):

| Setting | What it does |
|---|---|
| `features.<feature>.roles` | Roles that may use a feature; a feature not listed is open to every signed-in caller. The REST endpoint's callers are `api:<feature>` and the console's `api:console:<feature>`, so neither can pose as a built-in feature. A refusal is `PRV-1084` |
| `per-user-per-minute`, `features.<feature>.per-user-per-minute` | Calls per user per feature per minute (`PRV-1085`, "try again in N seconds") |
| `per-user-per-day` | Calls per user per day, UTC |
| `cost-per-user-per-day` | Money per user per day, in the unit the files' `cost-per-1k-*` are written in |
| `mask-redacted`, `redact`, `mask-patterns` | The value after any field named in `redact` (`name: v`, `name=v`, `"name":"v"`) and anything matching a pattern becomes `•••` before the prompt leaves the node |
| `audit` | One audit event per call |

**The audit event** goes into the node's own audit trail (`AuditSink`; `GET /api/v1/audit?action=ai.call`):
action `ai.call`, the caller, target `provider/model`, `ALLOW` (or `DENY` for a policy or limit
refusal), the outcome (`ok`, `failed`, `timeout`, `rate_limited`, `policy`, ...) and a detail of
`feature=`, `promptChars=`, `answerChars=`, `promptTokens=`, `completionTokens=`, `cost=`, `ms=`.
**Never the prompt or the answer.** A call the policy refuses has its row too.

## 6. REST

| Call | Who | What |
|---|---|---|
| `POST /api/v1/ai/chat` | any signed-in caller | `prompt` or `messages`, optionally `model`, `system`, `temperature`, `maxTokens`, `jsonMode`, `timeoutSeconds` (1–120, 30), `feature`. Answers `text`, `toolCalls`, `model`, `finishReason`, `usage` |
| `GET /api/v1/ai/models` | any signed-in caller | the models a caller may name (`ref` is `provider/model`) |
| `GET /api/v1/admin/ai` | `admin` | the layer on or off, the folder, the types installed, every provider with `keySet` (never the key), its models and **health** — `OFF`, `ERROR`, `UNKNOWN`, `UP`, `DOWN`, from the file and the calls seen, **never a network call** — and the retry state; `fileErrors` |
| `POST /api/v1/admin/ai/test` | `admin` | one real call (`model`, `prompt`), capped at 15 seconds and `pravaha.ai.test-per-minute` (6) per administrator |

Codes: `PRV-1080` off (409), `PRV-1081` configuration (503), `PRV-1082` no such model (404),
`PRV-1083` unsupported (400), `PRV-1084` policy (403), `PRV-1085` a limit or the vendor's 429 (429),
`PRV-1086` timeout (504), `PRV-1087` the model failed (502). A non-admin calling an admin endpoint is
`PRV-7002` (403).

## 7. Python: the SDK, the CLI, the assistant and the console

`pravaha.ai` in the Python SDK is the same layer, name for name: `types`, `protocols`, `config`,
`policy`, `registry`, `gateway`, every provider type above, and `AiLayer` (settings, hot reload by
`tick()`). It is standard library only and synchronous — the SDK has no dependencies — and reads a
provider file as JSON always and as YAML when PyYAML is installed. **One package serves every Python
consumer:**

- **The recommended client provider is `server`**: it calls the engine's `POST /api/v1/ai/chat`, so
  the client holds no model key and the engine's policy and audit apply. Per call it takes the
  person's own engine session (`request.context["engine"]`), else its host's, else `base-url` and an
  `api-key: ${PRAVAHA_TOKEN}` from its file.
- **The assistant** (`pravaha.assist`, ADR-058) gains three built-in provider types on the layer,
  after its five: `server`, `mock` (regex rules, the `fake` provider's part configured as the engine's
  mock is) and `ai` (any other layer type by the option `type`). Its contract is unchanged — chains,
  budgets, structured output with one repair turn, the four normalised errors, the engine as the judge
  of every draft ([ASSIST.md](../../guides/ASSIST.md#providers)).
- **The console** (`pravaha-console/core/ai.py`) builds an `AiLayer` whose one provider, `engine`, is
  of type `server`, through its engine adapter as the signed-in person; it holds no model key. Off by
  default (`ai.enabled`). Its Admin → AI page is the next wave.

## 8. The second layer: OpenAI-shaped models

`com.ash.messaging.pravaha.ai.llm` (and `pravaha.ai.llm`) is Drishti's port of sajhamcpserver's
abstractions: `LLMProvider` / `LLMModel` with `chatCompletionsCreate`, `chatCompletionsStream`,
`embeddingsCreate` and their async twins; the canonical `ChatCompletionRequest`, `ChatCompletion`,
`ChatCompletionChunk`, `EmbeddingsRequest` — OpenAI's fields, with the extension field `pravaha` (the
caller, a trace id; who answered, the cost, the latency); `ProviderBase` over any wire provider
(`LLMProviders.fromSettings` / `ProviderBase.from_settings`); and **`LLMFactory.model()`**, which hands
out a `GovernedModel` whose every call goes through the gateway and its policy. Streaming is emulated
from one completion (role, text, finish), as in Drishti.

## 9. Tests

| Where | What |
|---|---|
| `pravaha-ai` `LlmFakes` | Loopback fakes of the openai, azure, azure-entra, anthropic, gemini, cohere, ollama and bedrock APIs, one rule set: echo; `weather` a tool call; `boom` 500 with a body that must never reach a message; `limit` 429; `slow` two seconds; a wrong credential 401 |
| `HttpProvidersTest` | Every type: request shape, parse, errors, timeout, tools, embeddings, live models, readiness, masked description, TLS and proxy refusals; Bedrock's signature recomputed over the body sent |
| `SigV4Test` | AWS's `get-vanilla` vector |
| `HttpProvidersContractTest`, `LLMContractTest` | `contract/llm_contract_http.json` and `llm_contract.json`, **also run by the Python SDK** — the two layers must agree |
| `LlmLayerTest` | Files, literal keys refused, the shipped examples all disabled, reload, retry, the master switch, roles, minute/day/cost budgets, masking, an audit row without the prompt |
| `AiEndpointsHttpTest`, `AiControllerOffTest` | The REST face: chat through the policy, every code and status, admin endpoints refused to a non-admin, health without a call, the test connection's limit, the audit event without its prompt, hot reload, off by default |
| `ArchitectureRulesTest.aiLayerDependsOneWay` | The one-way dependency |
| `sdk/python/tests/test_ai_*.py`, `test_assist_ai_layer.py` | `llm_fakes.py` (the same rules), every type, the shared contracts, the catalogue copies equal, the `server` provider against a fake engine, the assistant on the layer |

No test calls a real model or holds a real key.

## 10. Where Pravaha differs from Drishti

| Drishti | Pravaha | Why |
|---|---|---|
| `openai` through Spring AI; `bedrock` through the AWS SDK | Both on the JDK client; SigV4 by hand | The owner's rule: no vendor SDK or Spring AI in the layer. One HTTP stack for every type, and nothing new for the enforcer's convergence and Java 21 bytecode rules |
| `anthropic` answered every status as `FAILED` | A `HttpProvider`: 429 is `RATE_LIMITED`, retries and `proxy.*` apply | One error mapping for every type |
| A policy refusal left no audit row | It has one (outcome `policy` / `rate_limited`, `DENY`) | The audit trail answers who was refused, not only who was served |
| `LlmException` a plain exception; `DRS-4015`..`4019` | A `PravahaException` with one `PRV` code per kind, `PRV-1080` for off | Pravaha's error contract: every refusal a code, a status and a help page |
| Its own `tls.*` (`ca-file`) and `proxy.*` blocks | The connectors' `PluginTls` vocabulary; `proxy.url` only | One TLS vocabulary per product |
| Console layer in the console, async on httpx | In the SDK, synchronous, standard library | The CLI, the SDK's assistant and the console share it, and the SDK has no dependencies |
| The registry rebuilt every provider on each reload | A provider whose file did not change is kept | Its connections survive a reload of a neighbour |
| MCP client and gateway | Not ported | Out of scope (the owner's decision) |
| Bedrock profiles, SSO, instance roles; Entra managed identity; Vertex | Not built | Keys (or a role's temporary keys) through the environment; client secret or workload identity; AI Studio |

## 11. The features on the layer

The plain-English features ([AI.md](../../guides/AI.md)) are the layer's first callers in the server, on
Drishti's BI AI design (`BiAiService`, `BiAiPrompts`, `AskService`): package
`com.ash.messaging.pravaha.server.ai.features`, behind `/api/v1/ai/{ask,draft,alert-draft,explain,narrate,page}`
(`AiFeaturesController`). The layer is unchanged; the one-way rule holds -- the features live in the server.

| Type | What it is |
|---|---|
| `AiFeatures` | The facade and the plumbing every feature shares: one `LlmGateway` call per turn under the feature's name (`ask`, `draft`, `alert`, `explain`, `narrative`, `page`), the feature's own `ai.<feature>` audit event, the two gates, and **repair once, then refuse** (`judged`: a refusal the model may mend gets one more turn with the engine's words; the second is `PRV-1088`; a grants refusal is the engine's at once). Ask your data: one `SELECT` run through `ViewQuery` as the person, the chart chosen from the result's shape |
| `AiGrounding` | What a model is told: the schema of what this person may read (masked columns left out, refused relations not named), markers with a per-request id, `clean`, the values-mode tables |
| `AiPrompts` | The fixed system texts; every user turn starts `TASK: <name>` so the `mock` provider's rules can script them |
| `AiDrafts` | A draft judged by `ContinuousQueryStatements.validate` and dry-run (`DraftFingerprint`, `QueryRegistry.explainAs`, `QueryListing` for sharing, the lane by the node's placement); an alert judged by `AlertService.validate` -- the checks `CREATE ALERT` runs, now one method both call |
| `AiExplains` | Explain (`sql` from the plan, `refusal` re-checked by the engine, `change`), the narrative, ask about this page |
| `ChangeWindow` | A view's recent commits, followed as an alert follows (`AnswerListener`) from the first question about it; bounded in views and commits; each reader's `RowNarrowing` applied when they ask |
| `AiFeatureErrors` | `PRV-1088` (`AI_ANSWER_UNUSABLE`, 422): Drishti's `DRS-4020` |

Settings: `pravaha.ai.values` (`labels-only` | `aggregates`) and `pravaha.ai.features.*` (the model, the
bounds). Tests: `AiFeaturesEndToEndTest` (pravaha-it), `AiFeaturesHttpTest`, `AiFeaturesOffHttpTest`,
`AiFeaturesShapeTest`, and the SDK's `test_cli_ai.py`.
