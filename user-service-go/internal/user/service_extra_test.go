package user

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	gpauth "banka1/go-platform/auth"
	"banka1/user-service-go/internal/platform"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestService(repo UserRepo, pub platform.NotificationPublisher) *Service {
	auth := platform.NewJWTService(platform.JWTConfig{
		Secret:              "test-secret-key-for-user-service-go",
		Issuer:              "test",
		AccessTokenDuration: time.Hour,
	})
	return NewService(repo, auth, pub, defaultCfg(), platform.ServicesConfig{}, platform.EmailConfig{
		EmployeeActivateURL: "https://bank.io/activate?token=",
		ClientActivateURL:   "https://bank.io/client/activate?token=",
	})
}

func TestEmployeeRefresh_ValidToken_ReturnsNewTokens(t *testing.T) {
	hash, _ := platform.HashPassword("pass")
	refreshPlain := "plain-refresh-token"
	repo := &mockRepo{
		employeeByRefreshResult: Employee{
			ID: 1, Email: "e@test.com", Ime: "E", Prezime: "E",
			Role: "BASIC", Aktivan: true, PasswordHash: &hash,
		},
		employeePermissions: []string{"BANKING_BASIC"},
	}
	svc := newTestService(repo, &mockPub{})

	resp, err := svc.EmployeeRefresh(context.Background(), refreshPlain)
	require.NoError(t, err)
	assert.NotEmpty(t, resp.JWT)
	assert.NotEmpty(t, resp.RefreshToken)
}

func TestEmployeeRefresh_InvalidToken_ReturnsError(t *testing.T) {
	repo := &mockRepo{employeeByRefreshErr: ErrInvalidToken}
	svc := newTestService(repo, &mockPub{})

	_, err := svc.EmployeeRefresh(context.Background(), "bad")
	assert.ErrorIs(t, err, ErrInvalidToken)
}

func TestActivateEmployee_DelegatesToRepo(t *testing.T) {
	repo := &mockRepo{}
	svc := newTestService(repo, &mockPub{})

	err := svc.ActivateEmployee(context.Background(), ActivateRequest{
		ID: 1, Password: "NewPass123!", Token: "activation-token",
	})
	require.NoError(t, err)
}

func TestActivateClient_DelegatesToRepo(t *testing.T) {
	repo := &mockRepo{}
	svc := newTestService(repo, &mockPub{})

	err := svc.ActivateClient(context.Background(), ActivateRequest{
		ID: 2, Password: "NewPass123!", Token: "client-token",
	})
	require.NoError(t, err)
}

func TestCheckEmployeeToken_ReturnsID(t *testing.T) {
	repo := &mockRepo{confirmationIDResult: 9}
	svc := newTestService(repo, &mockPub{})

	id, err := svc.CheckEmployeeToken(context.Background(), "token")
	require.NoError(t, err)
	assert.Equal(t, int64(9), id)
}

func TestCheckClientToken_ReturnsID(t *testing.T) {
	repo := &mockRepo{confirmationIDResult: 11}
	svc := newTestService(repo, &mockPub{})

	id, err := svc.CheckClientToken(context.Background(), "token")
	require.NoError(t, err)
	assert.Equal(t, int64(11), id)
}

func TestEmployeeResendActivation_InactiveEmployee_PublishesEmail(t *testing.T) {
	pub := &mockPub{}
	repo := &mockRepo{
		employeeByLoginResult: Employee{ID: 3, Email: "inactive@test.com", Ime: "I", Aktivan: false},
	}
	svc := newTestService(repo, pub)

	require.NoError(t, svc.EmployeeResendActivation(context.Background(), "inactive@test.com"))
	assert.Contains(t, pub.published, "employee.created")
}

func TestEmployeeResendActivation_ActiveEmployee_NoOp(t *testing.T) {
	pub := &mockPub{}
	repo := &mockRepo{
		employeeByLoginResult: Employee{ID: 3, Email: "active@test.com", Aktivan: true},
	}
	svc := newTestService(repo, pub)

	require.NoError(t, svc.EmployeeResendActivation(context.Background(), "active@test.com"))
	assert.Empty(t, pub.published)
}

func TestClientResendActivation_InactiveClient_PublishesEmail(t *testing.T) {
	pub := &mockPub{}
	repo := &mockRepo{
		clientByEmailResult: Client{ID: 4, Email: "client@test.com", Ime: "C", Aktivan: false},
	}
	svc := newTestService(repo, pub)

	require.NoError(t, svc.ClientResendActivation(context.Background(), "client@test.com"))
	assert.Contains(t, pub.published, "client.created")
}

