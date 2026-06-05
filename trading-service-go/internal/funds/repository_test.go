package funds

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func ctx() context.Context { return context.Background() }

// fundRow returns a 12-column investment_funds row matching scanFund.
func fundRow(id int64, naziv string, liq string) []any {
	now := time.Now()
	return []any{id, naziv, (*string)(nil), "100.00", int64(7), liq, "1600000000000000", DividendReinvest, now, false, now, int64(0)}
}

// positionRow returns a 7-column client_fund_positions row matching scanPosition.
func positionRow(id, clientID, fundID int64, invested string) []any {
	now := time.Now()
	return []any{id, clientID, fundID, invested, now, &now, int64(0)}
}

// txRow returns a 9-column client_fund_transactions row matching scanTransaction.
func txRow(id, clientID, fundID int64, amount string, inflow bool, status string) []any {
	now := time.Now()
	return []any{id, clientID, fundID, amount, inflow, status, now, "111", (*string)(nil)}
}

// holdingRow returns a 9-column fund_holdings row matching scanHolding.
func holdingRow(id, fundID int64, ticker string, qty int, avg string) []any {
	now := time.Now()
	return []any{id, fundID, ticker, qty, avg, false, now, &now, int64(0)}
}

// snapshotRow returns a 7-column fund_value_snapshots row matching scanSnapshot.
func snapshotRow(id, fundID int64, date time.Time, liq, hold, total string) []any {
	now := time.Now()
	return []any{id, fundID, date, liq, hold, total, now}
}

// distRow returns a 16-column fund_dividend_distributions row matching scanDistribution.
func distRow(id, fundID int64, ticker string) []any {
	now := time.Now()
	return []any{id, fundID, ticker, now, "1.50", "USD", 100, "150.00", "15000.00",
		DividendReinvest, DistStatusCompleted, (*int)(nil), (*string)(nil), (*string)(nil), (*string)(nil), now}
}

// ---- investment_funds ----

func TestNewRepository_PoolAndQuerier(t *testing.T) {
	r := NewRepository(nil)
	if r.Pool() != nil {
		t.Error("Pool should be nil")
	}
	// querier falls back to db when q is nil.
	r2 := &Repository{}
	if r2.querier() != nil {
		t.Error("expected nil querier on zero-value repo")
	}
}

func TestFindFundByID(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: fundRow(1, "Alpha", "500.00")}}
	r := NewRepositoryForTest(q)
	f, err := r.FindFundByID(ctx(), nil, 1)
	if err != nil {
		t.Fatal(err)
	}
	if f.ID != 1 || f.Naziv != "Alpha" || !f.LikvidnaSredstva.Equal(dec("500.00")) {
		t.Errorf("fund wrong: %+v", f)
	}
}

func TestFindFundByID_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindFundByID(ctx(), q, 1); !errors.Is(err, ErrNotFound) {
		t.Errorf("want ErrNotFound, got %v", err)
	}
}

func TestFindFundByID_BadDecimal(t *testing.T) {
	row := fundRow(1, "Alpha", "not-a-number")
	q := &fakeQuerier{row: &fakeRow{vals: row}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindFundByID(ctx(), q, 1); err == nil {
		t.Error("expected decimal parse error")
	}
}

func TestFindFundByIDForUpdate_NilGuardAndNotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindFundByIDForUpdate(ctx(), nil, 1); !errors.Is(err, ErrNotFound) {
		t.Errorf("want ErrNotFound, got %v", err)
	}
}

func TestFindFundByIDForUpdate_Success(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: fundRow(3, "Gamma", "9.99")}}
	r := NewRepositoryForTest(q)
	f, err := r.FindFundByIDForUpdate(ctx(), q, 3)
	if err != nil || f.ID != 3 {
		t.Fatalf("got %v %v", f, err)
	}
}

func TestFundExists(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{true}}}
	r := NewRepositoryForTest(q)
	ok, err := r.FundExists(ctx(), nil, 1)
	if err != nil || !ok {
		t.Errorf("got %v %v", ok, err)
	}
}

func TestFindFundsActive(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{fundRow(1, "A", "1.00"), fundRow(2, "B", "2.00")}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindFundsActive(ctx())
	if err != nil || len(out) != 2 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindFundsActive_QueryError(t *testing.T) {
	boom := errors.New("boom")
	q := &fakeQuerier{queryErr: boom}
	r := NewRepositoryForTest(q)
	if _, err := r.FindFundsActive(ctx()); !errors.Is(err, boom) {
		t.Errorf("got %v", err)
	}
}

func TestFindFundsActive_ScanError(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{fundRow(1, "A", "bad")}}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindFundsActive(ctx()); err == nil {
		t.Error("expected scan error")
	}
}

