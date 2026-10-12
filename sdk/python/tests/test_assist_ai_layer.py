"""The assistant on the shared AI layer (ADR-063): its ``server``, ``mock`` and ``ai`` provider types run
the layer's providers behind the assistant's own contract -- the router's chains, the structured-output
fallback and repair turn, the four normalised errors -- and the explain task works through them.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json

import pytest
from llm_fakes import KEY, FakeEngine, FakeServer

from pravaha.assist import (
    AssistConfig,
    AssistConfigError,
    ChatRequest,
    Message,
    ModelRateLimited,
    ModelRouter,
    ModelUnavailable,
    UsageLedger,
)
from pravaha.assist.providers.ai_layer import AiLayerProvider, MockProvider, ServerProvider
from pravaha.assist.registry import LAYER_PROVIDERS, create_provider, provider_names
from pravaha.assist.provider import ProviderSettings


def _router(document, tmp_path, environ=None):
    return ModelRouter(AssistConfig.from_dict(document), ledger=UsageLedger(tmp_path / "usage.json"),
                       environ=environ or {}, user="ana")


def _ask(text="Explain it.", **extra):
    return ChatRequest(messages=[Message("user", text)], max_tokens=100, **extra)


def test_the_layer_types_are_built_in_after_the_five_and_cannot_be_taken():
    names = provider_names()
    assert names[:5] == ["fake", "anthropic", "openai", "openai-compatible", "ollama"]
    assert names[5:8] == list(LAYER_PROVIDERS) == ["server", "mock", "ai"]
    assert isinstance(create_provider("mock", ProviderSettings(id="m")), MockProvider)


def test_the_mock_type_plays_the_fakes_part_by_regex_rule(tmp_path):
    doc = {"default_profile": "explain",
           "providers": [{"id": "rules", "type": "mock",
                          "options": {"rules": [{"match": "(?i)hello", "reply": "Hi there"},
                                                {"match": "boom", "error": "scripted failure"},
                                                {"match": "json", "reply": '{"summary": "ok"}'}]}}],
           "models": [{"id": "m", "provider": "rules", "model": "mock-1"}],
           "profiles": {"explain": ["m"]}}
    router = _router(doc, tmp_path)
    answer = router.complete(_ask("hello there"), profile="explain")
    assert answer.text == "Hi there" and answer.provider == "mock" and answer.model_id == "m"
    assert answer.usage.total_tokens > 0
    assert router.complete(_ask("plain"), profile="explain").text == "echo: plain"
    with pytest.raises(ModelUnavailable) as e:
        router.complete(_ask("boom"), profile="explain")
    assert "scripted failure" in str(e.value) and "PRV-1087" in str(e.value)
    schema = {"type": "object", "properties": {"summary": {"type": "string"}}, "required": ["summary"]}
    parsed = router.complete(_ask("as json please", response_schema=schema), profile="explain")
    assert parsed.response.parsed == {"summary": "ok"}


def test_the_server_type_asks_the_engine_and_a_limited_engine_falls_through(tmp_path):
    engine = FakeEngine()
    try:
        doc = {"default_profile": "explain",
               "providers": [{"id": "engine", "type": "server", "endpoint": engine.url, "api_key_env": "PRAVAHA_TOKEN",
                              "options": {"allow-insecure-token": True}},
                             {"id": "local", "type": "mock", "options": {"reply": "from the local mock"}}],
               "models": [{"id": "corp", "provider": "engine", "model": "openai/fast"},
                          {"id": "backup", "provider": "local", "model": "m"}],
               "profiles": {"explain": ["corp", "backup"]}}
        router = _router(doc, tmp_path, {"PRAVAHA_TOKEN": "tok-1"})
        answer = router.complete(_ask("what is event time?"), profile="explain")
        assert answer.text == "engine: what is event time?" and answer.model_id == "corp"
        path, hdr, body = engine.seen[-1]
        assert path == "/api/v1/ai/chat" and hdr["Authorization"] == "Bearer tok-1"
        assert body["model"] == "openai/fast" and body["feature"] == "assist"
        fell = router.complete(_ask("over the limit"), profile="explain")
        assert fell.text == "from the local mock"
        assert [(a.model_id, a.kind) for a in fell.attempts][0] == ("corp", "ModelRateLimited")
    finally:
        engine.close()


def test_the_server_type_calls_the_engine_as_the_signed_in_person_not_with_its_own_token(tmp_path):
    """The gap Wave 5 left: the provider's own token (``api_key_env``) made every person the same caller
    to the engine's policy and audit. A host's session -- the CLI's, the console's -- now goes with each
    call, and the file's token is only the fallback."""
    from pravaha.rest import RestClient
    engine = FakeEngine(token="ana-session")
    try:
        doc = {"default_profile": "explain",
               "providers": [{"id": "engine", "type": "server", "endpoint": engine.url, "api_key_env": "PRAVAHA_TOKEN",
                              "options": {"allow-insecure-token": True}},
                             {"id": "sessionless", "type": "server"}],
               "models": [{"id": "corp", "provider": "engine", "model": "default"},
                          {"id": "lent", "provider": "sessionless", "model": "default"}],
               "profiles": {"explain": ["corp"]}}
        person = RestClient(engine.url, token="ana-session", allow_insecure_token=True)
        router = ModelRouter(AssistConfig.from_dict(doc), ledger=UsageLedger(tmp_path / "usage.json"),
                             environ={"PRAVAHA_TOKEN": "service-token"}, user="ana", engine=person)
        router.complete(_ask("as whom?"), profile="explain")
        assert engine.seen[-1][1]["Authorization"] == "Bearer ana-session"
        router.complete(_ask("and with no endpoint at all?"), model="lent")
        assert engine.seen[-1][1]["Authorization"] == "Bearer ana-session"
        alone = ModelRouter(AssistConfig.from_dict(doc), ledger=UsageLedger(tmp_path / "u2.json"),
                            environ={"PRAVAHA_TOKEN": "service-token"}, user="ana")
        with pytest.raises(ModelUnavailable, match="credential was not accepted"):  # not one this engine takes
            alone.complete(_ask("no session to lend"), profile="explain")
        assert engine.seen[-1][1]["Authorization"] == "Bearer service-token"
    finally:
        engine.close()


