package http

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"banka1/trading-service-go/internal/actuary"
	"banka1/trading-service-go/internal/api"
	"banka1/trading-service-go/internal/audit"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/portfolio"

	gpauth "banka1/go-platform/auth"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
)

// ===========================================================================
// Stubs for the order package's deps.go collaborator interfaces. Each is its
// own type because orderRepo and orderPortfolios share method names (Insert,
// Pool) with incompatible signatures, so one struct cannot satisfy both.
// ===========================================================================

type ordRepoStub struct {
	orders    []order.Order
	recurring []order.RecurringOrder
	recur     *order.RecurringOrder
	byID      *order.Order

	findAllErr      error
	findStatusErr   error
	findUserErr     error
	findRecurUE     error
	insertRecurErr  error
	findRecurByIDE  error
	setActiveErr    error
	deleteRecurErr  error
	findByIDErr     error
}

func (s *ordRepoStub) Pool() *pgxpool.Pool { return nil }
func (s *ordRepoStub) Insert(context.Context, order.Querier, *order.Order) error { return nil }
func (s *ordRepoStub) Update(context.Context, order.Querier, *order.Order) error { return nil }
func (s *ordRepoStub) FindByID(_ context.Context, _ order.Querier, _ int64) (*order.Order, error) {
	return s.byID, s.findByIDErr
}
func (s *ordRepoStub) FindByIDForUpdate(_ context.Context, _ order.Querier, _ int64) (*order.Order, error) {
	return s.byID, s.findByIDErr
}
func (s *ordRepoStub) FindAll(context.Context, order.Querier) ([]order.Order, error) {
	return s.orders, s.findAllErr
}
func (s *ordRepoStub) FindByStatus(context.Context, order.Querier, string) ([]order.Order, error) {
	return s.orders, s.findStatusErr
}
func (s *ordRepoStub) FindByUserID(context.Context, order.Querier, int64) ([]order.Order, error) {
	return s.orders, s.findUserErr
}
func (s *ordRepoStub) InsertTransaction(context.Context, order.Querier, *order.Transaction) error {
	return nil
}
func (s *ordRepoStub) FindTransactionsByOrderID(context.Context, order.Querier, int64) ([]order.Transaction, error) {
	return nil, nil
}
func (s *ordRepoStub) FindRecurringByUserID(context.Context, order.Querier, int64) ([]order.RecurringOrder, error) {
	return s.recurring, s.findRecurUE
}
func (s *ordRepoStub) FindRecurringByID(context.Context, order.Querier, int64) (*order.RecurringOrder, error) {
	return s.recur, s.findRecurByIDE
}
func (s *ordRepoStub) FindDueRecurring(context.Context, order.Querier, time.Time) ([]order.RecurringOrder, error) {
	return nil, nil
}
func (s *ordRepoStub) InsertRecurring(_ context.Context, _ order.Querier, ro *order.RecurringOrder) error {
	ro.ID = 1
	return s.insertRecurErr
}
func (s *ordRepoStub) SetRecurringActive(context.Context, order.Querier, int64, bool) (bool, error) {
	return true, s.setActiveErr
}
func (s *ordRepoStub) DeleteRecurring(context.Context, order.Querier, int64) error {
	return s.deleteRecurErr
}
func (s *ordRepoStub) UpdateRecurringNextRun(context.Context, order.Querier, int64, time.Time) error {
	return nil
}

type ordPortStub struct{}