func TestFindFundsByManager(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{fundRow(1, "A", "1.00")}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindFundsByManager(ctx(), 7)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindFundsByManager_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("x")}
	r := NewRepositoryForTest(q)
	if _, err := r.FindFundsByManager(ctx(), 7); err == nil {
		t.Error("expected error")
	}
}

func TestInsertFund(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{int64(99), int64(0)}}}
	r := NewRepositoryForTest(q)
	f := &InvestmentFund{Naziv: "New"}
	if err := r.InsertFund(ctx(), nil, f); err != nil {
		t.Fatal(err)
	}
	if f.ID != 99 {
		t.Errorf("ID = %d", f.ID)
	}
	if f.DatumKreiranja.IsZero() || f.CreatedAt.IsZero() {
		t.Error("timestamps should default")
	}
}

func TestUpdateFundLiquidity(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 1")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateFundLiquidity(ctx(), nil, 1, dec("5.00")); err != nil {
		t.Fatal(err)
	}
}

func TestUpdateFundLiquidity_NotFound(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 0")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateFundLiquidity(ctx(), q, 1, dec("5.00")); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestUpdateFundLiquidity_ExecError(t *testing.T) {
	q := &fakeQuerier{execErr: errors.New("boom")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateFundLiquidity(ctx(), q, 1, dec("5.00")); err == nil {
		t.Error("expected error")
	}
}

func TestUpdateFundManager(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 1")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateFundManager(ctx(), nil, 1, 2); err != nil {
		t.Fatal(err)
	}
}

func TestUpdateFundManager_NotFound(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 0")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateFundManager(ctx(), q, 1, 2); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestUpdateFundManager_ExecError(t *testing.T) {
	q := &fakeQuerier{execErr: errors.New("boom")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateFundManager(ctx(), q, 1, 2); err == nil {
		t.Error("expected error")
	}
}

// ---- client_fund_positions ----

func TestFindPositionsByClient(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{positionRow(1, 5, 2, "100.00")}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindPositionsByClient(ctx(), 5)
	if err != nil || len(out) != 1 || !out[0].TotalInvested.Equal(dec("100.00")) {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindPositionsByClient_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("x")}
	r := NewRepositoryForTest(q)
	if _, err := r.FindPositionsByClient(ctx(), 5); err == nil {
		t.Error("expected error")
	}
}

func TestFindPositionsByClient_ScanError(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{positionRow(1, 5, 2, "bad")}}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindPositionsByClient(ctx(), 5); err == nil {
		t.Error("expected scan error")
	}
}

func TestFindPositionsByFund(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{positionRow(1, 5, 2, "100.00")}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindPositionsByFund(ctx(), nil, 2)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindPositionsByFund_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("x")}
	r := NewRepositoryForTest(q)
	if _, err := r.FindPositionsByFund(ctx(), q, 2); err == nil {
		t.Error("expected error")
	}
}

func TestFindPosition(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: positionRow(1, 5, 2, "100.00")}}
	r := NewRepositoryForTest(q)
	p, err := r.FindPosition(ctx(), nil, 5, 2)
	if err != nil || p.ClientID != 5 {
		t.Fatalf("got %v %v", p, err)
	}
}

func TestFindPosition_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindPosition(ctx(), q, 5, 2); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestFindPositionForUpdate_NilGuard(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: positionRow(1, 5, 2, "100.00")}}
	r := NewRepositoryForTest(q)
	p, err := r.FindPositionForUpdate(ctx(), nil, 5, 2)
	if err != nil || p.FundID != 2 {
		t.Fatalf("got %v %v", p, err)
	}
}

func TestFindPositionForUpdate_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindPositionForUpdate(ctx(), q, 5, 2); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestUpsertPosition_Insert(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{int64(11), int64(0)}}}
	r := NewRepositoryForTest(q)
	p := &ClientFundPosition{ClientID: 5, FundID: 2, TotalInvested: dec("10.00")}
	if err := r.UpsertPosition(ctx(), nil, p); err != nil {
		t.Fatal(err)
	}
	if p.ID != 11 || p.FirstInvestedAt.IsZero() {
		t.Errorf("insert wrong: %+v", p)
	}
}

func TestUpsertPosition_Update(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 1")}
	r := NewRepositoryForTest(q)
	p := &ClientFundPosition{ID: 11, ClientID: 5, FundID: 2, TotalInvested: dec("20.00")}
	if err := r.UpsertPosition(ctx(), q, p); err != nil {
		t.Fatal(err)
	}
}

