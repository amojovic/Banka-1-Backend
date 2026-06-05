package order

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"time"

	"banka1/trading-service-go/internal/actuary"
	"banka1/trading-service-go/internal/api"
	"banka1/trading-service-go/internal/audit"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/shopspring/decimal"
)

// ---------------------------------------------------------------------------
// In-package fakes for the deps.go collaborator interfaces. These let the
// service logic run with no Postgres, broker, or HTTP clients. A synchronous
// txRunner (fakeTxRunner) calls fn(nil) so transactional paths execute inline.
// ---------------------------------------------------------------------------

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// fakeTxRunner executes fn inline with a nil tx; the repo/portfolio/actuary
// methods ignore the concrete tx (they take a Querier and the fakes don't use
// it). recordErr/forceErr let a test fail the whole tx.
func fakeTxRunner(ctx context.Context, _ pgx.TxOptions, fn func(pgx.Tx) error) error {
	return fn(nil)
}

// --- fake order repository ---------------------------------------------------

type fakeOrderRepo struct {
	orders       map[int64]*Order
	all          []Order
	byStatus     map[string][]Order
	byUser       map[int64][]Order
	transactions map[int64][]Transaction
	recurring    map[int64]*RecurringOrder
	recurByUser  map[int64][]RecurringOrder
	due          []RecurringOrder

	nextID      int64
	insertErr   error
	updateErr   error
	findErr     error
	statusErr   error
	allErr      error
	userErr     error
	txnErr      error
	insertTxErr error

	insertRecurErr error
	findRecurErr   error
	dueErr         error
	setActiveErr   error
	deleteRecurErr error
	updateNextErr  error
	recurByUserErr error

	updated      []Order
	insertedTxns []Transaction
	nextRunSet   map[int64]time.Time
}

func newFakeOrderRepo() *fakeOrderRepo {
	return &fakeOrderRepo{
		orders:       map[int64]*Order{},
		byStatus:     map[string][]Order{},
		byUser:       map[int64][]Order{},
		transactions: map[int64][]Transaction{},
		recurring:    map[int64]*RecurringOrder{},
		recurByUser:  map[int64][]RecurringOrder{},
		nextRunSet:   map[int64]time.Time{},
	}
}

func (f *fakeOrderRepo) Pool() *pgxpool.Pool { return nil }

func (f *fakeOrderRepo) Insert(_ context.Context, _ Querier, o *Order) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	f.nextID++
	o.ID = f.nextID
	if o.CreatedAt.IsZero() {
		o.CreatedAt = time.Now()
	}
	o.LastModification = time.Now()
	cp := *o
	f.orders[o.ID] = &cp
	return nil
}

func (f *fakeOrderRepo) Update(_ context.Context, _ Querier, o *Order) error {
	if f.updateErr != nil {
		return f.updateErr
	}
	o.LastModification = time.Now()
	cp := *o
	f.orders[o.ID] = &cp
	f.updated = append(f.updated, *o)
	return nil
}

func (f *fakeOrderRepo) FindByID(_ context.Context, _ Querier, id int64) (*Order, error) {
	if f.findErr != nil {
		return nil, f.findErr
	}
	o, ok := f.orders[id]
	if !ok {
		return nil, nil
	}
	cp := *o
	return &cp, nil
}

func (f *fakeOrderRepo) FindByIDForUpdate(ctx context.Context, q Querier, id int64) (*Order, error) {
	return f.FindByID(ctx, q, id)
}

func (f *fakeOrderRepo) FindAll(_ context.Context, _ Querier) ([]Order, error) {
	if f.allErr != nil {
		return nil, f.allErr
	}
	return f.all, nil
}

func (f *fakeOrderRepo) FindByStatus(_ context.Context, _ Querier, status string) ([]Order, error) {
	if f.statusErr != nil {
		return nil, f.statusErr
	}
	return f.byStatus[status], nil
}

func (f *fakeOrderRepo) FindByUserID(_ context.Context, _ Querier, userID int64) ([]Order, error) {
	if f.userErr != nil {
		return nil, f.userErr
	}
	return f.byUser[userID], nil
}

func (f *fakeOrderRepo) InsertTransaction(_ context.Context, _ Querier, t *Transaction) error {
	if f.insertTxErr != nil {
		return f.insertTxErr
	}
	f.insertedTxns = append(f.insertedTxns, *t)
	f.transactions[t.OrderID] = append(f.transactions[t.OrderID], *t)
	return nil
}

