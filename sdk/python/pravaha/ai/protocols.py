"""The AI layer's interfaces as ``typing.Protocol``s, mirroring the engine's ``LlmProvider`` /
``LlmModel`` / ``EmbeddingModel`` / ``LlmPolicy``. A new provider is one class with these methods and an
entry in ``pravaha.ai.providers.FACTORIES`` (or ``register``).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

from typing import Any, List, Optional, Protocol, runtime_checkable

from pravaha.ai.types import CallContext, ChatRequest, ChatResponse, ModelInfo, ProviderConfig


@runtime_checkable
class ChatModel(Protocol):
    info: ModelInfo

    def chat(self, request: ChatRequest) -> ChatResponse: ...


@runtime_checkable
class EmbeddingModel(Protocol):
    info: ModelInfo

    def embed(self, texts: List[str]) -> List[List[float]]: ...


@runtime_checkable
class LlmProvider(Protocol):
    id: str
    type: str

    def models(self) -> List[ModelInfo]: ...

    def chat_model(self, logical_name: str) -> Optional[ChatModel]: ...

    def embedding_model(self, logical_name: str) -> Optional[EmbeddingModel]: ...

    def close(self) -> None: ...


class ProviderFactory(Protocol):
    """``FACTORIES[type] = factory``: builds a provider from one parsed provider file. ``runtime`` gives
    access to what a provider needs from its host (the ``server`` provider's engine)."""

    def __call__(self, config: ProviderConfig, runtime: Any) -> LlmProvider: ...


class Policy(Protocol):
    def before(self, ctx: CallContext, model: ModelInfo, request: ChatRequest) -> ChatRequest: ...

    def after(self, ctx: CallContext, model: ModelInfo, request: ChatRequest, response: Optional[ChatResponse],
              error: Optional[Exception], millis: int) -> None: ...


__all__ = ["ChatModel", "EmbeddingModel", "LlmProvider", "Policy", "ProviderFactory"]
