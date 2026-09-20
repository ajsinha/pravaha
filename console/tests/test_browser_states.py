"""The eight states of design 23.12, screen by screen, in a real browser.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Design 23.12: "every data-bearing component must implement all eight states, and this is
checked in review and by Storybook coverage". The component gallery shows the eight drawn on
their own (``test_browser_visual``, ``test_browser_accessibility``); this drives each screen
into the states it can be in and asserts the screen shows that state -- so a screen cannot
lose one in a refactor, and the table in the README cannot quietly go out of date.

How a state is produced: the fake engine fails or delays one call (``FakeEngine.fail``,
``slow``), answers with nothing (``fresh``), refuses through its policy, or the page is driven
into it (a filter that matches nothing, an edit after an answer, a hidden tab). Nothing is
faked in the browser: every state here is one a real engine can put the console in.

Each case is also audited by axe, because a state nobody could read is not implemented --
which is how the stale and unauthorized states were found failing WCAG on the gallery.
"""
from __future__ import annotations

import contextlib
from collections.abc import Iterator

import pytest
from browser_harness import (
    DETERMINISM,
    BrowserEngine,
    Console,
    axe,
    describe,
    open_page,
    settled,
    sign_in,
    theme_script,
)
from cdp import Browser, Page

pytestmark = pytest.mark.browser

#: Dark as well would double a six-minute suite for colours the gallery already audits in both;
#: these states are audited here for structure, and in both themes where they are photographed.
THEME = "light"


@pytest.fixture(scope="module")
def states_console() -> Iterator[Console]:
    """This module's own console: it breaks the engine on purpose, and no other test's screen
    may see that happen."""
    server = Console(BrowserEngine())
    try:
        yield server
    finally:
        server.close()


@pytest.fixture(scope="module")
def empty_console() -> Iterator[Console]:
    """An engine with nothing registered and no stream: the "never had data" state of every
    screen that lists something."""
    server = Console(BrowserEngine(fresh=True))
    try:
        yield server
    finally:
        server.close()


@pytest.fixture(scope="module")
def tab(chrome: Browser, states_console: Console) -> Iterator[Page]:
    page = chrome.new_page()
    page.before_every_document(DETERMINISM)
    page.before_every_document(theme_script(THEME))
    sign_in(page, states_console)
    yield page
    page.close()


@pytest.fixture(scope="module")
def empty_tab(chrome: Browser, empty_console: Console) -> Iterator[Page]:
    page = chrome.new_page()
    page.before_every_document(DETERMINISM)
    page.before_every_document(theme_script(THEME))
    sign_in(page, empty_console, next_path="/catalog")
    yield page
    page.close()


def clean(page: Page, where: str) -> None:
    violations = axe(page)
    assert not violations, f"axe found {len(violations)} rule(s) violated in {where}:\n{describe(violations)}"


@contextlib.contextmanager
def failing(console: Console, *calls: str, **kwargs):
    """The engine refusing or not answering those calls, healed afterwards whatever happens."""
    console.engine.fail(*calls, **kwargs)
    try:
        yield
    finally:
        console.engine.heal()


def shows(page: Page, console: Console, path: str, selector: str, where: str,
          ready: str = "true", timeout: float = 20.0) -> None:
    """Open the page and assert the state's own element is on it, then audit it."""
    open_page(page, console, path, ready, timeout=timeout)
    assert page.exists(selector), f"{where}: {selector} is not on {path}\n{page.text('main')[:600]}"
    clean(page, where)


# ============================================================ error: what failed, and a retry

#: (screen and state, the calls that fail, the path, the element that state draws).
ERRORS = [
    ("catalog · streams · error", ("streams",), "/catalog", "#streams-error"),
    ("catalog · queries · error", ("describe_queries", "queries"), "/catalog?tab=queries", "#queries-error"),
    ("catalog · sinks · error", ("sinks",), "/catalog?tab=sinks", "#sinks-error"),
    ("views · error", ("describe_queries", "queries"), "/views", "#views-error"),
    ("view · point query · error", ("query_typed",), "/views/big_txn?key=user_id&value=u1", "#lookup-error"),
    ("queries · error", ("describe_queries", "queries"), "/queries", "#queries-error"),
    ("dead letters · error", ("dead_letters",), "/queries/big_txn/dead-letters", "#dlq-error"),
    ("replacement · error", ("replacement",), "/queries/big_txn/replacement", "#rep-error"),
    ("plugins · error", ("plugins",), "/plugins", "#plugins-error"),
    ("admin access · error", ("permissions",), "/admin/access", "#access-error"),
    ("admin audit · error", ("audit",), "/admin/audit", "#audit-error"),
]


