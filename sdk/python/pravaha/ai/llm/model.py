"""Model metadata and the shared model implementations: ModelCapabilities, ModelInfo, HealthStatus,
ChatModel, EmbeddingModel. ``ChatModel`` implements ``LLMModel`` for chat: a subclass writes ``_create``
and the other calls follow; streaming is emulated from one completion; the async twins run on a thread.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import asyncio
import dataclasses
from typing import Any, AsyncIterator, Dict, FrozenSet, Iterator, List, Optional

from pravaha.ai.llm.base import LLMModel
from pravaha.ai.llm.canonical import (ChatCompletion, ChatCompletionChunk, ChatCompletionRequest, EmbeddingData,
                                      EmbeddingsRequest, EmbeddingsResponse, EmbeddingsUsage, completion_to_chunks)
from pravaha.ai.llm.errors import UnsupportedFeature


@dataclasses.dataclass(frozen=True)
class ModelCapabilities:
    chat: bool = True
    tools: bool = False
    structured_output: bool = False
    vision: bool = False
    streaming: bool = True
    embedding: bool = False
    context_window: int = 0
    max_output_tokens: int = 4096
    input_cost_per_mtok: float = 0.0
    output_cost_per_mtok: float = 0.0
    dimensions: int = 0
    temperature: bool = True
    forced_tool_choice: bool = True
    tags: FrozenSet[str] = frozenset()
    json_mode: Optional[bool] = None

    def cost(self, input_tokens: int, output_tokens: int) -> float:
        return (input_tokens * self.input_cost_per_mtok + output_tokens * self.output_cost_per_mtok) / 1_000_000

    def to_dict(self) -> Dict[str, Any]:
        d = dataclasses.asdict(self)
        d["tags"] = sorted(self.tags)
        return d


@dataclasses.dataclass(frozen=True)
class ModelInfo:
    """One model as ``GET /v1/models`` would list it."""

    id: str
    provider: str
    kind: str = "chat"
    capabilities: ModelCapabilities = dataclasses.field(default_factory=ModelCapabilities)
    display_name: str = ""
    source: str = "config"

    @property
    def qualified_id(self) -> str:
        return f"{self.provider}/{self.id}"

    def to_dict(self) -> Dict[str, Any]:
        return {"id": self.qualified_id, "object": "model", "owned_by": self.provider,
                "pravaha": {"model": self.id, "kind": self.kind, "display_name": self.display_name or self.id,
                            "source": self.source, "capabilities": self.capabilities.to_dict()}}


@dataclasses.dataclass
class HealthStatus:
    status: str = "ok"  # ok | degraded | down
    detail: str = ""

    @property
    def ok(self) -> bool:
        return self.status in ("ok", "degraded")

    def to_dict(self) -> Dict[str, str]:
        return {"status": self.status, "detail": self.detail}


class ChatModel(LLMModel):
    def __init__(self, provider: Any, model_id: str, info: Optional[ModelInfo] = None) -> None:
        self.provider, self.model_id = provider, model_id
        self._info = info or ModelInfo(model_id, getattr(provider, "name", "") or "")

    def info(self) -> ModelInfo:
        return self._info

    def _create(self, request: ChatCompletionRequest) -> ChatCompletion:
        raise NotImplementedError("a ChatModel implements _create")

    def _stream(self, request: ChatCompletionRequest) -> Iterator[ChatCompletionChunk]:
        return completion_to_chunks(self._create(request))

    def chat_completions_create(self, request: Any = None, /, **fields: Any) -> ChatCompletion:
        return self._create(ChatCompletionRequest.coerce(request, **fields))

    async def achat_completions_create(self, request: Any = None, /, **fields: Any) -> ChatCompletion:
        return await asyncio.to_thread(self._create, ChatCompletionRequest.coerce(request, **fields))

    def chat_completions_stream(self, request: Any = None, /, **fields: Any) -> Iterator[ChatCompletionChunk]:
        return self._stream(ChatCompletionRequest.coerce(request, **fields))

    async def achat_completions_stream(self, request: Any = None, /, **fields: Any) -> AsyncIterator[ChatCompletionChunk]:
        req = ChatCompletionRequest.coerce(request, **fields)
        for chunk in await asyncio.to_thread(lambda: list(self._stream(req))):
            yield chunk

    def embeddings_create(self, request: Any = None, /, **fields: Any) -> EmbeddingsResponse:
        raise UnsupportedFeature(f"model {self._info.qualified_id} is a chat model; it has no embeddings")

    async def aembeddings_create(self, request: Any = None, /, **fields: Any) -> EmbeddingsResponse:
        raise UnsupportedFeature(f"model {self._info.qualified_id} is a chat model; it has no embeddings")


class EmbeddingModel(LLMModel):
    def __init__(self, provider: Any, model_id: str, info: Optional[ModelInfo] = None) -> None:
        self.provider, self.model_id = provider, model_id
        self._info = info or ModelInfo(model_id, getattr(provider, "name", "") or "", "embedding")

    def info(self) -> ModelInfo:
        return self._info

    def _embed(self, texts: List[str]) -> List[List[float]]:
        raise NotImplementedError("an EmbeddingModel implements _embed")

    def _response(self, texts: List[str], out: List[List[float]]) -> EmbeddingsResponse:
        n = sum((len(t) + 3) // 4 for t in texts)
        return EmbeddingsResponse(data=[EmbeddingData(embedding=[float(x) for x in v], index=i) for i, v in enumerate(out)],
                                  model=self._info.id, usage=EmbeddingsUsage(prompt_tokens=n, total_tokens=n))

    def embeddings_create(self, request: Any = None, /, **fields: Any) -> EmbeddingsResponse:
        texts = EmbeddingsRequest.coerce(request, **fields).texts
        return self._response(texts, self._embed(texts))

    async def aembeddings_create(self, request: Any = None, /, **fields: Any) -> EmbeddingsResponse:
        return await asyncio.to_thread(self.embeddings_create, request, **fields)

    def _no_chat(self) -> UnsupportedFeature:
        return UnsupportedFeature(f"model {self._info.qualified_id} is an embedding model; it cannot chat")

    def chat_completions_create(self, request: Any = None, /, **fields: Any) -> ChatCompletion:
        raise self._no_chat()

    async def achat_completions_create(self, request: Any = None, /, **fields: Any) -> ChatCompletion:
        raise self._no_chat()

    def chat_completions_stream(self, request: Any = None, /, **fields: Any) -> Iterator[ChatCompletionChunk]:
        raise self._no_chat()

    def achat_completions_stream(self, request: Any = None, /, **fields: Any) -> AsyncIterator[ChatCompletionChunk]:
        raise self._no_chat()


__all__ = ["ChatModel", "EmbeddingModel", "HealthStatus", "ModelCapabilities", "ModelInfo"]
