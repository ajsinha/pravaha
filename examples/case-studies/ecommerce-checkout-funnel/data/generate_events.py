"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit checkout events as CSV, one row per step a shopper takes, for the filesystem source.

    python3 data/generate_events.py > data/checkout_events.csv

Sixty shopping sessions start ten seconds apart from 10:00:00 UTC on a Friday. Each puts something
in the basket (CART); most go on to CHECKOUT, most of those to PAYMENT, and a payment either ends
in an ORDER or a PAYMENT_FAILED. Steps are twenty seconds apart. One session in three is on the
mobile app, the rest on the web.

From 10:06 to 10:09 the payment provider's app integration fails: every app payment in those three
minutes is PAYMENT_FAILED. That is the incident the study's second query exists to catch.

The last row, at 10:15:20, is a new session's CART that moves event time past the end of every
five-minute window the sixty sessions touch. The output is the same on every run.
"""
from datetime import datetime, timedelta, timezone

START = datetime(2026, 11, 27, 10, 0, 0, tzinfo=timezone.utc)
HEADER = "event_id,session_id,step,device,basket_minor,event_time"
OUTAGE = (START + timedelta(minutes=6), START + timedelta(minutes=9))


def stamp(at: datetime) -> str:
    return at.strftime("%Y-%m-%dT%H:%M:%SZ")


def main() -> None:
    rows = []
    for i in range(60):
        session = f"s-{i:03d}"
        device = "app" if i % 3 == 0 else "web"
        basket = 1999 + (i * 737) % 9000               # minor units: 1999 is 19.99
        at = START + timedelta(seconds=10 * i)
        steps = [("CART", at)]
        if i % 4 != 3:                                  # a quarter abandon the basket
            steps.append(("CHECKOUT", at + timedelta(seconds=20)))
            if i % 5 != 4:                              # a fifth of those abandon at checkout
                paid = at + timedelta(seconds=40)
                steps.append(("PAYMENT", paid))
                failed = device == "app" and OUTAGE[0] <= paid < OUTAGE[1]
                steps.append(("PAYMENT_FAILED" if failed else "ORDER", paid + timedelta(seconds=20)))
        for step, when in steps:
            rows.append((when, session, step, device, basket))
    rows.sort(key=lambda r: (r[0], r[1]))

    print(HEADER)
    for n, (when, session, step, device, basket) in enumerate(rows, start=1):
        print(f"e-{n:04d},{session},{step},{device},{basket},{stamp(when)}")
    print(f"e-close,s-900,CART,web,2500,{stamp(START + timedelta(minutes=15, seconds=20))}")


if __name__ == "__main__":
    main()