func (f *fakeOrderRepo) FindTransactionsByOrderID(_ context.Context, _ Querier, orderID int64) ([]Transaction, error) {
	if f.txnErr != nil {
		return nil, f.txnErr
	}
	return f.transactions[orderID], nil
}

func (f *fakeOrderRepo) FindRecurringByUserID(_ context.Context, _ Querier, userID int64) ([]RecurringOrder, error) {
	if f.recurByUserErr != nil {
		return nil, f.recurByUserErr
	}
	return f.recurByUser[userID], nil
}

func (f *fakeOrderRepo) FindRecurringByID(_ context.Context, _ Querier, id int64) (*RecurringOrder, error) {
	if f.findRecurErr != nil {
		return nil, f.findRecurErr
	}
	ro, ok := f.recurring[id]
	if !ok {
		return nil, nil
	}
	cp := *ro
	return &cp, nil
}

func (f *fakeOrderRepo) FindDueRecurring(_ context.Context, _ Querier, _ time.Time) ([]RecurringOrder, error) {
	if f.dueErr != nil {
		return nil, f.dueErr
	}
	return f.due, nil
}

func (f *fakeOrderRepo) InsertRecurring(_ context.Context, _ Querier, ro *RecurringOrder) error {
	if f.insertRecurErr != nil {
		return f.insertRecurErr
	}
	f.nextID++
	ro.ID = f.nextID
	ro.CreatedAt = time.Now()
	cp := *ro
	f.recurring[ro.ID] = &cp
	return nil
}

func (f *fakeOrderRepo) SetRecurringActive(_ context.Context, _ Querier, id int64, active bool) (bool, error) {
	if f.setActiveErr != nil {
		return false, f.setActiveErr
	}
	if ro, ok := f.recurring[id]; ok {
		ro.Active = active
		return true, nil
	}
	return false, nil
}

func (f *fakeOrderRepo) DeleteRecurring(_ context.Context, _ Querier, id int64) error {
	if f.deleteRecurErr != nil {
		return f.deleteRecurErr
	}
	delete(f.recurring, id)
	return nil
}

func (f *fakeOrderRepo) UpdateRecurringNextRun(_ context.Context, _ Querier, id int64, nextRun time.Time) error {
	if f.updateNextErr != nil {
		return f.updateNextErr
	}
	f.nextRunSet[id] = nextRun
	return nil
}

// --- fake portfolios ---------------------------------------------------------

type fakePortfolios struct {
	positions map[[2]int64]*portfolio.Portfolio // key {userID, listingID}

	findErr        error
	findUpdErr     error
	updReservedErr error
	updSellErr     error
	insertErr      error
	updQtyErr      error
	deleteErr      error

	reservedUpdates []int
	inserted        bool
	deleted         bool
}

func newFakePortfolios() *fakePortfolios {
	return &fakePortfolios{positions: map[[2]int64]*portfolio.Portfolio{}}
}

func (f *fakePortfolios) Pool() *pgxpool.Pool { return nil }

func (f *fakePortfolios) FindByUserIDAndListingID(_ context.Context, _ portfolio.Querier, userID, listingID int64) (*portfolio.Portfolio, error) {
	if f.findErr != nil {
		return nil, f.findErr
	}
	p, ok := f.positions[[2]int64{userID, listingID}]
	if !ok {
		return nil, nil
	}
	cp := *p
	return &cp, nil
}

func (f *fakePortfolios) FindByUserIDAndListingIDForUpdate(_ context.Context, _ portfolio.Querier, userID, listingID int64) (*portfolio.Portfolio, error) {
	if f.findUpdErr != nil {
		return nil, f.findUpdErr
	}
	p, ok := f.positions[[2]int64{userID, listingID}]
	if !ok {
		return nil, nil
	}
	cp := *p
	return &cp, nil
}

func (f *fakePortfolios) UpdateReservedQuantity(_ context.Context, _ portfolio.Querier, id int64, reserved int) error {
	if f.updReservedErr != nil {
		return f.updReservedErr
	}
	f.reservedUpdates = append(f.reservedUpdates, reserved)
	for _, p := range f.positions {
		if p.ID == id {
			p.ReservedQuantity = reserved
		}
	}
	return nil
}

func (f *fakePortfolios) UpdateSellPosition(_ context.Context, _ portfolio.Querier, id int64, quantity, reserved, public int) error {
	if f.updSellErr != nil {
		return f.updSellErr
	}
	for _, p := range f.positions {
		if p.ID == id {
			p.Quantity = quantity
			p.ReservedQuantity = reserved
			p.PublicQuantity = public
		}
	}
	return nil
}

