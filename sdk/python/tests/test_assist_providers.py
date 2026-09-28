"""Every built-in provider against recorded answers from a local HTTP server: the request it
sends, how it reads the answer, and how it normalises each failure.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The conformance tests are parametrised over the HTTP providers, so a provider added to
``SPECS`` is held to the same contract as the others: text and usage read, raw kept only in
debug, a 429 is ModelRateLimited with its retry-after, a 5xx and an unreachable server are
retryable ModelUnavailable, a 404 is ModelUnavailable that is not, a schema asked for natively (or
in text, for a provider that cannot) comes back parsed, and ``ping`` spends no tokens.
"""

from __future__ import annotations

import dataclasses
import socket
from typing import Any, Callable

import pytest
from assist_support import FIXTURES, PlaybackServer

from pravaha.assist import (
    Capabilities,
    ChatRequest,
    Message,
    ModelProvider,
    ModelRateLimited,
    ModelRefused,
    ModelUnavailable,
    ProviderSettings,
)
from pravaha.assist.errors import AssistConfigError
from pravaha.assist.providers import (
    AnthropicProvider,
    FakeProvider,
    OllamaProvider,
    OpenAICompatibleProvider,
    OpenAIProvider,
)

SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "steps": {"type": "array", "items": {"type": "string"}},
        "notes": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["summary", "steps", "notes"],
    "additionalProperties": False,
}


@dataclasses.dataclass
class Spec:
    name: str
    make: Callable[[str, bool], Any]
    fixtures: str
    chat: tuple[str, str]
    ping: tuple[str, str]
    ping_fixture: str
    model: str
    native_schema: bool


SPECS = [
    Spec("anthropic",
         lambda url, debug: AnthropicProvider(ProviderSettings(endpoint=url, api_key="test-key",
                                                               debug=debug)),
         "anthropic", ("POST", "/v1/messages"), ("GET", "/v1/models/claude-opus-5"), "model",
         "claude-opus-5", True),
    Spec("openai",
         lambda url, debug: OpenAIProvider(ProviderSettings(endpoint=url + "/v1", api_key="test-key",
                                                            debug=debug)),
         "openai", ("POST", "/v1/chat/completions"), ("GET", "/v1/models/gpt-test-2026"), "model",
         "gpt-test-2026", True),
    Spec("openai-compatible",
         lambda url, debug: OpenAICompatibleProvider(ProviderSettings(endpoint=url + "/v1",
                                                                      debug=debug)),
         "openai", ("POST", "/v1/chat/completions"), ("GET", "/v1/models"), "models",
         "local-model", False),
    Spec("ollama",
         lambda url, debug: OllamaProvider(ProviderSettings(endpoint=url, debug=debug)),
         "ollama", ("POST", "/api/chat"), ("POST", "/api/show"), "show", "llama3.1:70b", True),
]
IDS = [s.name for s in SPECS]


@pytest.fixture
def server():
    playback = PlaybackServer()
    try:
        yield playback
    finally:
        playback.close()


def _ask(model: str, **extra: Any) -> ChatRequest:
    return ChatRequest(messages=[Message("user", "What does it compute?")], model=model,
                       system="You explain queries.", max_tokens=300, **extra)


# ---------------------------------------------------------------------------------- conformance


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_is_a_model_provider_with_capabilities(spec, server):
    provider = spec.make(server.url, False)
    assert isinstance(provider, ModelProvider)
    assert provider.name == spec.name
    capabilities = provider.capabilities(spec.model)
    assert isinstance(capabilities, Capabilities)
    assert capabilities.structured_output in ("json_schema", "tool_call", "none")


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_reads_text_usage_and_finish(spec, server):
    server.queue(*spec.chat, FIXTURES[spec.fixtures]["text"])
    answer = spec.make(server.url, False).complete(_ask(spec.model))
    assert answer.text == "It counts orders per minute."
    assert answer.usage.input_tokens == 42 and answer.usage.output_tokens == 9
    assert answer.finish_reason == "stop"
    assert answer.latency_ms > 0
    assert answer.raw is None, "raw is kept only in debug mode"
    assert answer.parsed is None


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_keeps_raw_only_in_debug(spec, server):
    server.queue(*spec.chat, FIXTURES[spec.fixtures]["text"])
    answer = spec.make(server.url, True).complete(_ask(spec.model))
    assert isinstance(answer.raw, dict)


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_a_429_is_rate_limited_with_retry_after(spec, server):
    server.queue(*spec.chat, FIXTURES[spec.fixtures]["rate_limited"])
    with pytest.raises(ModelRateLimited) as caught:
        spec.make(server.url, False).complete(_ask(spec.model))
    assert caught.value.retry_after == pytest.approx(7.0)
    assert caught.value.status == 429
    assert caught.value.provider == spec.name


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_a_5xx_is_retryable_unavailable(spec, server):
    server.queue(*spec.chat, FIXTURES[spec.fixtures]["server_error"])
    with pytest.raises(ModelUnavailable) as caught:
        spec.make(server.url, False).complete(_ask(spec.model))
    assert caught.value.retryable
    assert caught.value.status in (500, 503)


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_an_unknown_model_is_unavailable_and_not_retryable(spec, server):
    server.queue(*spec.chat, FIXTURES[spec.fixtures]["not_found"])
    with pytest.raises(ModelUnavailable) as caught:
        spec.make(server.url, False).complete(_ask(spec.model))
    assert not caught.value.retryable
    assert caught.value.status == 404


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_nothing_listening_is_unavailable(spec):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    with pytest.raises(ModelUnavailable) as caught:
        spec.make(f"http://127.0.0.1:{port}", False).complete(_ask(spec.model, timeout_s=5))
    assert caught.value.retryable
    assert "nothing answered" in caught.value.message


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_a_schema_comes_back_parsed(spec, server):
    server.queue(*spec.chat, FIXTURES[spec.fixtures]["json"])
    answer = spec.make(server.url, False).complete(_ask(spec.model, response_schema=SCHEMA))
    assert answer.parsed == {"summary": "Counts orders.", "steps": ["Reads orders."], "notes": []}
    sent = server.last().body
    text = str(sent)
    if spec.native_schema:
        assert "Answer with exactly one JSON object" not in text
    else:
        # No native schema: the base class asked for JSON in the system prompt.
        assert "Answer with exactly one JSON object" in sent["messages"][0]["content"]


