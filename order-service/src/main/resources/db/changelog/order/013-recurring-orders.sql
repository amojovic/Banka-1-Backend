-- liquibase formatted sql

-- changeset order:13
-- WP-13 (Celina 3.6): standing / recurring (dollar-cost-averaging) orders.
CREATE TABLE recurring_orders (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT          NOT NULL,
    listing_id          BIGINT          NOT NULL,
    direction           VARCHAR(10)     NOT NULL,
    mode                VARCHAR(20)     NOT NULL,
    -- "value" is quoted: a non-reserved keyword in PostgreSQL but reserved in H2;
    -- the JPA mapping quotes the column name to match across both.
    "value"             NUMERIC(19, 4)  NOT NULL,
    account_id          BIGINT          NOT NULL,
    cadence             VARCHAR(20)     NOT NULL,
    next_run            TIMESTAMP       NOT NULL,
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL
);

CREATE INDEX idx_recurring_orders_user_id ON recurring_orders (user_id);
-- The scheduler scans for active, due standing orders; index that exact predicate.
CREATE INDEX idx_recurring_orders_due ON recurring_orders (active, next_run);
