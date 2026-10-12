#!/usr/bin/env python3
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
"""Tutorial 4, step 4: keep an exact copy of a view by applying its weights.

    export PRAVAHA_TOKEN=$(pravaha login --user pravaha-dev-admin)
    python follow.py          # prints the snapshot, then the first commit after it, then the copy
"""
import os
from collections import Counter

from pravaha import ClientOptions, connect

client = connect(options=ClientOptions.create(
    os.environ.get("PRAVAHA_URL", "grpc://localhost:19090"),
    token=os.environ["PRAVAHA_TOKEN"],
    http_url=os.environ.get("PRAVAHA_HTTP", "http://localhost:18080"),
    allow_insecure_token=True,          # a node on this machine, over plaintext
))

copy = Counter()
# snapshot=True: the view as it is, then every commit after it -- none missed, none counted twice.
# overflow="FAIL": a copy built from weights must see every weight, so falling behind ends the stream.
for n, batch in enumerate(client.subscribe("big_payments", snapshot=True, overflow="FAIL")):
    for row in batch:
        r = row.to_dict()
        copy[(r["txn_id"], r["user_id"], r["amount"])] += row.weight     # apply the weight; never count rows
        print("snapshot" if batch.snapshot else "commit  ", f"{row.weight:+d}", r)
    if n == 1:                          # the snapshot, then one commit
        break

print("the copy:")
for txn_id, user_id, amount in sorted(k for k, weight in copy.items() if weight > 0):
    print(" ", txn_id, user_id, amount)
