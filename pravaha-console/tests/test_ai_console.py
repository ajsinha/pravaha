"""The console's AI layer (ADR-063): the SDK's ``pravaha.ai`` with the engine as its one provider.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Off by default; on, every call goes to the engine's ``/api/v1/ai/chat`` through the console's engine
adapter, and the engine's refusal comes back with the engine's code. No model is called.
"""
from __future__ import annotations

import pytest

from core.ai import console_ai_layer, settings_of, ask
from core.engine import EngineHttpError
from pravaha.ai import LlmError


class Config(dict):
    """The slice of PropertiesConfigurator the layer reads."""


class StubEngine:
    def __init__(self) -> None:
        self.asked: list[dict] = []

    def ai_chat(self, body: dict) -> dict:
        self.asked.append(body)
        text = body["messages"][-1]["content"]
        if "limit" in text:
            raise EngineHttpError(429, "too many calls; try again in 30 seconds", "PRV-1085")
        if "off" in text:
            raise EngineHttpError(409, "the AI layer is switched off", "PRV-1080")
        return {"text": "engine: " + text, "toolCalls": [], "model": "mock-1", "finishReason": "stop",
                "usage": {"promptTokens": 3, "completionTokens": 2, "cost": 0}}


def test_the_console_ai_layer_is_off_by_default():
    layer = console_ai_layer(Config(), StubEngine())
    assert layer.enabled is False and layer.registry.entries() == []
    with pytest.raises(LlmError) as e:
        ask(layer, "ann", frozenset(), "explain", "hello")
    assert e.value.code == "PRV-1080"


def test_on_every_call_goes_to_the_engine_as_the_person_and_its_codes_come_back():
    engine = StubEngine()
    layer = console_ai_layer(Config({"ai.enabled": True}), engine)
    assert [e.config.name for e in layer.registry.entries()] == ["engine"]
    assert ask(layer, "ann", frozenset({"analyst"}), "explain", "what is a window?") == "engine: what is a window?"
    assert engine.asked[-1]["feature"] == "console:explain" and "model" not in engine.asked[-1]
    with pytest.raises(LlmError) as e:
        ask(layer, "ann", frozenset(), "explain", "over the limit")
    assert (e.value.kind, e.value.code) == ("rate_limited", "PRV-1085")
    with pytest.raises(LlmError) as e:
        ask(layer, "ann", frozenset(), "explain", "switched off")
    assert e.value.code == "PRV-1080"
    assert layer.status()["providers"][0]["health"]["status"] == "DOWN"
    assert [a["outcome"] for a in layer.audit_log] == ["ok", "rate_limited", "config"]
    assert "window" not in str(layer.audit_log)


def test_the_settings_carry_the_engine_provider_and_nothing_secret():
    flat = settings_of(Config({"ai.enabled": True, "ai.default_model": "engine/default"}))
    assert flat["ai.providers"]["engine"]["type"] == "server" and flat["ai.dir"] == ""
    assert "api-key" not in str(flat)
