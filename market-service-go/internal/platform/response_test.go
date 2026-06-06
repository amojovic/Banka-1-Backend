package platform

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestJSONWritesStatusAndPayload(t *testing.T) {
	t.Parallel()
	rec := httptest.NewRecorder()

	JSON(rec, http.StatusCreated, map[string]string{"id": "42"})

	assert.Equal(t, http.StatusCreated, rec.Code)
	assert.Contains(t, rec.Header().Get("Content-Type"), "application/json")
	assert.JSONEq(t, `{"id":"42"}`, rec.Body.String())
}

func TestErrorWritesGenericErrorBody(t *testing.T) {
	t.Parallel()
	rec := httptest.NewRecorder()

	Error(rec, http.StatusBadRequest, "BAD_REQUEST", "bad input")

	assert.Equal(t, http.StatusBadRequest, rec.Code)
	var body map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "BAD_REQUEST", body["code"])
	assert.Equal(t, "bad input", body["message"])
	assert.NotEmpty(t, body["timestamp"])
}

func TestStockErrorWritesJavaCompatibleBody(t *testing.T) {
	t.Parallel()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/stocks/IBM", nil)

	StockError(rec, req, http.StatusNotFound, "stock not found")

	assert.Equal(t, http.StatusNotFound, rec.Code)
	var body StockErrorResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, http.StatusNotFound, body.Status)
	assert.Equal(t, "Not Found", body.Error)
	assert.Equal(t, "stock not found", body.Message)
	assert.Equal(t, "/api/stocks/IBM", body.Path)
	_, err := time.Parse(time.RFC3339, body.Timestamp)
	assert.NoError(t, err)
}

func TestExchangeErrorUsesEmptyValidationMapWhenNil(t *testing.T) {
	t.Parallel()
	rec := httptest.NewRecorder()

	ExchangeError(rec, http.StatusUnprocessableEntity, "VALIDATION", "Validation failed", "bad request", nil)

	assert.Equal(t, http.StatusUnprocessableEntity, rec.Code)
	var body ExchangeErrorResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "VALIDATION", body.Code)
	assert.Equal(t, "Validation failed", body.Title)
	assert.Equal(t, "bad request", body.Message)
	assert.NotNil(t, body.ValidationErrors)
	assert.Empty(t, body.ValidationErrors)
}

func TestExchangeErrorPreservesValidationMap(t *testing.T) {
	t.Parallel()
	rec := httptest.NewRecorder()
	validation := map[string]string{"amount": "must be positive"}

	ExchangeError(rec, http.StatusBadRequest, "BAD_REQUEST", "Bad request", "invalid fields", validation)

	var body ExchangeErrorResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, validation, body.ValidationErrors)
}

func TestNoContentWritesOnlyStatus(t *testing.T) {
	t.Parallel()
	rec := httptest.NewRecorder()

	NoContent(rec, http.StatusNoContent)

	assert.Equal(t, http.StatusNoContent, rec.Code)
	assert.Empty(t, rec.Body.String())
}
