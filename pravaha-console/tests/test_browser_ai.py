"""The plain-English screens in a real browser (docs/guides/AI.md), drishti's designs.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Each journey runs on a console of its own whose fake engine has its AI layer on (``FakeAi``: regular
expressions playing the engine, as the ``mock`` provider plays the model -- no model, no key, no network
beyond 127.0.0.1). What is proved here and not in ``test_ai_screens.py`` is ai.js's part: the controls
shown, a form answered in place in a live region without a page load, the chart drawn in the theme, a
follow-up appended to the conversation, the confirm step opened before anything is registered or
created, Test connection's answer -- every answered state as accessible as the page, and fitting a phone.
"""
from __future__ import annotations

import contextlib

import pytest
from browser_harness import axe, describe, open_page, own_console, sign_in
from test_browser_mobile import VIEWPORTS, audit, problems

pytestmark = pytest.mark.browser


@contextlib.contextmanager
def ai_console():
    with own_console() as own:
        own.engine.ai.on = True
        yield own


def _submit(page, form: str, field: str, text: str) -> None:
    page.eval(f"""(() => {{ const f = document.querySelector({form!r}); f.hidden = false;
        f.querySelector({field!r}).value = {text!r}; f.requestSubmit(); return true; }})()""")


def _clean(page) -> None:
    violations = axe(page)
    assert not violations, describe(violations)
    assert page.exceptions == [], page.exceptions


def test_ask_answers_in_place_draws_its_chart_and_follows_up(page):
    with ai_console() as own:
        sign_in(page, own)
        open_page(page, own, "/ask", "document.querySelector('[data-ai-q]')")
        before = page.url()
        page.focus("[data-ai-q]")
        page.type("total spend per user")
        page.press("Enter")
        page.wait_for("document.querySelector('[data-ai-answer] .ai-chart canvas')")
        assert page.url() == before                      # answered in place, no page load
        assert "The SQL the model chose" in page.text("[data-ai-answer]")
        assert page.eval("document.querySelectorAll('[data-ai-answer] tbody tr').length") == 2
        assert page.eval("document.querySelector('[data-ai-values]').dataset.aiValues") == "labels-only"
        assert page.eval("document.querySelector('[data-ai-new]').hidden") is False
        _clean(page)
        # A follow-up: the conversation travels, and the answer is one more turn.
        _submit(page, "form[data-ai-task=ask]", "[data-ai-q]", "and the total only for u2?")
        page.wait_for("document.querySelectorAll('.ai-turn [data-ai-answer]').length === 2")
        assert "WHERE user_id = 'u2'" in page.eval("document.querySelector('.ai-turn:last-child [data-ai-sql]').textContent")
        assert len(own.engine.ai.calls[-1][1]["conversation"]) == 1
        page.click("[data-ai-new]")
        page.wait_for("document.querySelectorAll('.ai-turn').length === 0")


def test_a_refusal_shows_the_sql_chosen_and_explains_itself_in_plain_words(page):
    with ai_console() as own:
        sign_in(page, own)
        open_page(page, own, "/views", "document.querySelector('[data-ai-q]')")
        _submit(page, "form[data-ai-task=ask]", "[data-ai-q]", "show me the secret")
        page.wait_for("document.querySelector('[data-ai-failure=\"PRV-7002\"]')")
        assert "SELECT * FROM secret_view" in page.text("[data-ai-failure]")
        page.click("[data-ai-failure] [data-ai-explain=refusal]")
        page.wait_for("document.querySelector('[data-ai-failure] [data-ai-explained=refusal]')")
        _clean(page)


def test_a_draft_is_reviewed_and_registered_only_after_the_confirm(page):
    with ai_console() as own:
        sign_in(page, own)
        open_page(page, own, "/workbench", "document.querySelector('[data-ai-open=ai-describe-form]:not([hidden])')")
        page.click("[data-ai-open=ai-describe-form]")
        page.focus("#ai-describe-text")
        page.type("payments over 1000")
        page.click("#ai-describe-form button[type=submit]")
        page.wait_for("document.querySelector('[data-ai-draft] [data-ai-dry-run]')")
        assert "big_txn" in page.text("[data-ai-shares]") and own.engine.registered == []
        page.click("[data-ai-draft] .ai-actions [data-ai-open]")
        page.wait_for("document.querySelector('[data-ai-confirm]') && !document.querySelector('[data-ai-confirm]').closest('form').hidden")
        assert own.engine.registered == [], "opening the confirm registers nothing"
        _clean(page)
        page.click("[data-ai-confirm]")
        page.wait_for("document.querySelector('[data-ai-registered]')")
        assert [r["name"] for r in own.engine.registered] == ["large_payments"]


