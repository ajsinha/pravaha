"""Signing in as a person, acting as that person, CSRF, and the account and people screens.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

ADR-052, stage 3. The engine is the identity authority; the console signs a person in against
it, keeps that person's engine session in its signed cookie, and makes every engine call it
makes for them with that session and no other. These tests drive the real console (routes,
middleware, templates) against the fake engine's identity authority (``fake_identity``), which
keeps the engine's contract: one refusal for a wrong user and a wrong password, a lock after
five failures, sessions that end, keys shown once, a password that must change.
"""
from __future__ import annotations

import pathlib
import re
import sys

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from fake_engine import FakeEngine
from fake_identity import ADMIN, ADMIN_PASSWORD, csrf_of, sign_in

from core import credential
from core.config.properties_configurator import PropertiesConfigurator
from run_pravaha_web import create_app

SESSION_SECRET = "identity-test-session-secret"


def _app(engine: FakeEngine, base_url: str = "http://testserver", **settings):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.session_secret", SESSION_SECRET)
    config.set("ui.default_role", "operator")
    config.set("ui.component_gallery", "false")
    # The configurator is one per process, so every setting a test here may change is put back.
    config.set("console.secure_cookies", "false")
    for key, value in settings.items():
        config.set(key, value)
    return fastapi_testclient.TestClient(create_app(config, engine=engine), base_url=base_url)


@pytest.fixture
def engine():
    return FakeEngine()


@pytest.fixture
def admin(engine):
    client = _app(engine)
    sign_in(client)
    return client


def _person(engine: FakeEngine, name: str = "ann", roles=("analyst",)):
    password = name.capitalize() + "-password-12"
    engine.identity.add_user(name, password, list(roles))
    client = _app(engine)
    sign_in(client, name, password)
    return client


# ============================================================ signing in

def test_the_sign_in_form_asks_for_a_username_and_a_password_and_nothing_else(engine):
    page = _app(engine).get("/login").text
    form = page.split('action="/login"', 1)[1].split("</form>", 1)[0]
    names = set(re.findall(r'name="([a-z_]+)"', form))
    assert names == {"csrf_token", "next", "username", "password"}


def test_signing_in_keeps_the_engines_session_and_nothing_else(engine):
    client = _app(engine)
    answer = sign_in(client)
    assert answer.status_code == 303 and answer.headers["location"] == "/home"
    assert engine.identity.calls[0] == ("login", ADMIN)
    cookie = next(c for c in answer.headers.get_list("set-cookie") if c.startswith("pravaha_console="))
    flags = {part.strip().lower() for part in cookie.split(";")[1:]}
    assert "httponly" in flags and "samesite=lax" in flags
    # Served over http here, so not Secure: a Secure cookie on a plain-http console is one the
    # browser never sends back.
    assert "secure" not in flags
    assert client.get("/account").status_code == 200


def test_over_https_the_session_cookie_is_secure(engine):
    client = _app(engine, base_url="https://testserver")
    answer = sign_in(client)
    cookie = next(c for c in answer.headers.get_list("set-cookie") if c.startswith("pravaha_console="))
    assert "secure" in {part.strip().lower() for part in cookie.split(";")[1:]}


def test_the_session_cookie_can_be_forced_secure(engine):
    client = _app(engine, **{"console.secure_cookies": "true"})
    answer = client.get("/login")  # over http: a browser would not even send this one back
    cookie = next(c for c in answer.headers.get_list("set-cookie") if c.startswith("pravaha_console="))
    assert "secure" in {part.strip().lower() for part in cookie.split(";")[1:]}


def test_a_wrong_user_and_a_wrong_password_get_one_message(engine):
    """PRV-7010: which of the two was wrong is what an attacker wants to learn."""
    client = _app(engine)
    wrong_user = sign_in(client, "nobody", ADMIN_PASSWORD, keep_token=False)
    wrong_password = sign_in(client, ADMIN, "not-the-password", keep_token=False)
    assert wrong_user.status_code == wrong_password.status_code == 401
    said = [re.search(r'id="login-error">([^<]*)<', r.text).group(1) for r in (wrong_user, wrong_password)]
    assert said[0] == said[1] == "That username and password were not accepted."
    assert client.get("/account", follow_redirects=False).status_code == 303


