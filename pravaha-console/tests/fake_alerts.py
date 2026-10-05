"""The engine's alerts (ADR-057), answered from memory for the fake engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Keeps the engine's contract where the console can see it: an alert is seen by those who may read it
(its tenant's people, here), changed by its owner or an administrator (MODIFY) and refused otherwise
with 403 ``PRV-7002``; one nobody may see is 404 ``PRV-8040``; a node with its alert service off
answers 409 ``PRV-8047``. The rules themselves are pinned in ``pravaha-registry``'s tests; this is a
stand-in, not a copy.
"""
from __future__ import annotations

import copy

from core.engine import EngineHttpError

LOW_STOCK = {
    "name": "low_stock_alert", "view": "low_stock", "tenant": "public", "owner": "admin", "state": "ACTIVE",
    "following": "FOLLOWING", "condition": "warehouse = 'LDN'", "channels": ["buyers"], "severity": "warning",
    "options": {"dedupe": "PT10M"}, "snoozedUntil": None, "firing": 1, "pending": 0, "keys": 2,
    "lastNotificationAt": "2026-06-01T09:20:01Z", "deliveryError": None, "problem": None,
    "createdAt": "2026-06-01T08:00:00Z", "updatedAt": "2026-06-01T08:00:00Z", "updatedBy": "admin",
}

KEYS = [
    {"key": {"sku": "sku-300", "warehouse": "LDN"}, "state": "FIRING", "episode": 1, "fired": 1,
     "since": "2026-06-01T09:10:00Z", "firingSince": "2026-06-01T09:10:00Z", "clearedAt": None,
     "notified": "FIRED", "lastNotifiedAt": "2026-06-01T09:10:01Z", "owed": None, "reminders": 0,
     "acknowledgedBy": None, "acknowledgedAt": None, "row": {"on_hand": 2, "reorder_point": 5}},
    {"key": {"sku": "sku-200", "warehouse": "LDN"}, "state": "CLEARED", "episode": 1, "fired": 1,
     "since": "2026-06-01T09:20:00Z", "firingSince": "2026-06-01T09:05:00Z", "clearedAt": "2026-06-01T09:20:00Z",
     "notified": "CLEARED", "lastNotifiedAt": "2026-06-01T09:20:01Z", "owed": None, "reminders": 0,
     "acknowledgedBy": None, "acknowledgedAt": None, "row": {}},
]

HISTORY = [
    {"at": "2026-06-01T09:20:01Z", "key": {"sku": "sku-200", "warehouse": "LDN"}, "kind": "CLEARED", "episode": 1,
     "idempotencyKey": "b1", "channels": ["buyers"], "outcome": "DELIVERED", "detail": "buyers: HTTP 200"},
    {"at": "2026-06-01T09:10:01Z", "key": {"sku": "sku-300", "warehouse": "LDN"}, "kind": "FIRED", "episode": 1,
     "idempotencyKey": "a1", "channels": ["buyers"], "outcome": "DELIVERED", "detail": "buyers: HTTP 200"},
]


class FakeAlerts:
    def __init__(self, engine) -> None:
        self._engine = engine
        self.on = True
        self.alerts: dict[str, dict] = {"low_stock_alert": copy.deepcopy(LOW_STOCK)}
        self.keys: dict[str, list[dict]] = {"low_stock_alert": copy.deepcopy(KEYS)}
        self.calls: list[tuple] = []

    def _me(self) -> dict:
        self._engine._check()
        if not self.on:
            raise EngineHttpError(409, "PRV-8047 this node serves no alerts (pravaha.alerts.enabled is false)",
                                  "PRV-8047")
        return self._engine._as_identity(lambda t: self._engine.identity.me(t))

    def _find(self, who: dict, name: str) -> dict:
        alert = self.alerts.get(name)
        if alert is None or (alert["tenant"] != (who.get("tenant") or "public") and "admin" not in who.get("roles", [])):
            raise EngineHttpError(404, f"PRV-8040 there is no alert '{name}' that you may see", "PRV-8040")
        return alert

    def _modify(self, who: dict, alert: dict, verb: str) -> None:
        if who["username"] != alert["owner"] and "admin" not in who.get("roles", []):
            raise EngineHttpError(403, f"PRV-7002 {who['username']} may not {verb} the alert '{alert['name']}': "
                                       "that needs MODIFY on it", "PRV-7002")

    # ------------------------------------------------------------------ the endpoints
    def list(self) -> list[dict]:
        who = self._me()
        return [dict(a) for a in self.alerts.values()
                if a["tenant"] == (who.get("tenant") or "public") or "admin" in who.get("roles", [])]

    def detail(self, name: str) -> dict:
        alert = self._find(self._me(), name)
        return {"alert": dict(alert), "keys": copy.deepcopy(self.keys.get(name, [])), "notifications": copy.deepcopy(HISTORY)}

    def channels(self) -> list[dict]:
        self._me()
        return [{"name": "buyers", "plugin": "webhook"}, {"name": "ops-log", "plugin": "log"}]

    def change(self, name: str, verb: str, **fields) -> dict:
        who = self._me()
        alert = self._find(who, name)
        self._modify(who, alert, verb)
        self.calls.append((verb, name, fields))
        if verb == "pause":
            alert["state"] = "PAUSED"
        elif verb == "resume":
            alert["state"], alert["snoozedUntil"] = "ACTIVE", None
        elif verb == "snooze":
            alert["state"], alert["snoozedUntil"] = "SNOOZED", "2026-06-01T11:00:00Z"
        elif verb == "ack":
            count = 0
            for key in self.keys.get(name, []):
                wanted = fields.get("key")
                text = ", ".join(f"{k}={v}" for k, v in key["key"].items())
                if key["state"] == "FIRING" and not key["acknowledgedBy"] and (not wanted or wanted == text):
                    key["acknowledgedBy"] = who["username"]
                    count += 1
            return {"name": name, "acknowledged": count}
        return dict(alert)
