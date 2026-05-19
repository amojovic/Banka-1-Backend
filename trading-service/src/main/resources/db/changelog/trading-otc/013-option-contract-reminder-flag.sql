--liquibase formatted sql

--changeset jovan:013-option-contract-reminder-flag
-- WP-15 (Celina 4.1): expiry-reminder idempotencija za OTC opcione ugovore.
-- Kolona belezi da li je "ugovor uskoro istice" notifikacija vec poslata, da
-- OtcExpiryReminderScheduler ne salje isti podsetnik vise puta. Postojeci
-- redovi se backfill-uju na FALSE (nijednom jos nije poslat podsetnik).
ALTER TABLE option_contracts
    ADD COLUMN expiry_reminder_sent BOOLEAN NOT NULL DEFAULT false;
