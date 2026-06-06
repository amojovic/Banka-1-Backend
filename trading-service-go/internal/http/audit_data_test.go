package http

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"banka1/trading-service-go/internal/audit"
	"banka1/trading-service-go/internal/clients"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
)

// dataAuditRepo returns a couple of rows so the AuditLog mapping loop and the
// AuditLogLegacy actor/target resolution + user-filter branches all execute.
type dataAuditRepo struct{}

func (dataAuditRepo) Pool() *pgxpool.Pool                                 { return nil }
func (dataAuditRepo) Insert(context.Context, audit.Querier, *audit.Entry) error { return nil }
func (dataAuditRepo) Search(context.Context, audit.Querier, audit.SearchFilter) ([]audit.Entry, int64, error) {
	aid := int64(7)
	an := "System Actor"
	tt := "EMPLOYEE"
	tid := "9"
	det := "limit changed"
	return []audit.Entry{
		{ID: 1, ActorID: &aid, ActorName: &an, ActionType: "ACTUARY_LIMIT_SET", TargetType: &tt, TargetID: &tid, Details: &det, CreatedAt: time.Now()},
		{ID: 2, ActorName: &an, ActionType: "TAX_RUN_MANUAL", CreatedAt: time.Now()}, // SYSTEM (no ActorID)
	}, 2, nil
}

func newAuditHandlersWithData() *Handlers {
	svc := audit.NewServiceForTest(dataAuditRepo{}, quietLogger())
	emp := clients.NewEmployeeClient("http://user", nil, okDoer{})
	return &Handlers{app: &App{Audit: svc, Employees: emp}}
}

func TestAuditLog_WithRows(t *testing.T) {
	h := newAuditHandlersWithData()
	w := httptest.NewRecorder()
	h.AuditLog(w, fundsReq(http.MethodGet, "/audit?page=0&size=10", ""))
	assert.Equal(t, http.StatusOK, w.Code)
	assert.Contains(t, w.Body.String(), "ACTUARY_LIMIT_SET")
}

func TestAuditLogLegacy_WithRows(t *testing.T) {
	h := newAuditHandlersWithData()
	w := httptest.NewRecorder()
	h.AuditLogLegacy(w, fundsReq(http.MethodGet, "/audit-log", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestAuditLogLegacy_WithUserFilterMatch(t *testing.T) {
	h := newAuditHandlersWithData()
	w := httptest.NewRecorder()
	// "system" matches the stored actorName → the filter keeps the rows.
	h.AuditLogLegacy(w, fundsReq(http.MethodGet, "/audit-log?user=system", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}
