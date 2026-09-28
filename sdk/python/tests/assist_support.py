"""Shared test support for the assistant: a local HTTP server that plays recorded provider
answers, and configurations built around the fake provider.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Nothing here reaches a real model API: every provider under test is pointed at 127.0.0.1.
"""

from __future__ import annotations

import json
import pathlib
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Optional

FIXTURES = json.loads(
    (pathlib.Path(__file__).parent / "fixtures" / "assist_http.json").read_text(encoding="utf-8")
)


class Recorded:
    """One request the server received."""

    def __init__(self, method: str, path: str, headers: dict[str, str], body: Any) -> None:
        self.method = method
        self.path = path
        self.headers = headers
        self.body = body


class PlaybackServer:
    """Answers each ``(method, path)`` with the fixture queued for it (the last one repeats),
    and records every request."""

    def __init__(self) -> None:
        self.requests: list[Recorded] = []
        self.answers: dict[tuple[str, str], list[dict[str, Any]]] = {}
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_: Any) -> None:
                pass

            def _serve(self, method: str) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                body = json.loads(raw) if raw else None
                outer.requests.append(
                    Recorded(method, self.path, {k.lower(): v for k, v in self.headers.items()}, body)
                )
                queue = outer.answers.get((method, self.path)) or [
                    {"status": 404, "body": {"error": f"nothing queued for {method} {self.path}"}}
                ]
                answer = queue.pop(0) if len(queue) > 1 else queue[0]
                data = json.dumps(answer.get("body")).encode("utf-8") \
                    if not isinstance(answer.get("body"), str) else answer["body"].encode("utf-8")
                self.send_response(int(answer.get("status", 200)))
                self.send_header("Content-Type", "application/json")
                for name, value in (answer.get("headers") or {}).items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

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

    def queue(self, method: str, path: str, *answers: dict[str, Any]) -> None:
        self.answers[(method, path)] = list(answers)

    def last(self) -> Recorded:
        return self.requests[-1]

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


def fake_config(
    replies_by_model: "dict[str, list[Any]]",
    *,
    chain: Optional[list[str]] = None,
    profile: str = "explain",
    budgets: Optional[dict[str, int]] = None,
    structured_output: str = "none",
) -> dict[str, Any]:
    """A configuration document with one fake provider and one model per entry."""
    models = [
        {"id": model_id, "provider": "fake", "model": f"fake-{model_id}",
         "options": {"replies": replies, "structured_output": structured_output}}
        for model_id, replies in replies_by_model.items()
    ]
    document: dict[str, Any] = {
        "default_profile": profile,
        "providers": [{"id": "fake", "type": "fake"}],
        "models": models,
        "profiles": {profile: chain or list(replies_by_model)},
    }
    if budgets:
        document["budgets"] = budgets
    return document


EXPLANATION = json.dumps({
    "summary": "It keeps a count of orders per customer for each minute.",
    "steps": ["Reads the orders stream.", "Groups rows into one-minute windows by event time."],
    "notes": ["A late order corrects a minute already reported."],
})

REFUSAL = json.dumps({
    "meaning": "The query's state would grow without bound.",
    "cause": "It groups by customer with no window, so every customer ever seen is kept.",
    "fix": "Group by a window of event time, or give the view a retention.",
    "rewrite": "SELECT customer, COUNT(*) AS n FROM orders "
               "GROUP BY customer, TUMBLE(ts, INTERVAL '1' MINUTE)",
})