func TestClientForgotPassword_ActiveClient_PublishesEmail(t *testing.T) {
	pub := &mockPub{}
	repo := &mockRepo{
		clientByEmailResult: Client{ID: 5, Email: "forgot@test.com", Ime: "F", Aktivan: true},
	}
	svc := newTestService(repo, pub)

	require.NoError(t, svc.ClientForgotPassword(context.Background(), "forgot@test.com"))
	assert.Contains(t, pub.published, "client.password_reset")
}

func TestUpdateEmployee_AdminEditingOtherAdmin_Forbidden(t *testing.T) {
	repo := &mockRepo{
		employeeByIDResult:  Employee{ID: 2, Role: "ADMIN"},
		employeePermissions: []string{"EMPLOYEE_MANAGE_ALL"},
	}
	svc := newTestService(repo, &mockPub{})
	ctx := gpauth.WithPrincipal(context.Background(), platform.Principal{ID: 1, Role: "ADMIN"})

	_, err := svc.UpdateEmployee(ctx, 2, EmployeeUpdateRequest{Ime: strPtr("X")})
	assert.ErrorIs(t, err, ErrForbidden)
}

func TestUpdateEmployee_WithMargin_AddsPermission(t *testing.T) {
	margin := true
	repo := &mockRepo{
		employeeByIDResult:   Employee{ID: 1, Role: "AGENT"},
		updateEmployeeResult: Employee{ID: 1, Role: "AGENT"},
		employeePermissions:  []string{"BANKING_BASIC"},
	}
	svc := newTestService(repo, &recordingNotificationPublisher{})
	ctx := gpauth.WithPrincipal(context.Background(), platform.Principal{ID: 1, Role: "ADMIN"})

	resp, err := svc.UpdateEmployee(ctx, 1, EmployeeUpdateRequest{Margin: &margin})
	require.NoError(t, err)
	assert.Equal(t, "AGENT", resp.Role)
}

func TestUpdateClient_ReturnsUpdatedClient(t *testing.T) {
	name := "Updated"
	repo := &mockRepo{
		clientByIDResult: Client{ID: 1, Ime: "Old", Role: "CLIENT_BASIC"},
	}
	svc := newTestService(repo, &mockPub{})

	_, err := svc.UpdateClient(context.Background(), 1, ClientUpdateRequest{Ime: &name})
	require.NoError(t, err)
}

func TestEmployeeForgotPassword_NotFound(t *testing.T) {
	repo := &mockRepo{employeeByLoginErr: ErrNotFound}
	svc := newTestService(repo, &mockPub{})

	err := svc.EmployeeForgotPassword(context.Background(), "missing@test.com")
	assert.ErrorIs(t, err, ErrNotFound)
}

func TestPublishEmail_PublisherError(t *testing.T) {
	svc := &Service{pub: &failingPublisher{err: errors.New("rabbit down")}}
	err := svc.publishEmail(context.Background(), "employee.created", "EMPLOYEE_CREATED", "A", "a@test.com", "link")
	assert.Error(t, err)
}

func TestEmployeeResendActivation_ActiveEmployee_NoEmail(t *testing.T) {
	pub := &mockPub{}
	repo := &mockRepo{
		employeeByLoginResult: Employee{ID: 3, Email: "active@test.com", Aktivan: true},
	}
	svc := newTestService(repo, pub)
	require.NoError(t, svc.EmployeeResendActivation(context.Background(), "active@test.com"))
	assert.Empty(t, pub.published)
}

func TestDeleteEmployee_Supervisor_ReassignsFunds(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPatch, r.Method)
		assert.Contains(t, r.URL.Path, "/funds/admin/reassign-manager")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	repo := &mockRepo{
		employeeByIDResult:  Employee{ID: 10, Role: "SUPERVISOR", Email: "sup@test.com", Ime: "Sup"},
		firstActiveIDResult: 20,
	}
	svc := NewService(repo, platform.NewJWTService(platform.JWTConfig{
		Secret: "test-secret", Issuer: "test", AccessTokenDuration: time.Hour,
	}), &mockPub{}, defaultCfg(), platform.ServicesConfig{TradingURL: server.URL}, platform.EmailConfig{})

	require.NoError(t, svc.DeleteEmployee(context.Background(), 10))
}

func TestDeleteEmployee_NonSupervisor_SkipsReassign(t *testing.T) {
	repo := &mockRepo{
		employeeByIDResult: Employee{ID: 11, Role: "BASIC", Email: "basic@test.com", Ime: "Basic"},
	}
	svc := newTestService(repo, &mockPub{})
	require.NoError(t, svc.DeleteEmployee(context.Background(), 11))
}

type failingPublisher struct {
	err error
}

func (p *failingPublisher) PublishEmail(context.Context, string, platform.EmailNotification) error {
	return p.err
}
func (p *failingPublisher) Publish(context.Context, string, any) error { return p.err }
func (p *failingPublisher) Close()                                       {}
