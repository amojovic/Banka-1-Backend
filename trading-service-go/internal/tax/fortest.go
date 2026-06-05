package tax

import (
	"log/slog"

	"github.com/shopspring/decimal"
)

// This file exposes test-only constructors over the unexported deps interfaces so
// the parallel internal/http handler tests (and these package tests) can build a
// fully stubbed Service / Repository without a live Postgres, HTTP upstream, or
// broker. Production wiring stays on NewService / NewRepository (unchanged
// signatures). Constructors only — no logic lives here.

// NewServiceForTest assembles a Service from stubbed collaborators. A nil notifier
// degrades to NoopNotifier, matching NewService.
func NewServiceForTest(
	taxRepo taxRepository,
	orderRepo orderReader,
	portfolioRepo portfolioReader,
	actuaryRepo actuaryReader,
	market marketClient,
	account accountClient,
	employee employeeClient,
	customer customerClient,
	notifier Notifier,
	taxRate decimal.Decimal,
	logger *slog.Logger,
) *Service {
	if notifier == nil {
		notifier = NoopNotifier{}
	}
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(discardWriter{}, nil))
	}
	return &Service{
		taxRepo:       taxRepo,
		orderRepo:     orderRepo,
		portfolioRepo: portfolioRepo,
		actuaryRepo:   actuaryRepo,
		market:        market,
		account:       account,
		employee:      employee,
		customer:      customer,
		notifier:      notifier,
		taxRate:       taxRate,
		logger:        logger,
	}
}

// NewRepositoryForTest builds a Repository over an arbitrary Querier (a pgx.Tx, a
// pool, or a test fake) so the scan/SQL paths can be exercised without a pool.
func NewRepositoryForTest(q Querier) *Repository {
	return &Repository{db: q}
}

type discardWriter struct{}

func (discardWriter) Write(p []byte) (int, error) { return len(p), nil }
