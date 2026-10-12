"""Value types of the AI layer; they mirror the engine's ``pravaha-ai`` module (ADR-063).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Ported from Drishti's console layer (``core/ai/types.py``), itself the Python twin of ``drishti-ai``.
"""

from __future__ import annotations

import dataclasses
from typing import Any, FrozenSet, Optional, Tuple

CHAT, STREAMING, TOOL_CALLS, EMBEDDINGS, JSON_MODE = "chat", "streaming", "tool-calls", "embeddings", "json-mode"
CAPABILITIES = (CHAT, STREAMING, TOOL_CALLS, EMBEDDINGS, JSON_MODE)

#: The kinds of failure, as the engine's ``LlmException.Kind`` names them (lower case here).
KINDS = ("config", "not_found", "unsupported", "policy", "rate_limited", "timeout", "failed")

#: The engine's code for each kind (``PRV-1080`` is the master switch).
CODES = {"config": "PRV-1081", "not_found": "PRV-1082", "unsupported": "PRV-1083", "policy": "PRV-1084",
         "rate_limited": "PRV-1085", "timeout": "PRV-1086", "failed": "PRV-1087"}


class LlmError(Exception):
    """A model call or a provider set-up failed. ``kind`` is config, not_found, unsupported, policy,
    rate_limited, timeout or failed; ``code`` is the engine's ``PRV`` code for it. The message never
    carries a key, a prompt or a vendor's error body."""

    def __init__(self, kind: str, message: str, code: Optional[str] = None) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.code = code or CODES.get(kind, "PRV-1087")

    @property
    def timeout(self) -> bool:
        return self.kind == "timeout"

    @staticmethod
    def disabled() -> "LlmError":
        """The refusal every call gets while the layer is switched off."""
        return LlmError("config", "the AI layer is switched off (ai.enabled is false); no provider file is read "
                        "and no model is called", "PRV-1080")


@dataclasses.dataclass(frozen=True)
class ChatMessage:
    role: str  # system | user | assistant | tool
    content: str = ""


@dataclasses.dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str = ""
    parameters_json_schema: str = '{"type":"object"}'


@dataclasses.dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments_json: str = "{}"


@dataclasses.dataclass(frozen=True)
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost: float = 0.0

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens


@dataclasses.dataclass(frozen=True)
class ChatRequest:
    """A chat call, independent of any provider. ``model`` is ``provider/model``, a unique model name,
    or None for the default. ``context`` carries what a provider may need from the caller (the
    ``server`` provider takes the person's engine session from it)."""

    model: Optional[str] = None
    system: str = ""
    messages: Tuple[ChatMessage, ...] = ()
    tools: Tuple[ToolSpec, ...] = ()
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    json_mode: bool = False
    timeout: Optional[float] = None
    context: "dict[str, Any]" = dataclasses.field(default_factory=dict, compare=False, repr=False)

    @staticmethod
    def of(user: str, system: str = "", model: Optional[str] = None, max_tokens: Optional[int] = None,
           timeout: Optional[float] = None, **ctx: Any) -> "ChatRequest":
        return ChatRequest(model=model, system=system, messages=(ChatMessage("user", user),),
                           max_tokens=max_tokens, timeout=timeout, context=dict(ctx))

    def prompt_chars(self) -> int:
        return len(self.system) + sum(len(m.content) for m in self.messages)


@dataclasses.dataclass(frozen=True)
class ChatResponse:
    text: str = ""
    tool_calls: Tuple[ToolCall, ...] = ()
    model: str = ""
    finish_reason: str = ""
    usage: Usage = Usage()


@dataclasses.dataclass(frozen=True)
class ModelInfo:
    name: str  # the logical name
    provider: str
    id: str  # the provider's own id
    kind: str = "chat"  # chat | embedding
    capabilities: Tuple[str, ...] = (CHAT,)
    max_tokens: Optional[int] = None
    temperature: Optional[float] = None
    cost_per_1k_input: float = 0.0
    cost_per_1k_output: float = 0.0

    @property
    def ref(self) -> str:
        return f"{self.provider}/{self.name}"

    @property
    def embedding(self) -> bool:
        return self.kind == "embedding"

    def cost(self, prompt_tokens: int, completion_tokens: int) -> float:
        return prompt_tokens / 1000 * self.cost_per_1k_input + completion_tokens / 1000 * self.cost_per_1k_output

    def to_dict(self) -> "dict[str, Any]":
        d = dataclasses.asdict(self)
        d["ref"] = self.ref
        d["capabilities"] = list(self.capabilities)
        return d


@dataclasses.dataclass(frozen=True)
class CallContext:
    """Who is calling and for which feature; the policy decides on this, never the provider.
    ``ident`` is whatever the caller's provider needs to act as that person (the console's engine
    session, for the ``server`` provider)."""

    user: str = "anonymous"
    roles: FrozenSet[str] = frozenset()
    feature: str = "other"
    ident: Any = None


@dataclasses.dataclass(frozen=True)
class ProviderConfig:
    name: str
    type: str
    enabled: bool = True
    base_url: str = ""
    api_key: str = dataclasses.field(default="", repr=False)
    timeout: float = 60.0
    retries: int = 0
    default_model: str = ""
    settings: "dict[str, str]" = dataclasses.field(default_factory=dict)
    models: "Tuple[dict[str, Any], ...]" = ()

    def setting(self, key: str, default: str = "") -> str:
        v = self.settings.get(key)
        return default if v is None or str(v).strip() == "" else str(v)


__all__ = ["CAPABILITIES", "CHAT", "CODES", "CallContext", "ChatMessage", "ChatRequest", "ChatResponse", "EMBEDDINGS",
           "JSON_MODE", "KINDS", "LlmError", "ModelInfo", "ProviderConfig", "STREAMING", "TOOL_CALLS", "ToolCall",
           "ToolSpec", "Usage"]