func (f *fakePortfolios) Insert(_ context.Context, _ portfolio.Querier, userID, listingID int64, listingType string, quantity int, avg decimal.Decimal) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	f.inserted = true
	f.positions[[2]int64{userID, listingID}] = &portfolio.Portfolio{
		ID: 9000, UserID: userID, ListingID: listingID, ListingType: listingType,
		Quantity: quantity, AveragePurchasePrice: avg,
	}
	return nil
}

func (f *fakePortfolios) UpdateQuantityAndAvg(_ context.Context, _ portfolio.Querier, id int64, quantity int, avg decimal.Decimal) error {
	if f.updQtyErr != nil {
		return f.updQtyErr
	}
	for _, p := range f.positions {
		if p.ID == id {
			p.Quantity = quantity
			p.AveragePurchasePrice = avg
		}
	}
	return nil
}

func (f *fakePortfolios) Delete(_ context.Context, _ portfolio.Querier, id int64) error {
	if f.deleteErr != nil {
		return f.deleteErr
	}
	f.deleted = true
	return nil
}

// --- fake actuaries ----------------------------------------------------------

type fakeActuaries struct {
	infos        map[int64]*actuary.ActuaryInfo
	idsIn        map[int64]bool
	findErr      error
	findUpdErr   error
	idsInErr     error
	updResErr    error
	updResUsdErr error

	reservedSet  map[int64]decimal.Decimal
	reservedUsed map[int64][2]decimal.Decimal
}

func newFakeActuaries() *fakeActuaries {
	return &fakeActuaries{
		infos:        map[int64]*actuary.ActuaryInfo{},
		idsIn:        map[int64]bool{},
		reservedSet:  map[int64]decimal.Decimal{},
		reservedUsed: map[int64][2]decimal.Decimal{},
	}
}

func (f *fakeActuaries) FindByEmployeeID(_ context.Context, employeeID int64) (*actuary.ActuaryInfo, error) {
	if f.findErr != nil {
		return nil, f.findErr
	}
	info, ok := f.infos[employeeID]
	if !ok {
		return nil, nil
	}
	cp := *info
	return &cp, nil
}

func (f *fakeActuaries) FindByEmployeeIDForUpdate(_ context.Context, _ actuary.Querier, employeeID int64) (*actuary.ActuaryInfo, error) {
	if f.findUpdErr != nil {
		return nil, f.findUpdErr
	}
	info, ok := f.infos[employeeID]
	if !ok {
		return nil, nil
	}
	cp := *info
	return &cp, nil
}

func (f *fakeActuaries) FindEmployeeIDsIn(_ context.Context, ids []int64) (map[int64]bool, error) {
	if f.idsInErr != nil {
		return nil, f.idsInErr
	}
	out := map[int64]bool{}
	for _, id := range ids {
		if f.idsIn[id] {
			out[id] = true
		}
	}
	return out, nil
}

func (f *fakeActuaries) UpdateReservedLimit(_ context.Context, _ actuary.Querier, employeeID int64, reserved decimal.Decimal) error {
	if f.updResErr != nil {
		return f.updResErr
	}
	f.reservedSet[employeeID] = reserved
	if info, ok := f.infos[employeeID]; ok {
		info.ReservedLimit = reserved
	}
	return nil
}

func (f *fakeActuaries) UpdateReservedAndUsedLimit(_ context.Context, _ actuary.Querier, employeeID int64, reserved, used decimal.Decimal) error {
	if f.updResUsdErr != nil {
		return f.updResUsdErr
	}
	f.reservedUsed[employeeID] = [2]decimal.Decimal{reserved, used}
	if info, ok := f.infos[employeeID]; ok {
		info.ReservedLimit = reserved
		info.UsedLimit = used
	}
	return nil
}

// --- fake market -------------------------------------------------------------

type fakeMarket struct {
	listings      map[int64]*clients.StockListing
	listingErr    error
	exchange      *clients.ExchangeStatus
	exchangeErr   error
	calc          *clients.ExchangeRate
	calcErr       error
	calcNoComm    *clients.ExchangeRate
	calcNoCommErr error

	refreshCalls int
}

func newFakeMarket() *fakeMarket {
	return &fakeMarket{listings: map[int64]*clients.StockListing{}}
}

func (f *fakeMarket) GetListing(_ context.Context, id int64) (*clients.StockListing, error) {
	if f.listingErr != nil {
		return nil, f.listingErr
	}
	l, ok := f.listings[id]
	if !ok {
		return nil, errors.New("listing not found")
	}
	return l, nil
}

func (f *fakeMarket) RefreshListing(_ context.Context, _ int64) { f.refreshCalls++ }

