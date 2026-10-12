"""``type: gemini``: Google's Gemini API (Google AI Studio) over ``urllib`` (no SDK).
``POST {base-url}/{api-version}/models/{id}:generateContent`` with the key in ``x-goog-api-key``;
embeddings by ``:batchEmbedContents``. Settings: ``base-url``, ``api-version`` (v1beta), ``schema-mode``
(``json_schema`` or ``openapi``), ``embedding-task-type``. **Vertex AI (``platform: vertex``) is not built.**

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Tuple
from urllib.parse import quote

from pravaha.ai.providers.base import CHAT_EXTRAS
from pravaha.ai.providers.http_base import JsonHttpProvider
from pravaha.ai.types import ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

FINISH = {"STOP": "stop", "MAX_TOKENS": "length", "SAFETY": "content_filter", "RECITATION": "content_filter",
          "BLOCKLIST": "content_filter", "PROHIBITED_CONTENT": "content_filter"}
_OPENAPI_DROP = {"$schema", "additionalProperties", "$id", "$defs", "definitions", "default", "examples"}


def openapi_schema(s: Any) -> Any:
    """A JSON Schema reduced to Gemini's OpenAPI subset (``schema-mode: openapi``)."""
    if isinstance(s, list):
        return [openapi_schema(x) for x in s]
    if not isinstance(s, dict):
        return s
    return {k: openapi_schema(v) for k, v in s.items() if k not in _OPENAPI_DROP}


class GeminiProvider(JsonHttpProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        super().__init__(cfg, cfg.base_url or "https://generativelanguage.googleapis.com")
        if cfg.setting("platform", "ai_studio").lower() != "ai_studio":
            raise LlmError("config", "platform: vertex is not built; use the Gemini API (AI Studio) with an api key")
        self.version = cfg.setting("api-version", "v1beta")
        self.openapi = cfg.setting("schema-mode", "json_schema").lower() == "openapi"

    def _headers(self) -> Dict[str, str]:
        h = super()._headers()
        if self.cfg.api_key:
            h["x-goog-api-key"] = self.cfg.api_key
        return h

    def _model_url(self, model_id: str, verb: str) -> str:
        return f"{self.base}/{self.version}/models/{quote(model_id, safe='')}:{verb}"

    def check(self) -> Tuple[bool, str]:
        if not self.cfg.api_key:
            return False, "no api key: set GEMINI_API_KEY or api-key: ${GEMINI_API_KEY}"
        return True, "configured; no call is made to find out"

    def live_models(self) -> List[Dict[str, str]]:
        j = self.get(f"{self.base}/{self.version}/models", 5.0)
        out = []
        for m in j.get("models") or []:
            n = str(m.get("name", ""))
            n = n[len("models/"):] if n.startswith("models/") else n
            gen = m.get("supportedGenerationMethods") or []
            if n:
                out.append({"id": n, "kind": "embedding" if "embedContent" in gen or "batchEmbedContents" in gen else "chat"})
        return out

    def probe(self) -> Tuple[bool, str]:
        ok, msg = self.check()
        if not ok:
            return ok, msg
        try:
            return True, f"the model list answered ({len(self.live_models())} models)"
        except LlmError as e:
            return False, e.message

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        contents = [{"role": "model" if m.role == "assistant" else "user", "parts": [{"text": m.content}]}
                    for m in req.messages if m.role != "system"]
        gen: Dict[str, Any] = {"maxOutputTokens": self.max_tokens(req, info, 1024)}
        t = self.temperature(req, info)
        if t is not None:
            gen["temperature"] = t
        if req.json_mode:
            gen["responseMimeType"] = "application/json"
        body: Dict[str, Any] = {"contents": contents, "generationConfig": gen}
        sys_text = self.system_text(req)
        if sys_text:
            body["systemInstruction"] = {"parts": [{"text": sys_text}]}
        if req.tools:
            decls = []
            for s in req.tools:
                schema = json.loads(s.parameters_json_schema)
                d: Dict[str, Any] = {"name": s.name, "description": s.description}
                d["parameters" if self.openapi else "parametersJsonSchema"] = openapi_schema(schema) if self.openapi else schema
                decls.append(d)
            body["tools"] = [{"functionDeclarations": decls}]
        j = self.post(self._model_url(info.id, "generateContent"), body, req.timeout)
        cand = (j.get("candidates") or [{}])[0]
        parts = (cand.get("content") or {}).get("parts") or []
        text = "".join(p.get("text", "") for p in parts if isinstance(p.get("text"), str))
        calls = tuple(ToolCall(f"call_{i}", p["functionCall"].get("name", ""),
                               json.dumps(p["functionCall"].get("args") or {}, separators=(",", ":")))
                      for i, p in enumerate(p for p in parts if "functionCall" in p))
        if not text and not calls:
            if (j.get("promptFeedback") or {}).get("blockReason"):
                raise LlmError("failed", "the model endpoint blocked the prompt (" + str(j["promptFeedback"]["blockReason"]) + ")")
            raise LlmError("failed", "the model endpoint's answer has no text content")
        u = j.get("usageMetadata") or {}
        pi, co = int(u.get("promptTokenCount") or 0), int(u.get("candidatesTokenCount") or 0)
        finish = "tool_calls" if calls else FINISH.get(cand.get("finishReason", "STOP"), "stop")
        return ChatResponse(text, calls, j.get("modelVersion") or info.id, finish, Usage(pi, co, info.cost(pi, co)))

    def embed(self, info: ModelInfo, texts: List[str]) -> List[List[float]]:
        task = self.cfg.setting("embedding-task-type", "RETRIEVAL_DOCUMENT")
        reqs = [{"model": f"models/{info.id}", "content": {"parts": [{"text": t}]}, "taskType": task} for t in texts]
        j = self.post(self._model_url(info.id, "batchEmbedContents"), {"requests": reqs}, None)
        out = [e.get("values") or [] for e in j.get("embeddings") or []]
        if len(out) != len(texts):
            raise LlmError("failed", "the model endpoint returned the wrong number of embeddings")
        return out


def factory(cfg: ProviderConfig, runtime: Any = None) -> GeminiProvider:
    return GeminiProvider(cfg, runtime)


__all__ = ["GeminiProvider", "factory", "openapi_schema"]
