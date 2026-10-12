"""
Pravaha console — the screens of the plain-English features (docs/guides/AI.md), in drishti's design.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    /ask                          Ask your data, with a conversation (Catalog -> Ask your data)
    /admin/ai                     Admin -> AI: the engine's AI layer and the console's, read-only
    /api/v1/admin/ai/test         Test connection (admin): one real call, its outcome and latency
    /api/v1/ai/features           which controls this person's pages draw
    /api/v1/ai/{task}             ask, draft, register, alert-draft, create-alert, explain, narrate,
                                  page -- each answered as JSON carrying the server-rendered fragment

Each task is the engine's (core/ai_features.py), called as the signed-in person; the answer is drawn
here, once, by ``_ai_fragment.html``, and ai.js puts it in place -- drishti's bi-ai.js, with the
fragment rendered on the server so a refusal, a draft and its confirm step look the same everywhere.
Ask also works with scripting off (the form posts to /ask and the page comes back with the answer).

The assistant's old task routes (ADR-058's /assist/*) are folded in: they answer with where their work
is now, so a bookmark or a form from a page still open keeps working.
"""
from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from core import credential
from core.ai import console_ai_layer
from core.ai_features import AiFeatures, Failure
from routes.auth_routes import current_user, login_required
from routes.base import Routes, _signed_in, session_roles, sign_in_first

logger = logging.getLogger(__name__)

#: The console tasks and the template fragment each answer is drawn with.
TASKS = ("ask", "draft", "register", "alert-draft", "create-alert", "explain", "narrate", "page")


def _yes(value: Any) -> bool:
    return str(value or "").strip().lower() in ("yes", "true", "on", "1")


