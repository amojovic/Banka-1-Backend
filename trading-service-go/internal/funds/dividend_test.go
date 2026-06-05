package funds

import (
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

func divSvc(q *fakeQuerier) *DividendService {
	repo := NewRepositoryForTest(q)
	holdings := NewHoldingServiceForTest(repo, okMarket(), discardLogger())
	snapshots := NewSnapshotServiceForTest(repo, holdings, discardLogger())
	stats := NewStatisticsServiceForTest(snapshots)
	svc := NewServiceForTest(repo, snapshots, stats, holdings, okMarket(), emptyAccount(),
		emptyEmployee(), &stubPublisher{}, FakeQRunner(q), discardLogger())
	return NewDividendServiceForTest(repo, holdings, snapshots, okMarket(), emptyAccount(),
		svc, FakeQRunner(q), discardLogger())
}

// divQuerier routes the dividend write paths: distribution + payout RETURNING
// ids, a fund (FOR UPDATE), an AAPL holding, and optionally a client position.
func divQuerier(withPositions bool) *fakeQuerier {
	return &fakeQuerier{
		execTag: tag("UPDATE 1"),
		rowFn: func(sql string, _ []any) *fakeRow {
			switch {
			case strings.Contains(sql, "INSERT INTO fund_dividend_distributions"):
				return &fakeRow{vals: []any{int64(1)}}
			case strings.Contains(sql, "INSERT INTO fund_dividend_payouts"):
				return &fakeRow{vals: []any{int64(1)}}
			case strings.Contains(sql, "fund_dividend_distributions"):
				return &fakeRow{err: pgx.ErrNoRows} // FindDistribution → not found
			case strings.Contains(sql, "INSERT INTO fund_holdings"):
				return &fakeRow{vals: []any{int64(1), int64(0)}}
			case strings.Contains(sql, "fund_holdings"):
				return &fakeRow{vals: holdingRow(1, 1, "AAPL", 10, "5.00")}
			case strings.Contains(sql, "investment_funds"):
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			default:
				return &fakeRow{err: pgx.ErrNoRows}
			}
		},
		rowsFn: func(sql string, _ []any) (*fakeRows, error) {
			if withPositions && strings.Contains(sql, "client_fund_positions") {
				return &fakeRows{data: [][]any{positionRow(1, 3, 1, "100")}}, nil
			}
			if strings.Contains(sql, "fund_holdings") {
				return &fakeRows{data: [][]any{holdingRow(1, 1, "AAPL", 10, "5.00")}}, nil
			}
			return &fakeRows{}, nil
		},
	}
}

func divReq(strategy string) DividendRequest {
	return DividendRequest{StockTicker: "AAPL", DividendPerShare: dec("1"), Currency: "", Strategy: strategy}
}

func TestRecordDividend_Duplicate(t *testing.T) {
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			if strings.Contains(sql, "fund_dividend_distributions") {
				return &fakeRow{vals: distRow(1, 1, "AAPL")} // already recorded
			}
			return &fakeRow{err: pgx.ErrNoRows}
		},
	}
	if _, err := divSvc(q).RecordDividend(ctx(), 1, divReq("")); err == nil {
		t.Error("expected 409 duplicate")
	}
}

func TestRecordDividend_FundNotFound(t *testing.T) {
	if _, err := divSvc(&fakeQuerier{}).RecordDividend(ctx(), 1, divReq("")); err == nil {
		t.Error("expected 404")
	}
}

func TestRecordDividend_HoldingNotFound(t *testing.T) {
	q := &fakeQuerier{
		rowFn: func(sql string, _ []any) *fakeRow {
			if strings.Contains(sql, "investment_funds") {
				return &fakeRow{vals: fundRow(1, "Alpha", "100.00")}
			}
			return &fakeRow{err: pgx.ErrNoRows}
		},
	}
	if _, err := divSvc(q).RecordDividend(ctx(), 1, divReq("")); err == nil {
		t.Error("expected holding 404")
	}
}

func TestRecordDividend_Reinvest_Success(t *testing.T) {
	dto, err := divSvc(divQuerier(false)).RecordDividend(ctx(), 1, divReq(DividendReinvest))
	if err != nil || dto == nil {
		t.Fatalf("got %+v %v", dto, err)
	}
}

func TestRecordDividend_PayoutClients_Success(t *testing.T) {
	dto, err := divSvc(divQuerier(true)).RecordDividend(ctx(), 1, divReq(DividendPayoutClients))
	if err != nil || dto == nil {
		t.Fatalf("got %+v %v", dto, err)
	}
}

func TestRecordDividend_UnknownStrategy(t *testing.T) {
	if _, err := divSvc(divQuerier(false)).RecordDividend(ctx(), 1, divReq("BOGUS")); err == nil {
		t.Error("expected unknown-strategy 404")
	}
}
