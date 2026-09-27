"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit dispatch and delivery scans as two CSV files, for two filesystem sources.

    python3 data/generate_shipments.py        # writes data/dispatches.csv and data/deliveries.csv

Twenty-four express parcels leave three depots, one every five minutes from 06:00 UTC. Express
means delivered within two hours of dispatch. Most are; four arrive between two and four hours
(breaches); one arrives after four hours and ten minutes; two are never delivered at all.

Each file ends with a heartbeat scan at 12:30. A query over both streams moves in event time only as
fast as the slower of them, so both must pass four hours after the last dispatch before the engine
can say, of any parcel, that it was not delivered. The output is the same on every run.
"""
import pathlib
from datetime import datetime, timedelta, timezone

START = datetime(2026, 10, 5, 6, 0, 0, tzinfo=timezone.utc)
DEPOTS = ["LHR1", "MAN2", "BRS3"]
COURIERS = ["van-11", "van-12", "van-21", "van-31", "van-32"]
# Minutes from dispatch to delivery, per parcel; None is never delivered.
MINUTES = [75, 90, 250, 60, 95, 150, 80, 110, 70, 185, 100, 85,
           65, 140, 90, 115, 75, 170, 95, 80, None, 100, 88, None]
DATA = pathlib.Path(__file__).resolve().parent


def stamp(at: datetime) -> str:
    return at.strftime("%Y-%m-%dT%H:%M:%SZ")


def main() -> None:
    dispatches = ["shipment_id,depot,service,dispatch_time"]
    deliveries = []
    for i, minutes in enumerate(MINUTES):
        shipment, sent = f"P{1000 + i}", START + timedelta(minutes=5 * i)
        dispatches.append(f"{shipment},{DEPOTS[i % 3]},express,{stamp(sent)}")
        if minutes is not None:
            deliveries.append((sent + timedelta(minutes=minutes), shipment, COURIERS[i % 5]))
    deliveries.sort()
    rows = ["shipment_id,courier,delivered_time"]
    rows += [f"{shipment},{courier},{stamp(at)}" for at, shipment, courier in deliveries]

    heartbeat = stamp(START + timedelta(hours=6, minutes=30))
    dispatches.append(f"HB-DISPATCH,none,heartbeat,{heartbeat}")
    rows.append(f"HB-DELIVERY,none,{heartbeat}")

    (DATA / "dispatches.csv").write_text("\n".join(dispatches) + "\n")
    (DATA / "deliveries.csv").write_text("\n".join(rows) + "\n")
    print(f"wrote {len(dispatches) - 1} dispatches and {len(rows) - 1} deliveries to {DATA}")


if __name__ == "__main__":
    main()
