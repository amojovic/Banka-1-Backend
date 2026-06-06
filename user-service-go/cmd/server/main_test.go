package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func newLoopbackServer(t *testing.T, handler http.Handler) (string, *httptest.Server) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := httptest.NewUnstartedServer(handler)
	srv.Listener.Close()
	srv.Listener = listener
	srv.Start()

	_, port, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatalf("split host port: %v", err)
	}
	return port, srv
}

func TestRunHealthcheck200(t *testing.T) {
	port, srv := newLoopbackServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/actuator/health/liveness" {
			t.Errorf("path = %q", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	t.Setenv("SERVER_PORT", port)
	if code := runHealthcheck(); code != 0 {
		t.Fatalf("runHealthcheck() = %d, want 0", code)
	}
}

func TestRunHealthcheckNon200(t *testing.T) {
	port, srv := newLoopbackServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	t.Setenv("SERVER_PORT", port)
	if code := runHealthcheck(); code != 1 {
		t.Fatalf("runHealthcheck() = %d, want 1", code)
	}
}

func TestRunHealthcheckConnError(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	_, port, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatalf("split host port: %v", err)
	}
	listener.Close()

	t.Setenv("SERVER_PORT", port)
	if code := runHealthcheck(); code != 1 {
		t.Fatalf("runHealthcheck() = %d, want 1", code)
	}
}

func TestRunHealthcheckDefaultPort(t *testing.T) {
	t.Setenv("SERVER_PORT", "")
	code := runHealthcheck()
	if code != 0 && code != 1 {
		t.Fatalf("runHealthcheck() = %d, want 0 or 1", code)
	}
}
