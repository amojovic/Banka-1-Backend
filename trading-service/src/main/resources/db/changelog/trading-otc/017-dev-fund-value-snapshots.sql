--liquibase formatted sql

-- changeset jovan:017-dev-fund-value-snapshots context:dev
-- comment: DEV-ONLY seed istorijskih snapshot-a vrednosti fonda.
--
-- WP-18 je dodao statistiku fonda (godisnji prinos, volatilnost, max drawdown,
-- reward-to-variability), ali se ona racuna tek kad mesecni FundSnapshotScheduler
-- akumulira >= FUND_STATISTICS_MIN_SNAPSHOTS (default 3) redova u
-- fund_value_snapshots. Na svezem dev stack-u tabela je prazna pa su sve metrike
-- prazne i feature deluje kao da ne radi.
--
-- Ovaj changeset ubacuje 9 mesecnih snapshot-a (2025-09-01 .. 2026-05-01) po
-- svakom dev seed fondu iz 010-fund-seed-rsd-multi-funds.sql (Konzervativni RSD,
-- Agresivni Tech RSD, Dividendni RSD, Likvidni Balans RSD). Vrednosti su
-- namerno varirane (ne ravna linija) — sa rastom, padom i delimicnim oporavkom —
-- da volatilnost / max drawdown / annualized return izracunaju netrivijalne
-- brojeve. Svaki red postuje semu iz 016-fund-value-snapshots.sql:
--   (fund_id, snapshot_date, total_value, profit), NUMERIC(19,4) za novac.
-- snapshot_date je 1. u mesecu — poklapa se sa FUND_SNAPSHOT_CRON (0 30 0 1 * *).
--
-- Fond se pronalazi po nazivu (id-evi nisu deterministicki); changeset je
-- idempotentan kroz NOT EXISTS na (fund_id, snapshot_date) — istom paru odgovara
-- UNIQUE uq_fund_value_snapshot_fund_date, pa re-run ne duplira redove.

-- Konzervativni RSD — niska volatilnost, stabilan rast uz jedan plitak pad.
INSERT INTO fund_value_snapshots (fund_id, snapshot_date, total_value, profit)
SELECT f.id, v.snapshot_date, v.total_value, v.profit
FROM investment_funds f
JOIN (
    VALUES
        (DATE '2025-09-01', 218000.0000,  -7000.0000),
        (DATE '2025-10-01', 221500.0000,  -3500.0000),
        (DATE '2025-11-01', 224000.0000,  -1000.0000),
        (DATE '2025-12-01', 222800.0000,  -2200.0000),
        (DATE '2026-01-01', 226400.0000,   1400.0000),
        (DATE '2026-02-01', 229100.0000,   4100.0000),
        (DATE '2026-03-01', 231700.0000,   6700.0000),
        (DATE '2026-04-01', 234500.0000,   9500.0000),
        (DATE '2026-05-01', 238900.0000,  13900.0000)
) AS v(snapshot_date, total_value, profit) ON TRUE
WHERE f.naziv = 'Konzervativni RSD'
  AND NOT EXISTS (
      SELECT 1 FROM fund_value_snapshots s
      WHERE s.fund_id = f.id AND s.snapshot_date = v.snapshot_date
  );

-- Agresivni Tech RSD — visoka volatilnost, izrazen rast pa drawdown pa oporavak.
INSERT INTO fund_value_snapshots (fund_id, snapshot_date, total_value, profit)
SELECT f.id, v.snapshot_date, v.total_value, v.profit
FROM investment_funds f
JOIN (
    VALUES
        (DATE '2025-09-01', 305000.0000,  25000.0000),
        (DATE '2025-10-01', 348000.0000,  68000.0000),
        (DATE '2025-11-01', 312000.0000,  32000.0000),
        (DATE '2025-12-01', 268000.0000, -12000.0000),
        (DATE '2026-01-01', 244000.0000, -36000.0000),
        (DATE '2026-02-01', 289000.0000,   9000.0000),
        (DATE '2026-03-01', 336000.0000,  56000.0000),
        (DATE '2026-04-01', 358000.0000,  78000.0000),
        (DATE '2026-05-01', 327000.0000,  47000.0000)
) AS v(snapshot_date, total_value, profit) ON TRUE
WHERE f.naziv = 'Agresivni Tech RSD'
  AND NOT EXISTS (
      SELECT 1 FROM fund_value_snapshots s
      WHERE s.fund_id = f.id AND s.snapshot_date = v.snapshot_date
  );

-- Dividendni RSD — srednja volatilnost, postojan rast uz blagi zastoj.
INSERT INTO fund_value_snapshots (fund_id, snapshot_date, total_value, profit)
SELECT f.id, v.snapshot_date, v.total_value, v.profit
FROM investment_funds f
JOIN (
    VALUES
        (DATE '2025-09-01',  96000.0000,  -4000.0000),
        (DATE '2025-10-01', 101500.0000,   1500.0000),
        (DATE '2025-11-01', 105200.0000,   5200.0000),
        (DATE '2025-12-01', 104100.0000,   4100.0000),
        (DATE '2026-01-01', 108700.0000,   8700.0000),
        (DATE '2026-02-01', 113900.0000,  13900.0000),
        (DATE '2026-03-01', 112400.0000,  12400.0000),
        (DATE '2026-04-01', 119800.0000,  19800.0000),
        (DATE '2026-05-01', 125300.0000,  25300.0000)
) AS v(snapshot_date, total_value, profit) ON TRUE
WHERE f.naziv = 'Dividendni RSD'
  AND NOT EXISTS (
      SELECT 1 FROM fund_value_snapshots s
      WHERE s.fund_id = f.id AND s.snapshot_date = v.snapshot_date
  );

-- Likvidni Balans RSD — gotovo ravno sa jednim padom pa oporavkom.
INSERT INTO fund_value_snapshots (fund_id, snapshot_date, total_value, profit)
SELECT f.id, v.snapshot_date, v.total_value, v.profit
FROM investment_funds f
JOIN (
    VALUES
        (DATE '2025-09-01',  51000.0000,   1000.0000),
        (DATE '2025-10-01',  50600.0000,    600.0000),
        (DATE '2025-11-01',  48900.0000,  -1100.0000),
        (DATE '2025-12-01',  47200.0000,  -2800.0000),
        (DATE '2026-01-01',  48500.0000,  -1500.0000),
        (DATE '2026-02-01',  49800.0000,   -200.0000),
        (DATE '2026-03-01',  50900.0000,    900.0000),
        (DATE '2026-04-01',  51700.0000,   1700.0000),
        (DATE '2026-05-01',  52600.0000,   2600.0000)
) AS v(snapshot_date, total_value, profit) ON TRUE
WHERE f.naziv = 'Likvidni Balans RSD'
  AND NOT EXISTS (
      SELECT 1 FROM fund_value_snapshots s
      WHERE s.fund_id = f.id AND s.snapshot_date = v.snapshot_date
  );
