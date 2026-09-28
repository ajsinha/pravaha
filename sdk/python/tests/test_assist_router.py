"""The router: fallback, budgets, and a refusal that does not fall through.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
import os
import stat

import pytest
from assist_support import fake_config

from pravaha.assist import (
    AssistConfig,
    AssistConfigError,
    BudgetExceeded,
    ChatRequest,
    Message,
    ModelOutputError,
    ModelRateLimited,
    ModelRefused,
    ModelRouter,
    UsageLedger,
)
from pravaha.assist.providers import FakeProvider


@pytest.fixture
def ledger(tmp_path):
    return UsageLedger(tmp_path / "assist-usage.json")


def _router(document, ledger, **kwargs):
    return ModelRouter(AssistConfig.from_dict(document), ledger=ledger, environ={}, user="ana",
                       **kwargs)


def _ask(text="Explain it.", **extra):
    return ChatRequest(messages=[Message("user", text)], max_tokens=100, **extra)


def _fake(router, model_id) -> FakeProvider:
    provider = router.provider(model_id)
    assert isinstance(provider, FakeProvider)
    return provider


def test_the_first_model_that_answers_answers_and_says_so(ledger):
    router = _router(fake_config({
        "down": [{"error": "unavailable", "message": "503 from upstream"}],
        "busy": [{"error": "rate_limited", "retry_after": 4}],
        "up": ["fine"],
    }), ledger)
    answer = router.complete(_ask(), profile="explain")
    assert answer.text == "fine"
    assert (answer.model_id, answer.provider, answer.response.model) == ("up", "fake", "fake-up")
    assert [(a.model_id, a.kind) for a in answer.attempts] == [
        ("down", "ModelUnavailable"), ("busy", "ModelRateLimited")]
    assert answer.attempts[1].retry_after == 4
    by = answer.answered_by()
    assert by["modelId"] == "up" and len(by["attempts"]) == 2
    # Each model was asked with its own model id and the caller as user.
    sent = _fake(router, "up").requests[0]
    assert sent.model == "fake-up" and sent.metadata["user"] == "ana"


@pytest.mark.parametrize("kind, error", [("refused", ModelRefused), ("output", ModelOutputError)])
def test_a_refusal_or_an_unusable_answer_stops_the_chain(ledger, kind, error):
    router = _router(fake_config({"first": [{"error": kind}], "second": ["would answer"]}), ledger)
    with pytest.raises(error) as caught:
        router.complete(_ask())
    assert caught.value.alias == "first"
    assert caught.value.provider == "fake"
    assert _fake(router, "second").requests == [], "a refusal is reported, not passed on"


def test_every_model_failing_reports_the_last_with_the_others(ledger):
    router = _router(fake_config({
        "a": [{"error": "unavailable"}],
        "b": [{"error": "rate_limited", "retry_after": 9}],
    }), ledger)
    with pytest.raises(ModelRateLimited) as caught:
        router.complete(_ask())
    assert caught.value.alias == "b" and caught.value.retry_after == 9
    assert "every model in the chain failed" in caught.value.message
    assert [a.model_id for a in caught.value.attempts] == ["a"]


def test_a_model_whose_key_has_gone_is_passed_over(ledger):
    document = fake_config({"backup": ["from the backup"]})
    document["providers"].append({"id": "claude", "type": "anthropic", "api_key_env": "K"})
    document["models"].insert(0, {"id": "main", "provider": "claude", "model": "claude-opus-5"})
    document["profiles"]["explain"] = ["main", "backup"]
    environ = {"K": "set-at-start"}
    router = ModelRouter(AssistConfig.from_dict(document), ledger=ledger, environ=environ)
    del environ["K"]  # unset after the configuration was applied
    answer = router.complete(_ask())
    assert answer.model_id == "backup"
    assert answer.attempts[0].kind == "AssistConfigError"


def test_only_configuration_failures_are_a_configuration_error(ledger):
    document = {
        "providers": [{"id": "claude", "type": "anthropic", "api_key_env": "K"}],
        "models": [{"id": "main", "provider": "claude", "model": "claude-opus-5"}],
        "profiles": {"explain": ["main"]},
    }
    environ = {"K": "x"}
    router = ModelRouter(AssistConfig.from_dict(document), ledger=ledger, environ=environ)
    environ.clear()
    with pytest.raises(AssistConfigError, match="no model in the chain could be used"):
        router.complete(_ask(), profile="explain")
    with pytest.raises(AssistConfigError, match="no default_profile"):
        router.complete(_ask(), profile="draft")


def test_one_model_can_be_asked_by_id(ledger):
    router = _router(fake_config({"a": ["from a"], "b": ["from b"]}), ledger)
    assert router.complete(_ask(), model="b").model_id == "b"
    with pytest.raises(AssistConfigError, match="no model 'c'"):
        router.complete(_ask(), model="c")


def test_a_profile_not_configured_uses_the_default(ledger):
    router = _router(fake_config({"a": ["from a"]}, profile="everything"), ledger)
    assert router.complete(_ask(), profile="explain").model_id == "a"


def test_a_request_over_the_per_request_budget_is_refused_before_it_is_sent(ledger):
    router = _router(fake_config({"a": ["x"]}, budgets={"per_request_max_tokens": 150}), ledger)
    with pytest.raises(BudgetExceeded, match="per-request budget is 150"):
        router.complete(_ask("y" * 400))  # ~101 in + 100 out
    assert _fake(router, "a").requests == []
    router.complete(_ask("short"))


def test_the_daily_budget_counts_what_was_spent(ledger):
    reply = {"text": "x", "usage": {"input_tokens": 300, "output_tokens": 200}}
    router = _router(fake_config({"a": [reply]}, budgets={"per_user_daily_tokens": 1000}), ledger)
    router.complete(_ask())
    assert ledger.used("ana") == 500
    with pytest.raises(BudgetExceeded, match="ana has used 500 of 1000"):
        router.complete(_ask("z" * 1800))
    assert ledger.used("bo") == 0, "the budget is per user"
    assert stat.S_IMODE(os.stat(ledger.path).st_mode) == 0o600
    document = json.loads(ledger.path.read_text())
    assert document["days"][UsageLedger.today()]["ana"] == {"a": 500}


def test_an_unusable_answer_still_counts_against_the_budget(ledger):
    router = _router(fake_config({"a": ["not json", "still not json"]}), ledger)
    schema = {"type": "object", "properties": {"x": {"type": "string"}}, "required": ["x"]}
    with pytest.raises(ModelOutputError):
        router.complete(_ask(response_schema=schema))
    assert ledger.used("ana") > 0


def test_max_tokens_is_capped_by_what_the_model_can_answer(ledger):
    document = fake_config({"a": ["x"]})
    document["models"][0]["options"]["max_output_tokens"] = 50
    router = _router(document, ledger)
    router.complete(_ask())
    assert _fake(router, "a").requests[0].max_tokens == 50


def test_check_pings_each_enabled_model(ledger):
    document = fake_config({"good": ["x"], "bad": ["x"], "off": ["x"]}, chain=["good", "bad"])
    document["models"][1]["options"]["ping"] = "fail"
    document["models"][2]["enabled"] = False
    router = _router(document, ledger)
    results = {r.model_id: r for r in router.check()}
    assert set(results) == {"good", "bad"}
    assert results["good"].ok and not results["bad"].ok
    assert results["bad"].error["kind"] == "ModelUnavailable"
    assert _fake(router, "good").requests == [], "a ping spends no tokens"


def test_a_provider_that_raises_something_else_is_treated_as_unavailable(ledger):
    class Broken:
        name = "broken"

        def capabilities(self, model):
            from pravaha.assist import Capabilities

            return Capabilities()

        def complete(self, request):
            raise RuntimeError("boom")

    router = _router(fake_config({"a": ["x"], "b": ["from b"]}), ledger,
                     providers={"a": Broken()})
    answer = router.complete(_ask())
    assert answer.model_id == "b"
    assert "RuntimeError: boom" in answer.attempts[0].message
