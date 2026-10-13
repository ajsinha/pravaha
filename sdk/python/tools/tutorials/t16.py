"""Tutorial 16 -- AI in Pravaha (docs/tutorials/16-ai-in-pravaha.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The engine's AI layer switched on with the mock provider (regular expressions play the model: no
network, no key): Admin -> AI and Test connection; ask your data (the SQL the model chose, the rows,
the chart), a follow-up, a repair turn, a question no view answers; a continuous query drafted, dry-run
and registered on confirmation; an alert from words, and one that cannot be written; a query and a
refusal explained; why a view changed -- echoed by the mock, so the reader sees what a model is sent --
and a card number masked out of a prompt; ask about this page; the policy refusing a second user; the
audit trail of every call, without its text. The console's own AI layer is on for the pictures.
"""

import re

from tutorial_captures import Cli, Http, Node, Open, Sh, Snap, Ui, Until

DOC = "16-ai-in-pravaha.md"

NODE = Node(config="t16/application.yaml", files=(
    ("t16/streams", "conf/streams"),
    ("t16/payments.csv", "data/incoming/payments.csv"),
    ("t16/mock.yaml", "conf/ai/mock.yaml"),
    ("t16/region_minutes.sql", "region_minutes.sql"),
    ("t16/big_payments.sql", "big_payments.sql"),
))

#: The console's own AI layer, whose one provider is the engine: on for the pictures.
CONSOLE = {"ai.enabled": "true"}

PASSWORD = "Tutorial-ann-2026"
#: Each prompt's markers carry a random id per request.
IDS = ((r"id=[0-9a-f]{6,}", "id=<id>"),)
#: The audit lists: the sequence numbers count every decision the node made.
AUDIT = ((r"^\d+\s+(?=\d{4}-\d\d-\d\dT)", "N         "), (r"^\d+ retained of ", "N retained of "),
         (r"ms=\d+", "ms=N"), (r"--cursor \d+", "--cursor N"))


def as_ann(capture, *argv: str) -> Sh:
    words = " ".join(_quoted(w) for w in argv)
    return Sh(capture, f"PRAVAHA_TOKEN=$(cat ann.token) pravaha {words}", show=f"PRAVAHA_TOKEN=$ANN pravaha {words}")


def _quoted(word: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_./:=,@+%-]+", word):
        return word
    return '"' + word.replace("\\", "\\\\").replace('"', '\\"') + '"' if "'" in word else "'" + word + "'"


def submit(form: str, field: str, text: str) -> str:
    """The browser's part: the form shown, the words typed, the form sent."""
    return (f"(() => {{ const f = document.querySelector({form!r}); f.hidden = false; "
            f"f.querySelector({field!r}).value = {text!r}; f.requestSubmit(); return true; }})()")


#: The scratch node's home, as a reader's is: $PRAVAHA_HOME.
HOME = ("(() => { const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); "
        "for (let n = walk.nextNode(); n; n = walk.nextNode()) "
        "n.nodeValue = n.nodeValue.replace(/\\/\\S*pravaha-captures-[^/\\s]+/g, '$PRAVAHA_HOME'); return true; })()")


def to(selector: str, last: bool = False) -> str:
    """Scrolls the page so ``selector`` (its last match, with ``last``) is at the top, under the bar."""
    pick = f"[...document.querySelectorAll({selector!r})].pop()" if last else f"document.querySelector({selector!r})"
    return (f"(() => {{ document.activeElement && document.activeElement.blur(); {pick}.scrollIntoView({{block: 'start'}}); "
            "window.scrollBy(0, -80); return true; })()")


TOP = "(() => { document.activeElement && document.activeElement.blur(); window.scrollTo(0, 0); return true; })()"
NOTHING = Sh(None, "true")
DRAFT = "spend per region per minute"
ALERT = "tell me when a region spends over 5000 in a minute"