func TestUpsertPosition_UpdateNotFound(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 0")}
	r := NewRepositoryForTest(q)
	p := &ClientFundPosition{ID: 11}
	if err := r.UpsertPosition(ctx(), q, p); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestUpsertPosition_UpdateExecError(t *testing.T) {
	q := &fakeQuerier{execErr: errors.New("boom")}
	r := NewRepositoryForTest(q)
	p := &ClientFundPosition{ID: 11}
	if err := r.UpsertPosition(ctx(), q, p); err == nil {
		t.Error("expected error")
	}
}

// ---- client_fund_transactions ----

func TestFindTransactionByID(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: txRow(1, 5, 2, "100.00", true, TxStatusPending)}}
	r := NewRepositoryForTest(q)
	tx, err := r.FindTransactionByID(ctx(), nil, 1)
	if err != nil || tx.Status != TxStatusPending {
		t.Fatalf("got %v %v", tx, err)
	}
}

func TestFindTransactionByID_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindTransactionByID(ctx(), q, 1); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestFindTransactionsByClient(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{txRow(1, 5, 2, "100.00", true, TxStatusCompleted)}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindTransactionsByClient(ctx(), 5)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindTransactionsByClient_Error(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("x")}
	r := NewRepositoryForTest(q)
	if _, err := r.FindTransactionsByClient(ctx(), 5); err == nil {
		t.Error("expected error")
	}
}

func TestFindTransactionsByFund(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{txRow(1, 5, 2, "100.00", false, TxStatusFailed)}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindTransactionsByFund(ctx(), 2)
	if err != nil || len(out) != 1 {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindTransactionsByFund_ScanError(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{txRow(1, 5, 2, "bad", false, TxStatusFailed)}}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindTransactionsByFund(ctx(), 2); err == nil {
		t.Error("expected scan error")
	}
}

func TestInsertTransaction(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{int64(7)}}}
	r := NewRepositoryForTest(q)
	tx := &ClientFundTransaction{ClientID: 5, FundID: 2, Amount: dec("100.00")}
	if err := r.InsertTransaction(ctx(), nil, tx); err != nil {
		t.Fatal(err)
	}
	if tx.ID != 7 || tx.OccurredAt.IsZero() {
		t.Errorf("insert wrong: %+v", tx)
	}
}

func TestUpdateTransactionStatus(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 1")}
	r := NewRepositoryForTest(q)
	reason := "x"
	if err := r.UpdateTransactionStatus(ctx(), nil, 1, TxStatusFailed, &reason); err != nil {
		t.Fatal(err)
	}
}

func TestUpdateTransactionStatus_NotFound(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 0")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateTransactionStatus(ctx(), q, 1, TxStatusCompleted, nil); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestUpdateTransactionStatus_ExecError(t *testing.T) {
	q := &fakeQuerier{execErr: errors.New("boom")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateTransactionStatus(ctx(), q, 1, TxStatusCompleted, nil); err == nil {
		t.Error("expected error")
	}
}

// ---- fund_holdings ----

func TestFindHoldingsActive(t *testing.T) {
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{holdingRow(1, 2, "AAPL", 10, "100.0000")}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindHoldingsActive(ctx(), nil, 2)
	if err != nil || len(out) != 1 || out[0].StockTicker != "AAPL" {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindHoldingsActive_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("x")}
	r := NewRepositoryForTest(q)
	if _, err := r.FindHoldingsActive(ctx(), q, 2); err == nil {
		t.Error("expected error")
	}
}

func TestFindHolding(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: holdingRow(1, 2, "AAPL", 10, "100.0000")}}
	r := NewRepositoryForTest(q)
	h, err := r.FindHolding(ctx(), nil, 2, "AAPL")
	if err != nil || h.Quantity != 10 {
		t.Fatalf("got %v %v", h, err)
	}
}

func TestFindHolding_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindHolding(ctx(), q, 2, "AAPL"); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestInsertHolding(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{int64(3), int64(0)}}}
	r := NewRepositoryForTest(q)
	h := &FundHolding{FundID: 2, StockTicker: "AAPL", Quantity: 5, AvgUnitPrice: dec("10.0000")}
	if err := r.InsertHolding(ctx(), nil, h); err != nil {
		t.Fatal(err)
	}
	if h.ID != 3 || h.CreatedAt.IsZero() {
		t.Errorf("insert wrong: %+v", h)
	}
}

func TestUpdateHolding(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 1")}
	r := NewRepositoryForTest(q)
	h := &FundHolding{ID: 3, Version: 1}
	if err := r.UpdateHolding(ctx(), nil, h); err != nil {
		t.Fatal(err)
	}
	if h.Version != 2 {
		t.Errorf("version not bumped: %d", h.Version)
	}
}

