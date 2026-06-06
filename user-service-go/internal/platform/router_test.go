package platform

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNewRouter_HealthEndpoints(t *testing.T) {
	cfg := LoadConfig()
	logger := slog.Default()
	auth := NewJWTService(cfg.JWT)

	handler := NewRouter(RouterDeps{
		Config:        cfg,
		Logger:        logger,
		DB:            nil,
		Authenticator: auth,
		Register:      func(m *Mux, _ *JWTService) {},
	})

	for _, path := range []string{
		"/actuator/health",
		"/actuator/health/liveness",
		"/actuator/health/readiness",
		"/actuator/info",
	} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("%s status = %d", path, w.Code)
		}
	}
}

func TestMux_HandleFunc(t *testing.T) {
	m := &Mux{mux: http.NewServeMux()}
	m.HandleFunc(http.MethodGet, "/ping", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	})

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	m.mux.ServeHTTP(w, req)
	if w.Code != http.StatusTeapot {
		t.Fatalf("status = %d", w.Code)
	}
}
