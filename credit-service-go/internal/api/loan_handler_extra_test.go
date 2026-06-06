package api_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"Banka1Back/credit-service-go/internal/api"
	"Banka1Back/credit-service-go/internal/dto"
	"Banka1Back/credit-service-go/internal/model"

	"github.com/stretchr/testify/assert"
)

// ---------------------------------------------------------------------------
// FindAllLoanRequests — service error and missing roles
// ---------------------------------------------------------------------------

func TestFindAllLoanRequests_ServiceError_Returns500(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{allRequestsErr: errors.New("db error")}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/requests", nil)
	req.Header.Set("Authorization", makeToken(1, "ADMIN"))
	w := httptest.NewRecorder()
	h.FindAllLoanRequests(w, req)
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestFindAllLoanRequests_BasicRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/requests", nil)
	req.Header.Set("Authorization", makeToken(1, "BASIC"))
	w := httptest.NewRecorder()
	h.FindAllLoanRequests(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindAllLoanRequests_SupervisorRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/requests", nil)
	req.Header.Set("Authorization", makeToken(1, "SUPERVISOR"))
	w := httptest.NewRecorder()
	h.FindAllLoanRequests(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindAllLoanRequests_AgentRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/requests", nil)
	req.Header.Set("Authorization", makeToken(1, "AGENT"))
	w := httptest.NewRecorder()
	h.FindAllLoanRequests(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindAllLoanRequests_NegativePage_DefaultsToZero(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/requests?page=-5&size=0", nil)
	req.Header.Set("Authorization", makeToken(1, "ADMIN"))
	w := httptest.NewRecorder()
	h.FindAllLoanRequests(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindAllLoanRequests_ZeroSize_DefaultsTen(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{
		allRequestsResult: dto.PageResponse[model.LoanRequest]{},
	}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/requests?size=-1", nil)
	req.Header.Set("Authorization", makeToken(1, "ADMIN"))
	w := httptest.NewRecorder()
	h.FindAllLoanRequests(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

// ---------------------------------------------------------------------------
// FindAllLoans — service error, missing roles, filters, pagination
// ---------------------------------------------------------------------------

func TestFindAllLoans_ServiceError_Returns500(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{allLoansErr: errors.New("db error")}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/all", nil)
	req.Header.Set("Authorization", makeToken(1, "ADMIN"))
	w := httptest.NewRecorder()
	h.FindAllLoans(w, req)
	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestFindAllLoans_SupervisorRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/all", nil)
	req.Header.Set("Authorization", makeToken(1, "SUPERVISOR"))
	w := httptest.NewRecorder()
	h.FindAllLoans(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindAllLoans_AgentRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/all", nil)
	req.Header.Set("Authorization", makeToken(1, "AGENT"))
	w := httptest.NewRecorder()
	h.FindAllLoans(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindAllLoans_WithAccountNumberFilter_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/all?brojRacuna=1234567890123456789", nil)
	req.Header.Set("Authorization", makeToken(1, "ADMIN"))
	w := httptest.NewRecorder()
	h.FindAllLoans(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindAllLoans_NegativePage_DefaultsToZero(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/all?page=-3&size=0", nil)
	req.Header.Set("Authorization", makeToken(1, "BASIC"))
	w := httptest.NewRecorder()
	h.FindAllLoans(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

// ---------------------------------------------------------------------------
// GetLoanInfo — service error and missing roles
// ---------------------------------------------------------------------------

func TestGetLoanInfo_ServiceError_Returns400(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{loanInfoErr: errors.New("not found")}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/5", nil)
	req.Header.Set("Authorization", makeToken(1, "CLIENT_BASIC"))
	w := httptest.NewRecorder()
	h.GetLoanInfo(w, req)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestGetLoanInfo_BasicRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/5", nil)
	req.Header.Set("Authorization", makeToken(1, "BASIC"))
	w := httptest.NewRecorder()
	h.GetLoanInfo(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestGetLoanInfo_ClientTradingRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/5", nil)
	req.Header.Set("Authorization", makeToken(1, "CLIENT_TRADING"))
	w := httptest.NewRecorder()
	h.GetLoanInfo(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

// ---------------------------------------------------------------------------
// CreateLoanRequest — missing role
// ---------------------------------------------------------------------------

func TestCreateLoanRequest_ClientTradingRole_Returns201(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{requestResult: dto.LoanRequestResponseDTO{ID: 2}}
	h := api.NewLoanHandler(svc)
	body := validBody()
	bodyBytes, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/api/loans/requests", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", makeToken(1, "CLIENT_TRADING"))
	w := httptest.NewRecorder()
	h.CreateLoanRequest(w, req)
	assert.Equal(t, http.StatusCreated, w.Code)
}

// ---------------------------------------------------------------------------
// FindClientLoans — missing role and pagination defaults
// ---------------------------------------------------------------------------

func TestFindClientLoans_ClientTradingRole_Returns200(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/client", nil)
	req.Header.Set("Authorization", makeToken(1, "CLIENT_TRADING"))
	w := httptest.NewRecorder()
	h.FindClientLoans(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestFindClientLoans_NegativePagination_UsesDefaults(t *testing.T) {
	withSecret(t)
	svc := &stubCreditService{}
	h := api.NewLoanHandler(svc)
	req := httptest.NewRequest(http.MethodGet, "/api/loans/client?page=-1&size=-5", nil)
	req.Header.Set("Authorization", makeToken(1, "CLIENT_BASIC"))
	w := httptest.NewRecorder()
	h.FindClientLoans(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}
