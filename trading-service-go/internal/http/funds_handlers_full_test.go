package http

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/funds"

	"github.com/stretchr/testify/assert"
)

// newFundsHandlers wires the funds family of services over the universal fakeDB
// and okDoer-backed real clients.
func newFundsHandlers(db *fakeDB) *Handlers {
	repo := funds.NewRepositoryForTest(db)
	market := clients.NewMarketClient("http://market", nil, okDoer{})
	account := clients.NewAccountClient("http://account", nil, okDoer{})
	employee := clients.NewEmployeeClient("http://user", nil, okDoer{})
	holding := funds.NewHoldingServiceForTest(repo, market, quietLogger())
	snapshot := funds.NewSnapshotServiceForTest(repo, holding, quietLogger())
	stats := funds.NewStatisticsServiceForTest(snapshot)
	svc := funds.NewServiceForTest(repo, snapshot, stats, holding, market, account, employee,
		nil, funds.FakeQRunner(db), quietLogger())
	liq := funds.NewLiquidationServiceForTest(repo, holding, market, account, snapshot, quietLogger())
	div := funds.NewDividendServiceForTest(repo, holding, snapshot, market, account, svc,
		funds.FakeQRunner(db), quietLogger())
	app := &App{Funds: svc, Holding: holding, Snapshot: snapshot, Liquidation: liq, Dividend: div}
	return &Handlers{app: app}
}

func fundsReq(method, target, body string) *http.Request {
	if body == "" {
		return httptest.NewRequest(method, target, nil)
	}
	return httptest.NewRequest(method, target, bytes.NewBufferString(body))
}

func withID(r *http.Request, key, val string) *http.Request {
	r.SetPathValue(key, val)
	return r
}

