-- Email templates
CREATE TABLE email_templates (
                                 id SERIAL PRIMARY KEY,
                                 subject TEXT NOT NULL,
                                 body_template TEXT NOT NULL
);

-- Notification requests
CREATE TABLE notification_requests (
                                       id SERIAL PRIMARY KEY,
                                       username VARCHAR(255),
                                       user_email VARCHAR(255) NOT NULL,
                                       template_variables JSONB,
                                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Persistent delivery tracking
CREATE TABLE notification_deliveries (
                                         delivery_id UUID PRIMARY KEY,
                                         recipient_email VARCHAR(255) NOT NULL,
                                         subject TEXT NOT NULL,
                                         body TEXT NOT NULL,
                                         status VARCHAR(50) NOT NULL,
                                         notification_type VARCHAR(50) NOT NULL,
                                         retry_count INT NOT NULL,
                                         max_retries INT NOT NULL,
                                         last_error TEXT,
                                         next_attempt_at TIMESTAMP,
                                         last_attempt_at TIMESTAMP,
                                         sent_at TIMESTAMP,
                                         created_at TIMESTAMP NOT NULL,
                                         updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_notification_deliveries_status ON notification_deliveries(status);
CREATE INDEX idx_notification_deliveries_next_attempt_at ON notification_deliveries(next_attempt_at);

-- Retry tasks (možeš izostaviti ako koristiš samo notification_deliveries.next_attempt_at)
CREATE TABLE retry_tasks (
                             delivery_id UUID PRIMARY KEY,
                             next_attempt_at TIMESTAMP NOT NULL
);

-- Error logs
CREATE TABLE error_logs (
                            id SERIAL PRIMARY KEY,
                            status INT NOT NULL,
                            message TEXT NOT NULL,
                            timestamp TIMESTAMP NOT NULL
);