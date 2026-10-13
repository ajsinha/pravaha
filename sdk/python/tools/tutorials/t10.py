"""Tutorial 10 -- Alerts (docs/tutorials/10-alerts.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Stock levels per warehouse and a view of what is below its reorder point; an alert on the London rows
that tells a signed webhook (the tutorial's catcher.py, started here) and the node's log; keys that
fire and clear as stock is counted again; a snooze that holds a notification back and a resume that
sends it, an acknowledgement, a pause; the refusals; the console's Alerts pages.
"""

from tutorial_captures import Background, Cli, Join, Node, Open, Py, Sh, Snap, Until

DOC = "10-alerts.md"

NODE = Node(config="t10/application.yaml", files=(
    ("t10/streams", "conf/streams"),
    ("t10/stock.csv", "data/incoming/stock.csv"),
    ("t10/low_stock.sql", "low_stock.sql"),
))

SECRET = """python3 -c 'import secrets; print(secrets.token_hex(32))' > conf/hook.secret"""

BEFORE_START = (
    Sh("t10-secret", SECRET,
       show="""python3 -c 'import secrets; print(secrets.token_hex(32))' > "$PRAVAHA_HOME/conf/hook.secret\""""),
)

A = "low_stock_ldn"
SHOW_KEYS = f"pravaha alerts show {A} | sed -n '/^keys:/,/^$/p'"


def stock(capture, *lines: str) -> Sh:
    quoted = " ".join(f"'{line}'" for line in lines)
    return Sh(capture, f"printf '%s\\n' {quoted} >> data/incoming/stock.csv",
              show=f"printf '%s\\n' {quoted} >> \"$PRAVAHA_HOME/data/incoming/stock.csv\"")


def hooks(n: int) -> Until:
    """Until the catcher has received n notifications in all."""
    return Until(f"test $(wc -l < hooks.log) -ge {n} && echo yes", r"^yes$", 30)


STEPS = (
    Background("catcher", Py(None, "catcher.py", ("--port", "18099", "--secret-file", "conf/hook.secret",
                                                   "--log", "hooks.log"))),
    Sh(None, "touch hooks.log"),
    Cli("t10-channels", ("alerts", "channels")),
    Cli("t10-register", ("register", "--name", "low_stock", "--sql-file", "low_stock.sql", "--keys", "0,1")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM low_stock"), r"^3\s*$"),
    Cli("t10-low-stock", ("query", "--sql", "SELECT * FROM low_stock")),
    Cli("t10-print-sql", ("alert", "create", A, "--on", "low_stock", "--where", "warehouse = 'LDN'",
                          "--notify", "buyers,ops-log", "--severity", "warning", "--include", "on_hand,reorder_point",
                          "--print-sql")),
    Cli("t10-create", ("alert", "create", A, "--on", "low_stock", "--where", "warehouse = 'LDN'",
                       "--notify", "buyers,ops-log", "--severity", "warning", "--include", "on_hand,reorder_point")),
    hooks(2),
    # Two keys fire in one commit and their notifications arrive in either order: sorted, so the capture is stable.
    Sh("t10-fired", "sort hooks.log"),
    Cli("t10-ls", ("alerts", "ls")),
    # Two notifications decided in one commit are listed in the order they were delivered, which is not
    # fixed: sorted by key here, so the block does not move between runs.
    Sh("t10-show", f"pravaha alerts show {A} > show.txt; sed -n '1,/^AT /p' show.txt; sed '1,/^AT /d' show.txt | sort -k3",
       show=f"pravaha alerts show {A}"),
    # The log channel's line, up to the JSON body it carries (whose idempotency key differs every run).
    Sh("t10-log-channel", "grep -o 'pravaha.alerts .*: alert low_stock_ldn FIRED[^{]*' logs/pravaha-server.log | sort",
       show="grep -o 'pravaha.alerts .*: alert low_stock_ldn FIRED[^{]*' \"$PRAVAHA_HOME/logs/pravaha-server.log\" | sort"),
    Open("/alerts"),
    Snap("01-alerts"),
    stock("t10-restock", "sku-200,LDN,8,10,2026-10-01T09:00:05Z,D", "sku-200,LDN,60,10,2026-10-01T09:01:00Z,I"),
    hooks(3),
    Sh("t10-cleared", "tail -n 1 hooks.log"),
    Cli("t10-snooze", ("alerts", "snooze", A, "1h")),
    stock("t10-sku-100-low", "sku-100,LDN,40,10,2026-10-01T09:00:00Z,D", "sku-100,LDN,6,10,2026-10-01T09:01:30Z,I"),
    Until(("alerts", "show", A), r"sku=sku-100, warehouse=LDN\s+FIRING"),
    Sh("t10-snoozed-keys", SHOW_KEYS, show=f"pravaha alerts show {A}"),
    Sh("t10-nothing-sent", "wc -l < hooks.log"),
    Open(f"/alerts/{A}"),
    Snap("02-snoozed"),
    Cli("t10-resume", ("alerts", "resume", A)),
    hooks(4),
    Sh("t10-sent-on-resume", "tail -n 1 hooks.log"),
    Cli("t10-ack", ("alerts", "ack", A, "--key", "sku=sku-400, warehouse=LDN")),
    Cli("t10-pause", ("alerts", "pause", A)),
    stock("t10-sku-400-restock", "sku-400,LDN,12,20,2026-10-01T09:00:15Z,D", "sku-400,LDN,30,20,2026-10-01T09:02:00Z,I"),
    Until(("alerts", "show", A), r"sku=sku-400, warehouse=LDN\s+CLEARED"),
    Sh("t10-paused-keys", SHOW_KEYS, show=f"pravaha alerts show {A}"),
    Cli("t10-resume-again", ("alerts", "resume", A)),
    hooks(5),
    Sh("t10-cleared-on-resume", "tail -n 1 hooks.log"),
    Cli("t10-show-alerts", ("query", "--sql", "SHOW ALERTS")),
    Open(f"/alerts/{A}"),
    Snap("03-alert"),
    Cli("t10-or-refused", ("alert", "create", "low_or_tiny", "--on", "low_stock", "--where",
                           "warehouse = 'LDN' OR on_hand < 2", "--notify", "buyers")),
    Cli("t10-channel-refused", ("alert", "create", "low_pager", "--on", "low_stock", "--notify", "pager")),
    Cli("t10-drop-refused", ("drop", "--name", "low_stock", "--yes")),
    Open("/queries/low_stock"),
    Snap("04-query"),
)