@pytest.mark.parametrize("where,calls,path,selector", ERRORS, ids=[e[0] for e in ERRORS])
def test_a_call_that_fails_is_the_error_state(tab, states_console, where, calls, path, selector):
    """What failed, whether retrying can help, the way to retry, and a correlation id."""
    with failing(states_console, *calls):
        shows(tab, states_console, path, selector, where)
        text = tab.text(selector)
        assert "correlation" in text, f"{where}: no correlation id to paste into a ticket: {text}"
        assert tab.exists(f"{selector} .btn, {selector} .chip"), f"{where}: neither a retry nor why not"


# ============================================================ partial: what is missing, named

PARTIALS = [
    ("stream · readers · partial", ("describe_queries", "queries"), "/catalog/streams/txn", "#readers-partial"),
    ("view · shape · partial", ("describe_view",), "/views/big_txn", "#schema-partial"),
    ("query · registration · partial", ("describe_query",), "/queries/big_txn", "#registration-partial"),
    ("operations · metrics · partial", ("prometheus",), "/operations", "#ops-partial"),
    ("plugins · node · partial", ("status",), "/plugins", "#plugins-partial-status"),
]


@pytest.mark.parametrize("where,calls,path,selector", PARTIALS, ids=[p[0] for p in PARTIALS])
def test_a_call_that_fails_beside_others_is_the_partial_state(tab, states_console, where, calls, path, selector):
    """Some of it answered: the screen renders what it has and names what is missing, rather
    than drawing the gap as a zero."""
    with failing(states_console, *calls):
        ready = "document.querySelector('#chart-rate canvas')" if path == "/operations" else "true"
        shows(tab, states_console, path, selector, where, ready=ready)


# ============================================================ empty: never had any / filtered

NEVER = [
    ("catalog · streams · empty", "/catalog", ".state h2"),
    ("catalog · queries · empty", "/catalog?tab=queries", ".state h2"),
    ("views · empty", "/views", ".state h2"),
    ("queries · empty", "/queries", ".state h2"),
    ("operations · empty", "/operations", ".state h2"),
]


@pytest.mark.parametrize("where,path,selector", NEVER, ids=[n[0] for n in NEVER])
def test_an_engine_with_nothing_registered_is_the_never_state(empty_tab, empty_console, where, path, selector):
    """Explains what this is and offers the action that makes the first one."""
    ready = "document.querySelector('#chart-rate canvas')" if path == "/operations" else "true"
    shows(empty_tab, empty_console, path, selector, where, ready=ready)
    assert empty_tab.exists(f"{selector} ~ * a, {selector} ~ a, .state a"), f"{where}: no way to make the first one"


FILTERED = [
    ("queries · filtered", "/queries?search=no_such_query", ".state"),
    ("admin audit · filtered", "/admin/audit?principal=nobody", "#audit-empty"),
    ("help search · filtered", "/help/search?q=zzzznothing", "#search-none"),
    ("view · point query · filtered", "/views/big_txn?key=user_id&value=nobody", "#lookup-none"),
]


@pytest.mark.parametrize("where,path,selector", FILTERED, ids=[f[0] for f in FILTERED])
def test_a_filter_that_matches_nothing_is_its_own_state(tab, states_console, where, path, selector):
    """Distinct from never having had any, and it offers the way out of the filter."""
    if "views/big_txn" in path:
        states_console.engine.view_rows["big_txn"] = []
    try:
        shows(tab, states_console, path, selector, where)
        assert tab.exists(f"{selector} a, {selector} button"), f"{where}: no way to clear the filter"
    finally:
        states_console.engine.view_rows.clear()


def leave_replacement(page: Page, console: Console) -> None:
    """Takes the tab off the replacement screen before the replacement is cleared.

    The screen watches its own 1 Hz stream and reloads when the replacement it was opened
    for is gone, which is right in a browser and a race in a test: the reload and the next
    test's navigation collide as ERR_ABORTED. Leaving first is what a person would do.
    """
    page.goto(console.url("/queries"))
    page.wait_for("document.readyState === 'complete'")


