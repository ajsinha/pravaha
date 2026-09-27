"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The checkout-funnel study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:9090            # register, wait, read
    python3 python/run.py --url grpc://localhost:9090 --watch    # then follow payment_failure_spikes

Run from the study's directory against a node started with conf/application.yaml. The client holds
no schemas and no engine: it sends SQL and reads answers.
"""
import argparse
import pathlib
import time

from pravaha import connect
from pravaha.client import QueryError

SQL = pathlib.Path(__file__).resolve().parent.parent / "sql"

# name, SQL file, key columns (ordinals in the SELECT list), sink
QUERIES = [
    ("checkout_funnel", "01-continuous-checkout-funnel.sql", [0, 1, 2], None),
    ("payment_failure_spikes", "02-continuous-payment-failure-spikes.sql", [0, 1], None),
]
FUNNEL = ["CART", "CHECKOUT", "PAYMENT", "ORDER", "PAYMENT_FAILED"]


def read(name: str) -> str:
    return (SQL / name).read_text().strip()


def plain(row) -> dict:
    """A row with its timestamps as ISO strings, which is easier to read than datetime reprs."""
    return {k: (v.isoformat() if hasattr(v, "isoformat") else v) for k, v in row.to_dict().items()}


def show(client, title: str, sql: str, parameters=None) -> list:
    print(f"-- {title}")
    rows = [plain(row) for row in client.query(sql, parameters)]
    for row in rows:
        print("  ", row)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="grpc://localhost:9090")
    parser.add_argument("--watch", action="store_true", help="follow payment_failure_spikes after reading")
    args = parser.parse_args()

    with connect(args.url) as client:
        for name, sql_file, keys, sink in QUERIES:
            try:
                registered = client.register(name, read(sql_file), keys, sink=sink)
                print("registered", registered.name, registered.state, registered.fingerprint)
            except QueryError as e:
                if e.engine_code != "PRV-8001":        # already registered: run it again freely
                    raise
                print("already registered", name)

        # Three five-minute windows close once the last event has been read.
        deadline = time.time() + 30
        while time.time() < deadline and len(client.query(
                "SELECT window_end FROM checkout_funnel WHERE step = 'ORDER'").to_list()) < 6:
            time.sleep(1)

        totals = show(client, "funnel totals", read("03-read-funnel-totals.sql"))
        # No CASE in a continuous query, and no need: the conversion rate is a division the reader
        # does over two rows of the same answer.
        by_step = {row["step"]: row["sessions"] for row in totals}
        print("-- conversion")
        for step in FUNNEL[1:]:
            print(f"   {step:<15} {by_step.get(step, 0):>3} of {by_step['CART']} baskets "
                  f"= {100 * by_step.get(step, 0) / by_step['CART']:.0f}%")
        show(client, "ORDER, per window and device", read("04-read-one-step.sql"), ["ORDER"])
        show(client, "app funnel", read("05-read-device-funnel.sql"), ["app"])
        show(client, "payment_failure_spikes", "SELECT * FROM payment_failure_spikes")

        if args.watch:
            print("following payment_failure_spikes; Ctrl-C to stop")
            for batch in client.subscribe("payment_failure_spikes", snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit")
                for row in batch:
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
