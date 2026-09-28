"""A stand-in for the engine's HTTP API, for the assistant's drafting tests: it answers the
catalogue, permission, validate and explain calls from a small in-memory catalogue, by rule.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

It listens on 127.0.0.1 only. Its planner is deliberately crude -- the tests pin what the
assistant does with the engine's answers, not how the engine plans:

* ``validate`` refuses a relation it does not know (``PRV-2002``), a ``GROUP BY`` with no window
  over a stream (``PRV-2050``), and anything a test adds to ``refuse``; otherwise it answers the
  ``SELECT`` list's names as ``outputFields``;
* ``explain`` answers a "plan" that is the statement's words, so equal SQL has equal plans.
"""

from __future__ import annotations

import json
import re
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Optional


def _output_names(sql: str) -> "list[str]":
    match = re.search(r"\bSELECT\s+(?:STREAM\s+)?(.*?)\bFROM\b", sql, re.IGNORECASE | re.DOTALL)
    if not match:
        return []
    items, depth, current = [], 0, ""
    for char in match.group(1):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        if char == "," and depth == 0:
            items.append(current)
            current = ""
        else:
            current += char
    items.append(current)
    names = []
    for item in items:
        item = item.strip()
        alias = re.search(r"\bAS\s+([A-Za-z_][A-Za-z0-9_]*)\s*$", item, re.IGNORECASE)
        if alias:
            names.append(alias.group(1))
        else:
            names.append(re.split(r"[.\s]", item)[-1])
    return names


def stream(name: str, *columns: str, event_time: Optional[str] = None) -> dict[str, Any]:
    return {"name": name, "version": 1,
            "fields": [{"name": c.split(":")[0], "type": c.split(":")[1], "nullable": False,
                        "ordinal": i} for i, c in enumerate(columns)],
            "eventTime": event_time, "outOfOrderness": "PT5S" if event_time else None}


class FakeEngine:
    """The engine's HTTP API over an in-memory catalogue. Mutate its fields between calls."""

    def __init__(self) -> None:
        self.streams: list[dict[str, Any]] = []
        #: Stream names ``/me/permissions`` says this principal may read; ``None`` for all.
        self.readable: Optional[set[str]] = None
        #: ``None`` to answer ``/me/permissions`` with 404, as an older node would.
        self.permissions_status = 200
        self.may_register = True
        self.queries: list[dict[str, Any]] = []
        self.views: dict[str, dict[str, Any]] = {}
        self.hidden_views: set[str] = set()
        self.sinks: list[dict[str, Any]] = []
        #: ``sql -> (code, message)`` refusals, checked before the built-in rules.
        self.refuse: dict[str, tuple[str, str]] = {}
        self.rule: Optional[Callable[[str], Optional[tuple[str, str]]]] = None
        self.requests: list[tuple[str, str, Any]] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_: Any) -> None:
                pass

            def _send(self, status: int, body: Any) -> None:
                data = json.dumps(body).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _serve(self, method: str) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                parsed = urllib.parse.urlsplit(self.path)
                outer.requests.append((method, parsed.path, body))
                status, answer = outer.answer(method, parsed.path, body)
                self._send(status, answer)

            def do_GET(self) -> None:
                self._serve("GET")

            def do_POST(self) -> None:
                self._serve("POST")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def calls(self, path: str) -> "list[Any]":
        return [body for _, p, body in self.requests if p == path]

    # ------------------------------------------------------------------ answers

    def visible_streams(self) -> "list[dict[str, Any]]":
        return list(self.streams)

    def known(self) -> "set[str]":
        return {s["name"].lower() for s in self.streams} | {q["name"].lower() for q in self.queries}

    def validate(self, sql: str) -> dict[str, Any]:
        refusal = self.refuse.get(sql) or (self.rule(sql) if self.rule else None)
        if refusal is None:
            from pravaha.assist.drafting import relations

            unknown = [r for r in relations(sql) if r.lower() not in self.known()]
            if unknown:
                refusal = ("PRV-2002", f"Object '{unknown[0]}' not found")
            elif re.search(r"\bGROUP\s+BY\b", sql, re.I) and not re.search(
                    r"\b(TUMBLE|HOP)\b", sql, re.I):
                refusal = ("PRV-2050", "the query's state would grow without bound: group by a "
                                       "window of event time")
        if refusal:
            return {"valid": False, "diagnostics": [
                {"code": refusal[0], "message": refusal[1],
                 "helpUrl": f"https://docs.example/{refusal[0]}"}],
                "outputFields": [], "elapsedMicros": 10}
        fields = [{"name": n, "type": "STRING", "nullable": False, "ordinal": i}
                  for i, n in enumerate(_output_names(sql))]
        return {"valid": True, "diagnostics": [], "outputFields": fields, "elapsedMicros": 10}

    def answer(self, method: str, path: str, body: Any) -> "tuple[int, Any]":
        if method == "GET" and path == "/api/v1/me/permissions":
            if self.permissions_status != 200:
                return self.permissions_status, {"code": "PRV-4404", "message": "no such endpoint"}
            names = sorted(s["name"] for s in self.streams
                           if self.readable is None or s["name"] in self.readable)
            return 200, {
                "principal": "alice", "tenant": None, "roles": ["analyst"], "anonymous": False,
                "policy": "rbac",
                "register": {"allowed": self.may_register,
                             "reason": None if self.may_register else "role analyst may not"},
                "readAudit": {"allowed": False, "reason": "no"},
                "views": [{"name": q["name"], "read": "full",
                           "administer": {"allowed": False, "reason": "no"}}
                          for q in self.queries],
                "streams": [{"name": n, "read": "full",
                             "administer": {"allowed": False, "reason": "no"}} for n in names],
            }
        if method == "GET" and path == "/api/v1/streams":
            return 200, self.visible_streams()
        if method == "GET" and path == "/api/v1/queries":
            return 200, self.queries
        if method == "GET" and path.startswith("/api/v1/views/"):
            name = urllib.parse.unquote(path.rsplit("/", 1)[1])
            if name in self.hidden_views or name not in self.views:
                return 403, {"code": "PRV-4003", "message": "denied"}
            return 200, self.views[name]
        if method == "GET" and path == "/api/v1/sinks":
            return 200, self.sinks
        if method == "POST" and path == "/api/v1/queries/validate":
            return 200, self.validate(str(body.get("sql")))
        if method == "POST" and path == "/api/v1/queries/explain":
            sql = str(body.get("sql"))
            verdict = self.validate(sql)
            if not verdict["valid"]:
                d = verdict["diagnostics"][0]
                return 400, {"code": d["code"], "message": d["message"]}
            return 200, {"level": "physical", "plan": "Plan\n  " + " ".join(sql.split()),
                         "outputFields": verdict["outputFields"]}
        return 404, {"code": "PRV-4404", "message": f"nothing at {method} {path}"}


