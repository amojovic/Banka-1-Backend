package tax

import (
	"context"
	"errors"
	"testing"

	"banka1/trading-service-go/internal/api"
)

// stubPublisher records the last publish and can fail.
type stubPublisher struct {
	routingKey string
	payload    any
	err        error
	calls      int
}

func (p *stubPublisher) Publish(_ context.Context, routingKey string, payload any) error {
	p.calls++
	p.routingKey = routingKey
	p.payload = payload
	return p.err
}

func (p *stubPublisher) PublishWithID(ctx context.Context, routingKey, _ string, payload any) error {
	return p.Publish(ctx, routingKey, payload)
}

func (p *stubPublisher) Close() error { return nil }

func payload() api.TaxCollectedPayload {
	return api.TaxCollectedPayload{TemplateVariables: map[string]string{"tax": "10"}}
}

func TestRabbitNotifier_PublishSuccess(t *testing.T) {
	pub := &stubPublisher{}
	n := NewRabbitNotifier(pub, quietLogger())
	n.TaxCollected(context.Background(), payload())
	if pub.calls != 1 {
		t.Fatalf("expected 1 publish, got %d", pub.calls)
	}
	if pub.routingKey != routingTaxCollected {
		t.Errorf("routingKey = %q, want %q", pub.routingKey, routingTaxCollected)
	}
}

func TestRabbitNotifier_PublishErrorLogged(t *testing.T) {
	pub := &stubPublisher{err: errors.New("broker down")}
	n := NewRabbitNotifier(pub, quietLogger())
	// Must not panic; error is logged and swallowed.
	n.TaxCollected(context.Background(), payload())
	if pub.calls != 1 {
		t.Errorf("expected 1 publish attempt, got %d", pub.calls)
	}
}

func TestNoopNotifier_DoesNothing(t *testing.T) {
	var n Notifier = NoopNotifier{}
	// Should be a no-op (no panic, no state).
	n.TaxCollected(context.Background(), payload())
}
