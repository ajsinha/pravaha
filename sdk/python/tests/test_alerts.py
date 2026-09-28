"""Alerts (ADR-057) from Python: ``EngineApi``'s calls and the ``pravaha alerts`` / ``alert`` commands.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Against the recording HTTP server of ``test_cli``: what is pinned is the endpoint, the body, what
prints, the statement ``alert create`` writes, and that ``alert drop`` does nothing without
``--yes``. What each endpoint decides is pinned in ``pravaha-registry``'s and ``pravaha-server``'s
own tests.
"""

from __future__ import annotations

import json

import pytest

from pravaha.api import EngineApi
from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_USAGE
from pravaha.cli._alerts import create_sql
from pravaha.cli._common import UsageError
from test_cli import _Engine, answer, engine, home, last, run  # noqa: F401 -- the recording server

LOW = {
    "name": "low_stock_alert",
    "view": "low_stock",
    "state": "ACTIVE",
    "following": "FOLLOWING",
    "condition": "warehouse = 'LDN'",
    "channels": ["buyers"],
    "severity": "warning",
    "options": {"dedupe": "PT10M"},
    "firing": 2,
    "pending": 0,
    "owner": "bea",
    "deliveryError": None,
}

DETAIL = {
    "alert": LOW,
    "keys": [
        {"key": {"sku": "sku-300", "warehouse": "LDN"}, "state": "FIRING", "episode": 1, "notified": "FIRED",
         "since": "2026-06-01T09:10:00Z", "owed": None, "acknowledgedBy": None},
        {"key": {"sku": "sku-200", "warehouse": "LDN"}, "state": "CLEARED", "episode": 1, "notified": "CLEARED",
         "since": "2026-06-01T09:20:00Z", "owed": None, "acknowledgedBy": None},
    ],
    "notifications": [
        {"at": "2026-06-01T09:20:01Z", "kind": "CLEARED", "key": {"sku": "sku-200", "warehouse": "LDN"},
         "episode": 1, "outcome": "DELIVERED", "detail": "buyers: HTTP 200"},
    ],
}


# ---------------------------------------------------------------------------------- EngineApi


def test_alerts_are_listed_shown_and_changed_through_their_endpoints(engine):
    api = EngineApi(engine)
    answer("GET", "/api/v1/alerts", {"items": [LOW]})
    assert api.alerts() == [LOW]
    answer("GET", "/api/v1/alerts/low_stock_alert", DETAIL)
    assert api.alert("low_stock_alert")["keys"][0]["state"] == "FIRING"
    answer("GET", "/api/v1/alerts/channels", {"items": [{"name": "buyers", "plugin": "webhook"}]})
    assert api.alert_channels() == [{"name": "buyers", "plugin": "webhook"}]
    answer("POST", "/api/v1/alerts/low_stock_alert/pause", dict(LOW, state="PAUSED"))
    assert api.pause_alert("low_stock_alert")["state"] == "PAUSED"
    answer("POST", "/api/v1/alerts/low_stock_alert/resume", LOW)
    api.resume_alert("low_stock_alert")
    answer("POST", "/api/v1/alerts/low_stock_alert/snooze", dict(LOW, state="SNOOZED"))
    api.snooze_alert("low_stock_alert", "2h")
    assert last()["body"] == {"duration": "2h"}
    answer("POST", "/api/v1/alerts/low_stock_alert/ack", {"name": "low_stock_alert", "acknowledged": 1})
    assert api.ack_alert("low_stock_alert", "sku=sku-300, warehouse=LDN")["acknowledged"] == 1
    assert last()["body"] == {"key": "sku=sku-300, warehouse=LDN"}
    api.ack_alert("low_stock_alert")
    assert last()["body"] == {}


def test_a_name_is_one_path_segment(engine):
    answer("GET", "/api/v1/alerts/a%2Fb", DETAIL)
    EngineApi(engine).alert("a/b")
    assert last()["path"] == "/api/v1/alerts/a%2Fb"


