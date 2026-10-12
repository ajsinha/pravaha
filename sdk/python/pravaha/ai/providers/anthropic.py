"""``type: anthropic``: the Anthropic Messages API over ``urllib`` (no SDK). Settings: ``base-url``
(https://api.anthropic.com), ``messages-path`` (/v1/messages), ``anthropic-version`` (2023-06-01),
``header.<Name>``, ``tls.*``, ``proxy.*``, ``retries``. On the shared HTTP base, so a 429 is
``rate_limited`` like every other type's.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Tuple

from pravaha.ai.providers.http_base import JsonHttpProvider
from pravaha.ai.types import TOOL_CALLS, ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

_FINISH = {"end_turn": "stop", "stop_sequence": "stop", "max_tokens": "length", "tool_use": "tool_calls"}


class AnthropicProvider(JsonHttpProvider):
    default_caps = (TOOL_CALLS,)

    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        super().__init__(cfg, cfg.base_url or "https://api.anthropic.com")
        self._url = self.base + cfg.setting("messages-path", "/v1/messages")

    def _headers(self) -> Dict[str, str]:
        h = super()._headers()
        h["anthropic-version"] = self.cfg.setting("anthropic-version", "2023-06-01")
        if self.cfg.api_key:
            h["x-api-key"] = self.cfg.api_key
        return h

    def check(self) -> Tuple[bool, str]:
        if not self.cfg.api_key:
            return False, "no api key: set api-key: ${ANTHROPIC_API_KEY}"
        return True, "configured; no call is made to find out"

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        body: Dict[str, Any] = {"model": info.id, "max_tokens": self.max_tokens(req, info, 1024),
                                "messages": [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content}
                                             for m in req.messages if m.role != "system"]}
        sys_text = self.system_text(req)
        if sys_text:
            body["system"] = sys_text
        t = self.temperature(req, info)
        if t is not None:
            body["temperature"] = t
        if req.tools:
            body["tools"] = [{"name": s.name, "description": s.description, "input_schema": json.loads(s.parameters_json_schema)}
                             for s in req.tools]
        j = self.post(self._url, body, req.timeout)
        blocks: List[Dict[str, Any]] = j.get("content") or []
        text = "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
        calls = tuple(ToolCall(b.get("id", ""), b.get("name", ""), json.dumps(b.get("input") or {}, separators=(",", ":")))
                      for b in blocks if b.get("type") == "tool_use")
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        u = j.get("usage") or {}
        pi, co = int(u.get("input_tokens") or 0), int(u.get("output_tokens") or 0)
        stop = j.get("stop_reason") or ""
        return ChatResponse(text, calls, j.get("model", info.id), _FINISH.get(stop, stop), Usage(pi, co, info.cost(pi, co)))


def factory(cfg: ProviderConfig, runtime: Any = None) -> AnthropicProvider:
    return AnthropicProvider(cfg, runtime)


__all__ = ["AnthropicProvider", "factory"]
