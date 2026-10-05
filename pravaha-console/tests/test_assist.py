"""The assistant in the console (ADR-058 phase 3): Admin · AI models, "Describe it", Explain.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The real console -- routes, middleware, templates, the one ModelRouter it holds -- against the
fake engine and the SDK's ``fake`` model provider: no real model and no network. The owner's
requirement is the centre of it: several providers and models configured at once, and switched
from the admin screen while the console runs, the next request using the change with no restart.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from fake_engine import FakeEngine
from fake_identity import csrf_of, sign_in

from core.config.properties_configurator import PropertiesConfigurator
from run_pravaha_web import create_app

SESSION_SECRET = "assist-test-session-secret"


def explanation(who: str) -> str:
    return json.dumps({"summary": f"summary from {who}", "steps": [f"step from {who}"], "notes": []})


def refusal(who: str, rewrite: str | None = None) -> str:
    return json.dumps({"meaning": f"meaning from {who}", "cause": "an unwindowed aggregate",
                       "fix": "window it", "rewrite": rewrite})


def draft(sql: str, *, keys=("txn_id",), questions=(), name="big_spend") -> str:
    return json.dumps({"name": name, "sql": sql, "keys": list(keys),
                       "options": {"retention": None, "index": None, "sink": None, "lane": None},
                       "explanation": "Each transaction over 500, keyed by its id.",
                       "assumptions": ["amount is in cents"], "questions": list(questions),
                       "confidence": 0.8})


ACCEPTED = draft("SELECT txn_id, user_id, amount FROM txn WHERE amount > 500")


def document(models: dict[str, list], chains: dict[str, list[str]], *, default: str = "explain",
             disabled: tuple[str, ...] = (), budgets: dict | None = None) -> dict:
    body = {
        "default_profile": default,
        "providers": [{"id": "fake", "type": "fake"}],
        "models": [{"id": m, "provider": "fake", "model": f"fake-{m}", "enabled": m not in disabled,
                    "options": {"replies": replies}} for m, replies in models.items()],
        "profiles": chains,
    }
    if budgets:
        body["budgets"] = budgets
    return body


class Console:
    """One console over one fake engine, with its assistant's files in ``root``."""

    def __init__(self, root: pathlib.Path, engine: FakeEngine | None = None, config: dict | None = None):
        self.root = root
        self.config_path = root / "assist.json"
        if config is not None:
            self.config_path.write_text(json.dumps(config), encoding="utf-8")
        self.engine = engine or FakeEngine()
        settings = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
        settings.set("console.session_secret", SESSION_SECRET)
        settings.set("ui.default_role", "operator")
        settings.set("ui.component_gallery", "false")
        settings.set("console.secure_cookies", "false")
        settings.set("assist.config", str(self.config_path))
        settings.set("assist.usage", str(root / "usage.json"))
        settings.set("assist.log", str(root / "log.jsonl"))
        # Nothing polls behind a test's back: a test that wants the watch calls poll() itself.
        settings.set("assist.watch_seconds", "3600")
        self.app = create_app(settings, engine=self.engine)
        self.service = self.app.state.assist

    def client(self, username: str | None = None, roles=("analyst",)):
        client = fastapi_testclient.TestClient(self.app)
        if username is None:
            sign_in(client)
        else:
            password = username.capitalize() + "-password-12"
            if username not in self.engine.identity.users:
                self.engine.identity.add_user(username, password, list(roles))
            sign_in(client, username, password)
        return client

    def stored(self) -> dict:
        return json.loads(self.config_path.read_text(encoding="utf-8"))


def csrf(client, path: str = "/admin/ai-models") -> str:
    return csrf_of(client.get(path).text)


def version_of(html: str) -> str:
    return re.search(r'name="version" value="(\d+)"', html).group(1)


def change(client, op: str, **fields):
    page = client.get("/admin/ai-models").text
    body = {"csrf_token": csrf_of(page), "version": version_of(page), **fields}
    return client.post(f"/admin/ai-models/{op}", data=body, follow_redirects=True)