STEPS = (
    Cli("t16-register-minutes", ("register", "--name", "region_minutes", "--sql-file", "region_minutes.sql",
                                 "--keys", "0,2")),
    Cli("t16-register-big", ("register", "--name", "big_payments", "--sql-file", "big_payments.sql", "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM region_minutes"), r"^6\s*$"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_payments"), r"^4\s*$"),
    Http("t16-features", "GET", "/api/v1/ai/features"),
    Http("t16-admin", "GET", "/api/v1/admin/ai", keep=lambda a: {
        "enabled": a["enabled"], "dir": a["dir"], "defaultModel": a["defaultModel"], "types": len(a["types"]),
        "providers": [{k: p[k] for k in ("name", "type", "enabled", "keySet", "health")} | {
            "models": [m["ref"] for m in p["models"]]} for p in a["providers"]],
        "fileErrors": a["fileErrors"]}),
    Open("/admin/ai", "document.querySelector('[data-ai-test]')"),
    Ui("(() => { document.querySelector('[data-ai-test]').click(); return true; })()",
       "document.querySelector('[data-ai-test-result][data-ok]')",
       instead=Http(None, "POST", "/api/v1/admin/ai/test", body={"model": "mock/m", "prompt": "ping"})),
    Snap("01-admin-ai", js=f"{TOP} && {HOME}"),
    # Ask your data.
    Cli("t16-ask", ("query", "--ask", "total spend per region", "--chart")),
    Cli("t16-chat", ("chat",), stdin="total spend per region\nand only for EU?\n"),
    Cli("t16-ask-line", ("query", "--ask", "total spend per minute", "--chart")),
    Cli("t16-ask-repair", ("query", "--ask", "what is the average big payment", "--json")),
    Cli("t16-ask-unanswerable", ("query", "--ask", "what is the weather in Paris")),
    Open("/ask", "document.querySelector('[data-ai-q]')"),
    Ui(submit("form[data-ai-task=ask]", "[data-ai-q]", "total spend per region"),
       "document.querySelector('[data-ai-answer] [data-ai-chart]')", instead=NOTHING),
    Ui(submit("form[data-ai-task=ask]", "[data-ai-q]", "and only for EU?"),
       "document.querySelectorAll('[data-ai-answer]').length >= 2", instead=NOTHING),
    Snap("02-ask", js=to("[data-ai-answer]", last=True)),
    # Draft a continuous query, then register it on confirmation.
    Cli("t16-draft", ("ask", DRAFT, "--engine")),
    Cli("t16-draft-register", ("ask", DRAFT, "--engine", "--register", "--yes")),
    Open("/workbench?new=1", "document.querySelector('[data-ai-open=ai-describe-form]:not([hidden])')"),
    Ui(submit("#ai-describe-form", "textarea", DRAFT), "document.querySelector('[data-ai-draft] [data-ai-dry-run]')",
       instead=NOTHING),
    Snap("03-draft", js=to("#ai-describe-form")),
    # Plain-English alerts.
    Cli("t16-alert", ("alert", "--ask", ALERT)),
    Cli("t16-alert-create", ("alert", "--ask", ALERT, "--yes")),
    Until(("alerts",), r"region_over_5000\s+region_minutes\s+ACTIVE\s+1\s"),
    Cli("t16-alerts", ("alerts",)),
    Cli("t16-alert-refused", ("alert", "--ask", "tell me when a user spends over 3000 per day")),
    Open("/alerts", "document.querySelector('[data-ai-open=ai-describe-alert-form]:not([hidden])')"),
    Ui(submit("#ai-describe-alert-form", "textarea", ALERT), "document.querySelector('[data-ai-alert-draft]')",
       instead=NOTHING),
    Snap("04-alert", js=TOP),
    # Explain: a query, a refusal.
    Cli("t16-explain-sql", ("explain-sql", "--query", "region_minutes", "--engine")),
    Cli("t16-why", ("why", "PRV-2050", "--sql", "SELECT user_id, SUM(amount) AS total FROM payments GROUP BY user_id",
                    "--engine")),
    Open("/queries/region_minutes", "document.querySelector('[data-ai-explain=sql]:not([hidden])')"),
    Ui("(() => { document.querySelector('[data-ai-explain=sql]').click(); return true; })()",
       "document.querySelector('[data-ai-explained=sql]')", instead=NOTHING),
    Snap("05-explain", js="(() => { document.activeElement && document.activeElement.blur(); "
                          "document.getElementById('ai-explain-query').scrollIntoView({block: 'center'}); return true; })()"),
    # Why did it change? -- and what a model is sent.
    Cli("t16-why-changed-first", ("explain", "--why-changed", "big_payments")),
    Sh("t16-append", "printf '%s\\n' '9,gus,EU,4111-1111-1111-9999,3000,2026-10-01T09:02:30Z' "
                     "'10,hal,US,5500-0000-0000-2222,90,2026-10-01T09:02:40Z' >> data/incoming/payments.csv",
       show="printf '%s\\n' '9,gus,EU,4111-1111-1111-9999,3000,2026-10-01T09:02:30Z' "
            "'10,hal,US,5500-0000-0000-2222,90,2026-10-01T09:02:40Z' >> \"$PRAVAHA_HOME/data/incoming/payments.csv\""),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_payments"), r"^5\s*$"),
    Cli("t16-why-changed", ("explain", "--why-changed", "big_payments"), subs=IDS),
    Cli("t16-masked", ("why", "PRV-7006", "--sql",
                       "SELECT payment_id FROM big_payments WHERE card_number = '4111-1111-1111-1234'", "--engine"),
        subs=IDS),
    # Ask about this page (the console's About panel).
    Open("/catalog/streams/payments", "document.querySelector('form[data-ai-task=page]')"),
    Ui("(() => { document.getElementById('page-help').open = true; return true; })() && "
       + submit("form[data-ai-task=page]", "[name=question]", "what is allowed lateness?"),
       "document.querySelector('[data-ai-page-answer]')", instead=NOTHING),
    Snap("06-page", js="(() => { document.activeElement && document.activeElement.blur(); "
                       "document.getElementById('page-help').scrollIntoView({block: 'start'}); window.scrollBy(0, -90); "
                       "return true; })()"),
    # Who may: a second person, whom the policy does not let have a query drafted.
    Sh("t16-user", f"printf '%s\\n' '{PASSWORD}' | pravaha user create ann --roles analyst --password-stdin"),
    Sh(None, f"printf '%s\\n' '{PASSWORD}' | pravaha login --user ann --password-stdin 2>/dev/null > ann.token"),
    as_ann("t16-ann-features", "query", "--ask", "total spend per region"),
    as_ann("t16-ann-draft", "ask", DRAFT, "--engine"),
    # The audit: every call, never its text.
    Cli("t16-audit-calls", ("audit", "--action", "ai.call", "--limit", "4"), subs=AUDIT),
    Sh("t16-audit-detail", "pravaha audit --action ai.call --limit 3 --json | python3 -c \"import json, sys; "
                           "[print(e['principal'], e.get('detail')) for e in json.load(sys.stdin)['events']]\"",
       subs=((r"ms=\d+", "ms=N"),)),
    Cli("t16-audit-asks", ("audit", "--action", "ai.ask", "--limit", "3"), subs=AUDIT),
)
