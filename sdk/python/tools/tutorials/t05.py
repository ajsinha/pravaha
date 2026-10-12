"""Tutorial 5 -- Changing a running query safely (docs/tutorials/05-changing-a-running-query.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

big_payments' threshold moves from 1000 to 5000 while it is read: the dry runs, a replacement
backfilled from history beside the running version, a cutover under a subscriber, a rollback that
loses nothing, an abandoned replacement, and a finished one after which there is no way back.
"""

import re

from tutorial_captures import Background, Cli, Http, Join, Node, Open, Sh, Snap, Until

DOC = "05-changing-a-running-query.md"

NODE = Node(config="t05/application.yaml", files=(
    ("t05/txn.csv", "data/incoming/txn.csv"),
    ("t05/big_payments_v2.sql", "big_payments_v2.sql"),
))

V1 = "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000"
IDS = ("query", "--sql", "SELECT txn_id, amount FROM big_payments")
REPLACE = ("replace", "--name", "big_payments", "--sql-file", "big_payments_v2.sql", "--keys", "0")


def _history(answer):
    """Which versions have answered the name, and the replacement's state: what the tutorial reads.
    A version's start is the wall-clock instant of the cutover, in milliseconds: shown as <time>."""
    shown = {k: answer.get(k) for k in ("state", "candidate", "replacing", "history", "rollbackAvailable")
             if k in answer}
    shown["history"] = [re.sub(r"^from \d{13}:", "from <time>:", h) for h in shown.get("history") or []]
    return shown


#: A backfill's first progress line is taken while it reads: how far it has got is a matter of timing.
PROGRESS = ((r"^(backfilling .*\n  history )\d+ rows, \d+ of 1", r"\1N rows, N of 1"),)
#: The rate column of `pravaha replacements` is a measurement.
RATE = ((r"^(big_payments\s+[A-Z_]+\s+\d+\s+)\d+", r"\1N"),)


STEPS = (
    Cli("t05-register", ("register", "--name", "big_payments", "--sql", V1, "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_payments"), r"^6\s*$"),
    Cli("t05-describe", ("describe", "big_payments")),
    Cli("t05-replace-dry-run", (*REPLACE, "--dry-run")),
    Cli("t05-drop-dry-run", ("drop", "--name", "big_payments", "--dry-run")),
    Open("/queries/big_payments/replacement", "document.querySelector('#rep-none')"),
    Snap("01-replace-form"),
    Cli("t05-replace", (*REPLACE, "--backfill", "history", "--rate-limit", "10000", "--cutover", "manual", "--wait"),
        subs=PROGRESS),
    Cli("t05-replacements", ("replacements", "--name", "big_payments"), subs=RATE),
    Cli("t05-still-old", IDS),
    Open("/queries/big_payments/replacement"),
    Snap("02-caught-up"),
    Background("follower", Cli(None, ("subscribe", "--view", "big_payments"))),
    Cli("t05-cutover-dry-run", ("cutover", "--name", "big_payments", "--dry-run")),
    Cli("t05-cutover", ("cutover", "--name", "big_payments")),
    Join("follower", "t05-subscriber-told"),
    Cli("t05-new", IDS),
    Http("t05-history", "GET", "/api/v1/queries/big_payments/replacement", keep=_history),
    Open("/queries/big_payments/replacement"),
    Snap("03-cut-over"),
    Sh("t05-append", "echo '9,gina,1500,COMPLETED,2026-10-01T09:02:20Z' >> data/incoming/txn.csv",
       show="echo '9,gina,1500,COMPLETED,2026-10-01T09:02:20Z' >> \"$PRAVAHA_HOME/data/incoming/txn.csv\""),
    Until(("describe", "big_payments"), r"rows in\s+9"),
    Cli("t05-new-after-append", IDS),
    Cli("t05-rollback", ("rollback", "--name", "big_payments")),
    Cli("t05-old-again", IDS),
    Cli("t05-replace-again", (*REPLACE, "--wait"), subs=PROGRESS),
    Cli("t05-abandon-plan", ("abandon", "--name", "big_payments")),
    Cli("t05-abandon", ("abandon", "--name", "big_payments", "--yes")),
    Cli("t05-replacements-abandoned", ("replacements", "--name", "big_payments"), subs=RATE),
    Cli("t05-replace-third", (*REPLACE, "--wait"), subs=PROGRESS),
    Cli("t05-cutover-third", ("cutover", "--name", "big_payments")),
    Cli("t05-finish-dry-run", ("finish", "--name", "big_payments", "--dry-run")),
    Cli("t05-finish", ("finish", "--name", "big_payments", "--yes")),
    Cli("t05-rollback-refused", ("rollback", "--name", "big_payments")),
    Cli("t05-final", IDS),
    Cli("t05-describe-final", ("describe", "big_payments"), omit=(r"^(execution|registered|sql|SELECT|--)",)),
    Open("/queries/big_payments"),
    Snap("04-finished"),
)
