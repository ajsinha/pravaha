"""Tutorial 8 -- Change data capture (docs/tutorials/08-change-data-capture.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A PostgreSQL in Docker with logical decoding on; its orders table captured by postgres-cdc from the
write-ahead log -- refused first for want of REPLICA IDENTITY FULL, then snapshotted and followed;
inserts, updates and deletes reaching a maintained view as +1 and -1, a subscriber seeing each, a
query over the view whose totals go down; the replication slot on the database's side.
"""

from tutorial_captures import Background, Cli, Container, Join, Node, Open, Restart, Sh, Snap, Until

DOC = "08-change-data-capture.md"

POSTGRES = Container(
    name="pravaha-postgres", image="postgres:16-alpine", port=15432, inside=5432,
    env=(("POSTGRES_HOST_AUTH_METHOD", "trust"), ("POSTGRES_DB", "shop")),
    args=("-c", "wal_level=logical"),
    # Over TCP: the server initdb starts first listens on its socket only, so this answers the real one.
    ready="docker exec {container} psql -h 127.0.0.1 -U postgres -d shop -c 'SELECT 1'",
)

NODE = Node(config="t08/application.yaml", containers=(POSTGRES,), files=(
    ("t08/streams", "conf/streams"),
    ("t08/connectors", "conf/connectors"),
    ("t08/shop.sql", "shop.sql"),
    ("t08/open_orders.sql", "open_orders.sql"),
))


def psql(capture, sql: str = "", script: str = "") -> Sh:
    """psql in the database's container, as a reader runs it."""
    if script:
        return Sh(capture, f"docker exec -i {{pravaha-postgres}} psql -U postgres -d shop < {script}",
                  show=f"docker exec -i pravaha-postgres psql -U postgres -d shop < {script}")
    return Sh(capture, f'docker exec {{pravaha-postgres}} psql -U postgres -d shop -c "{sql}"',
              show=f'docker exec pravaha-postgres psql -U postgres -d shop -c "{sql}"')


BY_CUSTOMER = ("SELECT customer, COUNT(*) AS open_orders, SUM(amount) AS open_amount "
               "FROM open_orders GROUP BY customer")
LIVE = "document.getElementById('live-state').dataset.state === 'fresh'"
#: What `pravaha describe` prints about this run rather than the query; after the restart "rows in"
#: counts from the checkpoint the restore took, which depends on when the last one fell.
DESCRIBE_OMIT = (r"^(execution|registered|sql|SELECT|FROM|WHERE|GROUP|rows in|--)",)


def rows(view: str, n: int) -> Until:
    return Until(("query", "--sql", f"SELECT COUNT(*) AS n FROM {view}"), rf"^{n}\s*$")


BEFORE_START = (
    Sh(None, "mkdir -p data"),
    psql("t08-load", script="shop.sql"),
    psql("t08-wal-level", "SHOW wal_level"),
)

STEPS = (
    Cli("t08-stream", ("streams", "describe", "orders")),
    Open("/catalog/streams/orders"),
    Snap("01-stream"),
    Cli("t08-refused", ("register", "--name", "open_orders", "--sql-file", "open_orders.sql", "--keys", "0")),
    psql("t08-replica-identity", "ALTER TABLE public.orders REPLICA IDENTITY FULL"),
    Cli("t08-register", ("register", "--name", "open_orders", "--sql-file", "open_orders.sql", "--keys", "0")),
    rows("open_orders", 3),
    Cli("t08-snapshot", ("query", "--sql", "SELECT * FROM open_orders")),
    psql("t08-slot", "SELECT slot_name, plugin, slot_type, active FROM pg_replication_slots"),
    Cli("t08-second-refused", ("register", "--name", "big_orders", "--sql",
                               "SELECT order_id, amount FROM orders WHERE amount > 100", "--keys", "0")),
    Cli("t08-register-by-customer", ("register", "--name", "open_by_customer", "--sql", BY_CUSTOMER, "--keys", "0")),
    rows("open_by_customer", 3),
    Cli("t08-by-customer", ("query", "--sql", "SELECT * FROM open_by_customer")),
    Open("/views/open_orders/live", LIVE),
    Background("follower", Cli(None, ("subscribe", "--view", "open_orders", "--limit", "5"))),
    psql("t08-insert", "INSERT INTO orders VALUES (5, 'dana', 'OPEN', 60)"),
    psql("t08-update", "UPDATE orders SET amount = 150 WHERE order_id = 1"),
    psql("t08-ship", "UPDATE orders SET status = 'SHIPPED' WHERE order_id = 4"),
    psql("t08-delete", "DELETE FROM orders WHERE order_id = 2"),
    Join("follower", "t08-subscribed"),
    Snap("02-live"),
    Cli("t08-after", ("query", "--sql", "SELECT * FROM open_orders")),
    rows("open_by_customer", 2),
    Cli("t08-by-customer-after", ("query", "--sql", "SELECT * FROM open_by_customer")),
    Open("/views/open_by_customer/live", LIVE),
    psql("t08-transaction", "BEGIN; UPDATE orders SET status = 'OPEN' WHERE order_id = 3; "
                            "INSERT INTO orders VALUES (6, 'ann', 'OPEN', 500); COMMIT;"),
    Until(("query", "--sql", "SELECT open_orders FROM open_by_customer WHERE customer = 'ann'"), r"^3\s*$"),
    Snap("03-by-customer"),
    Cli("t08-by-customer-transaction", ("query", "--sql", "SELECT * FROM open_by_customer")),
    # A checkpoint confirms the slot: the restart resumes from it.
    Until(("debug", "checkpoints", "--name", "open_orders"), r"^\s*\d+\s*$"),
    Restart(during=psql("t08-while-down", "UPDATE orders SET status = 'SHIPPED' WHERE order_id = 5")),
    rows("open_orders", 3),
    Cli("t08-after-restart", ("query", "--sql", "SELECT * FROM open_orders")),
    Cli("t08-describe", ("describe", "open_orders"), omit=DESCRIBE_OMIT),
    Open("/queries/open_orders"),
    Snap("04-query"),
)
