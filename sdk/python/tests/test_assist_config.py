"""Runtime configuration: validated snapshots, the file store, the admin facade, reconfiguring a
router under load, and a change made by one process reaching another's router.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
"""

from __future__ import annotations

import json
import os
import pathlib
import stat
import subprocess
import sys
import textwrap
import threading
import time
from typing import Any

import pytest
from assist_support import fake_config

from pravaha.assist import (
    AssistAdmin,
    AssistConfig,
    AssistConfigError,
    Capabilities,
    ChatRequest,
    ChatResponse,
    ConfigConflict,
    FileConfigStore,
    ModelRouter,
    UsageLedger,
)
from pravaha.assist.config import refuse_secrets
from pravaha.assist.store import default_config_path, parse_document

SDK = pathlib.Path(__file__).resolve().parents[1]


def _two_models() -> dict[str, Any]:
    return fake_config({"a": ["from a"], "b": ["from b"]}, chain=["a"])


@pytest.fixture
def store(tmp_path) -> FileConfigStore:
    return FileConfigStore(tmp_path / "assist.json")


@pytest.fixture
def seeded(store) -> FileConfigStore:
    store.save(AssistConfig.from_dict(_two_models()), expected_version=0, by="setup")
    return store


def _router(config: AssistConfig, tmp_path, **kwargs: Any) -> ModelRouter:
    return ModelRouter(config, ledger=UsageLedger(tmp_path / "usage.json"), environ={},
                       user="ana", **kwargs)


# ---------------------------------------------------------------------------------- snapshots


def test_a_snapshot_is_immutable_and_round_trips():
    config = AssistConfig.from_dict(_two_models())
    with pytest.raises(Exception):
        config.models[0].options["replies"] = []  # type: ignore[index]
    with pytest.raises(Exception):
        config.profiles["explain"] = ("b",)  # type: ignore[index]
    again = AssistConfig.from_dict(json.loads(json.dumps(config.to_dict())))
    assert again == config


@pytest.mark.parametrize(
    "change, words",
    [
        (lambda d: d["providers"].append({"id": "x", "type": "no-such-provider"}),
         "no provider type 'no-such-provider'"),
        (lambda d: d["models"].append(dict(d["models"][0])), "model id 'a' is used twice"),
        (lambda d: d["providers"].append({"id": "fake", "type": "fake"}),
         "provider id 'fake' is used twice"),
        (lambda d: d["profiles"].update(explain=["a", "ghost"]), "no model 'ghost' is configured"),
        (lambda d: d["models"][0].update(enabled=False), "model 'a' is disabled"),
        (lambda d: d["models"][0].update(provider="elsewhere"), "no provider 'elsewhere'"),
        (lambda d: d.update(default_profile="nope"), "default_profile 'nope' is not a profile"),
        (lambda d: d.update(budgets={"per_request_max_tokens": -5}), "positive whole number"),
        (lambda d: d.update(colour="blue"), "unknown field colour"),
        (lambda d: d["models"][0].update(temperature=2), "unknown field temperature"),
    ],
)
def test_an_invalid_document_is_refused_with_a_named_problem(change, words):
    document = _two_models()
    change(document)
    with pytest.raises(AssistConfigError, match=words.replace("(", r"\(")):
        AssistConfig.from_dict(document)


@pytest.mark.parametrize(
    "where",
    [
        lambda d: d["providers"][0].update(api_key="abc"),
        lambda d: d["providers"][0].update(token="abc"),
        lambda d: d["providers"][0].setdefault("options", {}).update(**{"client-secret": "abc"}),
        lambda d: d["models"][0]["options"].update(note="sk-ant-api03-abcdef"),
        lambda d: d["providers"][0].update(endpoint="Bearer abc.def"),
    ],
)
def test_a_key_in_configuration_is_refused_whoever_wrote_it(where, store):
    document = _two_models()
    where(document)
    with pytest.raises(AssistConfigError, match="never holds a secret"):
        AssistConfig.from_dict(document)
    with pytest.raises(AssistConfigError, match="never holds a secret"):
        refuse_secrets(document)
    store.path.write_text(json.dumps(document))
    with pytest.raises(AssistConfigError, match="never holds a secret"):
        store.load()


