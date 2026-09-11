#!/usr/bin/env python3
"""Emit intraday trades as aql INSERT statements.

    python3 generate_trades.py --seconds 120 --rate 5 | docker exec -i pravaha-aerospike aql

Every trade carries its payload in trade_json and its filterable attributes as
columns, which is the design point of this case study: the engine can filter on a
column and cannot see inside the JSON.

Some trades are amended, producing a second event with the same trade_id and a new
trade_event_id -- which is why the view is keyed on the event and not the trade.
"""
import argparse
import json
import random

NANOS = 1_000_000_000
START_NANOS = 1767225600 * NANOS

PRODUCTS = ["SWAP", "EQUITY", "FX", "BOND"]
COUNTERPARTIES = ["cp-1", "cp-2", "cp-3"]
# One book per desk, and a fourth id with no matching book record -- so the LEFT join has
# something to leave null and you can see that such a trade still reaches the feed.
BOOKS = ["bk-1", "bk-2", "bk-3", "bk-unknown"]
SOURCES = ["MUREX", "CALYPSO", "INHOUSE"]
CURRENCIES = ["GBP", "USD", "EUR", "JPY"]
SYMBOLS = ["VOD.L", "AAPL", "BP.L", "MSFT"]


def payload(product: str) -> str:
    if product == "EQUITY":
        body = {"qty": random.randint(100, 5000), "sym": random.choice(SYMBOLS)}
    elif product == "FX":
        body = {"notional": random.randint(100_000, 20_000_000),
                "pair": random.choice(["GBPUSD", "EURUSD", "USDJPY"])}
    else:
        body = {"notional": random.randint(250_000, 50_000_000), "ccy": random.choice(CURRENCIES)}
    # Compact separators keep the aql statement short; aql is not fond of very long lines.
    return json.dumps(body, separators=(",", ":")).replace("'", "")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--rate", type=float, default=5.0, help="trades per second")
    parser.add_argument("--amend-ratio", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=13)
    args = parser.parse_args()

    random.seed(args.seed)
    event_id = 0
    trade_number = 0

    for tick in range(int(args.seconds * args.rate)):
        offset = int(tick / args.rate * NANOS)
        product = random.choice(PRODUCTS)
        source = random.choice(SOURCES)
        trade_number += 1
        trade_id = f"T-{1000 + trade_number}"
        counterparty = random.choice(COUNTERPARTIES)
        book = random.choice(BOOKS)
        event_id += 1
        print(
            "INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, "
            "trade_time, counterparty_id, book_id, trade_json) VALUES "
            f"({event_id}, {event_id}, '{trade_id}', '{product}', '{source}', {START_NANOS + offset}, "
            f"'{counterparty}', '{book}', '{payload(product)}');"
        )
        if random.random() < args.amend_ratio:
            # An amendment is a new event for the same trade. Keying the view on trade_id would
            # make this overwrite the original and lose the audit trail.
            event_id += 1
            print(
                "INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, "
                "trade_time, counterparty_id, book_id, trade_json) VALUES "
                f"({event_id}, {event_id}, '{trade_id}', '{product}', '{source}', "
                f"{START_NANOS + offset + 500_000_000}, '{counterparty}', '{book}', '{payload(product)}');"
            )


if __name__ == "__main__":
    main()
