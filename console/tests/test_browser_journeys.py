"""The critical journeys, in a real browser, against the real console.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Design 23.18 names eight critical journeys, and all eight are here. Two walk end to end:
first run to a live view that changes, and author-validate-explain-deploy. The other six walk
as far as the console and the engine go today and stop where an engine feature that does not
exist would take over -- backpressure sampling, a readable DLQ, backfill jobs, blue/green
cutover, the time-travel debugger, an API for grants. Each stop is asserted (the console says
what is missing, or offers nothing), never simulated, and each test's docstring names what it
waits on; the README's 23.20 table lists them.

Each journey drives Chrome the way a person would: typing into fields, clicking with the
mouse or pressing keys, and reading what the page then shows. Nothing is called on the
server behind the page's back, except that the fake engine is told to commit a change when
the journey needs one to arrive, or to answer with a policy a grant has changed -- the things
a person could not do from a browser.

Skipped, with the reason, when this machine has no Chrome (see ``browser_harness``).
"""
from __future__ import annotations

import time

import pytest
from browser_harness import (
    PASSWORD,
    fresh_console,
    own_console,
    settled,
    sign_in,
)

pytestmark = pytest.mark.browser


# ============================================================ 1. first run -> a running query

def test_first_run_onboarding_reaches_a_live_view_that_changes(page):
    """Sign in on an empty engine, declare a stream, pick a question, register it, watch it
    change, and find it in the catalog and healthy on the operations dashboard."""
    with fresh_console(default_role="analyst") as fresh:
        sign_in(page, fresh, role="analyst")
        # An engine with nothing registered is a first run for whoever opens it.
        assert page.url().endswith("/start")
        page.wait_for("document.querySelector('#ds-name')")

        # Step 1: declare a stream -- the schema field comes prefilled with a sensible one.
        page.focus("#ds-name")
        page.type("txn")
        page.click("#start-app form button[type=submit]")
        page.wait_for("[...document.querySelectorAll('#start-app button.choice')].some(b => b.textContent.includes('txn'))")
        assert fresh.engine.streams_list[-1]["eventTime"] == "event_time"
        page.click("#start-app section > div:last-child > button.btn-primary")

        # Step 2: a question written against the stream's own columns, validated by the planner.
        page.wait_for("document.querySelector('#s2') && document.querySelectorAll('#start-app button.choice').length > 0")
        page.click("#start-app button.choice")
        page.wait_for("document.querySelector('#start-app .validity.ok')")
        page.click("#start-app .d-flex.gap-2.mt-3 button.btn-primary")

        # Step 3: register it under a name of our choosing.
        page.wait_for("document.querySelector('#ob-name')")
        page.eval("document.getElementById('ob-name').select()")
        page.focus("#ob-name")
        page.eval("document.getElementById('ob-name').value = ''")
        page.type("first_view")
        page.click("#start-app form button[type=submit]")
        page.wait_for("document.querySelector('#s4')")
        assert "first_view" in page.text("#start-app")
        assert fresh.engine.registered[-1]["name"] == "first_view"

        # Step 4: watch it change. The engine commits a change; the page shows it with its weight.
        page.wait_for_navigation(lambda: page.click("#start-app a.btn-primary"))
        assert page.url().endswith("/views/first_view/live")
        page.wait_for("document.querySelector('#live-state') && document.querySelector('#live-state').dataset.state === 'fresh'"
                      " || document.querySelector('#live-state').textContent.includes('connected')")
        fresh.engine.commit({"txn_id": 3, "user_id": "u3", "amount": 500, "_weight": 1})
        page.wait_for("document.getElementById('c-changes').textContent === '1'", timeout=10)
        assert page.text("#c-plus") == "1"
        assert "u3" in page.text("#live-app")

        # It is in the catalog...
        page.goto(fresh.url("/catalog?tab=queries"))
        assert "first_view" in page.text("main")
        # ...and the operations dashboard does not call it a problem.
        page.goto(fresh.url("/operations"))
        settled(page)
        verdict = page.text("#verdict")
        assert verdict and "first_view" not in page.text("#findings"), verdict


# ============================================================ 2. author, validate, explain, run

