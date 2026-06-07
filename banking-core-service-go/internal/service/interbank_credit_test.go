package service

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"banka1/banking-core-service-go/internal/config"
	"banka1/banking-core-service-go/internal/decimal"
)

// ---------------------------------------------------------------------------
// CreditMonas tests
//
// These exercise the dedicated idempotent, FX-aware credit primitive used by the
// inter-bank 2PC commit step. They run against a minimal in-memory database/sql
// driver fake (same technique as internal_account_controller_test.go) scoped to
// the statements CreditMonas issues.
// ---------------------------------------------------------------------------

// TestCreditMonas_SameCurrency_CreditsRecipient verifies a plain same-currency
// credit increases the recipient balance by the posting amount.
func TestCreditMonas_SameCurrency_CreditsRecipient(t *testing.T) {
	store := newCreditFakeStore()
	store.addAccount(creditFakeAccount{id: 1, number: "111000000000000001", ownerID: 7, currency: "USD", available: "100", booked: decimal.MustParse("100")})
	db := openCreditFakeDB(t, store)
	svc := NewInterbankService(db, NewAccountService(db, config.Config{}, nil), nil)

	resp, err := svc.CreditMonas(context.Background(), CreditMonasRequest{
		AccountNum: "111000000000000001", Currency: "USD",
		Amount: decimal.MustParse("250"), TxIDRouting: 222, TxIDLocal: "tx-1",
	})
	if err != nil {
		t.Fatalf("CreditMonas: %v", err)
	}
	if resp.Idempotent {
		t.Errorf("first call should not be idempotent")
	}
	if got := resp.CreditedAmount.String(); got != "250" {
		t.Errorf("creditedAmount=%s want 250", got)
	}
	if got := store.account("111000000000000001").booked.String(); got != "350" {
		t.Errorf("recipient booked=%s want 350 (100+250)", got)
	}
}

// TestCreditMonas_Idempotent_DoubleCallSingleCredit verifies a replay with the
// same (routing, local) key is a no-op: the balance is credited exactly once.
func TestCreditMonas_Idempotent_DoubleCallSingleCredit(t *testing.T) {
	store := newCreditFakeStore()
	store.addAccount(creditFakeAccount{id: 1, number: "111000000000000001", ownerID: 7, currency: "USD", available: "0", booked: decimal.Zero})
	db := openCreditFakeDB(t, store)
	svc := NewInterbankService(db, NewAccountService(db, config.Config{}, nil), nil)

	req := CreditMonasRequest{
		AccountNum: "111000000000000001", Currency: "USD",
		Amount: decimal.MustParse("250"), TxIDRouting: 222, TxIDLocal: "tx-dup",
	}
	if _, err := svc.CreditMonas(context.Background(), req); err != nil {
		t.Fatalf("first CreditMonas: %v", err)
	}
	resp2, err := svc.CreditMonas(context.Background(), req)
	if err != nil {
		t.Fatalf("second CreditMonas: %v", err)
	}
	if !resp2.Idempotent {
		t.Errorf("second call should be flagged idempotent")
	}
	if got := resp2.CreditedAmount.String(); got != "250" {
		t.Errorf("replay creditedAmount=%s want original 250", got)
	}
	if got := store.account("111000000000000001").booked.String(); got != "250" {
		t.Errorf("recipient booked=%s want 250 (credited exactly once)", got)
	}
}

