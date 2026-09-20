"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Insert settlement payments into PostgreSQL, continuously.

    python3 generate_payments.py --rate 5 --minutes 10

Unlike the Aerospike generators this writes directly rather than emitting statements,
because psql has no equivalent of piping a stream of INSERTs at a rate. Needs psycopg:

    pip install 'psycopg[binary]'

Value times advance from a fixed start, and the run finishes past the final hour
boundary so the last window closes instead of waiting for data that will not arrive.
"""
import argparse
import datetime as dt
import random

try:
    import psycopg
except ImportError:  # pragma: no cover - a missing driver should say what to install
    raise SystemExit("this generator needs psycopg: pip install 'psycopg[binary]'")

START = dt.datetime(2026, 1, 5, 9, 0, tzinfo=dt.timezone.utc)
COUNTERPARTIES = ["cp-acme", "cp-borealis", "cp-cygnus"]
CURRENCIES = ["GBP", "USD", "EUR"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dsn", default="postgresql://pravaha:pravaha@localhost:5432/pravaha")
    parser.add_argument("--rate", type=float, default=5.0, help="payments per simulated second")
    parser.add_argument("--minutes", type=int, default=10)
    parser.add_argument("--seed", type=int, default=5)
    args = parser.parse_args()

    random.seed(args.seed)
    rows = []
    for tick in range(int(args.minutes * 60 * args.rate)):
        value_time = START + dt.timedelta(seconds=tick / args.rate)
        rows.append((
            random.choice(COUNTERPARTIES),
            random.choice(CURRENCIES),
            random.randint(50_000, 9_000_000),
            "RECEIVE" if random.random() < 0.25 else "PAY",
            value_time,
        ))

    # One payment in the next hour, so the hour under test is told it is complete.
    rows.append((COUNTERPARTIES[0], "GBP", 100_000, "PAY", START + dt.timedelta(hours=1, minutes=5)))

    with psycopg.connect(args.dsn) as connection:
        with connection.cursor() as cursor:
            cursor.executemany(
                "INSERT INTO settlement (counterparty_id, currency, amount_minor, direction, value_time) "
                "VALUES (%s, %s, %s, %s, %s)",
                rows,
            )
        connection.commit()
    print(f"inserted {len(rows)} payments from {START:%Y-%m-%d %H:%M} onwards")


if __name__ == "__main__":
    main()
