"""LLMFactory and GovernedModel: the one door application code uses. ``LLMFactory.model(name)`` hands out a
``GovernedModel``: an ``LLMModel`` whose every call goes through the AI policy (masking, audit, limits,
feature roles, health: the ``LlmGateway``) before it reaches a provider.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import time
from typing import Any, Dict, Iterator, List, Optional, Tuple

from pravaha.ai.gateway import LlmGateway
from pravaha.ai.llm import errors as E
from pravaha.ai.llm.canonical import (ChatCompletion, ChatCompletionChunk, ChatCompletionRequest, EmbeddingsRequest,
                                      EmbeddingsResponse)
from pravaha.ai.llm.model import ChatModel, EmbeddingModel, ModelInfo
from pravaha.ai.llm.provider import ProviderBase, from_wire, info_of, to_wire
from pravaha.ai.types import CallContext, LlmError


class GovernedModel(ChatModel):
    """A chat (and, made with ``kind="embedding"``, embedding) model behind the policy."""

    def __init__(self, gateway: LlmGateway, ref: Optional[str], info: Optional[ModelInfo],
                 context: Optional[CallContext]) -> None:
        super().__init__(None, ref or "default", info)
        self._gw, self._ref, self._ctx = gateway, ref, context or CallContext()

    def _caller(self, request: ChatCompletionRequest) -> CallContext:
        return request.context if isinstance(request.context, CallContext) else self._ctx

    def _create(self, request: ChatCompletionRequest) -> ChatCompletion:
        ctx = self._caller(request)
        t0 = time.monotonic()
        try:
            resp = self._gw.chat(ctx, to_wire(request, model=self._ref))
            _, wire_model = self._gw.registry.chat(self._ref)
        except LlmError as e:
            raise E.from_wire(e, model=self._ref or "") from None
        return from_wire(resp, wire_model.info, int((time.monotonic() - t0) * 1000))

    def embeddings_create(self, request: Any = None, /, **fields: Any) -> EmbeddingsResponse:
        try:
            self._gw.require_enabled()
        except LlmError as e:
            raise E.from_wire(e) from None
        req = EmbeddingsRequest.coerce(request, **fields)
        ref = self._ref or req.model
        m = self._gw.registry.embedding(ref)
        if m is None:
            raise E.NoModelAvailable(f"no embedding model '{ref}' is configured", model=ref)
        try:
            vectors = m.embed(req.texts)
        except LlmError as e:
            raise E.from_wire(e, model=ref) from None

        class _Shim(EmbeddingModel):
            def _embed(self, texts: List[str]) -> List[List[float]]:
                return vectors
        return _Shim(None, m.info.name, info_of(m.info)).embeddings_create(req)


class LLMFactory:
    def __init__(self, gateway: LlmGateway) -> None:
        self.gateway = gateway

    def model(self, name: str = "default", *, kind: str = "", context: Optional[CallContext] = None) -> GovernedModel:
        """A governed model by ``provider/model``, a model name only one provider has, or (``default``) the
        layer's default. ``kind`` is chat or embedding."""
        try:
            self.gateway.require_enabled()
        except LlmError as e:
            raise E.from_wire(e) from None
        ref = None if not name or name == "default" else name
        if kind == "embedding":
            return GovernedModel(self.gateway, ref, ModelInfo(name, "", "embedding"), context)
        try:
            _, m = self.gateway.registry.chat(ref)
        except LlmError as e:
            raise E.from_wire(e, model=name) from None
        return GovernedModel(self.gateway, ref, info_of(m.info), context)

    def provider(self, name: str) -> Optional[ProviderBase]:
        e = next((x for x in self.gateway.registry.entries() if x.config.name == name and x.usable), None)
        return ProviderBase(e.config, e.provider) if e else None

    def provider_names(self) -> List[str]:
        return [e.config.name for e in self.gateway.registry.entries() if e.usable]

    @staticmethod
    def provider_types() -> Dict[str, str]:
        from pravaha.ai.llm.registry import registered_providers
        return {k: v.__name__ for k, v in registered_providers().items()}

    # passthrough to the model the request names, as the caller in the request (pravaha.context) or anonymous

    def _for(self, request: Any, fields: Dict[str, Any]) -> Tuple[ChatCompletionRequest, GovernedModel]:
        req = ChatCompletionRequest.coerce(request, **fields)
        return req, self.model(req.model, context=req.context if isinstance(req.context, CallContext) else None)

    def chat_completions_create(self, request: Any = None, /, **fields: Any) -> ChatCompletion:
        req, m = self._for(request, fields)
        return m.chat_completions_create(req)

    def chat_completions_stream(self, request: Any = None, /, **fields: Any) -> Iterator[ChatCompletionChunk]:
        req, m = self._for(request, fields)
        return m.chat_completions_stream(req)

    def embeddings_create(self, request: Any = None, /, **fields: Any) -> EmbeddingsResponse:
        req = EmbeddingsRequest.coerce(request, **fields)
        return self.model(req.model, kind="embedding").embeddings_create(req)


__all__ = ["GovernedModel", "LLMFactory"]