func (f *fakeMarket) GetExchangeStatus(_ context.Context, _ int64) (*clients.ExchangeStatus, error) {
	if f.exchangeErr != nil {
		return nil, f.exchangeErr
	}
	if f.exchange == nil {
		return &clients.ExchangeStatus{}, nil
	}
	return f.exchange, nil
}

func (f *fakeMarket) Calculate(_ context.Context, _, _ string, _ decimal.Decimal) (*clients.ExchangeRate, error) {
	return f.calc, f.calcErr
}

func (f *fakeMarket) CalculateWithoutCommission(_ context.Context, _, _ string, _ decimal.Decimal) (*clients.ExchangeRate, error) {
	return f.calcNoComm, f.calcNoCommErr
}

// --- fake account ------------------------------------------------------------

type fakeAccount struct {
	details       map[int64]*clients.AccountDetails
	detailsErr    map[int64]error
	bank          *clients.BankAccount
	bankErr       error
	txnErr        error
	transferErr   error
	exBuyErr      error
	exSellErr     error
	marginBuyErr  error
	marginSellErr error

	transactions []clients.Payment
	transfers    []clients.Payment
	exBuys       []clients.OneSidedTransaction
	exSells      []clients.OneSidedTransaction
	marginBuys   []decimal.Decimal
	marginSells  []decimal.Decimal
}

func newFakeAccount() *fakeAccount {
	return &fakeAccount{details: map[int64]*clients.AccountDetails{}, detailsErr: map[int64]error{}}
}

func (f *fakeAccount) GetAccountDetailsByID(_ context.Context, accountID int64) (*clients.AccountDetails, error) {
	if e, ok := f.detailsErr[accountID]; ok {
		return nil, e
	}
	d, ok := f.details[accountID]
	if !ok {
		return nil, clients.ErrNotFound
	}
	return d, nil
}

func (f *fakeAccount) GetBankAccount(_ context.Context, _ string) (*clients.BankAccount, error) {
	if f.bankErr != nil {
		return nil, f.bankErr
	}
	if f.bank == nil {
		id := int64(0)
		return &clients.BankAccount{ID: &id}, nil
	}
	return f.bank, nil
}

func (f *fakeAccount) Transaction(_ context.Context, p clients.Payment) error {
	f.transactions = append(f.transactions, p)
	return f.txnErr
}

func (f *fakeAccount) Transfer(_ context.Context, p clients.Payment) error {
	f.transfers = append(f.transfers, p)
	return f.transferErr
}

func (f *fakeAccount) ExchangeBuy(_ context.Context, r clients.OneSidedTransaction) error {
	f.exBuys = append(f.exBuys, r)
	return f.exBuyErr
}

func (f *fakeAccount) ExchangeSell(_ context.Context, r clients.OneSidedTransaction) error {
	f.exSells = append(f.exSells, r)
	return f.exSellErr
}

func (f *fakeAccount) StockBuyMarginTransaction(_ context.Context, _ int64, amount decimal.Decimal) error {
	f.marginBuys = append(f.marginBuys, amount)
	return f.marginBuyErr
}

func (f *fakeAccount) StockSellMarginTransaction(_ context.Context, _ int64, amount decimal.Decimal) error {
	f.marginSells = append(f.marginSells, amount)
	return f.marginSellErr
}

// --- fake employees / customers ----------------------------------------------

type fakeEmployees struct {
	employees map[int64]*clients.Employee
	err       error
}

func (f *fakeEmployees) GetEmployee(_ context.Context, id int64) (*clients.Employee, error) {
	if f.err != nil {
		return nil, f.err
	}
	e, ok := f.employees[id]
	if !ok {
		return nil, nil
	}
	return e, nil
}

type fakeCustomers struct {
	customers map[int64]*clients.Customer
	err       error
}

func (f *fakeCustomers) GetCustomer(_ context.Context, id int64) (*clients.Customer, error) {
	if f.err != nil {
		return nil, f.err
	}
	c, ok := f.customers[id]
	if !ok {
		return nil, nil
	}
	return c, nil
}

// --- fake auditor ------------------------------------------------------------

type fakeAuditor struct {
	events []audit.Event
}

func (f *fakeAuditor) RecordBestEffort(_ context.Context, ev audit.Event) {
	f.events = append(f.events, ev)
}

// --- fake notifier (records routing) -----------------------------------------

type fakeNotifier struct {
	approved, declined, created, done, partial, autoCancelled int
	recurringSkipped                                          int
}

