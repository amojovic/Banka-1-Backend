package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestNormalizeValue(t *testing.T) {
	t.Run("map drops volatile keys and sorts", func(t *testing.T) {
		in := map[string]any{
			"b":           2.0,
			"a":           1.0,
			"timestamp":   "now",
			"lastRefresh": "later",
			"createdAt":   "then",
		}
		out, ok := normalizeValue(in).(map[string]any)
		if !ok {
			t.Fatalf("expected map[string]any, got %T", normalizeValue(in))
		}
		if _, present := out["timestamp"]; present {
			t.Fatal("timestamp should be dropped")
		}
		if _, present := out["lastRefresh"]; present {
			t.Fatal("lastRefresh should be dropped")
		}
		if _, present := out["createdAt"]; present {
			t.Fatal("createdAt should be dropped")
		}
		if out["a"] != 1.0 || out["b"] != 2.0 {
			t.Fatalf("non-volatile keys lost: %#v", out)
		}
	})

	t.Run("nested slice", func(t *testing.T) {
		in := []any{
			map[string]any{"x": 1.0, "timestamp": "drop"},
			"raw",
		}
		out, ok := normalizeValue(in).([]any)
		if !ok {
			t.Fatalf("expected []any, got %T", normalizeValue(in))
		}
		if len(out) != 2 {
			t.Fatalf("len = %d, want 2", len(out))
		}
		first := out[0].(map[string]any)
		if _, present := first["timestamp"]; present {
			t.Fatal("nested timestamp should be dropped")
		}
	})

	t.Run("scalar passthrough", func(t *testing.T) {
		if got := normalizeValue("scalar"); got != "scalar" {
			t.Fatalf("scalar = %v", got)
		}
		if got := normalizeValue(3.14); got != 3.14 {
			t.Fatalf("number = %v", got)
		}
	})
}

func TestNormalizeJSON(t *testing.T) {
	t.Run("empty", func(t *testing.T) {
		if got := normalizeJSON([]byte("   ")); got != "" {
			t.Fatalf("empty = %q", got)
		}
	})

	t.Run("invalid json returns trimmed raw", func(t *testing.T) {
		if got := normalizeJSON([]byte("  not json  ")); got != "not json" {
			t.Fatalf("invalid = %q", got)
		}
	})

	t.Run("normalizes key order and drops volatile", func(t *testing.T) {
		a := normalizeJSON([]byte(`{"b":2,"a":1,"timestamp":"t1"}`))
		b := normalizeJSON([]byte(`{"a":1,"b":2,"timestamp":"t2"}`))
		if a != b {
			t.Fatalf("expected equal after normalize: %q vs %q", a, b)
		}
		if !strings.Contains(a, `"a":1`) {
			t.Fatalf("normalized output missing a: %q", a)
		}
	})
}

func TestLoadSpecs(t *testing.T) {
	t.Run("empty path returns defaults", func(t *testing.T) {
		specs, err := loadSpecs("   ")
		if err != nil {
			t.Fatalf("loadSpecs err = %v", err)
		}
		if len(specs) != 2 {
			t.Fatalf("default specs len = %d, want 2", len(specs))
		}
		if specs[0].Name != "actuator-info" {
			t.Fatalf("specs[0].Name = %q", specs[0].Name)
		}
	})

	t.Run("missing file errors", func(t *testing.T) {
		_, err := loadSpecs(filepath.Join(t.TempDir(), "nope.json"))
		if err == nil {
			t.Fatal("expected error for missing file")
		}
	})

	t.Run("invalid json errors", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "bad.json")
		if err := os.WriteFile(path, []byte("{not valid"), 0o644); err != nil {
			t.Fatal(err)
		}
		_, err := loadSpecs(path)
		if err == nil {
			t.Fatal("expected error for invalid json")
		}
	})

	t.Run("valid file parses", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "specs.json")
		content := `[{"name":"custom","method":"POST","path":"/x","headers":{"X-A":"b"},"body":"{}"}]`
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
		specs, err := loadSpecs(path)
		if err != nil {
			t.Fatalf("loadSpecs err = %v", err)
		}
		if len(specs) != 1 || specs[0].Name != "custom" {
			t.Fatalf("specs = %#v", specs)
		}
	})
}

