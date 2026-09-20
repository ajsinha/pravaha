"""The critical journeys, in a real browser, against the real console.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Design 23.18 names eight critical journeys, and all eight walk end to end. Four of them
once stopped at an assertion that the console offered nothing, because the engine offered
nothing: B5 gave them a readable dead-letter queue, B6 backpressure and per-operator
sampling, ADR-046 blue/green cutover with a rollback window, and ADR-048 a debug fork that
can be stepped and exported. Each landing turned an assertion into a screen this file drives.

Journey 8's grant is not a console step and cannot be one -- grants live in the deployment's
identity system behind ``SecurityPolicy``, and no API changes one -- so it ends where the
console's own half of it ends, which is not the same as waiting on the engine.

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


# ============================================================ 0. arriving at a bare host name


def test_a_stranger_arrives_at_the_landing_page_and_can_read_the_product(page, console):
    """Nobody has signed in. `/` answers with the landing page rather than the sign-in form,
    the figure is there, the documentation and About open, and the way in is one click.

    Driven signed *out* on purpose: a test that only ever drives a signed-in browser would
    not notice the day `/` starts redirecting to `/login` again.
    """
    page.goto(console.url("/"))
    settled(page)
    assert page.url().rstrip("/").endswith(console.url("").rstrip("/")), page.url()
    assert "Ask once." in page.text("main")

    # The rail: the version, the page's sections, the two public documents, one call to
    # action, and it reads "Sign in" because nobody has.
    assert page.exists(".rail")
    assert "Sign in" in page.text("#rail-cta")
    assert page.exists('.rail a[href="/help"]') and page.exists('.rail a[href="/about"]')

    # The figure is drawn, is decorative, and says the same thing in words below.
    assert page.exists("#figure svg[aria-hidden=true]")
    assert page.eval("document.querySelectorAll('#figure .fig-motion, #figure .fig-still').length") == 2
    assert "steps back to 90" in page.text("#how-it-works")

    # Nothing about this deployment: reachability, and not the address.
    assert "engine.test" not in page.text("body")

    # The documentation and About open with no session, and come back.
    page.wait_for_navigation(lambda: page.click('.rail a[href="/about"]'))
    assert page.url().endswith("/about")
    page.wait_for_navigation(lambda: page.click('a[href="/help"]'))
    assert "/help" in page.url()

    # And the way in is one click from the landing page.
    page.goto(console.url("/"))
    settled(page)
    page.wait_for_navigation(lambda: page.click("#rail-cta"))
    assert "/login" in page.url()
    assert page.exceptions == [], page.exceptions


def test_signing_out_comes_back_to_the_landing_page(page):
    """Not to the sign-in form: somebody who has just left is a reader again, and the page
    that says what this is is the one to leave them on."""
    with own_console() as own:
        sign_in(page, own)
        page.goto(own.url("/"))
        settled(page)
        assert "Open the console" in page.text("#rail-cta")
        page.wait_for_navigation(lambda: page.goto(own.url("/logout")))
        assert page.url().rstrip("/").endswith(own.url("").rstrip("/")), page.url()
        assert "Sign in" in page.text("#rail-cta")
        assert page.exceptions == [], page.exceptions


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
    assert len(cards) == 5 and all(c.startswith("/help/topics/") for c in cards)
    assert "/help/topics/compare-versions" in cards
    assert "/help/topics/reading-a-plan" in cards, "the numbers the plan now draws"
    page.wait_for_navigation(lambda: page.click("h1 .screen-help"))
    assert page.url().endswith(href)


# ============================================================ the other six journeys of design 23.18
#
# All six walk end to end. Debugging a wrong result is in two parts, because its second half
# forks the query and a fork is a second copy of it that no other test's screen should meet.

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
    """Design 23.18 journey 3, "diagnose a backpressured query from the dashboard", end to end:
    the verdict names the query *and* the operator, the finding says how much of the time its
    lane had nowhere to put a row and whether a neighbour on the shared lane is the cause, the
    plan draws the numbers on the operators and marks the bottleneck, and the operator acts.

    It used to stop half way and assert that both screens said backpressure was not sampled and
    per-operator numbers were not published. B6 measures both -- episodes and blocked time per
    query, blocked fraction and inbox depth per lane, and rows, state, watermark and a sampled
    self time per operator with the engine naming the bottleneck -- so the journey walks the
    whole thing instead of asserting the absence.
    """
    with own_console() as ops:
        sign_in(page, ops)
        assert page.url().endswith("/operations")
        settled(page)

        # The verdict answers "where?" with the operator, not just the query: on a
        # backpressured query the query's name is not yet an answer.
        assert "hot → Aggregate (n0)" in page.text("#verdict")
        findings = page.text("#findings")
        assert "Cannot be fed fast enough" in findings
        assert "92% of the time" in findings and "2,040 of 2,048 cells" in findings
        assert "Most of the time goes into Aggregate (n0)" in findings

        # The numbers behind it, on the query's row: the lane's blocked share and its inbox.
        row = page.eval("""[...document.querySelectorAll('#ops-queries tbody tr')]
            .find(r => r.textContent.includes('hot ') || r.querySelector('a').textContent === 'hot').textContent""")
        assert "92%" in row and "2,040 / 2,048" in row, row

        # And the lane's own view, which is the other half of "whose fault is it": the shared
        # lane this query is on is as blocked as it is, and two queries are queued behind it.
        lanes = page.text("#ops-lanes")
        assert "lane 0" in lanes and "92%" in lanes and "lane 1" in lanes
        assert "3%" in lanes, "the other lane is not blocked, so this is not the node"
        assert "Per-operator numbers are on for this node" in page.text("#ops-operators-enabled")

        # The finding is a link to the query it is about.
        page.wait_for_navigation(lambda: page.click("#findings a[href='/queries/hot']"))
        assert page.url().endswith("/queries/hot")
        assert "RUNNING" in page.text("#meta")
        assert "hot_alias" in page.text("main"), "the page says another name shares the computation"

        # Its plan: the totals for the query, and the numbers on the operators themselves.
        page.wait_for_navigation(lambda: page.click("a[href='/workbench?query=hot&panel=explain']"))
        page.wait_for("document.querySelectorAll('svg g.plan-node').length === 3", timeout=20)
        page.wait_for("document.getElementById('query-metrics')")
        totals = page.text("#query-metrics")
        assert "state 3 of 100" in totals
        assert "lane blocked 92% of the time" in totals
        assert "41 episodes, 312.5 s in all" in totals and "inbox 2,040 of 2,048" in totals

        # Every operator carries its own line, and exactly one is marked the bottleneck --
        # by a border, a glyph and the words in its accessible name, never colour alone.
        drawn = page.eval("[...document.querySelectorAll('svg g.plan-node')]"
                          ".map(g => g.getAttribute('aria-label'))")
        assert len(drawn) == 3 and all("4,213 rows in" in label for label in drawn), drawn
        hot_nodes = page.eval("[...document.querySelectorAll('svg g.plan-node.bottleneck')]"
                              ".map(g => [g.dataset.node, g.getAttribute('aria-label')])")
        assert len(hot_nodes) == 1 and hot_nodes[0][0] == "n0"
        assert "the bottleneck" in hot_nodes[0][1] and "86 per cent" in hot_nodes[0][1]
        assert page.exists("svg g.plan-node.bottleneck text.mark"), "marked by colour alone"
        assert "The bottleneck is Aggregate (n0)." in page.text("#metrics-note")

        # Its own numbers, and how many samples the share came from -- so a share read off a
        # handful of samples can be recognised as one.
        page.click("svg g.plan-node.bottleneck")
        page.wait_for("document.getElementById('operator-metrics')")
        detail = page.text("#operator-detail")
        assert "4,213" in detail and "8,388,608" in detail and "86%" in detail
        assert "Sampled from 4 rows" in detail

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


def test_a_node_with_the_operator_counters_off_names_the_setting(page):
    """The other half of journey 3, and the one a first run actually meets: per-operator
    counting costs about 8 % and is off by default, so the commonest answer to "which
    operator?" is "this node is not counting". The panel must say that and name the setting,
    not draw an empty graph and let the reader conclude the operators did no work."""
    with own_console() as off:
        off.engine.operator_metrics = False
        sign_in(page, off, role="analyst")
        page.goto(off.url("/workbench?query=hot&panel=explain"))
        page.wait_for("document.querySelectorAll('svg g.plan-node').length === 3", timeout=30)
        page.wait_for("document.getElementById('metrics-note')")
        note = page.text("#metrics-note")
        assert page.eval("document.getElementById('metrics-note').dataset.metricsState") == "operators_off"
        assert "switched off on this node" in note
        assert "pravaha.metrics.operators is off" in note and "re-register the query" in note
        assert not page.exists("svg g.plan-node.bottleneck")
        # The query's own totals are measured either way, and are still there.
        assert "lane blocked 92% of the time" in page.text("#query-metrics")
        # And no operator's numbers are invented in its place.
        page.click("svg g.plan-node")
        page.wait_for("document.getElementById('operator-not-measured')")
        assert "published no numbers" in page.text("#operator-not-measured")
        assert not page.exists("#operator-metrics")
        assert page.exceptions == [], page.exceptions


def test_journey_find_a_querys_dead_letters(page, console):
    """Design 23.18 journey 4, "inspect and act on a DLQ record", end to end: the operator
    looks for where a query's undecodable records went, finds the help, reaches the records
    themselves from the query's page, reads one, and puts a corrected one back.

    It used to stop at the help topic and assert that the console offered nothing, because
    the dead-letter queue was a file nothing read. B5 made it a product: an API lists a
    query's dead letters and returns one, a replay feeds a chosen record back through the
    query, and ``pravaha_query_dead_letters`` counts them for the dashboard. So the journey
    now walks screen 8 (Query - Errors / DLQ) rather than asserting it is absent.
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
    assert "pravaha dlq list" in topic, "and how to read one without a shell on the node"

    # The code an unwritable queue raises opens its own page.
    page.wait_for_navigation(lambda: page.click(".doc a.prv[href='/help/codes/PRV-4090']"))
    assert "PRV-4090" in page.text("h1")

    # Two records the feed could not decode. One is a genuine mistake somebody has since
    # corrected at the source; the other is still malformed.
    corrected = console.engine.reject("big_txn", offset="line 812", raw="812,u-1042,acme,12.50")
    console.engine.reject("big_txn", offset="line 900", raw="900,,,")
    console.engine.replay_succeeds.add(corrected)

    # Reached from the query, because a dead letter belongs to one.
    page.goto(console.url("/queries/big_txn"))
    settled(page)
    assert "dead letter" in page.text("main").lower()
    page.wait_for_navigation(lambda: page.click("#dead-letters-link"))
    assert "/queries/big_txn/dead-letters" in page.url()

    # Newest first, with the code linked to its page and the record legible rather than Base64.
    rows = page.eval("[...document.querySelectorAll('#dlq-table tbody tr')].map(r => r.textContent)")
    assert len(rows) == 2, rows
    assert "line 900" in rows[0], "newest first: a queue is read because something just failed"
    assert "line 812" in rows[1]
    assert "PRV-5040" in rows[1] and "12.50" in rows[1]
    assert "2 in the queue" in page.text("#dlq-summary")

    # Replay the corrected one. It becomes a row at the query's current frontier.
    page.click(f"#dlq-table input[value='{corrected}']")
    page.wait_for_navigation(lambda: page.click("#dlq-replay"))
    assert console.engine.replays == [("big_txn", [corrected])], console.engine.replays
    assert "1 replayed" in page.text("#dlq-replayed")
    assert "frontier" in page.text("#dlq-replayed"), "the semantics, said where it is acted on"
    states = page.eval("[...document.querySelectorAll('#dlq-table tbody tr')].map(r => r.textContent)")
    assert any("Replayed" in r for r in states), states

    # The one that is still malformed fails again and returns to the queue rather than looping.
    still_bad = next(e["id"] for e in console.engine.dead_letter_queues["big_txn"]
                     if e["replay"] == "NEW")
    page.click(f"#dlq-table input[value='{still_bad}']")
    page.wait_for_navigation(lambda: page.click("#dlq-replay"))
    assert "1 failed to decode again" in page.text("#dlq-replayed")
    assert len(console.engine.dead_letter_queues["big_txn"]) == 3, "back on the queue as a new entry"

    # And it is in the palette, so it is reachable without knowing the URL.
    assert [t for t in _palette_titles(page, "dead") if "dead" in t.lower()]
    assert page.exceptions == [], page.exceptions


