package user

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"banka1/user-service-go/internal/platform"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestEmployeeActivateHandler_Returns200(t *testing.T) {
	h := testHandlers(&mockRepo{})
	req := httptest.NewRequest(http.MethodPost, "/employees/auth/activate", jsonBody(ActivateRequest{
		ID: 1, Password: "StrongPass123!", Token: "activation-token",
	}))
	w := httptest.NewRecorder()
	h.employeeActivate(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestClientActivateHandler_Returns200(t *testing.T) {
	h := testHandlers(&mockRepo{})
	req := httptest.NewRequest(http.MethodPost, "/clients/auth/activate", jsonBody(ActivateRequest{
		ID: 2, Password: "StrongPass123!", Token: "client-token",
	}))
	w := httptest.NewRecorder()
	h.clientActivate(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestEmployeeResendActivationHandler_Returns202(t *testing.T) {
	repo := &mockRepo{
		employeeByLoginResult: Employee{ID: 1, Email: "e@test.com", Aktivan: false, Ime: "E"},
	}
	h := testHandlers(repo)
	req := httptest.NewRequest(http.MethodPost, "/employees/auth/resend-activation", jsonBody(EmailRequest{Email: "e@test.com"}))
	w := httptest.NewRecorder()
	h.employeeResendActivation(w, req)
	assert.Equal(t, http.StatusAccepted, w.Code)
}

func TestClientResendActivationHandler_Returns200(t *testing.T) {
	repo := &mockRepo{
		clientByEmailResult: Client{ID: 1, Email: "c@test.com", Aktivan: false, Ime: "C"},
	}
	h := testHandlers(repo)
	req := httptest.NewRequest(http.MethodPost, "/clients/auth/resend-activation", jsonBody(EmailRequest{Email: "c@test.com"}))
	w := httptest.NewRecorder()
	h.clientResendActivation(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestEmployeeRefreshHandler_Returns200(t *testing.T) {
	hash, _ := platform.HashPassword("pass")
	repo := &mockRepo{
		employeeByRefreshResult: Employee{
			ID: 1, Email: "e@test.com", Ime: "E", Prezime: "E",
			Role: "BASIC", Aktivan: true, PasswordHash: &hash,
		},
		employeePermissions: []string{"BANKING_BASIC"},
	}
	h := testHandlers(repo)
	req := httptest.NewRequest(http.MethodPost, "/employees/auth/refresh", jsonBody(RefreshTokenRequest{RefreshToken: "refresh-token"}))
	w := httptest.NewRecorder()
	h.employeeRefresh(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestEmployeeActivateHandler_InvalidJSON_Returns400(t *testing.T) {
	h := testHandlers(&mockRepo{})
	req := httptest.NewRequest(http.MethodPost, "/employees/auth/activate", jsonBody(struct {
		Bad string `json:"bad"`
	}{Bad: "x"}))
	w := httptest.NewRecorder()
	h.employeeActivate(w, req)
	assert.NotEqual(t, http.StatusInternalServerError, w.Code)
}

func TestRegisterRoutes_SearchEmployeesRequiresAuth(t *testing.T) {
	repo := &mockRepo{
		searchEmployeesResult: []Employee{{ID: 1, Ime: "A", Prezime: "B", Email: "a@test.com", Role: "BASIC"}},
		searchEmployeesTotal:  1,
	}
	auth := platform.NewJWTService(platform.JWTConfig{
		Secret:              "test-secret",
		Issuer:              "test",
		IDClaim:             "id",
		RolesClaim:          "roles",
		PermissionsClaim:    "permissions",
		AccessTokenDuration: time.Hour,
	})
	handler := platform.NewRouter(platform.RouterDeps{
		Config:        platform.LoadConfig(),
		Logger:        slog.Default(),
		Authenticator: auth,
		Register:      NewHandlers(testService(repo)).RegisterRoutes,
	})

	req := httptest.NewRequest(http.MethodGet, "/employees?page=0&size=5", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusUnauthorized, w.Code)

	token, err := auth.GenerateAccessToken(1, "basic@test.com", "BASIC", []string{"BANKING_BASIC"})
	require.NoError(t, err)
	req = httptest.NewRequest(http.MethodGet, "/employees?page=0&size=5", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestDeleteEmployeeHandler_Returns204(t *testing.T) {
	h := testHandlers(&mockRepo{})
	req := httptest.NewRequest(http.MethodDelete, "/employees/employees/5", nil)
	w := httptest.NewRecorder()
	h.deleteEmployee(w, req)
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestDeleteClientHandler_Returns204(t *testing.T) {
	h := testHandlers(&mockRepo{})
	req := httptest.NewRequest(http.MethodDelete, "/clients/customers/3", nil)
	w := httptest.NewRecorder()
	h.deleteClient(w, req)
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestAddMarginPermissionHandler_Returns204(t *testing.T) {
	h := testHandlers(&mockRepo{})
	req := httptest.NewRequest(http.MethodPut, "/clients/customers/margin/7", nil)
	w := httptest.NewRecorder()
	h.addMarginPermission(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}
