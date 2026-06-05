package funds

import (
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

func newCallback(q *fakeQuerier) *ServiceCallback {
	repo := NewRepositoryForTest(q)
	holdings := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	snapshots := NewSnapshotServiceForTest(repo, holdings, discardLogger())
	stats := NewStatisticsServiceForTest(snapshots)
	svc := NewServiceForTest(repo, snapshots, stats, holdings, okMarket(), emptyAccount(),
		emptyEmployee(), &stubPublisher{}, FakeQRunner(q), discardLogger())
	return NewServiceCallbackForTest(svc, holdings)
}

func TestCallback_AddHolding_Success(t *testing.T) {
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			if strings.Contains(sql, "INSERT INTO fund_holdings") {
				return &fakeRow{vals: []any{int64(1), int64(0)}} // RETURNING id, version
			}
			return &fakeRow{err: pgx.ErrNoRows}
		},
	}
	if err := newCallback(q).AddHolding(ctx(), 1, "AAPL", 5, dec("10")); err != nil {
		t.Fatal(err)
	}
}

func TestCallback_AddHolding_BadInput(t *testing.T) {
	if err := newCallback(&fakeQuerier{}).AddHolding(ctx(), 1, "AAPL", 0, dec("10")); err == nil {
		t.Error("expected validation error for qty<=0")
	}
}

func TestCallback_DebitLiquidity_NoOp(t *testing.T) {
	if err := newCallback(&fakeQuerier{}).DebitLiquidity(ctx(), 1, dec("0"), "reason"); err != nil {
		t.Fatal(err)
	}
}
