--liquibase formatted sql

--changeset jovan:011-audit-log
-- WP-2: centralizovani audit log. Servisi publikuju audit.* dogadjaje na
-- topic exchange (employee.events); AuditEventListener u trading-service-u
-- konzumira audit.# i upisuje po jedan red ovde.
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGSERIAL     PRIMARY KEY,
    actor_id      BIGINT,
    actor_name    VARCHAR(255),
    action_type   VARCHAR(64)   NOT NULL,
    target_type   VARCHAR(64),
    target_id     VARCHAR(64),
    details       TEXT,
    created_at    TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_action_type ON audit_log (action_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_id ON audit_log (actor_id);