def test_workbench_diagnoses_fixes_explains_and_runs(page, console):
    sign_in(page, console, role="analyst", next_path="/workbench?new=1")
    page.goto(console.url("/workbench?new=1"))
    page.wait_for("document.querySelector('.monaco-editor .view-lines')", timeout=20)
    # Monaco edits through the EditContext API, so text reaches it the way it reaches a person's
    # editor: click into it, then type.
    page.click(".monaco-editor .view-lines")
    page.type("SELECT txn_id, user_id FROM txm")

    # The diagnostic arrives as the planner answers, with its code and a certain fix.
    page.wait_for("document.querySelector('.diag')", timeout=10)
    diag = page.text(".diag")
    assert "PRV-2003" in diag and "txm" in diag
    page.wait_for("document.querySelector('.monaco-editor [class*=squiggly]')", timeout=5)
    fix = page.eval("[...document.querySelectorAll('.diag .actions button')].map(b => b.textContent)")
    assert any("txn" in f for f in fix), fix
    page.eval("[...document.querySelectorAll('.diag .actions button')].find(b => b.textContent.includes('txn')).setAttribute('data-test', 'fix')")
    page.click("[data-test=fix]")
    page.wait_for("document.querySelector('.validity.ok')", timeout=10)
    assert "FROM txn" in page.eval("document.querySelector('.monaco-editor .view-lines').textContent.replace(/\\u00a0/g, ' ')")

    # Explain draws the engine's plan as a graph: one node per operator, laid out by ELK.
    page.eval("[...document.querySelectorAll('.wb-toolbar button')].find(b => b.textContent.includes('Explain')).setAttribute('data-test', 'explain')")
    page.click("[data-test=explain]")
    page.wait_for("document.querySelectorAll('svg g.plan-node').length === 3", timeout=20)
    labels = page.eval("[...document.querySelectorAll('svg g.plan-node')].map(g => g.getAttribute('aria-label'))")
    assert any(label.startswith("Scan") for label in labels), labels

    # Run returns rows into the grid.
    page.eval("[...document.querySelectorAll('.wb-toolbar button')].find(b => b.textContent.includes('Run')).setAttribute('data-test', 'run')")
    page.click("[data-test=run]")
    page.wait_for("document.querySelectorAll('.vgrid tbody td').length > 0", timeout=10)
    grid = page.text(".vgrid")
    assert "u1" in grid and "u2" in grid

    # Deploy: register it with a key picked by name, and the engine receives exactly that.
    page.eval("[...document.querySelectorAll('.wb-toolbar button')].find(b => b.textContent.includes('Register')).setAttribute('data-test', 'register')")
    page.click("[data-test=register]")
    page.wait_for("document.querySelector('#reg-name')")
    page.click("#reg-name")
    page.type("wb_view")
    page.click("#key-txn_id")
    page.eval("[...document.querySelectorAll('form button[type=submit]')].find(b => b.textContent.includes('Register continuous')).setAttribute('data-test', 'submit')")
    # Enabled only once the query validates, it has a name and a key is chosen.
    page.wait_for("!document.querySelector('[data-test=submit]').disabled")
    page.click("[data-test=submit]")
    page.wait_for("document.querySelector('.alert-success') && document.querySelector('.alert-success').textContent.includes('wb_view')")
    registered = console.engine.registered[-1]
    assert registered["name"] == "wb_view" and registered["sql"] == "SELECT txn_id, user_id FROM txn"
    assert page.exceptions == [], page.exceptions


# ============================================================ 3. the command palette

def test_the_palette_opens_on_ctrl_k_filters_and_navigates_by_keyboard(page, console):
    sign_in(page, console)
    page.goto(console.url("/catalog"))
    settled(page)
    page.press("k", "Control")
    page.wait_for("document.querySelector('.palette[role=dialog]')")
    assert page.active()["role"] == "combobox"
    page.wait_for("document.querySelectorAll('#palette-list [role=option]').length > 5")
    page.type("hot_al")
    page.wait_for("document.querySelector('#palette-list [aria-selected=true]') && "
                  "document.querySelector('#palette-list [aria-selected=true]').textContent.includes('hot_alias')")
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert page.url().endswith("/queries/hot_alias")


def test_escape_closes_the_palette_and_gives_focus_back(page, console):
    sign_in(page, console)
    page.goto(console.url("/views"))
    settled(page)
    page.focus("#palette-trigger")
    page.press("Enter")
    page.wait_for("document.querySelector('.palette[role=dialog]')")
    page.wait_for("document.activeElement.getAttribute('role') === 'combobox'")
    page.press("Tab")  # focus stays inside the dialog
    assert page.active()["role"] == "combobox"
    page.press("Escape")
    page.wait_for("!document.querySelector('.palette')")
    assert page.active()["id"] == "palette-trigger"


def test_a_lifecycle_action_from_the_palette_reaches_the_engine(page, console):
    sign_in(page, console)
    page.goto(console.url("/catalog"))
    settled(page)
    page.press("k", "Control")
    page.wait_for("document.querySelectorAll('#palette-list [role=option]').length > 5")
    page.type("Pause big")
    page.wait_for("document.querySelector('#palette-list [aria-selected=true]').textContent.includes('Pause big_txn')")
    page.press("Enter")
    page.wait_for("!document.querySelector('.palette')")
    assert ("pause", "big_txn") in console.engine.lifecycle_calls


# ============================================================ 4. keyboard-only paths

def test_the_first_tab_stop_is_the_skip_link_and_it_moves_focus_to_main(page, console):
    sign_in(page, console)
    page.goto(console.url("/catalog"))
    settled(page)
    page.press("Tab")
    first = page.active()
    assert first["href"] == "#main" and first["visible"], first
    # Visible when focused: it slides on screen rather than staying at left:-9999px.
    assert page.eval("document.activeElement.getBoundingClientRect().left >= 0")
    page.press("Enter")
    page.wait_for("document.activeElement && document.activeElement.id === 'main'")


