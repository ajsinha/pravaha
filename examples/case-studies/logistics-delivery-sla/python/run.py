"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The delivery-SLA study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:19090            # register, wait, read
    python3 python/run.py --url grpc://localhost:19090 --watch    # then follow undelivered

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
    ("late_deliveries", "01-continuous-late-deliveries.sql", [0], "sla_breaches"),
    ("undelivered", "02-continuous-undelivered.sql", [0], None),
    ("depot_dispatches", "03-continuous-depot-dispatches.sql", [0, 1], None),
]


def read(name: str) -> str:
    return (SQL / name).read_text().strip()


def plain(row) -> dict:
    """A row with its timestamps as ISO strings, which is easier to read than datetime reprs."""
    return {k: (v.isoformat() if hasattr(v, "isoformat") else v) for k, v in row.to_dict().items()}


def show(client, title: str, sql: str, parameters=None) -> None:
    print(f"-- {title}")
    for row in client.query(sql, parameters):
        print("  ", plain(row))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="grpc://localhost:19090")
    parser.add_argument("--shipment", default="P1009")
    parser.add_argument("--watch", action="store_true", help="follow undelivered after reading")
    args = parser.parse_args()

    with connect(args.url) as client:
        for name, sql_file, keys, sink in QUERIES:
            try:
                registered = client.register(name, read(sql_file), keys, sink=sink)
                print("registered", registered.name, registered.state, registered.fingerprint, registered.sink)
            except QueryError as e:
                if e.engine_code != "PRV-8001":        # already registered: run it again freely
                    raise
                print("already registered", name)

        # A parcel is "undelivered" only once both streams are four hours past its dispatch.
        deadline = time.time() + 30
        while time.time() < deadline and len(client.query("SELECT shipment_id FROM undelivered").to_list()) < 3:
            time.sleep(1)

        show(client, "late_deliveries", "SELECT * FROM late_deliveries")
        show(client, "late deliveries by depot", read("04-read-breaches-by-depot.sql"))
        show(client, f"shipment {args.shipment}", read("05-read-one-shipment.sql"), [args.shipment])
        show(client, "undelivered after four hours", "SELECT * FROM undelivered")
        show(client, "dispatched per depot", read("06-read-depot-dispatches.sql"))

        if args.watch:
            print("following undelivered; Ctrl-C to stop")
            for batch in client.subscribe("undelivered", snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit")
                for row in batch:
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
