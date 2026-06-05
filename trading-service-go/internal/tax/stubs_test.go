package tax

import (
	"context"
	"errors"
	"time"

	"banka1/trading-service-go/internal/api"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// ============================ deps stubs ==================================

// stubOrderRepo is an orderReader backed by in-memory orders/transactions. The
// q (order.Querier) argument is ignored — the engine always passes Pool().
type stubOrderRepo struct {
	byDirection        map[string][]order.Order
	byDirectionErr     error
	byUserAndDirection map[int64][]order.Order
	byUserAndDirErr    error
	byUserID           map[int64][]order.Order
	byUserIDErr        error
	byUserIDIn         []order.Order
	byUserIDInErr      error
	byID               map[int64]*order.Order
	byIDErr            error
	txBetween          []order.Transaction
	txBetweenErr       error
	txBefore           []order.Transaction
	txBeforeErr        error
	findByIDCalledWith []int64
}

func (s *stubOrderRepo) Pool() *pgxpool.Pool { return nil }

func (s *stubOrderRepo) FindByDirection(_ context.Context, _ order.Querier, direction string) ([]order.Order, error) {
	return s.byDirection[direction], s.byDirectionErr
}
func (s *stubOrderRepo) FindByUserIDAndDirection(_ context.Context, _ order.Querier, userID int64, _ string) ([]order.Order, error) {
	return s.byUserAndDirection[userID], s.byUserAndDirErr
}
func (s *stubOrderRepo) FindByUserID(_ context.Context, _ order.Querier, userID int64) ([]order.Order, error) {
	return s.byUserID[userID], s.byUserIDErr
}
func (s *stubOrderRepo) FindByUserIDIn(_ context.Context, _ order.Querier, _ []int64) ([]order.Order, error) {
	return s.byUserIDIn, s.byUserIDInErr
}
func (s *stubOrderRepo) FindByID(_ context.Context, _ order.Querier, id int64) (*order.Order, error) {
	s.findByIDCalledWith = append(s.findByIDCalledWith, id)
	if s.byIDErr != nil {
		return nil, s.byIDErr
	}
	return s.byID[id], nil
}
func (s *stubOrderRepo) FindTransactionsByOrderIDsAndTimestampBetween(_ context.Context, _ order.Querier, _ []int64, _, _ time.Time) ([]order.Transaction, error) {
	return s.txBetween, s.txBetweenErr
}
func (s *stubOrderRepo) FindTransactionsByOrderIDsAndTimestampBefore(_ context.Context, _ order.Querier, _ []int64, _ time.Time) ([]order.Transaction, error) {
	return s.txBefore, s.txBeforeErr
}

// stubPortfolioRepo is a portfolioReader backed by a (user,listing) map.
type stubPortfolioRepo struct {
	byKey map[[2]int64]*portfolio.Portfolio
	err   error
}

func (s *stubPortfolioRepo) Pool() *pgxpool.Pool { return nil }
func (s *stubPortfolioRepo) FindByUserIDAndListingID(_ context.Context, _ portfolio.Querier, userID, listingID int64) (*portfolio.Portfolio, error) {
	if s.err != nil {
		return nil, s.err
	}
	return s.byKey[[2]int64{userID, listingID}], nil
}

// stubActuaryRepo is an actuaryReader.
type stubActuaryRepo struct {
	ids []int64
	err error
}

func (s *stubActuaryRepo) FindAllEmployeeIDs(_ context.Context) ([]int64, error) {
	return s.ids, s.err
}

// stubTaxRepo is a taxRepository with recorders for the write paths.
type stubTaxRepo struct {
	existsSellBuy    bool
	existsSellBuyErr error
	existsOtc        bool
	existsOtcErr     error
	byUserStatus     []TaxCharge
	byUserStatusErr  error
	all              []TaxCharge
	allErr           error
	insertErr        error
	updateChargedErr error
	markChargedErr   error
	deleteErr        error
	otcEntries       []OtcTaxEntry
	otcEntriesErr    error

	// recorders
	inserted        []*TaxCharge
	updateChargedID []int64
	markChargedID   []int64
	deletedID       []int64
	nextInsertID    int64
}

func (s *stubTaxRepo) ExistsBySellAndBuy(_ context.Context, _, _ int64) (bool, error) {
	return s.existsSellBuy, s.existsSellBuyErr
}
func (s *stubTaxRepo) ExistsByOtcContractID(_ context.Context, _ int64) (bool, error) {
	return s.existsOtc, s.existsOtcErr
}
func (s *stubTaxRepo) FindByUserIDAndStatus(_ context.Context, _ int64, _ string) ([]TaxCharge, error) {
	return s.byUserStatus, s.byUserStatusErr
}
func (s *stubTaxRepo) FindAll(_ context.Context) ([]TaxCharge, error) {
	return s.all, s.allErr
}
func (s *stubTaxRepo) Insert(_ context.Context, c *TaxCharge) error {
	if s.insertErr != nil {
		return s.insertErr
	}
	s.nextInsertID++
	c.ID = s.nextInsertID
	s.inserted = append(s.inserted, c)
	return nil
}
func (s *stubTaxRepo) UpdateCharged(_ context.Context, id int64, _ decimal.Decimal, _ time.Time) error {
	s.updateChargedID = append(s.updateChargedID, id)
	return s.updateChargedErr
}
func (s *stubTaxRepo) MarkCharged(_ context.Context, id int64, _ time.Time) error {
	s.markChargedID = append(s.markChargedID, id)
	return s.markChargedErr
}
func (s *stubTaxRepo) Delete(_ context.Context, id int64) error {
	s.deletedID = append(s.deletedID, id)
	return s.deleteErr
}
func (s *stubTaxRepo) LoadExercisedOtcTaxEntries(_ context.Context, _ time.Time) ([]OtcTaxEntry, error) {
	return s.otcEntries, s.otcEntriesErr
}

// stubMarket is a marketClient.
type stubMarket struct {
	listings    map[int64]*clients.StockListing
	listingErr  error
	convertResp *clients.ExchangeRate
	convertErr  error
}

func (s *stubMarket) GetListing(_ context.Context, id int64) (*clients.StockListing, error) {
	if s.listingErr != nil {
		return nil, s.listingErr
	}
	return s.listings[id], nil
}
func (s *stubMarket) CalculateWithoutCommission(_ context.Context, _, _ string, _ decimal.Decimal) (*clients.ExchangeRate, error) {
	return s.convertResp, s.convertErr
}

// stubAccount is an accountClient with recorders.
type stubAccount struct {
	details        map[int64]*clients.AccountDetails
	detailsErr     error
	govAccount     *clients.AccountDetails
	govErr         error
	transactionErr error
	defaultRsd     map[int64]string

	payments []clients.Payment
}

func (s *stubAccount) GetAccountDetailsByID(_ context.Context, id int64) (*clients.AccountDetails, error) {
	if s.detailsErr != nil {
		return nil, s.detailsErr
	}
	return s.details[id], nil
}
func (s *stubAccount) GetGovernmentBankAccountRsd(_ context.Context) (*clients.AccountDetails, error) {
	return s.govAccount, s.govErr
}
func (s *stubAccount) Transaction(_ context.Context, p clients.Payment) error {
	s.payments = append(s.payments, p)
	return s.transactionErr
}
func (s *stubAccount) GetDefaultRsdAccountNumberForOwner(_ context.Context, ownerID int64) string {
	return s.defaultRsd[ownerID]
}

// stubEmployee is an employeeClient.
type stubEmployee struct {
	employees map[int64]*clients.Employee
	err       error
}

func (s *stubEmployee) GetEmployee(_ context.Context, id int64) (*clients.Employee, error) {
	if s.err != nil {
		return nil, s.err
	}
	return s.employees[id], nil
}

// stubCustomer is a customerClient.
type stubCustomer struct {
	customers   map[int64]*clients.Customer
	customerErr error
	pages       []*clients.CustomerPage // returned in sequence, one per call
	searchErr   error
	pageIdx     int
}

func (s *stubCustomer) GetCustomer(_ context.Context, id int64) (*clients.Customer, error) {
	if s.customerErr != nil {
		return nil, s.customerErr
	}
	c, ok := s.customers[id]
	if !ok {
		return nil, errNotFound
	}
	return c, nil
}
func (s *stubCustomer) SearchCustomers(_ context.Context, _, _ *string, _, _ int) (*clients.CustomerPage, error) {
	if s.searchErr != nil {
		return nil, s.searchErr
	}
	if s.pageIdx >= len(s.pages) {
		return &clients.CustomerPage{}, nil
	}
	p := s.pages[s.pageIdx]
	s.pageIdx++
	return p, nil
}

var errNotFound = errors.New("not found")

// recordNotifier records TaxCollected payloads.
type recordNotifier struct {
	payloads []api.TaxCollectedPayload
}

func (n *recordNotifier) TaxCollected(_ context.Context, payload api.TaxCollectedPayload) {
	n.payloads = append(n.payloads, payload)
}

// ============================ helpers =====================================

// harness bundles the stubs and the wired Service for service-level tests.
type harness struct {
	tax       *stubTaxRepo
	order     *stubOrderRepo
	portfolio *stubPortfolioRepo
	actuary   *stubActuaryRepo
	market    *stubMarket
	account   *stubAccount
	employee  *stubEmployee
	customer  *stubCustomer
	notifier  *recordNotifier
	svc       *Service
}

func newHarness(rate string) *harness {
	h := &harness{
		tax:       &stubTaxRepo{},
		order:     &stubOrderRepo{},
		portfolio: &stubPortfolioRepo{},
		actuary:   &stubActuaryRepo{},
		market:    &stubMarket{},
		account:   &stubAccount{},
		employee:  &stubEmployee{},
		customer:  &stubCustomer{},
		notifier:  &recordNotifier{},
	}
	h.svc = NewServiceForTest(h.tax, h.order, h.portfolio, h.actuary, h.market,
		h.account, h.employee, h.customer, h.notifier, dec(rate), quietLogger())
	return h
}

func strptr(s string) *string { return &s }

func usdListing() *clients.StockListing {
	st := "STOCK"
	cur := "USD"
	return &clients.StockListing{ListingType: &st, CurrencyRaw: &cur}
}

func rsdListing() *clients.StockListing {
	st := "STOCK"
	cur := "Serbian Dinar"
	return &clients.StockListing{ListingType: &st, CurrencyRaw: &cur}
}

func nonStockListing() *clients.StockListing {
	t := "FUTURE"
	cur := "USD"
	return &clients.StockListing{ListingType: &t, CurrencyRaw: &cur}
}

func buyOrder(id, user, listing, account int64) order.Order {
	return order.Order{ID: id, UserID: user, ListingID: listing, AccountID: account, Direction: order.DirectionBuy}
}

func sellOrder(id, user, listing, account int64) order.Order {
	return order.Order{ID: id, UserID: user, ListingID: listing, AccountID: account, Direction: order.DirectionSell}
}

func tx(id, orderID int64, qty int, ppu string, ts time.Time) order.Transaction {
	return order.Transaction{ID: id, OrderID: orderID, Quantity: qty, PricePerUnit: dec(ppu), Timestamp: ts}
}

func fxRate(converted string) *clients.ExchangeRate {
	v := dec(converted)
	return &clients.ExchangeRate{ConvertedAmount: &v}
}

// ============================ repository fake =============================

// fakeQRow returns canned values on Scan.
type fakeQRow struct {
	vals    []any
	scanErr error
}

func (r *fakeQRow) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	return assignQ(dest, r.vals)
}