def test_tab_order_follows_the_page_and_every_stop_shows_focus(page, console):
    sign_in(page, console)
    page.goto(console.url("/catalog"))
    settled(page)
    stops = []
    for _ in range(14):
        page.press("Tab")
        stops.append(page.active())
    labels = [s["text"] or s["label"] for s in stops]
    # Brand, then the six sections in the order the bar shows them, then the chrome's controls.
    nav = [label for label in labels if label in {"Workbench", "Catalog", "Views", "Operations", "Queries", "Help"}]
    assert nav == ["Workbench", "Catalog", "Views", "Operations", "Queries", "Help"], labels
    for stop in stops:
        assert stop["visible"], stop
        assert stop["outline"] or stop["shadow"], f"no visible focus indicator on {stop}"


def test_a_view_can_be_found_and_queried_without_a_mouse(page, console):
    """Sign in, jump to a view from the palette, and ask it a point query -- keys only."""
    page.goto(console.url("/login?next=/home"))
    settled(page)
    page.focus("#password")
    page.type(PASSWORD)
    page.wait_for_navigation(lambda: page.press("Enter"))
    page.press("k", "Control")
    page.wait_for("document.querySelectorAll('#palette-list [role=option]').length > 5")
    page.type("big_txn browse")
    page.wait_for("document.querySelector('#palette-list [aria-selected=true]').textContent.includes('browse view')")
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert page.url().endswith("/views/big_txn")
    settled(page)
    page.focus("#lookup-key")
    # Walk to the value field with Tab, as a person would, and type into it.
    for _ in range(3):
        if page.active()["id"] == "lookup-value":
            break
        page.press("Tab")
    assert page.active()["id"] == "lookup-value"
    page.type("u1")
    page.press("Enter")
    page.wait_for("document.getElementById('lookup-result') && document.getElementById('lookup-result').textContent.includes('u1')")


def test_escape_closes_the_drop_confirmation_and_focus_returns(page, console):
    sign_in(page, console)
    page.goto(console.url("/queries/hot"))
    settled(page)
    page.wait_for("!document.getElementById('dropModalTrigger').classList.contains('d-none')")
    page.focus("#dropModalTrigger")
    page.press("Enter")
    page.wait_for("document.querySelector('#dropConfirmModal.show')")
    page.wait_for("document.getElementById('dropConfirmModal').contains(document.activeElement)")
    page.press("Escape")
    page.wait_for("!document.querySelector('#dropConfirmModal.show')")
    page.wait_for("document.activeElement && document.activeElement.id === 'dropModalTrigger'")
    assert console.engine.lifecycle_calls == [] or ("drop", "hot") not in console.engine.lifecycle_calls


def test_workbench_draft_tabs_follow_the_aria_tabs_pattern(page, console):
    sign_in(page, console, role="analyst")
    page.goto(console.url("/workbench?new=1"))
    page.wait_for("document.querySelector('.monaco-editor .view-lines')", timeout=20)
    page.click(".wb-new")
    page.wait_for("document.querySelectorAll('.wb-tabs [role=tab]').length === 2")
    # A new draft puts the cursor in the editor; the keyboard then goes back to the tab strip.
    page.wait_for("document.activeElement.closest('.monaco-editor')")
    page.focus(".wb-tabs [role=tab][aria-selected=true]")
    page.press("ArrowLeft")
    page.wait_for("document.activeElement.getAttribute('role') === 'tab' && "
                  "document.activeElement.getAttribute('aria-selected') === 'true' && "
                  "document.activeElement === document.querySelectorAll('.wb-tabs [role=tab]')[0]")
    page.press("Delete")
    page.wait_for("document.querySelectorAll('.wb-tabs [role=tab]').length === 1")
    assert page.active()["role"] == "tab"


# ============================================================ 5. the operations journey

def test_operations_shows_a_verdict_findings_and_goes_live(page, console):
    sign_in(page, console)
    page.goto(console.url("/operations"))
    settled(page)
    page.wait_for("document.querySelector('#chart-rate canvas')", timeout=15)
    assert "State ceiling nearly reached" in page.text("#findings")
    # The SSE stream keeps it current: the freshness indicator reports a live connection.
    page.wait_for("document.getElementById('ops-freshness') && document.getElementById('ops-freshness').dataset.state !== 'stale'",
                  timeout=10)
    assert page.exceptions == [], page.exceptions


def test_the_plugins_page_names_health_bindings_and_what_is_not_published(page, console):
    sign_in(page, console)
    page.goto(console.url("/plugins"))
    body = page.text("main")
    assert "filesystem" in body and page.exists("#plugin-cards .chip.ok")
    assert "feeds these streams" in body.lower() and "writes these sinks" in body.lower()
    assert "nope" in body and "not loaded" in body
    assert "Not published by the engine" in body
    assert "Needs plugin API" in body and "health not reported" in body


# ============================================================ 6. the admin persona: access and the audit trail

