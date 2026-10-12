"""``type: server``: delegates to the engine's AI API (``POST /api/v1/ai/chat``), so keys, policy,
masking, limits and audit live in one place -- the engine. The recommended provider for every client:
the CLI, the SDK, the console. The model ``id`` in the file is the engine's own reference
(``provider/model``); omit it (or ``default``) for the engine's default.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Which engine, and as whom: per call, ``request.context["engine"]`` (an ``EngineApi`` or ``RestClient``
acting as the signed-in person, which is how the console passes each person's session); else the
runtime's ``engine`` (an object or a callable returning one); else ``base-url`` and ``api-key`` (a token,
as ``${ENV}``) from the file. The engine's refusal comes back as the same kind with the engine's code.
"""

from __future__ import annotations

from typing import Any, Dict, Optional

from pravaha.ai.providers.base import CHAT_EXTRAS, BaseProvider
from pravaha.ai.types import CODES, ChatRequest, ChatResponse, LlmError, ModelInfo, ProviderConfig, ToolCall, Usage

_BY_CODE = {v: k for k, v in CODES.items()}
_BY_CODE["PRV-1080"] = "config"


class ServerProvider(BaseProvider):
    default_caps = CHAT_EXTRAS

    def __init__(self, cfg: ProviderConfig, runtime: Any = None) -> None:
        super().__init__(cfg)
        self._runtime = runtime
        self._own: Any = None

    def _engine(self, req: ChatRequest) -> Any:
        given = req.context.get("engine")
        if given is not None:
            return given
        engine = getattr(self._runtime, "engine", None)
        if engine is not None:
            return engine() if callable(engine) and not hasattr(engine, "post") and not hasattr(engine, "rest") else engine
        if self._own is None:
            if not self.cfg.base_url:
                raise LlmError("config", "the server provider needs the engine: base-url in its file, or an engine "
                               "given by its host")
            from pravaha.rest import RestClient
            self._own = RestClient(self.cfg.base_url, token=self.cfg.api_key or None,
                                   timeout_seconds=self.cfg.timeout,
                                   allow_insecure_token=self.cfg.setting("allow-insecure-token") == "true")
        return self._own

    def check(self) -> "tuple[bool, str]":
        if not self.cfg.base_url and getattr(self._runtime, "engine", None) is None:
            return True, "the engine is given per call by the host"
        return True, "delegates to the engine's /api/v1/ai/chat; no call is made to find out"

    def complete(self, info: ModelInfo, req: ChatRequest) -> ChatResponse:
        from pravaha.rest import ApiError
        engine = self._engine(req)
        rest = getattr(engine, "rest", engine)
        body: Dict[str, Any] = {"model": None if info.id in ("", info.name, "default") else info.id,
                                "system": req.system,
                                "messages": [{"role": m.role, "content": m.content} for m in req.messages],
                                "temperature": req.temperature, "maxTokens": req.max_tokens, "jsonMode": req.json_mode,
                                "feature": req.context.get("feature", "client"),
                                "timeoutSeconds": int(req.timeout) if req.timeout else None}
        try:
            j = rest.post("/api/v1/ai/chat", {k: v for k, v in body.items() if v is not None})
        except ApiError as e:
            code: Optional[str] = e.engine_code
            kind = "timeout" if e.status == 504 else _BY_CODE.get(code or "", "failed")
            raise LlmError(kind, e.message if e.status else "the engine could not be reached",
                           code if code in _BY_CODE else None) from None
        if not isinstance(j, dict):
            raise LlmError("failed", "the engine answered something unreadable")
        u = j.get("usage") or {}
        calls = tuple(ToolCall(c.get("id", ""), c.get("name", ""), c.get("argumentsJson", "{}"))
                      for c in j.get("toolCalls") or [])
        return ChatResponse(j.get("text", ""), calls, j.get("model", ""), j.get("finishReason") or "",
                            Usage(int(u.get("promptTokens") or 0), int(u.get("completionTokens") or 0),
                                  float(u.get("cost") or 0)))


def factory(cfg: ProviderConfig, runtime: Any = None) -> ServerProvider:
    return ServerProvider(cfg, runtime)


__all__ = ["ServerProvider", "factory"]
