#!/usr/bin/env python3
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
"""Register the fulfilment tutorial's two continuous queries, read them, and follow the alerts.

    PRAVAHA_TOKEN=<your qa token> python fulfilment.py register
    PRAVAHA_TOKEN=... python fulfilment.py read
    PRAVAHA_TOKEN=... python fulfilment.py follow        # prints each alert as it happens; Ctrl-C to stop
    PRAVAHA_TOKEN=... python fulfilment.py drop

PRAVAHA_URL and PRAVAHA_HTTP_URL default to a QA host on this machine.
"""
import os
import sys

from pravaha import ClientOptions, connect

# An order is fulfilled when it is paid within 5 minutes of being placed and shipped within 2.
FULFILLED = """
SELECT o.order_id, o.customer_id, o.region, o.amount, p.pay_method, s.carrier
FROM orders o
JOIN payments p
  ON p.order_id = o.order_id
 AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '2' MINUTE
"""

# Paid in time, and not shipped within 2 minutes of the order: the queue the warehouse must clear.
AWAITING_DISPATCH = """
SELECT o.order_id, o.customer_id, o.region, o.amount, p.pay_method
FROM orders o
JOIN payments p
  ON p.order_id = o.order_id
 AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE
LEFT JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '2' MINUTE
WHERE s.shipment_id IS NULL
"""


def client():
    return connect(options=ClientOptions.create(
        os.environ.get("PRAVAHA_URL", "grpc://localhost:9090"),
        token=os.environ["PRAVAHA_TOKEN"],
        http_url=os.environ.get("PRAVAHA_HTTP_URL", "http://localhost:8080"),
        allow_insecure_token=True,          # a QA host serves plaintext
    ))


def register(c):
    for name, sql in (("fulfilled_orders", FULFILLED), ("awaiting_dispatch", AWAITING_DISPATCH)):
        check = c.validate(sql)
        if not check["valid"]:
            sys.exit(f"{name}: {check['diagnostics'][0]['message']}")
        q = c.register(name, sql, key_columns=[0])
        print(f"registered {q.name}: {q.state}, fingerprint {q.fingerprint}")


def read(c):
    for name in ("fulfilled_orders", "awaiting_dispatch"):
        rows = sorted(c.query(f"SELECT * FROM {name}").to_list(), key=lambda r: r["order_id"])
        print(f"{name}: {len(rows)} row(s)")
        for row in rows:
            print("   ", row)


def follow(c):
    print("following awaiting_dispatch; Ctrl-C to stop")
    try:
        for batch in c.subscribe("awaiting_dispatch", snapshot=True):
            for row in batch:
                verb = "now waiting" if row.weight > 0 else "cleared"
                print(("snapshot " if batch.snapshot else "") + f"{verb}: {row.to_dict()}")
    except KeyboardInterrupt:
        print("\nstopped")


def drop(c):
    for name in ("fulfilled_orders", "awaiting_dispatch"):
        c.drop(name)
        print("dropped", name)


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "read"
    {"register": register, "read": read, "follow": follow, "drop": drop}[action](client())
