-- Tutorial 3: the customer table the lookup asks. Loaded into an H2 file with H2's RunScript tool.
CREATE TABLE customers (
    customer_id VARCHAR(16) PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    tier        VARCHAR(16) NOT NULL,
    region      VARCHAR(16) NOT NULL
);
INSERT INTO customers VALUES ('c-1', 'Asha Rao', 'gold', 'west');
INSERT INTO customers VALUES ('c-2', 'Ben Okafor', 'silver', 'east');
INSERT INTO customers VALUES ('c-3', 'Chen Li', 'bronze', 'west');
