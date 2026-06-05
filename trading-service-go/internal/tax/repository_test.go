package tax

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

func ctx() context.Context { return context.Background() }

func TestNewRepository_NotNil(t *testing.T) {
	if NewRepository(nil) == nil {
		t.Fatal("NewRepository(nil) returned nil")
	}
}

func TestNewRepositoryForTest(t *testing.T) {
	q := &fakeQuerier{}
	r := NewRepositoryForTest(q)
	if r == nil || r.db != q {
		t.Fatal("NewRepositoryForTest did not wire the querier")
	}
}

// taxChargeRow builds a 14-column row matching scanTaxCharge's order.
func taxChargeRow(id int64, rsd *string, chargedAt *time.Time, otc *int64) []any {
	now := time.Date(2026, 5, 10, 0, 0, 0, 0, time.UTC)
	return []any{
		id, int64(1), int64(2), int64(7), int64(99),
		int64(70), now, now, "12.50", rsd, StatusReserved,
		now, chargedAt, otc,
	}
}

func TestScanTaxCharge_FullRow(t *testing.T) {
	rsd := "1000.00"
	charged := time.Date(2026, 5, 11, 0, 0, 0, 0, time.UTC)
	otc := int64(555)
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{vals: taxChargeRow(1, &rsd, &charged, &otc)}})
	got, err := r.FindByUserIDAndStatus(ctx(), 7, StatusReserved)
	_ = got
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestFindByUserIDAndStatus_Rows(t *testing.T) {
	rsd := "500.00"
	rows := &fakeQRows{rows: [][]any{
		taxChargeRow(1, nil, nil, nil),
		taxChargeRow(2, &rsd, nil, nil),
	}}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	out, err := r.FindByUserIDAndStatus(ctx(), 7, StatusReserved)
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(out) != 2 {
		t.Fatalf("want 2 rows, got %d", len(out))
	}
	if !out[0].TaxAmount.Equal(dec("12.50")) {
		t.Errorf("TaxAmount = %s, want 12.50", out[0].TaxAmount)
	}
	if out[0].TaxAmountRsd != nil {
		t.Error("row 0 RSD should be nil")
	}
	if out[1].TaxAmountRsd == nil || !out[1].TaxAmountRsd.Equal(dec("500.00")) {
		t.Errorf("row 1 RSD wrong: %v", out[1].TaxAmountRsd)
	}
}

func TestFindByUserIDAndStatus_QueryError(t *testing.T) {
	boom := errors.New("query boom")
	r := NewRepositoryForTest(&fakeQuerier{queryErr: boom})
	if _, err := r.FindByUserIDAndStatus(ctx(), 7, StatusReserved); !errors.Is(err, boom) {
		t.Errorf("got %v, want %v", err, boom)
	}
}

func TestFindByUserIDAndStatus_ScanError(t *testing.T) {
	rows := &fakeQRows{rows: [][]any{{}}, scanErr: errors.New("scan boom")}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	if _, err := r.FindByUserIDAndStatus(ctx(), 7, StatusReserved); err == nil {
		t.Error("expected scan error")
	}
}

func TestFindByUserIDAndStatus_BadDecimal(t *testing.T) {
	bad := taxChargeRow(1, nil, nil, nil)
	bad[8] = "not-a-decimal" // tax_amount
	rows := &fakeQRows{rows: [][]any{bad}}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	if _, err := r.FindByUserIDAndStatus(ctx(), 7, StatusReserved); err == nil {
		t.Error("expected bad decimal error")
	}
}

func TestFindByUserIDAndStatus_BadRsdDecimal(t *testing.T) {
	bad := "garbage"
	row := taxChargeRow(1, &bad, nil, nil)
	rows := &fakeQRows{rows: [][]any{row}}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	if _, err := r.FindByUserIDAndStatus(ctx(), 7, StatusReserved); err == nil {
		t.Error("expected bad RSD decimal error")
	}
}

func TestFindAll_Empty(t *testing.T) {
	r := NewRepositoryForTest(&fakeQuerier{rows: &fakeQRows{}})
	out, err := r.FindAll(ctx())
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(out) != 0 {
		t.Errorf("want empty, got %d", len(out))
	}
}

