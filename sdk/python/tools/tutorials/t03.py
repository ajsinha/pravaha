"""Tutorial 3 -- Joins and lookups (docs/tutorials/03-joins-and-lookups.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Orders and payments joined within five minutes, the orders nobody paid (a LEFT join with a time bound,
held back by the slower stream), and a lookup against a customer table in an H2 file through the
jdbc-lookup plugin: the H2 jar in plugins/, the table loaded with H2's RunScript before the node
starts, and changed with H2's Shell while it runs.
"""

from urllib.parse import quote

from tutorial_captures import Cli, Node, Open, Sh, Snap, Until

DOC = "03-joins-and-lookups.md"

H2 = "com.h2database:h2:2.3.232"
JAR = "plugins/h2-2.3.232.jar"
URL = "jdbc:h2:./data/ref/customers;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
SHOWN_URL = "jdbc:h2:$PRAVAHA_HOME/data/ref/customers;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"

NODE = Node(jars=(H2,), files=(
    ("t03/streams", "conf/streams"),
    ("t03/lookups", "conf/lookups"),
    ("t03/connectors", "conf/connectors"),
    ("t03/orders.csv", "data/incoming/orders.csv"),
    ("t03/payments.csv", "data/incoming/payments.csv"),
    ("t03/customers.sql", "customers.sql"),
    ("t03/paid_orders.sql", "paid_orders.sql"),
    ("t03/unpaid_orders.sql", "unpaid_orders.sql"),
    ("t03/orders_enriched.sql", "orders_enriched.sql"),
))

BEFORE_START = (
    Sh("t03-load-table", f'java -cp {JAR} org.h2.tools.RunScript -url "{URL}" -user sa -script customers.sql '
                         f'-showResults',
       show=f'java -cp "$PRAVAHA_HOME/{JAR}" org.h2.tools.RunScript -url "{SHOWN_URL}" -user sa '
            f'-script customers.sql -showResults'),
)


def orders(capture: str, line: str) -> Sh:
    return Sh(capture, f"echo '{line}' >> data/incoming/orders.csv",
              show=f"echo '{line}' >> \"$PRAVAHA_HOME/data/incoming/orders.csv\"")


def payments(capture: str, line: str) -> Sh:
    return Sh(capture, f"echo '{line}' >> data/incoming/payments.csv",
              show=f"echo '{line}' >> \"$PRAVAHA_HOME/data/incoming/payments.csv\"")


#: paid_orders.sql on one line, for the Workbench's address.
PAID = ("SELECT o.order_id, o.customer_id, o.amount, p.payment_id, p.event_time AS paid_at FROM orders o "
        "JOIN payments p ON p.order_id = o.order_id "
        "AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE")
INNER_LOOKUP_FILTER = ("SELECT o.order_id, c.tier FROM orders o JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c "
                       "ON c.customer_id = o.customer_id WHERE c.tier = 'gold'")
GOLD = ("SELECT o.order_id, c.name, o.amount FROM orders o LEFT JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c "
        "ON c.customer_id = o.customer_id WHERE c.tier = 'gold'")
UNBOUNDED_LEFT = ("SELECT o.order_id, p.payment_id FROM orders o LEFT JOIN payments p ON p.order_id = o.order_id")
RIGHT = ("SELECT o.order_id, p.payment_id FROM orders o RIGHT JOIN payments p ON p.order_id = o.order_id "
         "AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE")
BY_CUSTOMER = ("SELECT o.customer_id, o.order_id, o.amount FROM orders o JOIN payments p ON p.order_id = o.order_id "
               "AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE")
EXPLAIN = ("(() => { [...document.querySelectorAll('button')].find(b => b.textContent.trim().endsWith('Explain'))"
           ".click(); return true; })()")

STEPS = (
    Cli("t03-lookup-plugin", ("plugins",), omit=(r"^(?!NAME|filesystem|jdbc-lookup)",)),
    Cli("t03-streams", ("streams", "list")),
    Cli("t03-connectors", ("connectors", "list")),
    Cli("t03-register-paid", ("register", "--name", "paid_orders", "--sql-file", "paid_orders.sql", "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM paid_orders"), r"^3\s*$"),
    Cli("t03-query-paid", ("query", "--sql", "SELECT * FROM paid_orders")),
    Open("/workbench?panel=explain&sql=" + quote(PAID), "document.querySelector('.validity.ok')"),
    Snap("01-join-plan", js=EXPLAIN, ready="document.querySelectorAll('svg g.plan-node').length >= 3"),
    Cli("t03-register-unpaid", ("register", "--name", "unpaid_orders", "--sql-file", "unpaid_orders.sql", "--keys", "0")),
    Cli("t03-query-unpaid-empty", ("query", "--sql", "SELECT * FROM unpaid_orders")),
    Cli("t03-left-unbounded", ("validate", "--sql", UNBOUNDED_LEFT)),
    Cli("t03-right-refused", ("validate", "--sql", RIGHT)),
    orders("t03-order-late", "o-7,c-3,150,2026-10-01T09:08:00Z"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM unpaid_orders"), r"^2\s*$"),
    Cli("t03-query-unpaid-two", ("query", "--sql", "SELECT * FROM unpaid_orders")),
    payments("t03-payment-moves", "p-5,o-7,150,2026-10-01T09:08:30Z"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM unpaid_orders"), r"^3\s*$"),
    Cli("t03-query-unpaid-three", ("query", "--sql", "SELECT * FROM unpaid_orders")),
    Open("/views/unpaid_orders/live", "document.getElementById('live-state').dataset.state === 'fresh'"),
    Snap("02-unpaid"),
    Cli("t03-register-enriched", ("register", "--name", "orders_enriched", "--sql-file", "orders_enriched.sql",
                                  "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM orders_enriched"), r"^7\s*$"),
    Cli("t03-query-enriched", ("query", "--sql", "SELECT * FROM orders_enriched")),
    Cli("t03-inner-filter-refused", ("validate", "--sql", INNER_LOOKUP_FILTER)),
    Cli("t03-register-gold", ("register", "--name", "gold_orders", "--sql", GOLD, "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM gold_orders"), r"^2\s*$"),
    Cli("t03-query-gold", ("query", "--sql", "SELECT * FROM gold_orders")),
    Cli("t03-register-by-customer", ("register", "--name", "paid_by_customer", "--sql", BY_CUSTOMER, "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM paid_by_customer"), r"^\d+\s*$"),
    Cli("t03-query-by-customer", ("query", "--sql", "SELECT * FROM paid_by_customer")),
    Sh("t03-update-table", f'java -cp {JAR} org.h2.tools.Shell -url "{URL}" -user sa '
                           f'-sql "UPDATE customers SET tier = \'gold\' WHERE customer_id = \'c-2\'"',
       show=f'java -cp "$PRAVAHA_HOME/{JAR}" org.h2.tools.Shell -url "{SHOWN_URL}" -user sa '
            f'-sql "UPDATE customers SET tier = \'gold\' WHERE customer_id = \'c-2\'"'),
    orders("t03-order-after-update", "o-8,c-2,700,2026-10-01T09:08:40Z"),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM orders_enriched"), r"^8\s*$"),
    Cli("t03-query-after-update", ("query", "--sql", "SELECT * FROM orders_enriched WHERE customer_id = 'c-2'")),
    Open("/views/orders_enriched?key=customer_id&value=c-2"),
    Snap("03-enriched"),
    Open("/catalog"),
    Snap("04-catalog"),
)
