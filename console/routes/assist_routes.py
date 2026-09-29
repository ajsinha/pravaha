"""
Pravaha console — the assistant (ADR-058 phase 3): Admin · AI models, and the assist surfaces.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    /admin/ai-models              providers, models, profiles and their chains, budgets, usage,
                                  and the recent changes -- an administrator's screen
    /admin/ai-models/{op}         one change (POST, CSRF-protected): applied to the console's
                                  router before the answer, so the next request uses it
    /assist/draft                 "Describe it": a draft from a description, judged by the engine
    /assist/register              register a draft the engine accepted, once the person confirms
    /assist/explain-query         a registered query (or SQL) in plain English
    /assist/explain-refusal       what a PRV code means for this statement, and what to change

Each ``/assist/*`` POST is also ``/api/v1/assist/*``: the same work, answered as JSON carrying the
server-rendered fragment (``html``) the page's script puts in place, so the result is drawn once,
here, whether or not scripting is on. Without scripting the form posts to the page route and the
fragment comes back inside a page.

**Who may administer.** The configuration is the console's, not the engine's, so the console has
to decide -- and it asks the engine, which is the identity authority (ADR-052): on every request
to Admin · AI models the engine's ``auth/me`` must name the ``admin`` role. A person without it is
shown the not-permitted state (and a 403); the session's copy of the roles only decides whether
the tab is offered.
"""
from __future__ import annotations

import logging
from typing import Any

from fastapi import Request
from fastapi.responses import (
    HTMLResponse,
    JSONResponse,
    PlainTextResponse,
    RedirectResponse,
)

from core.assist import AssistFailure, AssistService
from core.observability import authorized
from core.services import ServiceError
from routes.auth_routes import (
    current_user,
    flash,
    local_path,
    login_required,
    take_flashes,
)
from routes.base import Routes, failure, sign_in_first, ui_text

logger = logging.getLogger(__name__)

ADMIN_PATH = "/admin/ai-models"


def _int_or_none(value: Any, what: str) -> int | None:
    text = str(value or "").strip().replace(",", "").replace("_", "")
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        raise ServiceError(ui_text("admin.ai.bad_tokens", what=what, value=text)) from None


def _float_or_none(value: Any, what: str) -> float | None:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        raise ServiceError(ui_text("admin.ai.bad_seconds", what=what, value=text)) from None


def _refuse(failure: AssistFailure):
    """A task that fails before it starts (nothing to ask), in the shape of one that ran."""
    def run(_user: str):
        raise failure
    return run


def _chain(text: str) -> list[str]:
    return [part.strip() for part in str(text or "").replace("\n", ",").split(",") if part.strip()]


