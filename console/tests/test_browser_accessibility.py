"""Zero axe violations, on every page, in light, dark, blue and green (design 23.14, 23.20).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

axe-core 4.13 (vendored under ``tests/vendor/axe-core``) runs inside Chrome on each page once
its islands have mounted, against WCAG 2.0/2.1/2.2 A and AA plus the landmark and heading
rules listed in ``browser_harness.AXE_BEST_PRACTICE``. Any violation fails the test, with
the rule, the element and axe's own explanation in the message.

Pages are audited as they first render and in the states a person gets them into: the
palette open, the workbench showing a refusal, a plan and a result, the register form, the
onboarding steps, a live view with rows in it, the drop confirmation. A dialog that is only
accessible while closed is not accessible.

What this does not prove, and the README says so: axe finds about a third to a half of
WCAG failures. Screen-reader behaviour, reading order, the quality of every accessible
name, and cognitive load need the manual audit design 23.14 also asks for.
"""
from __future__ import annotations

import pytest
from browser_harness import (
    DETERMINISM,
    PAGES,
    Console,
    axe,
    density_script,
    describe,
    fresh_console,
    open_page,
    settled,
    sign_in,
    theme_script,
)
from cdp import Browser, Page

pytestmark = pytest.mark.browser

THEMES = ["light", "dark", "terminal", "blue", "green"]


@pytest.fixture(scope="module")
def themed(chrome: Browser, console: Console):
    """One signed-in tab per theme, opened on first use and shared by the module's tests."""
    tabs: dict[str, Page] = {}

    def get(theme: str) -> Page:
        if theme not in tabs:
            tab = chrome.new_page()
            tab.before_every_document(DETERMINISM)
            tab.before_every_document(theme_script(theme))
            sign_in(tab, console)
            tabs[theme] = tab
        return tabs[theme]

    yield get
    for tab in tabs.values():
        tab.close()


def _assert_clean(page: Page, where: str) -> None:
    violations = axe(page)
    assert not violations, f"axe found {len(violations)} rule(s) violated on {where}:\n{describe(violations)}"


@pytest.mark.parametrize("theme", THEMES)
@pytest.mark.parametrize("name,path,ready", [(n, p, r) for n, p, _, r in PAGES], ids=[n for n, *_ in PAGES])
def test_every_page_has_no_axe_violations(themed, console, theme, name, path, ready):
    page = themed(theme)
    before = len(page.exceptions)
    open_page(page, console, path, ready)
    assert page.eval("document.documentElement.getAttribute('data-theme')") == theme
    # A page whose script threw is not audited as if it had worked.
    assert page.exceptions[before:] == [], f"{path} threw: {page.exceptions[before:]}"
    _assert_clean(page, f"{path} ({theme})")


@pytest.fixture(scope="module")
def compact(chrome: Browser, console: Console):
    """One signed-in tab per theme in the compact density (design 23.4). Density moves no
    colour, but it moves text onto tighter grounds and closer to its neighbours, so each theme
    is audited in it rather than light standing in for the rest (VIS-2)."""
    tabs: dict[str, Page] = {}

    def get(theme: str) -> Page:
        if theme not in tabs:
            tab = chrome.new_page()
            tab.before_every_document(DETERMINISM)
            tab.before_every_document(theme_script(theme))
            tab.before_every_document(density_script("compact"))
            sign_in(tab, console)
            tabs[theme] = tab
        return tabs[theme]

    yield get
    for tab in tabs.values():
        tab.close()


@pytest.mark.parametrize("theme", THEMES)
@pytest.mark.parametrize("name,path,ready", [(n, p, r) for n, p, _, r in PAGES], ids=[n for n, *_ in PAGES])
def test_every_page_in_compact_density_has_no_axe_violations(compact, console, theme, name, path, ready):
    page = compact(theme)
    open_page(page, console, path, ready)
    assert page.eval("document.documentElement.getAttribute('data-density')") == "compact"
    assert page.eval("document.documentElement.getAttribute('data-theme')") == theme
    _assert_clean(page, f"{path} (compact, {theme})")


