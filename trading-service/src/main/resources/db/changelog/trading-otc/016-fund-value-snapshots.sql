--liquibase formatted sql

--changeset jovan:016-fund-value-snapshots
-- WP-18 (Celina 4.4): istorijski snapshot vrednosti investicionog fonda.
-- vrednostFonda i profit su inace izvedeni (racunaju se per query, ne cuvaju se
-- na investment_funds). Mesecni FundSnapshotScheduler materijalizuje tu izvedenu
-- vrednost u jedan red po fondu po danu, da statistika fonda (godisnji prinos,
-- volatilnost, max drawdown, reward-to-variability) ima istorijsku osnovu.
-- UNIQUE (fund_id, snapshot_date) -> najvise jedan snapshot po fondu po datumu;
-- scheduler je idempotentan na tom paru.
CREATE TABLE IF NOT EXISTS fund_value_snapshots (
    id              BIGSERIAL      PRIMARY KEY,
    fund_id         BIGINT         NOT NULL,
    snapshot_date   DATE           NOT NULL,
    total_value     NUMERIC(19,4)  NOT NULL,
    profit          NUMERIC(19,4)  NOT NULL,
    CONSTRAINT uq_fund_value_snapshot_fund_date UNIQUE (fund_id, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_fund_value_snapshots_fund_id ON fund_value_snapshots (fund_id);
CREATE INDEX IF NOT EXISTS idx_fund_value_snapshots_snapshot_date ON fund_value_snapshots (snapshot_date);
