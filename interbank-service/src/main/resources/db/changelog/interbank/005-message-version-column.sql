--liquibase formatted sql

--changeset banka1:005-interbank-messages-version
--comment: Tim 2 IMPORTANT-5 — dodaj version kolonu za JPA optimistic locking.
--          Bez @Version, dve scheduler instance mogu istovremeno povuci isti
--          PENDING_SEND red i poslati duplikat outbound poruke partneru.
ALTER TABLE interbank_messages
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
