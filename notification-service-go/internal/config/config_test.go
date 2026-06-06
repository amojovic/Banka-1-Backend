package config

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLoadUsesDefaults(t *testing.T) {
	cfg, err := Load()

	require.NoError(t, err)
	assert.Equal(t, "0.0.0.0", cfg.Server.Host)
	assert.Equal(t, 8006, cfg.Server.HTTPPort)
	assert.Equal(t, "localhost", cfg.AMQP.Host)
	assert.Equal(t, 5672, cfg.AMQP.Port)
	assert.Equal(t, "guest", cfg.AMQP.Username)
	assert.Equal(t, "guest", cfg.AMQP.Password)
	assert.Equal(t, "employee.events", cfg.Rabbit.Exchange)
	assert.Equal(t, "notification-service-queue", cfg.Rabbit.Queue)
	assert.Equal(t, 10, cfg.Rabbit.Prefetch)
	assert.Equal(t, 4, cfg.Rabbit.Workers)
	assert.Equal(t, "localhost", cfg.DB.Host)
	assert.Equal(t, 5432, cfg.DB.Port)
	assert.Equal(t, "notification_db", cfg.DB.Name)
	assert.Equal(t, "smtp.gmail.com", cfg.SMTP.Host)
	assert.Equal(t, 587, cfg.SMTP.Port)
	assert.True(t, cfg.SMTP.StartTLS)
	assert.True(t, cfg.SMTP.AuthRequired)
	assert.False(t, cfg.SMTP.InsecureSkipVerify)
	assert.Equal(t, 4, cfg.Retry.MaxRetries)
	assert.Equal(t, 5, cfg.Retry.DelaySeconds)
	assert.Equal(t, 1000, cfg.Retry.SchedulerIntervalMs)
	assert.Len(t, cfg.Rabbit.BindingPatterns, 11)
}

func TestLoadReadsEnvironmentOverrides(t *testing.T) {
	t.Setenv("NOTIFICATION_SERVICE_HOST", "127.0.0.1")
	t.Setenv("NOTIFICATION_SERVICE_PORT", "9000")
	t.Setenv("RABBITMQ_HOST", "rabbit")
	t.Setenv("RABBITMQ_PORT", "5673")
	t.Setenv("RABBITMQ_USERNAME", "user")
	t.Setenv("RABBITMQ_PASSWORD", "pass")
	t.Setenv("RABBITMQ_VHOST", "notifications")
	t.Setenv("NOTIFICATION_EXCHANGE", "events")
	t.Setenv("NOTIFICATION_QUEUE", "queue")
	t.Setenv("NOTIFICATION_ROUTING_KEY", "employee.created")
	t.Setenv("RABBITMQ_PREFETCH", "2")
	t.Setenv("RABBITMQ_WORKERS", "3")
	t.Setenv("POSTGRES_HOST", "db")
	t.Setenv("POSTGRES_PORT", "15432")
	t.Setenv("POSTGRES_DB", "notifications")
	t.Setenv("POSTGRES_USER", "dbuser")
	t.Setenv("POSTGRES_PASSWORD", "dbpass")
	t.Setenv("POSTGRES_SSLMODE", "require")
	t.Setenv("MAIL_HOST", "mail")
	t.Setenv("MAIL_PORT", "2525")
	t.Setenv("MAIL_USERNAME", "mailer")
	t.Setenv("MAIL_PASSWORD", "secret")
	t.Setenv("MAIL_SMTP_STARTTLS", "false")
	t.Setenv("MAIL_SMTP_AUTH", "false")
	t.Setenv("MAIL_SMTP_INSECURE_SKIP_VERIFY", "true")
	t.Setenv("NOTIFICATION_RETRY_MAX_RETRIES", "8")
	t.Setenv("NOTIFICATION_RETRY_DELAY_SECONDS", "30")
	t.Setenv("NOTIFICATION_RETRY_SCHEDULER_DELAY_MILLIS", "250")

	cfg, err := Load()

	require.NoError(t, err)
	assert.Equal(t, "127.0.0.1", cfg.Server.Host)
	assert.Equal(t, 9000, cfg.Server.HTTPPort)
	assert.Equal(t, "rabbit", cfg.AMQP.Host)
	assert.Equal(t, 5673, cfg.AMQP.Port)
	assert.Equal(t, "amqp://user:pass@rabbit:5673/notifications", cfg.AMQP.URL())
	assert.Equal(t, "events", cfg.Rabbit.Exchange)
	assert.Equal(t, "queue", cfg.Rabbit.Queue)
	assert.Equal(t, []string{"employee.created"}, cfg.Rabbit.BindingPatterns[:1])
	assert.Equal(t, 2, cfg.Rabbit.Prefetch)
	assert.Equal(t, 3, cfg.Rabbit.Workers)
	assert.Equal(t, "db", cfg.DB.Host)
	assert.Equal(t, 15432, cfg.DB.Port)
	assert.Equal(t, "notifications", cfg.DB.Name)
	assert.Equal(t, "dbuser", cfg.DB.User)
	assert.Equal(t, "dbpass", cfg.DB.Password)
	assert.Equal(t, "require", cfg.DB.SSLMode)
	assert.Equal(t, "mail", cfg.SMTP.Host)
	assert.Equal(t, 2525, cfg.SMTP.Port)
	assert.Equal(t, "mailer", cfg.SMTP.Username)
	assert.Equal(t, "secret", cfg.SMTP.Password)
	assert.False(t, cfg.SMTP.StartTLS)
	assert.False(t, cfg.SMTP.AuthRequired)
	assert.True(t, cfg.SMTP.InsecureSkipVerify)
	assert.Equal(t, 8, cfg.Retry.MaxRetries)
	assert.Equal(t, 30, cfg.Retry.DelaySeconds)
	assert.Equal(t, 250, cfg.Retry.SchedulerIntervalMs)
}

func TestLoadReturnsErrorForInvalidInteger(t *testing.T) {
	t.Setenv("RABBITMQ_PORT", "not-a-number")

	cfg, err := Load()

	require.Error(t, err)
	assert.Nil(t, cfg)
	assert.True(t, strings.Contains(err.Error(), "RABBITMQ_PORT"))
}

func TestGetenvBoolFallsBackForInvalidValue(t *testing.T) {
	t.Setenv("MAIL_SMTP_STARTTLS", "not-bool")

	cfg, err := Load()

	require.NoError(t, err)
	assert.True(t, cfg.SMTP.StartTLS)
}
