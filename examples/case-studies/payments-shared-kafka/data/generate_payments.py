"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit card payments as JSON, one object per line, for kafka-console-producer -- or as CSV.

    python3 data/generate_payments.py | docker exec -i pravaha-kafka \\
        /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payments
    python3 data/generate_payments.py --csv > data/sample/payment.csv

Three minutes of an afternoon, 14:00:00 to 14:02:50 UTC: a payment every ten seconds at three
merchants in turn (a coffee shop, a bookshop, a grocer). Most are small and on UK cards. Three are
large and on foreign cards -- the cross-border desk's business -- and three are declined -- the
risk desk's. The last payment, at 14:03:10, is the one whose time, less five seconds of
out-of-orderness, is past the end of the third minute: it closes it.

Each JSON object is a Kafka record's value, matched to the stream's columns by name, which is what
format: json reads. The output is the same on every run: the numbers in the README are these.
"""
import sys
from datetime import datetime, timedelta, timezone

START = datetime(2026, 9, 14, 14, 0, 0, tzinfo=timezone.utc)
COLUMNS = ["payment_id", "merchant", "card_country", "amount_minor", "status", "paid_at"]
MERCHANTS = ["m-coffee", "m-books", "m-grocer"]
# payment number -> (card country, amount): the large foreign ones
CROSS_BORDER = {5: ("US", 65000), 11: ("FR", 72000), 16: ("DE", 58000)}
DECLINED = {3, 10, 17}


def stamp(at: datetime) -> str:
    return at.strftime("%Y-%m-%dT%H:%M:%SZ")


def payments() -> list[list]:
    rows = []
    for i in range(18):
        country, amount = CROSS_BORDER.get(i, ("GB", 350 + (i * 1234) % 9000))
        status = "DECLINED" if i in DECLINED else "APPROVED"
        rows.append([f"p-{i:03d}", MERCHANTS[i % 3], country, amount, status, stamp(START + timedelta(seconds=10 * i))])
    rows.append(["p-close", "m-coffee", "GB", 400, "APPROVED", stamp(START + timedelta(minutes=3, seconds=10))])
    return rows


def main() -> None:
    if "--csv" in sys.argv:
        print(",".join(COLUMNS))
        for row in payments():
            print(",".join(str(v) for v in row))
        return
    for row in payments():
        fields = [f'"{c}":{v}' if isinstance(v, int) else f'"{c}":"{v}"' for c, v in zip(COLUMNS, row)]
        print("{" + ",".join(fields) + "}")


if __name__ == "__main__":
    main()
