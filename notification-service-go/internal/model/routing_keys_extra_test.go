package model_test

import (
	"testing"

	"Banka1Back/notification-service-go/internal/model"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestResolveNotificationType_OrderLifecycleKeys(t *testing.T) {
	t.Parallel()

	cases := map[string]model.NotificationType{
		"order.created":        model.NotificationTypeOrderCreated,
		"order.done":           model.NotificationTypeOrderDone,
		"order.partial_fill":   model.NotificationTypeOrderPartialFill,
		"order.auto_cancelled": model.NotificationTypeOrderAutoCancelled,
	}

	for routingKey, expected := range cases {
		routingKey := routingKey
		expected := expected
		t.Run(routingKey, func(t *testing.T) {
			t.Parallel()
			got, ok := model.ResolveNotificationType(routingKey)
			require.True(t, ok)
			assert.Equal(t, expected, got)
		})
	}
}

func TestIsPushOnlyNotificationType(t *testing.T) {
	t.Parallel()

	assert.True(t, model.IsPushOnlyNotificationType(model.NotificationTypePriceAlertTriggered))
	assert.True(t, model.IsPushOnlyNotificationType(model.NotificationTypeOrderRecurringSkipped))
	assert.True(t, model.IsPushOnlyNotificationType(model.NotificationTypeOrderCreated))
	assert.True(t, model.IsPushOnlyNotificationType(model.NotificationTypeOrderDone))
	assert.True(t, model.IsPushOnlyNotificationType(model.NotificationTypeOrderPartialFill))
	assert.True(t, model.IsPushOnlyNotificationType(model.NotificationTypeOrderAutoCancelled))
	assert.False(t, model.IsPushOnlyNotificationType(model.NotificationTypeEmployeeCreated))
	assert.False(t, model.IsPushOnlyNotificationType(model.NotificationType("UNKNOWN")))
}

func TestIgnoredRoutingKeys(t *testing.T) {
	t.Parallel()

	assert.True(t, model.IgnoredRoutingKeys["card.create"])
	assert.True(t, model.IgnoredRoutingKeys["card.deactivate"])
	assert.False(t, model.IgnoredRoutingKeys["card.blocked"])
}
