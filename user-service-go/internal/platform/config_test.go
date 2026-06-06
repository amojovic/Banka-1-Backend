package platform

import (
	"strings"
	"testing"
)

func TestLoadConfig_Defaults(t *testing.T) {
	for _, k := range []string{
		"SERVER_PORT", "USER_SERVICE_DB_HOST", "USER_SERVICE_DB_NAME",
		"JWT_SECRET", "BANKA_SECURITY_ISSUER", "BANKA_SECURITY_EXPIRATION_TIME",
		"BANKA_SECURITY_CORS_ALLOWED_ORIGINS", "RABBITMQ_HOST",
	} {
		t.Setenv(k, "")
	}

	c := LoadConfig()
	if c.ServerPort != "8081" {
		t.Fatalf("ServerPort = %q", c.ServerPort)
	}
	if c.DB.Name != "user_service" {
		t.Fatalf("DB.Name = %q", c.DB.Name)
	}
	if c.JWT.Issuer != "banka1" {
		t.Fatalf("JWT.Issuer = %q", c.JWT.Issuer)
	}
	if len(c.CORS.AllowedOrigins) == 0 {
		t.Fatal("AllowedOrigins empty")
	}
}

func TestLoadConfig_OverridesFromEnv(t *testing.T) {
	t.Setenv("SERVER_PORT", "9091")
	t.Setenv("USER_SERVICE_DB_NAME", "users_test")
	t.Setenv("BANKA_SECURITY_EXPIRATION_TIME", "5000")
	t.Setenv("BANKA_SECURITY_CORS_ALLOWED_ORIGINS", "http://a.test,http://b.test")
	t.Setenv("ACCOUNT_LOCKOUT_MAX_ATTEMPTS", "3")

	c := LoadConfig()
	if c.ServerPort != "9091" {
		t.Fatalf("ServerPort = %q", c.ServerPort)
	}
	if c.DB.Name != "users_test" {
		t.Fatalf("DB.Name = %q", c.DB.Name)
	}
	if c.JWT.AccessTokenDuration.Milliseconds() != 5000 {
		t.Fatalf("AccessTokenDuration = %v", c.JWT.AccessTokenDuration)
	}
	if c.User.EmployeeLockoutAttempts != 3 {
		t.Fatalf("EmployeeLockoutAttempts = %d", c.User.EmployeeLockoutAttempts)
	}
	if len(c.CORS.AllowedOrigins) != 2 {
		t.Fatalf("AllowedOrigins = %v", c.CORS.AllowedOrigins)
	}
}

func TestConfig_DatabaseURL(t *testing.T) {
	c := Config{
		DB: DBConfig{
			User:     "u",
			Password: "p",
			Host:     "localhost",
			Port:     "5432",
			Name:     "user_service",
			SSLMode:  "disable",
		},
	}
	url := c.DatabaseURL()
	if !strings.Contains(url, "postgres://u:p@localhost:5432/user_service") {
		t.Fatalf("DatabaseURL = %q", url)
	}
}

func TestConfig_RabbitURL(t *testing.T) {
	c := Config{
		RabbitMQ: RabbitConfig{
			Username: "guest",
			Password: "guest",
			Host:     "rabbit",
			Port:     "5672",
		},
	}
	if got := c.RabbitURL(); got != "amqp://guest:guest@rabbit:5672/" {
		t.Fatalf("RabbitURL = %q", got)
	}
}

func TestEnvInt_InvalidValueUsesFallback(t *testing.T) {
	t.Setenv("ACCOUNT_LOCKOUT_MAX_ATTEMPTS", "not-a-number")
	c := LoadConfig()
	if c.User.EmployeeLockoutAttempts != 5 {
		t.Fatalf("expected fallback 5, got %d", c.User.EmployeeLockoutAttempts)
	}
}