def test_keys_are_named_by_variable_or_file_and_checked_in_this_process(tmp_path):
    document = _two_models()
    document["providers"].append({"id": "claude", "type": "anthropic", "api_key_env": "CLAUDE_KEY"})
    document["models"].append({"id": "c", "provider": "claude", "model": "claude-opus-5"})
    config = AssistConfig.from_dict(document)  # the document alone is valid
    assert "environment variable CLAUDE_KEY" in " ".join(config.problems({}))
    assert config.problems({"CLAUDE_KEY": "x"}) == []
    secret = tmp_path / "key"
    secret.write_text("the-key\n")
    os.chmod(secret, 0o644)
    document["providers"][-1] = {"id": "claude", "type": "anthropic", "api_key_file": str(secret)}
    config = AssistConfig.from_dict(document)
    assert "may be read by others" in " ".join(config.problems({}))
    os.chmod(secret, 0o600)
    assert config.problems({}) == []
    assert config.provider("claude").resolve_key({}) == "the-key"
    document["providers"][-1] = {"id": "claude", "type": "anthropic"}
    assert "needs a key" in " ".join(AssistConfig.from_dict(document).problems({}))


def test_duplicate_fields_in_the_json_are_refused():
    with pytest.raises(AssistConfigError, match="appears twice"):
        parse_document('{"profiles": {}, "profiles": {}}')


def test_yaml_and_toml_are_refused_with_directions(tmp_path):
    for name in ("assist.yaml", "assist.toml"):
        with pytest.raises(AssistConfigError, match="is JSON"):
            FileConfigStore(tmp_path / name)


def test_the_default_path(monkeypatch, tmp_path):
    monkeypatch.delenv("PRAVAHA_ASSIST_CONFIG", raising=False)
    monkeypatch.setenv("PRAVAHA_CONFIG_DIR", str(tmp_path))
    assert default_config_path() == tmp_path / "assist.json"
    monkeypatch.setenv("PRAVAHA_ASSIST_CONFIG", str(tmp_path / "elsewhere.json"))
    assert default_config_path() == tmp_path / "elsewhere.json"


# ---------------------------------------------------------------------------------- the store


def test_the_store_writes_atomically_0600_and_versions_each_save(store):
    assert store.load() == AssistConfig()
    first = store.save(AssistConfig.from_dict(_two_models()), expected_version=0, by="ana")
    assert (first.version, first.changed_by) == (1, "ana")
    assert first.changed_at and first.changed_at.endswith("Z")
    assert stat.S_IMODE(os.stat(store.path).st_mode) == 0o600
    assert store.load() == first
    assert [p.name for p in store.path.parent.iterdir() if p.name.endswith(".tmp")] == []
    second = store.save(first.replace(default_profile="explain"), expected_version=1, by="bo")
    assert second.version == 2
    with pytest.raises(ConfigConflict, match="at version 2"):
        store.save(first, expected_version=1, by="ana")


def test_the_store_refuses_a_secret_on_the_way_in(store):
    config = AssistConfig.from_dict(_two_models())
    leaky = config.replace(models=[config.models[0].__class__(
        "a", "fake", "fake-a", True, None, {"password": "x"}), config.models[1]])
    with pytest.raises(AssistConfigError, match="never holds a secret"):
        store.save(leaky, expected_version=0, by="ana")
    assert not store.path.exists()


# ---------------------------------------------------------------------------------- admin


def test_each_admin_change_is_validated_stored_applied_and_audited(seeded, tmp_path):
    router = _router(seeded.load(), tmp_path)
    admin = AssistAdmin(seeded, router, actor="ana", environ={})
    record = admin.set_chain("explain", ["b", "a"])
    assert (record.actor, record.action, record.target) == ("ana", "set-chain", "explain")
    assert record.before == {"chain": ["a"], "default": True}
    assert record.after == {"chain": ["b", "a"], "default": True}
    assert record.version == seeded.load().version == 2
    assert router.config.version == 2
    assert router.ask("x", profile="explain").model_id == "b"

    record = admin.add_model("c", "fake", "fake-c", options={"replies": ["from c"]})
    assert record.before is None and record.after["model"] == "fake-c"
    admin.set_chain("draft", ["c"])
    assert router.ask("x", profile="draft").model_id == "c"
    admin.set_default_profile("draft")
    admin.set_budgets(per_request_max_tokens=9000)
    assert seeded.load().budgets.per_request_max_tokens == 9000
    admin.set_budgets(per_request_max_tokens=None)
    assert seeded.load().budgets.per_request_max_tokens is None
    assert json.loads(json.dumps(record.to_dict()))["actor"] == "ana"