def test_the_admin_persona_lands_on_access_and_searches_the_audit_trail_by_keyboard(page, console):
    """Sign in as the admin persona, land on Access, reach the audit trail from the palette,
    page back by its cursor and filter it with the form -- keys only after sign-in -- then see
    the screen the engine's refusal produces."""
    sign_in(page, console, role="admin")
    assert page.url().endswith("/admin/access")
    assert "read the audit trail" in page.text("#access-decisions")

    page.press("k", "Control")
    page.wait_for("document.querySelectorAll('#palette-list [role=option]').length > 5")
    page.type("audit trail")
    page.wait_for("document.querySelector('#palette-list [aria-selected=true]').textContent.includes('Audit trail')")
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert page.url().endswith("/admin/audit")
    settled(page)
    assert page.eval("document.querySelectorAll('#audit-events tbody tr').length") == 50

    # The next page is a link carrying the engine's cursor: reachable, and deep-linkable.
    page.focus("a[rel=next]")
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert "cursor=" in page.url()
    assert page.eval("document.querySelectorAll('#audit-events tbody tr').length") == 20
    assert "oldest readable decision" in page.text("main")

    # Filter by typing into the form and pressing Enter: the filter lands in the URL.
    page.focus("#af-principal")
    page.type("carol")
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert "principal=carol" in page.url() and "cursor=" not in page.url()
    principals = page.eval(
        "[...document.querySelectorAll('#audit-events tbody tr td:nth-child(3) a')].map(a => a.textContent)")
    assert principals and set(principals) == {"carol"}

    console.engine.audit_allowed = False
    try:
        page.goto(console.url("/admin/audit"))
        settled(page)
        assert page.exists("#audit-not-permitted")
        assert "Not permitted" in page.text("#audit-not-permitted")
        assert not page.exists("#audit-events")
    finally:
        console.engine.audit_allowed = True
    assert page.exceptions == [], page.exceptions


# ============================================================ help: search -> topic -> related -> full reference

def test_help_search_to_a_topic_to_a_related_one_to_its_full_reference(page, console):
    """Somebody with a word, not a URL: filter the index, search every page, open the topic,
    follow a related topic from its footer, and land in the long-form guide its "Full reference"
    names -- by keyboard where a person would use one, and anonymously, because help is public."""
    page.goto(console.url("/help"))
    settled(page)
    # Typing filters the cards in place, and a category with nothing left is hidden.
    page.focus("#help-search")
    page.type("watermark")
    page.wait_for("[...document.querySelectorAll('.help-item')].some(i => i.hidden)")
    visible = page.eval("[...document.querySelectorAll('.help-item:not([hidden]) a')].map(a => a.getAttribute('href'))")
    assert "/help/topics/event-time-watermarks" in visible
    assert page.eval("[...document.querySelectorAll('.help-cat')].some(c => c.hidden)")

    # Enter searches every page's text, on the server.
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert "/help/search?q=watermark" in page.url()
    first = page.eval("document.querySelector('#search-results a').getAttribute('href')")
    assert first == "/help/topics/event-time-watermarks"

    # The topic.
    page.wait_for_navigation(lambda: page.click("#search-results a"))
    assert page.url().endswith("/help/topics/event-time-watermarks")
    assert page.exists(".help-footer .help-companion")

    # A related topic, from the shared footer, by keyboard.
    related = page.eval("document.querySelector('.help-related .chip-links a').getAttribute('href')")
    assert related.startswith("/help/topics/") and related != "/help/topics/event-time-watermarks"
    page.focus(".help-related .chip-links a")
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert page.url().endswith(related)

    # And its full reference: a long-form guide rendered in place, at the section it names.
    target = page.eval("document.querySelector('.help-companion').getAttribute('href')")
    page.wait_for_navigation(lambda: page.click(".help-companion"))
    assert page.url().endswith(target)
    assert page.exists(".doc")
    if "#" in target:
        anchor = target.split("#", 1)[1]
        assert page.eval(f"!!document.getElementById({anchor!r})"), f"the guide has no #{anchor}"
    assert page.exceptions == [], page.exceptions


def test_a_code_on_a_help_page_opens_its_own_page(page, console):
    page.goto(console.url("/help/topics/sql-refusals"))
    settled(page)
    code = page.eval("document.querySelector('.doc a.prv') && document.querySelector('.doc a.prv').textContent")
    assert code and code.startswith("PRV-")
    page.wait_for_navigation(lambda: page.click(".doc a.prv"))
    assert page.url().endswith(f"/help/codes/{code}")
    assert code in page.text("h1")


def test_a_screen_s_question_mark_opens_its_help(page, console):
    """Contextual help: the "?" beside the workbench's heading opens the topic that answers the
    question the workbench provokes, and the cards at its foot are real topics."""
    sign_in(page, console)
    page.goto(console.url("/workbench"))
    settled(page)
    href = page.eval("document.querySelector('h1 .screen-help').getAttribute('href')")
    assert href == "/help/topics/sql-reference"
    cards = page.eval("[...document.querySelectorAll('.helpcards a')].map(a => a.getAttribute('href'))")
    assert len(cards) == 4 and all(c.startswith("/help/topics/") for c in cards)
    assert "/help/topics/compare-versions" in cards
    page.wait_for_navigation(lambda: page.click("h1 .screen-help"))
    assert page.url().endswith(href)


