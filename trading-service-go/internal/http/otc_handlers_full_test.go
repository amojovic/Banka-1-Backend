package http

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"

	"banka1/trading-service-go/internal/otc"

	gpauth "banka1/go-platform/auth"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
)

// ===========================================================================
// Pure helpers
// ===========================================================================

func TestParseFaultInjection(t *testing.T) {
	// no headers → nil
	assert.Nil(t, parseFaultInjection(httptest.NewRequest(http.MethodPost, "/x", nil)))
	// with headers → populated
	r := httptest.NewRequest(http.MethodPost, "/x", nil)
	r.Header.Set("X-Saga-Force-Fail", "F2")
	r.Header.Set("X-Saga-Force-Fail-Kind", "timeout")
	r.Header.Set("X-Saga-Compensate-Fail", "C1")
	r.Header.Set("X-Saga-Compensate-Fail-Times", "3")
	r.Header.Set("X-Saga-Inject-Delay", "F3:5000")
	fi := parseFaultInjection(r)
	assert.NotNil(t, fi)
	assert.Equal(t, "F2", fi.ForceFailStep)
	assert.Equal(t, 3, fi.CompensateFailTimes)
	assert.Equal(t, "F3", fi.InjectDelayStep)
	assert.Equal(t, 5000, fi.InjectDelayMs)
}

func TestParsePathInt64(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/x", nil)
	r.SetPathValue("offerId", "42")
	v, err := parsePathInt64(r, "offerId")
	assert.NoError(t, err)
	assert.Equal(t, int64(42), v)
	r.SetPathValue("offerId", "nope")
	_, err = parsePathInt64(r, "offerId")
	assert.Error(t, err)
}

func TestCorrelationID(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/x", nil)
	assert.Equal(t, "no-correlation", correlationID(r))
	r.Header.Set("X-Correlation-Id", "abc")
	assert.Equal(t, "abc", correlationID(r))
}

func TestTokenNameClaim(t *testing.T) {
	// malformed token → nil
	assert.Nil(t, tokenNameClaim(gpauth.Principal{Token: "not-a-jwt"}))
	// valid 3-part token with a name claim
	payload := base64.RawURLEncoding.EncodeToString([]byte(`{"name":"Alice"}`))
	tok := "h." + payload + ".s"
	name := tokenNameClaim(gpauth.Principal{Token: tok})
	assert.NotNil(t, name)
	assert.Equal(t, "Alice", *name)
	// valid token but no name claim → nil
	noName := base64.RawURLEncoding.EncodeToString([]byte(`{"sub":"x"}`))
	assert.Nil(t, tokenNameClaim(gpauth.Principal{Token: "h." + noName + ".s"}))
}

func TestParseLocalDateParam(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/x", nil)
	d, err := parseLocalDateParam(r, "absent")
	assert.NoError(t, err)
	assert.Nil(t, d)
	r = httptest.NewRequest(http.MethodGet, "/x?d=2026-01-02", nil)
	d, err = parseLocalDateParam(r, "d")
	assert.NoError(t, err)
	assert.NotNil(t, d)
	r = httptest.NewRequest(http.MethodGet, "/x?d=bad", nil)
	_, err = parseLocalDateParam(r, "d")
	assert.Error(t, err)
}

func TestValidateOfferTerms(t *testing.T) {
	// all missing
	f := map[string]string{}
	validateOfferTerms(f, nil, nil, nil, "")
	assert.Contains(t, f, "amount")
	assert.Contains(t, f, "pricePerStock")
	assert.Contains(t, f, "premium")
	assert.Contains(t, f, "settlementDate")
	// out-of-range values
	f = map[string]string{}
	amt := 0
	price := decimal.Zero
	prem := decimal.NewFromInt(-1)
	validateOfferTerms(f, &amt, &price, &prem, "not-a-date")
	assert.Contains(t, f, "amount")
	assert.Contains(t, f, "settlementDate")
	// past date
	f = map[string]string{}
	amt = 5
	price = decimal.NewFromInt(10)
	prem = decimal.NewFromInt(1)
	validateOfferTerms(f, &amt, &price, &prem, "2000-01-01")
	assert.Contains(t, f, "settlementDate")
	// valid future
	f = map[string]string{}
	got := validateOfferTerms(f, &amt, &price, &prem, "2999-01-01")
	assert.Empty(t, f)
	assert.Equal(t, 2999, got.Year())
}