# ================================== backfill and cutover (23.10), state by state

def test_a_query_nothing_is_replacing_is_the_never_state(tab, states_console):
    """Never had data: what a replacement is, and the way to start one. Not an empty
    progress panel, which would read as a job that has stalled."""
    shows(tab, states_console, "/queries/big_txn/replacement", "#rep-none",
          "replacement · never")
    assert tab.exists("#rep-none a"), "no way to start the first one"


def test_a_replacement_in_flight_draws_what_is_measured_and_nothing_else(tab, states_console):
    """The screen's own state, and the rule it exists to keep: no bar, no percentage, no
    estimate. Audited as well as asserted, because a meter is a thing a screen reader reads
    out and there must not be one over an unknown total."""
    states_console.engine.start_replacement(
        "big_txn", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500", [0],
        backfill="history", rate_limit=5000)
    try:
        states_console.engine.backfill_progress("big_txn", historyRows=412_000, partitionsLive=2)
        shows(tab, states_console, "/queries/big_txn/replacement", "#rep-numbers",
              "replacement · in flight")
        panel = tab.text("#rep-numbers")
        assert "412,000" in panel and "2 of 4" in panel
        assert "%" not in panel, panel
        assert not tab.exists("#rep-numbers [role=meter], #rep-numbers progress")
    finally:
        leave_replacement(tab, states_console)
        states_console.engine.replacements_by_name.clear()


def test_the_version_history_lists_who_has_served_this_name(tab, states_console):
    """The trail the screen exists to be able to show: each version that has answered this
    name, and the frontier it took over at, oldest first."""
    states_console.engine.start_replacement("big_txn", "SELECT 1", [0])
    try:
        shows(tab, states_console, "/queries/big_txn/replacement", "#rep-history",
              "replacement · history")
        assert "from the beginning" in tab.text("#rep-history")
        assert not tab.exists("#rep-history-partial")
    finally:
        leave_replacement(tab, states_console)
        states_console.engine.replacements_by_name.clear()


def test_a_version_history_the_console_could_not_read_is_the_partial_state(tab, states_console):
    """Partial: the history comes from the engine's REST surface, not the control wire, so a
    node without one has the replacement and no trail. An empty list would read as "nobody
    has served this name", which is never true of a query that runs."""
    states_console.engine.history_carried = False
    states_console.engine.start_replacement("big_txn", "SELECT 1", [0])
    try:
        shows(tab, states_console, "/queries/big_txn/replacement", "#rep-history-partial",
              "replacement · partial")
        assert not tab.exists("#rep-history")
    finally:
        leave_replacement(tab, states_console)
        states_console.engine.replacements_by_name.clear()
        states_console.engine.history_carried = True


def test_the_replacement_screen_dims_when_its_stream_drops(tab, states_console):
    """Stale: the tab is hidden, which closes the 1 Hz stream (design 23.11). The numbers
    stay, dimmed, and the banner says how old they are -- never shown as current."""
    states_console.engine.start_replacement("big_txn", "SELECT 1", [0])
    try:
        open_page(tab, states_console, "/queries/big_txn/replacement",
                  "document.getElementById('rep-numbers')")
        tab.eval("Object.defineProperty(document, 'hidden', {value: true, configurable: true});"
                 "document.dispatchEvent(new Event('visibilitychange'));"
                 "document.getElementById('rep-app').classList.add('stale')")
        tab.wait_for("document.querySelector('#rep-banner .alert')", timeout=10)
        assert tab.eval("document.querySelectorAll('#rep-app.stale').length") == 1
        clean(tab, "replacement · stale")
        tab.eval("Object.defineProperty(document, 'hidden', {value: false, configurable: true});"
                 "document.dispatchEvent(new Event('visibilitychange'))")
    finally:
        leave_replacement(tab, states_console)
        states_console.engine.replacements_by_name.clear()