def test_a_refused_change_changes_nothing(seeded, tmp_path):
    router = _router(seeded.load(), tmp_path)
    admin = AssistAdmin(seeded, router, actor="ana", environ={})
    before_file = seeded.path.read_bytes()
    with pytest.raises(AssistConfigError, match="model 'a' is disabled"):
        admin.disable_model("a")  # the explain chain names it
    with pytest.raises(AssistConfigError, match="no model 'ghost'"):
        admin.set_chain("explain", ["ghost"])
    with pytest.raises(AssistConfigError, match="never holds a secret"):
        admin.add_provider("claude", "anthropic", options={"api-key": "x"})
    # A provider nothing uses yet is valid without its key; a model on it is not, here.
    admin.add_provider("claude", "anthropic", api_key_env="ANTHROPIC_API_KEY")
    assert seeded.load().providers[-1].id == "claude"
    with pytest.raises(AssistConfigError, match="environment variable ANTHROPIC_API_KEY"):
        admin.add_model("c", "claude", "claude-opus-5")
    assert [m.id for m in seeded.load().models] == ["a", "b"]
    assert router.config.version == seeded.load().version
    assert seeded.path.read_bytes() != before_file  # only the valid add_provider was stored


def test_disable_after_the_chain_moves_and_enable_again(seeded, tmp_path):
    admin = AssistAdmin(seeded, actor="ana", environ={})
    admin.set_chain("explain", ["b"])
    record = admin.disable_model("a")
    assert record.before["id"] == "a" and record.after["enabled"] is False
    admin.enable_model("a")
    assert seeded.load().model("a").enabled


def test_a_dry_run_validates_and_stores_nothing(seeded):
    admin = AssistAdmin(seeded, actor="ana", environ={}, dry_run=True)
    record = admin.set_chain("explain", ["b"])
    assert record.after == {"chain": ["b"], "default": True}
    assert record.version == 1 and seeded.load().version == 1
    with pytest.raises(AssistConfigError):
        admin.set_chain("explain", ["ghost"])


def test_list_providers_and_test_a_model(seeded):
    admin = AssistAdmin(seeded, actor="ana", environ={})
    names = [p.name for p in admin.providers()]
    assert names[:5] == ["fake", "anthropic", "openai", "openai-compatible", "ollama"]
    anthropic = next(p for p in admin.providers() if p.name == "anthropic")
    assert anthropic.source == "builtin"
    assert anthropic.capabilities == Capabilities("json_schema", supports_prompt_cache=True)
    result = admin.test_model("a")
    assert result.ok and result.latency_ms >= 0
    admin.update_model("b", options={"replies": ["x"], "ping": "fail"})
    failed = admin.test_model("b")
    assert not failed.ok and failed.error["kind"] == "ModelUnavailable"
    rows = {r["id"]: r for r in admin.models()}
    assert rows["a"]["profiles"] == [{"profile": "explain", "position": 1, "default": True}]


# ---------------------------------------------------------------------------------- reconfigure


def test_reconfigure_validates_first_and_changes_nothing_on_a_problem(tmp_path):
    router = _router(AssistConfig.from_dict(_two_models()), tmp_path)
    before = router.config
    unknown = before.replace(profiles={"explain": ["a", "ghost"]})
    disabled = before.replace(models=[before.models[0].__class__("a", "fake", "fake-a", False),
                                      before.models[1]])
    keyless = AssistConfig.from_dict({
        **_two_models(),
        "providers": [{"id": "fake", "type": "fake"},
                      {"id": "claude", "type": "anthropic", "api_key_env": "UNSET_KEY"}],
        "models": [*_two_models()["models"], {"id": "c", "provider": "claude", "model": "m"}],
    })
    for bad, words in ((unknown, "no model 'ghost'"), (disabled, "model 'a' is disabled"),
                       (keyless, "UNSET_KEY")):
        with pytest.raises(AssistConfigError, match=words):
            router.reconfigure(bad)
        assert router.config is before