// TestCreditMonas_FX_ConvertsToAccountCurrency verifies that when the recipient's
// account currency differs from the posting currency, the converted amount (from
// the FX seam) is credited and the bank pool of the credited currency is debited.
func TestCreditMonas_FX_ConvertsToAccountCurrency(t *testing.T) {
	store := newCreditFakeStore()
	// Recipient holds RSD; posting arrives in EUR.
	store.addAccount(creditFakeAccount{id: 1, number: "111000000000000001", ownerID: 7, currency: "RSD", available: "0", booked: decimal.Zero})
	// Bank pool account for RSD (owner -1).
	store.addAccount(creditFakeAccount{id: 2, number: "111000110000000002", ownerID: -1, currency: "RSD", available: "1000000", booked: decimal.MustParse("1000000")})
	db := openCreditFakeDB(t, store)
	// Converter: 100 EUR -> 11700 RSD.
	conv := &fakeMonasConverter{toAmount: decimal.MustParse("11700")}
	svc := NewInterbankService(db, NewAccountService(db, config.Config{}, nil), conv)

	resp, err := svc.CreditMonas(context.Background(), CreditMonasRequest{
		AccountNum: "111000000000000001", Currency: "EUR",
		Amount: decimal.MustParse("100"), TxIDRouting: 222, TxIDLocal: "tx-fx",
	})
	if err != nil {
		t.Fatalf("CreditMonas FX: %v", err)
	}
	if conv.calls != 1 {
		t.Errorf("expected exactly 1 conversion call, got %d", conv.calls)
	}
	if conv.from != "EUR" || conv.to != "RSD" {
		t.Errorf("conversion from=%s to=%s, want EUR->RSD", conv.from, conv.to)
	}
	if got := resp.CreditedAmount.String(); got != "11700" {
		t.Errorf("creditedAmount=%s want 11700 (converted)", got)
	}
	if got := store.account("111000000000000001").booked.String(); got != "11700" {
		t.Errorf("recipient booked=%s want 11700", got)
	}
	if got := store.account("111000110000000002").booked.String(); got != "988300" {
		t.Errorf("bank pool booked=%s want 988300 (1000000-11700)", got)
	}
}

// TestCreditMonas_RejectsNonPositiveAmount guards the basic validation contract.
func TestCreditMonas_RejectsNonPositiveAmount(t *testing.T) {
	store := newCreditFakeStore()
	db := openCreditFakeDB(t, store)
	svc := NewInterbankService(db, NewAccountService(db, config.Config{}, nil), nil)
	if _, err := svc.CreditMonas(context.Background(), CreditMonasRequest{
		AccountNum: "111000000000000001", Currency: "USD",
		Amount: decimal.Zero, TxIDRouting: 222, TxIDLocal: "tx-bad",
	}); err == nil {
		t.Fatalf("expected error for non-positive amount")
	}
}

// ---------------------------------------------------------------------------
// fakeMonasConverter
// ---------------------------------------------------------------------------

type fakeMonasConverter struct {
	toAmount  decimal.Decimal
	from, to  string
	calls     int
	forcedErr error
}

func (f *fakeMonasConverter) ConvertNoCommission(_ context.Context, amount decimal.Decimal, from, to string) (ConversionResponse, error) {
	f.calls++
	f.from, f.to = from, to
	if f.forcedErr != nil {
		return ConversionResponse{}, f.forcedErr
	}
	return ConversionResponse{FromCurrency: from, ToCurrency: to, FromAmount: amount, ToAmount: f.toAmount}, nil
}

// ---------------------------------------------------------------------------
// In-memory database/sql driver fake scoped to CreditMonas
// ---------------------------------------------------------------------------

type creditFakeAccount struct {
	id        int64
	number    string
	ownerID   int64
	currency  string
	available string          // raspolozivo_stanje seed (string; not mutated by these tests)
	booked    decimal.Decimal // stanje, mutated by credits/debits
}

type creditFakeStore struct {
	mu         sync.Mutex
	byNumber   map[string]*creditFakeAccount
	creditedTx map[string]CreditMonasResponse // key: routing|local
}

func newCreditFakeStore() *creditFakeStore {
	return &creditFakeStore{byNumber: map[string]*creditFakeAccount{}, creditedTx: map[string]CreditMonasResponse{}}
}

// addAccount registers an account. The booked field is seeded from the booked arg
// passed via creditFakeAccount.booked already being set by the caller helper below.
func (s *creditFakeStore) addAccount(a creditFakeAccount) {
	s.byNumber[a.number] = &a
}

func (s *creditFakeStore) account(number string) *creditFakeAccount {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.byNumber[number]
}

