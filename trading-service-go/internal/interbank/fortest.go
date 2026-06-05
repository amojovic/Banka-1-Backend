package interbank

import (
	"io"
	"log/slog"
)

// NewServiceForTest builds a Service over stubbed collaborators (the package's
// unexported deps interfaces, all-exported methods) so external test packages
// can exercise it without Postgres. Production wiring stays on NewService.
func NewServiceForTest(repo interbankRepo, portfolioRepo interbankPortfolio, market interbankMarket, runTx txRunner, routingNumber int, logger *slog.Logger) *Service {
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Service{repo: repo, portfolio: portfolioRepo, market: market, runTx: runTx, routingNumber: routingNumber, logger: logger}
}
