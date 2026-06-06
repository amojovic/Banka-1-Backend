package platform

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestJSON_WritesPayload(t *testing.T) {
	w := httptest.NewRecorder()
	JSON(w, http.StatusOK, map[string]string{"ok": "yes"})
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d", w.Code)
	}
	var body map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["ok"] != "yes" {
		t.Fatalf("body = %#v", body)
	}
}

func TestNoContent(t *testing.T) {
	w := httptest.NewRecorder()
	NoContent(w, http.StatusNoContent)
	if w.Code != http.StatusNoContent {
		t.Fatalf("status = %d", w.Code)
	}
}

func TestDecodeJSON_IgnoresUnknownFields(t *testing.T) {
	type payload struct {
		Email string `json:"email"`
	}
	req := httptest.NewRequest(http.MethodPost, "/", bytes.NewBufferString(`{"email":"a@b.com","margin":true}`))
	var dst payload
	if err := DecodeJSON(req, &dst); err != nil {
		t.Fatal(err)
	}
	if dst.Email != "a@b.com" {
		t.Fatalf("email = %q", dst.Email)
	}
}

func TestError_WritesErrorBody(t *testing.T) {
	w := httptest.NewRecorder()
	Error(w, http.StatusBadRequest, "BAD_REQUEST", "invalid")
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", w.Code)
	}
	var body map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["code"] != "BAD_REQUEST" || body["message"] != "invalid" {
		t.Fatalf("body = %#v", body)
	}
}

func TestErrorWithRequest(t *testing.T) {
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	ErrorWithRequest(w, req, http.StatusUnauthorized, "UNAUTHORIZED", "nope")
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d", w.Code)
	}
}
