-- Tutorial 9 (docs/tutorials/09-exactly-once-sinks.md): the tables the sinks write, in an H2 file.
-- A sink never creates or alters its table: you do. Run with H2's RunScript before the node starts.
CREATE TABLE spend_by_user (
    user_id       VARCHAR(32) NOT NULL,
    window_start  TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end    TIMESTAMP WITH TIME ZONE NOT NULL,
    spend         BIGINT NOT NULL,
    PRIMARY KEY (user_id, window_end)
);

CREATE TABLE payments_log (
    txn_id   BIGINT NOT NULL,
    user_id  VARCHAR(32) NOT NULL,
    amount   BIGINT NOT NULL
);

CREATE TABLE payments_log_plain (
    txn_id   BIGINT NOT NULL,
    user_id  VARCHAR(32) NOT NULL,
    amount   BIGINT NOT NULL
);
