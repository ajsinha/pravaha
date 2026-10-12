"""The shared LLM contract (``pravaha-ai/src/test/resources/contract/llm_contract.json``) against the mock
provider through the canonical abstractions; the engine's ``LLMContractTest`` runs the same file. And the
factory's governed models go through the policy.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from pravaha.ai import AiLayer, CallContext
from pravaha.ai.llm import (ChatCompletionRequest, ChatMessage, EmbeddingsRequest, GovernedModel, LLMError,
                            PravahaRequest, ProviderBase)

REPO = Path(__file__).resolve().parents[3]
CONTRACT = json.loads((REPO / "pravaha-ai/src/test/resources/contract/llm_contract.json").read_text(encoding="utf-8"))
P = CONTRACT["provider"]
PROVIDER = ProviderBase.from_settings(P["name"], {**P["config"], "type": P["type"]})


def msgs(m):
    return [ChatMessage(role=r, content=t) for r, t in m]


def test_the_catalogue():
    assert PROVIDER.name == "contract" and PROVIDER.active
    got = [(m.id, m.kind, m.qualified_id) for m in PROVIDER.models()]
    assert got == [(c["id"], c["kind"], c["qualified_id"]) for c in CONTRACT["catalogue"]]
    assert PROVIDER.health().ok and PROVIDER.describe_config()["apiKey"] == ""


def test_chat_scenarios_answer_as_in_java():
    m = PROVIDER.chat_model("")
    assert m.info().id == "chat"
    for s in CONTRACT["chat"]:
        want = s["expect"]
        req = ChatCompletionRequest(model="chat", messages=msgs(s["messages"]))
        if "error" in want:
            with pytest.raises(LLMError) as e:
                m.chat_completions_create(req)
            assert e.value.kind == want["error"]
            continue
        c = m.chat_completions_create(req)
        assert c.finish_reason == want["finish_reason"], s["name"]
        if "text" in want:
            assert c.text == want["text"]
        if "tool" in want:
            assert c.tool_calls[0].function.name == want["tool"]
            assert c.tool_calls[0].function.arguments == want["tool_arguments"]
        assert c.usage.total_tokens > 0 and c.pravaha.qualified_model == "contract/chat"


def test_a_stream_is_the_completion_in_chunks_and_the_async_twins_agree():
    s = CONTRACT["stream"]
    m = PROVIDER.model("chat", "chat")
    chunks = list(m.chat_completions_stream(ChatCompletionRequest(model="chat", messages=msgs(s["messages"]))))
    assert len(chunks) == s["chunks"] and "".join(c.delta for c in chunks) == s["joined"]
    assert chunks[-1].choices[0].finish_reason == s["last_finish_reason"]

    async def run():
        out = [c async for c in m.achat_completions_stream(messages=[{"role": "user", "content": "hello"}])]
        one = await m.achat_completions_create(messages=[{"role": "user", "content": "ping"}])
        return out, one
    out, one = asyncio.run(run())
    assert len(out) == 3 and one.text == "echo: ping"


def test_embeddings():
    e = CONTRACT["embeddings"]
    r = PROVIDER.embedding_model("vec").embeddings_create(EmbeddingsRequest(model="vec", input=e["input"]))
    assert len(r.data) == e["count"] and len(r.vectors[0]) == e["dimensions"]


def test_refusals_carry_the_same_kinds_as_in_java():
    for s in CONTRACT["refusals"]:
        with pytest.raises(LLMError) as err:
            m = PROVIDER.model(s["model"])
            if s["call"] == "chat":
                m.chat_completions_create(messages=[{"role": "user", "content": "x"}])
            else:
                m.embeddings_create(input=["x"])
        assert err.value.kind == s["error"], s["name"]


def test_the_factory_hands_out_governed_models_that_go_through_the_policy(tmp_path):
    (tmp_path / "contract.json").write_text(json.dumps({**P["config"], "type": "mock"}), encoding="utf-8")
    ai = AiLayer({"ai.enabled": True, "ai.dir": str(tmp_path), "ai.redact": ["pin"],
                  "ai.policy.features": {"secret-feature": {"roles": ["admin"]}}})
    m = ai.llm.model("default", context=CallContext("ann", frozenset(), "help"))
    assert isinstance(m, GovernedModel)
    assert m.chat_completions_create(messages=[{"role": "user", "content": "my pin=1234"}]).text == "echo: my pin=\u2022\u2022\u2022"
    assert ai.audit_log[-1]["feature"] == "help" and "1234" not in json.dumps(ai.audit_log)
    as_bob = ChatCompletionRequest(messages=[ChatMessage.user("x")],
                                   pravaha=PravahaRequest(CallContext("bob", frozenset(), "secret-feature")))
    with pytest.raises(LLMError) as e:
        m.chat_completions_create(as_bob)
    assert e.value.kind == "POLICY"
    assert len(ai.llm.model("vec", kind="embedding").embeddings_create(input=["a"]).data) == 1
    assert ai.llm.chat_completions_create(messages=[{"role": "user", "content": "hello"}]).text == "Hi there"
    assert ai.llm.provider_names() == ["contract"] and ai.llm.provider("contract") is not None
    off = AiLayer({"ai.dir": str(tmp_path)})
    with pytest.raises(LLMError) as e:
        off.llm.model()
    assert e.value.code == "PRV-1080"