func (f *fakeNotifier) OrderApproved(context.Context, api.OrderNotificationPayload)    { f.approved++ }
func (f *fakeNotifier) OrderDeclined(context.Context, api.OrderNotificationPayload)    { f.declined++ }
func (f *fakeNotifier) OrderCreated(context.Context, api.OrderNotificationPayload)     { f.created++ }
func (f *fakeNotifier) OrderDone(context.Context, api.OrderNotificationPayload)        { f.done++ }
func (f *fakeNotifier) OrderPartialFill(context.Context, api.OrderNotificationPayload) { f.partial++ }
func (f *fakeNotifier) OrderAutoCancelled(context.Context, api.OrderNotificationPayload) {
	f.autoCancelled++
}
func (f *fakeNotifier) RecurringOrderSkipped(context.Context, api.RecurringOrderSkippedNotification) {
	f.recurringSkipped++
}

// --- fake funds callback -----------------------------------------------------

type fakeFunds struct {
	addErr   error
	debitErr error
	holdings int
	debits   int
}

func (f *fakeFunds) AddHolding(context.Context, int64, string, int, decimal.Decimal) error {
	f.holdings++
	return f.addErr
}

func (f *fakeFunds) DebitLiquidity(context.Context, int64, decimal.Decimal, string) error {
	f.debits++
	return f.debitErr
}

// harness bundles the fakes and the assembled Service.
type harness struct {
	repo       *fakeOrderRepo
	portfolios *fakePortfolios
	actuaries  *fakeActuaries
	market     *fakeMarket
	account    *fakeAccount
	employees  *fakeEmployees
	customers  *fakeCustomers
	auditor    *fakeAuditor
	notifier   *fakeNotifier
	funds      *fakeFunds
	svc        *Service
}

// newHarness assembles a Service backed entirely by in-memory fakes and a
// synchronous txRunner. The Worker is replaced with one whose process func is a
// no-op (so ExecuteOrderAsync after a tx commit does not schedule real work);
// tests that exercise the worker build their own.
func newHarness() *harness {
	h := &harness{
		repo:       newFakeOrderRepo(),
		portfolios: newFakePortfolios(),
		actuaries:  newFakeActuaries(),
		market:     newFakeMarket(),
		account:    newFakeAccount(),
		employees:  &fakeEmployees{employees: map[int64]*clients.Employee{}},
		customers:  &fakeCustomers{customers: map[int64]*clients.Customer{}},
		auditor:    &fakeAuditor{},
		notifier:   &fakeNotifier{},
		funds:      &fakeFunds{},
	}
	s := &Service{
		repo:       h.repo,
		portfolios: h.portfolios,
		actuaries:  h.actuaries,
		market:     h.market,
		account:    h.account,
		employees:  h.employees,
		customers:  h.customers,
		notifier:   h.notifier,
		funds:      h.funds,
		auditor:    h.auditor,
		runInTx:    fakeTxRunner,
		logger:     testLogger(),
	}
	// A worker with a no-op process so scheduled ticks never run real logic.
	s.worker = NewWorker(func(int64) {}, s.logger, 1)
	h.svc = s
	return h
}

// listing builds a StockListing with sane defaults for the happy path.
func listing(id int64) *clients.StockListing {
	exch := int64(1)
	return &clients.StockListing{
		ID:           id,
		Ask:          dp("10"),
		Bid:          dp("9"),
		Price:        dp("10"),
		ContractSize: ip(1),
		ListingType:  sp("STOCK"),
		Ticker:       sp("AAPL"),
		Name:         sp("Apple"),
		ExchangeID:   &exch,
	}
}

// acct builds AccountDetails with a balance and currency.
func acct(owner int64, currency, balance string) *clients.AccountDetails {
	bal := decimal.RequireFromString(balance)
	num := "ACC" + currency
	return &clients.AccountDetails{
		AccountNumber: &num,
		Currency:      &currency,
		OwnerID:       &owner,
		Balance:       &bal,
	}
}

// compile-time interface assertions.
var (
	_ orderRepo       = (*fakeOrderRepo)(nil)
	_ orderPortfolios = (*fakePortfolios)(nil)
	_ orderActuaries  = (*fakeActuaries)(nil)
	_ orderMarket     = (*fakeMarket)(nil)
	_ orderAccount    = (*fakeAccount)(nil)
	_ orderEmployees  = (*fakeEmployees)(nil)
	_ orderCustomers  = (*fakeCustomers)(nil)
	_ orderAuditor    = (*fakeAuditor)(nil)
	_ Notifier        = (*fakeNotifier)(nil)
	_ FundCallback    = (*fakeFunds)(nil)
)
