package funds

import (
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func day(y int, m time.Month, d int) time.Time {
	return time.Date(y, m, d, 0, 0, 0, 0, time.UTC)
}

func snapsFor(dates []time.Time, totals []string) *fakeRows {
	rows := make([][]any, len(dates))
	for i := range dates {
		rows[i] = snapshotRow(int64(i+1), 2, dates[i], "1.00", "1.00", totals[i])
	}
	return &fakeRows{data: rows}
}

func TestIndexForDate_Empty(t *testing.T) {
	if indexForDate(nil, time.Now()) != nil {
		t.Error("nil for empty")
	}
}

func TestIndexForDate_ZeroBase(t *testing.T) {
	snaps := []FundValueSnapshot{{SnapshotDate: day(2025, 1, 1), TotalValue: dec("0")}}
	if indexForDate(snaps, day(2025, 1, 1)) != nil {
		t.Error("nil for zero base")
	}
}

func TestIndexForDate_Match(t *testing.T) {
	snaps := []FundValueSnapshot{
		{SnapshotDate: day(2025, 1, 1), TotalValue: dec("100")},
		{SnapshotDate: day(2025, 2, 1), TotalValue: dec("150")},
	}
	idx := indexForDate(snaps, day(2025, 2, 1))
	if idx == nil || !idx.Equal(dec("150")) {
		t.Errorf("expected 150, got %v", idx)
	}
}

func TestIndexForDate_NoMatch(t *testing.T) {
	snaps := []FundValueSnapshot{{SnapshotDate: day(2025, 1, 1), TotalValue: dec("100")}}
	if indexForDate(snaps, day(2025, 6, 1)) != nil {
		t.Error("nil for no match")
	}
}

func TestNewSnapshotService(t *testing.T) {
	if NewSnapshotService(nil, nil, discardLogger()) == nil {
		t.Error("nil")
	}
}

func TestSnapshot_History(t *testing.T) {
	q := &fakeQuerier{rows: snapsFor([]time.Time{day(2025, 1, 1)}, []string{"100"})}
	repo := NewRepositoryForTest(q)
	svc := NewSnapshotServiceForTest(repo, nil, discardLogger())
	out, err := svc.History(ctx(), 2)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestSnapshot_MonthlySnapshots_Empty(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{}}
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	out, err := svc.MonthlySnapshots(ctx(), 2)
	if err != nil || out != nil {
		t.Errorf("expected nil,nil got %v %v", out, err)
	}
}

func TestSnapshot_MonthlySnapshots_LastPerMonth(t *testing.T) {
	dates := []time.Time{day(2025, 1, 5), day(2025, 1, 20), day(2025, 2, 3)}
	q := &fakeQuerier{rows: snapsFor(dates, []string{"100", "110", "120"})}
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	out, err := svc.MonthlySnapshots(ctx(), 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 2 {
		t.Fatalf("expected 2 months, got %d", len(out))
	}
	// Jan keeps the later (20th) snapshot → 110.
	if !out[0].TotalValue.Equal(dec("110")) {
		t.Errorf("Jan last = %v, want 110", out[0].TotalValue)
	}
	if !out[1].TotalValue.Equal(dec("120")) {
		t.Errorf("Feb = %v, want 120", out[1].TotalValue)
	}
}

func TestSnapshot_MonthlySnapshots_HistoryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errSnapshotNoHistory}
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	if _, err := svc.MonthlySnapshots(ctx(), 2); err == nil {
		t.Error("expected error")
	}
}

func TestSnapshot_AveragePerformance_NoHistory(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{}}
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	out, err := svc.AveragePerformance(ctx(), 2)
	if err != nil || out != nil {
		t.Errorf("expected nil,nil got %v %v", out, err)
	}
}

func TestSnapshot_AveragePerformance_ZeroBase(t *testing.T) {
	q := &fakeQuerier{rows: snapsFor([]time.Time{day(2025, 1, 1)}, []string{"0"})}
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	out, err := svc.AveragePerformance(ctx(), 2)
	if err != nil || out != nil {
		t.Errorf("expected nil,nil got %v %v", out, err)
	}
}

