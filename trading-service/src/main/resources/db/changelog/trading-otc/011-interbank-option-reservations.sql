--liquibase formatted sql

--changeset banka1:011-interbank-option-reservations-table
--comment: Tim 2 IMPORTANT-2 — persist inter-bank OTC option reservation map u DB tabelu
--          umesto in-memory ConcurrentHashMap. Restart trading-service-a ne sme da izgubi
--          negotiation->reservation mapping (orphan shares u suprotnom).
CREATE TABLE IF NOT EXISTS interbank_option_reservations (
    negotiation_id      VARCHAR(64)  NOT NULL PRIMARY KEY,
    reservation_id      VARCHAR(64)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    seller_user_id      BIGINT,
    ticker              VARCHAR(32),
    quantity            INTEGER,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_interbank_option_reservations_status
    ON interbank_option_reservations(status);
