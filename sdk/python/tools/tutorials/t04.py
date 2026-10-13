"""Tutorial 4 -- Following a view (docs/tutorials/04-following-a-view.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

big_payments followed every way a reader can: the CLI with a snapshot, a correction arriving as -1
and +1 (the source's op column), a filter applied at the server, a copy kept by weights from Python,
the Java client the console writes, psql through the PostgreSQL gateway with an API key, a
subscription that reconnects across a restart, and the end a drop gives it.
"""

from tutorial_captures import Background, Cli, Join, Node, Open, Py, Restart, Sh, Sleep, Snap, Until

DOC = "04-following-a-view.md"

NODE = Node(config="t04/application.yaml", pgwire=True, files=(("t04/streams", "conf/streams"),
                                                                  ("t04/txn.csv", "data/incoming/txn.csv")))

BIG = "SELECT txn_id, user_id, amount FROM txn WHERE amount >= 500"
PSQL = 'psql "host=127.0.0.1 port={port} dbname=pravaha user=pravaha-dev-admin"'


def append(capture: str, *lines: str) -> Sh:
    quoted = " ".join(f"'{line}'" for line in lines)
    return Sh(capture, f"printf '%s\\n' {quoted} >> data/incoming/txn.csv",
              show=f"printf '%s\\n' {quoted} >> \"$PRAVAHA_HOME/data/incoming/txn.csv\"")


def psql(capture: str, sql: str) -> Sh:
    return Sh(capture, f"""PGPASSWORD="$(cat bi.key)" {PSQL.format(port="{pg}")} -c '{sql}'""",
              show=f"""PGPASSWORD="$KEY" {PSQL.format(port=5433)} -c '{sql}'""")


STEPS = (
    Cli("t04-register", ("register", "--name", "big_payments", "--sql", BIG, "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_payments"), r"^4\s*$"),
    Background("snapshot", Cli(None, ("subscribe", "--view", "big_payments", "--snapshot", "--limit", "5"))),
    append("t04-append-one", "7,frank,2000,COMPLETED,2026-10-01T09:01:50Z,I"),
    Join("snapshot", "t04-subscribe-snapshot"),
    Background("correct", Cli(None, ("subscribe", "--view", "big_payments", "--limit", "2"))),
    Open("/views/big_payments/live", "document.getElementById('live-state')"),
    append("t04-append-correction", "6,bob,700,COMPLETED,2026-10-01T09:01:30Z,D",
           "6,bob,750,COMPLETED,2026-10-01T09:01:30Z,I"),
    Join("correct", "t04-subscribe-correction"),
    Snap("01-live-correction"),
    Background("filtered", Cli(None, ("subscribe", "--view", "big_payments", "--filter", "user_id=alice",
                                      "--limit", "1"))),
    append("t04-append-two", "8,alice,600,COMPLETED,2026-10-01T09:02:05Z,I",
           "9,carol,800,COMPLETED,2026-10-01T09:02:10Z,I"),
    Join("filtered", "t04-subscribe-filtered"),
    Cli("t04-filter-refused", ("subscribe", "--view", "big_payments", "--filter", "merchant=acme", "--limit", "1")),
    Open("/views/big_payments/live?filter=user_id%3Dalice", "document.getElementById('live-state')"),
    Snap("02-live-filtered"),
    Background("python", Py(None, "follow.py"), settle=6),
    append("t04-append-python", "9,carol,800,COMPLETED,2026-10-01T09:02:10Z,D",
           "9,carol,850,COMPLETED,2026-10-01T09:02:10Z,I"),
    Join("python", "t04-python"),
    Open("/views/big_payments?client=java"),
    Snap("03-java", js="document.getElementById('client-code').scrollIntoView({block: 'start'})"),
    Sh("t04-key", "pravaha key create bi-reader --days 30 | tee bi.key",
       show="pravaha key create bi-reader --days 30"),
    psql("t04-psql-read", "SELECT * FROM big_payments"),
    psql("t04-psql-tables", "\\dt"),
    psql("t04-psql-write", "DROP CONTINUOUS QUERY big_payments"),
    Open("/views/big_payments?client=psql"),
    Snap("04-psql", js="document.getElementById('client-code').scrollIntoView({block: 'start'})"),
    Background("reconnect", Cli(None, ("subscribe", "--view", "big_payments", "--reconnect", "--limit", "1"))),
    Restart(),
    # The node is back with its query (the registry journal under PRAVAHA_HOME) and has re-read the file;
    # the subscriber reopens its stream within its backoff (0.25 s to 10 s). Then a new payment.
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_payments"), r"^7\s*$"),
    Sleep(12),
    append("t04-append-after-restart", "10,erin,950,COMPLETED,2026-10-01T09:02:30Z,I"),
    Join("reconnect", "t04-subscribe-reconnect", timeout=120),
    Background("ends", Cli(None, ("subscribe", "--view", "big_payments"))),
    Cli("t04-drop", ("drop", "--name", "big_payments", "--yes")),
    Join("ends", "t04-subscribe-ends"),
)
