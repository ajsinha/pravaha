-- Tutorial 8 (docs/tutorials/08-change-data-capture.md): the shop's orders table, in PostgreSQL.
-- Run with psql against the tutorial's database; this is PostgreSQL, not Pravaha SQL.
CREATE TABLE orders (
    order_id  bigint PRIMARY KEY,
    customer  text   NOT NULL,
    status    text   NOT NULL,
    amount    bigint NOT NULL
);

INSERT INTO orders VALUES
    (1, 'ann',  'OPEN',    120),
    (2, 'bob',  'OPEN',     80),
    (3, 'ann',  'SHIPPED',  45),
    (4, 'carl', 'OPEN',    300);
