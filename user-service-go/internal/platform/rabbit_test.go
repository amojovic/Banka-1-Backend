package platform

import (
	"context"
	"log/slog"
	"testing"
)

func TestNewRabbitPublisher_NoBroker_ReturnsNoop(t *testing.T) {
	t.Setenv("RABBITMQ_HOST", "127.0.0.1")
	t.Setenv("RABBITMQ_PORT", "1")
	cfg := LoadConfig()
	pub, err := NewRabbitPublisher(context.Background(), cfg, slog.Default())
	if err != nil {
		t.Fatalf("NewRabbitPublisher: %v", err)
	}
	defer pub.Close()

	err = pub.PublishEmail(context.Background(), "employee.created", EmailNotification{
		UserEmail: "a@test.com",
		Username:  "A",
	})
	if err != nil {
		t.Fatalf("PublishEmail: %v", err)
	}
	if err := pub.Publish(context.Background(), "employee.created", map[string]string{"k": "v"}); err != nil {
		t.Fatalf("Publish: %v", err)
	}
}
