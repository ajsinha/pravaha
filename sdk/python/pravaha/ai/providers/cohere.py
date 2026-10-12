"""``type: cohere``: Cohere's v2 API over ``urllib`` (no SDK). ``POST {base-url}/v2/chat`` and
``/v2/embed`` with ``Authorization: Bearer``. Settings: ``base-url``, ``chat-path``, ``embed-path``,
``embedding-input-type``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Tuple

from pravaha.ai.providers.base import CHAT_EXTRAS
from pravaha.ai.providers.http_base import JsonHttpProvider
from pravaha.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

FINISH = {"COMPLETE": "stop", "STOP_SEQUENCE": "stop", "MAX_TOKENS": "length", "TOOL_CALL": "tool_calls",
          "ERROR": "stop", "TIMEOUT": "stop"}


class CohereProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        super().__init__(cfg, cfg.base_url or "https://api.cohere.com")
        self._chat = self.base + cfg.setting("chat-path", "/v2/chat")
        self._embed = self.base + cfg.setting("embed-path", "/v2/embed")

    def _headers(self) -> Dict[str, str]:
        h = super()._headers()
        if self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    def check(self) -> Tuple[bool, str]:
        if not self.cfg.api_key:
            return False, "no api key: set COHERE_API_KEY or api-key: ${COHERE_API_KEY}"
        return True, "configured; no call is made to find out"

    def live_models(self) -> List[Dict[str, str]]:
        j = self.get(f"{self.base}/v1/models", 5.0)
        return [{"id": m["name"], "kind": "embedding" if "embed" in (m.get("endpoints") or []) else "chat"}
                for m in j.get("models") or [] if m.get("name")]

    def probe(self) -> Tuple[bool, str]:
        ok, msg = self.check()
        if not ok:
            return ok, msg
        try:
            return True, f"the model list answered ({len(self.live_models())} models)"
        except LlmError as e:
            return False, e.message

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs: List[Dict[str, Any]] = []
        sys_text = self.system_text(req)
        if sys_text:
            msgs.append({"role": "system", "content": sys_text})
        msgs += [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content}
                 for m in req.messages if m.role != "system"]
        body: Dict[str, Any] = {"model": info.id, "messages": msgs, "max_tokens": self.max_tokens(req, info, 1024),
                                "stream": False}
        t = self.temperature(req, info)
        if t is not None:
            body["temperature"] = t
        if req.json_mode:
            body["response_format"] = {"type": "json_object"}
        if req.tools:
            body["tools"] = [{"type": "function", "function": {"name": s.name, "description": s.description,
                                                              "parameters": json.loads(s.parameters_json_schema)}}
                             for s in req.tools]
        j = self.post(self._chat, body, req.timeout)
        msg = j.get("message") or {}
        content = msg.get("content")
        text = ("".join(b.get("text", "") for b in content if b.get("type", "text") == "text") if isinstance(content, list)
                else (content or ""))
        calls = tuple(ToolCall(c.get("id", ""), (c.get("function") or {}).get("name", ""),
                               (c.get("function") or {}).get("arguments") or "{}") for c in msg.get("tool_calls") or [])
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        tok = (j.get("usage") or {}).get("tokens") or (j.get("usage") or {}).get("billed_units") or {}
        pi, co = int(tok.get("input_tokens") or 0), int(tok.get("output_tokens") or 0)
        finish = "tool_calls" if calls else FINISH.get(j.get("finish_reason", "COMPLETE"), "stop")
        return ChatResponse(text, calls, info.id, finish, Usage(pi, co, info.cost(pi, co)))

    def embed(self, info: ModelInfo, texts: List[str]) -> List[List[float]]:
        body = {"model": info.id, "texts": texts, "input_type": self.cfg.setting("embedding-input-type", "search_document"),
                "embedding_types": ["float"]}
        j = self.post(self._embed, body, None)
        e = j.get("embeddings")
        out = e.get("float") if isinstance(e, dict) else e
        if not isinstance(out, list) or len(out) != len(texts):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out


def factory(cfg: ProviderConfig, runtime: Any = None) -> CohereProvider:
    return CohereProvider(cfg, runtime)


__all__ = ["CohereProvider", "factory"]