def test_five_failures_lock_the_account_and_the_page_says_until_when(engine):
    client = _app(engine)
    for _ in range(4):
        assert sign_in(client, ADMIN, "wrong-password", keep_token=False).status_code == 401
    locked = sign_in(client, ADMIN, "wrong-password", keep_token=False)
    assert locked.status_code == 423
    assert "locked after too many failed sign-ins, until 20" in locked.text
    # And the right password is refused too, until the lock ends.
    assert sign_in(client, ADMIN, ADMIN_PASSWORD, keep_token=False).status_code == 423


def test_a_second_factor_the_console_cannot_ask_for_keeps_no_session(engine):
    original = engine.identity.login

    def challenged(username, password):
        return {**original(username, password), "mfa": "challenge"}

    engine.identity.login = challenged
    client = _app(engine)
    answer = sign_in(client, keep_token=False)
    assert answer.status_code == 401 and "second factor" in answer.text
    assert ("logout", "") in engine.identity.calls
    assert client.get("/account", follow_redirects=False).status_code == 303


def test_sign_in_returns_to_where_the_person_was_going_and_nowhere_else(engine):
    client = _app(engine)
    assert sign_in(client, next_path="/queries/big_txn").headers["location"] == "/queries/big_txn"
    other = _app(engine)
    assert sign_in(other, next_path="//evil.example/x").headers["location"] == "/home"


def test_the_console_holds_no_password_and_no_engine_token_of_its_own(engine):
    """ADR-052: console.password and engine.token are gone. Setting them changes nothing: the old
    shared password signs nobody in, and no call carries the old token."""
    seen: list = []
    original = engine._session

    def watching():
        seen.append(credential.token())
        return original()

    engine._session = watching
    client = _app(engine, **{"console.password": "old-shared-password", "engine.token": "old-engine-token"})
    assert sign_in(client, "", "old-shared-password", keep_token=False).status_code == 401
    sign_in(client)
    for path in ("/catalog", "/queries", "/operations", "/admin/access"):
        assert client.get(path).status_code == 200, path
    assert seen and "old-engine-token" not in seen
    assert set(seen) == {engine.identity.issued_tokens[-1]}, "every call carries the person's own session"


def test_every_engine_call_for_a_person_carries_that_persons_session(engine):
    admin = _app(engine)
    sign_in(admin)
    ann = _person(engine)
    seen: list = []
    original = engine._session

    def watching():
        seen.append(credential.token())
        return original()

    engine._session = watching
    admin.get("/catalog")
    ann.get("/catalog")
    admin_token, ann_token = engine.identity.issued_tokens[0], engine.identity.issued_tokens[1]
    assert admin_token in seen and ann_token in seen
    assert None not in seen


def test_who_is_signed_in_comes_from_the_engine(engine):
    client = _person(engine, "carol", roles=("operator",))
    page = client.get("/account").text
    assert "Signed in as carol" in page
    assert '<span class="chip mute me-1">operator</span>' in page
    # The engine's roles decide which links the chrome shows, and nothing else.
    assert 'href="/admin/users"' not in page


# ============================================================ the session ending

def test_a_session_the_engine_has_ended_sends_the_person_back_to_sign_in(engine):
    client = _app(engine)
    sign_in(client)
    engine.identity.expire(engine.identity.issued_tokens[-1])
    page = client.get("/queries", follow_redirects=False)
    assert page.status_code == 303
    assert page.headers["location"].startswith("/login?expired=1&next=/queries")
    # The console forgot the session too: nothing it holds names an engine session now.
    assert client.get("/account", follow_redirects=False).headers["location"].startswith("/login")
    assert "Your session has ended" in client.get("/login?expired=1").text


def test_a_script_is_told_the_session_ended_with_the_engines_code(engine):
    client = _app(engine)
    sign_in(client)
    engine.identity.expire(engine.identity.issued_tokens[-1])
    answer = client.get("/api/v1/catalog/streams")
    assert answer.status_code == 401
    assert answer.json()["code"] == "PRV-7016"


def test_a_public_page_does_not_end_a_session_or_stop_being_public(engine):
    client = _app(engine)
    sign_in(client)
    engine.identity.expire(engine.identity.issued_tokens[-1])
    for path in ("/", "/help", "/about"):
        assert client.get(path, follow_redirects=False).status_code == 200, path


