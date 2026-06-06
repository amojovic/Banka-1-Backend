package order

import (
	"log/slog"

	"github.com/jackc/pgx/v5/pgxpool"
)

// fortest.go exposes constructors that let other packages' tests (notably the
// internal/http handler tests) build an order.Service / order.Repository wired
// to in-memory fakes instead of Postgres, a broker, and the HTTP clients. The
// dependency parameters use the package's unexported collaborator interfaces:
// this is legal because every method on those interfaces is exported and
// returns exported types, so an external test package can implement them. No
// business logic lives here — only assembly.

// NewServiceForTest builds a Service from already-constructed collaborators,
// bypassing NewService's concrete-client wiring. Pass a synchronous txRunner
// (e.g. a fake that calls fn(nil)) so transactional paths run inline without a
// real pool. A nil notifier / funds callback falls back to the no-op
// implementations, matching NewService. The execution Worker is created but not
// started; call Start/Stop if the test exercises async execution.
func NewServiceForTest(
	repo orderRepo,
	portfolios orderPortfolios,
	actuaries orderActuaries,
	market orderMarket,
	account orderAccount,
	employees orderEmployees,
	customers orderCustomers,
	notifier Notifier,
	funds FundCallback,
	auditor orderAuditor,
	runInTx txRunner,
	logger *slog.Logger,
) *Service {
	if notifier == nil {
		notifier = NoopNotifier{}
	}
	if funds == nil {
		funds = NoopFundCallback{}
	}
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(discardWriter{}, nil))
	}
	s := &Service{
		repo:       repo,
		portfolios: portfolios,
		actuaries:  actuaries,
		market:     market,
		account:    account,
		employees:  employees,
		customers:  customers,
		notifier:   notifier,
		funds:      funds,
		auditor:    auditor,
		runInTx:    runInTx,
		logger:     logger,
	}
	s.worker = NewWorker(s.processExecutionAttempt, logger, 4)
	return s
}

// NewRepositoryForTest builds a Repository with an explicit pool (which may be
// nil in tests that only call query methods, since those take their Querier as
// a parameter). Provided for symmetry with NewServiceForTest.
func NewRepositoryForTest(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

type discardWriter struct{}

func (discardWriter) Write(p []byte) (int, error) { return len(p), nil }
