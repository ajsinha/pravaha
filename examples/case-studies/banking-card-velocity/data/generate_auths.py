"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit card authorisations as aql INSERT statements.

Pipe straight into the Aerospike shell:

    python3 generate_auths.py --cards c-1001,c-1002,c-1003 --seconds 120 \
      | docker exec -i pravaha-aerospike aql

One card is made to misbehave -- bursts at many merchants -- so the case study has
something to find. Every timestamp is event time in epoch nanoseconds, and the run
deliberately ends past the final window boundary so the last window closes rather
than sitting open waiting for data that never comes.
"""
import argparse
import random

NANOS = 1_000_000_000
# A fixed start so two runs of this script produce comparable output. Streaming results
# should not depend on when you happened to run the generator.
START_NANOS = 1767225600 * NANOS  # 2026-01-01T00:00:00Z


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cards", default="c-1001,c-1002,c-1003")
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--rate", type=float, default=2.0, help="authorisations per second")
    parser.add_argument("--hot-card", default=None, help="card that bursts; defaults to the second one")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    random.seed(args.seed)
    cards = [c.strip() for c in args.cards.split(",") if c.strip()]
    hot = args.hot_card or (cards[1] if len(cards) > 1 else cards[0])

    auth_id = 0
    for tick in range(int(args.seconds * args.rate)):
        offset = int(tick / args.rate * NANOS)
        card = hot if random.random() < 0.45 else random.choice(cards)
        # The hot card spreads across many merchants; the others revisit a few.
        merchant = f"m-{random.randint(90, 99)}" if card == hot else f"m-{random.randint(10, 12)}"
        amount = random.randint(1000, 40000) if card == hot else random.randint(200, 6000)
        mcc = 7995 if card == hot else random.choice([5411, 5812])
        status = "DECLINED" if random.random() < 0.08 else "APPROVED"
        auth_id += 1
        print(
            "INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time) "
            f"VALUES ('a-{auth_id}', 'a-{auth_id}', '{card}', '{merchant}', {amount}, {mcc}, "
            f"'{status}', {START_NANOS + offset});"
        )

    # One row past the end, so the final window is told nothing earlier is coming and publishes.
    # Without it the last window stays open and the case study looks broken.
    closing = START_NANOS + int(args.seconds * NANOS) + 60 * NANOS
    print(
        "INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time) "
        f"VALUES ('a-close', 'a-close', '{cards[0]}', 'm-01', 100, 5411, 'APPROVED', {closing});"
    )


if __name__ == "__main__":
    main()
