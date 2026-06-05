package funds

import (
	"errors"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// fundsSvc assembles a full *Service over the given fakeQuerier and the
// real-client-over-stub-doer helpers (okMarket/emptyAccount/emptyEmployee).
func fundsSvc(q *fakeQuerier) *Service {
	repo := NewRepositoryForTest(q)
	holdings := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	snapshots := NewSnapshotServiceForTest(repo, holdings, discardLogger())
	stats := NewStatisticsServiceForTest(snapshots)
	return NewServiceForTest(repo, snapshots, stats, holdings, okMarket(), emptyAccount(),
		emptyEmployee(), &stubPublisher{}, FakeQRunner(q), discardLogger())
}

// fundRowQuerier routes QueryRow by SQL: EXISTS → exists flag, investment_funds →
// a fund row, else ErrNoRows. Query paths return empty rows by default.
func fundRowQuerier(exists bool) *fakeQuerier {
	return &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			if strings.Contains(sql, "EXISTS") {
				return &fakeRow{vals: []any{exists}}
			}
			if strings.Contains(sql, "investment_funds") {
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			}
			return &fakeRow{err: pgx.ErrNoRows}
		},
	}
}

func TestDiscovery_Empty(t *testing.T) {
	out, err := fundsSvc(&fakeQuerier{}).Discovery(ctx(), "naziv", "asc")
	if err != nil || len(out) != 0 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestDiscovery_QueryError(t *testing.T) {
	if _, err := fundsSvc(&fakeQuerier{queryErr: errors.New("db")}).Discovery(ctx(), "", ""); err == nil {
		t.Error("expected error")
	}
}

func TestSupervisedBy_Empty(t *testing.T) {
	out, err := fundsSvc(&fakeQuerier{}).SupervisedBy(ctx(), 7)
	if err != nil || len(out) != 0 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestMyPositions_Empty(t *testing.T) {
	out, err := fundsSvc(&fakeQuerier{}).MyPositions(ctx(), 3)
	if err != nil || len(out) != 0 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestBankPositions_Empty(t *testing.T) {
	if _, err := fundsSvc(&fakeQuerier{}).BankPositions(ctx()); err != nil {
		t.Fatal(err)
	}
}

func TestMyTransactions_Empty(t *testing.T) {
	out, err := fundsSvc(&fakeQuerier{}).MyTransactions(ctx(), 3)
	if err != nil || len(out) != 0 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestDetails_NotFound(t *testing.T) {
	if _, err := fundsSvc(&fakeQuerier{}).Details(ctx(), 1); err == nil {
		t.Error("expected 404")
	}
}

func TestDetails_Success(t *testing.T) {
	dto, err := fundsSvc(fundRowQuerier(true)).Details(ctx(), 1)
	if err != nil || dto == nil || dto.Naziv != "Alpha" {
		t.Fatalf("got %+v %v", dto, err)
	}
}

func TestAnalytics_Success(t *testing.T) {
	a, err := fundsSvc(fundRowQuerier(true)).Analytics(ctx(), 1)
	if err != nil || a == nil {
		t.Fatalf("got %+v %v", a, err)
	}
}

func TestAnalytics_NotFound(t *testing.T) {
	if _, err := fundsSvc(&fakeQuerier{}).Analytics(ctx(), 1); err == nil {
		t.Error("expected 404")
	}
}

func TestFundPositions_NotFound(t *testing.T) {
	if _, err := fundsSvc(fundRowQuerier(false)).FundPositions(ctx(), 1); err == nil {
		t.Error("expected 404")
	}
}

func TestFundPositions_Success(t *testing.T) {
	if _, err := fundsSvc(fundRowQuerier(true)).FundPositions(ctx(), 1); err != nil {
		t.Fatal(err)
	}
}

func TestFundTransactions_NotFound(t *testing.T) {
	if _, err := fundsSvc(fundRowQuerier(false)).FundTransactions(ctx(), 1); err == nil {
		t.Error("expected 404")
	}
}

func TestFundTransactions_Success(t *testing.T) {
	if _, err := fundsSvc(fundRowQuerier(true)).FundTransactions(ctx(), 1); err != nil {
		t.Fatal(err)
	}
}

func TestFundPerformance_NotFound(t *testing.T) {
	if _, err := fundsSvc(&fakeQuerier{}).FundPerformance(ctx(), 1); err == nil {
		t.Error("expected 404")
	}
}

func TestFundPerformance_Success(t *testing.T) {
	if _, err := fundsSvc(fundRowQuerier(true)).FundPerformance(ctx(), 1); err != nil {
		t.Fatal(err)
	}
}

func TestDebitLiquidity_NoOp(t *testing.T) {
	if err := fundsSvc(&fakeQuerier{}).DebitLiquidity(ctx(), 1, dec("0"), "x"); err != nil {
		t.Fatal(err)
	}
}

func TestDebitLiquidity_NotFound(t *testing.T) {
	if err := fundsSvc(&fakeQuerier{}).DebitLiquidity(ctx(), 1, dec("10"), "x"); err == nil {
		t.Error("expected 404")
	}
}

func TestDebitLiquidity_Success(t *testing.T) {
	q := fundRowQuerier(true)
	q.execTag = tag("UPDATE 1")
	if err := fundsSvc(q).DebitLiquidity(ctx(), 1, dec("10"), "order"); err != nil {
		t.Fatal(err)
	}
}

func TestReassignManager_Empty(t *testing.T) {
	if err := fundsSvc(&fakeQuerier{}).ReassignManager(ctx(), 1, 2); err != nil {
		t.Fatal(err)
	}
}

func TestCreateFund_InsertError(t *testing.T) {
	// default querier: InsertFund's RETURNING QueryRow → ErrNoRows → scan error.
	if _, err := fundsSvc(&fakeQuerier{}).CreateFund(ctx(), "F", nil, dec("100"), "", 7); err == nil {
		t.Error("expected insert error")
	}
}

func TestGetEnrichedHoldings_Success(t *testing.T) {
	out, err := fundsSvc(fundRowQuerier(true)).GetEnrichedHoldings(ctx(), 1)
	if err != nil || len(out) != 0 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestGetEnrichedHoldings_NotFound(t *testing.T) {
	if _, err := fundsSvc(fundRowQuerier(false)).GetEnrichedHoldings(ctx(), 1); err == nil {
		t.Error("expected 404")
	}
}
