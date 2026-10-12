# ADR-063: an AI layer beside the engine, off by default, on Drishti's design

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **built**. The `pravaha-ai` module (providers, registry, policy, masking, gateway, the OpenAI-shaped second layer), the server's wiring and its four REST endpoints, the one-way ArchUnit rule, the Python SDK's `pravaha.ai`, the assistant's `server` / `mock` / `ai` provider types and the console's layer on the engine. The console's Admin → AI page is not built. See [`docs/design/architecture/ai.md`](../architecture/ai.md) |
| Date | 2026-10-12 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-058 (the assistant; this extends its provider part), ADR-052 (the engine is the identity authority), ADR-031 (authorization at the Pravaha layer), ADR-053 (native code only where Java cannot), ADR-062 (Java 21 or later) |

## Context

ADR-058 put model calls in the Python SDK's assistant and kept the engine model-free: "the engine gains
no model dependency, no outbound network call and no new permission." That served one feature, from
one process at a time. It left three gaps the owner asked to close by porting Drishti's AI layer
"using exactly the same designs":

- **Keys live wherever the assistant runs.** Every CLI user and the console hold a model key of their
  own; nothing central applies a budget, a mask or an audit to a model call.
- **One provider set per language.** The assistant's five providers are its own; the console has no
  general way to call a model, and the engine none at all.
- **No common contract for the next features** (natural-language features in the console, a model
  behind a catalogue description), each of which would otherwise grow its own provider code.

Drishti solved these with one layer, two languages: `drishti-ai` in the server — provider and model
interfaces, one YAML file per provider, a registry with health and reload, a policy around every call
(roles per feature, rate and cost limits, masking, an audit row without the prompt), one gateway — and
its console's mirror, whose recommended provider (`server`) delegates to the server so keys stay there.

## Decision

**Port Drishti's AI layer with its names**, as `pravaha-ai` (package `com.ash.messaging.pravaha.ai`) and
the SDK's `pravaha.ai`, wired by the server, **off by default** (`pravaha.ai.enabled`,
`PRAVAHA_AI_ENABLED`; with it off every call is `PRV-1080`).

1. **The engine core stays model-free, by rule.** `pravaha-ai` depends on `pravaha-api` and
   `pravaha-common` only; nothing in the engine depends on it; only `pravaha-server` wires it
   (`ArchitectureRulesTest.aiLayerDependsOneWay`). What ADR-058 kept by keeping models out of the
   engine is now kept by a machine-checked boundary, with the layer beside the engine in the server
   process rather than in every client. The engine judges SQL exactly as before; no model output is
   executed.
2. **Keys only from the environment.** One file per provider; a key or any other secret must be an
   `${ENV}` placeholder and **a literal is refused when the file is read**. A key never appears in a
   message, an admin answer or an audit row.
3. **One door.** Every call goes through `LlmGateway`: the master switch, the model by logical name,
   the policy before (roles, per-minute, per-day and cost-per-day limits, masking) and after (one
   audit event in the node's own audit trail — who, feature, model, sizes, tokens, cost, outcome,
   never the prompt or the answer), the timeout, and health from the calls seen.
4. **Plain HTTP, no vendor SDK.** Every provider type speaks its vendor's API on the JDK client (and
   `urllib` in Python). Where Drishti used Spring AI (`openai`) and the AWS SDK (`bedrock`), Pravaha
   uses the same HTTP stack as every other type and signs Bedrock's requests with SigV4 by hand: no
   dependency for the enforcer's convergence, native-code and Java 21 bytecode rules to weigh.
5. **Codes.** `PRV-1080` off, then one per failure kind: `1081` configuration, `1082` no such model,
   `1083` unsupported, `1084` policy, `1085` a limit or the vendor's 429, `1086` timeout, `1087`
   failed — in the configuration and API range, beside the REST API's own.
6. **One Python package.** `pravaha.ai` lives in the SDK, so the CLI, the assistant and the console
   share one implementation of every provider type. Its recommended provider is `server`, which
   delegates to the engine's `POST /api/v1/ai/chat`.

### How this extends ADR-058

ADR-058's **task design is unchanged**: the assistant drafts, the engine judges with up to three
repair turns, a person confirms, the evaluation scores by fingerprint. Its **provider part is
extended, not replaced**: the `ModelProvider` protocol, its five built-ins, the router's chains and
budgets and the four normalised errors stay, and three built-in types join them on the shared layer —
`server` (the engine's layer: one place for keys, policy and audit, and the recommended choice),
`mock` (regex rules) and `ai` (any other layer type: Gemini, Cohere, Bedrock, Azure OpenAI and the
OpenAI-compatible presets — most of what ADR-058 left for its phase 4). ADR-058's "the engine gains no
outbound network call" now reads: **the engine core** gains none; the server may, when an
administrator turns the layer on and writes a provider file.

## Alternatives considered

| Alternative | What goes wrong |
|---|---|
| Keep models in the clients only (ADR-058 as it was) | Keys on every desk; no central budget, mask or audit; each new feature grows its own providers |
| Put the layer in the engine core | Every embedding application inherits model code and an outbound surface it did not ask for; the ArchUnit rule exists to stop exactly this |
| Spring AI and vendor SDKs, as Drishti partly did | A dependency surface to keep convergent and Java 21-clean for code that is a few hundred lines of HTTP each; two HTTP stacks |
| A Pravaha-specific design | The owner asked for Drishti's; one design across products means one way to configure, extend and audit |
| Port Drishti's MCP client too | Out of scope by the owner's decision |

## Consequences

- An administrator turns models on in one place, with keys from the environment, budgets per person,
  masking and an audit trail; clients hold no key when they use `server`.
- New natural-language features have one contract to call (`LlmGateway`, `LLMFactory`) in Java and in
  Python; the console's Admin → AI page has `GET /api/v1/admin/ai` and `POST /api/v1/admin/ai/test`.
- The server has an outbound surface when, and only when, the layer is on and a provider file is
  enabled. `tls.*` is the connectors'; proxy authentication, Bedrock's credential chain beyond
  environment keys, Entra managed identity and Vertex AI are not built.
