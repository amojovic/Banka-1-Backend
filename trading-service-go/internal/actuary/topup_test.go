package actuary

import (
	"context"
	"errors"
	"testing"
	"time"

	"banka1/trading-service-go/internal/clients"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/shopspring/decimal"
)

// errDB is a Querier whose Query/QueryRow/Exec can be made to fail, and whose
// rows can carry a scan error mid-iteration. It complements the fakeDB in
// repository_fake_test.go which has no query-error injection.
type errDB struct {
	queryErr error
	rows     *fakeRows
	row      *fakeRow
	execErr  error
}

func (d *errDB) QueryRow(_ context.Context, _ string, _ ...any) pgx.Row {
	if d.row != nil {
		return d.row
	}
	return &fakeRow{scanErr: pgx.ErrNoRows}
}
func (d *errDB) Query(_ context.Context, _ string, _ ...any) (pgx.Rows, error) {
	if d.queryErr != nil {
		return nil, d.queryErr
	}
	if d.rows != nil {
		return d.rows, nil
	}
	return &fakeRows{}, nil
}
func (d *errDB) Exec(_ context.Context, _ string, _ ...any) (pgconn.CommandTag, error) {
	return pgconn.CommandTag{}, d.execErr
}

// ---- repository query-error and scan-error paths ----

func TestSumCommissionByActuary_QueryErr(t *testing.T) {
	r := &Repository{db: &errDB{queryErr: errors.New("db")}}
	if _, err := r.SumCommissionByActuary(context.Background(), time.Now(), time.Now()); err == nil {
		t.Fatal("query error should propagate")
	}
}

