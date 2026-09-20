"""
Pravaha console — the operator interface.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The UI holds no engine logic: it renders what the services computed, with their
reasoning. Every asset is vendored, so it renders air-gapped.

Each screen is rendered by the SERVER first and then kept current by its module.
Two reasons, both real. A page that is empty until a fetch returns looks broken
for the half-second before it isn't, and this one is opened by somebody who
already suspects something is wrong. And where a proxy eats `text/event-stream`
— which is exactly the deployment nobody tests — the page still works, just
without the live part.
"""
from __future__ import annotations

import base64
import binascii
import logging
from urllib.parse import quote

from fastapi import Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse

from core.services import ServiceError
from routes.auth_routes import current_user, login_required
from routes.base import Routes, failure, status_for

logger = logging.getLogger(__name__)


def _typed(value: str):
    """A form field is a string. A parameter is a value.

    Coerced here rather than left to the engine, because sending "40" where the
    column is an integer compares a string to a number and silently matches
    nothing — which looks like an empty result rather than like a form filled in
    wrongly (ADR-032).
    """
    text = str(value).strip()
    negative = text.startswith("-")
    digits = text[1:] if negative else text
    if digits.isdigit():
        return int(text)
    if digits.replace(".", "", 1).isdigit():
        return float(text)
    return value


#: How much of a dead letter's record a row of the table shows. A record is arbitrary bytes and
#: can be a whole JSON document; the row shows enough to recognise it and the rest is a fetch of
#: that one entry away, which is also the call the audit trail records separately.
_RECORD_PREVIEW = 160


def _readable(page: dict) -> dict:
    """The API's page with each entry's record decoded for a template.

    The wire carries the record as Base64 because it is arbitrary bytes. A screen has to show
    *something*, so it is decoded as UTF-8 with the undecodable bytes replaced -- which is
    honest for the common case (a CSV line with a letter where a number should be) and visibly
    mangled for the uncommon one (a binary record), rather than a wall of Base64 that nobody
    can read either way. ``withheld`` entries carry no record at all and are left alone.
    """
    entries = []
    for entry in page.get("entries") or []:
        shown = dict(entry)
        encoded = entry.get("raw")
        if encoded:
            try:
                text = base64.b64decode(encoded, validate=True).decode("utf-8", "replace")
            except (binascii.Error, ValueError):
                text = ""
            shown["text"] = text[:_RECORD_PREVIEW] + ("…" if len(text) > _RECORD_PREVIEW else "")
        else:
            shown["text"] = ""
        entries.append(shown)
    return {**page, "entries": entries}


class UIRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        page_size = self.ctx["config"].get_int("ui.page_size", 25)

        def _safe(fn, fallback):
            """The engine being unreachable is a state the page renders.

            Not an exception it raises: the bar already says the engine is down,
            and a 500 here would replace that with a stack trace that says less.
            """
            try:
                return fn()
            except ServiceError as exc:
                logger.warning("rendering without engine data: %s", exc)
                return fallback

        def _attempt(fn, fallback, what: str = ""):
            """``_safe``, keeping what failed: a Failure the page renders as its error or partial
            state (design 23.12), with the correlation id that is also on the log line."""
            try:
                return fn(), None
            except ServiceError as exc:
                return fallback, failure(exc, what=what)

        # ---------------------------------------------------------- overview
        @self.app.get("/overview", response_class=HTMLResponse, tags=["ui"])
        def overview(request: Request):
            """Is it up, what is registered, and how much of it is shared.

            Gated, unlike the landing page and the docs: a registered query's name and
            fingerprint are the engine's own data, reached with the console's one shared
            engine identity, not console-specific metadata safe to hand to anyone who can
            reach the port. Before this gate existed, an anonymous visitor saw exactly what
            an operator sees -- which is the read half of the same defect the login system
            was built to close on the write side (see routes/auth_routes.py's own account
            of why it exists).
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            busiest = _safe(lambda: services.queries.find(limit=8, sort="-rows_in").items, [])
            return self.page(request, "overview.html", current="/overview",
                             queries=busiest)

        # ----------------------------------------------------------- queries
        @self.app.get("/queries", response_class=HTMLResponse, tags=["ui"])
        def queries(request: Request, search: str = "", state: str = "",
                    sort: str = "name", offset: int = 0):
            """The list. Every filter is in the URL, so a view can be shared as it is.

            Gated for the same reason as /overview: this is which queries exist and what
            SQL they run, not console chrome.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            page, page_error = _attempt(
                lambda: services.queries.find(search=search, state=state, sort=sort,
                                              offset=offset, limit=page_size),
                None, "the query list")
            return self.page(request, "queries.html", current="/queries",
                             page=page, page_error=page_error, search=search, state_filter=state,
                             sort=sort, offset=offset, page_size=page_size)

        @self.app.get("/queries/{name}", response_class=HTMLResponse, tags=["ui"])
        def query_detail(request: Request, name: str):
            """The detail page: full SQL, fingerprint, siblings, a live tail.

            Gated -- this is the single most sensitive read the console has. The SQL text
            of a continuous query can itself be confidential (table names, join keys,
            business logic), which is exactly the "caller learning what exists" SX-5 names.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                query = services.queries.get(name)
                siblings = services.queries.siblings(name)
            except ServiceError as exc:
                return self.page(request, "not_found.html", http_status=404,
                                 current="/queries", what=self.t("not_found.what.query"), identifier=name,
                                 back_href="/queries", back_label=self.t("not_found.back.queries"),
                                 detail=str(exc))
            # The engine's description: keys by name, retention, the sink and whether it is still
            # attached. Rendered without it (older engine, HTTP port down) rather than refused.
            detail, detail_error = _attempt(lambda: services.queries.detail(name), None,
                                            "the query's description")
            # Pause, resume and drop are offered only when the engine's policy would allow them;
            # refused, they are disabled with its reason (design 23.16), not left to fail on click.
            refused = services.admin.affordances().administer_refused(name)
            return self.page(request, "query_detail.html", current="/queries",
                             query=query, siblings=siblings, detail=detail, detail_error=detail_error,
                             refused=refused)

        # ------------------------------------------------ dead letters (B5)
        @self.app.get("/queries/{name}/dead-letters", response_class=HTMLResponse, tags=["ui"])
        def dead_letters(request: Request, name: str, offset: int = 0,
                         replayed: str = "", replay_error: str = ""):
            """Screen 8: the records this query's feed could not decode.

            Gated like the query's own page, and for a stronger reason: a dead letter's
            bytes are a row of the source, un-decoded. The engine decides who may see them
            -- a caller who reads the view through a row filter is shown the counts, the
            codes and the offsets and not the records -- and this screen renders that
            refusal rather than an empty cell, so nobody reads "withheld" as "empty".

            ``replayed`` and ``replay_error`` come back from the redirect after a replay:
            redirect-after-POST, so a refresh does not put the record in a second time.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            page, page_error = _attempt(
                lambda: _readable(services.dead_letters.page(name, offset)), None,
                "the query's dead letters")
            # Replay changes the view every other reader sees, so the engine authorizes it as
            # it does drop, pause and resume. Disabled with the policy's own reason rather
            # than left to fail on click (design 23.16).
            refused = services.admin.affordances().administer_refused(name)
            return self.page(request, "dead_letters.html", current="/queries",
                             name=name, page=page or {"entries": [], "configured": True},
                             page_error=page_error, refused=refused,
                             replayed=replayed, replay_error=replay_error)

        @self.app.post("/queries/{name}/dead-letters/replay", tags=["ui"])
        # The default below is suppressed rather than changed: FastAPI reads it from the
        # signature and never mutates it, and a repeated form field that may be absent
        # cannot be declared to it any other way.
        def replay_dead_letters(request: Request, name: str,
                                ids: list[str] = Form(default=[])):  # noqa: B008
            """Feeds the chosen records back through the query.

            A new row at the query's current frontier, not a rewind, and a record that fails
            to decode again goes back on the queue rather than being retried. Named at INFO
            for the same reason a drop is: "who put that row in" is asked afterwards.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            chosen = [i for i in ids if i and i.strip()]
            if not chosen:
                # Said here rather than left to the engine: there is nothing wrong with the
                # request, the operator just has not ticked anything.
                return RedirectResponse(
                    f"/queries/{name}/dead-letters?replay_error={quote(self.t('dlq.error.none_chosen'))}",
                    status_code=303)
            logger.info("%s replayed %d dead letters on '%s'", current_user(request), len(chosen), name)
            try:
                result = services.dead_letters.replay(name, chosen)
            except ServiceError as exc:
                return RedirectResponse(
                    f"/queries/{name}/dead-letters?replay_error={quote(str(exc))}", status_code=303)
            done = int(result.get("replayed") or 0)
            again = int(result.get("failedAgain") or 0)
            return RedirectResponse(
                f"/queries/{name}/dead-letters?replayed={quote(self.t('dlq.replay_done', done=done, again=again))}",
                status_code=303)

        # ------------------------------ backfill and cutover (design 23.10, ADR-046)
        @self.app.get("/queries/{name}/replacement", response_class=HTMLResponse, tags=["ui"])
        def replacement(request: Request, name: str, acted: str = "",
                        action_error: str = "", action_code: str = ""):
            """Screen 14/15: the blue/green replacement of one query, and its backfill.

            One call renders it (ADR-046): the state, the progress and the rollback window
            are one answer, because a screen that asked separately would show three moments
            and let a reader join them up wrongly.

            **There is no ETA and no percentage on this page.** A source does not say how
            much history it holds, so there is no denominator that is not invented, and a
            bar drawn from an invented one is a promise the engine never made. What is here
            is what is measured: rows read, the rate, the partitions that have reached the
            live stream, and the candidate's lag behind the running version.

            Gated like the query's own page, and every control on it needs the administer
            permission the engine decides -- a reader sees the screen with the controls
            disabled and the policy's reason beside them (design 23.16).
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                query = services.queries.get(name)
            except ServiceError as exc:
                return self.page(request, "not_found.html", http_status=404,
                                 current="/queries", what=self.t("not_found.what.query"), identifier=name,
                                 back_href="/queries", back_label=self.t("not_found.back.queries"),
                                 detail=str(exc))
            status, status_error = _attempt(lambda: services.replacements.status(name), None,
                                            "the query's replacement")
            # The running version's own key columns, so the form that starts a replacement
            # begins from them: a candidate keyed differently is a different view, and nobody
            # means that by "a new version of this query".
            detail = _safe(lambda: services.queries.detail(name), None) or {}
            keys = [str(k.get("name")) for k in detail.get("keyColumns") or []]
            refused = services.admin.affordances().administer_refused(name)
            return self.page(request, "replacement.html", current="/queries", query=query,
                             replacement=status, replacement_error=status_error, refused=refused,
                             query_keys=keys, acted=acted, action_error=action_error,
                             action_code=action_code)

        def _replacement_redirect(name: str, message: str = "",
                                  refusal: ServiceError | None = None) -> RedirectResponse:
            """Redirect-after-POST, so a refresh does not cut over a second time.

            A refusal carries its PRV code as well as its sentence, because the code is what
            the help page is keyed by and "PRV-4014" pasted into a ticket finds the runbook
            where the sentence alone does not.
            """
            if refusal is not None:
                tail = "?action_error=" + quote(str(refusal))
                if refusal.code:
                    tail += "&action_code=" + quote(refusal.code)
            else:
                tail = f"?acted={quote(message)}" if message else ""
            return RedirectResponse(f"/queries/{name}/replacement{tail}", status_code=303)

        @self.app.post("/queries/{name}/replacement/start", tags=["ui"])
        def start_replacement(request: Request, name: str, sql: str = Form(...),
                              keys: str = Form(""), backfill: str = Form("history"),
                              rate: str = Form("")):
            """Starts a candidate beside the running version.

            The SQL is not checked here beyond being present: the planner is the engine's, and
            a console that decided what was replaceable would be a second, weaker opinion. The
            engine refuses a candidate that normalises to the computation already running
            (PRV-4017 -- a cutover to itself) and a stream whose source cannot replay
            (PRV-4018), and the screen shows those refusals with their codes.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s started replacing '%s'", current_user(request), name)
            parts = [part.strip() for part in keys.split(",") if part.strip()]
            try:
                if parts and not all(p.lstrip("-").isdigit() for p in parts):
                    ordinals, _fields = services.authoring.key_ordinals(sql, parts)
                else:
                    ordinals = [int(p) for p in parts]
                services.replacements.start(
                    name, sql, ordinals, backfill=backfill or None,
                    rate_limit=int(rate) if str(rate).strip().isdigit() and int(rate) else None)
            except ServiceError as exc:
                return _replacement_redirect(name, refusal=exc)
            except ValueError:
                return _replacement_redirect(
                    name, refusal=ServiceError(self.t("cutover.error.keys", keys=keys), 400))
            return _replacement_redirect(name, message=self.t("cutover.done.start", name=name))

        @self.app.post("/queries/{name}/replacement/throttle", tags=["ui"])
        def throttle_backfill(request: Request, name: str, rate: str = Form("")):
            """Lowers the backfill's ceiling. The engine refuses a raise above the one it was
            started with, and the console sends what was typed rather than clamping it: a
            silently altered number is worse than the engine's own refusal."""
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s throttled the backfill of '%s' to %s", current_user(request), name, rate)
            try:
                asked = int(str(rate).strip() or "0")
            except ValueError:
                return _replacement_redirect(
                    name, refusal=ServiceError(self.t("cutover.error.rate", rate=rate), 400))
            try:
                services.replacements.throttle(name, asked)
            except ServiceError as exc:
                return _replacement_redirect(name, refusal=exc)
            return _replacement_redirect(name, message=self.t("cutover.done.throttle", rate=asked))

        @self.app.post("/queries/{name}/replacement/{action}", tags=["ui"])
        def act_on_replacement(request: Request, name: str, action: str):
            """Cutover, rollback, finish, abandon, pause, resume -- as ordinary form posts.

            Cutover and rollback move what every reader of the name sees, so each is
            confirmed by the typed name, by the same pair of controls the drop button uses:
            a plain form that works with no script at all, and a typed-confirmation dialog
            ``replacement.js`` swaps in once it can run one. The confirmation is the
            dialog's, as drop's is, because ``confirm()`` is JavaScript too and a form that
            demanded a token only a script can supply would work for nobody without one.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s requested %s on the replacement of '%s'",
                        current_user(request), action, name)
            try:
                services.replacements.act(name, action)
            except ServiceError as exc:
                return _replacement_redirect(name, refusal=exc)
            done = {"cutover": "cutover", "rollback": "rollback", "finish": "finish",
                    "abandon": "abandon", "pause": "pause", "resume": "resume"}[action]
            return _replacement_redirect(name, message=self.t(f"cutover.done.{done}", name=name))

        # ------------------------------ the time-travel debugger (design 23.9, ADR-048)

        def _debug_page(request: Request, name: str, session_id: str, *,
                        operator: str = "", key: str = "", offset: int = 0,
                        step: dict | None = None, fixture: dict | None = None,
                        acted: str = "", action_error: str = "", action_code: str = "",
                        http_status: int = 200):
            """Screen 10, built from whatever of the session the engine answered.

            One page, several calls, and that is not the compromise it is on the replacement
            screen: a session does not move unless somebody steps it, so the status, the
            operator state and the view are the same moment however many calls read them.
            Nothing here is polled for the same reason -- there is nothing to poll.
            """
            try:
                query = services.queries.get(name)
            except ServiceError as exc:
                return self.page(request, "not_found.html", http_status=404,
                                 current="/queries", what=self.t("not_found.what.query"),
                                 identifier=name, back_href="/queries",
                                 back_label=self.t("not_found.back.queries"), detail=str(exc))
            refused = services.admin.affordances().administer_refused(name)
            # Everything on this screen takes the administer permission, reading included:
            # a fork exposes the query's SQL, its input rows and its operator state (ADR-048
            # 7). So a refused identity is shown the screen and its reason, and the console
            # does not ask the engine for a checkpoint list it would refuse anyway.
            session = slots = changes = page = None
            session_error = slots_error = changes_error = page_error = None
            if session_id and not refused:
                session, session_error = _attempt(
                    lambda: services.debug.session(session_id), None, "this debug session")
            # Only asked for when there is no session on screen: a reader looking at one does
            # not need a list of the others, and a fork is expensive enough that the engine
            # should not be asked twice for a page that will not draw it.
            ask_around = not refused and session is None and session_error is None
            checkpoints, checkpoints_error = _attempt(
                lambda: services.debug.checkpoints(name), [], "this query's checkpoints"
            ) if ask_around else ([], None)
            open_sessions, _ = _attempt(
                lambda: [s for s in services.debug.sessions() if s.get("query") == name], []
            ) if ask_around else ([], None)
            if session is not None:
                slots, slots_error = _attempt(
                    lambda: services.debug.state(session_id), None, "the operator state")
                changes, changes_error = _attempt(
                    lambda: services.debug.view(session_id), None, "the fork's view")
                if operator:
                    page, page_error = _attempt(
                        lambda: services.debug.inspect(session_id, operator, key=key or None,
                                                       offset=offset),
                        None, f"the state of {operator}")
            return self.page(request, "debug.html", current="/queries", query=query,
                             http_status=http_status, refused=refused,
                             checkpoints=checkpoints, checkpoints_error=checkpoints_error,
                             open_sessions=open_sessions, session=session,
                             session_id=session_id, session_error=session_error,
                             slots=slots, slots_error=slots_error,
                             changes=changes, changes_error=changes_error,
                             operator=operator, key=key, offset=offset,
                             page=page, page_error=page_error, step=step, fixture=fixture,
                             acted=acted, action_error=action_error, action_code=action_code)

        @self.app.get("/queries/{name}/debug", response_class=HTMLResponse, tags=["ui"])
        def debugger(request: Request, name: str, session: str = "", operator: str = "",
                     key: str = "", offset: int = 0, acted: str = "",
                     action_error: str = "", action_code: str = ""):
            """The debugger for one query: fork it from a checkpoint and step the fork.

            The session id is in the URL and nowhere else. The engine owns the session, so a
            reload, a second tab and a link pasted into a ticket all reach the same one, and
            a browser closed without ending it leaves the console nothing to tidy -- the
            node's own TTL releases it (``pravaha.debug.session.ttl``).
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            return _debug_page(request, name, session, operator=operator, key=key,
                               offset=offset, acted=acted, action_error=action_error,
                               action_code=action_code)

        def _debug_redirect(name: str, session_id: str = "", message: str = "",
                            refusal: ServiceError | None = None) -> RedirectResponse:
            parts = []
            if session_id:
                parts.append("session=" + quote(session_id))
            if refusal is not None:
                parts.append("action_error=" + quote(str(refusal)))
                if refusal.code:
                    parts.append("action_code=" + quote(refusal.code))
            elif message:
                parts.append("acted=" + quote(message))
            tail = ("?" + "&".join(parts)) if parts else ""
            return RedirectResponse(f"/queries/{name}/debug{tail}", status_code=303)

        @self.app.post("/queries/{name}/debug/fork", tags=["ui"])
        def fork_for_debugging(request: Request, name: str, checkpoint: str = Form("")):
            """Starts a session. Redirect-after-POST, so a refresh does not fork twice --
            and forking twice is not harmless: each session is a whole second copy of the
            query, and four of them is the default ceiling."""
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s forked '%s' for debugging", current_user(request), name)
            asked = str(checkpoint).strip()
            if asked and not asked.lstrip("-").isdigit():
                return _debug_redirect(name, refusal=ServiceError(
                    self.t("debug.error.checkpoint", checkpoint=asked), 400))
            try:
                session = services.debug.fork(name, int(asked) if asked else None)
            except ServiceError as exc:
                return _debug_redirect(name, refusal=exc)
            return _debug_redirect(name, session_id=str(session.get("id") or ""),
                                   message=self.t("debug.done.fork", id=session.get("id")))

        @self.app.post("/queries/{name}/debug/step", tags=["ui"])
        def step_the_fork(request: Request, name: str, session: str = Form(...),
                          step: str = Form("")):
            """Advances the fork, and answers with the page carrying the step's report.

            The one place on this console that does not redirect after a POST, because the
            report *is* the answer: rows in, what every operator did, what the view did and
            where event time stands. A redirect would throw it away, and the engine has no
            call that hands back the step it has already taken.

            What a redirect buys elsewhere is that a refresh does not repeat the action. A
            repeated step is the cheapest mistake on this screen: nothing outside the fork
            can be reached by it (ADR-048 1), so it costs one row of the session's budget
            and the reader steps again on purpose. The island below makes the question moot
            for anyone whose browser runs it -- it steps without navigating at all.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s stepped debug session %s (%s)", current_user(request), session, step)
            try:
                report = services.debug.step(session, step)
            except ServiceError as exc:
                return _debug_page(request, name, session, action_error=str(exc),
                                   action_code=exc.code or "", http_status=status_for(exc))
            return _debug_page(request, name, session, step=report)

        @self.app.post("/queries/{name}/debug/fixture", tags=["ui"])
        def export_the_fixture(request: Request, name: str, session: str = Form(...),
                               fixture: str = Form("")):
            """Exports the session as a JUnit fixture and shows the source it generated.

            Rendered rather than redirected for the same reason a step is: the generated
            file is the answer, and the console writes nothing to disk -- the file belongs
            in the repository this engine is built from, not on the machine the browser
            happens to be on.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s exported a fixture from debug session %s", current_user(request), session)
            try:
                made = services.debug.export(session, fixture)
            except ServiceError as exc:
                return _debug_page(request, name, session, action_error=str(exc),
                                   action_code=exc.code or "", http_status=status_for(exc))
            return _debug_page(request, name, session, fixture=made)

        @self.app.post("/queries/{name}/debug/end", tags=["ui"])
        def end_the_session(request: Request, name: str, session: str = Form(...)):
            """Releases the second copy of the query. Nothing read it and nothing wrote."""
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s ended debug session %s", current_user(request), session)
            try:
                services.debug.end(session)
            except ServiceError as exc:
                return _debug_redirect(name, session_id=session, refusal=exc)
            return _debug_redirect(name, message=self.t("debug.done.end", id=session))

        # The lifecycle actions as ordinary form posts. The module intercepts
        # them so the page does not reload, but they work without it: a control
        # that only exists once a script has run is not one an operator can rely
        # on when something on the page has already thrown.
        @self.app.post("/queries/{name}/{action}", tags=["ui"])
        def act(request: Request, name: str, action: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            # Named, and at INFO, because "who dropped it" is the question asked after a
            # query disappears and there was previously nothing that could answer it.
            logger.info("%s requested %s on '%s'", current_user(request), action, name)
            try:
                services.queries.act(name, action)
            except ServiceError as exc:
                return self.page(request, "refused.html", http_status=400,
                                 current="/queries", what=self.t("refused.what.action", action=action, name=name),
                                 detail=str(exc), code=exc.code or "",
                                 back_href=f"/queries/{name}", back_label=self.t("not_found.back.query"))
            # Drop removes the thing this page was about, so it returns to the
            # list; the others come back here. Redirect-after-POST either way, so
            # a refresh does not repeat the action.
            return RedirectResponse("/queries" if action == "drop" else f"/queries/{name}",
                                    status_code=303)

        # ------------------------------------------------ the component gallery
        gallery = self.ctx["config"].get_bool("ui.component_gallery", False)

        @self.app.get("/_components", response_class=HTMLResponse, tags=["ui"])
        def components(request: Request):
            """Every design-system component, and the eight states of design 23.12, on one page.

            The no-build stand-in for Storybook (23.20): the console renders it with its own
            templates, tokens and ``states.js``, so what is reviewed here is what the screens
            use, and the browser tests audit and photograph it like any screen. A development
            aid -- off unless ``ui.component_gallery`` is set, when it is a 404 that does not
            say it exists -- and behind the sign-in when on.
            """
            if not gallery:
                return self.page(request, "not_found.html", http_status=404, current="",
                                 what=self.t("not_found.what.page"), identifier="/_components",
                                 back_href="/", back_label=self.t("not_found.back.start"), detail="")
            if (refusal := login_required(request)) is not None:
                return refusal
            return self.page(request, "components.html", current="/_components")

        # --------------------------------------------------------- workbench
        @self.app.get("/workbench", response_class=HTMLResponse, tags=["ui"])
        def workbench(request: Request, query: str = "", sql: str = "", template: str = "",
                      stream: str = ""):
            """The SQL Workbench: the analyst's landing (design 23.7).

            Gated now, because it renders the catalog -- which streams exist and what is in
            them -- and prefills from a registered query's own SQL. ``?query=`` opens a
            registered query, ``?sql=`` a piece of SQL, ``?template=`` a library template
            written against ``?stream=``.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            from core import authoring

            streams = _safe(services.catalog.streams, [])
            prefill, origin = sql, ""
            if query:
                found = _safe(lambda: services.queries.get(query), None)
                if found is not None:
                    prefill, origin = found.sql, query
            elif template:
                chosen = next((s for s in streams if s.get("name") == stream), None)
                for item in authoring.templates(chosen or (streams[0] if streams else None)):
                    if item["id"] == template:
                        prefill = item["sql"]
            return self.page(request, "workbench.html", current="/workbench",
                             result=None, sql=prefill, params="", origin=origin,
                             streams=streams, sinks=services.catalog.sinks_or_empty(),
                             library=authoring.templates(streams[0] if streams else None),
                             register_refused=services.admin.affordances().register_refused())

        @self.app.post("/workbench", response_class=HTMLResponse, tags=["ui"])
        def run(request: Request, sql: str = Form(...), params: str = Form("")):
            # A read, but it reaches the engine as this deployment's principal, so it is
            # gated too. An unauthenticated visitor should not be able to use the console
            # as a free query endpoint against data they cannot otherwise reach.
            if (refusal := login_required(request)) is not None:
                return refusal
            values = [_typed(part) for part in params.split(",") if part.strip()]
            from core import authoring

            streams = _safe(services.catalog.streams, [])
            library = authoring.templates(streams[0] if streams else None)
            try:
                result = services.adhoc.run(sql, values or None)
            except ServiceError as exc:
                return self.page(request, "workbench.html", http_status=400,
                                 current="/workbench", result=None, sql=sql,
                                 params=params, error=str(exc), code=exc.code or "",
                                 origin="", streams=streams, library=library)
            return self.page(request, "workbench.html", current="/workbench",
                             result=result, sql=sql, params=params, origin="",
                             streams=streams, library=library)

        @self.app.post("/queries", tags=["ui"])
        def register_query(request: Request, name: str = Form(...), sql: str = Form(...),
                           keys: str = Form("0"), sink: str = Form(""), retention: str = Form("")):
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s registered '%s'", current_user(request), name)
            # Keys by ordinal ("0,1") or by name ("user_id, window_end"): a name is mapped
            # against the schema the engine validated, which is what the workbench does too.
            parts = [part.strip() for part in keys.split(",") if part.strip()]
            try:
                if parts and not all(p.lstrip("-").isdigit() for p in parts):
                    ordinals, _fields = services.authoring.key_ordinals(sql, parts)
                else:
                    ordinals = [int(p) for p in parts]
                services.queries.register(name, sql, ordinals, sink=sink or None,
                                          retention=retention or None)
            except ServiceError as exc:
                return self.page(request, "refused.html", http_status=400,
                                 current="/workbench", what=self.t("refused.what.register", name=name),
                                 detail=str(exc), code=exc.code or "",
                                 back_href="/workbench", back_label=self.t("not_found.back.workbench"))
            return RedirectResponse(f"/queries/{name}", status_code=303)
