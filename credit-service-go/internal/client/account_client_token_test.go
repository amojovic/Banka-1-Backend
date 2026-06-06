package client_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"Banka1Back/credit-service-go/internal/client"
	"Banka1Back/credit-service-go/internal/model"

	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// These tests cover the serviceToken() branch — setting JWT_SECRET causes
// GetDetails/TransactionFromBank/TransactionToBank to include a Bearer JWT
// in the Authorization header.

func TestGetDetails_WithJWTSecret_SendsBearerToken(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-service-secret")

	var receivedAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedAuth = r.Header.Get("Authorization")
		resp := client.AccountDetailsResponse{
			OwnerID:  1,
			Currency: model.CurrencyRSD,
			Email:    "svc@bank.io",
			Username: "svc",
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	t.Setenv("SERVICES_ACCOUNT_URL", server.URL)
	c := client.NewAccountClient()

	result, err := c.GetDetails("1234567890")
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.OwnerID)
	assert.True(t, strings.HasPrefix(receivedAuth, "Bearer "), "expected Bearer token, got: %s", receivedAuth)
}

func TestTransactionFromBank_WithJWTSecret_SendsBearerToken(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-service-secret")

	var receivedAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedAuth = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	t.Setenv("SERVICES_ACCOUNT_URL", server.URL)
	c := client.NewAccountClient()

	err := c.TransactionFromBank("1234567890", decimal.NewFromInt(500))
	require.NoError(t, err)
	assert.True(t, strings.HasPrefix(receivedAuth, "Bearer "), "expected Bearer token, got: %s", receivedAuth)
}

func TestTransactionToBank_WithJWTSecret_SendsBearerToken(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-service-secret")

	var receivedAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedAuth = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	t.Setenv("SERVICES_ACCOUNT_URL", server.URL)
	c := client.NewAccountClient()

	err := c.TransactionToBank("9876543210", decimal.NewFromInt(250))
	require.NoError(t, err)
	assert.True(t, strings.HasPrefix(receivedAuth, "Bearer "), "expected Bearer token, got: %s", receivedAuth)
}

func TestGetDetails_NoJWTSecret_NoAuthHeader(t *testing.T) {
	t.Setenv("JWT_SECRET", "")

	var receivedAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedAuth = r.Header.Get("Authorization")
		resp := client.AccountDetailsResponse{OwnerID: 2}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	t.Setenv("SERVICES_ACCOUNT_URL", server.URL)
	c := client.NewAccountClient()

	_, err := c.GetDetails("999")
	require.NoError(t, err)
	assert.Empty(t, receivedAuth, "expected no Authorization header when JWT_SECRET is empty")
}

func TestAddMarginPermission_WithJWTSecret_SendsBearerToken(t *testing.T) {
	t.Setenv("JWT_SECRET", "test-service-secret")

	var receivedAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedAuth = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	t.Setenv("SERVICES_USER_URL", server.URL)
	c := client.NewClientServiceClient()

	err := c.AddMarginPermission(42)
	require.NoError(t, err)
	assert.True(t, strings.HasPrefix(receivedAuth, "Bearer "), "expected Bearer token, got: %s", receivedAuth)
}
