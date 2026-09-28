"""
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.
"""

#!/usr/bin/env python3
"""Emit a morning of stock movements, as MySQL statements or as the changes the binlog carries.

    python3 data/generate_stock.py | docker exec -i pravaha-mysql mysql -uroot -ppravaha inventory
    python3 data/generate_stock.py --changes > data/sample/stock.csv

Two warehouses, London (LDN) and Manchester (MAN), open at 09:00 UTC with six stock lines. Through
the morning:

  * 09:05  sku-200 (toasters) sells five in London: 12 -> 7, under its reorder point of 8;
  * 09:10  sku-300 (blenders) sells four in London: 6 -> 2, under its reorder point of 5;
  * 09:15  sku-100 (kettles) sells three in Manchester: 25 -> 22;
  * 09:20  a delivery tops sku-200 up in London: 7 -> 27, and its alert clears;
  * 09:25  sku-400 (mixers), already under its reorder point in Manchester, is discontinued and
           its row deleted -- the alert goes with it;
  * 09:30  a trade customer takes thirty kettles in London: 40 -> 10, exactly at its reorder point.

Without --changes the output is SQL for the mysql client: one INSERT per opening line, then one
UPDATE or DELETE per movement, each its own transaction. With --changes it is what mysql-cdc hands
the engine for those statements: an INSERT is the new row, a DELETE the old row retracted (a line
starting "-,"), and an UPDATE the old row retracted then the new one inserted. That second form is
data/sample/stock.csv, which the build runs the study's queries over. The output is the same on
every run.
"""
import sys

HEADER = "sku,warehouse,on_hand,reorder_point,updated_at"
DAY = "2026-06-01"

OPENING = [
    # sku, warehouse, on_hand, reorder_point
    ("sku-100", "LDN", 40, 10),
    ("sku-100", "MAN", 25, 10),
    ("sku-200", "LDN", 12, 8),
    ("sku-300", "LDN", 6, 5),
    ("sku-300", "MAN", 30, 5),
    ("sku-400", "MAN", 3, 4),
]

# minute past nine, sku, warehouse, new on_hand (None deletes the row)
MOVEMENTS = [
    (5, "sku-200", "LDN", 7),
    (10, "sku-300", "LDN", 2),
    (15, "sku-100", "MAN", 22),
    (20, "sku-200", "LDN", 27),
    (25, "sku-400", "MAN", None),
    (30, "sku-100", "LDN", 10),
]


def at(minute: int) -> str:
    return f"{DAY} 09:{minute:02d}:00"


def iso(minute: int) -> str:
    return f"{DAY}T09:{minute:02d}:00Z"


def sql() -> None:
    for sku, warehouse, on_hand, reorder in OPENING:
        print(f"INSERT INTO stock (sku, warehouse, on_hand, reorder_point, updated_at) "
              f"VALUES ('{sku}', '{warehouse}', {on_hand}, {reorder}, '{at(0)}');")
    for minute, sku, warehouse, on_hand in MOVEMENTS:
        where = f"WHERE sku = '{sku}' AND warehouse = '{warehouse}'"
        if on_hand is None:
            print(f"DELETE FROM stock {where};")
        else:
            print(f"UPDATE stock SET on_hand = {on_hand}, updated_at = '{at(minute)}' {where};")


def changes() -> None:
    rows = {}
    print(HEADER)
    for sku, warehouse, on_hand, reorder in OPENING:
        rows[(sku, warehouse)] = (on_hand, reorder, iso(0))
        print(f"{sku},{warehouse},{on_hand},{reorder},{iso(0)}")
    for minute, sku, warehouse, on_hand in MOVEMENTS:
        old_on_hand, reorder, old_time = rows.pop((sku, warehouse))
        print(f"-,{sku},{warehouse},{old_on_hand},{reorder},{old_time}")
        if on_hand is not None:
            rows[(sku, warehouse)] = (on_hand, reorder, iso(minute))
            print(f"{sku},{warehouse},{on_hand},{reorder},{iso(minute)}")


if __name__ == "__main__":
    changes() if "--changes" in sys.argv else sql()
