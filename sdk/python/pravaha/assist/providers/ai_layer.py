"""``ai``, ``server`` and ``mock``: the assistant's providers on the shared AI layer (``pravaha.ai``, ADR-063).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The assistant keeps its own contract -- the ``ModelProvider`` protocol, the structured-output fallback
and its one repair turn, the four normalised errors, the router's chains and budgets, the engine as the
judge of every draft -- and gains, through one adapter, every provider type the engine's AI layer has:

* ``server`` -- the engine's own AI layer (``POST /api/v1/ai/chat``): keys, policy, masking, limits and the
  audit row stay in the engine. The recommended provider: ``endpoint`` is the engine's HTTP URL,
  ``api_key_env`` names the variable holding your engine token, ``model`` is the engine's
  ``provider/model`` (or ``default``);
* ``mock`` -- the layer's deterministic provider, regex ``rules`` -> ``reply`` / ``tool`` / ``error`` /
  ``latency-ms``, ``fail-every``: the ``fake`` provider's role, configured the way the engine's mock is;
* ``ai`` -- any other type of the layer, named by the option ``type``: ``gemini``, ``cohere``,
  ``bedrock``, ``azure_openai``, ``mistral``, ``groq``, ... -- each over the standard library, as the
  built-in five are. ``endpoint`` is its ``base-url``, ``api_key_env`` its key; every other option goes
  to the provider's settings block unchanged (``region``, ``api-style``, ``deployments`` ...).

The layer's failures map to the assistant's four: ``rate_limited`` is :class:`ModelRateLimited`, a
timeout and a failure are :class:`ModelUnavailable` (retryable), and a configuration problem, an unknown
model, an unsupported request or a refusal by the engine's policy are :class:`ModelUnavailable` that is
not retryable -- so a chain still falls through to its next model, and nothing is guessed.
"""

from __future__ import annotations

import threading
from typing import Any, Dict, Optional

from pravaha.ai.providers import FACTORIES
from pravaha.ai.types import ChatMessage as LayerMessage
from pravaha.ai.types import ChatRequest as LayerRequest
from pravaha.ai.types import LlmError
from pravaha.assist.errors import AssistConfigError, ModelError, ModelRateLimited, ModelUnavailable
from pravaha.assist.provider import (
    BaseProvider,
    Capabilities,
    ChatRequest,
    ChatResponse,
    ProviderSettings,
    Usage,
)

#: Options the assistant reads itself; the rest go to the layer's settings block.
_ASSIST_OPTIONS = ("structured_output", "max_context_tokens", "max_output_tokens", "type")
#: The layer's ``mock.*`` settings, given as options of a ``mock`` provider.
_MOCK_OPTIONS = ("reply", "latency-ms", "fail", "fail-every", "dimensions", "rules")


def normalise(e: LlmError, provider: str, model: str) -> ModelError:
    """One of the layer's failures as one of the assistant's four."""
    who: Dict[str, Any] = {"provider": provider, "model": model or None}
    words = f"{e.message} ({e.code})"
    if e.kind == "rate_limited":
        return ModelRateLimited(words, **who)
    if e.kind in ("timeout", "failed"):
        return ModelUnavailable(words, **who)
    return ModelUnavailable(words, retryable=False, **who)


