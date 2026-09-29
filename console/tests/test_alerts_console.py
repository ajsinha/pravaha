"""The alert screens (ADR-057): the list with firing and cleared chips, one alert with every key's
state and its notification history, and pause, resume, snooze and acknowledge -- each a form that
carries the session's CSRF token and asks the engine as the signed-in person.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Driven through the real console against the fake engine's alerts (``fake_alerts``), which keeps the
engine's contract: the engine decides who sees and changes what, and the console renders its answers
and its refusals -- with their codes -- and decides nothing itself.
"""
from __future__ import annotations

import pytest

pytest.importorskip("fastapi.testclient")

from fake_engine import FakeEngine
from fake_identity import csrf_of, sign_in
from test_identity import _app, _person


@pytest.fixture
def engine():
    return FakeEngine()


@pytest.fixture
def admin(engine):
    client = _app(engine)
    sign_in(client)
    return client


def test_the_list_shows_each_alert_with_its_firing_chip_and_a_nav_entry(admin):
    page = admin.get("/alerts").text
    # Alerts is an item in the Operate panel of the mega menu (not a top-level entry), current here.
    assert 'href="/alerts" aria-current="page"' in page
    assert 'data-alert="low_stock_alert"' in page
    assert 'data-chip="firing"' in page and "1 firing" in page
    assert "warehouse = &#39;LDN&#39;" in page or "warehouse = 'LDN'" in page
    assert "buyers · webhook" in page
    assert any(i["href"] == "/alerts" for i in admin.get("/api/v1/palette").json()["items"])


def test_an_alert_with_nothing_firing_shows_clear(admin, engine):
    engine.alerting.alerts["low_stock_alert"]["firing"] = 0
    page = admin.get("/alerts").text
    assert 'data-chip="clear"' in page and 'data-chip="firing"' not in page


def test_one_alert_shows_every_key_and_its_notification_history(admin):
    page = admin.get("/alerts/low_stock_alert").text
    assert 'id="alert-keys"' in page
    assert 'data-key="sku=sku-300, warehouse=LDN"' in page and 'data-state="FIRING"' in page
    assert 'data-state="CLEARED"' in page
    assert 'id="alert-history"' in page and "buyers: HTTP 200" in page
    for form in ("pause-form", "snooze-form", "ack-form"):
        section = page.split(f'id="{form}"', 1)[1].split("</form>", 1)[0]
        assert 'name="csrf_token"' in section, form


def test_pause_resume_snooze_and_ack_go_through_the_engine(admin, engine):
    done = admin.post("/alerts/low_stock_alert/pause").text
    assert "low_stock_alert is paused" in done and engine.alerting.alerts["low_stock_alert"]["state"] == "PAUSED"
    assert 'id="resume-form"' in done
    done = admin.post("/alerts/low_stock_alert/resume").text
    assert "is resumed" in done and engine.alerting.alerts["low_stock_alert"]["state"] == "ACTIVE"
    done = admin.post("/alerts/low_stock_alert/snooze", data={"duration": "2h"}).text
    assert "snoozed for 2h" in done and engine.alerting.calls[-1] == ("snooze", "low_stock_alert", {"duration": "2h"})
    refused = admin.post("/alerts/low_stock_alert/snooze", data={"duration": "soon"}).text
    assert "PRV-8042" in refused
    done = admin.post("/alerts/low_stock_alert/ack", data={"key": "sku=sku-300, warehouse=LDN"}).text
    assert "1 firing keys acknowledged" in done
    assert engine.alerting.keys["low_stock_alert"][0]["acknowledgedBy"] == "admin"


def test_the_engines_refusal_is_shown_with_its_code(admin, engine):
    ann = _person(engine)
    page = ann.post("/alerts/low_stock_alert/pause").text
    assert "PRV-7002" in page and engine.alerting.alerts["low_stock_alert"]["state"] == "ACTIVE"
    missing = admin.get("/alerts/nothing")
    assert missing.status_code == 404 and "PRV-8040" in missing.text


def test_a_form_without_the_token_is_refused(engine):
    client = _app(engine)
    sign_in(client, keep_token=False)
    assert csrf_of(client.get("/alerts/low_stock_alert").text)
    refused = client.post("/alerts/low_stock_alert/pause")
    assert refused.status_code == 403 and engine.alerting.alerts["low_stock_alert"]["state"] == "ACTIVE"


def test_a_node_serving_no_alerts_says_so(admin, engine):
    engine.alerting.on = False
    page = admin.get("/alerts")
    assert page.status_code == 409 and "This engine serves no alerts" in page.text and "PRV-8047" in page.text


def test_signed_out_is_sent_to_sign_in(engine):
    answer = _app(engine).get("/alerts", follow_redirects=False)
    assert answer.status_code in (302, 303) and "/login" in answer.headers["location"]