func TestFundsDiscovery_Empty(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsDiscovery(w, fundsReq(http.MethodGet, "/funds", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsDetails_BadID(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.FundsDetails(w, withID(fundsReq(http.MethodGet, "/funds/x", ""), "id", "x"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsDetails_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsDetails(w, withID(fundsReq(http.MethodGet, "/funds/1", ""), "id", "1"))
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsAnalytics_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsAnalytics(w, withID(fundsReq(http.MethodGet, "/funds/1/analytics", ""), "id", "1"))
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsSecurities_NotOK(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsSecurities(w, withID(fundsReq(http.MethodGet, "/funds/1/securities", ""), "id", "1"))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestFundsPerformance_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsPerformance(w, withID(fundsReq(http.MethodGet, "/funds/1/performance", ""), "id", "1"))
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsCreate_BadJSON(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.FundsCreate(w, fundsReq(http.MethodPost, "/funds", "{bad"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsCreate_InsertFails(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsCreate(w, fundsReq(http.MethodPost, "/funds", `{"naziv":"F","minimumContribution":100}`))
	assert.NotEqual(t, http.StatusCreated, w.Code)
}

func TestFundsSupervised_Empty(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsSupervised(w, fundsReq(http.MethodGet, "/funds/supervised", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsInvest_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsInvest(w, withID(fundsReq(http.MethodPost, "/funds/1/invest", `{"amount":100}`), "id", "1"))
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsInvest_BadID(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.FundsInvest(w, withID(fundsReq(http.MethodPost, "/funds/x/invest", ""), "id", "x"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsRedeem_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsRedeem(w, withID(fundsReq(http.MethodPost, "/funds/1/redeem", `{"amount":100}`), "id", "1"))
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsBankInvest_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsBankInvest(w, withID(fundsReq(http.MethodPost, "/funds/1/bank-invest", `{"amount":100}`), "id", "1"))
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsBankRedeem_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsBankRedeem(w, withID(fundsReq(http.MethodPost, "/funds/1/bank-redeem", `{"amount":100}`), "id", "1"))
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsMyPositions_Empty(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsMyPositions(w, fundsReq(http.MethodGet, "/funds/my-positions", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsBankPositions_Empty(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsBankPositions(w, fundsReq(http.MethodGet, "/funds/bank-positions", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsPositions_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsPositions(w, withID(fundsReq(http.MethodGet, "/funds/1/positions", ""), "id", "1"))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestFundsMyTransactions_Empty(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsMyTransactions(w, fundsReq(http.MethodGet, "/funds/my-transactions", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFundsTransactions_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsTransactions(w, withID(fundsReq(http.MethodGet, "/funds/1/transactions", ""), "id", "1"))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestFundsSellSecurity_MissingTicker(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := withID(fundsReq(http.MethodPost, "/funds/1/securities//sell", `{"quantity":1}`), "id", "1")
	r.SetPathValue("ticker", "")
	w := httptest.NewRecorder()
	h.FundsSellSecurity(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsSellSecurity_BadQty(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := withID(fundsReq(http.MethodPost, "/funds/1/securities/AAPL/sell", `{"quantity":0}`), "id", "1")
	r.SetPathValue("ticker", "AAPL")
	w := httptest.NewRecorder()
	h.FundsSellSecurity(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsSellSecurity_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := withID(fundsReq(http.MethodPost, "/funds/1/securities/AAPL/sell", `{"quantity":1}`), "id", "1")
	r.SetPathValue("ticker", "AAPL")
	w := httptest.NewRecorder()
	h.FundsSellSecurity(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsRecordDividend_BadDate(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := withID(fundsReq(http.MethodPost, "/funds/1/dividends", `{"stockTicker":"AAPL","paymentDate":"bad"}`), "id", "1")
	w := httptest.NewRecorder()
	h.FundsRecordDividend(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsRecordDividend_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := withID(fundsReq(http.MethodPost, "/funds/1/dividends", `{"stockTicker":"AAPL","dividendPerShare":1,"paymentDate":"2026-01-01"}`), "id", "1")
	w := httptest.NewRecorder()
	h.FundsRecordDividend(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsReassignManager_MissingIDs(t *testing.T) {
	h := &Handlers{app: &App{}}
	w := httptest.NewRecorder()
	h.FundsReassignManager(w, fundsReq(http.MethodPatch, "/funds/admin/reassign-manager", `{"oldManagerId":0,"newManagerId":0}`))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsReassignManager_Success(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	w := httptest.NewRecorder()
	h.FundsReassignManager(w, fundsReq(http.MethodPatch, "/funds/admin/reassign-manager", `{"oldManagerId":1,"newManagerId":2}`))
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestFundsInternalLiquidate_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := fundsReq(http.MethodPost, "/funds/internal/1/liquidate", `{"targetAmount":100}`)
	r.SetPathValue("fundId", "1")
	w := httptest.NewRecorder()
	h.FundsInternalLiquidate(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}

func TestFundsInternalLiquidate_BadFundID(t *testing.T) {
	h := &Handlers{app: &App{}}
	r := fundsReq(http.MethodPost, "/funds/internal/x/liquidate", "")
	r.SetPathValue("fundId", "x")
	w := httptest.NewRecorder()
	h.FundsInternalLiquidate(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsInternalAddHolding_BadInput(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := fundsReq(http.MethodPost, "/funds/internal/1/holdings/add", `{"ticker":"","quantity":0}`)
	r.SetPathValue("fundId", "1")
	w := httptest.NewRecorder()
	h.FundsInternalAddHolding(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsInternalDebitLiquidity_BadAmount(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := fundsReq(http.MethodPost, "/funds/internal/1/liquidity/debit", `{"amount":0}`)
	r.SetPathValue("fundId", "1")
	w := httptest.NewRecorder()
	h.FundsInternalDebitLiquidity(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestFundsInternalDebitLiquidity_NotFound(t *testing.T) {
	h := newFundsHandlers(&fakeDB{})
	r := fundsReq(http.MethodPost, "/funds/internal/1/liquidity/debit", `{"amount":50}`)
	r.SetPathValue("fundId", "1")
	w := httptest.NewRecorder()
	h.FundsInternalDebitLiquidity(w, r)
	assert.Equal(t, http.StatusNotFound, w.Code)
}
