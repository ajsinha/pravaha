"""
Pravaha console — the screens the top menu deliberately does not list, and why.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Ask once. Answer always.

The menu itself is data in ``web/templates/_nav.html``, where MAYA keeps its own: one list of
(label, icon, columns), each column a titled list of (href, icon, label, description, match),
drawn as mega-menu panels. Every screen is reachable from it or is named here with the reason it
is not; ``tests/test_navigation.py`` renders the menu and fails when a screen is neither, or when
an entry below names a screen that no longer exists.

A path with a ``{parameter}`` is one object's own screen, reached from the list that names it.
"""
from __future__ import annotations

EXCLUDED: dict[str, str] = {
    "/": "the landing page: the brand in the public bar links to it",
    "/home": "a redirect to the signed-in person's landing: the brand in the bar links to it",
    "/login": "the public bar's Sign in",
    "/login/reset": "linked from the sign-in form",
    "/account": "the user menu",
    "/account/password": "the user menu",
    "/admin": "a redirect to Admin · Access",
    "/_components": "the component gallery, a developer's page",
    "/catalog/streams/{name}": "one stream, from Catalog · Streams",
    "/catalog/objects/{name:path}": "one catalog object, from the stream or view it names",
    "/views/{name}": "one view, from Catalog · Views",
    "/views/{name}/live": "one view's live changes, from the view",
    "/queries/{name}": "one query, from Operate · Queries",
    "/queries/{name}/dead-letters": "one query's dead letters, from the query",
    "/queries/{name}/replacement": "one query's replacement, from the query (Workbench · Replace)",
    "/queries/{name}/debug": "one query's debugger, from the query",
    "/alerts/{name}": "one alert, from Operate · Alerts",
    "/help/topics/{slug}": "one help topic, from the help index and search",
    "/help/{slug}": "one guide, from Help · Guides",
    "/help/codes/{code}": "one error code, from Help · Error codes",
    "/help/decisions/{record}": "one decision record, from the guides",
    "/help/case-studies/{slug}": "one case study, from Help · Case studies",
    "/tutorials/{slug}": "one tutorial, from Help · Tutorials",
    "/about/papers/{name}": "the research paper and the deck, as files, from About · Read further",
}