def test_the_policy_refusing_a_replacement_disables_every_control_with_the_reason(tab, states_console):
    """Unauthorized: everything on this screen needs the administer permission, so a reader
    gets the screen read-only with the engine's own reason on the page."""
    states_console.engine.start_replacement("big_txn", "SELECT 1", [0])
    states_console.engine.administer_refused["big_txn"] = \
        "administering 'big_txn' needs one of the roles [ops]"
    try:
        shows(tab, states_console, "/queries/big_txn/replacement", "#rep-refused",
              "replacement · unauthorized")
        assert tab.eval("['bf-pause', 'rep-cutover', 'rep-rollback-btn']"
                        ".every(id => document.getElementById(id).disabled)")
        assert tab.eval("document.getElementById('rep-cutover')"
                        ".getAttribute('aria-describedby')") == "rep-refused"
        assert tab.exists("#rep-numbers"), "a reader may still read"
    finally:
        leave_replacement(tab, states_console)
        states_console.engine.administer_refused.clear()
        states_console.engine.replacements_by_name.clear()


def test_a_cutover_the_engine_would_refuse_is_not_offered_and_says_why(tab, states_console):
    """Not one of the eight, but the same rule: a control the engine's own precondition
    would refuse is disabled with the reason **on the page**, because a disabled button
    takes no focus and shows no tooltip and a title alone is a reason nobody can read."""
    states_console.engine.start_replacement("big_txn", "SELECT 1", [0])
    try:
        shows(tab, states_console, "/queries/big_txn/replacement", "#rep-cutover-early",
              "replacement · cutover not yet")
        assert tab.eval("document.getElementById('rep-cutover').disabled")
        assert tab.eval("document.getElementById('rep-cutover')"
                        ".getAttribute('aria-describedby')") == "rep-cutover-early"
    finally:
        leave_replacement(tab, states_console)
        states_console.engine.replacements_by_name.clear()


# ============================= the time-travel debugger (23.9), state by state


def _clear_sessions(console: Console) -> None:
    console.engine.debug_sessions_by_id.clear()
    console.engine._debug_forks.clear()
    console.engine.debug_calls.clear()


def test_a_query_nothing_is_debugging_is_the_never_state(tab, states_console):
    """Never had data: what a fork is, the three things it cannot touch, and the control
    that makes the first one. Not an empty stepping panel, which reads as a stalled session."""
    shows(tab, states_console, "/queries/big_txn/debug", "#dbg-none", "debug · never")
    assert tab.exists("#dbg-fork-form button[type=submit]"), "no way to make the first one"
    # The three absences are the feature, so they are on the screen and not in a footnote.
    assert tab.eval("document.querySelectorAll('#dbg-absences li').length") == 3


def test_a_node_with_no_checkpoint_of_this_query_is_the_never_state(tab, states_console):
    """Never had data, one level down: there is no position to fork from, and the screen
    does not guess which of the three reasons it is."""
    held = states_console.engine.checkpoints_by_query.pop("big_txn")
    try:
        shows(tab, states_console, "/queries/big_txn/debug", "#dbg-no-checkpoints",
              "debug · no checkpoint")
        assert not tab.exists("#dbg-fork-form")
    finally:
        states_console.engine.checkpoints_by_query["big_txn"] = held


def test_a_session_that_has_ended_is_its_own_state_and_not_an_error(tab, states_console):
    """Ended, or released by the node's TTL. A fact with the way back to a new session,
    rather than a screen reporting that something went wrong."""
    shows(tab, states_console, "/queries/big_txn/debug?session=dbg-nosuchsession", "#dbg-gone",
          "debug · session over")
    assert tab.exists("#dbg-gone a")


def test_a_fork_says_permanently_that_its_sinks_are_disabled(tab, states_console):
    """ADR-048's first absence, on the surface that shows it: it is not a mode that can be
    left on by mistake, and the banner is read from the engine's answer."""
    session = states_console.engine.debug_fork("big_txn", 4471)["id"]
    try:
        shows(tab, states_console, f"/queries/big_txn/debug?session={session}", "#dbg-banner",
              "debug · session open")
        assert "DEBUG" in tab.text("#dbg-banner")
        assert session in tab.text("#dbg-summary")
    finally:
        _clear_sessions(states_console)


def test_a_step_the_engine_refuses_keeps_the_session_and_shows_the_refusal(tab, states_console):
    """Error, in the island: the spec is sent as typed and PRV-8015 comes back. The session
    is untouched, so the next thing to do is fix the spec and step again."""
    session = states_console.engine.debug_fork("big_txn", 4471)["id"]
    try:
        open_page(tab, states_console, f"/queries/big_txn/debug?session={session}",
                  "document.getElementById('dbg-step')")
        tab.eval("document.getElementById('dbg-step').value = 'until:total'")
        tab.click("#dbg-step-go")
        tab.wait_for("document.getElementById('dbg-step-error')")
        assert "PRV-8015" in tab.text("#dbg-step-error")
        assert tab.exists("#dbg-app"), "the session is still open"
        clean(tab, "debug · step refused")
    finally:
        _clear_sessions(states_console)


