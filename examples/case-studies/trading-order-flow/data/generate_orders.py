"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit order lifecycle events as aql INSERT statements.

    python3 generate_orders.py --traders t-7,t-9 --seconds 180 --cancel-ratio 0.9 \
      | docker exec -i pravaha-aerospike aql

The first trader cancels at --cancel-ratio; the others behave normally, so the two
registered queries have something to distinguish. An order that is cancelled produces
two rows -- a NEW and a CANCEL -- because that is what a venue sends.
"""
import argparse
import random

NANOS = 1_000_000_000
START_NANOS = 1767225600 * NANOS


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--traders", default="t-7,t-9")
    parser.add_argument("--instruments", default="i-1,i-2,i-3")
    parser.add_argument("--seconds", type=int, default=180)
    parser.add_argument("--rate", type=float, default=4.0, help="new orders per second")
    parser.add_argument("--cancel-ratio", type=float, default=0.9)
    parser.add_argument("--seed", type=int, default=11)
    args = parser.parse_args()

    random.seed(args.seed)
    traders = [t.strip() for t in args.traders.split(",") if t.strip()]
    instruments = [i.strip() for i in args.instruments.split(",") if i.strip()]
    spoofer = traders[0]

    event_id = 0
    order_id = 0
    for tick in range(int(args.seconds * args.rate)):
        offset = int(tick / args.rate * NANOS)
        trader = spoofer if random.random() < 0.7 else random.choice(traders)
        instrument = random.choice(instruments)
        side = random.choice(["BUY", "SELL"])
        qty = random.choice([100, 250, 500, 1000])
        price = random.randint(7000, 7500) if instrument == "i-1" else random.randint(18000, 20000)
        order_id += 1
        event_id += 1
        print(
            "INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, "
            f"price_minor, event_type, event_time) VALUES ('e-{event_id}', {order_id}, '{trader}', "
            f"'{instrument}', '{side}', {qty}, {price}, 'NEW', {START_NANOS + offset});"
        )
        ratio = args.cancel_ratio if trader == spoofer else 0.1
        if random.random() < ratio:
            event_id += 1
            # Cancelled a moment later, which is what makes the pattern visible in a hopping window
            # and invisible in a daily total.
            print(
                "INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, "
                f"price_minor, event_type, event_time) VALUES ('e-{event_id}', {order_id}, '{trader}', "
                f"'{instrument}', '{side}', {qty}, {price}, 'CANCEL', "
                f"{START_NANOS + offset + random.randint(200, 900) * 1_000_000});"
            )

    closing = START_NANOS + int(args.seconds * NANOS) + 120 * NANOS
    print(
        "INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, price_minor, "
        f"event_type, event_time) VALUES ('e-close', 0, '{traders[-1]}', '{instruments[0]}', 'BUY', 1, "
        f"7000, 'NEW', {closing});"
    )


if __name__ == "__main__":
    main()
