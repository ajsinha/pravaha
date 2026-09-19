"""
Pravaha console — public pages and health probes.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The landing page, what this is, the documentation, and the probes a deployment
watches. None of them need the engine to be up, which is the point: an operator
opening a console during an incident needs it to load and tell them what is
wrong, and that is exactly the moment a page that requires a healthy engine is
least useful.
"""
from __future__ import annotations

import time

from fastapi import Request
from fastapi.responses import HTMLResponse, JSONResponse

from routes.base import Routes


class PublicRoutes(Routes):
    def register(self) -> None:
        started = time.time()
        content = self.ctx["content"]

        @self.app.get("/", response_class=HTMLResponse, tags=["public"])
        def landing(request: Request):
            """What this is, before what it is doing.

            The overview lives at /overview rather than here. Somebody arriving
            at a bare host name is at least as likely to be asking what this
            server is as to be an operator checking on it, and a wall of query
            statistics answers only the second.
            """
            return self.page(request, "landing.html", current="/")

        @self.app.get("/about", response_class=HTMLResponse, tags=["public"])
        def about(request: Request):
            return self.page(request, "about.html", current="/about",
                             topics=content.topics("about"))

        # Two content areas, one renderer. Adding a third is a directory and a
        # dictionary entry, not another pair of routes.
        AREAS = {
            "help": {
                "kicker": "HELP", "heading": "Documentation",
                "title": "Help", "path": "/help",
                "blurb": ("Everything below is available over Flight SQL and both SDKs; "
                          "nothing requires this console. These pages are markdown files "
                          "under content/help/, rendered at request time — so they are "
                          "versioned, reviewable in a pull request alongside the behaviour "
                          "they describe, and cannot drift from the release that shipped them."),
            },
            "tutorials": {
                "kicker": "TUTORIALS", "heading": "Working through Pravaha",
                "title": "Tutorials", "path": "/tutorials",
                "blurb": ("Worked walkthroughs, end to end and with real calls: registering "
                          "a continuous query and watching it maintained, joining two streams "
                          "with a time bound, subscribing from both SDKs, and reading the "
                          "case studies the engine ships with."),
            },
        }

        # The OTHER area, so each index points at its sibling. The bar carries
        # Help and not Tutorials, which would otherwise leave the walkthroughs
        # reachable only by typing the URL.
        SIBLING = {"help": "tutorials", "tutorials": "help"}

        def area_context(area: str) -> dict:
            meta = AREAS[area]
            other = AREAS[SIBLING[area]]
            return {"area": area, "area_kicker": meta["kicker"],
                    "area_heading": meta["heading"], "area_title": meta["title"],
                    "area_path": meta["path"], "area_blurb": meta["blurb"],
                    "sibling_title": other["title"], "sibling_path": other["path"],
                    "sibling_blurb": other["blurb"]}

        def index(request: Request, area: str):
            return self.page(request, "help.html", current="/help",
                             sections=content.sections(area),
                             content_dir=str(content.root), **area_context(area))

        def topic(request: Request, area: str, slug: str):
            found = content.get(area, slug)
            if found is None:
                # A 404, not a 200 with an apology in it. The allow-list refused
                # the name, and "not found" is both true and actionable.
                return self.page(request, "not_found.html", http_status=404,
                                 current="/help", what=f"{area} topic",
                                 identifier=f"{area}/{slug}",
                                 back_href=AREAS[area]["path"],
                                 back_label=f"Back to {AREAS[area]['title'].lower()}")
            related = [t for t in content.topics(area) if t.section == found.section]
            return self.page(request, "help_topic.html", current="/help",
                             topic=found, related=related, **area_context(area))

        @self.app.get("/help", response_class=HTMLResponse, tags=["public"])
        def help_index(request: Request):
            """Cards, grouped by section, from the markdown on disk."""
            return index(request, "help")

        @self.app.get("/help/codes/{code}", response_class=HTMLResponse, tags=["public"])
        def help_code(request: Request, code: str):
            """One PRV code: what the documentation says about it, gathered onto one page.

            Public, like the rest of the documentation: a code read off a log during an
            incident should explain itself before anybody has found the console password.
            """
            from core.content.codes import lookup

            entry = lookup(code, content.include_root / "docs")
            if entry is None:
                return self.page(request, "not_found.html", http_status=404, current="/help",
                                 what="error code", identifier=code, back_href="/help/troubleshooting",
                                 back_label="Every code")
            renderer = content.renderer
            sections = [(doc, heading, renderer.render(body)[0])
                        for doc, heading, body in entry.sections]
            mentions = [(doc, renderer.render(line)[0]) for doc, line in entry.mentions]
            return self.page(request, "help_code.html", current="/help", entry=entry,
                             sections=sections, mentions=mentions)

        @self.app.get("/help/{slug}", response_class=HTMLResponse, tags=["public"])
        def help_topic(request: Request, slug: str):
            return topic(request, "help", slug)

        @self.app.get("/tutorials", response_class=HTMLResponse, tags=["public"])
        def tutorials_index(request: Request):
            return index(request, "tutorials")

        @self.app.get("/tutorials/{slug}", response_class=HTMLResponse, tags=["public"])
        def tutorial(request: Request, slug: str):
            return topic(request, "tutorials", slug)

        # ------------------------------------------------------------- probes
        @self.app.get("/health", tags=["health"])
        def health():
            """The console's own health, and what it can see of the engine's.

            200 even when the engine is down. This endpoint answers "is the
            console up", and a 503 would have a monitoring system report the
            console as broken when it is working correctly and reporting
            accurately that something else is not.
            """
            engine = self.ctx["services"].health.health()
            return JSONResponse({
                "status": "healthy",
                "uptime_seconds": round(time.time() - started, 1),
                "version": self.ctx["config"].get("app.version"),
                "engine": engine.as_dict(),
            })

        @self.app.get("/health/live", tags=["health"])
        def live():
            """Is the process running. Never consults the engine.

            Kept distinct from readiness because conflating them makes an
            orchestrator restart a console that is perfectly healthy and merely
            pointed at an engine that is still starting.
            """
            return JSONResponse({"status": "alive"})

        @self.app.get("/health/ready", tags=["health"])
        def ready():
            """Can this console do its job, which means: can it reach the engine."""
            engine = self.ctx["services"].health.health()
            return JSONResponse(
                {"status": "ready" if engine.reachable else "not-ready",
                 "engine": engine.as_dict()},
                status_code=200 if engine.reachable else 503)