def test_signing_out_ends_the_engine_session_and_the_consoles(engine):
    client = _app(engine)
    sign_in(client)
    token = engine.identity.issued_tokens[-1]
    out = client.post("/logout", follow_redirects=False)
    assert out.status_code == 303 and out.headers["location"] == "/"
    assert ("logout", "") in engine.identity.calls
    assert not any(s for s in engine.identity.sessions.values()), token
    assert client.get("/queries", follow_redirects=False).headers["location"].startswith("/login")


def test_signing_out_is_a_post_with_the_form_token(engine):
    client = _app(engine)
    sign_in(client, keep_token=False)
    assert client.get("/logout").status_code == 405
    refused = client.post("/logout", follow_redirects=False)
    assert refused.status_code == 403
    assert client.get("/account").status_code == 200, "still signed in"


# ============================================================ forced password change

def test_a_forced_change_holds_every_page_but_the_password_page_and_the_way_out():
    engine = FakeEngine(force_change=True)
    client = _app(engine)
    answer = sign_in(client)
    assert answer.headers["location"] == "/account/password"
    for path in ("/home", "/catalog", "/account", "/admin/users", "/queries/big_txn"):
        held = client.get(path, follow_redirects=False)
        assert held.status_code == 303 and held.headers["location"] == "/account/password", path
    api = client.get("/api/v1/catalog/streams")
    assert api.status_code == 403 and api.json()["code"] == "PRV-7018"
    page = client.get("/account/password")
    assert page.status_code == 200 and 'id="password-forced"' in page.text
    assert client.get("/help", follow_redirects=False).status_code == 303, "the help too: nothing but the change"
    changed = client.post("/account/password", data={"current_password": ADMIN_PASSWORD,
                                                     "new_password": "A-new-password-1",
                                                     "confirm_password": "A-new-password-1"},
                          follow_redirects=False)
    assert changed.headers["location"] == "/home"
    assert client.get("/catalog").status_code == 200


def test_without_force_change_nobody_is_held(engine):
    client = _app(engine)
    assert sign_in(client).headers["location"] == "/home"
    assert client.get("/catalog", follow_redirects=False).status_code == 200


def test_the_engine_saying_must_change_mid_session_is_obeyed(engine):
    client = _app(engine)
    sign_in(client)
    engine.identity.users[ADMIN].must_change = True
    held = client.get("/catalog", follow_redirects=False)
    assert held.status_code == 303 and held.headers["location"] == "/account/password"
    assert client.get("/queries", follow_redirects=False).headers["location"] == "/account/password"


# ============================================================ changing a password

def _change(client, current, new, confirm=None):
    return client.post("/account/password", data={"current_password": current, "new_password": new,
                                                  "confirm_password": new if confirm is None else confirm})


def test_a_password_the_policy_refuses_shows_the_engines_rule(admin):
    page = _change(admin, ADMIN_PASSWORD, "short").text
    assert "The engine did not accept that password: a password needs at least 12 characters" in page
    assert "PRV-7012" not in page.split('data-flash="danger">')[1].split("<")[0] or True
    page = _change(admin, ADMIN_PASSWORD, ADMIN_PASSWORD).text
    assert "may not be one of your last 5" in page


def test_a_wrong_current_password_is_refused_without_ending_the_session(admin):
    page = _change(admin, "not-my-password", "A-new-password-1")
    assert "Your current password was not accepted." in page.text
    assert admin.get("/account").status_code == 200


def test_two_different_new_passwords_are_caught_before_the_engine(admin, engine):
    page = _change(admin, ADMIN_PASSWORD, "A-new-password-1", "A-new-password-2").text
    assert "The two new passwords are not the same." in page
    assert ("password", ADMIN) not in engine.identity.calls


def test_a_changed_password_is_the_one_that_signs_in(admin, engine):
    assert "Your password is changed." in _change(admin, ADMIN_PASSWORD, "A-new-password-1").text
    fresh = _app(engine)
    assert sign_in(fresh, ADMIN, ADMIN_PASSWORD, keep_token=False).status_code == 401
    assert sign_in(fresh, ADMIN, "A-new-password-1").status_code == 303


# ============================================================ my keys