def test_journey_start_and_throttle_a_backfill(page):
    """Design 23.18 journey 5, "start and throttle a backfill", end to end: the operator finds
    the stream to reload, what feeds it and whether that source can replay, sees which queries
    a reload would reach, starts a backfill against one of them, watches it read, turns it
    down while production is busy, pauses it and resumes it.

    It used to stop at "whether the source can replay" and assert that nothing offered a
    backfill, because ``pravaha-backfill`` was a library reachable from no running path.
    ADR-046 put it on one, so this walks the screen.

    The journey also holds the console to the rule that made this screen hard: **no ETA and no
    percentage**. It asserts that no number on the panel is a share of unknown work, and that a
    lag nothing has measured is said to be unknown rather than shown as zero.
    """
    with own_console() as bf:
        sign_in(page, bf)
        page.goto(bf.url("/catalog"))
        settled(page)
        page.wait_for_navigation(lambda: page.click("main a[href='/catalog/streams/txn']"))
        assert "filesystem" in page.text("#stream-time"), "what feeds it"
        readers = page.eval("[...document.querySelectorAll('main a[href^=\"/queries/\"]')]"
                            ".map(a => a.textContent.trim())")
        assert {"big_txn", "hot"} <= set(readers), readers

        # Whether the source can rewind to an offset: its plugin's declared capabilities.
        # A backfill against a source that cannot replay is refused by the engine (PRV-4018),
        # so this is the thing to check before starting one.
        page.goto(bf.url("/plugins"))
        settled(page)
        plugin = page.eval("[...document.querySelectorAll('#plugin-cards .card')]"
                           ".find(c => c.textContent.includes('filesystem')).textContent")
        assert "replayable" in plugin.lower(), plugin

        # Reached from the query, because a backfill belongs to one.
        page.goto(bf.url("/queries/big_txn"))
        settled(page)
        page.wait_for_navigation(lambda: page.click("#replacement-link"))
        assert page.url().endswith("/queries/big_txn/replacement")
        assert page.exists("#rep-none"), "nothing is being replaced yet"

        # Start one. The SQL comes prefilled with what is running and is edited here; the
        # planner is the engine's, and the form says so rather than pretending to check it.
        assert "does not check it" in page.text("#rep-start-note")
        page.eval("document.getElementById('start-sql').value = "
                  "'SELECT txn_id, user_id, amount FROM txn WHERE amount > 500'")
        page.eval("document.getElementById('start-rate').value = '5000'")
        page.wait_for_navigation(lambda: page.click("#rep-start"))
        assert bf.engine.replacement_calls[0][0] == "start"
        assert bf.engine.replacement_calls[0][2].endswith("amount > 500")
        assert "still answers the version it answered before" in page.text("#rep-acted")

        # What it shows while it reads, and what it refuses to show.
        assert "Backfilling" in page.text("#rep-state")
        panel = page.text("#rep-numbers")
        assert "0 of 4" in panel, "partitions on the live stream, out of how many it has"
        assert "not known yet" in panel, "no lag measured yet, and not 0 s"
        assert "5,000 rows/s" in panel, "the ceiling it was started with"
        assert "There is no estimate and no percentage here" in page.text("#rep-no-eta")
        assert "%" not in panel, panel
        assert not page.exists("#rep-numbers [role=meter], #rep-numbers progress"), \
            "a bar over an unknown total is a promise the engine never made"

        # It reads. The numbers move over the 1 Hz stream, and nothing else on the page does.
        bf.engine.backfill_progress("big_txn", historyRows=412_000, liveRows=980,
                                    rowsPerSecond=4800.0, partitionsLive=2, lagSeconds=63.0)
        page.wait_for("document.querySelector('#rep-numbers [data-field=historyRows]')"
                      ".textContent.includes('412,000')", timeout=10)
        moved = page.text("#rep-numbers")
        assert "2 of 4" in moved and "63.0 s" in moved

        # Production is busy: turn it down. The engine's ceiling is the one it started with,
        # and asking for more is its refusal to give, not the console's to hide.
        page.eval("document.getElementById('bf-rate').value = '800'")
        page.wait_for_navigation(lambda: page.click("#bf-throttle"))
        assert ("throttle", "big_txn", 800) in bf.engine.replacement_calls
        assert "at most 800 records a second" in page.text("#rep-acted")

        page.eval("document.getElementById('bf-rate').value = '90000'")
        page.wait_for_navigation(lambda: page.click("#bf-throttle"))
        refusal = page.text("#rep-action-error")
        assert "PRV-4018" in refusal and "may be slowed, not sped up" in refusal
        assert ("throttle", "big_txn", 90000) in bf.engine.replacement_calls, "the console clamped it"

        # Pause, and see that what it has read is kept.
        page.wait_for_navigation(lambda: page.click("#bf-pause"))
        assert "paused" in page.text("#rep-numbers")
        assert "412,000" in page.text("#rep-numbers"), "pausing gave up what it had read"
        page.wait_for_navigation(lambda: page.click("#bf-pause"))
        assert ("resume", "big_txn", None) in bf.engine.replacement_calls
        assert "reading" in page.text("#rep-numbers")

        # And it is in the palette, so it is reachable without knowing the URL.
        assert [t for t in _palette_titles(page, "replacement") if "replacement" in t.lower()]
        assert page.exceptions == [], page.exceptions