def ask(client, task: str, **fields):
    token = csrf(client, "/workbench")
    return client.post(f"/api/v1/assist/{task}", data=fields, headers={"X-CSRF-Token": token})


def flashes(html: str) -> list[str]:
    return [re.sub(r"\s+", " ", m).strip() for m in re.findall(r'data-flash="[a-z]+">(.*?)</div>', html, re.DOTALL)]


@pytest.fixture
def two_models(tmp_path):
    return Console(tmp_path, config=document(
        {"a": [refusal("a")], "b": [refusal("b")]},
        {"explain": ["a", "b"], "draft": ["a"]}))


# ============================================================ who may administer

def test_admin_ai_models_is_for_the_engines_administrators(two_models):
    admin = two_models.client()
    page = admin.get("/admin/ai-models")
    assert page.status_code == 200
    assert 'id="models-table"' in page.text and 'data-model="a"' in page.text and 'data-model="b"' in page.text
    assert 'href="/admin/ai-models" aria-current="page"' in page.text
    # An analyst is refused by the console on the engine's word, and is not offered the tab.
    ann = two_models.client("ann")
    refused = ann.get("/admin/ai-models")
    assert refused.status_code == 403 and 'id="ai-not-permitted"' in refused.text
    assert 'id="models-table"' not in refused.text
    assert 'href="/admin/ai-models"' not in ann.get("/admin/access").text
    # ... and a change posted anyway changes nothing.
    before = two_models.stored()
    answer = ann.post("/admin/ai-models/models-disable",
                      data={"csrf_token": csrf(ann, "/account"), "model": "b"}, follow_redirects=True)
    assert "admin role" in " ".join(flashes(answer.text))
    assert two_models.stored() == before
    # Anonymous: the sign-in.
    anonymous = fastapi_testclient.TestClient(two_models.app)
    assert anonymous.get("/admin/ai-models", follow_redirects=False).status_code == 303


def test_the_gate_asks_the_engine_now_not_the_roles_copied_at_sign_in(two_models):
    admin = two_models.client()
    assert admin.get("/admin/ai-models").status_code == 200
    two_models.engine.identity.users["admin"].roles = ["analyst"]
    assert admin.get("/admin/ai-models").status_code == 403


# ============================================================ changing the configuration

def test_providers_and_models_are_added_enabled_disabled_reordered_and_removed(tmp_path):
    console = Console(tmp_path)
    admin = console.client()
    assert 'id="models-empty"' in admin.get("/admin/ai-models").text
    assert "done: add-provider" in " ".join(flashes(change(admin, "providers-add", provider="local",
                                                             type="fake", key_kind="none").text)).lower()
    for model in ("m1", "m2"):
        change(admin, "models-add", model=model, provider="local", name=f"fake-{model}", enabled="yes")
    change(admin, "profiles-set", profile="explain", chain="m1, m2", default="yes")
    assert console.stored()["profiles"] == {"explain": ["m1", "m2"]}
    assert console.service.router.config.profiles["explain"] == ("m1", "m2")

    change(admin, "profiles-down", profile="explain", model="m1")
    assert console.service.router.config.profiles["explain"] == ("m2", "m1")
    # A model a chain names cannot be disabled: what answers is always somebody's decision.
    page = change(admin, "models-disable", model="m1")
    assert "Not changed" in " ".join(flashes(page.text)) and "disabled" in page.text
    assert console.stored()["models"][0].get("enabled", True) is True
    change(admin, "profiles-drop", profile="explain", model="m1")
    change(admin, "models-disable", model="m1")
    assert console.stored()["models"][0]["enabled"] is False
    change(admin, "models-enable", model="m1")
    change(admin, "profiles-add", profile="explain", model="m1")
    assert console.service.router.config.profiles["explain"] == ("m2", "m1")
    change(admin, "models-update", model="m1", provider="local", name="fake-renamed", timeout_s="12")
    assert console.service.router.config.model("m1").model == "fake-renamed"
    change(admin, "profiles-drop", profile="explain", model="m1")
    change(admin, "models-remove", model="m1")
    assert [m["id"] for m in console.stored()["models"]] == ["m2"]

    page = admin.get("/admin/ai-models").text
    # Every change is on the page: who, what, before and after, newest first.
    changes = page.split('id="changes-table"', 1)[1]
    assert changes.index("remove-model") < changes.index("add-provider")
    assert "admin" in changes and "fake-renamed" in changes
    log = [json.loads(line) for line in (tmp_path / "log.jsonl").read_text().splitlines()]
    assert {e["kind"] for e in log} == {"change"} and log[0]["action"] == "add-provider"
    assert all(e["actor"] == "admin" for e in log)


