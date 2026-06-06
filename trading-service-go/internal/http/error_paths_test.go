package http

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"banka1/trading-service-go/internal/audit"
	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/funds"
	"banka1/trading-service-go/internal/order"
	"banka1/trading-service-go/internal/platform"
	"banka1/trading-service-go/internal/tax"

	gpauth "banka1/go-platform/auth"
	"banka1/go-platform/rabbitmq"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
)

// ---- funds read handlers: the err != nil branch (Query fails) → 500 ----

func fundsDBErr() *Handlers { return newFundsHandlers(&fakeDB{queryErr: errors.New("db down")}) }

func TestFundsDiscovery_Error(t *testing.T) {
	w := httptest.NewRecorder()
	fundsDBErr().FundsDiscovery(w, fundsReq(http.MethodGet, "/funds", ""))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestFundsSupervised_Error(t *testing.T) {
	w := httptest.NewRecorder()
	fundsDBErr().FundsSupervised(w, fundsReq(http.MethodGet, "/funds/supervised", ""))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestFundsMyPositions_Error(t *testing.T) {
	w := httptest.NewRecorder()
	fundsDBErr().FundsMyPositions(w, fundsReq(http.MethodGet, "/funds/my-positions", ""))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestFundsBankPositions_Error(t *testing.T) {
	w := httptest.NewRecorder()
	fundsDBErr().FundsBankPositions(w, fundsReq(http.MethodGet, "/funds/bank-positions", ""))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestFundsMyTransactions_Error(t *testing.T) {
	w := httptest.NewRecorder()
	fundsDBErr().FundsMyTransactions(w, fundsReq(http.MethodGet, "/funds/my-transactions", ""))
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

// ---- TaxCollect: the audit-recording block (Audit + Employees + principal) ----

func TestTaxCollect_WithAudit(t *testing.T) {
	big := taxBigStub{}
	taxSvc := tax.NewServiceForTest(big, taxOrderStub{}, taxPortStub{}, big, big, big, big, big, nil,
		decimal.RequireFromString("0.15"), quietLogger())
	auditSvc := audit.NewServiceForTest(auditRepoStub{}, quietLogger())
	emp := clients.NewEmployeeClient("http://user", nil, okDoer{})
	h := &Handlers{app: &App{Tax: taxSvc, Audit: auditSvc, Employees: emp}}

	r := fundsReq(http.MethodPost, "/tax/collect", "")
	r = r.WithContext(gpauth.WithPrincipal(r.Context(), gpauth.Principal{ID: 7, Email: "a@b.io"}))
	w := httptest.NewRecorder()
	h.TaxCollect(w, r)
	assert.Equal(t, http.StatusOK, w.Code)
}

// ---- NewApp with every scheduler enabled (covers the cron registration) ----

func TestNewApp_SchedulersEnabled(t *testing.T) {
	cfg := platform.Config{}
	cfg.OrderSchedulersEnabled = true
	cfg.RecurringOrderSchedulerEnabled = true
	cfg.TaxSchedulerEnabled = true
	cfg.DividendSchedulerEnabled = true
	cfg.FundSnapshotSchedulerEnabled = true
	cfg.OtcSchedulersEnabled = true

	app := NewApp(cfg, nil, newTestJWT(), quietLogger(),
		order.NoopNotifier{}, tax.NoopNotifier{}, funds.NewNoopSagaPublisher(quietLogger()),
		rabbitmq.Config{}, nil, otcNoopPub{}, rabbitmq.Config{})
	assert.NotNil(t, app)
	app.Close()
}
