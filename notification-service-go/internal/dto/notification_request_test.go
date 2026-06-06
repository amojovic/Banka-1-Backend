package dto

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNotificationRequestUnmarshalSupportsAliases(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name      string
		emailKey  string
		paramsKey string
	}{
		{"canonical", "userEmail", "templateVariables"},
		{"spring aliases", "email", "params"},
		{"recipient and data aliases", "recipientEmail", "data"},
		{"payload alias", "userEmail", "payload"},
		{"userData alias", "userEmail", "userData"},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			raw := `{"username":"  alice  ","` + tc.emailKey + `":"alice@example.com","` + tc.paramsKey + `":{"code":"123"},"clientId":42,"operationType":"RESET","sessionId":"s-1"}`

			var req NotificationRequest
			err := json.Unmarshal([]byte(raw), &req)

			require.NoError(t, err)
			assert.Equal(t, "  alice  ", req.Username)
			assert.Equal(t, "alice@example.com", req.UserEmail)
			assert.Equal(t, map[string]string{"code": "123"}, req.TemplateVariables)
			assert.Equal(t, int64(42), req.ClientID)
			assert.Equal(t, "RESET", req.OperationType)
			assert.Equal(t, "s-1", req.SessionID)
			assert.Equal(t, "alice", req.EffectiveUsername())
			assert.NoError(t, req.Validate())
		})
	}
}

func TestNotificationRequestUnmarshalInitializesMissingTemplateVariables(t *testing.T) {
	t.Parallel()

	var req NotificationRequest
	err := json.Unmarshal([]byte(`{"userEmail":"alice@example.com"}`), &req)

	require.NoError(t, err)
	assert.NotNil(t, req.TemplateVariables)
	assert.Empty(t, req.TemplateVariables)
}

func TestNotificationRequestValidateRequiresEmail(t *testing.T) {
	t.Parallel()

	req := NotificationRequest{UserEmail: "   "}

	err := req.Validate()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "ERR_NOTIFICATION_003")
}

func TestNotificationRequestUnmarshalReportsFieldErrors(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name     string
		raw      string
		contains string
	}{
		{"username", `{"username":123}`, "NotificationRequest.username"},
		{"clientId", `{"clientId":"bad"}`, "NotificationRequest.clientId"},
		{"operationType", `{"operationType":123}`, "NotificationRequest.operationType"},
		{"sessionId", `{"sessionId":123}`, "NotificationRequest.sessionId"},
		{"email alias", `{"email":123}`, "NotificationRequest.email"},
		{"template alias", `{"params":"bad"}`, "NotificationRequest.params"},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			var req NotificationRequest

			err := json.Unmarshal([]byte(tc.raw), &req)

			require.Error(t, err)
			assert.Contains(t, err.Error(), tc.contains)
		})
	}
}
