"""Tutorial 1 -- Your first 15 minutes (docs/tutorials/01-first-15-minutes.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The project ``pravaha init`` writes, run as the reader runs it: its configuration folder (the
stream in ``conf/streams/txn.yaml``, the watch interval made ten seconds) and its CSV, a second stream
file added and taken out while the node runs, a first query registered from the file it scaffolds, a
row appended, doctor, and the view read from Python.
"""

from urllib.parse import quote

from tutorial_captures import Background, Cli, Http, Join, Node, Open, Py, Sh, Snap, Ui, Until

DOC = "01-first-15-minutes.md"

#: The node is the project's: ``shop/conf/`` (its application.yaml and streams/), its CSV where the
#: stream file reads it.
NODE = Node(config="@shop/conf/application.yaml", files=(("@shop/conf/streams", "conf/streams"),
                                                         ("@shop/data/txn.csv", "data/incoming/txn.csv")))

PRE = (
    Cli("t01-init", ("init", "shop")),
    # Step 1: the tutorial looks at the folder every ten seconds, not the scaffold's ten minutes.
    Sh(None, "sed -i 's/watch-interval: 10m/watch-interval: 10s/' shop/conf/application.yaml"),
)

REFUNDS = """\
schema: "refund_id:INT64,txn_id:INT64,amount:INT64,event_time:TIMESTAMP"
event-time: event_time
out-of-orderness: 5
source:
  plugin: filesystem
  options:
    path: data/incoming/refunds.csv
    follow: true
"""

SQL_FILE = "shop/queries/spend_per_minute.sql"
#: The query the reader registers in the Workbench: every payment of 500 or more, keyed by its id.
BIG = "SELECT txn_id, user_id, amount, status FROM txn WHERE amount >= 500"
_SET = "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set"
FILL_REGISTER = ("(() => { const n = document.querySelector('#reg-name'); " + _SET + ".call(n, 'big_payments');"
                 " n.dispatchEvent(new Event('input', {bubbles: true}));"
                 " document.querySelector('#key-txn_id').click(); return true; })()")
SUBMIT_REGISTER = ("(() => { [...document.querySelectorAll('#workbench-app button[type=submit]')]"
                   ".find(b => !b.disabled && b.closest('form').querySelector('#reg-name')).click(); return true; })()")
UNBOUNDED = "SELECT user_id, SUM(amount) AS spend FROM txn GROUP BY user_id"

STEPS = (
    Cli("t01-status", ("status",)),
    Sh("t01-log", "grep -oE '(sources bound|stream txn|Flight SQL listening|identity: captive).*' logs/pravaha-server.log",
       show="grep -oE '(sources bound|stream txn|Flight SQL listening|identity: captive).*' node/logs/pravaha-server.log"),
    Open("/login"),
    Snap("01-sign-in"),
    Cli("t01-whoami", ("whoami",)),
    Cli("t01-streams", ("streams", "describe", "txn")),
    Open("/catalog/streams/txn"),
    Snap("02-stream"),
    # Step 5: a stream file added while the node runs, then taken out.
    Sh(None, "echo '1,2,50,2026-10-01T09:02:30Z' > data/incoming/refunds.csv"),
    Sh(None, "cat > conf/streams/refunds.yaml <<'YAML'\n" + REFUNDS + "YAML"),
    Until(("streams",), r"^refunds\s", timeout=60),
    Cli("t01-declarations", ("declarations",)),
    Cli("t01-streams-list", ("streams",)),
    Open("/catalog"),
    Snap("03-catalog"),
    Sh("t01-remove", "rm conf/streams/refunds.yaml", show='rm "$STREAMS/refunds.yaml"'),
    Until("pravaha streams | grep -c '^refunds ' || true", r"^0$", timeout=60),
    Cli("t01-streams-after", ("streams",)),
    Cli("t01-validate-refused", ("validate", "--sql", UNBOUNDED)),
    Open("/workbench?panel=register&sql=" + quote(BIG), "document.querySelector('#key-txn_id')"),
    Ui(FILL_REGISTER, "document.querySelector('#key-txn_id').checked",
       instead=Cli(None, ("register", "--name", "big_payments", "--sql", BIG, "--keys", "0"))),
    Ui(SUBMIT_REGISTER, "document.querySelector('#workbench-app .alert-success')", instead=Http(None, "GET", "/api/v1/status")),
    Snap("04-workbench", js="document.querySelector('#workbench-app .alert-success').scrollIntoView({block: 'end'})"),
    Cli("t01-register", ("register", "--name", "spend_per_minute", "--sql-file", SQL_FILE, "--keys", "0,1"),
        show="pravaha register --name spend_per_minute --sql-file queries/spend_per_minute.sql --keys 0,1"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM spend_per_minute"), r"^7\s*$"),
    Cli("t01-queries", ("queries",)),
    Cli("t01-query", ("query", "--sql", "SELECT * FROM spend_per_minute")),
    Open("/views/spend_per_minute?key=user_id&value=bob"),
    Snap("05-view"),
    Background("follow", Cli(None, ("subscribe", "--view", "spend_per_minute", "--limit", "2"))),
    Open("/views/spend_per_minute/live", "document.getElementById('live-state')"),
    Sh("t01-append", "echo '13,bob,10,COMPLETED,2026-10-01T09:05:00Z' >> data/incoming/txn.csv",
       show="echo '13,bob,10,COMPLETED,2026-10-01T09:05:00Z' >> \"$INCOMING/txn.csv\""),
    Join("follow", "t01-subscribe"),
    Snap("06-live"),
    Cli("t01-query-after", ("query", "--sql", "SELECT user_id, window_start, spend, txns FROM spend_per_minute "
                                              "WHERE window_start >= TIMESTAMP '2026-10-01 09:03:00'")),
    Cli("t01-doctor", ("doctor",), omit=(r"^\S+\s+port \d+\s",)),
    Py("t01-python", "read_view.py"),
)