def test_a_change_made_in_the_ui_is_used_by_the_very_next_request(two_models):
    admin = two_models.client()
    first = ask(admin, "explain-refusal", code="PRV-2050")
    assert first.status_code == 200 and first.json()["result"]["answeredBy"]["modelId"] == "a"
    change(admin, "profiles-down", profile="explain", model="a")      # b first now
    second = ask(admin, "explain-refusal", code="PRV-2050")
    assert second.json()["result"]["answeredBy"]["modelId"] == "b"
    assert "meaning from b" in second.json()["html"]
    # Disable-by-chain, then the other way round: still no restart.
    change(admin, "profiles-drop", profile="explain", model="b")
    change(admin, "models-disable", model="b")
    assert ask(admin, "explain-refusal", code="PRV-2050").json()["result"]["answeredBy"]["modelId"] == "a"


def test_a_change_another_process_stores_is_followed(two_models):
    from pravaha.assist import AssistAdmin, FileConfigStore

    admin = two_models.client()
    assert ask(admin, "explain-refusal", code="PRV-2050").json()["result"]["answeredBy"]["modelId"] == "a"
    # `pravaha assist use explain b --yes`, from a shell, on the same file.
    AssistAdmin(FileConfigStore(two_models.config_path), actor="ops").set_chain("explain", ["b"])
    two_models.service.watch.poll()
    assert ask(admin, "explain-refusal", code="PRV-2050").json()["result"]["answeredBy"]["modelId"] == "b"


def test_a_conflicting_edit_is_refused_and_said_as_one(two_models):
    admin = two_models.client()
    page = admin.get("/admin/ai-models").text
    stale = {"csrf_token": csrf_of(page), "version": version_of(page)}
    # Another administrator changes the chain after this page was drawn.
    two_models.service.change("carol", None, "set_chain", "explain", ["b", "a"])
    answer = admin.post("/admin/ai-models/profiles-drop", data={**stale, "profile": "explain", "model": "a"},
                        follow_redirects=True)
    said = " ".join(flashes(answer.text))
    assert "somebody else changed the configuration" in said and "carol" in said
    assert two_models.stored()["profiles"]["explain"] == ["b", "a"]


def test_the_test_button_shows_latency_or_the_normalised_error(tmp_path):
    config = document({"up": [explanation("up")], "down": [explanation("down")]}, {"explain": ["up"]})
    config["models"][1]["options"]["ping"] = "fail"
    console = Console(tmp_path, config=config)
    admin = console.client()
    ok = " ".join(flashes(change(admin, "models-test", model="up").text))
    assert re.search(r"up answered in \d+ ms", ok)
    failed = " ".join(flashes(change(admin, "models-test", model="down").text))
    assert "down did not answer: ModelUnavailable" in failed and "scripted ping failure" in failed