func (ordPortStub) Pool() *pgxpool.Pool { return nil }
func (ordPortStub) FindByUserIDAndListingID(context.Context, portfolio.Querier, int64, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (ordPortStub) FindByUserIDAndListingIDForUpdate(context.Context, portfolio.Querier, int64, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (ordPortStub) UpdateReservedQuantity(context.Context, portfolio.Querier, int64, int) error {
	return nil
}
func (ordPortStub) UpdateSellPosition(context.Context, portfolio.Querier, int64, int, int, int) error {
	return nil
}
func (ordPortStub) Insert(context.Context, portfolio.Querier, int64, int64, string, int, decimal.Decimal) error {
	return nil
}
func (ordPortStub) UpdateQuantityAndAvg(context.Context, portfolio.Querier, int64, int, decimal.Decimal) error {
	return nil
}
func (ordPortStub) Delete(context.Context, portfolio.Querier, int64) error { return nil }

type ordActStub struct{}

func (ordActStub) FindByEmployeeID(context.Context, int64) (*actuary.ActuaryInfo, error) {
	return nil, nil
}
func (ordActStub) FindByEmployeeIDForUpdate(context.Context, actuary.Querier, int64) (*actuary.ActuaryInfo, error) {
	return nil, nil
}
func (ordActStub) FindEmployeeIDsIn(context.Context, []int64) (map[int64]bool, error) {
	return map[int64]bool{}, nil
}
func (ordActStub) UpdateReservedLimit(context.Context, actuary.Querier, int64, decimal.Decimal) error {
	return nil
}
func (ordActStub) UpdateReservedAndUsedLimit(context.Context, actuary.Querier, int64, decimal.Decimal, decimal.Decimal) error {
	return nil
}

type ordMktStub struct {
	listing    *clients.StockListing
	listingErr error
}

func (s ordMktStub) GetListing(_ context.Context, _ int64) (*clients.StockListing, error) {
	return s.listing, s.listingErr
}
func (ordMktStub) RefreshListing(context.Context, int64) {}
func (ordMktStub) GetExchangeStatus(context.Context, int64) (*clients.ExchangeStatus, error) {
	return &clients.ExchangeStatus{}, nil
}
func (ordMktStub) Calculate(context.Context, string, string, decimal.Decimal) (*clients.ExchangeRate, error) {
	return nil, nil
}
func (ordMktStub) CalculateWithoutCommission(context.Context, string, string, decimal.Decimal) (*clients.ExchangeRate, error) {
	return nil, nil
}

type ordAccStub struct{}

func (ordAccStub) GetAccountDetailsByID(context.Context, int64) (*clients.AccountDetails, error) {
	return nil, clients.ErrNotFound
}
func (ordAccStub) GetBankAccount(context.Context, string) (*clients.BankAccount, error) {
	return &clients.BankAccount{}, nil
}
func (ordAccStub) Transaction(context.Context, clients.Payment) error                 { return nil }
func (ordAccStub) Transfer(context.Context, clients.Payment) error                    { return nil }
func (ordAccStub) ExchangeBuy(context.Context, clients.OneSidedTransaction) error     { return nil }
func (ordAccStub) ExchangeSell(context.Context, clients.OneSidedTransaction) error    { return nil }
func (ordAccStub) StockBuyMarginTransaction(context.Context, int64, decimal.Decimal) error  { return nil }
func (ordAccStub) StockSellMarginTransaction(context.Context, int64, decimal.Decimal) error { return nil }

type ordEmpStub struct{}

func (ordEmpStub) GetEmployee(context.Context, int64) (*clients.Employee, error) { return nil, nil }

type ordCustStub struct{}

func (ordCustStub) GetCustomer(context.Context, int64) (*clients.Customer, error) { return nil, nil }

type ordAudStub struct{}

func (ordAudStub) RecordBestEffort(context.Context, audit.Event) {}

// newOrderHandlers builds a *Handlers whose App.Order is a fully stubbed
// order.Service. The market stub can be tuned (listing / listingErr) for the
// create paths.
func newOrderHandlers(repo *ordRepoStub, mkt ordMktStub) *Handlers {
	svc := order.NewServiceForTest(repo, ordPortStub{}, ordActStub{}, mkt, ordAccStub{},
		ordEmpStub{}, ordCustStub{}, nil, nil, ordAudStub{},
		func(ctx context.Context, _ pgx.TxOptions, fn func(pgx.Tx) error) error { return fn(nil) },
		nil)
	return &Handlers{app: &App{Order: svc}}
}

// withRole attaches a principal carrying the given role so authUser sees it.
func withRole(r *http.Request, id int64, role string) *http.Request {
	ctx := gpauth.WithPrincipal(r.Context(), gpauth.Principal{ID: id, Role: role})
	return r.WithContext(ctx)
}

// ===========================================================================
// Pure helpers
// ===========================================================================

func TestRequireNotNullPositiveInt64(t *testing.T) {
	f := map[string]string{}
	requireNotNullPositiveInt64(f, "a", nil)
	assert.Equal(t, msgNotNull, f["a"])
	neg := int64(-1)
	requireNotNullPositiveInt64(f, "b", &neg)
	assert.Equal(t, msgPositive, f["b"])
	ok := int64(5)
	requireNotNullPositiveInt64(f, "c", &ok)
	assert.NotContains(t, f, "c")
}

func TestRequireNotNullPositiveInt(t *testing.T) {
	f := map[string]string{}
	requireNotNullPositiveInt(f, "a", nil)
	assert.Equal(t, msgNotNull, f["a"])
	z := 0
	requireNotNullPositiveInt(f, "b", &z)
	assert.Equal(t, msgPositive, f["b"])
	ok := 3
	requireNotNullPositiveInt(f, "c", &ok)
	assert.NotContains(t, f, "c")
}

func TestRequirePositiveInt64IfPresent(t *testing.T) {
	f := map[string]string{}
	requirePositiveInt64IfPresent(f, "a", nil) // absent → no error
	assert.NotContains(t, f, "a")
	neg := int64(-2)
	requirePositiveInt64IfPresent(f, "b", &neg)
	assert.Equal(t, msgPositive, f["b"])
}

func TestRequirePositiveDecimal(t *testing.T) {
	f := map[string]string{}
	requirePositiveDecimal(f, "a", nil)
	assert.NotContains(t, f, "a")
	z := decimal.Zero
	requirePositiveDecimal(f, "b", &z)
	assert.Equal(t, msgPositive, f["b"])
	pos := decimal.NewFromInt(1)
	requirePositiveDecimal(f, "c", &pos)
	assert.NotContains(t, f, "c")
}

func TestNormalizeEnum(t *testing.T) {
	v, ok := normalizeEnum(nil, "BUY")
	assert.True(t, ok)
	assert.Equal(t, "", v)
	blank := "  "
	v, ok = normalizeEnum(&blank, "BUY")
	assert.True(t, ok)
	assert.Equal(t, "", v)
	good := "buy"
	v, ok = normalizeEnum(&good, "BUY", "SELL")
	assert.True(t, ok)
	assert.Equal(t, "BUY", v)
	bad := "HODL"
	_, ok = normalizeEnum(&bad, "BUY", "SELL")
	assert.False(t, ok)
}

func TestNormalizeRecurringMode(t *testing.T) {
	_, ok := normalizeRecurringMode(nil)
	assert.True(t, ok)
	q := "by_quantity"
	v, ok := normalizeRecurringMode(&q)
	assert.True(t, ok)
	assert.Equal(t, order.RecurringModeByQuantity, v)
	a := "BYAMOUNT"
	v, ok = normalizeRecurringMode(&a)
	assert.True(t, ok)
	assert.Equal(t, order.RecurringModeByAmount, v)
	bad := "weird"
	_, ok = normalizeRecurringMode(&bad)
	assert.False(t, ok)
}

func TestDefaultRecurringNextRun(t *testing.T) {
	now := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	assert.Equal(t, now.Truncate(time.Second).AddDate(0, 0, 1), defaultRecurringNextRun(now, order.CadenceDaily, nil))
	assert.Equal(t, now.Truncate(time.Second).AddDate(0, 0, 7), defaultRecurringNextRun(now, order.CadenceWeekly, nil))
	// monthly without dayOfMonth → +1 month
	assert.Equal(t, now.Truncate(time.Second).AddDate(0, 1, 0), defaultRecurringNextRun(now, order.CadenceMonthly, nil))
	// monthly with a future day-of-month in the same month
	d20 := 20
	got := defaultRecurringNextRun(now, order.CadenceMonthly, &d20)
	assert.Equal(t, 20, got.Day())
	// monthly with a past day-of-month rolls to next month
	d5 := 5
	got = defaultRecurringNextRun(now, order.CadenceMonthly, &d5)
	assert.Equal(t, time.February, got.Month())
	// unknown cadence → +1 day
	assert.Equal(t, now.Truncate(time.Second).AddDate(0, 0, 1), defaultRecurringNextRun(now, "WHATEVER", nil))
}

func TestClampDay(t *testing.T) {
	assert.Equal(t, 28, clampDay(2026, time.February, 31)) // Feb 2026 has 28 days
	assert.Equal(t, 15, clampDay(2026, time.January, 15))
}

func TestQueryDate(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/x", nil)
	d, ok := queryDate(r, "absent")
	assert.True(t, ok)
	assert.Nil(t, d)
	r = httptest.NewRequest(http.MethodGet, "/x?d=2026-01-02", nil)
	d, ok = queryDate(r, "d")
	assert.True(t, ok)
	assert.NotNil(t, d)
	r = httptest.NewRequest(http.MethodGet, "/x?d=notadate", nil)
	_, ok = queryDate(r, "d")
	assert.False(t, ok)
}

func TestValidateCreateBuy(t *testing.T) {
	assert.NotNil(t, validateCreateBuy(api.CreateBuyOrderRequest{})) // missing listingId+quantity
	lid := int64(1)
	q := 2
	assert.Nil(t, validateCreateBuy(api.CreateBuyOrderRequest{ListingID: &lid, Quantity: &q}))
}

func TestValidateCreateSell(t *testing.T) {
	assert.NotNil(t, validateCreateSell(api.CreateSellOrderRequest{})) // missing fields
	lid := int64(1)
	q := 2
	acc := int64(5)
	assert.Nil(t, validateCreateSell(api.CreateSellOrderRequest{ListingID: &lid, Quantity: &q, AccountID: &acc}))
}

func TestAuthUser(t *testing.T) {
	r := withRole(httptest.NewRequest(http.MethodGet, "/x", nil), 7, "CLIENT")
	u := authUser(r)
	assert.Equal(t, int64(7), u.UserID)
	assert.True(t, u.IsClient())
	// no principal → empty roles
	u2 := authUser(httptest.NewRequest(http.MethodGet, "/x", nil))
	assert.Empty(t, u2.Roles)
}

// ===========================================================================
// Handlers backed by the stub service
// ===========================================================================

func TestOrderList_Success(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderList(w, httptest.NewRequest(http.MethodGet, "/orders", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestOrderList_BadStatus(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderList(w, httptest.NewRequest(http.MethodGet, "/orders?status=NONSENSE", nil))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOrderList_RepoError(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{findAllErr: errors.New("db")}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderList(w, httptest.NewRequest(http.MethodGet, "/orders", nil))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestOrderMyOrders_Success(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderMyOrders(w, withRole(httptest.NewRequest(http.MethodGet, "/orders/my-orders", nil), 1, "CLIENT"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestOrderMyOrders_Forbidden(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderMyOrders(w, httptest.NewRequest(http.MethodGet, "/orders/my-orders", nil))
	assert.Equal(t, http.StatusForbidden, w.Code)
}

func TestOrderMyOrdersPaged_Success(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderMyOrdersPaged(w, withRole(httptest.NewRequest(http.MethodGet, "/orders/my-orders/paged?status=ALL&listingType=STOCK&dateFrom=2026-01-01&dateTo=2026-02-01", nil), 1, "CLIENT"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestOrderMyOrdersPaged_BadStatus(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderMyOrdersPaged(w, httptest.NewRequest(http.MethodGet, "/orders/my-orders/paged?status=ZZZ", nil))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOrderMyOrdersPaged_BadListingType(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderMyOrdersPaged(w, httptest.NewRequest(http.MethodGet, "/orders/my-orders/paged?listingType=GOLD", nil))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOrderMyOrdersPaged_BadDateFrom(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderMyOrdersPaged(w, httptest.NewRequest(http.MethodGet, "/orders/my-orders/paged?dateFrom=bad", nil))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOrderMyOrdersPaged_BadPageSize(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderMyOrdersPaged(w, withRole(httptest.NewRequest(http.MethodGet, "/orders/my-orders/paged?size=999", nil), 1, "CLIENT"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOrderBuy_ServiceError(t *testing.T) {
	// valid body, but market.GetListing fails → service error.
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{listingErr: errors.New("no listing")})
	body := `{"listingId":1,"quantity":2}`
	w := httptest.NewRecorder()
	h.OrderBuy(w, withRole(httptest.NewRequest(http.MethodPost, "/orders/buy", bytes.NewBufferString(body)), 1, "CLIENT_TRADING"))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestOrderBuy_ValidationError(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.OrderBuy(w, httptest.NewRequest(http.MethodPost, "/orders/buy", bytes.NewBufferString(`{"quantity":-1}`)))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOrderSell_ServiceError(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{listingErr: errors.New("no listing")})
	body := `{"listingId":1,"quantity":2,"accountId":5}`
	w := httptest.NewRecorder()
	h.OrderSell(w, withRole(httptest.NewRequest(http.MethodPost, "/orders/sell", bytes.NewBufferString(body)), 1, "CLIENT_TRADING"))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestRecurringOrdersList_Success(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.RecurringOrdersList(w, withRole(httptest.NewRequest(http.MethodGet, "/recurring-orders", nil), 1, "CLIENT"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestRecurringOrdersList_Error(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{findRecurUE: errors.New("db")}, ordMktStub{})
	w := httptest.NewRecorder()
	h.RecurringOrdersList(w, httptest.NewRequest(http.MethodGet, "/recurring-orders", nil))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestRecurringOrderCreate_Success(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	body := `{"listingId":1,"direction":"BUY","mode":"BY_QUANTITY","value":2,"accountId":5,"cadence":"DAILY"}`
	w := httptest.NewRecorder()
	h.RecurringOrderCreate(w, withRole(httptest.NewRequest(http.MethodPost, "/recurring-orders", bytes.NewBufferString(body)), 1, "CLIENT"))
	assert.Equal(t, http.StatusCreated, w.Code)
}

func TestRecurringOrderCreate_BadEnum(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	body := `{"listingId":1,"direction":"HODL","mode":"BY_QUANTITY","value":2,"accountId":5,"cadence":"DAILY"}`
	w := httptest.NewRecorder()
	h.RecurringOrderCreate(w, httptest.NewRequest(http.MethodPost, "/recurring-orders", bytes.NewBufferString(body)))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestRecurringOrderCreate_ValidationFail(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	body := `{"direction":"BUY","mode":"BY_QUANTITY","cadence":"DAILY"}` // missing listingId/value/accountId
	w := httptest.NewRecorder()
	h.RecurringOrderCreate(w, httptest.NewRequest(http.MethodPost, "/recurring-orders", bytes.NewBufferString(body)))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestRecurringOrderCreate_PastNextRun(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	body := `{"listingId":1,"direction":"BUY","mode":"BY_QUANTITY","value":2,"accountId":5,"cadence":"DAILY","nextRun":"2000-01-01T00:00:00"}`
	w := httptest.NewRecorder()
	h.RecurringOrderCreate(w, httptest.NewRequest(http.MethodPost, "/recurring-orders", bytes.NewBufferString(body)))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestRecurringOrdersRunDueInternal_Success(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{}, ordMktStub{})
	w := httptest.NewRecorder()
	h.RecurringOrdersRunDueInternal(w, httptest.NewRequest(http.MethodPost, "/internal/recurring-orders/run-due", nil))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestRecurringOrderPauseResume_Success(t *testing.T) {
	owned := &order.RecurringOrder{ID: 9, UserID: 1}
	h := newOrderHandlers(&ordRepoStub{recur: owned}, ordMktStub{})
	for _, path := range []string{"pause", "resume"} {
		r := httptest.NewRequest(http.MethodPost, "/recurring-orders/9/"+path, nil)
		r.SetPathValue("id", "9")
		r = withRole(r, 1, "CLIENT")
		w := httptest.NewRecorder()
		if path == "pause" {
			h.RecurringOrderPause(w, r)
		} else {
			h.RecurringOrderResume(w, r)
		}
		assert.Equal(t, http.StatusOK, w.Code)
	}
}

func TestRecurringOrderPause_NotFound(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{recur: nil}, ordMktStub{}) // not found → 404
	r := httptest.NewRequest(http.MethodPost, "/recurring-orders/9/pause", nil)
	r.SetPathValue("id", "9")
	w := httptest.NewRecorder()
	h.RecurringOrderPause(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestRecurringOrderDelete_Success(t *testing.T) {
	owned := &order.RecurringOrder{ID: 9, UserID: 1}
	h := newOrderHandlers(&ordRepoStub{recur: owned}, ordMktStub{})
	r := httptest.NewRequest(http.MethodDelete, "/recurring-orders/9", nil)
	r.SetPathValue("id", "9")
	r = withRole(r, 1, "CLIENT")
	w := httptest.NewRecorder()
	h.RecurringOrderDelete(w, r)
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestOrderConfirm_NotFound(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{byID: nil}, ordMktStub{})
	r := httptest.NewRequest(http.MethodPost, "/orders/9/confirm", nil)
	r.SetPathValue("id", "9")
	w := httptest.NewRecorder()
	h.OrderConfirm(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestOrderCancel_NotFound(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{byID: nil}, ordMktStub{})
	r := httptest.NewRequest(http.MethodPost, "/orders/9/cancel", nil)
	r.SetPathValue("id", "9")
	w := httptest.NewRecorder()
	h.OrderCancel(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestOrderCancelSupervisor_NotFound(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{byID: nil}, ordMktStub{})
	r := httptest.NewRequest(http.MethodPut, "/orders/9/cancel", bytes.NewBufferString(`{}`))
	r.SetPathValue("id", "9")
	w := httptest.NewRecorder()
	h.OrderCancelSupervisor(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestOrderCancelSupervisor_BadID(t *testing.T) {
	h := &Handlers{app: &App{}}
	r := httptest.NewRequest(http.MethodPut, "/orders/bad/cancel", nil)
	r.SetPathValue("id", "bad")
	w := httptest.NewRecorder()
	h.OrderCancelSupervisor(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOrderApprove_NotFound(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{byID: nil}, ordMktStub{})
	r := httptest.NewRequest(http.MethodPut, "/orders/9/approve", nil)
	r.SetPathValue("id", "9")
	w := httptest.NewRecorder()
	h.OrderApprove(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestOrderDecline_NotFound(t *testing.T) {
	h := newOrderHandlers(&ordRepoStub{byID: nil}, ordMktStub{})
	r := httptest.NewRequest(http.MethodPut, "/orders/9/decline", nil)
	r.SetPathValue("id", "9")
	w := httptest.NewRecorder()
	h.OrderDecline(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestOrderConfirm_BadID(t *testing.T) {
	h := &Handlers{app: &App{}}
	r := httptest.NewRequest(http.MethodPost, "/orders/bad/confirm", nil)
	r.SetPathValue("id", "bad")
	w := httptest.NewRecorder()
	h.OrderConfirm(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}
