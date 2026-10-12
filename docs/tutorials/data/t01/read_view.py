#!/usr/bin/env python3
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
"""Tutorial 1, step 9: read the maintained view from Python.

    export PRAVAHA_TOKEN=$(pravaha login --user pravaha-dev-admin)
    python read_view.py
"""
import os

from pravaha import ClientOptions, connect

client = connect(options=ClientOptions.create(
    os.environ.get("PRAVAHA_URL", "grpc://localhost:19090"),             # Flight SQL: reads and subscriptions
    token=os.environ["PRAVAHA_TOKEN"],
    http_url=os.environ.get("PRAVAHA_HTTP", "http://localhost:18080"),   # HTTP: the catalogue, plans, status
    allow_insecure_token=True,                                             # a node on this machine, over plaintext
))

query = client.describe_query("spend_per_minute")
print(query["name"], query["state"], "rows in:", query["rowsIn"])

# A read of the view looks up the answer the query already holds: it does not scan txn. A view has
# no row order (ORDER BY is refused, PRV-2020), so the reader sorts.
rows = client.query("SELECT user_id, window_start, spend, txns FROM spend_per_minute WHERE user_id = ?", ["bob"])
for row in sorted(rows.to_list(), key=lambda r: r["window_start"]):
    print(row["user_id"], row["window_start"].isoformat(), row["spend"], row["txns"])
