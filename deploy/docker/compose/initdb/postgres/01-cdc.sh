#!/bin/sh
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The cdc profile's PostgreSQL, prepared for the postgres-cdc source (console help: "PostgreSQL CDC"):
# the table the stream `customers` reads, REPLICA IDENTITY FULL so an update or delete carries the
# whole old row, and a role that may replicate, owns the table and may create in the database, so
# the plugin can create its publication. wal_level=logical is on the server's command line in docker-compose.yml.
#
# Run once, by the image's entrypoint, when the data volume is empty. PRAVAHA_CDC_PASSWORD comes
# from deploy/docker/compose/.env, which tools/docker-env.sh generated.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE pravaha_cdc WITH LOGIN REPLICATION PASSWORD '${PRAVAHA_CDC_PASSWORD}';
CREATE TABLE public.customers (
  id   BIGINT PRIMARY KEY,
  name TEXT   NOT NULL,
  tier TEXT   NOT NULL
);
ALTER TABLE public.customers REPLICA IDENTITY FULL;
ALTER TABLE public.customers OWNER TO pravaha_cdc;
-- CREATE PUBLICATION needs CREATE on the database, as well as owning the table.
GRANT CREATE ON DATABASE shop TO pravaha_cdc;
INSERT INTO public.customers VALUES (1, 'acme', 'gold'), (2, 'globex', 'silver'), (3, 'initech', 'bronze');
SQL
