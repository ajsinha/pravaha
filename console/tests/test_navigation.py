"""The shell in MAYA's form: the menu defined once as data, nothing orphaned, the public bar for
anybody signed out, the banners and the footer.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The browser half -- signing out from the user menu and landing on the public bar, the mega menu
at phone width -- is in ``test_browser_journeys.py``.
"""
from __future__ import annotations

import re

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

from fake_engine import FakeEngine
from fake_identity import sign_in
from starlette.routing import Route
from test_product import _app

from core import navigation

#: Paths that are not screens: the JSON API, the health probes, the metrics scrape, static files.
NOT_A_SCREEN = ("/api/", "/health", "/metrics", "/static", "/docs", "/openapi", "/redoc", "/assist/")


def _screens(app) -> dict[str, Route]:
    found = {}
    for route in app.routes:
        methods = getattr(route, "methods", None) or set()
        path = getattr(route, "path", "")
        if "GET" not in methods or path.startswith(NOT_A_SCREEN):
            continue
        found[path] = route
    return found


@pytest.fixture
def engine():
    return FakeEngine()


@pytest.fixture
def admin(engine):
    client = _app(engine)
    sign_in(client)
    return client


@pytest.fixture
def anonymous(engine):
    return _app(engine)


def _menu_hrefs(page: str) -> set[str]:
    """Every path the rendered menu links to, without its query or fragment."""
    return {re.split(r"[?#]", href)[0] for href in re.findall(r'class="mega-item[^"]*" href="([^"]+)"', page)}


def test_every_screen_is_in_the_menu_or_deliberately_is_not(admin):
    """MAYA's rule for its menu, held here: every screen is one click from the bar, or the
    reason it is not is written down (``navigation.EXCLUDED``). A new screen that is neither
    fails here, and so does an exclusion for a screen that no longer exists. The menu is read
    as an administrator sees it, which is all of it."""
    screens = _screens(admin.app)
    assert len(screens) > 40
    linked = _menu_hrefs(admin.get("/catalog").text)
    assert len(linked) > 25
    orphans = sorted(p for p in screens if p not in linked and p not in navigation.EXCLUDED)
    assert not orphans, f"screens neither in _nav.html's MENU nor in core/navigation.EXCLUDED: {orphans}"
    stale = sorted(p for p in navigation.EXCLUDED if p not in screens)
    assert not stale, f"EXCLUDED names screens that do not exist: {stale}"
    dead = sorted(p for p in linked if not any(r.path_regex.match(p) for r in screens.values()))
    assert not dead, f"the menu links to paths no route serves: {dead}"
    assert all(reason.strip() for reason in navigation.EXCLUDED.values())


def test_the_menu_is_mega_panels_in_mayas_groups(admin):
    page = admin.get("/catalog").text
    labels = re.findall(r'<i class="bi bi-[a-z0-9-]+" aria-hidden="true"></i> ([A-Za-z]+)</a>\s*<div class="dropdown-menu mega-panel', page)
    assert labels == ["Catalog", "Workbench", "Operate", "Admin", "Help"]
    assert 'class="dropdown-menu mega-panel cols-4"' in page        # Admin: four columns
    # Alerts is an item in the Operate panel, not a sixth top-level entry.
    operate = page.split("> Operate</a>")[1].split("</li>")[0]
    assert 'href="/alerts"' in operate and "Alerts" in operate
    # The right-hand tools, as MAYA's: the search (the palette), alerts, the theme menu, the user.
    for piece in ('id="palette-trigger"', 'class="tool" href="/alerts"', 'id="theme-toggle"',
                  'id="account-menu"', 'id="density-toggle"', 'id="sign-out-form"'):
        assert piece in page, piece
    # The brand's three lines: the name, what Pravaha is, and the creed.
    brand = page.split('class="navbar-brand pv-brand"')[1].split("</a>")[0]
    assert "Pravaha" in brand and "Continuous SQL where your data already lives" in brand
    assert 'class="creed">Ask once. Answer always.' in brand