func TestValidatePublicQuantity(t *testing.T) {
	f := map[string]string{}
	validatePublicQuantity(f, nil)
	assert.Contains(t, f, "publicQuantity")
	f = map[string]string{}
	z := 0
	validatePublicQuantity(f, &z)
	assert.Contains(t, f, "publicQuantity")
	f = map[string]string{}
	one := 1
	validatePublicQuantity(f, &one)
	assert.Empty(t, f)
}

func TestIsValidContractStatus(t *testing.T) {
	assert.True(t, isValidContractStatus(otc.ContractActive))
	assert.False(t, isValidContractStatus("BOGUS"))
}

func TestIsValidOfferStatus(t *testing.T) {
	assert.True(t, isValidOfferStatus(otc.OfferAccepted))
	assert.False(t, isValidOfferStatus("BOGUS"))
}

// ===========================================================================
// Handler validation / early-exit paths (no service needed)
// ===========================================================================

func emptyOtc() *Handlers { return &Handlers{app: &App{}} }

func TestOtcCreateOffer_BadJSON(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcCreateOffer(w, fundsReq(http.MethodPost, "/otc/offers", "{bad"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcCreateOffer_ValidationFails(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcCreateOffer(w, fundsReq(http.MethodPost, "/otc/offers", `{"stockTicker":""}`))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcCounterOffer_BadID(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPost, "/otc/offers/x/counter", "")
	r.SetPathValue("offerId", "x")
	emptyOtc().OtcCounterOffer(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcCounterOffer_ValidationFails(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPost, "/otc/offers/1/counter", `{}`)
	r.SetPathValue("offerId", "1")
	emptyOtc().OtcCounterOffer(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcAcceptRejectWithdrawExercise_BadID(t *testing.T) {
	for _, tc := range []struct {
		name string
		h    func(*Handlers, http.ResponseWriter, *http.Request)
		key  string
	}{
		{"accept", (*Handlers).OtcAcceptOffer, "offerId"},
		{"reject", (*Handlers).OtcRejectOffer, "offerId"},
		{"withdraw", (*Handlers).OtcWithdrawOffer, "offerId"},
		{"exercise", (*Handlers).OtcExerciseContract, "contractId"},
	} {
		w := httptest.NewRecorder()
		r := fundsReq(http.MethodPost, "/otc/x", "")
		r.SetPathValue(tc.key, "x")
		tc.h(emptyOtc(), w, r)
		assert.Equal(t, http.StatusBadRequest, w.Code, tc.name)
	}
}

func TestOtcMyContracts_BadStatus(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcMyContracts(w, fundsReq(http.MethodGet, "/otc/contracts/my?status=BOGUS", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcAddPosition_BadJSON(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcAddPosition(w, fundsReq(http.MethodPost, "/otc/positions", "{bad"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcAddPosition_ValidationFails(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcAddPosition(w, fundsReq(http.MethodPost, "/otc/positions", `{}`))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcUpdatePosition_BadID(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPut, "/otc/positions/x", "")
	r.SetPathValue("positionId", "x")
	emptyOtc().OtcUpdatePosition(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcUpdatePosition_ValidationFails(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPut, "/otc/positions/1", `{}`)
	r.SetPathValue("positionId", "1")
	emptyOtc().OtcUpdatePosition(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcRemovePosition_BadID(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodDelete, "/otc/positions/x", "")
	r.SetPathValue("positionId", "x")
	emptyOtc().OtcRemovePosition(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcNegotiationHistory_BadStatus(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcNegotiationHistory(w, fundsReq(http.MethodGet, "/otc/offers/history?status=BOGUS", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcNegotiationHistory_BadOtherParty(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcNegotiationHistory(w, fundsReq(http.MethodGet, "/otc/offers/history?otherPartyId=x", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestOtcNegotiationHistory_BadDate(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().OtcNegotiationHistory(w, fundsReq(http.MethodGet, "/otc/offers/history?dateFrom=bad", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestStocksInternalReserve_BadJSON(t *testing.T) {
	w := httptest.NewRecorder()
	emptyOtc().StocksInternalReserve(w, fundsReq(http.MethodPost, "/stocks/internal/reserve", "{bad"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestStocksInternalTransfer_BadJSON(t *testing.T) {
	w := httptest.NewRecorder()
	r := fundsReq(http.MethodPost, "/stocks/internal/reservations/abc/transfer", "{bad")
	r.SetPathValue("id", "abc")
	emptyOtc().StocksInternalTransfer(w, r)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}
