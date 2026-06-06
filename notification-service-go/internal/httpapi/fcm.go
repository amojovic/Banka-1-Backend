// Package httpapi exposes the notification-service HTTP surface. Today that is
// the FCM device-token registry the mobile app calls on login/logout — ported
// from the Java FcmTokenController (@RequestMapping("/notifications/fcm")) so
// the mobile contract is unchanged across the Java→Go cut-over.
package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"Banka1Back/notification-service-go/internal/model"
)

// TokenRegistry is the subset of store.FcmTokenStore the HTTP surface needs.
// Kept as an interface so the handler is unit-testable without a database.
type TokenRegistry interface {
	Upsert(ctx context.Context, token *model.FcmToken) error
	DeleteByClientId(ctx context.Context, clientId int64) error
}

// TokenLookup resolves a client's registered device token. Used by the test
// endpoint to address a push at a clientId.
type TokenLookup interface {
	FindByClientId(ctx context.Context, clientId int64) (*model.FcmToken, error)
}

// PushSender is the subset of push.FCMSender the test endpoint needs.
type PushSender interface {
	SendData(ctx context.Context, deviceToken string, data map[string]string) error
}

// FcmHandler serves the FCM token register/deregister endpoints, plus an
// optional manual push-trigger endpoint when a push sender is configured.
type FcmHandler struct {
	store  TokenRegistry
	lookup TokenLookup
	pusher PushSender
	logger *slog.Logger
}

// NewFcmHandler builds the handler over the given token registry.
func NewFcmHandler(store TokenRegistry, logger *slog.Logger) *FcmHandler {
	if logger == nil {
		logger = slog.Default()
	}
	return &FcmHandler{store: store, logger: logger}
}

// WithTestPush enables POST /notifications/fcm/test by wiring a token lookup and
// push sender. Call only when FCM is configured; without it the route returns
// 503. Returns the handler for chaining.
func (h *FcmHandler) WithTestPush(lookup TokenLookup, pusher PushSender) *FcmHandler {
	h.lookup = lookup
	h.pusher = pusher
	return h
}

// Register wires the FCM routes onto the given mux. Paths match the Java
// controller exactly (the gateway proxies /notifications/ through unchanged).
func (h *FcmHandler) Register(mux *http.ServeMux) {
	mux.HandleFunc("PUT /notifications/fcm/token", h.registerToken)
	mux.HandleFunc("DELETE /notifications/fcm/token/{clientId}", h.deregisterToken)
	mux.HandleFunc("POST /notifications/fcm/test", h.testPush)
}

// fcmTokenRequest mirrors the Java FcmTokenRequest body: {clientId, fcmToken}.
type fcmTokenRequest struct {
	ClientId *int64 `json:"clientId"`
	FcmToken string `json:"fcmToken"`
}

