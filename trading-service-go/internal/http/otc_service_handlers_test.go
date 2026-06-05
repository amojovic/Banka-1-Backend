package http

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"
	"banka1/trading-service-go/internal/otc"
	"banka1/trading-service-go/internal/portfolio"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
)

// ---- stubs for otc's deps.go interfaces ----

type otcRepoStub struct{}

func (otcRepoStub) Pool() *pgxpool.Pool                                              { return nil }
func (otcRepoStub) InsertOffer(context.Context, otc.Querier, *otc.OtcOffer) error    { return nil }
func (otcRepoStub) FindOfferByID(context.Context, otc.Querier, int64) (*otc.OtcOffer, error) {
	return nil, otc.ErrNotFound
}
func (otcRepoStub) FindOfferByIDForUpdate(context.Context, otc.Querier, int64) (*otc.OtcOffer, error) {
	return nil, otc.ErrNotFound
}
func (otcRepoStub) UpdateOffer(context.Context, otc.Querier, *otc.OtcOffer) error { return nil }
func (otcRepoStub) FindActiveOffersForUser(context.Context, int64) ([]otc.OtcOffer, error) {
	return nil, nil
}
func (otcRepoStub) InsertOptionContract(context.Context, otc.Querier, *otc.OptionContract) error {
	return nil
}
func (otcRepoStub) FindOptionContractByID(context.Context, otc.Querier, int64) (*otc.OptionContract, error) {
	return nil, otc.ErrNotFound
}
func (otcRepoStub) FindOptionContractByIDForUpdate(context.Context, otc.Querier, int64) (*otc.OptionContract, error) {
	return nil, otc.ErrNotFound
}
func (otcRepoStub) UpdateOptionContractStatus(context.Context, otc.Querier, int64, string) error {
	return nil
}
func (otcRepoStub) SetOptionContractExercisedAt(context.Context, otc.Querier, int64, time.Time) error {
	return nil
}
func (otcRepoStub) SumActiveBySellerAndTicker(context.Context, otc.Querier, int64, string) (int64, error) {
	return 0, nil
}
func (otcRepoStub) FindContractsByBuyerIDAndStatus(context.Context, int64, string) ([]otc.OptionContract, error) {
	return nil, nil
}
func (otcRepoStub) FindContractsBySellerIDAndStatus(context.Context, int64, string) ([]otc.OptionContract, error) {
	return nil, nil
}
func (otcRepoStub) FindContractsByStatusAndSettlementDateBefore(context.Context, string, time.Time) ([]otc.OptionContract, error) {
	return nil, nil
}
func (otcRepoStub) FindContractsByStatusAndSettlementDate(context.Context, string, time.Time) ([]otc.OptionContract, error) {
	return nil, nil
}
func (otcRepoStub) InsertExpiryReminderIfAbsent(context.Context, otc.Querier, int64, int) (bool, error) {
	return true, nil
}
func (otcRepoStub) InsertHistory(context.Context, otc.Querier, *otc.NegotiationHistory) error {
	return nil
}
func (otcRepoStub) HistoryForUser(context.Context, int64, *string, *int64, *time.Time, *time.Time) ([]otc.NegotiationHistory, error) {
	return nil, nil
}

type otcPortStub struct{}