def test_journey_blue_green_update_and_roll_back(page):
    """Design 23.18 journey 6, "blue/green update with rollback", end to end: open v1 in the
    workbench, change it, explain it, register it beside v1 as v2, diff the two plans, compare
    the two views with the same point query -- and then take the name across and put it back.

    It used to stop at the diff and assert that nothing offered a cutover. ADR-046 built one,
    so the journey now runs the second half on the real screen: start the replacement, let the
    backfill reach the seam, cut over with the name typed, watch the rollback window, roll back
    with the name typed again, and see the name answering the version it answered before.
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
        # v1's side also carries its measured numbers, so the mark is named in the label
        # rather than being the last thing in it.
        assert len(changed) == 2 and all(
            label.startswith("Filter") and ", changed" in label for label in changed), changed
        assert any("of the sampled time" in label for label in changed), \
            "v1 is running and its operators are measured"
        assert not any("of the sampled time" in label for label in page.eval(
            "[...document.querySelectorAll('#diff-result svg g.plan-node')]"
            ".map(g => g.getAttribute('aria-label'))")[3:]), \
            "the draft has not run, so nothing on its side is measured"
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

        # Drop the trial registration: the real move is a cutover of the name, not a second
        # name a client has to know about. Dropped by its typed name; v1 never stopped.
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

        # Now the cutover itself: the same new version, but replacing big_txn rather than
        # standing beside it, so readers of the name move across without being told to.
        page.goto(bg.url("/queries/big_txn/replacement"))
        settled(page)
        page.eval("document.getElementById('start-sql').value = "
                  "'SELECT txn_id, user_id, amount FROM txn WHERE amount > 500'")
        page.wait_for_navigation(lambda: page.click("#rep-start"))
        assert "Backfilling" in page.text("#rep-state")

        # Cutting over before the candidate has read all of the history is not offered: the
        # engine would refuse it (PRV-4014), and a control that fails on click is the thing
        # 23.12's unauthorized state exists to prevent.
        assert page.eval("document.getElementById('rep-cutover').disabled") is True
        assert "has not read all of the history yet" in page.text("main")

        bg.engine.backfill_progress("big_txn", historyRows=412_000, partitionsLive=4,
                                    historyComplete=True, lagSeconds=0.0)
        page.wait_for("document.getElementById('rep-state').dataset.state === 'CAUGHT_UP'", timeout=10)
        settled(page)
        assert page.eval("document.getElementById('rep-cutover').disabled") is False

        # Deliberately deliberate (23.10): the name, typed, exactly as a drop asks for it.
        page.click("#rep-cutover")
        page.wait_for("document.querySelector('#cutoverConfirmModal.show')")
        page.wait_for("document.activeElement && document.activeElement.id === 'cutoverConfirmInput'")
        assert page.eval("document.getElementById('cutoverConfirmSubmit').disabled") is True
        page.type("big_txn_wrong")
        assert page.eval("document.getElementById('cutoverConfirmSubmit').disabled") is True
        page.eval("document.getElementById('cutoverConfirmInput').value = ''")
        page.type("big_txn")
        page.wait_for("!document.getElementById('cutoverConfirmSubmit').disabled")
        page.wait_for_navigation(lambda: page.click("#cutoverConfirmSubmit"))
        assert ("cutover", "big_txn", None) in bg.engine.replacement_calls
        assert "now answers the new version" in page.text("#rep-acted")
        assert "Cut over" in page.text("#rep-state")

        # The rollback window, as a time and not as a mood.
        assert "retained until 2026-09-19T15:30:00Z" in page.text("#rep-rollback")

        # Roll back, by the typed name again. The name answers what it answered before.
        page.click("#rep-rollback-btn")
        page.wait_for("document.querySelector('#rollbackConfirmModal.show')")
        page.wait_for("document.activeElement && document.activeElement.id === 'rollbackConfirmInput'")
        page.type("big_txn")
        page.wait_for("!document.getElementById('rollbackConfirmSubmit').disabled")
        page.wait_for_navigation(lambda: page.click("#rollbackConfirmSubmit"))
        assert ("rollback", "big_txn", None) in bg.engine.replacement_calls
        assert "answers the replaced version again" in page.text("#rep-acted")
        assert "Rolled back" in page.text("#rep-state")
        # The window is over, and the screen says so rather than leaving the button to guess at.
        assert "no window to keep open" in page.text("#rep-rollback")

        # v1 ran throughout: nothing dropped it, and the point query answers as it always did.
        assert ("drop", "big_txn") not in bg.engine.lifecycle_calls
        page.goto(bg.url("/views/big_txn?key=user_id&value=u1"))
        assert "u1" in page.text("#lookup-result")
        assert page.exceptions == [], page.exceptions


def test_journey_trace_a_wrong_looking_row(page, console):
    """Design 23.18 journey 7, "debug a wrong result and export the fixture", end to end: a
    developer point-queries the row that looks wrong, filters the live view to that key,
    watches a correction arrive as a -1 and a +1, sees the current row as the running sum of
    the weights, opens the plan that produced it, then forks the query from a retained
    checkpoint and steps the fork until the operator lines say which operator did it.

    The second half of this journey was an assertion that the console offered nothing until
    ADR-048 built the engine side. It walks now, in ``_debug_the_wrong_row`` below, because
    the stop was the debugger and there is one.
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

    # And the debugger is one click from the query, and one from the palette.
    page.goto(console.url("/queries/big_txn"))
    settled(page)
    assert "big_txn — debug" in _palette_titles(page, "debug")
    assert page.exists("a[href='/queries/big_txn/debug']")
    assert page.exceptions == [], page.exceptions