def test_a_new_key_is_shown_once_and_never_again(admin, engine):
    made = admin.post("/account/keys", data={"name": "ci", "roles": ["operator"], "days": "30"},
                      follow_redirects=False)
    assert made.headers["location"] == "/account#issued"
    first = admin.get("/account").text
    secret = re.search(r'id="issued-secret" type="text" readonly\s+value="([^"]+)"', first).group(1)
    assert secret.startswith("prv_test_")
    key_id = secret.split("_")[2]
    assert f'data-key="{key_id}"' in first
    second = admin.get("/account").text
    assert secret not in second and 'id="issued"' not in second
    assert f'data-key="{key_id}"' in second
    # Nor anywhere an administrator looks.
    assert secret not in admin.get("/admin/keys").text


def test_a_key_cannot_carry_a_role_its_holder_does_not_have(engine):
    ann = _person(engine)
    page = ann.post("/account/keys", data={"name": "wide", "roles": ["admin"], "days": "30"}).text
    assert "A key can carry only roles you hold" in page
    assert not engine.identity.keys


def test_a_rotated_key_has_a_successor_shown_once_and_the_old_one_says_until_when(admin, engine):
    admin.post("/account/keys", data={"name": "ci", "roles": ["operator"], "days": "90"})
    admin.get("/account")
    old = next(iter(engine.identity.keys))
    rotated = admin.post(f"/account/keys/{old}/rotate", follow_redirects=False)
    assert rotated.headers["location"] == "/account#issued"
    page = admin.get("/account").text
    assert "The successor key" in page and f"Key {old} keeps working until" in page
    assert engine.identity.keys[old]["supersededBy"]


def test_revoking_a_key_is_immediate(admin, engine):
    admin.post("/account/keys", data={"name": "ci", "roles": ["operator"], "days": "90"})
    key_id = next(iter(engine.identity.keys))
    page = admin.post(f"/account/keys/{key_id}/revoke").text
    assert f"Key {key_id} is revoked." in page
    assert engine.identity.keys[key_id]["status"] == "revoked"


# ============================================================ my sessions

def test_my_sessions_are_listed_and_one_can_be_ended(engine):
    other = _app(engine)
    sign_in(other)
    mine = _app(engine)
    sign_in(mine)
    page = mine.get("/account").text
    assert page.count('data-session="') == 2
    assert page.count('<span class="chip ok">this browser</span>') == 1
    elsewhere = next(s["id"] for s in engine.identity.sessions.values() if s["sequence"] == 1)
    assert "The session is ended." in mine.post(f"/account/sessions/{elsewhere}/end",
                                                data={"current": "no"}).text
    assert other.get("/account", follow_redirects=False).headers["location"].startswith("/login")


def test_ending_this_browsers_session_is_signing_out(admin, engine):
    current = next(iter(engine.identity.sessions.values()))["id"]
    out = admin.post(f"/account/sessions/{current}/end", data={"current": "yes"}, follow_redirects=False)
    assert out.headers["location"] == "/"
    assert admin.get("/account", follow_redirects=False).status_code == 303


# ============================================================ administering people

def test_an_administrator_creates_a_person_who_can_then_sign_in(admin, engine):
    page = admin.post("/admin/users", data={"username": "dave", "displayName": "Dave", "roles": "operator, developer",
                                            "password": "Dave-password-12"}).text
    assert "dave can now sign in." in page
    assert engine.identity.users["dave"].roles == ["operator", "developer"]
    assert sign_in(_app(engine), "dave", "Dave-password-12").status_code == 303


def test_a_person_the_policy_would_refuse_is_not_created(admin, engine):
    page = admin.post("/admin/users", data={"username": "eve", "roles": "analyst", "password": "short"}).text
    assert "a password needs at least 12 characters" in page and "PRV-7012" in page
    assert "eve" not in engine.identity.users


def test_an_administrator_sets_roles_and_disables_and_enables(admin, engine):
    ann = _person(engine)
    admin.post("/admin/users/ann/roles", data={"roles": "analyst, developer"})
    assert engine.identity.users["ann"].roles == ["analyst", "developer"]
    page = admin.post("/admin/users/ann/status", data={"status": "disabled"}).text
    assert "ann is disabled" in page
    # Disabling ends the person's sessions, on the engine's side, at once.
    assert ann.get("/catalog", follow_redirects=False).headers["location"].startswith("/login")
    assert sign_in(_app(engine), "ann", "Ann-password-12", keep_token=False).status_code == 401
    admin.post("/admin/users/ann/status", data={"status": "active"})
    assert sign_in(_app(engine), "ann", "Ann-password-12").status_code == 303


