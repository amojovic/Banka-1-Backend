package push

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"time"
)

const fcmLegacyEndpoint = "https://fcm.googleapis.com/fcm/send"

type SenderConfig struct {
	ServerKey    string
	HTTPTimeout  time.Duration
}

func SenderConfigFromEnv() SenderConfig {
	return SenderConfig{
		ServerKey:   os.Getenv("FCM_SERVER_KEY"),
		HTTPTimeout: 15 * time.Second,
	}
}

type FCMSender struct {
	cfg    SenderConfig
	client *http.Client
	log    *slog.Logger
}

func NewFCMSender(cfg SenderConfig, log *slog.Logger) *FCMSender {
	return &FCMSender{
		cfg: cfg,
		client: &http.Client{
			Timeout: cfg.HTTPTimeout,
		},
		log: log,
	}
}

type fcmRequest struct {
	To               string            `json:"to"`
	Priority         string            `json:"priority,omitempty"`
	ContentAvailable bool              `json:"content_available,omitempty"`
	Notification     *fcmNotification  `json:"notification,omitempty"`
	Data             map[string]string `json:"data,omitempty"`
}

type fcmNotification struct {
	Title string `json:"title"`
	Body  string `json:"body"`
}

// SendNotification sends a high-priority FCM notification message with the
// given title and body to the specified device token.
func (s *FCMSender) SendNotification(ctx context.Context, deviceToken, title, body string) error {
	req := fcmRequest{
		To:       deviceToken,
		Priority: "high",
		Notification: &fcmNotification{
			Title: title,
			Body:  body,
		},
	}

	return s.send(ctx, req)
}

// SendData sends a high-priority data-only FCM message with the provided
// key-value pairs to the specified device token.
// The data map is safely defaulted to an empty map when nil or empty.
func (s *FCMSender) SendData(ctx context.Context, deviceToken string, data map[string]string) error {
	if data == nil || len(data) == 0 {
		data = make(map[string]string)
	}

	req := fcmRequest{
		To:               deviceToken,
		Priority:         "high",
		ContentAvailable: true,
		Data:             data,
	}

	return s.send(ctx, req)
}

func (s *FCMSender) send(ctx context.Context, req fcmRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("fcm marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, fcmLegacyEndpoint, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("fcm create request: %w", err)
	}

	httpReq.Header.Set("Authorization", "key="+s.cfg.ServerKey)
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := s.client.Do(httpReq)
	if err != nil {
		return fmt.Errorf("fcm http request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		s.log.Debug("fcm push sent successfully", "status", resp.StatusCode)
		return nil
	}

	var errBody bytes.Buffer
	errBody.ReadFrom(resp.Body)
	s.log.Error("fcm push failed",
		"status", resp.StatusCode,
		"response", errBody.String(),
	)
	return fmt.Errorf("fcm push returned non-2xx: %d", resp.StatusCode)
}
