package funds

import (
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

func liqSvc(q *fakeQuerier) *LiquidationService {
	repo := NewRepositoryForTest(q)
	holdings := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	snapshots := NewSnapshotServiceForTest(repo, holdings, discardLogger())
	return NewLiquidationServiceForTest(repo, holdings, okMarket(), emptyAccount(), snapshots, discardLogger())
}

// liqQuerier serves a fund (FOR UPDATE), one AAPL holding (qty 10 @ 5.00) for
// both the List (Query) and FindHolding (QueryRow) paths, and a successful
// UPDATE tag for the reduce / liquidity writes.
func liqQuerier() *fakeQuerier {
	return &fakeQuerier{
		execTag: tag("UPDATE 1"),
		rowFn: func(sql string, _ []any) *fakeRow {
			switch {
			case strings.Contains(sql, "investment_funds"):
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			case strings.Contains(sql, "fund_holdings"):
				return &fakeRow{vals: holdingRow(1, 1, "AAPL", 10, "5.00")}
			default:
				return &fakeRow{err: pgx.ErrNoRows}
			}
		},
		rowsFn: func(sql string, _ []any) (*fakeRows, error) {
			if strings.Contains(sql, "fund_holdings") {
				return &fakeRows{data: [][]any{holdingRow(1, 1, "AAPL", 10, "5.00")}}, nil
			}
			return &fakeRows{}, nil
		},
	}
}

func TestNewUUIDv4_Format(t *testing.T) {
	id := newUUIDv4()
	if len(id) != 36 || strings.Count(id, "-") != 4 {
		t.Errorf("bad uuid: %q", id)
	}
}

func TestLiquidateForFund_NotFound(t *testing.T) {
	if _, err := liqSvc(&fakeQuerier{}).LiquidateForFund(ctx(), 1, dec("100"), "corr"); err == nil {
		t.Error("expected 404")
	}
}

func TestLiquidateForFund_EmptyHoldings(t *testing.T) {
	// fund exists, but no holdings → nothing sold, liquidity unchanged.
	q := &fakeQuerier{
		execTag: tag("UPDATE 1"),
		rowFn: func(sql string, _ []any) *fakeRow {
			if strings.Contains(sql, "investment_funds") {
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			}
			return &fakeRow{err: pgx.ErrNoRows}
		},
	}
	res, err := liqSvc(q).LiquidateForFund(ctx(), 1, dec("100"), "corr")
	if err != nil || res == nil || res.HoldingsSold != 0 {
		t.Fatalf("got %+v %v", res, err)
	}
}

func TestLiquidateForFund_SellsDown(t *testing.T) {
	res, err := liqSvc(liqQuerier()).LiquidateForFund(ctx(), 1, dec("1000"), "corr")
	if err != nil || res == nil || res.HoldingsSold != 1 {
		t.Fatalf("got %+v %v", res, err)
	}
}

func TestSellHolding_NotFound(t *testing.T) {
	if _, err := liqSvc(&fakeQuerier{}).SellHolding(ctx(), 1, "AAPL", 1); err == nil {
		t.Error("expected fund 404")
	}
}

func TestSellHolding_TickerNotOwned(t *testing.T) {
	// fund exists but holdings list is empty → ticker not owned.
	q := &fakeQuerier{
		execTag: tag("UPDATE 1"),
		rowFn: func(sql string, _ []any) *fakeRow {
			if strings.Contains(sql, "investment_funds") {
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			}
			return &fakeRow{err: pgx.ErrNoRows}
		},
	}
	if _, err := liqSvc(q).SellHolding(ctx(), 1, "AAPL", 1); err == nil {
		t.Error("expected ticker-not-owned 404")
	}
}

func TestSellHolding_QuantityTooHigh(t *testing.T) {
	if _, err := liqSvc(liqQuerier()).SellHolding(ctx(), 1, "AAPL", 999); err == nil {
		t.Error("expected quantity-too-high 404")
	}
}

func TestSellHolding_Success(t *testing.T) {
	res, err := liqSvc(liqQuerier()).SellHolding(ctx(), 1, "AAPL", 5)
	if err != nil || res == nil || res.QuantitySold != 5 {
		t.Fatalf("got %+v %v", res, err)
	}
}
