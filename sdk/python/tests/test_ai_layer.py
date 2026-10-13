"""The SDK's AI layer with no network (Drishti's ``test_ai_layer.py``, ported): provider files, the
registry and its reload, the mock, the master switch, the policy (roles, limits, masking, an audit record
without the prompt), literal keys refused, failed providers retried, and the ``server`` provider against a
fake engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""
from __future__ import annotations

import json

import pytest

from llm_fakes import FakeEngine
from pravaha.ai import AiLayer, CallContext, ChatRequest, LlmError, LlmGateway, LlmRegistry, NoPolicy, RetryBoard
from pravaha.ai import config as cfgmod
from pravaha.ai.policy import DefaultPolicy, PolicySettings, PromptMasker
from pravaha.ai.providers import FACTORIES
from pravaha.api import EngineApi

MOCK = {"type": "mock", "default-model": "m",
        "models": [{"name": "m", "cost-per-1k-input": 1.0, "cost-per-1k-output": 1.0}, {"name": "vec", "kind": "embedding"}],
        "mock": {"rules": [{"match": "(?i)hello", "reply": "Hi there"},
                           {"match": "weather", "tool": "get_weather", "tool-args": '{"city":"Oslo"}'},
                           {"match": "boom", "error": "scripted"}]}}


def write(d, name, data):
    (d / name).write_text(json.dumps(data) if name.endswith(".json") else data, encoding="utf-8")


def registry(d, env=None):
    r = LlmRegistry(FACTORIES, lambda: cfgmod.load_providers(d, (env or {}).get))
    r.reload()
    return r


ANN = CallContext("ann", frozenset({"analyst"}), "test")


def test_the_mock_answers_by_rule_echo_tools_errors_and_embeds(tmp_path):
    write(tmp_path, "mock.json", MOCK)
    reg = registry(tmp_path)
    gw = LlmGateway(reg)
    assert gw.chat(ANN, ChatRequest.of("Hello you", system="sys")).text == "Hi there"
    assert gw.chat(ANN, ChatRequest.of("plain", model="mock/m")).text == "echo: plain"
    tool = gw.chat(ANN, ChatRequest.of("the weather", model="m"))
    assert [(c.name, c.arguments_json) for c in tool.tool_calls] == [("get_weather", '{"city":"Oslo"}')]
    assert tool.usage.cost > 0
    with pytest.raises(LlmError) as e:
        gw.chat(ANN, ChatRequest.of("boom"))
    assert e.value.kind == "failed" and e.value.message == "scripted" and e.value.code == "PRV-1087"
    assert reg.embedding("mock/vec").embed(["abc"]) == reg.embedding("vec").embed(["abc"])
    assert reg.health(reg.provider("mock"))["status"] == "DOWN"


def test_the_layer_is_off_by_default_and_off_refuses_every_call_with_its_code(tmp_path):
    write(tmp_path, "mock.json", MOCK)
    off = AiLayer({"ai.dir": str(tmp_path)})
    assert off.enabled is False and off.registry.entries() == []  # nothing was read
    with pytest.raises(LlmError) as e:
        off.gateway.chat(ANN, ChatRequest.of("hello"))
    assert e.value.code == "PRV-1080"
    on = AiLayer({"ai.enabled": "true", "ai.dir": str(tmp_path)})
    assert on.gateway.chat(ANN, ChatRequest.of("hello")).text == "Hi there"
    assert on.status()["providers"][0]["health"]["status"] == "UP"


def test_timeouts_are_reported_as_timeouts(tmp_path):
    write(tmp_path, "slow.json", {"type": "mock", "models": [{"name": "m"}], "mock": {"latency-ms": 2000}})
    with pytest.raises(LlmError) as e:
        LlmGateway(registry(tmp_path)).chat(ANN, ChatRequest.of("x", timeout=0.1))
    assert e.value.timeout and e.value.code == "PRV-1086"


def test_bad_files_are_reported_per_file_and_never_echo_a_literal_secret(tmp_path):
    write(tmp_path, "good.json", {"type": "mock", "models": [{"name": "m"}]})
    write(tmp_path, "literal.json", {"type": "openai", "api-key": "sk-123"})
    write(tmp_path, "secret.json", {"type": "bedrock", "aws-secret-access-key": "wJalrXUtnFEMI"})
    write(tmp_path, "header.json", {"type": "mock", "header": {"Authorization": "Bearer abc"}})
    write(tmp_path, "notype.json", {"models": []})
    write(tmp_path, "unknown.json", {"type": "nonesuch", "models": [{"name": "x"}]})
    write(tmp_path, "off.json", {"type": "mock", "enabled": False, "models": [{"name": "y"}]})
    write(tmp_path, "ignored.yaml.example", "type: mock")
    reg = registry(tmp_path)
    assert set(reg.file_errors) == {"literal.json", "secret.json", "header.json", "notype.json"}
    assert "placeholder" in reg.file_errors["literal.json"] and "sk-123" not in reg.file_errors["literal.json"]
    assert "wJalr" not in reg.file_errors["secret.json"] and "abc" not in reg.file_errors["header.json"]
    assert reg.health(reg.provider("unknown"))["status"] == "ERROR"
    assert reg.health(reg.provider("off"))["status"] == "OFF"
    assert reg.health(reg.provider("good"))["status"] == "UNKNOWN"
    assert [m.ref for m in reg.models()] == ["good/m"]
    assert reg.provider("ignored") is None


def test_a_yaml_file_is_read_when_pyyaml_is_there(tmp_path):
    pytest.importorskip("yaml")
    write(tmp_path, "y.yaml", "type: mock\nmodels:\n  - name: m\nmock:\n  reply: from yaml\n")
    assert LlmGateway(registry(tmp_path)).chat(ANN, ChatRequest.of("x")).text == "from yaml"


def test_keys_come_from_the_environment_and_never_appear_in_the_config_repr(tmp_path):
    write(tmp_path, "oai.json", {"type": "openai", "api-key": "${OAI_KEY}", "models": [{"name": "m"}]})
    write(tmp_path, "empty.json", {"type": "openai", "api-key": "${MISSING:-}", "models": [{"name": "n"}]})
    write(tmp_path, "dflt.json", {"type": "openai", "api-key": "${MISSING:-sk-fallback}", "models": [{"name": "n"}]})
    write(tmp_path, "hdr.json", {"type": "openai", "api-key": "${OAI_KEY}", "header": {"X-Api-Key": "${H:-sk-h}"},
                                 "models": [{"name": "n"}]})
    reg = registry(tmp_path, {"OAI_KEY": "sekret"})
    assert reg.provider("oai").config.api_key == "sekret" and "sekret" not in repr(reg.provider("oai").config)
    assert reg.provider("empty").config.api_key == ""
    # AIQA-7: a fallback value is a secret written into the file, refused like any literal
    assert reg.provider("dflt") is None and reg.provider("hdr") is None
    assert "no fallback value" in reg.file_errors["dflt.json"] and "sk-fallback" not in reg.file_errors["dflt.json"]
    assert "sk-h" not in reg.file_errors["hdr.json"]


def test_reload_picks_up_added_changed_and_removed_files_and_ambiguity_is_refused(tmp_path):
    layer = AiLayer({"ai.enabled": True, "ai.dir": str(tmp_path)})
    assert layer.tick() is False
    write(tmp_path, "a.json", {"type": "mock", "models": [{"name": "same"}]})
    assert layer.tick() is True and [m.ref for m in layer.registry.models()] == ["a/same"]
    write(tmp_path, "b.json", {"type": "mock", "models": [{"name": "same"}, {"name": "v", "kind": "embedding"}]})
    layer.tick()
    with pytest.raises(LlmError, match="several providers"):
        layer.registry.chat("same")
    with pytest.raises(LlmError) as e:
        layer.registry.chat("b/v")
    assert e.value.kind == "unsupported"
    with pytest.raises(LlmError) as e:
        layer.registry.chat("nope")
    assert e.value.kind == "not_found"
    (tmp_path / "a.json").unlink()
    layer.tick()
    assert layer.registry.chat("same")[0].config.name == "b"


def test_a_provider_that_could_not_be_built_is_retried_on_its_schedule(tmp_path):
    now = [1000.0]
    board = RetryBoard(5, 300, lambda: now[0])
    env = {}
    write(tmp_path, "az.json", {"type": "azure_openai", "api-key": "${AZ_KEY}", "models": [{"name": "m"}]})
    reg = LlmRegistry(FACTORIES, lambda: cfgmod.load_providers(tmp_path, env.get), retry=board)
    reg.reload()
    assert reg.health(reg.provider("az"))["status"] == "ERROR" and board.statuses()["az"].permanent
    env.update(AZURE_OPENAI_ENDPOINT="https://example.openai.azure.com", AZ_KEY="k")
    reg.reload()
    assert reg.health(reg.provider("az"))["status"] == "UNKNOWN" and not board.failing("az")
    board.failed("x", "temporary", False)
    assert board.due() == []
    now[0] += 6
    assert board.due() == ["x"]
    assert board.retry_all_now() == 1


def test_policy_masks_limits_audits_and_gates_features_by_role(tmp_path):
    write(tmp_path, "mock.json", {"type": "mock", "models": [{"name": "m", "cost-per-1k-input": 1000}]})
    audit = []
    ps = PolicySettings(per_user_per_minute=2, feature_roles={"admin-only": ["admin"]})
    gw = LlmGateway(registry(tmp_path), DefaultPolicy(ps, PromptMasker(["salary"], [r"\b\d{16}\b"]), audit.append))
    ann = CallContext("ann", frozenset({"user"}), "help")
    r = gw.chat(ann, ChatRequest.of("salary: 90000 card 1234567812345678 ok"))
    assert r.text == "echo: salary: \u2022\u2022\u2022 card \u2022\u2022\u2022 ok"  # the model never saw the values
    assert audit[0]["user"] == "ann" and audit[0]["outcome"] == "ok" and audit[0]["promptTokens"] > 0
    assert "salary" not in json.dumps(audit[0]) and "echo" not in json.dumps(audit[0])
    with pytest.raises(LlmError) as e:
        gw.chat(CallContext("ann", frozenset({"user"}), "admin-only"), ChatRequest.of("x"))
    assert e.value.kind == "policy" and e.value.code == "PRV-1084"
    assert [a["outcome"] for a in audit] == ["ok", "policy"]  # a refused call has its audit record too
    gw.chat(ann, ChatRequest.of("again"))
    with pytest.raises(LlmError) as e:
        gw.chat(ann, ChatRequest.of("third"))
    assert e.value.kind == "rate_limited"


def test_per_day_and_daily_cost_limits(tmp_path):
    write(tmp_path, "mock.json", {"type": "mock", "models": [{"name": "m", "cost-per-1k-input": 1000}]})
    gw = LlmGateway(registry(tmp_path), DefaultPolicy(PolicySettings(per_user_per_day=1, audit=False)))
    gw.chat(ANN, ChatRequest.of("one"))
    with pytest.raises(LlmError, match="daily limit of 1"):
        gw.chat(ANN, ChatRequest.of("two"))
    clock = [0.0]
    cost = LlmGateway(registry(tmp_path), DefaultPolicy(PolicySettings(cost_per_user_per_day=0.5), clock=lambda: clock[0]))
    cost.chat(ANN, ChatRequest.of("a long enough prompt to cost something"))
    with pytest.raises(LlmError, match="cost limit"):
        cost.chat(ANN, ChatRequest.of("x"))
    clock[0] += 86400
    assert cost.chat(ANN, ChatRequest.of("a new day")).text == "echo: a new day"


def test_the_server_provider_delegates_to_the_engine_and_maps_its_codes(tmp_path):
    engine = FakeEngine()
    try:
        write(tmp_path, "engine.json", {"type": "server", "base-url": engine.url, "api-key": "${PRAVAHA_TOKEN}",
                                        "allow-insecure-token": True, "models": [{"name": "default"}, {"name": "corp", "id": "openai/fast"}],
                                        "default-model": "default"})
        gw = LlmGateway(registry(tmp_path, {"PRAVAHA_TOKEN": "tok-1"}), NoPolicy())
        r = gw.chat(CallContext("ann", frozenset(), "explain"), ChatRequest.of("hi", system="be brief"))
        assert r.text == "engine: hi" and r.usage.cost == 0.5
        path, hdr, body = engine.seen[-1]
        assert path == "/api/v1/ai/chat" and hdr["Authorization"] == "Bearer tok-1"
        assert body["feature"] == "explain" and body["system"] == "be brief" and "model" not in body
        gw.chat(ANN, ChatRequest.of("x", model="engine/corp"))
        assert engine.seen[-1][2]["model"] == "openai/fast"
        for text, kind, code in (("limit", "rate_limited", "PRV-1085"), ("off", "config", "PRV-1080"), ("slow", "timeout", "PRV-1086")):
            with pytest.raises(LlmError) as e:
                gw.chat(ANN, ChatRequest.of(text))
            assert (e.value.kind, e.value.code) == (kind, code)
        # the person's own engine session, per call, wins over the file's
        other = EngineApi(engine.url, token="wrong", allow_insecure_token=True)
        with pytest.raises(LlmError) as e:
            gw.chat(ANN, ChatRequest.of("x", engine=other))
        assert e.value.kind == "failed"
    finally:
        engine.close()


def test_engine_api_names_the_ai_endpoints():
    engine = FakeEngine()
    try:
        api = EngineApi(engine.url, token="tok-1", allow_insecure_token=True)
        assert api.ai_chat({"prompt": "x", "messages": [{"role": "user", "content": "x"}]})["text"] == "engine: x"
    finally:
        engine.close()
