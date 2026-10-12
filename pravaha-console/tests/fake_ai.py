"""The engine's plain-English features (docs/guides/AI.md), answered from memory for the fake engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The engine's ``mock`` provider is regular expressions playing the model; this is the same idea one step
up -- regular expressions playing the engine's features -- so the console's screens are tested end to
end with no node, no model and no key. The contract kept is the engine's (pravaha-server's
``AiFeaturesHttpTest`` pins the real one): off by default, and then every feature answers ``409
PRV-1080``; ``GET /ai/features`` answers either way; a feature the policy closes is ``403 PRV-1084``; a
question nothing matches is ``422 PRV-1088``; a view the person may not read is ``403 PRV-7002`` quoting
the SQL the model chose. Nothing here registers or creates anything: that stays the console's next call.
"""
from __future__ import annotations

import re

from core.engine import EngineHttpError

FEATURES = ("ask", "draft", "alert", "explain", "narrative", "page")

ASK_RULES = [
    (re.compile(r"secret", re.I), None),
    (re.compile(r"total|spend|amount", re.I), {
        "sql": "SELECT user_id, SUM(amount) AS total FROM big_txn GROUP BY user_id",
        "explanation": "Sums each user's amounts in big_txn.",
        "columns": [{"name": "user_id", "type": "VARCHAR"}, {"name": "total", "type": "BIGINT"}],
        "rows": [["u1", 150], ["u2", 900]], "chart": {"kind": "bar", "x": "user_id", "y": "total", "series": None}}),
    (re.compile(r"how many|count", re.I), {
        "sql": "SELECT COUNT(*) AS payments FROM big_txn",
        "explanation": "Counts the rows of big_txn.",
        "columns": [{"name": "payments", "type": "BIGINT"}], "rows": [[2]],
        "chart": {"kind": "stat", "x": None, "y": "payments", "series": None}}),
]