func TestDefaultSpecs(t *testing.T) {
	specs := defaultSpecs()
	if len(specs) != 2 {
		t.Fatalf("len = %d, want 2", len(specs))
	}
	for _, s := range specs {
		if s.Method != http.MethodGet {
			t.Fatalf("spec %q method = %q", s.Name, s.Method)
		}
		if s.Path == "" {
			t.Fatalf("spec %q has empty path", s.Name)
		}
	}
}

func TestCallEndpoint(t *testing.T) {
	t.Run("GET no body", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet {
				t.Errorf("method = %q", r.Method)
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"UP"}`))
		}))
		defer srv.Close()

		client := &http.Client{Timeout: 5 * time.Second}
		snap, err := callEndpoint(client, srv.URL, endpointSpec{Method: http.MethodGet, Path: "/actuator/info"}, "")
		if err != nil {
			t.Fatalf("callEndpoint err = %v", err)
		}
		if snap.status != http.StatusOK {
			t.Fatalf("status = %d", snap.status)
		}
		if string(snap.body) != `{"status":"UP"}` {
			t.Fatalf("body = %q", string(snap.body))
		}
	})

	t.Run("POST with body sets default content-type and token", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if ct := r.Header.Get("Content-Type"); ct != "application/json" {
				t.Errorf("Content-Type = %q", ct)
			}
			if auth := r.Header.Get("Authorization"); auth != "Bearer tok123" {
				t.Errorf("Authorization = %q", auth)
			}
			body := make([]byte, r.ContentLength)
			_, _ = r.Body.Read(body)
			w.WriteHeader(http.StatusCreated)
		}))
		defer srv.Close()

		client := &http.Client{Timeout: 5 * time.Second}
		spec := endpointSpec{Method: http.MethodPost, Path: "/x", Body: `{"k":"v"}`}
		snap, err := callEndpoint(client, srv.URL, spec, "tok123")
		if err != nil {
			t.Fatalf("callEndpoint err = %v", err)
		}
		if snap.status != http.StatusCreated {
			t.Fatalf("status = %d", snap.status)
		}
	})

	t.Run("explicit headers preserved over defaults", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if ct := r.Header.Get("Content-Type"); ct != "text/plain" {
				t.Errorf("Content-Type = %q, want text/plain", ct)
			}
			if auth := r.Header.Get("Authorization"); auth != "Bearer explicit" {
				t.Errorf("Authorization = %q", auth)
			}
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		client := &http.Client{Timeout: 5 * time.Second}
		spec := endpointSpec{
			Method:  http.MethodPost,
			Path:    "/x",
			Body:    "raw",
			Headers: map[string]string{"Content-Type": "text/plain", "Authorization": "Bearer explicit"},
		}
		if _, err := callEndpoint(client, srv.URL, spec, "ignored"); err != nil {
			t.Fatalf("callEndpoint err = %v", err)
		}
	})

	t.Run("bad method errors on NewRequest", func(t *testing.T) {
		client := &http.Client{Timeout: 5 * time.Second}
		_, err := callEndpoint(client, "http://example.com", endpointSpec{Method: "bad method", Path: "/x"}, "")
		if err == nil {
			t.Fatal("expected NewRequest error for invalid method")
		}
	})

	t.Run("connection error", func(t *testing.T) {
		// Point at a closed server to force a client.Do error.
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
		url := srv.URL
		srv.Close()
		client := &http.Client{Timeout: 1 * time.Second}
		_, err := callEndpoint(client, url, endpointSpec{Method: http.MethodGet, Path: "/x"}, "")
		if err == nil {
			t.Fatal("expected client.Do error against closed server")
		}
	})
}

// TestMain handles the self-exec subprocess guard for the main() coverage runs.
func TestMain(m *testing.M) {
	switch os.Getenv("PARITYCHECK_TEST_MODE") {
	case "":
		os.Exit(m.Run())
	default:
		main()
		os.Exit(0)
	}
}

// runMain re-execs this test binary with the harness disabled so main() runs
// inside the coverage-instrumented process, recording coverage for it.
func runMain(t *testing.T, args []string, extraEnv ...string) (string, error) {
	t.Helper()
	cmdArgs := append([]string{"-test.run=TestMain"}, args...)
	cmd := exec.Command(os.Args[0], cmdArgs...)
	cmd.Env = append(os.Environ(), "PARITYCHECK_TEST_MODE=run")
	cmd.Env = append(cmd.Env, extraEnv...)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func TestMainMatching(t *testing.T) {
	// Two identical servers → main() reports OK for both default actuator specs
	// and exits 0.
	handler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	}
	java := httptest.NewServer(http.HandlerFunc(handler))
	defer java.Close()
	goSrv := httptest.NewServer(http.HandlerFunc(handler))
	defer goSrv.Close()

	out, err := runMain(t, []string{"-java-base", java.URL, "-go-base", goSrv.URL})
	if err != nil {
		t.Fatalf("main() exited non-zero: %v\noutput:\n%s", err, out)
	}
	if !strings.Contains(out, "[OK] actuator-info") {
		t.Fatalf("missing OK line:\n%s", out)
	}
	if !strings.Contains(out, "2 ok, 0 diffs") {
		t.Fatalf("missing summary:\n%s", out)
	}
}

func TestMainDiff(t *testing.T) {
	// Servers return different bodies → DIFF, exit 1.
	java := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	}))
	defer java.Close()
	goSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"status":"DOWN"}`))
	}))
	defer goSrv.Close()

	out, err := runMain(t, []string{"-java-base", java.URL, "-go-base", goSrv.URL})
	if err == nil {
		t.Fatalf("expected non-zero exit for diff:\n%s", out)
	}
	if !strings.Contains(out, "[DIFF] actuator-info") {
		t.Fatalf("missing DIFF line:\n%s", out)
	}
}

