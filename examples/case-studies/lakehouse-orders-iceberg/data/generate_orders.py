"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit a web shop's order lines as CSV, one row per line, for the filesystem source.

    python3 data/generate_orders.py > data/order_lines.csv
    python3 data/generate_orders.py --phase late >> data/order_lines.csv

Two hours of a morning, 10:00 to 12:00 UTC: an order line every five minutes, 24 in all, from three
regions in turn (EU, UK, US) across four categories in turn (books, garden, toys, games), from seven
customers. Amounts are minor units: 1000 + (i * 371) % 9000.

The last line of the first phase, at 12:10, is a line in the next hour whose time, less a minute of
out-of-orderness, is past the end of both hours: it closes them, and their totals are written.

--phase late prints what the warehouse system uploads afterwards: a UK line for 11:50 that was held
in a retry queue, late for an hour already written but inside the stream's fifteen minutes of
allowed lateness, so the 11:00-12:00 UK row is corrected -- in the view, and in the Iceberg table,
where upsert mode replaces the row rather than adding a second. Then a line at 12:20 moves event
time on, which is what publishes the correction. The output is the same on every run.
"""
import sys
from datetime import datetime, timedelta, timezone

START = datetime(2026, 4, 20, 10, 0, 0, tzinfo=timezone.utc)
HEADER = "order_id,customer_id,region,category,amount_minor,ordered_at"
REGIONS = ["EU", "UK", "US"]
CATEGORIES = ["books", "garden", "toys", "games"]


def stamp(at: datetime) -> str:
    return at.strftime("%Y-%m-%dT%H:%M:%SZ")


def morning() -> None:
    print(HEADER)
    for i in range(24):
        at = START + timedelta(minutes=5 * i)
        print(f"o-{i:03d},c-{i % 7},{REGIONS[i % 3]},{CATEGORIES[i % 4]},{1000 + (i * 371) % 9000},{stamp(at)}")
    print(f"o-100,c-2,EU,books,1500,{stamp(START + timedelta(hours=2, minutes=10))}")


def late() -> None:
    print(f"o-late,c-5,UK,toys,4200,{stamp(START + timedelta(hours=1, minutes=50))}")
    print(f"o-101,c-3,US,garden,2500,{stamp(START + timedelta(hours=2, minutes=20))}")


if __name__ == "__main__":
    late() if "--phase" in sys.argv and sys.argv[-1] == "late" else morning()
