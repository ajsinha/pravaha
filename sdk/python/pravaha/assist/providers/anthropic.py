"""``anthropic``: Claude through the Messages API, standard library only.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``POST {endpoint}/v1/messages`` with ``x-api-key`` and ``anthropic-version``. A schema is asked
for natively with ``output_config.format`` (``json_schema``) -- not a forced tool call, which
newer models refuse -- and still validated by :class:`BaseProvider`. ``stop_reason: "refusal"``
is :class:`ModelRefused`; ``529 overloaded_error`` is :class:`ModelUnavailable`. ``ping`` reads
``GET /v1/models/{model}``, which spends no tokens.
"""

from __future__ import annotations

import urllib.parse
from typing import Any, Optional

from pravaha.assist._http import request_json
from pravaha.assist.errors import ModelError, ModelRefused, ModelUnavailable
from pravaha.assist.provider import BaseProvider, Capabilities, ChatRequest, ChatResponse, Usage

DEFAULT_ENDPOINT = "https://api.anthropic.com"
API_VERSION = "2023-06-01"
#: ``max_tokens`` is required by the Messages API; this is sent when the request names none.
DEFAULT_MAX_TOKENS = 4096

_FINISH = {"end_turn": "stop", "stop_sequence": "stop", "max_tokens": "length", "tool_use": "tool"}


def _classify(status: int, body: Any) -> Optional[ModelError]:
    error = body.get("error") if isinstance(body, dict) else None
    kind = error.get("type") if isinstance(error, dict) else None
    words = error.get("message") if isinstance(error, dict) else None
    if status == 529 or kind == "overloaded_error":
        return ModelUnavailable(f"overloaded: {words or 'try again later'}")
    return None


class AnthropicProvider(BaseProvider):
    name = "anthropic"
    requires_key = True
    default_capabilities = Capabilities(
        structured_output="json_schema",
        supports_system=True,
        supports_streaming=False,
        supports_prompt_cache=True,
    )

    @property
    def endpoint(self) -> str:
        return (self.settings.endpoint or DEFAULT_ENDPOINT).rstrip("/")

    def _headers(self) -> dict[str, str]:
        return {"x-api-key": str(self.settings.api_key), "anthropic-version": API_VERSION}

    def _send(self, request: ChatRequest, *, native_schema: bool) -> ChatResponse:
        body: dict[str, Any] = {
            "model": request.model,
            "max_tokens": int(request.max_tokens or DEFAULT_MAX_TOKENS),
            "messages": [{"role": m.role, "content": m.content} for m in request.messages],
        }
        if request.system:
            body["system"] = request.system
        if request.temperature is not None:
            body["temperature"] = request.temperature
        if request.stop:
            body["stop_sequences"] = list(request.stop)
        if request.metadata.get("user"):
            body["metadata"] = {"user_id": str(request.metadata["user"])}
        if native_schema and request.response_schema is not None:
            body["output_config"] = {
                "format": {"type": "json_schema", "schema": dict(request.response_schema)}
            }
        answer = request_json(
            "POST",
            self.endpoint + "/v1/messages",
            body=body,
            headers=self._headers(),
            timeout_s=request.timeout_s,
            provider=self.name,
            model=request.model,
            classify=_classify,
        )
        if not isinstance(answer, dict):
            raise ModelUnavailable("the Messages API answered with no message", provider=self.name,
                                   model=request.model)
        model = str(answer.get("model") or request.model)
        stop = str(answer.get("stop_reason") or "")
        if stop == "refusal":
            details = answer.get("stop_details") or {}
            why = details.get("explanation") or details.get("category") or "declined"
            raise ModelRefused(f"the model declined: {why}", provider=self.name, model=model)
        text = "".join(
            str(block.get("text", ""))
            for block in answer.get("content") or []
            if isinstance(block, dict) and block.get("type") == "text"
        )
        usage = answer.get("usage") or {}
        return ChatResponse(
            text=text,
            model=model,
            usage=Usage(
                int(usage.get("input_tokens") or 0),
                int(usage.get("output_tokens") or 0),
                int(usage.get("cache_read_input_tokens") or 0),
            ),
            finish_reason=_FINISH.get(stop, stop or "stop"),
            raw=answer,
        )

    def ping(self, model: str) -> None:
        request_json(
            "GET",
            self.endpoint + "/v1/models/" + urllib.parse.quote(model, safe=""),
            headers=self._headers(),
            timeout_s=self.settings.timeout_s,
            provider=self.name,
            model=model,
            classify=_classify,
        )


__all__ = ["AnthropicProvider"]
