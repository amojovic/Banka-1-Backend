--liquibase formatted sql

--changeset jovan:015-fund-dividend-policy
-- WP-17 (Celina 4.3): per-fond politika obrade dividende koju fond primi po
-- hartiji koju drzi. REINVEST = priliv se reinvestira u istu hartiju;
-- DISTRIBUTE = priliv se raspodeljuje klijentima srazmerno udelu. Default
-- REINVEST; NOT NULL DEFAULT backfiluje sve postojece fondove.
ALTER TABLE investment_funds
    ADD COLUMN dividend_policy VARCHAR(16) NOT NULL DEFAULT 'REINVEST';
