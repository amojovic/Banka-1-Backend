package http

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"banka1/trading-service-go/internal/audit"
	"banka1/trading-service-go/internal/clients"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
)

// ---- pure helpers ----

func TestFirstNonEmpty(t *testing.T) {
	assert.Equal(t, "b", firstNonEmpty("", "  ", "b", "c"))
	assert.Equal(t, "", firstNonEmpty("", "   "))
}

func TestContainsAuditUser(t *testing.T) {
	entry := map[string]any{"actorName": "Alice Smith", "targetName": "Bob"}
	assert.True(t, containsAuditUser(entry, "alice"))
	assert.True(t, containsAuditUser(entry, "bob"))
	assert.False(t, containsAuditUser(entry, "zzz"))
	// non-string values are ignored
	assert.False(t, containsAuditUser(map[string]any{"actorName": 5}, "5"))
}

func TestParseAuditBound(t *testing.T) {
	d, ok := parseAuditBound("", true)
	assert.True(t, ok)
	assert.Nil(t, d)
	d, ok = parseAuditBound("2026-05-18T14:30:00", true)
	assert.True(t, ok)
	assert.NotNil(t, d)
	// bare date, end-of-day expansion
	d, ok = parseAuditBound("2026-05-18", false)
	assert.True(t, ok)
	assert.Equal(t, 23, d.Hour())
	// bare date, start-of-day
	d, ok = parseAuditBound("2026-05-18", true)
	assert.True(t, ok)
	assert.Equal(t, 0, d.Hour())
	// invalid
	_, ok = parseAuditBound("nonsense", true)
	assert.False(t, ok)
}

// ---- audit handlers over a stubbed service ----

type auditRepoStub struct{}

func (auditRepoStub) Pool() *pgxpool.Pool                                 { return nil }
func (auditRepoStub) Insert(context.Context, audit.Querier, *audit.Entry) error { return nil }
func (auditRepoStub) Search(context.Context, audit.Querier, audit.SearchFilter) ([]audit.Entry, int64, error) {
	return nil, 0, nil
}

func newAuditHandlers() *Handlers {
	svc := audit.NewServiceForTest(auditRepoStub{}, quietLogger())
	emp := clients.NewEmployeeClient("http://user", nil, okDoer{})
	return &Handlers{app: &App{Audit: svc, Employees: emp}}
}

func TestAuditLog_Empty(t *testing.T) {
	h := newAuditHandlers()
	w := httptest.NewRecorder()
	h.AuditLog(w, fundsReq(http.MethodGet, "/audit", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestAuditLog_BadActionType(t *testing.T) {
	h := newAuditHandlers()
	w := httptest.NewRecorder()
	h.AuditLog(w, fundsReq(http.MethodGet, "/audit?actionType=BOGUS", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestAuditLog_BadActorID(t *testing.T) {
	h := newAuditHandlers()
	w := httptest.NewRecorder()
	h.AuditLog(w, fundsReq(http.MethodGet, "/audit?actorId=x", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestAuditLogLegacy_Empty(t *testing.T) {
	h := newAuditHandlers()
	w := httptest.NewRecorder()
	h.AuditLogLegacy(w, fundsReq(http.MethodGet, "/audit-log", ""))
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestAuditLogLegacy_WithUserFilter(t *testing.T) {
	h := newAuditHandlers()
	w := httptest.NewRecorder()
	h.AuditLogLegacy(w, fundsReq(http.MethodGet, "/audit-log?user=alice&actionType=BOGUS", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code) // bad actionType
}

func TestAuditLogLegacy_BadFrom(t *testing.T) {
	h := newAuditHandlers()
	w := httptest.NewRecorder()
	h.AuditLogLegacy(w, fundsReq(http.MethodGet, "/audit-log?from=nonsense", ""))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}