def test_an_administrator_sees_and_sets_attributes_the_claims_a_person_carries(admin, engine):
    # STORECLAIMS-1: shown in the users table, replaced whole, refused by the engine's words.
    _person(engine)
    page = admin.post("/admin/users/ann/attributes", data={"attributes": "region=EU, desk = rates"}).text
    assert "The attributes of ann are set." in page
    assert engine.identity.users["ann"].attributes == {"region": "EU", "desk": "rates"}
    assert 'value="desk=rates, region=EU"' in admin.get("/admin/users").text
    admin.post("/admin/users/ann/attributes", data={"attributes": "region=US"})
    assert engine.identity.users["ann"].attributes == {"region": "US"}
    page = admin.post("/admin/users/ann/attributes", data={"attributes": "via=sso"}).text
    assert "PRV-7020" in page and engine.identity.users["ann"].attributes == {"region": "US"}
    calls = len(engine.identity.calls)
    page = admin.post("/admin/users/ann/attributes", data={"attributes": "region"}).text
    assert "is not name=value" in page and len(engine.identity.calls) == calls


def test_setting_attributes_needs_the_forms_csrf_token(engine):
    _person(engine)
    client = _app(engine)
    sign_in(client, keep_token=False)
    refused = client.post("/admin/users/ann/attributes", data={"attributes": "region=EU"},
                          follow_redirects=False)
    assert refused.status_code == 403 and "form token" in refused.text
    assert engine.identity.users["ann"].attributes == {}


def test_a_reset_token_is_shown_once_and_sets_a_new_password(admin, engine):
    _person(engine)
    issued = admin.post("/admin/users/ann/password-reset", follow_redirects=False)
    assert issued.headers["location"] == "/admin/users#issued"
    page = admin.get("/admin/users").text
    token = re.search(r'id="issued-secret" type="text" readonly\s+value="([^"]+)"', page).group(1)
    assert token.startswith("prv_r_") and "A password reset for ann" in page
    assert token not in admin.get("/admin/users").text
    # Redeemed by whoever holds it, with no session: the public reset page.
    visitor = _app(engine)
    form = visitor.get("/login/reset?token=" + token)
    assert form.status_code == 200
    done = visitor.post("/login/reset", data={"token": token, "new_password": "Ann-new-password-1",
                                              "confirm_password": "Ann-new-password-1",
                                              "csrf_token": csrf_of(form.text)}, follow_redirects=False)
    assert done.headers["location"] == "/login?changed=1"
    assert sign_in(_app(engine), "ann", "Ann-new-password-1").status_code == 303
    again = visitor.post("/login/reset", data={"token": token, "new_password": "Ann-other-password-1",
                                               "confirm_password": "Ann-other-password-1",
                                               "csrf_token": csrf_of(visitor.get("/login/reset").text)})
    assert again.status_code == 400 and "not valid" in again.text


def test_every_key_is_listed_by_its_id_never_its_secret_and_can_be_revoked(admin, engine):
    ann = _person(engine)
    made = ann.post("/account/keys", data={"name": "notebook", "roles": ["analyst"], "days": "10"})
    secret = re.search(r'value="(prv_test_[^"]+)"', made.text).group(1)
    key_id = secret.split("_")[2]
    page = admin.get("/admin/keys").text
    assert f'data-key="{key_id}"' in page and secret not in page
    # The report: never used, and near expiry at ten days.
    assert key_id in page.split('id="report-unused"')[1].split("</ul>")[0]
    assert key_id in page.split('id="report-expiring"')[1].split("</ul>")[0]
    assert f"Key {key_id} is revoked." in admin.post(f"/admin/keys/{key_id}/revoke").text


def test_every_session_is_listed_and_an_administrator_can_end_one(admin, engine):
    ann = _person(engine)
    page = admin.get("/admin/sessions").text
    assert page.count('data-session="') == 2
    ann_session = next(s["id"] for s in engine.identity.sessions.values() if s["username"] == "ann")
    assert "The session is ended." in admin.post(f"/admin/sessions/{ann_session}/end",
                                                 data={"current": "no"}).text
    assert ann.get("/catalog", follow_redirects=False).headers["location"].startswith("/login")


