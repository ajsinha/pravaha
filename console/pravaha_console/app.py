"""The console: a FastAPI process that talks to Pravaha through the published SDK.

**This is a functional admin console, on purpose.** The implementation plan is explicit
that without a dedicated frontend engineer the console degrades to exactly that, and
says so is a legitimate trade to make deliberately rather than by accident. So: server-
rendered HTML, no build step, no framework, no design system. It shows what an operator
needs to see and lets them do what an operator needs to do.

What it deliberately does surface, because nothing else does:

* which registrations are **shared** -- two names on one fingerprint are one computation
  with one copy of the state, and "ten analysts on one dashboard cost one query" is a
  claim worth being able to watch holding;
* a **live tail** of a view rather than a poll, because the product's whole argument is
  that the answer does not have to be a little out of date.
"""
from __future__ import annotations

import argparse
import html
import json
from typing import Optional

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, StreamingResponse

from pravaha_console import docs
from pravaha_console.engine import Engine

#: Contextual help, per page. Short enough to read without leaving the task, and each one links
#: to the document that says the rest. An operator who has to go and find a wiki has already lost
#: the thread, and usually the question.
HELP_CARDS: dict[str, list[tuple[str, str, str]]] = {
    "index": [
        (
            "A registration is a computation, not a request",
            "It keeps running and keeps its view current until somebody drops it. Registering is the "
            "expensive act; querying the view afterwards is a hash probe.",
            "CONCEPTS.md",
        ),
        (
            "Two names, one fingerprint, one copy of the state",
            "The same question registered twice -- even worded differently -- is one computation. Rows "
            "marked <em>shared</em> below are where that is happening.",
            "CONCEPTS.md",
        ),
        (
            "A view needs a key",
            "Key columns are ordinals into the query's output. A view with no key is a log, and a point "
            "read against it has nothing to look up.",
            "USER_GUIDE.md",
        ),
    ],
    "detail": [
        (
            "Nothing appearing is usually correct",
            "A window closes when <em>data</em> says it is over, not when the clock does, and a "
            "subscription starts from now rather than the beginning of time.",
            "CONCEPTS.md",
        ),
        (
            "Pausing is not stopping",
            "A paused query keeps answering at the frontier it reached and stops advancing. Rows "
            "arriving while paused are dropped, not buffered -- a pause is meant to stop it doing work.",
            "USER_GUIDE.md",
        ),
        (
            "Dropping removes a name, not always the computation",
            "It is released when its <em>last</em> name goes. If somebody else registered the same "
            "question, yours going leaves theirs running.",
            "CONCEPTS.md",
        ),
    ],
    "ask": [
        (
            "Bind values; never build the string",
            "A bound value is never parsed as SQL -- by the time it reaches the server the statement is "
            "already planned. The server also plans it once however many values you ask about.",
            "USER_GUIDE.md",
        ),
        (
            "Not every SQL construct runs here",
            "No ORDER BY, LIMIT, CASE or LIKE; no outer or self joins between streams. The full list is "
            "checked by a test rather than written from memory.",
            "SQL_SUPPORT.md",
        ),
        (
            "= NULL matches nothing",
            "Three-valued logic: a comparison with NULL is UNKNOWN, so no row passes. IS NULL is what "
            "finds the empty ones.",
            "TROUBLESHOOTING.md",
        ),
    ],
}