// TestSnapshot_AveragePerformance_FullPath routes the three distinct queries
// (base history, active funds, per-fund snapshots) by SQL substring.
func TestSnapshot_AveragePerformance_FullPath(t *testing.T) {
	base := snapsFor([]time.Time{day(2025, 1, 1), day(2025, 2, 1)}, []string{"100", "200"})
	q := &fakeQuerier{
		rowsFn: func(sql string, _ []any) (*fakeRows, error) {
			switch {
			case contains(sql, "investment_funds WHERE deleted = false"):
				return &fakeRows{data: [][]any{fundRow(2, "Self", "1.00")}}, nil
			case contains(sql, "fund_value_snapshots"):
				// fresh copy each call (idx resets)
				return snapsFor([]time.Time{day(2025, 1, 1), day(2025, 2, 1)}, []string{"100", "200"}), nil
			}
			return &fakeRows{}, nil
		},
	}
	_ = base
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	out, err := svc.AveragePerformance(ctx(), 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 2 {
		t.Fatalf("expected 2 points, got %d", len(out))
	}
	if out[1].AveragePerformanceIndex == nil {
		t.Error("expected non-nil average on second point")
	}
}

func TestSnapshot_AveragePerformance_FundsError(t *testing.T) {
	q := &fakeQuerier{
		rowsFn: func(sql string, _ []any) (*fakeRows, error) {
			if contains(sql, "investment_funds WHERE deleted = false") {
				return nil, errSnapshotNoHistory
			}
			return snapsFor([]time.Time{day(2025, 1, 1)}, []string{"100"}), nil
		},
	}
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	if _, err := svc.AveragePerformance(ctx(), 2); err == nil {
		t.Error("expected error")
	}
}

func TestSnapshot_Record(t *testing.T) {
	// FindFundByID (row) then UpsertSnapshot (RETURNING id) — route by SQL.
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			if contains(sql, "FROM investment_funds") {
				return &fakeRow{vals: fundRow(2, "F", "100.00")}
			}
			return &fakeRow{vals: []any{int64(7)}} // upsert RETURNING id
		},
	}
	repo := NewRepositoryForTest(q)
	hold := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	svc := NewSnapshotServiceForTest(repo, hold, discardLogger())
	snap, err := svc.Record(ctx(), 2, day(2025, 1, 1))
	if err != nil {
		t.Fatal(err)
	}
	if !snap.TotalValue.Equal(dec("100.00")) {
		t.Errorf("total = %v, want 100.00", snap.TotalValue)
	}
}

func TestSnapshot_Record_FundError(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	repo := NewRepositoryForTest(q)
	hold := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	svc := NewSnapshotServiceForTest(repo, hold, discardLogger())
	if _, err := svc.Record(ctx(), 2, time.Now()); err == nil {
		t.Error("expected error")
	}
}

func TestSnapshot_RecordSilently_SwallowsError(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	repo := NewRepositoryForTest(q)
	hold := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	svc := NewSnapshotServiceForTest(repo, hold, discardLogger())
	svc.RecordSilently(ctx(), 2) // must not panic
}

func TestSnapshot_CaptureDailySnapshots(t *testing.T) {
	q := &fakeQuerier{
		rowsFn: func(sql string, _ []any) (*fakeRows, error) {
			if contains(sql, "investment_funds WHERE deleted = false") {
				return &fakeRows{data: [][]any{fundRow(2, "F", "10.00")}}, nil
			}
			return &fakeRows{}, nil // holdings active empty
		},
		rowFn: func(sql string, _ []any) *fakeRow {
			if contains(sql, "FROM investment_funds WHERE id") {
				return &fakeRow{vals: fundRow(2, "F", "10.00")}
			}
			return &fakeRow{vals: []any{int64(1)}} // upsert
		},
	}
	repo := NewRepositoryForTest(q)
	hold := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	svc := NewSnapshotServiceForTest(repo, hold, discardLogger())
	svc.CaptureDailySnapshots(ctx()) // must not panic
}

func TestSnapshot_CaptureDailySnapshots_FundsError(t *testing.T) {
	q := &fakeQuerier{queryErr: errSnapshotNoHistory}
	svc := NewSnapshotServiceForTest(NewRepositoryForTest(q), nil, discardLogger())
	svc.CaptureDailySnapshots(ctx()) // logs + returns, must not panic
}

func TestSnapshot_Pool(t *testing.T) {
	repo := NewRepository(nil)
	svc := NewSnapshotServiceForTest(repo, nil, discardLogger())
	if svc.pool() != nil {
		t.Error("expected nil pool")
	}
}

// helpers --------------------------------------------------------------------

func contains(s, sub string) bool { return strings.Contains(s, sub) }
