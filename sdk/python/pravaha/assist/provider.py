"""The provider abstraction: the one interface a model provider implements (ADR-058 §1).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A provider is anything with a ``name``, ``capabilities(model)`` and ``complete(request)``:
:class:`ModelProvider` is a :class:`typing.Protocol`, so a third-party provider need not inherit
from anything here. Most will still subclass :class:`BaseProvider`, because it does the part
every provider would otherwise get subtly different -- answering a ``response_schema``:

1. a provider that can constrain its output natively (``Capabilities.structured_output`` is
   ``json_schema`` or ``tool_call``) is asked to; one that cannot is told, in the system prompt,
   to answer with one JSON object satisfying the schema;
2. either way the answer is parsed and validated against the schema here -- a native guarantee
   is a claim, and checking it costs nothing;
3. an answer that fails gets **exactly one** repair turn, with the validation error;
4. a second failure is :class:`~pravaha.assist.errors.ModelOutputError`, never a guess.

A subclass implements :meth:`BaseProvider._send`: one HTTP round trip, its errors normalised.
"""

from __future__ import annotations

import dataclasses
import json
import time
from typing import Any, Iterator, Literal, Mapping, Optional, Protocol, Sequence, runtime_checkable

from pravaha.assist.errors import AssistConfigError, ModelOutputError
from pravaha.assist.schema import check_schema, problems

StructuredOutput = Literal["json_schema", "tool_call", "none"]
Role = Literal["user", "assistant"]


@dataclasses.dataclass(frozen=True)
class Message:
    """One turn of the conversation. The system prompt is :attr:`ChatRequest.system`, not a
    message, because providers disagree about where it goes."""

    role: Role
    content: str


@dataclasses.dataclass(frozen=True)
class Usage:
    """Tokens, as the provider counted them."""

    input_tokens: int = 0
    output_tokens: int = 0
    cached_tokens: int = 0

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens

    def __add__(self, other: "Usage") -> "Usage":
        return Usage(
            self.input_tokens + other.input_tokens,
            self.output_tokens + other.output_tokens,
            self.cached_tokens + other.cached_tokens,
        )


@dataclasses.dataclass(frozen=True)
class ChatRequest:
    """What to ask. ``model`` is the provider's own model id; the router fills it in from the
    configured model, so a caller going through the router may leave it empty."""

    messages: Sequence[Message]
    model: str = ""
    system: Optional[str] = None
    #: A JSON Schema the answer must satisfy, or ``None`` for free text.
    response_schema: Optional[Mapping[str, Any]] = None
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    stop: Sequence[str] = ()
    timeout_s: float = 60.0
    #: Request id, tenant, user: passed to a provider's own logs where it takes them.
    metadata: Mapping[str, str] = dataclasses.field(default_factory=dict)


@dataclasses.dataclass(frozen=True)
class ChatResponse:
    """The answer. ``parsed`` is set when a schema was asked for and satisfied; ``model`` is the
    model that actually answered, as the provider named it; ``raw`` is the provider's own
    document, kept only when the provider runs in debug mode."""

    text: str
    model: str
    usage: Usage = Usage()
    finish_reason: str = "stop"
    latency_ms: float = 0.0
    parsed: Any = None
    raw: Any = None


@dataclasses.dataclass(frozen=True)
class ChatChunk:
    """One piece of a streamed answer (for providers that implement ``stream``)."""

    text: str
    finish_reason: Optional[str] = None


@dataclasses.dataclass(frozen=True)
class Capabilities:
    """What a model can do. ``None`` means the provider does not know."""

    structured_output: StructuredOutput = "none"
    max_context_tokens: Optional[int] = None
    max_output_tokens: Optional[int] = None
    supports_system: bool = True
    supports_streaming: bool = False
    supports_prompt_cache: bool = False

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


@runtime_checkable
class ModelProvider(Protocol):
    """The only interface a provider implements. ``stream`` is optional::

        def stream(self, request: ChatRequest) -> Iterator[ChatChunk]: ...
    """

    name: str

    def capabilities(self, model: str) -> Capabilities: ...

    def complete(self, request: ChatRequest) -> ChatResponse: ...


@runtime_checkable
class StreamingProvider(ModelProvider, Protocol):
    """A provider that also streams."""

    def stream(self, request: ChatRequest) -> Iterator[ChatChunk]: ...


@dataclasses.dataclass(frozen=True)
class ProviderSettings:
    """How one configured provider is reached. What a provider factory is called with.

    ``api_key`` is resolved by the configuration from an environment variable or a secret file
    at the moment the provider is built; it is never read from configuration text.
    """

    endpoint: Optional[str] = None
    api_key: Optional[str] = None
    options: Mapping[str, Any] = dataclasses.field(default_factory=dict)
    timeout_s: float = 60.0
    debug: bool = False
    #: The configuration's id for this provider, for messages.
    id: str = ""


#: What a provider answers with when asked for JSON it cannot constrain natively.
JSON_INSTRUCTION = (
    "Answer with exactly one JSON object and nothing else -- no prose before or after it, no "
    "code fence. It must satisfy this JSON Schema:\n{schema}"
)
#: The one repair turn.
REPAIR_INSTRUCTION = (
    "That answer could not be used: {problem}. Reply again with only the corrected JSON object, "
    "satisfying the same schema."
)


