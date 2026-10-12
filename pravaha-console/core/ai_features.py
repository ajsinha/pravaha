"""
Pravaha console — the plain-English features' service (docs/guides/AI.md), in drishti's design.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Every feature is the engine's: ask your data, a draft, an alert draft, explain (``sql``, ``refusal``,
``change``), the change narrative and Ask about this page, each answered by ``/api/v1/ai/*`` **as the
signed-in person** through the console's one engine adapter (their session; the engine's policy, masks,
limits and audit). The console holds no model key and calls no model itself.

What the console adds is what drishti's screens add: whether a control is drawn at all (``can``, from
``GET /api/v1/ai/features``, asked once per request), one shape for a refusal with its code and its help,
and the confirm step. **A draft is held here**, server-side, between the answer and its registration or
creation, so what is registered is exactly what the engine judged -- the browser sends the draft's id and
a confirmation, never its SQL (as the assistant's drafts were, ADR-058). Registration goes through the
Workbench's own path (``QueryService.register``, keys by name mapped against the engine's schema) and an
alert's creation through the statement the Workbench would run.

No HTTP and no HTML in this module, like ``core.services``.
"""
from __future__ import annotations

import re
import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any

from core.engine import EngineHttpError
from core.services import ServiceError

#: The features, as the engine's policy names them.
FEATURES = ("ask", "draft", "alert", "explain", "narrative", "page")
#: The help that explains a refusal of these features, beside the code's own page.
REFUSAL_HELP = {
    "PRV-1080": "/help/topics/ai-features#turn-it-on",
    "PRV-1084": "/help/topics/ai-features#who-may",
    "PRV-1085": "/help/topics/ai-features#who-may",
    "PRV-1086": "/help/topics/ai-features",
    "PRV-1087": "/help/topics/ai-features",
    "PRV-1088": "/help/topics/ask-your-data#when-it-cannot-answer",
    "PRV-7002": "/help/topics/ai-features#who-may",
}
#: The most turns of a conversation the engine is sent (it keeps the last three itself).
TURNS = 3
#: How long, and how many, drafts are held for their confirmation.
DRAFT_SECONDS = 3600
DRAFT_LIMIT = 500
_CHOSEN = re.compile(r"\(the sql it chose: (.*)\)\s*$", re.S)


@dataclass
class Failure(Exception):
    """A feature's refusal, in one shape: the engine's code, its words, the HTTP status and the help."""
    code: str
    message: str
    status: int = 400
    sql: str = ""

    def __post_init__(self) -> None:
        super().__init__(self.message)

    @property
    def help(self) -> str:
        return REFUSAL_HELP.get(self.code, "")

    @property
    def off(self) -> bool:
        return self.code == "PRV-1080"

    def to_dict(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message, "status": self.status, "help": self.help,
                "sql": self.sql}


@dataclass
class Draft:
    id: str
    user: str
    kind: str                       # "query" | "alert"
    answer: dict[str, Any]
    at: float = field(default_factory=time.monotonic)


def _failure(exc: Exception) -> Failure:
    if isinstance(exc, Failure):
        return exc
    status = int(getattr(exc, "status", 0) or 0)
    code = str(getattr(exc, "code", "") or "")
    message = str(exc)
    chosen = _CHOSEN.search(message)
    return Failure(code, message, status or 502, chosen.group(1).strip() if chosen else "")


