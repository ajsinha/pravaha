"""The console's AI layer: the SDK's ``pravaha.ai`` (ADR-063), with the engine as its one provider.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Drishti's console carried its own copy of the AI layer; Pravaha's console uses the SDK's, which the CLI
and the assistant use too, so there is one implementation of every provider type. The console defines one
provider in code, ``engine``, of type ``server``: every call goes to the engine's ``POST /api/v1/ai/chat``
through the console's engine adapter, as the signed-in person -- so the keys, the policy, the masking, the
limits and the audit row stay in the engine, and the console holds no model key at all. Files in
``ai.dir`` may add others (``*.yaml``, needing PyYAML, which the console has), and are read only while
``ai.enabled`` is on. Off by default, like the engine's.

Admin -> AI (the page that shows ``layer.status()`` and the engine's ``/api/v1/admin/ai``) is wave 6.
"""
from __future__ import annotations

from typing import Any

from pravaha.ai import AiLayer, CallContext, ChatRequest, LlmError

from core.engine import EngineHttpError

#: The console's own provider: the engine.
ENGINE_PROVIDER = {"type": "server", "default-model": "default", "models": [{"name": "default"}]}


class EngineAiAdapter:
    """What the ``server`` provider calls -- ``post(path, body)`` -- answered by the console's engine
    adapter, so the call carries the signed-in person's engine session; an engine refusal comes back
    as the SDK's :class:`pravaha.rest.ApiError` with the engine's code, which the provider maps."""

    def __init__(self, engine: Any) -> None:
        self._engine = engine

    def post(self, path: str, body: dict) -> Any:
        from pravaha.rest import ApiError

        if path != "/api/v1/ai/chat":
            raise ApiError(0, f"the console does not call {path} for the AI layer")
        try:
            return self._engine.ai_chat(body)
        except EngineHttpError as exc:
            raise ApiError(int(exc.status or 0), str(exc), exc.code) from exc


class _Runtime:
    def __init__(self, engine: Any) -> None:
        self.engine = EngineAiAdapter(engine)


def settings_of(config: Any) -> dict[str, Any]:
    """The console's ``ai.*`` settings as the flat mapping :class:`pravaha.ai.AiLayer` reads."""
    keys = ("ai.enabled", "ai.dir", "ai.default_model", "ai.poll_seconds", "ai.redact", "ai.policy.mask_redacted",
            "ai.policy.mask_patterns", "ai.policy.audit", "ai.policy.per_user_per_minute",
            "ai.policy.per_user_per_day", "ai.policy.cost_per_user_per_day", "ai.policy.features")
    out = {k: config.get(k) for k in keys if config.get(k) is not None}
    out.setdefault("ai.dir", "")
    out["ai.providers"] = {"engine": ENGINE_PROVIDER}
    return out


def console_ai_layer(config: Any, engine: Any) -> AiLayer:
    """The console's one AI layer, built from its configuration, with the engine as its provider."""
    return AiLayer(settings_of(config), runtime=_Runtime(engine))


def ask(layer: AiLayer, user: str, roles: frozenset[str], feature: str, question: str, **options: Any) -> str:
    """One question as the signed-in person, for a console feature (``feature`` is ``console:<name>``);
    the engine applies its own policy again. Raises :class:`pravaha.ai.LlmError` with the engine's code."""
    answer = layer.gateway.chat(CallContext(user, roles, "console:" + feature), ChatRequest.of(question, **options))
    return answer.text


__all__ = ["ENGINE_PROVIDER", "EngineAiAdapter", "LlmError", "ask", "console_ai_layer", "settings_of"]
