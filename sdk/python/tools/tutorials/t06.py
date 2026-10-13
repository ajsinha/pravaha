"""Tutorial 6 -- Investigating an incident (docs/tutorials/06-investigating-an-incident.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A customer says erin paid acme twice in the minute from 09:04 and one payment is missing. The node's
health, top, metrics and lanes; the malformed payment on the dead-letter queue, a replay that fails
again, the correction at the source; then the time-travel debugger forked from a checkpoint taken
before the incident, stepped row by row and in event time, exported as a test.
"""

from tutorial_captures import Cli, Node, Open, Sh, Snap, Until

DOC = "06-investigating-an-incident.md"

NODE = Node(config="t06/application.yaml", files=(
    ("t06/streams", "conf/streams"),
    ("t06/txn.csv", "data/incoming/txn.csv"),
    ("t06/spend_per_minute.sql", "spend_per_minute.sql"),
))

INCOMING = '"$PRAVAHA_HOME/data/incoming/txn.csv"'
Q = ("--name", "spend_per_minute")
#: The newest dead letter's id, from `pravaha dlq list --json` (the tutorial copies it from the list).
NEWEST = """"$(python3 -c 'import json,sys; print(json.load(sys.stdin)["entries"][0]["id"])' < dlq.json)\""""
METRICS = ("pravaha metrics | grep -E "
           "'^pravaha_query_(rows_in|dead_letters|dead_letters_fraction|feed_stopped|checkpoint_failures_total)\\{'")
#: `pravaha top`'s watermark delay is against this machine's clock, and the sample data is from 2026-10-01.
DELAY = ((r"^(spend_per_minute\s+RUNNING\s+own\s+\d+\s+\S+\s+)\d+d", r"\1Nd "),
         (r"\d+\.\d KiB", "NN.N KiB"))


def append(capture: str, *lines: str) -> Sh:
    quoted = " ".join(f"'{line}'" for line in lines)
    return Sh(capture, f"printf '%s\\n' {quoted} >> data/incoming/txn.csv",
              show=f"printf '%s\\n' {quoted} >> {INCOMING}")


def debug(capture: str, verb: str, *rest: str) -> Sh:
    """A command on the debug session the fork opened, which the tutorial keeps in $S."""
    tail = (" " + " ".join(rest)) if rest else ""
    return Sh(capture, f'pravaha debug {verb} --session "$(cat session.id)"{tail}',
              show=f'pravaha debug {verb} --session "$S"{tail}')


STEPS = (
    Cli("t06-register", ("register", *Q, "--sql-file", "spend_per_minute.sql", "--keys", "0,1")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM spend_per_minute"), r"^8\s*$"),
    Cli("t06-health", ("health",)),
    Cli("t06-status", ("status",)),
    Cli("t06-top", ("top", "--once", "--interval", "1"), subs=DELAY),
    Cli("t06-lanes", ("lanes",)),
    Open("/admin/lanes"),
    Snap("01-lanes"),
    # A checkpoint from before the incident: the debugger starts from one.
    Until(("debug", "checkpoints", *Q), r"^\s*1\s*$"),
    Sh("t06-checkpoints", "pravaha debug checkpoints --name spend_per_minute | tail -n 1"),
    append("t06-incident", "11,erin,acme,7777,2026-10-01T09:04:10Z", "12,erin,acme,77.70,2026-10-01T09:04:20Z"),
    Until(("dlq", "count", *Q), r"^total\s+1\s*$"),
    Sh("t06-metrics", METRICS),
    Cli("t06-dlq-count", ("dlq", "count", *Q)),
    # The WHEN column is as wide as its widest instant, and an instant drops trailing zeros of its
    # milliseconds: the column is set to one width here, so the table does not move between runs.
    Cli("t06-dlq-list", ("dlq", "list", *Q), subs=(
        (r"^ID(\s+)WHEN\s+CODE", r"ID\1WHEN                    CODE"),
        (r"^(\S+\s+)\d{4}-\d\d-\d\dT[\d:.]+Z\s+", r"\1<time>                  "))),
    Sh(None, "pravaha dlq list --name spend_per_minute --json > dlq.json"),
    Sh("t06-dlq-show", f"pravaha dlq show --name spend_per_minute --id {NEWEST}",
       show="pravaha dlq show --name spend_per_minute --id <id>"),
    Open("/queries/spend_per_minute/dead-letters"),
    Snap("02-dead-letters"),
    Sh("t06-dlq-replay", f"pravaha dlq replay --name spend_per_minute --id {NEWEST}",
       show="pravaha dlq replay --name spend_per_minute --id <id>"),
    append("t06-correction", "12,erin,acme,7770,2026-10-01T09:04:20Z", "13,frank,initech,50,2026-10-01T09:05:30Z"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM spend_per_minute"), r"^10\s*$"),
    Cli("t06-erin", ("query", "--sql", "SELECT * FROM spend_per_minute WHERE user_id = 'erin'")),
    # Operations once the per-query meters have been reconciled (every 15 s): the numbers, not "not published yet".
    Open("/operations", "document.querySelector('#chart-rate canvas')"),
    Snap("03-operations", ready="document.querySelector('td [role=meter]')", timeout=60,
         js="document.querySelector('td [role=meter]') || setTimeout(() => location.reload(), 16000)"),
    Sh("t06-fork", "pravaha debug fork --name spend_per_minute --checkpoint 1 > session.id",
       show="S=$(pravaha debug fork --name spend_per_minute --checkpoint 1)"),
    debug("t06-step-row", "step", "--step", "row"),
    debug("t06-step-rows", "step", "--step", "rows:5"),
    debug("t06-state", "state"),
    Open("/queries/spend_per_minute/debug"),
    Snap("04-debugger"),
    debug("t06-step-watermark", "step", "--step", "watermark:1790845560000000000"),
    debug("t06-view", "view"),
    debug("t06-fixture", "fixture", "--name", "'erin payment dead-lettered in minute four'",
          "--out", "ErinFixtureTest.java"),
    Sh("t06-fixture-expected", "sed -n '/EXPECTED = List.of(/,/);/p' ErinFixtureTest.java"),
    debug("t06-end", "end"),
    Cli("t06-sessions", ("debug", "sessions")),
)