def test_a_key_is_named_never_shown_and_a_key_value_is_refused(tmp_path, monkeypatch):
    monkeypatch.setenv("CONSOLE_TEST_KEY", "sk-the-actual-secret-value")
    console = Console(tmp_path)
    admin = console.client()
    refused = change(admin, "providers-add", provider="leak", type="anthropic", key_kind="env",
                     key_ref="sk-ant-api03-pasted-by-mistake")
    assert "never holds a secret" in " ".join(flashes(refused.text))
    assert "providers" not in console.stored() if console.config_path.exists() else True
    change(admin, "providers-add", provider="claude", type="anthropic", key_kind="env", key_ref="CONSOLE_TEST_KEY")
    change(admin, "models-add", model="opus", provider="claude", name="claude-opus-5", enabled="yes")
    page = admin.get("/admin/ai-models").text
    assert "CONSOLE_TEST_KEY" in page and "set here" in page
    assert "sk-the-actual-secret-value" not in page
    assert "sk-the-actual-secret-value" not in console.config_path.read_text()
    # A model on a provider whose variable this process lacks is refused, nothing stored.
    change(admin, "providers-add", provider="other", type="anthropic", key_kind="env", key_ref="NOT_SET_HERE_XYZ")
    missing = change(admin, "models-add", model="other-m", provider="other", name="x", enabled="yes")
    assert "NOT_SET_HERE_XYZ" in " ".join(flashes(missing.text))
    assert "other-m" not in console.config_path.read_text()


def test_budgets_are_set_here_and_respected_by_the_next_request(two_models):
    admin = two_models.client()
    change(admin, "budgets-set", per_user_daily_tokens="", per_request_max_tokens="100")
    assert two_models.stored()["budgets"] == {"per_request_max_tokens": 100}
    answer = ask(admin, "explain-refusal", code="PRV-2050")
    assert answer.status_code == 429 and 'data-assist-failure="budget"' in answer.json()["html"]
    assert "Over budget" in answer.json()["html"]
    bad = change(admin, "budgets-set", per_user_daily_tokens="lots", per_request_max_tokens="")
    assert "whole number of tokens" in " ".join(flashes(bad.text))


def test_usage_is_shown_per_model_and_per_person(two_models):
    admin = two_models.client()
    ann = two_models.client("ann")
    ask(admin, "explain-refusal", code="PRV-2050")
    ask(ann, "explain-refusal", code="PRV-2050")
    page = admin.get("/admin/ai-models").text
    models = page.split('id="usage-models"', 1)[1].split("</table>", 1)[0]
    people = page.split('id="usage-people"', 1)[1].split("</table>", 1)[0]
    assert ">a</th>" in models and ">admin</th>" in people and ">ann</th>" in people
    ledger = json.loads((two_models.root / "usage.json").read_text())
    today = max(ledger["days"])
    assert set(ledger["days"][today]) == {"admin", "ann"}


# ============================================================ CSRF

def test_every_change_and_every_assist_request_needs_the_sessions_csrf_token(two_models):
    admin = two_models.client()
    page = admin.get("/admin/ai-models").text
    before = two_models.stored()
    # sign_in sends the token on every request as api.js does; this test sends none.
    admin.headers.pop("X-CSRF-Token", None)
    refused = admin.post("/admin/ai-models/profiles-drop",
                         data={"version": version_of(page), "profile": "explain", "model": "a"})
    assert refused.status_code == 403 and two_models.stored() == before
    assert admin.post("/api/v1/assist/explain-refusal", data={"code": "PRV-2050"}).status_code == 403
    assert admin.post("/assist/draft", data={"description": "x"}).status_code == 403


# ============================================================ Describe it

@pytest.fixture
def drafting(tmp_path):
    def make(replies: list) -> Console:
        return Console(tmp_path, config=document({"d": replies, "e": [explanation("e"), refusal("e")]},
                                                 {"draft": ["d"], "explain": ["e"]}))
    return make


def test_describe_it_drafts_and_the_engine_judges(drafting):
    console = drafting([ACCEPTED])
    admin = console.client()
    workbench = admin.get("/workbench").text
    assert 'id="assist-describe-form"' in workbench and 'id="assist-describe-empty"' not in workbench
    answer = ask(admin, "draft", description="transactions over 500", next="/workbench")
    body = answer.json()
    assert answer.status_code == 200 and body["result"]["status"] == "accepted"
    html = body["html"]
    assert 'data-draft-status="accepted"' in html and 'data-assist-verdict="accepted"' in html
    assert "CREATE CONTINUOUS QUERY big_spend" in html and "amount &gt; 500" in html
    assert "data-assist-plan" in html and "amount is in cents" in html
    assert 'href="/workbench?sql=SELECT' in html and "data-assist-copy" in html
    register = html.split('id="assist-register-', 1)[1].split("</form>", 1)[0]
    assert "disabled" not in register.split('type="submit"', 1)[1].split(">", 1)[0]


