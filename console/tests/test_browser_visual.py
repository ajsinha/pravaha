"""Visual regression: every page, light and dark, narrow and wide, against committed baselines.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Design 23.18 asks for "no unreviewed pixel change" in both themes. Each page is photographed
(the viewport, not the whole scroll height -- the first screen is what a person judges, and a
full-page capture of a long table is a baseline that changes whenever a row does) and compared
with ``tests/visual/baselines/<page>-<theme>-<viewport>.png``. A page may differ from its
baseline in at most ``TOLERANCE`` of its pixels, each by more than ``THRESHOLD`` in some
channel; beyond that the test fails and writes the actual screenshot and a diff image (changed
pixels in red) to ``tests/visual/failures/``, which is git-ignored.

Deterministic by construction: the fake engine's answers are fixed, the clock is pinned,
animations are off, fonts are vendored, and what is *about* time -- chart canvases, which
draw a series against the wall clock, and "updated 2 s ago" -- is masked (``MASK``).

A different Chrome renders text differently, which is not a regression in the console. The
baselines record the Chrome major version that took them (``baselines/CHROME``); on another
version the comparison is skipped with that reason rather than failed, and
``PRAVAHA_UPDATE_BASELINES=1`` retakes every baseline -- after a person has looked at the
diffs, which is the review the design asks for.

Documents included verbatim from the repository (``DOCUMENT_PAGES``) are not photographed;
their pixels change with the prose.
"""
from __future__ import annotations

import base64
import json
import os
import pathlib

import pytest
from browser_harness import (
    DETERMINISM,
    DOCUMENT_PAGES,
    PAGES,
    BrowserEngine,
    Console,
    compare_png,
    open_page,
    sign_in,
    theme_script,
)
from cdp import Browser, Page

pytestmark = pytest.mark.browser

VISUAL = pathlib.Path(__file__).resolve().parent / "visual"
BASELINES = VISUAL / "baselines"
FAILURES = VISUAL / "failures"
UPDATE = os.environ.get("PRAVAHA_UPDATE_BASELINES") == "1"

THEMES = ["light", "dark"]
VIEWPORTS = {"wide": (1280, 800), "narrow": (390, 844)}

#: At most this share of pixels may differ, each by more than THRESHOLD (0-255) in a channel.
TOLERANCE = 0.002
THRESHOLD = 24

#: Hidden before a screenshot: what changes with the clock rather than with the console.
MASK = """
canvas, .freshness, #ops-freshness, #count, .monaco-editor .cursors-layer,
.monaco-editor .current-line, .monaco-editor .scrollbar, .monaco-editor .decorationsOverviewRuler,
[data-volatile] { visibility: hidden !important; }
"""

SHOTS = [(name, path, ready) for name, path, _, ready in PAGES if name not in DOCUMENT_PAGES]


@pytest.fixture(scope="module")
def console():
    """A console of this module's own, over an untouched fake engine. The session's shared one
    has had queries registered and changes committed by the journeys by the time this runs,
    and a baseline must not depend on which tests ran first."""
    server = Console(BrowserEngine())
    try:
        yield server
    finally:
        server.close()


def _chrome_major(chrome: Browser) -> str:
    return str(chrome.version.get("product", "")).split("/")[-1].split(".")[0]


@pytest.fixture(scope="module")
def shooters(chrome: Browser, console: Console):
    tabs: dict[tuple[str, str], Page] = {}

    def get(theme: str, viewport: str) -> Page:
        key = (theme, viewport)
        if key not in tabs:
            width, height = VIEWPORTS[viewport]
            tab = chrome.new_page(width=width, height=height)
            tab.before_every_document(DETERMINISM)
            tab.before_every_document(theme_script(theme))
            tab.emulate(reduced_motion=True, scheme="dark" if theme == "dark" else "light")
            sign_in(tab, console)
            tabs[key] = tab
        return tabs[key]

    yield get
    for tab in tabs.values():
        tab.close()


@pytest.fixture(scope="module")
def comparer(chrome: Browser):
    tab = chrome.new_page(width=200, height=200)
    yield tab
    tab.close()


@pytest.fixture(scope="module")
def baseline_chrome(chrome: Browser):
    marker = BASELINES / "CHROME"
    current = _chrome_major(chrome)
    if UPDATE:
        BASELINES.mkdir(parents=True, exist_ok=True)
        marker.write_text(current + "\n", encoding="utf-8")
    recorded = marker.read_text(encoding="utf-8").strip() if marker.exists() else None
    return recorded, current


def _shoot(page: Page, console: Console, path: str, ready: str) -> bytes:
    open_page(page, console, path, ready)
    page.eval("""(() => { let s = document.getElementById('visual-mask');
        if (!s) { s = document.createElement('style'); s.id = 'visual-mask'; document.head.appendChild(s); }
        s.textContent = __MASK__; window.scrollTo(0, 0);
        if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
        return true; })()""".replace("__MASK__", json.dumps(MASK)))
    page.settle(quiet_ms=150)
    return page.screenshot()


@pytest.mark.parametrize("viewport", list(VIEWPORTS))
@pytest.mark.parametrize("theme", THEMES)
@pytest.mark.parametrize("name,path,ready", SHOTS, ids=[s[0] for s in SHOTS])
def test_every_page_matches_its_baseline(shooters, comparer, console, baseline_chrome,
                                         name, path, ready, theme, viewport):
    recorded, current = baseline_chrome
    shot = _shoot(shooters(theme, viewport), console, path, ready)
    baseline = BASELINES / f"{name}-{theme}-{viewport}.png"
    if UPDATE:
        baseline.write_bytes(shot)
        return
    if not baseline.exists():
        pytest.fail(f"no baseline {baseline.name}; take one with PRAVAHA_UPDATE_BASELINES=1 and review it")
    if recorded != current:
        pytest.skip(f"baselines were taken with Chrome {recorded}, this is Chrome {current}: text renders "
                    "differently across versions. Retake them with PRAVAHA_UPDATE_BASELINES=1 after review.")
    expected = baseline.read_bytes()
    if expected == shot:
        return
    result = compare_png(comparer, expected, shot, THRESHOLD)
    share = result["changed"] / max(1, result["total"])
    if result["sameSize"] and share <= TOLERANCE:
        return
    FAILURES.mkdir(parents=True, exist_ok=True)
    (FAILURES / f"{name}-{theme}-{viewport}.actual.png").write_bytes(shot)
    (FAILURES / f"{name}-{theme}-{viewport}.diff.png").write_bytes(base64.b64decode(result["diff"]))
    pytest.fail(f"{path} ({theme}, {viewport}) differs from its baseline in {result['changed']} pixels "
                f"({share:.3%}, tolerance {TOLERANCE:.1%}); see tests/visual/failures/{name}-{theme}-{viewport}.*.png")


def test_the_comparison_itself_catches_a_change(comparer, shooters, console):
    """A regression test that could never fail would be a picture gallery."""
    page = shooters("light", "wide")
    before = _shoot(page, console, "/catalog", "true")
    page.eval("document.querySelector('h1').textContent = 'Something else entirely'")
    after = page.screenshot()
    result = compare_png(comparer, before, after, THRESHOLD)
    assert result["changed"] / result["total"] > 0.0005, result["changed"]
    same = compare_png(comparer, before, before, THRESHOLD)
    assert same["changed"] == 0