func TestFindAll_QueryError(t *testing.T) {
	boom := errors.New("boom")
	r := NewRepositoryForTest(&fakeQuerier{queryErr: boom})
	if _, err := r.FindAll(ctx()); !errors.Is(err, boom) {
		t.Errorf("got %v", err)
	}
}

func TestExistsBySellAndBuy_True(t *testing.T) {
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{vals: []any{true}}})
	got, err := r.ExistsBySellAndBuy(ctx(), 1, 2)
	if err != nil || !got {
		t.Errorf("got (%v,%v), want (true,nil)", got, err)
	}
}

func TestExistsBySellAndBuy_ScanError(t *testing.T) {
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{scanErr: errors.New("boom")}})
	if _, err := r.ExistsBySellAndBuy(ctx(), 1, 2); err == nil {
		t.Error("expected error")
	}
}

func TestExistsByOtcContractID_False(t *testing.T) {
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{vals: []any{false}}})
	got, err := r.ExistsByOtcContractID(ctx(), 5)
	if err != nil || got {
		t.Errorf("got (%v,%v), want (false,nil)", got, err)
	}
}

func TestInsert_Success(t *testing.T) {
	now := time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC)
	// RETURNING id, created_at
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{vals: []any{int64(42), now}}})
	c := &TaxCharge{SellTransactionID: 1, BuyTransactionID: 2, UserID: 7, TaxAmount: dec("10"), Status: StatusReserved}
	if err := r.Insert(ctx(), c); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if c.ID != 42 {
		t.Errorf("ID = %d, want 42", c.ID)
	}
	if !c.CreatedAt.Equal(now) {
		t.Errorf("CreatedAt not set")
	}
}

func TestInsert_WithRsd(t *testing.T) {
	rsd := dec("999.99")
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{vals: []any{int64(1), time.Now()}}})
	c := &TaxCharge{TaxAmount: dec("10"), TaxAmountRsd: &rsd, Status: StatusReserved}
	if err := r.Insert(ctx(), c); err != nil {
		t.Fatalf("unexpected: %v", err)
	}
}

func TestInsert_DuplicateMapped(t *testing.T) {
	pgErr := &pgconn.PgError{Code: "23505"}
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{scanErr: pgErr}})
	c := &TaxCharge{TaxAmount: dec("10"), Status: StatusReserved}
	if err := r.Insert(ctx(), c); !errors.Is(err, ErrDuplicate) {
		t.Errorf("got %v, want ErrDuplicate", err)
	}
}

func TestInsert_OtherError(t *testing.T) {
	boom := errors.New("db down")
	r := NewRepositoryForTest(&fakeQuerier{row: &fakeQRow{scanErr: boom}})
	c := &TaxCharge{TaxAmount: dec("10"), Status: StatusReserved}
	if err := r.Insert(ctx(), c); !errors.Is(err, boom) {
		t.Errorf("got %v, want %v", err, boom)
	}
}

