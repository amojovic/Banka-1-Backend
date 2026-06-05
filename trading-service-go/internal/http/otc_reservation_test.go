package http

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"banka1/trading-service-go/internal/otc"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
)

type resvPortStub struct{}

func (resvPortStub) FindByUserID(context.Context, portfolio.Querier, int64) ([]portfolio.Portfolio, error) {
	return nil, nil
}
func (resvPortStub) FindByUserIDAndListingIDForUpdate(context.Context, portfolio.Querier, int64, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (resvPortStub) UpdateReservedQuantity(context.Context, portfolio.Querier, int64, int) error {
	return nil
}
func (resvPortStub) UpdateQuantityAndReserved(context.Context, portfolio.Querier, int64, int, int) error {
	return nil
}
func (resvPortStub) UpdateQuantity(context.Context, portfolio.Querier, int64, int) error { return nil }
func (resvPortStub) Insert(context.Context, portfolio.Querier, int64, int64, string, int, decimal.Decimal) error {
	return nil
}

func newReservationHandlers() *Handlers {
	svc := otc.NewReservationServiceForTest(resvPortStub{}, otcMktStub2{}, &fakeDB{}, nil)
	return &Handlers{app: &App{OtcReservation: svc}}
}

func TestStocksInternalReserve_NoPosition(t *testing.T) {
	h := newReservationHandlers()
	w := httptest.NewRecorder()
	h.StocksInternalReserve(w, fundsReq(http.MethodPost, "/stocks/internal/reserve",
		`{"ownerId":1,"stockTicker":"AAPL","amount":5}`))
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestStocksInternalRelease_Handled(t *testing.T) {
	h := newReservationHandlers()
	r := fundsReq(http.MethodDelete, "/stocks/internal/reservations/abc", "")
	r.SetPathValue("id", "abc")
	w := httptest.NewRecorder()
	h.StocksInternalRelease(w, r)
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestStocksInternalTransfer_Handled(t *testing.T) {
	h := newReservationHandlers()
	r := fundsReq(http.MethodPost, "/stocks/internal/reservations/abc/transfer", `{"buyerId":2}`)
	r.SetPathValue("id", "abc")
	w := httptest.NewRecorder()
	h.StocksInternalTransfer(w, r)
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestStocksInternalReverse_Handled(t *testing.T) {
	h := newReservationHandlers()
	r := fundsReq(http.MethodPost, "/stocks/internal/ownership-transfers/abc/reverse", "")
	r.SetPathValue("id", "abc")
	w := httptest.NewRecorder()
	h.StocksInternalReverse(w, r)
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}