class FakeAi:
    def __init__(self, engine) -> None:
        self.engine = engine
        self.on = False
        self.values = "labels-only"
        #: Features the policy closes to everyone here (PRV-1084).
        self.denied: set[str] = set()
        self.calls: list[tuple[str, dict]] = []
        self.tests: list[str | None] = []

    # ------------------------------------------------------------------ the gate
    def features(self) -> dict:
        return {"enabled": self.on, "values": self.values,
                "features": {f: self.on and f not in self.denied for f in FEATURES}}

    def _gate(self, feature: str, body: dict) -> None:
        self.calls.append((feature, dict(body)))
        if not self.on:
            raise EngineHttpError(409, "the AI layer is switched off (pravaha.ai.enabled)", "PRV-1080")
        if feature in self.denied:
            raise EngineHttpError(403, f"feature '{feature}' is not open to your roles", "PRV-1084")

    # ------------------------------------------------------------------ the features
    def ask(self, question: str, conversation: list | None = None) -> dict:
        self._gate("ask", {"question": question, "conversation": conversation})
        for pattern, reply in ASK_RULES:
            if pattern.search(question):
                if reply is None:
                    raise EngineHttpError(403, "the SQL the model chose was refused: you may not read secret_view "
                                          "(the sql it chose: SELECT * FROM secret_view)", "PRV-7002")
                sql = reply["sql"]
                if conversation and re.search(r"only|just", question, re.I):
                    sql = sql.replace(" GROUP BY", " WHERE user_id = 'u2' GROUP BY")
                rows = reply["rows"] if sql == reply["sql"] else [reply["rows"][-1]]
                return {"question": question, "sql": sql, "explanation": reply["explanation"],
                        "columns": reply["columns"], "rows": rows, "rowCount": len(rows), "truncated": False,
                        "chart": reply["chart"], "values": self.values,
                        "summary": "u2 spent the most." if self.values == "aggregates" else None,
                        "turns": len(conversation or []) + 1}
        raise EngineHttpError(422, "the model's answer could not be used: the views you may read cannot "
                              "answer that", "PRV-1088")

    def draft(self, description: str, name: str | None = None) -> dict:
        self._gate("draft", {"description": description})
        if not re.search(r"payment|transaction|txn", description, re.I):
            raise EngineHttpError(422, "the model's answer could not be used: no query it wrote planned", "PRV-1088")
        sql = "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000"
        return {"description": description, "name": name or "large_payments", "sql": sql, "keys": ["txn_id"],
                "options": {}, "statement": "CREATE CONTINUOUS QUERY large_payments\n  KEYED BY (txn_id)\nAS\n" + sql,
                "explanation": "Every payment over 1000, one row per payment.", "turns": 1,
                "dryRun": {"fingerprint": "3f9a2c71d0be4e55", "plan": "Filter(amount > 1000)\n  Scan(txn)",
                           "outputFields": [], "reads": ["txn"],
                           "lane": "the lane of big_txn's computation, which it would share: no new computation",
                           "sharesWith": ["big_txn"]},
                "registered": False}

    def alert_draft(self, description: str, name: str | None = None) -> dict:
        self._gate("alert", {"description": description})
        return {"description": description, "name": name or "big_spender", "view": "big_txn",
                "condition": "amount > 500", "channels": ["ops_log"],
                "statement": "CREATE ALERT big_spender ON big_txn\n  WHERE amount > 500\n  NOTIFY ops_log\n"
                             "  WITH (severity = 'critical')",
                "explanation": "Fires for each payment over 500.", "turns": 1, "created": False}

    def explain(self, body: dict) -> dict:
        self._gate("explain", body)
        kind = body.get("kind")
        common = {"kind": kind, "sql": body.get("sql"), "plan": None, "code": None, "valid": None,
                  "engineCodes": [], "change": None, "values": self.values}
        if kind == "sql":
            return {**common, "explanation": "It keeps every transaction over 1000, one row per transaction.",
                    "plan": "Filter(amount > 1000)\n  Scan(txn)"}
        if kind == "refusal":
            return {**common, "code": body.get("code"), "valid": False if body.get("sql") else None,
                    "engineCodes": [body.get("code")] if body.get("sql") else [],
                    "explanation": f"{body.get('code')} means the statement names something the engine cannot use."}
        return {**common, "explanation": "Nothing has been kept for this view yet.",
                "change": {"view": body.get("view"), "since": None, "commits": 0, "entered": 0, "left": 0,
                           "keyColumns": ["txn_id"], "enteredKeys": [], "leftKeys": []}}

    def narrate(self, view: str, changes: list) -> dict:
        self._gate("narrative", {"view": view, "changes": changes})
        entered = sum(1 for c in changes if (c.get("weight") or 0) > 0)
        left = sum(1 for c in changes if (c.get("weight") or 0) < 0)
        return {"view": view, "narrative": f"{entered} rows entered {view} and {left} left.", "entered": entered,
                "left": left, "values": self.values}

    def page(self, screen: str, about: str, question: str, topics: list | None = None) -> dict:
        self._gate("page", {"screen": screen, "about": about, "question": question, "topics": topics})
        return {"screen": screen, "answer": f"From this page: {about.splitlines()[0] if about else ''}",
                "sources": [t.get("title") for t in topics or []]}

    # ------------------------------------------------------------------ Admin -> AI
    def admin(self) -> dict:
        return {"enabled": self.on, "dir": "/opt/pravaha/conf/ai", "types": ["mock", "openai", "anthropic"],
                "defaultModel": "mock/m" if self.on else "",
                "providers": [{"name": "mock", "type": "mock", "enabled": True, "baseUrl": "", "keySet": False,
                               "health": {"status": "UP" if self.on else "OFF", "detail": "answered 3 calls"},
                               "retry": None,
                               "models": [{"name": "m", "provider": "mock", "id": "m", "kind": "chat",
                                           "capabilities": ["CHAT"]}]},
                              {"name": "vendor", "type": "openai", "enabled": True, "baseUrl": "https://llm.example",
                               "keySet": True, "health": {"status": "DOWN", "detail": "connection refused"},
                               "retry": {"attempts": 2, "nextRetry": "2026-06-01T09:05:00Z",
                                         "lastError": "connection refused", "permanent": False},
                               "models": []}] if self.on else [],
                "fileErrors": {"broken.yaml": "a literal api-key is refused: name an environment variable"} if self.on else {},
                "loadedAt": "2026-06-01T09:00:00Z"}

    def admin_test(self, model: str | None) -> dict:
        self.tests.append(model)
        if not self.on:
            raise EngineHttpError(409, "the AI layer is switched off (pravaha.ai.enabled)", "PRV-1080")
        return {"ok": True, "text": "ready", "model": model or "mock/m",
                "usage": {"promptTokens": 5, "completionTokens": 1, "cost": 0}, "millis": 7}