func TestUpdateCharged(t *testing.T) {
	r := NewRepositoryForTest(&fakeQuerier{execTag: pgconn.NewCommandTag("UPDATE 1")})
	if err := r.UpdateCharged(ctx(), 1, dec("100"), time.Now()); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestUpdateCharged_Error(t *testing.T) {
	boom := errors.New("boom")
	r := NewRepositoryForTest(&fakeQuerier{execErr: boom})
	if err := r.UpdateCharged(ctx(), 1, dec("100"), time.Now()); !errors.Is(err, boom) {
		t.Errorf("got %v", err)
	}
}

func TestMarkCharged(t *testing.T) {
	r := NewRepositoryForTest(&fakeQuerier{})
	if err := r.MarkCharged(ctx(), 1, time.Now()); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestMarkCharged_Error(t *testing.T) {
	boom := errors.New("boom")
	r := NewRepositoryForTest(&fakeQuerier{execErr: boom})
	if err := r.MarkCharged(ctx(), 1, time.Now()); !errors.Is(err, boom) {
		t.Errorf("got %v", err)
	}
}

func TestDelete(t *testing.T) {
	r := NewRepositoryForTest(&fakeQuerier{})
	if err := r.Delete(ctx(), 1); err != nil {
		t.Errorf("unexpected: %v", err)
	}
}

func TestDelete_Error(t *testing.T) {
	boom := errors.New("boom")
	r := NewRepositoryForTest(&fakeQuerier{execErr: boom})
	if err := r.Delete(ctx(), 1); !errors.Is(err, boom) {
		t.Errorf("got %v", err)
	}
}

// otcRow builds an 8-column row matching LoadExercisedOtcTaxEntries scan order.
func otcRow(contractID int64, ppu, avg string) []any {
	ex := time.Date(2026, 5, 5, 0, 0, 0, 0, time.UTC)
	return []any{contractID, int64(7), int64(99), "AAPL", 10, ppu, avg, ex}
}

func TestLoadExercisedOtcTaxEntries_Rows(t *testing.T) {
	rows := &fakeQRows{rows: [][]any{
		otcRow(1, "150.00", "100.00"),
		otcRow(2, "200.00", "120.00"),
	}}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	out, err := r.LoadExercisedOtcTaxEntries(ctx(), time.Now())
	if err != nil {
		t.Fatalf("unexpected: %v", err)
	}
	if len(out) != 2 {
		t.Fatalf("want 2, got %d", len(out))
	}
	if out[0].ContractID != 1 || out[0].Amount != 10 || out[0].Ticker != "AAPL" {
		t.Errorf("row 0 wrong: %+v", out[0])
	}
	if !out[0].SellPricePerStock.Equal(dec("150.00")) || !out[0].AveragePurchasePrice.Equal(dec("100.00")) {
		t.Errorf("row 0 prices wrong: %+v", out[0])
	}
}

func TestLoadExercisedOtcTaxEntries_QueryError(t *testing.T) {
	boom := errors.New("boom")
	r := NewRepositoryForTest(&fakeQuerier{queryErr: boom})
	if _, err := r.LoadExercisedOtcTaxEntries(ctx(), time.Now()); !errors.Is(err, boom) {
		t.Errorf("got %v", err)
	}
}

func TestLoadExercisedOtcTaxEntries_ScanError(t *testing.T) {
	rows := &fakeQRows{rows: [][]any{{}}, scanErr: errors.New("boom")}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	if _, err := r.LoadExercisedOtcTaxEntries(ctx(), time.Now()); err == nil {
		t.Error("expected scan error")
	}
}

func TestLoadExercisedOtcTaxEntries_BadPpu(t *testing.T) {
	rows := &fakeQRows{rows: [][]any{otcRow(1, "bad", "100.00")}}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	if _, err := r.LoadExercisedOtcTaxEntries(ctx(), time.Now()); err == nil {
		t.Error("expected bad ppu decimal error")
	}
}

func TestLoadExercisedOtcTaxEntries_BadAvg(t *testing.T) {
	rows := &fakeQRows{rows: [][]any{otcRow(1, "150.00", "bad")}}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	if _, err := r.LoadExercisedOtcTaxEntries(ctx(), time.Now()); err == nil {
		t.Error("expected bad avg decimal error")
	}
}

func TestScanTaxCharges_RowsErr(t *testing.T) {
	rows := &fakeQRows{rows: [][]any{}, nextErr: errors.New("rows err")}
	r := NewRepositoryForTest(&fakeQuerier{rows: rows})
	if _, err := r.FindAll(ctx()); err == nil {
		t.Error("expected rows.Err propagation")
	}
}

// Sanity: a no-rows QueryRow path returns pgx.ErrNoRows for scanners that wrap it.
func TestFakeQuerier_DefaultNoRows(t *testing.T) {
	q := &fakeQuerier{}
	var b bool
	if err := q.QueryRow(ctx(), "x").Scan(&b); !errors.Is(err, pgx.ErrNoRows) {
		t.Errorf("default QueryRow should yield ErrNoRows, got %v", err)
	}
}