func (otcPortStub) Pool() *pgxpool.Pool { return nil }
func (otcPortStub) FindByUserID(context.Context, portfolio.Querier, int64) ([]portfolio.Portfolio, error) {
	return nil, nil
}
func (otcPortStub) FindByID(context.Context, portfolio.Querier, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (otcPortStub) FindByUserIDAndListingID(context.Context, portfolio.Querier, int64, int64) (*portfolio.Portfolio, error) {
	return nil, nil
}
func (otcPortStub) UpdatePublic(context.Context, portfolio.Querier, int64, int, bool) error {
	return nil
}
func (otcPortStub) UpdateReservedAndPublic(context.Context, portfolio.Querier, int64, int, int) error {
	return nil
}
func (otcPortStub) FindAllPublicStocks(context.Context, portfolio.Querier) ([]portfolio.Portfolio, error) {
	return nil, nil
}

type otcMktStub2 struct{}

func (otcMktStub2) GetListing(context.Context, int64) (*clients.StockListing, error) {
	return nil, nil
}

type otcCustStub struct{}

func (otcCustStub) GetCustomer(context.Context, int64) (*clients.Customer, error) { return nil, nil }

type otcEmpStub struct{}

func (otcEmpStub) ActuaryClientIDs(context.Context) []int64 { return nil }

type otcNoopPub struct{}

func (otcNoopPub) PublishPremiumTransferRequested(context.Context, otc.PremiumTransferRequestedEvent) error {
	return nil
}
func (otcNoopPub) PublishExerciseRequested(context.Context, otc.ExerciseRequestedEvent) error {
	return nil
}

func newOtcHandlers() *Handlers {
	svc := otc.NewServiceForTest(otcRepoStub{}, otcPortStub{}, otcMktStub2{}, otcCustStub{}, otcEmpStub{},
		otcNoopPub{}, nil,
		func(ctx context.Context, fn func(pgx.Tx) error) error { return fn(nil) }, nil)
	return &Handlers{app: &App{Otc: svc}}
}

func otcReq(method, target, body string, id int64) *http.Request {
	r := fundsReq(method, target, body)
	return withRole(r, id, "CLIENT")
}

func TestOtcCreateOffer_Success(t *testing.T) {
	h := newOtcHandlers()
	w := httptest.NewRecorder()
	body := `{"stockTicker":"AAPL","sellerId":2,"amount":5,"pricePerStock":10,"premium":1,"settlementDate":"2999-01-01"}`
	h.OtcCreateOffer(w, otcReq(http.MethodPost, "/otc/offers", body, 1))
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestOtcCounterOffer_NotFound(t *testing.T) {
	h := newOtcHandlers()
	r := otcReq(http.MethodPost, "/otc/offers/1/counter", `{"amount":5,"pricePerStock":10,"premium":1,"settlementDate":"2999-01-01"}`, 1)
	r.SetPathValue("offerId", "1")
	w := httptest.NewRecorder()
	h.OtcCounterOffer(w, r)
	assert.NotEqual(t, http.StatusOK, w.Code) // offer not found
}

func TestOtcAccept_NotFound(t *testing.T) {
	h := newOtcHandlers()
	r := otcReq(http.MethodPost, "/otc/offers/1/accept", "", 1)
	r.SetPathValue("offerId", "1")
	w := httptest.NewRecorder()
	h.OtcAcceptOffer(w, r)
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestOtcReject_NotFound(t *testing.T) {
	h := newOtcHandlers()
	r := otcReq(http.MethodPost, "/otc/offers/1/reject", "", 1)
	r.SetPathValue("offerId", "1")
	w := httptest.NewRecorder()
	h.OtcRejectOffer(w, r)
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestOtcWithdraw_NotFound(t *testing.T) {
	h := newOtcHandlers()
	r := otcReq(http.MethodPost, "/otc/offers/1/withdraw", "", 1)
	r.SetPathValue("offerId", "1")
	w := httptest.NewRecorder()
	h.OtcWithdrawOffer(w, r)
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestOtcActiveOffers_Empty(t *testing.T) {
	h := newOtcHandlers()
	w := httptest.NewRecorder()
	h.OtcActiveOffers(w, otcReq(http.MethodGet, "/otc/offers/active", "", 1))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestOtcPublicStocks_Empty(t *testing.T) {
	h := newOtcHandlers()
	w := httptest.NewRecorder()
	h.OtcPublicStocks(w, otcReq(http.MethodGet, "/otc/public-stocks", "", 1))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestOtcExerciseContract_NotFound(t *testing.T) {
	h := newOtcHandlers()
	r := otcReq(http.MethodPost, "/otc/contracts/1/exercise", "", 1)
	r.SetPathValue("contractId", "1")
	w := httptest.NewRecorder()
	h.OtcExerciseContract(w, r)
	assert.NotEqual(t, http.StatusAccepted, w.Code)
}

func TestOtcMyContracts_Empty(t *testing.T) {
	h := newOtcHandlers()
	w := httptest.NewRecorder()
	h.OtcMyContracts(w, otcReq(http.MethodGet, "/otc/contracts/my", "", 1))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestOtcMyPositions_Empty(t *testing.T) {
	h := newOtcHandlers()
	w := httptest.NewRecorder()
	h.OtcMyPositions(w, otcReq(http.MethodGet, "/otc/my-positions", "", 1))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestOtcAddPosition_Handled(t *testing.T) {
	h := newOtcHandlers()
	w := httptest.NewRecorder()
	h.OtcAddPosition(w, otcReq(http.MethodPost, "/otc/positions", `{"listingId":1,"publicQuantity":5}`, 1))
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestOtcUpdatePosition_NotFound(t *testing.T) {
	h := newOtcHandlers()
	r := otcReq(http.MethodPut, "/otc/positions/1", `{"publicQuantity":5}`, 1)
	r.SetPathValue("positionId", "1")
	w := httptest.NewRecorder()
	h.OtcUpdatePosition(w, r)
	assert.NotEqual(t, http.StatusOK, w.Code)
}

func TestOtcRemovePosition_NotFound(t *testing.T) {
	h := newOtcHandlers()
	r := otcReq(http.MethodDelete, "/otc/positions/1", "", 1)
	r.SetPathValue("positionId", "1")
	w := httptest.NewRecorder()
	h.OtcRemovePosition(w, r)
	assert.NotEqual(t, http.StatusNoContent, w.Code)
}

func TestOtcNegotiationHistory_Empty(t *testing.T) {
	h := newOtcHandlers()
	w := httptest.NewRecorder()
	h.OtcNegotiationHistory(w, otcReq(http.MethodGet, "/otc/offers/history", "", 1))
	assert.Equal(t, http.StatusOK, w.Code)
}
