"""Every page ends with "About this page": what it is for, two to four tiles, and the help topics
that say the rest. A new page cannot be added without saying what it is.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

MAYA's tests/test_page_help.py, held here: the routes are walked and each page rendered, so a page
with no panel fails, and so does a screen with words no page shows. The browser half -- the ?
in the top bar opening the panel and the closed state being remembered -- is in
``test_browser_journeys.py``.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

from fake_engine import FakeEngine
from fake_identity import sign_in
from test_product import _app

from core import page_help
from core.help_catalog import SCREEN_HELP
from core.i18n import Messages

CONSOLE_ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = CONSOLE_ROOT / "web" / "templates"

#: Not pages: the JSON API, the probes, the metrics scrape, static files, the API docs, the
#: assistant's fragments -- and Help, whose topics end with their own footer.
NOT_A_PAGE = ("/api/", "/health", "/metrics", "/static", "/docs", "/openapi", "/redoc", "/assist/",
              "/help", "/tutorials")
#: GET routes that answer with a redirect or a file rather than a page.
NOT_HTML = {"/home": "a redirect to the person's landing", "/admin": "a redirect to Admin · Access",
            "/about/papers/{name}": "the paper and the deck, as files"}
#: A real object for each route with a parameter, from the fake engine. A new route with a
#: parameter fails the walk until it has one here.
SAMPLES = {
    "/catalog/streams/{name}": "/catalog/streams/txn",
    "/catalog/objects/{name:path}": "/catalog/objects/public.sales.revenue",
    "/views/{name}": "/views/big_txn",
    "/views/{name}/live": "/views/big_txn/live",
    "/queries/{name}": "/queries/big_txn",
    "/queries/{name}/dead-letters": "/queries/big_txn/dead-letters",
    "/queries/{name}/replacement": "/queries/big_txn/replacement",
    "/queries/{name}/debug": "/queries/big_txn/debug",
    "/alerts/{name}": "/alerts/low_stock_alert",
}

PANEL = re.compile(r'<details class="page-help" id="page-help" data-screen="([a-z-]+)" open>')


@pytest.fixture
def admin():
    client = _app(FakeEngine())
    sign_in(client)
    return client


def _page_routes(app) -> list[str]:
    paths = []
    for route in app.routes:
        path = getattr(route, "path", "")
        if "GET" in (getattr(route, "methods", None) or set()) and not path.startswith(NOT_A_PAGE) \
                and path not in NOT_HTML:
            paths.append(path)
    return sorted(set(paths))


def test_every_page_route_ends_with_its_panel(admin):
    pages = _page_routes(admin.app)
    assert len(pages) > 30
    seen: dict[str, str] = {}
    for path in pages:
        url = SAMPLES.get(path, path)
        assert "{" not in url, f"{path} has a parameter: give it a real object in SAMPLES"
        response = admin.get(url, follow_redirects=False)
        assert response.status_code < 300 or response.status_code >= 400, f"{url} redirects: add it to NOT_HTML"
        found = PANEL.findall(response.text)
        assert len(found) == 1, f"{url} does not end with exactly one 'About this page'"
        seen[path] = found[0]
        assert 'href="#page-help" data-page-help' in response.text, f"{url} has no ? to its panel"
    # Each walked page shows the screen its own template names, not a neighbour's.
    assert seen["/catalog"] == "catalog" and seen["/queries/{name}"] == "query"
    assert seen["/admin/access"] == "admin-access" and seen["/login"] == "login"
    stale = sorted(path for path in NOT_HTML if path not in {getattr(r, "path", "") for r in admin.app.routes})
    assert not stale, f"NOT_HTML names routes that no longer exist: {stale}"
    stale = sorted(path for path in SAMPLES if path not in pages)
    assert not stale, f"SAMPLES names routes that no longer exist: {stale}"


def test_every_template_is_a_screen_or_exempt_and_every_screen_is_a_page():
    pages = {p.name for p in TEMPLATES.glob("*.html") if not p.name.startswith("_") and p.name != "base.html"}
    declared = set(page_help.TEMPLATES) | set(page_help.EXEMPT)
    assert not sorted(pages - declared), f"templates with no 'About this page' screen: {sorted(pages - declared)}"
    assert not sorted(declared - pages), f"screens declared for templates that do not exist: {sorted(declared - pages)}"
    assert not set(page_help.TEMPLATES) & set(page_help.EXEMPT)
    assert all(reason.strip() for reason in page_help.EXEMPT.values())
    screens = set(page_help.TEMPLATES.values())
    assert screens == set(page_help.TILES), (
        f"tiles with no page: {sorted(set(page_help.TILES) - screens)}; "
        f"pages with no tiles: {sorted(screens - set(page_help.TILES))}")


def test_every_screen_has_its_words_and_tiles_of_the_right_shape():
    messages = Messages(strict=True)
    css = (CONSOLE_ROOT / "web" / "static" / "vendor" / "bootstrap-icons" / "bootstrap-icons.css").read_text()
    have = set(re.findall(r"\.bi-([a-z0-9-]+)::before", css))
    catalog = json.loads((CONSOLE_ROOT / "web" / "i18n" / "en.json").read_text(encoding="utf-8"))["page"]
    assert set(catalog) == set(page_help.TILES), "words in en.json for a screen with no tiles, or none for one"
    for screen, icons in page_help.TILES.items():
        assert page_help.MIN_TILES <= len(icons) <= page_help.MAX_TILES, f"{screen} has {len(icons)} tiles"
        what = messages(f"page.{screen}.what")
        assert what.strip() and len(what) <= 160, f"{screen}: the summary is one line"
        assert set(catalog[screen]["points"]) == {str(n) for n in range(1, len(icons) + 1)}, \
            f"{screen}: one heading and text per icon"
        for n, icon in page_help.tiles(screen):
            heading = messages(f"page.{screen}.points.{n}.heading")
            text = messages(f"page.{screen}.points.{n}.text")
            assert icon in have, f"{screen} tile {n}: the icon font has no {icon}"
            assert heading.strip() and len(heading) <= 40, f"{screen} tile {n}: a heading, not a sentence"
            assert text.strip() and len(text) <= 200, f"{screen} tile {n}: one or two sentences"
        # "More in Help": the screen's topics, which test_help.py checks exist.
        assert screen in SCREEN_HELP, f"{screen} has no help topics to link to"


def test_the_panel_says_what_the_page_is_and_links_its_help(admin):
    page = admin.get("/workbench").text
    panel = page[page.index('id="page-help"'):]
    assert ">About this page<" in page.replace("</i> ", ">")
    assert Messages()("page.workbench.what") in panel
    assert panel.count('class="ph-tile"') == len(page_help.TILES["workbench"])
    assert Messages()("page.workbench.points.1.heading") in panel
    assert "More in Help" in panel
    for entry in SCREEN_HELP["workbench"]:
        assert f'href="/help/topics/{entry}"' in panel, entry
    # The ? in the top bar is a link to it, named for what it opens.
    assert re.search(r'<a class="tool" href="#page-help" data-page-help aria-label="About this page"', page)
    # It replaces the old strip of help cards: the topics are said once, not twice.
    assert "helpcards" not in page
    assert page.count('href="/help/topics/sql-refusals"') == 1


def test_the_panel_is_on_the_pages_nobody_signed_in_for():
    anonymous = _app(FakeEngine())
    for path, screen in (("/", "landing"), ("/login", "login"), ("/login/reset", "login-reset"),
                         ("/about", "about"), ("/about/competitive", "competitive"),
                         ("/views/no_such_view", "not-found")):
        response = anonymous.get(path, follow_redirects=False)
        if response.status_code in (302, 303):
            continue  # a signed-in page, which sends somebody anonymous to sign in
        assert PANEL.findall(response.text) == [screen], path


def test_help_pages_keep_their_own_footer_and_get_no_second_one(admin):
    for path in ("/help", "/help/topics/getting-started", "/help/quickstart", "/tutorials",
                 "/help/codes", "/help/search?q=view"):
        page = admin.get(path).text
        assert 'id="page-help"' not in page, f"{path} has an 'About this page' as well as its own footer"
        assert "data-page-help" not in page, f"{path} has a ? to a panel it does not have"
    assert 'class="help-footer"' in admin.get("/help/topics/getting-started").text
