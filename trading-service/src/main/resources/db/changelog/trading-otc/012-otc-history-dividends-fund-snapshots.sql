--liquibase formatted sql

-- changeset codex:12-1
-- OTC negotiation history + expiry reminder idempotency + fund dividends + fund value snapshots.

CREATE TABLE IF NOT EXISTS otc_negotiation_history (
    id                      BIGSERIAL PRIMARY KEY,
    offer_id                BIGINT NOT NULL REFERENCES otc_offers(id),
    buyer_id                BIGINT NOT NULL,
    seller_id               BIGINT NOT NULL,
    actor_id                BIGINT,
    actor_name              VARCHAR(128),
    event_type              VARCHAR(32) NOT NULL,
    stock_ticker            VARCHAR(16) NOT NULL,
    old_amount              INTEGER,
    new_amount              INTEGER,
    old_price_per_stock     NUMERIC(19,2),
    new_price_per_stock     NUMERIC(19,2),
    old_premium             NUMERIC(19,2),
    new_premium             NUMERIC(19,2),
    old_settlement_date     DATE,
    new_settlement_date     DATE,
    old_status              VARCHAR(24),
    new_status              VARCHAR(24),
    changed_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_otc_history_offer_id ON otc_negotiation_history(offer_id);
CREATE INDEX IF NOT EXISTS idx_otc_history_buyer_id ON otc_negotiation_history(buyer_id);
CREATE INDEX IF NOT EXISTS idx_otc_history_seller_id ON otc_negotiation_history(seller_id);
CREATE INDEX IF NOT EXISTS idx_otc_history_changed_at ON otc_negotiation_history(changed_at);
CREATE INDEX IF NOT EXISTS idx_otc_history_new_status ON otc_negotiation_history(new_status);

CREATE TABLE IF NOT EXISTS otc_contract_expiry_reminders (
    id              BIGSERIAL PRIMARY KEY,
    contract_id     BIGINT NOT NULL REFERENCES option_contracts(id),
    reminder_days   INTEGER NOT NULL CHECK (reminder_days > 0),
    sent_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_otc_contract_expiry_reminder UNIQUE (contract_id, reminder_days)
);

CREATE INDEX IF NOT EXISTS idx_otc_expiry_reminders_sent_at ON otc_contract_expiry_reminders(sent_at);

ALTER TABLE investment_funds
    ADD COLUMN IF NOT EXISTS dividend_strategy VARCHAR(24) NOT NULL DEFAULT 'REINVEST';

CREATE TABLE IF NOT EXISTS fund_dividend_distributions (
    id                        BIGSERIAL PRIMARY KEY,
    fund_id                   BIGINT NOT NULL REFERENCES investment_funds(id),
    stock_ticker              VARCHAR(16) NOT NULL,
    payment_date              DATE NOT NULL,
    dividend_per_share        NUMERIC(19,8) NOT NULL CHECK (dividend_per_share > 0),
    source_currency           VARCHAR(8) NOT NULL,
    holding_quantity          INTEGER NOT NULL CHECK (holding_quantity >= 0),
    gross_amount_source       NUMERIC(19,8) NOT NULL CHECK (gross_amount_source >= 0),
    gross_amount_rsd          NUMERIC(19,2) NOT NULL CHECK (gross_amount_rsd >= 0),
    strategy                  VARCHAR(24) NOT NULL,
    status                    VARCHAR(24) NOT NULL,
    reinvested_shares         INTEGER,
    reinvested_amount_rsd     NUMERIC(19,2),
    distributed_amount_rsd    NUMERIC(19,2),
    note                      VARCHAR(255),
    processed_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_fund_dividend_distribution UNIQUE (fund_id, stock_ticker, payment_date)
);

CREATE INDEX IF NOT EXISTS idx_fund_dividend_distribution_fund_id
    ON fund_dividend_distributions(fund_id);
CREATE INDEX IF NOT EXISTS idx_fund_dividend_distribution_payment_date
    ON fund_dividend_distributions(payment_date);

CREATE TABLE IF NOT EXISTS fund_dividend_payouts (
    id                          BIGSERIAL PRIMARY KEY,
    distribution_id             BIGINT NOT NULL REFERENCES fund_dividend_distributions(id),
    client_id                   BIGINT NOT NULL,
    client_account_number       VARCHAR(32),
    ownership_ratio             NUMERIC(19,8) NOT NULL,
    amount_rsd                  NUMERIC(19,2) NOT NULL CHECK (amount_rsd >= 0),
    status                      VARCHAR(24) NOT NULL,
    failure_reason              VARCHAR(255),
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_fund_dividend_payout_distribution_client UNIQUE (distribution_id, client_id)
);

CREATE INDEX IF NOT EXISTS idx_fund_dividend_payout_distribution_id
    ON fund_dividend_payouts(distribution_id);
CREATE INDEX IF NOT EXISTS idx_fund_dividend_payout_client_id
    ON fund_dividend_payouts(client_id);

CREATE TABLE IF NOT EXISTS fund_value_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    fund_id             BIGINT NOT NULL REFERENCES investment_funds(id),
    snapshot_date       DATE NOT NULL,
    liquidity_value     NUMERIC(19,2) NOT NULL CHECK (liquidity_value >= 0),
    holdings_value      NUMERIC(19,2) NOT NULL CHECK (holdings_value >= 0),
    total_value         NUMERIC(19,2) NOT NULL CHECK (total_value >= 0),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_fund_value_snapshot_fund_date UNIQUE (fund_id, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_fund_value_snapshots_fund_id
    ON fund_value_snapshots(fund_id);
CREATE INDEX IF NOT EXISTS idx_fund_value_snapshots_snapshot_date
    ON fund_value_snapshots(snapshot_date);

-- rollback DROP TABLE IF EXISTS fund_value_snapshots;
-- rollback DROP TABLE IF EXISTS fund_dividend_payouts;
-- rollback DROP TABLE IF EXISTS fund_dividend_distributions;
-- rollback ALTER TABLE investment_funds DROP COLUMN IF EXISTS dividend_strategy;
-- rollback DROP TABLE IF EXISTS otc_contract_expiry_reminders;
-- rollback DROP TABLE IF EXISTS otc_negotiation_history;
