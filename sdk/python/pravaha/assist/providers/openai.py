"""``openai`` and ``openai-compatible``: the Chat Completions API, standard library only.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

**Chat Completions, not Responses.** ``POST {base}/chat/completions`` is the one wire shape that
OpenAI *and* every server that imitates it speak -- vLLM, LM Studio, llama.cpp's server, LiteLLM
and most gateways -- so one implementation serves both built-ins, and a fix to one is a fix to
both. The Responses API is OpenAI's alone; nothing the assistant asks for (one turn, text or one
JSON object) needs what it adds.

The two differ only where the servers do:

* ``openai`` needs a key, defaults to ``https://api.openai.com/v1``, sends
  ``max_completion_tokens`` (what its newer models require) and asks for a schema natively with
  ``response_format: {type: json_schema}``;
* ``openai-compatible`` needs an ``endpoint`` (the base URL, ``.../v1``), takes a key only when
  one is configured, sends ``max_tokens`` (what the servers accept), and by default does *not*
  claim native schemas -- set the model option ``structured_output: json_schema`` for a server
  that honours ``response_format``; otherwise the base class asks for JSON in text.

A ``message.refusal`` or ``finish_reason: content_filter`` is :class:`ModelRefused`; ``429`` with
``insufficient_quota`` is :class:`ModelUnavailable` (waiting will not help; another model might).
"""

from __future__ import annotations

import urllib.parse
from typing import Any, Optional

from pravaha.assist._http import request_json
from pravaha.assist.errors import AssistConfigError, ModelError, ModelRefused, ModelUnavailable
from pravaha.assist.provider import (
    BaseProvider,
    Capabilities,
    ChatRequest,
    ChatResponse,
    ProviderSettings,
    Usage,
)

DEFAULT_ENDPOINT = "https://api.openai.com/v1"


def _classify(status: int, body: Any) -> Optional[ModelError]:
    error = body.get("error") if isinstance(body, dict) else None
    code = (error.get("code") or error.get("type")) if isinstance(error, dict) else None
    words = error.get("message") if isinstance(error, dict) else None
    if status == 429 and code == "insufficient_quota":
        return ModelUnavailable(f"quota exhausted: {words or code}", retryable=False)
    if code in ("content_policy_violation", "content_filter"):
        return ModelRefused(f"the model declined: {words or code}")
    return None


class OpenAIProvider(BaseProvider):
    name = "openai"
    requires_key = True
    default_capabilities = Capabilities(
        structured_output="json_schema",
        supports_system=True,
        supports_streaming=False,
        supports_prompt_cache=True,
    )
    #: The request field that caps the answer's length.
    max_tokens_field = "max_completion_tokens"

    @property
    def endpoint(self) -> str:
        return (self.settings.endpoint or DEFAULT_ENDPOINT).rstrip("/")

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.settings.api_key}"} if self.settings.api_key else {}

    def _send(self, request: ChatRequest, *, native_schema: bool) -> ChatResponse:
        messages: list[dict[str, str]] = []
        if request.system:
            messages.append({"role": "system", "content": request.system})
        messages.extend({"role": m.role, "content": m.content} for m in request.messages)
        body: dict[str, Any] = {"model": request.model, "messages": messages}
        if request.max_tokens:
            body[self.max_tokens_field] = int(request.max_tokens)
        if request.temperature is not None:
            body["temperature"] = request.temperature
        if request.stop:
            body["stop"] = list(request.stop)
        if native_schema and request.response_schema is not None:
            body["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": "answer",
                    "schema": dict(request.response_schema),
                    "strict": False,
                },
            }
        answer = request_json(
            "POST",
            self.endpoint + "/chat/completions",
            body=body,
            headers=self._headers(),
            timeout_s=request.timeout_s,
            provider=self.name,
            model=request.model,
            classify=_classify,
        )
        choices = answer.get("choices") if isinstance(answer, dict) else None
        if not choices or not isinstance(choices[0], dict):
            raise ModelUnavailable("the answer has no choices", provider=self.name,
                                   model=request.model)
        choice = choices[0]
        message = choice.get("message") or {}
        model = str(answer.get("model") or request.model)
        finish = str(choice.get("finish_reason") or "stop")
        if message.get("refusal"):
            raise ModelRefused(f"the model declined: {message['refusal']}", provider=self.name,
                               model=model)
        if finish == "content_filter":
            raise ModelRefused("the provider's content filter stopped the answer",
                               provider=self.name, model=model)
        usage = answer.get("usage") or {}
        details = usage.get("prompt_tokens_details") or {}
        return ChatResponse(
            text=str(message.get("content") or ""),
            model=model,
            usage=Usage(
                int(usage.get("prompt_tokens") or 0),
                int(usage.get("completion_tokens") or 0),
                int(details.get("cached_tokens") or 0),
            ),
            finish_reason=finish,
            raw=answer,
        )

    def ping(self, model: str) -> None:
        request_json(
            "GET",
            self.endpoint + "/models/" + urllib.parse.quote(model, safe=""),
            headers=self._headers(),
            timeout_s=self.settings.timeout_s,
            provider=self.name,
            model=model,
            classify=_classify,
        )


class OpenAICompatibleProvider(OpenAIProvider):
    name = "openai-compatible"
    requires_key = False
    default_capabilities = Capabilities(structured_output="none", supports_system=True)
    max_tokens_field = "max_tokens"

    def __init__(self, settings: Optional[ProviderSettings] = None) -> None:
        super().__init__(settings)
        if not self.settings.endpoint:
            raise AssistConfigError(
                f"provider {self.settings.id or self.name!r} (openai-compatible) needs an "
                f"endpoint: the server's base URL, such as http://localhost:8000/v1"
            )

    def ping(self, model: str) -> None:
        # Servers differ on /models/{id}; the list is what they all answer.
        request_json(
            "GET",
            self.endpoint + "/models",
            headers=self._headers(),
            timeout_s=self.settings.timeout_s,
            provider=self.name,
            model=model,
            classify=_classify,
        )


__all__ = ["OpenAICompatibleProvider", "OpenAIProvider"]
