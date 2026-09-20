CREATE TABLE counterparty (
  counterparty_id TEXT PRIMARY KEY,
  legal_name      TEXT   NOT NULL,
  rating          TEXT   NOT NULL,
  limit_minor     BIGINT NOT NULL
);

CREATE TABLE settlement (
  payment_id      BIGSERIAL PRIMARY KEY,
  counterparty_id TEXT        NOT NULL REFERENCES counterparty,
  currency        TEXT        NOT NULL,
  amount_minor    BIGINT      NOT NULL,
  direction       TEXT        NOT NULL,
  value_time      TIMESTAMPTZ NOT NULL,
  -- value_time as epoch nanoseconds, maintained by the database and never written by hand. The
  -- jdbc source polls on an integer column and stamps every row it reads with that column's value
  -- as its event time, so this is what advances the watermark -- and it has to be the same instant
  -- the query windows on, or the windows would be cut from one clock and closed by another.
  value_ns        BIGINT GENERATED ALWAYS AS
                    ((EXTRACT(EPOCH FROM (value_time AT TIME ZONE 'UTC')) * 1000000000)::BIGINT) STORED
);

-- The source polls on (value_ns, payment_id). Without this index the poll degrades into a table
-- scan as the table grows, and it degrades slowly, so nobody notices until it matters.
CREATE INDEX settlement_by_value_ns ON settlement (value_ns, payment_id);
