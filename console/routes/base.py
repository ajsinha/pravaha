"""
Pravaha console — shared route scaffolding.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Every route module needs the same four things: the services, the brand context
for a template, one place that turns an engine refusal into an HTTP status, and
one place that renders a page. Written once here rather than four times.

The error mapping is the important part. A refusal must explain itself, which
only holds if there is a single table to check rather than a try/except in each
module that drifts away from the others.
"""
from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.templating import Jinja2Templates

from core.services import ServiceError

logger = logging.getLogger(__name__)

#: Where the JSON API lives. One constant, because the browser modules build
#: their URLs from what the template tells them rather than from a string
#: repeated in eleven files.
API = "/api/v1"

#: Engine refusal to HTTP status.
#:
#: Keyed by the PRV code's family rather than by the exact code, because the
#: families are stable and the codes are not: a new code in the 2xxx range is
#: still a planner refusal and should still be a 400 without anybody having to
#: remember to add it here.
STATUS_BY_FAMILY = {
    1: 503,   # transport: the engine could not be reached
    2: 400,   # planner: the query was refused, and the message says why
    3: 400,   # runtime
    4: 400,   # state
    5: 400,   # plugin
    6: 400,   # flight
    7: 403,   # security
    8: 400,   # registry
    9: 503,   # cluster
}


def status_for(exc: ServiceError) -> int:
    """The HTTP status a refusal deserves.

    The service layer's own status wins when it set one deliberately; the code
    family decides otherwise. A refusal arriving as a 500 is the unmapped
    failure this table exists to prevent.
    """
    if exc.status and exc.status != 400:
        return exc.status
    if exc.code and exc.code.startswith("PRV-") and len(exc.code) > 4:
        try:
            return STATUS_BY_FAMILY.get(int(exc.code[4]), exc.status or 400)
        except ValueError:
            return exc.status or 400
    return exc.status or 400


def problem(exc: ServiceError) -> dict[str, Any]:
    """One error shape, whichever route raised it.

    A client that has to parse two error formats will handle one of them badly,
    which is the same reasoning that turned off Spring's problem-details in the
    engine's own HTTP surface.
    """
    body: dict[str, Any] = {"error": str(exc), "status": status_for(exc)}
    if exc.code:
        # The PRV code travels so a reader can be sent to the entry in
        # TROUBLESHOOTING instead of searching a long message for the useful part.
        body["code"] = exc.code
    return body


class Routes:
    """Base for every route module. Subclasses implement ``register``."""

    def __init__(self, app: FastAPI, ctx: dict[str, Any],
                 templates: Jinja2Templates | None = None):
        self.app, self.ctx, self.templates = app, ctx, templates
        self.api = API
        self.register()

    def register(self) -> None:                                # pragma: no cover
        raise NotImplementedError

    # ----------------------------------------------------------------- domain
    def guard(self, fn: Callable[[], Any]) -> Any:
        """Run a service call, mapping any refusal onto the taxonomy."""
        try:
            return fn()
        except ServiceError as exc:
            # A refusal is normal operation, not a fault -- but it is never
            # translated without a trace, or a console that swallows the engine's
            # reasoning becomes the reason nobody can see it.
            logger.warning("refused (%s): %s", exc.code or "-", exc)
            raise HTTPException(status_for(exc), problem(exc)) from exc

    def json_guard(self, fn: Callable[[], Any]) -> JSONResponse:
        """The same, for the API: a JSON body rather than an exception."""
        try:
            return JSONResponse(fn())
        except ServiceError as exc:
            logger.warning("refused (%s): %s", exc.code or "-", exc)
            return JSONResponse(problem(exc), status_code=status_for(exc))

    # ------------------------------------------------------------------ pages
    def brand(self, request: Request | None = None) -> dict[str, Any]:
        """What every template gets without asking for it."""
        c = self.ctx["config"]
        health = self.ctx["services"].health.health()
        return {
            "app_name": c.get("app.name", "Pravaha"),
            "tagline": c.get("app.tagline", ""),
            "slogan": c.get("app.slogan", ""),
            "version": c.get("app.version", ""),
            "principle": c.get("app.principle", ""),
            "api": API,
            # The engine's reachability is on EVERY page, not only the one that
            # failed. An operator who navigates away from the overview must not
            # lose the single fact that explains why the next screen is empty.
            "engine_url": health.url,
            "engine_up": health.reachable,
            "engine_error": health.error or "",
        }

    def page(self, request: Request, template: str, *, http_status: int = 200,
             **context):
        """Render a template. Every other keyword reaches the template.

        The HTTP code is spelled ``http_status`` and is keyword-only on purpose.
        Called ``status``, it would collide with the most natural name a page has
        for a query's status -- and the caller would get a silently empty
        variable in the template plus a response code taken from a domain word.
        """
        brand = self.brand(request)
        # A page may not shadow a brand key. Whichever wins, one of the two
        # readers is getting the other's value, and nothing raises: shadowing is
        # simply what a merged dict does.
        if collisions := sorted(set(context) & set(brand)):
            raise RuntimeError(
                f"{template} passes {', '.join(collisions)}, which the brand "
                f"context already supplies. Rename the page's key to what it "
                f"holds -- `query_version` rather than `version` -- because "
                f"whichever wins, one of the two readers is getting the other's "
                f"value.")
        return self.templates.TemplateResponse(
            request, template, {**brand, **context}, status_code=http_status)
