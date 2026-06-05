package http

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/funds"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/stretchr/testify/assert"
)

// ---- reflective canned row/rows + a SQL-routing funds.Querier ----

func assignVals(dest, src []any) error {
	if len(dest) != len(src) {
		return errors.New("column count mismatch")
	}
	for i := range dest {
		dv := reflect.ValueOf(dest[i])
		if dv.Kind() != reflect.Ptr || dv.IsNil() {
			return errors.New("dest not a pointer")
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
				return errors.New("type mismatch")
			}
		}
		dv.Elem().Set(sv)
	}
	return nil
}

type cRow struct {
	vals []any
	err  error
}

func (r cRow) Scan(dest ...any) error {
	if r.err != nil {
		return r.err
	}
	return assignVals(dest, r.vals)
}

type cRows struct {
	data [][]any
	idx  int
}

func (r *cRows) Close()                                       {}
func (r *cRows) Err() error                                   { return nil }
func (r *cRows) CommandTag() pgconn.CommandTag                { return pgconn.CommandTag{} }
func (r *cRows) FieldDescriptions() []pgconn.FieldDescription { return nil }
func (r *cRows) Values() ([]any, error)                       { return nil, nil }
func (r *cRows) RawValues() [][]byte                          { return nil }
func (r *cRows) Conn() *pgx.Conn                              { return nil }
func (r *cRows) Next() bool {
	if r.idx >= len(r.data) {
		return false
	}
	r.idx++
	return true
}
func (r *cRows) Scan(dest ...any) error { return assignVals(dest, r.data[r.idx-1]) }

func fundVals() []any {
	now := time.Now()
	return []any{int64(1), "Alpha", (*string)(nil), "100.00", int64(7), "100.00", "1600000000000000", funds.DividendReinvest, now, false, now, int64(0)}
}
func positionVals() []any {
	now := time.Now()
	return []any{int64(1), int64(3), int64(1), "100", now, &now, int64(0)}
}
func holdingVals() []any {
	now := time.Now()
	return []any{int64(1), int64(1), "AAPL", 10, "5.00", false, now, &now, int64(0)}
}
func txVals() []any {
	now := time.Now()
	return []any{int64(1), int64(3), int64(1), "50", true, "COMPLETED", now, "111", (*string)(nil)}
}

// fundsDataDB serves canned funds rows so the read/write handler success paths run.
type fundsDataDB struct{}

func (fundsDataDB) Exec(context.Context, string, ...any) (pgconn.CommandTag, error) {
	return pgconn.NewCommandTag("UPDATE 1"), nil
}
func (fundsDataDB) Query(_ context.Context, sql string, _ ...any) (pgx.Rows, error) {
	switch {
	case strings.Contains(sql, "client_fund_positions"):
		return &cRows{data: [][]any{positionVals()}}, nil
	case strings.Contains(sql, "client_fund_transactions"):
		return &cRows{data: [][]any{txVals()}}, nil
	case strings.Contains(sql, "fund_holdings"):
		return &cRows{data: [][]any{holdingVals()}}, nil
	case strings.Contains(sql, "investment_funds"):
		return &cRows{data: [][]any{fundVals()}}, nil
	default:
		return &cRows{}, nil
	}
}
func (fundsDataDB) QueryRow(_ context.Context, sql string, _ ...any) pgx.Row {
	switch {
	case strings.Contains(sql, "EXISTS"):
		return cRow{vals: []any{true}}
	case strings.Contains(sql, "INSERT INTO investment_funds"):
		return cRow{vals: []any{int64(1), int64(0)}}
	case strings.Contains(sql, "INSERT INTO fund_holdings"):
		return cRow{vals: []any{int64(1), int64(0)}}
	case strings.Contains(sql, "client_fund_transactions"):
		return cRow{vals: []any{int64(1)}}
	case strings.Contains(sql, "client_fund_positions"):
		return cRow{vals: positionVals()}
	case strings.Contains(sql, "fund_holdings"):
		return cRow{vals: holdingVals()}
	case strings.Contains(sql, "investment_funds"):
		return cRow{vals: fundVals()}
	default:
		return cRow{err: pgx.ErrNoRows}
	}
}