class _Slow:
    """A provider that takes a while, so a reconfiguration lands while requests are in flight."""

    name = "slow"

    def __init__(self, label: str) -> None:
        self.label = label

    def capabilities(self, model: str) -> Capabilities:
        return Capabilities()

    def complete(self, request: ChatRequest) -> ChatResponse:
        time.sleep(0.002)
        return ChatResponse(text=self.label, model=request.model)


def test_requests_in_flight_finish_on_their_snapshot_and_none_sees_half_of_two(tmp_path):
    base = AssistConfig.from_dict(_two_models())
    # Odd versions route explain to a with a large budget; even versions to b with a small one.
    odd = base.replace(profiles={"explain": ["a"]}, version=1)
    even = base.replace(profiles={"explain": ["b"]}, version=2,
                        budgets=base.budgets.__class__(per_request_max_tokens=1_000_000))
    router = _router(odd, tmp_path, providers={"a": _Slow("a"), "b": _Slow("b")})
    seen: list[tuple[str, int, str]] = []
    errors: list[BaseException] = []
    stop = threading.Event()

    def ask() -> None:
        while not stop.is_set():
            try:
                answer = router.ask("x", profile="explain")
                seen.append((answer.model_id, answer.config_version, answer.text))
            except BaseException as exc:  # pragma: no cover - reported below
                errors.append(exc)

    workers = [threading.Thread(target=ask) for _ in range(8)]
    for worker in workers:
        worker.start()
    for i in range(200):
        router.reconfigure(odd.replace(version=2 * i + 1) if i % 2 == 0
                           else even.replace(version=2 * i + 2))
        time.sleep(0.001)
    stop.set()
    for worker in workers:
        worker.join()
    assert not errors
    assert len(seen) > 50
    for model_id, version, text in seen:
        assert model_id == text
        assert (model_id == "a") == (version % 2 == 1), (model_id, version)
    assert {m for m, _, _ in seen} == {"a", "b"}


# ---------------------------------------------------------------------------------- watching


def test_a_router_following_the_store_applies_valid_changes_and_keeps_out_invalid_ones(seeded,
                                                                                      tmp_path):
    router = ModelRouter.from_store(seeded, ledger=UsageLedger(tmp_path / "u.json"), environ={})
    watch = router.follow(start=False)
    assert watch.poll() is None, "nothing changed yet"
    AssistAdmin(seeded, actor="bo", environ={}).set_chain("explain", ["b"])
    assert watch.poll() is not None
    assert router.config.version == 2 and router.ask("x", profile="explain").model_id == "b"
    # A change this process cannot satisfy (its key variable is not set here) is kept out.
    AssistAdmin(seeded, actor="bo", environ={"K": "x"}).add_provider(
        "claude", "anthropic", api_key_env="K")
    AssistAdmin(seeded, actor="bo", environ={"K": "x"}).add_model("c", "claude", "claude-opus-5")
    watch.poll()
    assert router.config.version == 2
    assert router.last_rejected is not None and "environment variable K" in str(router.last_rejected)
    # A file mangled by hand is reported, not applied.
    seeded.path.write_text("{ not json")
    watch.poll()
    assert router.config.version == 2 and "not valid JSON" in str(router.last_rejected)


def test_a_change_written_by_another_process_reaches_this_router(seeded, tmp_path):
    router = ModelRouter.from_store(seeded, ledger=UsageLedger(tmp_path / "u.json"), environ={})
    watch = router.follow(interval_s=0.05)
    try:
        script = textwrap.dedent(f"""
            from pravaha.assist import AssistAdmin, FileConfigStore
            admin = AssistAdmin(FileConfigStore({str(seeded.path)!r}), actor="console-admin")
            print(admin.set_chain("explain", ["b", "a"]).version)
        """)
        done = subprocess.run([sys.executable, "-c", script], cwd=str(SDK), capture_output=True,
                              text=True, timeout=60,
                              env={**os.environ, "PYTHONPATH": str(SDK)})
        assert done.returncode == 0, done.stderr
        assert done.stdout.strip() == "2"
        deadline = time.monotonic() + 10
        while router.config.version != 2 and time.monotonic() < deadline:
            time.sleep(0.02)
        assert router.config.version == 2
        assert router.config.changed_by == "console-admin"
        assert router.config.chain("explain") == ("b", "a")
        assert router.ask("x", profile="explain").model_id == "b"
    finally:
        watch.stop()
