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
  value_time      TIMESTAMPTZ NOT NULL
);

-- The source polls on payment_id. Without this index the poll degrades into a table scan as the
-- table grows, and it degrades slowly, so nobody notices until it matters.
CREATE INDEX settlement_by_id ON settlement (payment_id);