func newFundsHandlersData() *Handlers {
	db := fundsDataDB{}
	repo := funds.NewRepositoryForTest(db)
	market := clients.NewMarketClient("http://market", nil, okDoer{})
	account := clients.NewAccountClient("http://account", nil, okDoer{})
	employee := clients.NewEmployeeClient("http://user", nil, okDoer{})
	holding := funds.NewHoldingServiceForTest(repo, market, quietLogger())
	snapshot := funds.NewSnapshotServiceForTest(repo, holding, quietLogger())
	stats := funds.NewStatisticsServiceForTest(snapshot)
	svc := funds.NewServiceForTest(repo, snapshot, stats, holding, market, account, employee,
		funds.NewNoopSagaPublisher(quietLogger()), funds.FakeQRunner(db), quietLogger())
	liq := funds.NewLiquidationServiceForTest(repo, holding, market, account, snapshot, quietLogger())
	div := funds.NewDividendServiceForTest(repo, holding, snapshot, market, account, svc,
		funds.FakeQRunner(db), quietLogger())
	return &Handlers{app: &App{Funds: svc, Holding: holding, Snapshot: snapshot, Liquidation: liq, Dividend: div}}
}

func dataID(r *http.Request, key, val string) *http.Request { r.SetPathValue(key, val); return r }

func TestFundsDetails_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsDetails(w, dataID(fundsReq(http.MethodGet, "/funds/1", ""), "id", "1"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsAnalytics_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsAnalytics(w, dataID(fundsReq(http.MethodGet, "/funds/1/analytics", ""), "id", "1"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsPerformance_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsPerformance(w, dataID(fundsReq(http.MethodGet, "/funds/1/performance", ""), "id", "1"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsPositions_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsPositions(w, dataID(fundsReq(http.MethodGet, "/funds/1/positions", ""), "id", "1"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsTransactions_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsTransactions(w, dataID(fundsReq(http.MethodGet, "/funds/1/transactions", ""), "id", "1"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsSecurities_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsSecurities(w, dataID(fundsReq(http.MethodGet, "/funds/1/securities", ""), "id", "1"))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsCreate_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsCreate(w, fundsReq(http.MethodPost, "/funds", `{"naziv":"F","minimumContribution":100}`))
	assert.Equal(t, http.StatusCreated, w.Code)
}

func TestFundsInvest_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsInvest(w, dataID(fundsReq(http.MethodPost, "/funds/1/invest", `{"amount":200}`), "id", "1"))
	assert.Equal(t, http.StatusAccepted, w.Code)
}

func TestFundsBankInvest_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsBankInvest(w, dataID(fundsReq(http.MethodPost, "/funds/1/bank-invest", `{"amount":200}`), "id", "1"))
	assert.Equal(t, http.StatusAccepted, w.Code)
}

func TestFundsInternalAddHolding_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPost, "/funds/internal/1/holdings/add", `{"ticker":"AAPL","quantity":5,"unitPrice":10}`)
	r.SetPathValue("fundId", "1")
	newFundsHandlersData().FundsInternalAddHolding(w, r)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsInternalLiquidate_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPost, "/funds/internal/1/liquidate", `{"targetAmount":50}`)
	r.SetPathValue("fundId", "1")
	newFundsHandlersData().FundsInternalLiquidate(w, r)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsInternalDebitLiquidity_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPost, "/funds/internal/1/liquidity/debit", `{"amount":10}`)
	r.SetPathValue("fundId", "1")
	newFundsHandlersData().FundsInternalDebitLiquidity(w, r)
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestFundsSellSecurity_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	r := dataID(fundsReq(http.MethodPost, "/funds/1/securities/AAPL/sell", `{"quantity":5}`), "id", "1")
	r.SetPathValue("ticker", "AAPL")
	newFundsHandlersData().FundsSellSecurity(w, r)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsRedeem_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsRedeem(w, dataID(fundsReq(http.MethodPost, "/funds/1/redeem", `{"amount":10}`), "id", "1"))
	assert.Equal(t, http.StatusAccepted, w.Code)
}

func TestFundsBankRedeem_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsBankRedeem(w, dataID(fundsReq(http.MethodPost, "/funds/1/bank-redeem", `{"amount":10}`), "id", "1"))
	assert.Equal(t, http.StatusAccepted, w.Code)
}

func TestFundsReassignManager_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsReassignManager(w, fundsReq(http.MethodPatch, "/funds/admin/reassign-manager", `{"oldManagerId":1,"newManagerId":2}`))
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestFundsDiscovery_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsDiscovery(w, fundsReq(http.MethodGet, "/funds?sortField=totalValue&sortDirection=desc", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsSupervised_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsSupervised(w, fundsReq(http.MethodGet, "/funds/supervised", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsMyPositions_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsMyPositions(w, fundsReq(http.MethodGet, "/funds/my-positions", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsMyTransactions_SuccessData(t *testing.T) {
	w := httptest.NewRecorder()
	newFundsHandlersData().FundsMyTransactions(w, fundsReq(http.MethodGet, "/funds/my-transactions", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}