# ---------------------------------------------------------------------------------- the CLI


def test_alerts_ls_and_show(engine, home):
    answer("GET", "/api/v1/alerts", {"items": [LOW]})
    code, out, _ = run("alerts", "ls", "--http", engine)
    assert code == EXIT_OK
    assert "low_stock_alert" in out and "low_stock" in out and "buyers" in out
    answer("GET", "/api/v1/alerts/low_stock_alert", DETAIL)
    code, out, _ = run("alerts", "show", "low_stock_alert", "--http", engine)
    assert code == EXIT_OK
    assert "sku=sku-300, warehouse=LDN" in out and "FIRING" in out and "CLEARED" in out
    assert "dedupe=PT10M" in out and "buyers: HTTP 200" in out
    code, out, _ = run("alerts", "show", "low_stock_alert", "--http", engine, "--json")
    assert json.loads(out) == DETAIL


def test_pause_resume_snooze_and_ack(engine, home):
    answer("POST", "/api/v1/alerts/low/pause", dict(LOW, state="PAUSED"))
    code, out, _ = run("alerts", "pause", "low", "--http", engine)
    assert code == EXIT_OK and "low paused" in out
    answer("POST", "/api/v1/alerts/low/resume", LOW)
    assert "resumed" in run("alerts", "resume", "low", "--http", engine)[1]
    answer("POST", "/api/v1/alerts/low/snooze", dict(LOW, snoozedUntil="2026-06-01T11:00:00Z"))
    code, out, _ = run("alerts", "snooze", "low", "2h", "--http", engine)
    assert "snoozed until 2026-06-01T11:00:00Z" in out and last()["body"] == {"duration": "2h"}
    assert run("alerts", "snooze", "low", "--http", engine)[0] == EXIT_USAGE
    answer("POST", "/api/v1/alerts/low/ack", {"acknowledged": 2})
    code, out, _ = run("alerts", "ack", "low", "--http", engine)
    assert "2 firing keys acknowledged" in out
    run("alerts", "ack", "low", "--key", "sku=sku-1, warehouse=LDN", "--http", engine)
    assert last()["body"] == {"key": "sku=sku-1, warehouse=LDN"}


def test_a_refusal_exits_one_with_its_code(engine, home):
    answer("POST", "/api/v1/alerts/low/pause", {"code": "PRV-7002", "message": "sam may not pause"}, 403)
    code, _, err = run("alerts", "pause", "low", "--http", engine)
    assert code == EXIT_REFUSED and "PRV-7002" in err


def test_alert_create_writes_the_statement():
    assert create_sql("low", "inventory.low_stock", ["buyers", "ops-hook"], "warehouse = 'LDN'",
                      {"severity": "critical", "dedupe": "10m", "include": "sku, on_hand"}) == (
        "CREATE ALERT low ON inventory.low_stock WHERE warehouse = 'LDN' NOTIFY buyers, \"ops-hook\" "
        "WITH (severity = 'critical', dedupe = '10m', include = (sku, on_hand))")
    assert create_sql("low", "low_stock", ["buyers"]) == "CREATE ALERT low ON low_stock NOTIFY buyers"
    with pytest.raises(UsageError):
        create_sql("low", "low_stock", [])


def test_alert_create_prints_the_statement_and_drop_asks_first(engine, home):
    code, out, _ = run("alert", "create", "low", "--on", "low_stock", "--notify", "buyers",
                       "--fire-after", "1m", "--print-sql", "--url", "grpc://127.0.0.1:1")
    assert code == EXIT_OK
    assert out.strip() == "CREATE ALERT low ON low_stock NOTIFY buyers WITH (fire_after = '1m')"
    code, out, _ = run("alert", "drop", "low", "--url", "grpc://127.0.0.1:1")
    assert code == EXIT_OK and "would drop the alert low" in out
    assert _Engine.calls == []
