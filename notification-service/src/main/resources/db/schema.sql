CREATE TABLE notification_delivery (
    delivery_id VARCHAR(255) PRIMARY KEY,
    retry_count INT NOT NULL,
    max_retries INT NOT NULL,
    status VARCHAR(50) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    recipient_email VARCHAR(255),
    subject TEXT,
    body TEXT,
    last_error VARCHAR(1000),
    next_attempt_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    sent_at TIMESTAMP
);