@pytest.mark.parametrize("spec", SPECS, ids=IDS)
def test_ping_spends_no_tokens(spec, server):
    server.queue(*spec.ping, FIXTURES[spec.fixtures][spec.ping_fixture])
    spec.make(server.url, False).ping(spec.model)
    assert (server.last().method, server.last().path) == spec.ping
    assert all(r.path != spec.chat[1] for r in server.requests)


# ---------------------------------------------------------------------------------- request shapes


def test_anthropic_sends_the_messages_api_shape(server):
    server.queue("POST", "/v1/messages", FIXTURES["anthropic"]["json"])
    AnthropicProvider(ProviderSettings(endpoint=server.url, api_key="test-key")).complete(
        _ask("claude-opus-5", response_schema=SCHEMA, stop=["END"], metadata={"user": "ana"})
    )
    sent = server.last()
    assert sent.headers["x-api-key"] == "test-key"
    assert sent.headers["anthropic-version"] == "2023-06-01"
    assert "authorization" not in sent.headers
    assert sent.body["model"] == "claude-opus-5"
    assert sent.body["max_tokens"] == 300
    assert sent.body["system"] == "You explain queries."
    assert sent.body["messages"] == [{"role": "user", "content": "What does it compute?"}]
    assert sent.body["stop_sequences"] == ["END"]
    assert sent.body["metadata"] == {"user_id": "ana"}
    assert sent.body["output_config"] == {"format": {"type": "json_schema", "schema": SCHEMA}}
    assert "temperature" not in sent.body, "sampling is sent only when asked for"
    assert "tools" not in sent.body and "tool_choice" not in sent.body


def test_anthropic_reads_cached_tokens_and_a_refusal(server):
    server.queue("POST", "/v1/messages", FIXTURES["anthropic"]["text"], FIXTURES["anthropic"]["refusal"])
    provider = AnthropicProvider(ProviderSettings(endpoint=server.url, api_key="test-key"))
    assert provider.complete(_ask("claude-opus-5")).usage.cached_tokens == 30
    with pytest.raises(ModelRefused) as caught:
        provider.complete(_ask("claude-opus-5"))
    assert "policy" in caught.value.message


def test_anthropic_529_overloaded_is_unavailable(server):
    server.queue("POST", "/v1/messages", FIXTURES["anthropic"]["overloaded"])
    with pytest.raises(ModelUnavailable) as caught:
        AnthropicProvider(ProviderSettings(endpoint=server.url, api_key="k")).complete(
            _ask("claude-opus-5"))
    assert caught.value.status == 529 and caught.value.retryable


def test_openai_sends_chat_completions_with_a_bearer_key(server):
    server.queue("POST", "/v1/chat/completions", FIXTURES["openai"]["json"])
    OpenAIProvider(ProviderSettings(endpoint=server.url + "/v1", api_key="test-key")).complete(
        _ask("gpt-test-2026", response_schema=SCHEMA, temperature=0.2)
    )
    sent = server.last()
    assert sent.headers["authorization"] == "Bearer test-key"
    assert sent.body["messages"][0] == {"role": "system", "content": "You explain queries."}
    assert sent.body["max_completion_tokens"] == 300 and "max_tokens" not in sent.body
    assert sent.body["temperature"] == 0.2
    assert sent.body["response_format"]["type"] == "json_schema"
    assert sent.body["response_format"]["json_schema"]["schema"] == SCHEMA