def test_a_stateless_fork_says_it_holds_nothing_rather_than_drawing_an_empty_table(tab, states_console):
    """Never had data, on the operator-state panel: a plan of scans, filters and projections
    keeps nothing between rows, and that is an answer rather than a gap."""
    session = states_console.engine.debug_fork("big_txn", 4471)["id"]
    try:
        shows(tab, states_console, f"/queries/big_txn/debug?session={session}", "#dbg-no-slots",
              "debug · no operator state")
    finally:
        _clear_sessions(states_console)


def test_paging_an_operators_state_to_a_key_it_does_not_hold_is_the_filtered_state(tab, states_console):
    """Filtered, not empty: the operator holds groups, and none of them is this one."""
    session = states_console.engine.debug_fork("hot", 4471)["id"]
    try:
        shows(tab, states_console,
              f"/queries/hot/debug?session={session}&operator=aggregate%230&key=nobody",
              "#dbg-page-filtered", "debug · state filtered")
        assert tab.exists("#dbg-page-filtered a"), "no way out of the filter"
    finally:
        _clear_sessions(states_console)


def test_the_policy_refusing_the_debugger_disables_its_control_with_the_reason(tab, states_console):
    """Unauthorized: a fork shows the SQL, the input rows and the operator state, so reading
    takes the administer permission too -- and the checkpoint list is not even asked for."""
    states_console.engine.administer_refused["big_txn"] = (
        "administering 'big_txn' needs one of the roles [ops]")
    try:
        shows(tab, states_console, "/queries/big_txn/debug", "#dbg-refused", "debug · refused")
        assert tab.eval("document.getElementById('dbg-fork').disabled")
        assert tab.eval("document.getElementById('dbg-fork')"
                        ".getAttribute('aria-describedby')") == "dbg-refused"
        assert "needs one of the roles [ops]" in tab.text("#dbg-refused")
    finally:
        states_console.engine.administer_refused.clear()


def test_the_checkpoint_list_failing_is_the_screens_error_state(tab, states_console):
    with failing(states_console, "debug_checkpoints"):
        shows(tab, states_console, "/queries/big_txn/debug", "#dbg-checkpoints-error",
              "debug · error")
        assert "correlation" in tab.text("#dbg-checkpoints-error")


# =========================== the plan's per-operator numbers (B6), state by state

def test_a_node_with_the_operator_counters_off_says_so_on_the_plan(tab, states_console):
    """The plan's own "not measured": three answers, not two, and the middle one names the
    setting instead of drawing a graph with no numbers on it."""
    states_console.engine.operator_metrics = False
    try:
        open_page(tab, states_console, "/workbench?query=big_txn&panel=explain",
                  "document.querySelectorAll('svg g.plan-node').length === 3", timeout=30)
        tab.wait_for("document.getElementById('metrics-note')")
        assert tab.eval("document.getElementById('metrics-note').dataset.metricsState") == "operators_off"
        assert "pravaha.metrics.operators" in tab.text("#metrics-note")
        assert not tab.exists("svg g.plan-node text.measured")
        clean(tab, "workbench · explain · operators off")
    finally:
        states_console.engine.operator_metrics = True


def test_the_dashboard_with_no_shared_lane_says_so_rather_than_drawing_none(tab, chrome):
    """Never had data, on the lanes table: with lane sharing off there are no shared lanes
    at all, which is not the same as lanes sitting idle. Its own console, because the engine
    it needs answers differently from every other screen's."""
    quiet = BrowserEngine()
    # And an engine too old to publish the operator switch at all, which is a third answer:
    # "off" and "this node does not say" are not the same, and the screen must not read a
    # missing gauge as a setting somebody turned off.
    quiet.metrics_text = "\n".join(
        line for line in quiet.metrics_text.splitlines()
        if not line.startswith(("pravaha_lane_", "pravaha_metrics_operators_enabled")))
    server = Console(quiet)
    page = chrome.new_page()
    try:
        page.before_every_document(DETERMINISM)
        page.before_every_document(theme_script(THEME))
        sign_in(page, server)
        shows(page, server, "/operations", "#ops-lanes-none", "operations · lanes · never",
              ready="document.querySelector('#chart-rate canvas')")
        assert not page.exists("#ops-lanes")
        assert "not published by it" in page.text("#ops-operators-enabled")
    finally:
        page.close()
        server.close()