class AiLayerProvider(BaseProvider):
    """An assistant provider over one provider type of the shared AI layer."""

    name = "ai"
    default_capabilities = Capabilities(structured_output="none", supports_system=True)
    #: The layer's type this class always builds, or None to read it from the option ``type``.
    layer_type: Optional[str] = None

    def __init__(self, settings: Optional[ProviderSettings] = None) -> None:
        super().__init__(settings)
        typ = self.layer_type or str(self.settings.options.get("type") or "")
        if not typ:
            raise AssistConfigError(
                f"provider {self.settings.id or self.name!r} (ai) needs the option 'type': the AI layer's "
                f"provider type, one of {', '.join(sorted(FACTORIES))}"
            )
        if typ.lower() not in FACTORIES:
            raise AssistConfigError(
                f"provider {self.settings.id or self.name!r}: the AI layer has no type {typ!r}; "
                f"known: {', '.join(sorted(FACTORIES))}"
            )
        self.layer_type_name = typ.lower()
        self._wires: Dict[str, Any] = {}
        self._lock = threading.Lock()

    def _block(self, model: str) -> Dict[str, Any]:
        from pravaha.assist.config import _thaw
        options = {k: _thaw(v) for k, v in self.settings.options.items() if k not in _ASSIST_OPTIONS}
        block: Dict[str, Any] = {"type": self.layer_type_name, "catalogue": False,
                                 "timeout": f"{max(1, int(self.settings.timeout_s))}s",
                                 "models": [{"name": "assist", "id": model or "default"}]}
        if self.layer_type_name == "mock":
            mock = {k: options.pop(k) for k in list(options) if k in _MOCK_OPTIONS}
            block["mock"] = mock
        block.update(options)
        if self.settings.endpoint:
            block["base-url"] = self.settings.endpoint
        if self.settings.api_key:
            block["api-key"] = "${PRAVAHA_ASSIST_KEY}"  # resolved below from the key the configuration read
        return block

    def _wire(self, model: str) -> Any:
        """The layer's provider for ``model``, built once per model id."""
        with self._lock:
            wire = self._wires.get(model)
            if wire is None:
                from pravaha.ai import config as cfgmod
                key = self.settings.api_key or ""
                try:
                    cfg = cfgmod.provider_from_dict(self._block(model), self.settings.id or self.name,
                                                    {"PRAVAHA_ASSIST_KEY": key}.get)
                    wire = FACTORIES[self.layer_type_name](cfg, None)
                except LlmError as e:
                    raise AssistConfigError(f"provider {self.settings.id or self.name!r} "
                                            f"({self.layer_type_name}): {e.message}") from None
                self._wires[model] = wire
            return wire

    def _send(self, request: ChatRequest, *, native_schema: bool) -> ChatResponse:
        model = request.model
        bound = self._wire(model).chat_model("assist")
        layer = LayerRequest(model=None, system=request.system or "",
                             messages=tuple(LayerMessage(m.role, m.content) for m in request.messages),
                             temperature=request.temperature, max_tokens=request.max_tokens,
                             timeout=request.timeout_s, context={"feature": "assist"})
        try:
            answer = bound.chat(layer)
        except LlmError as e:
            raise normalise(e, self.name, model) from None
        return ChatResponse(
            text=answer.text,
            model=answer.model or model or self.layer_type_name,
            usage=Usage(answer.usage.prompt_tokens, answer.usage.completion_tokens),
            finish_reason=answer.finish_reason or "stop",
            raw={"layer": self.layer_type_name, "finish_reason": answer.finish_reason},
        )

    def ping(self, model: str) -> None:
        wire = self._wire(model)
        ok, why = wire.probe()
        if not ok:
            raise ModelUnavailable(f"{self.layer_type_name}: {why}", retryable=False, provider=self.name,
                                   model=model)


class ServerProvider(AiLayerProvider):
    """``server``: the engine's AI layer, so keys and policy stay in the engine (ADR-063)."""

    name = "server"
    layer_type = "server"

    def __init__(self, settings: Optional[ProviderSettings] = None) -> None:
        super().__init__(settings)
        if not self.settings.endpoint:
            raise AssistConfigError(
                f"provider {self.settings.id or self.name!r} (server) needs an endpoint: the engine's HTTP "
                f"URL, such as https://engine:18080"
            )

    def _block(self, model: str) -> Dict[str, Any]:
        block = super()._block(model)
        if self.settings.endpoint and self.settings.endpoint.startswith("http://"):
            # A token over plaintext is refused by the SDK unless asked for: the configuration says so
            # with the option allow-insecure-token (a loopback engine in a test).
            block.setdefault("allow-insecure-token", self.settings.options.get("allow-insecure-token", False))
        return block


class MockProvider(AiLayerProvider):
    """``mock``: the layer's deterministic provider -- regex rules, replies, tools, errors, latency."""

    name = "mock"
    layer_type = "mock"

    def ping(self, model: str) -> None:
        return None


__all__ = ["AiLayerProvider", "MockProvider", "ServerProvider", "normalise"]
