"""drishti's seven themes, in a real browser: the default, the migration of the old choice, no flash, the menu.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The console wears drishti's seven themes under drishti's ids (``static/css/tokens.css``), Crimson
by default. ``theme-init.js`` applies a stored choice before the first paint, as a file rather than
an inline script, and moves a choice stored by the earlier four-theme console (key
``pravaha.theme``: light, dark, blue, green) to the new key ``pravaha.theme.v2`` -- the old
"light" was the crimson look, and drishti's "light" id is Parchment, so it must never be read as
one. ``test_contrast.py`` holds the colours; this holds the mechanism.
"""
from __future__ import annotations

import json

import pytest
from browser_harness import DARK_THEMES, THEMES, Console, open_page, theme_script
from cdp import Browser

pytestmark = pytest.mark.browser

#: Records data-theme at the moment the first stylesheet is put into the document: what the
#: browser paints with. A theme set after that would flash.
AT_FIRST_STYLESHEET = """(() => { window.__themeAtCss = null;
  new MutationObserver((records, obs) => { for (const r of records) for (const n of r.addedNodes) {
    if (n.nodeName === 'LINK' && n.rel === 'stylesheet' && window.__themeAtCss === null) {
      window.__themeAtCss = document.documentElement.getAttribute('data-theme'); obs.disconnect(); return; } } })
  .observe(document, {childList: true, subtree: true}); })();"""

STATE = """(() => ({theme: document.documentElement.getAttribute('data-theme'),
  bs: document.documentElement.getAttribute('data-bs-theme'),
  v2: localStorage.getItem('pravaha.theme.v2'), old: localStorage.getItem('pravaha.theme'),
  atCss: window.__themeAtCss}))()"""


def _fresh(chrome: Browser, console: Console, setup: str = "", path: str = "/help"):
    """A tab with an empty origin storage, then ``setup`` run before the page's own scripts."""
    page = chrome.new_page()
    page.before_every_document("try{if(!sessionStorage.getItem('t')){localStorage.clear();sessionStorage.setItem('t','1');}}catch(e){}")
    if setup:
        page.before_every_document(setup)
    page.before_every_document(AT_FIRST_STYLESHEET)
    open_page(page, console, path, "true")
    return page


def test_nothing_chosen_is_crimson(chrome, console):
    page = _fresh(chrome, console)
    try:
        state = page.eval(STATE)
        assert state["theme"] == "crimson" and state["bs"] == "light", state
        assert state["v2"] is None and state["atCss"] == "crimson", state
    finally:
        page.close()


@pytest.mark.parametrize("old,new", [("light", "crimson"), ("dark", "crimson-dark"), ("blue", "blue"),
                                     ("green", "green"), ("system", "crimson")])
def test_a_choice_from_the_four_theme_console_is_migrated(chrome, console, old, new):
    """Read once from the old key, written to the new one, the old key removed; and applied before
    the stylesheets, so the first paint is already the migrated theme."""
    setup = f"try{{if(sessionStorage.getItem('m')===null){{localStorage.setItem('pravaha.theme', {json.dumps(old)});sessionStorage.setItem('m','1');}}}}catch(e){{}}"
    page = _fresh(chrome, console, setup)
    try:
        state = page.eval(STATE)
        assert state["theme"] == new and state["atCss"] == new, state
        assert state["old"] is None, state
        assert state["v2"] == (None if old == "system" else new), state
        assert state["bs"] == ("dark" if new in DARK_THEMES else "light"), state
    finally:
        page.close()


def test_drishtis_light_id_under_the_new_key_is_parchment(chrome, console):
    page = _fresh(chrome, console, theme_script("light"))
    try:
        state = page.eval(STATE)
        assert state["theme"] == "light" and state["atCss"] == "light", state
        assert page.eval("getComputedStyle(document.documentElement).getPropertyValue('--d-bg').trim()") == "#f7f5f0"
    finally:
        page.close()


@pytest.mark.parametrize("theme", THEMES)
def test_every_theme_is_applied_before_the_first_paint(chrome, console, theme):
    page = _fresh(chrome, console, theme_script(theme))
    try:
        state = page.eval(STATE)
        assert state["theme"] == theme and state["atCss"] == theme, state
        assert state["bs"] == ("dark" if theme in DARK_THEMES else "light"), state
        assert page.exceptions == [], page.exceptions
    finally:
        page.close()


def test_the_menu_lists_the_seven_with_swatches_and_choosing_one_sticks(chrome, console):
    page = _fresh(chrome, console)
    try:
        page.click("#theme-toggle")
        page.wait_for("document.querySelector('.theme-menu.show') !== null")
        items = page.eval("""[...document.querySelectorAll('.theme-menu [data-theme-choice]')].map(b => ({
            id: b.dataset.themeChoice, name: b.textContent.trim(), checked: b.getAttribute('aria-checked'),
            swatch: getComputedStyle(b.querySelector('.swatch')).backgroundImage}))""")
        assert [i["id"] for i in items] == THEMES
        assert [i["name"] for i in items] == ["Terminal", "Parchment", "Blue", "Wall Street", "Green", "Crimson", "Crimson dark"]
        assert all("linear-gradient" in i["swatch"] for i in items), items
        assert [i["id"] for i in items if i["checked"] == "true"] == ["crimson"]
        page.click('[data-theme-choice="wallstreet"]')
        state = page.eval(STATE)
        assert state["theme"] == "wallstreet" and state["v2"] == "wallstreet" and state["bs"] == "dark", state
        open_page(page, console, "/about", "true")
        assert page.eval(STATE)["atCss"] == "wallstreet"
        assert page.eval("document.querySelector('[data-theme-choice=\"wallstreet\"]').getAttribute('aria-checked')") == "true"
    finally:
        page.close()


def test_compact_density_holds_in_every_theme(chrome, console):
    """Density is a block of tokens apart from the theme: compact is applied with any of the seven."""
    for theme in THEMES:
        page = _fresh(chrome, console, theme_script(theme) + "try{localStorage.setItem('pravaha.density','compact')}catch(e){}")
        try:
            assert page.eval("document.documentElement.getAttribute('data-density')") == "compact", theme
            assert page.eval("getComputedStyle(document.documentElement).getPropertyValue('--row-h').trim()") == "28px", theme
        finally:
            page.close()