@pytest.mark.parametrize("motion", ["moving", "reduced"])
@pytest.mark.parametrize("theme", THEMES)
def test_the_landing_page_signed_out_has_no_axe_violations(chrome, console, theme, motion):
    """The one page most people see first, as they see it: nobody signed in, the figure
    moving -- or, for somebody who asked for reduced motion, still."""
    page = chrome.new_page()
    try:
        page.before_every_document(DETERMINISM)
        page.before_every_document(theme_script(theme))
        page.emulate(reduced_motion=motion == "reduced", scheme="dark" if theme == "dark" else "light")
        open_page(page, console, "/", "window.PravahaLanding && (PravahaLanding.stills() > 0 "
                                      "|| PravahaLanding.frames() > 0)")
        assert "Sign in" in page.text("#rail-cta")
        assert page.exceptions == [], page.exceptions
        _assert_clean(page, f"the landing page, signed out ({theme}, {motion})")
    finally:
        page.close()


@pytest.mark.parametrize("theme", THEMES)
def test_the_open_palette_has_no_axe_violations(themed, console, theme):
    page = themed(theme)
    open_page(page, console, "/catalog", "true")
    page.press("k", "Control")
    page.wait_for("document.querySelectorAll('#palette-list [role=option]').length > 5")
    _assert_clean(page, f"the command palette ({theme})")
    page.press("Escape")


@pytest.mark.parametrize("theme", THEMES)
def test_the_workbench_in_use_has_no_axe_violations(themed, console, theme):
    page = themed(theme)
    open_page(page, console, "/workbench?new=1", "document.querySelector('.monaco-editor .view-lines')")
    page.click(".monaco-editor .view-lines")
    page.type("SELECT txn_id, user_id FROM txm WHERE amount > ?")
    page.wait_for("document.querySelector('.diag')")
    _assert_clean(page, f"the workbench showing a refusal ({theme})")

    page.eval("[...document.querySelectorAll('.diag .actions button')].find(b => b.textContent.includes('txn')).click()")
    page.wait_for("document.querySelector('.validity.ok')")
    for panel, ready in (("Explain", "document.querySelectorAll('svg g.plan-node').length === 3"),
                         ("Register", "document.querySelector('#reg-name')"),
                         ("Library", "document.querySelector('.panel-body [hidden]') !== null")):
        page.eval(f"[...document.querySelectorAll('.panel-tabs [role=tab]')].find(b => b.textContent.startsWith('{panel}')).click()")
        if panel == "Explain":
            page.eval("window.__wbExplain && window.__wbExplain()")
        page.wait_for(ready, timeout=20)
        page.settle(quiet_ms=200)
        _assert_clean(page, f"the workbench's {panel} panel ({theme})")

    page.eval("[...document.querySelectorAll('.panel-tabs [role=tab]')].find(b => b.textContent.startsWith('Run')).click()")
    page.focus("#wb-params")
    page.type("100")
    page.eval("window.__wbRun && window.__wbRun()")
    page.wait_for("document.querySelectorAll('.vgrid tbody td').length > 0")
    _assert_clean(page, f"the workbench with a result ({theme})")


