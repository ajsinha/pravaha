"""The abstract LLM API: two abstract base classes shaped like an OpenAI client, name for name as in
sajhamcpserver's ``base.py`` (via Drishti) and, in camelCase, the engine's ``LLMModel`` / ``LLMProvider``.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

    LLMModel      one model: chat_completions_create / chat_completions_stream and their async twins,
                  embeddings_create / aembeddings_create, and info(). A chat model refuses embeddings and an
                  embedding model refuses chat, both with UnsupportedFeature.
    LLMProvider   a vendor or runtime: a factory for its LLMModel objects, its catalogue, health,
                  configuration and close.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING, Any, AsyncIterator, Dict, Iterator, List

if TYPE_CHECKING:  # pragma: no cover
    from pravaha.ai.llm.canonical import ChatCompletion, ChatCompletionChunk, EmbeddingsResponse
    from pravaha.ai.llm.model import HealthStatus, ModelInfo


class LLMModel(ABC):
    """One model behind an OpenAI-style interface. ``request`` is a canonical request, a dict, or keyword fields."""

    @abstractmethod
    def info(self) -> "ModelInfo":
        """The model as one entry of GET /v1/models."""

    @abstractmethod
    def chat_completions_create(self, request: Any = None, /, **fields: Any) -> "ChatCompletion":
        """POST /v1/chat/completions."""

    @abstractmethod
    async def achat_completions_create(self, request: Any = None, /, **fields: Any) -> "ChatCompletion":
        """The async twin of chat_completions_create."""

    @abstractmethod
    def chat_completions_stream(self, request: Any = None, /, **fields: Any) -> Iterator["ChatCompletionChunk"]:
        """POST /v1/chat/completions with stream=true, as chunks."""

    @abstractmethod
    def achat_completions_stream(self, request: Any = None, /, **fields: Any) -> AsyncIterator["ChatCompletionChunk"]:
        """The async twin of chat_completions_stream (an async generator)."""

    @abstractmethod
    def embeddings_create(self, request: Any = None, /, **fields: Any) -> "EmbeddingsResponse":
        """POST /v1/embeddings."""

    @abstractmethod
    async def aembeddings_create(self, request: Any = None, /, **fields: Any) -> "EmbeddingsResponse":
        """The async twin of embeddings_create."""


class LLMProvider(ABC):
    """A vendor or runtime: credentials, HTTP, a model catalogue, and a factory for its models."""

    name: str = ""

    @property
    @abstractmethod
    def active(self) -> bool:
        """Enabled."""

    @abstractmethod
    def models(self) -> List["ModelInfo"]:
        """Every model of this provider (like GET /v1/models)."""

    @abstractmethod
    def model(self, name: str = "", kind: str = "") -> LLMModel:
        """A model by id; ``kind`` (chat | embedding) defaults to what the catalogue says."""

    @abstractmethod
    def chat_model(self, model_id: str = "", **options: Any) -> LLMModel:
        """A chat model (the provider's default when ``model_id`` is empty)."""

    @abstractmethod
    def embedding_model(self, model_id: str = "") -> LLMModel:
        """An embedding model (the provider's default when ``model_id`` is empty)."""

    @abstractmethod
    def health(self) -> "HealthStatus":
        """Cheap and bounded: no call is made."""

    @abstractmethod
    def describe_config(self) -> Dict[str, Any]:
        """The effective configuration (secrets masked)."""

    @abstractmethod
    def close(self) -> None:
        """Release what it holds."""


__all__ = ["LLMModel", "LLMProvider"]
