package funds

import (
	"context"
	"log/slog"

	"banka1/trading-service-go/internal/clients"

	gpdb "banka1/go-platform/db"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// This file exposes constructors and seam helpers that let the http layer and
// package-external tests build funds services over fakes (fake Querier + fake
// QRunner) without a live Postgres pool or broker. The production constructors
// (NewService, NewDividendService, …) keep IDENTICAL public signatures; these
// *ForTest variants take the already-wired collaborators and seams directly.

// PoolQRunner adapts gpdb.RunInTx into a QRunner (the pgx.Tx is the Querier).
func PoolQRunner(pool *pgxpool.Pool) QRunner {
	return func(ctx context.Context, fn func(Querier) error) error {
		return gpdb.RunInTx(ctx, pool, pgx.TxOptions{}, func(tx pgx.Tx) error {
			return fn(tx)
		})
	}
}

// FakeQRunner returns a QRunner that calls fn(q) directly (no transaction).
func FakeQRunner(q Querier) QRunner {
	return func(ctx context.Context, fn func(Querier) error) error {
		return fn(q)
	}
}

// NewRepositoryForTest builds a Repository over an injected Querier (no pool).
func NewRepositoryForTest(q Querier) *Repository { return &Repository{q: q} }

// NewServiceForTest builds a Service with explicit collaborators and seams.
func NewServiceForTest(repo *Repository, snapshots *SnapshotService, stats *StatisticsService, holdings *HoldingService, market *clients.MarketClient, account *clients.AccountClient, employee *clients.EmployeeClient, publisher SagaPublisher, runInTx QRunner, logger *slog.Logger) *Service {
	return &Service{
		repo: repo, snapshots: snapshots, stats: stats, holdings: holdings,
		market: market, account: account, employee: employee,
		publisher: publisher, runInTx: runInTx, logger: logger,
	}
}

// NewHoldingServiceForTest builds a HoldingService directly.
func NewHoldingServiceForTest(repo *Repository, market *clients.MarketClient, logger *slog.Logger) *HoldingService {
	return &HoldingService{repo: repo, market: market, logger: logger}
}

// NewSnapshotServiceForTest builds a SnapshotService directly.
func NewSnapshotServiceForTest(repo *Repository, holding *HoldingService, logger *slog.Logger) *SnapshotService {
	return &SnapshotService{repo: repo, holding: holding, logger: logger}
}

// NewLiquidationServiceForTest builds a LiquidationService directly.
func NewLiquidationServiceForTest(repo *Repository, holding *HoldingService, market *clients.MarketClient, account *clients.AccountClient, snapshot *SnapshotService, logger *slog.Logger) *LiquidationService {
	return &LiquidationService{repo: repo, holding: holding, market: market, account: account, snapshot: snapshot, logger: logger}
}

// NewDividendServiceForTest builds a DividendService with an explicit QRunner seam.
func NewDividendServiceForTest(repo *Repository, holdings *HoldingService, snapshots *SnapshotService, market *clients.MarketClient, account *clients.AccountClient, funds *Service, runInTx QRunner, logger *slog.Logger) *DividendService {
	return &DividendService{
		repo: repo, holdings: holdings, snapshots: snapshots, market: market,
		account: account, funds: funds, runInTx: runInTx, logger: logger,
	}
}

// NewStatisticsServiceForTest builds a StatisticsService directly.
func NewStatisticsServiceForTest(snapshots *SnapshotService) *StatisticsService {
	return &StatisticsService{snapshots: snapshots}
}

// NewServiceCallbackForTest builds a ServiceCallback directly.
func NewServiceCallbackForTest(svc *Service, holding *HoldingService) *ServiceCallback {
	return &ServiceCallback{svc: svc, holding: holding}
}