@pytest.mark.parametrize("theme", THEMES)
def test_the_compare_panel_in_each_of_its_states_has_no_axe_violations(themed, console, theme):
    """The workbench's diff (23.7) through the states of 23.12 a person can put it in: never
    compared, compared, refreshing, out of date, unified, every operator shown, the engine's
    refusal of one side (partial) and its policy's (not permitted)."""
    page = themed(theme)
    ready = "document.querySelectorAll('#diff-result svg g.plan-node').length === 6"
    try:
        open_page(page, console, "/workbench?query=big_txn", "document.querySelector('.validity.ok')")
        page.eval("[...document.querySelectorAll('.panel-tabs [role=tab]')].find(b => b.textContent.startsWith('Compare')).click()")
        page.wait_for("document.querySelector('#diff-empty') && document.getElementById('diff-against').value !== ''")
        _assert_clean(page, f"the compare panel, never compared ({theme})")

        console.engine.down = True
        try:
            page.click("#diff-compare")
            page.wait_for("document.querySelector('#diff-error button')", timeout=20)
            _assert_clean(page, f"the compare panel, the engine unreachable ({theme})")
        finally:
            console.engine.down = False

        page.eval("document.getElementById('diff-against').value = 'q:hot';"
                  "document.getElementById('diff-against').dispatchEvent(new Event('change', {bubbles: true}))")
        page.click("#diff-compare")
        page.wait_for(ready + " && document.querySelector('.monaco-diff-editor .view-line')", timeout=20)
        page.settle(quiet_ms=300)
        _assert_clean(page, f"the compare panel, compared ({theme})")

        page.click("#diff-layout")
        page.wait_for("!document.querySelector('.monaco-diff-editor.side-by-side')")
        page.click("#diff-show-all")
        page.wait_for("document.querySelectorAll('#diff-changes li').length === 4")
        page.settle(quiet_ms=200)
        _assert_clean(page, f"the compare panel, unified with every operator ({theme})")
        page.click("#diff-layout")

        page.click(".wb-editor .monaco-editor .view-lines")
        page.press("End", "Control")
        page.type(" ")
        page.wait_for("document.querySelector('#diff-stale')")
        _assert_clean(page, f"the compare panel, out of date ({theme})")

        console.engine.plan_refused["hot"] = "reading plans needs one of the roles [ops]"
        page.click("#diff-compare")
        page.wait_for("document.querySelector('#diff-left-not-permitted')", timeout=20)
        _assert_clean(page, f"the compare panel, not permitted ({theme})")
        console.engine.plan_refused.clear()

        open_page(page, console, "/workbench?sql=SELECT+PLANONLY+FROM+txn&panel=diff&against=big_txn",
                  "document.querySelector('#diff-partial')")
        _assert_clean(page, f"the compare panel, partly compared ({theme})")
    finally:
        console.engine.plan_refused.clear()
        page.eval("try { localStorage.removeItem('pravaha.workbench.tabs') } catch (e) {} ; true")


@pytest.mark.parametrize("theme", THEMES)
def test_a_live_view_with_changes_has_no_axe_violations(themed, console, theme):
    page = themed(theme)
    open_page(page, console, "/views/big_txn/live", {n: r for n, _, _, r in PAGES}["live"])
    console.engine.commit({"txn_id": 7, "user_id": "u7", "amount": 700, "_weight": 1})
    console.engine.commit({"txn_id": 1, "user_id": "u1", "amount": 150, "_weight": -1})
    page.wait_for("Number(document.getElementById('c-changes').textContent) >= 2")
    _assert_clean(page, f"a live view with changes ({theme})")


@pytest.mark.parametrize("theme", THEMES)
def test_the_audit_trail_when_not_permitted_has_no_axe_violations(themed, console, theme):
    """The state the engine's refusal produces is a screen of its own, audited like one."""
    page = themed(theme)
    console.engine.audit_allowed = False
    try:
        open_page(page, console, "/admin/audit", "document.getElementById('audit-not-permitted')")
        _assert_clean(page, f"the audit trail, not permitted ({theme})")
    finally:
        console.engine.audit_allowed = True


@pytest.mark.parametrize("theme", THEMES)
def test_controls_the_policy_refuses_have_no_axe_violations(themed, console, theme):
    """The unauthorized state (23.12): disabled controls with the policy's reason beside them."""
    page = themed(theme)
    console.engine.administer_refused["hot"] = "administering 'hot' needs one of the roles [ops]"
    try:
        open_page(page, console, "/queries/hot", "document.getElementById('controls-refused')")
        _assert_clean(page, f"a query whose controls the policy refuses ({theme})")
    finally:
        console.engine.administer_refused.clear()


@pytest.mark.parametrize("theme", THEMES)
def test_a_replacement_in_flight_has_no_axe_violations(themed, console, theme):
    """The backfill and cutover screen carrying a job: the numbers, the throttle form, the
    controls, and the typed-name dialog a cutover opens."""
    page = themed(theme)
    console.engine.start_replacement(
        "big_txn", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500", [0],
        backfill="history", rate_limit=5000)
    try:
        console.engine.backfill_progress("big_txn", historyRows=412_000, liveRows=980,
                                         rowsPerSecond=4800.0, partitionsLive=2, lagSeconds=63.0)
        open_page(page, console, "/queries/big_txn/replacement",
                  "document.getElementById('rep-numbers')")
        _assert_clean(page, f"a replacement in flight ({theme})")

        console.engine.backfill_progress("big_txn", partitionsLive=4, historyComplete=True)
        open_page(page, console, "/queries/big_txn/replacement",
                  "document.getElementById('rep-cutover') && !document.getElementById('rep-cutover').disabled")
        page.click("#rep-cutover")
        page.wait_for("document.querySelector('#cutoverConfirmModal.show')")
        page.settle(quiet_ms=400)
        _assert_clean(page, f"the cutover confirmation ({theme})")
        page.press("Escape")
        page.wait_for("!document.querySelector('#cutoverConfirmModal.show')")

        console.engine.cut_over("big_txn")
        open_page(page, console, "/queries/big_txn/replacement",
                  "document.getElementById('rep-rollback-btn')")
        _assert_clean(page, f"a replacement that has cut over ({theme})")
    finally:
        console.engine.replacements_by_name.clear()