# ============================================================ the other six journeys of design 23.18
#
# Each walks as far as the console and the engine can go today, and stops where an engine
# feature that does not exist would have to take over. The stop is asserted, not assumed: the
# console must say what is missing, or offer nothing, rather than draw a control that fails.
# What each waits on is in its docstring and in the README's 23.20 table.

def _palette_titles(page, text: str) -> list[str]:
    """The palette's options after typing ``text``; the palette is closed again after."""
    page.press("k", "Control")
    page.wait_for("document.querySelectorAll('#palette-list [role=option]').length > 5")
    page.type(text)
    page.settle(quiet_ms=150)
    # An option is its kind, its title and a hint; the title is the middle one.
    titles = page.eval("[...document.querySelectorAll('#palette-list [role=option]')]"
                       ".map(o => o.children[1].textContent)")
    page.press("Escape")
    page.wait_for("!document.querySelector('.palette')")
    return list(titles)


def _access_row(page, name: str) -> str:
    return str(page.eval(f"""[...document.querySelectorAll('#access-views tbody tr')]
        .find(r => r.querySelector('a').textContent.trim() === {name!r}).textContent"""))


def test_journey_diagnose_a_struggling_query_from_the_dashboard(page):
    """Design 23.18 journey 3, "diagnose a backpressured query from the dashboard": the operator
    lands on the verdict, follows the finding to the query, reads its plan and measured totals,
    and acts on it -- pauses it, sees it paused, resumes it.

    Waits on the engine for the half the name promises. The engine does not sample lane
    backpressure, so no finding can say "backpressured" and the plan cannot colour its edges by
    it (23.8); and it counts rows, state and watermarks per query, not per operator, so the plan
    cannot name the operator that is the bottleneck. The journey diagnoses what the engine does
    measure -- state against its ceiling, watermark lag -- and asserts both screens say what is
    not measured.
    """
    with own_console() as ops:
        sign_in(page, ops)
        assert page.url().endswith("/operations")
        settled(page)
        assert "State ceiling nearly reached" in page.text("#findings")
        # What is not measured is on the dashboard, not left for the operator to infer.
        unmeasured = page.text("main")
        assert "Backpressure" in unmeasured and "does not sample lane backpressure" in unmeasured

        # The finding is a link to the query it is about.
        page.wait_for_navigation(lambda: page.click("#findings a[href='/queries/hot']"))
        assert page.url().endswith("/queries/hot")
        assert "RUNNING" in page.text("#meta")
        assert "hot_alias" in page.text("main"), "the page says another name shares the computation"

        # Its plan, with the totals the engine measured for the query as a whole.
        page.wait_for_navigation(lambda: page.click("a[href='/workbench?query=hot&panel=explain']"))
        page.wait_for("document.querySelectorAll('svg g.plan-node').length === 3", timeout=20)
        page.wait_for("document.getElementById('query-metrics')")
        assert "state 3 of 100" in page.text("#query-metrics")
        page.click("svg g.plan-node")
        page.wait_for("[...document.querySelectorAll('.card-body')]"
                      ".some(c => c.textContent.includes('Per-operator numbers are not shown'))")

        # Act: pause it, see it paused, resume it.
        page.goto(ops.url("/queries/hot"))
        settled(page)
        page.wait_for_navigation(lambda: page.click("#pause"))
        settled(page)
        assert "PAUSED" in page.text("#meta")
        assert page.eval("document.getElementById('pause').disabled") is True
        page.wait_for_navigation(lambda: page.click("#resume"))
        settled(page)
        assert "RUNNING" in page.text("#meta")
        assert ops.engine.lifecycle_calls == [("pause", "hot"), ("resume", "hot")]
        assert page.exceptions == [], page.exceptions


def test_journey_find_a_querys_dead_letters(page, console):
    """Design 23.18 journey 4, "inspect and act on a DLQ record": the operator looks for where a
    query's undecodable records went, from the console, and finds the setting, the file, how to
    read one, and how to put a corrected one back.

    Waits on the engine for inspecting and acting in the console. The dead-letter queue is a file
    per query under ``pravaha.dlq.directory``, written by the node and read by nothing: no API
    lists a query's dead letters or returns one, nothing replays one, and no ``pravaha_*`` meter
    counts them for the dashboard to find (``DeadLetterRate`` is in the runtime, wired to
    nothing). So there is no screen 8 (Query - Errors / DLQ), and the journey asserts no page and
    no palette entry pretends there is.
    """
    sign_in(page, console)
    page.goto(console.url("/help"))
    settled(page)
    page.focus("#help-search")
    page.type("dead letter")
    page.wait_for_navigation(lambda: page.press("Enter"))
    assert "/help/search?q=dead" in page.url()
    hrefs = page.eval("[...document.querySelectorAll('#search-results a')].map(a => a.getAttribute('href'))")
    assert "/help/topics/dead-letters" in hrefs[:3], hrefs
    page.wait_for_navigation(lambda: page.click("#search-results a[href='/help/topics/dead-letters']"))
    topic = page.text("main")
    assert "pravaha.dlq.directory" in topic and "<query>.dlq" in topic
    assert "correlationId" in topic and "jq -r .raw" in topic, "how to read one, and its original bytes"

    # The code an unwritable queue raises opens its own page.
    page.wait_for_navigation(lambda: page.click(".doc a.prv[href='/help/codes/PRV-4090']"))
    assert "PRV-4090" in page.text("h1")

    # Nowhere in the console is a dead letter offered, so nothing fails on click.
    page.goto(console.url("/queries/big_txn"))
    settled(page)
    assert "dead letter" not in page.text("main").lower()
    assert not [t for t in _palette_titles(page, "dead") if "dead" in t.lower()]


