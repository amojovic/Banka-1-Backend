package otc

import (
	"context"
	"io"
	"log/slog"

	"github.com/jackc/pgx/v5"
)

// fortest.go exposes a constructor so other packages' tests (notably the
// internal/http handler tests) can build an otc.Service wired to in-memory
// fakes instead of Postgres / a broker. The dependency parameters use the
// package's unexported collaborator interfaces, which is legal because every
// method on them is exported and returns exported types, so an external test
// package can implement them. No business logic lives here.

// NewServiceForTest builds a Service from already-constructed collaborators. A
// nil notifier falls back to NoopNotifier; a nil logger is replaced with a
// discard logger. runInTx is the transaction seam (a fake that calls fn(nil)
// runs the tx body inline).
func NewServiceForTest(repo otcRepo, portfolioRepo otcPortfolioRepo, market marketLister,
	customer customerLookup, employee employeeLookup, publisher SagaPublisher,
	notifier OtcNotifier, runInTx func(ctx context.Context, fn func(pgx.Tx) error) error,
	logger *slog.Logger) *Service {
	if notifier == nil {
		notifier = NoopNotifier{}
	}
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Service{
		repo: repo, portfolio: portfolioRepo, market: market, customer: customer,
		employee: employee, publisher: publisher, notifier: notifier, logger: logger,
		runInTx: runInTx,
	}
}

// NewReservationServiceForTest builds a ReservationService whose transaction
// seam runs inline over the supplied Querier (a test fake). The pool stays nil.
func NewReservationServiceForTest(portfolioRepo reservationPortfolioRepo, market marketLister,
	q reservationQuerier, logger *slog.Logger) *ReservationService {
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &ReservationService{
		portfolio: portfolioRepo, market: market, logger: logger,
		runInTx: func(ctx context.Context, fn func(reservationQuerier) error) error { return fn(q) },
	}
}
