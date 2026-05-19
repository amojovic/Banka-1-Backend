--liquibase formatted sql

--changeset jovan:012-dividend-payouts
-- WP-14 (Celina 3.7): kvartalna isplata dividende drzaocima akcija.
-- Jedan red = jedna isplata dividende jednom drzaocu za jednu hartiju.
-- Tretira se kao kapitalna dobit (15% porez u RSD); for_bank pozicije bez poreza.
CREATE TABLE IF NOT EXISTS dividend_payouts (
    id              BIGSERIAL      PRIMARY KEY,
    user_id         BIGINT         NOT NULL,
    stock_ticker    VARCHAR(32),
    listing_id      BIGINT         NOT NULL,
    quantity        INT            NOT NULL,
    gross_amount    NUMERIC(19,4)  NOT NULL,
    currency        VARCHAR(8),
    tax_amount_rsd  NUMERIC(19,4)  NOT NULL DEFAULT 0,
    net_amount      NUMERIC(19,4)  NOT NULL,
    account_id      BIGINT,
    payment_date    DATE           NOT NULL,
    for_bank        BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_dividend_payout_user_listing_date UNIQUE (user_id, listing_id, payment_date)
);

CREATE INDEX IF NOT EXISTS idx_dividend_payouts_user_id ON dividend_payouts (user_id);
CREATE INDEX IF NOT EXISTS idx_dividend_payouts_listing_id ON dividend_payouts (listing_id);
CREATE INDEX IF NOT EXISTS idx_dividend_payouts_payment_date ON dividend_payouts (payment_date DESC);
