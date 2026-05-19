-- liquibase formatted sql

-- changeset client-service:13
-- Celina 1, Scenario 5: per-account zakljucavanje naloga posle vise neuspesnih pokusaja prijave.
ALTER TABLE clients
    ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;

-- changeset client-service:14
ALTER TABLE clients
    ADD COLUMN locked_until TIMESTAMP;
