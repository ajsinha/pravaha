"""``ollama``: a local model through Ollama's own ``/api/chat``, standard library only.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``POST {endpoint}/api/chat`` with ``stream: false``; the endpoint defaults to
``http://localhost:11434``. A schema goes in ``format`` (Ollama constrains the output to it) and
is still validated here. No key: an Ollama server is on the caller's own hardware, which is the
point of configuring one (ADR-058 §3, data minimisation). ``ping`` is ``POST /api/show``, which
answers whether the model is pulled without running it.
"""

from __future__ import annotations

from typing import Any

from pravaha.assist._http import request_json
from pravaha.assist.errors import ModelUnavailable
from pravaha.assist.provider import BaseProvider, Capabilities, ChatRequest, ChatResponse, Usage

DEFAULT_ENDPOINT = "http://localhost:11434"


class OllamaProvider(BaseProvider):
    name = "ollama"
    default_capabilities = Capabilities(structured_output="json_schema", supports_system=True)

    @property
    def endpoint(self) -> str:
        return (self.settings.endpoint or DEFAULT_ENDPOINT).rstrip("/")

    def _headers(self) -> dict[str, str]:
        # An Ollama behind an authenticating proxy is the one case for a key.
        return {"Authorization": f"Bearer {self.settings.api_key}"} if self.settings.api_key else {}

    def _send(self, request: ChatRequest, *, native_schema: bool) -> ChatResponse:
        messages: list[dict[str, str]] = []
        if request.system:
            messages.append({"role": "system", "content": request.system})
        messages.extend({"role": m.role, "content": m.content} for m in request.messages)
        options: dict[str, Any] = {}
        if request.temperature is not None:
            options["temperature"] = request.temperature
        if request.max_tokens:
            options["num_predict"] = int(request.max_tokens)
        if request.stop:
            options["stop"] = list(request.stop)
        body: dict[str, Any] = {"model": request.model, "messages": messages, "stream": False}
        if options:
            body["options"] = options
        if native_schema and request.response_schema is not None:
            body["format"] = dict(request.response_schema)
        answer = request_json(
            "POST",
            self.endpoint + "/api/chat",
            body=body,
            headers=self._headers(),
            timeout_s=request.timeout_s,
            provider=self.name,
            model=request.model,
        )
        if not isinstance(answer, dict) or not isinstance(answer.get("message"), dict):
            raise ModelUnavailable("the answer has no message", provider=self.name,
                                   model=request.model)
        return ChatResponse(
            text=str(answer["message"].get("content") or ""),
            model=str(answer.get("model") or request.model),
            usage=Usage(int(answer.get("prompt_eval_count") or 0), int(answer.get("eval_count") or 0)),
            finish_reason=str(answer.get("done_reason") or "stop"),
            raw=answer,
        )

    def ping(self, model: str) -> None:
        request_json(
            "POST",
            self.endpoint + "/api/show",
            body={"model": model},
            headers=self._headers(),
            timeout_s=self.settings.timeout_s,
            provider=self.name,
            model=model,
        )


__all__ = ["OllamaProvider"]
