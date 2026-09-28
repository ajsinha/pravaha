"""``fake``: scripted answers, for tests and for the evaluation harness's controls.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

It sends nothing anywhere. Each call takes the next reply from its script; the last reply is
repeated once the script runs out. A reply is text, or an object::

    {"text": "...", "usage": {"input_tokens": 10, "output_tokens": 5}, "finish_reason": "stop"}
    {"error": "unavailable" | "rate_limited" | "refused" | "output", "message": "...",
     "retry_after": 3}

Configured, the script is the provider option ``replies``; from Python,
``FakeProvider(replies=[...])``. ``structured_output`` (default ``none``) says which path of
the base class's schema handling to exercise; ``ping: "fail"`` makes ``ping`` fail. Every request
is kept in :attr:`FakeProvider.requests`, so a test can see what would have been sent.
"""

from __future__ import annotations

import threading
from typing import Any, Mapping, Optional, Sequence

from pravaha.assist.errors import (
    ModelError,
    ModelOutputError,
    ModelRateLimited,
    ModelRefused,
    ModelUnavailable,
)
from pravaha.assist.provider import (
    BaseProvider,
    Capabilities,
    ChatRequest,
    ChatResponse,
    ProviderSettings,
    StructuredOutput,
    Usage,
)


def _error(reply: Mapping[str, Any], model: str) -> ModelError:
    kind = str(reply.get("error"))
    words = str(reply.get("message") or f"scripted {kind}")
    who: dict[str, Any] = {"provider": "fake", "model": model}
    if kind == "rate_limited":
        return ModelRateLimited(words, retry_after=reply.get("retry_after"), **who)
    if kind == "refused":
        return ModelRefused(words, **who)
    if kind == "output":
        return ModelOutputError(words, **who)
    return ModelUnavailable(words, **who)


class FakeProvider(BaseProvider):
    name = "fake"

    def __init__(
        self,
        settings: Optional[ProviderSettings] = None,
        *,
        replies: Optional[Sequence[Any]] = None,
        structured_output: Optional[StructuredOutput] = None,
    ) -> None:
        super().__init__(settings)
        options = self.settings.options
        self.replies: list[Any] = list(replies if replies is not None else options.get("replies", []))
        chosen = structured_output or options.get("structured_output") or "none"
        self.default_capabilities = Capabilities(structured_output=chosen)
        self.requests: list[ChatRequest] = []
        self.pings: list[str] = []
        self._lock = threading.Lock()
        self._next = 0

    def _take(self) -> Any:
        with self._lock:
            if not self.replies:
                return {"error": "unavailable", "message": "the fake provider has no replies"}
            reply = self.replies[min(self._next, len(self.replies) - 1)]
            self._next += 1
            return reply

    def _send(self, request: ChatRequest, *, native_schema: bool) -> ChatResponse:
        with self._lock:
            self.requests.append(request)
        reply = self._take()
        if isinstance(reply, Mapping) and reply.get("error"):
            raise _error(reply, request.model)
        text = str(reply.get("text", "")) if isinstance(reply, Mapping) else str(reply)
        stated = reply.get("usage") if isinstance(reply, Mapping) else None
        if isinstance(stated, Mapping):
            usage = Usage(int(stated.get("input_tokens", 0)), int(stated.get("output_tokens", 0)))
        else:
            sent = len(request.system or "") + sum(len(m.content) for m in request.messages)
            usage = Usage(sent // 4 + 1, len(text) // 4 + 1)
        finish = str(reply.get("finish_reason", "stop")) if isinstance(reply, Mapping) else "stop"
        return ChatResponse(text=text, model=request.model or "fake", usage=usage,
                            finish_reason=finish, raw={"reply": reply})

    def ping(self, model: str) -> None:
        self.pings.append(model)
        if self.settings.options.get("ping") == "fail":
            raise ModelUnavailable("scripted ping failure", provider=self.name, model=model)


__all__ = ["FakeProvider"]
