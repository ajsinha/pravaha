"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The lakehouse orders study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:19090            # register, wait, read
    python3 python/run.py --url grpc://localhost:19090 --watch    # then follow hourly_revenue

Run from the study's directory against a node started with conf/application.yaml. The client holds
no schemas and no engine, and never touches the Iceberg table: the node writes it, and the
analysts' own tools read it.
"""
import argparse
import pathlib
import time

from pravaha import connect
from pravaha.client import QueryError

SQL = pathlib.Path(__file__).resolve().parent.parent / "sql"

# name, SQL file, key columns (ordinals in the SELECT list), sink
QUERIES = [
    # The key is the Iceberg table's too: the sink's key.columns are window_end and region.
    ("hourly_revenue", "01-continuous-hourly-revenue.sql", [0, 1], "hourly_revenue_table"),
    ("category_revenue", "02-continuous-category-revenue.sql", [0, 1], None),
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
    parser.add_argument("--region", default="UK")
    parser.add_argument("--watch", action="store_true", help="follow hourly_revenue after reading")
    args = parser.parse_args()

    with connect(args.url) as client:
        for name, sql_file, keys, sink in QUERIES:
            try:
                registered = client.register(name, read(sql_file), keys, sink=sink)
                print("registered", registered.name, registered.state, registered.fingerprint, sink)
            except QueryError as e:
                if e.engine_code != "PRV-8001":        # already registered: run it again freely
                    raise
                print("already registered", name)

        # Hours answer when they close in event time; wait for the two the morning opens.
        deadline = time.time() + 30
        while time.time() < deadline and len(client.query("SELECT region FROM hourly_revenue").to_list()) < 6:
            time.sleep(1)

        show(client, f"hourly_revenue for {args.region}", read("04-read-one-region.sql"), [args.region])
        show(client, "the morning, per region", read("03-read-region-totals.sql"))
        show(client, "the morning, per category", read("05-read-category-mix.sql"))

        if args.watch:
            # A late order inside the lateness is one commit: -1 of the hour's old row, +1 of the
            # new. The Iceberg table receives the same commit and replaces the row by its key.
            print("following hourly_revenue; Ctrl-C to stop")
            for batch in client.subscribe("hourly_revenue", snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit")
                for row in batch:
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
