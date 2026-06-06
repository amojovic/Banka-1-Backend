package httpapi

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"Banka1Back/notification-service-go/internal/model"
)

type fakeRegistry struct {
	upserted *model.FcmToken
	deleted  int64
	upErr    error
	delErr   error
}

func (f *fakeRegistry) Upsert(_ context.Context, token *model.FcmToken) error {
	f.upserted = token
	return f.upErr
}

func (f *fakeRegistry) DeleteByClientId(_ context.Context, clientId int64) error {
	f.deleted = clientId
	return f.delErr
}

func newServer(reg TokenRegistry) http.Handler {
	mux := http.NewServeMux()
	NewFcmHandler(reg, nil).Register(mux)
	return mux
}

func TestRegisterToken_Valid_Upserts(t *testing.T) {
	reg := &fakeRegistry{}
	srv := newServer(reg)

	req := httptest.NewRequest(http.MethodPut, "/notifications/fcm/token",
		strings.NewReader(`{"clientId": 7, "fcmToken": "device-abc"}`))
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if reg.upserted == nil || reg.upserted.ClientId != 7 || reg.upserted.Token != "device-abc" {
		t.Fatalf("expected upsert clientId=7 token=device-abc, got %+v", reg.upserted)
	}
}

func TestRegisterToken_MissingClientId_400(t *testing.T) {
	reg := &fakeRegistry{}
	srv := newServer(reg)

	req := httptest.NewRequest(http.MethodPut, "/notifications/fcm/token",
		strings.NewReader(`{"fcmToken": "device-abc"}`))
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", rec.Code)
	}
	if reg.upserted != nil {
		t.Fatalf("store must not be called on invalid input")
	}
}

func TestRegisterToken_BlankToken_400(t *testing.T) {
	reg := &fakeRegistry{}
	srv := newServer(reg)

	req := httptest.NewRequest(http.MethodPut, "/notifications/fcm/token",
		strings.NewReader(`{"clientId": 7, "fcmToken": "   "}`))
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", rec.Code)
	}
}

func TestRegisterToken_StoreError_500(t *testing.T) {
	reg := &fakeRegistry{upErr: errors.New("db down")}
	srv := newServer(reg)

	req := httptest.NewRequest(http.MethodPut, "/notifications/fcm/token",
		strings.NewReader(`{"clientId": 7, "fcmToken": "device-abc"}`))
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500, got %d", rec.Code)
	}
}

func TestDeregisterToken_Valid_Deletes(t *testing.T) {
	reg := &fakeRegistry{}
	srv := newServer(reg)

	req := httptest.NewRequest(http.MethodDelete, "/notifications/fcm/token/42", nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if reg.deleted != 42 {
		t.Fatalf("expected delete clientId=42, got %d", reg.deleted)
	}
}

func TestDeregisterToken_BadId_400(t *testing.T) {
	reg := &fakeRegistry{}
	srv := newServer(reg)

	req := httptest.NewRequest(http.MethodDelete, "/notifications/fcm/token/abc", nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", rec.Code)
	}
}