def test_journey_prepare_a_backfill(page, console):
    """Design 23.18 journey 5, "start and throttle a backfill", up to the start: the operator
    finds the stream to reload, what feeds it, whether that source can replay, and which queries
    a reload would reach.

    Waits on the engine for starting and throttling. ``pravaha-backfill`` is built as a library
    and reachable from no running path: there is no job to start, pause or abort, no throttle,
    no progress stream (23.10; 23.11's "long jobs"), and the storage cluster's own latency is
    not a metric. So there is no screen 14, and the journey asserts nothing offers a backfill.
    """
    sign_in(page, console)
    page.goto(console.url("/catalog"))
    settled(page)
    page.wait_for_navigation(lambda: page.click("main a[href='/catalog/streams/txn']"))
    assert "filesystem" in page.text("#stream-time"), "what feeds it"
    readers = page.eval("[...document.querySelectorAll('main a[href^=\"/queries/\"]')].map(a => a.textContent.trim())")
    assert {"big_txn", "hot"} <= set(readers), readers

    # Whether the source can rewind to an offset: its plugin's declared capabilities.
    page.goto(console.url("/plugins"))
    settled(page)
    plugin = page.eval("[...document.querySelectorAll('#plugin-cards .card')]"
                       ".find(c => c.textContent.includes('filesystem')).textContent")
    assert "replayable" in plugin.lower(), plugin

    assert not [t for t in _palette_titles(page, "backfill") if "backfill" in t.lower()]


