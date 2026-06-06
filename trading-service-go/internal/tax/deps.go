package tax

import (
	"context"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// The interfaces below abstract the exact collaborator methods Service calls so
// the tax engine can be unit-tested with stubs (no Postgres / HTTP / broker).
// The concrete types wired in NewService (*order.Repository, *portfolio.Repository,
// *actuary.Repository, the *clients.* clients) all satisfy these — NewService's
// body is unchanged. Mirrors the otc/funds deps precedent.

// orderReader abstracts the *order.Repository reads the FIFO engine needs.
// *order.Repository satisfies it. The q parameter is order.Querier so the real
// repository (which runs over its own pool) is assignable unchanged.
type orderReader interface {
	Pool() *pgxpool.Pool
	FindByDirection(ctx context.Context, q order.Querier, direction string) ([]order.Order, error)
	FindByUserIDAndDirection(ctx context.Context, q order.Querier, userID int64, direction string) ([]order.Order, error)
	FindByUserID(ctx context.Context, q order.Querier, userID int64) ([]order.Order, error)
	FindByUserIDIn(ctx context.Context, q order.Querier, userIDs []int64) ([]order.Order, error)
	FindByID(ctx context.Context, q order.Querier, id int64) (*order.Order, error)
	FindTransactionsByOrderIDsAndTimestampBetween(ctx context.Context, q order.Querier, orderIDs []int64, start, end time.Time) ([]order.Transaction, error)
	FindTransactionsByOrderIDsAndTimestampBefore(ctx context.Context, q order.Querier, orderIDs []int64, end time.Time) ([]order.Transaction, error)
}

// portfolioReader abstracts the *portfolio.Repository reads the engine needs.
type portfolioReader interface {
	Pool() *pgxpool.Pool
	FindByUserIDAndListingID(ctx context.Context, q portfolio.Querier, userID, listingID int64) (*portfolio.Portfolio, error)
}

// actuaryReader abstracts the *actuary.Repository employee-id lookup used by the
// actuary tax-tracking rows.
type actuaryReader interface {
	FindAllEmployeeIDs(ctx context.Context) ([]int64, error)
}

// taxRepository abstracts the in-package *Repository (the tax_charges ledger +
// the OTC tax JOIN). *Repository satisfies it; tests substitute a stub.
type taxRepository interface {
	ExistsBySellAndBuy(ctx context.Context, sellTxID, buyTxID int64) (bool, error)
	ExistsByOtcContractID(ctx context.Context, contractID int64) (bool, error)
	FindByUserIDAndStatus(ctx context.Context, userID int64, status string) ([]TaxCharge, error)
	FindAll(ctx context.Context) ([]TaxCharge, error)
	Insert(ctx context.Context, c *TaxCharge) error
	UpdateCharged(ctx context.Context, id int64, taxAmountRsd decimal.Decimal, chargedAt time.Time) error
	MarkCharged(ctx context.Context, id int64, chargedAt time.Time) error
	Delete(ctx context.Context, id int64) error
	LoadExercisedOtcTaxEntries(ctx context.Context, endExclusive time.Time) ([]OtcTaxEntry, error)
}

// marketClient abstracts the *clients.MarketClient listing + FX calls.
type marketClient interface {
	GetListing(ctx context.Context, id int64) (*clients.StockListing, error)
	CalculateWithoutCommission(ctx context.Context, from, to string, amount decimal.Decimal) (*clients.ExchangeRate, error)
}

// accountClient abstracts the *clients.AccountClient settlement calls.
type accountClient interface {
	GetAccountDetailsByID(ctx context.Context, accountID int64) (*clients.AccountDetails, error)
	GetGovernmentBankAccountRsd(ctx context.Context) (*clients.AccountDetails, error)
	Transaction(ctx context.Context, payment clients.Payment) error
	GetDefaultRsdAccountNumberForOwner(ctx context.Context, ownerID int64) string
}

// employeeClient abstracts the *clients.EmployeeClient lookup.
type employeeClient interface {
	GetEmployee(ctx context.Context, id int64) (*clients.Employee, error)
}

// customerClient abstracts the *clients.CustomerClient lookups.
type customerClient interface {
	GetCustomer(ctx context.Context, id int64) (*clients.Customer, error)
	SearchCustomers(ctx context.Context, ime, prezime *string, page, size int) (*clients.CustomerPage, error)
}
