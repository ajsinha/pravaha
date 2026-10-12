"""ProviderBase: the shared implementation of ``LLMProvider`` over the wire-level providers in
``pravaha.ai.providers``, whose HTTP code is unchanged. The wire call is translated to and from the
canonical types here, once, so a provider is written once.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
import os
import time
from typing import Any, Callable, Dict, List, Optional

from pravaha.ai import config as cfgmod
from pravaha.ai.llm import errors as E
from pravaha.ai.llm.base import LLMModel, LLMProvider
from pravaha.ai.llm.canonical import (ChatCompletion, ChatCompletionRequest, ChatMessage, Choice, CompletionUsage,
                                      ResponsePravaha, ToolCall, ToolDefinition)
from pravaha.ai.llm.model import ChatModel, EmbeddingModel, HealthStatus, ModelCapabilities, ModelInfo
from pravaha.ai.types import CHAT, EMBEDDINGS, JSON_MODE, TOOL_CALLS
from pravaha.ai.types import ChatMessage as WireMessage
from pravaha.ai.types import ChatRequest, ChatResponse, LlmError, ProviderConfig, ToolSpec
from pravaha.ai.types import ModelInfo as WireInfo


def masked_settings(settings: Dict[str, str]) -> Dict[str, str]:
    """The provider file's other settings with every credential shown as ``********``."""
    from pravaha.ai.providers.http_base import SECRETISH
    return {k: ("********" if SECRETISH.match(k.split(".")[-1]) and v else v) for k, v in sorted(settings.items())}


def _schema_json(t: ToolDefinition) -> str:
    return json.dumps(t.function.parameters) if t.function and t.function.parameters else '{"type":"object"}'


def to_wire(req: ChatCompletionRequest, ctx: Optional[Dict[str, Any]] = None, model: Optional[str] = None,
            timeout: Optional[float] = None) -> ChatRequest:
    """The wire request for a canonical one. ``system`` / ``developer`` messages stay in ``messages`` (the
    providers fold them into the system text)."""
    msgs = tuple(WireMessage("system" if m.role in ("system", "developer") else m.role, m.text) for m in req.messages)
    tools = tuple(ToolSpec(t.name, (t.function.description or "") if t.function else "", _schema_json(t))
                  for t in req.tools or [])
    json_mode = bool(req.response_format and req.response_format.type != "text")
    return ChatRequest(model=model, system="", messages=msgs, tools=tools, temperature=req.temperature,
                       max_tokens=req.max_output_tokens, json_mode=json_mode, timeout=timeout, context=dict(ctx or {}))


def from_wire(resp: ChatResponse, winfo: WireInfo, millis: int = 0) -> ChatCompletion:
    calls = [ToolCall.of(c.id, c.name, c.arguments_json) for c in resp.tool_calls]
    finish = resp.finish_reason or ("tool_calls" if calls else "stop")
    return ChatCompletion(model=resp.model or winfo.id,
                          choices=[Choice(message=ChatMessage.assistant(resp.text, calls or None), finish_reason=finish)],
                          usage=CompletionUsage.of(resp.usage.prompt_tokens, resp.usage.completion_tokens),
                          pravaha=ResponsePravaha(provider=winfo.provider, qualified_model=winfo.ref,
                                                  cost_usd=resp.usage.cost, latency_ms=millis))


def info_of(w: WireInfo) -> ModelInfo:
    c = set(w.capabilities)
    caps = ModelCapabilities(chat=CHAT in c, tools=TOOL_CALLS in c, structured_output=JSON_MODE in c, streaming=True,
                             embedding=EMBEDDINGS in c, max_output_tokens=w.max_tokens or 4096,
                             input_cost_per_mtok=w.cost_per_1k_input * 1000, output_cost_per_mtok=w.cost_per_1k_output * 1000,
                             json_mode=True if JSON_MODE in c else None)
    return ModelInfo(w.name, w.provider, w.kind, caps, w.name, "config")


class _WireChat(ChatModel):
    def __init__(self, provider: "ProviderBase", wire_model: Any) -> None:
        super().__init__(provider, wire_model.info.name, info_of(wire_model.info))
        self._wire = wire_model

    def _create(self, request: ChatCompletionRequest) -> ChatCompletion:
        ctx = request.context
        t0 = time.monotonic()
        try:
            resp = self._wire.chat(to_wire(request, {"ident": getattr(ctx, "ident", None),
                                                     "feature": getattr(ctx, "feature", "other")}))
        except LlmError as e:
            raise E.from_wire(e, provider=self.provider.name, model=self.model_id) from None
        return from_wire(resp, self._wire.info, int((time.monotonic() - t0) * 1000))


