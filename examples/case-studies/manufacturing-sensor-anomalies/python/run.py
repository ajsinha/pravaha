"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The sensor-anomaly study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:9090            # register, wait, read
    python3 python/run.py --url grpc://localhost:9090 --watch    # then follow machine_health

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
    ("machine_health", "01-continuous-machine-health.sql", [0, 1], None),
    ("overheat_alerts", "02-continuous-overheat-alerts.sql", [0], "overheat_alerts"),
]


def read(name: str) -> str:
    return (SQL / name).read_text().strip()


def plain(row) -> dict:
    """A row with its timestamps as ISO strings, which is easier to read than datetime reprs."""
    return {k: (v.isoformat() if hasattr(v, "isoformat") else v) for k, v in row.to_dict().items()}


def show(client, title: str, sql_file: str, parameters=None) -> None:
    print(f"-- {title}")
    for row in client.query(read(sql_file), parameters):
        print("  ", plain(row))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="grpc://localhost:9090")
    parser.add_argument("--machine", default="press-02")
    parser.add_argument("--watch", action="store_true", help="follow the machine in machine_health after reading")
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

        # A window's row appears when the minute closes in event time, not as readings arrive.
        deadline = time.time() + 30
        while time.time() < deadline and not client.query("SELECT window_end FROM machine_health").to_list():
            time.sleep(1)

        show(client, f"machine_health for {args.machine}", "03-read-one-machine.sql", [args.machine])
        show(client, "minutes over 95.0 C or 150 um", "04-read-anomalous-minutes.sql", [950, 150])
        show(client, "by line", "05-read-line-summary.sql")
        print("-- overheat_alerts")
        for row in client.query("SELECT * FROM overheat_alerts"):
            print("  ", plain(row))

        if args.watch:
            # snapshot=True: the view as it stands, then every commit after it -- a correction
            # arrives as a -1 of the old row and a +1 of the new one.
            print(f"following machine_health for {args.machine}; Ctrl-C to stop")
            for batch in client.subscribe("machine_health", {"machine_id": args.machine}, snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit")
                for row in batch:
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
