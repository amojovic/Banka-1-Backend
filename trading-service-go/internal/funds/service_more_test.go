package funds

import (
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// dataQuerier returns populated rows for the "happy path with data" tests: a
// fund, a client position, transactions, and RETURNING ids for the inserts.
func dataQuerier() *fakeQuerier {
	return &fakeQuerier{
		execTag: tag("UPDATE 1"),
		rowFn: func(sql string, _ []any) *fakeRow {
			switch {
			case strings.Contains(sql, "INSERT INTO investment_funds"):
				return &fakeRow{vals: []any{int64(1), int64(0)}}
			case strings.Contains(sql, "INSERT INTO fund_holdings"):
				return &fakeRow{vals: []any{int64(1), int64(0)}}
			case strings.Contains(sql, "client_fund_transactions"):
				return &fakeRow{vals: []any{int64(1)}}
			case strings.Contains(sql, "client_fund_positions"):
				return &fakeRow{vals: positionRow(1, 3, 1, "100")}
			case strings.Contains(sql, "investment_funds"):
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			case strings.Contains(sql, "fund_holdings"):
				return &fakeRow{vals: holdingRow(1, 1, "AAPL", 10, "5.00")}
			default:
				return &fakeRow{err: pgx.ErrNoRows}
			}
		},
		rowsFn: func(sql string, _ []any) (*fakeRows, error) {
			switch {
			case strings.Contains(sql, "client_fund_positions"):
				return &fakeRows{data: [][]any{positionRow(1, 3, 1, "100")}}, nil
			case strings.Contains(sql, "client_fund_transactions"):
				return &fakeRows{data: [][]any{txRow(1, 3, 1, "50", true, "COMPLETED")}}, nil
			case strings.Contains(sql, "investment_funds"):
				return &fakeRows{data: [][]any{fundRow(1, "Alpha", "100.00")}}, nil
			default:
				return &fakeRows{}, nil // fund_holdings → empty (holdings value 0)
			}
		},
	}
}

// TestProductionConstructors exercises the real NewX wiring (covers the 0% prod
// constructors + PoolQRunner; the pool is nil but never dereferenced here).
func TestProductionConstructors(t *testing.T) {
	repo := NewRepositoryForTest(&fakeQuerier{})
	h := NewHoldingService(repo, okMarket(), discardLogger())
	sn := NewSnapshotService(repo, h, discardLogger())
	st := NewStatisticsService(sn)
	svc := NewService(repo, sn, st, h, okMarket(), emptyAccount(), emptyEmployee(), &stubPublisher{}, discardLogger())
	div := NewDividendService(repo, h, sn, okMarket(), emptyAccount(), svc, discardLogger())
	liq := NewLiquidationService(repo, h, okMarket(), emptyAccount(), sn, discardLogger())
	cb := NewOrderCallback(svc, h)
	if svc == nil || div == nil || liq == nil || cb == nil {
		t.Fatal("nil constructor result")
	}
}

func TestDiscovery_WithFunds(t *testing.T) {
	out, err := fundsSvc(dataQuerier()).Discovery(ctx(), "totalValue", "desc")
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestSupervisedBy_WithFunds(t *testing.T) {
	out, err := fundsSvc(dataQuerier()).SupervisedBy(ctx(), 7)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestMyPositions_WithPosition(t *testing.T) {
	out, err := fundsSvc(dataQuerier()).MyPositions(ctx(), 3)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestMyTransactions_WithData(t *testing.T) {
	out, err := fundsSvc(dataQuerier()).MyTransactions(ctx(), 3)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFundPerformance_WithPositions(t *testing.T) {
	out, err := fundsSvc(dataQuerier()).FundPerformance(ctx(), 1)
	if err != nil || out == nil {
		t.Fatalf("got %v %v", out, err)
	}
}

func TestCreateFund_Success(t *testing.T) {
	dto, err := fundsSvc(dataQuerier()).CreateFund(ctx(), "NewFund", nil, dec("100"), DividendReinvest, 7)
	if err != nil || dto == nil {
		t.Fatalf("got %+v %v", dto, err)
	}
}

func TestRedeem_Success(t *testing.T) {
	saved, err := fundsSvc(dataQuerier()).Redeem(ctx(), 1, 3, dec("50"), "acc")
	if err != nil || saved == nil {
		t.Fatalf("got %+v %v", saved, err)
	}
}

func TestReassignManager_WithFunds(t *testing.T) {
	if err := fundsSvc(dataQuerier()).ReassignManager(ctx(), 7, 8); err != nil {
		t.Fatal(err)
	}
}