def test_journey_blue_green_update_and_roll_back(page):
    """Design 23.18 journey 6, "blue/green update with rollback", as far as registration goes:
    open v1 in the workbench, change it, validate and explain the change, register it beside v1
    as v2, diff v2 against v1, compare the two views with the same point query, and roll back by dropping v2 --
    confirmed by its typed name -- with v1 running throughout.

    Waits on the engine for the cutover. Moving a view's name (or a sink) from v1 to v2 at an
    aligned frontier, with v1 kept for rollback through its retention, is built in
    ``pravaha-backfill`` and reachable from no running path, and ``CREATE OR REPLACE`` is refused
    (PRV-2072). So there is no screen 15 and no cutover button: v1 and v2 stay two names a
    client switches between itself. What the console can show before a cutover it does: the
    workbench's SQL and plan diff of v2 against v1 (23.7), with the changed operator marked and
    the engine's fingerprints saying the two are separate computations.
    """
    with own_console(default_role="analyst") as bg:
        bg.engine.view_rows["big_txn_v2"] = [[2, "u2", 900]]
        sign_in(page, bg, role="analyst")
        page.goto(bg.url("/queries/big_txn"))
        settled(page)
        page.wait_for_navigation(lambda: page.click("a[href='/workbench?query=big_txn']"))
        page.wait_for("document.querySelector('.monaco-editor .view-line') && document.querySelector('.validity.ok')",
                      timeout=20)

        # Change the threshold: to the end of the text, three characters out, three in.
        page.click(".monaco-editor .view-lines")
        page.press("End", "Control")
        for _ in range(3):
            page.press("Backspace")
        page.type("500")
        page.wait_for("document.querySelector('.monaco-editor .view-lines').textContent"
                      ".replace(/\\u00a0/g, ' ').includes('amount > 500')")
        page.wait_for("document.querySelector('.validity.ok')", timeout=10)

        # Its plan. The engine's measured totals belong to v1's SQL, so none are shown for v2's.
        page.eval("[...document.querySelectorAll('.wb-toolbar button')].find(b => b.textContent.includes('Explain'))"
                  ".setAttribute('data-test', 'explain')")
        page.click("[data-test=explain]")
        page.wait_for("document.querySelectorAll('svg g.plan-node').length === 3", timeout=20)
        assert not page.exists("#query-metrics")

        # Register v2 beside v1.
        page.eval("[...document.querySelectorAll('.wb-toolbar button')].find(b => b.textContent.includes('Register'))"
                  ".setAttribute('data-test', 'register')")
        page.click("[data-test=register]")
        page.wait_for("document.querySelector('#reg-name')")
        page.click("#reg-name")
        page.type("big_txn_v2")
        page.click("#key-txn_id")
        page.eval("[...document.querySelectorAll('form button[type=submit]')]"
                  ".find(b => b.textContent.includes('Register continuous')).setAttribute('data-test', 'submit')")
        page.wait_for("!document.querySelector('[data-test=submit]').disabled")
        page.click("[data-test=submit]")
        page.wait_for("document.querySelector('.alert-success') && "
                      "document.querySelector('.alert-success').textContent.includes('big_txn_v2')")
        assert bg.engine.registered[-1]["sql"].endswith("amount > 500")

        # Diff v2 against v1 (23.7): the draft came from big_txn, so that is what it is compared with.
        page.eval("[...document.querySelectorAll('.panel-tabs [role=tab]')].find(b => b.textContent.startsWith('Compare'))"
                  ".setAttribute('data-test', 'compare-tab')")
        page.click("[data-test=compare-tab]")
        page.wait_for("document.querySelector('#diff-against') && document.querySelector('#diff-against').value === 'q:big_txn'"
                      " && document.querySelectorAll('#diff-against option').length > 1")
        # From the keyboard, as the rest of the panel is reachable.
        page.focus("#diff-compare")
        page.press("Enter")
        page.wait_for("document.querySelector('#diff-result') && "
                      "document.querySelectorAll('#diff-result svg g.plan-node').length === 6", timeout=20)
        # The changed operator, marked in v2's plan and said in words; nothing added or removed.
        changed = page.eval("[...document.querySelectorAll('#diff-result svg g.plan-node.diff-changed')]"
                            ".map(g => g.getAttribute('aria-label'))")
        assert len(changed) == 2 and all(label.startswith("Filter") and label.endswith("changed") for label in changed)
        assert not page.exists("#diff-result svg g.diff-added") and not page.exists("#diff-result svg g.diff-removed")
        assert page.text("#diff-changes").strip() == "changed: Filter(amount > 100) → Filter(amount > 500)"
        # The engine's own answer, now that v2 is registered: two fingerprints, two computations.
        consequences = page.text("#diff-consequences")
        assert "Two computations" in consequences and "abc123def456" in consequences and "newfp" in consequences
        # v1's measured totals on v1's side; none implied for v2.
        assert "1,200 rows in" in page.text("#diff-left-metrics")
        assert "has not run" in page.text("#diff-right-no-metrics")
        # The SQL diff side by side, and unified from the keyboard.
        page.wait_for("document.querySelector('.monaco-diff-editor.side-by-side .editor.original .view-line')")
        page.focus("#diff-layout")
        page.press("Enter")
        page.wait_for("document.getElementById('diff-layout').getAttribute('aria-pressed') === 'true'")
        page.wait_for("!document.querySelector('.monaco-diff-editor.side-by-side')")

        # Compare: the same point query against each version.
        page.goto(bg.url("/views/big_txn?key=user_id&value=u1"))
        assert "u1" in page.text("#lookup-result")
        page.goto(bg.url("/views/big_txn_v2?key=user_id&value=u1"))
        v2 = page.text("#lookup-result")
        assert "u1" not in v2 and "u2" in v2

        # Roll back: drop v2, by its typed name. v1 never stopped.
        page.goto(bg.url("/queries/big_txn_v2"))
        settled(page)
        page.wait_for("!document.getElementById('dropModalTrigger').classList.contains('d-none')")
        page.click("#dropModalTrigger")
        page.wait_for("document.querySelector('#dropConfirmModal.show')")
        page.wait_for("document.activeElement && document.activeElement.id === 'dropConfirmInput'")
        page.type("big_txn_v2")
        page.wait_for("!document.getElementById('dropConfirmSubmit').disabled")
        page.wait_for_navigation(lambda: page.click("#dropConfirmSubmit"))
        assert page.url().endswith("/queries")
        assert ("drop", "big_txn_v2") in bg.engine.lifecycle_calls
        listed = page.eval("[...document.querySelectorAll('main a[href^=\"/queries/\"]')].map(a => a.textContent.trim())")
        assert "big_txn_v2" not in listed and "big_txn" in listed, listed
        assert ("drop", "big_txn") not in bg.engine.lifecycle_calls
        assert not [t for t in _palette_titles(page, "cutover") if "cutover" in t.lower()]
        assert page.exceptions == [], page.exceptions


