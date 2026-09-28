"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The shared-Kafka payments study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:19090            # register, wait, read
    python3 python/run.py --url grpc://localhost:19090 --watch    # then follow declines

Run from the study's directory against a node started with conf/application.yaml. Three queries
over one topic: the node reads the topic once for all of them. The client holds no schemas and no
engine, and never talks to Kafka.
"""
import argparse
import pathlib
import time

from pravaha import connect
from pravaha.client import QueryError

SQL = pathlib.Path(__file__).resolve().parent.parent / "sql"

# name, SQL file, key columns (ordinals in the SELECT list). merchant_minute is a CREATE CONTINUOUS
# QUERY statement -- it names itself, its key and its index -- so it is sent as SQL, not registered.
STATEMENT = "01-continuous-merchant-minute.sql"
QUERIES = [
    ("declines", "02-continuous-declines.sql", [0]),
    ("cross_border", "03-continuous-cross-border.sql", [0]),
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


def already_registered(e: QueryError) -> bool:
    return e.engine_code == "PRV-8001"                 # run it again freely


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="grpc://localhost:19090")
    parser.add_argument("--merchant", default="m-coffee")
    parser.add_argument("--country", default="FR")
    parser.add_argument("--watch", action="store_true", help="follow declines after reading")
    args = parser.parse_args()

    with connect(args.url) as client:
        try:
            for row in client.query(read(STATEMENT)):
                print("created", plain(row))
        except QueryError as e:
            if not already_registered(e):
                raise
            print("already registered merchant_minute")
        for name, sql_file, keys in QUERIES:
            try:
                registered = client.register(name, read(sql_file), keys)
                print("registered", registered.name, registered.state, registered.fingerprint)
            except QueryError as e:
                if not already_registered(e):
                    raise
                print("already registered", name)

        # Minutes answer when they close in event time; wait for the three the data opens.
        deadline = time.time() + 30
        while time.time() < deadline and len(client.query("SELECT merchant FROM merchant_minute").to_list()) < 9:
            time.sleep(1)

        # merchant = ? on the indexed column: a probe of the index, not a walk of the view.
        show(client, f"merchant_minute for {args.merchant}", read("04-read-one-merchant.sql"), [args.merchant])
        show(client, "coffee and books", read("05-read-two-merchants.sql"))
        show(client, "declines by merchant", read("06-read-declines-by-merchant.sql"))
        show(client, f"cross-border from {args.country}", read("07-read-cross-border-by-country.sql"), [args.country])

        if args.watch:
            print("following declines; Ctrl-C to stop")
            for batch in client.subscribe("declines", snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit")
                for row in batch:
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
