-- The table the study captures, and the user the node reads the binary log as. Run once, as root:
--
--   docker exec -i pravaha-mysql mysql -uroot -ppravaha < data/schema.sql

CREATE DATABASE IF NOT EXISTS inventory;
USE inventory;

-- One row per product per warehouse. mysql-cdc streams every column, typed from
-- information_schema, in this order: the stream's schema is this table's.
CREATE TABLE stock (
  sku           VARCHAR(32) NOT NULL,
  warehouse     VARCHAR(8)  NOT NULL,
  on_hand       INT         NOT NULL,
  reorder_point INT         NOT NULL,
  -- When the row last changed, by the application's clock, in UTC. The stream's event time.
  updated_at    DATETIME    NOT NULL,
  PRIMARY KEY (sku, warehouse)
);

-- A replica needs both grants; without them the binding is refused at start with PRV-5152, naming
-- the statement that fixes it. SELECT is for reading the table's columns from information_schema.
CREATE USER IF NOT EXISTS 'pravaha_cdc'@'%' IDENTIFIED BY 'pravaha';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'pravaha_cdc'@'%';
