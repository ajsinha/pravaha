#!/bin/sh
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The seed profile's second half, in the console image, which carries the Python SDK and so the
# `pravaha` command line: wait for the node to answer over Flight SQL, register the two continuous
# queries over the stream orders (each unless it is registered already), and print what they hold.
#
#   orders_live       every order, keyed by order_id                          (orders_live.sql)
#   spend_per_minute  orders and spend per customer per minute of event time  (spend_per_minute.sql)
#
# PRAVAHA_URL, PRAVAHA_TOKEN and PRAVAHA_INSECURE_TOKEN come from the compose file: the token is the
# static `seed` one tools/docker-env.sh generated, and Flight on the compose network is plaintext.
set -eu

tries=0
until pravaha queries >/dev/null 2>&1; do
  tries=$((tries + 1))
  if [ "$tries" -ge 90 ]; then
    echo "seed: the node did not answer on $PRAVAHA_URL in 180s" >&2
    pravaha queries || true
    exit 1
  fi
  sleep 2
done

register() {
  if pravaha queries | grep -q "^$1 "; then
    echo "seed: $1 is registered already"
  else
    pravaha register --name "$1" --keys "$2" --sql-file "/seed/$1.sql"
  fi
}
register orders_live 0
register spend_per_minute 0,1,2

# The views fill as the source reads the topic, and a minute's window closes a little after the
# watermark passes it; give both a moment, then show them.
tries=0
until pravaha query --sql "SELECT order_id FROM orders_live" 2>/dev/null | grep -q '^12 rows\|^[1-9][0-9] rows'; do
  tries=$((tries + 1)); [ "$tries" -lt 30 ] || break; sleep 2
done
tries=0
until ! pravaha query --sql "SELECT customer FROM spend_per_minute" 2>/dev/null | grep -q '^0 rows'; do
  tries=$((tries + 1)); [ "$tries" -lt 45 ] || break; sleep 2
done

echo
echo "seed: pravaha queries"
pravaha queries
echo
echo "seed: what each customer has spent, asked of the view orders_live"
pravaha query --sql "SELECT customer, COUNT(*) AS orders, SUM(amount) AS spend FROM orders_live GROUP BY customer"
echo
echo "seed: the view spend_per_minute"
pravaha query --sql "SELECT window_start, customer, orders, spend FROM spend_per_minute"
