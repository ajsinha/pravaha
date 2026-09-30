#!/bin/sh
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The cdc profile's MySQL, prepared for the mysql-cdc source (console help: "MySQL CDC"): the table the
# stream `payments` reads, and a user with the replication grants the source checks for. Row-based
# binlog with full row images and GTIDs are on the server's command line in docker-compose.yml.
#
# Run once, by the image's entrypoint, when the data volume is empty. PRAVAHA_CDC_PASSWORD comes
# from deploy/docker/compose/.env, which tools/docker-env.sh generated.
set -eu

mysql --user=root --password="$MYSQL_ROOT_PASSWORD" <<SQL
CREATE TABLE IF NOT EXISTS shop.payments (
  id       BIGINT      NOT NULL PRIMARY KEY,
  customer VARCHAR(64) NOT NULL,
  amount   BIGINT      NOT NULL
);
CREATE USER 'pravaha_cdc'@'%' IDENTIFIED BY '${PRAVAHA_CDC_PASSWORD}';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'pravaha_cdc'@'%';
FLUSH PRIVILEGES;
SQL
