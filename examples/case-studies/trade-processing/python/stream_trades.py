#!/usr/bin/env python3
"""Query the trade feed with filters, and show the shape a streaming consumer uses.

    . ../../../sdk/python/.venv/bin/activate
    python3 stream_trades.py --product SWAP --source MUREX

Filters are bound, never concatenated into the SQL. A bound value cannot be read as
SQL -- by the time it reaches the server the statement is already planned -- and the
server plans the statement once however many product types you ask about.
"""
import argparse
import json
import pathlib

from pravaha import connect

SQL_DIR = pathlib.Path(__file__).resolve().parent.parent / "sql"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="grpc://localhost:9090")
    parser.add_argument("--product", default="SWAP", help="product_type to filter on")
    parser.add_argument("--source", default="MUREX", help="source_system to filter on")
    parser.add_argument("--trade-id", default=None, help="look up one trade and all its events")
    args = parser.parse_args()

    with connect(args.url) as client:
        if args.trade_id:
            sql = (SQL_DIR / "04-read-one-trade.sql").read_text()
            for row in client.query(sql, [args.trade_id]):
                # Every event for the trade, amendments included, because the view is keyed on
                # trade_event_id rather than trade_id.
                print(row["trade_event_id"], row["source_system"], row["trade_json"])
            return

        sql = (SQL_DIR / "02-read-by-product-and-source.sql").read_text()
        for row in client.query(sql, [args.product, args.source]):
            # trade_json is an opaque string to the engine; parsing it is the consumer's job, and
            # that is exactly why anything you filter on has to be promoted to its own column.
            body = json.loads(row["trade_json"])
            print(row["trade_id"], row["product_type"], body)

        print("---")
        summary = (SQL_DIR / "03-read-counts-by-product.sql").read_text()
        for row in client.query(summary):
            print(row["product_type"], row["source_system"], row["trades"])


if __name__ == "__main__":
    main()