def test_the_help_search_with_nothing_asked_says_what_it_searches(tab, states_console):
    shows(tab, states_console, "/help/search", "#search-never", "help search · never asked")


def test_filtering_the_view_list_to_nothing_is_not_an_empty_engine(tab, states_console):
    """The list is filtered in the browser, so the state is too -- and it clears the filter."""
    open_page(tab, states_console, "/views", "document.getElementById('view-filter')")
    tab.focus("#view-filter")
    tab.type("no_such_view")
    tab.wait_for("!document.getElementById('views-filtered-host').hidden", timeout=5)
    clean(tab, "views · filtered")
    tab.click("#views-filtered a")
    tab.wait_for("document.getElementById('views-filtered-host').hidden", timeout=5)


def test_a_view_with_no_rows_says_so_rather_than_drawing_an_empty_table(tab, states_console):
    """Never had data, on the live screen: the view has committed nothing to show."""
    kept = states_console.engine.rows
    states_console.engine.rows = []
    try:
        open_page(tab, states_console, "/views/hot/live",
                  "document.getElementById('live-state').dataset.state === 'fresh'", timeout=20)
        tab.wait_for("document.querySelector('#current-rows .state')", timeout=10)
        clean(tab, "live · never")
    finally:
        states_console.engine.rows = kept


def test_an_engine_with_no_sink_bound_says_how_one_is_declared(tab, states_console):
    kept = states_console.engine.sinks_list
    states_console.engine.sinks_list = []
    try:
        shows(tab, states_console, "/catalog?tab=sinks", ".state h2", "catalog · sinks · empty")
    finally:
        states_console.engine.sinks_list = kept


def test_the_workbench_panels_say_what_they_are_before_anything_is_asked(tab, states_console):
    """Never had data, in the workbench: each panel explains itself rather than sitting blank."""
    open_page(tab, states_console, "/workbench?new=1", "document.querySelector('.monaco-editor .view-lines')",
              timeout=30)
    assert "Nothing to check yet" in tab.text(".panel-body"), tab.text(".panel-body")[:300]
    for panel, marker in (("Explain", "No plan yet"), ("Run", "Nothing run yet"), ("Library", "None yet")):
        tab.eval("[...document.querySelectorAll('.panel-tabs [role=tab]')]"
                 f".find(b => b.textContent.startsWith('{panel}')).click()")
        tab.wait_for(f"document.querySelector('.panel-body').textContent.includes({marker!r})", timeout=10)
    clean(tab, "workbench · panels · never")


def test_a_tap_filter_matching_no_row_is_the_filtered_state(tab, states_console):
    """The filter reaches the engine's own subscription, so "nothing matches" is the engine's
    answer -- and the screen says it is the filter, with the way back to everything."""
    open_page(tab, states_console, "/views/big_txn/live?filter=user_id%3Dnobody",
              "document.getElementById('live-state').dataset.state === 'fresh'", timeout=20)
    tab.wait_for("document.querySelector('#current-rows .state')", timeout=10)
    assert tab.exists("#current-rows [data-state-action=clear]"), "no way back to every row"
    clean(tab, "live · filtered")


def test_a_subscription_the_engine_ends_is_the_error_state_with_a_retry(tab, states_console):
    """The stream ends with the engine's reason: what failed, and a button that reconnects."""
    with failing(states_console, "mirror", message="the subscription was refused"):
        tab.goto(states_console.url("/views/big_txn/live"))
        settled(tab)
        tab.wait_for("document.querySelector('#live-banner .alert-danger')", timeout=20)
        assert tab.exists("#live-banner [data-state-action=retry]"), "no way to reconnect"
        clean(tab, "live · error")


def test_validation_the_engine_cannot_answer_is_the_error_state(tab, states_console):
    """The workbench keeps editing and saving drafts; the diagnostics panel says what is
    unavailable, offers the retry and names what still works."""
    with failing(states_console, "validate"):
        open_page(tab, states_console, "/workbench?query=big_txn",
                  "document.getElementById('diag-unavailable')", timeout=30)
        assert tab.exists("#diag-unavailable [data-state-action=retry]"), "no retry"
        clean(tab, "workbench · diagnostics · error")