class FakeRegistered:
    def __init__(self, name: str, fingerprint: str, sink: Optional[str] = None) -> None:
        self.name = name
        self.state = "RUNNING"
        self.fingerprint = fingerprint
        self.sink = sink


class FakeResult:
    def __init__(self, rows: "list[dict[str, Any]]") -> None:
        self.rows = rows

    def to_list(self) -> "list[dict[str, Any]]":
        return list(self.rows)


class FakeClient:
    """Stands in for :class:`pravaha.client.Client`'s register, query and drop."""

    def __init__(self) -> None:
        self.registered: list[tuple[str, str, list[int], Optional[str], Optional[str]]] = []
        self.queries: list[str] = []
        self.dropped: list[str] = []
        #: ``sql -> fingerprint``; unknown SQL gets a fingerprint from its words.
        self.fingerprints: dict[str, str] = {}
        #: ``view name -> rows`` answered by ``SELECT * FROM name``.
        self.rows: dict[str, list[dict[str, Any]]] = {}

    def register(self, name: str, sql: str, key_columns: Any, sink: Optional[str] = None,
                 retention: Optional[str] = None) -> FakeRegistered:
        self.registered.append((name, sql, list(key_columns), sink, retention))
        fingerprint = self.fingerprints.get(sql) or format(abs(hash(" ".join(sql.split()))), "x")
        return FakeRegistered(name, fingerprint, sink)

    def query(self, sql: str) -> FakeResult:
        self.queries.append(sql)
        match = re.match(r"SELECT \* FROM (\w+)$", sql)
        if match:
            return FakeResult(self.rows.get(match.group(1), []))
        name = re.search(r"CREATE CONTINUOUS QUERY (\w+)", sql)
        return FakeResult([{"name": name.group(1) if name else "?", "state": "RUNNING",
                            "fingerprint": "f00d", "sink": None}])

    def drop(self, name: str) -> None:
        self.dropped.append(name)

    def close(self) -> None:
        pass