func TestSumCommissionByActuary_ScanErr(t *testing.T) {
	r := &Repository{db: &errDB{rows: &fakeRows{rows: [][]any{{int64(1), "x", int64(1)}}, scanErr: errors.New("scan")}}}
	if _, err := r.SumCommissionByActuary(context.Background(), time.Now(), time.Now()); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestSumCommissionByActuary_BadDecimal(t *testing.T) {
	r := &Repository{db: &errDB{rows: &fakeRows{rows: [][]any{{int64(1), "not-a-number", int64(1)}}}}}
	if _, err := r.SumCommissionByActuary(context.Background(), time.Now(), time.Now()); err == nil {
		t.Fatal("bad commission decimal should error")
	}
}

func TestFindAllEmployeeIDs_QueryErr(t *testing.T) {
	r := &Repository{db: &errDB{queryErr: errors.New("db")}}
	if _, err := r.FindAllEmployeeIDs(context.Background()); err == nil {
		t.Fatal("query error should propagate")
	}
}

func TestFindAllEmployeeIDs_ScanErr(t *testing.T) {
	r := &Repository{db: &errDB{rows: &fakeRows{rows: [][]any{{int64(1)}}, scanErr: errors.New("scan")}}}
	if _, err := r.FindAllEmployeeIDs(context.Background()); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestFindEmployeeIDsIn_Empty(t *testing.T) {
	r := &Repository{db: &fakeDB{}}
	out, err := r.FindEmployeeIDsIn(context.Background(), nil)
	if err != nil || len(out) != 0 {
		t.Errorf("empty ids -> empty map, got %v %v", out, err)
	}
}

func TestFindEmployeeIDsIn_QueryErr(t *testing.T) {
	r := &Repository{db: &errDB{queryErr: errors.New("db")}}
	if _, err := r.FindEmployeeIDsIn(context.Background(), []int64{1}); err == nil {
		t.Fatal("query error should propagate")
	}
}

func TestFindEmployeeIDsIn_ScanErr(t *testing.T) {
	r := &Repository{db: &errDB{rows: &fakeRows{rows: [][]any{{int64(1)}}, scanErr: errors.New("scan")}}}
	if _, err := r.FindEmployeeIDsIn(context.Background(), []int64{1}); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestFindByEmployeeID_QueryError(t *testing.T) {
	r := &Repository{db: &errDB{row: &fakeRow{scanErr: errors.New("db")}}}
	if _, err := r.FindByEmployeeID(context.Background(), 1); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestFindByEmployeeID_BadReservedDecimal(t *testing.T) {
	r := &Repository{db: &fakeDB{row: &fakeRow{vals: []any{int64(1), nil, "0", "bad", false}}}}
	if _, err := r.FindByEmployeeID(context.Background(), 1); err == nil {
		t.Fatal("bad reserved decimal should error")
	}
}

func TestFindByEmployeeID_BadLimitDecimal(t *testing.T) {
	bad := "bad"
	r := &Repository{db: &fakeDB{row: &fakeRow{vals: []any{int64(1), &bad, "0", "0", false}}}}
	if _, err := r.FindByEmployeeID(context.Background(), 1); err == nil {
		t.Fatal("bad limit decimal should error")
	}
}

func TestFindByEmployeeIDForUpdate_ScanError(t *testing.T) {
	r := &Repository{db: &fakeDB{}}
	q := &errDB{row: &fakeRow{scanErr: errors.New("db")}}
	if _, err := r.FindByEmployeeIDForUpdate(context.Background(), q, 1); err == nil {
		t.Fatal("scan error should propagate")
	}
}

func TestFindByEmployeeIDForUpdate_BadUsedDecimal(t *testing.T) {
	r := &Repository{db: &fakeDB{}}
	q := &fakeDB{row: &fakeRow{vals: []any{int64(1), nil, "bad", "0", false}}}
	if _, err := r.FindByEmployeeIDForUpdate(context.Background(), q, 1); err == nil {
		t.Fatal("bad used decimal should error")
	}
}

func TestFindByEmployeeIDForUpdate_BadLimitDecimal(t *testing.T) {
	bad := "bad"
	r := &Repository{db: &fakeDB{}}
	q := &fakeDB{row: &fakeRow{vals: []any{int64(7), &bad, "0", "0", true}}}
	if _, err := r.FindByEmployeeIDForUpdate(context.Background(), q, 7); err == nil {
		t.Fatal("bad limit decimal should error")
	}
}

func TestFindByEmployeeIDForUpdate_WithLimit(t *testing.T) {
	lim := "300.00"
	r := &Repository{db: &fakeDB{}}
	q := &fakeDB{row: &fakeRow{vals: []any{int64(7), &lim, "10", "5", true}}}
	info, err := r.FindByEmployeeIDForUpdate(context.Background(), q, 7)
	if err != nil {
		t.Fatal(err)
	}
	if info.Limit == nil || !info.Limit.Equal(decimal.RequireFromString("300.00")) {
		t.Errorf("limit = %v", info.Limit)
	}
}

func TestFindByEmployeeIDForUpdate_BadReservedDecimal(t *testing.T) {
	r := &Repository{db: &fakeDB{}}
	q := &fakeDB{row: &fakeRow{vals: []any{int64(1), nil, "0", "bad", false}}}
	if _, err := r.FindByEmployeeIDForUpdate(context.Background(), q, 1); err == nil {
		t.Fatal("bad reserved decimal should error")
	}
}

func TestGetAgents_MultiplePages(t *testing.T) {
	// two pages of agents -> the pageIndex < TotalPages loop iterates twice
	emp := &pagingSearcher{
		getEmp: &clients.Employee{ID: 1, Role: agentRole()},
		pages: []*clients.EmployeePage{
			{Content: []clients.Employee{{ID: 1, Role: agentRole()}}, TotalPages: 2},
			{Content: []clients.Employee{{ID: 2, Role: agentRole()}}, TotalPages: 2},
		},
	}
	repo := &stubActuaryRepo{info: &ActuaryInfo{EmployeeID: 1}}
	svc := newTestActuaryService(repo, emp)
	page, err := svc.GetAgents(context.Background(), nil, nil, nil, nil, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Content) != 2 {
		t.Errorf("expected 2 agents across pages, got %d", len(page.Content))
	}
}

func TestFindOrCreate_FindError(t *testing.T) {
	r := &Repository{db: &errDB{row: &fakeRow{scanErr: errors.New("db")}}}
	if _, err := r.FindOrCreate(context.Background(), 1); err == nil {
		t.Fatal("find error should propagate")
	}
}

func TestFindOrCreate_ExecError(t *testing.T) {
	// FindByEmployeeID returns nil (no rows), then Exec fails
	r := &Repository{db: &errDB{row: &fakeRow{scanErr: pgx.ErrNoRows}, execErr: errors.New("db")}}
	if _, err := r.FindOrCreate(context.Background(), 1); err == nil {
		t.Fatal("insert error should propagate")
	}
}

func TestFindOrCreate_CreatesThenReloads(t *testing.T) {
	// First lookup: no rows -> insert (Exec ok) -> reload returns no rows again
	// (the fakeDB always returns the same row). Use a fakeDB returning no rows so
	// the create path + reload both run without error.
	r := &Repository{db: &fakeDB{row: &fakeRow{scanErr: pgx.ErrNoRows}}}
	info, err := r.FindOrCreate(context.Background(), 1)
	if err != nil {
		t.Fatal(err)
	}
	if info != nil {
		t.Errorf("reload after insert returns nil with this fake, got %+v", info)
	}
}

func TestResetLimit_ExecError(t *testing.T) {
	r := &Repository{db: &errDB{execErr: errors.New("db")}}
	if err := r.ResetLimit(context.Background(), 1); err == nil {
		t.Fatal("exec error should propagate")
	}
}

func TestSetNeedApproval_ExecError(t *testing.T) {
	r := &Repository{db: &errDB{execErr: errors.New("db")}}
	if err := r.SetNeedApproval(context.Background(), 1, true); err == nil {
		t.Fatal("exec error should propagate")
	}
}

func TestResetAllLimits_ExecError(t *testing.T) {
	r := &Repository{db: &errDB{execErr: errors.New("db")}}
	if err := r.ResetAllLimits(context.Background()); err == nil {
		t.Fatal("exec error should propagate")
	}
}

func TestUpdateReservedLimit_ExecError(t *testing.T) {
	r := &Repository{db: &fakeDB{}}
	q := &errDB{execErr: errors.New("db")}
	if err := r.UpdateReservedLimit(context.Background(), q, 1, decimal.NewFromInt(1)); err == nil {
		t.Fatal("exec error should propagate")
	}
}

func TestUpdateReservedAndUsedLimit_ExecError(t *testing.T) {
	r := &Repository{db: &fakeDB{}}
	q := &errDB{execErr: errors.New("db")}
	if err := r.UpdateReservedAndUsedLimit(context.Background(), q, 1, decimal.NewFromInt(1), decimal.NewFromInt(2)); err == nil {
		t.Fatal("exec error should propagate")
	}
}

// ---- service: NewServiceForTest + extra branches ----

func TestNewServiceForTest(t *testing.T) {
	svc := NewServiceForTest(&stubActuaryRepo{}, &stubEmployeeSearcher{})
	if svc == nil {
		t.Fatal("NewServiceForTest returned nil")
	}
}

func TestSetLimit_UpdateError(t *testing.T) {
	info := &ActuaryInfo{EmployeeID: 99, UsedLimit: decimal.NewFromFloat(100)}
	emp := &stubEmployeeSearcher{emp: &clients.Employee{ID: 99, Role: agentRole()}}
	repo := &stubActuaryRepo{info: info, updateErr: errors.New("db")}
	svc := newTestActuaryService(repo, emp)
	if err := svc.SetLimit(context.Background(), 1, "SUP", 99, decimal.NewFromFloat(500)); err == nil {
		t.Fatal("UpdateLimit error should propagate")
	}
}

func TestSetLimit_WithExistingLimit_RecordsOldValue(t *testing.T) {
	old := decimal.NewFromFloat(300)
	info := &ActuaryInfo{EmployeeID: 99, UsedLimit: decimal.NewFromFloat(100), Limit: &old}
	emp := &stubEmployeeSearcher{emp: &clients.Employee{ID: 99, Role: agentRole()}}
	repo := &stubActuaryRepo{info: info}
	svc := newTestActuaryService(repo, emp) // nil auditor -> recordAgentAudit returns early
	if err := svc.SetLimit(context.Background(), 1, "SUP", 99, decimal.NewFromFloat(500)); err != nil {
		t.Fatal(err)
	}
}

func TestResetLimit_NotAgent(t *testing.T) {
	sup := "SUPERVISOR"
	emp := &stubEmployeeSearcher{emp: &clients.Employee{ID: 99, Role: &sup}}
	svc := newTestActuaryService(&stubActuaryRepo{}, emp)
	if err := svc.ResetLimit(context.Background(), 1, "SUP", 99); err == nil {
		t.Fatal("non-agent reset should error")
	}
}

func TestResetLimit_ResetError(t *testing.T) {
	emp := &stubEmployeeSearcher{emp: &clients.Employee{ID: 99, Role: agentRole()}}
	repo := &stubActuaryRepo{info: &ActuaryInfo{EmployeeID: 99}, resetErr: errors.New("db")}
	svc := newTestActuaryService(repo, emp)
	if err := svc.ResetLimit(context.Background(), 1, "SUP", 99); err == nil {
		t.Fatal("ResetLimit error should propagate")
	}
}

func TestResetLimit_NilInfoOldUsedDefault(t *testing.T) {
	// FindByEmployeeID returns nil info -> oldUsed stays "0"
	emp := &stubEmployeeSearcher{emp: &clients.Employee{ID: 99, Role: agentRole()}}
	repo := &stubActuaryRepo{info: nil}
	svc := newTestActuaryService(repo, emp)
	if err := svc.ResetLimit(context.Background(), 1, "SUP", 99); err != nil {
		t.Fatal(err)
	}
}

func TestSetNeedApproval_RepoError(t *testing.T) {
	emp := &stubEmployeeSearcher{emp: &clients.Employee{ID: 99, Role: agentRole()}}
	repo := &stubActuaryRepo{approvalErr: errors.New("db")}
	svc := newTestActuaryService(repo, emp)
	if err := svc.SetNeedApproval(context.Background(), 99, true); err == nil {
		t.Fatal("SetNeedApproval error should propagate")
	}
}

func TestProfitByActuary_EnrichesNamesAndPaginates(t *testing.T) {
	rows := []ProfitRow{{UserID: 1, TotalCommission: decimal.NewFromFloat(300), TransactionCount: 3}}
	repo := &stubActuaryRepo{profitRows: rows}
	// employee 1 enriched via GetEmployee; page sweep finds agent 2 (zero commission)
	emp := &pagingSearcher{
		getEmp: &clients.Employee{ID: 1, Ime: sp("A"), Prezime: sp("B"), Pozicija: sp("AGENT")},
		pages: []*clients.EmployeePage{
			{Content: []clients.Employee{{ID: 1, Role: agentRole()}, {ID: 2, Role: agentRole()}}, TotalPages: 1},
		},
	}
	svc := newTestActuaryService(repo, emp)
	out, err := svc.ProfitByActuary(context.Background(), ptr(time.Now()), ptr(time.Now()))
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 2 {
		t.Errorf("expected 2 entries, got %d", len(out))
	}
	// sorted by commission desc -> userID 1 first
	if out[0].UserID != 1 {
		t.Errorf("expected highest-commission actuary first, got %d", out[0].UserID)
	}
}

func TestProfitByActuary_SearchErrorBreaksLoop(t *testing.T) {
	rows := []ProfitRow{{UserID: 1, TotalCommission: decimal.NewFromFloat(100), TransactionCount: 1}}
	repo := &stubActuaryRepo{profitRows: rows}
	emp := &stubEmployeeSearcher{pageErr: errors.New("emp down")}
	svc := newTestActuaryService(repo, emp)
	out, err := svc.ProfitByActuary(context.Background(), nil, nil)
	if err != nil {
		t.Fatal("search error mid-enrichment should break, not fail")
	}
	if len(out) != 1 {
		t.Errorf("expected the DB row to still be returned, got %d", len(out))
	}
}

// pagingSearcher returns a fixed GetEmployee result and a sequence of pages.
type pagingSearcher struct {
	getEmp    *clients.Employee
	getErr    error
	pages     []*clients.EmployeePage
	pageIdx   int
	searchErr error
}

func (s *pagingSearcher) GetEmployee(_ context.Context, _ int64) (*clients.Employee, error) {
	return s.getEmp, s.getErr
}
func (s *pagingSearcher) SearchEmployees(_ context.Context, _, _, _, _ *string, page, _ int) (*clients.EmployeePage, error) {
	if s.searchErr != nil {
		return nil, s.searchErr
	}
	if page < len(s.pages) {
		return s.pages[page], nil
	}
	return &clients.EmployeePage{}, nil
}

func ptr[T any](v T) *T { return &v }