def test_register_needs_an_accepted_draft_the_persons_confirmation_and_their_own_draft(drafting):
    console = drafting([ACCEPTED])
    admin = console.client()
    draft_id = ask(admin, "draft", description="transactions over 500").json()["result"]["id"]
    unconfirmed = ask(admin, "register", draft_id=draft_id, name="big_spend")
    assert unconfirmed.status_code == 409 and "must confirm" in unconfirmed.json()["html"]
    assert console.engine.registered == []
    # Another person cannot register somebody else's draft by its id.
    ann = console.client("ann")
    assert ask(ann, "register", draft_id=draft_id, confirmed="yes").status_code == 409
    assert console.engine.registered == []
    done = ask(admin, "register", draft_id=draft_id, name="big_spend", confirmed="yes")
    assert done.status_code == 200 and 'data-assist-registered="big_spend"' in done.json()["html"]
    assert console.engine.registered == [{"name": "big_spend", "keys": [0], "sink": None, "retention": None,
                                          "sql": "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500"}]
    log = [json.loads(line) for line in (console.root / "log.jsonl").read_text().splitlines()]
    requests = [e for e in log if e["kind"] == "request"]
    assert [(e["task"], e["registered"]) for e in requests][-1] == ("register", True)
    assert all("transactions over 500" not in json.dumps(e) for e in requests)  # a hash, not the words


def test_a_refused_draft_cannot_be_registered(drafting):
    console = drafting([draft("SELECT txn_id, amout FROM txn")])
    admin = console.client()
    answer = ask(admin, "draft", description="the amounts")
    result = answer.json()["result"]
    assert result["status"] == "refused" and result["repairs"] == 3 and result["verdict"]["code"] == "PRV-2002"
    html = answer.json()["html"]
    assert 'data-draft-status="refused"' in html and "PRV-2002" in html
    assert "Register is available once the engine accepts the draft." in html
    # The refusal carries its own Explain.
    assert 'action="/assist/explain-refusal"' in html and 'value="PRV-2002"' in html
    refused = ask(admin, "register", draft_id=result["id"], confirmed="yes")
    assert refused.status_code == 409 and "not accepted by the engine" in refused.json()["html"]
    assert console.engine.registered == []


def test_questions_are_answered_inline_and_drafted_again(drafting):
    console = drafting([draft("", questions=["Which amount counts as big?"]), ACCEPTED])
    admin = console.client()
    first = ask(admin, "draft", description="big transactions", next="/workbench")
    html = first.json()["html"]
    assert first.json()["result"]["status"] == "questions"
    assert "Which amount counts as big?" in html and 'name="answer_0"' in html and 'name="question_0"' in html
    assert console.engine.registered == []
    again = ask(admin, "draft", description="big transactions", question_0="Which amount counts as big?",
                answer_0="over 500")
    assert again.json()["result"]["status"] == "accepted"
    asked = console.service.router.provider("d").requests[-1].messages[0].content
    assert "big transactions" in asked and "Which amount counts as big? over 500" in asked


def test_a_draft_the_same_as_a_running_query_offers_to_reuse_it(drafting):
    console = drafting([draft("SELECT txn_id, user_id, amount FROM txn WHERE amount > 100")])
    html = ask(console.client(), "draft", description="transactions over 100").json()["html"]
    assert "A running query appears to compute the same thing" in html and 'href="/views/big_txn"' in html


def test_describe_it_works_without_scripting(drafting):
    console = drafting([ACCEPTED])
    admin = console.client()
    page = admin.post("/assist/draft", data={"csrf_token": csrf(admin, "/workbench"),
                                              "description": "transactions over 500", "next": "/workbench"})
    assert page.status_code == 200 and "<h1>A drafted query" in page.text
    assert 'href="/workbench">' in page.text and "CREATE CONTINUOUS QUERY big_spend" in page.text


