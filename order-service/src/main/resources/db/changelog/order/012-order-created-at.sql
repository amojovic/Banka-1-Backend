-- liquibase formatted sql

-- changeset order:12
ALTER TABLE orders ADD COLUMN created_at TIMESTAMP;
-- Backfill existing rows: lastModification is the only timestamp available; use it
-- as a best-effort creation time so legacy orders are not stuck with a null date.
UPDATE orders SET created_at = last_modification WHERE created_at IS NULL;
