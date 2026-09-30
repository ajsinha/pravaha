#!/bin/sh
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The seed profile's first half, in the Kafka image: create the topic `orders` and write the twelve
# orders in orders.jsonl to it. The orders are written only when this run created the topic, so
# running the seed again does not double every total; `SEED_AGAIN=1` writes them regardless.
set -eu
bootstrap="${KAFKA_BOOTSTRAP:-kafka:9092}"

if kafka-topics --bootstrap-server "$bootstrap" --list | grep -qx orders; then
  echo "seed: topic orders exists"
  created=0
else
  kafka-topics --bootstrap-server "$bootstrap" --create --topic orders --partitions 3 --replication-factor 1
  created=1
fi

if [ "$created" = 1 ] || [ "${SEED_AGAIN:-0}" = 1 ]; then
  kafka-console-producer --bootstrap-server "$bootstrap" --topic orders < /seed/orders.jsonl
  echo "seed: wrote $(wc -l < /seed/orders.jsonl) orders to topic orders"
else
  echo "seed: orders already written (SEED_AGAIN=1 writes them again)"
fi
