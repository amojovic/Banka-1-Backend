package http

import (
	"testing"

	"banka1/trading-service-go/internal/funds"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/platform"
	"banka1/trading-service-go/internal/tax"

	"banka1/go-platform/rabbitmq"
	"github.com/stretchr/testify/assert"
)

// TestNewApp_WiresWithoutDB builds the full App with a nil pool and the default
// (coexistence) config — every scheduler / consumer flag is off, so NewApp just
// wires the domain services and starts the order worker, no DB or broker call.
// Close() then drains the worker.
func TestNewApp_WiresWithoutDB(t *testing.T) {
	app := NewApp(
		platform.Config{},
		nil, // db pool
		newTestJWT(),
		quietLogger(),
		order.NoopNotifier{},
		tax.NoopNotifier{},
		funds.NewNoopSagaPublisher(quietLogger()),
		rabbitmq.Config{},
		nil, // employeeEventsPub
		otcNoopPub{},
		rabbitmq.Config{},
	)
	assert.NotNil(t, app)
	assert.NotNil(t, app.Order)
	assert.NotNil(t, app.Funds)
	assert.NotNil(t, app.Otc)
	app.Close()
	app.Close() // idempotent (closeOnce)
}
