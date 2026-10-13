"""Tutorial 9 -- Exactly-once sinks (docs/tutorials/09-exactly-once-sinks.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Payments into three jdbc-sink tables in an H2 file: each user's spend per minute upserted
transactionally, and the big payments appended twice -- transactionally and not. A checkpoint is
waited for, payments arrive, and the node is killed before the next one: while it is down the
transactional tables hold what the last checkpoint committed and the other holds more; after the
restart the replay leaves the transactional log exactly right and the other with a duplicate.
"""

from tutorial_captures import Cli, Node, Open, Restart, Sh, Snap, Until

DOC = "09-exactly-once-sinks.md"

H2 = "com.h2database:h2:2.3.232"
JAR = "plugins/h2-2.3.232.jar"
URL = "jdbc:h2:./data/warehouse/warehouse;AUTO_SERVER=TRUE"
SHOWN_URL = "jdbc:h2:$PRAVAHA_HOME/data/warehouse/warehouse;AUTO_SERVER=TRUE"

NODE = Node(config="t09/application.yaml", jars=(H2,), files=(
    ("t09/streams", "conf/streams"),
    ("t09/sinks", "conf/sinks"),
    ("t09/connectors", "conf/connectors"),
    ("t09/txn.csv", "data/incoming/txn.csv"),
    ("t09/warehouse.sql", "warehouse.sql"),
    ("t09/spend_by_user.sql", "spend_by_user.sql"),
))


def h2(capture, sql: str) -> Sh:
    """A statement against the sinks' database with H2's Shell, as a reader runs it."""
    return Sh(capture, f'java -cp {JAR} org.h2.tools.Shell -url "{URL}" -user sa -sql "{sql}"',
              show=f'java -cp "$PRAVAHA_HOME/{JAR}" org.h2.tools.Shell -url "{SHOWN_URL}" -user sa -sql "{sql}"')


BIG = "SELECT txn_id, user_id, amount FROM txn WHERE amount >= 1000"
COUNTS = ("SELECT (SELECT COUNT(*) FROM payments_log) AS logged, (SELECT COUNT(*) FROM payments_log_plain) "
          "AS logged_plain, (SELECT COUNT(*) FROM spend_by_user) AS spend_rows")
DUPLICATES = ("SELECT 'payments_log' AS t, txn_id, COUNT(*) AS copies FROM payments_log GROUP BY txn_id "
              "HAVING COUNT(*) > 1 UNION ALL SELECT 'payments_log_plain', txn_id, COUNT(*) FROM payments_log_plain "
              "GROUP BY txn_id HAVING COUNT(*) > 1")
#: The node's own words for each registration's guarantee, from its log.
GUARANTEE = ("grep -ho \"query '[a-z_]*' writes to sink .*\" logs/pravaha-server.log | sort | cut -c1-150")
#: Waits for the checkpoint after the newest one there is: what follows then has a whole interval.
NEXT_CHECKPOINT = ('n=$(pravaha debug checkpoints --name big_log | head -n 1); '
                   'until [ "$(pravaha debug checkpoints --name big_log | head -n 1)" -gt "$n" ]; do sleep 0.5; done')


def h2_until(sql: str, pattern: str, timeout: float = 120) -> Until:
    return Until(f'java -cp {JAR} org.h2.tools.Shell -url "{URL}" -user sa -sql "{sql}"', pattern, timeout)


BEFORE_START = (
    Sh("t09-create", f'java -cp {JAR} org.h2.tools.RunScript -url "{URL}" -user sa -script warehouse.sql',
       show=f'java -cp "$PRAVAHA_HOME/{JAR}" org.h2.tools.RunScript -url "{SHOWN_URL}" -user sa -script warehouse.sql'),
)

STEPS = (
    Cli("t09-sinks", ("sinks",)),
    Cli("t09-register-spend", ("register", "--name", "spend_by_user", "--sql-file", "spend_by_user.sql",
                               "--keys", "0,2", "--sink", "spend_table")),
    Cli("t09-register-log", ("register", "--name", "big_log", "--sql", BIG, "--keys", "0", "--sink", "payments_log")),
    Cli("t09-register-plain", ("register", "--name", "big_log_plain", "--sql", BIG, "--keys", "0",
                               "--sink", "payments_log_plain")),
    Sh("t09-guarantee", GUARANTEE, show="grep -ho \"query '[a-z_]*' writes to sink .*\" "
                                        "\"$PRAVAHA_HOME/logs/pravaha-server.log\" | sort | cut -c1-150"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM spend_by_user"), r"^2\s*$"),
    Cli("t09-view", ("query", "--sql", "SELECT * FROM spend_by_user")),
    h2_until(COUNTS, r"^4\s+\|\s+4\s+\|\s+2\s*$"),
    h2("t09-table", "SELECT * FROM spend_by_user ORDER BY window_end, user_id"),
    h2("t09-counts", COUNTS),
    Open("/queries/spend_by_user"),
    Snap("01-query-sink"),
    Sh(None, NEXT_CHECKPOINT),
    Sh("t09-append", "printf '%s\\n' '7,ann,7000,2026-10-01T09:02:30Z' '8,carl,90,2026-10-01T09:03:10Z' "
                     ">> data/incoming/txn.csv",
       show="printf '%s\\n' '7,ann,7000,2026-10-01T09:02:30Z' '8,carl,90,2026-10-01T09:03:10Z' "
            ">> \"$PRAVAHA_HOME/data/incoming/txn.csv\""),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_log"), r"^5\s*$"),
    h2_until("SELECT COUNT(*) FROM payments_log_plain", r"^5\s*$", 20),
    Cli("t09-view-ahead", ("query", "--sql", "SELECT * FROM big_log")),
    h2("t09-counts-ahead", COUNTS),
    h2("t09-staged", "SELECT sink_id, COUNT(*) AS staged_batches FROM pravaha_sink_staging GROUP BY sink_id ORDER BY sink_id"),
    Restart(crash=True, during=h2("t09-while-down", COUNTS)),
    h2_until("SELECT COUNT(*) FROM payments_log", r"^5\s*$"),
    h2_until("SELECT COUNT(*) FROM spend_by_user", r"^6\s*$"),
    h2("t09-counts-after", COUNTS),
    h2("t09-duplicates", DUPLICATES),
    h2("t09-log", "SELECT * FROM payments_log ORDER BY txn_id"),
    h2("t09-table-after", "SELECT * FROM spend_by_user ORDER BY window_end, user_id"),
    Cli("t09-view-after", ("query", "--sql", "SELECT * FROM spend_by_user")),
    Open("/queries/big_log_plain"),
    Snap("02-plain"),
    Open("/views/spend_by_user/live", "document.getElementById('live-state').dataset.state === 'fresh'"),
    Snap("03-view"),
    Open("/plugins"),
    # The jdbc-sink card, with the three bindings and what each declares.
    Snap("04-plugins", js="(() => { const h = [...document.querySelectorAll('#plugin-cards h2')]"
                          ".find(e => e.textContent.trim() === 'jdbc-sink'); "
                          "h.closest('section').scrollIntoView({block: 'start'}); window.scrollBy(0, -110); return true; })()"),
)
