"""The console application: the shell, the screens, and the services under them.

Three layers, and the separation is ADR-033 rather than taste.

* ``services`` — typed calls, no HTTP, no HTML. Stateless, so the console scales sideways.
* ``api`` — versioned JSON at ``/api/v1``. Everything the browser can do goes through it.
* ``ui`` — the server-rendered shell, made live by ``static/app.js`` against that API.

The help pages are the fourth thing and sit slightly apart: they render the repository's
own ``docs/`` set, so the quick start and the case studies are reachable from the console
rather than living only in a checkout somebody may not have.
"""
from __future__ import annotations

import html
from pathlib import Path

from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles

from pravaha_console import docs, ui
from pravaha_console.api import api_router
from pravaha_console.engine import Engine
from pravaha_console.services import ServiceError, Services

STATIC = Path(__file__).parent / "static"


def _coerce(value: str) -> object:
    """Turns a typed parameter into the value it obviously is.

    Only the unambiguous cases. Anything else stays a string, because guessing wrongly here
    turns a comparison that would have failed loudly into one that quietly matches nothing.
    """
    for cast in (int, float):
        try:
            return cast(value)
        except ValueError:
            continue
    return value


def create_app(engine: Engine) -> FastAPI:
    services = Services(engine)
    app = FastAPI(title="Pravaha console", docs_url="/api/docs")
    app.include_router(api_router(services))
    app.mount("/static", StaticFiles(directory=str(STATIC)), name="static")

    def health_now():
        return services.health.health()

    @app.get("/health")
    def health() -> JSONResponse:
        # 200 even when the engine is down. This endpoint answers "is the console up", and
        # a 503 here would have a monitoring system report the console as broken when it is
        # working correctly and reporting accurately that something else is not.
        return JSONResponse(health_now().as_dict())

    def _queries_or_empty(**kwargs):
        """The current list, or nothing if the engine will not answer.

        Unreachable is a state the page renders, not an exception it raises: the banner in
        the shell already says the engine is down, and a 500 here would replace that with a
        stack trace that says less.
        """
        try:
            return services.queries.find(**kwargs).items
        except ServiceError:
            return []

    @app.get("/", response_class=HTMLResponse)
    def index() -> HTMLResponse:
        state = health_now()
        rows = _queries_or_empty(limit=8, sort="-rows_in") if state.reachable else []
        body = ui.overview(state, rows) + ui.help_cards("/")
        return ui.shell(body, title="Overview", current="/", health=state)

    @app.get("/queries", response_class=HTMLResponse)
    def queries() -> HTMLResponse:
        state = health_now()
        body = ui.queries_screen(_queries_or_empty(limit=25)) + ui.help_cards("/queries")
        return ui.shell(body, title="Queries", current="/queries", health=state)

    @app.get("/queries/{name}", response_class=HTMLResponse)
    def query_detail(name: str) -> HTMLResponse:
        state = health_now()
        try:
            query = services.queries.get(name)
            siblings = services.queries.siblings(name)
            error = None
        except ServiceError as exc:
            query, siblings, error = None, [], str(exc)
        body = ui.query_detail(name, query, siblings, error) + ui.help_cards("/queries")
        return ui.shell(body, title=name, current="/queries", health=state)

    @app.get("/workbench", response_class=HTMLResponse)
    def workbench() -> HTMLResponse:
        body = ui.workbench() + ui.help_cards("/workbench")
        return ui.shell(body, title="Workbench", current="/workbench", health=health_now())

    # ---- The same actions, as ordinary form posts ---------------------------------------
    #
    # The module intercepts these so the page does not reload, but they work without it.
    # "Every workflow completable by keyboard alone" is a design 23.20 requirement, and a
    # control that only exists once a script has run is not a control an operator can rely
    # on when something on the page has thrown.

    @app.post("/queries")
    def register_form(name: str = Form(...), sql: str = Form(...), keys: str = Form("0")):
        ordinals = [int(part.strip()) for part in keys.split(",") if part.strip()]
        try:
            services.queries.register(name, sql, ordinals)
        except ServiceError as exc:
            body = (
                f'<h1>Register</h1><div class="banner error" role="alert"><div class="body">'
                f'<div class="title">{html.escape(str(exc))}</div>'
                f'<div><a href="/workbench">Back to the workbench</a></div></div></div>'
            )
            return ui.shell(body, title="Register", current="/workbench", health=health_now())
        return RedirectResponse(f"/queries/{name}", status_code=303)

    @app.post("/queries/{name}/{action}")
    def act_form(name: str, action: str):
        try:
            services.queries.act(name, action)
        except ServiceError as exc:
            body = (
                f'<h1>{html.escape(name)}</h1><div class="banner error" role="alert"><div class="body">'
                f'<div class="title">{html.escape(str(exc))}</div>'
                f'<div><a href="/queries">All queries</a></div></div></div>'
            )
            return ui.shell(body, title=name, current="/queries", health=health_now())
        # Drop removes the thing this page was about, so it returns to the list; the others
        # come back here, which is the POST-redirect-GET that stops a refresh repeating it.
        return RedirectResponse("/queries" if action == "drop" else f"/queries/{name}", status_code=303)

    # ---- Help: the repository's documentation, rendered ---------------------------------

    @app.get("/help", response_class=HTMLResponse)
    def help_index() -> HTMLResponse:
        pages = docs.available()
        studies = docs.available_studies()
        if not pages:
            body = """<h1>Help</h1>
              <div class="banner warn"><div class="body">
              <div class="title">The documentation is not next to this console</div>
              <div>It ships in the repository under <code>docs/</code>. An installed copy
              outside the repository will not find it, which is this.</div></div></div>"""
            return ui.shell(body, title="Help", current="/help", health=health_now())

        guides = "".join(
            f'<tr><td><a href="/help/{name}">{html.escape(title)}</a></td>'
            f'<td class="subtitle" style="margin:0">{html.escape(blurb)}</td></tr>'
            for name, title, blurb in pages
        )
        cases = "".join(
            f'<tr><td><a href="/help/study/{name}">{html.escape(title)}</a></td>'
            f'<td class="subtitle" style="margin:0">{html.escape(blurb)}</td></tr>'
            for name, title, blurb in studies
        )
        body = f"""<h1>Help</h1>
          <p class="subtitle">The documentation that ships with this engine, rendered here so it
          is reachable from the console rather than only from a checkout.</p>
          <h2>Guides</h2>
          <div class="card"><table><tbody>{guides}</tbody></table></div>
          {'<h2>Case studies — worked systems</h2><div class="card"><table><tbody>' + cases + '</tbody></table></div>' if cases else ''}"""
        return ui.shell(body, title="Help", current="/help", health=health_now())

    @app.get("/help/study/{name}", response_class=HTMLResponse)
    def help_study(name: str) -> HTMLResponse:
        text = docs.load_study(name)
        if text is None:
            # A 404, not a 200 with an apology in it. The allow-list refused the name, and
            # saying "not found" is both true and what a client can act on.
            page = ui.shell(
                '<h1>Help</h1><div class="banner error"><div class="body">'
                '<div class="title">There is no such case study</div>'
                '<div><a href="/help">All guides</a></div></div></div>',
                title="Help",
                current="/help",
                health=health_now(),
            )
            return HTMLResponse(page.body, status_code=404)
        body = ('<p class="subtitle"><a href="/help">&larr; All guides</a></p>'
                '<article class="card"><div class="card-body">' + docs.render_markdown(text) + "</div></article>")
        return ui.shell(body, title=name, current="/help", health=health_now())

    @app.get("/help/{name}", response_class=HTMLResponse)
    def help_page(name: str) -> HTMLResponse:
        text = docs.load(name)
        if text is None:
            page = ui.shell(
                '<h1>Help</h1><div class="banner error"><div class="body">'
                '<div class="title">There is no such page</div>'
                '<div><a href="/help">All guides</a></div></div></div>',
                title="Help",
                current="/help",
                health=health_now(),
            )
            return HTMLResponse(page.body, status_code=404)
        body = ('<p class="subtitle"><a href="/help">&larr; All guides</a></p>'
                '<article class="card"><div class="card-body">' + docs.render_markdown(text) + "</div></article>")
        return ui.shell(body, title=name, current="/help", health=health_now())

    # ---- Compatibility -------------------------------------------------------------------

    @app.get("/query")
    def query_redirect() -> RedirectResponse:
        # The ad-hoc page moved into the workbench, which does the same and more. Redirected
        # rather than removed: somebody has this bookmarked.
        return RedirectResponse("/workbench", status_code=307)

    @app.post("/query", response_class=HTMLResponse)
    def run_form(sql: str = Form(...), params: str = Form("")):
        """The workbench's run, as an ordinary form post.

        Values are coerced the same way the browser coerces them: a parameter is a value,
        and sending "40" where the column is an integer compares a string to a number and
        silently matches nothing (ADR-032).
        """
        values = [_coerce(part.strip()) for part in params.split(",") if part.strip()]
        try:
            result = services.adhoc.run(sql, values or None)
        except ServiceError as exc:
            body = (
                '<h1>Workbench</h1><div class="banner error" role="alert"><div class="body">'
                f'<div class="title">{html.escape(str(exc))}</div>'
                '<div><a href="/workbench">Back to the workbench</a></div></div></div>'
            ) + ui.help_cards("/workbench")
            return ui.shell(body, title="Workbench", current="/workbench", health=health_now())

        head = "".join(f"<th>{html.escape(c)}</th>" for c in result["columns"])
        rows = "".join(
            "<tr>" + "".join(f"<td>{html.escape(str(v))}</td>" for v in row) + "</tr>"
            for row in result["rows"]
        )
        body = (
            f'<h1>Workbench</h1><p class="subtitle">{result["returned"]} rows in '
            f'{result["took_ms"]}ms. <a href="/workbench">Ask another</a></p>'
            f'<div class="card"><table><thead><tr>{head}</tr></thead><tbody>{rows}</tbody></table></div>'
        ) + ui.help_cards("/workbench")
        return ui.shell(body, title="Workbench", current="/workbench", health=health_now())

    return app
