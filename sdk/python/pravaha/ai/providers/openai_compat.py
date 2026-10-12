"""``type: openai`` and its relatives: OpenAI's Chat Completions API over ``urllib``, and the presets
``groq``, ``together``, ``fireworks``, ``deepseek``, ``xai``, ``openrouter``, ``perplexity``,
``huggingface``, ``vllm``, ``lmstudio``, ``mistral`` and the generic ``openai_compatible`` (a base URL, the
variable a key is read from and a catalogue, ``llm_catalog.json``).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Settings: ``base-url`` (``openai``: https://api.openai.com; a preset: its own, with ``/v1`` in it),
``completions-path`` / ``chat-path``, ``embeddings-path``, ``max-tokens-param``, ``live-models``,
``models-path``, ``header.<Name>``, ``proxy.*``, ``tls.*``, ``retries``.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional, Tuple

from pravaha.ai.providers import catalog
from pravaha.ai.providers.base import CHAT_EXTRAS
from pravaha.ai.providers.http_base import JsonHttpProvider
from pravaha.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage


class OpenAiCompatibleProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        self.preset = catalog.preset(cfg.type)
        base = (cfg.base_url or (self.preset or {}).get("base-url") or ("" if self.preset else "https://api.openai.com")).rstrip("/")
        if not base:
            raise LlmError("config", "base-url is required for this type (the server's address, for example "
                           "http://localhost:8000/v1)")
        super().__init__(cfg, base)
        chat = cfg.setting("completions-path") or cfg.setting("chat-path") or (
            "/chat/completions" if self.preset else "/v1/chat/completions")
        self._url = base + chat
        self._embed_url = base + cfg.setting("embeddings-path", "/embeddings" if self.preset else "/v1/embeddings")
        self._tokens_param = cfg.setting("max-tokens-param", str((self.preset or {}).get("max-tokens-param", "max_tokens")))
        self._models_url = base + cfg.setting("models-path", "/models")

    def chat_url(self, info: ModelInfo) -> str:
        return self._url

    def embeddings_url(self, info: ModelInfo) -> str:
        return self._embed_url

    def wire_id(self, info: ModelInfo) -> str:
        """The ``model`` of the request body (Azure: the deployment)."""
        return info.id

    def _headers(self) -> Dict[str, str]:
        h = super()._headers()
        if self.cfg.api_key:
            h["Authorization"] = "Bearer " + self.cfg.api_key
        return h

    def _keyless(self) -> bool:
        return self.preset is None or not self.preset.get("requires-key", True)

    def check(self) -> Tuple[bool, str]:
        if not self._keyless() and not self.cfg.api_key:
            names = " or ".join((self.preset or {}).get("key-env") or []) or "api-key"
            return False, f"no api key: set {names} (or api-key: ${{ENV_NAME}})"
        return True, "configured; no call is made to find out"

    def probe(self) -> Tuple[bool, str]:
        ok, msg = self.check()
        if not ok:
            return ok, msg
        try:
            self.get(self._models_url, 5.0)
        except LlmError as e:
            return False, e.message
        return True, "the model list answered"

    def live_models(self) -> List[Dict[str, str]]:
        live = self.cfg.setting("live-models", "")
        if (live or ("true" if self._keyless() and self.preset else "false")).lower() != "true":
            return []
        j = self.get(self._models_url, 5.0)
        return [{"id": m["id"], "kind": "embedding" if "embed" in m["id"] else "chat"} for m in j.get("data") or [] if m.get("id")]

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        msgs: List[Dict[str, Any]] = []
        sys_text = self.system_text(req)
        if sys_text:
            msgs.append({"role": "system", "content": sys_text})
        msgs += [{"role": "assistant" if m.role == "assistant" else "user", "content": m.content}
                 for m in req.messages if m.role != "system"]
        body: Dict[str, Any] = {"model": self.wire_id(info), "messages": msgs,
                                self._tokens_param: self.max_tokens(req, info, 1024), "stream": False}
        t = self.temperature(req, info)
        if t is not None:
            body["temperature"] = t
        if req.json_mode:
            body["response_format"] = {"type": "json_object"}
        if req.tools:
            body["tools"] = [{"type": "function", "function": {"name": s.name, "description": s.description,
                                                              "parameters": json.loads(s.parameters_json_schema)}}
                             for s in req.tools]
        j = self.post(self.chat_url(info), body, req.timeout)
        choice = (j.get("choices") or [{}])[0]
        msg = choice.get("message") or {}
        calls = tuple(ToolCall(c.get("id", ""), (c.get("function") or {}).get("name", ""),
                               (c.get("function") or {}).get("arguments", "{}")) for c in msg.get("tool_calls") or [])
        text: Optional[str] = msg.get("content")
        if not isinstance(text, str) and not calls:
            raise LlmError("failed", "the model endpoint's answer has no message content")
        u = j.get("usage") or {}
        pi, co = int(u.get("prompt_tokens") or 0), int(u.get("completion_tokens") or 0)
        return ChatResponse(text or "", calls, j.get("model", info.id), choice.get("finish_reason") or "",
                            Usage(pi, co, info.cost(pi, co)))

    def embed(self, info: ModelInfo, texts: List[str]) -> List[List[float]]:
        j = self.post(self.embeddings_url(info), {"model": self.wire_id(info), "input": texts}, None)
        out = [d["embedding"] for d in sorted(j.get("data") or [], key=lambda d: d.get("index", 0))]
        if len(out) != len(texts):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out


def factory(cfg: ProviderConfig, runtime: Any = None) -> OpenAiCompatibleProvider:
    return OpenAiCompatibleProvider(cfg, runtime)


__all__ = ["OpenAiCompatibleProvider", "factory"]
