package funds

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"reflect"
	"strings"

	"banka1/trading-service-go/internal/clients"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/shopspring/decimal"
)

// ============================ fake Querier ================================
//
// A reflection-based fake satisfying the funds Querier interface so every
// repository scan/query path is exercisable without Postgres. Scan targets in
// funds include decimal.Decimal (via ::text → *string), time.Time, *int,
// *string, **string, *int64, *bool, *int — assignScan handles them all by
// reflective assign/convert, mirroring otc/repository_test.go:57.

type fakeRow struct {
	vals []any
	err  error
}

func (r *fakeRow) Scan(dest ...any) error {
	if r.err != nil {
		return r.err
	}
	return assignScan(dest, r.vals)
}

type fakeRows struct {
	data    [][]any
	idx     int
	err     error
	scanErr error
	closed  bool
}

func (r *fakeRows) Close()                                       { r.closed = true }
func (r *fakeRows) Err() error                                   { return r.err }
func (r *fakeRows) CommandTag() pgconn.CommandTag                { return pgconn.CommandTag{} }
func (r *fakeRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *fakeRows) Values() ([]any, error)                       { return nil, nil }
func (r *fakeRows) RawValues() [][]byte                          { return nil }
func (r *fakeRows) Conn() *pgx.Conn                              { return nil }
func (r *fakeRows) Next() bool {
	if r.idx >= len(r.data) {
		return false
	}
	r.idx++
	return true
}
func (r *fakeRows) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	return assignScan(dest, r.data[r.idx-1])
}

// assignScan copies src values into dest pointers via reflection, converting
// numeric kinds where assignable (e.g. int → *int, int64 → *int64).
func assignScan(dest []any, src []any) error {
	if len(dest) != len(src) {
		return errors.New("scan: column count mismatch")
	}
	for i := range dest {
		dv := reflect.ValueOf(dest[i])
		if dv.Kind() != reflect.Ptr || dv.IsNil() {
			return errors.New("scan: dest not a pointer")
		}
		if src[i] == nil {
			dv.Elem().Set(reflect.Zero(dv.Elem().Type()))
			continue
		}
		sv := reflect.ValueOf(src[i])
		if !sv.Type().AssignableTo(dv.Elem().Type()) {
			if sv.Type().ConvertibleTo(dv.Elem().Type()) {
				sv = sv.Convert(dv.Elem().Type())
			} else {
				return errors.New("scan: type mismatch at col " + string(rune('0'+i)))
			}
		}
		dv.Elem().Set(sv)
	}
	return nil
}

// fakeQuerier returns prepared rows for Query/QueryRow and a tag for Exec. The
// callback fields let a single test drive multiple successive queries with
// different responses (e.g. find-fund then find-positions).
type fakeQuerier struct {
	row      *fakeRow
	rows     *fakeRows
	queryErr error
	execErr  error
	execTag  pgconn.CommandTag

	// rowFn / rowsFn / execFn, when set, take precedence and are called per
	// invocation with the SQL so a test can route by query text.
	rowFn  func(sql string, args []any) *fakeRow
	rowsFn func(sql string, args []any) (*fakeRows, error)
	execFn func(sql string, args []any) (pgconn.CommandTag, error)

	lastSQL  string
	lastArgs []any
	queries  []string
}

func (q *fakeQuerier) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	q.lastSQL = sql
	q.lastArgs = args
	q.queries = append(q.queries, sql)
	if q.execFn != nil {
		return q.execFn(sql, args)
	}
	return q.execTag, q.execErr
}

func (q *fakeQuerier) Query(_ context.Context, sql string, args ...any) (pgx.Rows, error) {
	q.lastSQL = sql
	q.lastArgs = args
	q.queries = append(q.queries, sql)
	if q.rowsFn != nil {
		return q.rowsFn(sql, args)
	}
	if q.queryErr != nil {
		return nil, q.queryErr
	}
	if q.rows != nil {
		return q.rows, nil
	}
	return &fakeRows{}, nil
}

