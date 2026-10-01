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

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse

from core import credential
from core.about import (CAPABILITIES, DIFFERENT, LIMITS, MEASURED, PRINCIPLES, PROBLEM_LEAD, PROBLEMS,
                        TECHNOLOGY, AboutSource)
from core.competitive import BEHIND, SHINE
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
            """What Pravaha is, the problem, what makes it different, how it works, what is built,
            what it measures, where it stands against the alternatives, and who made it -- in MAYA's
            About's shape (core/about.py).

            Public, like the documentation. The engine's version and state come from its own
            status call when it answers -- and only those two: the node's id, its plugins and its
            queries are for a signed-in operator, not for whoever reaches this page.
            """
            source = AboutSource(content.include_root, content.renderer)
            topics = {t.slug: t for t in content.topics("about")}
            land = source.landscape()
            columns, rows = land.table()
            landscape = {"columns": columns, "rows": rows,
                         "shine": [r for r in rows if r["pravaha"] == "Yes"],
                         "behind": [r for r in rows if r["pravaha"] != "Yes"],
                         "disclaimer": land.disclaimer()}
            return self.page(request, "about.html", current="/about", topics=topics,
                             engine_status=engine_status(), built=source.built(),
                             not_built=source.not_built(), status_line=source.status_line(),
                             measured=MEASURED, principles=PRINCIPLES, problems=PROBLEMS,
                             problem_lead=PROBLEM_LEAD, different=DIFFERENT, capabilities=CAPABILITIES,
                             limits=LIMITS, technology=TECHNOLOGY, readings=source.readings(),
                             release=source.release(), landscape=landscape,
                             legal=source.legal(), licence=source.licence(),
                             console_version=self.ctx["config"].get("app.version"),
                             topic_count=len(catalog.topics()))

        @self.app.get("/about/competitive", response_class=HTMLResponse, tags=["public"])
        def competitive(request: Request):
            """Where Pravaha stands against the product categories that do part of its job.

            Drawn whole from docs/publications/COMPETITIVE_LANDSCAPE.md, which is canonical, in MAYA's form: the
            landscape, the scored table, a note per row -- where Pravaha shines, where it is partial
            or behind -- what the rows have in common, and the sections after. Public, like About.
            """
            land = AboutSource(content.include_root, content.renderer).landscape()
            columns, rows = land.table()
            return self.page(request, "competitive.html", current="/about", columns=columns, rows=rows,
                             short=land.short(columns), landscape=land.landscape(),
                             notes=land.table_notes(), intro=land.intro(), shine=land.cards(SHINE),
                             behind=land.cards(BEHIND), common=land.common(), reading=land.reading(),
                             sections=land.sections(), disclaimer=land.disclaimer())

        @self.app.get("/about/papers/{name}", tags=["public"])
        def paper(name: str):
            """The research paper and the deck, as files: only a name core.about.PAPERS lists, and
            only when this installation carries it -- anything else is a 404."""
            found = AboutSource(content.include_root, content.renderer).paper(name)
            if found is None:
                raise HTTPException(404, "no such paper")
            path, media = found
            return FileResponse(path, media_type=media, filename=name)

        def engine_status() -> dict:
            # On a credential of its own: a public page reports what the engine said, and never
            # ends the session of a visitor who happens to have one (routes.web_security).
            try:
                with credential.bound(credential.Credential(credential.token())):
                    raw = self.ctx["services"].engine.status()
            except Exception as exc:  # noqa: BLE001 -- an unreachable engine is a state, not an error here
                if getattr(exc, "status", None) in (401, 403) or credential.code_of(exc) in (
                        "PRV-7001", "PRV-7016", "PRV-7018", "PRV-7002"):
                    # ADR-052: the engine answers its status to a signed-in caller only. It is
                    # there; what it runs is for whoever signs in.
                    return {"reachable": True, "private": True}
                return {"reachable": False}
            if not raw:
                return {"reachable": False}
            return {"reachable": True, "version": raw.get("version"), "state": raw.get("engineState")}

        # Two content areas, one renderer. Adding a third is a directory and a
        # dictionary entry, not another pair of routes.
        # Its words are in the string catalog, under help.area.<area>.*.
        AREAS = {area: {**{part: self.t(f"help.area.{area}.{part}")
                           for part in ("kicker", "heading", "title", "blurb")}, "path": "/" + area}
                 for area in ("help", "tutorials")}

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
                                 current="/help", what=self.t("not_found.what.topic", area=area),
                                 identifier=f"{area}/{slug}",
                                 back_href=AREAS[area]["path"],
                                 back_label=self.t("not_found.back.area", area=AREAS[area]["title"].lower()))
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
            moved = catalog.moved(slug) if found is None else None
            if moved:
                # A topic merged into another: the address somebody bookmarked says it has moved,
                # and where to -- the section that holds what the old page said.
                return RedirectResponse(f"/help/topics/{moved}", status_code=301)
            if found is None:
                return self.page(request, "not_found.html", http_status=404, current="/help",
                                 what=self.t("not_found.what.help_topic"), identifier=slug,
                                 back_href="/help", back_label=self.t("not_found.back.help"))
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
            adr_dir = content.include_root / "docs" / "design" / "adr"
            known = {p.stem: p for p in adr_dir.glob("[0-9][0-9][0-9]-*.md")} if adr_dir.is_dir() else {}
            path = known.get(record)
            if path is None:
                return self.page(request, "not_found.html", http_status=404, current="/help",
                                 what=self.t("not_found.what.decision"), identifier=record,
                                 back_href="/help/decisions", back_label=self.t("not_found.back.decisions"))
            html, headings = content.renderer.render(path.read_text(encoding="utf-8"))
            from core.content.library import Topic

            title = next((h["name"] for h in headings if h["level"] == 1), record)
            decision = Topic(slug=record, title=title, section=self.t("help.decision_records"), html=html,
                             headings=headings, source=f"docs/design/adr/{path.name}")
            return self.page(request, "help_topic.html", current="/help", topic=decision,
                             related=[], **area_context("help"))

        @self.app.get("/help/codes/{code}", response_class=HTMLResponse, tags=["public"])
        def help_code(request: Request, code: str):
            """One PRV code: what the documentation says about it, gathered onto one page.

            Public, like the rest of the documentation: a code read off a log during an
            incident should explain itself before anybody has signed in.
            """
            from core.content.codes import lookup

            entry = lookup(code, content.include_root / "docs")
            if entry is None:
                return self.page(request, "not_found.html", http_status=404, current="/help",
                                 what=self.t("not_found.what.code"), identifier=code,
                                 back_href="/help/codes", back_label=self.t("not_found.back.codes"))
            renderer = content.renderer
            sections = [(doc, heading, renderer.render(body)[0])
                        for doc, heading, body in entry.sections]
            mentions = [(doc, renderer.render(line)[0]) for doc, line in entry.mentions]
            family = ERROR_FAMILIES.get(entry.code[4], ("", ""))[1]
            return self.page(request, "help_code.html", current="/help", entry=entry,
                             sections=sections, mentions=mentions,
                             family_topic=family if catalog.topic(family) else "")

        @self.app.get("/help/case-studies", response_class=HTMLResponse, tags=["public"])
        def help_case_studies(request: Request):
            """Every case study, a card each, in the order examples/case-studies/README.md lists them."""
            from core.content.case_studies import catalog as studies

            return self.page(request, "help_case_studies.html", current="/help",
                             studies=studies(content.include_root))

        @self.app.get("/help/case-studies/{slug}", response_class=HTMLResponse, tags=["public"])
        def help_case_study(request: Request, slug: str):
            """One case study's README, rendered in place. Allow-listed by the index table, so the
            name reaches a path only once the table has named it."""
            from core.content import case_studies
            from core.content.library import Topic

            study = case_studies.find(content.include_root, slug)
            if study is None:
                return self.page(request, "not_found.html", http_status=404, current="/help",
                                 what=self.t("not_found.what.case_study"), identifier=slug,
                                 back_href="/help/case-studies", back_label=self.t("not_found.back.case_studies"))
            readme = (content.include_root / "examples" / "case-studies" / study.slug / "README.md").read_text(
                encoding="utf-8")
            html, headings = content.renderer.render(
                case_studies.relink(readme, study.slug, content.include_root))
            page = Topic(slug=study.slug, title=case_studies.title_of(readme, study.title),
                         section=self.t("help.case_studies.title"),
                         html=html,
                         headings=headings, source=f"examples/case-studies/{study.slug}/README.md")
            before, after = case_studies.neighbours(content.include_root, study)
            return self.page(request, "help_case_study.html", current="/help", topic=page, study=study,
                             before=before, after=after)

        @self.app.get("/help/{slug}", response_class=HTMLResponse, tags=["public"])
        def help_topic(request: Request, slug: str):
            return topic(request, "help", slug)

        @self.app.get("/tutorials", response_class=HTMLResponse, tags=["public"])
        def tutorials_index(request: Request):
            return index(request, "tutorials")

        #: Where each case study lived before it had its own section: an address somebody bookmarked
        #: or linked keeps working, and says (301) that it has moved.
        MOVED_TO_CASE_STUDIES = {
            "trade-processing": "trade-processing",
            "counterparty-exposure": "finance-counterparty-exposure",
            "card-velocity": "banking-card-velocity",
            "order-flow": "trading-order-flow",
            "sequencing-qc": "biology-sequencing-qc",
            "sensor-anomalies": "manufacturing-sensor-anomalies",
            "checkout-funnel": "ecommerce-checkout-funnel",
            "click-attribution": "adtech-click-attribution",
            "cdr-fraud": "telecom-cdr-fraud",
            "delivery-sla": "logistics-delivery-sla",
        }

        @self.app.get("/tutorials/{slug}", response_class=HTMLResponse, tags=["public"])
        def tutorial(request: Request, slug: str):
            if slug in MOVED_TO_CASE_STUDIES:
                return RedirectResponse(f"/help/case-studies/{MOVED_TO_CASE_STUDIES[slug]}", status_code=301)
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
