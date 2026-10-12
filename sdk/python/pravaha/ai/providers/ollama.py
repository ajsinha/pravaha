"""``type: ollama``: a local Ollama server over ``urllib``. No key is needed. ``POST {base-url}/api/chat``
(tools with object arguments; ids are made up as ``call_<n>``), ``/api/embed``, and ``GET /api/tags`` for
the models pulled on the host. Settings: ``base-url`` (http://localhost:11434; ``OLLAMA_HOST`` /
``OLLAMA_BASE_URL`` are read, and a bare ``host:port`` gets ``http://``), ``keep-alive``, ``num-ctx``,
``think``, ``options.<name>``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Tuple

from pravaha.ai.providers.base import CHAT_EXTRAS
from pravaha.ai.providers.http_base import JsonHttpProvider
from pravaha.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

FINISH = {"stop": "stop", "length": "length", "load": "stop", "unload": "stop"}


def normalize_base(url: str) -> str:
    url = (url or "http://localhost:11434").strip().rstrip("/")
    if "://" not in url:
        host = url if url and not url.startswith(":") else "localhost" + url
        url = "http://" + (host if ":" in host else host + ":11434")
    return url


def _scalar(v: Any) -> Any:
    s = str(v)
    for cast in (int, float):
        try:
            return cast(s)
        except ValueError:
            pass
    return {"true": True, "false": False}.get(s.lower(), s)


class OllamaProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        super().__init__(cfg, normalize_base(cfg.base_url))

    def _headers(self) -> Dict[str, str]:
        h = super()._headers()
        if self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    def live_models(self) -> List[Dict[str, str]]:
        j = self.get(self.base + "/api/tags", 5.0)
        out = []
        for m in j.get("models") or []:
            n = str(m.get("name") or m.get("model") or "")
            n = n[:-7] if n.endswith(":latest") else n
            if n:
                out.append({"id": n, "kind": "embedding" if "embed" in n else "chat"})
        return out

    def probe(self) -> Tuple[bool, str]:
        try:
            return True, f"{self.base}: {len(self.live_models())} model(s) pulled"
        except LlmError as e:
            return False, f"{self.base} is not answering ({e.message})"

    def _options(self, req: ChatRequest, info: ModelInfo) -> Dict[str, Any]:
        o = {k[8:]: _scalar(v) for k, v in self.cfg.settings.items() if k.startswith("options.")}
        o["num_predict"] = self.max_tokens(req, info, 1024)
        t = self.temperature(req, info)
        if t is not None:
            o["temperature"] = t
        if self.cfg.setting("num-ctx"):
            o["num_ctx"] = int(self.cfg.setting("num-ctx"))
        return o

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs: List[Dict[str, Any]] = []
        sys_text = self.system_text(req)
        if sys_text:
            msgs.append({"role": "system", "content": sys_text})
        msgs += [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content}
                 for m in req.messages if m.role != "system"]
        body: Dict[str, Any] = {"model": info.id, "messages": msgs, "stream": False, "options": self._options(req, info)}
        if req.json_mode:
            body["format"] = "json"
        if self.cfg.setting("keep-alive"):
            body["keep_alive"] = _scalar(self.cfg.setting("keep-alive"))
        if self.cfg.setting("think"):
            body["think"] = self.cfg.setting("think").lower() == "true"
        if req.tools:
            body["tools"] = [{"type": "function", "function": {"name": s.name, "description": s.description,
                                                              "parameters": json.loads(s.parameters_json_schema)}}
                             for s in req.tools]
        j = self.post(self.base + "/api/chat", body, req.timeout)
        msg = j.get("message") or {}
        text = msg.get("content") or ""
        calls = tuple(ToolCall(f"call_{i}", (c.get("function") or {}).get("name", ""),
                               json.dumps((c.get("function") or {}).get("arguments") or {}, separators=(",", ":")))
                      for i, c in enumerate(msg.get("tool_calls") or []))
        if not text and not calls:
            raise LlmError("failed", "the model endpoint's answer has no text content")
        pi, co = int(j.get("prompt_eval_count") or 0), int(j.get("eval_count") or 0)
        finish = "tool_calls" if calls else FINISH.get(j.get("done_reason", "stop"), "stop")
        return ChatResponse(text, calls, j.get("model") or info.id, finish, Usage(pi, co, info.cost(pi, co)))

    def embed(self, info: ModelInfo, texts: List[str]) -> List[List[float]]:
        j = self.post(self.base + "/api/embed", {"model": info.id, "input": texts}, None)
        out = j.get("embeddings")
        if not isinstance(out, list) or len(out) != len(texts):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out


def factory(cfg: ProviderConfig, runtime: Any = None) -> OllamaProvider:
    return OllamaProvider(cfg, runtime)


__all__ = ["OllamaProvider", "factory", "normalize_base"]