// registerToken handles PUT /notifications/fcm/token — upsert-in-place so the
// mobile app can call it on every login regardless of whether the token changed.
func (h *FcmHandler) registerToken(w http.ResponseWriter, r *http.Request) {
	var req fcmTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body", http.StatusBadRequest)
		return
	}
	if req.ClientId == nil || *req.ClientId <= 0 {
		http.Error(w, "clientId is required", http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(req.FcmToken) == "" {
		http.Error(w, "fcmToken must not be blank", http.StatusBadRequest)
		return
	}

	if err := h.store.Upsert(r.Context(), &model.FcmToken{
		ClientId: *req.ClientId,
		Token:    strings.TrimSpace(req.FcmToken),
	}); err != nil {
		h.logger.Error("FCM token upsert failed", "clientId", *req.ClientId, "error", err)
		http.Error(w, "failed to persist FCM token", http.StatusInternalServerError)
		return
	}

	h.logger.Info("registered FCM token", "clientId", *req.ClientId)
	w.WriteHeader(http.StatusOK)
}

// deregisterToken handles DELETE /notifications/fcm/token/{clientId} — called on
// logout. Absent rows are a silent no-op, mirroring the Java deleteByClientId.
func (h *FcmHandler) deregisterToken(w http.ResponseWriter, r *http.Request) {
	clientId, err := strconv.ParseInt(r.PathValue("clientId"), 10, 64)
	if err != nil || clientId <= 0 {
		http.Error(w, "clientId must be a positive integer", http.StatusBadRequest)
		return
	}

	if err := h.store.DeleteByClientId(r.Context(), clientId); err != nil {
		h.logger.Error("FCM token delete failed", "clientId", clientId, "error", err)
		http.Error(w, "failed to delete FCM token", http.StatusInternalServerError)
		return
	}

	h.logger.Info("deregistered FCM token", "clientId", clientId)
	w.WriteHeader(http.StatusOK)
}

// fcmTestRequest is the body of POST /notifications/fcm/test. Ported from the
// Java FcmTestRequest and extended with optional fields so any mobile push type
// can be simulated end-to-end (the mobile app dispatches on the "type" key):
//   - VERIFICATION_OTP: code, operationType, sessionId
//   - ORDER_* / PRICE_ALERT_TRIGGERED: title, body, orderId, status, ticker
type fcmTestRequest struct {
	ClientId      *int64 `json:"clientId"`
	Type          string `json:"type"`
	Code          string `json:"code"`
	OperationType string `json:"operationType"`
	SessionId     string `json:"sessionId"`
	Title         string `json:"title"`
	Body          string `json:"body"`
	OrderId       string `json:"orderId"`
	Status        string `json:"status"`
	Ticker        string `json:"ticker"`
}

// testPush handles POST /notifications/fcm/test — sends a single data push to the
// client's registered device. Restored from the Java FcmTokenController.testPush
// so push delivery can be exercised without driving a full order/payment flow.
func (h *FcmHandler) testPush(w http.ResponseWriter, r *http.Request) {
	if h.pusher == nil || h.lookup == nil {
		http.Error(w, "push not configured in this environment", http.StatusServiceUnavailable)
		return
	}

	var req fcmTestRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body", http.StatusBadRequest)
		return
	}
	if req.ClientId == nil || *req.ClientId <= 0 {
		http.Error(w, "clientId is required", http.StatusBadRequest)
		return
	}

	token, err := h.lookup.FindByClientId(r.Context(), *req.ClientId)
	if err != nil {
		h.logger.Error("test push token lookup failed", "clientId", *req.ClientId, "error", err)
		http.Error(w, "token lookup failed", http.StatusInternalServerError)
		return
	}
	if token == nil || token.Token == "" {
		http.Error(w, fmt.Sprintf("no FCM token registered for clientId=%d", *req.ClientId), http.StatusBadRequest)
		return
	}

	pushType := strings.TrimSpace(req.Type)
	if pushType == "" {
		pushType = "VERIFICATION_OTP"
	}
	data := map[string]string{"type": pushType}
	putIfSet(data, "code", req.Code)
	putIfSet(data, "operationType", req.OperationType)
	putIfSet(data, "sessionId", req.SessionId)
	putIfSet(data, "title", req.Title)
	putIfSet(data, "body", req.Body)
	putIfSet(data, "orderId", req.OrderId)
	putIfSet(data, "status", req.Status)
	putIfSet(data, "ticker", req.Ticker)

	if err := h.pusher.SendData(r.Context(), token.Token, data); err != nil {
		h.logger.Error("test push send failed", "clientId", *req.ClientId, "type", pushType, "error", err)
		http.Error(w, "push send failed: "+err.Error(), http.StatusBadGateway)
		return
	}

	h.logger.Info("test push sent", "clientId", *req.ClientId, "type", pushType)
	w.WriteHeader(http.StatusOK)
	_, _ = fmt.Fprintf(w, "FCM push (%s) sent to clientId=%d", pushType, *req.ClientId)
}

// putIfSet adds key=value to data only when value is non-empty, keeping the FCM
// payload free of blank fields the mobile app would otherwise have to filter.
func putIfSet(data map[string]string, key, value string) {
	if strings.TrimSpace(value) != "" {
		data[key] = value
	}
}