def extract_json(text: str) -> Any:
    """The JSON object in a model's answer: the whole text, or inside a code fence, or the span
    from the first ``{`` to the last ``}``. Raises ``ValueError`` when there is none."""
    candidate = text.strip()
    if candidate.startswith("```"):
        lines = candidate.splitlines()
        body = lines[1:]
        if body and body[-1].strip().startswith("```"):
            body = body[:-1]
        candidate = "\n".join(body).strip()
    try:
        return json.loads(candidate)
    except ValueError:
        pass
    start, end = candidate.find("{"), candidate.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(candidate[start : end + 1])
        except ValueError as exc:
            raise ValueError(f"the answer is not valid JSON ({exc})") from None
    raise ValueError("the answer contains no JSON object")


def _judge(text: str, schema: Mapping[str, Any]) -> "tuple[Any, Optional[str]]":
    try:
        value = extract_json(text)
    except ValueError as exc:
        return None, str(exc)
    found = problems(value, schema)
    if found:
        return None, "it does not satisfy the schema: " + "; ".join(found[:8])
    return value, None


class BaseProvider:
    """The shared half of a provider: capabilities from defaults and configuration, the
    structured-output fallback and repair turn, latency, and ``raw`` only in debug mode.

    Subclasses set :attr:`name` and :attr:`default_capabilities` and implement :meth:`_send`.
    """

    name: str = ""
    default_capabilities = Capabilities()
    #: Whether this provider cannot work without a key.
    requires_key = False

    def __init__(self, settings: Optional[ProviderSettings] = None) -> None:
        self.settings = settings or ProviderSettings()
        if self.requires_key and not self.settings.api_key:
            raise AssistConfigError(
                f"provider {self.settings.id or self.name!r} ({self.name}) needs a key: name the "
                f"environment variable that holds it with api_key_env, or a secret file with "
                f"api_key_file"
            )

    @property
    def debug(self) -> bool:
        return self.settings.debug

    def capabilities(self, model: str) -> Capabilities:
        """The defaults for this provider, with whatever the configuration overrides (the
        options ``structured_output``, ``max_context_tokens``, ``max_output_tokens``)."""
        overrides = {
            key: self.settings.options[key]
            for key in ("structured_output", "max_context_tokens", "max_output_tokens")
            if self.settings.options.get(key) is not None
        }
        return dataclasses.replace(self.default_capabilities, **overrides)

    def complete(self, request: ChatRequest) -> ChatResponse:
        started = time.monotonic()
        schema = request.response_schema
        if schema is None:
            return self._finish(self._send(request, native_schema=False), started)
        check_schema(schema)
        native = self.capabilities(request.model).structured_output != "none"
        first = request if native else self._with_json_instruction(request, schema)
        answer = self._send(first, native_schema=native)
        parsed, problem = _judge(answer.text, schema)
        if problem is None:
            return self._finish(dataclasses.replace(answer, parsed=parsed), started)
        repair = dataclasses.replace(
            first,
            messages=[
                *first.messages,
                Message("assistant", answer.text),
                Message("user", REPAIR_INSTRUCTION.format(problem=problem)),
            ],
        )
        second = self._send(repair, native_schema=native)
        usage = answer.usage + second.usage
        parsed, problem = _judge(second.text, schema)
        if problem is not None:
            raise ModelOutputError(
                f"the answer did not satisfy the schema after one repair turn: {problem}",
                text=second.text,
                problem=problem,
                usage=usage,
                provider=self.name,
                model=second.model or request.model,
            )
        return self._finish(dataclasses.replace(second, parsed=parsed, usage=usage), started)

    def ping(self, model: str) -> None:
        """Proves the model is reachable, as cheaply as the provider allows. The default asks
        for a few tokens; providers with a models endpoint override it to spend none."""
        self._send(
            ChatRequest(messages=[Message("user", "Reply with OK.")], model=model, max_tokens=5),
            native_schema=False,
        )

    # ------------------------------------------------------------------ for subclasses

    def _send(self, request: ChatRequest, *, native_schema: bool) -> ChatResponse:
        """One round trip. With ``native_schema``, constrain the output to
        ``request.response_schema`` the provider's own way. Errors normalised."""
        raise NotImplementedError

    @staticmethod
    def _with_json_instruction(request: ChatRequest, schema: Mapping[str, Any]) -> ChatRequest:
        instruction = JSON_INSTRUCTION.format(schema=json.dumps(schema, indent=1, sort_keys=True))
        system = f"{request.system}\n\n{instruction}" if request.system else instruction
        return dataclasses.replace(request, system=system)

    def _finish(self, answer: ChatResponse, started: float) -> ChatResponse:
        return dataclasses.replace(
            answer,
            latency_ms=round((time.monotonic() - started) * 1000.0, 3),
            raw=answer.raw if self.debug else None,
        )


__all__ = [
    "BaseProvider",
    "Capabilities",
    "ChatChunk",
    "ChatRequest",
    "ChatResponse",
    "JSON_INSTRUCTION",
    "Message",
    "ModelProvider",
    "ProviderSettings",
    "REPAIR_INSTRUCTION",
    "StreamingProvider",
    "StructuredOutput",
    "Usage",
    "extract_json",
]
