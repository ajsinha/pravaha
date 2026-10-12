"""The canonical model format: OpenAI Chat Completions and Embeddings, typed -- the names and fields of
sajhamcpserver's ``canonical.py`` as Drishti ported them, with the extension field named ``pravaha``.
A request stripped of ``pravaha`` is a valid OpenAI request. Plain dataclasses: the SDK has no
dependencies (Drishti's used pydantic); ``coerce`` reads the dicts an OpenAI client would send.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import dataclasses
import json
import time
import uuid
from typing import Any, Dict, Iterator, List, Mapping, Optional, Union


def new_id(prefix: str = "chatcmpl-") -> str:
    return prefix + uuid.uuid4().hex[:24]


def _drop_none(v: Any) -> Any:
    if isinstance(v, dict):
        return {k: _drop_none(x) for k, x in v.items() if x is not None}
    if isinstance(v, list):
        return [_drop_none(x) for x in v]
    return v


class _Model:
    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = _drop_none(dataclasses.asdict(self))  # type: ignore[call-overload]
        return d


@dataclasses.dataclass
class FunctionCall(_Model):
    name: str = ""
    arguments: str = "{}"  # a JSON string, as in OpenAI


@dataclasses.dataclass
class ToolCall(_Model):
    id: str = ""
    type: str = "function"
    function: FunctionCall = dataclasses.field(default_factory=FunctionCall)

    @classmethod
    def of(cls, id: str, name: str, arguments: Any) -> "ToolCall":
        return cls(id=id, function=FunctionCall(name=name, arguments=arguments if isinstance(arguments, str)
                                                else json.dumps(arguments)))

    @classmethod
    def coerce(cls, v: Any) -> "ToolCall":
        if isinstance(v, cls):
            return v
        f = dict(v.get("function") or {})
        raw = f.get("arguments")
        arguments = raw if isinstance(raw, str) else json.dumps(raw or {})
        return cls(id=str(v.get("id", "")), type=str(v.get("type") or "function"),
                   function=FunctionCall(str(f.get("name", "")), arguments))


@dataclasses.dataclass
class ChatMessage(_Model):
    role: str = "user"
    content: Optional[str] = None
    name: Optional[str] = None
    tool_calls: Optional[List[ToolCall]] = None
    tool_call_id: Optional[str] = None
    refusal: Optional[str] = None

    @classmethod
    def system(cls, text: str) -> "ChatMessage":
        return cls(role="system", content=text)

    @classmethod
    def developer(cls, text: str) -> "ChatMessage":
        return cls(role="developer", content=text)

    @classmethod
    def user(cls, text: str) -> "ChatMessage":
        return cls(role="user", content=text)

    @classmethod
    def assistant(cls, text: Optional[str] = None, tool_calls: Optional[List[ToolCall]] = None) -> "ChatMessage":
        return cls(role="assistant", content=text, tool_calls=tool_calls or None)

    @classmethod
    def tool(cls, tool_call_id: str, content: Any) -> "ChatMessage":
        return cls(role="tool", tool_call_id=tool_call_id, content=content if isinstance(content, str) else str(content))

    @classmethod
    def coerce(cls, v: Any) -> "ChatMessage":
        if isinstance(v, cls):
            return v
        content = v.get("content")
        if isinstance(content, list):  # content parts: the text ones
            content = "".join(str(p.get("text") or "") for p in content if p.get("type") == "text")
        calls = v.get("tool_calls")
        return cls(role=str(v.get("role") or "user"), content=content, name=v.get("name"),
                   tool_calls=[ToolCall.coerce(c) for c in calls] if calls else None,
                   tool_call_id=v.get("tool_call_id"), refusal=v.get("refusal"))

    @property
    def text(self) -> str:
        return self.content or ""


@dataclasses.dataclass
class FunctionDefinition(_Model):
    name: str = ""
    description: Optional[str] = None
    parameters: Optional[Dict[str, Any]] = None
    strict: Optional[bool] = None


@dataclasses.dataclass
class ToolDefinition(_Model):
    type: str = "function"
    function: Optional[FunctionDefinition] = None

    @classmethod
    def of(cls, name: str, description: str = "", parameters: Optional[Dict[str, Any]] = None) -> "ToolDefinition":
        return cls(function=FunctionDefinition(name=name, description=description, parameters=parameters))

    @classmethod
    def coerce(cls, v: Any) -> "ToolDefinition":
        if isinstance(v, cls):
            return v
        f = dict(v.get("function") or {})
        return cls(type=str(v.get("type") or "function"),
                   function=FunctionDefinition(str(f.get("name", "")), f.get("description"), f.get("parameters"),
                                               f.get("strict")))

    @property
    def name(self) -> str:
        return self.function.name if self.function else ""


@dataclasses.dataclass
class ResponseFormat(_Model):
    type: str = "text"  # text | json_object | json_schema
    json_schema: Optional[Dict[str, Any]] = None


@dataclasses.dataclass
class PravahaRequest(_Model):
    """The extension field of a request: the caller (``context``, a ``CallContext``: identity and feature, for
    the policy), a trace id, and for embeddings the purpose."""

    context: Any = None
    trace_id: str = ""
    input_purpose: Optional[str] = None


@dataclasses.dataclass
class ChatCompletionRequest(_Model):
    model: str = "default"
    messages: List[ChatMessage] = dataclasses.field(default_factory=list)
    tools: Optional[List[ToolDefinition]] = None
    tool_choice: Optional[Union[str, Dict[str, Any]]] = None
    parallel_tool_calls: Optional[bool] = None
    response_format: Optional[ResponseFormat] = None
    temperature: Optional[float] = None
    top_p: Optional[float] = None
    max_completion_tokens: Optional[int] = None
    max_tokens: Optional[int] = None
    stop: Optional[Union[str, List[str]]] = None
    seed: Optional[int] = None
    n: Optional[int] = None
    stream: Optional[bool] = None
    user: Optional[str] = None
    metadata: Optional[Dict[str, str]] = None
    reasoning_effort: Optional[str] = None
    extra_body: Optional[Dict[str, Any]] = None
    pravaha: Optional[PravahaRequest] = None

    @classmethod
    def coerce(cls, request: Any = None, **fields: Any) -> "ChatCompletionRequest":
        """A request object, a dict, or keyword fields (``messages=[...]``), as one request."""
        if isinstance(request, cls):
            return dataclasses.replace(request, **fields) if fields else request
        data: Dict[str, Any] = dict(request or {})
        data.update(fields)
        known = {f.name for f in dataclasses.fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in known})

    def __post_init__(self) -> None:
        """Dicts, as an OpenAI client sends them, become the typed fields."""
        self.model = self.model or "default"
        self.messages = [ChatMessage.coerce(m) for m in self.messages]
        if self.tools is not None:
            self.tools = [ToolDefinition.coerce(t) for t in self.tools]
        rf: Any = self.response_format
        if isinstance(rf, Mapping):
            self.response_format = ResponseFormat(str(rf.get("type") or "text"), rf.get("json_schema"))
        px: Any = self.pravaha
        if isinstance(px, Mapping):
            self.pravaha = PravahaRequest(px.get("context"), str(px.get("trace_id") or ""), px.get("input_purpose"))

    @property
    def max_output_tokens(self) -> Optional[int]:
        return self.max_completion_tokens if self.max_completion_tokens is not None else self.max_tokens

    @property
    def wants_tools(self) -> bool:
        return bool(self.tools)

    @property
    def context(self) -> Any:
        return self.pravaha.context if self.pravaha else None


@dataclasses.dataclass
class CompletionUsage(_Model):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0

    @classmethod
    def of(cls, prompt: int, completion: int) -> "CompletionUsage":
        return cls(prompt_tokens=prompt, completion_tokens=completion, total_tokens=prompt + completion)


@dataclasses.dataclass
class ResponsePravaha(_Model):
    """The extension field of a response: who answered, what it cost, how long it took."""

    provider: str = ""
    qualified_model: str = ""
    cost_usd: float = 0.0
    cached: bool = False
    latency_ms: int = 0
    trace_id: str = ""


@dataclasses.dataclass
class Choice(_Model):
    index: int = 0
    message: ChatMessage = dataclasses.field(default_factory=lambda: ChatMessage(role="assistant"))
    finish_reason: Optional[str] = "stop"


@dataclasses.dataclass
class ChatCompletion(_Model):
    id: str = dataclasses.field(default_factory=new_id)
    object: str = "chat.completion"
    created: int = dataclasses.field(default_factory=lambda: int(time.time()))
    model: str = ""
    choices: List[Choice] = dataclasses.field(default_factory=list)
    usage: Optional[CompletionUsage] = None
    pravaha: Optional[ResponsePravaha] = None

    @property
    def message(self) -> ChatMessage:
        return self.choices[0].message if self.choices else ChatMessage(role="assistant")

    @property
    def text(self) -> str:
        return self.message.text

    @property
    def tool_calls(self) -> List[ToolCall]:
        return list(self.message.tool_calls or [])

    @property
    def finish_reason(self) -> str:
        return (self.choices[0].finish_reason if self.choices else None) or "stop"


@dataclasses.dataclass
class ChoiceDelta(_Model):
    role: Optional[str] = None
    content: Optional[str] = None
    refusal: Optional[str] = None


@dataclasses.dataclass
class ChunkChoice(_Model):
    index: int = 0
    delta: ChoiceDelta = dataclasses.field(default_factory=ChoiceDelta)
    finish_reason: Optional[str] = None


@dataclasses.dataclass
class ChatCompletionChunk(_Model):
    id: str = ""
    object: str = "chat.completion.chunk"
    created: int = 0
    model: str = ""
    choices: List[ChunkChoice] = dataclasses.field(default_factory=list)
    usage: Optional[CompletionUsage] = None
    pravaha: Optional[ResponsePravaha] = None

    @property
    def delta(self) -> str:
        return (self.choices[0].delta.content or "") if self.choices else ""


def completion_to_chunks(c: ChatCompletion) -> Iterator[ChatCompletionChunk]:
    """A completion as the chunks a stream would carry: the role, the text, the finish (with the usage).
    Streaming is emulated this way unless a provider streams natively."""
    yield ChatCompletionChunk(id=c.id, created=c.created, model=c.model,
                              choices=[ChunkChoice(delta=ChoiceDelta(role="assistant", content=""))])
    yield ChatCompletionChunk(id=c.id, created=c.created, model=c.model,
                              choices=[ChunkChoice(delta=ChoiceDelta(content=c.text))])
    yield ChatCompletionChunk(id=c.id, created=c.created, model=c.model,
                              choices=[ChunkChoice(finish_reason=c.finish_reason)], usage=c.usage, pravaha=c.pravaha)


@dataclasses.dataclass
class EmbeddingsRequest(_Model):
    model: str = "embedding"
    input: Union[str, List[str]] = dataclasses.field(default_factory=list)
    encoding_format: Optional[str] = None
    dimensions: Optional[int] = None
    user: Optional[str] = None
    pravaha: Optional[PravahaRequest] = None

    @classmethod
    def coerce(cls, request: Any = None, **fields: Any) -> "EmbeddingsRequest":
        if isinstance(request, cls):
            return dataclasses.replace(request, **fields) if fields else request
        data: Dict[str, Any] = dict(request or {})
        data.update(fields)
        known = {f.name for f in dataclasses.fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in known})

    @property
    def texts(self) -> List[str]:
        return [self.input] if isinstance(self.input, str) else list(self.input)


@dataclasses.dataclass
class EmbeddingData(_Model):
    embedding: List[float] = dataclasses.field(default_factory=list)
    index: int = 0
    object: str = "embedding"


@dataclasses.dataclass
class EmbeddingsUsage(_Model):
    prompt_tokens: int = 0
    total_tokens: int = 0


@dataclasses.dataclass
class EmbeddingsResponse(_Model):
    object: str = "list"
    data: List[EmbeddingData] = dataclasses.field(default_factory=list)
    model: str = ""
    usage: EmbeddingsUsage = dataclasses.field(default_factory=EmbeddingsUsage)
    pravaha: Optional[ResponsePravaha] = None

    @property
    def vectors(self) -> List[List[float]]:
        return [d.embedding for d in self.data]


__all__ = ["ChatCompletion", "ChatCompletionChunk", "ChatCompletionRequest", "ChatMessage", "ChoiceDelta", "Choice",
           "ChunkChoice", "CompletionUsage", "EmbeddingData", "EmbeddingsRequest", "EmbeddingsResponse",
           "EmbeddingsUsage", "FunctionCall", "FunctionDefinition", "PravahaRequest", "ResponseFormat",
           "ResponsePravaha", "ToolCall", "ToolDefinition", "completion_to_chunks", "new_id"]
