# Aerospike fulfilment — the tutorial's files

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

The files for [Joining two Aerospike sets and a CSV file](../../../docs/guides/tutorials/aerospike-fulfilment.md).
Read the tutorial first; it says when to run each.

| File | What it is |
|---|---|
| `push_orders.py` | Writes orders and payments to Aerospike (namespace `test`, sets `orders` and `payments`) and appends shipments to a CSV file. `--seed` writes six orders with known outcomes; `--live` keeps going until Ctrl-C. Needs `pip install aerospike`. |
| `aerospike-fulfilment.yaml` | The engine's configuration for the three streams and their sources, pulled into `/opt/pravaha/conf/application.yaml` with one `spring.config.import` line. |
| `fulfilment.py` | Registers the two continuous queries (`register`), reads them (`read`), follows the alert queue (`follow`) and drops them (`drop`), with the Python SDK. |