func TestUpdateHolding_NotFound(t *testing.T) {
	q := &fakeQuerier{execTag: tag("UPDATE 0")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateHolding(ctx(), q, &FundHolding{ID: 3}); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestUpdateHolding_ExecError(t *testing.T) {
	q := &fakeQuerier{execErr: errors.New("boom")}
	r := NewRepositoryForTest(q)
	if err := r.UpdateHolding(ctx(), q, &FundHolding{ID: 3}); err == nil {
		t.Error("expected error")
	}
}

// ---- fund_value_snapshots ----

func TestFindSnapshots(t *testing.T) {
	d := time.Now().Truncate(24 * time.Hour)
	q := &fakeQuerier{rows: &fakeRows{data: [][]any{snapshotRow(1, 2, d, "10.00", "5.00", "15.00")}}}
	r := NewRepositoryForTest(q)
	out, err := r.FindSnapshots(ctx(), 2)
	if err != nil || len(out) != 1 || !out[0].TotalValue.Equal(dec("15.00")) {
		t.Fatalf("got %d %v", len(out), err)
	}
}

func TestFindSnapshots_QueryError(t *testing.T) {
	q := &fakeQuerier{queryErr: errors.New("x")}
	r := NewRepositoryForTest(q)
	if _, err := r.FindSnapshots(ctx(), 2); err == nil {
		t.Error("expected error")
	}
}

func TestFindSnapshotByDate(t *testing.T) {
	d := time.Now().Truncate(24 * time.Hour)
	q := &fakeQuerier{row: &fakeRow{vals: snapshotRow(1, 2, d, "10.00", "5.00", "15.00")}}
	r := NewRepositoryForTest(q)
	s, err := r.FindSnapshotByDate(ctx(), 2, d)
	if err != nil || s.ID != 1 {
		t.Fatalf("got %v %v", s, err)
	}
}

func TestFindSnapshotByDate_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindSnapshotByDate(ctx(), 2, time.Now()); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestUpsertSnapshot(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{int64(9)}}}
	r := NewRepositoryForTest(q)
	s := &FundValueSnapshot{FundID: 2, SnapshotDate: time.Now(), LiquidityValue: dec("1.00"), HoldingsValue: dec("2.00"), TotalValue: dec("3.00")}
	if err := r.UpsertSnapshot(ctx(), nil, s); err != nil {
		t.Fatal(err)
	}
	if s.ID != 9 || s.CreatedAt.IsZero() {
		t.Errorf("upsert wrong: %+v", s)
	}
}

// ---- fund_dividend_distributions / payouts ----

func TestFindDistribution(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: distRow(1, 2, "AAPL")}}
	r := NewRepositoryForTest(q)
	d, err := r.FindDistribution(ctx(), 2, "AAPL", time.Now())
	if err != nil || d.ID != 1 || !d.GrossAmountRsd.Equal(dec("15000.00")) {
		t.Fatalf("got %v %v", d, err)
	}
}

func TestFindDistribution_NotFound(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{err: pgx.ErrNoRows}}
	r := NewRepositoryForTest(q)
	if _, err := r.FindDistribution(ctx(), 2, "AAPL", time.Now()); !errors.Is(err, ErrNotFound) {
		t.Errorf("got %v", err)
	}
}

func TestFindDistribution_WithOptionalDecimals(t *testing.T) {
	row := distRow(1, 2, "AAPL")
	reinv := "100.00"
	distr := "50.00"
	row[12] = &reinv // reinvested_amount_rsd::text
	row[13] = &distr // distributed_amount_rsd::text
	q := &fakeQuerier{row: &fakeRow{vals: row}}
	r := NewRepositoryForTest(q)
	d, err := r.FindDistribution(ctx(), 2, "AAPL", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if d.ReinvestedAmountRsd == nil || !d.ReinvestedAmountRsd.Equal(dec("100.00")) {
		t.Errorf("reinvested wrong: %v", d.ReinvestedAmountRsd)
	}
	if d.DistributedAmountRsd == nil || !d.DistributedAmountRsd.Equal(dec("50.00")) {
		t.Errorf("distributed wrong: %v", d.DistributedAmountRsd)
	}
}

func TestInsertDistribution(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{int64(5)}}}
	r := NewRepositoryForTest(q)
	d := &FundDividendDistribution{FundID: 2, StockTicker: "AAPL", DividendPerShare: dec("1.00"), GrossAmountSource: dec("1.00"), GrossAmountRsd: dec("1.00")}
	if err := r.InsertDistribution(ctx(), nil, d); err != nil {
		t.Fatal(err)
	}
	if d.ID != 5 || d.ProcessedAt.IsZero() {
		t.Errorf("insert wrong: %+v", d)
	}
}

func TestInsertPayout(t *testing.T) {
	q := &fakeQuerier{row: &fakeRow{vals: []any{int64(8)}}}
	r := NewRepositoryForTest(q)
	p := &FundDividendPayout{DistributionID: 5, ClientID: 3, OwnershipRatio: dec("0.5"), AmountRsd: dec("10.00")}
	if err := r.InsertPayout(ctx(), nil, p); err != nil {
		t.Fatal(err)
	}
	if p.ID != 8 || p.CreatedAt.IsZero() {
		t.Errorf("insert wrong: %+v", p)
	}
}