def test_openai_refusal_and_exhausted_quota(server):
    server.queue("POST", "/v1/chat/completions", FIXTURES["openai"]["refusal"],
                 FIXTURES["openai"]["quota"])
    provider = OpenAIProvider(ProviderSettings(endpoint=server.url + "/v1", api_key="k"))
    with pytest.raises(ModelRefused):
        provider.complete(_ask("gpt-test-2026"))
    with pytest.raises(ModelUnavailable) as caught:
        provider.complete(_ask("gpt-test-2026"))
    assert not caught.value.retryable, "an exhausted quota does not come back by waiting"
    assert "quota" in caught.value.message


def test_openai_compatible_sends_max_tokens_and_no_key_unless_configured(server):
    server.queue("POST", "/v1/chat/completions", FIXTURES["openai"]["text"])
    OpenAICompatibleProvider(ProviderSettings(endpoint=server.url + "/v1")).complete(
        _ask("local-model"))
    sent = server.last()
    assert sent.body["max_tokens"] == 300 and "max_completion_tokens" not in sent.body
    assert "authorization" not in sent.headers
    assert "response_format" not in sent.body


def test_openai_compatible_claims_native_schemas_only_when_configured(server):
    server.queue("POST", "/v1/chat/completions", FIXTURES["openai"]["json"])
    provider = OpenAICompatibleProvider(ProviderSettings(
        endpoint=server.url + "/v1", options={"structured_output": "json_schema"}))
    assert provider.capabilities("local-model").structured_output == "json_schema"
    provider.complete(_ask("local-model", response_schema=SCHEMA))
    assert server.last().body["response_format"]["json_schema"]["schema"] == SCHEMA


def test_ollama_sends_its_native_chat_shape(server):
    server.queue("POST", "/api/chat", FIXTURES["ollama"]["json"])
    OllamaProvider(ProviderSettings(endpoint=server.url)).complete(
        _ask("llama3.1:70b", response_schema=SCHEMA, temperature=0.0, stop=["END"]))
    sent = server.last().body
    assert sent["stream"] is False
    assert sent["format"] == SCHEMA
    assert sent["options"] == {"temperature": 0.0, "num_predict": 300, "stop": ["END"]}
    assert sent["messages"][0]["role"] == "system"


def test_keys_are_required_where_the_api_requires_them():
    with pytest.raises(AssistConfigError, match="api_key_env"):
        AnthropicProvider(ProviderSettings())
    with pytest.raises(AssistConfigError, match="api_key_env"):
        OpenAIProvider(ProviderSettings())
    with pytest.raises(AssistConfigError, match="endpoint"):
        OpenAICompatibleProvider(ProviderSettings())
    OllamaProvider(ProviderSettings())  # a local server needs no key


def test_a_key_never_appears_in_an_error(server):
    server.queue("POST", "/v1/messages", FIXTURES["anthropic"]["server_error"])
    with pytest.raises(ModelUnavailable) as caught:
        AnthropicProvider(ProviderSettings(endpoint=server.url, api_key="sk-ant-SECRET")).complete(
            _ask("claude-opus-5"))
    assert "SECRET" not in str(caught.value) and "SECRET" not in str(caught.value.to_dict())


def test_retry_after_as_an_http_date(server):
    import email.utils
    import time

    when = email.utils.formatdate(time.time() + 30, usegmt=True)
    server.queue("POST", "/api/chat", {"status": 429, "headers": {"Retry-After": when},
                                       "body": {"error": "slow down"}})
    with pytest.raises(ModelRateLimited) as caught:
        OllamaProvider(ProviderSettings(endpoint=server.url)).complete(_ask("llama3.1:70b"))
    assert 20 <= (caught.value.retry_after or 0) <= 31


# ---------------------------------------------------------------------------------- fake


def test_the_fake_provider_plays_its_script_and_records_requests():
    fake = FakeProvider(replies=["one", {"text": "two", "usage": {"input_tokens": 3,
                                                                    "output_tokens": 4}},
                                 {"error": "rate_limited", "retry_after": 2}])
    assert isinstance(fake, ModelProvider)
    assert fake.complete(_ask("m")).text == "one"
    second = fake.complete(_ask("m"))
    assert (second.text, second.usage.total_tokens) == ("two", 7)
    with pytest.raises(ModelRateLimited) as caught:
        fake.complete(_ask("m"))
    assert caught.value.retry_after == 2
    with pytest.raises(ModelRateLimited):
        fake.complete(_ask("m"))  # the last reply repeats
    assert len(fake.requests) == 4
    fake.ping("m")
    assert fake.pings == ["m"]