def test_journey_trace_a_wrong_looking_row(page, console):
    """Design 23.18 journey 7, "debug a wrong result and export the fixture", up to where the
    debugger would take over: a developer point-queries the row that looks wrong, filters the
    live view to that key, watches a correction arrive as a -1 and a +1, sees the current row as
    the running sum of the weights, and opens the plan that produced it.

    Waits on the engine for the debugging. The time-travel debugger (23.9, screen 10) needs a
    retained checkpoint forked into an isolated instance with sinks disabled, a step protocol
    (by record, batch and watermark, with breakpoints on state), each step's operator state and
    generated source line, and an export of the step as a JUnit fixture. None of it exists, so
    the console offers no debugger and no fixture export, and the journey asserts it does not.
    """
    sign_in(page, console, role="developer")
    page.goto(console.url("/views/big_txn?key=user_id&value=u2"))
    settled(page)
    assert "900" in page.text("#lookup-result")

    # That key's changes, live, through the tap filter's form.
    page.wait_for_navigation(lambda: page.click("main a[href='/views/big_txn/live']"))
    page.wait_for("document.getElementById('live-state') && document.getElementById('live-state').dataset.state === 'fresh'",
                  timeout=15)
    page.eval("document.getElementById('tap-column').value = 'user_id'")
    page.focus("#tap-value")
    page.type("u2")
    page.press("Enter")
    page.wait_for("document.getElementById('live-state').dataset.state === 'fresh' && "
                  "document.getElementById('c-rows').textContent !== '—'", timeout=15)
    assert "filter=user_id" in page.url(), "the tap filter is in the URL"
    # The filter reaches the engine as the subscription's own; the changes are committed once
    # that subscription is open, as they would be to it and not to the one it replaced.
    deadline = time.monotonic() + 10
    while not any("u2" in str(filters) for _, filters in console.engine.tails_opened):
        assert time.monotonic() < deadline, console.engine.tails_opened
        time.sleep(0.05)
    before = int(page.eval("Number(document.getElementById('c-changes').textContent) || 0"))
    console.engine.commit({"txn_id": 2, "user_id": "u2", "amount": 900, "_weight": -1})
    console.engine.commit({"txn_id": 2, "user_id": "u2", "amount": 90, "_weight": 1})
    page.wait_for(f"Number(document.getElementById('c-changes').textContent) >= {before + 2}", timeout=10)
    log = page.text("#change-log")
    assert "−1" in log and "+1" in log and "900" in log, log
    current = page.eval("[...document.querySelectorAll('#current-rows tbody tr')]"
                        ".map(r => [...r.querySelectorAll('td')].map(td => td.textContent.trim()))")
    assert any("90" in cells for cells in current), current
    assert not any("900" in cells for cells in current), "the corrected row is gone from the sum"

    # The plan that produced it.
    page.goto(console.url("/queries/big_txn"))
    settled(page)
    page.wait_for_navigation(lambda: page.click("a[href='/workbench?query=big_txn&panel=explain']"))
    page.wait_for("document.querySelectorAll('svg g.plan-node').length === 3", timeout=20)

    # And no debugger: nothing offers one, so nothing fails when pressed.
    assert not [t for t in _palette_titles(page, "debug") if "debug" in t.lower()]
    assert not page.exists("a[href*='/debug']")
    assert page.exceptions == [], page.exceptions


def test_journey_a_grant_makes_the_affordance_appear(page):
    """Design 23.18 journey 8, "grant a role and verify the affordance appears": the admin sees
    what the engine's policy refuses the console's identity, finds the refused actions disabled
    with the policy's reason (not offered and then failing), and -- once the grant is made --
    sees them appear and work.

    The grant is not a console step and cannot be one: the engine is not where grants live.
    ``SecurityPolicy`` is an SPI a deployment implements against its own identity system, and no
    API changes one (SECURITY.md). The journey makes the grant where it would be made -- in the
    fake engine's policy, as other journeys have the engine commit a change -- and verifies all
    the console owns: the refusal on Access, the controls disabled with its reason, the palette
    leaving the actions out, and each following the engine's next answer. Editing grants, and
    tenants and quotas (screens 20, 21), wait on an engine API for them.

    Against the console before the change that came with it, this journey fails at its second
    step: the query page offered Pause, Resume and Drop whatever the policy said.
    """
    with own_console() as adm:
        adm.engine.administer_refused["hot"] = "administering 'hot' needs one of the roles [ops]"
        sign_in(page, adm, role="admin")
        assert page.url().endswith("/admin/access")
        row = _access_row(page, "hot")
        assert "refused" in row and "needs one of the roles [ops]" in row

        # The refused actions are disabled, with the reason on the page...
        page.wait_for_navigation(lambda: page.click("#access-views a[href='/queries/hot']"))
        assert "needs one of the roles [ops]" in page.text("#controls-refused")
        assert page.eval("['pause', 'resume', 'drop'].every(id => document.getElementById(id).disabled)")
        assert not page.exists("#dropModalTrigger")
        # ...and the palette does not offer them at all.
        titles = _palette_titles(page, "hot")
        assert "hot — browse view" in titles, titles
        assert not {"Pause hot", "Resume hot", "Drop hot…"} & set(titles), titles

        # The grant, made in the deployment's identity system: the engine answers differently.
        adm.engine.administer_refused.clear()

        page.goto(adm.url("/admin/access"))
        assert "allowed" in _access_row(page, "hot")
        page.goto(adm.url("/queries/hot"))
        settled(page)
        assert not page.exists("#controls-refused")
        page.wait_for("!document.getElementById('dropModalTrigger').classList.contains('d-none')")
        assert "Pause hot" in _palette_titles(page, "Pause hot")
        page.wait_for_navigation(lambda: page.click("#pause"))
        assert ("pause", "hot") in adm.engine.lifecycle_calls
        assert page.exceptions == [], page.exceptions
