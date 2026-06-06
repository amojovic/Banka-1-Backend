package order

import (
	"context"
	"time"

	"banka1/trading-service-go/internal/actuary"
	"banka1/trading-service-go/internal/audit"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	gpdb "banka1/go-platform/db"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// deps.go isolates the order Service from its concrete collaborators behind
// narrow interfaces so the service logic is unit-testable without Postgres, a
// broker, or the HTTP clients. The production wiring (NewService) assigns the
// concrete *Repository / *portfolio.Repository / *actuary.Repository /
// *clients.* / *audit.Service values, which all satisfy these interfaces (every
// method is exported and returns exported types). Mirrors the established
// precedent in internal/otc/deps.go, internal/dividend/deps.go.

// orderRepo abstracts *Repository. *Repository satisfies it.
type orderRepo interface {
	Pool() *pgxpool.Pool
	Insert(ctx context.Context, q Querier, o *Order) error
	Update(ctx context.Context, q Querier, o *Order) error
	FindByID(ctx context.Context, q Querier, id int64) (*Order, error)
	FindByIDForUpdate(ctx context.Context, q Querier, id int64) (*Order, error)
	FindAll(ctx context.Context, q Querier) ([]Order, error)
	FindByStatus(ctx context.Context, q Querier, status string) ([]Order, error)
	FindByUserID(ctx context.Context, q Querier, userID int64) ([]Order, error)
	InsertTransaction(ctx context.Context, q Querier, t *Transaction) error
	FindTransactionsByOrderID(ctx context.Context, q Querier, orderID int64) ([]Transaction, error)
	// Recurring orders.
	FindRecurringByUserID(ctx context.Context, q Querier, userID int64) ([]RecurringOrder, error)
	FindRecurringByID(ctx context.Context, q Querier, id int64) (*RecurringOrder, error)
	FindDueRecurring(ctx context.Context, q Querier, now time.Time) ([]RecurringOrder, error)
	InsertRecurring(ctx context.Context, q Querier, ro *RecurringOrder) error
	SetRecurringActive(ctx context.Context, q Querier, id int64, active bool) (bool, error)
	DeleteRecurring(ctx context.Context, q Querier, id int64) error
	UpdateRecurringNextRun(ctx context.Context, q Querier, id int64, nextRun time.Time) error
}

// orderPortfolios abstracts *portfolio.Repository. *portfolio.Repository satisfies it.
type orderPortfolios interface {
	Pool() *pgxpool.Pool
	FindByUserIDAndListingID(ctx context.Context, q portfolio.Querier, userID, listingID int64) (*portfolio.Portfolio, error)
	FindByUserIDAndListingIDForUpdate(ctx context.Context, q portfolio.Querier, userID, listingID int64) (*portfolio.Portfolio, error)
	UpdateReservedQuantity(ctx context.Context, q portfolio.Querier, id int64, reserved int) error
	UpdateSellPosition(ctx context.Context, q portfolio.Querier, id int64, quantity, reserved, public int) error
	Insert(ctx context.Context, q portfolio.Querier, userID, listingID int64, listingType string, quantity int, avg decimal.Decimal) error
	UpdateQuantityAndAvg(ctx context.Context, q portfolio.Querier, id int64, quantity int, avg decimal.Decimal) error
	Delete(ctx context.Context, q portfolio.Querier, id int64) error
}

// orderActuaries abstracts *actuary.Repository. *actuary.Repository satisfies it.
type orderActuaries interface {
	FindByEmployeeID(ctx context.Context, employeeID int64) (*actuary.ActuaryInfo, error)
	FindByEmployeeIDForUpdate(ctx context.Context, q actuary.Querier, employeeID int64) (*actuary.ActuaryInfo, error)
	FindEmployeeIDsIn(ctx context.Context, ids []int64) (map[int64]bool, error)
	UpdateReservedLimit(ctx context.Context, q actuary.Querier, employeeID int64, reserved decimal.Decimal) error
	UpdateReservedAndUsedLimit(ctx context.Context, q actuary.Querier, employeeID int64, reserved, used decimal.Decimal) error
}

// orderMarket abstracts *clients.MarketClient. *clients.MarketClient satisfies it.
type orderMarket interface {
	GetListing(ctx context.Context, id int64) (*clients.StockListing, error)
	RefreshListing(ctx context.Context, id int64)
	GetExchangeStatus(ctx context.Context, exchangeID int64) (*clients.ExchangeStatus, error)
	Calculate(ctx context.Context, from, to string, amount decimal.Decimal) (*clients.ExchangeRate, error)
	CalculateWithoutCommission(ctx context.Context, from, to string, amount decimal.Decimal) (*clients.ExchangeRate, error)
}

// orderAccount abstracts *clients.AccountClient. *clients.AccountClient satisfies it.
type orderAccount interface {
	GetAccountDetailsByID(ctx context.Context, accountID int64) (*clients.AccountDetails, error)
	GetBankAccount(ctx context.Context, currency string) (*clients.BankAccount, error)
	Transaction(ctx context.Context, payment clients.Payment) error
	Transfer(ctx context.Context, payment clients.Payment) error
	ExchangeBuy(ctx context.Context, req clients.OneSidedTransaction) error
	ExchangeSell(ctx context.Context, req clients.OneSidedTransaction) error
	StockBuyMarginTransaction(ctx context.Context, userID int64, amount decimal.Decimal) error
	StockSellMarginTransaction(ctx context.Context, userID int64, amount decimal.Decimal) error
}

// orderEmployees abstracts *clients.EmployeeClient. *clients.EmployeeClient satisfies it.
type orderEmployees interface {
	GetEmployee(ctx context.Context, id int64) (*clients.Employee, error)
}

// orderCustomers abstracts *clients.CustomerClient. *clients.CustomerClient satisfies it.
type orderCustomers interface {
	GetCustomer(ctx context.Context, id int64) (*clients.Customer, error)
}

// orderAuditor abstracts *audit.Service. *audit.Service satisfies it.
type orderAuditor interface {
	RecordBestEffort(ctx context.Context, ev audit.Event)
}

// txRunner runs fn inside a transaction. In production it is gpdb.RunInTx over a
// real pool; tests substitute a fake that calls fn(nil) (or fn with a fake Tx).
type txRunner func(ctx context.Context, opts pgx.TxOptions, fn func(pgx.Tx) error) error

// poolTxRunner adapts gpdb.RunInTx over a concrete pool into a txRunner.
func poolTxRunner(pool *pgxpool.Pool) txRunner {
	return func(ctx context.Context, opts pgx.TxOptions, fn func(pgx.Tx) error) error {
		return gpdb.RunInTx(ctx, pool, opts, fn)
	}
}