type fakeQRows struct {
	rows    [][]any
	idx     int
	scanErr error
	nextErr error
}

func (r *fakeQRows) Next() bool                                   { return r.idx < len(r.rows) }
func (r *fakeQRows) Close()                                       {}
func (r *fakeQRows) Err() error                                   { return r.nextErr }
func (r *fakeQRows) CommandTag() pgconn.CommandTag                { return pgconn.CommandTag{} }
func (r *fakeQRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *fakeQRows) Values() ([]any, error)                       { return nil, nil }
func (r *fakeQRows) RawValues() [][]byte                          { return nil }
func (r *fakeQRows) Conn() *pgx.Conn                              { return nil }
func (r *fakeQRows) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	row := r.rows[r.idx]
	r.idx++
	return assignQ(dest, row)
}

// assignQ copies src values into dest pointers, handling the concrete types the
// tax scanners use (int64, string, *string, time.Time, *time.Time, *int64, bool).
func assignQ(dest []any, src []any) error {
	for i, d := range dest {
		if i >= len(src) {
			break
		}
		s := src[i]
		switch dst := d.(type) {
		case *int64:
			if s == nil {
				*dst = 0
			} else {
				*dst = s.(int64)
			}
		case **int64:
			if s == nil {
				*dst = nil
			} else {
				switch v := s.(type) {
				case int64:
					*dst = &v
				case *int64:
					*dst = v
				}
			}
		case *string:
			if s == nil {
				*dst = ""
			} else {
				*dst = s.(string)
			}
		case **string:
			if s == nil {
				*dst = nil
			} else {
				switch v := s.(type) {
				case string:
					*dst = &v
				case *string:
					*dst = v
				}
			}
		case *int:
			if s == nil {
				*dst = 0
			} else {
				*dst = s.(int)
			}
		case *bool:
			if s == nil {
				*dst = false
			} else {
				*dst = s.(bool)
			}
		case *time.Time:
			if s == nil {
				*dst = time.Time{}
			} else {
				*dst = s.(time.Time)
			}
		case **time.Time:
			if s == nil {
				*dst = nil
			} else {
				switch v := s.(type) {
				case time.Time:
					*dst = &v
				case *time.Time:
					*dst = v
				}
			}
		default:
			// ignore
		}
	}
	return nil
}

// fakeQuerier serves prepared rows for Query/QueryRow and a tag/err for Exec.
type fakeQuerier struct {
	row      *fakeQRow
	rows     *fakeQRows
	queryErr error
	execErr  error
	execTag  pgconn.CommandTag
	lastSQL  string
	lastArgs []any
}

func (q *fakeQuerier) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	q.lastSQL = sql
	q.lastArgs = args
	return q.execTag, q.execErr
}
func (q *fakeQuerier) Query(_ context.Context, sql string, args ...any) (pgx.Rows, error) {
	q.lastSQL = sql
	q.lastArgs = args
	if q.queryErr != nil {
		return nil, q.queryErr
	}
	if q.rows != nil {
		return q.rows, nil
	}
	return &fakeQRows{}, nil
}
func (q *fakeQuerier) QueryRow(_ context.Context, sql string, args ...any) pgx.Row {
	q.lastSQL = sql
	q.lastArgs = args
	if q.row != nil {
		return q.row
	}
	return &fakeQRow{scanErr: pgx.ErrNoRows}
}
