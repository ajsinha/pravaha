"""The administrative screens' services: the audit trail and the console identity's permissions.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Both are the engine's answers, reached through the SDK, and neither is decided here. The
engine authorizes reading the audit trail with a permission of its own
(``SecurityPolicy.mayReadAudit``), and it decides for the identity the console connects as
(``engine.token``) -- one identity for every person signed in to this console. So a refusal is
the engine's, rendered as a state of the screen ("not permitted", with the engine's reason),
never a console rule layered on top: a console that hid the audit screen by its own role
would be enforcing nothing, and one that showed it regardless would be showing a 403.
"""
from __future__ import annotations

import re
from typing import Any

from core.engine import Engine
from core.services import ServiceError, _refusal

#: The filters the audit screen understands, in the order its form shows them. Every one is in
#: the URL, so a filtered page is a link somebody can paste into an incident channel.
AUDIT_FILTERS = ("since", "until", "principal", "view", "action", "decision")

#: A browser's datetime-local value: a minute or a second, and no zone. Read as UTC, and the
#: form says so, because an audit search whose window moved with the viewer's clock would
#: answer a different question for each person who opened the same link.
_LOCAL = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$")
_INSTANT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$")


def instant(text: str) -> str | None:
    """An ISO-8601 instant from what a person typed or a link carried, or None when blank."""
    value = (text or "").strip()
    if not value:
        return None
    if _LOCAL.match(value):
        return value + (":00" if value.count(":") == 1 else "") + "Z"
    if _INSTANT.match(value):
        return value
    raise ServiceError(f"'{value}' is not a time; use 2026-09-19T08:00 (UTC) or an ISO-8601 instant",
                       status=400, code="PRV-1051")


class AdminService:
    """The audit trail and the permissions page, from the engine."""

    PAGE_SIZE = 50

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def audit(self, filters: dict[str, Any], cursor: str | None = None, limit: int | None = None) -> dict:
        """One page of the audit trail, or why this console may not read it.

        ``permitted`` is False only when the engine refused *this identity* (403): that is a
        state of the screen, not an error. Anything else the engine said -- a malformed filter,
        an engine that did not answer -- is raised, with its code and status.
        """
        form = {name: str(filters.get(name) or "").strip() for name in AUDIT_FILTERS}
        decision = form["decision"].lower()
        if decision not in {"", "allow", "deny"}:
            raise ServiceError(f"'{form['decision']}' is not a decision; choose allow or deny",
                               status=400, code="PRV-1051")
        query = {
            "since": instant(form["since"]),
            "until": instant(form["until"]),
            "principal": form["principal"] or None,
            "view": form["view"] or None,
            "action": form["action"] or None,
            "decision": decision or None,
            "limit": max(1, min(500, int(limit or self.PAGE_SIZE))),
            "cursor": (cursor or "").strip() or None,
        }
        try:
            page = self._engine.audit(**query)
        except Exception as exc:  # sorted into a state below
            status = getattr(exc, "status", None)
            if status == 403:
                return {"permitted": False, "reason": str(exc), "code": getattr(exc, "code", None),
                        "filters": form, "page": None}
            raise _refusal(exc) from exc
        return {"permitted": True, "reason": None, "code": None, "filters": form,
                "cursor": query["cursor"], "page": page}

    def permissions(self) -> dict:
        """What the engine's policy lets the console's identity do."""
        try:
            return self._engine.permissions()
        except Exception as exc:
            raise _refusal(exc) from exc

    def affordances(self) -> Affordances:
        """The same answers, as the screens that offer an action need them (design 23.16).

        Never raises: an engine that did not answer, or one too old to have the endpoint, is
        an unknown, and an unknown keeps the control -- the engine re-checks every action
        anyway, so the worst case is the refusal it always gave. Only a policy that said *no*
        takes a control away.
        """
        try:
            return Affordances(self._engine.permissions())
        except Exception:  # noqa: BLE001 -- an unknown keeps the control; see above
            return Affordances(None)


class Affordances:
    """Which actions the engine's policy refuses this console's identity, and why.

    "RBAC drives affordances" (design 23.16): an action the policy refuses is disabled with
    the policy's reason beside it, never a button that fails on click (the *unauthorized*
    state of 23.12). The answer is the engine's -- ``GET /api/v1/me/permissions`` -- so a
    grant made in the deployment's identity system shows here on the next page load, and the
    console adds no rule of its own.
    """

    def __init__(self, permissions: dict | None) -> None:
        self._permissions = permissions or {}
        self._views = {str(v.get("name")): v for v in self._permissions.get("views") or []
                       if isinstance(v, dict)}

    @staticmethod
    def _refusal(decision: Any) -> str | None:
        if isinstance(decision, dict) and decision.get("allowed") is False:
            return str(decision.get("reason") or "the engine's policy refuses it")
        return None

    def register_refused(self) -> str | None:
        """Why this identity may not register a query, or None when it may (or nobody said)."""
        return self._refusal(self._permissions.get("register"))

    def administer_refused(self, name: str) -> str | None:
        """Why this identity may not pause, resume or drop ``name``, or None."""
        view = self._views.get(name)
        return self._refusal(view.get("administer")) if view else None
