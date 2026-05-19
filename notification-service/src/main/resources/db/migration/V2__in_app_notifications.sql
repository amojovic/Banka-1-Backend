-- In-app notifications shown inside the web client.
-- Separate from notification_deliveries (which is an email delivery/retry audit
-- log with no user FK and no read flag). One row per consumed RabbitMQ message
-- that carries a resolvable recipient (recipient_user_id + recipient_type).
CREATE TABLE in_app_notifications (
    id                BIGSERIAL    PRIMARY KEY,
    recipient_user_id BIGINT       NOT NULL,
    recipient_type    VARCHAR(16)  NOT NULL,
    type              VARCHAR(64)  NOT NULL,
    title             VARCHAR(255) NOT NULL,
    body              TEXT         NOT NULL,
    read              BOOLEAN      NOT NULL DEFAULT FALSE,
    reference_id      VARCHAR(255),
    created_at        TIMESTAMP    NOT NULL
);

-- Feed query: scoped to the owner, ordered newest-first.
CREATE INDEX idx_in_app_notifications_recipient
    ON in_app_notifications (recipient_user_id, recipient_type, created_at);

-- Unread-count query: scoped to the owner, filtered by read flag.
CREATE INDEX idx_in_app_notifications_unread
    ON in_app_notifications (recipient_user_id, recipient_type, read);