# ============================================================ Explain buttons

def test_query_detail_explains_the_query_and_every_refusal_code(tmp_path):
    # The fake answers in script order: two explanations, then a refusal's explanation.
    two_models = Console(tmp_path, config=document(
        {"a": [explanation("a"), explanation("a"), refusal("a")]}, {"explain": ["a"]}))
    admin = two_models.client()
    page = admin.get("/queries/big_txn").text
    assert 'id="assist-explain-form"' in page
    # big_txn's sink is detached with PRV-8009: an Explain sits beside the code.
    sink = page.split('id="sink-failure"', 1)[1].split("</div>", 1)[0]
    assert 'action="/assist/explain-refusal"' in sink and 'value="PRV-8009"' in sink
    explained = ask(admin, "explain-query", name="big_txn")
    assert explained.status_code == 200 and "summary from a" in explained.json()["html"]
    assert 'data-assist-answered-by="a"' in explained.json()["html"]
    # Without scripting: a page of its own, and the way back.
    plain = admin.post("/assist/explain-query", data={"csrf_token": csrf(admin), "name": "big_txn",
                                                      "next": "/queries/big_txn"})
    assert plain.status_code == 200 and "summary from" in plain.text and 'href="/queries/big_txn"' in plain.text
    why = ask(admin, "explain-refusal", code="PRV-8009", sql="SELECT 1")
    assert 'data-assist-explained="PRV-8009"' in why.json()["html"]


def test_the_workbench_offers_explain_beside_a_refusal(two_models):
    admin = two_models.client()
    two_models.engine.fail("query_typed", status=400, code="PRV-2002", message="PRV-2002 column 'amout' not found")
    page = admin.post("/workbench", data={"csrf_token": csrf(admin, "/workbench"),
                                          "sql": "SELECT txn_id FROM txn WHERE amout > 1"}).text
    why = page.split('action="/assist/explain-refusal"', 1)[1].split("</form>", 1)[0]
    assert 'value="PRV-2002"' in why and "amout" in why and 'id="assist-why-workbench-refused"' in page
    # The island's diagnostics offer the same, told by the mount that a model can answer.
    assert 'data-assist-ready="1"' in page


def test_an_engine_refusal_is_said_as_the_engines(two_models):
    admin = two_models.client()
    answer = ask(admin, "explain-query", name="no_such_query")
    assert answer.status_code == 404 and 'data-assist-failure="engine"' in answer.json()["html"]
    assert "PRV-8002" in answer.json()["html"]


# ============================================================ the empty state

@pytest.mark.parametrize("config", [None, document({"a": [explanation("a")]}, {}, default=None, disabled=("a",))])
def test_with_no_usable_model_every_surface_shows_one_empty_state(tmp_path, config):
    if config is not None:
        config.pop("default_profile")
    console = Console(tmp_path, config=config)
    admin = console.client()
    workbench = admin.get("/workbench").text
    assert 'id="assist-describe-empty"' in workbench and 'href="/admin/ai-models"' in workbench
    assert 'id="assist-describe-form"' not in workbench and "data-assist-ready" not in workbench
    query = admin.get("/queries/big_txn").text
    assert 'id="assist-explain-empty"' in query and 'action="/assist/explain-refusal"' not in query
    answer = ask(admin, "explain-refusal", code="PRV-2050")
    assert answer.status_code == 503 and "data-assist-empty" in answer.json()["html"]
    # A person who is not an administrator is told whom to ask, not sent to a screen they cannot use.
    ann = console.client("ann")
    theirs = ann.get("/workbench").text
    assert "Ask an administrator to configure one." in theirs
    assert 'href="/admin/ai-models"' not in theirs.split('id="assist-describe-empty"', 1)[1].split("</div>", 1)[0]
    status = admin.get("/admin/ai-models").text
    assert "no model is configured" in status or "every model is disabled" in status