func txCreditKey(routing int64, local string) string {
	return strings.Join([]string{decimal.NewFromInt(routing).String(), local}, "|")
}

const creditFakeDriverName = "interbank-credit-fake"

var (
	creditFakeDriverOnce sync.Once
	creditFakeSeq        atomic.Int64
	creditFakeMu         sync.Mutex
	creditFakeStores     = map[string]*creditFakeStore{}
)

func openCreditFakeDB(t *testing.T, store *creditFakeStore) *sql.DB {
	t.Helper()
	creditFakeDriverOnce.Do(func() { sql.Register(creditFakeDriverName, creditFakeDriver{}) })
	dsn := "credit-fake-" + decimal.NewFromInt(creditFakeSeq.Add(1)).String()
	creditFakeMu.Lock()
	creditFakeStores[dsn] = store
	creditFakeMu.Unlock()
	db, err := sql.Open(creditFakeDriverName, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		creditFakeMu.Lock()
		delete(creditFakeStores, dsn)
		creditFakeMu.Unlock()
		_ = db.Close()
	})
	return db
}

type creditFakeDriver struct{}

func (creditFakeDriver) Open(name string) (driver.Conn, error) {
	creditFakeMu.Lock()
	store := creditFakeStores[name]
	creditFakeMu.Unlock()
	if store == nil {
		return nil, errors.New("credit fake: unknown dsn " + name)
	}
	return &creditFakeConn{store: store}, nil
}

type creditFakeConn struct{ store *creditFakeStore }

func (c *creditFakeConn) Prepare(string) (driver.Stmt, error) { return nil, driver.ErrSkip }
func (c *creditFakeConn) Close() error                        { return nil }
func (c *creditFakeConn) Begin() (driver.Tx, error)           { return creditFakeTx{}, nil }
func (c *creditFakeConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	return creditFakeTx{}, nil
}

type creditFakeTx struct{}

func (creditFakeTx) Commit() error   { return nil }
func (creditFakeTx) Rollback() error { return nil }

func (c *creditFakeConn) QueryContext(_ context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	c.store.mu.Lock()
	defer c.store.mu.Unlock()
	q := normalize(query)
	switch {
	case strings.Contains(q, "from interbank_credits"):
		// loadCreditForUpdate: args = (routing, local)
		key := txCreditKey(asInt(args[0].Value), asString(args[1].Value))
		if r, ok := c.store.creditedTx[key]; ok {
			return &creditRowsCredit{resp: r, has: true}, nil
		}
		return &creditRowsCredit{}, nil
	case strings.Contains(q, "where a.broj_racuna"):
		acc := c.store.byNumber[asString(args[0].Value)]
		return accountRows(acc), nil
	case strings.Contains(q, "where a.vlasnik") && strings.Contains(q, "c.oznaka"):
		ownerID := asInt(args[0].Value)
		currency := asString(args[1].Value)
		for _, a := range c.store.byNumber {
			if a.ownerID == ownerID && a.currency == currency {
				return accountRows(a), nil
			}
		}
		return accountRows(nil), nil
	}
	return nil, errors.New("credit fake: unhandled query: " + q)
}