def test_journey_debug_a_wrong_row_and_export_the_fixture(page):
    """The second half of design 23.18 journey 7, on a console of its own because it forks
    the query and a fork is a second copy nobody else's screen should meet.

    The developer has a row that looks wrong and the log says nothing, because nothing went
    wrong -- the engine did exactly what the query asked. So: fork it from a checkpoint, step
    one row, and read what every operator did with that row. The step where the filter reads
    ``in=1 out=0`` is the answer a view alone cannot give, because a filter that rejected the
    row and an aggregate that produced a zero delta look identical from outside.

    Then the incident becomes a test: the export names the class, says where the file belongs
    and shows the source, and the console writes nothing -- the file belongs in the repository
    this engine is built from, not on the machine the browser is on.
    """
    with own_console(default_role="developer") as dbg:
        sign_in(page, dbg, role="developer")
        page.goto(dbg.url("/queries/big_txn"))
        settled(page)

        # Into the debugger, from the query whose row looked wrong.
        page.wait_for_navigation(lambda: page.click("#debug-link"))
        assert page.url().endswith("/queries/big_txn/debug")
        assert "4471" in page.text("#dbg-fork-form"), "the positions a fork can start from"

        # Fork it from the newest position this node still retains.
        page.wait_for_navigation(lambda: page.click("#dbg-fork"))
        page.wait_for("document.getElementById('dbg-app')")
        session = page.eval("document.getElementById('dbg-app').dataset.session")
        assert ("fork", "big_txn", None) in dbg.engine.debug_calls
        # It says so permanently, and the live query is untouched.
        assert "DEBUG" in page.text("#dbg-banner")
        assert "session=" in page.url(), "the session is in the URL, so the link reaches it"

        # One row. The island steps without navigating, and the report lands at the top.
        page.click("#dbg-step-row")
        page.wait_for("document.querySelectorAll('#dbg-log .dbg-report').length === 1")
        first = page.text("#dbg-log .dbg-report")
        assert "8841" in first and "u2" in first, first
        assert "+1" in first, "the row arrives with its weight"

        # The second row is 40, which the filter rejects. The view does not move -- and only
        # the operator lines say why, which is the whole reason the panel is there.
        page.click("#dbg-step-row")
        page.wait_for("document.querySelectorAll('#dbg-log .dbg-report').length === 2")
        latest = page.eval("document.querySelector('#dbg-log .dbg-report').textContent")
        assert "The view did not change." in latest, latest
        flows = page.eval("""[...document.querySelectorAll('#dbg-log .dbg-report')[0]
            .querySelectorAll('.dbg-operators tbody tr')]
            .map(r => [...r.querySelectorAll('td')].map(c => c.textContent.trim()))""")
        assert ["n1", "Filter(amount > 100)", "1", "0"] in flows, flows
        assert ["n2", "Scan(txn)", "1", "1"] in flows, flows
        # The counters moved with it, without a reload.
        summary = page.text("#dbg-summary")
        assert "rows consumed 2" in summary and "steps 2" in summary, summary

        # A step the engine cannot read is refused by name, and the session survives it.
        page.eval("document.getElementById('dbg-step').value = 'until:total'")
        page.click("#dbg-step-go")
        page.wait_for("document.getElementById('dbg-step-error')")
        assert "PRV-8015" in page.text("#dbg-step-error")

        # The incident becomes a test in the repository.
        page.focus("#dbg-fixture-name")
        page.type("the row u2 should not have")
        page.wait_for_navigation(lambda: page.click("#dbg-fixture-go"))
        assert "TheRowU2ShouldNotHaveFixtureTest" in page.text("#dbg-fixture")
        assert "pravaha-it/src/test/java/" in page.text("#dbg-fixture")
        assert "the answer over those rows from empty" in page.text("#dbg-fixture-expectation")

        # And the fork is released: nothing read it and nothing wrote.
        page.wait_for_navigation(lambda: page.click("#dbg-end"))
        assert ("end", session, None) in dbg.engine.debug_calls
        assert not dbg.engine.debug_sessions_by_id
        assert page.exists("#dbg-none"), "back to the screen for a query nothing is debugging"

        # The live query ran throughout: it was never paused, never dropped, and answers.
        assert not [call for call in dbg.engine.lifecycle_calls if call[-1] == "big_txn"]
        page.goto(dbg.url("/views/big_txn?key=user_id&value=u2"))
        assert "900" in page.text("#lookup-result")
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
