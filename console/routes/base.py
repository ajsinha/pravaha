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
from typing import Any, Self

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.templating import Jinja2Templates

from core.i18n import Messages
from core.services import ServiceError

logger = logging.getLogger(__name__)

#: The catalog a Routes built without a template environment speaks from, and what a module-level
#: helper (the 401 every JSON route answers anonymously) says. The entry point replaces it with
#: the configured language's (``use_messages``).
_MESSAGES = Messages()


def use_messages(messages: Messages) -> None:
    global _MESSAGES
    _MESSAGES = messages


def ui_text(key: str, **params: Any) -> str:
    """A UI string by key, for code that has no Routes at hand."""
    return str(_MESSAGES(key, **params))


class Failure(str):
    """A call that failed while a page was being rendered, as design 23.12's error state needs it:
    the message (the string itself, so a template that prints it is unchanged), the engine's PRV
    code, whether retrying can help, and a correlation id -- the same id is in the console's log
    line for it, which is what makes "paste the correlation id into a ticket" find something."""

    code: str
    correlation: str
    retryable: bool

    def __new__(cls, message: str, code: str = "", correlation: str = "",
                retryable: bool = True) -> Self:
        made = super().__new__(cls, message)
        made.code, made.correlation, made.retryable = code or "", correlation, retryable
        return made


def failure(exc: BaseException, request: Request | None = None, what: str = "") -> Failure:
    """``exc`` as a :class:`Failure`, logged once with its correlation id. The browser's own id
    when the request carried one (api.js sends it), else a fresh one."""
    import uuid

    cid = _correlation(request)
    if cid == "-":
        cid = uuid.uuid4().hex[:8]
    status = getattr(exc, "status", 503)
    code = getattr(exc, "code", None) or ""
    # A refusal (4xx) will be refused again; an engine that did not answer may answer next time.
    retryable = not isinstance(status, int) or status >= 500 or status in (0, 408, 429)
    logger.warning("page rendered without %s (%s) [%s]: %s", what or "engine data", code or "-", cid, exc)
    return Failure(str(exc), code=code, correlation=cid, retryable=retryable)


def sign_in_first() -> JSONResponse:
    """The JSON API's answer to a request without a session: 401, not a redirect to a login page
    a fetch would read as data."""
    return JSONResponse({"error": ui_text("api.sign_in_first"), "status": 401}, status_code=401)


def _signed_in(request: Request | None) -> bool:
    """Whether this request carries a console session.

    Defensive about the session being absent entirely, because the middleware is
    configured in the entry point and a test may build an app without it.
    """
    if request is None:
        return False
    try:
        return request.session.get("user") is not None
    except Exception:  # noqa: BLE001 -- no session middleware on this app
        return False


def _correlation(request: Request | None) -> str:
    """The id api.js generated for this call, or ``"-"`` for a caller that sent none.

    A request without the header is not an error -- a server-rendered page's own POST
    (the pause/resume/drop forms, which work with scripting off) never runs api.js at all,
    so it never had an id to send. What matters is that when the header IS present, the
    same string ends up in this log line and nowhere else it could drift from.
    """
    if request is None:
        return "-"
    return request.headers.get("x-correlation-id", "-")

#: The principal the console's shared-secret sign-in records for everybody.
SHARED_PRINCIPAL = "operator"

#: The personas a signed-in person can be (design 23.2), where each lands, and what the
#: landing is for. A role picks a landing, not a permission: the admin persona lands on Access,
#: and what the audit screen shows is still decided by the engine's policy for the console's
#: identity, whoever chose which role.
#: ``label`` and ``blurb`` are keys into the UI string catalog, not English: a template says
#: ``t(meta.label)``, so the words are where every other string is.
ROLES: dict[str, dict[str, str]] = {
    "analyst": {"label": "roles.analyst.label", "landing": "/workbench",
                "blurb": "roles.analyst.blurb"},
    "operator": {"label": "roles.operator.label", "landing": "/operations",
                 "blurb": "roles.operator.blurb"},
    "developer": {"label": "roles.developer.label", "landing": "/views",
                  "blurb": "roles.developer.blurb"},
    "admin": {"label": "roles.admin.label", "landing": "/admin/access",
              "blurb": "roles.admin.blurb"},
}


def role_of(request: Request | None, default: str = "operator") -> str:
    """The signed-in person's role: their own choice, else the principal's name, else the default.

    The console's sign-in is one shared secret today, so the principal is usually just
    "operator"; a deployment fronted by an identity provider would name the person, and a
    principal literally called ``analyst`` or ``developer`` lands where that name says.
    """
    chosen = None
    user = None
    if request is not None:
        try:
            chosen = request.session.get("role")
            user = request.session.get("user")
        except Exception:  # noqa: BLE001 -- no session middleware on this app
            chosen = user = None
    if chosen in ROLES:
        return str(chosen)
    # The shared-secret sign-in names everyone "operator" -- a name for the gate, not a
    # statement about the person -- so it does not decide a role; the configured default
    # does. A principal from a real identity provider named for a role does.
    if user in ROLES and user != SHARED_PRINCIPAL:
        return str(user)
    return default if default in ROLES else "operator"


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

    def t(self, key: str, **params: Any) -> str:
        """A UI string from the catalog the templates use, for what a route hands a page as data
        (a not-found page's "Back to …", the palette's entries): keys cross into Python, and
        English stays in ``web/i18n``."""
        messages = self.templates.env.globals.get("t") if self.templates is not None else None
        return str((messages if isinstance(messages, Messages) else _MESSAGES)(key, **params))

    # ----------------------------------------------------------------- domain
    def guard(self, fn: Callable[[], Any], request: Request | None = None) -> Any:
        """Run a service call, mapping any refusal onto the taxonomy."""
        try:
            return fn()
        except ServiceError as exc:
            # A refusal is normal operation, not a fault -- but it is never
            # translated without a trace, or a console that swallows the engine's
            # reasoning becomes the reason nobody can see it.
            logger.warning("refused (%s) [%s]: %s", exc.code or "-", _correlation(request), exc)
            raise HTTPException(status_for(exc), problem(exc)) from exc

    def json_guard(self, fn: Callable[[], Any], request: Request | None = None) -> JSONResponse:
        """The same, for the API: a JSON body rather than an exception.

        ``request`` is how the browser's own correlation id (api.js's ``X-Correlation-Id``,
        shown on screen by ``States.error``) reaches this log line. Generating the id in the
        browser and never logging it server-side would make "paste the correlation id into a
        ticket" a step that finds nothing -- the whole point of having one is that the string
        on screen is the string an operator can grep for.
        """
        try:
            return JSONResponse(fn())
        except ServiceError as exc:
            logger.warning("refused (%s) [%s]: %s", exc.code or "-", _correlation(request), exc)
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
            # On every page, because a control that is present but refuses is worse than
            # one whose absence is explained.
            "signed_in": _signed_in(request),
            "role": role_of(request, c.get("ui.default_role", "operator")),
            "roles": ROLES,
            # Asset versions are part of the page, so a cached module can never run against
            # a template from another release.
            "asset_version": c.get("app.version", "0"),
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
        if self.templates is None:
            # Constructed without a template environment -- an API-only assembly. Better to
            # say so than to fail inside Jinja with a null dereference two frames down.
            raise RuntimeError(
                template + " was requested, but this Routes was built without a Jinja "
                + "environment. Pass one to the constructor, or use the JSON API.")
        return self.templates.TemplateResponse(
            request, template, {**brand, **context}, status_code=http_status)