class AiRoutes(Routes):
    def register(self) -> None:
        self.service = AiFeatures(self.ctx["engine"], self.ctx["services"])
        self.ctx["ai_features"] = self.service
        self.layer = console_ai_layer(self.ctx["config"], self.ctx["engine"])
        if self.templates is not None:
            self.templates.env.globals["ai_can"] = self.can
            self.templates.env.globals["ai_values"] = lambda request: self.features_of(request)["values"]
        self._pages()
        self._admin()
        self._tasks()
        self._folded()

    # ------------------------------------------------------------------ which controls are drawn
    def features_of(self, request: Request) -> dict[str, Any]:
        """Asked of the engine once per request, and only for a signed-in person on a reachable engine.

        On a credential of its own, as a public page asks the engine's status: a page draws its
        controls from the answer, and a refusal of the question -- a session the engine has ended, a
        password it is holding the person to change -- must not end or hold the session of a page
        that is otherwise allowed (routes.web_security decides that, not this)."""
        held = getattr(request.state, "ai_features", None)
        if held is None:
            held = {"enabled": False, "values": "labels-only", "features": {}}
            if _signed_in(request) and self.ctx["services"].health.health().reachable:
                with credential.bound(credential.Credential(credential.token())):
                    held = self.service.features()
            request.state.ai_features = held
        return held

    def can(self, request: Request, feature: str) -> bool:
        """Whether a page draws this feature's control: the layer on and the policy open to this person."""
        return bool(self.features_of(request)["features"].get(feature))

    # ------------------------------------------------------------------ fragments
    def fragment(self, request: Request, kind: str, **values: Any) -> str:
        assert self.templates is not None, "the AI answers are drawn by the templates"
        brand = self.brand(request)
        return self.templates.env.get_template("_ai_fragment.html").render(
            kind=kind, request=request, csrf_token=brand["csrf_token"], ai_can=self.can, **values)

    def about_page(self, screen: str) -> tuple[str, list[dict[str, str]]]:
        """A screen's "About this page" text and up to four of its help topics, from the console's own
        words -- never from the browser."""
        from core import page_help

        parts = [self.t(f"page.{screen}.what")]
        for n, _icon in page_help.tiles(screen):
            parts.append(f"{self.t(f'page.{screen}.points.{n}.heading')}: {self.t(f'page.{screen}.points.{n}.text')}")
        catalog = self.ctx["help"]
        topics = []
        for card in catalog.for_screen(screen)[:4]:
            topic = catalog.topic(card.slug)
            if topic is not None:
                topics.append({"title": topic.title, "text": (topic.summary + "\n\n" + topic.body)[:3000]})
        return "\n".join(parts), topics

    # ------------------------------------------------------------------ Ask
    def _pages(self) -> None:
        @self.app.get("/ask", response_class=HTMLResponse, tags=["ui"])
        def ask_page(request: Request, view: str = ""):
            if (refusal := login_required(request)) is not None:
                return refusal
            return self.page(request, "ask.html", current="/ask", answer=None, failure=None, question="",
                             conversation="[]", scope_view=view[:200])

        @self.app.post("/ask", response_class=HTMLResponse, tags=["ui"])
        async def ask_post(request: Request):
            """Ask with scripting off: the form posts, and the page comes back with the answer."""
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            question = str(form.get("question") or "")
            view = str(form.get("view") or "")[:200]
            try:
                conversation = json.loads(str(form.get("conversation") or "[]"))
            except ValueError:
                conversation = []
            answer = failure = None
            try:
                answer = self.service.ask(question, conversation, view=view)
            except Failure as exc:
                failure = exc
            html = self.fragment(request, "failure" if failure else "ask", answer=answer, failure=failure)
            return self.page(request, "ask.html", http_status=failure.status if failure else 200, current="/ask",
                             answer_html=html, answer=answer, failure=failure, question="" if answer else question,
                             conversation=json.dumps(answer["conversation"] if answer else conversation),
                             scope_view=view)

    # ------------------------------------------------------------------ Admin -> AI
    def _admin(self) -> None:
        @self.app.get("/admin/ai", response_class=HTMLResponse, tags=["ui"])
        def admin_ai(request: Request):
            """Admin -> AI (drishti's admin/ai.html): the engine's layer and the console's, read-only."""
            if (refusal := login_required(request)) is not None:
                return refusal
            engine_layer, engine_error = None, None
            try:
                engine_layer = self.ctx["engine"].admin_ai()
            except Exception as exc:  # noqa: BLE001 -- the page says what the engine said
                engine_error = {"code": getattr(exc, "code", "") or "", "message": str(exc),
                                "status": int(getattr(exc, "status", 0) or 0)}
            status = 403 if engine_error and engine_error["status"] in (401, 403) else 200
            return self.page(request, "admin_ai.html", http_status=status, current="/admin", tab="ai",
                             engine_layer=engine_layer, layer_error=engine_error, console_layer=self.layer.status())

        @self.app.post(f"{self.api}/admin/ai/test", tags=["api"])
        async def admin_ai_test(request: Request):
            """Test connection: one real call through the engine (admin only, bounded and rate-limited
            there); the answer is its outcome and how long it took -- never a key."""
            if current_user(request) is None:
                return sign_in_first()
            if "admin" not in session_roles(request):
                return JSONResponse({"ok": False, "error": "an administrator only", "code": "PRV-7002"}, status_code=403)
            try:
                body = await request.json()
            except ValueError:
                body = {}
            model = str((body or {}).get("model") or "").strip() or None
            try:
                answer = self.ctx["engine"].admin_ai_test(model)
            except Exception as exc:  # noqa: BLE001 -- a failed test is an answer, not an error page
                logger.info("'%s' tested AI model %s: %s", current_user(request), model or "(default)", exc)
                return JSONResponse({"ok": False, "code": getattr(exc, "code", "") or "", "error": str(exc)},
                                    status_code=200)
            logger.info("'%s' tested AI model %s: ok", current_user(request), answer.get("model"))
            return JSONResponse({"ok": bool(answer.get("ok", True)), "model": answer.get("model"),
                                 "millis": answer.get("millis"), "text": str(answer.get("text") or "")[:200]})

    # ------------------------------------------------------------------ the tasks
    def _run(self, request: Request, task: str, body: dict[str, Any]) -> tuple[str, Any]:
        user = str(current_user(request))
        service = self.service
        if task == "ask":
            return "ask", service.ask(str(body.get("question") or ""), body.get("conversation"), view=str(body.get("view") or ""))
        if task == "draft":
            return "draft", service.draft(user, str(body.get("description") or ""))
        if task == "register":
            return "registered", service.register(user, str(body.get("draft_id") or ""), _yes(body.get("confirmed")))
        if task == "alert-draft":
            return "alert-draft", service.alert_draft(user, str(body.get("description") or ""))
        if task == "create-alert":
            return "alert-created", service.create_alert(user, str(body.get("draft_id") or ""),
                                                         _yes(body.get("confirmed")))
        if task == "explain":
            return "explain", service.explain(body)
        if task == "narrate":
            return "narrate", service.narrate(str(body.get("view") or ""), body.get("changes"))
        screen = str(body.get("screen") or "")
        about, topics = self.about_page(screen)
        return "page", service.page(screen, about, str(body.get("question") or ""), topics)

    def _tasks(self) -> None:
        @self.app.get(f"{self.api}/ai/features", tags=["api"])
        def ai_features(request: Request):
            if current_user(request) is None:
                return sign_in_first()
            return JSONResponse(self.features_of(request))

        @self.app.post(f"{self.api}/ai/{{task}}", tags=["api"])
        async def ai_task(request: Request, task: str):
            if current_user(request) is None:
                return sign_in_first()
            if task not in TASKS:
                return JSONResponse({"ok": False, "error": f"no such task: {task}"}, status_code=404)
            try:
                body = await request.json()
            except ValueError:
                body = {}
            body = body if isinstance(body, dict) else {}
            try:
                kind, result = self._run(request, task, body)
            except Failure as exc:
                logger.info("ai %s refused for '%s': %s %s", task, current_user(request), exc.code or "-", exc.message)
                html = self.fragment(request, "failure", failure=exc, task=task)
                return JSONResponse({"ok": False, "html": html, "error": exc.to_dict()}, status_code=exc.status or 400)
            html = self.fragment(request, kind, answer=result, task=task)
            return JSONResponse({"ok": True, "html": html, "result": result})

    # ------------------------------------------------------------------ the assistant, folded in
    def _folded(self) -> None:
        """ADR-058's console tasks now live on the engine's features: a form still open on an old page
        lands where its work is, and the old JSON calls say where to go."""
        where = {"draft": "/workbench#ai-describe", "register": "/workbench#ai-describe",
                 "explain-query": "/queries", "explain-refusal": "/workbench"}

        for task, target in where.items():
            def moved(_request: Request, _target: str = target):
                return RedirectResponse(_target, status_code=303)
            moved.__name__ = f"assist_{task.replace('-', '_')}_moved"
            self.app.post(f"/assist/{task}", tags=["ui"])(moved)

            def gone(_request: Request, _task: str = task):
                return JSONResponse({"error": f"the assistant's {_task} is now the engine's: POST {self.api}/ai/"
                                     + ("draft" if _task in ("draft", "register") else "explain"),
                                     "status": 410}, status_code=410)
            gone.__name__ = f"assist_{task.replace('-', '_')}_gone"
            self.app.post(f"{self.api}/assist/{task}", tags=["api"])(gone)


__all__ = ["AiRoutes", "TASKS"]