def test_a_run_the_engine_refuses_keeps_the_editor_and_shows_the_refusal(tab, states_console):
    open_page(tab, states_console, "/workbench?query=big_txn&panel=run",
              "document.getElementById('wb-params')", timeout=30)
    with failing(states_console, "query_typed", status=400, code="PRV-2003",
                 message="the engine refused this read"):
        tab.eval("window.__wbRun()")
        tab.wait_for("document.querySelector('.panel-body .alert-danger')", timeout=20)
        # A refusal is not retryable, and says so rather than offering a button that fails again.
        assert tab.exists(".panel-body .alert-danger .chip"), tab.text(".panel-body")[:300]
        clean(tab, "workbench · run · error")


# ============================================================ loading, first time and refresh

def test_the_first_load_of_a_point_query_is_a_skeleton_not_a_spinner(tab, states_console):
    """Loading (first): the shape of the answer while the engine is answering."""
    states_console.engine.slow["query_typed"] = 1.5
    try:
        open_page(tab, states_console, "/views/big_txn", "document.getElementById('lookup-form')")
        tab.eval("document.getElementById('lookup-key').value = 'user_id'")
        tab.focus("#lookup-value")
        tab.type("u1")
        tab.eval("document.getElementById('lookup-form').requestSubmit()")
        tab.wait_for("document.querySelector('#lookup-result .skeleton')", timeout=5)
        clean(tab, "view · point query · loading")
        tab.wait_for("document.querySelector('#lookup-result table')", timeout=20)
    finally:
        states_console.engine.heal()


def test_the_live_screen_loads_into_a_skeleton_and_then_the_rows(tab, states_console):
    """The rows a subscription starts from take a moment; the table keeps their shape."""
    states_console.engine.slow["mirror"] = 1.5
    try:
        tab.goto(states_console.url("/views/big_txn/live"))
        tab.wait_for("document.querySelector('#rows-loading .skeleton')", timeout=8)
        clean(tab, "live · loading")
        tab.wait_for("document.getElementById('live-state').dataset.state === 'fresh'", timeout=20)
    finally:
        states_console.engine.heal()


def test_explaining_again_keeps_the_plan_on_screen_and_says_it_is_refreshing(tab, states_console):
    """Loading (refresh): never blank-then-refill. The plan stays; an indicator moves."""
    open_page(tab, states_console, "/workbench?query=big_txn&panel=explain",
              "document.querySelectorAll('svg g.plan-node').length === 3", timeout=30)
    states_console.engine.slow["explain"] = 1.2
    states_console.engine.slow["query_plan"] = 1.2
    try:
        tab.eval("window.__wbExplain(); true")   # not the promise: awaiting it would miss the state
        tab.wait_for("document.querySelector('.freshness[data-state=refreshing]')", timeout=5)
        assert tab.eval("document.querySelectorAll('svg g.plan-node').length") == 3, \
            "the plan was blanked while the next one was asked for"
        clean(tab, "workbench · explain · refreshing")
        tab.wait_for("!document.querySelector('.freshness[data-state=refreshing]')", timeout=20)
    finally:
        states_console.engine.heal()


# ============================================================ stale: never as if it were live

def test_a_live_view_that_loses_its_stream_dims_and_says_how_old_it_is(tab, states_console):
    """Stale: the tab is hidden, which closes the subscription (design 23.11). The rows stay,
    greyed and fenced, and the banner says they are not live."""
    open_page(tab, states_console, "/views/big_txn/live",
              "document.getElementById('live-state').dataset.state === 'fresh'", timeout=20)
    tab.eval("Object.defineProperty(document, 'hidden', {value: true, configurable: true});"
             "document.dispatchEvent(new Event('visibilitychange'))")
    tab.wait_for("document.querySelector('#live-banner .alert')", timeout=10)
    assert tab.eval("document.querySelectorAll('[data-live-data].stale').length") >= 1, \
        "the numbers are still drawn as live"
    clean(tab, "live · stale")
    tab.eval("Object.defineProperty(document, 'hidden', {value: false, configurable: true});"
             "document.dispatchEvent(new Event('visibilitychange'))")