func (c *creditFakeConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	c.store.mu.Lock()
	defer c.store.mu.Unlock()
	q := normalize(query)
	switch {
	case strings.Contains(q, "update account_table") && strings.Contains(q, "stanje = stanje + $1"):
		// CreditTx: args = (amount, id)
		amt := decimal.MustParse(asString(args[0].Value))
		id := asInt(args[1].Value)
		if a := c.byID(id); a != nil {
			a.booked = a.booked.Add(amt)
		}
		return driver.RowsAffected(1), nil
	case strings.Contains(q, "update account_table") && strings.Contains(q, "stanje = stanje - $1"):
		// DebitTx: args = (amount, id)
		amt := decimal.MustParse(asString(args[0].Value))
		id := asInt(args[1].Value)
		if a := c.byID(id); a != nil {
			a.booked = a.booked.Sub(amt)
		}
		return driver.RowsAffected(1), nil
	case strings.Contains(q, "insert into interbank_credits"):
		// args: routing, local, accountNumber, postingCcy, postingAmt, creditedCcy, creditedAmt
		key := txCreditKey(asInt(args[0].Value), asString(args[1].Value))
		if _, exists := c.store.creditedTx[key]; exists {
			return nil, errors.New("duplicate key uk_interbank_credits_tx")
		}
		c.store.creditedTx[key] = CreditMonasResponse{
			AccountNumber:    asString(args[2].Value),
			PostingCurrency:  asString(args[3].Value),
			PostingAmount:    decimal.MustParse(asString(args[4].Value)),
			CreditedCurrency: asString(args[5].Value),
			CreditedAmount:   decimal.MustParse(asString(args[6].Value)),
		}
		return driver.RowsAffected(1), nil
	}
	return driver.RowsAffected(0), nil
}

func (c *creditFakeConn) byID(id int64) *creditFakeAccount {
	for _, a := range c.store.byNumber {
		if a.id == id {
			return a
		}
	}
	return nil
}

func normalize(q string) string { return strings.ToLower(strings.Join(strings.Fields(q), " ")) }

func asString(v driver.Value) string {
	switch t := v.(type) {
	case string:
		return t
	case []byte:
		return string(t)
	}
	if d, ok := v.(interface{ String() string }); ok {
		return d.String()
	}
	return ""
}

func asInt(v driver.Value) int64 {
	switch t := v.(type) {
	case int64:
		return t
	case int:
		return int64(t)
	}
	return 0
}

// ---------------------------------------------------------------------------
// driver.Rows implementations
// ---------------------------------------------------------------------------

func accountRows(a *creditFakeAccount) driver.Rows {
	if a == nil {
		return &accountRowsImpl{}
	}
	return &accountRowsImpl{acc: a, has: true}
}

type accountRowsImpl struct {
	acc     *creditFakeAccount
	has     bool
	scanned bool
}

func (r *accountRowsImpl) Columns() []string {
	return []string{
		"id", "broj_racuna", "vlasnik", "oznaka", "raspolozivo_stanje",
		"stanje", "status", "account_type", "email", "username",
		"dnevni_limit", "mesecni_limit", "dnevna_potrosnja", "mesecna_potrosnja",
		"has_daily_limit", "has_monthly_limit", "datum_isteka",
		"daily_limit_remaining", "has_daily_limit_remaining",
	}
}

func (r *accountRowsImpl) Close() error { return nil }

func (r *accountRowsImpl) Next(dest []driver.Value) error {
	if !r.has || r.scanned {
		return io.EOF
	}
	r.scanned = true
	a := r.acc
	values := []driver.Value{
		a.id, a.number, a.ownerID, a.currency, a.available,
		a.booked.String(), "ACTIVE", "CHECKING", "", "",
		"0", "0", "0", "0",
		false, false, nil,
		"0", false,
	}
	copy(dest, values)
	return nil
}

type creditRowsCredit struct {
	resp    CreditMonasResponse
	has     bool
	scanned bool
}

func (r *creditRowsCredit) Columns() []string {
	return []string{"account_number", "posting_currency", "posting_amount", "credited_currency", "credited_amount"}
}
func (r *creditRowsCredit) Close() error { return nil }
func (r *creditRowsCredit) Next(dest []driver.Value) error {
	if !r.has || r.scanned {
		return io.EOF
	}
	r.scanned = true
	copy(dest, []driver.Value{
		r.resp.AccountNumber, r.resp.PostingCurrency, r.resp.PostingAmount.String(),
		r.resp.CreditedCurrency, r.resp.CreditedAmount.String(),
	})
	return nil
}

var (
	_ driver.QueryerContext = (*creditFakeConn)(nil)
	_ driver.ExecerContext  = (*creditFakeConn)(nil)
	_ driver.ConnBeginTx    = (*creditFakeConn)(nil)
)