def help_cards(page: str) -> str:
    cards = HELP_CARDS.get(page, [])
    if not cards:
        return ""
    items = "".join(
        f"<details class='help'><summary>{html.escape(title)}</summary>"
        f"<p>{body}</p><p><a href='/help/{doc}'>read more &rarr;</a></p></details>"
        for title, body, doc in cards
    )
    return f"<section class='helpcards'><h3>Help</h3>{items}</section>"

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>Pravaha console</title>
<style>
 body {{ font: 14px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; margin: 2rem; max-width: 70rem;
        color: #111; background: #fff; }}
 h1 {{ font-size: 1.1rem; letter-spacing: .02em; }}
 h1 small {{ font-weight: normal; color: #666; }}
 table {{ border-collapse: collapse; width: 100%; margin: 1rem 0; }}
 th, td {{ text-align: left; padding: .4rem .6rem; border-bottom: 1px solid #e5e5e5; vertical-align: top; }}
 th {{ color: #666; font-weight: 600; border-bottom: 1px solid #bbb; }}
 .muted {{ color: #777; }}
 .bad {{ color: #a00; }}
 .ok {{ color: #060; }}
 .pill {{ background: #eef; border: 1px solid #ccd; border-radius: 10px; padding: 0 .4rem; font-size: .85em; }}
 form {{ margin: 1rem 0; }}
 textarea {{ width: 100%; height: 7rem; font: inherit; }}
 input[type=text] {{ font: inherit; padding: .2rem; }}
 button {{ font: inherit; padding: .2rem .6rem; }}
 pre {{ background: #f7f7f7; padding: .6rem; overflow-x: auto; }}
 nav a {{ margin-right: 1rem; }}
 .helpcards {{ margin-top: 2.5rem; border-top: 1px solid #e5e5e5; padding-top: .5rem; }}
 .helpcards h3 {{ font-size: .85rem; text-transform: uppercase; letter-spacing: .08em; color: #888; }}
 details.help {{ border: 1px solid #e5e5e5; border-left: 3px solid #ccd; padding: .4rem .7rem;
                 margin: .4rem 0; background: #fcfcfd; }}
 details.help summary {{ cursor: pointer; font-weight: 600; }}
 details.help p {{ margin: .5rem 0 0; color: #444; }}
 .doc h2 {{ margin-top: 1.6rem; }}
 .doc table {{ font-size: .95em; }}
 .doc blockquote {{ border-left: 3px solid #ccd; margin: .8rem 0; padding: .2rem .9rem;
                    background: #fafaff; color: #333; }}
 .doc code {{ background: #f2f2f4; padding: 0 .2em; }}
</style></head><body>
<h1>Pravaha console <small>{engine}</small></h1>
<nav><a href="/">queries</a><a href="/query">ask</a><a href="/help">help &amp; guides</a><a href="/health">health</a></nav>
{body}
</body></html>"""


def render(engine_url: str, body: str) -> HTMLResponse:
    return HTMLResponse(PAGE.format(engine=html.escape(engine_url), body=body))


def create_app(engine: Engine) -> FastAPI:
    app = FastAPI(title="Pravaha console", docs_url="/api/docs")

    @app.get("/health")
    def health() -> JSONResponse:
        state = engine.health()
        # 200 when the console is up and the engine is not, because the console's own health
        # is the question this endpoint answers. The payload says what it found.
        return JSONResponse(state)

    @app.get("/", response_class=HTMLResponse)
    def index() -> HTMLResponse:
        state = engine.health()
        if not state["reachable"]:
            return render(
                engine.url,
                "<p class='bad'>the engine is not reachable</p><pre>{}</pre>"
                "<p class='muted'>The console is running; the engine it was pointed at is not "
                "answering. Nothing below would be true, so nothing is shown.</p>".format(
                    html.escape(str(state.get("error", "")))
                ),
            )

        rows = engine.queries()
        if rows:
            body = ["<table><tr><th>name</th><th>state</th><th>rows in</th><th>computation</th><th></th></tr>"]
            for row in rows:
                shared = (
                    f"<span class='pill' title='another name shares this computation'>"
                    f"shared {html.escape(row.fingerprint)}</span>"
                    if row.shared
                    else f"<span class='muted'>{html.escape(row.fingerprint)}</span>"
                )
                state_class = "ok" if row.state == "RUNNING" else "bad" if row.state == "FAILED" else "muted"
                body.append(
                    f"<tr><td><a href='/queries/{html.escape(row.name)}'>{html.escape(row.name)}</a></td>"
                    f"<td class='{state_class}'>{html.escape(row.state)}</td>"
                    f"<td>{row.rows_in}</td><td>{shared}</td>"
                    f"<td><form method='post' action='/queries/{html.escape(row.name)}/drop' "
                    f"style='margin:0'><button>drop</button></form></td></tr>"
                )
            body.append("</table>")
        else:
            body = ["<p class='muted'>no continuous queries are registered</p>"]

        body.append(
            "<h2>register</h2><form method='post' action='/queries'>"
            "<p><input type='text' name='name' placeholder='view name' required> "
            "<input type='text' name='keys' value='0' size='6' title='key column ordinals'></p>"
            "<p><textarea name='sql' placeholder='SELECT ... FROM ...' required></textarea></p>"
            "<button>register</button></form>"
            "<p class='muted'>A registration runs until it is dropped. The same question registered "
            "twice is one computation with two names &mdash; the fingerprint column is how you see it.</p>"
        )
        body.append(help_cards("index"))
        return render(engine.url, "".join(body))

    @app.post("/queries")
    def register(name: str = Form(...), sql: str = Form(...), keys: str = Form("0")):
        try:
            ordinals = [int(part.strip()) for part in keys.split(",") if part.strip()]
            engine.register(name, sql, ordinals)
        except Exception as exc:
            return render(engine.url, f"<p class='bad'>{html.escape(str(exc))}</p><p><a href='/'>back</a></p>")
        return RedirectResponse("/", status_code=303)

    @app.post("/queries/{name}/{action}")
    def lifecycle(name: str, action: str):
        try:
            engine.lifecycle(action, name)
        except Exception as exc:
            return render(engine.url, f"<p class='bad'>{html.escape(str(exc))}</p><p><a href='/'>back</a></p>")
        return RedirectResponse("/", status_code=303)

    @app.get("/queries/{name}", response_class=HTMLResponse)
    def detail(name: str) -> HTMLResponse:
        rows = [row for row in engine.queries() if row.name == name]
        if not rows:
            return render(engine.url, f"<p class='bad'>no query named {html.escape(name)}</p>")
        row = rows[0]
        body = [
            f"<h2>{html.escape(row.name)} <span class='muted'>{html.escape(row.state)}</span></h2>",
            f"<pre>{html.escape(row.sql)}</pre>",
            f"<p class='muted'>fingerprint {html.escape(row.fingerprint)} &middot; {row.rows_in} rows in"
            + (" &middot; <span class='pill'>shared with another name</span>" if row.shared else "")
            + "</p>",
            "<h3>live</h3>",
            f"<pre id='tail' class='muted'>waiting for the next commit&hellip;</pre>",
            # A tail rather than a poll: the product's argument is that the answer does not
            # have to be out of date, and a console that polled would undercut it on its own
            # front page.
            "<script>"
            f"const s = new EventSource('/queries/{html.escape(row.name)}/tail');"
            "const p = document.getElementById('tail'); let n = 0;"
            "s.onmessage = e => { if (n++ === 0) p.textContent = ''; "
            "p.textContent = e.data + '\\n' + p.textContent; p.className = ''; };"
            "s.onerror = () => { p.className = 'bad'; };"
            "</script>",
            "<p><form method='post' action='/queries/"
            + html.escape(row.name)
            + "/pause' style='display:inline'><button>pause</button></form> "
            "<form method='post' action='/queries/"
            + html.escape(row.name)
            + "/resume' style='display:inline'><button>resume</button></form></p>",
        ]
        body.append(help_cards("detail"))
        return render(engine.url, "".join(body))

    @app.get("/queries/{name}/tail")
    def tail(name: str) -> StreamingResponse:
        def events():
            try:
                for row in engine.tail(name):
                    yield f"data: {json.dumps(row, default=str)}\n\n"
            except Exception as exc:
                yield f"event: error\ndata: {json.dumps(str(exc))}\n\n"

        return StreamingResponse(events(), media_type="text/event-stream")

    @app.get("/help", response_class=HTMLResponse)
    def help_index() -> HTMLResponse:
        pages = docs.available()
        if not pages:
            return render(
                engine.url,
                "<h2>help</h2><p class='muted'>The documentation is not next to this console. It ships "
                "in the repository under <code>docs/</code>; an installed copy outside the repository "
                "has no access to it.</p>",
            )
        rows = "".join(
            f"<tr><td><a href='/help/{name}'>{html.escape(title)}</a></td>"
            f"<td class='muted'>{html.escape(blurb)}</td></tr>"
            for name, title, blurb in pages
        )
        return render(
            engine.url,
            "<h2>help &amp; guides</h2><table>" + rows + "</table>"
            "<p class='muted'>Rendered from the repository's own documentation rather than a copy, so "
            "it cannot drift from the pages the build checks.</p>",
        )

    @app.get("/help/{name}", response_class=HTMLResponse)
    def help_page(name: str) -> HTMLResponse:
        text = docs.load(name)
        if text is None:
            return render(
                engine.url,
                "<h2>help</h2><p class='bad'>no such page</p><p><a href='/help'>all guides</a></p>",
            )
        return render(
            engine.url,
            "<p class='muted'><a href='/help'>&larr; all guides</a></p>"
            "<article class='doc'>" + docs.render_markdown(text) + "</article>",
        )

    @app.get("/query", response_class=HTMLResponse)
    def ask_form() -> HTMLResponse:
        return render(
            engine.url,
            "<h2>ask</h2><form method='post' action='/query'>"
            "<p><textarea name='sql' placeholder='SELECT ... FROM view WHERE col = ?'></textarea></p>"
            "<p><input type='text' name='params' placeholder='comma-separated values for ?' size='40'></p>"
            "<button>run</button></form>"
            "<p class='muted'>Values are bound, never pasted into the SQL. A bound value is never "
            "parsed as SQL, and the server plans the statement once however many you ask about.</p>"
            + help_cards("ask"),
        )

    @app.post("/query", response_class=HTMLResponse)
    def ask(sql: str = Form(...), params: str = Form("")) -> HTMLResponse:
        values = [coerce(part.strip()) for part in params.split(",") if part.strip()]
        try:
            columns, rows = engine.query(sql, values)
        except Exception as exc:
            return render(engine.url, f"<p class='bad'>{html.escape(str(exc))}</p><p><a href='/query'>back</a></p>")
        head = "".join(f"<th>{html.escape(str(c))}</th>" for c in columns)
        body = "".join(
            "<tr>" + "".join(f"<td>{html.escape(str(v))}</td>" for v in row) + "</tr>" for row in rows
        )
        return render(
            engine.url,
            f"<h2>ask</h2><pre>{html.escape(sql)}</pre>"
            f"<table><tr>{head}</tr>{body}</table>"
            f"<p class='muted'>{len(rows)} row(s)</p><p><a href='/query'>another</a></p>",
        )

    return app


def coerce(value: str) -> object:
    """A parameter is a number when it looks like one, and text otherwise."""
    try:
        return int(value)
    except ValueError:
        try:
            return float(value)
        except ValueError:
            return value
