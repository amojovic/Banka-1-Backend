package audit

import (
	"io"
	"log/slog"
)

// NewServiceForTest builds a Service over a stubbed auditRepo (its methods are
// all exported, so external test packages can implement it) — used by the
// internal/http audit handler tests. Production wiring stays on NewService.
func NewServiceForTest(repo auditRepo, logger *slog.Logger) *Service {
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Service{repo: repo, logger: logger}
}
