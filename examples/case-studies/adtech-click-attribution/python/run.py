"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""The click-attribution study, end to end, through the published SDK.

    python3 python/run.py --url grpc://localhost:9090            # register, wait, read
    python3 python/run.py --url grpc://localhost:9090 --watch    # then follow attributed_clicks

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
    ("attributed_clicks", "01-continuous-attributed-clicks.sql", [0], None),
    ("campaign_clicks", "02-continuous-campaign-clicks.sql", [0, 1], None),
    ("campaign_impressions", "03-continuous-campaign-impressions.sql", [0, 1], None),
]


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
    parser.add_argument("--campaign", default="travel")
    parser.add_argument("--watch", action="store_true", help="follow attributed_clicks after reading")
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

        # The windows over the join close when BOTH streams' event time has passed them.
        deadline = time.time() + 30
        while time.time() < deadline and len(client.query("SELECT clicks FROM campaign_clicks").to_list()) < 7:
            time.sleep(1)

        show(client, f"attributed clicks for {args.campaign}", read("05-read-campaign-clicks.sql"), [args.campaign])
        show(client, "clicks by placement", read("06-read-clicks-by-placement.sql"))
        # A read names one view (PRV-4025 otherwise), so the click-through rate -- clicks over
        # impressions, per campaign and window -- is joined here, on the two views' shared key.
        clicks = {(r["window_end"], r["campaign_id"]): r["clicks"]
                  for r in map(plain, client.query("SELECT window_end, campaign_id, clicks FROM campaign_clicks"))}
        print("-- click-through rate, per campaign and five minutes")
        for row in sorted(map(plain, client.query(read("04-read-campaign-impressions.sql"))),
                          key=lambda r: (r["window_end"], r["campaign_id"])):
            clicked = clicks.get((row["window_end"], row["campaign_id"]), 0)
            cost_per_click = f"{row['spend_micros'] / clicked / 1e6:.4f}" if clicked else "-"
            print(f"   {row['window_end'][11:16]} {row['campaign_id']:<12} {clicked:>2} clicks / "
                  f"{row['impressions']:>2} impressions = {100 * clicked / row['impressions']:4.1f}%  "
                  f"cost per click {cost_per_click}")

        if args.watch:
            print("following attributed_clicks; Ctrl-C to stop")
            for batch in client.subscribe("attributed_clicks", snapshot=True):
                print("-- snapshot" if batch.snapshot else "-- commit", f"({len(batch)} rows)")
                for row in ([] if batch.snapshot else batch):
                    print(f"   {row.weight:+d}", plain(row))


if __name__ == "__main__":
    main()
