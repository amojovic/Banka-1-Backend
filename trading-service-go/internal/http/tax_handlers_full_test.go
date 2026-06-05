package http

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/portfolio"
	"banka1/trading-service-go/internal/tax"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
)

// taxBigStub satisfies tax's taxRepository, actuaryReader, marketClient,
// accountClient, employeeClient and customerClient interfaces at once — none of
// those share a method name, so a single empty-returning stub covers them all.
type taxBigStub struct{}

// taxRepository
func (taxBigStub) ExistsBySellAndBuy(context.Context, int64, int64) (bool, error) { return false, nil }
func (taxBigStub) ExistsByOtcContractID(context.Context, int64) (bool, error)      { return false, nil }
func (taxBigStub) FindByUserIDAndStatus(context.Context, int64, string) ([]tax.TaxCharge, error) {
	return nil, nil
}
func (taxBigStub) FindAll(context.Context) ([]tax.TaxCharge, error) { return nil, nil }
func (taxBigStub) Insert(context.Context, *tax.TaxCharge) error     { return nil }
func (taxBigStub) UpdateCharged(context.Context, int64, decimal.Decimal, time.Time) error {
	return nil
}
func (taxBigStub) MarkCharged(context.Context, int64, time.Time) error { return nil }
func (taxBigStub) Delete(context.Context, int64) error                 { return nil }
func (taxBigStub) LoadExercisedOtcTaxEntries(context.Context, time.Time) ([]tax.OtcTaxEntry, error) {
	return nil, nil
}

// actuaryReader
func (taxBigStub) FindAllEmployeeIDs(context.Context) ([]int64, error) { return nil, nil }

// marketClient
func (taxBigStub) GetListing(context.Context, int64) (*clients.StockListing, error) { return nil, nil }
func (taxBigStub) CalculateWithoutCommission(context.Context, string, string, decimal.Decimal) (*clients.ExchangeRate, error) {
	return nil, nil
}

// accountClient
func (taxBigStub) GetAccountDetailsByID(context.Context, int64) (*clients.AccountDetails, error) {
	return nil, nil
}
func (taxBigStub) GetGovernmentBankAccountRsd(context.Context) (*clients.AccountDetails, error) {
	return nil, nil
}
func (taxBigStub) Transaction(context.Context, clients.Payment) error                 { return nil }
func (taxBigStub) GetDefaultRsdAccountNumberForOwner(context.Context, int64) string   { return "" }

// employeeClient
func (taxBigStub) GetEmployee(context.Context, int64) (*clients.Employee, error) { return nil, nil }

// customerClient
func (taxBigStub) GetCustomer(context.Context, int64) (*clients.Customer, error) { return nil, nil }
func (taxBigStub) SearchCustomers(context.Context, *string, *string, int, int) (*clients.CustomerPage, error) {
	return &clients.CustomerPage{}, nil
}

// taxOrderStub satisfies tax's orderReader. findDirErr forces an engine error.
type taxOrderStub struct{ findDirErr error }

func (taxOrderStub) Pool() *pgxpool.Pool { return nil }
func (s taxOrderStub) FindByDirection(context.Context, order.Querier, string) ([]order.Order, error) {
	return nil, s.findDirErr
}
func (taxOrderStub) FindByUserIDAndDirection(context.Context, order.Querier, int64, string) ([]order.Order, error) {
	return nil, nil
}
func (taxOrderStub) FindByUserID(context.Context, order.Querier, int64) ([]order.Order, error) {
	return nil, nil
}
func (taxOrderStub) FindByUserIDIn(context.Context, order.Querier, []int64) ([]order.Order, error) {
	return nil, nil
}
func (taxOrderStub) FindByID(context.Context, order.Querier, int64) (*order.Order, error) {
	return nil, nil
}
func (taxOrderStub) FindTransactionsByOrderIDsAndTimestampBetween(context.Context, order.Querier, []int64, time.Time, time.Time) ([]order.Transaction, error) {
	return nil, nil
}
func (taxOrderStub) FindTransactionsByOrderIDsAndTimestampBefore(context.Context, order.Querier, []int64, time.Time) ([]order.Transaction, error) {
	return nil, nil
}

type taxPortStub struct{}

func (taxPortStub) Pool() *pgxpool.Pool { return nil }
func (taxPortStub) FindByUserIDAndListingID(context.Context, portfolio.Querier, int64, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}

func newTaxHandlers(ord taxOrderStub) *Handlers {
	big := taxBigStub{}
	svc := tax.NewServiceForTest(big, ord, taxPortStub{}, big, big, big, big, big, nil,
		decimal.RequireFromString("0.15"), nil)
	return &Handlers{app: &App{Tax: svc}}
}

func TestTaxCollect_Success(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{})
	w := httptest.NewRecorder()
	h.TaxCollect(w, httptest.NewRequest(http.MethodPost, "/tax/collect", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestTaxCollectCurrentMonth_Success(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{})
	w := httptest.NewRecorder()
	h.TaxCollectCurrentMonth(w, httptest.NewRequest(http.MethodPost, "/tax/collect/current-month", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestTaxRunInternal_Success(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{})
	w := httptest.NewRecorder()
	h.TaxRunInternal(w, httptest.NewRequest(http.MethodPost, "/internal/tax/capital-gains/run", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestTaxRunInternal_Error(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{findDirErr: errors.New("db")})
	w := httptest.NewRecorder()
	h.TaxRunInternal(w, httptest.NewRequest(http.MethodPost, "/internal/tax/capital-gains/run", nil))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestTaxDebts_Success(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{})
	w := httptest.NewRecorder()
	h.TaxDebts(w, httptest.NewRequest(http.MethodGet, "/tax/capital-gains/debts?page=0&size=10", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestTaxDebts_Error(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{findDirErr: errors.New("db")})
	w := httptest.NewRecorder()
	h.TaxDebts(w, httptest.NewRequest(http.MethodGet, "/tax/capital-gains/debts", nil))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestTaxUserDebt_Success(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{})
	r := httptest.NewRequest(http.MethodGet, "/tax/capital-gains/7", nil)
	r.SetPathValue("userId", "7")
	w := httptest.NewRecorder()
	h.TaxUserDebt(w, r)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestTaxTracking_Success(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{})
	w := httptest.NewRecorder()
	h.TaxTracking(w, httptest.NewRequest(http.MethodGet, "/tax/tracking?page=0&size=10", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestTaxTracking_FilteredClient(t *testing.T) {
	h := newTaxHandlers(taxOrderStub{})
	w := httptest.NewRecorder()
	h.TaxTracking(w, httptest.NewRequest(http.MethodGet, "/tax/tracking?userType=CLIENT&firstName=A", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}