def test_the_ai_type_reaches_any_provider_of_the_layer_and_normalises_its_errors(tmp_path):
    s = FakeServer("gemini")
    try:
        doc = {"default_profile": "explain",
               "providers": [{"id": "g", "type": "ai", "endpoint": s.url, "api_key_env": "GEMINI_KEY",
                              "options": {"type": "gemini"}}],
               "models": [{"id": "flash", "provider": "g", "model": "gemini-flash"}],
               "profiles": {"explain": ["flash"]}}
        router = _router(doc, tmp_path, {"GEMINI_KEY": KEY})
        assert router.complete(_ask("ping"), profile="explain").text == "echo: ping"
        assert s.seen[-1][1] == "/v1beta/models/gemini-flash:generateContent"
        assert s.seen[-1][2]["x-goog-api-key"] == KEY and KEY not in s.seen[-1][3]
        with pytest.raises(ModelRateLimited):
            router.complete(_ask("limit"), profile="explain")
        with pytest.raises(ModelUnavailable) as e:
            router.complete(_ask("boom"), profile="explain")
        assert "secret prompt" not in str(e.value)
        assert router.provider("flash").name == "ai"
    finally:
        s.close()


def test_configuration_mistakes_are_refused_before_anything_is_sent():
    with pytest.raises(AssistConfigError, match="needs the option 'type'"):
        AiLayerProvider(ProviderSettings(id="x"))
    with pytest.raises(AssistConfigError, match="has no type 'nonesuch'"):
        AiLayerProvider(ProviderSettings(id="x", options={"type": "nonesuch"}))
    # No endpoint is fine (the host lends the person's session per call); with neither, the call says so.
    with pytest.raises(ModelUnavailable, match="needs the engine"):
        ServerProvider(ProviderSettings(id="engine"))._send(_ask(), native_schema=False)
    with pytest.raises(AssistConfigError, match="base-url is required"):
        AiLayerProvider(ProviderSettings(id="v", options={"type": "openai_compatible"}))._send(
            _ask(), native_schema=False)
    with pytest.raises(AssistConfigError):
        AssistConfig.from_dict({"providers": [{"id": "s", "type": "server", "options": {"api_key": "sk-123"}}]})


def test_the_explain_task_runs_on_the_layer(tmp_path):
    from pravaha.assist import Assistant
    reply = json.dumps({"meaning": "an unbounded group", "cause": "no window", "fix": "add a window",
                        "rewrite": None})
    doc = {"default_profile": "explain",
           "providers": [{"id": "rules", "type": "mock", "options": {"reply": reply}}],
           "models": [{"id": "m", "provider": "rules", "model": "mock-1"}],
           "profiles": {"explain": ["m"]}}
    why = Assistant(_router(doc, tmp_path)).explain_refusal("PRV-2050")
    assert why.fix == "add a window" and why.answered_by["provider"] == "mock"