class AssistRoutes(Routes):
    def register(self) -> None:
        config = self.ctx["config"]
        service = AssistService(
            self.ctx["engine"],
            config_path=config.get("assist.config") or None,
            usage_path=config.get("assist.usage") or None,
            log_path=config.get("assist.log") or None,
            watch_seconds=config.get_float("assist.watch_seconds", 1.0) or 1.0,
        )
        self.ctx["assist"] = service
        self.app.state.assist = service
        if self.templates is not None:
            # What every assist surface asks before drawing itself: ready, or the empty state.
            self.templates.env.globals["assist_status"] = service.status
        self.service = service
        self._register_admin()
        self._register_tasks()
        self._register_metrics()

    # ================================================================== /metrics

    def _register_metrics(self) -> None:
        """``/metrics``: the assistant's requests, tokens, failures, fallbacks and latency for
        Prometheus -- only with ``metrics.enabled``, and behind ``metrics.token`` when one is set.

        Configuration-gated rather than behind the admin sign-in, because the caller is a scraper
        with no session and no password; off by default, because it names the models configured and
        how much each is used. A token is a bearer Prometheus sends (``authorization: credentials``);
        without one the endpoint answers anyone who can reach the console, which is said at startup.
        """
        config = self.ctx["config"]
        if not config.get_bool("metrics.enabled", False):
            return
        token = str(config.get("metrics.token", "") or "")
        if not token:
            logger.warning("/metrics is on (metrics.enabled) with no metrics.token: anyone who can reach "
                           "this console can read which models are configured and how much each is used")
        service = self.service
        version = str(config.get("app.version", "") or "")

        @self.app.get("/metrics", include_in_schema=False)
        def metrics(request: Request):
            if not authorized(request.headers.get("authorization"), token):
                return PlainTextResponse("a bearer token is required (metrics.token)\n", status_code=401,
                                         headers={"WWW-Authenticate": "Bearer"})
            return PlainTextResponse(service.prometheus(version),
                                     media_type="text/plain; version=0.0.4; charset=utf-8")

    # ================================================================== Admin · AI models

    def _admin_gate(self, request: Request) -> tuple[dict | None, Any]:
        """``(denied, error)``: the engine's answer to whether this person is an administrator."""
        try:
            if self.service.is_admin():
                return None, None
        except ServiceError as exc:
            if exc.status in (401, 403):
                return {"reason": str(exc), "code": exc.code}, None
            return None, failure(exc, request, "who you are")
        return {"reason": self.t("admin.ai.denied"), "code": None}, None

    def _register_admin(self) -> None:
        service = self.service

        @self.app.get(ADMIN_PATH, response_class=HTMLResponse, tags=["ui"])
        def ai_models(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            denied, error = self._admin_gate(request)
            view = usage = changes = None
            if denied is None and error is None:
                view, usage, changes = service.admin_view(), service.usage(), service.recent_changes()
            status = 403 if denied else 503 if error else 200
            return self.page(request, "admin_ai_models.html", http_status=status, current="/admin",
                             tab="ai-models", denied=denied, gate_error=error, view=view,
                             usage=usage, changes=changes, flashes=take_flashes(request))

        @self.app.post(ADMIN_PATH + "/{op}", tags=["ui"])
        async def ai_models_change(request: Request, op: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            denied, error = self._admin_gate(request)
            if denied is not None or error is not None:
                flash(request, self.t("admin.ai.denied") if denied else str(error), "danger")
                return RedirectResponse(ADMIN_PATH, status_code=303)
            form = await request.form()
            field = lambda name: str(form.get(name) or "").strip()
            actor = str(current_user(request))
            back = ADMIN_PATH + {"models": "#models", "providers": "#providers",
                                 "profiles": "#profiles", "budgets": "#budgets"}.get(op.split("-")[0], "")
            try:
                version = int(field("version")) if field("version") else None
            except ValueError:
                version = None
            try:
                if op == "models-test":
                    result = service.test_model(field("model"))
                    if result.get("ok"):
                        flash(request, self.t("admin.ai.test.ok", model=field("model"),
                                              ms=f"{float(result.get('latency_ms') or 0):.0f}"), "success")
                    else:
                        err = result.get("error") or {}
                        flash(request, self.t("admin.ai.test.failed", model=field("model"),
                                              kind=str(err.get("kind") or "error"),
                                              message=str(err.get("message") or "")), "danger")
                    logger.info("'%s' tested model '%s': %s", actor, field("model"),
                                "ok" if result.get("ok") else "failed")
                    return RedirectResponse(back, status_code=303)
                record = self._apply(op, field, version, actor)
            except ServiceError as exc:
                key = "admin.ai.conflict" if exc.status == 409 else "admin.ai.refused"
                flash(request, self.t(key, detail=str(exc)), "danger")
                return RedirectResponse(back, status_code=303)
            flash(request, self.t("admin.ai.changed", action=record["action"], target=record["target"],
                                  version=record["version"]), "success")
            return RedirectResponse(back, status_code=303)

    def _apply(self, op: str, field, version: int | None, actor: str) -> dict:
        """One change from the admin page's form, as the one :class:`AssistAdmin` call it is."""
        change = lambda action, *a, **k: self.service.change(actor, version, action, *a, **k)
        if op == "models-add":
            return change("add_model", field("model"), field("provider"), field("name"),
                          enabled=field("enabled") == "yes",
                          timeout_s=_float_or_none(field("timeout_s"), ui_text("admin.ai.timeout")))
        if op == "models-update":
            return change("update_model", field("model"), provider=field("provider"),
                          model=field("name"),
                          timeout_s=_float_or_none(field("timeout_s"), ui_text("admin.ai.timeout")))
        if op == "models-enable":
            return change("enable_model", field("model"))
        if op == "models-disable":
            return change("disable_model", field("model"))
        if op == "models-remove":
            return change("remove_model", field("model"))
        if op in ("providers-add", "providers-update"):
            kind, ref = field("key_kind"), field("key_ref")
            key = {"api_key_env": ref if kind == "env" and ref else None,
                   "api_key_file": ref if kind == "file" and ref else None}
            timeout = _float_or_none(field("timeout_s"), ui_text("admin.ai.timeout")) or 60.0
            if op == "providers-add":
                return change("add_provider", field("provider"), field("type"),
                              endpoint=field("endpoint") or None, timeout_s=timeout, **key)
            return change("update_provider", field("provider"), endpoint=field("endpoint") or None,
                          timeout_s=timeout, **key)
        if op == "providers-remove":
            return change("remove_provider", field("provider"))
        if op == "profiles-set":
            return change("set_chain", field("profile"), _chain(field("chain")),
                          default=field("default") == "yes")
        if op in ("profiles-up", "profiles-down", "profiles-drop", "profiles-add"):
            profile, model = field("profile"), field("model")
            stored = self.service.store.load()
            chain = list(stored.profiles.get(profile) or [])
            if op == "profiles-add":
                if model and model not in chain:
                    chain.append(model)
            elif model in chain:
                at = chain.index(model)
                if op == "profiles-drop":
                    chain.pop(at)
                else:
                    to = at - 1 if op == "profiles-up" else at + 1
                    if 0 <= to < len(chain):
                        chain[at], chain[to] = chain[to], chain[at]
            return change("set_chain", profile, chain)
        if op == "profiles-default":
            return change("set_default_profile", field("profile"))
        if op == "profiles-remove":
            return change("remove_profile", field("profile"))
        if op == "budgets-set":
            return change("set_budgets",
                          per_user_daily_tokens=_int_or_none(field("per_user_daily_tokens"),
                                                             ui_text("admin.ai.budget_daily")),
                          per_request_max_tokens=_int_or_none(field("per_request_max_tokens"),
                                                              ui_text("admin.ai.budget_request")))
        raise ServiceError(ui_text("admin.ai.no_such_change", op=op), status=404)

    # ================================================================== the tasks

    def _fragment(self, request: Request, kind: str, **values: Any) -> str:
        brand = self.brand(request)
        assert self.templates is not None, "the assistant's answers are drawn by the templates"
        template = self.templates.env.get_template("_assist_fragment.html")
        return template.render(kind=kind, request=request, csrf_token=brand["csrf_token"],
                               is_admin=brand["is_admin"], asset_version=brand["asset_version"],
                               **values)

    async def _fields(self, request: Request) -> dict[str, Any]:
        if request.headers.get("content-type", "").startswith("application/json"):
            try:
                body = await request.json()
            except ValueError:
                body = {}
            return body if isinstance(body, dict) else {}
        form = await request.form()
        return {k: v for k, v in form.items() if isinstance(v, str)}

    def _answer(self, request: Request, api: bool, kind: str, run, fields: dict[str, Any]):
        """Runs one task and answers it: JSON with the fragment for the page's script, or a page
        around the fragment. A failure is a fragment too -- the empty state when no model can
        answer, the budget's refusal, the model's normalised error, the engine's refusal."""
        user = str(current_user(request))
        try:
            result, failed = run(user), None
        except AssistFailure as exc:
            result, failed = None, exc
        except ServiceError as exc:
            result, failed = None, AssistFailure("engine", str(exc), exc.status or 400, code=exc.code)
        shown = "empty" if failed is not None and failed.kind == "unconfigured" else \
            "failure" if failed is not None else kind
        fragment = self._fragment(request, shown, result=result, failure=failed, fields=fields)
        status = failed.status if failed is not None else 200
        if api:
            return JSONResponse({"html": fragment, "ok": failed is None, "result": result,
                                 "error": failed.to_dict() if failed is not None else None},
                                status_code=status)
        back = local_path(str(fields.get("next") or "")) if fields.get("next") else "/workbench"
        return self.page(request, "assist_result.html", http_status=status, fragment=fragment,
                         back=back, task=kind)

    def _register_tasks(self) -> None:
        service = self.service

        def gate(request: Request, api: bool):
            if api:
                return sign_in_first() if current_user(request) is None else None
            return login_required(request)

        def draft(fields: dict[str, Any]):
            description = str(fields.get("description") or "")
            answers = []
            for index in range(12):
                question = fields.get(f"question_{index}")
                if question is None:
                    continue
                answers.append((str(question), str(fields.get(f"answer_{index}") or "")))
            wanted = service.with_answers(description, answers)
            if not wanted.strip():
                raise AssistFailure("input", self.t("assist.describe.empty_description"), 400)
            return lambda user: service.draft(user, wanted, name=str(fields.get("name") or "") or None)

        def register(fields: dict[str, Any]):
            return lambda user: service.register(
                user, str(fields.get("draft_id") or ""),
                confirmed=str(fields.get("confirmed") or "").lower() in ("yes", "true", "on", "1"),
                name=str(fields.get("name") or "").strip() or None)

        def explain_query(fields: dict[str, Any]):
            return lambda user: service.explain_query(user, name=str(fields.get("name") or "") or None,
                                                      sql=str(fields.get("sql") or "") or None)

        def explain_refusal(fields: dict[str, Any]):
            return lambda user: service.explain_refusal(user, str(fields.get("code") or ""),
                                                        str(fields.get("sql") or "") or None)

        tasks = {"draft": ("draft", draft), "register": ("registered", register),
                 "explain-query": ("explained-query", explain_query),
                 "explain-refusal": ("explained-refusal", explain_refusal)}

        def handler(task: str, api: bool):
            kind, build = tasks[task]

            async def handle(request: Request):
                if (refusal := gate(request, api)) is not None:
                    return refusal
                fields = await self._fields(request)
                try:
                    run = build(fields)
                except AssistFailure as exc:
                    run = _refuse(exc)
                return self._answer(request, api, kind, run, fields)

            handle.__name__ = f"assist_{task.replace('-', '_')}{'_api' if api else ''}"
            return handle

        for task in tasks:
            self.app.post(f"/assist/{task}", tags=["ui"])(handler(task, False))
            self.app.post(f"{self.api}/assist/{task}", tags=["api"])(handler(task, True))

        @self.app.get(f"{self.api}/assist/status", tags=["api"])
        def assist_status(request: Request, profile: str = ""):
            if current_user(request) is None:
                return sign_in_first()
            return JSONResponse(service.status(profile or None))
