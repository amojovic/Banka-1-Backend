-- +goose Up
-- +goose StatementBegin
-- S4: buyer-side OTC option contract persistence.
--
-- interbank_contracts originally held only SELLER-side contracts created by the
-- accept coordinator (we hosted the option pseudo-account). When we are the BUYER
-- bank (the partner hosts the option), the inbound accept-COMMIT must also persist
-- a contract so the buyer can later list & exercise their held options.
--
-- local_party_type distinguishes the two:
--   - 'SELLER' — we host the option pseudo-account (existing rows).
--   - 'BUYER'  — our user holds the option right (new buyer-side rows).
--
-- Existing rows were all created by the seller-side accept coordinator, so the
-- backfill default of 'SELLER' is correct for historical data.
ALTER TABLE interbank_contracts
    ADD COLUMN IF NOT EXISTS local_party_type VARCHAR(8) NOT NULL DEFAULT 'SELLER';

CREATE INDEX IF NOT EXISTS idx_interbank_contracts_party_buyer
    ON interbank_contracts(local_party_type, buyer_routing_number, buyer_id, status);
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP INDEX IF EXISTS idx_interbank_contracts_party_buyer;
ALTER TABLE interbank_contracts DROP COLUMN IF EXISTS local_party_type;
-- +goose StatementEnd