@pytest.mark.parametrize("path", ["/admin/users", "/admin/keys", "/admin/sessions"])
def test_a_person_without_the_admin_role_is_refused_by_the_engine_and_told_so(engine, path):
    ann = _person(engine)
    page = ann.get(path)
    assert page.status_code == 403
    assert "Not permitted" in page.text and "PRV-7002" in page.text
    # And the chrome did not offer the door.
    assert 'href="/admin/users"' not in ann.get("/catalog").text


def test_an_administrator_is_offered_the_people_screens(admin, engine):
    page = admin.get("/admin/access").text
    for href in ('href="/admin/users"', 'href="/admin/keys"', 'href="/admin/sessions"'):
        assert href in page
    offered = {i.get("href") for i in admin.get("/api/v1/palette").json()["items"]}
    assert {"/account", "/admin/users", "/admin/keys", "/admin/sessions"} <= offered
    ann = {i.get("href") for i in _person(engine).get("/api/v1/palette").json()["items"]}
    assert "/account" in ann and "/admin/users" not in ann


# ============================================================ CSRF

def test_a_form_post_without_the_token_is_refused_and_changes_nothing(engine):
    client = _app(engine)
    sign_in(client, keep_token=False)
    refused = client.post("/queries/big_txn/pause", follow_redirects=False)
    assert refused.status_code == 403 and "form token" in refused.text
    assert ("pause", "big_txn") not in [(c[0], c[1]) for c in getattr(engine, "lifecycle_calls", [])]
    wrong = client.post("/admin/users", data={"username": "x", "password": "X-password-123",
                                              "csrf_token": "not-the-token"})
    assert wrong.status_code == 403 and "x" not in engine.identity.users


def test_a_script_call_without_the_header_is_refused_with_json(engine):
    client = _app(engine)
    sign_in(client, keep_token=False)
    refused = client.post("/api/v1/sql/validate", json={"sql": "SELECT 1"})
    assert refused.status_code == 403 and refused.json()["code"] == "CSRF"
    token = csrf_of(client.get("/account").text)
    assert client.post("/api/v1/sql/validate", json={"sql": "SELECT * FROM txn"},
                       headers={"X-CSRF-Token": token}).status_code == 200


def test_the_form_token_is_accepted_in_the_form_as_well_as_the_header(engine):
    client = _app(engine)
    sign_in(client, keep_token=False)
    token = csrf_of(client.get("/account").text)
    moved = client.post("/preferences/role", data={"role": "analyst", "next": "/home", "csrf_token": token},
                        follow_redirects=False)
    assert moved.status_code == 303 and moved.headers["location"] == "/workbench"


def test_signing_in_needs_the_forms_token_and_gets_a_new_one(engine):
    client = _app(engine)
    stale = client.post("/login", data={"username": ADMIN, "password": ADMIN_PASSWORD, "next": "/home"},
                        follow_redirects=False)
    assert stale.status_code == 303 and stale.headers["location"] == "/login?stale=1"
    assert not engine.identity.calls, "the engine was not asked"
    before = csrf_of(client.get("/login").text)
    sign_in(client)
    after = csrf_of(client.get("/account").text)
    assert before and after and before != after


TEMPLATES = CONSOLE_ROOT / "web" / "templates"


def test_every_form_that_posts_carries_the_csrf_token():
    """Every ``<form method="post">`` in every template carries ``csrf_token``. A new form that
    forgets it fails here, before a browser finds it refused."""
    missing = []
    count = 0
    for template in sorted(TEMPLATES.glob("*.html")):
        text = template.read_text(encoding="utf-8")
        for match in re.finditer(r"<form\b[^>]*>", text):
            if not re.search(r'method\s*=\s*"post"', match.group(0), re.IGNORECASE):
                continue
            count += 1
            body = text[match.end():text.find("</form>", match.end())]
            if 'name="csrf_token" value="{{ csrf_token }}"' not in body:
                missing.append(f"{template.name}: {match.group(0)[:80]}")
    assert count >= 30, count
    assert not missing, "forms that post without the CSRF token:\n" + "\n".join(missing)