class _WireEmbed(EmbeddingModel):
    def __init__(self, provider: "ProviderBase", wire_model: Any) -> None:
        super().__init__(provider, wire_model.info.name, info_of(wire_model.info))
        self._wire = wire_model

    def _embed(self, texts: List[str]) -> List[List[float]]:
        try:
            out: List[List[float]] = self._wire.embed(texts)
            return out
        except LlmError as e:
            raise E.from_wire(e, provider=self.provider.name, model=self.model_id) from None


class ProviderBase(LLMProvider):
    def __init__(self, cfg: ProviderConfig, wire: Any) -> None:
        self.cfg, self._wire = cfg, wire
        self.name = cfg.name  # the instance's name (a file name)
        self.type = cfg.type

    @classmethod
    def from_settings(cls, name: str, config: Optional[Dict[str, Any]] = None, *, runtime: Any = None,
                      env: Optional[Callable[[str], Optional[str]]] = None) -> "ProviderBase":
        """Python's ``from_settings``: build the provider from its settings block (what a provider file holds)."""
        from pravaha.ai.providers import FACTORIES
        data = dict(config or {})
        typ = str(data.get("type") or "").lower()
        if typ not in FACTORIES:
            raise E.ConfigurationError(f"no provider type '{typ}' is installed (have: {sorted(FACTORIES)})")
        try:
            cfg = cfgmod.provider_from_dict(data, name, env or os.environ.get)
            return cls(cfg, FACTORIES[typ](cfg, runtime))
        except LlmError as e:
            raise E.from_wire(e, provider=name) from None

    @property
    def active(self) -> bool:
        return bool(self.cfg.enabled)

    def models(self) -> List[ModelInfo]:
        return [info_of(m) for m in self._wire.models()]

    def model(self, name: str = "", kind: str = "") -> LLMModel:
        embedding = kind == "embedding" or (not kind and any(m.id == name and m.kind == "embedding" for m in self.models()))
        return self.embedding_model(name) if embedding else self.chat_model(name)

    def _default_chat(self) -> str:
        return self.cfg.default_model or next((m.id for m in self.models() if m.kind != "embedding"), "")

    def chat_model(self, model_id: str = "", **options: Any) -> ChatModel:
        mid = model_id or self._default_chat()
        w = self._wire.chat_model(mid)
        if w is None:
            raise E.NoModelAvailable(f"provider {self.name} has no chat model '{mid}'", provider=self.name, model=mid)
        return _WireChat(self, w)

    def embedding_model(self, model_id: str = "") -> EmbeddingModel:
        mid = model_id or next((m.id for m in self.models() if m.kind == "embedding"), "")
        w = self._wire.embedding_model(mid)
        if w is None:
            if self._wire.chat_model(mid) is not None:
                raise E.UnsupportedFeature(f"model {self.name}/{mid} is a chat model; it has no embeddings",
                                           provider=self.name, model=mid)
            raise E.NoModelAvailable(f"provider {self.name} has no embedding model '{mid}'", provider=self.name, model=mid)
        return _WireEmbed(self, w)

    def health(self) -> HealthStatus:
        """No network: ``enabled`` and the type's readiness check (a key, a region)."""
        if not self.cfg.enabled:
            return HealthStatus("down", "enabled: false")
        check = getattr(self._wire, "check", None)
        ok, why = check() if check else (True, "configured; no call is made to find out")
        return HealthStatus("ok" if ok else "down", why)

    def probe(self) -> HealthStatus:
        """One cheap live call to the endpoint (a model list) when the type has one; the readiness check otherwise."""
        if not self.cfg.enabled:
            return HealthStatus("down", "enabled: false")
        probe = getattr(self._wire, "probe", None)
        if probe is None:
            return self.health()
        ok, why = probe()
        return HealthStatus("ok" if ok else "down", why)

    def live_models(self) -> List[ModelInfo]:
        """The models the endpoint reports now that the file does not list, with source ``live``."""
        live = getattr(self._wire, "live_models", None)
        if live is None:
            return []
        known = {m.id for m in self._wire.models()} | {m.id for m in self.models()}
        return [ModelInfo(m["id"], self.name, m["kind"], ModelCapabilities(chat=m["kind"] != "embedding",
                                                                            embedding=m["kind"] == "embedding"),
                          m["id"], "live") for m in live() if m["id"] not in known]

    def describe_config(self) -> Dict[str, Any]:
        c = self.cfg
        return {"name": c.name, "type": c.type, "enabled": c.enabled, "baseUrl": c.base_url,
                "apiKey": "********" if c.api_key else "", "timeoutSeconds": c.timeout, "defaultModel": c.default_model,
                "models": [m.id for m in self.models()], "settings": masked_settings(c.settings)}

    def close(self) -> None:
        self._wire.close()


__all__ = ["ProviderBase", "from_wire", "info_of", "masked_settings", "to_wire"]
