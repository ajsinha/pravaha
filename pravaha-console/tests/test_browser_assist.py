"""The assistant in a real browser (ADR-058 phase 3): Describe it, Copy to editor, Register,
Explain, and Admin · AI models' switch taking effect on the next request.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Each journey runs on a console of its own, seeded with a configuration of the SDK's ``fake``
provider: no model and no network beyond 127.0.0.1. What is proved here and not in
``test_assist.py`` is the script's part -- the form answered in place, in a live region, without
a page load; the draft opened as an editor tab; the island's diagnostics offering Explain.
"""
from __future__ import annotations

import json

import pytest
from browser_harness import ASSIST_SEED, axe, describe, open_page, own_console, sign_in

pytestmark = pytest.mark.browser

ACCEPTED = json.dumps({"name": "big_spend", "sql": "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500",
                       "keys": ["txn_id"], "options": {"retention": None, "index": None, "sink": None, "lane": None},
                       "explanation": "Each transaction over 500.", "assumptions": [], "questions": [],
                       "confidence": 0.9})
REFUSAL = json.dumps({"meaning": "a column the query names does not exist", "cause": "amout is misspelled",
                      "fix": "write amount", "rewrite": "SELECT txn_id, amount FROM txn"})


def seed(**replies) -> dict:
    config = json.loads(json.dumps(ASSIST_SEED))
    for model in config["models"]:
        if model["id"] in replies:
            model["options"] = {"replies": replies[model["id"]]}
    return config


def test_describe_it_drafts_in_place_opens_the_draft_in_the_editor_and_registers(page):
    with own_console(assist=seed(drafter=[ACCEPTED])) as own:
        sign_in(page, own)
        open_page(page, own, "/workbench", "document.querySelector('.monaco-editor .view-line')")
        page.eval("(() => { document.getElementById('assist-describe').open = true; return true; })()")
        page.focus("#assist-description")
        page.type("transactions over 500")
        before = page.url()
        page.click("#assist-describe-form button[type=submit]")
        page.wait_for("document.querySelector('#assist-describe-result [data-draft-status]')")
        assert page.url() == before                      # answered in place, no page load
        assert page.eval("document.querySelector('[data-draft-status]').dataset.draftStatus") == "accepted"
        assert "CREATE CONTINUOUS QUERY big_spend" in page.text("#assist-describe-result")
        assert page.eval("document.getElementById('assist-describe-result').getAttribute('aria-live')") == "polite"
        # The answer as drawn -- the draft, its plan, its Register -- is as accessible as the page.
        violations = axe(page)
        assert not violations, describe(violations)

        tabs = page.eval("document.querySelectorAll('.wb-tab[role=tab]').length")
        page.click("[data-assist-copy]")
        page.wait_for(f"document.querySelectorAll('.wb-tab[role=tab]').length === {tabs + 1}")
        assert "big_spend" in page.text(".wb-tab[aria-selected=true]")

        # Register: refused by the browser until confirmed, then the engine's registration.
        register = "[id^=assist-register-] button[type=submit]"
        assert page.eval(f"document.querySelector('{register}').disabled") is False
        page.eval("(() => { document.querySelector('[id^=assist-confirm-]').checked = true; return true; })()")
        page.click(register)
        page.wait_for("document.querySelector('[data-assist-registered]')")
        assert [r["name"] for r in own.engine.registered] == ["big_spend"]
        assert page.exceptions == [], page.exceptions


def test_a_switch_made_in_admin_is_used_by_the_next_explain(page):
    with own_console(assist=seed(explainer=[REFUSAL], drafter=[REFUSAL])) as own:
        sign_in(page, own)
        open_page(page, own, "/queries/big_txn", "document.querySelector('#assist-explain-form')")
        page.click("#sink-failure .assist-why button[type=submit]")
        page.wait_for("document.querySelector('#assist-why-sink [data-assist-answered-by]')")
        assert page.eval("document.querySelector('#assist-why-sink [data-assist-answered-by]')"
                         ".dataset.assistAnsweredBy") == "explainer"

        # Admin · AI models: move explainer later in the explain chain -- drafter answers first now.
        open_page(page, own, "/admin/ai-models", "document.querySelector('#models-table')")
        down = ("[data-profile=explain] form[action='/admin/ai-models/profiles-down'] input[name=model]"
                "[value=explainer]")
        page.wait_for_navigation(lambda: page.eval(
            f"(() => {{ document.querySelector(\"{down}\").form.submit(); return true; }})()"))
        assert "in force for the next request" in page.text("[data-flash=success]")
        violations = axe(page)
        assert not violations, describe(violations)

        open_page(page, own, "/queries/big_txn", "document.querySelector('#assist-explain-form')")
        page.click("#sink-failure .assist-why button[type=submit]")
        page.wait_for("document.querySelector('#assist-why-sink [data-assist-answered-by]')")
        assert page.eval("document.querySelector('#assist-why-sink [data-assist-answered-by]')"
                         ".dataset.assistAnsweredBy") == "drafter"
        assert page.exceptions == [], page.exceptions


def test_the_workbench_diagnostics_offer_explain(page):
    with own_console(assist=seed(explainer=[REFUSAL])) as own:
        sign_in(page, own)
        open_page(page, own, "/workbench?sql=SELECT%20txn_id%20FROM%20txm",
                  "document.querySelector('.diag [data-assist-explain]')")
        page.click(".diag [data-assist-explain]")
        page.wait_for("document.querySelector('.diag .assist-slot [data-assist-explained]')")
        assert "amout is misspelled" in page.text(".diag .assist-slot")
        assert page.exceptions == [], page.exceptions