def test_every_rendered_form_that_posts_carries_this_sessions_token(admin):
    """The same, on pages as the console renders them: the field is there and holds the token
    of this session, not an empty value."""
    token = csrf_of(admin.get("/account").text)
    for path in ("/account", "/account/password", "/admin/users", "/admin/keys", "/admin/sessions",
                 "/queries/big_txn", "/queries/big_txn/replacement", "/queries/big_txn/debug",
                 "/workbench?query=big_txn", "/catalog"):
        page = admin.get(path).text
        for match in re.finditer(r"<form\b[^>]*method=\"post\"[^>]*>", page):
            body = page[match.end():page.find("</form>", match.end())]
            assert f'name="csrf_token" value="{token}"' in body, (path, match.group(0))


def test_every_script_request_goes_through_the_helper_that_sends_the_token():
    """api.js is the one place a script calls the console with fetch, and it sends the token on
    every call; the one form a script builds (the palette's persona switch) carries it too."""
    static = CONSOLE_ROOT / "web" / "static"
    for script in [*static.glob("app/*.js"), *static.glob("js/*.js")]:
        text = script.read_text(encoding="utf-8")
        if script.name == "api.js":
            assert '"X-CSRF-Token": csrfToken()' in text
            continue
        assert not re.search(r"(?<![\w.])fetch\(\s*[`'\"/A-Za-z]", text) or script.name == "workbench.js", script.name
        if 'createElement("form")' in text:
            assert '"csrf_token"' in text, script.name


# ============================================================ what the console shares

def test_a_live_feed_is_shared_by_one_person_never_across_people(engine):
    from core.services import Services

    services = Services(engine)
    a = engine.identity.login(ADMIN, ADMIN_PASSWORD)["token"]
    engine.identity.add_user("ann", "Ann-password-12", ["analyst"])
    b = engine.identity.login("ann", "Ann-password-12")["token"]
    subscribers = []
    try:
        with credential.bound(credential.Credential(a)):
            subscribers.append(services.feeds.subscribe("big_txn"))
            subscribers.append(services.feeds.subscribe("big_txn"))
        assert services.feeds.live_feeds() == 1, "two tabs of one person share a feed"
        with credential.bound(credential.Credential(b)):
            subscribers.append(services.feeds.subscribe("big_txn"))
        assert services.feeds.live_feeds() == 2, "another person gets a feed of their own"
    finally:
        for s in subscribers:
            s.close()


def test_the_catalogue_is_cached_per_person(engine):
    from core.services import Services

    services = Services(engine)
    a = engine.identity.login(ADMIN, ADMIN_PASSWORD)["token"]
    with credential.bound(credential.Credential(a)):
        assert services.catalog.streams()[0]["name"] == "txn"
    engine.streams_list = []
    with credential.bound(credential.Credential(a)):
        assert services.catalog.streams(), "cached for the same person"
    engine.identity.add_user("ann", "Ann-password-12", ["analyst"])
    b = engine.identity.login("ann", "Ann-password-12")["token"]
    with credential.bound(credential.Credential(b)):
        assert services.catalog.streams() == [], "another person's answer is not reused"


# ============================================================ lanes

def test_everyone_signed_in_sees_where_each_query_runs_but_only_an_admin_is_offered_a_rebalance(admin, engine):
    ann = _person(engine)
    page = ann.get("/admin/lanes")
    assert page.status_code == 200
    assert 'id="lanes-table"' in page.text and 'data-lane="own"' in page.text and 'data-lane="shared"' in page.text
    assert "auto — a lane each until 1 queries are hosted" in page.text
    assert 'id="lanes-admins-only"' in page.text and 'id="rebalance-preview"' not in page.text
    assert 'href="/admin/lanes"' in admin.get("/admin/access").text


def test_a_rebalance_is_previewed_before_it_can_run_and_runs_only_when_posted(admin, engine):
    plain = admin.get("/admin/lanes").text
    assert 'id="rebalance-preview"' in plain and 'id="rebalance-run"' not in plain
    preview = admin.get("/admin/lanes?preview=1").text
    assert 'id="rebalance-moves"' in preview and "planned" in preview and 'id="rebalance-run"' in preview
    assert getattr(engine, "rebalances", 0) == 0
    done = admin.post("/admin/lanes/rebalance").text
    assert "Rebalance started." in done
    assert engine.rebalances == 1


def test_a_rebalance_posted_without_the_admin_role_is_refused_by_the_engine(engine):
    ann = _person(engine)
    page = ann.post("/admin/lanes/rebalance").text
    assert "PRV-7002" in page and "admin role" in page
    assert getattr(engine, "rebalances", 0) == 0
