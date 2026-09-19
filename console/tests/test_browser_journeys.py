"""The critical journeys, in a real browser, against the real console.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Design 23.18 names eight critical journeys. Four of them are journeys the console can already
walk end to end, and are here; the other four need engine features that do not exist yet
(a DLQ, backfill control, blue/green, the time-travel debugger) and are listed in the
console README rather than simulated.

Each journey drives Chrome the way a person would: typing into fields, clicking with the
mouse or pressing keys, and reading what the page then shows. Nothing is called on the
server behind the page's back, except that the fake engine is told to commit a change when
the journey needs one to arrive -- the one thing a person could not do from a browser.

Skipped, with the reason, when this machine has no Chrome (see ``browser_harness``).
"""
from __future__ import annotations

import pytest
from browser_harness import (
    PASSWORD,
    fresh_console,
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
