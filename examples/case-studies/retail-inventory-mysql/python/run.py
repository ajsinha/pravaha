"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The retail inventory study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:19090            # register, then read
    python3 python/run.py --url grpc://localhost:19090 --watch    # then follow low_stock

Run from the study's directory against a node started with conf/application.yaml. The client holds
no schemas and no engine, and knows nothing about MySQL: it sends SQL and reads answers.
"""
import argparse
import pathlib

from pravaha import connect
from pravaha.client import QueryError

SQL = pathlib.Path(__file__).resolve().parent.parent / "sql"

# name, SQL file, key columns (ordinals in the SELECT list): the table's own primary key
QUERIES = [
    ("stock_levels", "01-continuous-stock-levels.sql", [0, 1]),
    ("low_stock", "02-continuous-low-stock.sql", [0, 1]),
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
    parser.add_argument("--sku", default="sku-100")
    parser.add_argument("--warehouse", default="LDN")
    parser.add_argument("--watch", action="store_true", help="follow low_stock after reading")
    args = parser.parse_args()

    with connect(args.url) as client:
        for name, sql_file, keys in QUERIES:
            try:
                registered = client.register(name, read(sql_file), keys)
                print("registered", registered.name, registered.state, registered.fingerprint)
            except QueryError as e:
                if e.engine_code != "PRV-8001":        # already registered: run it again freely
                    raise
                print("already registered", name)

        show(client, f"{args.sku} in every warehouse", read("03-read-one-sku.sql"), [args.sku])
        show(client, "units per warehouse", read("04-read-warehouse-totals.sql"))
        show(client, f"low stock in {args.warehouse}", read("05-read-low-stock-in-warehouse.sql"), [args.warehouse])

        if args.watch:
            # An alert is a row entering low_stock (+1); a delivery, or a discontinued line, is the
            # row leaving it (-1). An update that stays under the reorder point is -1 then +1.
            print("following low_stock; Ctrl-C to stop")
            for batch in client.subscribe("low_stock", snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit")
                for row in batch:
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