func (q *fakeQuerier) QueryRow(_ context.Context, sql string, args ...any) pgx.Row {
	q.lastSQL = sql
	q.lastArgs = args
	q.queries = append(q.queries, sql)
	if q.rowFn != nil {
		return q.rowFn(sql, args)
	}
	if q.row != nil {
		return q.row
	}
	return &fakeRow{err: pgx.ErrNoRows}
}

func tag(s string) pgconn.CommandTag { return pgconn.NewCommandTag(s) }

func dec(s string) decimal.Decimal { return decimal.RequireFromString(s) }

func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// ============================ stub HTTP doer ==============================
//
// routeStub routes outbound client requests to a handler keyed by a substring
// match on "METHOD path". This lets tests build REAL clients.MarketClient /
// AccountClient / EmployeeClient over a deterministic in-memory transport.

type routeStub struct {
	// routes maps a "METHOD /path-prefix" key to a JSON response body. The first
	// route whose key prefix-matches "<method> <path>" wins.
	routes map[string]stubResp
	// fallback is returned when no route matches (defaults to 200 "null").
	fallback stubResp
	// err, when set, is returned as a transport error for every call.
	err error
	// calls records each "METHOD path" the doer saw.
	calls []string
}

type stubResp struct {
	status int
	body   string
}

func (s *routeStub) Do(req *http.Request) (*http.Response, error) {
	key := req.Method + " " + req.URL.Path
	s.calls = append(s.calls, key)
	if s.err != nil {
		return nil, s.err
	}
	for prefix, resp := range s.routes {
		if strings.HasPrefix(key, prefix) {
			return mkResp(resp), nil
		}
	}
	fb := s.fallback
	if fb.status == 0 {
		fb = stubResp{status: 200, body: "null"}
	}
	return mkResp(fb), nil
}

func mkResp(r stubResp) *http.Response {
	return &http.Response{
		StatusCode: r.status,
		Body:       io.NopCloser(strings.NewReader(r.body)),
		Header:     http.Header{"Content-Type": []string{"application/json"}},
	}
}

// newMarket / newAccount / newEmployee build real clients over a stub doer.
func newMarket(doer clients.HTTPDoer) *clients.MarketClient {
	return clients.NewMarketClient("http://market", nil, doer)
}
func newAccount(doer clients.HTTPDoer) *clients.AccountClient {
	return clients.NewAccountClient("http://account", nil, doer)
}
func newEmployee(doer clients.HTTPDoer) *clients.EmployeeClient {
	return clients.NewEmployeeClient("http://user", nil, doer)
}

// okMarket returns a market client that 200s "null" everywhere (no prices, no
// FX — CurrentPrices/FetchSnapshots come back empty, ConvertNoCommission fails
// and callers fall back to source). Good default for value-neutral tests.
func okMarket() *clients.MarketClient { return newMarket(&routeStub{}) }

// emptyAccount returns an account client that 200s "{}" everywhere — every
// credit/debit/transaction succeeds, system-account creation returns an empty
// object, default-rsd lookup returns "".
func emptyAccount() *clients.AccountClient {
	return newAccount(&routeStub{fallback: stubResp{status: 200, body: "{}"}})
}

// emptyEmployee returns an employee client that 200s "{}" everywhere.
func emptyEmployee() *clients.EmployeeClient {
	return newEmployee(&routeStub{fallback: stubResp{status: 200, body: "{}"}})
}

// stubPublisher records the events it was asked to publish and can be forced to
// error.
type stubPublisher struct {
	subscribeErr error
	redeemErr    error
	subscribes   []FundSubscribeRequestedEvent
	redeems      []FundRedeemRequestedEvent
}

func (p *stubPublisher) PublishSubscribeRequested(_ context.Context, e FundSubscribeRequestedEvent) error {
	p.subscribes = append(p.subscribes, e)
	return p.subscribeErr
}

func (p *stubPublisher) PublishRedeemRequested(_ context.Context, e FundRedeemRequestedEvent) error {
	p.redeems = append(p.redeems, e)
	return p.redeemErr
}
