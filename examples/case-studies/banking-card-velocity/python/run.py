"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""This case study, end to end, through the published SDK.

    . ../../../sdk/python/.venv/bin/activate
    python3 run.py --url grpc://localhost:9090

Everything here goes over the wire to a running server. The shape worth copying is
that **the client holds no schemas and no engine**: it sends SQL and reads answers.
The stream definitions live on the server, where the data is.
"""
import argparse
import pathlib

from pravaha import connect

SQL = pathlib.Path(__file__).resolve().parent.parent / "sql"


def read(name: str) -> str:
    return (SQL / name).read_text().strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="grpc://localhost:9090")
    parser.add_argument("--key", default="c-1002", help="the card_id to look up")
    parser.add_argument("--watch", action="store_true", help="stream changes instead of exiting")
    args = parser.parse_args()

    with connect(args.url) as client:
        # 1. Register. It runs until dropped, maintaining the "card_velocity" view. The same
        #    question registered twice is one computation with two names -- the fingerprint says so.
        registered = client.register("card_velocity", read("01-continuous-card-velocity.sql"), [1])
        print("registered", registered)

        # 2. Ask it something. Values are bound, never interpolated into the SQL.
        for row in client.query(read("02-read-one-card.sql"), [args.key]):
            print({name: row[name] for name in row.columns})

        if not args.watch:
            return

        # 3. Watch it. The filter is applied at the tap, so rows this consumer did not ask for
        #    never cross the network -- and every other consumer reads the same computation.
        print("watching card_velocity; Ctrl-C to stop")
        for batch in client.subscribe("card_velocity", {"risk_band": "HIGH"}):
            # One batch is one commit, never a partial window.
            print(f"-- commit of {len(batch)} row(s)")
            for row in batch:
                print("  ", {name: row[name] for name in row.columns})


if __name__ == "__main__":
    main()
