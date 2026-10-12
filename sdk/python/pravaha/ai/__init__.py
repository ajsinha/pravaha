"""Pravaha's AI layer in Python: the provider and model interfaces, their implementations, the registry,
the policy and the gateway -- the same design, and the same names, as the engine's ``pravaha-ai`` module
(ADR-063, docs/design/architecture/ai.md), which ports Drishti's AI layer. Off by default.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

One package for every Python consumer -- the ``pravaha`` CLI, the SDK's assistant
(``pravaha.assist``, through its ``ai`` provider type) and the console -- so there is one implementation
of each provider type. Standard library only and synchronous (the console calls it from a worker
thread). The recommended client provider is ``server``: it delegates to the engine's
``POST /api/v1/ai/chat``, so keys, policy, masking, limits and audit stay in the engine::

    from pravaha.ai import AiLayer, CallContext, ChatRequest
    ai = AiLayer({"ai.enabled": True, "ai.dir": "~/.config/pravaha/ai"})
    answer = ai.gateway.chat(CallContext("ann", frozenset({"analyst"}), "explain"), ChatRequest.of("hello"))

The OpenAI-shaped second layer is ``pravaha.ai.llm`` (``LLMFactory``, ``LLMModel``, ``ProviderBase``).
"""

from __future__ import annotations

import json
import logging
import threading
from pathlib import Path
from typing import Any, Callable, Dict, List, Mapping, Optional

from pravaha.ai import config as cfgmod
from pravaha.ai.gateway import LlmGateway
from pravaha.ai.policy import DefaultPolicy, NoPolicy, PolicySettings, PromptMasker
from pravaha.ai.providers import FACTORIES, register
from pravaha.ai.registry import LlmRegistry, RetryBoard
from pravaha.ai.types import (CallContext, ChatMessage, ChatRequest, ChatResponse, LlmError, ModelInfo, ToolCall,
                              ToolSpec, Usage)

AUDIT_LOG = logging.getLogger("pravaha.ai.audit")


def _on(v: Any) -> bool:
    return str(v).lower() in ("true", "1", "yes", "on")


def _get(settings: Mapping[str, Any], key: str, default: Any) -> Any:
    v = settings.get(key)
    return default if v is None else v


class AiLayer:
    """Everything a Python host's AI layer needs, built once from flat settings (``ai.enabled``, ``ai.dir``,
    ``ai.default_model``, ``ai.poll_seconds``, ``ai.redact``, ``ai.policy.*``, and ``ai.providers``: name to a
    provider block, for a provider a host defines in code rather than in a file). ``runtime`` is handed to
    the provider factories (the ``server`` provider's ``engine``). While ``ai.enabled`` is false no file is
    read and every call is refused with ``PRV-1080``, as in the engine."""

    def __init__(self, settings: Optional[Mapping[str, Any]] = None, runtime: Any = None,
                 env: Optional[Callable[[str], Optional[str]]] = None) -> None:
        s: Mapping[str, Any] = settings or {}
        self.enabled = _on(_get(s, "ai.enabled", False))
        self.dir = str(Path(str(_get(s, "ai.dir", "config/ai"))).expanduser())
        self.poll_seconds = max(0.5, float(_get(s, "ai.poll_seconds", 5) or 5))
        redact = list(_get(s, "ai.redact", []) or [])
        patterns = list(_get(s, "ai.policy.mask_patterns", []) or [])
        features = _get(s, "ai.policy.features", {}) or {}
        ps = PolicySettings(mask_redacted=_on(_get(s, "ai.policy.mask_redacted", True)),
                            audit=_on(_get(s, "ai.policy.audit", True)),
                            per_user_per_minute=int(_get(s, "ai.policy.per_user_per_minute", 0) or 0),
                            per_user_per_day=int(_get(s, "ai.policy.per_user_per_day", 0) or 0),
                            cost_per_user_per_day=float(_get(s, "ai.policy.cost_per_user_per_day", 0) or 0),
                            feature_roles={k: list((v or {}).get("roles", [])) for k, v in features.items()},
                            feature_per_user_per_minute={k: int(v["per_user_per_minute"]) for k, v in features.items()
                                                         if (v or {}).get("per_user_per_minute") is not None})
        self.audit_log: List[Dict[str, Any]] = []
        self._lock = threading.Lock()
        self._env = env
        self._defined: Dict[str, Any] = dict(_get(s, "ai.providers", {}) or {})
        self.registry = LlmRegistry(FACTORIES, self._source, runtime, str(_get(s, "ai.default_model", "") or ""),
                                    RetryBoard())
        self.gateway = LlmGateway(self.registry, DefaultPolicy(ps, PromptMasker(redact, patterns), self._audit),
                                  lambda: self.enabled)
        from pravaha.ai.llm import LLMFactory  # the canonical door, over the same gateway and policy
        self.llm = LLMFactory(self.gateway)
        self._fingerprint: Any = None
        self.reload()

    def _source(self) -> Any:
        if not self.enabled:
            return [], {}
        configs, errors = cfgmod.load_providers(self.dir, self._env)
        have = {c.name for c in configs}
        for name, block in self._defined.items():
            if name in have:
                errors[name] = f"a file already defines provider '{name}'"
                continue
            try:
                configs.append(cfgmod.provider_from_dict(dict(block), name, self._env or cfgmod.env_getter()))
            except LlmError as e:
                errors[name] = e.message
        return configs, errors

    def _audit(self, record: Dict[str, Any]) -> None:
        with self._lock:
            self.audit_log.append(record)
            del self.audit_log[:-500]
        AUDIT_LOG.info("ai-audit %s", json.dumps(record, default=str, sort_keys=True))

    def reload(self) -> None:
        self.registry.reload()
        self._fingerprint = cfgmod.fingerprint(self.dir) if self.enabled else ()

    def tick(self) -> bool:
        """One look: reload when a file changed or a provider that failed is due for another try."""
        if not self.enabled:
            return False
        changed = cfgmod.fingerprint(self.dir) != self._fingerprint or bool(self.registry.retry.due())
        if changed:
            self.reload()
        return changed

    def status(self) -> Dict[str, Any]:
        """The layer as an administrator sees it: on or off, every provider with its health (no network call)."""
        rows = []
        for e in self.registry.entries():
            rows.append({"name": e.config.name, "type": e.config.type, "enabled": e.config.enabled,
                         "baseUrl": e.config.base_url, "keySet": bool(e.config.api_key), "health": self.registry.health(e),
                         "models": [m.to_dict() for m in e.provider.models()] if e.provider is not None else []})
        return {"enabled": self.enabled, "dir": self.dir, "types": self.registry.types(), "providers": rows,
                "fileErrors": dict(self.registry.file_errors)}

    def close(self) -> None:
        self.registry.close()


__all__ = ["AiLayer", "CallContext", "ChatMessage", "ChatRequest", "ChatResponse", "DefaultPolicy", "FACTORIES",
           "LlmError", "LlmGateway", "LlmRegistry", "ModelInfo", "NoPolicy", "PolicySettings", "PromptMasker",
           "RetryBoard", "ToolCall", "ToolSpec", "Usage", "register"]
