-- liquibase formatted sql

-- changeset order:12
ALTER TABLE orders ADD COLUMN created_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN executed_at TIMESTAMP;
UPDATE orders SET created_at = last_modification WHERE created_at IS NULL;
UPDATE orders SET executed_at = last_modification WHERE executed_at IS NULL AND status = 'DONE';
ALTER TABLE orders ALTER COLUMN created_at SET NOT NULL;