#: MAYA's rule for which item is current: its own path, or a path under its ``match`` prefix.
#: A tab of a screen (``/catalog?tab=sinks``) is never lit by itself; the screen is.
@pytest.mark.parametrize("path, current", [
    ("/catalog", ["/catalog"]),
    ("/catalog?tab=sinks", ["/catalog"]),
    ("/catalog/streams/txn", ["/catalog"]),
    ("/views/big_txn", ["/views"]),
    ("/queries/big_txn", ["/queries"]),
    ("/queries/big_txn/dead-letters", ["/queries"]),
    ("/queries/big_txn/replacement", ["/queries"]),
    ("/workbench", ["/workbench"]),
    ("/alerts", ["/alerts"]),
    ("/admin/grants", ["/admin/grants"]),
    ("/help/codes/PRV-2050", ["/help/codes"]),
    ("/help/topics/first-view", ["/help"]),
    ("/help/topics/cli-reference", ["/help", "/help/topics/cli-reference"]),
])
def test_the_current_item_is_lit_as_maya_lights_it(admin, path, current):
    page = admin.get(path).text
    lit = re.findall(r'class="mega-item active" href="([^"]+)" aria-current="page"', page)
    assert lit == current, (path, lit)
    assert page.count('class="nav-link dropdown-toggle active"') == 1


def test_the_admin_panel_is_for_administrators_only(engine):
    """As MAYA shows its Admin menu only to staff. Presentation only: the engine still decides."""
    engine.identity.add_user("dana", "Pravaha-test-user-1", ["analyst"])
    client = _app(engine)
    sign_in(client, "dana", "Pravaha-test-user-1")
    page = client.get("/catalog").text
    assert "> Catalog</a>" in page and "> Admin</a>" not in page
    assert 'href="/admin/users"' not in page


def test_signed_out_the_landing_page_has_the_public_bar(anonymous):
    """The owner's rule: signed out, the bar is MAYA's public one -- the brand, Help, About, the
    theme menu and Sign in -- and never the app's menu, whose every entry would bounce to the
    sign-in form."""
    page = anonymous.get("/").text
    nav = page.split("<nav ")[1].split("</nav>")[0]
    assert 'href="/help"' in nav and 'href="/about"' in nav and 'href="/login"' in nav
    assert 'id="theme-toggle"' in nav
    assert "mega-panel" not in page and 'id="account-menu"' not in page
    for app_only in ("/workbench", "/catalog", "/operations", "/queries", "/admin/"):
        assert f'href="{app_only}' not in nav, app_only


def test_signing_out_lands_on_the_landing_page_with_the_public_bar(admin):
    from fake_identity import csrf_of

    out = admin.post("/logout", data={"csrf_token": csrf_of(admin.get("/catalog").text)},
                     follow_redirects=False)
    assert out.status_code in (302, 303) and out.headers["location"] == "/"
    page = admin.get("/").text
    assert "mega-panel" not in page and 'href="/login"' in page


def test_the_sign_in_page_stands_on_the_gradient_without_a_bar(anonymous):
    page = anonymous.get("/login").text
    assert '<body class="pv-public">' in page
    assert "<nav " not in page.split("<main")[0]
    assert 'class="login-name">Pravaha<' in page and "Ask once. Answer always." in page


def test_the_footer_is_mayas_closing_line(admin, anonymous):
    for client, path in ((admin, "/catalog"), (anonymous, "/help"), (anonymous, "/login")):
        foot = client.get(path).text.split('<footer class="pv-foot">')[1].split("</footer>")[0]
        text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", foot)).strip()
        assert text == "Ask once. Answer always. Pravaha 0.2.1 · Help · About · © 2026 Ashutosh Sinha. All rights reserved.", text


def test_the_banners_say_where_this_is_and_what_is_wrong(admin, engine):
    page = admin.get("/catalog").text
    assert 'id="banner-info"' in page and "engine up" in page and 'id="banner-engine"' not in page
    engine.down = True
    admin.app.state.services.health._cached = None
    page = admin.get("/catalog").text
    assert 'id="banner-engine"' in page and "engine unreachable" in page


def test_the_bootstrap_admin_on_its_published_password_is_warned(engine):
    engine.identity.add_user("admin", "pravaha-dev-admin", ["admin"])
    client = _app(engine)
    sign_in(client, "admin", "pravaha-dev-admin")
    page = client.get("/catalog").text
    assert 'id="banner-default-password"' in page and 'href="/account/password"' in page
    other = _app(engine)
    engine.identity.add_user("admin", "Pravaha-test-admin-9", ["admin"])
    sign_in(other, "admin", "Pravaha-test-admin-9")
    assert 'id="banner-default-password"' not in other.get("/catalog").text


def test_a_flash_is_drawn_as_mayas_dismissible_alert(admin):
    from fake_identity import csrf_of

    page = admin.post("/account/password", data={"current_password": "x", "new_password": "a",
                                                  "confirm_password": "b",
                                                  "csrf_token": csrf_of(admin.get("/account").text)}).text
    assert 'class="pv-flashes" aria-live="polite"' in page
    assert re.search(r'class="alert alert-danger alert-dismissible fade show py-2"[^>]*data-flash="danger"', page)
    assert 'data-bs-dismiss="alert"' in page