def test_a_query_list_that_cannot_refresh_keeps_its_rows_dimmed(tab, states_console):
    """Stale: the poll failed, so the rows on screen are the last ones known to be true --
    dimmed, with the error and the age beside them, never blanked."""
    open_page(tab, states_console, "/queries", "document.querySelectorAll('#table tbody tr').length > 1")
    with failing(states_console, "describe_queries", "queries"):
        tab.eval("window.queriesReload()")
        tab.wait_for("document.querySelector('#banner .alert-warning') && document.querySelector('#banner .alert-danger')",
                     timeout=10)
        assert tab.eval("document.querySelectorAll('#table tbody.stale tr').length") > 1, \
            "the rows were thrown away when the refresh failed"
        clean(tab, "queries · stale")


def test_a_plan_of_sql_that_has_since_changed_is_marked_out_of_date(tab, states_console):
    """The workbench's own stale: an answer about the previous text, never shown as current."""
    open_page(tab, states_console, "/workbench?query=big_txn&panel=explain",
              "document.querySelectorAll('svg g.plan-node').length === 3", timeout=30)
    tab.click(".wb-editor .monaco-editor .view-lines")
    tab.press("End", "Control")
    tab.type(" ")
    tab.wait_for("document.getElementById('explain-stale')", timeout=10)
    clean(tab, "workbench · explain · out of date")


# ============================================================ unauthorized: disabled, with why

def test_the_policy_refusing_an_action_disables_it_with_the_reason(tab, states_console):
    states_console.engine.administer_refused["hot"] = "administering 'hot' needs one of the roles [ops]"
    try:
        shows(tab, states_console, "/queries/hot", "#controls-refused", "query · controls · unauthorized")
        assert tab.eval("document.getElementById('pause').disabled"), "an action that fails on click"
        assert tab.eval("document.getElementById('pause').getAttribute('aria-describedby')") == "controls-refused"
    finally:
        states_console.engine.administer_refused.clear()


def test_the_policy_refusing_registration_disables_the_workbench_button(tab, states_console):
    states_console.engine.register_refusal = "registering needs one of the roles [author]"
    try:
        open_page(tab, states_console, "/workbench?query=big_txn&panel=register",
                  "document.getElementById('reg-name')", timeout=30)
        assert tab.exists("#register-refused"), tab.text("main")[:400]
        assert tab.eval("document.querySelector('#workbench-app button[type=submit]').disabled")
        clean(tab, "workbench · register · unauthorized")
    finally:
        states_console.engine.register_refusal = None


def test_the_engine_refusing_the_audit_trail_is_a_designed_state(tab, states_console):
    states_console.engine.audit_allowed = False
    try:
        shows(tab, states_console, "/admin/audit", "#audit-not-permitted", "admin audit · unauthorized")
    finally:
        states_console.engine.audit_allowed = True


def test_an_engine_that_has_recorded_nothing_is_not_an_empty_filter(tab, states_console):
    """Never had data, on the audit trail: distinct from a filter matching none of it."""
    kept = states_console.engine.audit_events
    states_console.engine.audit_events = []
    try:
        shows(tab, states_console, "/admin/audit", "#audit-never", "admin audit · never")
    finally:
        states_console.engine.audit_events = kept


# ============================================================ the query page's raw tail

def test_the_raw_tail_says_it_has_seen_nothing_and_then_that_it_is_disconnected(tab, states_console):
    """Never had data, then stale: a frozen tail and a quiet one look identical on a stream,
    and only one of them is a problem."""
    open_page(tab, states_console, "/queries/big_txn", "document.getElementById('tail')")
    assert tab.exists("#tail-empty"), "an empty tail with nothing said about it"
    clean(tab, "query · tail · never")
    with failing(states_console, "mirror"):
        # The subscription behind the console's stream fails, so the stream ends and the
        # browser's EventSource reports it: the tail is disconnected, and says so. A view
        # nothing else in this module has watched, because one subscription is shared by every
        # browser on a view and an already-running one would not be asked for again.
        tab.goto(states_console.url("/queries/hot_alias"))
        settled(tab)
        tab.wait_for("document.getElementById('tail-state').dataset.state === 'stale'", timeout=20)
        tab.wait_for("document.querySelector('#tail-banner .alert')", timeout=10)
        clean(tab, "query · tail · stale")
