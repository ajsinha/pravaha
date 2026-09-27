"""The administrative screens' services: the audit trail, the console identity's permissions,
and each tenant's use against its quotas.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Both are the engine's answers, reached through the SDK, and neither is decided here. The
engine authorizes reading the audit trail with a permission of its own
(``SecurityPolicy.mayReadAudit``), and it decides for the person signed in, whose own engine
session every call carries (ADR-052). So a refusal is the engine's, rendered as a state of the screen ("not permitted", with the engine's reason),
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


def _limit(value: Any) -> int | None:
    """One limit as the engine sent it: None (no limit) stays None, and a number is a number,
    zero included -- zero is a limit that admits nothing (ADR-050 section 2)."""
    if value is None:
        return None
    return int(value)


def _limits(raw: Any) -> dict:
    raw = raw if isinstance(raw, dict) else {}
    return {"maxQueries": _limit(raw.get("maxQueries")), "maxStateKeys": _limit(raw.get("maxStateKeys"))}


#: The share of a limit from which the tenants screen warns that the limit is close.
NEAR = 0.8


def usage(used: int, limit: int | None) -> dict:
    """What ``used`` against ``limit`` means for the tenant's next registration.

    ``state`` is ``unlimited`` (no limit is set), ``ok``, ``near`` (at or past 80 per cent),
    ``full`` (at the limit: the next registration that needs room is refused) or ``over``
    (past it, which only state can be: a quota is checked at admission, and admitted
    queries keep growing). ``percent`` is capped at 100 for drawing; ``used`` is not.
    """
    if limit is None:
        return {"used": used, "limit": None, "state": "unlimited", "percent": None}
    if used > limit:
        state = "over"
    elif used >= limit:
        state = "full"
    elif used >= NEAR * limit:
        state = "near"
    else:
        state = "ok"
    percent = 100 if limit == 0 else min(100, round(100 * used / limit))
    return {"used": used, "limit": limit, "state": state, "percent": percent}


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

    def tenants(self) -> dict:
        """Each tenant's use against its quotas and its refusals, as the tenants screen draws it.

        The engine's page (``scope``, ``defaults``, ``tenants``) with one :func:`usage` per
        quota added to each tenant. A limit the engine sends as ``null`` is **no limit**, and
        stays None here: it is never read as zero, which would be a tenant that may hold
        nothing. Anything the engine refused is raised, with its code and status.
        """
        try:
            page = self._engine.tenants()
        except Exception as exc:
            raise _refusal(exc) from exc
        defaults = _limits(page.get("defaults"))
        tenants = []
        for row in page.get("tenants") or []:
            if not isinstance(row, dict):
                continue
            limits = _limits(row.get("limits"))
            tenants.append({
                "tenant": str(row.get("tenant") or ""),
                "queries": int(row.get("queries") or 0),
                "computations": int(row.get("computations") or 0),
                "stateKeys": int(row.get("stateKeys") or 0),
                "limits": limits,
                "queryRefusals": int(row.get("queryRefusals") or 0),
                "stateRefusals": int(row.get("stateRefusals") or 0),
                "queryUse": usage(int(row.get("queries") or 0), limits["maxQueries"]),
                "stateUse": usage(int(row.get("stateKeys") or 0), limits["maxStateKeys"]),
            })
        tenants.sort(key=lambda t: t["tenant"])
        return {"scope": "all" if page.get("scope") == "all" else "own",
                "defaults": defaults, "tenants": tenants,
                "refusals": sum(t["queryRefusals"] + t["stateRefusals"] for t in tenants)}

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