def test_an_alert_draft_is_created_only_after_the_confirm(page):
    with ai_console() as own:
        sign_in(page, own)
        open_page(page, own, "/alerts", "document.querySelector('[data-ai-open=ai-describe-alert-form]:not([hidden])')")
        _submit(page, "#ai-describe-alert-form", "textarea", "tell me when a payment is over 500")
        page.wait_for("document.querySelector('[data-ai-alert-draft]')")
        page.click("[data-ai-alert-draft] .ai-actions [data-ai-open]")
        assert own.engine.statements == []
        page.click("[data-ai-confirm]")
        page.wait_for("document.querySelector('[data-ai-created]')")
        assert own.engine.statements[0].startswith("CREATE ALERT big_spender")
        _clean(page)


def test_explain_why_it_changed_summarise_and_ask_about_this_page(page):
    with ai_console() as own:
        sign_in(page, own)
        open_page(page, own, "/queries/big_txn", "document.querySelector('[data-ai-explain=sql]:not([hidden])')")
        page.click("[data-ai-explain=sql]")
        page.wait_for("document.querySelector('[data-ai-explained=sql] [data-ai-plan]')")
        open_page(page, own, "/views/big_txn", "document.querySelector('[data-ai-explain=change]')")
        page.click("[data-ai-explain=change]")
        page.wait_for("document.querySelector('[data-ai-keeping]')")
        _clean(page)
        open_page(page, own, "/overview", "document.querySelector('form[data-ai-task=page]:not([hidden])')")
        _submit(page, "form[data-ai-task=page]", "input[name=question]", "what is this page for?")
        page.wait_for("document.querySelector('[data-ai-page-answer]')")
        assert own.engine.ai.calls[-1][1]["screen"] == "overview"
        _clean(page)


def test_summarise_changes_sends_what_the_live_page_received(page):
    with ai_console() as own:
        sign_in(page, own)
        open_page(page, own, "/views/big_txn/live",
                  "document.getElementById('live-state').dataset.state === 'fresh' && window.PravahaLive")
        own.engine.commit({"txn_id": 9, "user_id": "u9", "amount": 990, "_weight": 1})
        page.wait_for("window.PravahaLive.recent().length > 0")
        page.click("form[data-ai-task=narrate] button")
        page.wait_for("document.querySelector('[data-ai-narrative]')")
        assert own.engine.ai.calls[-1][1]["changes"], "the batch the page received was sent"


def test_admin_test_connection_answers_with_the_outcome_and_its_latency(page):
    with ai_console() as own:
        sign_in(page, own)
        open_page(page, own, "/admin/ai", "document.querySelector('[data-ai-test]')")
        page.click("[data-ai-test]")
        page.wait_for("document.querySelector('[data-ai-test-result][data-ok]')")
        assert "7 ms" in page.text("[data-ai-test-result]") and own.engine.ai.tests == ["mock/m"]
        _clean(page)


@pytest.mark.parametrize("width", sorted(VIEWPORTS))
def test_the_answered_screens_fit_a_phone(chrome, width):
    w, h = VIEWPORTS[width]
    with ai_console() as own:
        tab = chrome.new_page(width=w, height=h)
        try:
            sign_in(tab, own)
            open_page(tab, own, "/ask", "document.querySelector('[data-ai-q]')")
            _submit(tab, "form[data-ai-task=ask]", "[data-ai-q]", "total spend per user")
            tab.wait_for("document.querySelector('[data-ai-answer] .ai-chart canvas')")
            found = problems(audit(tab, w))
            assert not found, f"/ask answered at {width}px:\n  " + "\n  ".join(found)
            open_page(tab, own, "/workbench", "document.querySelector('[data-ai-open=ai-describe-form]:not([hidden])')")
            _submit(tab, "#ai-describe-form", "textarea", "payments over 1000")
            tab.wait_for("document.querySelector('[data-ai-draft]')")
            found = problems(audit(tab, w))
            assert not found, f"/workbench draft at {width}px:\n  " + "\n  ".join(found)
            open_page(tab, own, "/admin/ai", "document.querySelector('[data-ai-test]')")
            found = problems(audit(tab, w))
            assert not found, f"/admin/ai at {width}px:\n  " + "\n  ".join(found)
        finally:
            tab.close()