@pytest.mark.parametrize("theme", THEMES)
def test_a_plan_with_its_operator_numbers_has_no_axe_violations(themed, console, theme):
    """The plan graph carrying B6's numbers: the measured line inside each operator, the
    bottleneck's marking, and the detail card a selected operator opens."""
    page = themed(theme)
    open_page(page, console, "/workbench?query=hot&panel=explain",
              "document.querySelectorAll('svg g.plan-node').length === 3")
    page.wait_for("document.querySelector('svg g.plan-node.bottleneck')", timeout=20)
    _assert_clean(page, f"a plan with its operator numbers ({theme})")
    page.click("svg g.plan-node.bottleneck")
    page.wait_for("document.getElementById('operator-metrics')")
    _assert_clean(page, f"an operator's measured detail ({theme})")


@pytest.mark.parametrize("theme", THEMES)
def test_the_drop_confirmation_has_no_axe_violations(themed, console, theme):
    page = themed(theme)
    open_page(page, console, "/queries/hot", "!document.getElementById('dropModalTrigger').classList.contains('d-none')")
    page.click("#dropModalTrigger")
    page.wait_for("document.querySelector('#dropConfirmModal.show')")
    page.settle(quiet_ms=400)
    _assert_clean(page, f"the drop confirmation ({theme})")
    page.press("Escape")
    page.wait_for("!document.querySelector('#dropConfirmModal.show')")


@pytest.mark.parametrize("theme", THEMES)
def test_every_onboarding_step_has_no_axe_violations(chrome, theme):
    with fresh_console() as fresh:
        page = chrome.new_page()
        try:
            page.before_every_document(DETERMINISM)
            page.before_every_document(theme_script(theme))
            sign_in(page, fresh)
            page.wait_for("document.querySelector('#ds-name')")
            _assert_clean(page, f"onboarding, declaring a stream ({theme})")
            page.focus("#ds-name")
            page.type("txn")
            page.click("#start-app form button[type=submit]")
            page.wait_for("document.querySelector('#start-app button.choice')")
            page.click("#start-app section > div:last-child > button.btn-primary")
            page.wait_for("document.querySelectorAll('#start-app button.choice').length > 0 && document.querySelector('#s2')")
            page.click("#start-app button.choice")
            page.wait_for("document.querySelector('#start-app .validity.ok')")
            _assert_clean(page, f"onboarding, choosing a question ({theme})")
            page.click("#start-app .d-flex.gap-2.mt-3 button.btn-primary")
            page.wait_for("document.querySelector('#ob-name')")
            _assert_clean(page, f"onboarding, registering ({theme})")
            page.click("#start-app form button[type=submit]")
            page.wait_for("document.querySelector('#s4')")
            _assert_clean(page, f"onboarding, done ({theme})")
        finally:
            page.close()


def test_reduced_motion_is_honoured(chrome, console):
    """prefers-reduced-motion stops the skeleton shimmer and the new-row flash (design 23.14)."""
    page = chrome.new_page()
    try:
        page.emulate(reduced_motion=True)
        sign_in(page, console)
        page.goto(console.url("/catalog"))
        settled(page)
        duration = page.eval("""(() => { const d = document.createElement('div'); d.className = 'skeleton';
            document.body.appendChild(d); const s = getComputedStyle(d);
            return [s.animationName, s.animationDuration]; })()""")
        assert duration[0] == "none" or duration[1] in {"1e-05s", "0.00001s", "0s"}, duration
    finally:
        page.close()