class AiFeatures:
    """The console's half of the plain-English features."""

    def __init__(self, engine: Any, services: Any) -> None:
        self._engine = engine
        self._services = services
        self._drafts: dict[str, Draft] = {}
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ which controls are drawn
    def features(self) -> dict[str, Any]:
        """``{enabled, values, features}`` for this person; off when the engine cannot say."""
        try:
            answer = dict(self._engine.ai_features() or {})
        except Exception:  # noqa: BLE001 -- an engine that cannot say is a layer that is off here
            return {"enabled": False, "values": "labels-only", "features": {f: False for f in FEATURES}}
        flags = dict(answer.get("features") or {})
        on = bool(answer.get("enabled"))
        return {"enabled": on, "values": str(answer.get("values") or "labels-only"),
                "features": {f: on and bool(flags.get(f)) for f in FEATURES}}

    # ------------------------------------------------------------------ the features
    def _call(self, fn, *args, **kwargs) -> dict[str, Any]:
        try:
            return dict(fn(*args, **kwargs) or {})
        except EngineHttpError as exc:
            raise _failure(exc) from exc
        except ServiceError as exc:
            raise _failure(exc) from exc

    @staticmethod
    def _text(value: Any, what: str, limit: int = 600) -> str:
        text = str(value or "").strip()
        if not text:
            raise Failure("PRV-1051", f"{what} is missing", 400)
        return text[:limit * 2]

    def ask(self, question: str, conversation: Any = None, view: str = "") -> dict[str, Any]:
        """Ask your data. ``view`` scopes the question to one view (a view's page), by saying so."""
        asked = self._text(question, "a question")
        sent = f"About the view {view}: {asked}" if view else asked
        turns = [{"question": str(t.get("question") or "")[:600], "sql": str(t.get("sql") or "")[:4000]}
                 for t in (conversation if isinstance(conversation, list) else []) if isinstance(t, dict)]
        answer = self._call(self._engine.ai_ask, sent, turns[-TURNS:] or None)
        answer["asked"] = asked
        answer["conversation"] = (turns + [{"question": sent, "sql": answer.get("sql", "")}])[-TURNS:]
        return answer

    def draft(self, user: str, description: str) -> dict[str, Any]:
        answer = self._call(self._engine.ai_draft, self._text(description, "a description"))
        answer["draft_id"] = self._hold(user, "query", answer)
        return answer

    def alert_draft(self, user: str, description: str) -> dict[str, Any]:
        answer = self._call(self._engine.ai_alert_draft, self._text(description, "a description"))
        answer["draft_id"] = self._hold(user, "alert", answer)
        return answer

    def explain(self, body: dict[str, Any]) -> dict[str, Any]:
        kind = str(body.get("kind") or "")
        if kind not in ("sql", "refusal", "change"):
            raise Failure("PRV-1051", f"unknown kind '{kind}': sql, refusal or change", 400)
        sent = {"kind": kind}
        for key in ("sql", "query", "code", "message", "view"):
            if body.get(key):
                sent[key] = str(body[key])[:8000]
        return self._call(self._engine.ai_explain, sent)

    def narrate(self, view: str, changes: Any) -> dict[str, Any]:
        rows = [{"weight": int(c.get("weight") or 0), "values": list(c.get("values") or [])}
                for c in (changes if isinstance(changes, list) else [])[:500] if isinstance(c, dict)]
        return self._call(self._engine.ai_narrate, self._text(view, "a view", 200), rows)

    def page(self, screen: str, about: str, question: str, topics: list[dict[str, str]]) -> dict[str, Any]:
        return self._call(self._engine.ai_page, self._text(screen, "a screen", 100), about[:4000],
                          self._text(question, "a question"), topics[:4])

    # ------------------------------------------------------------------ drafts, held for confirmation
    def _hold(self, user: str, kind: str, answer: dict[str, Any]) -> str:
        draft_id = secrets.token_urlsafe(12)
        now = time.monotonic()
        with self._lock:
            for key in [k for k, d in self._drafts.items() if now - d.at > DRAFT_SECONDS]:
                del self._drafts[key]
            while len(self._drafts) >= DRAFT_LIMIT:
                del self._drafts[next(iter(self._drafts))]
            self._drafts[draft_id] = Draft(draft_id, user, kind, dict(answer))
        return draft_id

    def held(self, user: str, draft_id: str, kind: str) -> dict[str, Any]:
        """A draft this person was given and has not used: what is registered is what was judged."""
        with self._lock:
            draft = self._drafts.get(str(draft_id or ""))
        if draft is None or draft.user != user or draft.kind != kind \
                or time.monotonic() - draft.at > DRAFT_SECONDS:
            raise Failure("", "that draft is no longer held: draft it again", 404)
        return draft.answer

    def _release(self, draft_id: str) -> None:
        with self._lock:
            self._drafts.pop(draft_id, None)

    def register(self, user: str, draft_id: str, confirmed: bool) -> dict[str, Any]:
        """Registers a held draft -- only once the person has confirmed -- through the Workbench's path."""
        draft = self.held(user, draft_id, "query")
        if not confirmed:
            raise Failure("", "confirm first: nothing is registered until you do", 400)
        sql, keys, options = draft["sql"], list(draft.get("keys") or []), dict(draft.get("options") or {})
        try:
            ordinals, _fields = self._services.authoring.key_ordinals(sql, keys)
            self._services.queries.register(draft["name"], sql, ordinals, sink=options.get("sink") or None,
                                            retention=options.get("retention") or None)
        except (ServiceError, EngineHttpError) as exc:
            raise _failure(exc) from exc
        self._release(draft_id)
        return {"name": draft["name"], "href": f"/queries/{draft['name']}"}

    def create_alert(self, user: str, draft_id: str, confirmed: bool) -> dict[str, Any]:
        """Creates a held alert draft -- only once confirmed -- by running its statement as the Workbench would."""
        draft = self.held(user, draft_id, "alert")
        if not confirmed:
            raise Failure("", "confirm first: nothing is created until you do", 400)
        try:
            self._services.adhoc.run(draft["statement"])
        except (ServiceError, EngineHttpError) as exc:
            raise _failure(exc) from exc
        self._release(draft_id)
        return {"name": draft["name"], "href": f"/alerts/{draft['name']}"}


__all__ = ["AiFeatures", "FEATURES", "Failure", "REFUSAL_HELP"]
