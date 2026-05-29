--liquibase formatted sql

-- changeset codex:13-1
-- Reconcile fund_value_snapshots schema with the current FundValueSnapshot entity.
--
-- Dev databases created on the previous backendNew-fani branch had this table
-- created with (id, fund_id, snapshot_date, total_value, profit). The current
-- entity expects (id, fund_id, snapshot_date, liquidity_value, holdings_value,
-- total_value, created_at). Migration 012-otc-history-dividends-fund-snapshots
-- uses CREATE TABLE IF NOT EXISTS, so it is a no-op against the stale table and
-- the GET /funds query fails on the missing columns. Adding the columns is
-- idempotent: on a freshly created table the columns already exist, on a stale
-- table they are added with safe defaults.

ALTER TABLE fund_value_snapshots ADD COLUMN IF NOT EXISTS liquidity_value NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE fund_value_snapshots ADD COLUMN IF NOT EXISTS holdings_value  NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE fund_value_snapshots ADD COLUMN IF NOT EXISTS created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- rollback ALTER TABLE fund_value_snapshots DROP COLUMN IF EXISTS liquidity_value;
-- rollback ALTER TABLE fund_value_snapshots DROP COLUMN IF EXISTS holdings_value;
-- rollback ALTER TABLE fund_value_snapshots DROP COLUMN IF EXISTS created_at;
