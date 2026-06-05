package dividend

import (
	"io"
	"log/slog"

	"github.com/shopspring/decimal"
)

// NewServiceForTest builds a Service over stubbed collaborators (the package's
// unexported deps interfaces / function seams) so external test packages can
// exercise it without Postgres. Production wiring stays on NewService.
func NewServiceForTest(repo dividendRepo, portfolios dividendPortfolios, market dividendMarket,
	account dividendAccount, bankHeldBuy bankHeldFn, runTx txRunner, taxRate decimal.Decimal,
	logger *slog.Logger) *Service {
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Service{
		repo: repo, portfolios: portfolios, market: market, account: account,
		bankHeldBuy: bankHeldBuy, runTx: runTx, taxRate: taxRate, logger: logger,
	}
}
