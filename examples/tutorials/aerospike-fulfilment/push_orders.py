#!/usr/bin/env python3
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
"""Push orders and payments into Aerospike, and shipments into a CSV file, for the fulfilment tutorial.

    pip install aerospike
    python push_orders.py --seed                          # six orders with known outcomes, then exit
    python push_orders.py --live                          # keep going until Ctrl-C
    python push_orders.py --live --rate 2 --shipments /opt/pravaha/feeds/shipments.csv

Aerospike holds two sets in namespace `test`:

    orders    key order_id    bins order_id, customer_id, region, amount, event_time
    payments  key payment_id  bins payment_id, order_id, pay_method, amount, event_time

`event_time` is an integer of nanoseconds since the epoch: that is the form Pravaha's aerospike
source reads a TIMESTAMP bin in. A record's key is not a bin, so order_id and payment_id are stored
as bins as well -- a query can only read bins.

Shipments go to a CSV file that Pravaha follows:

    shipment_id,order_id,carrier,event_time          event_time as ISO-8601 UTC

In --live mode each order is paid a few seconds later nine times in ten, and a paid order is shipped
a little later four times in five: the rest are what the tutorial's second query is there to catch.
"""
import argparse
import datetime as dt
import os
import random
import sys
import time

try:
    import aerospike
except ImportError:
    sys.exit("this script needs the Aerospike client: pip install aerospike")

REGIONS = ["north", "south", "east", "west"]
METHODS = ["card", "wallet", "bank"]
CARRIERS = ["dhl", "ups", "fedex"]
HEADER = "shipment_id,order_id,carrier,event_time\n"


def nanos(when: dt.datetime) -> int:
    return int(when.timestamp() * 1_000_000_000)


def iso(when: dt.datetime) -> str:
    return when.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Pusher:
    def __init__(self, host: str, port: int, namespace: str, shipments: str):
        self.client = aerospike.client({"hosts": [(host, port)]}).connect()
        self.ns = namespace
        self.shipments = shipments
        if not os.path.exists(shipments) or os.path.getsize(shipments) == 0:
            with open(shipments, "w") as f:
                f.write(HEADER)

    def order(self, order_id: int, customer: str, region: str, amount: int, at: dt.datetime) -> None:
        self.client.put((self.ns, "orders", order_id),
                        {"order_id": order_id, "customer_id": customer, "region": region,
                         "amount": amount, "event_time": nanos(at)})
        print(f"order     {order_id}  {customer:<5} {region:<5} {amount:>7}  {iso(at)}")

    def payment(self, payment_id: int, order_id: int, method: str, amount: int, at: dt.datetime) -> None:
        self.client.put((self.ns, "payments", payment_id),
                        {"payment_id": payment_id, "order_id": order_id, "pay_method": method,
                         "amount": amount, "event_time": nanos(at)})
        print(f"payment   {payment_id}  for order {order_id}  {method:<6} {amount:>7}  {iso(at)}")

    def shipment(self, shipment_id: int, order_id: int, carrier: str, at: dt.datetime) -> None:
        with open(self.shipments, "a") as f:
            f.write(f"{shipment_id},{order_id},{carrier},{iso(at)}\n")
        print(f"shipment  {shipment_id}  for order {order_id}  {carrier:<6}          {iso(at)}")


def seed(p: Pusher) -> None:
    """Six orders whose outcomes the tutorial predicts, on 2026-09-26 from 10:00 UTC."""
    t0 = dt.datetime(2026, 9, 26, 10, 0, 0, tzinfo=dt.timezone.utc)
    m = lambda minutes, seconds=0: t0 + dt.timedelta(minutes=minutes, seconds=seconds)
    p.order(1001, "c1", "north", 2500, m(0))
    p.order(1002, "c2", "south", 12000, m(0, 30))
    p.order(1003, "c1", "north", 800, m(1))
    p.order(1004, "c3", "east", 4300, m(1, 30))
    p.order(1005, "c4", "west", 9900, m(2))
    p.order(1006, "c2", "south", 1500, m(2, 30))
    p.payment(5001, 1001, "card", 2500, m(0, 20))
    p.payment(5002, 1002, "wallet", 12000, m(1))
    p.payment(5003, 1003, "card", 800, m(1, 40))
    p.payment(5004, 1004, "bank", 4300, m(2, 10))
    p.payment(5005, 1005, "card", 9900, m(9))      # paid too late: outside the 5-minute window
    # 1006 is never paid.
    p.shipment(9001, 1001, "dhl", m(1))
    p.shipment(9002, 1002, "ups", m(2))
    p.shipment(9003, 1003, "fedex", m(3))
    # 1004 is paid and never shipped: the second query's alert, once its 2-minute dispatch window has passed.


def live(p: Pusher, rate: float) -> None:
    """Orders at `rate` a second, stamped with the current time, until interrupted."""
    order_id, payment_id, shipment_id = 20000, 60000, 90000
    pending = []                      # (due, kind, fields)
    print(f"pushing about {rate} orders a second; Ctrl-C to stop")
    try:
        while True:
            now = dt.datetime.now(dt.timezone.utc)
            order_id += 1
            amount = random.choice([500, 1200, 2500, 4800, 9900, 15000])
            p.order(order_id, f"c{random.randint(1, 40)}", random.choice(REGIONS), amount, now)
            if random.random() < 0.9:
                paid = now + dt.timedelta(seconds=random.uniform(2, 20))
                payment_id += 1
                pending.append((paid, "payment", (payment_id, order_id, random.choice(METHODS), amount)))
                if random.random() < 0.8:
                    shipped = paid + dt.timedelta(seconds=random.uniform(5, 60))
                    shipment_id += 1
                    pending.append((shipped, "shipment", (shipment_id, order_id, random.choice(CARRIERS))))
            for due, kind, fields in sorted(pending, key=lambda item: item[0]):
                if due <= now:
                    getattr(p, kind)(*fields, due)
            pending = [item for item in pending if item[0] > now]
            time.sleep(1.0 / rate)
    except KeyboardInterrupt:
        print("\nstopped")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3000)
    ap.add_argument("--namespace", default="test")
    ap.add_argument("--shipments", default="/opt/pravaha/feeds/shipments.csv",
                    help="the CSV file Pravaha follows for shipments")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--seed", action="store_true", help="write the six tutorial orders and exit")
    mode.add_argument("--live", action="store_true", help="keep writing orders until Ctrl-C")
    ap.add_argument("--rate", type=float, default=1.0, help="orders a second in --live mode")
    args = ap.parse_args()
    p = Pusher(args.host, args.port, args.namespace, args.shipments)
    seed(p) if args.seed else live(p, args.rate)


if __name__ == "__main__":
    main()