func TestMainJavaCallFails(t *testing.T) {
	// java-base points at a closed port → java call fails branch, exit 1.
	closed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	javaURL := closed.URL
	closed.Close()

	goSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{}`))
	}))
	defer goSrv.Close()

	out, err := runMain(t, []string{"-java-base", javaURL, "-go-base", goSrv.URL, "-timeout", "1s"})
	if err == nil {
		t.Fatalf("expected non-zero exit:\n%s", out)
	}
	if !strings.Contains(out, "java call failed") {
		t.Fatalf("missing java-fail line:\n%s", out)
	}
}

func TestMainGoCallFails(t *testing.T) {
	// java ok, go-base closed → go call fails branch, exit 1.
	java := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{}`))
	}))
	defer java.Close()

	closed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	goURL := closed.URL
	closed.Close()

	out, err := runMain(t, []string{"-java-base", java.URL, "-go-base", goURL, "-timeout", "1s"})
	if err == nil {
		t.Fatalf("expected non-zero exit:\n%s", out)
	}
	if !strings.Contains(out, "go call failed") {
		t.Fatalf("missing go-fail line:\n%s", out)
	}
}

func TestMainLoadSpecsError(t *testing.T) {
	// -endpoints-file pointing at a missing file → loadSpecs error branch, exit 1.
	out, err := runMain(t, []string{"-endpoints-file", filepath.Join(t.TempDir(), "missing.json")})
	if err == nil {
		t.Fatalf("expected non-zero exit:\n%s", out)
	}
	if !strings.Contains(out, "load endpoint specs") {
		t.Fatalf("missing load-specs error line:\n%s", out)
	}
}

func TestMainWithTokenAndCustomSpec(t *testing.T) {
	// Custom endpoints file with a token to cover the token + headers path in
	// callEndpoint from within main().
	java := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer java.Close()
	goSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer goSrv.Close()

	specPath := filepath.Join(t.TempDir(), "specs.json")
	if err := os.WriteFile(specPath, []byte(`[{"name":"echo","method":"POST","path":"/echo","body":"{}"}]`), 0o644); err != nil {
		t.Fatal(err)
	}

	out, err := runMain(t, []string{
		"-java-base", java.URL,
		"-go-base", goSrv.URL,
		"-token", "abc",
		"-endpoints-file", specPath,
	})
	if err != nil {
		t.Fatalf("main() exited non-zero: %v\n%s", err, out)
	}
	if !strings.Contains(out, "[OK] echo") {
		t.Fatalf("missing OK echo line:\n%s", out)
	}
}
