package http

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"banka1/trading-service-go/internal/platform"

	"github.com/stretchr/testify/assert"
)

// ---- NewRouter wires the full route table (covers NewRouter + registerRoutes) ----

func TestNewRouter_BuildsAndServesActuator(t *testing.T) {
	router := NewRouter(platform.Config{}, quietLogger(), nil, newTestJWT(), &App{})
	assert.NotNil(t, router)
	// the public health endpoint needs no auth and no DB.
	req := httptest.NewRequest(http.MethodGet, "/actuator/health/liveness", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

// ---- todo handlers: validation / method paths that never touch the DB ----

func TestWatchlists_BadJSON(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.Watchlists(w, fundsReq(http.MethodPost, "/watchlists", "{bad"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestWatchlists_MethodNotAllowed(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.Watchlists(w, fundsReq(http.MethodPut, "/watchlists", ""))
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestWatchlistItemDelete_BadID(t *testing.T) {
	h := &Handlers{app: &App{}}
	r := fundsReq(http.MethodDelete, "/watchlists/items/x", "")
	r.SetPathValue("id", "x")
	w := httptest.NewRecorder()
	h.WatchlistItemDelete(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestPriceAlerts_BadJSON(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.PriceAlerts(w, fundsReq(http.MethodPost, "/price-alerts", "{bad"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestPriceAlerts_ValidationFails(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	// valid JSON, but ticker empty / condition invalid → 400 before any DB call.
	h.PriceAlerts(w, fundsReq(http.MethodPost, "/price-alerts", `{"ticker":"","condition":"SIDEWAYS"}`))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestPriceAlerts_MethodNotAllowed(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.PriceAlerts(w, fundsReq(http.MethodPut, "/price-alerts", ""))
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}
