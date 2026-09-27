"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The CDR fraud study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:9090            # register, wait, read
    python3 python/run.py --url grpc://localhost:9090 --watch    # then follow top_callers

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
    ("caller_velocity", "01-continuous-caller-velocity.sql", [0, 1], None),
    ("premium_calls", "02-continuous-premium-calls.sql", [0], None),
    ("top_callers", "03-continuous-top-callers.sql", [0, 1], None),
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
    parser.add_argument("--url", default="grpc://localhost:9090")
    parser.add_argument("--caller", default="447700900666")
    parser.add_argument("--watch", action="store_true", help="follow top_callers after reading")
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

        # Windows answer when they close in event time; wait for the last one the data opens.
        deadline = time.time() + 30
        while time.time() < deadline and len(client.query("SELECT caller FROM top_callers").to_list()) < 3:
            time.sleep(1)

        show(client, f"caller_velocity for {args.caller}", read("04-read-one-caller.sql"), [args.caller])
        show(client, "SIM-box suspects: 8+ calls to 8+ numbers in five minutes",
             read("05-read-simbox-suspects.sql"), [8, 8])
        show(client, "premium_calls", "SELECT cdr_id, caller, callee_country, duration_s FROM premium_calls")
        show(client, "premium destinations by caller", read("06-read-premium-by-caller.sql"))
        show(client, "top_callers", "SELECT * FROM top_callers")

        if args.watch:
            print("following top_callers; Ctrl-C to stop")
            for batch in client.subscribe("top_callers", snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit")
                for row in batch:
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
