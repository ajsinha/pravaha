"""Every HTTP provider type of the SDK's AI layer against its fake vendor server on loopback (Drishti's
``test_llm_providers.py``, ported): request shape, reply parse, error mapping (401 and 500 failed
without the body, 429 rate_limited, a slow answer timeout), tools, embeddings, live models, readiness,
secrets masked; the shared HTTP contract the engine runs too; and the two catalogue copies equal.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from llm_fakes import KEY, FakeServer
from pravaha.ai.llm import ChatCompletionRequest, ChatMessage, EmbeddingsRequest, LLMError, ProviderBase
from pravaha.ai.llm.registry import registered_providers
from pravaha.ai.providers import bedrock, catalog

REPO = Path(__file__).resolve().parents[3]
CONTRACT = json.loads((REPO / "pravaha-ai/src/test/resources/contract/llm_contract_http.json").read_text(encoding="utf-8"))
ENV = {"THE_KEY": KEY, "AKID": "AKIDEXAMPLE", "ASECRET": "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "CS": "cs-1"}
PRESETS = ["groq", "together", "fireworks", "deepseek", "xai", "openrouter", "perplexity", "huggingface", "vllm",
           "lmstudio", "mistral", "openai_compatible"]
FAKE = {"azure_openai": "azure", "anthropic": "anthropic", "gemini": "gemini", "cohere": "cohere", "ollama": "ollama",
        "bedrock": "bedrock"}


def make(typ, url, name="p", **extra):
    cfg = {"type": typ, "base-url": url, "timeout": "1s", "catalogue": False, "default-model": "m",
           "models": [{"name": "m", "id": "the-model", "cost-per-1k-input": 1.0, "cost-per-1k-output": 2.0},
                      {"name": "v", "id": "amazon.titan-embed-text-v2:0" if typ == "bedrock" else "the-embed", "kind": "embedding"}]}
    if typ not in ("ollama", "bedrock"):
        cfg["api-key"] = "${THE_KEY}"
    if typ == "bedrock":
        cfg.update({"aws-access-key-id": "${AKID}", "aws-secret-access-key": "${ASECRET}", "control-url": url})
    cfg.update(extra)
    return ProviderBase.from_settings(name, cfg, env=ENV.get)


def ask(provider, text, **fields):
    return provider.chat_model("m").chat_completions_create(messages=[{"role": "system", "content": "be brief"},
                                                                      {"role": "user", "content": text}], **fields)


@pytest.fixture()
def fake():
    made = []

    def f(api, **kw):
        s = FakeServer(api, **kw)
        made.append(s)
        return s
    yield f
    for s in made:
        s.close()


@pytest.mark.parametrize("typ", ["openai", "groq", "mistral", "vllm", "azure_openai", "anthropic", "gemini", "cohere",
                                 "ollama", "bedrock"])
def test_every_type_echoes_calls_tools_and_maps_errors_without_the_body(fake, typ):
    s = fake(FAKE.get(typ, "openai"))
    p = make(typ, s.url)
    c = ask(p, "ping")
    assert c.text == "echo: ping" and c.usage.total_tokens == 8 and c.finish_reason == "stop"
    assert c.pravaha.cost_usd == pytest.approx(0.011) and c.pravaha.provider == "p"
    req = ChatCompletionRequest(model="m", messages=[ChatMessage.user("weather in Oslo")],
                                tools=[{"type": "function", "function": {"name": "get_weather", "description": "d",
                                                                          "parameters": {"type": "object"}}}])
    t = p.chat_model("m").chat_completions_create(req)
    assert [(x.function.name, json.loads(x.function.arguments)) for x in t.tool_calls] == [("get_weather", {"city": "Oslo"})]
    assert t.finish_reason == "tool_calls"
    with pytest.raises(LLMError) as e:
        ask(p, "boom")
    assert e.value.kind == "FAILED" and "HTTP 500" in e.value.message and "secret prompt" not in e.value.message
    with pytest.raises(LLMError) as e:
        ask(p, "limit")
    assert e.value.kind == "RATE_LIMITED" and e.value.code == "PRV-1085"
    with pytest.raises(LLMError) as e:
        ask(p, "slow")
    assert e.value.kind == "TIMEOUT"
    assert p.health().ok, p.health().detail


def test_the_openai_request_shape_and_the_key_only_in_the_header(fake):
    s = fake("openai")
    ask(make("openai", s.url, header={"X-Team": "red"}), "ping")
    method, path, hdr, body = s.seen[0]
    j = json.loads(body)
    assert (method, path) == ("POST", "/v1/chat/completions") and hdr["authorization"] == "Bearer " + KEY
    assert hdr["x-team"] == "red" and KEY not in body
    assert j["model"] == "the-model" and j["messages"][0] == {"role": "system", "content": "be brief"}
    assert j["max_tokens"] == 1024 and j["stream"] is False


@pytest.mark.parametrize("typ", PRESETS)
def test_every_openai_compatible_preset_posts_to_chat_completions(fake, typ):
    s = fake("openai")
    ask(make(typ, s.url), "ping")
    assert s.seen[0][1] == "/chat/completions" and s.seen[0][2]["authorization"] == "Bearer " + KEY


def test_max_tokens_param_and_json_mode(fake):
    s = fake("openai")
    p = make("openai_compatible", s.url, **{"max-tokens-param": "max_completion_tokens"})
    p.chat_model("m").chat_completions_create(messages=[{"role": "user", "content": "x"}], max_tokens=9, temperature=0.2,
                                              response_format={"type": "json_object"})
    j = json.loads(s.seen[0][3])
    assert j["max_completion_tokens"] == 9 and "max_tokens" not in j and j["temperature"] == 0.2
    assert j["response_format"] == {"type": "json_object"}


def test_anthropic_speaks_the_messages_api(fake):
    s = fake("anthropic")
    ask(make("anthropic", s.url), "hi")
    _, path, hdr, body = s.seen[0]
    assert path == "/v1/messages" and hdr["x-api-key"] == KEY and hdr["anthropic-version"] == "2023-06-01"
    assert json.loads(body)["system"] == "be brief"


def test_azure_addresses_the_deployment_and_entra_fetches_a_token_once(fake):
    s = fake("azure")
    ask(make("azure_openai", s.url, **{"api-style": "deployments", "deployments": {"the-model": "dep-1"}}), "ping")
    assert s.seen[0][1] == "/openai/deployments/dep-1/chat/completions?api-version=2024-10-21"
    assert s.seen[0][2]["api-key"] == KEY and json.loads(s.seen[0][3])["model"] == "dep-1"
    e = fake("azure-entra")
    p = make("azure_openai", e.url, auth="entra", **{"tenant-id": "t1", "client-id": "c1", "client-secret": "${CS}",
                                                     "entra-authority": e.url})
    assert p.health().ok
    ask(p, "ping")
    ask(p, "pong")
    assert [x[1] for x in e.seen].count("/t1/oauth2/v2.0/token") == 1
    assert "client_secret=cs-1" in e.seen[0][3] and e.seen[1][2]["authorization"] == "Bearer entra-token-1"
    nosecret = make("azure_openai", "https://x.openai.azure.com", auth="entra", **{"tenant-id": "t", "client-id": "c"})
    assert not nosecret.health().ok and "managed identity is not built" in nosecret.health().detail


def test_gemini_cohere_and_ollama_paths(fake):
    g = fake("gemini")
    ask(make("gemini", g.url), "ping")
    assert g.seen[0][1] == "/v1beta/models/the-model:generateContent" and g.seen[0][2]["x-goog-api-key"] == KEY
    with pytest.raises(LLMError):
        make("gemini", "https://x", platform="vertex")
    c = fake("cohere")
    ask(make("cohere", c.url), "ping")
    assert c.seen[0][1] == "/v2/chat"
    o = fake("ollama")
    ask(make("ollama", o.url, options={"top_k": 7}, **{"keep-alive": "5m"}), "ping")
    j = json.loads(o.seen[0][3])
    assert o.seen[0][1] == "/api/chat" and j["options"]["top_k"] == 7 and j["keep_alive"] == "5m"


def test_bedrock_is_signed_by_hand_and_the_signature_covers_the_body(fake):
    def check(method, url, data, hdr):
        h = {k.lower(): v for k, v in hdr.items()}
        import datetime
        when = datetime.datetime.strptime(h["x-amz-date"], "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc)
        again = bedrock.sign(method, url, data, "application/json", "AKIDEXAMPLE", ENV["ASECRET"], "", "us-east-1", "bedrock", when)
        return again["Authorization"] == h["authorization"]
    s = fake("bedrock", sigv4_check=check)
    p = make("bedrock", s.url)
    assert ask(p, "ping").text == "echo: ping"
    assert s.seen[0][1] == "/model/the-model/converse"
    assert "SignedHeaders=content-type;host;x-amz-date" in s.seen[0][2]["authorization"]
    assert len(p.embedding_model("v").embeddings_create(input=["a", "b"]).data) == 2
    assert "/model/amazon.titan-embed-text-v2%3A0/invoke" in [x[1] for x in s.seen]
    assert [m.kind for m in p.live_models()] == ["chat"]  # the titan model is the file's own
    nokeys = ProviderBase.from_settings("b", {"type": "bedrock", "catalogue": False}, env={}.get)
    assert nokeys.health().ok is not None


def test_sigv4_matches_aws_get_vanilla_vector():
    import datetime
    h = bedrock.sign("GET", "https://example.amazonaws.com/", b"", "", "AKIDEXAMPLE",
                     "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "", "us-east-1", "service",
                     datetime.datetime(2015, 8, 30, 12, 36, tzinfo=datetime.timezone.utc))
    assert h["Authorization"] == ("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                                  "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31")


def test_embeddings_live_models_and_probe(fake):
    for typ in ("openai_compatible", "gemini", "cohere", "ollama"):
        s = fake(FAKE.get(typ, "openai"))
        p = make(typ, s.url, **{"live-models": "true"})
        r = p.embedding_model("v").embeddings_create(EmbeddingsRequest(input=["a", "bb"]))
        assert len(r.vectors) == 2 and len(r.vectors[0]) == 4, typ
        assert len(p.live_models()) == 2, typ
        assert p.probe().ok, typ


def test_a_wrong_key_says_where_to_look_and_only_429_and_5xx_are_retried(fake):
    s = fake("openai")
    p = ProviderBase.from_settings("p", {"type": "openai_compatible", "base-url": s.url, "api-key": "${W}", "retries": 2,
                                         "models": [{"name": "m"}]}, env={"W": "nope"}.get)
    with pytest.raises(LLMError) as e:
        ask(p, "ping")
    assert "HTTP 401" in e.value.message and "authentication failed" in e.value.message and "nope" not in e.value.message
    assert len(s.seen) == 1
    q = ProviderBase.from_settings("p", {"type": "openai_compatible", "base-url": s.url, "api-key": "${THE_KEY}",
                                         "retries": 2, "models": [{"name": "m"}]}, env=ENV.get)
    with pytest.raises(LLMError):
        ask(q, "boom")
    assert len(s.seen) == 4


def test_readiness_without_a_key_names_the_variable_and_the_description_masks_secrets():
    p = ProviderBase.from_settings("g", {"type": "groq"}, env={}.get)
    assert not p.health().ok and "GROQ_API_KEY" in p.health().detail
    keyed = ProviderBase.from_settings("g", {"type": "groq", "api-key": "${THE_KEY}"}, env=ENV.get).describe_config()
    assert keyed["apiKey"] == "********" and KEY not in json.dumps(keyed)
    assert keyed["baseUrl"] == "https://api.groq.com/openai/v1"


def test_tls_and_proxy_use_the_connector_vocabulary_and_refuse_what_they_cannot_honour():
    for extra, words in (({"tls": {"verify-hostname": False}}, "verify-hostname"),
                         ({"tls": {"ca-certificate": "/x.pem"}}, "tls.ca"),
                         ({"tls": {"ca": "/no/such/ca.pem"}}, "could not be read"),
                         ({"tls": {"truststore": "/x.jks"}}, "PEM"),
                         ({"proxy": {"url": "http://proxy:3128", "username": "u"}}, "proxy authentication"),
                         ({"proxy": {"password": "literal"}}, "placeholder")):
        with pytest.raises(LLMError) as e:
            make("openai", "https://x", **extra)
        assert words in e.value.message, (extra, e.value.message)
    assert make("openai", "https://x", proxy={"url": "http://proxy:3128"}) is not None


def test_every_type_is_registered():
    want = set(PRESETS) | {"openai", "anthropic", "server", "mock", "azure_openai", "gemini", "cohere", "ollama", "bedrock"}
    assert want <= set(registered_providers())


@pytest.mark.parametrize("case", CONTRACT["providers"], ids=[c["label"] for c in CONTRACT["providers"]])
def test_the_shared_contract_against_each_http_provider(fake, case):
    s = fake(case["fake"])
    cfg = {**case["config"], "type": case["type"], "base-url": s.url, "timeout": "5s"}
    if case["type"] == "bedrock":
        cfg["control-url"] = s.url
    p = ProviderBase.from_settings(CONTRACT["name"], cfg, env=ENV.get)
    assert len(p.models()) == len(CONTRACT["catalogue"])
    m = p.chat_model("")
    for sc in CONTRACT["chat"]:
        want = sc["expect"]
        req = ChatCompletionRequest(model="chat", messages=[ChatMessage(role=r, content=t) for r, t in sc["messages"]])
        if "error" in want:
            with pytest.raises(LLMError) as e:
                m.chat_completions_create(req)
            assert e.value.kind == want["error"], sc["name"]
            continue
        c = m.chat_completions_create(req)
        assert c.finish_reason == want["finish_reason"], sc["name"]
        if "text" in want:
            assert c.text == want["text"]
        if "tool" in want:
            assert c.tool_calls[0].function.name == want["tool"]
            assert json.loads(c.tool_calls[0].function.arguments) == json.loads(want["tool_arguments"])
    st = CONTRACT["stream"]
    chunks = list(m.chat_completions_stream(messages=[{"role": r, "content": t} for r, t in st["messages"]]))
    assert len(chunks) == st["chunks"] and "".join(c.delta for c in chunks) == st["joined"]
    if case.get("embeddings", True):
        e = CONTRACT["embeddings"]
        r = p.embedding_model("vec").embeddings_create(input=e["input"])
        assert len(r.data) == e["count"] and len(r.vectors[0]) == e["dimensions"]
    for ref in CONTRACT["refusals"]:
        with pytest.raises(LLMError) as err:
            mm = p.model(ref["model"])
            if ref["call"] == "chat":
                mm.chat_completions_create(messages=[{"role": "user", "content": "x"}])
            else:
                mm.embeddings_create(input=["x"])
        assert err.value.kind == ref["error"], ref["name"]


def test_the_two_catalogue_copies_are_identical():
    engine = (REPO / "pravaha-ai/src/main/resources/ai/llm_catalog.json").read_text(encoding="utf-8")
    sdk = (Path(catalog.__file__).with_name("llm_catalog.json")).read_text(encoding="utf-8")
    assert json.loads(engine) == json.loads(sdk)
