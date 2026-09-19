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

from core.about import MEASURED, PRINCIPLES, AboutSource
from core.help_catalog import ERROR_FAMILIES, HelpCatalog
from routes.base import Routes


class PublicRoutes(Routes):
    def register(self) -> None:
        started = time.time()
        content = self.ctx["content"]
        catalog = self.ctx.get("help") or HelpCatalog(content)

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
            """What Pravaha is, how it works, what is built, what it measures, who made it.

            Public, like the documentation. The engine's version and state come from its own
            status call when it answers -- and only those two: the node's id, its plugins and its
            queries are for a signed-in operator, not for whoever reaches this page.
            """
            source = AboutSource(content.include_root, content.renderer)
            topics = {t.slug: t for t in content.topics("about")}
            case_studies = [t for _section, ts in content.sections("tutorials") for t in ts if t.slug != "setup"]
            return self.page(request, "about.html", current="/about", topics=topics,
                             engine_status=engine_status(), built=source.built(),
                             not_built=source.not_built(), how_built=source.how_built(),
                             status_line=source.status_line(), decisions=source.decisions(),
                             measured=MEASURED, principles=PRINCIPLES, legal=source.legal(),
                             licence=source.licence(), case_studies=case_studies,
                             console_version=self.ctx["config"].get("app.version"),
                             topic_count=len(catalog.topics()))

        def engine_status() -> dict:
            try:
                raw = self.ctx["services"].engine.status()
            except Exception:  # noqa: BLE001 -- an unreachable engine is a state, not an error here
                return {"reachable": False}
            if not raw:
                return {"reachable": False}
            return {"reachable": True, "version": raw.get("version"), "state": raw.get("engineState")}

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
            """Searchable, collapsible category tiles, rendered from core/help_catalog.py."""
            return self.page(request, "help_index.html", current="/help", index=catalog.index(),
                             topic_count=len(catalog.topics()), guide_count=len(catalog.guides()))

        @self.app.get("/help/search", response_class=HTMLResponse, tags=["public"])
        def help_search(request: Request, q: str = ""):
            """Full-text search over every topic and guide. The index filters its cards in place
            as you type; this is what Enter does, and what works with no JavaScript at all."""
            query = q.strip()[:200]
            return self.page(request, "help_search.html", current="/help", query=query,
                             results=catalog.search(query) if query else [])

        @self.app.get("/help/guides", response_class=HTMLResponse, tags=["public"])
        def help_guides(request: Request):
            """Every long-form guide, grouped as the documents group themselves."""
            return self.page(request, "help_guides.html", current="/help",
                             sections=content.sections("help"), tutorials=content.sections("tutorials"))

        @self.app.get("/help/topics/{slug}", response_class=HTMLResponse, tags=["public"])
        def help_topic_page(request: Request, slug: str):
            found = catalog.topic(slug)
            if found is None:
                return self.page(request, "not_found.html", http_status=404, current="/help",
                                 what="help topic", identifier=slug, back_href="/help",
                                 back_label="Back to help")
            before, after = catalog.neighbours(found)
            return self.page(request, "help_topic_page.html", current="/help", topic=found,
                             footer=catalog.related(found), before=before, after=after)

        @self.app.get("/help/codes", response_class=HTMLResponse, tags=["public"])
        def help_codes(request: Request):
            """Every PRV code, from the table TROUBLESHOOTING.md generates from the source."""
            from core.content.codes import every_code

            codes = every_code(content.include_root / "docs")
            families: dict[str, list] = {}
            for entry in codes:
                families.setdefault(entry["code"][4], []).append(entry)
            groups = [{"family": f"PRV-{digit}xxx", "label": ERROR_FAMILIES.get(digit, ("", ""))[0],
                       "topic": ERROR_FAMILIES.get(digit, ("", ""))[1], "codes": entries}
                      for digit, entries in sorted(families.items())]
            return self.page(request, "help_codes.html", current="/help", codes=codes, groups=groups)

        @self.app.get("/help/decisions/{record}", response_class=HTMLResponse, tags=["public"])
        def help_decision(request: Request, record: str):
            """One architecture decision record, rendered in place. Allow-listed by the directory's
            own listing, so the name never reaches a path join."""
            adr_dir = content.include_root / "docs" / "adr"
            known = {p.stem: p for p in adr_dir.glob("[0-9][0-9][0-9]-*.md")} if adr_dir.is_dir() else {}
            path = known.get(record)
            if path is None:
                return self.page(request, "not_found.html", http_status=404, current="/help",
                                 what="decision record", identifier=record, back_href="/help/decisions",
                                 back_label="Every decision")
            html, headings = content.renderer.render(path.read_text(encoding="utf-8"))
            from core.content.library import Topic

            title = next((h["name"] for h in headings if h["level"] == 1), record)
            decision = Topic(slug=record, title=title, section="Decision records", html=html,
                             headings=headings, source=f"docs/adr/{path.name}")
            return self.page(request, "help_topic.html", current="/help", topic=decision,
                             related=[], **area_context("help"))

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
                                 what="error code", identifier=code, back_href="/help/codes",
                                 back_label="Every code")
            renderer = content.renderer
            sections = [(doc, heading, renderer.render(body)[0])
                        for doc, heading, body in entry.sections]
            mentions = [(doc, renderer.render(line)[0]) for doc, line in entry.mentions]
            family = ERROR_FAMILIES.get(entry.code[4], ("", ""))[1]
            return self.page(request, "help_code.html", current="/help", entry=entry,
                             sections=sections, mentions=mentions,
                             family_topic=family if catalog.topic(family) else "")

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
