--liquibase formatted sql

--changeset jovan:014-otc-offer-revisions
-- WP-16 (Celina 4.2): kompletna istorija OTC pregovora. Do sada OtcService.counterOffer
-- prepisuje otc_offers red "u mestu" pa se istorija gubi. Ovaj changeset uvodi
-- otc_offer_revisions tabelu — po jedan red za svaku akciju (CREATE/COUNTER/ACCEPT/
-- REJECT/WITHDRAW) sa starim i novim vrednostima, aktorom i trenutkom izmene.
-- Mapiranje mora odgovarati OtcOfferRevision entitetu (ddl-auto=validate).
CREATE TABLE otc_offer_revisions (
    id                  BIGSERIAL     PRIMARY KEY,
    offer_id            BIGINT        NOT NULL,
    action              VARCHAR(16)   NOT NULL,
    actor_user_id       BIGINT,
    actor_name          VARCHAR(128),
    actor_role          VARCHAR(16),
    old_amount          INTEGER,
    new_amount          INTEGER,
    old_price_per_stock NUMERIC(19,2),
    new_price_per_stock NUMERIC(19,2),
    old_premium         NUMERIC(19,2),
    new_premium         NUMERIC(19,2),
    old_settlement_date DATE,
    new_settlement_date DATE,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_otc_offer_revisions_offer_id   ON otc_offer_revisions(offer_id);
CREATE INDEX idx_otc_offer_revisions_created_at ON otc_offer_revisions(created_at);

-- rollback DROP TABLE IF EXISTS otc_offer_revisions;
