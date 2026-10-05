"""
Pravaha console — the alert screens (ADR-057).
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    /alerts                     every alert this person may see: its view, firing and pending
                                chips, severity, channels, and whether it is paused or snoozed
    /alerts/{name}              one alert: every key it holds -- firing, pending, clearing,
                                cleared -- what the channels were last told and what they are
                                owed, and its recent notifications; pause, resume, snooze, ack
    POST /alerts/{name}/pause|resume|snooze|ack

Every answer and every refusal is the engine's (``/api/v1/alerts``), called as the signed-in
person, and every form carries the session's CSRF token. The console decides nothing: a person
who may not pause an alert is refused by the engine, and the flash says so with its code.
"""
from __future__ import annotations

import logging
from urllib.parse import quote

from fastapi import Request
from fastapi.responses import HTMLResponse, RedirectResponse

from core.alerting import AlertsService, key_text
from core.services import ServiceError
from routes.auth_routes import current_user, flash, login_required, take_flashes
from routes.base import Routes, failure

logger = logging.getLogger(__name__)


def _said(exc: ServiceError) -> str:
    text = str(exc)
    return text + (f" ({exc.code})" if exc.code and exc.code not in text else "")


class AlertRoutes(Routes):
    def register(self) -> None:
        alerts = AlertsService(self.ctx["services"].engine)
        self.ctx["alerting"] = alerts

        @self.app.get("/alerts", response_class=HTMLResponse, tags=["ui"])
        def alert_list(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            items, error, off, channels = [], None, False, []
            try:
                items = alerts.alerts()
                channels = alerts.channels()
            except ServiceError as exc:
                if AlertsService.is_off(exc):
                    off = True
                else:
                    error = failure(exc, request, "the alerts")
            firing = sum(int(a.get("firing") or 0) for a in items)
            return self.page(request, "alerts.html", http_status=409 if off else 200, current="/operations",
                             alerts=items, alerts_error=error, alerts_off=off, channels=channels,
                             firing_total=firing, flashes=take_flashes(request))

        @self.app.get("/alerts/{name}", response_class=HTMLResponse, tags=["ui"])
        def alert_detail(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                detail = alerts.alert(name)
            except ServiceError as exc:
                if AlertsService.is_off(exc):
                    return self.page(request, "alerts.html", http_status=409, current="/operations", alerts=[],
                                     alerts_error=None, alerts_off=True, channels=[], firing_total=0,
                                     flashes=[])
                return self.page(request, "not_found.html", http_status=exc.status if exc.status == 404 else 503,
                                 current="/operations", what=self.t("alerts.what"), identifier=name,
                                 back_href="/alerts", back_label=self.t("alerts.back"), detail=str(exc))
            return self.page(request, "alert_detail.html", current="/operations", detail=detail,
                             key_text=key_text, flashes=take_flashes(request))

        def done(request: Request, name: str, fn, message: str, **params):
            back = "/alerts/" + quote(name, safe="")
            try:
                fn()
            except ServiceError as exc:
                flash(request, self.t("alerts.failed", detail=_said(exc)), "danger")
                return RedirectResponse(back, status_code=303)
            flash(request, self.t(message, name=name, **params), "success")
            return RedirectResponse(back, status_code=303)

        @self.app.post("/alerts/{name}/pause", tags=["ui"])
        async def pause(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("'%s' asked the engine to pause the alert %s", current_user(request), name)
            return done(request, name, lambda: alerts.pause(name), "alerts.paused")

        @self.app.post("/alerts/{name}/resume", tags=["ui"])
        async def resume(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("'%s' asked the engine to resume the alert %s", current_user(request), name)
            return done(request, name, lambda: alerts.resume(name), "alerts.resumed")

        @self.app.post("/alerts/{name}/snooze", tags=["ui"])
        async def snooze(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            duration = str(form.get("duration") or "").strip()
            logger.info("'%s' asked the engine to snooze the alert %s for %s", current_user(request), name, duration)
            return done(request, name, lambda: alerts.snooze(name, duration), "alerts.snoozed", duration=duration)

        @self.app.post("/alerts/{name}/ack", tags=["ui"])
        async def ack(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            key = str(form.get("key") or "").strip()
            logger.info("'%s' asked the engine to acknowledge %s on the alert %s", current_user(request),
                        key or "every firing key", name)
            back = "/alerts/" + quote(name, safe="")
            try:
                answer = alerts.ack(name, key or None)
            except ServiceError as exc:
                flash(request, self.t("alerts.failed", detail=_said(exc)), "danger")
                return RedirectResponse(back, status_code=303)
            flash(request, self.t("alerts.acked", name=name, count=answer.get("acknowledged", 0)), "success")
            return RedirectResponse(back, status_code=303)


__all__ = ["AlertRoutes"]
